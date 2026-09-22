package io.hermes.missioncontrol.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerCmd;
import com.github.dockerjava.api.command.InspectContainerResponse.ContainerState;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.ContainerConfig;
import com.github.dockerjava.api.command.ListContainersCmd;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ContainerPort;
import io.hermes.missioncontrol.config.AppProperties;
import io.hermes.missioncontrol.docker.DockerExecService.ExecResult;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.stubbing.OngoingStubbing;
import org.slf4j.LoggerFactory;

class ContainerInventoryTest {

  private static final DockerHostRef HOST = new DockerHostRef("local", "unix:///sock");
  /** The Engine substitutes this for a reference it can no longer resolve. */
  private static final String BARE_IMAGE_ID =
      "sha256:e5b3a1c7d90f4b2e6a8c0d1f3b5a7c9e1d3f5b7a9c1e3d5f7b9a1c3e5d7f9b1c";

  private final DockerClients clients = mock(DockerClients.class);
  private final DockerClient client = mock(DockerClient.class);
  private final DockerClient streamingClient = mock(DockerClient.class);
  private final DockerExecService dockerExec = mock(DockerExecService.class);
  private final ContainerInventory subject = DockerWiring.inventory(clients, new AppProperties("", "unix:///sock", "hermes/image", "hermes", "test", true), dockerExec);

  private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

  @BeforeEach
  void setUp() {
    when(clients.forUrl("unix:///sock")).thenReturn(client);
    when(clients.streamingForUrl("unix:///sock")).thenReturn(streamingClient);
    appender.start();
    ((Logger) LoggerFactory.getLogger(ContainerInventory.class)).addAppender(appender);
  }

  @AfterEach
  void tearDown() {
    ((Logger) LoggerFactory.getLogger(ContainerInventory.class)).detachAppender(appender);
    appender.stop();
  }

  /** The WARN lines emitted so far, so a test can count reports rather than assert on a set. */
  private List<String> warningsSoFar() {
    return appender.list.stream()
        .filter(event -> event.getLevel() == Level.WARN)
        .map(ILoggingEvent::getFormattedMessage)
        .toList();
  }

  @Test
  void parkedUpgradeLeftoversAreExcludedFromTheDefaultListingButVisibleWithAll() {
    stubListing(
        container("aaaaaaa1111", "/demo", "hermes/image:v1"),
        container("bbbbbbb2222", "/demo-mc-upgrade-0a1b2c3d", "hermes/image:v1"));

    // the parked original runs the same image as the live one, so only the name
    // tells them apart — showing both would offer the operator two identical cards
    assertEquals(List.of("demo"), names(subject.listContainers(HOST, false)));
    assertEquals(List.of("demo", "demo-mc-upgrade-0a1b2c3d"),
        names(subject.listContainers(HOST, true)));
  }

  @Test
  void onlyContainersOnTheConfiguredHermesRepositoryAreListed() {
    stubListing(
        container("aaaaaaa1111", "/demo", "hermes/image:v1"),
        container("bbbbbbb2222", "/postgres", "other/thing:v1"));

    List<ContainerDto> fleet = subject.listContainers(HOST, false);

    assertEquals(List.of("demo"), names(fleet));
    assertEquals("hermes/image", fleet.get(0).image());
    assertEquals("v1", fleet.get(0).version());
  }

  @Test
  void aDigestPinnedHermesContainerStillAppearsInTheFleet() {
    stubListing(container("aaaaaaa1111", "/demo",
        "hermes/image@sha256:9b2c1e4f6a8d0c3e5f7a9b1d3f5a7c9e1b3d5f7a9c1e3b5d7f9a1c3e5b7d9f01"));

    List<ContainerDto> fleet = subject.listContainers(HOST, false);

    // the digest carries its own ':', so a repository comparison that does not strip
    // it first hides every container this dashboard pinned by digest
    assertEquals(List.of("demo"), names(fleet));
    assertEquals("hermes/image", fleet.get(0).image());
    assertEquals("latest", fleet.get(0).version());
  }

