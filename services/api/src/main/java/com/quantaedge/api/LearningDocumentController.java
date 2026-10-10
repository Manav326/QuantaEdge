package com.quantaedge.api;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1")
public class LearningDocumentController {
  private static final long MAX_PDF_BYTES = 50L * 1024 * 1024;
  private static final int MAX_PDF_PAGES = 2000;
  private static final float PAGE_RENDER_DPI = 120f;

  private final JdbcTemplate jdbc;
  private final AuthorizationService authorization;
  private final StaffAuditService staffAudit;

  public LearningDocumentController(
      JdbcTemplate jdbc, AuthorizationService authorization, StaffAuditService staffAudit) {
    this.jdbc = jdbc;
    this.authorization = authorization;
    this.staffAudit = staffAudit;
  }

  /** Existing PDFs appear here so admins can attach one without uploading it again. */
  @GetMapping("/admin/learning-pdfs")
  public List<Map<String, Object>> listPdfLibrary(
      @RequestParam(required = false) String query,
      @RequestAttribute(value = "authContext", required = false) AuthContext context) {
    context = requireContentView(context);
    String term = query == null ? "" : query.trim();
    if (term.length() > 160) term = term.substring(0, 160);
    return jdbc.queryForList("""
      select a.id as pdf_asset_id,a.title,a.original_filename,a.sha256,
             a.file_size_bytes,a.page_count,a.source_kind,a.source_reference,a.created_at,
             a.created_by_staff_id,
             (select count(*) from learning_document d
              where d.pdf_asset_id=a.id and d.status<>'ARCHIVED') as assignment_count
      from learning_pdf_asset a
      where a.review_status='APPROVED'
        and (?='' or a.title ilike ? or a.original_filename ilike ?
             or coalesce(a.source_reference,'') ilike ?)
      order by a.created_at desc,a.id desc
      limit 300
      """, term, "%" + term + "%", "%" + term + "%", "%" + term + "%");
  }

