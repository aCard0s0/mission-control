package io.hermes.missioncontrol.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.validation.Validation;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DeployRequestValidationTest {

  @Test
  void seedProfilesUseHermesLowercaseNameRules() {
    try (var factory = Validation.buildDefaultValidatorFactory()) {
      var validator = factory.getValidator();
      assertTrue(validator.validate(
          new DeployRequest("dh-local", "demo", "latest", List.of("default", "ops-team"), null, null, null, null, null)).isEmpty());
      assertFalse(validator.validate(
          new DeployRequest("dh-local", "demo", "latest", List.of("Bad.Name"), null, null, null, null, null)).isEmpty());
    }
  }

  @Test
  void aDefaultTemplateIdIsBoundedAndAbsentByDefault() {
    try (var factory = Validation.buildDefaultValidatorFactory()) {
      var validator = factory.getValidator();
      var bare = new DeployRequest("dh-local", "demo", "latest", List.of(), null, null, null, null, null);
      assertFalse(bare.hasDefaultTemplate());
      assertFalse(new DeployRequest(
          "dh-local", "demo", "latest", List.of(), null, null, null, null, null, "   ").hasDefaultTemplate());
      var withBlueprint = new DeployRequest(
          "dh-local", "demo", "latest", List.of(), null, null, null, null, null, "pt-1");
      assertTrue(withBlueprint.hasDefaultTemplate());
      assertTrue(validator.validate(withBlueprint).isEmpty());
      assertFalse(validator.validate(new DeployRequest(
          "dh-local", "demo", "latest", List.of(), null, null, null, null, null, "x".repeat(65))).isEmpty());
    }
  }

  @Test
  void aDeployTagIsValidatedTheSameWayAnUpdateTagIs() {
    // the same value reaches the same daemon as an update tag; unconstrained, a typo was
    // only caught by the daemon — after the managed volume had already been created
    try (var factory = Validation.buildDefaultValidatorFactory()) {
      var validator = factory.getValidator();

      for (String rejected : List.of("bad tag!", "v1\nlatest", "t".repeat(200))) {
        var violations = validator.validate(
            new DeployRequest("dh-local", "demo", rejected, List.of("default"), null, null, null, null, null));
        assertEquals(1, violations.size(), "should have been rejected: " + rejected);
        var violation = violations.iterator().next();
        assertEquals("version", violation.getPropertyPath().toString());
        assertEquals("invalid image tag", violation.getMessage());
      }

      for (String accepted : List.of("v2026.8.3", "latest")) {
        assertTrue(validator.validate(
                new DeployRequest("dh-local", "demo", accepted, List.of("default"), null, null, null, null, null)).isEmpty(),
            "should have been accepted: " + accepted);
      }
    }
  }

  @Test
  void anAbsentOrBlankVersionStillMeansLatest() {
    // DockerGateway.deploy maps a blank version onto 'latest', so the new rule must leave
    // the unset case alone rather than forcing the caller to spell out a tag
    try (var factory = Validation.buildDefaultValidatorFactory()) {
      var validator = factory.getValidator();
      for (String unset : Arrays.asList(null, "")) {
        assertTrue(validator.validate(
                new DeployRequest("dh-local", "demo", unset, List.of("default"), null, null, null, null, null))
                .stream()
                .noneMatch(v -> v.getPropertyPath().toString().equals("version")),
            "an unset version must still mean 'latest'");
      }
    }
  }

  @Test
  void aMountThatCarriesTheDockerSocketIsRefusedWithItsReason() {
    for (String source : List.of("/var/run/docker.sock", "/var/run", "/run/", "/var", "/")) {
      var request = new DeployRequest("dh-local", "demo", "latest", List.of(), null, null,
          null, null, List.of(new HostAccess.Mount(source, "/work", false)));
      var refused = assertThrows(IllegalArgumentException.class, request::hostAccess, source);
      assertTrue(refused.getMessage().contains("Docker socket"), refused.getMessage());
    }
  }

  @Test
  void aMountMayNotShadowTheDataVolumeOrTheInstallTree_andPathsMustBeAbsolute() {
    for (String target : List.of("/opt/data", "/opt/data/profiles", "/opt/hermes/", "/opt/hermes/bin")) {
      var request = new DeployRequest("dh-local", "demo", "latest", List.of(), null, null,
          null, null, List.of(new HostAccess.Mount("/srv/repo", target, false)));
      assertThrows(IllegalArgumentException.class, request::hostAccess, target);
    }
    var relative = new DeployRequest("dh-local", "demo", "latest", List.of(), null, null,
        null, null, List.of(new HostAccess.Mount("repo", "/work", false)));
    assertThrows(IllegalArgumentException.class, relative::hostAccess);
    assertEquals(HostAccess.NONE,
        new DeployRequest("dh-local", "demo", "latest", List.of(), null, null, null, null, null).hostAccess(),
        "nothing asked for is the same as empty lists");
  }

  @Test
  void portsAndVariablesAreValidatedAsTheDaemonWouldTakeThem() {
    try (var factory = Validation.buildDefaultValidatorFactory()) {
      var validator = factory.getValidator();
      var bad = new DeployRequest("dh-local", "demo", "latest", List.of(), null, null,
          List.of(new HostAccess.PortMapping(0, 70000, "not-an-ip")),
          List.of(new HostAccess.EnvVar("9BAD-NAME", "x")), null);
      var paths = validator.validate(bad).stream()
          .map(v -> v.getPropertyPath().toString()).sorted().toList();
      assertEquals(List.of("env[0].key", "ports[0].containerPort", "ports[0].hostIp", "ports[0].hostPort"), paths);

      var good = new DeployRequest("dh-local", "demo", "latest", List.of(), null, null,
          List.of(new HostAccess.PortMapping(9119, 9119, ""), new HostAccess.PortMapping(8642, 18642, "0.0.0.0")),
          List.of(new HostAccess.EnvVar("HERMES_DASHBOARD", "1")), null);
      assertTrue(validator.validate(good).isEmpty());
      assertEquals("127.0.0.1", good.hostAccess().ports().getFirst().bindIp(), "blank binds loopback");
    }
  }
}