  @Test
  void aDockerHubQualifiedReferenceMatchesTheShortConfiguredForm() {
    stubListing(container("aaaaaaa1111", "/demo", "docker.io/hermes/image:v1"));

    List<ContainerDto> fleet = subject.listContainers(HOST, false);

    assertEquals(List.of("demo"), names(fleet));
    // the card still shows the reference the daemon reported, registry host included
    assertEquals("docker.io/hermes/image", fleet.get(0).image());
    assertEquals("v1", fleet.get(0).version());
  }

  @Test
  void theSubstringFilterMatchesImageOrNameWhenNoHermesImageIsConfigured() {
    ContainerInventory substringInventory = DockerWiring.inventory(clients, new AppProperties("", "unix:///sock", "", "hermes", "test", true), dockerExec);
    stubListing(
        container("aaaaaaa1111", "/alpha", "Acme/HERMES-agent:v1"),
        container("bbbbbbb2222", "/hermes-beta", "acme/other:v1"),
        container("ccccccc3333", "/gamma", "acme/other:v1"));

    List<ContainerDto> fleet = substringInventory.listContainers(HOST, false);

    // an operator who typed a lowercase filter still expects to see a container whose
    // image was published with capitals
    assertEquals(List.of("alpha", "hermes-beta"), names(fleet));
  }

  @Test
  void runningStoppedAndUnhealthyStatesAreMappedForTheStatusPill() {
    stubListing(
        containerInState("aaaaaaa1111", "/healthy", "running", "Up 2 hours"),
        containerInState("bbbbbbb2222", "/sick", "running", "Up 2 hours (unhealthy)"),
        containerInState("ccccccc3333", "/flapping", "restarting", "Restarting (1) 3 seconds ago"),
        containerInState("ddddddd4444", "/gone", "exited", "Exited (0) 5 minutes ago"),
        containerInState("eeeeeee5555", "/fresh", "created", "Created"),
        containerInState("fff11115555", "/held", "paused", "Up 2 hours (Paused)"),
        containerInState("aaabbbb6666", "/lost", "dead", "Dead"),
        containerInState("cccdddd7777", "/vanishing", "removing", "Removal In Progress"));
    stubStartedAt("aaaaaaa1111", "2026-08-14T10:00:00Z");
    // raced with a removal: the container is gone by the time uptime is read
    stubMissingOnInspect("bbbbbbb2222");

    Map<String, ContainerDto> fleet = byName(subject.listContainers(HOST, false));

    assertEquals("running", fleet.get("healthy").status());
    assertEquals("unhealthy", fleet.get("sick").status());
    assertEquals("unhealthy", fleet.get("flapping").status());
    assertEquals("stopped", fleet.get("gone").status());
    assertEquals("stopped", fleet.get("fresh").status());
    assertEquals("stopped", fleet.get("held").status());
    assertEquals("stopped", fleet.get("lost").status());
    assertEquals("unknown", fleet.get("vanishing").status());

    assertEquals(1786701600000L, fleet.get("healthy").startedAt());
    assertNull(fleet.get("gone").startedAt());
    // a failed inspect costs one card its uptime, never the whole fleet view
    assertNull(fleet.get("sick").startedAt());
  }

  @Test
  void theProfilesLabelIsSplitAndAnEmptyLabelYieldsNoProfiles() {
    Container seeded = container("aaaaaaa1111", "/seeded", "hermes/image:v1");
    when(seeded.getLabels()).thenReturn(Map.of("mc.profiles", "ops,research"));
    Container blank = container("bbbbbbb2222", "/blank", "hermes/image:v1");
    when(blank.getLabels()).thenReturn(Map.of("mc.profiles", "   "));
    Container otherLabels = container("ccccccc3333", "/plain", "hermes/image:v1");
    when(otherLabels.getLabels()).thenReturn(Map.of("mc.managed", "true"));
    // deployed outside this dashboard, so the daemon reports no labels at all
    Container unlabelled = container("ddddddd4444", "/foreign", "hermes/image:v1");
    when(unlabelled.getLabels()).thenReturn(null);
    stubListing(seeded, blank, otherLabels, unlabelled);

    Map<String, ContainerDto> fleet = byName(subject.listContainers(HOST, false));

    assertEquals(List.of("ops", "research"), fleet.get("seeded").profiles());
    assertEquals(List.of(), fleet.get("blank").profiles());
    assertEquals(List.of(), fleet.get("plain").profiles());
    assertEquals(List.of(), fleet.get("foreign").profiles());
  }

