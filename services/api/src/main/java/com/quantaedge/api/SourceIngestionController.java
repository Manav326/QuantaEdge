package com.quantaedge.api;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.multipdf.Splitter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.http.CacheControl;
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
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Reusable admin pipeline for existing source records, public PDF download, chapter splitting,
 * review, and draft library placement. This controller never publishes directly to students.
 */
@RestController
@RequestMapping("/api/v1/admin/source-ingestion")
public class SourceIngestionController {
  private static final int MAX_PDF_PAGES = 2000;
  private final JdbcTemplate jdbc;
  private final AuthorizationService authorization;
  private final StaffAuditService staffAudit;
  private final SourceIngestionWorker worker;

  public SourceIngestionController(
      JdbcTemplate jdbc, AuthorizationService authorization, StaffAuditService staffAudit,
      SourceIngestionWorker worker) {
    this.jdbc = jdbc;
    this.authorization = authorization;
    this.staffAudit = staffAudit;
    this.worker = worker;
  }

  @GetMapping("/sources")
  public List<Map<String, Object>> listSources(
      @RequestAttribute(value = "authContext", required = false) AuthContext context) {
    requireReviewer(context);
    return jdbc.queryForList("""
      select cs.id as source_id,cs.source_kind,cs.title,cs.provider,cs.source_url,cs.edition,
             cs.language,cs.source_year,cs.checksum,cs.status,cs.accessed_at,cs.learning_pdf_asset_id,
             a.id as matching_pdf_asset_id,a.title as pdf_asset_title,a.page_count as pdf_page_count,
             a.review_status as pdf_review_status,a.sha256 as pdf_sha256,
             (select count(*) from chapter_source chs where chs.source_id=cs.id) as chapter_mapping_count
      from content_source cs
      left join lateral (
        select x.id,x.title,x.page_count,x.review_status,x.sha256
        from learning_pdf_asset x
        where x.id=cs.learning_pdf_asset_id
           or x.source_content_id=cs.id
           or (nullif(cs.checksum,'') is not null and lower(x.sha256)=lower(cs.checksum))
           or (cs.source_url is not null and x.source_reference=cs.source_url)
        order by case when x.id=cs.learning_pdf_asset_id then 0 when x.source_content_id=cs.id then 1 else 2 end,x.id desc
        limit 1
      ) a on true
      order by case when a.id is null then 0 else 1 end,cs.updated_at desc,cs.id
      limit 500
      """);
  }

  @GetMapping("/sources/{sourceId}/chapter-mappings")
  public List<Map<String, Object>> listExistingChapterMappings(
      @PathVariable long sourceId,
      @RequestAttribute(value = "authContext", required = false) AuthContext context) {
    requireReviewer(context);
    return jdbc.queryForList("""
      select cs.id as chapter_source_id,cs.chapter_id,ch.code as chapter_code,
             ch.display_name as chapter_name,cs.source_chapter_no,cs.source_chapter_title,
             cs.source_locator,cs.coverage_type,cs.coverage_status,cs.notes,
             d.document_id,d.document_title,d.document_status,d.document_source_title,d.document_source_url,
             d.pdf_asset_id,d.pdf_title,d.pdf_page_count,d.pdf_review_status,d.pdf_sha256
      from chapter_source cs
      join content_source src on src.id=cs.source_id
      join curriculum_chapter ch on ch.id=cs.chapter_id
      left join lateral (
        select ld.id as document_id,ld.title as document_title,ld.status as document_status,
               ld.source_title as document_source_title,ld.source_url as document_source_url,
               a.id as pdf_asset_id,a.title as pdf_title,a.page_count as pdf_page_count,
               a.review_status as pdf_review_status,a.sha256 as pdf_sha256
        from learning_document ld
        join learning_pdf_asset a on a.id=ld.pdf_asset_id
        where ld.chapter_id=cs.chapter_id and ld.scope='CHAPTER_PDF'
          and (
            (src.source_url is not null and ld.source_url=src.source_url)
            or ld.source_title=src.title
            or a.source_content_id=src.id
            or (src.source_url is not null and a.source_reference=src.source_url)
          )
        order by case when a.source_content_id=src.id then 0
                      when src.source_url is not null and ld.source_url=src.source_url then 1
                      else 2 end,
                 case when ld.status='PUBLISHED' then 0 when ld.status='DRAFT' then 1 else 2 end,
                 ld.updated_at desc,ld.id desc
        limit 1
      ) d on true
      where cs.source_id=?
      order by ch.sort_order,ch.teaching_order,ch.id,cs.id
      """, sourceId);
  }

