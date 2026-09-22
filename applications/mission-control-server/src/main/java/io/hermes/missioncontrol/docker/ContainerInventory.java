package io.hermes.missioncontrol.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.Version;
import io.hermes.missioncontrol.config.AppProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * What the fleet view shows: the daemon's own version, and the containers on it that are
 * Mission Control's business.
 *
 * <p>Split out of {@link DockerGateway} because the filtering is the subtle part. Every
 * {@code continue} below is a container deliberately hidden, and each has cost a live Agent
 * its card at some point — a moved floating tag, a stranded bootstrap helper, a container
 * parked by an interrupted upgrade.
 */
@Component
public class ContainerInventory {

  private static final Logger log = LoggerFactory.getLogger(ContainerInventory.class);

  /**
   * Containers already reported as hidden from the fleet, as {@code hostId/name}, so each is
   * reported once rather than once per listing.
   *
   * <p>This is what the exclusion warnings are for: an operator looking for a container that
   * is not on the dashboard. They describe a standing property of a container, not an event,
   * and the fleet view polls every 10 seconds — so unguarded they re-fire forever. On a
   * machine running the managed MCP stack that was 572 of 612 log lines, and it buried the
   * upgrade and terminal failures the file existed to surface.
   *
   * <p>Pruned in {@link #listContainers} against the names that daemon still reports, so a
   * container that goes away and comes back is reported again — the second occurrence is
   * genuinely new information. Keyed by host as well as name because container names are only
   * unique per daemon, and listing one host must not re-arm another host's report.
   */
  private final Set<String> reportedExclusions = ConcurrentHashMap.newKeySet();

  /** How long a root-filesystem size is reused before a listing pays to re-read it. */
  private static final Duration SIZE_TTL = Duration.ofMinutes(5);

  /**
   * Container sizes per daemon url, and how current they are.
   *
   * <p>{@code withShowSize} makes the daemon walk every container's layer diff. Measured
   * against this project's own daemon that is ~90ms per listing against ~46ms without it —
   * a ~90% surcharge on the one call the fleet view makes every 10 seconds, for a number
   * that moves on the order of hours. So the poll asks for size only once per
   * {@link #SIZE_TTL} and reuses the last answer in between.
   *
   * <p>{@code covered} is tracked apart from {@code gbById} because a daemon can list a
   * container and report no size for it. Without that distinction such a container reads as
   * permanently uncached and re-arms the expensive listing on every single poll — exactly
   * the cost this exists to avoid.
   */
  private final Map<String, SizeCache> sizesByHost = new ConcurrentHashMap<>();

  private record SizeCache(Map<String, Double> gbById, Set<String> covered, long fetchedAt) { }

  /**
   * When a running container started, keyed by {@code hostId/containerId}.
   *
   * <p>Uptime is the one card field the listing does not carry, so it costs an inspect per
   * running container per poll — ~7ms each, every 10 seconds, for a value that cannot change
   * while a container keeps running. What makes reuse safe is the daemon's own status line:
   * a restart keeps the container id but resets {@code "Up 3 hours"} to {@code "Up 2 seconds"},
   * so pinning the cached answer to the status it was read under re-inspects exactly when it
   * must. Steady state falls to one inspect per status change — a minute at first, then an hour.
   *
   * <p>Only successful reads are cached. A failed inspect is retried on the next poll rather
   * than remembered as "no uptime" for as long as the status line happens to hold still.
   */
  private final Map<String, StartedAt> startedAtCache = new ConcurrentHashMap<>();

  private record StartedAt(String status, long epochMs) { }

  /**
   * The Hermes release baked into an image, keyed by image id.
   *
   * <p>A container on {@code latest} reports a pointer, not a version, and the registry can only
   * resolve that pointer when some release tag shares its digest — a build published straight
   * from {@code main} has none, so the card read "latest" beside an "update available" badge.
   * Hermes itself knows: {@code hermes_cli/__init__.py} carries {@code __release_date__}, the
   * same calendar version the image tags use. Reading it costs one exec, and the answer is a
   * property of the image, so it is read once per image id for the life of the process.
   *
   * <p>Only a completed read is cached, an empty one included — an image without the file
   * genuinely has no release to report. A transport failure is retried on the next poll.
   */
  private final Map<String, String> releaseByImage = new ConcurrentHashMap<>();

