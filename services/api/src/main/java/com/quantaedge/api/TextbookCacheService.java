package com.quantaedge.api;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads the locally synchronized Hindi/English GHCR textbook registries.
 *
 * The registry index is metadata only; every book used for ingestion is resolved beneath the
 * configured cache root and checked for its whole-file SHA-256 before PDF processing.
 */
@Component
public class TextbookCacheService {
  private static final int MAX_METADATA_INDEX_BYTES = 32 * 1024 * 1024;
  private static final int HASH_BUFFER_BYTES = 1024 * 1024;

  private final Path root;
  private final ObjectMapper mapper;

  public TextbookCacheService() {
    this(Path.of(System.getenv().getOrDefault(
        "APP_TEXTBOOK_CACHE_DIR", "/var/lib/quantaedge/textbook-cache")), new ObjectMapper());
  }

  TextbookCacheService(Path root, ObjectMapper mapper) {
    this.root = root.toAbsolutePath().normalize();
    this.mapper = mapper;
  }

  public List<Map<String, Object>> list(String medium, Integer classNo) {
    List<String> languages;
    if (medium == null || medium.isBlank() || "both".equalsIgnoreCase(medium)) {
      languages = List.of("hindi", "english");
    } else {
      String normalized = medium.trim().toLowerCase(Locale.ROOT);
      if (!List.of("hindi", "english").contains(normalized)) {
        throw new IllegalArgumentException("Medium must be hindi, english, or both.");
      }
      languages = List.of(normalized);
    }

    List<Map<String, Object>> result = new ArrayList<>();
    for (String language : languages) {
      Path indexPath = root.resolve(language).resolve("index.json").normalize();
      if (!indexPath.startsWith(root) || !Files.isRegularFile(indexPath)) continue;
      Map<?, ?> index = readIndex(indexPath);
      Object raw = index.get("books");
      if (!(raw instanceof List<?> books)) {
        throw new IllegalArgumentException("The " + language + " textbook cache index has no books array.");
      }
      for (Object row : books) {
        if (!(row instanceof Map<?, ?> item)) continue;
        String bookId = string(item.get("book_id"));
        if (bookId.isBlank()) continue;
        List<Integer> classes = classes(item);
        if (classNo != null && !classes.contains(classNo)) continue;
        Path pdfPath;
        try {
          pdfPath = resolvePath(language, string(item.get("file")));
        } catch (IllegalArgumentException ex) {
          continue;
        }
        if (!Files.isRegularFile(pdfPath)) continue;
        long bytes = number(item.get("bytes"), -1);
        if (bytes >= 0 && safeFileSize(pdfPath) != bytes) continue;
        result.add(publicMap(item, language, classes));
      }
    }
    result.sort(Comparator
        .comparingInt((Map<String, Object> item) -> ((Number) item.get("class")).intValue())
        .thenComparing(item -> String.valueOf(item.get("medium")))
        .thenComparing(item -> String.valueOf(item.get("publisher")))
        .thenComparing(item -> String.valueOf(item.get("subject")))
        .thenComparing(item -> String.valueOf(item.get("title")), String.CASE_INSENSITIVE_ORDER)
        .thenComparing(item -> String.valueOf(item.get("book_id"))));
    return result;
  }

  public CachedBook requireBook(String medium, String bookId) {
    return resolveBook(medium, bookId, null, true);
  }

  /** Re-resolve a previously verified job without re-hashing a huge book for every preview. */
  public CachedBook requireBookForJob(String medium, String bookId, String expectedSha256) {
    if (expectedSha256 == null || !expectedSha256.matches("(?i)[0-9a-f]{64}")) {
      throw new IllegalArgumentException("The ingestion job has no valid expected whole-book checksum.");
    }
    return resolveBook(medium, bookId, expectedSha256.toLowerCase(Locale.ROOT), false);
  }

