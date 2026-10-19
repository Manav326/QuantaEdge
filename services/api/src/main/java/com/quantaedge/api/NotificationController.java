package com.quantaedge.api;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Shared rich-notification contract. Workspace path and authenticated principal, never a body
 * field, determine the caller's authority and platform/institution ownership.
 */
@RestController
@RequestMapping("/api/v1")
public class NotificationController {
  private final RichNotificationService notifications;
  private final AuthorizationService authorization;
  private final InstitutionTenantContextService tenantContexts;
  private final InstitutionAuthService institutionAuth;
  private final JdbcTemplate jdbc;

  public NotificationController(
      RichNotificationService notifications,
      AuthorizationService authorization,
      InstitutionTenantContextService tenantContexts,
      InstitutionAuthService institutionAuth,
      JdbcTemplate jdbc) {
    this.notifications=notifications;
    this.authorization=authorization;
    this.tenantContexts=tenantContexts;
    this.institutionAuth=institutionAuth;
    this.jdbc=jdbc;
  }

  @GetMapping("/notifications")
  public Map<String,Object> inbox(
      @RequestAttribute(value="authContext",required=false) AuthContext context,
      @RequestAttribute(value=InstitutionTenantContextService.REQUEST_ATTRIBUTE,required=false) InstitutionTenantContext tenant,
      @RequestHeader(value=InstitutionTenantContextService.HEADER_NAME,required=false) String institutionCode,
      @RequestParam(defaultValue="50") int limit,
      @RequestParam(required=false) Long beforeId,
      @RequestParam(defaultValue="false") boolean unreadOnly) {
    context=authorization.requireAuth(context);
    if(!context.isStudent()&&!context.isParent()) throw new SecurityException("The user notification inbox is available to learner and parent accounts.");
    InstitutionTenantContext selected=tenant!=null?tenant:tenantContexts.requireSelector(institutionCode);
    tenantContexts.assertActiveMembership(context,selected);
    List<Map<String,Object>> rows=notifications.inbox(context,selected.institutionId(),limit,beforeId,unreadOnly);
    Map<String,Object> response=new LinkedHashMap<>();
    response.put("items",rows);
    response.put("limit",Math.max(1,Math.min(limit,100)));
    response.put("nextBeforeId",rows.isEmpty()?null:rows.getLast().get("recipient_id"));
    response.put("unreadOnly",unreadOnly);
    return response;
  }

  @PostMapping("/notifications/{recipientId}/read")
  public Map<String,Object> markRead(
      @PathVariable long recipientId,
      @RequestAttribute(value="authContext",required=false) AuthContext context,
      @RequestAttribute(value=InstitutionTenantContextService.REQUEST_ATTRIBUTE,required=false) InstitutionTenantContext tenant,
      @RequestHeader(value=InstitutionTenantContextService.HEADER_NAME,required=false) String institutionCode) {
    if(recipientId<1) throw new IllegalArgumentException("Notification identity is invalid.");
    context=authorization.requireAuth(context);
    if(!context.isStudent()&&!context.isParent()) throw new SecurityException("Only a notification recipient can update read state.");
    InstitutionTenantContext selected=tenant!=null?tenant:tenantContexts.requireSelector(institutionCode);
    tenantContexts.assertActiveMembership(context,selected);
    boolean updated=notifications.markRead(context,selected.institutionId(),recipientId,false);
    if(!updated) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Notification was not found for this recipient.");
    return Map.of("read",true,"recipientId",recipientId);
  }

  @PostMapping("/notifications/read-all")
  public Map<String,Object> markAllRead(
      @RequestAttribute(value="authContext",required=false) AuthContext context,
      @RequestAttribute(value=InstitutionTenantContextService.REQUEST_ATTRIBUTE,required=false) InstitutionTenantContext tenant,
      @RequestHeader(value=InstitutionTenantContextService.HEADER_NAME,required=false) String institutionCode) {
    context=authorization.requireAuth(context);
    if(!context.isStudent()&&!context.isParent()) throw new SecurityException("Only a notification recipient can update read state.");
    InstitutionTenantContext selected=tenant!=null?tenant:tenantContexts.requireSelector(institutionCode);
    tenantContexts.assertActiveMembership(context,selected);
    notifications.markRead(context,selected.institutionId(),0L,true);
    return Map.of("readAll",true);
  }