  private static final Duration RELEASE_TIMEOUT = Duration.ofSeconds(10);
  private static final List<String> READ_RELEASE = List.of("sh", "-c",
      "sed -n 's/^__release_date__ = \"\\(.*\\)\"/\\1/p' /opt/hermes/hermes_cli/__init__.py 2>/dev/null || true");

  private final DockerClients clients;
  private final AppProperties props;
  private final ImageStore images;
  private final DockerExecService dockerExec;

  public ContainerInventory(
      DockerClients clients, AppProperties props, ImageStore images, DockerExecService dockerExec) {
    this.clients = clients;
    this.props = props;
    this.images = images;
    this.dockerExec = dockerExec;
  }

  public DaemonInfo ping(String url) {
    DockerClient client = clients.forUrl(url);
    long t0 = System.nanoTime();
    client.pingCmd().exec();
    long latencyMs = Math.max(0, (System.nanoTime() - t0) / 1_000_000);
    Version version = client.versionCmd().exec();
    return new DaemonInfo("Docker " + version.getVersion(), version.getApiVersion(), latencyMs);
  }

  public List<ContainerDto> listContainers(DockerHostRef host, boolean includeAll) {
    DockerClient client = clients.forUrl(host.url());
    boolean withSize = sizesAreStale(host);
    List<Container> containers = client.listContainersCmd()
        .withShowAll(true)
        .withShowSize(withSize)
        .exec();
    Map<String, Double> sizes = withSize ? cacheSizes(host, containers) : cachedSizes(host);

    List<ContainerDto> result = new ArrayList<>();
    Set<String> present = new HashSet<>();
    Set<String> live = new HashSet<>();
    // containers on a host overwhelmingly share a handful of images, so the digest lookup
    // is resolved once per image rather than once per container
    Map<String, String> digests = new HashMap<>();
    for (Container c : containers) {
      present.add(exclusionKey(host.id(), primaryName(c)));
      live.add(startedAtKey(host.id(), c.getId()));
      String image = imageReference(client, c);
      if (!includeAll && !isFleetMember(host.id(), c, image)) continue;
      result.add(toDto(client, c, image, host, digests, sizes));
    }
    // every call lists the whole daemon (withShowAll), so anything this host reported before
    // and does not report now is gone; other hosts' entries are left alone
    reportedExclusions.removeIf(
        key -> key.startsWith(host.id() + "/") && !present.contains(key));
    // an uptime nothing can ask for again, for the same reason and on the same terms
    startedAtCache.keySet().removeIf(
        key -> key.startsWith(host.id() + "/") && !live.contains(key));
    expireSizesIfUncovered(host, containers);
    return result;
  }

  /** Whether the next listing has to pay {@code withShowSize} to answer for this daemon. */
  private boolean sizesAreStale(DockerHostRef host) {
    SizeCache cached = sizesByHost.get(host.url());
    return cached == null
        || System.currentTimeMillis() - cached.fetchedAt() >= SIZE_TTL.toMillis();
  }

  private Map<String, Double> cachedSizes(DockerHostRef host) {
    SizeCache cached = sizesByHost.get(host.url());
    return cached == null ? Map.of() : cached.gbById();
  }

  /** Folds a size-carrying listing into the cache and answers what it now holds. */
  private Map<String, Double> cacheSizes(DockerHostRef host, List<Container> listed) {
    Map<String, Double> gbById = new HashMap<>();
    Set<String> covered = new HashSet<>();
    for (Container c : listed) {
      covered.add(c.getId());
      if (c.getSizeRootFs() != null) gbById.put(c.getId(), c.getSizeRootFs() / 1_073_741_824.0);
    }
    sizesByHost.put(host.url(),
        new SizeCache(Map.copyOf(gbById), Set.copyOf(covered), System.currentTimeMillis()));
    return gbById;
  }

  /**
   * Ages out the size cache the moment the daemon lists a container it never covered.
   *
   * <p>A container deployed a minute into the TTL would otherwise read 0 GB until the whole
   * period ran out. Marking the entry stale rather than dropping it means the next poll
   * re-reads sizes while this one still renders the ones it already knows — dropping it
   * would blank every card's disk figure for a poll to fix one card's.
   */
  private void expireSizesIfUncovered(DockerHostRef host, List<Container> listed) {
    SizeCache cached = sizesByHost.get(host.url());
    if (cached == null || cached.fetchedAt() == 0) return;
    if (listed.stream().allMatch(c -> cached.covered().contains(c.getId()))) return;
    sizesByHost.put(host.url(), new SizeCache(cached.gbById(), cached.covered(), 0));
  }

