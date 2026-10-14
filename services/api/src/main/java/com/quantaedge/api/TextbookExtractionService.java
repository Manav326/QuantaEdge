package com.quantaedge.api;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Extracts whole-book PDF text asynchronously into a page-level, source-aware index.
 * PDFs are streamed to a temporary file so large textbooks are not copied into one heap byte array.
 * Extracted pages remain private/reviewable and are never published by this worker.
 */
@Component
public class TextbookExtractionService {
  private static final int MIN_EMBEDDED_TEXT_CHARS = 35;
  private static final int MAX_OCR_PAGES_PER_JOB = 250;

  private final JdbcTemplate jdbc;
  private final PdfOcrService ocr;

  public TextbookExtractionService(JdbcTemplate jdbc, PdfOcrService ocr) {
    this.jdbc = jdbc;
    this.ocr = ocr;
  }

  public Map<String, Object> queue(long assetId, long staffId) {
    List<Map<String, Object>> sources = jdbc.queryForList("""
      select a.id,a.sha256,a.review_status,
        coalesce(i.class_id,
          (select s.class_id from source_ingestion_job j join curriculum_subject s on s.id=j.subject_id
           where j.book_asset_id=a.id order by j.id desc limit 1),
          (select s.class_id from learning_document d join curriculum_subject s on s.id=d.subject_id
           where d.pdf_asset_id=a.id and d.status<>'ARCHIVED' order by d.id desc limit 1)) as class_id,
        coalesce(i.subject_id,
          (select j.subject_id from source_ingestion_job j where j.book_asset_id=a.id order by j.id desc limit 1),
          (select d.subject_id from learning_document d where d.pdf_asset_id=a.id and d.status<>'ARCHIVED' order by d.id desc limit 1)) as subject_id,
        coalesce(i.language,
          (select j.language from source_ingestion_job j where j.book_asset_id=a.id order by j.id desc limit 1),
          (select d.language from learning_document d where d.pdf_asset_id=a.id and d.status<>'ARCHIVED' order by d.id desc limit 1)) as language
      from learning_pdf_asset a
      left join learning_book_intake i on i.pdf_asset_id=a.id
      where a.id=?
      """, assetId);
    if (sources.isEmpty()) throw new IllegalArgumentException("The PDF asset was not found.");
    Map<String, Object> source = sources.getFirst();
    if (source.get("subject_id") == null || source.get("language") == null) {
      throw new IllegalArgumentException("Classify this PDF with its class, subject, and language before extraction.");
    }
    if (!"APPROVED".equals(String.valueOf(source.get("review_status")))) {
      throw new IllegalArgumentException("Approve the source PDF before extracting it.");
    }
    String hash = String.valueOf(source.get("sha256"));
    jdbc.update("""
      insert into textbook_extraction_job(pdf_asset_id,source_sha256,status,requested_by_staff_id,requested_at,updated_at)
      values(?,?,'PENDING',?,now(),now())
      on conflict(pdf_asset_id) do update set
        source_sha256=excluded.source_sha256,status='PENDING',total_pages=0,processed_pages=0,
        extracted_pages=0,ocr_pages=0,failed_pages=0,last_error=null,
        requested_by_staff_id=excluded.requested_by_staff_id,requested_at=now(),
        started_at=null,finished_at=null,updated_at=now()
      where textbook_extraction_job.status not in ('RUNNING')
        or textbook_extraction_job.source_sha256<>excluded.source_sha256
      """, assetId, hash, staffId);
    Map<String, Object> job = jdbc.queryForMap("""
      select id,pdf_asset_id,source_sha256,status,total_pages,processed_pages,extracted_pages,
        ocr_pages,failed_pages,last_error,requested_at,started_at,finished_at,updated_at
      from textbook_extraction_job where pdf_asset_id=?
      """, assetId);
    return job;
  }

