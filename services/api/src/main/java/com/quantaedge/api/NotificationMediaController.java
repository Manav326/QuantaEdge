package com.quantaedge.api;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/** Private, tenant-scoped rich-notification media. Uploads never accept SVG or executable markup. */
@RestController
@RequestMapping("/api/v1")
public class NotificationMediaController {
  private static final long MAX_IMAGE_BYTES=12L*1024*1024;
  private static final long MAX_VIDEO_BYTES=100L*1024*1024;
  private static final long MAX_IMAGE_PIXELS=40000000L;

  private final JdbcTemplate jdbc;
  private final AuthorizationService authorization;
  private final InstitutionTenantContextService tenantContexts;
  private final InstitutionAuthService institutionAuth;
  private final Path mediaRoot;

  public NotificationMediaController(
      JdbcTemplate jdbc,AuthorizationService authorization,
      InstitutionTenantContextService tenantContexts,InstitutionAuthService institutionAuth,
      @Value("${app.media.storage-dir:/var/lib/quantaedge/media}") String configuredMediaRoot) {
    this.jdbc=jdbc;
    this.authorization=authorization;
    this.tenantContexts=tenantContexts;
    this.institutionAuth=institutionAuth;
    this.mediaRoot=Path.of(configuredMediaRoot).toAbsolutePath().normalize().resolve("notifications").normalize();
  }