  /** True the first time a container is excluded, false while that exclusion stands. */
  private boolean firstReportOf(String hostId, String name) {
    return reportedExclusions.add(exclusionKey(hostId, name));
  }

  private static String exclusionKey(String hostId, String name) {
    return hostId + "/" + name;
  }

  private static String startedAtKey(String hostId, String containerId) {
    return hostId + "/" + containerId;
  }

  /**
   * When this container started, from {@link #startedAtCache} unless the daemon's status line
   * has moved since it was read.
   *
   * <p>Inspection is best effort: a container that goes away mid-listing, or a daemon that
   * drops the call, leaves the card showing '—' for uptime rather than failing the listing.
   */
  private Long startedAt(DockerClient client, String hostId, Container c) {
    String key = startedAtKey(hostId, c.getId());
    String status = c.getStatus() == null ? "" : c.getStatus();
    StartedAt cached = startedAtCache.get(key);
    if (cached != null && cached.status().equals(status)) return cached.epochMs();
    try {
      String iso = client.inspectContainerCmd(c.getId()).exec().getState().getStartedAt();
      if (iso == null) return null;
      long epochMs = Instant.parse(iso).toEpochMilli();
      startedAtCache.put(key, new StartedAt(status, epochMs));
      return epochMs;
    } catch (Exception ignored) {
      // the card just shows '—' for uptime, and the next poll tries again
      return null;
    }
  }

  /** Whether the filtered fleet view shows this container. Every rejection is logged or
   *  commented at the point it is made, because each hid a container an operator looked for. */
  private boolean isFleetMember(String hostId, Container c, String image) {
    String name = primaryName(c);
    Map<String, String> labels = c.getLabels() == null ? Map.of() : c.getLabels();

    if (ParkedContainerName.isUpgradeLeftover(name)) {
      // a daemon crash mid-upgrade can strand the parked original; keep it out
      // of the fleet view but reachable through ?all=true
      if (firstReportOf(hostId, name)) {
        log.warn("hiding {} from the fleet: left parked by an interrupted upgrade "
            + "(still listed by ?all=true)", name);
      }
      return false;
    }
    if (ManagedContainer.isBootstrap(labels)) {
      // a short-lived seeding helper, or one stranded by a failed deploy — never an Agent
      return false;
    }
    // A container we deployed is ours whatever its image reference reads as, so it is
    // never matched on that reference. The Engine substitutes the raw image ID once a
    // reference stops resolving — moving the `latest` tag during one Agent's upgrade
    // does exactly that to every other Agent on that tag — and an ID parses as
    // repository "sha256", matching nothing and dropping a live Agent out of the fleet.
    if (ManagedContainer.isManaged(labels)) return true;

    if (isImageIdReference(image)) {
      // only when inspect could not say either — see imageReference
      if (firstReportOf(hostId, name)) {
        log.warn("hiding {} from the fleet: it reports an image id rather than a reference "
            + "and carries no Mission Control label", name);
      }
      return false;
    }
    String hermesRepo = images.normalizedHermesRepository();
    if (!hermesRepo.isEmpty()) {
      return hermesRepo.equals(ImageRef.normalizeRepository(ImageRef.splitImage(image)[0]));
    }
    String filter = props.containerFilter() == null
        ? "" : props.containerFilter().toLowerCase(Locale.ROOT);
    if (filter.isEmpty()) return true;
    return image.toLowerCase(Locale.ROOT).contains(filter)
        || name.toLowerCase(Locale.ROOT).contains(filter);
  }

  private ContainerDto toDto(
      DockerClient client, Container c, String image, DockerHostRef host,
      Map<String, String> digestCache, Map<String, Double> sizes) {
    String hostId = host.id();
    String name = primaryName(c);
    String[] imageParts = ImageRef.splitImage(image);
    if (isImageIdReference(image)) {
      // "sha256:e5b3…" would otherwise render as repository "sha256" with the hex as the
      // version. Inspect could not recover the reference either (see imageReference), so
      // report the repository the container is known to run and leave the tag blank.
      imageParts = new String[]{images.hermesRepository(), ""};
    }
    String status = mapStatus(c.getState(), c.getStatus());

    Long startedAt = null;
    String release = null;
    if ("running".equals(status) || "unhealthy".equals(status)) {
      startedAt = startedAt(client, hostId, c);
      release = release(host, c);
    }

    Double sizeGb = sizes.get(c.getId());
    Map<String, String> labels = c.getLabels() == null ? Map.of() : c.getLabels();
    List<String> profiles = ManagedContainer.seedProfilesOf(labels);

    return new ContainerDto(
        c.getId(), c.getId().substring(0, Math.min(7, c.getId().length())), name, hostId,
        status, imageParts[0], imageParts[1], imageDigest(client, c, digestCache), release,
        startedAt, sizeGb, profiles, PublishedPorts.fromListing(c.getPorts()));
  }