  public Map<String, Object> status(long assetId) {
    List<Map<String, Object>> rows = jdbc.queryForList("""
      select j.id,j.pdf_asset_id,j.source_sha256,j.status,j.total_pages,j.processed_pages,
        j.extracted_pages,j.ocr_pages,j.failed_pages,j.last_error,j.requested_at,j.started_at,
        j.finished_at,j.updated_at
      from textbook_extraction_job j where j.pdf_asset_id=?
      """, assetId);
    if (rows.isEmpty()) throw new IllegalArgumentException("No extraction job exists for this PDF asset.");
    return rows.getFirst();
  }

  @Async("sourceIngestionExecutor")
  public void start(long assetId) {
    Integer claimed = jdbc.update("""
      update textbook_extraction_job set status='RUNNING',started_at=now(),updated_at=now()
      where pdf_asset_id=? and status='PENDING'
      """, assetId);
    if (claimed == null || claimed != 1) return;
    processNow(assetId);
  }

  private void processNow(long assetId) {
    Path pdfPath = null;
    try {
      List<Map<String, Object>> rows = jdbc.queryForList("""
        select a.sha256,a.page_count,
          coalesce(i.class_id,
            (select s.class_id from source_ingestion_job j join curriculum_subject s on s.id=j.subject_id
             where j.book_asset_id=a.id order by j.id desc limit 1),
            (select s.class_id from learning_document d join curriculum_subject s on s.id=d.subject_id
             where d.pdf_asset_id=a.id and d.status<>'ARCHIVED' order by d.id desc limit 1)) as class_id,
          coalesce(i.subject_id,
            (select j.subject_id from source_ingestion_job j where j.book_asset_id=a.id order by j.id desc limit 1),
            (select d.subject_id from learning_document d where d.pdf_asset_id=a.id and d.status<>'ARCHIVED' order by d.id desc limit 1)) as subject_id,
          coalesce(i.language,
            (select j.language from source_ingestion_job j where j.book_asset_id=a.id order by j.id desc limit 1),
            (select d.language from learning_document d where d.pdf_asset_id=a.id and d.status<>'ARCHIVED' order by d.id desc limit 1)) as language
        from learning_pdf_asset a left join learning_book_intake i on i.pdf_asset_id=a.id
        where a.id=? and a.review_status='APPROVED'
        """, assetId);
      if (rows.isEmpty() || rows.getFirst().get("subject_id") == null
          || rows.getFirst().get("language") == null) {
        throw new IllegalArgumentException("The approved PDF or its class/subject/language classification is missing.");
      }
      Map<String, Object> source = rows.getFirst();
      String hash = String.valueOf(source.get("sha256"));
      String language = normalizeLanguage(String.valueOf(source.get("language")));
      long subjectId = ((Number) source.get("subject_id")).longValue();
      Long classId = source.get("class_id") == null ? null : ((Number) source.get("class_id")).longValue();

      jdbc.update("""
        update textbook_extraction_job set status='RUNNING',total_pages=?,processed_pages=0,
          extracted_pages=0,ocr_pages=0,failed_pages=0,last_error=null,started_at=now(),
          finished_at=null,updated_at=now()
        where pdf_asset_id=? and source_sha256=?
        """, ((Number) source.get("page_count")).intValue(), assetId, hash);

      // A changed source checksum invalidates every previous page; a retry of the same source
      // can reuse successfully extracted pages.
      jdbc.update("delete from textbook_extracted_page where pdf_asset_id=? and source_sha256<>?", assetId, hash);
      pdfPath = streamPdfToTempFile(assetId);
      int processed = 0;
      int extracted = 0;
      int ocrCount = 0;
      int failed = 0;
      int attemptedOcr = 0;
      List<String> pageErrors = new ArrayList<>();

      try (PDDocument document = Loader.loadPDF(pdfPath.toFile())) {
        if (document.isEncrypted()) throw new IllegalArgumentException("Password-protected PDFs are not supported.");
        int pages = document.getNumberOfPages();
        if (pages < 1 || pages > 2000) throw new IllegalArgumentException("Books must contain 1–2,000 PDF pages.");
        boolean ocrAvailable = ocr.supportsLanguage(language);
        PDFTextStripper stripper = new PDFTextStripper();

        for (int page = 1; page <= pages; page++) {
          if (pageAlreadyExtracted(assetId, hash, page)) {
            processed++;
            extracted++;
            continue;
          }
          String text = "";
          String method = "EMPTY";
          String quality = "OCR_REQUIRED";
          String error = null;
          try {
            stripper.setStartPage(page);
            stripper.setEndPage(page);
            text = normalizeText(stripper.getText(document));
            if (text.length() >= MIN_EMBEDDED_TEXT_CHARS) {
              method = "PDF_TEXT";
              quality = "READY";
            } else if (ocrAvailable && attemptedOcr < MAX_OCR_PAGES_PER_JOB) {
              attemptedOcr++;
              String ocrText = normalizeText(ocr.extractPageText(document, page - 1, language));
              if (ocrText.length() >= MIN_EMBEDDED_TEXT_CHARS) {
                text = ocrText;
                method = "OCR";
                quality = "READY";
                ocrCount++;
              } else {
                text = ocrText;
                method = "OCR";
                quality = "LOW_TEXT";
              }
            } else if (!ocrAvailable) {
              error = "Scanned/low-text page needs OCR; required Tesseract language data is unavailable.";
            } else {
              error = "OCR page budget reached; retry this book in another extraction batch.";
            }
            if (text.isBlank() && error == null) error = "No readable text was detected on this page.";
          } catch (Exception pageError) {
            method = "FAILED";
            quality = "FAILED";
            error = safeMessage(pageError);
            failed++;
            if (pageErrors.size() < 5) pageErrors.add("Page " + page + ": " + error);
          }

          savePage(assetId, hash, classId, subjectId, language, page, text, method, quality, error);
          processed++;
          if (!text.isBlank()) extracted++;
          if (page % 10 == 0 || page == pages) {
            jdbc.update("""
              update textbook_extraction_job set total_pages=?,processed_pages=?,extracted_pages=?,
                ocr_pages=?,failed_pages=?,last_error=?,updated_at=now()
              where pdf_asset_id=? and source_sha256=?
              """, pages, processed, extracted, ocrCount, failed,
              pageErrors.isEmpty() ? null : String.join("; ", pageErrors), assetId, hash);
          }
        }
        Long unresolvedPages = jdbc.queryForObject("""
          select count(*) from textbook_extracted_page
          where pdf_asset_id=? and source_sha256=? and quality_status<>'READY'
          """, Long.class, assetId, hash);
        boolean partial = failed > 0 || processed < pages || (unresolvedPages != null && unresolvedPages > 0);
        String status = partial ? "PARTIAL" : "COMPLETE";
        String summary = pageErrors.isEmpty() ? null : String.join("; ", pageErrors);
        if (unresolvedPages != null && unresolvedPages > 0) {
          String unresolvedSummary = unresolvedPages + " page(s) still need OCR or quality review.";
          summary = summary == null ? unresolvedSummary : summary + "; " + unresolvedSummary;
        }
        jdbc.update("""
          update textbook_extraction_job set status=?,total_pages=?,processed_pages=?,extracted_pages=?,
            ocr_pages=?,failed_pages=?,last_error=?,finished_at=now(),updated_at=now()
          where pdf_asset_id=? and source_sha256=?
          """, status, pages, processed, extracted, ocrCount, failed,
          summary, assetId, hash);
      }
    } catch (Exception ex) {
      String message = safeMessage(ex);
      jdbc.update("""
        update textbook_extraction_job set status='FAILED',last_error=?,finished_at=now(),updated_at=now()
        where pdf_asset_id=?
        """, message, assetId);
    } finally {
      if (pdfPath != null) {
        try { Files.deleteIfExists(pdfPath); } catch (IOException ignored) { }
      }
    }
  }

