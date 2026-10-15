package com.quantaedge.api;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
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
  private static final long STANDARD_MAX_PDF_BYTES = 50L * 1024 * 1024;
  // Align with application.yml spring.servlet.multipart.max-file-size (500MB).
  // Admins may upload larger official textbook PDFs; the framework still enforces the
  // configured multipart/request ceilings before this controller is invoked.
  private static final long ADMIN_MAX_PDF_BYTES = 500L * 1024 * 1024;
  private static final int MAX_PDF_PAGES = 2000;
  private static final float PAGE_RENDER_DPI = 120f;
  private static final float THUMBNAIL_RENDER_DPI = 42f;
  private static final long MAX_RENDER_CACHE_BYTES = 32L * 1024 * 1024;
  private static final int MAX_RENDER_CACHE_ENTRIES = 128;
  private static final int MAX_CACHED_PAGE_BYTES = 4 * 1024 * 1024;
  private static final long MAX_CACHED_SOURCE_PDF_BYTES = 32L * 1024 * 1024;
  private static final long MAX_SOURCE_PDF_CACHE_BYTES = 64L * 1024 * 1024;
  private static final int MAX_SOURCE_PDF_CACHE_ENTRIES = 4;
  private static final long RENDER_CACHE_TTL_MILLIS = 15 * 60 * 1000L;
  private static final long MAX_RENDERED_PAGE_PIXELS = 25_000_000L;
  private static final double MAX_RENDERED_PAGE_DIMENSION = 14_000d;
  private static final long RENDER_PERMIT_WAIT_SECONDS = 2L;

  private final Object renderCacheMonitor = new Object();
  private final LinkedHashMap<PdfPageCacheKey, CachedPdfPage> renderedPageCache =
      new LinkedHashMap<>(16, 0.75f, true);
  private long renderedPageCacheBytes;
  private final LinkedHashMap<String, CachedSourcePdf> sourcePdfCache =
      new LinkedHashMap<>(8, 0.75f, true);
  private long sourcePdfCacheBytes;
  // Bound concurrent PDFBox work so a burst cannot create unbounded image/PDF allocations.
  private final Semaphore renderPermits = new Semaphore(2);

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
  public Map<String,Object> listPdfLibrary(
      @RequestParam(required = false) String query,
      @RequestParam(defaultValue="25") Integer limit,
      @RequestParam(defaultValue="0") Integer offset,
      @RequestAttribute(value = "authContext", required = false) AuthContext context) {
    context=requireContentView(context);
    String term=query==null?"":query.trim();
    if(term.length()>160)term=term.substring(0,160);
    int rowLimit=Math.max(1,Math.min(limit==null?25:limit,100));
    int rowOffset=Math.max(0,Math.min(offset==null?0:offset,10_000_000));
    String pattern="%"+term+"%";
    List<Map<String,Object>> items=jdbc.queryForList("""
      select a.id as pdf_asset_id,a.title,a.original_filename,a.sha256,
             a.file_size_bytes,a.page_count,a.source_kind,a.source_reference,a.created_at,
             a.created_by_staff_id,
             (select count(*) from learning_document d
              where d.pdf_asset_id=a.id and d.status<>'ARCHIVED') as assignment_count
      from learning_pdf_asset a
      where a.review_status='APPROVED'
        and (?='' or a.title ilike ? or a.original_filename ilike ?
             or coalesce(a.source_reference,'') ilike ?)
      order by a.created_at desc,a.id desc limit ? offset ?
      """,term,pattern,pattern,pattern,rowLimit,rowOffset);
    Long total=jdbc.queryForObject("""
      select count(*) from learning_pdf_asset a
      where a.review_status='APPROVED'
        and (?='' or a.title ilike ? or a.original_filename ilike ?
             or coalesce(a.source_reference,'') ilike ?)
      """,Long.class,term,pattern,pattern,pattern);
    return Map.of("items",items,"total",total==null?0L:total,"limit",rowLimit,"offset",rowOffset);
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
    long maxPdfBytes = "ADMIN".equalsIgnoreCase(String.valueOf(context.role()))
        ? ADMIN_MAX_PDF_BYTES : STANDARD_MAX_PDF_BYTES;
    if (file.getSize() > maxPdfBytes) {
      String limit = "ADMIN".equalsIgnoreCase(String.valueOf(context.role())) ? "500 MB for Admin uploads" : "50 MB";
      throw badRequest("PDF files must be " + limit + " or smaller.");
    }
    String title = requiredText(suppliedTitle, "PDF title", 240);
    String filename = safeFilename(file.getOriginalFilename());
    byte[] bytes;
    try {
      bytes = file.getBytes();
    } catch (IOException ex) {
      throw badRequest("The uploaded PDF could not be read. Please try again.");
    }
    int pageCount = validatePdf(bytes);
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
      """, Long.class, title, filename, sha, bytes.length, pageCount, bytes,
      sourceKind, normalizedReference, context.staffId());
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("pdf_asset_id", assetId);
    result.put("title", title);
    result.put("original_filename", filename);
    result.put("sha256", sha);
    result.put("file_size_bytes", bytes.length);
    result.put("page_count", pageCount);
    result.put("source_kind", sourceKind);
    result.put("duplicate", false);
    staffAudit.recordAction(context, "/api/v1/admin/learning-pdfs/" + assetId + "/uploaded",
        "Added a PDF to the private source library: " + filename + " (" + sha + ").");
    return result;
  }

  @GetMapping("/admin/learning-documents")
  public Map<String,Object> listAssignments(
      @RequestParam(required=false) String classCode,@RequestParam(required=false) String subjectCode,
      @RequestParam(required=false) String status,@RequestParam(required=false) String classCodes,
      @RequestParam(required=false) String subjectCodes,@RequestParam(required=false) String statuses,
      @RequestParam(required=false) String languages,@RequestParam(required=false) String scopes,
      @RequestParam(required=false) String programCode,
      @RequestParam(defaultValue="25") Integer limit,@RequestParam(defaultValue="0") Integer offset,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    requireContentView(context);
    String classQuery=classCodes!=null&&!classCodes.isBlank()?classCodes:classCode;
    String subjectQuery=subjectCodes!=null&&!subjectCodes.isBlank()?subjectCodes:subjectCode;
    String statusQuery=statuses!=null&&!statuses.isBlank()?statuses:status;
    List<String> classFilter=csvFilters(classQuery,false),subjectFilter=csvFilters(subjectQuery,false);
    List<String> statusFilter=csvFilters(statusQuery,true);
    if(statusFilter.contains("ALL"))statusFilter=List.of();
    validateFilterValues(statusFilter,List.of("DRAFT","PUBLISHED","ARCHIVED"),"document status");
    List<String> languageFilter=csvFilters(languages,false);validateFilterValues(languageFilter,List.of("hi","en"),"document language");
    List<String> scopeFilter=csvFilters(scopes,true);validateFilterValues(scopeFilter,List.of("SUBJECT_BOOK","CHAPTER_PDF"),"resource type");
    int rowLimit=Math.max(1,Math.min(limit==null?25:limit,100)),rowOffset=Math.max(0,Math.min(offset==null?0:offset,10_000_000));
    String from="""
      from learning_document d
      join learning_pdf_asset a on a.id=d.pdf_asset_id
      join curriculum_subject s on s.id=d.subject_id
      join curriculum_class c on c.id=s.class_id
      left join curriculum_chapter ch on ch.id=d.chapter_id
      left join curriculum_program p on p.id=s.program_id
      """;
    StringBuilder where=new StringBuilder(" where 1=1");List<Object> filterArgs=new ArrayList<>();
    if (programCode != null && !programCode.isBlank()) {
      where.append(" and upper(p.code)=upper(?) and p.status='ACTIVE'");
      filterArgs.add(programCode.trim());
    }
    appendDocumentFilter(where,filterArgs,"c.code",classFilter);
    appendDocumentFilter(where,filterArgs,"s.code",subjectFilter);
    appendDocumentFilter(where,filterArgs,"d.status",statusFilter);
    appendDocumentFilter(where,filterArgs,"d.language",languageFilter);
    appendDocumentFilter(where,filterArgs,"d.scope",scopeFilter);
    List<Object> pageArgs=new ArrayList<>(filterArgs);pageArgs.add(rowLimit);pageArgs.add(rowOffset);
    String selectSql="""
      select d.id as document_id,d.pdf_asset_id,d.scope,d.book_part,d.title,d.source_title,d.source_url,d.edition,
             d.page_start,d.page_end,d.language,d.status,d.version_no,d.created_at,d.updated_at,d.published_at,
             a.original_filename,a.sha256,a.file_size_bytes,a.page_count,
             c.code as class_code,c.display_name as class_name,p.code as program_code,p.display_name as program_name,
             s.code as subject_code,s.display_name as subject_name,
             ch.id as chapter_id,ch.code as chapter_code,ch.display_name as chapter_name,
             ch.active as chapter_active,ch.content_status as chapter_content_status,a.review_status as asset_review_status
      """;
    String orderLimit=" order by c.sort_order,s.sort_order,coalesce(ch.teaching_order,ch.sort_order),d.scope,d.created_at desc,d.id desc limit ? offset ?";
    List<Map<String,Object>> items=jdbc.queryForList(selectSql+from+where+orderLimit,pageArgs.toArray());
    Long total=jdbc.queryForObject("select count(*) "+from+where,Long.class,filterArgs.toArray());
    Map<String,Object> facets=new LinkedHashMap<>();
    facets.put("class",documentFacetCounts(from,"c.code","class",classFilter,subjectFilter,statusFilter,languageFilter,scopeFilter));
    facets.put("subject",documentFacetCounts(from,"s.code","subject",classFilter,subjectFilter,statusFilter,languageFilter,scopeFilter));
    facets.put("status",documentFacetCounts(from,"d.status","status",classFilter,subjectFilter,statusFilter,languageFilter,scopeFilter));
    facets.put("language",documentFacetCounts(from,"d.language","language",classFilter,subjectFilter,statusFilter,languageFilter,scopeFilter));
    facets.put("scope",documentFacetCounts(from,"d.scope","scope",classFilter,subjectFilter,statusFilter,languageFilter,scopeFilter));
    Map<String,Object> result=new LinkedHashMap<>();result.put("items",items);result.put("total",total==null?0L:total);
    result.put("limit",rowLimit);result.put("offset",rowOffset);result.put("facets",facets);return result;
  }

  private List<String> csvFilters(String raw,boolean upper) {
    if(raw==null||raw.isBlank()||"ALL".equalsIgnoreCase(raw.trim()))return List.of();
    return java.util.Arrays.stream(raw.split(",")).map(String::trim).filter(v->!v.isEmpty())
        .map(v->upper?v.toUpperCase(Locale.ROOT):v).distinct().limit(100).toList();
  }

  private void validateFilterValues(List<String> values,List<String> allowed,String label) {
    for(String value:values)if(!allowed.contains(value))throw badRequest("Choose a valid "+label+" filter.");
  }

  private void appendDocumentFilter(StringBuilder where,List<Object> args,String column,List<String> values) {
    if(values.isEmpty())return;
    where.append(" and ").append(column).append(" in (");
    for(int i=0;i<values.size();i++){if(i>0)where.append(',');where.append('?');}
    where.append(')');args.addAll(values);
  }

  private Map<String,Long> documentFacetCounts(String from,String column,String excluded,
      List<String> classes,List<String> subjects,List<String> statuses,List<String> languages,List<String> scopes) {
    StringBuilder where=new StringBuilder(" where 1=1");List<Object> args=new ArrayList<>();
    if(!"class".equals(excluded))appendDocumentFilter(where,args,"c.code",classes);
    if(!"subject".equals(excluded))appendDocumentFilter(where,args,"s.code",subjects);
    if(!"status".equals(excluded))appendDocumentFilter(where,args,"d.status",statuses);
    if(!"language".equals(excluded))appendDocumentFilter(where,args,"d.language",languages);
    if(!"scope".equals(excluded))appendDocumentFilter(where,args,"d.scope",scopes);
    List<Map<String,Object>> rows=jdbc.queryForList("select "+column+" as facet_value,count(*) as facet_count "+from+where+" group by "+column,args.toArray());
    Map<String,Long> result=new LinkedHashMap<>();
    for(Map<String,Object> row:rows)result.put(String.valueOf(row.get("facet_value")),((Number)row.get("facet_count")).longValue());
    return result;
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
    String programCode = optionalText(body.get("programCode"), 60);
    Long subjectId;
    if (programCode == null || programCode.isBlank()) {
      subjectId = jdbc.query("""
        select s.id from curriculum_subject s
        join curriculum_class c on c.id=s.class_id
        where c.code=? and s.code=? and s.program_id is null and c.active=true and s.active=true
        """, rs -> rs.next() ? rs.getLong(1) : null, classCode, subjectCode);
    } else {
      subjectId = jdbc.query("""
        select s.id from curriculum_subject s
        join curriculum_class c on c.id=s.class_id
        join curriculum_program p on p.id=s.program_id and p.class_id=c.id and p.status='ACTIVE'
        where c.code=? and s.code=? and upper(p.code)=upper(?) and c.active=true and s.active=true
        """, rs -> rs.next() ? rs.getLong(1) : null, classCode, subjectCode, programCode);
    }
    if (subjectId == null) throw badRequest("The selected active class, course, and subject do not exist.");
    Long chapterId = optionalLong(body.get("chapterId"), "chapterId");
    Integer bookPart = body.get("bookPart") == null
        ? null : integerValue(body.get("bookPart"), "bookPart", 1, 9);
    if ("CHAPTER_PDF".equals(scope)) {
      if (bookPart != null) throw badRequest("A chapter PDF cannot have a textbook part number.");
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
    String language = normalizeDocumentLanguage(body.getOrDefault("language", "hi"));
    String title = optionalText(body.get("title"), 240);
    if (title == null) title = String.valueOf(assets.getFirst().get("title"));
    String sourceTitle = optionalText(body.get("sourceTitle"), 300);
    String sourceUrl = optionalText(body.get("sourceUrl"), 2000);
    String edition = optionalText(body.get("edition"), 160);

    Long documentId = jdbc.queryForObject("""
      insert into learning_document(
        pdf_asset_id,scope,subject_id,chapter_id,book_part,title,source_title,source_url,edition,language,
        page_start,page_end,status,version_no,created_by_staff_id
      ) values(?,?,?,?,?,?,?,?,?,?,?,?,'DRAFT',1,?)
      returning id
      """, Long.class, assetId, scope, subjectId, chapterId, bookPart, title, sourceTitle, sourceUrl,
      edition, language, start, end, context.staffId());
    staffAudit.recordAction(context, "/api/v1/admin/learning-documents/" + documentId + "/created",
        "Attached a private PDF to " + scope + " for class " + classCode + ", subject " + subjectCode
            + (chapterId == null ? "" : ", chapter " + chapterId)
            + (bookPart == null ? "" : ", textbook part " + bookPart) + ".");
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
      select d.id,d.scope,d.subject_id,d.chapter_id,d.book_part,d.language,d.status,d.source_title,d.source_url,d.edition,
             d.title,d.page_start,d.page_end,a.page_count,a.id as asset_id,a.review_status as asset_review_status,
             ch.active as chapter_active,ch.content_status as chapter_content_status
      from learning_document d join learning_pdf_asset a on a.id=d.pdf_asset_id
      left join curriculum_chapter ch on ch.id=d.chapter_id
      where d.id=?
      """, documentId);
    if (rows.isEmpty()) throw notFound("Learning document", documentId);
    Map<String, Object> current = rows.getFirst();
    if ("PUBLISHED".equals(nextStatus)) {
      if (!"APPROVED".equals(String.valueOf(current.get("asset_review_status")))) {
        throw badRequest("Only an approved PDF asset can be published to students.");
      }
      if ("CHAPTER_PDF".equals(String.valueOf(current.get("scope")))
          && !Boolean.TRUE.equals(current.get("chapter_active"))) {
        throw badRequest("This curriculum chapter is inactive. Reactivate it before publishing its chapter PDF.");
      }
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
      if (current.get("book_part") == null) {
        // A whole-book or chapter replacement supersedes the full existing publication slot.
        jdbc.update("""
          update learning_document set status='ARCHIVED',updated_at=now()
          where scope=? and subject_id=? and language=?
            and chapter_id is not distinct from ?
            and status='PUBLISHED' and id<>?
          """, current.get("scope"), current.get("subject_id"), current.get("language"),
          current.get("chapter_id"), documentId);
      } else {
        // Publishing Part I must preserve Part II, and vice versa; only archive the same part
        // plus any legacy whole-book row that occupied the slot before the migration.
        jdbc.update("""
          update learning_document set status='ARCHIVED',updated_at=now()
          where scope=? and subject_id=? and language=?
            and chapter_id is not distinct from ?
            and status='PUBLISHED' and id<>?
            and (book_part is null or book_part=?)
          """, current.get("scope"), current.get("subject_id"), current.get("language"),
          current.get("chapter_id"), documentId, current.get("book_part"));
      }
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


  @GetMapping(value = "/admin/learning-documents/{documentId}/pages/{pageNumber}", produces = MediaType.IMAGE_PNG_VALUE)
  public ResponseEntity<byte[]> previewLearningDocumentPage(
      @PathVariable long documentId,
      @PathVariable int pageNumber,
      @RequestAttribute(value = "authContext", required = false) AuthContext context) {
    context = authorization.requireAuth(context);
    if (!authorization.hasPermission(context, "CONTENT_VIEW")
        && !authorization.hasPermission(context, "CONTENT_EDIT")
        && !authorization.hasPermission(context, "CONTENT_PUBLISH")) {
      throw new SecurityException("Learning document preview permission required.");
    }
    List<Map<String, Object>> rows = jdbc.queryForList("""
      select d.pdf_asset_id,d.page_start,d.page_end,a.review_status,a.sha256,a.file_size_bytes
      from learning_document d join learning_pdf_asset a on a.id=d.pdf_asset_id
      where d.id=?
      """, documentId);
    if (rows.isEmpty()) throw notFound("Learning document", documentId);
    Map<String, Object> row = rows.getFirst();
    if (!"APPROVED".equals(String.valueOf(row.get("review_status")))) {
      throw notFound("Approved learning document", documentId);
    }
    int start = ((Number) row.get("page_start")).intValue();
    int end = ((Number) row.get("page_end")).intValue();
    int visiblePages = end - start + 1;
    if (pageNumber < 1 || pageNumber > visiblePages) throw notFound("Document preview page", pageNumber);
    long assetId = ((Number) row.get("pdf_asset_id")).longValue();
    String assetSha = String.valueOf(row.get("sha256"));
    long assetBytes = ((Number) row.get("file_size_bytes")).longValue();
    int sourceIndex = start + pageNumber - 2;
    byte[] rendered = renderPdfPage(assetId, assetSha, sourceIndex, 90f, assetBytes);
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.IMAGE_PNG);
    headers.setCacheControl(CacheControl.noStore());
    headers.set("X-Content-Type-Options", "nosniff");
    headers.set("X-Robots-Tag", "noindex, noarchive");
    return new ResponseEntity<>(rendered, headers, HttpStatus.OK);
  }

  @GetMapping("/learning/documents")
  public List<Map<String, Object>> studentDocumentCatalog(
      @RequestParam(required = false) String subjectCode,
      @RequestParam(defaultValue = "hi") String language,
      @RequestParam(required = false) String displayLanguage,
      @RequestParam(required = false) String programCode,
      @RequestAttribute(value = "authContext", required = false) AuthContext context) {
    context = authorization.requireStudent(context);
    String subjectFilter = subjectCode == null ? "" : subjectCode.trim();
    String languageFilter = normalizeDocumentLanguage(language);
    String chapterDisplayLanguage = normalizeDocumentLanguage(displayLanguage == null ? language : displayLanguage);
    Long programId = null;
    if (programCode != null && !programCode.isBlank()) {
      List<Map<String,Object>> enrolledProgram = jdbc.queryForList("""
        select p.id
        from curriculum_program p
        join curriculum_class cc on cc.id=p.class_id and cc.active=true
        join student st on st.id=? and st.active=true and st.class_code=cc.code
        join student_program_enrollment spe on spe.student_id=st.id
          and spe.program_id=p.id and spe.status='ACTIVE'
        where upper(p.code)=upper(?) and p.status='ACTIVE' and p.code<>'GENERAL'
        """, context.studentId(), programCode.trim());
      if (enrolledProgram.isEmpty()) throw new SecurityException("Active enrolment in this course is required.");
      programId = ((Number)enrolledProgram.getFirst().get("id")).longValue();
    }
    String programScope = programId == null ? "s.program_id is null"
        : "s.program_id=? and exists(select 1 from student_program_enrollment spe where spe.student_id=st.id and spe.program_id=s.program_id and spe.status='ACTIVE')";
    String sql = """
      select d.id as document_id,d.scope,d.book_part,d.title,d.edition,d.language,d.page_start,d.page_end,
             a.page_count as source_page_count,
             d.page_end-d.page_start+1 as page_count,
             s.code as subject_code,s.display_name as subject_name,
             c.code as class_code,
             ch.id as chapter_id,ch.code as chapter_code,coalesce(ct.display_name,ch.display_name) as chapter_name,
             (ct.id is not null) as chapter_translated,
             coalesce(p.last_page,1) as last_page,p.last_opened_at
      from learning_document d
      join learning_pdf_asset a on a.id=d.pdf_asset_id
      join curriculum_subject s on s.id=d.subject_id and s.active=true
      join curriculum_class c on c.id=s.class_id and c.active=true
      join student st on st.id=? and st.active=true and st.class_code=c.code
      join student_track_enrollment ste on ste.student_id=st.id and ste.subject_id=s.id and ste.status='ACTIVE'
      left join curriculum_chapter ch on ch.id=d.chapter_id
      left join lateral (
        select t.id,t.display_name
        from curriculum_chapter_translation t
        where t.chapter_id=ch.id and t.language=? and t.translation_status='PUBLISHED'
          and t.source_hash::text=md5(jsonb_build_object('display_name',ch.display_name,'description',ch.description)::text)
        order by t.version_no desc,t.id desc limit 1
      ) ct on true
      left join student_learning_document_progress p
        on p.learning_document_id=d.id and p.student_id=st.id
      where d.status='PUBLISHED'
        and d.language=?
        and (?='' or s.code=?)
        and __PROGRAM_SCOPE__
        and a.review_status='APPROVED'
        and (d.scope='SUBJECT_BOOK' or (ch.id is not null and ch.active=true))
      order by s.sort_order,d.language,case when d.scope='SUBJECT_BOOK' then 0 else 1 end,
               coalesce(ch.teaching_order,ch.sort_order),ch.display_name,d.id
      """.replace("__PROGRAM_SCOPE__",programScope);
    if (programId == null) {
      return jdbc.queryForList(sql, context.studentId(), chapterDisplayLanguage, languageFilter, subjectFilter, subjectFilter);
    }
    return jdbc.queryForList(sql, context.studentId(), chapterDisplayLanguage, languageFilter, subjectFilter, subjectFilter, programId);
  }

  /** Source-compatible overloads for existing Java callers. */
  public List<Map<String, Object>> studentDocumentCatalog(String subjectCode, String language, AuthContext context) {
    return studentDocumentCatalog(subjectCode, language, null, null, context);
  }
  public List<Map<String, Object>> studentDocumentCatalog(String subjectCode, String language, String displayLanguage, AuthContext context) {
    return studentDocumentCatalog(subjectCode, language, displayLanguage, null, context);
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
    document.remove("asset_sha256");
    document.remove("asset_file_size_bytes");
    document.put("last_page", lastPage);
    return document;
  }

  /** Delivers a single rendered PNG page, never the source PDF bytes. */
  @GetMapping(value = "/learning/documents/{documentId}/pages/{pageNumber}", produces = MediaType.IMAGE_PNG_VALUE)
  public ResponseEntity<byte[]> studentDocumentPage(
      @PathVariable long documentId,
      @PathVariable int pageNumber,
      @RequestParam(value = "rendition", defaultValue = "full") String rendition,
      @RequestAttribute(value = "authContext", required = false) AuthContext context) {
    // Authenticate and re-check active enrollment/publication before even looking in the cache.
    context = authorization.requireStudent(context);
    Map<String, Object> metadata = readableDocument(documentId, context.studentId());
    int start = ((Number) metadata.get("page_start")).intValue();
    int end = ((Number) metadata.get("page_end")).intValue();
    int visibleCount = end - start + 1;
    if (pageNumber < 1 || pageNumber > visibleCount) throw notFound("Document page", pageNumber);

    float renderDpi = switch (rendition == null ? "full" : rendition.trim().toLowerCase(Locale.ROOT)) {
      case "full" -> PAGE_RENDER_DPI;
      case "thumbnail" -> THUMBNAIL_RENDER_DPI;
      default -> throw badRequest("PDF rendition must be full or thumbnail.");
    };
    long assetId = ((Number) metadata.get("pdf_asset_id")).longValue();
    String assetSha = String.valueOf(metadata.get("asset_sha256"));
    long assetBytes = ((Number) metadata.get("asset_file_size_bytes")).longValue();
    int sourceIndex = start + pageNumber - 2;
    byte[] rendered = renderPdfPage(assetId, assetSha, sourceIndex, renderDpi, assetBytes);

    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.IMAGE_PNG);
    headers.setContentDisposition(ContentDisposition.inline()
        .filename("page-" + pageNumber + ".png", StandardCharsets.UTF_8).build());
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
      select d.id as document_id,d.pdf_asset_id,a.sha256 as asset_sha256,
             a.file_size_bytes as asset_file_size_bytes,d.scope,d.book_part,d.title,d.edition,d.language,
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
        and a.review_status='APPROVED'
        and (d.scope='SUBJECT_BOOK' or (ch.id is not null and ch.active=true))
        and (s.program_id is null or exists(
          select 1 from student_program_enrollment spe
          join curriculum_program ep on ep.id=spe.program_id and ep.status='ACTIVE'
          where spe.student_id=st.id and spe.program_id=s.program_id and spe.status='ACTIVE'
        ))
      """, studentId, documentId);
    if (rows.isEmpty()) throw notFound("Learning document", documentId);
    return new LinkedHashMap<>(rows.getFirst());
  }

  private Map<String, Object> assignmentById(long documentId) {
    List<Map<String, Object>> rows = jdbc.queryForList("""
      select d.id as document_id,d.pdf_asset_id,d.scope,d.title,d.source_title,d.source_url,d.edition,d.language,
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
        && !authorization.hasPermission(context, "CONTENT_EDIT")
        && !authorization.hasPermission(context, "CONTENT_PUBLISH")) {
      throw new SecurityException("Content view, edit, or publish permission required.");
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

  private int validatePdf(byte[] bytes) {
    if (bytes.length < 5 || !new String(bytes, 0, 5, StandardCharsets.US_ASCII).equals("%PDF-")) {
      throw badRequest("The uploaded file is not a valid PDF.");
    }
    try (PDDocument document = Loader.loadPDF(bytes)) {
      if (document.isEncrypted()) throw badRequest("Password-protected PDFs are not supported.");
      int pages = document.getNumberOfPages();
      if (pages < 1 || pages > MAX_PDF_PAGES) {
        throw badRequest("PDFs must contain between 1 and " + MAX_PDF_PAGES + " pages.");
      }
      for (int pageIndex = 0; pageIndex < pages; pageIndex++) {
        assertPageWithinRenderLimits(document.getPage(pageIndex).getCropBox(), PAGE_RENDER_DPI,
            HttpStatus.BAD_REQUEST, "Every PDF page must fit within the secure viewer's rendering limits.");
      }
      new PDFRenderer(document).renderImageWithDPI(0, 36f, ImageType.RGB).flush();
      return pages;
    } catch (IOException ex) {
      throw badRequest("The uploaded file could not be opened as a PDF.");
    }
  }

  /**
   * Caches rendered pages in bounded, private server memory. The caller must run its current
   * authorization/publication checks before calling this method; browser responses remain no-store.
   * Cache identity is based on immutable PDF content, source page and render DPI, never a user ID.
   */
  private byte[] renderPdfPage(long assetId, String sha256, int sourceIndex, float dpi, long sourceSizeBytes) {
    String contentKey = sha256 == null || sha256.isBlank() ? "asset-" + assetId : sha256;
    PdfPageCacheKey key = new PdfPageCacheKey(contentKey, sourceIndex, Math.round(dpi * 10f));
    byte[] cached = cachedPdfPage(key);
    if (cached != null) return cached;

    // Large originals occupy both slots, preventing two 500 MiB PDFs from rendering together.
    int permitsRequired = sourceSizeBytes > MAX_CACHED_SOURCE_PDF_BYTES ? 2 : 1;
    boolean acquired;
    try {
      acquired = renderPermits.tryAcquire(permitsRequired, RENDER_PERMIT_WAIT_SECONDS, TimeUnit.SECONDS);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
          "The PDF renderer is busy. Please retry shortly.", ex);
    }
    if (!acquired) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
          "The PDF renderer is busy. Please retry shortly.");
    }

    try {
      // Another request may have filled the cache while this request waited for a render slot.
      cached = cachedPdfPage(key);
      if (cached != null) return cached;

      byte[] pdfBytes = sourcePdfBytes(assetId, contentKey);
      if (pdfBytes == null || pdfBytes.length == 0) throw notFound("PDF content", assetId);
      try (PDDocument pdf = Loader.loadPDF(pdfBytes); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
        if (sourceIndex < 0 || sourceIndex >= pdf.getNumberOfPages()) throw notFound("PDF page", sourceIndex + 1);
        assertPageWithinRenderLimits(pdf.getPage(sourceIndex).getCropBox(), dpi,
            HttpStatus.UNPROCESSABLE_ENTITY,
            "This PDF page exceeds the safe rendering dimensions and cannot be displayed.");
        BufferedImage image = new PDFRenderer(pdf).renderImageWithDPI(sourceIndex, dpi, ImageType.RGB);
        if (!javax.imageio.ImageIO.write(image, "png", output)) {
          image.flush();
          throw new IllegalStateException("PNG rendering is not available.");
        }
        image.flush();
        byte[] rendered = output.toByteArray();
        cachePdfPage(key, rendered);
        return rendered;
      } catch (IOException ex) {
        throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
            "This PDF page could not be rendered.", ex);
      }
    } finally {
      renderPermits.release(permitsRequired);
    }
  }

  private void assertPageWithinRenderLimits(
      PDRectangle cropBox, float dpi, HttpStatus status, String message) {
    double widthPoints = cropBox == null ? 0d : cropBox.getWidth();
    double heightPoints = cropBox == null ? 0d : cropBox.getHeight();
    double widthPixels = Math.ceil(widthPoints * dpi / 72d);
    double heightPixels = Math.ceil(heightPoints * dpi / 72d);
    double pixelCount = widthPixels * heightPixels;
    if (!Double.isFinite(widthPixels) || !Double.isFinite(heightPixels)
        || widthPixels < 1d || heightPixels < 1d
        || widthPixels > MAX_RENDERED_PAGE_DIMENSION
        || heightPixels > MAX_RENDERED_PAGE_DIMENSION
        || !Double.isFinite(pixelCount) || pixelCount > MAX_RENDERED_PAGE_PIXELS) {
      throw new ResponseStatusException(status, message);
    }
  }

  private byte[] sourcePdfBytes(long assetId, String contentKey) {
    synchronized (renderCacheMonitor) {
      CachedSourcePdf entry = sourcePdfCache.get(contentKey);
      if (entry != null && System.currentTimeMillis() - entry.cachedAtMillis() <= RENDER_CACHE_TTL_MILLIS) {
        return entry.bytes();
      }
      if (entry != null) {
        sourcePdfCache.remove(contentKey);
        sourcePdfCacheBytes -= entry.bytes().length;
      }
    }

    byte[] bytes = jdbc.queryForObject(
        "select pdf_bytes from learning_pdf_asset where id=?", byte[].class, assetId);
    if (bytes == null || bytes.length == 0 || bytes.length > MAX_CACHED_SOURCE_PDF_BYTES) return bytes;

    synchronized (renderCacheMonitor) {
      CachedSourcePdf previous = sourcePdfCache.remove(contentKey);
      if (previous != null) sourcePdfCacheBytes -= previous.bytes().length;
      while (sourcePdfCache.size() >= MAX_SOURCE_PDF_CACHE_ENTRIES
          || sourcePdfCacheBytes + bytes.length > MAX_SOURCE_PDF_CACHE_BYTES) {
        Iterator<Map.Entry<String, CachedSourcePdf>> iterator = sourcePdfCache.entrySet().iterator();
        if (!iterator.hasNext()) break;
        Map.Entry<String, CachedSourcePdf> oldest = iterator.next();
        sourcePdfCacheBytes -= oldest.getValue().bytes().length;
        iterator.remove();
      }
      sourcePdfCache.put(contentKey, new CachedSourcePdf(bytes, System.currentTimeMillis()));
      sourcePdfCacheBytes += bytes.length;
    }
    return bytes;
  }

  private byte[] cachedPdfPage(PdfPageCacheKey key) {
    synchronized (renderCacheMonitor) {
      CachedPdfPage entry = renderedPageCache.get(key);
      if (entry == null) return null;
      if (System.currentTimeMillis() - entry.cachedAtMillis() > RENDER_CACHE_TTL_MILLIS) {
        renderedPageCache.remove(key);
        renderedPageCacheBytes -= entry.bytes().length;
        return null;
      }
      return entry.bytes();
    }
  }

  private void cachePdfPage(PdfPageCacheKey key, byte[] bytes) {
    if (bytes == null || bytes.length == 0 || bytes.length > MAX_CACHED_PAGE_BYTES) return;
    synchronized (renderCacheMonitor) {
      CachedPdfPage previous = renderedPageCache.remove(key);
      if (previous != null) renderedPageCacheBytes -= previous.bytes().length;
      while (renderedPageCache.size() >= MAX_RENDER_CACHE_ENTRIES
          || renderedPageCacheBytes + bytes.length > MAX_RENDER_CACHE_BYTES) {
        Iterator<Map.Entry<PdfPageCacheKey, CachedPdfPage>> iterator =
            renderedPageCache.entrySet().iterator();
        if (!iterator.hasNext()) break;
        Map.Entry<PdfPageCacheKey, CachedPdfPage> oldest = iterator.next();
        renderedPageCacheBytes -= oldest.getValue().bytes().length;
        iterator.remove();
      }
      renderedPageCache.put(key, new CachedPdfPage(bytes, System.currentTimeMillis()));
      renderedPageCacheBytes += bytes.length;
    }
  }

  private record PdfPageCacheKey(String sha256, int sourcePageIndex, int dpiTenths) {}
  private record CachedPdfPage(byte[] bytes, long cachedAtMillis) {}
  private record CachedSourcePdf(byte[] bytes, long cachedAtMillis) {}

  private String sha256(byte[] value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 is not available.", ex);
    }
  }

  private String normalizeDocumentLanguage(Object value) {
    String normalized = value == null ? "hi" : String.valueOf(value).trim().toLowerCase(Locale.ROOT);
    return switch (normalized) {
      case "hi", "hindi", "hinglish" -> "hi";
      case "en", "english" -> "en";
      default -> throw badRequest("Resource language must be Hindi (hi) or English (en).");
    };
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
