package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class AndroidPushServiceTest {
  @Test
  void missingProviderCredentialsLeaveInAppNotificationsAvailableAndDisablePushSafely() {
    JdbcTemplate jdbc=mock(JdbcTemplate.class);
    AndroidPushService push=new AndroidPushService(jdbc,"","");
    assertFalse(push.isConfigured());
    assertEquals(0,push.dispatchPendingDeliveries());
    verifyNoInteractions(jdbc);
  }
}