  @Test
  void publishedPortsAreReportedOncePerContainerPortWithTheAddressABrowserCanUse() {
    Container published = container("aaaaaaa1111", "/published", "hermes/image:v1");
    when(published.getPorts()).thenReturn(new ContainerPort[] {
        // an all-interfaces binding is listed twice by the daemon; the IPv4 row is the one to keep
        new ContainerPort().withIp("::").withPrivatePort(9119).withPublicPort(9119).withType("tcp"),
        new ContainerPort().withIp("0.0.0.0").withPrivatePort(9119).withPublicPort(9119).withType("tcp"),
        // a second IPv6 row after the IPv4 one changes nothing
        new ContainerPort().withIp("::").withPrivatePort(9119).withPublicPort(9119).withType("tcp"),
        new ContainerPort().withIp("127.0.0.1").withPrivatePort(8644).withPublicPort(18644).withType("tcp"),
        // exposed by the image but never published, and a row the daemon left half-filled
        new ContainerPort().withPrivatePort(8642).withType("tcp"),
        new ContainerPort().withPublicPort(7000).withType("tcp"),
        // no address at all is reported as a blank one rather than failing the listing
        new ContainerPort().withPrivatePort(8080).withPublicPort(8080).withType("tcp")});
    Container plain = container("bbbbbbb2222", "/plain", "hermes/image:v1");
    when(plain.getPorts()).thenReturn(null);
    stubListing(published, plain);

    Map<String, ContainerDto> fleet = byName(subject.listContainers(HOST, false));

    assertEquals(List.of(
        new PublishedPortDto(8080, "", 8080), new PublishedPortDto(8644, "127.0.0.1", 18644),
        new PublishedPortDto(9119, "0.0.0.0", 9119)),
        fleet.get("published").ports());
    assertEquals(List.of(), fleet.get("plain").ports());
  }

  @Test
  void aContainerWithNoNamesIsStillListed() {
    Container nameless = container("aaaaaaa1111", "/demo", "hermes/image:v1");
    when(nameless.getNames()).thenReturn(null);
    stubListing(nameless);

    List<ContainerDto> fleet = subject.listContainers(HOST, false);

    // a container mid-rename reports no name; dropping the whole listing over it
    // would blank the dashboard
    assertEquals(List.of("?"), names(fleet));
    assertEquals("aaaaaaa1111", fleet.get(0).id());
  }

  @Test
  void theShortIdIsTheFirstSevenCharactersOfTheContainerId() {
    stubListing(container("9f3c1a4e8b2d7c05", "/demo", "hermes/image:v1"));

    List<ContainerDto> fleet = subject.listContainers(HOST, false);

    assertEquals("9f3c1a4", fleet.get(0).shortId());
    assertEquals("9f3c1a4e8b2d7c05", fleet.get(0).id());
  }

  @Test
  void rootFilesystemSizeIsReportedInGibibytes() {
    Container sized = container("aaaaaaa1111", "/sized", "hermes/image:v1");
    when(sized.getSizeRootFs()).thenReturn(1_073_741_824L);
    // the daemon only reports size when asked, and answers null when it cannot
    Container unsized = container("bbbbbbb2222", "/unsized", "hermes/image:v1");
    when(unsized.getSizeRootFs()).thenReturn(null);
    stubListing(sized, unsized);

    Map<String, ContainerDto> fleet = byName(subject.listContainers(HOST, false));

    assertEquals(1.0, fleet.get("sized").sizeRootFsGb(), 1e-9);
    assertNull(fleet.get("unsized").sizeRootFsGb());
  }

  @Test
  void containerSizesAreReusedBetweenPollsRatherThanRewalkingEveryLayer() {
    Container sized = container("aaaaaaa1111", "/sized", "hermes/image:v1");
    when(sized.getSizeRootFs()).thenReturn(1_073_741_824L);
    // the same container as the daemon answers for it when the listing did not ask for size
    Container unpriced = container("aaaaaaa1111", "/sized", "hermes/image:v1");
    when(unpriced.getSizeRootFs()).thenReturn(null);
    ListContainersCmd list = stubListings(List.of(sized), List.of(unpriced));

    Double first = byName(subject.listContainers(HOST, false)).get("sized").sizeRootFsGb();
    Double second = byName(subject.listContainers(HOST, false)).get("sized").sizeRootFsGb();

    assertEquals(1.0, first, 1e-9);
    // the layer walk is ~90% of the cost of this listing and the answer moves on the order of
    // hours, so the second poll must skip it and still report — from the cache, not the daemon
    verify(list).withShowSize(true);
    verify(list).withShowSize(false);
    assertEquals(1.0, second, 1e-9);
  }