  private Path streamPdfToTempFile(long assetId) throws IOException {
    Path path = Files.createTempFile("quantaedge-textbook-", ".pdf");
    final int chunkSize = 1024 * 1024;
    try {
      Long size = jdbc.queryForObject(
          "select octet_length(pdf_bytes)::bigint from learning_pdf_asset where id=?", Long.class, assetId);
      if (size == null || size < 5 || size > 524288000L) {
        throw new IOException("Stored PDF bytes are empty, missing, or exceed the 500 MiB asset limit.");
      }
      try (OutputStream output = Files.newOutputStream(path)) {
        long offset = 1;
        while (offset <= size) {
          int length = (int) Math.min(chunkSize, size - offset + 1);
          byte[] chunk = jdbc.queryForObject("""
            select substring(pdf_bytes from ? for ?) from learning_pdf_asset where id=?
            """, byte[].class, Math.toIntExact(offset), length, assetId);
          if (chunk == null || chunk.length == 0) {
            throw new IOException("The stored PDF could not be read completely.");
          }
          output.write(chunk);
          offset += chunk.length;
        }
      }
      if (Files.size(path) != size) throw new IOException("The streamed PDF size does not match its stored size.");
      return path;
    } catch (Exception ex) {
      Files.deleteIfExists(path);
      if (ex instanceof IOException io) throw io;
      throw new IOException("Unable to stream PDF bytes to a temporary file.", ex);
    }
  }

