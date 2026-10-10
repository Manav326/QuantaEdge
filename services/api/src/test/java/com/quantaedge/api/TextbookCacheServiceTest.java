package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class TextbookCacheServiceTest {
  @Test
  void resolvesAndHashesWholeBookFromSelectedLanguageCache() throws Exception {
    Path root = Files.createTempDirectory("qe-ghcr-textbook-cache");
    Path books = root.resolve("hindi/books");
    Files.createDirectories(books);
    byte[] bytes = "%PDF-1.4\nwhole textbook fixture\n".getBytes(StandardCharsets.US_ASCII);
    Path bookPath = books.resolve("ncert-c6-hindi-fhgp1.pdf");
    Files.write(bookPath, bytes);
    String hash = sha256(bytes);
    writeIndex(root.resolve("hindi/index.json"), registryEntry(
        "ncert-c6-hindi-fhgp1", "गणित प्रकाश", 6, "hindi", "Mathematics", bookPath, hash, bytes.length));

    TextbookCacheService cache = new TextbookCacheService(root, new ObjectMapper());
    TextbookCacheService.CachedBook cached = cache.requireBook("hindi", "ncert-c6-hindi-fhgp1");

    assertEquals(hash, cached.sha256());
    assertEquals("गणित प्रकाश", cached.title());
    assertEquals(6, cached.classNo());
    assertEquals(bytes.length, cached.bytes());
    assertTrue(cached.path().startsWith(root.resolve("hindi")));
    assertEquals(1, cache.list("hindi", 6).size());
    assertEquals(0, cache.list("english", 6).size());
  }

  @Test
  void rejectsCacheFileWhenWholeBookChecksumWasModified() throws Exception {
    Path root = Files.createTempDirectory("qe-ghcr-textbook-bad-hash");
    Path books = root.resolve("english/books");
    Files.createDirectories(books);
    byte[] original = "%PDF-1.4\noriginal\n".getBytes(StandardCharsets.US_ASCII);
    byte[] tampered = "%PDF-1.4\ntampered\n".getBytes(StandardCharsets.US_ASCII);
    Path bookPath = books.resolve("book.pdf");
    Files.write(bookPath, tampered);
    writeIndex(root.resolve("english/index.json"), registryEntry(
        "ncert-c7-english-book", "Test textbook", 7, "english", "Mathematics",
        bookPath, sha256(original), tampered.length));

    TextbookCacheService cache = new TextbookCacheService(root, new ObjectMapper());
    IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
        () -> cache.requireBook("english", "ncert-c7-english-book"));
    assertTrue(error.getMessage().toLowerCase().contains("checksum"));
  }

  @Test
  void rejectsAStaleJobWhenRegistryWholeBookChecksumChanges() throws Exception {
    Path root = Files.createTempDirectory("qe-ghcr-textbook-stale-job");
    Path books = root.resolve("hindi/books");
    Files.createDirectories(books);
    byte[] bytes = "%PDF-1.4\nbook\n".getBytes(StandardCharsets.US_ASCII);
    Path bookPath = books.resolve("book.pdf");
    Files.write(bookPath, bytes);
    String hash = sha256(bytes);
    writeIndex(root.resolve("hindi/index.json"), registryEntry(
        "scert-bihar-123456-hindi", "State textbook", 6, "hindi", "Science", bookPath, hash, bytes.length));

    TextbookCacheService cache = new TextbookCacheService(root, new ObjectMapper());
    IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
        () -> cache.requireBookForJob("hindi", "scert-bihar-123456-hindi", "0".repeat(64)));
    assertTrue(error.getMessage().toLowerCase().contains("changed"));
  }

  private static Map<String, Object> registryEntry(
      String bookId, String title, int classNo, String medium, String subject,
      Path path, String sha256, long bytes) {
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("book_id", bookId);
    item.put("publisher", "hindi".equals(medium) ? "NCERT" : "SCERT Bihar");
    item.put("source_type", "hindi".equals(medium) ? "NCERT" : "SCERT_BIHAR");
    item.put("class", String.valueOf(classNo));
    item.put("classes", List.of(String.valueOf(classNo)));
    item.put("medium", medium);
    item.put("title", title);
    item.put("subject", subject);
    item.put("language", "hindi".equals(medium) ? "Hindi" : "English");
    item.put("source_url", "https://ncert.nic.in/textbook.php?ln=en");
    item.put("catalog_entry_url", "https://ncert.nic.in/textbook.php?book=sample");
    item.put("pdf_url", "https://ncert.nic.in/textbook/pdf/sampledd.zip");
    item.put("bundle_url", "https://ncert.nic.in/textbook/pdf/sampledd.zip");
    item.put("edition", "Official catalogue");
    item.put("file", "books/" + path.getFileName());
    item.put("sha256", sha256);
    item.put("bytes", bytes);
    item.put("page_count", 1);
    return item;
  }

  private static void writeIndex(Path path, Map<String, Object> book) throws Exception {
    Files.createDirectories(path.getParent());
    new ObjectMapper().writeValue(path.toFile(), Map.of("books", List.of(book)));
  }

  private static String sha256(byte[] value) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
  }
}
