package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

class PrivateMediaStorageServiceTest {
  @TempDir Path tempDirectory;

  @Test
  void storesValidMp4WithGeneratedPrivateKeyAndChecksum() throws Exception {
    PrivateMediaStorageService service = new PrivateMediaStorageService(tempDirectory.toString());
    byte[] bytes = "0000ftypisom0000representative mp4 data payload for a stored class recording".getBytes();
    MockMultipartFile file = new MockMultipartFile(
        "file", "../../private-class.mp4", "video/mp4", bytes);

    PrivateMediaStorageService.StoredMedia stored = service.store(file);

    assertTrue(stored.storageKey().matches("[a-f0-9-]{36}\\.mp4"));
    assertEquals("private-class.mp4", stored.originalFilename());
    assertEquals("video/mp4", stored.mediaType());
    assertEquals(bytes.length, stored.sizeBytes());
    assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)), stored.sha256());
    assertTrue(Files.isRegularFile(tempDirectory.resolve(stored.storageKey())));
    assertFalse(stored.storageKey().contains(".."));
  }

  @Test
  void rejectsVideoContentWithInvalidSignatureAndCleansTemporaryFile() {
    PrivateMediaStorageService service = new PrivateMediaStorageService(tempDirectory.toString());
    MockMultipartFile file = new MockMultipartFile(
        "file", "fake.mp4", "video/mp4",
        "this is not an mp4 container but enough bytes to pass minimum size".getBytes());

    ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> service.store(file));

    assertEquals(400, error.getStatusCode().value());
    assertTrue(Files.exists(tempDirectory));
    try (var files = Files.list(tempDirectory)) {
      assertEquals(0, files.count());
    } catch (java.io.IOException ex) {
      throw new AssertionError(ex);
    }
  }

  @Test
  void neverResolvesUserSuppliedPathsAsStorageKeys() {
    PrivateMediaStorageService service = new PrivateMediaStorageService(tempDirectory.toString());

    ResponseStatusException error = assertThrows(ResponseStatusException.class,
        () -> service.resolve("../../etc/passwd"));

    assertEquals(404, error.getStatusCode().value());
  }
}