  @Test
  void aContainerTheSizeCacheNeverCoveredRearmsTheListingThatCanPriceIt() {
    Container known = container("aaaaaaa1111", "/known", "hermes/image:v1");
    when(known.getSizeRootFs()).thenReturn(1_073_741_824L);
    Container fresh = container("bbbbbbb2222", "/fresh", "hermes/image:v1");
    when(fresh.getSizeRootFs()).thenReturn(2_147_483_648L);
    ListContainersCmd list = stubListings(
        List.of(known), List.of(known, fresh), List.of(known, fresh));

    subject.listContainers(HOST, false);          // prices what is there
    subject.listContainers(HOST, false);          // a deploy lands mid-period, unpriced
    Map<String, ContainerDto> third = byName(subject.listContainers(HOST, false));

    // an Agent deployed inside the TTL must not read 0 GB until the period runs out
    verify(list, times(2)).withShowSize(true);
    assertEquals(2.0, third.get("fresh").sizeRootFsGb(), 1e-9);
  }

  @Test
  void uptimeIsReadOncePerStatusLineRatherThanOncePerPoll() {
    Container running = containerInState("aaaaaaa1111", "/live", "running", "Up 3 minutes");
    stubListings(List.of(running), List.of(running));
    stubStartedAt("aaaaaaa1111", "2026-08-24T07:00:00.000000000Z");

    Long first = subject.listContainers(HOST, false).get(0).startedAt();
    Long second = subject.listContainers(HOST, false).get(0).startedAt();

    assertEquals(first, second);
    // it cannot move while the container keeps running, so paying an inspect per poll for it
    // buys nothing
    verify(client, times(1)).inspectContainerCmd("aaaaaaa1111");
  }

  @Test
  void theHermesReleaseIsReadOncePerImageAndOnlyFromARunningContainer() {
    Container running = containerInState("aaaaaaa1111", "/live", "running", "Up 3 minutes");
    when(running.getImageId()).thenReturn("sha256:img1");
    Container stopped = container("bbbbbbb2222", "/parked", "hermes/image:latest");
    when(stopped.getImageId()).thenReturn("sha256:img1");
    stubListings(List.of(running, stopped), List.of(running, stopped));
    stubStartedAt("aaaaaaa1111", "2026-08-24T07:00:00.000000000Z");
    when(dockerExec.run(eq(HOST), eq("aaaaaaa1111"), any(), anyString(), anyBoolean(), anyBoolean(), any()))
        .thenReturn(new ExecResult(0, "2026.8.19\n", ""));

    Map<String, ContainerDto> first = byName(subject.listContainers(HOST, false));
    Map<String, ContainerDto> second = byName(subject.listContainers(HOST, false));

    // a container on `latest` reports a pointer, so the release is what the card shows instead
    assertEquals("2026.8.19", first.get("live").release());
    assertEquals("2026.8.19", second.get("live").release());
    // a property of the image, so one exec answers for every poll that follows
    verify(dockerExec, times(1)).run(any(), anyString(), any(), anyString(), anyBoolean(), anyBoolean(), any());
    // an exec needs a running container; a stopped one shows its tag rather than a guess
    assertNull(first.get("parked").release());
    verify(dockerExec, never()).run(any(), eq("bbbbbbb2222"), any(), anyString(), anyBoolean(), anyBoolean(), any());
  }