  @GetMapping("/jobs")
  public List<Map<String, Object>> listJobs(
      @RequestAttribute(value = "authContext", required = false) AuthContext context) {
    requireReviewer(context);
    return jdbc.queryForList("""
      select j.id as job_id,j.source_id,j.source_title,j.source_url,j.final_pdf_url,j.edition,j.language,
             j.status,j.error_message,j.book_asset_id,j.page_count,j.created_at,j.updated_at,j.reviewed_at,
             c.code as class_code,c.display_name as class_name,
             s.code as subject_code,s.display_name as subject_name,
             (select count(*) from source_ingestion_chapter ch where ch.job_id=j.id) as chapter_count,
             (select count(*) from source_ingestion_chapter ch where ch.job_id=j.id and ch.status='APPROVED') as approved_chapter_count,
             (select count(*) from source_ingestion_chapter ch where ch.job_id=j.id and ch.status='REJECTED') as rejected_chapter_count
      from source_ingestion_job j
      join curriculum_subject s on s.id=j.subject_id
      join curriculum_class c on c.id=s.class_id
      order by j.created_at desc,j.id desc
      limit 200
      """);
  }

  @GetMapping("/jobs/{jobId}")
  public Map<String, Object> getJob(
      @PathVariable long jobId,
      @RequestAttribute(value = "authContext", required = false) AuthContext context) {
    requireReviewer(context);
    return jobDetails(jobId);
  }

