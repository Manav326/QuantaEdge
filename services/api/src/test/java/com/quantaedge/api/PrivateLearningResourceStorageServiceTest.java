package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

class PrivateLearningResourceStorageServiceTest {
  @TempDir Path tempDirectory;

  @Test
  void streamsVideoToPrivateStorageAndVerifiesItsChecksum() {
    PrivateLearningResourceStorageService storage=new PrivateLearningResourceStorageService(tempDirectory.toString());
    byte[] bytes=new byte[64];bytes[4]='f';bytes[5]='t';bytes[6]='y';bytes[7]='p';
    MockMultipartFile file=new MockMultipartFile("file","lesson.mp4","video/mp4",bytes);
    PrivateLearningResourceStorageService.StoredResource stored=storage.store(file,"VIDEO");
    Path target=tempDirectory.resolve("learning-resources").resolve(stored.storageKey());
    assertTrue(Files.isRegularFile(target));assertEquals(64L,stored.sizeBytes());
    assertTrue(storage.matchesStoredFile(stored.storageKey(),stored.sizeBytes(),stored.sha256()));
    assertFalse(Files.exists(target.resolveSibling(target.getFileName()+".part")));
  }

  @Test
  void rejectsMismatchedFileSignatureAndCleansTemporaryFile() throws Exception {
    PrivateLearningResourceStorageService storage=new PrivateLearningResourceStorageService(tempDirectory.toString());
    MockMultipartFile file=new MockMultipartFile("file","fake.mp4","video/mp4",new byte[64]);
    ResponseStatusException error=assertThrows(ResponseStatusException.class,()->storage.store(file,"VIDEO"));
    assertEquals(400,error.getStatusCode().value());
    Path root=tempDirectory.resolve("learning-resources");
    if(Files.exists(root))try(var entries=Files.list(root)){assertFalse(entries.findAny().isPresent());}
  }

  @Test
  void rejectsMismatchBetweenResourceTypeAndMediaTypeBeforeWriting() {
    PrivateLearningResourceStorageService storage=new PrivateLearningResourceStorageService(tempDirectory.toString());
    byte[] bytes="%PDF-1.4\n1234567890".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    MockMultipartFile file=new MockMultipartFile("file","lesson.pdf","application/pdf",bytes);
    ResponseStatusException error=assertThrows(ResponseStatusException.class,()->storage.store(file,"VIDEO"));
    assertEquals(400,error.getStatusCode().value());
    assertFalse(Files.exists(tempDirectory.resolve("learning-resources")));
  }
}