  @Test
  void aReleaseReadThatFailsOrFindsNothingIsNullAndAFailedOneIsRetriedNextPoll() {
    Container noImage = containerInState("aaaaaaa1111", "/no-image", "running", "Up 1 minute");
    when(noImage.getImageId()).thenReturn(null);
    Container failing = containerInState("bbbbbbb2222", "/failing", "running", "Up 1 minute");
    when(failing.getImageId()).thenReturn("sha256:img-fail");
    Container silent = containerInState("ccccccc3333", "/silent", "running", "Up 1 minute");
    when(silent.getImageId()).thenReturn("sha256:img-silent");
    stubListings(List.of(noImage, failing, silent), List.of(noImage, failing, silent));
    stubStartedAt("aaaaaaa1111", "2026-08-24T07:00:00.000000000Z");
    stubStartedAt("bbbbbbb2222", "2026-08-24T07:00:00.000000000Z");
    stubStartedAt("ccccccc3333", "2026-08-24T07:00:00.000000000Z");
    when(dockerExec.run(eq(HOST), eq("bbbbbbb2222"), any(), anyString(), anyBoolean(), anyBoolean(), any()))
        .thenReturn(new ExecResult(1, "", "sh: not found"));
    when(dockerExec.run(eq(HOST), eq("ccccccc3333"), any(), anyString(), anyBoolean(), anyBoolean(), any()))
        .thenReturn(new ExecResult(0, "\n", ""));

    Map<String, ContainerDto> first = byName(subject.listContainers(HOST, false));
    Map<String, ContainerDto> second = byName(subject.listContainers(HOST, false));

    assertNull(first.get("no-image").release());
    assertNull(first.get("failing").release());
    assertNull(first.get("silent").release());
    assertNull(second.get("silent").release());
    // no image id: nothing to key a read by, so none is attempted
    verify(dockerExec, never()).run(any(), eq("aaaaaaa1111"), any(), anyString(), anyBoolean(), anyBoolean(), any());
    // a read that did not run to completion is not remembered as "no release"
    verify(dockerExec, times(2)).run(any(), eq("bbbbbbb2222"), any(), anyString(), anyBoolean(), anyBoolean(), any());
    // one that ran and found nothing is — the image genuinely has no release to report
    verify(dockerExec, times(1)).run(any(), eq("ccccccc3333"), any(), anyString(), anyBoolean(), anyBoolean(), any());
  }

  @Test
  void aRestartedContainerGetsAFreshUptimeEvenThoughItKeptItsId() {
    Container before = containerInState("aaaaaaa1111", "/live", "running", "Up 3 hours");
    Container after = containerInState("aaaaaaa1111", "/live", "running", "Up 2 seconds");
    stubListings(List.of(before), List.of(after));
    stubStartedAt("aaaaaaa1111",
        "2026-08-24T07:00:00.000000000Z", "2026-08-24T10:00:00.000000000Z");

    Long first = subject.listContainers(HOST, false).get(0).startedAt();
    Long second = subject.listContainers(HOST, false).get(0).startedAt();

    // a restart keeps the container id, so the daemon's own status line is the only thing
    // that gives it away — and reusing the cached answer past it would report a wrong uptime
    assertNotEquals(first, second);
    assertEquals(Instant.parse("2026-08-24T10:00:00.000000000Z").toEpochMilli(), second);
  }

  @Test
  void anUptimeIsForgottenWithTheContainerThatOwnedIt() {
    Container live = containerInState("aaaaaaa1111", "/live", "running", "Up 3 minutes");
    Container other = container("bbbbbbb2222", "/other", "hermes/image:v1");
    stubListings(List.of(live), List.of(other), List.of(live));
    stubStartedAt("aaaaaaa1111",
        "2026-08-24T07:00:00.000000000Z", "2026-08-24T10:00:00.000000000Z");

    subject.listContainers(HOST, false);
    subject.listContainers(HOST, false);        // gone — nothing can ask about it again
    Long readAgain = subject.listContainers(HOST, false).get(0).startedAt();

    // recreated under the same id and the same status line, it must not inherit the old answer
    assertEquals(Instant.parse("2026-08-24T10:00:00.000000000Z").toEpochMilli(), readAgain);
  }

