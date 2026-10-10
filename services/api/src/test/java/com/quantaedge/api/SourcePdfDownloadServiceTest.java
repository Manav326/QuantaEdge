package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class SourcePdfDownloadServiceTest {
  private final SourcePdfDownloadService service = new SourcePdfDownloadService();

  @Test
  void exactCachedNcertEntryIsServedWithoutNetwork() throws Exception {
    Path root = Files.createTempDirectory("qe-textbook-cache-test");
    Path books = root.resolve("hindi/books");
    Files.createDirectories(books);
    byte[] bytes = "%PDF-1.4\ncache fixture\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    Path pdf = books.resolve("ganit.pdf");
    Files.write(pdf, bytes);
    String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    String catalogUrl = "https://ncert.nic.in/textbook.php?fhgp1=0-14";
    Map<String, Object> book = Map.of(
        "book_id", "ncert-c6-hindi-fhgp1",
        "publisher", "NCERT",
        "title", "गणित प्रकाश",
        "source_url", "https://ncert.nic.in/textbook.php?ln=en",
        "catalog_entry_url", catalogUrl,
        "pdf_url", "https://ncert.nic.in/textbook/pdf/fhgp1dd.zip",
        "file", "books/ganit.pdf",
        "sha256", hash);
    new ObjectMapper().writeValue(root.resolve("hindi/index.json").toFile(), Map.of("books", List.of(book)));

    SourcePdfDownloadService cached = new SourcePdfDownloadService(root, false, new ObjectMapper());
    SourcePdfDownloadService.DownloadedPdf result = cached.download(catalogUrl, "गणित प्रकाश");

    assertArrayEquals(bytes, result.bytes());
    assertEquals(catalogUrl, result.resolvedUrl());
    org.junit.jupiter.api.Assertions.assertTrue(result.filename().endsWith(".pdf"));
  }

  @Test
  void cacheOnlyModeRejectsMissingBookBeforeAnyNetworkCall() throws Exception {
    Path root = Files.createTempDirectory("qe-textbook-cache-only-test");
    SourcePdfDownloadService cacheOnly = new SourcePdfDownloadService(root, true, new ObjectMapper());

    IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
        () -> cacheOnly.download("https://ncert.nic.in/textbook.php?ln=en", "Missing textbook"));

    org.junit.jupiter.api.Assertions.assertTrue(error.getMessage().contains("textbook-cache-only"));
  }

  @Test
  void rejectsNonHttpsSourceUrlsBeforeAnyNetworkCall() {
    IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
        () -> service.safeUri("http://example.com/book.pdf"));
    assertEquals("Source downloads require a public HTTPS URL.", error.getMessage());
  }

  @Test
  void rejectsLocalHostSourceUrlsBeforeAnyNetworkCall() {
    IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
        () -> service.safeUri("https://localhost/book.pdf"));
    assertEquals("Private and local network URLs cannot be used as source URLs.", error.getMessage());
  }

  @Test
  void rejectsUrlsWithEmbeddedCredentials() {
    IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
        () -> service.safeUri("https://admin:secret@example.com/book.pdf"));
    assertEquals("Source downloads require a public HTTPS URL.", error.getMessage());
  }
}
