package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class HrmsBootstrapCredentialTest {
  private static final String BOOTSTRAP_HASH =
      "$2a$12$vv2074FPhWNMKb9HDsP9hu3Av11EyJX/Z8cjDAaCShvZWbrV1m2bK";

  @Test
  void bootstrapMigrationHashMatchesDocumentedInitialPassword() {
    assertTrue(new BCryptPasswordEncoder(12).matches("admin", BOOTSTRAP_HASH),
        "The HRMS bootstrap account must authenticate with the documented initial password.");
  }
}
