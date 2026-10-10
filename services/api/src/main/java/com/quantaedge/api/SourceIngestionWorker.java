package com.quantaedge.api;

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
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/** Executes network/PDF work away from the request thread and leaves files in REVIEW. */
@Component
public class SourceIngestionWorker {
  private final JdbcTemplate jdbc;
  private final SourcePdfDownloadService downloader;

  public SourceIngestionWorker(JdbcTemplate jdbc, SourcePdfDownloadService downloader) {
    this.jdbc = jdbc;
    this.downloader = downloader;
  }

  @Async("sourceIngestionExecutor")
  public void process(long jobId) {
    try {
      Map<String, Object> job = jdbc.queryForMap("""
        select j.id,j.source_id,j.source_title,j.source_url,j.book_asset_id,j.created_by_staff_id
        from source_ingestion_job j where j.id=?
        """, jobId);
      byte[] bytes;
      String resolvedUrl;
      String filename;
      Long assetId = job.get("book_asset_id") == null ? null : ((Number) job.get("book_asset_id")).longValue();
      if (assetId != null) {
        bytes = jdbc.queryForObject("select pdf_bytes from learning_pdf_asset where id=?", byte[].class, assetId);
        resolvedUrl = String.valueOf(job.get("source_url"));
        filename = "existing-source.pdf";
      } else {
        SourcePdfDownloadService.DownloadedPdf download =
            downloader.download(String.valueOf(job.get("source_url")), String.valueOf(job.get("source_title")));
        bytes = download.bytes();
        resolvedUrl = download.resolvedUrl();
        filename = download.filename();
      }
      if (bytes == null || bytes.length < 5 || bytes.length > SourcePdfDownloadService.MAX_PDF_BYTES) {
        throw new IllegalArgumentException("The stored source PDF is empty or exceeds the 50 MB limit.");
      }
      int pages;
      List<Map<String, Object>> outline;
      try (PDDocument pdf = Loader.loadPDF(bytes)) {
        if (pdf.isEncrypted()) throw new IllegalArgumentException("Password-protected PDFs are not supported.");
        pages = pdf.getNumberOfPages();
        if (pages < 1 || pages > 2000) throw new IllegalArgumentException("Books must contain 1–2,000 PDF pages.");
        outline = detectOutline(pdf);
      }
      String hash = sha256(bytes);
      if (assetId == null) {
        List<Map<String, Object>> sameHash = jdbc.queryForList(
            "select id from learning_pdf_asset where sha256=?", hash);
        if (!sameHash.isEmpty()) {
          assetId = ((Number) sameHash.getFirst().get("id")).longValue();
        } else {
          String sourceReference = "source-ingestion:" + jobId + ":" + resolvedUrl;
          assetId = jdbc.queryForObject("""
            insert into learning_pdf_asset(
              title,original_filename,sha256,file_size_bytes,page_count,pdf_bytes,
              source_kind,source_reference,created_by_staff_id,review_status,source_content_id
            ) values(?,?,?,?,?,?,'SOURCE_INGESTION',?,?, 'REVIEW',?)
            returning id
            """, Long.class, String.valueOf(job.get("source_title")), safeFilename(filename), hash, bytes.length,
            pages, bytes, sourceReference, job.get("created_by_staff_id"), job.get("source_id"));
        }
      }
      jdbc.update("""
        update content_source set learning_pdf_asset_id=?,checksum=?,accessed_at=now(),status='REVIEW',updated_at=now()
        where id=?
        """, assetId, hash, job.get("source_id"));
      jdbc.update("""
        update source_ingestion_job set book_asset_id=?,page_count=?,final_pdf_url=?,
          detected_outline=cast(? as jsonb),status='REVIEW',error_message=null,updated_at=now()
        where id=?
        """, assetId, pages, resolvedUrl, outlineJson(outline), jobId);
    } catch (Exception ex) {
      String message = ex.getMessage() == null ? "The source could not be processed." : ex.getMessage();
      if (message.length() > 1200) message = message.substring(0, 1200);
      try {
        jdbc.update("""
          update source_ingestion_job set status='FAILED',error_message=?,updated_at=now()
          where id=? and status='DOWNLOADING'
          """, message, jobId);
      } catch (Exception ignored) { }
    }
  }

