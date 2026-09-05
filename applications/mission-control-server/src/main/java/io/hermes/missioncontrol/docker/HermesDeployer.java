package io.hermes.missioncontrol.docker;

import static io.hermes.missioncontrol.docker.ContainerIds.shortId;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.exception.DockerClientException;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.AccessMode;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.RestartPolicy;
import com.github.dockerjava.api.model.Volume;
import io.hermes.missioncontrol.errors.ResourceConflictException;
import io.hermes.missioncontrol.errors.UpstreamUnavailableException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.Ports;
import java.util.ArrayList;

/**
 * Creating a new Hermes container, its data volume, and its seed profiles.
 *
 * <p>Split out of {@link DockerGateway} because a deploy is a multi-resource transaction:
 * a volume, one or more one-shot bootstrap containers, then the gateway itself. Everything
 * after the volume creation runs inside a rollback guard, and keeping that guard readable
 * is the point of the split.
 */
@Component
public class HermesDeployer {

  private static final Logger log = LoggerFactory.getLogger(HermesDeployer.class);

  private final DockerClients clients;
  private final ImageStore images;
  private final DeploymentReadiness readiness;

  public HermesDeployer(
      DockerClients clients, ImageStore images, DeploymentReadiness readiness) {
    this.clients = clients;
    this.images = images;
    this.readiness = readiness;
  }

  /** A deploy with nothing to do after the gateway is ready. */
  public String deploy(
      DockerHostRef host, String name, String version, List<String> profiles,
      ContainerResources resources, HostAccess access) {
    return deploy(host, name, version, profiles, resources, access, containerId -> { });
  }

  /**
   * @param afterReady runs with the new container's id once readiness has passed and before
   *     the deploy is reported — the seam through which a blueprint reaches the {@code default}
   *     profile, which the image itself creates. It runs inside the rollback guard: a blueprint
   *     that fails takes the container and its volume with it, the same as a seed profile that
   *     fails, rather than leaving an agent that is half of what was asked for. A
   *     {@code Consumer} rather than a reference to the agents package, because that dependency
   *     already points the other way.
   */
  public String deploy(
      DockerHostRef host, String name, String version, List<String> profiles,
      ContainerResources resources, HostAccess access, Consumer<String> afterReady) {
    DockerClient client = clients.forUrl(host.url());
    String tag = ImageStore.tagOf(version);
    String image = images.reference(tag);
    String volumeName = ManagedContainer.dataVolumeName(name);
    List<String> seedProfiles = normalizeProfiles(profiles);

    try {
      client.inspectVolumeCmd(volumeName).exec();
      throw new ResourceConflictException(
          "managed data volume already exists: " + volumeName + "; recover or remove it before redeploying");
    } catch (NotFoundException expected) {
      // no legacy data to attach accidentally
    }

    Map<String, String> labels = ManagedContainer.labelsFor(volumeName, seedProfiles);

    String containerId = null;
    boolean volumeCreated = false;
    try {
      client.createVolumeCmd().withName(volumeName).exec();
      volumeCreated = true;
      HostConfig dataHostConfig = HostConfig.newHostConfig()
          .withBinds(new Bind(volumeName, new Volume(ManagedContainer.DATA_MOUNT), AccessMode.rw));
      // One-shot containers run the image's normal init hooks before their main
      // command. This seeds the default profile and creates named profiles while
      // the long-running gateway is still stopped, avoiding restart/exec races.
      // They get the operator's environment too, and not only for consistency: the init hook
      // is env-driven, and without API_SERVER_KEY in sight it generates one into .env — which
      // hermes then prefers over the key the gateway was given, so every request with the
      // operator's key is refused.
      List<String> env = environment(access);
      runOneShot(host, client, image, dataHostConfig, env, List.of("true"), "initialize Hermes data volume");
      for (String profile : seedProfiles) {
        runOneShot(host, client, image, dataHostConfig, env,
            List.of("profile", "create", profile, "--no-alias"),
            "create seed profile " + profile);
      }

      // The ceiling goes on the gateway, not on the one-shots above: those run the image's
      // init hooks for seconds and a limit there would turn a tight-but-workable size into a
      // deploy that fails during seeding, which reads as a broken image rather than a small box.
      // Ports and mounts go on the gateway alone: the one-shots seed a volume and are gone,
      // and a published port on one would be a second listener fighting the gateway for the
      // same host port.
      List<Bind> binds = new ArrayList<>();
      binds.add(new Bind(volumeName, new Volume(ManagedContainer.DATA_MOUNT), AccessMode.rw));
      for (HostAccess.Mount mount : access.mounts()) {
        binds.add(new Bind(mount.source(), new Volume(mount.target()),
            mount.readOnly() ? AccessMode.ro : AccessMode.rw));
      }
      Ports portBindings = new Ports();
      List<ExposedPort> exposed = new ArrayList<>();
      for (HostAccess.PortMapping port : access.ports()) {
        ExposedPort containerPort = ExposedPort.tcp(port.containerPort());
        exposed.add(containerPort);
        portBindings.bind(containerPort, Ports.Binding.bindIpAndPort(port.bindIp(), port.hostPort()));
      }
      HostConfig hostConfig = HostConfig.newHostConfig()
          .withBinds(binds)
          .withRestartPolicy(RestartPolicy.unlessStoppedRestart())
          .withMemory(resources.memoryBytes())
          .withNanoCPUs(resources.nanoCpus())
          .withShmSize(ContainerResources.SHM_SIZE_BYTES)
          .withPortBindings(portBindings)
          .withExtraHosts(HostAccess.HOST_GATEWAY);

      CreateContainerResponse created;
      try {
        created = createContainer(client, image, name, labels, hostConfig, env, exposed);
      } catch (NotFoundException missingImage) {
        images.pull(host, images.hermesRepository(), tag);
        created = createContainer(client, image, name, labels, hostConfig, env, exposed);
      }
      containerId = created.getId();
      client.startContainerCmd(containerId).exec();
      readiness.validate(host, client, containerId, seedProfiles);
      afterReady.accept(containerId);
      log.info("deployed {} from {} on {} — container {}, volume {}, seed profiles {}, {} MB / {} cpus, "
              + "{} published port(s), {} mount(s)",
          name, image, host.id(), shortId(containerId), volumeName,
          seedProfiles.isEmpty() ? "none" : seedProfiles, resources.memoryMb(), resources.cpus(),
          access.ports().size(), access.mounts().size());
      return containerId;
    } catch (RuntimeException failure) {
      // The deploy pulls an image and runs bootstrap containers, so a failure can arrive
      // minutes in. Naming what is being undone is the only way to tell a clean rollback
      // from one that stranded a volume — the caller sees the cause, never the cleanup.
      log.warn("deploy of {} on {} failed, rolling back{}: {}", name, host.id(),
          volumeCreated ? " (container and data volume " + volumeName + ")" : "",
          failure.getMessage());
      rollback(client, containerId, volumeCreated ? volumeName : null, failure);
      throw failure;
    }
  }