  private boolean pageAlreadyExtracted(long assetId, String hash, int page) {
    Integer count = jdbc.queryForObject("""
      select count(*) from textbook_extracted_page
      where pdf_asset_id=? and source_sha256=? and page_no=?
        and quality_status in ('READY','LOW_TEXT') and extraction_method in ('PDF_TEXT','OCR')
      """, Integer.class, assetId, hash, page);
    return count != null && count > 0;
  }

  private void savePage(long assetId, String hash, Long classId, long subjectId, String language,
      int page, String text, String method, String quality, String error) {
    Long documentId = null;
    Long chapterId = null;
    List<Map<String, Object>> mapped = jdbc.queryForList("""
      select id,chapter_id from learning_document
      where pdf_asset_id=? and language=? and status<>'ARCHIVED' and page_start<=? and page_end>=?
      order by case when chapter_id is not null then 0 else 1 end,
        case when status='PUBLISHED' then 0 else 1 end,id desc limit 1
      """, assetId, language, page, page);
    if (!mapped.isEmpty()) {
      documentId = ((Number) mapped.getFirst().get("id")).longValue();
      chapterId = mapped.getFirst().get("chapter_id") == null
          ? null : ((Number) mapped.getFirst().get("chapter_id")).longValue();
    }
    if (chapterId == null) {
      List<Map<String, Object>> intakeRange = jdbc.queryForList("""
        select (r->>'chapter_id')::bigint as chapter_id
        from learning_book_intake i
        cross join lateral jsonb_array_elements(i.chapter_ranges) r
        where i.pdf_asset_id=? and (r->>'page_start')::integer<=?
          and (r->>'page_end')::integer>=?
        limit 1
        """, assetId, page, page);
      if (!intakeRange.isEmpty()) chapterId = ((Number) intakeRange.getFirst().get("chapter_id")).longValue();
    }
    Long ingestionJobId = jdbc.query("""
      select id from source_ingestion_job where book_asset_id=? order by id desc limit 1
      """, rs -> rs.next() ? rs.getLong(1) : null, assetId);

    jdbc.update("""
      insert into textbook_extracted_page(pdf_asset_id,learning_document_id,source_ingestion_job_id,
        class_id,subject_id,chapter_id,language,page_no,source_sha256,extracted_text,
        extraction_method,quality_status,extraction_error,extracted_at,updated_at)
      values(?,?,?,?,?,?,?,?,?,?,?,?,?,now(),now())
      on conflict(pdf_asset_id,page_no) do update set
        learning_document_id=excluded.learning_document_id,
        source_ingestion_job_id=excluded.source_ingestion_job_id,
        class_id=excluded.class_id,subject_id=excluded.subject_id,chapter_id=excluded.chapter_id,
        language=excluded.language,source_sha256=excluded.source_sha256,
        extracted_text=excluded.extracted_text,extraction_method=excluded.extraction_method,
        quality_status=excluded.quality_status,extraction_error=excluded.extraction_error,
        extracted_at=now(),updated_at=now()
      """, assetId, documentId, ingestionJobId, classId, subjectId, chapterId, language,
      page, hash, text, method, quality, error);
  }

