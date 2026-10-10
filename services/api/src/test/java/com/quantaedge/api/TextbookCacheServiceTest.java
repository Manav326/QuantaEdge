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
    Path books = root.resolve("class-6/hindi/books");
    Files.createDirectories(books);
    byte[] bytes = "%PDF-1.4\nwhole textbook fixture\n".getBytes(StandardCharsets.US_ASCII);
    Path bookPath = books.resolve("ncert-c6-hindi-fhgp1.pdf");
    Files.write(bookPath, bytes);
    String hash = sha256(bytes);
    writeIndex(root.resolve("class-6/hindi/index.json"), registryEntry(
        "ncert-c6-hindi-fhgp1", "गणित प्रकाश", 6, "hindi", "Mathematics", bookPath, hash, bytes.length));

    TextbookCacheService cache = new TextbookCacheService(root, new ObjectMapper());
    TextbookCacheService.CachedBook cached = cache.requireBook("hindi", "ncert-c6-hindi-fhgp1", 6);

    assertEquals(hash, cached.sha256());
    assertEquals("गणित प्रकाश", cached.title());
    assertEquals(6, cached.classNo());
    assertEquals(bytes.length, cached.bytes());
    assertTrue(cached.path().startsWith(root.resolve("class-6/hindi")));
    assertEquals(1, cache.list("hindi", 6).size());
    assertEquals(0, cache.list("english", 6).size());
  }

  @Test
  void rejectsCacheFileWhenWholeBookChecksumWasModified() throws Exception {
    Path root = Files.createTempDirectory("qe-ghcr-textbook-bad-hash");
    Path books = root.resolve("class-7/english/books");
    Files.createDirectories(books);
    byte[] original = "%PDF-1.4\noriginal\n".getBytes(StandardCharsets.US_ASCII);
    byte[] tampered = "%PDF-1.4\ntampered\n".getBytes(StandardCharsets.US_ASCII);
    Path bookPath = books.resolve("book.pdf");
    Files.write(bookPath, tampered);
    writeIndex(root.resolve("class-7/english/index.json"), registryEntry(
        "ncert-c7-english-book", "Test textbook", 7, "english", "Mathematics",
        bookPath, sha256(original), tampered.length));

    TextbookCacheService cache = new TextbookCacheService(root, new ObjectMapper());
    IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
        () -> cache.requireBook("english", "ncert-c7-english-book", 7));
    assertTrue(error.getMessage().toLowerCase().contains("checksum"));
  }

  @Test
  void partialWholeBookEntriesAreHiddenAndCannotBeIngested() throws Exception {
    Path root = Files.createTempDirectory("qe-ghcr-textbook-partial");
    Path books = root.resolve("class-6/hindi/books");
    Files.createDirectories(books);
    byte[] bytes = "%PDF-1.4\nonly-part-of-the-book\n".getBytes(StandardCharsets.US_ASCII);
    Path bookPath = books.resolve("partial-book.pdf");
    Files.write(bookPath, bytes);
    Map<String, Object> item = registryEntry(
        "ncert-c6-hindi-partial", "Incomplete Mathematics", 6, "hindi", "Mathematics",
        bookPath, sha256(bytes), bytes.length);
    item.put("content_availability", Map.of(
        "status", "partial", "expected_chapters", List.of(1, 2),
        "available_chapters", List.of(1), "missing_chapters", List.of(2)));
    item.put("download_method", "Merged official sources; PARTIAL");
    writeIndex(root.resolve("class-6/hindi/index.json"), item);

    TextbookCacheService cache = new TextbookCacheService(root, new ObjectMapper());

    assertEquals(0, cache.list("hindi", 6).size());
    IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
        () -> cache.requireBook("hindi", "ncert-c6-hindi-partial", 6));
    assertTrue(error.getMessage().toLowerCase().contains("incomplete"));
  }

  @Test
  void rejectsAStaleJobWhenRegistryWholeBookChecksumChanges() throws Exception {
    Path root = Files.createTempDirectory("qe-ghcr-textbook-stale-job");
    Path books = root.resolve("class-6/hindi/books");
    Files.createDirectories(books);
    byte[] bytes = "%PDF-1.4\nbook\n".getBytes(StandardCharsets.US_ASCII);
    Path bookPath = books.resolve("book.pdf");
    Files.write(bookPath, bytes);
    String hash = sha256(bytes);
    writeIndex(root.resolve("class-6/hindi/index.json"), registryEntry(
        "scert-bihar-123456-hindi", "State textbook", 6, "hindi", "Science", bookPath, hash, bytes.length));

    TextbookCacheService cache = new TextbookCacheService(root, new ObjectMapper());
    IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
        () -> cache.requireBookForJob("hindi", "scert-bihar-123456-hindi", "0".repeat(64), 6));
    assertTrue(error.getMessage().toLowerCase().contains("changed"));
  }

  @Test
  void sameBookIdIsResolvedOnlyFromRequestedClassImage() throws Exception {
    Path root = Files.createTempDirectory("qe-ghcr-class-isolation");
    Path grade6Books = root.resolve("class-6/hindi/books");
    Path grade7Books = root.resolve("class-7/hindi/books");
    Files.createDirectories(grade6Books);
    Files.createDirectories(grade7Books);
    byte[] grade6 = "%PDF-1.4\nclass six edition\n".getBytes(StandardCharsets.US_ASCII);
    byte[] grade7 = "%PDF-1.4\nclass seven edition\n".getBytes(StandardCharsets.US_ASCII);
    Path p6 = grade6Books.resolve("same-book.pdf");
    Path p7 = grade7Books.resolve("same-book.pdf");
    Files.write(p6, grade6);
    Files.write(p7, grade7);
    writeIndex(root.resolve("class-6/hindi/index.json"), registryEntry(
        "same-book", "Same title", 6, "hindi", "Mathematics", p6, sha256(grade6), grade6.length));
    writeIndex(root.resolve("class-7/hindi/index.json"), registryEntry(
        "same-book", "Same title", 7, "hindi", "Mathematics", p7, sha256(grade7), grade7.length));

    TextbookCacheService cache = new TextbookCacheService(root, new ObjectMapper());

    assertEquals(sha256(grade6), cache.requireBook("hindi", "same-book", 6).sha256());
    assertEquals(sha256(grade7), cache.requireBook("hindi", "same-book", 7).sha256());
    assertEquals(2, cache.list("hindi", null).size());
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
