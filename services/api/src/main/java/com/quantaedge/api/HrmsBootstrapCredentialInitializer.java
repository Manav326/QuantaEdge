package com.quantaedge.api;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Replaces the documented local-only bootstrap password with a deployment secret exactly once
 * before the HTTP server accepts traffic. The one-time DB state prevents later restarts from
 * resetting a password that an administrator has already changed.
 */
@Component
public class HrmsBootstrapCredentialInitializer {
  private static final String BOOTSTRAP_KEY = "HRMS_ADMIN_CREDENTIALS";

  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;
  private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder(12);
  private final String appEnvironment;
  private final String activeProfiles;
  private final String bootstrapPassword;

  public HrmsBootstrapCredentialInitializer(
      JdbcTemplate jdbc,
      TransactionTemplate transactions,
      @Value("${app.environment:local}") String appEnvironment,
      @Value("${spring.profiles.active:}") String activeProfiles,
      @Value("${app.auth.hrms-bootstrap-password:}") String bootstrapPassword) {
    this.jdbc = jdbc;
    this.transactions = transactions;
    this.appEnvironment = appEnvironment;
    this.activeProfiles = activeProfiles;
    this.bootstrapPassword = bootstrapPassword;
  }

  @PostConstruct
  void initializeProductionBootstrapCredential() {
    if (!isProduction()) return;
    validateBootstrapPassword();
    transactions.executeWithoutResult(status -> {
      List<Map<String,Object>> rows = jdbc.queryForList("""
          select initialized from auth_bootstrap_state
          where bootstrap_key=? for update
          """, BOOTSTRAP_KEY);
      if (rows.isEmpty()) {
        throw new IllegalStateException("HRMS bootstrap state is missing; verify Flyway migration V92.");
      }
      if (Boolean.TRUE.equals(rows.getFirst().get("initialized"))) return;

      int changed = jdbc.update("""
          update staff_account
          set password_hash=?, must_change_password=true, password_changed_at=null, updated_at=now()
          where lower(username)='hrms_admin' and active=true
          """, passwordEncoder.encode(bootstrapPassword));
      if (changed != 1) {
        throw new IllegalStateException("The active HRMS_Admin bootstrap identity is missing.");
      }
      jdbc.update("""
          update auth_bootstrap_state set initialized=true, updated_at=now()
          where bootstrap_key=?
          """, BOOTSTRAP_KEY);
    });
  }

  private boolean isProduction() {
    return "production".equalsIgnoreCase(appEnvironment)
        || "prod".equalsIgnoreCase(appEnvironment)
        || Arrays.stream(activeProfiles.split(","))
            .map(String::trim)
            .anyMatch(profile -> "prod".equalsIgnoreCase(profile) || "production".equalsIgnoreCase(profile));
  }

  private void validateBootstrapPassword() {
    if (bootstrapPassword == null
        || bootstrapPassword.length() < 12
        || bootstrapPassword.getBytes(StandardCharsets.UTF_8).length > 72
        || bootstrapPassword.isBlank()
        || "admin".equalsIgnoreCase(bootstrapPassword.trim())) {
      throw new IllegalStateException(
          "Set APP_HRMS_BOOTSTRAP_PASSWORD to a unique 12–72 byte secret before the first production start.");
    }
  }
}
