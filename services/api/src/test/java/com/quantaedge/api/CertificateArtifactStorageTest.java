package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;

class CertificateArtifactStorageTest {
  @TempDir Path tempDir;

  @Test
  void storesArtifactsAtomicallyAndVerifiesStableContentHash() {
    CertificateArtifactStorage storage = new CertificateArtifactStorage(tempDir.toString());
    byte[] contents = "%PDF-1.7 test certificate".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    CertificateArtifactStorage.StoredArtifact stored = storage.store(contents, "pdf");
    assertArrayEquals(contents, storage.read(stored.storageKey()));
    assertEquals(contents.length, stored.sizeBytes());
    assertEquals(CertificateArtifactStorage.sha256(contents), stored.sha256());
  }

  @Test
  void refusesPathTraversalAndUnknownExtensions() {
    CertificateArtifactStorage storage = new CertificateArtifactStorage(tempDir.toString());
    assertThrows(ResponseStatusException.class, () -> storage.read("../../secrets.pdf"));
    assertThrows(IllegalArgumentException.class, () -> storage.store(new byte[]{1}, "svg"));
  }
}
