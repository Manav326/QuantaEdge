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
    if (medium == null || medium.isBlank() || "both".equalsIgnoreCase(medium)) languages = List.of("hindi", "english");
    else {
      String normalized = medium.trim().toLowerCase(Locale.ROOT);
      if (!List.of("hindi", "english").contains(normalized)) {
        throw new IllegalArgumentException("Medium must be hindi, english, or both.");
      }
      languages = List.of(normalized);
    }
    if (classNo != null && (classNo < 6 || classNo > 12)) {
      throw new IllegalArgumentException("Class filter must be between 6 and 12.");
    }
    List<Map<String, Object>> result = new ArrayList<>();
    for (String language : languages) {
      boolean classwise = hasClasswiseIndexes(language);
      if (classwise) {
        for (int grade = 6; grade <= 12; grade++) {
          if (classNo != null && classNo != grade) continue;
          Path path = classIndexPath(grade, language);
          if (Files.isRegularFile(path)) appendIndexBooks(result, path, language, grade);
        }
      } else {
        Path legacy = root.resolve(language).resolve("index.json").normalize();
        if (Files.isRegularFile(legacy)) appendIndexBooks(result, legacy, language, classNo);
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

  /**
   * Read-only inventory of books which are incomplete or could not be fetched.
   * These rows are deliberately separate from list(), which only returns verified complete books.
   */
  public List<Map<String, Object>> availabilityGaps(String medium, Integer classNo) {
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
    if (classNo != null && (classNo < 6 || classNo > 12)) {
      throw new IllegalArgumentException("Class filter must be between 6 and 12.");
    }

    Map<String, Map<String, Object>> gaps = new LinkedHashMap<>();
    for (String language : languages) {
      if (hasClasswiseIndexes(language)) {
        for (int grade = 6; grade <= 12; grade++) {
          if (classNo != null && grade != classNo) continue;
          Path path = classIndexPath(grade, language);
          if (Files.isRegularFile(path)) appendIndexGaps(gaps, path, language, grade);
        }
      } else {
        Path legacy = root.resolve(language).resolve("index.json").normalize();
        if (Files.isRegularFile(legacy)) appendIndexGaps(gaps, legacy, language, classNo);
      }
    }
    List<Map<String, Object>> result = new ArrayList<>(gaps.values());
    result.sort(Comparator
        .comparingInt((Map<String, Object> item) -> intValue(item.get("class"), 0))
        .thenComparing(item -> string(item.get("medium")))
        .thenComparing(item -> string(item.get("title")), String.CASE_INSENSITIVE_ORDER)
        .thenComparing(item -> string(item.get("book_id"))));
    return result;
  }

  private void appendIndexGaps(
      Map<String, Map<String, Object>> target, Path indexPath, String language, Integer imageClass) {
    Map<?, ?> index = readIndex(indexPath);
    Map<String, Map<String, Object>> entriesById = new LinkedHashMap<>();
    Object raw = index.get("books");
    if (raw instanceof List<?> books) {
      for (Object row : books) {
        if (!(row instanceof Map<?, ?> item)) continue;
        String id = string(item.get("book_id"));
        if (id.isBlank()) continue;
        entriesById.put(id, stringMap(item));
        Map<?, ?> coverage = asMap(item.get("content_availability"));
        boolean incomplete = !isCompleteWholeBook(item);
        boolean missingFile = false;
        if (!incomplete) {
          try {
            Path pdf = resolvePath(indexPath, string(item.get("file")));
            long expectedBytes = number(item.get("bytes"), -1);
            missingFile = !Files.isRegularFile(pdf)
                || (expectedBytes >= 0 && safeFileSize(pdf) != expectedBytes);
          } catch (IllegalArgumentException ex) {
            missingFile = true;
          }
        }
        if (!incomplete && !missingFile) continue;
        String status = string(coverage.get("status")).toLowerCase(Locale.ROOT);
        if (!List.of("partial", "unavailable").contains(status)) {
          String method = string(item.get("download_method")).toLowerCase(Locale.ROOT);
          status = method.contains("partial") ? "partial" : "unavailable";
        }
        Map<String, Object> gap = availabilityGapRow(item, coverage, language,
            gradesForGap(item, imageClass), status, missingFile ? "The registry PDF file is missing or has the wrong byte size." : null);
        for (Object grade : (List<?>) gap.get("classes")) {
          int gradeNo = intValue(grade, 0);
          if (gradeNo >= 6 && gradeNo <= 12) {
            String key = language + ":" + gradeNo + ":" + id;
            target.put(key, gap);
          }
        }
      }
    }

    Object statusObject = index.get("download_status");
    if (statusObject instanceof Map<?, ?> statuses) {
      for (Map.Entry<?, ?> statusEntry : statuses.entrySet()) {
        String bookId = string(statusEntry.getKey());
        if (!(statusEntry.getValue() instanceof Map<?, ?> statusRow) || bookId.isBlank()) continue;
        Map<String, Object> metadata = entriesById.getOrDefault(bookId, new LinkedHashMap<>());
        Map<?, ?> coverage = asMap(statusRow.get("content_availability"));
        String state = string(statusRow.get("status")).toLowerCase(Locale.ROOT);
        String coverageState = string(coverage.get("status")).toLowerCase(Locale.ROOT);
        boolean gap = List.of("failed", "retry_pending", "partial", "downloaded_pending_push", "unavailable").contains(state)
            || List.of("partial", "unavailable").contains(coverageState);
        if (!gap) continue;
        // A stale failure status must not hide a book that is now verified complete.
        if (!metadata.isEmpty() && isCompleteWholeBook(metadata)) continue;
        Map<String, Object> mergedMetadata = new LinkedHashMap<>(metadata);
        mergedMetadata.putAll(stringMap(statusRow));
        // Failed entries are keyed by book ID in download_status; some status rows
        // intentionally do not duplicate that key in their JSON value.
        mergedMetadata.putIfAbsent("book_id", bookId);
        mergedMetadata.putIfAbsent("medium", language);
        Integer statusClass = imageClass;
        if (statusClass == null) {
          List<Integer> grades = classes(metadata);
          if (!grades.isEmpty()) statusClass = grades.getFirst();
          else {
            int grade = intValue(statusRow.get("class"), 0);
            if (grade >= 6 && grade <= 12) statusClass = grade;
          }
        }
        if (statusClass == null || statusClass < 6 || statusClass > 12) continue;
        Map<String, Object> gapRow = availabilityGapRow(mergedMetadata, coverage, language,
            List.of(statusClass), coverageState.equals("partial") ? "partial" : "unavailable",
            string(statusRow.get("last_error")));
        String key = language + ":" + statusClass + ":" + bookId;
        Map<String, Object> existing = target.get(key);
        if (existing == null) {
          target.put(key, gapRow);
        } else {
          if (!string(gapRow.get("last_error")).isBlank()) existing.put("last_error", gapRow.get("last_error"));
          if (((List<?>) existing.getOrDefault("missing_chapters", List.of())).isEmpty()
              && !((List<?>) gapRow.getOrDefault("missing_chapters", List.of())).isEmpty()) {
            existing.put("expected_chapters", gapRow.get("expected_chapters"));
            existing.put("available_chapters", gapRow.get("available_chapters"));
            existing.put("missing_chapters", gapRow.get("missing_chapters"));
            existing.put("content_availability", gapRow.get("content_availability"));
          }
          if (string(existing.get("note")).isBlank()) existing.put("note", gapRow.get("note"));
          if (string(existing.get("source_url")).isBlank()) existing.put("source_url", gapRow.get("source_url"));
        }
      }
    }
  }

  private List<Integer> gradesForGap(Map<?, ?> item, Integer imageClass) {
    if (imageClass != null) return List.of(imageClass);
    return classes(item);
  }

  private Map<String, Object> availabilityGapRow(
      Map<?, ?> item, Map<?, ?> coverage, String language, List<Integer> grades,
      String status, String supplementalError) {
    List<Integer> expected = chapterNumbers(coverage.get("expected_chapters"));
    List<Integer> available = chapterNumbers(coverage.get("available_chapters"));
    List<Integer> missing = chapterNumbers(coverage.get("missing_chapters"));
    if (missing.isEmpty() && !expected.isEmpty() && !available.isEmpty() && !status.equals("complete")) {
      List<Integer> derived = new ArrayList<>(expected);
      derived.removeAll(available);
      missing = derived;
    }
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("book_id", string(item.get("book_id")));
    row.put("title", string(item.get("title")).isBlank() ? string(item.get("book_id")) : string(item.get("title")));
    row.put("publisher", string(item.get("publisher")));
    row.put("subject", string(item.get("subject")));
    row.put("class", grades.isEmpty() ? intValue(item.get("class"), 0) : grades.getFirst());
    row.put("classes", grades);
    row.put("medium", language);
    row.put("status", status);
    row.put("expected_chapters", expected);
    row.put("available_chapters", available);
    row.put("missing_chapters", missing);
    row.put("content_availability", new LinkedHashMap<>(stringMap(coverage)));
    row.put("note", string(coverage.get("note")));
    row.put("source_url", firstText(item.get("catalog_entry_url"), item.get("source_url"),
        coverage.get("diagnostic_bundle_url"), coverage.get("source_url")));
    row.put("last_error", firstText(item.get("last_error"), supplementalError, coverage.get("note")));
    row.put("attempts_total", number(item.get("attempts_total"), 0));
    row.put("updated_at", firstText(item.get("updated_at"), coverage.get("checked_at")));
    return row;
  }

  private List<Integer> chapterNumbers(Object value) {
    List<Integer> result = new ArrayList<>();
    if (value instanceof List<?> values) {
      for (Object item : values) {
        int number = intValue(item, -1);
        if (number > 0 && !result.contains(number)) result.add(number);
      }
    }
    result.sort(Integer::compareTo);
    return result;
  }

  private Map<?, ?> asMap(Object value) {
    return value instanceof Map<?, ?> map ? map : Map.of();
  }

  private Map<String, Object> stringMap(Map<?, ?> value) {
    Map<String, Object> result = new LinkedHashMap<>();
    value.forEach((key, item) -> result.put(String.valueOf(key), item));
    return result;
  }

  private String firstText(Object... values) {
    for (Object value : values) {
      String found = string(value);
      if (!found.isBlank()) return found;
    }
    return "";
  }

  public CachedBook requireBook(String medium, String bookId) {
    return resolveBook(medium, bookId, null, null, true);
  }

  public CachedBook requireBook(String medium, String bookId, int classNo) {
    validateClassNo(classNo);
    return resolveBook(medium, bookId, classNo, null, true);
  }

  /** Re-resolve a previously verified job without re-hashing a huge book for every preview. */
  public CachedBook requireBookForJob(String medium, String bookId, String expectedSha256) {
    if (expectedSha256 == null || !expectedSha256.matches("(?i)[0-9a-f]{64}")) {
      throw new IllegalArgumentException("The ingestion job has no valid expected whole-book checksum.");
    }
    return resolveBook(medium, bookId, null, expectedSha256.toLowerCase(Locale.ROOT), false);
  }

  public CachedBook requireBookForJob(String medium, String bookId, String expectedSha256, int classNo) {
    validateClassNo(classNo);
    if (expectedSha256 == null || !expectedSha256.matches("(?i)[0-9a-f]{64}")) {
      throw new IllegalArgumentException("The ingestion job has no valid expected whole-book checksum.");
    }
    return resolveBook(medium, bookId, classNo, expectedSha256.toLowerCase(Locale.ROOT), false);
  }

  private CachedBook resolveBook(
      String medium, String bookId, Integer classNo, String expectedSha256, boolean verifyHash) {
    String language = normalizeMedium(medium);
    if (bookId == null || bookId.isBlank() || bookId.length() > 240) {
      throw new IllegalArgumentException("A valid cached textbook ID is required.");
    }
    boolean classwise = hasClasswiseIndexes(language);
    if (classwise) {
      int first = classNo == null ? 6 : classNo;
      int last = classNo == null ? 12 : classNo;
      for (int grade = first; grade <= last; grade++) {
        Path path = classIndexPath(grade, language);
        if (!Files.isRegularFile(path)) continue;
        CachedBook found = findBookInIndex(path, language, grade, bookId, expectedSha256, verifyHash);
        if (found != null) return found;
      }
      throw new IllegalArgumentException("Textbook " + bookId + " is not listed in the "
          + language + (classNo == null ? "" : " Class " + classNo) + " GHCR cache.");
    }
    Path legacy = root.resolve(language).resolve("index.json").normalize();
    if (Files.isRegularFile(legacy)) {
      CachedBook found = findBookInIndex(legacy, language, classNo, bookId, expectedSha256, verifyHash);
      if (found != null) return found;
    }
    throw new IllegalArgumentException("Textbook " + bookId + " is not listed in the "
        + language + (classNo == null ? "" : " Class " + classNo) + " GHCR cache.");
  }

  private CachedBook findBookInIndex(
      Path indexPath, String language, Integer requestedClass, String bookId,
      String expectedSha256, boolean verifyHash) {
    Map<?, ?> index = readIndex(indexPath);
    Object raw = index.get("books");
    if (!(raw instanceof List<?> books)) {
      throw new IllegalArgumentException("The textbook cache index has no books array: " + indexPath);
    }
    for (Object row : books) {
      if (!(row instanceof Map<?, ?> item) || !bookId.equals(string(item.get("book_id")))) continue;
      if (!isCompleteWholeBook(item)) {
        throw new IllegalArgumentException("Textbook " + bookId
            + " is incomplete or partial in the GHCR index; only verified complete whole books may be ingested.");
      }
      List<Integer> mappedClasses = classes(item);
      int grade = requestedClass != null
          ? requestedClass
          : mappedClasses.stream().findFirst().orElse(intValue(item.get("class"), 0));
      if (grade < 6 || grade > 12 || !mappedClasses.contains(grade)) {
        throw new IllegalArgumentException("The selected textbook's class metadata does not match its cache image.");
      }
      Path pdfPath = resolvePath(indexPath, string(item.get("file")));
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
          string(item.get("source_type")), grade, List.of(grade), language,
          string(item.get("subject")), string(item.get("source_url")),
          string(item.get("catalog_entry_url")), string(item.get("pdf_url")), string(item.get("bundle_url")),
          string(item.get("edition")), indexHash, size, intValue(item.get("page_count"), 0), pdfPath);
    }
    return null;
  }

  private void appendIndexBooks(
      List<Map<String, Object>> target, Path indexPath, String language, Integer imageClass) {
    Map<?, ?> index = readIndex(indexPath);
    Object raw = index.get("books");
    if (!(raw instanceof List<?> books)) {
      throw new IllegalArgumentException("The textbook cache index has no books array: " + indexPath);
    }
    for (Object row : books) {
      if (!(row instanceof Map<?, ?> item) || !isCompleteWholeBook(item)) continue;
      String bookId = string(item.get("book_id"));
      if (bookId.isBlank()) continue;
      List<Integer> mappedClasses = classes(item);
      List<Integer> rowGrades;
      if (imageClass != null) {
        if (!mappedClasses.contains(imageClass)) continue;
        rowGrades = List.of(imageClass);
      } else {
        rowGrades = mappedClasses;
      }
      Path pdf;
      try { pdf = resolvePath(indexPath, string(item.get("file"))); }
      catch (IllegalArgumentException ex) { continue; }
      if (!Files.isRegularFile(pdf)) continue;
      long size = number(item.get("bytes"), -1);
      if (size >= 0 && safeFileSize(pdf) != size) continue;
      for (int grade : rowGrades) target.add(publicMap(item, language, List.of(grade), grade));
    }
  }

  private boolean isCompleteWholeBook(Map<?, ?> item) {
    Object coverageValue = item.get("content_availability");
    if (coverageValue instanceof Map<?, ?> coverage) {
      String status = string(coverage.get("status")).toLowerCase(Locale.ROOT);
      if (!status.isBlank() && !status.equals("complete")) return false;
    }
    return !string(item.get("download_method")).toLowerCase(Locale.ROOT).contains("partial");
  }

  private boolean hasClasswiseIndexes(String language) {
    for (int grade = 6; grade <= 12; grade++) {
      if (Files.isRegularFile(classIndexPath(grade, language))) return true;
    }
    return false;
  }

  private Path classIndexPath(int classNo, String language) {
    return root.resolve("class-" + classNo).resolve(language).resolve("index.json").normalize();
  }

  private void validateClassNo(int classNo) {
    if (classNo < 6 || classNo > 12) {
      throw new IllegalArgumentException("Class must be between 6 and 12.");
    }
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

  private Path resolvePath(Path indexPath, String filename) {
    if (filename.isBlank()) throw new IllegalArgumentException("A cache file path is missing.");
    Path relative = Path.of(filename).normalize();
    if (relative.isAbsolute() || relative.getNameCount() == 0 || relative.startsWith("..")) {
      throw new IllegalArgumentException("A cache index contains an unsafe file path.");
    }
    Path registryRoot = indexPath.getParent().toAbsolutePath().normalize();
    Path resolved = registryRoot.resolve(relative).normalize();
    if (!resolved.startsWith(registryRoot)) {
      throw new IllegalArgumentException("A cache index path escapes its class/medium registry directory.");
    }
    return resolved;
  }

  private Map<String, Object> publicMap(Map<?, ?> item, String language, List<Integer> classes, int grade) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("book_id", string(item.get("book_id")));
    result.put("publisher", string(item.get("publisher")));
    result.put("source_type", string(item.get("source_type")));
    result.put("class", grade);
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
