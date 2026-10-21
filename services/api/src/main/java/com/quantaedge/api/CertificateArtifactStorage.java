package com.quantaedge.api;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Private, path-safe and atomic storage for issued certificate artifacts. */
@Service
public class CertificateArtifactStorage {
  private static final long MAX_ARTIFACT_BYTES = 25L * 1024 * 1024;
  private final Path root;

  public record StoredArtifact(String storageKey, long sizeBytes, String sha256) {}

  public CertificateArtifactStorage(
      @Value("$" + "{app.certificates.storage-dir:/var/lib/quantaedge/media/certificates}") String directory) {
    this.root = Path.of(directory).toAbsolutePath().normalize();
  }

  public StoredArtifact store(byte[] bytes, String extension) {
    if (bytes == null || bytes.length == 0 || bytes.length > MAX_ARTIFACT_BYTES) {
      throw new IllegalArgumentException("Certificate artifact is empty or exceeds the storage limit.");
    }
    if (!"pdf".equals(extension) && !"png".equals(extension)) {
      throw new IllegalArgumentException("Unsupported certificate format.");
    }
    String key = UUID.randomUUID().toString() + "." + extension;
    Path target = resolve(key);
    Path temporary = resolve(key + ".part");
    try {
      Files.createDirectories(root);
      Files.write(temporary, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
      try {
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
      } catch (AtomicMoveNotSupportedException ex) {
        Files.move(temporary, target);
      }
      return new StoredArtifact(key, bytes.length, sha256(bytes));
    } catch (IOException ex) {
      deleteQuietly(temporary);
      throw new ResponseStatusException(HttpStatus.INSUFFICIENT_STORAGE,
          "The certificate could not be saved to durable storage.", ex);
    } catch (RuntimeException ex) {
      deleteQuietly(temporary);
      throw ex;
    }
  }

  public byte[] read(String key) {
    Path path = resolve(key);
    try {
      long size = Files.size(path);
      if (size <= 0 || size > MAX_ARTIFACT_BYTES) {
        throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Certificate artifact failed integrity checks.");
      }
      return Files.readAllBytes(path);
    } catch (IOException ex) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
          "The stored certificate is temporarily unavailable.", ex);
    }
  }

  public void deleteQuietly(String key) {
    if (key == null || key.isBlank()) return;
    try {
      Files.deleteIfExists(resolve(key));
    } catch (IOException | ResponseStatusException ignored) {
      // Failed cleanup can leave only a private, unreferenced file.
    }
  }

  private void deleteQuietly(Path path) {
    try {
      Files.deleteIfExists(path);
    } catch (IOException ignored) {
      // Failed cleanup can leave only a private, unreferenced temporary file.
    }
  }

  public static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 is not available.", ex);
    }
  }

  private Path resolve(String key) {
    if (key == null || !key.matches("[0-9a-fA-F-]{36}\\.(pdf|png)(\\.part)?")) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Certificate artifact not found.");
    }
    Path result = root.resolve(key).normalize();
    if (!result.startsWith(root)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Certificate artifact not found.");
    }
    return result;
  }
}