  @Test
  void anAgentWeDeployedStaysInTheFleetAfterItsImageReferenceStopsResolving() {
    Container agent = container("aaaaaaa1111", "/demo", BARE_IMAGE_ID);
    when(agent.getLabels()).thenReturn(Map.of("mc.managed", "true"));
    stubListing(agent);

    List<ContainerDto> fleet = subject.listContainers(HOST, false);

    // moving a floating tag during another container's upgrade makes the Engine report this
    // one by image id; that parses as repository "sha256", which matches nothing
    assertEquals(List.of("demo"), names(fleet));
    assertEquals("hermes/image", fleet.get(0).image());
    assertNotEquals("sha256", fleet.get(0).image());
    assertNotEquals(BARE_IMAGE_ID.substring("sha256:".length()), fleet.get(0).version());
    assertEquals("", fleet.get(0).version());
  }

  @Test
  void anUnmanagedContainerReportingABareImageIdIsNotGuessedIntoTheFleet() {
    Container stray = container("bbbbbbb2222", "/stray", BARE_IMAGE_ID);
    when(stray.getLabels()).thenReturn(Map.of("mc.profiles", "ops"));
    Container agent = container("aaaaaaa1111", "/demo", "hermes/image:v1");
    when(agent.getLabels()).thenReturn(Map.of("mc.managed", "true"));
    stubListing(agent, stray);

    // nothing ties this one to the configured repository, so claiming it runs Hermes
    // would invent an Agent the operator never deployed
    assertEquals(List.of("demo"), names(subject.listContainers(HOST, false)));
  }

  @Test
  void anUnmanagedAgentWhoseTagMovedIsRecoveredFromTheReferenceItWasCreatedFrom() {
    Container stray = container("bbbbbbb2222", "/stray", BARE_IMAGE_ID);
    stubCreatedFrom("bbbbbbb2222", "hermes/image:v1");
    Container foreign = container("ccccccc3333", "/foreign", BARE_IMAGE_ID);
    stubCreatedFrom("ccccccc3333", "other/thing:latest");
    stubListing(stray, foreign);

    List<ContainerDto> fleet = subject.listContainers(HOST, false);

    // the listing lost both references to a moved tag; inspect still holds what each was
    // created from, so the Hermes one is an Agent and the other is an ordinary non-member
    assertEquals(List.of("stray"), names(fleet));
    assertEquals("hermes/image", fleet.get(0).image());
    assertEquals("v1", fleet.get(0).version());
  }

  @Test
  void isImageIdReferenceRecognisesBothPrefixedAndBareDigests() {
    String hex = BARE_IMAGE_ID.substring("sha256:".length());

    assertTrue(ContainerInventory.isImageIdReference(BARE_IMAGE_ID));
    assertTrue(ContainerInventory.isImageIdReference(hex));
    assertFalse(ContainerInventory.isImageIdReference("hermes/image:v1"));
    assertFalse(ContainerInventory.isImageIdReference("hermes/image"));
    assertFalse(ContainerInventory.isImageIdReference(""));
    assertFalse(ContainerInventory.isImageIdReference(null));
  }

  @Test
  void bootstrapHelpersFromADeployAreNotShownAsAgents() {
    Container agent = container("aaaaaaa1111", "/demo", "hermes/image:v1");
    Container helper = container("bbbbbbb2222", "/nostalgic_wozniak", "hermes/image:v1");
    when(helper.getLabels()).thenReturn(Map.of("mc.bootstrap", "true"));
    stubListing(agent, helper);

    // a seeding helper runs the Hermes image but is never an Agent; a stray left by a
    // failed deploy still has to be reachable through ?all=true so it can be reaped
    assertEquals(List.of("demo"), names(subject.listContainers(HOST, false)));
    assertEquals(List.of("demo", "nostalgic_wozniak"),
        names(subject.listContainers(HOST, true)));
  }

  // ── exclusion reporting ────────────────────────────────────────────────────

  @Test
  void aStandingExclusionIsReportedOnceAcrossRepeatedListings() {
    stubListing(
        container("aaaaaaa1111", "/demo", "hermes/image:v1"),
        container("bbbbbbb2222", "/foreign", BARE_IMAGE_ID));

    // the fleet view polls every 10s forever; the warning describes a property of the
    // container, so re-reporting it per poll drowns every real event in the log
    for (int i = 0; i < 5; i++) {
      assertEquals(List.of("demo"), names(subject.listContainers(HOST, false)));
    }

    assertEquals(1, warningsSoFar().stream().filter(line -> line.contains("foreign")).count());
  }