  @PostMapping("/jobs")
  public Map<String, Object> startJob(
      @RequestBody Map<String, Object> body,
      @RequestAttribute(value = "authContext", required = false) AuthContext context) {
    context = requireReviewer(context);
    String classCode = requiredText(body.get("classCode"), "classCode", 30);
    String subjectCode = requiredText(body.get("subjectCode"), "subjectCode", 40);
    Long subjectId = jdbc.query("""
      select s.id from curriculum_subject s join curriculum_class c on c.id=s.class_id
      where c.code=? and s.code=? and c.active=true and s.active=true
      """, rs -> rs.next() ? rs.getLong(1) : null, classCode, subjectCode);
    if (subjectId == null) throw badRequest("Select an active class and subject from the curriculum.");

    Long requestedSourceId = optionalLong(body.get("sourceId"), "sourceId");
    Long sourceId;
    String sourceTitle;
    String sourceUrl;
    String edition;
    String language;
    String sourceKind;
    String checksum;
    Object linkedAsset;
    if (requestedSourceId != null) {
      List<Map<String, Object>> sources = jdbc.queryForList("""
        select id,title,source_url,edition,language,source_kind,checksum,learning_pdf_asset_id
        from content_source where id=?
        """, requestedSourceId);
      if (sources.isEmpty()) throw badRequest("The selected registered content source no longer exists.");
      Map<String, Object> source = sources.getFirst();
      sourceId = requestedSourceId;
      sourceTitle = optionalText(body.get("sourceTitle"), 300);
      if (sourceTitle == null) sourceTitle = String.valueOf(source.get("title"));
      sourceUrl = optionalText(body.get("sourceUrl"), 2000);
      if (sourceUrl == null) sourceUrl = optionalText(source.get("source_url"), 2000);
      edition = optionalText(body.get("edition"), 160);
      if (edition == null) edition = String.valueOf(source.get("edition"));
      language = optionalText(body.get("language"), 20);
      if (language == null) language = String.valueOf(source.get("language"));
      sourceKind = String.valueOf(source.get("source_kind"));
      checksum = source.get("checksum") == null ? null : String.valueOf(source.get("checksum"));
      linkedAsset = source.get("learning_pdf_asset_id");
    } else {
      sourceTitle = requiredText(body.get("sourceTitle"), "sourceTitle", 300);
      sourceUrl = requiredText(body.get("sourceUrl"), "sourceUrl", 2000);
      if (!sourceUrl.toLowerCase(Locale.ROOT).startsWith("https://")) {
        throw badRequest("Enter an official HTTPS source page or direct PDF URL.");
      }
      edition = optionalText(body.get("edition"), 160);
      if (edition == null) edition = "UNVERIFIED";
      language = optionalText(body.get("language"), 20);
      if (language == null) language = "hi";
      sourceKind = optionalText(body.get("sourceKind"), 40);
      if (sourceKind == null) sourceKind = "BOARD_TEXTBOOK";
      String provider = optionalText(body.get("provider"), 200);
      if (provider == null) provider = "Admin registered source";
      sourceId = jdbc.queryForObject("""
        insert into content_source(source_kind,title,provider,source_url,edition,language,status)
        values(?,?,?,?,?,?,'REGISTERED')
        on conflict(source_kind,title,edition) do update set
          provider=excluded.provider,source_url=excluded.source_url,language=excluded.language,updated_at=now()
        returning id
        """, Long.class, sourceKind, sourceTitle, provider, sourceUrl, edition, language);
      checksum = null;
      linkedAsset = null;
    }
    if (sourceUrl == null || !sourceUrl.toLowerCase(Locale.ROOT).startsWith("https://")) {
      throw badRequest("Enter an official HTTPS source page or direct PDF URL.");
    }
    if (requestedSourceId != null) {
      if (hasText(body.get("sourceUrl"))) {
        jdbc.update("update content_source set source_url=?,updated_at=now() where id=?", sourceUrl, sourceId);
      }
      if (hasText(body.get("sourceTitle")) || hasText(body.get("edition")) || hasText(body.get("language"))) {
        jdbc.update("update content_source set title=?,edition=?,language=?,updated_at=now() where id=?",
            sourceTitle, edition, language, sourceId);
      }
    }
    Long activeJobId = jdbc.query("""
      select id from source_ingestion_job
      where source_id=? and subject_id=? and status='DOWNLOADING'
        and created_at > now() - interval '15 minutes'
      order by created_at desc,id desc limit 1
      """, rs -> rs.next() ? rs.getLong(1) : null, sourceId, subjectId);
    if (activeJobId != null) {
      return jobDetails(activeJobId);
    }
    Long assetId = jdbc.query("""
      select a.id from learning_pdf_asset a
      where a.id=coalesce(?, -1)
         or (nullif(?,'') is not null and lower(a.sha256)=lower(?))
         or (nullif(?,'') is not null and a.source_reference=?)
      order by case when a.id=coalesce(?, -1) then 0 else 1 end,a.id desc
      limit 1
      """, rs -> rs.next() ? rs.getLong(1) : null,
        linkedAsset == null ? null : ((Number) linkedAsset).longValue(),
        checksum, checksum, sourceUrl, sourceUrl,
        linkedAsset == null ? null : ((Number) linkedAsset).longValue());
    Long jobId = jdbc.queryForObject("""
      insert into source_ingestion_job(
        source_id,subject_id,source_title,source_url,edition,language,book_asset_id,status,created_by_staff_id
      ) values(?,?,?,?,?,?,?,'DOWNLOADING',?)
      returning id
      """, Long.class, sourceId, subjectId, sourceTitle, sourceUrl, edition, language, assetId, context.staffId());
    staffAudit.recordAction(context, "/api/v1/admin/source-ingestion/jobs/" + jobId,
        "Started a textbook source ingestion job for class " + classCode + " and subject " + subjectCode + ".");
    worker.process(jobId);
    return jobDetails(jobId);
  }

