package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
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

    List<Map<String, Object>> rows = controller.studentDocumentCatalog(null, "hi", student);

    assertTrue(rows.isEmpty());
    verify(jdbc).queryForList(
        contains("d.status='PUBLISHED'"), eq(7L), eq("hi"), eq("hi"), eq(""), eq(""));
    verify(jdbc).queryForList(
        contains("ste.status='ACTIVE'"), eq(7L), eq("hi"), eq("hi"), eq(""), eq(""));
    verify(jdbc).queryForList(
        contains("(ch.id is not null and ch.active=true)"), eq(7L), eq("hi"), eq("hi"), eq(""), eq(""));
  }

  @Test
  void englishStudentCatalogFiltersToEnglishAssignments() {
    when(authorization.requireStudent(student)).thenReturn(student);
    when(jdbc.queryForList(contains("from learning_document d"), any(Object[].class)))
        .thenReturn(List.of());

    assertTrue(controller.studentDocumentCatalog("science", "english", student).isEmpty());

    verify(jdbc).queryForList(contains("d.language=?"), eq(7L), eq("en"), eq("en"), eq("science"), eq("science"));
  }

  @Test
  void englishChapterMetadataCanBeUsedWhileTheAvailablePdfIsHindi() {
    when(authorization.requireStudent(student)).thenReturn(student);
    when(jdbc.queryForList(contains("from learning_document d"), any(Object[].class)))
        .thenReturn(List.of());

    assertTrue(controller.studentDocumentCatalog("science", "hi", "en", student).isEmpty());

    verify(jdbc).queryForList(contains("d.language=?"), eq(7L), eq("en"), eq("hi"), eq("science"), eq("science"));
    verify(jdbc).queryForList(contains("coalesce(ct.display_name,ch.display_name) as chapter_name"),
        eq(7L), eq("en"), eq("hi"), eq("science"), eq("science"));
  }
  @Test
  void studentCatalogRejectsUnsupportedLanguageBeforeQueryingDocuments() {
    when(authorization.requireStudent(student)).thenReturn(student);

    ResponseStatusException error = assertThrows(ResponseStatusException.class,
        () -> controller.studentDocumentCatalog(null, "fr", student));

    assertEquals(400, error.getStatusCode().value());
    verifyNoInteractions(jdbc);
  }

  @Test
  void outOfScopeStudentCannotCausePdfBytesToBeRead() {
    when(authorization.requireStudent(student)).thenReturn(student);
    when(jdbc.queryForList(contains("from learning_document d"), eq(7L), eq(55L)))
        .thenReturn(List.of());

    ResponseStatusException error = assertThrows(ResponseStatusException.class,
        () -> controller.studentDocumentPage(55L, 1, "full", student));

    assertEquals(404, error.getStatusCode().value());
    verify(jdbc, never()).queryForObject(
        contains("select pdf_bytes from learning_pdf_asset"), eq(byte[].class), anyLong());
  }

  @Test
  void activeChapterPdfCanBePublishedBeforeLessonContentIsPublished() {
    when(authorization.requireAuth(author)).thenReturn(author);
    when(authorization.requirePermission(author, "CONTENT_PUBLISH")).thenReturn(author);
    when(jdbc.queryForList(contains("from learning_document d"), eq(88L))).thenReturn(List.of(
        Map.ofEntries(
            Map.entry("id", 88L), Map.entry("document_id", 88L), Map.entry("scope", "CHAPTER_PDF"), Map.entry("subject_id", 3L),
            Map.entry("chapter_id", 9L), Map.entry("status", "DRAFT"),
            Map.entry("source_title", "Textbook"), Map.entry("source_url", "https://example.org/book.pdf"),
            Map.entry("edition", "2026"), Map.entry("language", "en"), Map.entry("title", "Chapter 1"),
            Map.entry("page_start", 1), Map.entry("page_end", 3), Map.entry("page_count", 3),
            Map.entry("asset_id", 10L), Map.entry("asset_review_status", "APPROVED"),
            Map.entry("chapter_active", true), Map.entry("chapter_content_status", "DRAFT")
        )
    ));

    Map<String, Object> result = controller.setAssignmentStatus(88L, Map.of("status", "PUBLISHED"), author);

    assertEquals(88L, result.get("document_id"));
    verify(jdbc).update(
        contains("where scope=? and subject_id=? and language=?"),
        eq("CHAPTER_PDF"), eq(3L), eq("en"), eq(9L), eq(88L));
  }

  @Test
  void publishingOneMathsPartDoesNotArchiveTheOtherPart() {
    when(authorization.requireAuth(author)).thenReturn(author);
    when(authorization.requirePermission(author, "CONTENT_PUBLISH")).thenReturn(author);
    when(jdbc.queryForList(contains("from learning_document d"), eq(88L))).thenReturn(List.of(
        Map.ofEntries(
            Map.entry("id", 88L), Map.entry("document_id", 88L),
            Map.entry("scope", "SUBJECT_BOOK"), Map.entry("subject_id", 3L),
            Map.entry("book_part", 1), Map.entry("status", "DRAFT"),
            Map.entry("source_title", "Class 7 Mathematics — Part I"),
            Map.entry("source_url", "https://ncert.nic.in/textbook.php"),
            Map.entry("edition", "Reprint 2026-27"), Map.entry("language", "en"),
            Map.entry("title", "NCERT | Class 7 | Mathematics | English | Part I"),
            Map.entry("page_start", 1), Map.entry("page_end", 10), Map.entry("page_count", 10),
            Map.entry("asset_id", 10L), Map.entry("asset_review_status", "APPROVED")
        )
    ));

    Map<String, Object> result = controller.setAssignmentStatus(88L, Map.of("status", "PUBLISHED"), author);

    assertEquals(88L, result.get("document_id"));
    verify(jdbc).update(
        contains("and (book_part is null or book_part=?)"),
        eq("SUBJECT_BOOK"), eq(3L), eq("en"), isNull(), eq(88L), eq(1));
  }

  @Test
  void dedicatedPublisherCanListResourceAssignments() {
    when(authorization.requireAuth(author)).thenReturn(author);
    when(authorization.hasPermission(author, "CONTENT_VIEW")).thenReturn(false);
    when(authorization.hasPermission(author, "CONTENT_EDIT")).thenReturn(false);
    when(authorization.hasPermission(author, "CONTENT_PUBLISH")).thenReturn(true);
    when(jdbc.queryForList(contains("from learning_document d"), any(Object[].class))).thenReturn(List.of());

    Map<String,Object> response=controller.listAssignments(null,null,null,null,null,null,null,null,null,25,0,author);
    assertTrue(((List<?>)response.get("items")).isEmpty());
  }

  @Test
  void pdfLibraryListRequiresContentViewOrEditPermission() {
    when(authorization.requireAuth(null)).thenThrow(new SecurityException("Authentication required"));

    assertThrows(SecurityException.class, () -> controller.listPdfLibrary(null, null, null, null));
    verifyNoInteractions(jdbc);
  }

  @Test
  void studentPageDeliveryReusesSourcePdfBytesAndKeepsThumbnailQualitySeparate() throws Exception {
    byte[] sourcePdf;
    try (PDDocument pdf = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      pdf.addPage(new PDPage());
      pdf.addPage(new PDPage());
      pdf.save(output);
      sourcePdf = output.toByteArray();
    }

    when(authorization.requireStudent(student)).thenReturn(student);
    Map<String, Object> metadata = Map.of(
        "document_id", 88L,
        "pdf_asset_id", 12L,
        "asset_sha256", "ab".repeat(32),
        "asset_file_size_bytes", 1024L,
        "page_start", 1,
        "page_end", 2,
        "page_count", 2);
    when(jdbc.queryForList(
        contains("where d.id=? and d.status='PUBLISHED'"), eq(7L), eq(88L)))
        .thenReturn(List.of(metadata));
    when(jdbc.queryForObject(
        contains("select pdf_bytes from learning_pdf_asset where id=?"),
        eq(byte[].class), eq(12L))).thenReturn(sourcePdf);

    var pageOne = controller.studentDocumentPage(88L, 1, "full", student);
    var pageTwo = controller.studentDocumentPage(88L, 2, "full", student);
    var thumbnail = controller.studentDocumentPage(88L, 2, "thumbnail", student);

    assertEquals(200, pageOne.getStatusCode().value());
    assertEquals(200, pageTwo.getStatusCode().value());
    assertEquals(200, thumbnail.getStatusCode().value());
    assertEquals("image/png", pageOne.getHeaders().getContentType().toString());
    assertEquals("no-store", pageOne.getHeaders().getCacheControl());
    assertTrue(pageOne.getBody().length > 8);
    assertTrue(thumbnail.getBody().length > 8);
    // Navigating to the next page and generating a thumbnail should not repeatedly fetch the same PDF.
    verify(jdbc, times(1)).queryForObject(
        contains("select pdf_bytes from learning_pdf_asset where id=?"),
        eq(byte[].class), eq(12L));
  }

}