  @GetMapping({
    "/admin/notifications/options",
    "/hrms/notifications/options",
    "/institution/notifications/options"
  })
  public Map<String,Object> options(HttpServletRequest request,
      @RequestAttribute(value="authContext",required=false) AuthContext context,
      @RequestHeader(value=InstitutionTenantContextService.HEADER_NAME,required=false) String institutionCode) {
    RichNotificationService.Actor actor=actor(request,context,institutionCode,"NOTIFICATION_TEMPLATE_MANAGE");
    Map<String,Object> result=new LinkedHashMap<>();
    if("HRMS".equals(actor.workspace())) {
      result.put("institutions",jdbc.queryForList("""
        select id,tenant_code as institution_code,display_name,status
        from institution_tenant where status='ACTIVE' order by lower(display_name),id limit 1000
        """));
      result.put("courses",List.of());
      result.put("recipientKinds",List.of("STUDENT","PARENT","INSTITUTION_ADMINS"));
    } else {
      result.put("institutions",List.of(Map.of(
        "id",actor.institutionId(),"institution_code","INSTITUTION","display_name",
        "INSTITUTION_ADMIN".equals(actor.workspace())?"Current institution":"QuantaEdge","status","ACTIVE")));
      result.put("courses",jdbc.queryForList("""
        select distinct p.id as course_id,p.code as course_code,p.display_name as course_name,
          c.code as class_code
        from institution_course_assignment a
        join institution_course_release r on r.id=a.course_release_id
        join curriculum_program p on p.id=r.program_id and p.status='ACTIVE' and p.code<>'GENERAL'
        join curriculum_class c on c.id=p.class_id
        where a.institution_id=? and a.status='ASSIGNED'
        order by p.code,p.id
        """,actor.institutionId()));
      result.put("recipientKinds",List.of("STUDENT","PARENT","INSTITUTION_ADMINS"));
    }
    result.put("maxRecipients",10000);
    result.put("supportsRichBlocks",true);
    result.put("supportsScheduledSend",true);
    return result;
  }

  @GetMapping({
    "/admin/notifications/recipients",
    "/institution/notifications/recipients"
  })
  public Map<String,Object> recipientOptions(HttpServletRequest request,
      @RequestAttribute(value="authContext",required=false) AuthContext context,
      @RequestHeader(value=InstitutionTenantContextService.HEADER_NAME,required=false) String institutionCode,
      @RequestParam(required=false,defaultValue="") String query,
      @RequestParam(required=false,defaultValue="STUDENT") String kind) {
    RichNotificationService.Actor actor=actor(request,context,institutionCode,"NOTIFICATION_TEMPLATE_MANAGE");
    String normalized=kind==null?"STUDENT":kind.trim().toUpperCase(java.util.Locale.ROOT);
    String needle=query==null?"":query.trim();
    if(needle.length()>80)throw new IllegalArgumentException("Recipient search text is too long.");
    List<Map<String,Object>> rows;
    if("STUDENT".equals(normalized)) {
      rows=jdbc.queryForList("""
        select distinct st.id as recipient_id,'STUDENT' as recipient_kind,
          st.display_name as recipient_name,st.class_code
        from student st join institution_membership m on m.student_id=st.id
          and m.institution_id=? and m.status='ACTIVE'
        where st.active=true and (?='' or st.display_name ilike '%'||?||'%')
        order by lower(st.display_name),st.id limit 50
        """,actor.institutionId(),needle,needle);
    } else if("USER_ACCOUNT".equals(normalized)) {
      rows=jdbc.queryForList("""
        select distinct u.id as recipient_id,'USER_ACCOUNT' as recipient_kind,
          u.display_name as recipient_name,null::varchar as class_code
        from user_account u
        join institution_user_membership um on um.user_id=u.id and um.institution_id=? and um.status='ACTIVE'
        join guardian_student gs on gs.guardian_user_id=u.id and gs.active=true
          and gs.consent_status='CONSENTED' and gs.access_level='FULL'
        join student st on st.id=gs.student_id and st.active=true
        join institution_membership m on m.student_id=st.id and m.institution_id=um.institution_id and m.status='ACTIVE'
        where u.active=true and (?='' or u.display_name ilike '%'||?||'%')
        order by lower(u.display_name),u.id limit 50
        """,actor.institutionId(),needle,needle);
    } else if("INSTITUTION_ADMIN".equals(normalized)) {
      rows=jdbc.queryForList("""
        select id as recipient_id,'INSTITUTION_ADMIN' as recipient_kind,
          display_name as recipient_name,null::varchar as class_code
        from institution_admin_account where institution_id=? and active=true
          and (?='' or display_name ilike '%'||?||'%')
        order by lower(display_name),id limit 50
        """,actor.institutionId(),needle,needle);
    } else {
      throw new IllegalArgumentException("Choose Student, Parent/Guardian or Institution Admin.");
    }
    return Map.of("recipients",rows,"limit",50);
  }