  @Test
  void anExclusionIsReportedAgainAfterTheContainerGoesAwayAndComesBack() {
    stubListing(container("bbbbbbb2222", "/foreign", BARE_IMAGE_ID));
    subject.listContainers(HOST, false);

    stubListing(container("aaaaaaa1111", "/demo", "hermes/image:v1"));
    subject.listContainers(HOST, false);

    stubListing(container("bbbbbbb2222", "/foreign", BARE_IMAGE_ID));
    subject.listContainers(HOST, false);

    // a container that came back is a new occurrence, not the one already reported
    assertEquals(2, warningsSoFar().stream().filter(line -> line.contains("foreign")).count());
  }

  @Test
  void listingOneHostDoesNotReArmAnIdenticallyNamedExclusionOnAnother() {
    DockerHostRef other = new DockerHostRef("remote", "tcp://elsewhere:2375");
    DockerClient otherClient = mock(DockerClient.class);
    when(clients.forUrl("tcp://elsewhere:2375")).thenReturn(otherClient);

    stubListing(container("bbbbbbb2222", "/foreign", BARE_IMAGE_ID));
    subject.listContainers(HOST, false);

    // container names are unique per daemon, not across them
    ListContainersCmd list = mock(ListContainersCmd.class, Answers.RETURNS_SELF);
    when(otherClient.listContainersCmd()).thenReturn(list);
    when(list.exec()).thenReturn(List.<Container>of());
    subject.listContainers(other, false);

    stubListing(container("bbbbbbb2222", "/foreign", BARE_IMAGE_ID));
    subject.listContainers(HOST, false);

    assertEquals(1, warningsSoFar().stream().filter(line -> line.contains("foreign")).count());
  }

  private static List<String> names(List<ContainerDto> containers) {
    return containers.stream().map(ContainerDto::name).toList();
  }

  private static Map<String, ContainerDto> byName(List<ContainerDto> containers) {
    Map<String, ContainerDto> byName = new LinkedHashMap<>();
    for (ContainerDto dto : containers) {
      byName.put(dto.name(), dto);
    }
    return byName;
  }

  /** A stopped container — the one state that needs no inspect round-trip for uptime. */
  private static Container container(String id, String name, String image) {
    Container c = mock(Container.class);
    when(c.getId()).thenReturn(id);
    when(c.getNames()).thenReturn(new String[]{name});
    when(c.getImage()).thenReturn(image);
    when(c.getState()).thenReturn("exited");
    return c;
  }

  private static Container containerInState(String id, String name, String state, String statusText) {
    Container c = container(id, name, "hermes/image:v1");
    when(c.getState()).thenReturn(state);
    when(c.getStatus()).thenReturn(statusText);
    return c;
  }

  private void stubListing(Container... containers) {
    stubListings(List.of(containers));
  }

  /** Successive listings, for the caches that only show up across more than one poll. */
  @SafeVarargs
  private ListContainersCmd stubListings(List<Container>... listings) {
    ListContainersCmd list = mock(ListContainersCmd.class, Answers.RETURNS_SELF);
    when(client.listContainersCmd()).thenReturn(list);
    OngoingStubbing<List<Container>> stub = when(list.exec()).thenReturn(listings[0]);
    for (int i = 1; i < listings.length; i++) stub = stub.thenReturn(listings[i]);
    return list;
  }

  /** Successive inspects of one container — a second value is what a restart looks like. */
  private void stubStartedAt(String id, String... isos) {
    InspectContainerCmd inspect = mock(InspectContainerCmd.class);
    InspectContainerResponse inspected = mock(InspectContainerResponse.class);
    ContainerState state = mock(ContainerState.class);
    when(client.inspectContainerCmd(id)).thenReturn(inspect);
    when(inspect.exec()).thenReturn(inspected);
    when(inspected.getState()).thenReturn(state);
    OngoingStubbing<String> stub = when(state.getStartedAt()).thenReturn(isos[0]);
    for (int i = 1; i < isos.length; i++) stub = stub.thenReturn(isos[i]);
  }

  private void stubCreatedFrom(String id, String reference) {
    InspectContainerCmd inspect = mock(InspectContainerCmd.class);
    InspectContainerResponse inspected = mock(InspectContainerResponse.class);
    ContainerConfig config = mock(ContainerConfig.class);
    when(client.inspectContainerCmd(id)).thenReturn(inspect);
    when(inspect.exec()).thenReturn(inspected);
    when(inspected.getConfig()).thenReturn(config);
    when(config.getImage()).thenReturn(reference);
  }

