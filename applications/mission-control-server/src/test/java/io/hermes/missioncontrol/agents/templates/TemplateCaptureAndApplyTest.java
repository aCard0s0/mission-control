package io.hermes.missioncontrol.agents.templates;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.hermes.missioncontrol.agents.HermesProfiles;
import io.hermes.missioncontrol.agents.HermesSetup;
import io.hermes.missioncontrol.agents.McpServerDefinition;
import io.hermes.missioncontrol.agents.ProfileSpec;
import io.hermes.missioncontrol.agents.api.AddMcpServerRequest;
import io.hermes.missioncontrol.agents.api.AgentMcpServerDto;
import io.hermes.missioncontrol.agents.api.AgentProfileDto;
import io.hermes.missioncontrol.agents.api.GatewayDto;
import io.hermes.missioncontrol.agents.api.AgentSetupDto;
import io.hermes.missioncontrol.agents.api.DeployedPart;
import io.hermes.missioncontrol.agents.api.ApiKeyStatusDto;
import io.hermes.missioncontrol.agents.api.EnvEntry;
import io.hermes.missioncontrol.agents.api.SkillDto;
import io.hermes.missioncontrol.docker.DockerHostRef;
import io.hermes.missioncontrol.secrets.SecretCipher;
import io.hermes.missioncontrol.secrets.SecretInput;
import io.hermes.missioncontrol.secrets.SecretRef;
import io.hermes.missioncontrol.secrets.StoredSecret;
import io.hermes.missioncontrol.skills.GuideDeploy;
import io.hermes.missioncontrol.skills.Skill;
import io.hermes.missioncontrol.skills.SkillDeployer;
import io.hermes.missioncontrol.skills.SkillFile;
import io.hermes.missioncontrol.skills.SkillGuide;
import io.hermes.missioncontrol.skills.SkillGuideRepository;
import io.hermes.missioncontrol.skills.SkillRepository;
import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

/**
 * Capturing a template off a running agent, and writing one back onto a profile.
 *
 * <p>Two rules here are the ones worth protecting. A capture cannot read {@code .env} values
 * back, so it records which keys were set and nothing else — a captured template must never look
 * like it carries credentials it does not have. And a half-applied template leaves a
 * misconfigured agent, so the profile is rolled back exactly when this code created it and never
 * when it belongs to the caller.
 */
class TemplateCaptureAndApplyTest {

  private static final DockerHostRef HOST = new DockerHostRef("dh-test", "unix:///sock");
  private static final String CONTAINER = "c1";

  private final ProfileTemplateRepository repository = mock(ProfileTemplateRepository.class);
  private final HermesProfiles profiles = TemplatesWiring.profilesMock();
  private final HermesSetup setup = mock(HermesSetup.class);
  private final SecretCipher cipher = new SecretCipher("unit-test-key", "", true);
  private final ProfileTemplateService service =
      TemplatesWiring.service(repository, cipher, profiles, setup);

  // the deploys that resolve library skills and guides go through these
  private final SkillRepository skillLibrary = mock(SkillRepository.class);
  private final SkillDeployer skillDeployer = mock(SkillDeployer.class);
  private final SkillGuideRepository guides = mock(SkillGuideRepository.class);
  private final GuideDeploy guideDeploy = mock(GuideDeploy.class);
  private final ProfileTemplateService libraryService = TemplatesWiring.service(
      repository, cipher, profiles, setup,
      new TemplatesWiring.Libraries(skillLibrary, skillDeployer, guides, guideDeploy));

  // ── capture ─────────────────────────────────────────────────────────────