  @GetMapping("/institution/notifications")
  public Map<String,Object> institutionInbox(HttpServletRequest request,
      @RequestParam(defaultValue="50") int limit,@RequestParam(required=false) Long beforeId,
      @RequestParam(defaultValue="false") boolean unreadOnly) {
    InstitutionAuthService.InstitutionPrincipal principal=institutionPrincipal(request);
    List<Map<String,Object>> rows=notifications.inboxForInstitutionAdmin(
      principal.adminId(),principal.institutionId(),limit,beforeId,unreadOnly);
    Map<String,Object> response=new LinkedHashMap<>();
    response.put("items",rows);
    response.put("nextBeforeId",rows.isEmpty()?null:rows.getLast().get("recipient_id"));
    response.put("limit",Math.max(1,Math.min(limit,100)));
    return response;
  }

  @PostMapping("/institution/notifications/{recipientId}/read")
  public Map<String,Object> markInstitutionRead(HttpServletRequest request,@PathVariable long recipientId) {
    InstitutionAuthService.InstitutionPrincipal principal=institutionPrincipal(request);
    boolean updated=notifications.markReadForInstitutionAdmin(
      principal.adminId(),principal.institutionId(),recipientId,false);
    if(!updated)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Notification was not found for this recipient.");
    return Map.of("read",true,"recipientId",recipientId);
  }

  @PostMapping("/institution/notifications/read-all")
  public Map<String,Object> markInstitutionAllRead(HttpServletRequest request) {
    InstitutionAuthService.InstitutionPrincipal principal=institutionPrincipal(request);
    notifications.markReadForInstitutionAdmin(principal.adminId(),principal.institutionId(),0L,true);
    return Map.of("readAll",true);
  }

  @GetMapping({
    "/admin/notifications/templates",
    "/hrms/notifications/templates",
    "/institution/notifications/templates"
  })
  public Map<String,Object> templates(HttpServletRequest request,
      @RequestAttribute(value="authContext",required=false) AuthContext context,
      @RequestHeader(value=InstitutionTenantContextService.HEADER_NAME,required=false) String institutionCode) {
    RichNotificationService.Actor actor=actor(request,context,institutionCode,"NOTIFICATION_TEMPLATE_MANAGE");
    return Map.of("templates",notifications.listTemplates(actor));
  }

  @PostMapping({
    "/admin/notifications/templates",
    "/hrms/notifications/templates",
    "/institution/notifications/templates"
  })
  public Map<String,Object> createTemplate(HttpServletRequest request,
      @RequestAttribute(value="authContext",required=false) AuthContext context,
      @RequestHeader(value=InstitutionTenantContextService.HEADER_NAME,required=false) String institutionCode,
      @RequestBody Map<String,Object> body) {
    RichNotificationService.Actor actor=actor(request,context,institutionCode,"NOTIFICATION_TEMPLATE_MANAGE");
    return notifications.createTemplate(actor,text(body,"templateName"),text(body,"description"),body.get("contentDocument"));
  }

  @PutMapping({
    "/admin/notifications/templates/{templateId}",
    "/hrms/notifications/templates/{templateId}",
    "/institution/notifications/templates/{templateId}"
  })
  public Map<String,Object> updateTemplate(HttpServletRequest request,
      @RequestAttribute(value="authContext",required=false) AuthContext context,
      @RequestHeader(value=InstitutionTenantContextService.HEADER_NAME,required=false) String institutionCode,
      @PathVariable long templateId,@RequestBody Map<String,Object> body) {
    RichNotificationService.Actor actor=actor(request,context,institutionCode,"NOTIFICATION_TEMPLATE_MANAGE");
    return notifications.updateTemplate(actor,templateId,text(body,"templateName"),text(body,"description"),body.get("contentDocument"));
  }

  @PostMapping({
    "/admin/notifications/preview",
    "/hrms/notifications/preview",
    "/institution/notifications/preview"
  })
  public Map<String,Object> preview(HttpServletRequest request,
      @RequestAttribute(value="authContext",required=false) AuthContext context,
      @RequestHeader(value=InstitutionTenantContextService.HEADER_NAME,required=false) String institutionCode,
      @RequestBody Map<String,Object> body) {
    RichNotificationService.Actor actor=actorForSend(request,context,institutionCode,body);
    Target target=target(request,actor,body,institutionCode);
    return notifications.preview(actor,target.scope(),target.institutionId(),target.courseId(),target.filter(),
      text(body,"title"),text(body,"summary"),document(request,actor,body));
  }

