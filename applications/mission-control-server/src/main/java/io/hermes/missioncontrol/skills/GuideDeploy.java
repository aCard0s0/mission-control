package io.hermes.missioncontrol.skills;

import io.hermes.missioncontrol.agents.AgentMcpCatalogService;
import io.hermes.missioncontrol.agents.HermesProfiles;
import io.hermes.missioncontrol.agents.api.AgentProfileDto;
import io.hermes.missioncontrol.agents.api.ConnectCatalogMcpRequest;
import io.hermes.missioncontrol.agents.api.DeployedPart;
import io.hermes.missioncontrol.docker.DockerHostRef;
import io.hermes.missioncontrol.mcp.McpRegistryService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Puts a whole guide on one agent: every skill it names, every MCP server it needs, and the
 * guide itself as an umbrella skill the agent can read.
 *
 * <p>Not in {@link SkillGuideController} — where it was — for the reason {@code AgentLifecycle}
 * gives for the same move: this is several independent writes to someone else's agent, they can
 * fail one at a time, and the ordering between them is load-bearing. None of that is an
 * implementation detail of an HTTP handler, and there it was reachable from nowhere else and
 * assertable only through MockMvc.
 *
 * <p>It follows the rule {@code agents.templates.TemplateApplier} states for layering onto a
 * profile the caller does not own — <em>surface the error, do not roll back</em> — and reports
 * what each part did rather than collapsing the lot into one status. Undoing half a guide would
 * mean removing skills and MCP entries that may have been on that agent before the guide ever
 * ran.
 *
 * <p><b>The umbrella skill is written last</b> and names only the parts the agent can actually
 * reach. Writing it first would tell the agent to reach for a skill that then failed to deploy,
 * which is worse than not mentioning it.
 */
@Service
public class GuideDeploy {

  private static final Logger log = LoggerFactory.getLogger(GuideDeploy.class);

  private final SkillRepository skills;
  private final SkillDeployer deployer;
  private final McpRegistryService registry;
  private final AgentMcpCatalogService mcpCatalog;
  private final HermesProfiles profiles;

  public GuideDeploy(
      SkillRepository skills, SkillDeployer deployer, McpRegistryService registry,
      AgentMcpCatalogService mcpCatalog, HermesProfiles profiles) {
    this.skills = skills;
    this.deployer = deployer;
    this.registry = registry;
    this.mcpCatalog = mcpCatalog;
    this.profiles = profiles;
  }

  /** The refreshed profile, plus one row per part so a half-applied guide is legible. */
  public record Deployed(AgentProfileDto profile, List<DeployedPart> parts) {
  }

  public Deployed onto(SkillGuide guide, DockerHostRef host, String containerId, String profile) {
    List<DeployedPart> parts = new ArrayList<>();
    List<String> deployedSkills = new ArrayList<>();
    List<String> linkedServers = new ArrayList<>();

    for (String skillId : guide.skillIds()) {
      Skill skill = skills.find(skillId).orElse(null);
      if (skill == null) {
        // the guide outlived the skill; say which one rather than failing the whole deploy
        parts.add(new DeployedPart("skill", skillId, DeployedPart.SKIPPED,
            "no longer in the library"));
        continue;
      }
      try {
        deployer.deploy(host, containerId, profile, skill);
        deployedSkills.add(skill.name());
        parts.add(DeployedPart.ok("skill", skill.name()));
      } catch (RuntimeException failure) {
        parts.add(failed("skill", skill.name(), failure));
      }
    }

    for (String serverId : guide.mcpServerIds()) {
      String name = serverId;
      try {
        name = registry.definition(serverId).name();
        boolean connected = mcpCatalog
            .connectIfAbsent(host, containerId, profile,
                new ConnectCatalogMcpRequest(serverId, AgentMcpCatalogService.aliasFor(name)))
            .isPresent();
        // Either way the server the guide wanted is on the agent, so it belongs in the
        // umbrella document below. An alias already there reads as skipped rather than
        // failed — the same answer a group deploy gives, now that both ask
        // AgentMcpCatalogService rather than reading the message a conflict carried.
        linkedServers.add(name);
        parts.add(connected
            ? DeployedPart.ok("mcp", name)
            : new DeployedPart("mcp", name, DeployedPart.SKIPPED, "already connected"));
      } catch (NoSuchElementException gone) {
        parts.add(new DeployedPart("mcp", name, DeployedPart.SKIPPED, "no longer in the catalog"));
      } catch (RuntimeException failure) {
        parts.add(failed("mcp", name, failure));
      }
    }

    // A guide and a library skill of the same name both resolve to skills/<name>/, so
    // writing the umbrella there would replace that skill's own SKILL.md with this
    // document — silently, and on the agent rather than here. Refuse that part instead.
    String clash = skills.findByName(guide.name()).map(Skill::name).orElse(null);
    if (clash != null) {
      parts.add(new DeployedPart("guide", guide.name(), DeployedPart.FAILED,
          "a library skill is also called '" + clash + "', and both deploy to skills/"
              + clash + " — rename one"));
    } else {
      try {
        profiles.installSkillFiles(host, containerId, profile, guide.name(),
            Map.of(Skill.SKILL_MD, GuideDocument.render(guide, deployedSkills, linkedServers)));
        parts.add(DeployedPart.ok("guide", guide.name()));
      } catch (RuntimeException failure) {
        parts.add(failed("guide", guide.name(), failure));
      }
    }

    return new Deployed(refreshedProfile(host, containerId, profile, parts), List.copyOf(parts));
  }

  /**
   * The profile as it now stands, or null if reading it back fails.
   *
   * <p>Null rather than a throw: by this point the parts have already landed on the agent,
   * and letting a failed re-read propagate would answer 500 and discard the report of what
   * was written — the one thing a half-applied deploy needs to hand back.
   */
  private AgentProfileDto refreshedProfile(
      DockerHostRef host, String containerId, String profile, List<DeployedPart> parts) {
    try {
      return profiles.get(host, containerId, profile);
    } catch (RuntimeException failure) {
      log.warn("guide deploy: could not re-read profile '{}' afterwards: {}",
          profile, failure.getMessage());
      parts.add(new DeployedPart("guide", profile, DeployedPart.FAILED,
          "deployed, but the agent could not be read back: " + failure.getMessage()));
      return null;
    }
  }

  private static DeployedPart failed(String kind, String name, RuntimeException failure) {
    log.warn("guide deploy: {} '{}' failed: {}", kind, name, failure.getMessage());
    return new DeployedPart(kind, name, DeployedPart.FAILED, failure.getMessage());
  }
}