  @Transactional
  public Map<String, Object> importGhcrBook(MultipartFile manifestFile, MultipartFile pagesFile,
      long staffId) throws IOException {
    if (manifestFile == null || pagesFile == null || manifestFile.isEmpty() || pagesFile.isEmpty()) {
      throw new IllegalArgumentException("Both the extraction manifest and page-text JSONL are required.");
    }
    if (manifestFile.getSize() > 2 * 1024 * 1024 || pagesFile.getSize() > 50L * 1024 * 1024) {
      throw new IllegalArgumentException("Manifest must be <=2 MiB and extracted page text <=50 MiB.");
    }
    ObjectMapper mapper = new ObjectMapper();
    JsonNode manifest = mapper.readTree(manifestFile.getInputStream());
    if (!"DRAFT_EXTRACTION_ONLY_REQUIRES_REVIEW".equals(manifest.path("content_state").asText())) {
      throw new IllegalArgumentException("This is not a draft-only QuantaEdge extraction manifest.");
    }
    if (!"SUBJECT_BOOK".equalsIgnoreCase(manifest.path("scope").asText())) {
      throw new IllegalArgumentException("Only complete-book page extractions can be imported through this endpoint.");
    }
    String bookId = manifest.path("book_id").asText("").trim();
    String sourceTitle = manifest.path("source_title").asText(manifest.path("title").asText("")).trim();
    String sourceUrl = manifest.path("source_url").asText("").trim();
    String edition = manifest.path("edition").asText("edition not recorded").trim();
    String sourceHash = manifest.path("original_book_sha256").asText("").toLowerCase(Locale.ROOT);
    int classNo = extractClassNumber(manifest);
    String language = normalizeLanguage(manifest.path("language").asText(""));
    String subjectName = manifest.path("subject").asText("").trim();
    int expectedPages = manifest.path("page_count").asInt(0);
    if (bookId.isBlank() || sourceTitle.isBlank() || !sourceHash.matches("[0-9a-f]{64}")
        || expectedPages < 1 || expectedPages > 2000) {
      throw new IllegalArgumentException("The manifest is missing a valid book ID, title, source hash, or page count.");
    }
    validateOfficialSourceUrl(sourceUrl);
    String subjectCode;
    String subjectLower = subjectName.toLowerCase(Locale.ROOT);
    if (subjectLower.contains("math") || subjectName.contains("गणित")) subjectCode = "maths";
    else if (subjectLower.contains("science") || subjectName.contains("विज्ञान")) subjectCode = "science";
    else throw new IllegalArgumentException("Only Maths and Science source books can be imported into this pilot.");
    List<Map<String, Object>> scopeRows = jdbc.queryForList("""
      select c.id as class_id,s.id as subject_id
      from curriculum_class c join curriculum_subject s on s.class_id=c.id
      where c.code=? and s.code=? and c.active=true and s.active=true
      """, String.valueOf(classNo), subjectCode);
    if (scopeRows.isEmpty()) {
      throw new IllegalArgumentException("No active QuantaEdge curriculum track matches this class and subject.");
    }
    long classId = ((Number) scopeRows.getFirst().get("class_id")).longValue();
    long subjectId = ((Number) scopeRows.getFirst().get("subject_id")).longValue();

    List<ExtractedPageInput> pages = new ArrayList<>();
    Set<Integer> pageNumbers = new HashSet<>();
    try (BufferedReader reader = new BufferedReader(
        new InputStreamReader(pagesFile.getInputStream(), StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.isBlank()) continue;
        JsonNode row = mapper.readTree(line);
        String rowBookId = row.path("book_id").asText("");
        String rowHash = row.path("original_book_sha256").asText("").toLowerCase(Locale.ROOT);
        String rowLanguage = normalizeLanguage(row.path("language").asText(""));
        int pageNo = row.path("source_book_page_number").asInt(0);
        String text = row.path("text").asText("");
        String reviewStatus = row.path("review_status").asText("");
        if (!bookId.equals(rowBookId) || !sourceHash.equals(rowHash) || !language.equals(rowLanguage)
            || !subjectName.equalsIgnoreCase(row.path("subject").asText(""))
            || !text.equals(text.stripTrailing()) || text.length() > 100_000
            || pageNo < 1 || pageNo > expectedPages || !pageNumbers.add(pageNo)) {
          throw new IllegalArgumentException("Extracted page metadata is inconsistent or contains duplicate/invalid page numbers.");
        }
        String quality = switch (reviewStatus) {
          case "TEXT_EXTRACTED_REQUIRES_CONTENT_REVIEW" -> "READY";
          case "LOW_TEXT_REVIEW", "NO_DEVANAGARI_REVIEW" -> "LOW_TEXT";
          case "NO_EXTRACTABLE_TEXT_REVIEW" -> "OCR_REQUIRED";
          default -> throw new IllegalArgumentException("Unknown page quality state in extraction output.");
        };
        String method = text.isBlank() ? "EMPTY" : "PDF_TEXT";
        pages.add(new ExtractedPageInput(pageNo, text, method, quality));
        if (pages.size() > expectedPages) {
          throw new IllegalArgumentException("The JSONL contains more pages than the source manifest.");
        }
      }
    }
    if (pages.size() != expectedPages) {
      throw new IllegalArgumentException("Expected " + expectedPages + " extracted page records but received " + pages.size() + ".");
    }
    for (int page = 1; page <= expectedPages; page++) {
      if (!pageNumbers.contains(page)) {
        throw new IllegalArgumentException("The extracted page set has a gap at source PDF page " + page + ".");
      }
    }

    Long sourceBookId = jdbc.queryForObject("""
      insert into textbook_source_book(registry_book_id,source_sha256,source_title,source_url,edition,
        class_id,subject_id,language,total_pages,status,imported_pages,imported_by_staff_id,
        imported_at,updated_at)
      values(?,?,?,?,?,?,?,?,?,'DRAFT',?,?,now(),now())
      on conflict(registry_book_id,source_sha256,language) do update set
        source_title=excluded.source_title,source_url=excluded.source_url,edition=excluded.edition,
        class_id=excluded.class_id,subject_id=excluded.subject_id,total_pages=excluded.total_pages,
        imported_pages=excluded.imported_pages,imported_by_staff_id=excluded.imported_by_staff_id,
        imported_at=now(),updated_at=now(),
        status=case when textbook_source_book.status='APPROVED' then 'APPROVED' else 'DRAFT' end
      returning id
      """, Long.class, bookId, sourceHash, sourceTitle, sourceUrl,
      edition.isBlank() ? "edition not recorded" : edition, classId, subjectId, language,
      expectedPages, pages.size(), staffId);
    for (ExtractedPageInput page : pages) {
      jdbc.update("""
        insert into textbook_extracted_page(pdf_asset_id,source_book_id,learning_document_id,
          source_ingestion_job_id,class_id,subject_id,chapter_id,language,page_no,source_sha256,
          extracted_text,extraction_method,quality_status,extraction_error,extracted_at,updated_at)
        values(null,?,null,null,?,?,null,?,?,?,?,?,?,null,now(),now())
        on conflict (source_book_id,page_no) where source_book_id is not null do update set
          class_id=excluded.class_id,subject_id=excluded.subject_id,language=excluded.language,
          source_sha256=excluded.source_sha256,extracted_text=excluded.extracted_text,
          extraction_method=excluded.extraction_method,quality_status=excluded.quality_status,
          extraction_error=null,extracted_at=now(),updated_at=now()
        """, sourceBookId, classId, subjectId, language, page.pageNo(), sourceHash,
        page.text(), page.method(), page.quality());
    }
    return Map.of("sourceBookId", sourceBookId, "bookId", bookId, "classNo", classNo,
        "subject", subjectCode, "language", language, "pagesImported", pages.size(),
        "status", "DRAFT", "message", "Imported as draft; explicitly approve the source before tutor retrieval.");
  }

