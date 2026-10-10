package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class SourcePdfDownloadServiceTest {
  private final SourcePdfDownloadService service = new SourcePdfDownloadService();

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