  @Test
  void aCaptureTakesTheEnabledSkillsAndOnlyTheNamesOfTheKeysThatAreSet() {
    // .env cannot be read back, so a captured secret is a placeholder: the client has to see it
    // as not-set, or an operator would deploy the template expecting a key that isn't there
    agentIs(agent("scout", List.of(
        new SkillDto("s1", "refactor", "builtin", "1", "", true),
        new SkillDto("s2", "deploy", "builtin", "1", "", false)), List.of()));
    setupIs(List.of(
        new ApiKeyStatusDto("Anthropic", "ANTHROPIC_API_KEY", true, "sk-…abcd"),
        new ApiKeyStatusDto("OpenAI", "OPENAI_API_KEY", false, null)));

    ProfileTemplateDto captured = service.captureFromAgent(HOST, CONTAINER, "scout", "ops");

    assertEquals(List.of("refactor"), captured.skills());
    SecretRef secret = captured.secrets().getFirst();
    assertEquals(List.of("ANTHROPIC_API_KEY"), captured.secrets().stream().map(SecretRef::key).toList());
    assertFalse(secret.set(), "a captured key holds no value");
    assertFalse(secret.recoverable());
  }

  @Test
  void aCapturedMcpEntryKeepsItsTransportAndIsEnabledUnlessItWasDisabled() {
    agentIs(agent("scout", List.of(), List.of(
        mcp("files", "stdio", "connected"),
        mcp("docs", "http", "disabled"))));
    setupIs(List.of());

    ProfileTemplateDto captured = service.captureFromAgent(HOST, CONTAINER, "scout", "ops");

    assertEquals(List.of("files", "docs"), captured.mcpServers().stream().map(McpServerSpec::name).toList());
    assertEquals(true, captured.mcpServers().get(0).enabled());
    assertEquals(false, captured.mcpServers().get(1).enabled());
    assertEquals("stdio", captured.mcpServers().get(0).transport());
  }

  @Test
  void aCaptureFilesItselfUnderCapturedSoSnapshotsGroupWithoutBeingNamed() {
    agentIs(agent("scout", List.of(), List.of()));
    setupIs(List.of());

    assertEquals("captured", service.captureFromAgent(HOST, CONTAINER, "scout", "ops").category());
  }

  @Test
  void aCaptureIsNamedAfterTheAgentWhenTheOperatorGivesNoName() {
    agentIs(agent("scout", List.of(), List.of()));
    setupIs(List.of());

    assertEquals("scout-template", service.captureFromAgent(HOST, CONTAINER, "scout", null).name());
    assertEquals("scout-template", service.captureFromAgent(HOST, CONTAINER, "scout", "  ").name());
  }

  @Test
  void aNameAlreadyTakenGetsASuffixRatherThanFailingTheCapture() {
    // capture is a one-click action off an agent page; refusing it over a name the operator never
    // typed would be a dead end
    agentIs(agent("scout", List.of(), List.of()));
    setupIs(List.of());
    when(repository.existsByName("scout-template")).thenReturn(true);
    when(repository.existsByName("scout-template-2")).thenReturn(true);

    assertEquals("scout-template-3", service.captureFromAgent(HOST, CONTAINER, "scout", null).name());
  }

  @Test
  void aCaptureRecordsWhatItCameFromAndTheModelItWasRunning() {
    agentIs(agent("scout", List.of(), List.of()));
    setupIs(List.of());

    ProfileTemplateDto captured = service.captureFromAgent(HOST, CONTAINER, "scout", "ops");

    assertEquals("Captured from scout", captured.description());
    assertEquals("anthropic", captured.provider());
    assertEquals("claude-opus-5", captured.model());
    assertEquals("/work", captured.cwd());
    assertEquals("be useful", captured.soul());
    assertEquals("remembered", captured.memory());
    // baseUrl is not readable off a live profile, so it is captured empty rather than guessed
    assertEquals("", captured.baseUrl());
    verify(repository).insert(any(ProfileTemplate.class));
  }

  // ── deploy: the profile this code owns ──────────────────────────────────

  @Test
  void deployingCreatesTheProfileFromTheTemplatesOwnModelSettings() {
    templateIs(template(t -> {
      t.provider = "openai";
      t.model = "gpt-5.2";
      t.baseUrl = "https://gateway.test/v1";
    }));
    when(profiles.get(HOST, CONTAINER, "scout")).thenReturn(agent("scout", List.of(), List.of()));

    service.deploy("pt-1", HOST, CONTAINER, "scout");

    ArgumentCaptor<ProfileSpec> created = ArgumentCaptor.forClass(ProfileSpec.class);
    verify(profiles).create(eq(HOST), created.capture());
    assertEquals("openai", created.getValue().provider());
    assertEquals("gpt-5.2", created.getValue().model());
    assertEquals("https://gateway.test/v1", created.getValue().baseUrl());
    assertEquals("scout", created.getValue().name());
  }