  private static CreateContainerResponse createContainer(
      DockerClient client, String image, String name, Map<String, String> labels,
      HostConfig hostConfig, List<String> env, List<ExposedPort> exposed) {
    var create = client.createContainerCmd(image)
        .withName(name)
        .withLabels(labels)
        .withHostConfig(hostConfig);
    if (!env.isEmpty()) create.withEnv(env);
    if (!exposed.isEmpty()) create.withExposedPorts(exposed);
    return create.withCmd(List.of("gateway", "run")).exec();
  }

  /** The operator's variables, with the write-safe root widened over their writable mounts —
   *  {@link HostAccess#environment} over an environment that is empty, this being a new container. */
  static List<String> environment(HostAccess access) {
    return access.environment(List.of());
  }

  static List<String> normalizeProfiles(List<String> profiles) {
    if (profiles == null || profiles.isEmpty()) return List.of();
    Set<String> unique = new LinkedHashSet<>();
    for (String profile : profiles) {
      if (profile == null) continue;
      String normalized = profile.trim();
      if (!normalized.isEmpty() && !"default".equals(normalized)) unique.add(normalized);
    }
    return List.copyOf(unique);
  }

  void runOneShot(
      DockerHostRef host, DockerClient client, String image, HostConfig hostConfig,
      List<String> env, List<String> command, String operation) {
    String helperId = null;
    RuntimeException failure = null;
    try {
      CreateContainerResponse helper;
      try {
        helper = createHelper(client, image, hostConfig, env, command);
      } catch (NotFoundException missingImage) {
        String[] parts = ImageRef.splitImage(image);
        images.pull(host, parts[0], parts[1]);
        helper = createHelper(client, image, hostConfig, env, command);
      }
      helperId = helper.getId();
      client.startContainerCmd(helperId).exec();
      // /wait emits nothing until the container exits, so it must not run on a client
      // carrying a socket timeout — the 90s budget below is the real bound
      var callback = clients.streamingForUrl(host.url()).waitContainerCmd(helperId).start();
      try {
        Integer exitCode = callback.awaitStatusCode(90, TimeUnit.SECONDS);
        if (exitCode == null) throw new UpstreamUnavailableException(operation + " timed out");
        if (exitCode != 0) throw new UpstreamUnavailableException(operation + " failed with exit code " + exitCode);
      } catch (DockerClientException exhausted) {
        // how docker-java actually reports an expired wait: a DockerClientException, which is
        // not a DockerException, so it would otherwise reach the advice's catch-all as a 500
        throw new UpstreamUnavailableException(operation + " timed out", exhausted);
      } finally {
        try {
          callback.close();
        } catch (Exception ignored) { }
      }
    } catch (RuntimeException e) {
      failure = e;
      throw e;
    } finally {
      if (helperId != null) {
        try {
          client.removeContainerCmd(helperId).withForce(true).exec();
        } catch (RuntimeException cleanup) {
          if (failure != null) {
            failure.addSuppressed(cleanup);
          } else {
            // the step itself succeeded. Failing the deploy over a transient cleanup error
            // would roll back working state — and the surviving helper still mounts the data
            // volume, so the rollback could not remove that either. It is labelled
            // mc.bootstrap and can be reaped later.
            log.warn("{} succeeded but its bootstrap helper {} could not be removed: {}",
                operation, helperId, cleanup.getMessage());
          }
        }
      }
    }
  }

  private static CreateContainerResponse createHelper(
      DockerClient client, String image, HostConfig hostConfig, List<String> env,
      List<String> command) {
    var create = client.createContainerCmd(image)
        .withLabels(ManagedContainer.bootstrapLabels())
        .withHostConfig(hostConfig)
        .withCmd(command);
    if (!env.isEmpty()) create.withEnv(env);
    return create.exec();
  }

  private void rollback(
      DockerClient client, String containerId, String volumeName, RuntimeException failure) {
    if (containerId != null) {
      try {
        client.removeContainerCmd(containerId).withForce(true).exec();
      } catch (RuntimeException cleanup) {
        failure.addSuppressed(cleanup);
      }
    }
    if (volumeName != null) {
      try {
        client.removeVolumeCmd(volumeName).exec();
      } catch (RuntimeException cleanup) {
        failure.addSuppressed(cleanup);
      }
    }
  }
}
