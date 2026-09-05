package io.hermes.missioncontrol.agents.templates;

import io.hermes.missioncontrol.agents.HermesProfiles;
import io.hermes.missioncontrol.agents.HermesSetup;
import io.hermes.missioncontrol.agents.McpServerDefinition;
import io.hermes.missioncontrol.agents.ProfileSpec;
import io.hermes.missioncontrol.agents.api.AgentProfileDto;
import io.hermes.missioncontrol.agents.api.DeployedPart;
import io.hermes.missioncontrol.agents.api.EnvEntry;
import io.hermes.missioncontrol.docker.DockerHostRef;
import io.hermes.missioncontrol.secrets.StoredSecret;
import io.hermes.missioncontrol.skills.GuideDeploy;
import io.hermes.missioncontrol.skills.Skill;
import io.hermes.missioncontrol.skills.SkillDeployer;
import io.hermes.missioncontrol.skills.SkillGuide;
import io.hermes.missioncontrol.skills.SkillGuideRepository;
import io.hermes.missioncontrol.skills.SkillRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Writing a template's contents — soul, memory, skills, MCP entries, guides, secrets — onto a
 * live agent profile.
 *
 * <p>Split out of {@link ProfileTemplateService} because this is the only part that reaches a
 * container, and because it carries a rule that is easy to get wrong: a half-applied template
 * leaves a misconfigured profile, so the profile is rolled back <em>only</em> when this code
 * created it. {@link #deployNew} owns the profile and is all-or-nothing;
 * {@link #layerOnto} writes into a profile someone else owns and only surfaces the error.
 *
 * <p>Library skills and guides are references, resolved here at deploy time the way a guide
 * resolves its own parts. The difference is what a missing one means: a guide layers onto an
 * agent that exists and reports the part as skipped, while a template is creating the agent the
 * operator asked for, so a row that is gone fails the deploy and the rollback runs.
 */
@Component
class TemplateApplier {

  private static final Logger log = LoggerFactory.getLogger(TemplateApplier.class);

  private final HermesProfiles profiles;
  private final HermesSetup setup;
  private final TemplateSecrets secrets;
  private final SkillRepository skillLibrary;
  private final SkillDeployer skillDeployer;
  private final SkillGuideRepository guides;
  private final GuideDeploy guideDeploy;

  TemplateApplier(
      HermesProfiles profiles, HermesSetup setup, TemplateSecrets secrets,
      SkillRepository skillLibrary, SkillDeployer skillDeployer,
      SkillGuideRepository guides, GuideDeploy guideDeploy) {
    this.profiles = profiles;
    this.setup = setup;
    this.secrets = secrets;
    this.skillLibrary = skillLibrary;
    this.skillDeployer = skillDeployer;
    this.guides = guides;
    this.guideDeploy = guideDeploy;
  }

  /** Creates the profile from the template's own model settings, then applies it. All or
   *  nothing: a failure anywhere drops the profile this call created. */
  AgentProfileDto deployNew(ProfileTemplate template, DockerHostRef host, String containerId, String name) {
    ProfileSpec spec = specFor(template, containerId, name);
    // spec.name(), not name: the spec folded it to the directory hermes actually created, and
    // the apply and the rollback both have to address that one. The whole of it sits inside the
    // creating window, so the Agents page does not list — and a shell cannot start hermes on —
    // a profile whose key is still a few writes away.
    return profiles.whileCreating(containerId, spec.name(), () -> {
      profiles.create(host, spec);
      try {
        return apply(template, host, containerId, spec.name());
      } catch (RuntimeException failure) {
        rollback(host, containerId, spec.name(), failure);
        throw failure;
      }
    });
  }

  /** Applies the template onto a profile the caller already owns. The profile is left in
   *  place on failure — dropping someone else's agent is not this code's call. */
  AgentProfileDto layerOnto(
      ProfileTemplate template, DockerHostRef host, String containerId, String name) {
    return apply(template, host, containerId, name);
  }

  /**
   * Applies the template, model settings included, onto a profile that already exists and that
   * nobody configured yet: a new container's {@code default} profile, which the image creates
   * on first boot with hermes' own defaults. {@link #layerOnto} leaves the model alone because
   * the caller chose one; here the template is the only thing that has.
   *
   * <p>No rollback of its own — there is no profile to drop, the container deploy that calls
   * this undoes the whole container — but the same creating window as {@link #deployNew}, so
   * the Agents page does not list a default agent that is still being written to.
   */
  AgentProfileDto configureAndApply(
      ProfileTemplate template, DockerHostRef host, String containerId, String name) {
    return profiles.whileCreating(containerId, name, () -> {
      profiles.configureModel(host, specFor(template, containerId, name));
      return apply(template, host, containerId, name);
    });
  }

  /** The template's model settings as the spec a profile is created or configured from. */
  private static ProfileSpec specFor(ProfileTemplate template, String containerId, String name) {
    return new ProfileSpec(
        containerId, name,
        blankTo(template.provider(), "nous"),
        blankTo(template.model(), "Hermes-4-405B"),
        null, null, blankTo(template.baseUrl(), null), null);
  }

  /** Best-effort cleanup of a profile the caller created and could not finish configuring. */
  void rollback(DockerHostRef host, String containerId, String name, RuntimeException failure) {
    try {
      profiles.delete(host, containerId, name);
    } catch (RuntimeException cleanup) {
      failure.addSuppressed(cleanup);
      log.warn("rollback of partially-applied profile '{}' failed: {}", name, cleanup.getMessage());
    }
  }

  private AgentProfileDto apply(
      ProfileTemplate template, DockerHostRef host, String containerId, String name) {
    if (!template.soul().isBlank()) {
      profiles.updateSoul(host, containerId, name, template.soul());
    }
    if (!template.memory().isBlank()) {
      profiles.updateMemory(host, containerId, name, template.memory());
    }
    // the editor has always offered a working dir; until now nothing wrote it
    if (template.cwd() != null && !template.cwd().isBlank()) {
      profiles.setWorkingDir(host, containerId, name, template.cwd().trim());
    }
    for (String skill : template.skills()) {
      if (skill != null && !skill.isBlank()) {
        profiles.installSkill(host, containerId, name, skill.trim());
      }
    }
    for (String skillId : template.librarySkillIds()) {
      if (skillId == null || skillId.isBlank()) continue;
      Skill skill = skillLibrary.find(skillId.trim()).orElseThrow(() -> new NoSuchElementException(
          "library skill " + skillId.trim() + " is no longer in the library"));
      skillDeployer.deploy(host, containerId, name, skill);
    }
    for (McpServerSpec server : template.mcpServers()) {
      if (server == null || server.name() == null || server.name().isBlank()) continue;
      profiles.addMcpServer(host, containerId, name, definitionOf(server));
    }
    for (String guideId : template.guideIds()) {
      if (guideId == null || guideId.isBlank()) continue;
      SkillGuide guide = guides.find(guideId.trim()).orElseThrow(() -> new NoSuchElementException(
          "guide " + guideId.trim() + " is no longer in the library"));
      deployGuide(guide, host, containerId, name);
    }
    List<EnvEntry> env = environment(template);
    if (!env.isEmpty()) {
      setup.putEnv(host, containerId, name, env);
    }
    return profiles.get(host, containerId, name);
  }

  /**
   * A guide goes on through {@link GuideDeploy} — its skills, its MCP servers and the umbrella
   * SKILL.md, in the order that class keeps — which reports per part and never throws. That is
   * the right shape for layering onto someone else's agent and the wrong one here, where the
   * template is all or nothing: a failed part becomes the failure, so the caller's rollback runs.
   *
   * <p>ponytail: a skipped part (a skill gone from the guide's library, a server already linked)
   * is logged and let through — the umbrella document names only what landed, so the agent is
   * not told about a part it cannot reach. Tighten to a failure if a silently thinner guide
   * ever bites.
   */
  private void deployGuide(SkillGuide guide, DockerHostRef host, String containerId, String name) {
    for (DeployedPart part : guideDeploy.onto(guide, host, containerId, name).parts()) {
      if (DeployedPart.FAILED.equals(part.status())) {
        throw new IllegalStateException("guide '" + guide.name() + "': " + part.kind() + " '"
            + part.name() + "' failed — " + part.detail());
      }
      if (DeployedPart.SKIPPED.equals(part.status())) {
        log.warn("template deploy: guide '{}' skipped {} '{}': {}",
            guide.name(), part.kind(), part.name(), part.detail());
      }
    }
  }

  /** A snapshot's stdio environment applies only to a stdio server, and its headers only to a
   *  network one — sending either to the wrong transport would write credentials that
   *  transport never reads. {@link McpServerDefinition} drops the irrelevant side itself, so
   *  this only has to decrypt the one that survives. */
  private McpServerDefinition definitionOf(McpServerSpec server) {
    McpServerDefinition.Transport transport =
        McpServerDefinition.Transport.of(server.transport());
    boolean stdio = transport == McpServerDefinition.Transport.STDIO;
    return new McpServerDefinition(
        server.name(), transport, server.url(), server.command(),
        stdio ? McpServerDefinition.splitArgs(server.args()) : List.of(),
        server.enabled(),
        stdio ? null : secrets.decryptValues(server.headers()),
        stdio ? secrets.decryptValues(server.environment()) : null);
  }

  /** The template's secrets that still decrypt. A capture-only placeholder holds no value,
   *  and an unrecoverable one yields null — neither is written as an empty variable. */
  private List<EnvEntry> environment(ProfileTemplate template) {
    List<EnvEntry> env = new ArrayList<>();
    for (StoredSecret stored : template.secrets()) {
      String value = secrets.decryptOrNull(stored.enc());
      if (value != null && !value.isBlank()) {
        env.add(new EnvEntry(stored.key(), value));
      }
    }
    return env;
  }

  private static String blankTo(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }
}