  @Test
  void aTemplateWithNoModelSettingsFallsBackToTheHermesDefaults() {
    // a template captured before these fields existed, or authored empty, still has to deploy
    templateIs(template(t -> {
      t.provider = "";
      t.model = "  ";
      t.baseUrl = "";
    }));
    when(profiles.get(HOST, CONTAINER, "scout")).thenReturn(agent("scout", List.of(), List.of()));

    service.deploy("pt-1", HOST, CONTAINER, "scout");

    ArgumentCaptor<ProfileSpec> created = ArgumentCaptor.forClass(ProfileSpec.class);
    verify(profiles).create(eq(HOST), created.capture());
    assertEquals("nous", created.getValue().provider());
    assertEquals("Hermes-4-405B", created.getValue().model());
    assertNull(created.getValue().baseUrl(), "a blank base HOST must not be sent as an empty string");
  }

  @Test
  void everyPartOfATemplateIsWrittenAndTheEmptyPartsAreSkipped() {
    templateIs(template(t -> {
      t.soul = "be useful";
      t.memory = "   ";
      // List.of rejects nulls; a stored template can legitimately carry them
      t.skills = Arrays.asList("refactor", "  ", null);
      t.mcpServers = Arrays.asList(mcpSpec("files"), mcpSpec("  "), null);
      t.secrets = List.of(new StoredSecret("ANTHROPIC_API_KEY", cipher.encrypt("sk-ant-real")));
    }));
    when(profiles.get(HOST, CONTAINER, "scout")).thenReturn(agent("scout", List.of(), List.of()));

    service.deploy("pt-1", HOST, CONTAINER, "scout");

    InOrder order = inOrder(profiles, setup);
    order.verify(profiles).create(eq(HOST), any());
    order.verify(profiles).updateSoul(HOST, CONTAINER, "scout", "be useful");
    // the editor always offered a working dir; for a long time nothing wrote it
    order.verify(profiles).setWorkingDir(HOST, CONTAINER, "scout", "/work");
    order.verify(profiles).installSkill(HOST, CONTAINER, "scout", "refactor");
    order.verify(profiles).addMcpServer(eq(HOST), eq(CONTAINER), eq("scout"), any(McpServerDefinition.class));
    order.verify(setup).putEnv(HOST, CONTAINER, "scout",
        List.of(new EnvEntry("ANTHROPIC_API_KEY", "sk-ant-real")));
    // a blank memory is not a memory: writing it would overwrite whatever the profile had
    verify(profiles, never()).updateMemory(any(), anyString(), anyString(), anyString());
    verify(profiles, never()).installSkill(HOST, CONTAINER, "scout", "");
  }

  @Test
  void aSecretThatNoLongerDecryptsIsLeftOutRatherThanWrittenAsAnEmptyVariable() {
    // an empty ANTHROPIC_API_KEY in .env is worse than a missing one: the agent starts and fails
    // its first call with an auth error instead of saying the key is not configured
    String foreign = new SecretCipher("some-other-key", "", true).encrypt("sk-ant-real");
    templateIs(template(t -> t.secrets = List.of(
        new StoredSecret("ANTHROPIC_API_KEY", foreign),
        new StoredSecret("OPENAI_API_KEY", null))));
    when(profiles.get(HOST, CONTAINER, "scout")).thenReturn(agent("scout", List.of(), List.of()));

    service.deploy("pt-1", HOST, CONTAINER, "scout");

    verify(setup, never()).putEnv(any(), anyString(), anyString(), anyList());
  }