  private CachedBook resolveBook(String medium, String bookId, String expectedSha256, boolean verifyHash) {
    String language = normalizeMedium(medium);
    if (bookId == null || bookId.isBlank() || bookId.length() > 240) {
      throw new IllegalArgumentException("A valid cached textbook ID is required.");
    }
    Path indexPath = root.resolve(language).resolve("index.json").normalize();
    if (!indexPath.startsWith(root) || !Files.isRegularFile(indexPath)) {
      throw new IllegalArgumentException("The " + language + " GHCR textbook cache is not mounted. Sync the image first.");
    }
    Map<?, ?> index = readIndex(indexPath);
    Object raw = index.get("books");
    if (!(raw instanceof List<?> books)) {
      throw new IllegalArgumentException("The " + language + " textbook cache index has no books array.");
    }

    for (Object row : books) {
      if (!(row instanceof Map<?, ?> item) || !bookId.equals(string(item.get("book_id")))) continue;
      Path pdfPath = resolvePath(language, string(item.get("file")));
      if (!Files.isRegularFile(pdfPath)) {
        throw new IllegalArgumentException("The cached PDF for " + bookId + " is missing. Re-sync its GHCR image.");
      }
      long size = safeFileSize(pdfPath);
      long declaredSize = number(item.get("bytes"), -1);
      if (size < 5 || (declaredSize >= 0 && size != declaredSize)) {
        throw new IllegalArgumentException("The cached PDF size does not match index.json for " + bookId + ".");
      }
      String indexHash = string(item.get("sha256")).toLowerCase(Locale.ROOT);
      if (!indexHash.matches("[0-9a-f]{64}")) {
        throw new IllegalArgumentException("The cached book SHA-256 is invalid for " + bookId + ".");
      }
      if (expectedSha256 != null && !expectedSha256.equals(indexHash)) {
        throw new IllegalArgumentException("The cached book changed since this ingestion job was created. Start a new job.");
      }
      byte[] signature = new byte[5];
      try (InputStream input = Files.newInputStream(pdfPath)) {
        if (input.read(signature) != 5 || signature[0] != '%' || signature[1] != 'P'
            || signature[2] != 'D' || signature[3] != 'F' || signature[4] != '-') {
          throw new IllegalArgumentException("The cache entry " + bookId + " is not a PDF.");
        }
      } catch (IOException ex) {
        throw new IllegalArgumentException("Could not read the cached PDF signature for " + bookId + ".", ex);
      }
      if (verifyHash && !indexHash.equals(safeSha256(pdfPath))) {
        throw new IllegalArgumentException("The cached whole-book checksum does not match for " + bookId
            + ". Re-sync GHCR before ingesting it.");
      }
      return new CachedBook(
          bookId, string(item.get("title")), string(item.get("publisher")),
          string(item.get("source_type")), intValue(item.get("class"), classes(item).stream().findFirst().orElse(0)),
          classes(item), language, string(item.get("subject")), string(item.get("source_url")),
          string(item.get("catalog_entry_url")), string(item.get("pdf_url")), string(item.get("bundle_url")),
          string(item.get("edition")), indexHash, size, intValue(item.get("page_count"), 0), pdfPath);
    }
    throw new IllegalArgumentException("Textbook " + bookId + " is not listed in the " + language + " GHCR cache.");
  }


  public int classNumber(String classCode, String className) {
    String combined = string(classCode) + " " + string(className);
    java.util.regex.Matcher matcher = java.util.regex.Pattern
        .compile("(?i)(?:class[ _-]*)?(6|7|8|9|10|11|12)\\b").matcher(combined);
    int found = 0;
    while (matcher.find()) found = Integer.parseInt(matcher.group(1));
    if (found < 6 || found > 12) {
      throw new IllegalArgumentException("Could not safely resolve the selected curriculum class to a grade from 6 to 12.");
    }
    return found;
  }

  private Map<?, ?> readIndex(Path indexPath) {
    try {
      long size = Files.size(indexPath);
      if (size < 2 || size > MAX_METADATA_INDEX_BYTES) {
        throw new IllegalArgumentException("The textbook registry index has an invalid size.");
      }
      Object value = mapper.readValue(indexPath.toFile(), Map.class);
      if (!(value instanceof Map<?, ?> map)) throw new IllegalArgumentException("The textbook registry index is invalid.");
      return map;
    } catch (IOException | RuntimeException ex) {
      if (ex instanceof IllegalArgumentException iae) throw iae;
      throw new IllegalArgumentException("Cannot read textbook registry index " + indexPath + ".", ex);
    }
  }