  @PostMapping({
    "/admin/notifications/media",
    "/hrms/notifications/media",
    "/institution/notifications/media"
  })
  public Map<String,Object> upload(
      HttpServletRequest request,
      @RequestAttribute(value="authContext",required=false) AuthContext context,
      @RequestHeader(value=InstitutionTenantContextService.HEADER_NAME,required=false) String institutionCode,
      @RequestPart("file") MultipartFile file) throws IOException {
    UploadOwner owner=uploadOwner(request,context,institutionCode);
    if(file==null||file.isEmpty())throw new IllegalArgumentException("Choose an image, graphic or video first.");
    byte[] bytes=file.getBytes();
    MediaInfo info=inspect(bytes,file.getOriginalFilename());
    long size=bytes.length;
    long maximum=info.contentType().startsWith("video/")?MAX_VIDEO_BYTES:MAX_IMAGE_BYTES;
    if(size>maximum)throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
        info.contentType().startsWith("video/")?"Video assets must be 100 MiB or smaller.":"Image and animation assets must be 12 MiB or smaller.");
    int width=0,height=0;
    if(info.contentType().startsWith("image/")) {
      try {
        // The JDK has no built-in WebP decoder. Other accepted raster formats must decode successfully.
        if(!"image/webp".equals(info.contentType())) {
          BufferedImage image=ImageIO.read(new ByteArrayInputStream(bytes));
          if(image==null) throw new IllegalArgumentException("The selected image is corrupt or unsupported.");
          width=image.getWidth();height=image.getHeight();
          if(width<1||height<1||(long)width*height>MAX_IMAGE_PIXELS) {
            throw new IllegalArgumentException("Image dimensions exceed the safe rendering limit.");
          }
        }
      } catch(OutOfMemoryError unsafeImage) {
        throw new IllegalArgumentException("This image could not be safely decoded.");
      }
    }
    UUID publicId=UUID.randomUUID();
    String extension=info.extension();
    Path folder=mediaRoot;
    Files.createDirectories(folder);
    Path destination=folder.resolve(publicId+"."+extension).normalize();
    Path temporary=folder.resolve(publicId+".part").normalize();
    if(!destination.startsWith(folder)||!temporary.startsWith(folder))throw new SecurityException("Invalid media storage path.");
    String relativeKey=publicId+"."+extension;
    try {
      Files.write(temporary,bytes);
      try {Files.move(temporary,destination,StandardCopyOption.ATOMIC_MOVE);}
      catch(AtomicMoveNotSupportedException noAtomicMove) {Files.move(temporary,destination);}
      String sha=sha256(bytes);
      Long id=jdbc.queryForObject("""
        insert into notification_media_asset(
          public_id,owner_scope,institution_id,storage_key,original_file_name,content_type,
          sha256,byte_length,width,height,status,uploaded_by_staff_id,uploaded_by_institution_admin_id)
        values (?,?,?,?,?,?,?,?,?,?,'READY',?,?) returning id
        """,Long.class,publicId,owner.ownerScope(),owner.institutionId(),relativeKey,
        safeFileName(file.getOriginalFilename()),info.contentType(),sha,size,
        width>0?width:null,height>0?height:null,owner.staffId(),owner.institutionAdminId());
      jdbc.update("""
        insert into notification_audit(institution_id,actor_workspace,actor_staff_id,
          actor_institution_admin_id,action_code,details)
        values (?,?,?,?, 'MEDIA_UPLOADED',jsonb_build_object('assetId',?,'contentType',?,'bytes',?,'sha256',?))
        """,owner.institutionId(),owner.workspace(),owner.staffId(),owner.institutionAdminId(),
        publicId.toString(),info.contentType(),size,sha);
      Map<String,Object> response=new LinkedHashMap<>();
      response.put("assetId",publicId.toString());
      response.put("contentType",info.contentType());
      response.put("byteLength",size);
      response.put("sha256",sha);
      response.put("width",width>0?width:null);
      response.put("height",height>0?height:null);
      response.put("previewUrl",previewUrl(owner.workspace(),publicId));
      response.put("ready",true);
      return response;
    } catch(RuntimeException|IOException failure) {
      Files.deleteIfExists(temporary);
      Files.deleteIfExists(destination);
      throw failure;
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  @GetMapping({
    "/admin/notifications/media/{assetId}",
    "/hrms/notifications/media/{assetId}",
    "/institution/notifications/media/{assetId}"
  })
  public ResponseEntity<byte[]> preview(
      HttpServletRequest request,
      @PathVariable UUID assetId,
      @RequestAttribute(value="authContext",required=false) AuthContext context,
      @RequestHeader(value=InstitutionTenantContextService.HEADER_NAME,required=false) String institutionCode) throws IOException {
    UploadOwner requester=uploadOwner(request,context,institutionCode);
    MediaRow asset=findAsset(assetId);
    if("PLATFORM".equals(asset.ownerScope())) {
      if(!"HRMS".equals(requester.workspace()))throw new SecurityException("Only an HRMS platform owner can preview platform-owned media.");
    } else if(requester.institutionId()==null||!requester.institutionId().equals(asset.institutionId())) {
      throw new SecurityException("This media asset belongs to another institution.");
    }
    return mediaResponse(asset);
  }

  @GetMapping("/notifications/media/{assetId}")
  public ResponseEntity<byte[]> readForRecipient(
      @PathVariable UUID assetId,
      @RequestAttribute(value="authContext",required=false) AuthContext context,
      @RequestAttribute(value=InstitutionTenantContextService.REQUEST_ATTRIBUTE,required=false) InstitutionTenantContext tenant,
      @RequestHeader(value=InstitutionTenantContextService.HEADER_NAME,required=false) String institutionCode) throws IOException {
    context=authorization.requireAuth(context);
    if(!context.isStudent()&&!context.isParent())throw new SecurityException("Notification media is available only to its authorized recipient or creator.");
    InstitutionTenantContext selected=tenant!=null?tenant:tenantContexts.requireSelector(institutionCode);
    tenantContexts.assertActiveMembership(context,selected);
    MediaRow asset=findAsset(assetId);
    if(asset.institutionId()!=null&&asset.institutionId()!=selected.institutionId()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Notification media was not found.");
    }
    String identityCondition=context.studentId()!=null?"r.student_id=? and r.recipient_kind='STUDENT'":"r.user_account_id=? and r.recipient_kind='USER_ACCOUNT'";
    Long identity=context.studentId()!=null?context.studentId():context.userId();
    Boolean allowed=jdbc.queryForObject("""
      select exists(
        select 1 from notification_recipient r
        join notification_campaign c on c.id=r.campaign_id and c.status='SENT'
        where (r.institution_id=? or (r.institution_id is null and c.scope='HRMS_PLATFORM')) and """+identityCondition+"""
          and (c.content_document::text like ?)
          and (c.expires_at is null or c.expires_at>now())
      )
      """,Boolean.class,selected.institutionId(),identity,"%"+assetId+"%");
    if(!Boolean.TRUE.equals(allowed))throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Notification media was not found.");
    return mediaResponse(asset);
  }

  private MediaRow findAsset(UUID publicId) {
    List<Map<String,Object>> rows=jdbc.queryForList("""
      select id,public_id,owner_scope,institution_id,storage_key,content_type,sha256,byte_length,status
      from notification_media_asset where public_id=? and status='READY'
      """,publicId);
    if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Media asset was not found.");
    Map<String,Object> row=rows.getFirst();
    return new MediaRow(String.valueOf(row.get("owner_scope")),
      row.get("institution_id") instanceof Number n?n.longValue():null,
      String.valueOf(row.get("storage_key")),String.valueOf(row.get("content_type")),
      String.valueOf(row.get("sha256")),((Number)row.get("byte_length")).longValue());
  }

  private ResponseEntity<byte[]> mediaResponse(MediaRow asset) throws IOException {
    Path path=mediaRoot.resolve(asset.storageKey()).normalize();
    if(!path.startsWith(mediaRoot)||!Files.isRegularFile(path)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Media asset is not available.");
    }
    byte[] bytes=Files.readAllBytes(path);
    if(bytes.length!=asset.byteLength()||!sha256(bytes).equals(asset.sha256())) {
      throw new ResponseStatusException(HttpStatus.GONE,"Media asset integrity verification failed.");
    }
    HttpHeaders headers=new HttpHeaders();
    headers.setContentType(MediaType.parseMediaType(asset.contentType()));
    headers.setCacheControl(CacheControl.noStore());
    headers.set("X-Content-Type-Options","nosniff");
    headers.set("Content-Security-Policy","default-src 'none'; sandbox");
    headers.setContentLength(bytes.length);
    return ResponseEntity.ok().headers(headers).body(bytes);
  }

  private UploadOwner uploadOwner(HttpServletRequest request,AuthContext context,String institutionCode) {
    String path=request.getRequestURI();
    if(path.startsWith("/api/v1/hrms/")) {
      context=authorization.requirePermission(context,"QE_PLATFORM_OWNER");
      if(context.staffId()==null||!"HRMS".equals(AuthService.normalizeWorkspace(context.workspace()))) {
        throw new SecurityException("An HRMS platform-owner session is required.");
      }
      return new UploadOwner("HRMS","PLATFORM",null,context.staffId(),null);
    }
    if(path.startsWith("/api/v1/institution/")) {
      InstitutionAuthService.InstitutionPrincipal principal=institutionPrincipal(request);
      return new UploadOwner("INSTITUTION_ADMIN","INSTITUTION",principal.institutionId(),null,principal.adminId());
    }
    context=authorization.requirePermission(context,"NOTIFICATION_TEMPLATE_MANAGE");
    String configured=tenantContexts.selectTenantCode(institutionCode);
    if(configured==null||configured.isBlank())configured="QUANTA-EDGE";
    InstitutionTenantContext tenant=tenantContexts.requireSelector(configured);
    if(!"QUANTA-EDGE".equals(tenant.tenantCode())) {
      throw new SecurityException("Education Admin media uploads are restricted to the QuantaEdge institution.");
    }
    return new UploadOwner("EDUCATION_ADMIN","INSTITUTION",tenant.institutionId(),context.staffId(),null);
  }

  private InstitutionAuthService.InstitutionPrincipal institutionPrincipal(HttpServletRequest request) {
    String token=cookieValue(request,AuthService.INSTITUTION_COOKIE);
    InstitutionAuthService.InstitutionPrincipal principal=institutionAuth.current(token);
    if(principal==null||principal.mustChangePassword())throw new SecurityException("An active institution-admin session is required.");
    return principal;
  }

  private static String cookieValue(HttpServletRequest request,String name) {
    if(request.getCookies()==null)return null;
    for(Cookie cookie:request.getCookies())if(name.equals(cookie.getName()))return cookie.getValue();
    return null;
  }

  private static MediaInfo inspect(byte[] bytes,String originalName) {
    if(bytes.length<12)throw new IllegalArgumentException("The selected media file is empty or invalid.");
    String name=safeFileName(originalName).toLowerCase(Locale.ROOT);
    if((bytes[0]&0xff)==0xff&&(bytes[1]&0xff)==0xd8&&(bytes[2]&0xff)==0xff) {
      return new MediaInfo("image/jpeg","jpg");
    }
    if(bytes.length>=8&&(bytes[0]&0xff)==0x89&&bytes[1]=='P'&&bytes[2]=='N'&&bytes[3]=='G'
        &&bytes[4]==13&&bytes[5]==10&&bytes[6]==26&&bytes[7]==10) {
      return new MediaInfo("image/png","png");
    }
    if(bytes.length>=12&&ascii(bytes,0,4).equals("RIFF")&&ascii(bytes,8,4).equals("WEBP")) {
      return new MediaInfo("image/webp","webp");
    }
    if(ascii(bytes,0,6).equals("GIF87a")||ascii(bytes,0,6).equals("GIF89a")) {
      return new MediaInfo("image/gif","gif");
    }
    if(ascii(bytes,4,4).equals("ftyp")) {
      return new MediaInfo("video/mp4","mp4");
    }
    if((bytes[0]&0xff)==0x1a&&(bytes[1]&0xff)==0x45&&(bytes[2]&0xff)==0xdf&&(bytes[3]&0xff)==0xa3) {
      return new MediaInfo("video/webm","webm");
    }
    throw new IllegalArgumentException("Supported media formats are JPEG, PNG, WebP, GIF, MP4 and WebM. SVG and arbitrary files are not supported.");
  }

  private static String ascii(byte[] bytes,int offset,int length) {
    return new String(bytes,offset,length,java.nio.charset.StandardCharsets.US_ASCII);
  }

  private static String safeFileName(String raw) {
    String value=raw==null?"media":raw.replace('\\','/').replaceAll("^.*/","");
    value=value.replaceAll("[^A-Za-z0-9._ -]","_").trim();
    if(value.isBlank())value="media";
    return value.length()>180?value.substring(value.length()-180):value;
  }

  private static String sha256(byte[] bytes) {
    try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    catch(java.security.NoSuchAlgorithmException ex){throw new IllegalStateException("SHA-256 is unavailable.",ex);}
  }

  private static String previewUrl(String workspace,UUID id) {
    return "HRMS".equals(workspace)?"/api/v1/hrms/notifications/media/"+id:
      "INSTITUTION_ADMIN".equals(workspace)?"/api/v1/institution/notifications/media/"+id:
      "/api/v1/admin/notifications/media/"+id;
  }

  private record UploadOwner(String workspace,String ownerScope,Long institutionId,Long staffId,Long institutionAdminId) {}
  private record MediaInfo(String contentType,String extension) {}
  private record MediaRow(String ownerScope,Long institutionId,String storageKey,String contentType,String sha256,long byteLength) {}
}