  @Test
  void aFailureWhileApplyingDropsTheProfileThisCallCreated() {
    templateIs(template(t -> t.skills = List.of("refactor")));
    doThrow(new IllegalStateException("skill not found"))
        .when(profiles).installSkill(HOST, CONTAINER, "scout", "refactor");

    assertEquals("skill not found", assertThrows(IllegalStateException.class,
        () -> service.deploy("pt-1", HOST, CONTAINER, "scout")).getMessage());

    // all or nothing: a profile with a soul and no skills is not what the operator asked for
    verify(profiles).delete(HOST, CONTAINER, "scout");
  }

  @Test
  void aRollbackThatItselfFailsIsAttachedToTheOriginalFailureNotSubstitutedForIt() {
    // the operator needs the reason the deploy failed; the cleanup problem is secondary
    templateIs(template(t -> t.skills = List.of("refactor")));
    IllegalStateException original = new IllegalStateException("skill not found");
    doThrow(original).when(profiles).installSkill(HOST, CONTAINER, "scout", "refactor");
    doThrow(new IllegalStateException("container is gone"))
        .when(profiles).delete(HOST, CONTAINER, "scout");

    IllegalStateException thrown = assertThrows(IllegalStateException.class,
        () -> service.deploy("pt-1", HOST, CONTAINER, "scout"));

    assertSame(original, thrown);
    assertEquals("container is gone", thrown.getSuppressed()[0].getMessage());
  }

  @Test
  void nullAndBlankReferencesAreSkippedRatherThanLookedUp() {
    // List.of refuses nulls, but a stored row can carry them, and a blueprint written before
    // the working dir existed reads it back as null
    templateIs(template(t -> {
      t.cwd = null;
      t.librarySkillIds = Arrays.asList(null, "  ");
      t.guideIds = Arrays.asList(null, "");
    }));
    when(profiles.get(HOST, CONTAINER, "scout")).thenReturn(agent("scout", List.of(), List.of()));

    libraryService.deploy("pt-1", HOST, CONTAINER, "scout");

    verify(profiles, never()).setWorkingDir(any(), anyString(), anyString(), anyString());
    verify(skillLibrary, never()).find(anyString());
    verify(guides, never()).find(anyString());
  }

  @Test
  void aBlankWorkingDirWritesNothing() {
    templateIs(template(t -> t.cwd = "  "));
    when(profiles.get(HOST, CONTAINER, "scout")).thenReturn(agent("scout", List.of(), List.of()));

    service.deploy("pt-1", HOST, CONTAINER, "scout");

    verify(profiles, never()).setWorkingDir(any(), anyString(), anyString(), anyString());
  }

  // ── apply to a new container's default profile ──────────────────────────

  @Test
  void theDefaultProfileGetsTheTemplatesModelSettingsThenItsContentsInsideTheWindow() {
    // the image creates `default` itself, so there is nothing to `profile create`; what the
    // template has that a layered-on profile does not get is the model
    templateIs(template(t -> {
      t.provider = "openai";
      t.model = "gpt-5.2";
      t.soul = "be useful";
    }));
    when(profiles.get(HOST, CONTAINER, "default")).thenReturn(agent("default", List.of(), List.of()));

    service.applyToDefault("pt-1", HOST, CONTAINER);

    ArgumentCaptor<ProfileSpec> configured = ArgumentCaptor.forClass(ProfileSpec.class);
    InOrder order = inOrder(profiles);
    order.verify(profiles).whileCreating(eq(CONTAINER), eq("default"), any());
    order.verify(profiles).configureModel(eq(HOST), configured.capture());
    order.verify(profiles).updateSoul(HOST, CONTAINER, "default", "be useful");
    assertEquals("default", configured.getValue().name());
    assertEquals("openai", configured.getValue().provider());
    assertEquals("gpt-5.2", configured.getValue().model());
    verify(profiles, never()).create(any(), any());
    verify(profiles, never()).createProfileBare(any(), any());
  }

  @Test
  void aFailureOnTheDefaultProfileIsThrownAndDropsNoProfile() {
    // the container deploy that asked for this rolls the whole container back; a profile-level
    // delete here would run `hermes profile delete default`, which is not a thing
    templateIs(template(t -> t.soul = "be useful"));
    doThrow(new IllegalStateException("soul write failed"))
        .when(profiles).updateSoul(HOST, CONTAINER, "default", "be useful");

    assertEquals("soul write failed", assertThrows(IllegalStateException.class,
        () -> service.applyToDefault("pt-1", HOST, CONTAINER)).getMessage());

    verify(profiles, never()).delete(any(), anyString(), anyString());
  }

