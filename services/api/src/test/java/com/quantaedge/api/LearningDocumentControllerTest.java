package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class LearningDocumentControllerTest {
  @Mock private JdbcTemplate jdbc;
  @Mock private AuthorizationService authorization;
  @Mock private StaffAuditService staffAudit;

  private LearningDocumentController controller;
  private final AuthContext author = new AuthContext(1L, null, 9L, "CONTENT_AUTHOR", "Author");
  private final AuthContext student = new AuthContext(2L, 7L, "STUDENT", "Student");

  @BeforeEach
  void setUp() {
    controller = new LearningDocumentController(jdbc, authorization, staffAudit);
  }

  @Test
  void invalidPdfIsRejectedBeforeDatabaseWrite() {
    when(authorization.requirePermission(author, "CONTENT_EDIT")).thenReturn(author);
    MockMultipartFile file = new MockMultipartFile(
        "file", "textbook.pdf", "application/pdf", "this is not a PDF".getBytes());

    ResponseStatusException error = assertThrows(ResponseStatusException.class,
        () -> controller.uploadPdf(file, "Class book", null, "ADMIN_UPLOAD", author));

    assertEquals(400, error.getStatusCode().value());
    verifyNoInteractions(jdbc, staffAudit);
  }

  @Test
  void studentCatalogOnlyUsesPublishedDocumentsAndActiveEnrolment() {
    when(authorization.requireStudent(student)).thenReturn(student);
    when(jdbc.queryForList(contains("from learning_document d"), any(Object[].class)))
        .thenReturn(List.of());

    List<Map<String, Object>> rows = controller.studentDocumentCatalog(null, student);

    assertTrue(rows.isEmpty());
    verify(jdbc).queryForList(
        contains("d.status='PUBLISHED'"), eq(7L), eq(""), eq(""));
    verify(jdbc).queryForList(
        contains("ste.status='ACTIVE'"), eq(7L), eq(""), eq(""));
  }

  @Test
  void outOfScopeStudentCannotCausePdfBytesToBeRead() {
    when(authorization.requireStudent(student)).thenReturn(student);
    when(jdbc.queryForList(contains("from learning_document d"), eq(7L), eq(55L)))
        .thenReturn(List.of());

    ResponseStatusException error = assertThrows(ResponseStatusException.class,
        () -> controller.studentDocumentPage(55L, 1, student));

    assertEquals(404, error.getStatusCode().value());
    verify(jdbc, never()).queryForObject(
        contains("select pdf_bytes from learning_pdf_asset"), eq(byte[].class), anyLong());
  }

  @Test
  void pdfLibraryListRequiresContentViewOrEditPermission() {
    when(authorization.requireAuth(null)).thenThrow(new SecurityException("Authentication required"));

    assertThrows(SecurityException.class, () -> controller.listPdfLibrary(null, null));
    verifyNoInteractions(jdbc);
  }
}
