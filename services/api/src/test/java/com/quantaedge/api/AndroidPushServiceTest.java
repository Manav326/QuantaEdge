package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class AndroidPushServiceTest {
  @Test
  void missingProviderCredentialsLeaveInAppNotificationsAvailableAndDisablePushSafely() {
    JdbcTemplate jdbc=mock(JdbcTemplate.class);
    AndroidPushService push=new AndroidPushService(jdbc,new ObjectMapper(),"","");
    assertFalse(push.isConfigured());
    assertEquals(0,push.dispatchPendingDeliveries());
    verifyNoInteractions(jdbc);
  }
  @Test
  void platformCampaignUsesFrozenInstitutionTargetsInsteadOfTheCurrentDeviceTenant() {
    AndroidPushService push=new AndroidPushService(mock(JdbcTemplate.class),new ObjectMapper(),"","");
    Map<String,Object> campaign=Map.of(
        "audience_snapshot_json","{\"institutionIds\":[101,202,303]}");
    assertEquals(List.of(101L,202L,303L),push.eligibilityInstitutionIds(campaign,null));
    assertEquals(List.of(202L),push.eligibilityInstitutionIds(campaign,202L));
  }
}