  @PostMapping("/jobs/{jobId}/chapters")
  @Transactional
  public Map<String, Object> splitIntoChapters(
      @PathVariable long jobId, @RequestBody Map<String, Object> body,
      @RequestAttribute(value = "authContext", required = false) AuthContext context) {
    context = requireReviewer(context);
    Map<String, Object> job = jobRow(jobId);
    if (!"REVIEW".equals(String.valueOf(job.get("status")))) {
      throw badRequest("The source book must finish downloading before chapter ranges can be prepared.");
    }
    List<Map<String, Object>> existingApproved = jdbc.queryForList(
        "select id from source_ingestion_chapter where job_id=? and status='APPROVED'", jobId);
    if (!existingApproved.isEmpty()) throw badRequest("Approved chapter reviews are locked. Start a new ingestion version to change them.");
    Object raw = body.get("chapters");
    if (!(raw instanceof List<?> submitted) || submitted.isEmpty() || submitted.size() > 100) {
      throw badRequest("Select between 1 and 100 chapters with page ranges.");
    }
    int pageCount = ((Number) job.get("page_count")).intValue();
    List<ChapterCandidate> candidates = new ArrayList<>();
    for (Object value : submitted) {
      if (!(value instanceof Map<?, ?> item)) throw badRequest("Every chapter mapping must be an object.");
      long chapterId = longValue(item.get("chapterId"), "chapterId");
      int start = integerValue(item.get("pageStart"), "pageStart", 1, pageCount);
      int end = integerValue(item.get("pageEnd"), "pageEnd", start, pageCount);
      List<Map<String, Object>> chapterRows = jdbc.queryForList("""
        select ch.id,ch.code,ch.display_name from curriculum_chapter ch
        where ch.id=? and ch.subject_id=? and ch.active=true
        """, chapterId, job.get("subject_id"));
      if (chapterRows.isEmpty()) throw badRequest("A selected chapter does not belong to this job's subject.");
      Map<String, Object> curriculumChapter = chapterRows.getFirst();
      String title = optionalText(item.get("title"), 300);
      if (title == null) title = String.valueOf(curriculumChapter.get("display_name"));
      candidates.add(new ChapterCandidate(chapterId, title, start, end, String.valueOf(curriculumChapter.get("code"))));
    }
    java.util.Set<Long> chapterIds = new java.util.HashSet<>();
    List<ChapterCandidate> ordered = new ArrayList<>(candidates);
    ordered.sort(java.util.Comparator.comparingInt(ChapterCandidate::pageStart));
    for (int i = 0; i < ordered.size(); i++) {
      ChapterCandidate current = ordered.get(i);
      if (!chapterIds.add(current.chapterId())) throw badRequest("Each curriculum chapter can be mapped only once.");
      if (i > 0 && current.pageStart() <= ordered.get(i - 1).pageEnd()) {
        throw badRequest("Chapter page ranges overlap. Correct the page numbers before splitting.");
      }
    }
    Long bookAssetId = ((Number) job.get("book_asset_id")).longValue();
    byte[] bookBytes = jdbc.queryForObject("select pdf_bytes from learning_pdf_asset where id=?", byte[].class, bookAssetId);
    if (bookBytes == null) throw badRequest("The source PDF bytes are not available for splitting.");
    jdbc.update("""
      update learning_pdf_asset set review_status='REJECTED'
      where source_reference like ? and review_status='REVIEW'
      """, "source-ingestion:" + jobId + ":chapter:%");
    jdbc.update("delete from source_ingestion_chapter where job_id=?", jobId);
    for (ChapterCandidate candidate : ordered) {
      byte[] chapterPdf = extractRange(bookBytes, candidate.pageStart(), candidate.pageEnd());
      String hash = sha256(chapterPdf);
      String reference = "source-ingestion:" + jobId + ":chapter:" + candidate.chapterCode();
      Long chapterAssetId = storePendingAsset(
          candidate.title(), candidate.chapterCode() + ".pdf", chapterPdf,
          candidate.pageEnd() - candidate.pageStart() + 1, hash, reference,
          ((Number) job.get("source_id")).longValue(), context.staffId());
      jdbc.update("""
        insert into source_ingestion_chapter(job_id,chapter_id,chapter_title,page_start,page_end,pdf_asset_id)
        values(?,?,?,?,?,?)
        """, jobId, candidate.chapterId(), candidate.title(), candidate.pageStart(),
        candidate.pageEnd(), chapterAssetId);
    }
    staffAudit.recordAction(context, "/api/v1/admin/source-ingestion/jobs/" + jobId + "/chapters",
        "Prepared chapter-wise PDF candidates for admin review.");
    return jobDetails(jobId);
  }

