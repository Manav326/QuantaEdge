package com.quantaedge.api;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/** Private, disk-backed storage for reusable learning resources; bytes are never stored in PostgreSQL. */
@Service
public class PrivateLearningResourceStorageService {
  public static final long MAX_RESOURCE_BYTES=500L*1024*1024;
  private static final Map<String,String> EXTENSIONS=Map.ofEntries(
      Map.entry("video/mp4",".mp4"),Map.entry("video/webm",".webm"),Map.entry("video/quicktime",".mov"),
      Map.entry("application/pdf",".pdf"),Map.entry("application/msword",".doc"),
      Map.entry("application/vnd.openxmlformats-officedocument.wordprocessingml.document",".docx"),
      Map.entry("application/vnd.ms-powerpoint",".ppt"),
      Map.entry("application/vnd.openxmlformats-officedocument.presentationml.presentation",".pptx"),
      Map.entry("application/vnd.ms-excel",".xls"),
      Map.entry("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",".xlsx"),
      Map.entry("text/plain",".txt"),Map.entry("audio/mpeg",".mp3"),Map.entry("audio/mp4",".m4a"),
      Map.entry("audio/wav",".wav"),Map.entry("audio/ogg",".ogg"),Map.entry("image/png",".png"),
      Map.entry("image/jpeg",".jpg"),Map.entry("image/webp",".webp"),Map.entry("image/gif",".gif"));
  private static final Set<String> OFFICE_TYPES=Set.of("application/msword",
      "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
      "application/vnd.ms-powerpoint","application/vnd.openxmlformats-officedocument.presentationml.presentation",
      "application/vnd.ms-excel","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
  private final Path root;

  public record StoredResource(String storageKey,String originalFilename,String mediaType,long sizeBytes,String sha256) {}
  public PrivateLearningResourceStorageService(
      @Value("${app.media.storage-dir:/var/lib/quantaedge/media}") String storageDirectory) {
    this.root=Path.of(storageDirectory).toAbsolutePath().normalize().resolve("learning-resources").normalize();
  }

  public StoredResource store(MultipartFile file,String resourceType) {
    if(file==null||file.isEmpty())throw badRequest("Choose a resource file to upload.");
    if(file.getSize()>MAX_RESOURCE_BYTES)throw badRequest("Learning resource files must be 500 MB or smaller.");
    String kind=normalizeResourceType(resourceType),mediaType=normalizeMediaType(file.getContentType());
    validateResourceMediaType(kind,mediaType);
    String extension=EXTENSIONS.get(mediaType),filename=safeFilename(file.getOriginalFilename(),extension);
    String key=UUID.randomUUID()+extension;
    Path target=resolve(key),temporary=resolve(key+".part");
    long count=0;byte[] prefix=new byte[32];int prefixCount=0;
    try {
      Files.createDirectories(root);
      MessageDigest digest=MessageDigest.getInstance("SHA-256");
      try(InputStream raw=file.getInputStream();DigestInputStream input=new DigestInputStream(raw,digest);
          OutputStream output=Files.newOutputStream(temporary,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)) {
        byte[] buffer=new byte[64*1024];int read;
        while((read=input.read(buffer))!=-1) {
          if(count+read>MAX_RESOURCE_BYTES)throw badRequest("Learning resource files must be 500 MB or smaller.");
          if(prefixCount<prefix.length) {
            int take=Math.min(read,prefix.length-prefixCount);System.arraycopy(buffer,0,prefix,prefixCount,take);prefixCount+=take;
          }
          output.write(buffer,0,read);count+=read;
        }
      }
      if(!validSignature(prefix,prefixCount,mediaType))throw badRequest("The uploaded bytes do not match the selected resource file type.");
      if(count<8)throw badRequest("The uploaded resource is too small to be a valid file.");
      try {Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE);}
      catch(AtomicMoveNotSupportedException ex){Files.move(temporary,target);}
      return new StoredResource(key,filename,mediaType,count,HexFormat.of().formatHex(digest.digest()));
    } catch(ResponseStatusException ex) {
      deleteQuietly(temporary);throw ex;
    } catch(IOException ex) {
      deleteQuietly(temporary);throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"The resource could not be saved.",ex);
    } catch(NoSuchAlgorithmException ex) {
      deleteQuietly(temporary);throw new IllegalStateException("SHA-256 is not available.",ex);
    } catch(RuntimeException ex) {
      deleteQuietly(temporary);throw ex;
    }
  }

  public Path resolve(String key) {
    if(key==null||!key.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(mp4|webm|mov|pdf|doc|docx|ppt|pptx|xls|xlsx|txt|mp3|m4a|wav|ogg|png|jpg|webp|gif)(\\.part)?"))
      throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Learning resource file not found.");
    Path result=root.resolve(key).normalize();
    if(!result.startsWith(root))throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Learning resource file not found.");
    return result;
  }

  public boolean matchesStoredFile(String key,long expectedSize,String expectedSha256) {
    Path file=resolve(key);
    if(!Files.isRegularFile(file)||expectedSize<1||expectedSha256==null||!expectedSha256.matches("[0-9a-f]{64}"))return false;
    try {
      if(Files.size(file)!=expectedSize)return false;
      MessageDigest digest=MessageDigest.getInstance("SHA-256");
      try(InputStream input=Files.newInputStream(file)) {
        byte[] buffer=new byte[64*1024];int read;while((read=input.read(buffer))!=-1)digest.update(buffer,0,read);
      }
      return expectedSha256.equals(HexFormat.of().formatHex(digest.digest()));
    } catch(IOException ex){return false;}
    catch(NoSuchAlgorithmException ex){throw new IllegalStateException("SHA-256 is not available.",ex);}
  }
  public void deleteQuietly(Path path) {
    try{Files.deleteIfExists(path);}catch(IOException ignored){/* a private orphan is preferable to exposing a partial asset */}
  }

  private String normalizeResourceType(String raw) {
    String type=raw==null?"":raw.trim().toUpperCase(Locale.ROOT);
    if(!Set.of("VIDEO","PDF","WORKSHEET","SLIDES","AUDIO","IMAGE","OTHER").contains(type))
      throw badRequest("Choose a supported upload resource type.");
    return type;
  }
  private String normalizeMediaType(String raw) {
    String type=raw==null?"":raw.split(";",2)[0].trim().toLowerCase(Locale.ROOT);
    if(!EXTENSIONS.containsKey(type))throw badRequest("This file media type is not supported for resource uploads.");
    return type;
  }
  private void validateResourceMediaType(String kind,String type) {
    boolean allowed=switch(kind) {
      case "VIDEO" -> type.startsWith("video/");
      case "PDF" -> "application/pdf".equals(type);
      case "WORKSHEET" -> Set.of("application/pdf","application/msword","application/vnd.openxmlformats-officedocument.wordprocessingml.document","text/plain").contains(type);
      case "SLIDES" -> Set.of("application/pdf","application/vnd.ms-powerpoint","application/vnd.openxmlformats-officedocument.presentationml.presentation").contains(type);
      case "AUDIO" -> type.startsWith("audio/");
      case "IMAGE" -> type.startsWith("image/");
      case "OTHER" -> true;
      default -> false;
    };
    if(!allowed)throw badRequest("The selected resource type does not match this file format.");
  }
  private boolean validSignature(byte[] b,int length,String type) {
    if("application/pdf".equals(type))return length>=5&&b[0]=='%'&&b[1]=='P'&&b[2]=='D'&&b[3]=='F'&&b[4]=='-';
    if("video/mp4".equals(type)||"video/quicktime".equals(type)||"audio/mp4".equals(type))return length>=8&&b[4]=='f'&&b[5]=='t'&&b[6]=='y'&&b[7]=='p';
    if("video/webm".equals(type))return length>=4&&(b[0]&255)==0x1a&&(b[1]&255)==0x45&&(b[2]&255)==0xdf&&(b[3]&255)==0xa3;
    if("image/png".equals(type))return length>=8&&(b[0]&255)==0x89&&b[1]=='P'&&b[2]=='N'&&b[3]=='G'&&b[4]==13&&b[5]==10&&b[6]==26&&b[7]==10;
    if("image/jpeg".equals(type))return length>=3&&(b[0]&255)==0xff&&(b[1]&255)==0xd8&&(b[2]&255)==0xff;
    if("image/webp".equals(type))return length>=12&&ascii(b,0,"RIFF")&&ascii(b,8,"WEBP");
    if("image/gif".equals(type))return length>=6&&(ascii(b,0,"GIF87a")||ascii(b,0,"GIF89a"));
    if("audio/wav".equals(type))return length>=12&&ascii(b,0,"RIFF")&&ascii(b,8,"WAVE");
    if("audio/ogg".equals(type))return length>=4&&ascii(b,0,"OggS");
    if("audio/mpeg".equals(type))return length>=3&&(ascii(b,0,"ID3")||((b[0]&255)==0xff&&(b[1]&0xe0)==0xe0));
    if(OFFICE_TYPES.contains(type))return (length>=8&&b[0]=='P'&&b[1]=='K'&&b[2]==3&&b[3]==4)
        ||(length>=8&&(b[0]&255)==0xd0&&(b[1]&255)==0xcf&&(b[2]&255)==0x11&&(b[3]&255)==0xe0);
    if("text/plain".equals(type)) {for(int i=0;i<Math.min(length,32);i++)if(b[i]==0)return false;return length>0;}
    return false;
  }
  private boolean ascii(byte[] b,int offset,String value) {
    if(offset+value.length()>b.length)return false;
    for(int i=0;i<value.length();i++)if(b[offset+i]!=(byte)value.charAt(i))return false;
    return true;
  }
  private String safeFilename(String original,String extension) {
    String name=original==null||original.isBlank()?"resource"+extension:original.replace('\\','/');
    name=name.substring(name.lastIndexOf('/')+1).trim().replaceAll("[^A-Za-z0-9._() -]","_");
    if(name.isBlank())name="resource"+extension;
    if(!name.toLowerCase(Locale.ROOT).endsWith(extension))name+=extension;
    return name.length()>255?name.substring(name.length()-255):name;
  }
  private ResponseStatusException badRequest(String message){return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);}
}