  // ── deploy: the name hermes actually uses ───────────────────────────────

  @Test
  void aMixedCaseNameIsCreatedAndAppliedUnderTheNameHermesFoldsItTo() {
    // hermes lower-cases on `profile create`, so Coach lives at profiles/coach. Every later
    // write has to address that directory: `-p Coach` misses it, argparse reads the name as a
    // subcommand and answers with its usage text — the failure an operator saw as
    // "usage: hermes [-h] [--version] …" on deploying a blueprint called Coach
    templateIs(template(t -> t.soul = "be useful"));
    when(profiles.get(HOST, CONTAINER, "coach")).thenReturn(agent("coach", List.of(), List.of()));

    service.deploy("pt-1", HOST, CONTAINER, "Coach");

    ArgumentCaptor<ProfileSpec> created = ArgumentCaptor.forClass(ProfileSpec.class);
    verify(profiles).create(eq(HOST), created.capture());
    assertEquals("coach", created.getValue().name());
    verify(profiles).updateSoul(HOST, CONTAINER, "coach", "be useful");
    verify(profiles, never()).updateSoul(eq(HOST), eq(CONTAINER), eq("Coach"), anyString());
  }

  @Test
  void theWholeDeployHappensInsideTheCreatingWindowUnderTheFoldedName() {
    // the Agents page lists what is outside the window; a profile listed before its key landed
    // is one an operator opens a shell on and finds "No inference provider is configured yet"
    templateIs(template(t -> t.soul = "be useful"));
    when(profiles.get(HOST, CONTAINER, "coach")).thenReturn(agent("coach", List.of(), List.of()));

    service.deploy("pt-1", HOST, CONTAINER, "Coach");

    InOrder order = inOrder(profiles);
    order.verify(profiles).whileCreating(eq(CONTAINER), eq("coach"), any());
    order.verify(profiles).create(eq(HOST), any());
    order.verify(profiles).updateSoul(HOST, CONTAINER, "coach", "be useful");
  }

  @Test
  void theRollbackOfAMixedCaseNameDeletesTheProfileHermesActuallyMade() {
    // the rollback used to test for profiles/Coach, find nothing, and leave profiles/coach
    // behind — a half-built agent on hermes' default model, under a name nothing could reach
    templateIs(template(t -> t.skills = List.of("refactor")));
    doThrow(new IllegalStateException("skill not found"))
        .when(profiles).installSkill(HOST, CONTAINER, "coach", "refactor");

    assertThrows(IllegalStateException.class, () -> service.deploy("pt-1", HOST, CONTAINER, "Coach"));

    verify(profiles).delete(HOST, CONTAINER, "coach");
  }

  // ── deploy: library skills and guides ───────────────────────────────────

  @Test
  void aLibrarySkillDeploysByTheLibrarysOwnRuleNotByAHubInstall() {
    Skill local = new Skill("s-1", Skill.LOCAL, "pdf", "", "general", null, "1",
        List.of(new SkillFile("SKILL.md", "---\nname: pdf\n---\n")), 1L, 1L);
    when(skillLibrary.find("s-1")).thenReturn(Optional.of(local));
    templateIs(template(t -> t.librarySkillIds = List.of(" s-1 ")));
    when(profiles.get(HOST, CONTAINER, "scout")).thenReturn(agent("scout", List.of(), List.of()));

    libraryService.deploy("pt-1", HOST, CONTAINER, "scout");

    verify(skillDeployer).deploy(HOST, CONTAINER, "scout", local);
    verify(profiles, never()).installSkill(any(), anyString(), anyString(), anyString());
  }