  @PatchMapping("/jobs/{jobId}/chapters/{chapterRowId}")
  @Transactional
  public Map<String, Object> reviewChapter(
      @PathVariable long jobId, @PathVariable long chapterRowId, @RequestBody Map<String, Object> body,
      @RequestAttribute(value = "authContext", required = false) AuthContext context) {
    context = requireReviewer(context);
    Map<String, Object> job = jobRow(jobId);
    if (!"REVIEW".equals(String.valueOf(job.get("status")))) throw badRequest("This ingestion job is not open for review.");
    String status = requiredText(body.get("status"), "status", 20).toUpperCase(Locale.ROOT);
    if (!List.of("APPROVED", "REJECTED", "NEEDS_REVIEW").contains(status)) {
      throw badRequest("Choose APPROVED, REJECTED, or NEEDS_REVIEW.");
    }
    String notes = optionalText(body.get("reviewNotes"), 1200);
    int changed = jdbc.update("""
      update source_ingestion_chapter set status=?,review_notes=?,reviewed_by_staff_id=?,reviewed_at=now(),updated_at=now()
      where id=? and job_id=?
      """, status, notes, context.staffId(), chapterRowId, jobId);
    if (changed == 0) throw notFound("Source chapter candidate", chapterRowId);
    staffAudit.recordAction(context, "/api/v1/admin/source-ingestion/jobs/" + jobId + "/chapters/" + chapterRowId,
        "Changed a source chapter review status to " + status + ".");
    return jobDetails(jobId);
  }

  @PostMapping("/jobs/{jobId}/approve")
  @Transactional
  public Map<String, Object> approveJob(
      @PathVariable long jobId,
      @RequestAttribute(value = "authContext", required = false) AuthContext context) {
    context = requireReviewer(context);
    Map<String, Object> job = jobRow(jobId);
    if ("APPROVED".equals(String.valueOf(job.get("status")))) return jobDetails(jobId);
    if (!"REVIEW".equals(String.valueOf(job.get("status")))) throw badRequest("Only a downloaded book in review can be approved.");
    List<Map<String, Object>> chapters = jdbc.queryForList("""
      select ch.id,ch.chapter_id,ch.chapter_title,ch.page_start,ch.page_end,ch.pdf_asset_id,ch.status,
             a.page_count,a.title as asset_title
      from source_ingestion_chapter ch join learning_pdf_asset a on a.id=ch.pdf_asset_id
      where ch.job_id=? order by ch.page_start,ch.id
      """, jobId);
    chapters = chaptersReadyForPublishing(chapters);
    Long bookId = ((Number) job.get("book_asset_id")).longValue();
    Long sourceId = ((Number) job.get("source_id")).longValue();
    Long subjectId = ((Number) job.get("subject_id")).longValue();
    Long staffId = context.staffId();
    jdbc.update("""
      update learning_pdf_asset set review_status='REJECTED',reviewed_by_staff_id=?,reviewed_at=now()
      where id in (
        select pdf_asset_id from source_ingestion_chapter
        where job_id=? and status='REJECTED' and pdf_asset_id is not null
      ) and review_status in ('DRAFT','REVIEW')
      """, staffId, jobId);
    jdbc.update("""
      update learning_pdf_asset set review_status='APPROVED',reviewed_by_staff_id=?,reviewed_at=now()
      where id=? and review_status in ('DRAFT','REVIEW')
      """, staffId, bookId);
    Long bookDocumentId = jdbc.queryForObject("""
      insert into learning_document(
        pdf_asset_id,scope,subject_id,chapter_id,title,source_title,source_url,edition,
        page_start,page_end,status,version_no,created_by_staff_id
      ) values(?,'SUBJECT_BOOK',?,null,?,?,?,?,1,?,'DRAFT',1,?)
      returning id
      """, Long.class, bookId, subjectId, truncate(String.valueOf(job.get("source_title")), 240),
      job.get("source_title"), job.get("source_url"), job.get("edition"),
      job.get("page_count"), staffId);
    for (Map<String, Object> chapter : chapters) {
      long chapterId = ((Number) chapter.get("chapter_id")).longValue();
      long assetId = ((Number) chapter.get("pdf_asset_id")).longValue();
      int start = ((Number) chapter.get("page_start")).intValue();
      int end = ((Number) chapter.get("page_end")).intValue();
      jdbc.update("""
        update learning_pdf_asset set review_status='APPROVED',reviewed_by_staff_id=?,reviewed_at=now()
        where id=? and review_status in ('DRAFT','REVIEW')
        """, staffId, assetId);
      Long documentId = jdbc.queryForObject("""
        insert into learning_document(
          pdf_asset_id,scope,subject_id,chapter_id,title,source_title,source_url,edition,
          page_start,page_end,status,version_no,created_by_staff_id
        ) values(?,'CHAPTER_PDF',?,?,?,?,?,?,1,?,'DRAFT',1,?)
        returning id
        """, Long.class, assetId, subjectId, chapterId, chapter.get("chapter_title"), job.get("source_title"),
        job.get("source_url"), job.get("edition"), chapter.get("page_count"), staffId);
      jdbc.update("update source_ingestion_chapter set learning_document_id=?,updated_at=now() where id=?",
          documentId, chapter.get("id"));
      jdbc.update("""
        insert into chapter_source(chapter_id,source_id,source_chapter_title,source_locator,
          coverage_status,coverage_type,notes,updated_at)
        values(?,?,?,?, 'VERIFIED','TEXTBOOK_PDF',?,now())
        on conflict(chapter_id,source_id,coverage_type) do update set
          source_chapter_title=excluded.source_chapter_title,source_locator=excluded.source_locator,
          coverage_status='VERIFIED',notes=excluded.notes,updated_at=now()
        """, chapterId, sourceId, chapter.get("chapter_title"),
        "Source PDF pages " + start + "–" + end,
        "Reviewed source PDF; chapter PDF stored as learning document " + documentId + ".");
    }
    jdbc.update("""
      update content_source set learning_pdf_asset_id=?,status='APPROVED',updated_at=now()
      where id=?
      """, bookId, sourceId);
    jdbc.update("""
      update source_ingestion_job set status='APPROVED',reviewed_by_staff_id=?,reviewed_at=now(),updated_at=now()
      where id=?
      """, staffId, jobId);
    staffAudit.recordAction(context, "/api/v1/admin/source-ingestion/jobs/" + jobId + "/approve",
        "Approved the source book and chapter PDFs into the admin library as unpublished drafts.");
    return jobDetails(jobId);
  }

