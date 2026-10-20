package com.quantaedge.api;

import java.io.IOException;
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

/** Private atomic storage for issued fiscal-document files. */
@Service
public class FiscalDocumentStorage {
  private static final long MAX_BYTES=25L*1024*1024;
  private final Path root;

  public record StoredDocument(String storageKey,long sizeBytes,String sha256) {}

  public FiscalDocumentStorage(
      @Value("${app.fiscal-documents.storage-dir:/var/lib/quantaedge/media/fiscal-documents}") String directory) {
    this.root=Path.of(directory).toAbsolutePath().normalize();
  }

  public StoredDocument storePdf(byte[] bytes) {
    if(bytes==null||bytes.length==0||bytes.length>MAX_BYTES)
      throw new IllegalArgumentException("Fiscal-document PDF is empty or exceeds the storage limit.");
    String key=UUID.randomUUID()+".pdf";
    Path target=resolve(key), temporary=resolve(key+".part");
    try {
      Files.createDirectories(root);
      Files.write(temporary,bytes,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);
      try { Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE); }
      catch(java.nio.file.AtomicMoveNotSupportedException unsupported) { Files.move(temporary,target); }
      return new StoredDocument(key,bytes.length,sha256(bytes));
    } catch(IOException ex) {
      deleteQuietlyPath(temporary);
      throw new ResponseStatusException(HttpStatus.INSUFFICIENT_STORAGE,
          "The issued fiscal document could not be saved to durable storage.",ex);
    } catch(RuntimeException ex) {
      deleteQuietlyPath(temporary);
      throw ex;
    }
  }

  public byte[] readPdf(String key) {
    Path path=resolve(key);
    try {
      long size=Files.size(path);
      if(size<=0||size>MAX_BYTES)
        throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,"Fiscal-document file failed integrity checks.");
      byte[] bytes=Files.readAllBytes(path);
      if(bytes.length!=size) throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,"Fiscal-document size changed while reading.");
      return bytes;
    } catch(IOException ex) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"The issued document is temporarily unavailable.",ex);
    }
  }

  public void deleteQuietly(String key) {
    if(key==null||key.isBlank()) return;
    try { Files.deleteIfExists(resolve(key)); }
    catch(IOException|ResponseStatusException ignored) { }
  }

  private void deleteQuietlyPath(Path path) {
    try { Files.deleteIfExists(path); } catch(IOException ignored) { }
  }

  public static String sha256(byte[] bytes) {
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    catch(NoSuchAlgorithmException ex) { throw new IllegalStateException("SHA-256 is unavailable.",ex); }
  }

  private Path resolve(String key) {
    if(key==null||!key.matches("[0-9a-fA-F-]{36}\\.pdf(\\.part)?"))
      throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Fiscal document asset not found.");
    Path path=root.resolve(key).normalize();
    if(!path.startsWith(root)) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Fiscal document asset not found.");
    return path;
  }
}
