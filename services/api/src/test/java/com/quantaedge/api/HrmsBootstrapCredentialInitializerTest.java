package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class HrmsBootstrapCredentialInitializerTest {
  private static final String STATE_KEY = "HRMS_ADMIN_CREDENTIALS";

  @Test
  void localDevelopmentKeepsMigrationCredentialAndDoesNotTouchBootstrapState() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    TransactionTemplate transactions = mock(TransactionTemplate.class);
    HrmsBootstrapCredentialInitializer initializer =
        new HrmsBootstrapCredentialInitializer(jdbc, transactions, "local", "", "");

    initializer.initializeProductionBootstrapCredential();

    verifyNoInteractions(jdbc, transactions);
  }

  @Test
  void productionRequiresAUniqueStrongSecretBeforeFirstInitialization() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    TransactionTemplate transactions = executeTransactionsInline();
    when(jdbc.queryForList(contains("select initialized"), eq(STATE_KEY)))
        .thenReturn(List.of(Map.of("initialized", false)));
    HrmsBootstrapCredentialInitializer initializer =
        new HrmsBootstrapCredentialInitializer(jdbc, transactions, "production", "", "");

    assertThrows(IllegalStateException.class, initializer::initializeProductionBootstrapCredential);
    verify(jdbc, never()).update(contains("update staff_account"), anyString());
  }

  @Test
  void productionRejectsTheDocumentedExamplePlaceholder() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    TransactionTemplate transactions = executeTransactionsInline();
    when(jdbc.queryForList(contains("select initialized"), eq(STATE_KEY)))
        .thenReturn(List.of(Map.of("initialized", false)));
    HrmsBootstrapCredentialInitializer initializer =
        new HrmsBootstrapCredentialInitializer(jdbc, transactions, "production", "",
            "replace-with-strong-random-secret-before-first-start");

    assertThrows(IllegalStateException.class, initializer::initializeProductionBootstrapCredential);
    verify(jdbc, never()).update(contains("update staff_account"), anyString());
    verify(jdbc, never()).update(contains("update auth_bootstrap_state"), anyString());
  }

  @Test
  void firstProductionStartHashesSecretAndMarksItInitialized() {
    String secret = "a-unique-bootstrap-secret-2026";
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    TransactionTemplate transactions = executeTransactionsInline();
    when(jdbc.queryForList(contains("select initialized"), eq(STATE_KEY)))
        .thenReturn(List.of(Map.of("initialized", false)));
    when(jdbc.update(contains("update staff_account"), anyString())).thenReturn(1);
    HrmsBootstrapCredentialInitializer initializer =
        new HrmsBootstrapCredentialInitializer(jdbc, transactions, "production", "", secret);

    initializer.initializeProductionBootstrapCredential();

    org.mockito.ArgumentCaptor<String> hash = org.mockito.ArgumentCaptor.forClass(String.class);
    verify(jdbc).update(contains("update staff_account"), hash.capture());
    assertTrue(new BCryptPasswordEncoder(12).matches(secret, hash.getValue()));
    verify(jdbc).update(contains("update auth_bootstrap_state"), eq(STATE_KEY));
  }

  @Test
  void laterProductionStartsDoNotRequireOrResetTheBootstrapPassword() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    TransactionTemplate transactions = executeTransactionsInline();
    when(jdbc.queryForList(contains("select initialized"), eq(STATE_KEY)))
        .thenReturn(List.of(Map.of("initialized", true)));
    HrmsBootstrapCredentialInitializer initializer =
        new HrmsBootstrapCredentialInitializer(jdbc, transactions, "production", "", "");

    initializer.initializeProductionBootstrapCredential();

    verify(jdbc, never()).update(contains("update staff_account"), anyString());
    verify(jdbc, never()).update(contains("update auth_bootstrap_state"), anyString());
  }

  @SuppressWarnings("unchecked")
  private TransactionTemplate executeTransactionsInline() {
    TransactionTemplate transactions = mock(TransactionTemplate.class);
    doAnswer(invocation -> {
      Consumer<TransactionStatus> action = invocation.getArgument(0);
      action.accept(mock(TransactionStatus.class));
      return null;
    }).when(transactions).executeWithoutResult(any());
    return transactions;
  }
}