  @Transactional
  public Map<String, Object> approveGhcrBook(long sourceBookId, long staffId) {
    int changed = jdbc.update("""
      update textbook_source_book set status='APPROVED',approved_by_staff_id=?,approved_at=now(),updated_at=now()
      where id=? and status<>'ARCHIVED'
      """, staffId, sourceBookId);
    if (changed != 1) throw new IllegalArgumentException("The GHCR source book was not found or is archived.");
    return jdbc.queryForMap("""
      select id as "sourceBookId",registry_book_id as "bookId",source_title as "title",
        class_id as "classId",subject_id as "subjectId",language,total_pages as "totalPages",
        imported_pages as "importedPages",status,approved_at as "approvedAt"
      from textbook_source_book where id=?
      """, sourceBookId);
  }

  private int extractClassNumber(JsonNode manifest) {
    String value = manifest.path("class").asText("");
    if (value.isBlank()) value = manifest.path("classes").toString();
    java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(?<!\\\\d)(6|7|8)(?!\\\\d)").matcher(value);
    if (!matcher.find()) throw new IllegalArgumentException("The source book is not tagged for QuantaEdge Class 6, 7, or 8.");
    return Integer.parseInt(matcher.group(1));
  }

  private void validateOfficialSourceUrl(String value) {
    try {
      URI uri = URI.create(value);
      String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
      boolean official = host.equals("ncert.nic.in") || host.endsWith(".ncert.nic.in")
          || host.equals("ncert.ncert.org.in") || host.endsWith(".ncert.ncert.org.in")
          || host.equals("scert.bihar.gov.in") || host.endsWith(".scert.bihar.gov.in")
          || host.equals("bstbpc.gov.in") || host.endsWith(".bstbpc.gov.in");
      if (!"https".equalsIgnoreCase(uri.getScheme()) || !official || uri.getUserInfo() != null) {
        throw new IllegalArgumentException("Only official HTTPS NCERT/SCERT source URLs are accepted.");
      }
    } catch (RuntimeException ex) {
      if (ex instanceof IllegalArgumentException && ex.getMessage() != null
          && ex.getMessage().startsWith("Only official")) throw ex;
      throw new IllegalArgumentException("The official source URL is missing or invalid.");
    }
  }

  private record ExtractedPageInput(int pageNo, String text, String method, String quality) {}

  private String normalizeLanguage(String value) {
    String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    if (normalized.equals("hi") || normalized.equals("hindi")) return "hi";
    if (normalized.equals("en") || normalized.equals("english")) return "en";
    throw new IllegalArgumentException("Book language must be Hindi or English.");
  }

  private String normalizeText(String value) {
    if (value == null) return "";
    return value.replace((char) 0, ' ').replaceAll("[\\t\\x0B\\f\\r]+", " ")
        .replaceAll(" *\\n *", "\n").replaceAll("\\n{3,}", "\n\n").trim();
  }

  private String safeMessage(Throwable error) {
    String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    return message.length() > 1200 ? message.substring(0, 1200) : message;
  }
}