  @Test
  void aLibrarySkillThatIsGoneFailsTheDeployAndRollsTheProfileBack() {
    // a guide reports such a part as skipped; a template is creating the agent the operator
    // asked for. And hermes answers 0 to a hub install of a name it cannot find, so this is
    // the one place the loss can be said out loud rather than discovered on the agent
    when(skillLibrary.find("s-gone")).thenReturn(Optional.empty());
    templateIs(template(t -> t.librarySkillIds = List.of("s-gone")));

    NoSuchElementException gone = assertThrows(NoSuchElementException.class,
        () -> libraryService.deploy("pt-1", HOST, CONTAINER, "scout"));

    assertEquals("library skill s-gone is no longer in the library", gone.getMessage());
    verify(profiles).delete(HOST, CONTAINER, "scout");
    verify(skillDeployer, never()).deploy(any(), anyString(), anyString(), any());
  }

  @Test
  void aGuideGoesOnThroughGuideDeployAndAFailedPartRollsTheProfileBack() {
    SkillGuide guide = guide("g-1", "pdf-triage");
    when(guides.find("g-1")).thenReturn(Optional.of(guide));
    when(guideDeploy.onto(guide, HOST, CONTAINER, "scout")).thenReturn(new GuideDeploy.Deployed(null, List.of(
        DeployedPart.ok("skill", "pdf"),
        new DeployedPart("mcp", "browser", DeployedPart.FAILED, "managed server is not running"))));
    templateIs(template(t -> t.guideIds = List.of("g-1")));

    IllegalStateException failure = assertThrows(IllegalStateException.class,
        () -> libraryService.deploy("pt-1", HOST, CONTAINER, "scout"));

    assertEquals("guide 'pdf-triage': mcp 'browser' failed — managed server is not running",
        failure.getMessage());
    verify(profiles).delete(HOST, CONTAINER, "scout");
  }

  @Test
  void aGuideWhosePartsLandedOrWereSkippedLetsTheDeployFinish() {
    // skipped is "already there" or "gone from the guide's own library" — the umbrella document
    // names only what landed, so the agent is not sent after a part it cannot reach
    SkillGuide guide = guide("g-1", "pdf-triage");
    when(guides.find("g-1")).thenReturn(Optional.of(guide));
    when(guideDeploy.onto(guide, HOST, CONTAINER, "scout")).thenReturn(new GuideDeploy.Deployed(null, List.of(
        DeployedPart.ok("guide", "pdf-triage"),
        new DeployedPart("mcp", "browser", DeployedPart.SKIPPED, "already connected"))));
    templateIs(template(t -> t.guideIds = List.of("g-1")));
    when(profiles.get(HOST, CONTAINER, "scout")).thenReturn(agent("scout", List.of(), List.of()));

    libraryService.deploy("pt-1", HOST, CONTAINER, "scout");

    verify(profiles, never()).delete(any(), anyString(), anyString());
  }

  @Test
  void aGuideThatIsGoneFailsTheDeployBeforeAnyOfItIsWritten() {
    when(guides.find("g-gone")).thenReturn(Optional.empty());
    templateIs(template(t -> t.guideIds = List.of("g-gone")));

    assertEquals("guide g-gone is no longer in the library",
        assertThrows(NoSuchElementException.class,
            () -> libraryService.deploy("pt-1", HOST, CONTAINER, "scout")).getMessage());

    verify(guideDeploy, never()).onto(any(), any(), anyString(), anyString());
    verify(profiles).delete(HOST, CONTAINER, "scout");
  }

  // ── layer: a profile someone else owns ──────────────────────────────────

  @Test
  void layeringOntoAnExistingProfileNeverDeletesItOnFailure() {
    // dropping an agent the caller already had is not this code's call
    ProfileTemplate template = template(t -> t.skills = List.of("refactor"));
    doThrow(new IllegalStateException("skill not found"))
        .when(profiles).installSkill(HOST, CONTAINER, "scout", "refactor");
    TemplateApplier applier = TemplatesWiring.applier(profiles, setup, cipher);

    assertThrows(IllegalStateException.class,
        () -> applier.layerOnto(template, HOST, CONTAINER, "scout"));

    verify(profiles, never()).delete(any(), anyString(), anyString());
    verify(profiles, never()).create(any(), any());
  }