  /** Stores a PDF in the private database library, de-duplicating by SHA-256. */
  @PostMapping(value = "/admin/learning-pdfs", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @Transactional
  public Map<String, Object> uploadPdf(
      @RequestPart("file") MultipartFile file,
      @RequestParam("title") String suppliedTitle,
      @RequestParam(value = "sourceReference", required = false) String sourceReference,
      @RequestParam(value = "sourceKind", defaultValue = "ADMIN_UPLOAD") String requestedSourceKind,
      @RequestAttribute(value = "authContext", required = false) AuthContext context) {
    context = authorization.requirePermission(context, "CONTENT_EDIT");
    String sourceKind = requiredText(requestedSourceKind, "sourceKind", 30).toUpperCase(Locale.ROOT);
    if (!List.of("ADMIN_UPLOAD", "EXTRACTION_IMPORT", "LEGACY_IMPORT").contains(sourceKind)) {
      throw badRequest("Choose a valid PDF source kind.");
    }
    if (!"ADMIN_UPLOAD".equals(sourceKind)) authorization.requirePermission(context, "CONTENT_REVIEW");
    if (file == null || file.isEmpty()) throw badRequest("Choose a PDF file to upload.");
    if (file.getSize() > MAX_PDF_BYTES) throw badRequest("PDF files must be 50 MB or smaller.");
    String title = requiredText(suppliedTitle, "PDF title", 240);
    String filename = safeFilename(file.getOriginalFilename());
    byte[] bytes;
    try {
      bytes = file.getBytes();
    } catch (IOException ex) {
      throw badRequest("The uploaded PDF could not be read. Please try again.");
    }
    validatePdf(bytes);
    String sha = sha256(bytes);
    List<Map<String, Object>> existing = jdbc.queryForList("""
      select id,title,original_filename,sha256,file_size_bytes,page_count,source_kind
      from learning_pdf_asset where sha256=?
      """, sha);
    if (!existing.isEmpty()) {
      Map<String, Object> result = new LinkedHashMap<>(existing.getFirst());
      result.put("pdf_asset_id", result.get("id"));
      result.put("duplicate", true);
      return result;
    }
    String normalizedReference = optionalText(sourceReference, 500);
    Long assetId = jdbc.queryForObject("""
      insert into learning_pdf_asset(
        title,original_filename,sha256,file_size_bytes,page_count,pdf_bytes,
        source_kind,source_reference,created_by_staff_id
      ) values(?,?,?,?,?,?,?,?,?)
      returning id
      """, Long.class, title, filename, sha, bytes.length, pdfPageCount(bytes), bytes,
      sourceKind, normalizedReference, context.staffId());
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("pdf_asset_id", assetId);
    result.put("title", title);
    result.put("original_filename", filename);
    result.put("sha256", sha);
    result.put("file_size_bytes", bytes.length);
    result.put("page_count", pdfPageCount(bytes));
    result.put("source_kind", sourceKind);
    result.put("duplicate", false);
    staffAudit.recordAction(context, "/api/v1/admin/learning-pdfs/" + assetId + "/uploaded",
        "Added a PDF to the private source library: " + filename + " (" + sha + ").");
    return result;
  }

  @GetMapping("/admin/learning-documents")
  public List<Map<String, Object>> listAssignments(
      @RequestParam(required = false) String classCode,
      @RequestParam(required = false) String subjectCode,
      @RequestParam(required = false) String status,
      @RequestAttribute(value = "authContext", required = false) AuthContext context) {
    requireContentView(context);
    String normalizedStatus = status == null || status.isBlank() ? "ALL" : status.trim().toUpperCase(Locale.ROOT);
    if (!List.of("ALL", "DRAFT", "PUBLISHED", "ARCHIVED").contains(normalizedStatus)) {
      throw badRequest("Choose a valid document status filter.");
    }
    String normalizedClass = classCode == null ? "" : classCode.trim();
    String normalizedSubject = subjectCode == null ? "" : subjectCode.trim();
    return jdbc.queryForList("""
      select d.id as document_id,d.pdf_asset_id,d.scope,d.title,d.source_title,d.source_url,d.edition,
             d.page_start,d.page_end,d.status,d.version_no,d.created_at,d.updated_at,d.published_at,
             a.original_filename,a.sha256,a.file_size_bytes,a.page_count,
             c.code as class_code,c.display_name as class_name,
             s.code as subject_code,s.display_name as subject_name,
             ch.id as chapter_id,ch.code as chapter_code,ch.display_name as chapter_name
      from learning_document d
      join learning_pdf_asset a on a.id=d.pdf_asset_id
      join curriculum_subject s on s.id=d.subject_id
      join curriculum_class c on c.id=s.class_id
      left join curriculum_chapter ch on ch.id=d.chapter_id
      where (?='' or c.code=?)
        and (?='' or s.code=?)
        and (?='ALL' or d.status=?)
      order by c.sort_order,s.sort_order,coalesce(ch.teaching_order,ch.sort_order),d.scope,d.created_at desc,d.id desc
      limit 500
      """, normalizedClass, normalizedClass, normalizedSubject, normalizedSubject,
      normalizedStatus, normalizedStatus);
  }

  /** Assigns a library PDF to a complete subject book or one chapter; it does not copy the PDF bytes. */
  @PostMapping("/admin/learning-documents")
  @Transactional
  public Map<String, Object> createAssignment(
      @RequestBody Map<String, Object> body,
      @RequestAttribute(value = "authContext", required = false) AuthContext context) {
    context = authorization.requirePermission(context, "CONTENT_EDIT");
    long assetId = longValue(body.get("pdfAssetId"), "pdfAssetId");
    String scope = requiredText(body.get("scope"), "scope", 24).toUpperCase(Locale.ROOT);
    if (!List.of("SUBJECT_BOOK", "CHAPTER_PDF").contains(scope)) {
      throw badRequest("Resource scope must be SUBJECT_BOOK or CHAPTER_PDF.");
    }
    String classCode = requiredText(body.get("classCode"), "classCode", 30);
    String subjectCode = requiredText(body.get("subjectCode"), "subjectCode", 40);
    Long subjectId = jdbc.query("""
      select s.id from curriculum_subject s
      join curriculum_class c on c.id=s.class_id
      where c.code=? and s.code=? and c.active=true and s.active=true
      """, rs -> rs.next() ? rs.getLong(1) : null, classCode, subjectCode);
    if (subjectId == null) throw badRequest("The selected active class and subject do not exist.");
    Long chapterId = optionalLong(body.get("chapterId"), "chapterId");
    if ("CHAPTER_PDF".equals(scope)) {
      if (chapterId == null) throw badRequest("Choose a chapter for a chapter PDF.");
      Long chapterCount = jdbc.queryForObject("""
        select count(*) from curriculum_chapter
        where id=? and subject_id=? and active=true
        """, Long.class, chapterId, subjectId);
      if (chapterCount == null || chapterCount == 0) {
        throw badRequest("The selected chapter does not belong to the selected active subject.");
      }
    } else if (chapterId != null) {
      throw badRequest("A complete subject book must not be assigned to a single chapter.");
    }

    List<Map<String, Object>> assets = jdbc.queryForList("""
      select id,page_count,title from learning_pdf_asset where id=? and review_status='APPROVED'
      """, assetId);
    if (assets.isEmpty()) throw notFound("PDF library item", assetId);
    int sourcePageCount = ((Number) assets.getFirst().get("page_count")).intValue();
    int start = body.containsKey("pageStart") ? integerValue(body.get("pageStart"), "pageStart", 1, sourcePageCount) : 1;
    int end = body.containsKey("pageEnd") ? integerValue(body.get("pageEnd"), "pageEnd", start, sourcePageCount) : sourcePageCount;
    if (end < start) throw badRequest("The ending PDF page must be equal to or after the starting page.");
    String title = optionalText(body.get("title"), 240);
    if (title == null) title = String.valueOf(assets.getFirst().get("title"));
    String sourceTitle = optionalText(body.get("sourceTitle"), 300);
    String sourceUrl = optionalText(body.get("sourceUrl"), 2000);
    String edition = optionalText(body.get("edition"), 160);

    Long documentId = jdbc.queryForObject("""
      insert into learning_document(
        pdf_asset_id,scope,subject_id,chapter_id,title,source_title,source_url,edition,
        page_start,page_end,status,version_no,created_by_staff_id
      ) values(?,?,?,?,?,?,?,?,?,?,'DRAFT',1,?)
      returning id
      """, Long.class, assetId, scope, subjectId, chapterId, title, sourceTitle, sourceUrl,
      edition, start, end, context.staffId());
    staffAudit.recordAction(context, "/api/v1/admin/learning-documents/" + documentId + "/created",
        "Attached a private PDF to " + scope + " for class " + classCode + ", subject " + subjectCode
            + (chapterId == null ? "" : ", chapter " + chapterId) + ".");
    return assignmentById(documentId);
  }

  @PatchMapping("/admin/learning-documents/{documentId}/status")
  @Transactional
  public Map<String, Object> setAssignmentStatus(
      @PathVariable long documentId,
      @RequestBody Map<String, Object> body,
      @RequestAttribute(value = "authContext", required = false) AuthContext context) {
    context = authorization.requireAuth(context);
    String nextStatus = requiredText(body.get("status"), "status", 20).toUpperCase(Locale.ROOT);
    if (!List.of("DRAFT", "PUBLISHED", "ARCHIVED").contains(nextStatus)) {
      throw badRequest("Choose DRAFT, PUBLISHED, or ARCHIVED.");
    }
    if ("PUBLISHED".equals(nextStatus) || "ARCHIVED".equals(nextStatus)) {
      authorization.requirePermission(context, "CONTENT_PUBLISH");
    } else {
      authorization.requirePermission(context, "CONTENT_EDIT");
    }
    List<Map<String, Object>> rows = jdbc.queryForList("""
      select d.id,d.scope,d.subject_id,d.chapter_id,d.status,d.source_title,d.source_url,d.edition,
             d.title,d.page_start,d.page_end,a.page_count,a.id as asset_id
      from learning_document d join learning_pdf_asset a on a.id=d.pdf_asset_id
      where d.id=?
      """, documentId);
    if (rows.isEmpty()) throw notFound("Learning document", documentId);
    Map<String, Object> current = rows.getFirst();
    if ("PUBLISHED".equals(nextStatus)) {
      if (!hasText(current.get("source_title")) || !hasText(current.get("edition"))
          || !hasText(current.get("source_url")) || !String.valueOf(current.get("source_url")).startsWith("https://")) {
        throw badRequest("Add the official HTTPS source URL, source title, and edition before publishing this PDF.");
      }
      int actualPageCount = ((Number) current.get("page_count")).intValue();
      int start = ((Number) current.get("page_start")).intValue();
      int end = ((Number) current.get("page_end")).intValue();
      if (start < 1 || end < start || end > actualPageCount) {
        throw badRequest("The assigned PDF page range is invalid.");
      }
      jdbc.update("""
        update learning_document set status='ARCHIVED',updated_at=now()
        where scope=? and subject_id=? and (chapter_id=? or (chapter_id is null and ? is null))
          and status='PUBLISHED' and id<>?
        """, current.get("scope"), current.get("subject_id"), current.get("chapter_id"),
        current.get("chapter_id"), documentId);
      jdbc.update("""
        update learning_document set status='PUBLISHED',published_by_staff_id=?,published_at=now(),updated_at=now()
        where id=?
        """, context.staffId(), documentId);
    } else {
      jdbc.update("""
        update learning_document set status=?,updated_at=now(),
          published_by_staff_id=case when ?='DRAFT' then null else published_by_staff_id end,
          published_at=case when ?='DRAFT' then null else published_at end
        where id=?
        """, nextStatus, nextStatus, nextStatus, documentId);
    }
    staffAudit.recordAction(context, "/api/v1/admin/learning-documents/" + documentId + "/status",
        "Changed learning document status to " + nextStatus + ".");
    return assignmentById(documentId);
  }

  @GetMapping("/learning/documents")
  public List<Map<String, Object>> studentDocumentCatalog(
      @RequestParam(required = false) String subjectCode,
      @RequestAttribute(value = "authContext", required = false) AuthContext context) {
    context = authorization.requireStudent(context);
    String subjectFilter = subjectCode == null ? "" : subjectCode.trim();
    return jdbc.queryForList("""
      select d.id as document_id,d.scope,d.title,d.edition,d.page_start,d.page_end,
             a.page_count as source_page_count,
             d.page_end-d.page_start+1 as page_count,
             s.code as subject_code,s.display_name as subject_name,
             c.code as class_code,
             ch.id as chapter_id,ch.code as chapter_code,ch.display_name as chapter_name,
             coalesce(p.last_page,1) as last_page,p.last_opened_at
      from learning_document d
      join learning_pdf_asset a on a.id=d.pdf_asset_id
      join curriculum_subject s on s.id=d.subject_id and s.active=true
      join curriculum_class c on c.id=s.class_id and c.active=true
      join student st on st.id=? and st.active=true and st.class_code=c.code
      join student_track_enrollment ste on ste.student_id=st.id and ste.subject_id=s.id and ste.status='ACTIVE'
      left join curriculum_chapter ch on ch.id=d.chapter_id
      left join student_learning_document_progress p
        on p.learning_document_id=d.id and p.student_id=st.id
      where d.status='PUBLISHED'
        and (?='' or s.code=?)
        and (d.scope='SUBJECT_BOOK' or (ch.id is not null and ch.active=true and ch.content_status='PUBLISHED'))
      order by s.sort_order,case when d.scope='SUBJECT_BOOK' then 0 else 1 end,
               coalesce(ch.teaching_order,ch.sort_order),ch.display_name,d.id
      """, context.studentId(), subjectFilter, subjectFilter);
  }

  @GetMapping("/learning/documents/{documentId}")
  public Map<String, Object> studentDocument(
      @PathVariable long documentId,
      @RequestAttribute(value = "authContext", required = false) AuthContext context) {
    context = authorization.requireStudent(context);
    Map<String, Object> document = readableDocument(documentId, context.studentId());
    int lastPage = 1;
    List<Map<String, Object>> progress = jdbc.queryForList("""
      select last_page from student_learning_document_progress
      where student_id=? and learning_document_id=?
      """, context.studentId(), documentId);
    if (!progress.isEmpty()) lastPage = ((Number) progress.getFirst().get("last_page")).intValue();
    document.remove("pdf_asset_id");
    document.put("last_page", lastPage);
    return document;
  }

  /** Delivers a single rendered PNG page, never the source PDF bytes. */
  @GetMapping(value = "/learning/documents/{documentId}/pages/{pageNumber}", produces = MediaType.IMAGE_PNG_VALUE)
  public ResponseEntity<byte[]> studentDocumentPage(
      @PathVariable long documentId,
      @PathVariable int pageNumber,
      @RequestAttribute(value = "authContext", required = false) AuthContext context) {
    context = authorization.requireStudent(context);
    Map<String, Object> metadata = readableDocument(documentId, context.studentId());
    int start = ((Number) metadata.get("page_start")).intValue();
    int end = ((Number) metadata.get("page_end")).intValue();
    int visibleCount = end - start + 1;
    if (pageNumber < 1 || pageNumber > visibleCount) throw notFound("Document page", pageNumber);
    long assetId = ((Number) metadata.get("pdf_asset_id")).longValue();
    byte[] pdfBytes = jdbc.queryForObject("select pdf_bytes from learning_pdf_asset where id=?", byte[].class, assetId);
    if (pdfBytes == null || pdfBytes.length == 0) throw notFound("PDF content", assetId);
    byte[] rendered;
    try (PDDocument pdf = Loader.loadPDF(pdfBytes); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      int sourceIndex = start + pageNumber - 2;
      if (sourceIndex < 0 || sourceIndex >= pdf.getNumberOfPages()) throw notFound("PDF page", pageNumber);
      BufferedImage image = new PDFRenderer(pdf).renderImageWithDPI(sourceIndex, PAGE_RENDER_DPI, ImageType.RGB);
      if (!javax.imageio.ImageIO.write(image, "png", output)) {
        throw new IllegalStateException("PNG rendering is not available.");
      }
      image.flush();
      rendered = output.toByteArray();
    } catch (IOException ex) {
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "This PDF page could not be rendered.", ex);
    }
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.IMAGE_PNG);
    headers.setContentDisposition(ContentDisposition.inline().filename("page-" + pageNumber + ".png", StandardCharsets.UTF_8).build());
    headers.setCacheControl(CacheControl.noStore());
    headers.setPragma("no-cache");
    headers.set("X-Content-Type-Options", "nosniff");
    headers.set("X-Robots-Tag", "noindex, noarchive");
    return new ResponseEntity<>(rendered, headers, HttpStatus.OK);
  }

  @PutMapping("/learning/documents/{documentId}/progress")
  @Transactional
  public Map<String, Object> saveDocumentProgress(
      @PathVariable long documentId,
      @RequestBody Map<String, Object> body,
      @RequestAttribute(value = "authContext", required = false) AuthContext context) {
    context = authorization.requireStudent(context);
    Map<String, Object> document = readableDocument(documentId, context.studentId());
    int pageCount = ((Number) document.get("page_count")).intValue();
    int lastPage = integerValue(body.get("lastPage"), "lastPage", 1, pageCount);
    jdbc.update("""
      insert into student_learning_document_progress(student_id,learning_document_id,last_page,last_opened_at,updated_at)
      values(?,?,?,now(),now())
      on conflict(student_id,learning_document_id) do update set
        last_page=excluded.last_page,last_opened_at=now(),updated_at=now()
      """, context.studentId(), documentId, lastPage);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("documentId", documentId);
    result.put("lastPage", lastPage);
    result.put("pageCount", pageCount);
    return result;
  }

  private Map<String, Object> readableDocument(long documentId, long studentId) {
    List<Map<String, Object>> rows = jdbc.queryForList("""
      select d.id as document_id,d.pdf_asset_id,d.scope,d.title,d.edition,
             d.page_start,d.page_end,d.page_end-d.page_start+1 as page_count,
             s.code as subject_code,s.display_name as subject_name,
             c.code as class_code,ch.id as chapter_id,ch.code as chapter_code,ch.display_name as chapter_name
      from learning_document d
      join learning_pdf_asset a on a.id=d.pdf_asset_id
      join curriculum_subject s on s.id=d.subject_id and s.active=true
      join curriculum_class c on c.id=s.class_id and c.active=true
      join student st on st.id=? and st.active=true and st.class_code=c.code
      join student_track_enrollment ste on ste.student_id=st.id and ste.subject_id=s.id and ste.status='ACTIVE'
      left join curriculum_chapter ch on ch.id=d.chapter_id
      where d.id=? and d.status='PUBLISHED'
        and (d.scope='SUBJECT_BOOK' or (ch.id is not null and ch.active=true and ch.content_status='PUBLISHED'))
      """, studentId, documentId);
    if (rows.isEmpty()) throw notFound("Learning document", documentId);
    return new LinkedHashMap<>(rows.getFirst());
  }

  private Map<String, Object> assignmentById(long documentId) {
    List<Map<String, Object>> rows = jdbc.queryForList("""
      select d.id as document_id,d.pdf_asset_id,d.scope,d.title,d.source_title,d.source_url,d.edition,
             d.page_start,d.page_end,d.status,d.version_no,d.created_at,d.updated_at,d.published_at,
             a.original_filename,a.sha256,a.file_size_bytes,a.page_count,
             c.code as class_code,c.display_name as class_name,
             s.code as subject_code,s.display_name as subject_name,
             ch.id as chapter_id,ch.code as chapter_code,ch.display_name as chapter_name
      from learning_document d join learning_pdf_asset a on a.id=d.pdf_asset_id
      join curriculum_subject s on s.id=d.subject_id
      join curriculum_class c on c.id=s.class_id
      left join curriculum_chapter ch on ch.id=d.chapter_id
      where d.id=?
      """, documentId);
    if (rows.isEmpty()) throw notFound("Learning document", documentId);
    return new LinkedHashMap<>(rows.getFirst());
  }

  private AuthContext requireContentView(AuthContext context) {
    context = authorization.requireAuth(context);
    if (!authorization.hasPermission(context, "CONTENT_VIEW")
        && !authorization.hasPermission(context, "CONTENT_EDIT")) {
      throw new SecurityException("Content view permission required.");
    }
    return context;
  }

  private String safeFilename(String original) {
    if (original == null || original.isBlank()) return "textbook.pdf";
    String name = original.replace('\\', '/');
    name = name.substring(name.lastIndexOf('/') + 1).trim();
    name = name.replaceAll("[^A-Za-z0-9._() -]", "_");
    if (name.isBlank()) name = "textbook.pdf";
    if (!name.toLowerCase(Locale.ROOT).endsWith(".pdf")) name += ".pdf";
    return name.length() > 255 ? name.substring(name.length() - 255) : name;
  }

  private void validatePdf(byte[] bytes) {
    if (bytes.length < 5 || !new String(bytes, 0, 5, StandardCharsets.US_ASCII).equals("%PDF-")) {
      throw badRequest("The uploaded file is not a valid PDF.");
    }
    try (PDDocument document = Loader.loadPDF(bytes)) {
      if (document.isEncrypted()) throw badRequest("Password-protected PDFs are not supported.");
      int pages = document.getNumberOfPages();
      if (pages < 1 || pages > MAX_PDF_PAGES) {
        throw badRequest("PDFs must contain between 1 and " + MAX_PDF_PAGES + " pages.");
      }
      new PDFRenderer(document).renderImageWithDPI(0, 36f, ImageType.RGB).flush();
    } catch (IOException ex) {
      throw badRequest("The uploaded file could not be opened as a PDF.");
    }
  }

  private int pdfPageCount(byte[] bytes) {
    try (PDDocument document = Loader.loadPDF(bytes)) {
      return document.getNumberOfPages();
    } catch (IOException ex) {
      throw badRequest("The uploaded file could not be opened as a PDF.");
    }
  }

  private String sha256(byte[] value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 is not available.", ex);
    }
  }

  private String requiredText(Object value, String name, int max) {
    String result = value == null ? "" : String.valueOf(value).trim();
    if (result.isEmpty() || result.length() > max) {
      throw badRequest(name + " is required and must be " + max + " characters or fewer.");
    }
    return result;
  }

  private String optionalText(Object value, int max) {
    if (value == null || String.valueOf(value).isBlank()) return null;
    String result = String.valueOf(value).trim();
    if (result.length() > max) throw badRequest("A document field is too long.");
    return result;
  }

  private boolean hasText(Object value) {
    return value != null && !String.valueOf(value).isBlank();
  }

  private Long optionalLong(Object value, String name) {
    if (value == null || String.valueOf(value).isBlank()) return null;
    return longValue(value, name);
  }

  private long longValue(Object value, String name) {
    try {
      long number = value instanceof Number numberValue
          ? numberValue.longValue() : Long.parseLong(String.valueOf(value).trim());
      if (number <= 0) throw badRequest(name + " must be a positive integer.");
      return number;
    } catch (NumberFormatException ex) {
      throw badRequest(name + " must be a valid integer.");
    }
  }

  private int integerValue(Object value, String name, int min, int max) {
    try {
      int number = value instanceof Number numberValue
          ? numberValue.intValue() : Integer.parseInt(String.valueOf(value).trim());
      if (number < min || number > max) throw badRequest(name + " must be between " + min + " and " + max + ".");
      return number;
    } catch (NumberFormatException ex) {
      throw badRequest(name + " must be a valid integer.");
    }
  }

  private ResponseStatusException badRequest(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  private ResponseStatusException notFound(String type, long id) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, type + " not found: " + id);
  }
}