  /** The Hermes release this container's image carries, or null when it cannot say. */
  private String release(DockerHostRef host, Container c) {
    String imageId = c.getImageId();
    if (imageId == null || imageId.isBlank()) return null;
    String cached = releaseByImage.get(imageId);
    if (cached == null) {
      try {
        var result = dockerExec.run(
            host, c.getId(), READ_RELEASE, "read hermes release", false, false, RELEASE_TIMEOUT);
        if (result.exitCode() != 0) return null;
        cached = result.stdout().trim();
      } catch (RuntimeException unavailable) {
        return null;
      }
      releaseByImage.put(imageId, cached);
    }
    return cached.isBlank() ? null : cached;
  }

  /**
   * The registry manifest digest of the image a container runs, from its {@code RepoDigests}.
   *
   * <p>Best effort by design: an image built locally and never pushed has no repo digest, and
   * an unreachable daemon is already reported by everything else on this path. Both answer
   * null, which the dashboard reads as "cannot tell" rather than "up to date" — the one thing
   * this must never do is manufacture a false update prompt.
   */
  private static String imageDigest(
      DockerClient client, Container c, Map<String, String> cache) {
    String imageId = c.getImageId();
    if (imageId == null || imageId.isBlank()) return null;
    String digest = cache.computeIfAbsent(imageId, id -> {
      try {
        List<String> repoDigests = client.inspectImageCmd(id).exec().getRepoDigests();
        if (repoDigests == null) return "";
        // entries read repository@sha256:… — the digest is what compares against a registry
        return repoDigests.stream()
            .map(entry -> entry.substring(entry.indexOf('@') + 1))
            .filter(value -> value.startsWith("sha256:"))
            .findFirst()
            .orElse("");
      } catch (RuntimeException unavailable) {
        return "";
      }
    });
    return digest.isBlank() ? null : digest;
  }

  /**
   * The image reference this container runs. The listing resolves the stored reference
   * against the image store and substitutes the bare image id once that stops resolving —
   * moving a floating tag does it to every container left on the old layer — but inspect
   * keeps {@code Config.Image}, the reference the container was created from, verbatim. So
   * an id in the listing is answered from there; the id itself only when inspect cannot say.
   */
  private static String imageReference(DockerClient client, Container c) {
    String listed = c.getImage() == null ? "" : c.getImage();
    if (!isImageIdReference(listed)) return listed;
    try {
      // ponytail: one inspect per id-reporting container per poll; key a cache by container
      // id if a daemon ever carries more than a handful of them
      String created = client.inspectContainerCmd(c.getId()).exec().getConfig().getImage();
      return created == null || created.isBlank() ? listed : created;
    } catch (RuntimeException unavailable) {
      return listed;
    }
  }

  private static String primaryName(Container c) {
    String[] names = c.getNames();
    if (names == null || names.length == 0) return "?";
    return names[0].startsWith("/") ? names[0].substring(1) : names[0];
  }

  /**
   * True when the Engine reported a bare image ID instead of a reference. It does that once
   * the stored reference no longer resolves to the container's image — notably after another
   * container's upgrade moves a floating tag. Such a value is not a repository, so matching
   * it against one is meaningless.
   */
  static boolean isImageIdReference(String image) {
    if (image == null || image.isBlank()) return false;
    String value = image.startsWith("sha256:") ? image.substring("sha256:".length()) : image;
    return value.length() >= 32 && value.chars()
        .allMatch(ch -> (ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'f'));
  }

  static String mapStatus(String state, String statusText) {
    String s = state == null ? "" : state.toLowerCase(Locale.ROOT);
    if ("running".equals(s)) {
      return statusText != null && statusText.contains("(unhealthy)") ? "unhealthy" : "running";
    }
    if ("restarting".equals(s)) return "unhealthy";
    if ("exited".equals(s) || "created".equals(s) || "paused".equals(s) || "dead".equals(s)) return "stopped";
    return "unknown";
  }
}
