package com.quantaedge.api;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
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
    Map<String, Object> source = jdbc.queryForMap("""
      select a.id,a.sha256,a.review_status,i.class_id,i.subject_id,i.language
      from learning_pdf_asset a
      join learning_book_intake i on i.pdf_asset_id=a.id
      where a.id=?
      """, assetId);
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
        select a.sha256,a.page_count,i.class_id,i.subject_id,i.language
        from learning_pdf_asset a join learning_book_intake i on i.pdf_asset_id=a.id
        where a.id=? and a.review_status='APPROVED'
        """, assetId);
      if (rows.isEmpty()) throw new IllegalArgumentException("The approved PDF or its class/subject/language classification is missing.");
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
              quality = "REVIEW";
            } else if (ocrAvailable && attemptedOcr < MAX_OCR_PAGES_PER_JOB) {
              attemptedOcr++;
              String ocrText = normalizeText(ocr.extractPageText(document, page - 1, language));
              if (ocrText.length() >= MIN_EMBEDDED_TEXT_CHARS) {
                text = ocrText;
                method = "OCR";
                quality = "REVIEW";
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
        String status = failed > 0 || processed < pages ? "PARTIAL" : "COMPLETE";
        jdbc.update("""
          update textbook_extraction_job set status=?,total_pages=?,processed_pages=?,extracted_pages=?,
            ocr_pages=?,failed_pages=?,last_error=?,finished_at=now(),updated_at=now()
          where pdf_asset_id=? and source_sha256=?
          """, status, pages, processed, extracted, ocrCount, failed,
          pageErrors.isEmpty() ? null : String.join("; ", pageErrors), assetId, hash);
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
    try {
      jdbc.query("select pdf_bytes from learning_pdf_asset where id=?",
          (ResultSetExtractor<Void>) rs -> {
            if (!rs.next()) throw new SQLException("PDF asset was not found.");
            try (InputStream input = rs.getBinaryStream(1)) {
              if (input == null) throw new SQLException("PDF asset has no stored bytes.");
              Files.copy(input, path, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ex) {
              throw new SQLException("Unable to stream PDF bytes to a temporary file.", ex);
            }
            return null;
          }, assetId);
      if (Files.size(path) < 5) throw new IOException("Stored PDF bytes are empty or invalid.");
      return path;
    } catch (Exception ex) {
      Files.deleteIfExists(path);
      if (ex instanceof IOException io) throw io;
      throw new IOException("Unable to prepare the PDF for extraction.", ex);
    }
  }

  private boolean pageAlreadyExtracted(long assetId, String hash, int page) {
    Integer count = jdbc.queryForObject("""
      select count(*) from textbook_extracted_page
      where pdf_asset_id=? and source_sha256=? and page_no=?
        and quality_status in ('REVIEW','LOW_TEXT') and extraction_method in ('PDF_TEXT','OCR')
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
      order by case when status='PUBLISHED' then 0 else 1 end,id desc limit 1
      """, assetId, language, page, page);
    if (!mapped.isEmpty()) {
      documentId = ((Number) mapped.getFirst().get("id")).longValue();
      chapterId = mapped.getFirst().get("chapter_id") == null
          ? null : ((Number) mapped.getFirst().get("chapter_id")).longValue();
    } else {
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
