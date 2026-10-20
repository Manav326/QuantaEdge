package com.quantaedge.api;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class NotificationDeviceController {
  private final AuthorizationService authorization;
  private final InstitutionTenantContextService tenantContexts;
  private final JdbcTemplate jdbc;
  private final AndroidPushService push;

  public NotificationDeviceController(AuthorizationService authorization,
      InstitutionTenantContextService tenantContexts, JdbcTemplate jdbc, AndroidPushService push) {
    this.authorization=authorization; this.tenantContexts=tenantContexts; this.jdbc=jdbc; this.push=push;
  }

  @PostMapping("/notifications/devices")
  @Transactional
  public Map<String,Object> registerDevice(
      @RequestAttribute(value="authContext",required=false) AuthContext context,
      @RequestAttribute(value=InstitutionTenantContextService.REQUEST_ATTRIBUTE,required=false) InstitutionTenantContext tenant,
      @RequestHeader(value=InstitutionTenantContextService.HEADER_NAME,required=false) String institutionCode,
      @RequestBody Map<String,Object> body) {
    context=learnerOrParent(context);
    InstitutionTenantContext selected=tenant!=null?tenant:tenantContexts.requireSelector(institutionCode);
    tenantContexts.assertActiveMembership(context,selected);
    String deviceId=uuid(body,"deviceId");
    String token=required(body,"token",4096);
    if(token.length()<100||token.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("The Firebase registration token is invalid.");
    if(!"ANDROID".equalsIgnoreCase(String.valueOf(body.getOrDefault("platform","ANDROID")).trim())) throw new IllegalArgumentException("Only Android registrations are supported.");
    String packageName=required(body,"packageName",220);
    if(!packageName.matches("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")) throw new IllegalArgumentException("The Android application identifier is invalid.");
    String appVersion=optional(body,"appVersion",80);
    String kind=context.studentId()!=null?"STUDENT":"USER_ACCOUNT";
    Long studentId=context.studentId(), userId=context.isParent()?context.userId():null;
    String tokenHash=AndroidPushService.sha256(token);
    // A rotated token can already be associated with another installation. Revoke that binding,
    // then update this installation in place so launches do not accumulate stale registration rows.
    jdbc.update("""
      update notification_device_registration set status='REVOKED',revoked_at=now(),updated_at=now()
      where status='ACTIVE' and token_hash=? and device_id<>?::uuid
      """,tokenHash,deviceId);
    int refreshed=jdbc.update("""
      update notification_device_registration
      set identity_kind=?,student_id=?,user_account_id=?,institution_id=?,device_token=?,token_hash=?,
          platform='ANDROID',package_name=?,app_version=?,status='ACTIVE',revoked_at=null,
          last_seen_at=now(),updated_at=now()
      where device_id=?::uuid and status='ACTIVE'
      """,kind,studentId,userId,selected.institutionId(),token,tokenHash,packageName,appVersion,deviceId);
    if(refreshed==0) {
      jdbc.update("""
        insert into notification_device_registration(
          device_id,identity_kind,student_id,user_account_id,institution_id,device_token,token_hash,
          platform,package_name,app_version,status,registered_at,last_seen_at,updated_at)
        values (?::uuid,?,?,?,?,?,?,'ANDROID',?,?,'ACTIVE',now(),now(),now())
        """,deviceId,kind,studentId,userId,selected.institutionId(),token,tokenHash,packageName,appVersion);
    }
    Map<String,Object> result=new LinkedHashMap<>();
    result.put("registered",true); result.put("deviceId",deviceId);
    result.put("institutionCode",selected.institutionCode()); result.put("providerConfigured",push.isConfigured());
    return result;
  }

  @PostMapping("/notifications/devices/revoke")
  @Transactional
  public Map<String,Object> revokeDevice(
      @RequestAttribute(value="authContext",required=false) AuthContext context,
      @RequestAttribute(value=InstitutionTenantContextService.REQUEST_ATTRIBUTE,required=false) InstitutionTenantContext tenant,
      @RequestHeader(value=InstitutionTenantContextService.HEADER_NAME,required=false) String institutionCode,
      @RequestBody Map<String,Object> body) {
    context=learnerOrParent(context);
    InstitutionTenantContext selected=tenant!=null?tenant:tenantContexts.requireSelector(institutionCode);
    tenantContexts.assertActiveMembership(context,selected);
    String deviceId=uuid(body,"deviceId");
    String kind=context.studentId()!=null?"STUDENT":"USER_ACCOUNT";
    Long identityId=context.studentId()!=null?context.studentId():context.userId();
    int updated=jdbc.update("""
      update notification_device_registration set status='REVOKED',revoked_at=now(),updated_at=now()
      where device_id=?::uuid and institution_id=? and identity_kind=? and status='ACTIVE'
        and ((?='STUDENT' and student_id=?) or (?='USER_ACCOUNT' and user_account_id=?))
      """,deviceId,selected.institutionId(),kind,kind,identityId,kind,identityId);
    return Map.of("revoked",updated>0,"deviceId",deviceId);
  }

  /** HRMS-only health summary; never returns credentials or raw device tokens. */
  @GetMapping("/hrms/notifications/push/status")
  public Map<String,Object> pushStatus(@RequestAttribute(value="authContext",required=false) AuthContext context) {
    authorization.requirePermission(context,"QE_PLATFORM_OWNER");
    Long devices=jdbc.queryForObject("select count(*) from notification_device_registration where status='ACTIVE'",Long.class);
    Long pending=jdbc.queryForObject("select count(*) from notification_delivery_attempt where channel='ANDROID_PUSH' and status in ('PENDING','RETRYING')",Long.class);
    Long failed=jdbc.queryForObject("select count(*) from notification_delivery_attempt where channel='ANDROID_PUSH' and status='FAILED' and attempted_at>=now()-interval '24 hours'",Long.class);
    return Map.of("provider","FIREBASE_CLOUD_MESSAGING","configured",push.isConfigured(),
      "activeDevices",devices==null?0L:devices,"pendingDeliveries",pending==null?0L:pending,
      "failedDeliveriesLast24Hours",failed==null?0L:failed,"credentialSource","server-side runtime configuration");
  }

  private AuthContext learnerOrParent(AuthContext context) {
    context=authorization.requireAuth(context);
    if(!context.isStudent()&&!context.isParent()) throw new SecurityException("Android push is available only to learners and parents.");
    return context;
  }
  private static String uuid(Map<String,Object> body,String key) {
    String raw=required(body,key,64);
    try { return UUID.fromString(raw).toString(); }
    catch(IllegalArgumentException ex) { throw new IllegalArgumentException("The Android installation identifier is invalid."); }
  }
  private static String required(Map<String,Object> body,String key,int maxLength) {
    String value=body.get(key)==null?"":String.valueOf(body.get(key)).trim();
    if(value.isBlank()||value.length()>maxLength) throw new IllegalArgumentException("The Android device registration is invalid.");
    return value;
  }
  private static String optional(Map<String,Object> body,String key,int maxLength) {
    String value=body.get(key)==null?"":String.valueOf(body.get(key)).trim();
    if(value.length()>maxLength||value.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("The Android device metadata is invalid.");
    return value.isBlank()?null:value;
  }
}
