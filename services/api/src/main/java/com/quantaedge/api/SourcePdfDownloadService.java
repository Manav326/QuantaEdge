package com.quantaedge.api;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Downloads source PDFs from HTTPS pages/direct links with SSRF and size guards. */
@Component
public class SourcePdfDownloadService {
  static final int MAX_PDF_BYTES = 50 * 1024 * 1024;
  private static final int MAX_HTML_BYTES = 4 * 1024 * 1024;
  private static final Pattern PDF_LINK = Pattern.compile(
      "(?is)<a\\b[^>]*?href\\s*=\\s*['\\x22]([^'\\x22]+)['\\x22][^>]*>(.*?)</a>");
  private final HttpClient client = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(12))
      .followRedirects(HttpClient.Redirect.NEVER)
      .build();
  private final Path textbookCacheRoot;
  private final boolean textbookCacheOnly;
  private final ObjectMapper cacheMapper;

  public SourcePdfDownloadService() {
    this(Path.of(System.getenv().getOrDefault(
        "APP_TEXTBOOK_CACHE_DIR", "/var/lib/quantaedge/textbook-cache")),
        Boolean.parseBoolean(System.getenv().getOrDefault("APP_TEXTBOOK_CACHE_ONLY", "false")),
        new ObjectMapper());
  }

  SourcePdfDownloadService(Path textbookCacheRoot, boolean textbookCacheOnly, ObjectMapper cacheMapper) {
    this.textbookCacheRoot = textbookCacheRoot.toAbsolutePath().normalize();
    this.textbookCacheOnly = textbookCacheOnly;
    this.cacheMapper = cacheMapper;
  }

  public DownloadedPdf download(String sourceUrl, String sourceTitle) {
    DownloadedPdf cached = findCachedTextbook(sourceUrl, sourceTitle);
    if (cached != null) return cached;
    if (textbookCacheOnly) {
      throw new IllegalArgumentException(
          "This environment is configured for textbook-cache-only downloads, but no matching book "
              + "was found in the mounted Hindi/English GHCR cache. Sync the correct image and use "
              + "the exact source URL/title from index.json.");
    }
    URI source = safeUri(sourceUrl);
    HttpResult first = get(source);
    if (isPdf(first.bytes())) return result(first.bytes(), first.url());
    String html = new String(first.bytes(), java.nio.charset.StandardCharsets.UTF_8);
    List<URI> candidates = pdfLinks(html, first.url());
    if (candidates.isEmpty()) {
      throw new IllegalArgumentException(
          "The source page did not expose a direct PDF link. Paste the official PDF download URL and retry.");
    }
    String needle = normalize(sourceTitle);
    candidates.sort(Comparator.comparingInt((URI u) -> score(u, needle)).reversed());
    IllegalArgumentException last = null;
    for (URI candidate : candidates.stream().limit(8).toList()) {
      try {
        HttpResult downloaded = get(candidate);
        if (isPdf(downloaded.bytes())) return result(downloaded.bytes(), downloaded.url());
        last = new IllegalArgumentException("A discovered source link did not return a PDF file.");
      } catch (IllegalArgumentException ex) {
        last = ex;
      }
    }
    throw last == null
        ? new IllegalArgumentException("No valid PDF download could be found on the registered source page.")
        : last;
  }

  private DownloadedPdf findCachedTextbook(String sourceUrl, String sourceTitle) {
    String requestUrl = normalizeSourceUrl(sourceUrl);
    String requestTitle = normalize(sourceTitle);
    for (String language : List.of("hindi", "english")) {
      Path indexPath = textbookCacheRoot.resolve(language).resolve("index.json").normalize();
      if (!indexPath.startsWith(textbookCacheRoot) || !Files.isRegularFile(indexPath)) continue;
      final Map<?, ?> index;
      try {
        index = cacheMapper.readValue(indexPath.toFile(), Map.class);
      } catch (IOException ex) {
        throw new IllegalArgumentException("The mounted " + language
            + " textbook-cache index is unreadable: " + indexPath, ex);
      }
      Object rawBooks = index.get("books");
      if (!(rawBooks instanceof List<?> books)) {
        throw new IllegalArgumentException("The mounted " + language + " textbook-cache index has no books list.");
      }
      for (Object value : books) {
        if (!(value instanceof Map<?, ?> item)) continue;
        String title = valueOrEmpty(item.get("title"));
        String publisher = valueOrEmpty(item.get("publisher"));
        boolean ncert = publisher.toUpperCase(Locale.ROOT).contains("NCERT");
        String indexedSource = normalizeSourceUrl(valueOrEmpty(item.get("source_url")));
        String catalogUrl = normalizeSourceUrl(valueOrEmpty(item.get("catalog_entry_url")));
        String pdfUrl = normalizeSourceUrl(valueOrEmpty(item.get("pdf_url")));
        String bundleUrl = normalizeSourceUrl(valueOrEmpty(item.get("bundle_url")));
        boolean exactSpecificUrl = !requestUrl.isBlank()
            && (requestUrl.equals(catalogUrl) || requestUrl.equals(pdfUrl) || requestUrl.equals(bundleUrl));
        boolean exactSourceUrl = !requestUrl.isBlank() && requestUrl.equals(indexedSource);
        boolean exactTitle = !requestTitle.isBlank() && requestTitle.equals(normalize(title));
        // NCERT's root catalogue URL is shared by every book; require title too.
        boolean genericNcertCatalogue = ncert && (
            indexedSource.equals("https://ncert.nic.in/textbook.php?ln=en")
            || indexedSource.equals("https://ncert.nic.in/textbook.php?ln=hi")
            || indexedSource.equals("https://ncert.ncert.org.in/textbook.php?ln=en"));
        boolean matches = exactSpecificUrl
            || (exactSourceUrl && (!genericNcertCatalogue || exactTitle))
            || (genericNcertCatalogue && exactTitle);
        if (!matches) continue;

        String relativeName = valueOrEmpty(item.get("file"));
        Path relative = Path.of(relativeName);
        if (relative.isAbsolute() || relative.getNameCount() == 0 || relative.startsWith("..")) {
          throw new IllegalArgumentException("The textbook cache contains an unsafe PDF path for " + title + ".");
        }
        Path languageRoot = textbookCacheRoot.resolve(language).normalize();
        Path pdfPath = languageRoot.resolve(relative).normalize();
        if (!pdfPath.startsWith(languageRoot)) {
          throw new IllegalArgumentException("The textbook cache path escapes its language directory.");
        }
        if (!Files.isRegularFile(pdfPath)) {
          throw new IllegalArgumentException("Textbook " + title + " is listed in the local cache index, but its PDF is missing.");
        }
        final byte[] bytes;
        try {
          bytes = Files.readAllBytes(pdfPath);
        } catch (IOException ex) {
          throw new IllegalArgumentException("Could not read cached textbook " + title + ".", ex);
        }
        String expectedHash = valueOrEmpty(item.get("sha256")).toLowerCase(Locale.ROOT);
        if (bytes.length < 5 || bytes[0] != '%' || bytes[1] != 'P' || bytes[2] != 'D'
            || bytes[3] != 'F' || bytes[4] != '-') {
          throw new IllegalArgumentException("Cached source for " + title + " is not a PDF.");
        }
        if (expectedHash.isBlank() || !expectedHash.equals(sha256(bytes))) {
          throw new IllegalArgumentException("Cached textbook checksum mismatch for " + title
              + ". Re-sync the corresponding GHCR language image before retrying.");
        }
        if (bytes.length > MAX_PDF_BYTES) {
          throw new IllegalArgumentException("Cached textbook " + title + " exceeds the 50 MiB database-library limit. "
              + "Split it with the textbook_registry.py prepare-library command and import the smaller chapter PDFs.");
        }
        String filename = title.replaceAll("[^A-Za-z0-9._() -]", "_").trim();
        if (filename.isBlank()) filename = "cached-source";
        if (!filename.toLowerCase(Locale.ROOT).endsWith(".pdf")) filename += ".pdf";
        if (filename.length() > 255) filename = filename.substring(filename.length() - 255);
        return new DownloadedPdf(bytes, sourceUrl, filename);
      }
    }
    return null;
  }

  private String valueOrEmpty(Object value) {
    return value == null ? "" : String.valueOf(value);
  }

  private String normalizeSourceUrl(String value) {
    if (value == null) return "";
    String normalized = value.trim();
    while (normalized.endsWith("/") && !normalized.endsWith("://")) {
      normalized = normalized.substring(0, normalized.length() - 1);
    }
    return normalized;
  }

  private String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 is unavailable.", ex);
    }
  }

  private DownloadedPdf result(byte[] bytes, URI url) {
    if (bytes.length > MAX_PDF_BYTES) throw new IllegalArgumentException("PDF files must be 50 MB or smaller.");
    String path = url.getPath() == null ? "" : url.getPath();
    String name = path.substring(path.lastIndexOf('/') + 1);
    name = name.replaceAll("[^A-Za-z0-9._() -]", "_");
    if (name.isBlank() || !name.toLowerCase(Locale.ROOT).endsWith(".pdf")) name = "source-book.pdf";
    if (name.length() > 255) name = name.substring(name.length() - 255);
    return new DownloadedPdf(bytes, url.toString(), name);
  }

  private HttpResult get(URI initial) {
    URI uri = safeUri(initial.toString());
    for (int redirects = 0; redirects <= 5; redirects++) {
      try {
        HttpRequest request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(75))
            .header("User-Agent", "QuantaEdge-SourceImporter/1.0")
            .header("Accept", "application/pdf,application/octet-stream,text/html;q=0.9,*/*;q=0.1")
            .GET().build();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        int status = response.statusCode();
        if (status >= 300 && status < 400) {
          String location = response.headers().firstValue("location").orElse("");
          try { response.body().close(); } catch (IOException ignored) { }
          if (location.isBlank() || redirects == 5) throw new IllegalArgumentException("The source URL has too many or invalid redirects.");
          uri = safeUri(uri.resolve(location).toString());
          continue;
        }
        if (status < 200 || status >= 300) {
          try { response.body().close(); } catch (IOException ignored) { }
          throw new IllegalArgumentException("The source server returned HTTP " + status + ".");
        }
        int limit = response.headers().firstValue("content-type").orElse("").toLowerCase(Locale.ROOT).contains("text/html")
            ? MAX_HTML_BYTES : MAX_PDF_BYTES;
        byte[] bytes = readLimited(response.body(), limit);
        return new HttpResult(bytes, uri);
      } catch (IOException ex) {
        throw new IllegalArgumentException("The source PDF could not be downloaded: " + ex.getMessage());
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        throw new IllegalArgumentException("The source download was interrupted.");
      }
    }
    throw new IllegalArgumentException("The source URL has too many redirects.");
  }

  private byte[] readLimited(InputStream input, int limit) throws IOException {
    try (InputStream in = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[16 * 1024];
      int total = 0;
      int count;
      while ((count = in.read(buffer)) != -1) {
        total += count;
        if (total > limit) throw new IllegalArgumentException("The source response exceeds the permitted download size.");
        output.write(buffer, 0, count);
      }
      return output.toByteArray();
    }
  }

  private List<URI> pdfLinks(String html, URI base) {
    Matcher matcher = PDF_LINK.matcher(html);
    List<URI> result = new ArrayList<>();
    while (matcher.find()) {
      String href = matcher.group(1).trim().replace("&amp;", "&");
      try {
        URI candidate = safeUri(base.resolve(href).toString());
        if (candidate.getPath() != null && candidate.getPath().toLowerCase(Locale.ROOT).endsWith(".pdf")
            && !result.contains(candidate)) result.add(candidate);
      } catch (IllegalArgumentException ignored) { }
    }
    return result;
  }

  private int score(URI candidate, String title) {
    String path = normalize(candidate.getPath());
    if (title.isBlank()) return path.endsWith("pdf") ? 1 : 0;
    int score = 0;
    for (String token : title.split(" ")) {
      if (token.length() > 2 && path.contains(token)) score += 2;
    }
    if (path.endsWith("pdf")) score++;
    return score;
  }

  private String normalize(String value) {
    return value == null ? "" : value.toLowerCase(Locale.ROOT)
        .replaceAll("[^a-z0-9\\p{IsAlphabetic}\\p{IsDigit}]+", " ").trim();
  }

  private boolean isPdf(byte[] bytes) {
    return bytes != null && bytes.length >= 5
        && bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D' && bytes[3] == 'F' && bytes[4] == '-';
  }

  URI safeUri(String value) {
    final URI uri;
    try { uri = URI.create(value == null ? "" : value.trim()); }
    catch (IllegalArgumentException ex) { throw new IllegalArgumentException("Enter a valid source URL."); }
    if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) {
      throw new IllegalArgumentException("Source downloads require a public HTTPS URL.");
    }
    String host = uri.getHost().toLowerCase(Locale.ROOT);
    if (host.equals("localhost") || host.endsWith(".localhost") || host.endsWith(".local")
        || host.endsWith(".internal") || host.equals("metadata.google.internal")) {
      throw new IllegalArgumentException("Private and local network URLs cannot be used as source URLs.");
    }
    try {
      for (InetAddress address : InetAddress.getAllByName(host)) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
            || address.isSiteLocalAddress() || address.isMulticastAddress()) {
          throw new IllegalArgumentException("Private and local network URLs cannot be used as source URLs.");
        }
      }
    } catch (UnknownHostException ex) {
      throw new IllegalArgumentException("The source hostname could not be resolved.");
    }
    return uri;
  }

  public record DownloadedPdf(byte[] bytes, String resolvedUrl, String filename) {}
  private record HttpResult(byte[] bytes, URI url) {}
}