  private void stubMissingOnInspect(String id) {
    InspectContainerCmd inspect = mock(InspectContainerCmd.class);
    when(client.inspectContainerCmd(id)).thenReturn(inspect);
    when(inspect.exec()).thenThrow(new NotFoundException("no such container"));
  }

  // ── pure mapping the fleet view depends on ───────────────────────────────

  @Test
  void aBareImageIdIsRecognisedWithAndWithoutItsPrefix() {
    // the Engine reports one once the stored reference stops resolving, and it is not a repository
    assertTrue(ContainerInventory.isImageIdReference(
        "sha256:e5b3f7c1a9d24b6e8f0a1c2d3e4f5061728394a5b6c7d8e9f0a1b2c3d4e5f607"));
    assertTrue(ContainerInventory.isImageIdReference(
        "e5b3f7c1a9d24b6e8f0a1c2d3e4f5061728394a5b6c7d8e9f0a1b2c3d4e5f607"));
    // an ordinary reference, a short hex string and nothing at all are not image ids
    assertFalse(ContainerInventory.isImageIdReference("hermes/agent:latest"));
    assertFalse(ContainerInventory.isImageIdReference("deadbeef"));
    assertFalse(ContainerInventory.isImageIdReference("sha256:notevenhexnotevenhexnotevenhex00"));
    assertFalse(ContainerInventory.isImageIdReference(null));
    assertFalse(ContainerInventory.isImageIdReference("   "));
  }

  @Test
  void everyEngineStateMapsToOneOfTheFourTheDashboardRenders() {
    assertEquals("running", ContainerInventory.mapStatus("running", "Up 3 hours"));
    // the health suffix is the only way the Engine reports an unhealthy container in a list
    assertEquals("unhealthy", ContainerInventory.mapStatus("running", "Up 3 hours (unhealthy)"));
    assertEquals("unhealthy", ContainerInventory.mapStatus("restarting", "Restarting (1)"));
    assertEquals("stopped", ContainerInventory.mapStatus("exited", "Exited (0)"));
    assertEquals("stopped", ContainerInventory.mapStatus("created", ""));
    assertEquals("stopped", ContainerInventory.mapStatus("paused", "Paused"));
    assertEquals("stopped", ContainerInventory.mapStatus("dead", "Dead"));
    assertEquals("unknown", ContainerInventory.mapStatus("removing", ""));
    assertEquals("unknown", ContainerInventory.mapStatus(null, null));
    // the Engine capitalises inconsistently across versions
    assertEquals("running", ContainerInventory.mapStatus("Running", "Up 1 second"));
  }

  @Test
  void aRunningContainerWithNoStatusTextIsStillRunning() {
    assertEquals("running", ContainerInventory.mapStatus("running", null));
  }

  @Test
  void aContainerWeDeployedIsShownWhateverItsImageReferenceReadsAs() {
    // the Engine substitutes a bare image id once the stored reference stops resolving — which
    // another Agent's upgrade does by moving a floating tag — and an id matches no repository
    Container managed = container("aaaaaaa1111", "/demo", BARE_IMAGE_ID);
    when(managed.getLabels()).thenReturn(Map.of("mc.managed", "true"));
    stubListing(managed);

    assertEquals(List.of("demo"), names(subject.listContainers(HOST, false)));
  }

  @Test
  void aContainerReportingAnImageIdThatWeDidNotDeployIsNotShown() {
    Container foreign = container("bbbbbbb2222", "/someone-elses", BARE_IMAGE_ID);
    stubListing(foreign);

    assertTrue(names(subject.listContainers(HOST, false)).isEmpty());
  }

  @Test
  void aBootstrapHelperIsNeverAnAgent() {
    Container helper = container("ccccccc3333", "/mc-seed-demo", "hermes/image:v1");
    when(helper.getLabels()).thenReturn(Map.of("mc.bootstrap", "true"));
    stubListing(helper);

    assertTrue(names(subject.listContainers(HOST, false)).isEmpty());
  }
}