  private List<Map<String, Object>> detectOutline(PDDocument pdf) {
    List<Map<String, Object>> found = new ArrayList<>();
    PDDocumentOutline root = pdf.getDocumentCatalog().getDocumentOutline();
    if (root != null) {
      for (PDOutlineItem item : root.children()) {
        try {
          PDPage page = item.findDestinationPage(pdf);
          if (page == null) continue;
          int pageNo = pdf.getPages().indexOf(page) + 1;
          if (pageNo < 1 || pageNo > pdf.getNumberOfPages()) continue;
          String title = item.getTitle() == null ? "" : item.getTitle().trim();
          if (!title.isBlank()) found.add(new LinkedHashMap<>(Map.of("title", title, "pageStart", pageNo)));
        } catch (IOException | RuntimeException ignored) { }
      }
    }
    found.sort((left, right) -> Integer.compare(
        ((Number) left.get("pageStart")).intValue(), ((Number) right.get("pageStart")).intValue()));
    List<Map<String, Object>> distinct = new ArrayList<>();
    for (Map<String, Object> row : found) {
      if (!distinct.isEmpty()
          && ((Number) distinct.getLast().get("pageStart")).intValue()
              == ((Number) row.get("pageStart")).intValue()) continue;
      distinct.add(row);
    }
    if (!distinct.isEmpty()) {
      for (int i = 0; i < distinct.size(); i++) {
        int start = ((Number) distinct.get(i).get("pageStart")).intValue();
        int end = i + 1 < distinct.size()
            ? ((Number) distinct.get(i + 1).get("pageStart")).intValue() - 1 : pdf.getNumberOfPages();
        if (end >= start) distinct.get(i).put("pageEnd", end);
      }
      return distinct;
    }
    // Some scanned books have no bookmarks. These are conservative suggestions only, for reviewer correction.
    java.util.regex.Pattern heading = java.util.regex.Pattern.compile(
        "(?im)^\\s*(?:chapter\\s+[0-9०-९]+|अध्याय\\s*[0-9०-९]+|पाठ\\s*[0-9०-९]+)\\s*[:.\\-—]?\\s*([^\\r\\n]{0,100})");
    int limit = Math.min(pdf.getNumberOfPages(), 500);
    for (int i = 0; i < limit; i++) {
      int pageNo = i + 1;
      try {
        org.apache.pdfbox.text.PDFTextStripper stripper = new org.apache.pdfbox.text.PDFTextStripper();
        stripper.setStartPage(pageNo);
        stripper.setEndPage(pageNo);
        java.util.regex.Matcher matcher = heading.matcher(stripper.getText(pdf));
        if (matcher.find()) {
          String title = matcher.group().trim();
          if (!title.isBlank()) found.add(new LinkedHashMap<>(Map.of("title", title, "pageStart", pageNo)));
        }
      } catch (IOException ignored) { }
    }
    found.sort((left, right) -> Integer.compare(
        ((Number) left.get("pageStart")).intValue(), ((Number) right.get("pageStart")).intValue()));
    for (int i = 0; i < found.size(); i++) {
      int start = ((Number) found.get(i).get("pageStart")).intValue();
      int end = i + 1 < found.size() ? ((Number) found.get(i + 1).get("pageStart")).intValue() - 1
          : pdf.getNumberOfPages();
      found.get(i).put("pageEnd", Math.max(start, end));
    }
    return found.stream().limit(100).toList();
  }

  private String outlineJson(List<Map<String, Object>> rows) {
    StringBuilder result = new StringBuilder("[");
    for (int i = 0; i < rows.size(); i++) {
      if (i > 0) result.append(',');
      Map<String, Object> row = rows.get(i);
      result.append("{\"title\":\"").append(escapeJson(String.valueOf(row.get("title"))))
          .append("\",\"pageStart\":").append(((Number) row.get("pageStart")).intValue())
          .append(",\"pageEnd\":").append(((Number) row.getOrDefault("pageEnd", row.get("pageStart"))).intValue())
          .append('}');
    }
    return result.append(']').toString();
  }

  private String escapeJson(String value) {
    StringBuilder escaped = new StringBuilder();
    for (int i = 0; i < value.length(); i++) {
      char ch = value.charAt(i);
      if (ch == '\\') escaped.append("\\\\");
      else if (ch == '"') escaped.append("\\\"");
      else if (ch == '\n') escaped.append("\\n");
      else if (ch == '\r') escaped.append("\\r");
      else if (ch == '\t') escaped.append("\\t");
      else if (ch < 0x20) escaped.append(String.format("\\u%04x", (int) ch));
      else escaped.append(ch);
    }
    return escaped.toString();
  }

  private String safeFilename(String name) {
    String value = name == null ? "source-book.pdf" : name.replace('\\', '/');
    value = value.substring(value.lastIndexOf('/') + 1).replaceAll("[^A-Za-z0-9._() -]", "_");
    if (value.isBlank()) value = "source-book.pdf";
    if (!value.toLowerCase(Locale.ROOT).endsWith(".pdf")) value += ".pdf";
    return value.length() > 255 ? value.substring(value.length() - 255) : value;
  }

  private String sha256(byte[] bytes) {
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    catch (NoSuchAlgorithmException ex) { throw new IllegalStateException("SHA-256 unavailable.", ex); }
  }
}