  @PostMapping({
    "/admin/notifications/send",
    "/hrms/notifications/send",
    "/institution/notifications/send"
  })
  public Map<String,Object> send(HttpServletRequest request,
      @RequestAttribute(value="authContext",required=false) AuthContext context,
      @RequestHeader(value=InstitutionTenantContextService.HEADER_NAME,required=false) String institutionCode,
      @RequestBody Map<String,Object> body) {
    RichNotificationService.Actor actor=actorForSend(request,context,institutionCode,body);
    Target target=target(request,actor,body,institutionCode);
    Long templateId=numberOrNull(body.get("templateId"));
    Integer templateVersion=integerOrNull(body.get("templateVersion"));
    Instant scheduledAt=instantOrNull(body.get("scheduledAt"));
    Instant expiresAt=instantOrNull(body.get("expiresAt"));
    String idempotencyKey=text(body,"idempotencyKey");
    if(idempotencyKey.isBlank()) throw new IllegalArgumentException("A unique idempotency key is required for sending.");
    return notifications.send(actor,target.scope(),target.institutionId(),target.courseId(),target.filter(),
      text(body,"title"),text(body,"summary"),document(request,actor,body),templateId,templateVersion,
      idempotencyKey,scheduledAt,expiresAt);
  }

  @PostMapping({
    "/admin/notifications/campaigns/{campaignId}/cancel",
    "/hrms/notifications/campaigns/{campaignId}/cancel",
    "/institution/notifications/campaigns/{campaignId}/cancel"
  })
  public Map<String,Object> cancelCampaign(HttpServletRequest request,
      @RequestAttribute(value="authContext",required=false) AuthContext context,
      @RequestHeader(value=InstitutionTenantContextService.HEADER_NAME,required=false) String institutionCode,
      @PathVariable long campaignId) {
    if(campaignId<1)throw new IllegalArgumentException("Notification campaign identity is invalid.");
    RichNotificationService.Actor actor=actor(request,context,institutionCode,"NOTIFICATION_INSTITUTION_SEND");
    return notifications.cancelCampaign(actor,campaignId);
  }

  @GetMapping({
    "/admin/notifications/campaigns",
    "/hrms/notifications/campaigns",
    "/institution/notifications/campaigns"
  })
  public Map<String,Object> campaigns(HttpServletRequest request,
      @RequestAttribute(value="authContext",required=false) AuthContext context,
      @RequestHeader(value=InstitutionTenantContextService.HEADER_NAME,required=false) String institutionCode,
      @RequestParam(defaultValue="50") int limit) {
    RichNotificationService.Actor actor=actor(request,context,institutionCode,"NOTIFICATION_CAMPAIGN_VIEW");
    return Map.of("campaigns",notifications.listCampaigns(actor,limit));
  }

  private RichNotificationService.Actor actorForSend(HttpServletRequest request,AuthContext context,String institutionCode,Map<String,Object> body) {
    String path=request.getRequestURI();
    String permission="NOTIFICATION_INSTITUTION_SEND";
    if("COURSE".equalsIgnoreCase(text(body,"scope"))) permission="NOTIFICATION_COURSE_SEND";
    if(path.startsWith("/api/v1/hrms/")) permission="QE_PLATFORM_OWNER";
    if(path.startsWith("/api/v1/institution/")) permission="INSTITUTION_ADMIN_SEND";
    return actor(request,context,institutionCode,permission);
  }

  private RichNotificationService.Actor actor(HttpServletRequest request,AuthContext context,String institutionCode,String permission) {
    String path=request.getRequestURI();
    if(path.startsWith("/api/v1/hrms/")) {
      context=authorization.requirePermission(context,"QE_PLATFORM_OWNER");
      if(context.staffId()==null||!"HRMS".equals(AuthService.normalizeWorkspace(context.workspace()))) {
        throw new SecurityException("An HRMS platform-owner session is required.");
      }
      return new RichNotificationService.Actor("HRMS",context.staffId(),null,null);
    }
    if(path.startsWith("/api/v1/institution/")) {
      InstitutionAuthService.InstitutionPrincipal principal=institutionPrincipal(request);
      return new RichNotificationService.Actor("INSTITUTION_ADMIN",null,principal.adminId(),principal.institutionId());
    }
    context=authorization.requirePermission(context,permission);
    InstitutionTenantContext tenant=educationAdminTenant(institutionCode);
    // The separate Institution Admin portal is the multi-tenant institution sender. Education
    // Admin remains bound to QuantaEdge until an explicit staff-to-institution grant exists.
    if(!"QUANTA-EDGE".equals(tenant.tenantCode())) {
      throw new SecurityException("Education Admin notifications are restricted to the QuantaEdge institution. Use that institution's authenticated Admin portal for other tenants.");
    }
    return new RichNotificationService.Actor(
      "EDUCATION_ADMIN",context.staffId(),null,tenant.institutionId());
  }