  @PostMapping("/jobs/{jobId}/reject")
  @Transactional
  public Map<String, Object> rejectJob(
      @PathVariable long jobId,
      @RequestAttribute(value = "authContext", required = false) AuthContext context) {
    context = requireReviewer(context);
    Map<String, Object> job = jobRow(jobId);
    if ("APPROVED".equals(String.valueOf(job.get("status")))) throw badRequest("An approved ingestion is immutable; archive its learning documents instead.");
    if (!List.of("REVIEW", "FAILED").contains(String.valueOf(job.get("status")))) {
      throw badRequest("Only a failed or in-review ingestion can be rejected.");
    }
    jdbc.update("""
      update learning_pdf_asset set review_status='REJECTED',reviewed_by_staff_id=?,reviewed_at=now()
      where id in (
        select pdf_asset_id from source_ingestion_chapter
        where job_id=? and pdf_asset_id is not null
        union select book_asset_id from source_ingestion_job
        where id=? and book_asset_id is not null
      ) and review_status in ('DRAFT','REVIEW')
      """, context.staffId(), jobId, jobId);
    jdbc.update("update content_source set status='REJECTED',updated_at=now() where id=?", job.get("source_id"));
    jdbc.update("""
      update source_ingestion_job set status='REJECTED',reviewed_by_staff_id=?,reviewed_at=now(),updated_at=now()
      where id=?
      """, context.staffId(), jobId);
    staffAudit.recordAction(context, "/api/v1/admin/source-ingestion/jobs/" + jobId + "/reject",
        "Rejected a textbook ingestion candidate.");
    return jobDetails(jobId);
  }

