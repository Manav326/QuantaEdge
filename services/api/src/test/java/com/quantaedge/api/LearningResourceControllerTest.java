package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class LearningResourceControllerTest {
  @Mock private JdbcTemplate jdbc;
  @Mock private AuthorizationService authorization;
  @Mock private StaffAuditService staffAudit;
  @Mock private PrivateLearningResourceStorageService resourceStorage;
  @Mock private PrivateMediaStorageService recordingStorage;
  @Mock private StructuredLessonArtifactService artifacts;

  private final AuthContext admin=new AuthContext(101L,null,99L,"ADMIN","Administrator");
  private LearningResourceController controller;

  @BeforeEach
  void setUp(){controller=new LearningResourceController(jdbc,authorization,staffAudit,resourceStorage,recordingStorage,artifacts);}

  @Test
  void externalResourcesMustUseHttpsAndCannotContainEmbeddedCredentials() {
    when(authorization.requirePermission(admin,"CONTENT_EDIT")).thenReturn(admin);
    ResponseStatusException error=assertThrows(ResponseStatusException.class,()->controller.createResource(
      Map.of("title","External study link","resourceType","EXTERNAL_LINK","sourceKind","EXTERNAL_URL","externalUrl","http://example.org/resource"),admin));
    assertEquals(400,error.getStatusCode().value());
    verifyNoInteractions(jdbc,staffAudit,resourceStorage,recordingStorage,artifacts);
  }

  @Test
  void externalResourcesCannotUseLocalHostsEvenWithHttps() {
    when(authorization.requirePermission(admin,"CONTENT_EDIT")).thenReturn(admin);
    ResponseStatusException error=assertThrows(ResponseStatusException.class,()->controller.createResource(
      Map.of("title","Private local link","resourceType","EXTERNAL_LINK","sourceKind","EXTERNAL_URL","externalUrl","https://localhost/resource"),admin));
    assertEquals(400,error.getStatusCode().value());
    verifyNoInteractions(jdbc,staffAudit,resourceStorage,recordingStorage,artifacts);
  }

  @Test
  void privateDownloadPermissionRequiresContentPublishPermission() {
    when(authorization.requirePermission(admin,"CONTENT_EDIT")).thenReturn(admin);
    when(authorization.requirePermission(admin,"CONTENT_PUBLISH")).thenThrow(new SecurityException("publish required"));
    assertThrows(SecurityException.class,()->controller.createResource(
      Map.of("title","Restricted video","resourceType","VIDEO","sourceKind","RECORDED_CLASS","recordedClassId",55,"downloadAllowed",true),admin));
    verifyNoInteractions(jdbc,staffAudit,resourceStorage,recordingStorage,artifacts);
  }
}