  private Target target(HttpServletRequest request,RichNotificationService.Actor actor,Map<String,Object> body,String institutionCode) {
    String path=request.getRequestURI();
    String scope=text(body,"scope").trim().toUpperCase(java.util.Locale.ROOT);
    Long courseId=numberOrNull(body.get("courseId"));
    Long institutionId=actor.institutionId();
    Map<String,Object> filter=map(body.get("audienceFilter"));
    if("HRMS".equals(actor.workspace())) {
      if(!"HRMS_PLATFORM".equals(scope)) {
        throw new SecurityException("HRMS platform campaigns must use HRMS_PLATFORM scope.");
      }
      institutionId=null;
      courseId=null;
    } else if("INSTITUTION_ADMIN".equals(actor.workspace())) {
      if(!Set.of("USER","COURSE","INSTITUTION").contains(scope)) {
        throw new SecurityException("Institution Admin cannot send HRMS platform campaigns.");
      }
    } else {
      if(!Set.of("USER","COURSE","INSTITUTION").contains(scope)) {
        throw new SecurityException("Education Admin cannot send HRMS platform campaigns.");
      }
    }
    return new Target(scope,institutionId,courseId,filter);
  }

  private Object document(HttpServletRequest request,RichNotificationService.Actor actor,Map<String,Object> body) {
    Object raw=body.get("contentDocument");
    Long templateId=numberOrNull(body.get("templateId"));
    if(templateId!=null) {
      Map<String,Object> template=notifications.getTemplateForActor(actor,templateId);
      Object version=template.get("content_version");
      Integer requested=integerOrNull(body.get("templateVersion"));
      if(requested!=null && version instanceof Number n && requested.intValue()!=n.intValue()) {
        throw new IllegalArgumentException("The selected template version changed. Review the latest template and preview again.");
      }
      if(raw==null)raw=template.get("contentDocument");
    }
    if(raw==null) throw new IllegalArgumentException("Rich notification content is required.");
    return notifications.validateDocument(raw);
  }

  private InstitutionTenantContext educationAdminTenant(String suppliedCode) {
    String configured=tenantContexts.selectTenantCode(suppliedCode);
    if(configured==null||configured.isBlank()) configured="QUANTA-EDGE";
    return tenantContexts.requireSelector(configured);
  }

  private InstitutionAuthService.InstitutionPrincipal institutionPrincipal(HttpServletRequest request) {
    String token=cookieValue(request,AuthService.INSTITUTION_COOKIE);
    InstitutionAuthService.InstitutionPrincipal principal=institutionAuth.current(token);
    if(principal==null||principal.mustChangePassword()) {
      throw new SecurityException("An active institution-admin session with a completed password setup is required.");
    }
    return principal;
  }

  private static String cookieValue(HttpServletRequest request,String name) {
    if(request.getCookies()==null)return null;
    for(Cookie cookie:request.getCookies()) if(name.equals(cookie.getName())) return cookie.getValue();
    return null;
  }

  private static Map<String,Object> map(Object value) {
    if(value instanceof Map<?,?> raw) {
      Map<String,Object> result=new LinkedHashMap<>();
      raw.forEach((key,item)->result.put(String.valueOf(key),item));
      return result;
    }
    return Map.of();
  }

  private static String text(Map<String,Object> body,String key) {
    Object value=body.get(key);
    return value==null?"":String.valueOf(value);
  }

  private static Long numberOrNull(Object value) {
    if(value==null||String.valueOf(value).isBlank())return null;
    try{return Long.valueOf(String.valueOf(value));}
    catch(NumberFormatException ex){throw new IllegalArgumentException("A numeric reference is invalid.");}
  }

  private static Integer integerOrNull(Object value) {
    if(value==null||String.valueOf(value).isBlank())return null;
    try{return Integer.valueOf(String.valueOf(value));}
    catch(NumberFormatException ex){throw new IllegalArgumentException("A version reference is invalid.");}
  }

  private static Instant instantOrNull(Object value) {
    if(value==null||String.valueOf(value).isBlank())return null;
    try{return Instant.parse(String.valueOf(value));}
    catch(RuntimeException ex){throw new IllegalArgumentException("Use an ISO-8601 timestamp with timezone for scheduled notifications.");}
  }

  private record Target(String scope,Long institutionId,Long courseId,Map<String,Object> filter) {}
}