  @GetMapping(value = "/jobs/{jobId}/pages/{pageNumber}", produces = MediaType.IMAGE_PNG_VALUE)
  public ResponseEntity<byte[]> previewSourcePage(
      @PathVariable long jobId, @PathVariable int pageNumber,
      @RequestParam(required = false) Long chapterRowId,
      @RequestAttribute(value = "authContext", required = false) AuthContext context) {
    requireReviewer(context);
    Map<String, Object> job = jobRow(jobId);
    Long assetId;
    int pageCount;
    if (chapterRowId == null) {
      if (job.get("book_asset_id") == null) throw notFound("Source PDF", jobId);
      assetId = ((Number) job.get("book_asset_id")).longValue();
      pageCount = ((Number) job.get("page_count")).intValue();
    } else {
      List<Map<String, Object>> rows = jdbc.queryForList("""
        select ch.pdf_asset_id,a.page_count from source_ingestion_chapter ch
        join learning_pdf_asset a on a.id=ch.pdf_asset_id where ch.id=? and ch.job_id=?
        """, chapterRowId, jobId);
      if (rows.isEmpty()) throw notFound("Chapter PDF", chapterRowId);
      assetId = ((Number) rows.getFirst().get("pdf_asset_id")).longValue();
      pageCount = ((Number) rows.getFirst().get("page_count")).intValue();
    }
    if (pageNumber < 1 || pageNumber > pageCount) throw notFound("PDF page", pageNumber);
    byte[] bytes = jdbc.queryForObject("select pdf_bytes from learning_pdf_asset where id=?", byte[].class, assetId);
    try (PDDocument pdf = Loader.loadPDF(bytes); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      BufferedImage image = new PDFRenderer(pdf).renderImageWithDPI(pageNumber - 1, 90f, ImageType.RGB);
      if (!javax.imageio.ImageIO.write(image, "png", output)) throw new IllegalStateException("PNG rendering is unavailable.");
      image.flush();
      HttpHeaders headers = new HttpHeaders();
      headers.setContentType(MediaType.IMAGE_PNG);
      headers.setCacheControl(CacheControl.noStore());
      headers.set("X-Content-Type-Options", "nosniff");
      headers.set("X-Robots-Tag", "noindex, noarchive");
      return new ResponseEntity<>(output.toByteArray(), headers, HttpStatus.OK);
    } catch (IOException ex) {
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "This PDF page could not be rendered.", ex);
    }
  }


  static List<Map<String, Object>> chaptersReadyForPublishing(List<Map<String, Object>> candidates) {
    if (candidates == null || candidates.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "Split the source book into chapter PDFs before approval.");
    }
    if (candidates.stream().anyMatch(row ->
        !List.of("APPROVED", "REJECTED").contains(String.valueOf(row.get("status"))))) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "Review every chapter mapping as approved or rejected before adding the book to the library.");
    }
    List<Map<String, Object>> approved = candidates.stream()
        .filter(row -> "APPROVED".equals(String.valueOf(row.get("status"))))
        .toList();
    if (approved.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "Approve at least one chapter before adding the book to the library.");
    }
    return approved;
  }

  private Map<String, Object> jobDetails(long jobId) {
    List<Map<String, Object>> rows = jdbc.queryForList("""
      select j.id as job_id,j.source_id,j.subject_id,j.source_title,j.source_url,j.final_pdf_url,j.edition,
             j.language,j.book_asset_id,j.page_count,j.detected_outline::text as detected_outline,j.status,j.error_message,
             j.created_at,j.updated_at,j.reviewed_at,c.code as class_code,c.display_name as class_name,
             s.code as subject_code,s.display_name as subject_name,a.title as book_asset_title,
             a.original_filename,a.file_size_bytes,a.sha256,a.review_status as book_review_status
      from source_ingestion_job j join curriculum_subject s on s.id=j.subject_id
      join curriculum_class c on c.id=s.class_id
      left join learning_pdf_asset a on a.id=j.book_asset_id where j.id=?
      """, jobId);
    if (rows.isEmpty()) throw notFound("Source ingestion job", jobId);
    Map<String, Object> result = new LinkedHashMap<>(rows.getFirst());
    result.put("chapters", jdbc.queryForList("""
      select ch.id as chapter_row_id,ch.chapter_id,ch.chapter_title,ch.page_start,ch.page_end,
             ch.pdf_asset_id,ch.learning_document_id,ch.status,ch.review_notes,ch.reviewed_at,
             c.code as chapter_code,c.display_name as curriculum_chapter_name,a.title as pdf_title,
             a.page_count as pdf_page_count,a.sha256 as pdf_sha256,a.review_status as pdf_review_status
      from source_ingestion_chapter ch join curriculum_chapter c on c.id=ch.chapter_id
      left join learning_pdf_asset a on a.id=ch.pdf_asset_id
      where ch.job_id=? order by ch.page_start,ch.id
      """, jobId));
    return result;
  }

  private Map<String, Object> jobRow(long jobId) {
    List<Map<String, Object>> rows = jdbc.queryForList("select * from source_ingestion_job where id=?", jobId);
    if (rows.isEmpty()) throw notFound("Source ingestion job", jobId);
    return rows.getFirst();
  }

  private Long storePendingAsset(String title, String filename, byte[] bytes, int pages, String hash,
      String sourceReference, long sourceId, Long staffId) {
    List<Map<String, Object>> existing = jdbc.queryForList("select id from learning_pdf_asset where sha256=?", hash);
    if (!existing.isEmpty()) {
      long existingId = ((Number) existing.getFirst().get("id")).longValue();
      jdbc.update("""
        update learning_pdf_asset set review_status='REVIEW',
          source_content_id=coalesce(source_content_id,?)
        where id=? and review_status='REJECTED'
        """, sourceId, existingId);
      return existingId;
    }
    return jdbc.queryForObject("""
      insert into learning_pdf_asset(
        title,original_filename,sha256,file_size_bytes,page_count,pdf_bytes,source_kind,
        source_reference,created_by_staff_id,review_status,source_content_id
      ) values(?,?,?,?,?,?,'SOURCE_INGESTION',?,?, 'REVIEW',?)
      returning id
      """, Long.class, truncate(title, 240), truncate(filename, 255), hash, bytes.length, pages, bytes,
      sourceReference, staffId, sourceId);
  }

  private byte[] extractRange(byte[] sourceBytes, int start, int end) {
    try (PDDocument source = Loader.loadPDF(sourceBytes); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      Splitter splitter = new Splitter();
      splitter.setStartPage(start);
      splitter.setEndPage(end);
      splitter.setSplitAtPage(end - start + 1);
      List<PDDocument> pieces = splitter.split(source);
      if (pieces.isEmpty()) throw new IllegalArgumentException("The selected chapter page range produced no PDF.");
      try {
        pieces.getFirst().save(output);
      } finally {
        for (PDDocument piece : pieces) {
          try { piece.close(); } catch (IOException ignored) { }
        }
      }
      byte[] result = output.toByteArray();
      if (result.length < 5 || result.length > SourcePdfDownloadService.MAX_PDF_BYTES) {
        throw badRequest("A split chapter PDF is empty or exceeds the 50 MB limit.");
      }
      return result;
    } catch (IOException ex) {
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "The selected chapter pages could not be split.", ex);
    }
  }

  private String sha256(byte[] bytes) {
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    catch (NoSuchAlgorithmException ex) { throw new IllegalStateException("SHA-256 unavailable.", ex); }
  }

  private AuthContext requireReviewer(AuthContext context) {
    return authorization.requirePermission(context, "CONTENT_REVIEW");
  }

  private String requiredText(Object value, String name, int max) {
    String text = value == null ? "" : String.valueOf(value).trim();
    if (text.isEmpty() || text.length() > max) throw badRequest(name + " is required and must be " + max + " characters or fewer.");
    return text;
  }

  private String optionalText(Object value, int max) {
    if (value == null || String.valueOf(value).isBlank()) return null;
    String text = String.valueOf(value).trim();
    if (text.length() > max) throw badRequest("A source field is too long.");
    return text;
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
      long number = value instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(value).trim());
      if (number <= 0) throw badRequest(name + " must be a positive integer.");
      return number;
    } catch (NumberFormatException ex) {
      throw badRequest(name + " must be a valid integer.");
    }
  }

  private int integerValue(Object value, String name, int min, int max) {
    try {
      int number = value instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(value).trim());
      if (number < min || number > max) throw badRequest(name + " must be between " + min + " and " + max + ".");
      return number;
    } catch (NumberFormatException ex) {
      throw badRequest(name + " must be a valid integer.");
    }
  }

  private String truncate(String value, int max) {
    return value.length() <= max ? value : value.substring(0, max);
  }

  private ResponseStatusException badRequest(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  private ResponseStatusException notFound(String type, long id) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, type + " not found: " + id);
  }

  private record ChapterCandidate(long chapterId, String title, int pageStart, int pageEnd, String chapterCode) {}
}