  @Test
  void theCreateFlowOwnsItsBareProfileAndRollsItBack() {
    // createFromTemplate builds the profile from the caller's model settings, not the template's,
    // so it creates it itself — and therefore owns the rollback
    templateIs(template(t -> t.skills = List.of("refactor")));
    doThrow(new IllegalStateException("skill not found"))
        .when(profiles).installSkill(HOST, CONTAINER, "scout", "refactor");
    ProfileSpec spec = new ProfileSpec(
        CONTAINER, "scout", "anthropic", "claude-opus-5", null, null, null, null);

    assertThrows(IllegalStateException.class, () -> service.createFromTemplate("pt-1", HOST, spec));

    verify(profiles).createProfileBare(HOST, spec);
    verify(profiles).delete(HOST, CONTAINER, "scout");
    verify(profiles, never()).create(any(), any());
  }

  // ── stored secrets on the way in ────────────────────────────────────────

  @Test
  void aBlankSecretValueKeepsWhatIsStoredAndReSealsIt() {
    // the editor never receives ciphertext, so blank is how it says 'unchanged'
    String stored = cipher.encrypt("sk-ant-real");
    ProfileTemplate existing = template(t -> t.secrets = List.of(new StoredSecret("ANTHROPIC_API_KEY", stored)));
    when(repository.findById("pt-1")).thenReturn(Optional.of(existing));

    ProfileTemplateDto updated = service.update("pt-1", upsert(List.of(
        new SecretInput("ANTHROPIC_API_KEY", "  "))));

    assertEquals(List.of("ANTHROPIC_API_KEY"), updated.secrets().stream().map(SecretRef::key).toList());
    assertTrue(updated.secrets().getFirst().set());
    assertTrue(updated.secrets().getFirst().recoverable());

    // a save is also the rotation opportunity — a kept secret comes back under the current
    // key rather than riding on whatever wrote it, which is what retires MC_SECRET_KEY_PREVIOUS
    ArgumentCaptor<ProfileTemplate> saved = ArgumentCaptor.forClass(ProfileTemplate.class);
    verify(repository).update(saved.capture());
    String resealed = saved.getValue().secrets().getFirst().enc();
    assertFalse(stored.equals(resealed), "the envelope was carried over verbatim");
    assertEquals("sk-ant-real", cipher.decrypt(resealed));
  }

  @Test
  void aBlankSecretValueWithNothingStoredIsRefusedRatherThanDropped() {
    // Dropping it reported a success that did not happen: the template came back without the
    // key, and the next deploy produced an agent missing a credential it appeared to carry.
    // The MCP catalog already refused this; the two paths had drifted apart.
    when(repository.findById("pt-1")).thenReturn(Optional.of(template(t -> { })));

    assertEquals("secret value is required: OPENAI_API_KEY",
        assertThrows(IllegalArgumentException.class, () -> service.update("pt-1",
            upsert(List.of(new SecretInput("OPENAI_API_KEY", ""))))).getMessage());

    verify(repository, never()).update(any());
  }

  @Test
  void aCapturedPlaceholderSurvivesASaveThatDoesNotFillItIn() {
    // a capture records which keys were set and never their values, so a placeholder has no
    // envelope at all — refusing it would make every captured template unsaveable until the
    // operator typed in every credential at once
    ProfileTemplate captured =
        template(t -> t.secrets = List.of(new StoredSecret("ANTHROPIC_API_KEY", null)));
    when(repository.findById("pt-1")).thenReturn(Optional.of(captured));

    ProfileTemplateDto updated = service.update("pt-1", upsert(List.of(
        new SecretInput("ANTHROPIC_API_KEY", ""))));

    assertEquals(List.of("ANTHROPIC_API_KEY"), updated.secrets().stream().map(SecretRef::key).toList());
    assertFalse(updated.secrets().getFirst().set(), "a placeholder must not look like a stored key");
    assertFalse(updated.secrets().getFirst().recoverable());
  }