  private Path resolvePath(String language, String filename) {
    if (filename.isBlank()) throw new IllegalArgumentException("A cache file path is missing.");
    Path relative = Path.of(filename).normalize();
    if (relative.isAbsolute() || relative.getNameCount() == 0 || relative.startsWith("..")) {
      throw new IllegalArgumentException("A cache index contains an unsafe file path.");
    }
    Path languageRoot = root.resolve(language).normalize();
    Path resolved = languageRoot.resolve(relative).normalize();
    if (!languageRoot.startsWith(root) || !resolved.startsWith(languageRoot)) {
      throw new IllegalArgumentException("A cache index path escapes the configured registry directory.");
    }
    return resolved;
  }

  private Map<String, Object> publicMap(Map<?, ?> item, String language, List<Integer> classes) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("book_id", string(item.get("book_id")));
    result.put("publisher", string(item.get("publisher")));
    result.put("source_type", string(item.get("source_type")));
    result.put("class", intValue(item.get("class"), classes.isEmpty() ? 0 : classes.getFirst()));
    result.put("classes", classes);
    result.put("medium", language);
    result.put("title", string(item.get("title")));
    result.put("subject", string(item.get("subject")));
    result.put("language", string(item.get("language")));
    result.put("edition", string(item.get("edition")));
    result.put("source_url", string(item.get("source_url")));
    result.put("catalog_entry_url", string(item.get("catalog_entry_url")));
    result.put("pdf_url", string(item.get("pdf_url")));
    result.put("sha256", string(item.get("sha256")));
    result.put("bytes", number(item.get("bytes"), 0));
    result.put("page_count", intValue(item.get("page_count"), 0));
    return result;
  }

  private List<Integer> classes(Map<?, ?> item) {
    List<Integer> result = new ArrayList<>();
    Object raw = item.get("classes");
    if (raw instanceof List<?> values) {
      for (Object value : values) {
        int number = intValue(value, 0);
        if (number >= 6 && number <= 12 && !result.contains(number)) result.add(number);
      }
    }
    if (result.isEmpty()) {
      int number = intValue(item.get("class"), 0);
      if (number >= 6 && number <= 12) result.add(number);
    }
    result.sort(Integer::compareTo);
    return result;
  }

  private String normalizeMedium(String value) {
    String medium = string(value).trim().toLowerCase(Locale.ROOT);
    if (!List.of("hindi", "english").contains(medium)) {
      throw new IllegalArgumentException("Medium must be hindi or english.");
    }
    return medium;
  }

  private int intValue(Object value, int fallback) {
    return (int) number(value, fallback);
  }

  private long number(Object value, long fallback) {
    if (value instanceof Number number) return number.longValue();
    if (value != null) {
      try { return Long.parseLong(String.valueOf(value)); } catch (NumberFormatException ignored) { }
    }
    return fallback;
  }

  private String string(Object value) {
    return value == null ? "" : String.valueOf(value).trim();
  }

  private long safeFileSize(Path path) {
    try {
      return Files.size(path);
    } catch (IOException ex) {
      throw new IllegalArgumentException("Could not stat cached textbook " + path.getFileName() + ".", ex);
    }
  }

  private String safeSha256(Path path) {
    try {
      return sha256(path);
    } catch (IOException ex) {
      throw new IllegalArgumentException("Could not read cached textbook " + path.getFileName() + " for checksum verification.", ex);
    }
  }

  private String sha256(Path path) throws IOException {
    try (InputStream input = Files.newInputStream(path)) {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] buffer = new byte[HASH_BUFFER_BYTES];
      int read;
      while ((read = input.read(buffer)) >= 0) {
        if (read > 0) digest.update(buffer, 0, read);
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 is unavailable.", ex);
    }
  }

  public record CachedBook(
      String bookId, String title, String publisher, String sourceType, int classNo, List<Integer> classes,
      String medium, String subject, String sourceUrl, String catalogEntryUrl, String pdfUrl,
      String bundleUrl, String edition, String sha256, long bytes, int pageCount, Path path) {
    public Map<String, Object> publicMap() {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("book_id", bookId);
      item.put("title", title);
      item.put("publisher", publisher);
      item.put("source_type", sourceType);
      item.put("class", classNo);
      item.put("classes", classes);
      item.put("medium", medium);
      item.put("subject", subject);
      item.put("source_url", sourceUrl);
      item.put("catalog_entry_url", catalogEntryUrl);
      item.put("pdf_url", pdfUrl);
      item.put("edition", edition);
      item.put("sha256", sha256);
      item.put("bytes", bytes);
      item.put("page_count", pageCount);
      return item;
    }
  }
}