  @Test
  void aSecretKeyMustLookLikeAnEnvironmentVariableAndItsValueIsBounded() {
    when(repository.findById("pt-1")).thenReturn(Optional.of(template(t -> { })));

    assertEquals("invalid secret key: lower_case",
        assertThrows(IllegalArgumentException.class, () -> service.update("pt-1",
            upsert(List.of(new SecretInput("lower_case", "x"))))).getMessage());
    assertEquals("invalid secret key: 1LEADING",
        assertThrows(IllegalArgumentException.class, () -> service.update("pt-1",
            upsert(List.of(new SecretInput("1LEADING", "x"))))).getMessage());
    assertEquals("secret value too large for BIG_KEY",
        assertThrows(IllegalArgumentException.class, () -> service.update("pt-1",
            upsert(List.of(new SecretInput("BIG_KEY", "x".repeat(65_537)))))).getMessage());
    verify(repository, never()).update(any());
  }

  @Test
  void aSecretEntryWithNoKeyAtAllIsSkipped() {
    when(repository.findById("pt-1")).thenReturn(Optional.of(template(t -> { })));

    ProfileTemplateDto updated = service.update("pt-1", upsert(Arrays.asList(
        null, new SecretInput(null, "x"), new SecretInput("  ", "x"),
        new SecretInput("GOOD_KEY", "value"))));

    assertEquals(List.of("GOOD_KEY"), updated.secrets().stream().map(SecretRef::key).toList());
  }

  // ── fixtures ────────────────────────────────────────────────────────────

  private void agentIs(AgentProfileDto agent) {
    when(profiles.get(HOST, CONTAINER, agent.name())).thenReturn(agent);
  }

  private void setupIs(List<ApiKeyStatusDto> apiKeys) {
    when(setup.setup(eq(HOST), eq(CONTAINER), anyString())).thenReturn(
        new AgentSetupDto("/opt/data/.env", true, apiKeys, List.of(), List.of(), List.of()));
  }

  private void templateIs(ProfileTemplate template) {
    when(repository.findById("pt-1")).thenReturn(Optional.of(template));
  }

  private static AgentProfileDto agent(String name, List<SkillDto> skills, List<AgentMcpServerDto> mcp) {
    return new AgentProfileDto("c1:" + name, CONTAINER, name, "role", "idle", "anthropic",
        "claude-opus-5", "sk-…abcd", "/work", "be useful", "remembered", "model: opus\n",
        skills, mcp, List.of(), GatewayDto.unknown(), 0L);
  }

  private static AgentMcpServerDto mcp(String name, String transport, String status) {
    return new AgentMcpServerDto(name, name, transport, !"disabled".equals(status), status,
        0, null, null, null, "http://x:1/mcp", null, null);
  }

  private static McpServerSpec mcpSpec(String name) {
    return new McpServerSpec(name, "http", "http://x:1/mcp", null, null, true);
  }

  /** A template with everything empty, so each test sets only the part it is about. */
  private static ProfileTemplate template(java.util.function.Consumer<Fields> tweak) {
    Fields fields = new Fields();
    tweak.accept(fields);
    return new ProfileTemplate("pt-1", "ops", "", "desc", "ops", fields.provider, fields.model, fields.baseUrl,
        fields.cwd, fields.soul, fields.memory, fields.skills, fields.librarySkillIds, fields.guideIds,
        fields.mcpServers, fields.secrets, 1L, 1L);
  }

  private static SkillGuide guide(String id, String name) {
    return new SkillGuide(id, name, "", "## how the pieces fit", "general", List.of("s-1"), List.of(), 1L, 1L);
  }

  private static final class Fields {
    String provider = "anthropic";
    String model = "claude-opus-5";
    String baseUrl = "";
    String cwd = "/work";
    String soul = "";
    String memory = "";
    List<String> skills = List.of();
    List<String> librarySkillIds = List.of();
    List<String> guideIds = List.of();
    List<McpServerSpec> mcpServers = List.of();
    List<StoredSecret> secrets = List.of();
  }

  private static UpsertProfileTemplateRequest upsert(List<SecretInput> secrets) {
    return new UpsertProfileTemplateRequest("ops", "", "desc", "ops", "anthropic", "claude-opus-5", "",
        "/work", "soul", "memory", List.of(), List.of(), List.of(), List.of(), secrets);
  }
}
