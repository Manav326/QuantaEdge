package com.quantaedge.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Separate institution-client API. Every report query obtains institution scope from the
 * opaque institution_admin_session cookie; this controller deliberately never accepts a
 * tenant ID from an institution administrator.
 */
@RestController
@RequestMapping("/api/v1/institution")
public class InstitutionTenantController {
  private final InstitutionAuthService institutionAuth;
  private final AuthorizationService authorization;
  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;
  private final boolean secureCookies;

  public InstitutionTenantController(
      InstitutionAuthService institutionAuth,
      AuthorizationService authorization,
      JdbcTemplate jdbc,
      ObjectMapper objectMapper,
      @Value("${app.auth.secure-cookies:true}") boolean secureCookies) {
    this.institutionAuth = institutionAuth;
    this.authorization = authorization;
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
    this.secureCookies = secureCookies;
  }

  @PostMapping("/auth/login")
  public ResponseEntity<Map<String,Object>> login(
      @RequestBody Map<String,Object> body,
      HttpServletRequest request) {
    InstitutionAuthService.SessionTokens tokens = institutionAuth.login(
        value(body, "username"), value(body, "password"));
    auditTenant(tokens.principal(), "INSTITUTION_LOGIN", "SESSION", null, Map.of());
    return sessionResponse(tokens, tokens.principal().mustChangePassword());
  }

  @PostMapping("/auth/refresh")
  public ResponseEntity<Map<String,Object>> refresh(HttpServletRequest request) {
    String refresh = cookieValue(request, AuthService.INSTITUTION_REFRESH_COOKIE);
    InstitutionAuthService.SessionTokens tokens = institutionAuth.refresh(refresh);
    return sessionResponse(tokens, tokens.principal().mustChangePassword());
  }

  @GetMapping("/auth/me")
  public Map<String,Object> me(HttpServletRequest request) {
    InstitutionAuthService.InstitutionPrincipal principal = requirePrincipal(request, true);
    Map<String,Object> result = principalMap(principal);
    Map<String,Object> branding = brandingFor(principal.institutionId());
    result.put("branding", branding);
    String joinCode = institutionAuth.readableJoinCode(principal.institutionId());
    result.put("joinCode", joinCode);
    if (joinCode != null) {
      auditTenant(principal, "JOIN_CODE_VIEWED", "JOIN_CODE", null, Map.of("delivery", "institution-admin-copy"));
      jdbc.update("""
          insert into institution_membership_event(institution_id,event_type,actor_institution_admin_id,details)
          values (?,'JOIN_CODE_VIEWED',?,jsonb_build_object('delivery','institution-admin-copy'))
          """, principal.institutionId(), principal.adminId());
    }
    return result;
  }

  @PostMapping("/auth/logout")
  public ResponseEntity<Map<String,Object>> logout(HttpServletRequest request) {
    institutionAuth.logout(cookieValue(request, AuthService.INSTITUTION_COOKIE),
        cookieValue(request, AuthService.INSTITUTION_REFRESH_COOKIE));
    return ResponseEntity.ok()
        .header("Set-Cookie", expiredCookie(AuthService.INSTITUTION_COOKIE).toString())
        .header("Set-Cookie", expiredCookie(AuthService.INSTITUTION_REFRESH_COOKIE).toString())
        .body(Map.of("loggedOut", true));
  }

  @PostMapping("/auth/change-password")
  public ResponseEntity<Map<String,Object>> changePassword(
      @RequestBody Map<String,Object> body,
      HttpServletRequest request) {
    InstitutionAuthService.InstitutionPrincipal principal = requirePrincipal(request, true);
    institutionAuth.changePassword(principal, value(body, "currentPassword"), value(body, "newPassword"));
    institutionAuth.logout(cookieValue(request, AuthService.INSTITUTION_COOKIE),
        cookieValue(request, AuthService.INSTITUTION_REFRESH_COOKIE));
    return ResponseEntity.ok()
        .header("Set-Cookie", expiredCookie(AuthService.INSTITUTION_COOKIE).toString())
        .header("Set-Cookie", expiredCookie(AuthService.INSTITUTION_REFRESH_COOKIE).toString())
        .body(Map.of("changed", true, "signInRequired", true));
  }

  @GetMapping("/public/branding/{tenantCode}")
  public Map<String,Object> publicBranding(@PathVariable String tenantCode) {
    String normalized = tenantCode == null ? "" : tenantCode.trim().toUpperCase(Locale.ROOT);
    if (!normalized.matches("^[A-Z0-9][A-Z0-9_-]{2,31}$")) {
      throw new IllegalArgumentException("Institution code is invalid.");
    }
    List<Map<String,Object>> rows = jdbc.queryForList("""
        select t.tenant_code,coalesce(b.display_name,t.display_name) as display_name,
          b.logo_asset_key,b.app_icon_asset_key,b.store_listing_url,b.theme_tokens::text as theme_tokens,
          coalesce(b.version,1) as branding_version
        from institution_tenant t
        left join institution_branding b on b.institution_id=t.id
        where t.tenant_code=? and t.status='ACTIVE'
        """, normalized);
    if (rows.isEmpty()) throw new IllegalArgumentException("Institution branding is not available.");
    Map<String,Object> row = rows.getFirst();
    Map<String,Object> result = new LinkedHashMap<>();
    result.put("institutionCode", row.get("tenant_code"));
    result.put("displayName", row.get("display_name"));
    result.put("logoAssetKey", row.get("logo_asset_key"));
    result.put("appIconAssetKey", row.get("app_icon_asset_key"));
    result.put("storeListingUrl", row.get("store_listing_url"));
    result.put("brandingVersion", row.get("branding_version"));
    result.put("themeTokens", parseJsonObject(row.get("theme_tokens")));
    return result;
  }

  /** Authenticated learner joins the institution represented by a secret join code. */
  @PostMapping("/join")
  public Map<String,Object> joinInstitution(
      @RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext", required=false) AuthContext context,
      HttpServletRequest request) {
    AuthContext student = authorization.requireStudent(context);
    if (student.studentId() == null) throw new SecurityException("An authenticated learner is required.");
    String address = request.getRemoteAddr() == null ? "unknown" : request.getRemoteAddr();
    String sourceHash = institutionAuth.hash("institution-join-source:" + address);
    Map<String,Object> result = institutionAuth.redeemJoinCode(value(body,"code"), student.studentId(), sourceHash);
    jdbc.update("""
        insert into institution_audit_log(institution_id,action,target_type,target_id,details)
        select id,'MEMBERSHIP_JOINED','STUDENT',?,jsonb_build_object('source','institution_code')
        from institution_tenant where tenant_code=? 
        """, String.valueOf(student.studentId()), result.get("institutionCode"));
    return result;
  }

  /** Shows only the authenticated learner's own institution memberships. */
  @GetMapping("/memberships/me")
  public Map<String,Object> myInstitutionMemberships(
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    AuthContext student=authorization.requireStudent(context);
    List<Map<String,Object>> memberships=jdbc.queryForList("""
        select t.tenant_code,coalesce(b.display_name,t.display_name) as institution_name,
          t.status as institution_status,m.status as membership_status,m.joined_at,
          coalesce(b.logo_asset_key,'') as logo_asset_key,coalesce(b.version,1) as branding_version,
          (select count(*) from institution_course_entitlement e
            join curriculum_program p on p.id=e.program_id and p.status='ACTIVE' and p.code<>'GENERAL'
            where e.institution_id=t.id and e.status='ACTIVE' and e.valid_from<=now()
              and (e.valid_until is null or e.valid_until>now())) as authorized_courses
        from institution_membership m
        join institution_tenant t on t.id=m.institution_id
        left join institution_branding b on b.institution_id=t.id
        where m.student_id=? and m.status='ACTIVE'
        order by t.display_name,t.id
        """,student.studentId());
    Map<String,Object> result=new LinkedHashMap<>();
    result.put("institutions",memberships);
    return result;
  }

  @GetMapping("/reports/overview")
  public Map<String,Object> reportOverview(HttpServletRequest request) {
    InstitutionAuthService.InstitutionPrincipal principal = requirePrincipal(request, false);
    auditReport(principal, "OVERVIEW");
    List<Map<String,Object>> rows = jdbc.queryForList("""
        select
          (select count(*) from institution_membership m
            where m.institution_id=? and m.status='ACTIVE') as active_learners,
          (select count(*) from institution_membership m
            where m.institution_id=?) as all_memberships,
          (select count(*) from institution_course_entitlement e
            join curriculum_program p on p.id=e.program_id and p.status='ACTIVE' and p.code<>'GENERAL'
            where e.institution_id=? and e.status='ACTIVE' and e.valid_from<=now()
              and (e.valid_until is null or e.valid_until>now())) as authorized_courses,
          (select count(distinct p.lesson_id) from institution_membership m
            join student_lesson_progress p on p.student_id=m.student_id
            join lesson l on l.id=p.lesson_id and l.active=true and l.status='PUBLISHED'
            join curriculum_chapter ch on ch.id=l.chapter_id and ch.active=true
            join curriculum_subject sub on sub.id=ch.subject_id and sub.active=true
            join institution_course_entitlement e on e.institution_id=m.institution_id
              and e.program_id=sub.program_id and e.status='ACTIVE' and e.valid_from<=now()
              and (e.valid_until is null or e.valid_until>now())
            where m.institution_id=? and m.status='ACTIVE') as lessons_with_activity,
          (select count(distinct p.lesson_id) from institution_membership m
            join student_lesson_progress p on p.student_id=m.student_id and p.status='COMPLETED'
            join lesson l on l.id=p.lesson_id and l.active=true and l.status='PUBLISHED'
            join curriculum_chapter ch on ch.id=l.chapter_id and ch.active=true
            join curriculum_subject sub on sub.id=ch.subject_id and sub.active=true
            join institution_course_entitlement e on e.institution_id=m.institution_id
              and e.program_id=sub.program_id and e.status='ACTIVE' and e.valid_from<=now()
              and (e.valid_until is null or e.valid_until>now())
            where m.institution_id=? and m.status='ACTIVE') as completed_lessons,
          (select count(*) from institution_billable_event be
            where be.institution_id=? and be.event_type='CHARGE'
              and be.billing_period_start=date_trunc('month',current_date)::date) as current_period_billable_events,
          (select coalesce(sum(be.amount_paise),0) from institution_billable_event be
            where be.institution_id=? and be.event_type='CHARGE'
              and be.billing_period_start=date_trunc('month',current_date)::date) as current_period_amount_paise
        """, principal.institutionId(), principal.institutionId(), principal.institutionId(),
        principal.institutionId(), principal.institutionId(), principal.institutionId(), principal.institutionId());
    return rows.getFirst();
  }

  @GetMapping("/reports/roster")
  public Map<String,Object> reportRoster(
      HttpServletRequest request,
      @RequestParam(defaultValue="1") int page,
      @RequestParam(defaultValue="50") int pageSize,
      @RequestParam(defaultValue="") String query) {
    InstitutionAuthService.InstitutionPrincipal principal = requirePrincipal(request, false);
    int safePage = Math.max(1, Math.min(page, 100000));
    int safePageSize = Math.max(1, Math.min(pageSize, 200));
    int offset = (safePage - 1) * safePageSize;
    String search = query == null ? "" : query.trim();
    auditReport(principal, "ROSTER");

    List<Map<String,Object>> students = jdbc.queryForList("""
        select st.public_user_id as learner_reference,st.display_name,st.class_code,st.board,
          st.language,st.city,st.state,st.school_name,m.status as membership_status,m.joined_at,
          count(distinct case when p.status='COMPLETED' then p.lesson_id end) as completed_lessons,
          count(distinct p.lesson_id) as lessons_with_activity,
          coalesce(round(avg(p.progress_percent)),0) as average_lesson_progress_percent
        from institution_membership m
        join student st on st.id=m.student_id and st.active=true and st.environment='PRODUCTION'
        left join student_lesson_progress p on p.student_id=st.id and p.lesson_id in (
          select l.id from lesson l
          join curriculum_chapter ch on ch.id=l.chapter_id and ch.active=true
          join curriculum_subject sub on sub.id=ch.subject_id and sub.active=true
          join institution_course_entitlement e on e.institution_id=m.institution_id
            and e.program_id=sub.program_id and e.status='ACTIVE' and e.valid_from<=now()
            and (e.valid_until is null or e.valid_until>now())
          join curriculum_program pr on pr.id=e.program_id and pr.status='ACTIVE' and pr.code<>'GENERAL'
          where l.active=true and l.status='PUBLISHED'
        )
        where m.institution_id=? and (?='ALL' or m.status='ACTIVE')
          and (?='' or lower(st.display_name) like lower(?) or lower(st.class_code) like lower(?)
            or lower(coalesce(st.city,'')) like lower(?) or lower(coalesce(st.state,'')) like lower(?))
        group by st.id,m.id
        order by st.display_name,st.id
        limit ? offset ?
        """, principal.institutionId(), "ALL", search, "%"+search+"%", "%"+search+"%", "%"+search+"%", "%"+search+"%",
        safePageSize, offset);
    Integer total = jdbc.queryForObject("""
        select count(*) from institution_membership m
        join student st on st.id=m.student_id and st.active=true and st.environment='PRODUCTION'
        where m.institution_id=? and (?='' or lower(st.display_name) like lower(?) or lower(st.class_code) like lower(?)
          or lower(coalesce(st.city,'')) like lower(?) or lower(coalesce(st.state,'')) like lower(?))
        """, Integer.class, principal.institutionId(), search, "%"+search+"%", "%"+search+"%", "%"+search+"%", "%"+search+"%");
    Map<String,Object> result = new LinkedHashMap<>();
    result.put("page", safePage);
    result.put("pageSize", safePageSize);
    result.put("total", total == null ? 0 : total);
    result.put("learners", students);
    result.put("addressFieldsNote", "Only the city and state stored in the learner profile are available; the platform does not collect exact home addresses in this profile.");
    return result;
  }

  @GetMapping("/reports/courses")
  public List<Map<String,Object>> reportCourses(HttpServletRequest request) {
    InstitutionAuthService.InstitutionPrincipal principal = requirePrincipal(request, false);
    auditReport(principal, "COURSES");
    return jdbc.queryForList("""
        select p.code,p.display_name,
          e.valid_from as entitlement_start,e.valid_until as entitlement_end,
          (select count(distinct l.id) from curriculum_subject sub
            join curriculum_chapter ch on ch.subject_id=sub.id and ch.active=true
            join lesson l on l.chapter_id=ch.id and l.active=true and l.status='PUBLISHED'
            where sub.program_id=p.id and sub.active=true) as published_lessons,
          (select count(distinct m.student_id) from institution_membership m
            where m.institution_id=e.institution_id and m.status='ACTIVE') as institution_learners
        from institution_course_entitlement e
        join curriculum_program p on p.id=e.program_id and p.status='ACTIVE' and p.code<>'GENERAL'
        where e.institution_id=? and e.status='ACTIVE' and e.valid_from<=now()
          and (e.valid_until is null or e.valid_until>now())
        order by p.display_name,p.code
        """, principal.institutionId());
  }

  private InstitutionAuthService.InstitutionPrincipal requirePrincipal(HttpServletRequest request, boolean allowPasswordChange) {
    InstitutionAuthService.InstitutionPrincipal principal =
        institutionAuth.current(cookieValue(request, AuthService.INSTITUTION_COOKIE));
    if (principal == null) throw new SecurityException("Institution authentication required.");
    if (principal.mustChangePassword() && !allowPasswordChange) {
      throw new SecurityException("Change the initial institution password before continuing.");
    }
    return principal;
  }

  private ResponseEntity<Map<String,Object>> sessionResponse(
      InstitutionAuthService.SessionTokens tokens, boolean mustChangePassword) {
    return ResponseEntity.ok()
        .header("Set-Cookie", sessionCookie(AuthService.INSTITUTION_COOKIE, tokens.accessToken(), 15 * 60).toString())
        .header("Set-Cookie", sessionCookie(AuthService.INSTITUTION_REFRESH_COOKIE, tokens.refreshToken(), 7 * 24 * 60 * 60).toString())
        .body(Map.of("authenticated", true, "workspace", "INSTITUTION",
            "institutionCode", tokens.principal().tenantCode(),
            "institutionName", tokens.principal().institutionName(),
            "displayName", tokens.principal().displayName(),
            "mustChangePassword", mustChangePassword));
  }

  private ResponseCookie sessionCookie(String name, String value, long maxAgeSeconds) {
    return ResponseCookie.from(name,value).httpOnly(true).secure(secureCookies).sameSite("Strict")
        .path("/").maxAge(java.time.Duration.ofSeconds(maxAgeSeconds)).build();
  }

  private ResponseCookie expiredCookie(String name) {
    return ResponseCookie.from(name,"").httpOnly(true).secure(secureCookies).sameSite("Strict")
        .path("/").maxAge(java.time.Duration.ZERO).build();
  }

  private String cookieValue(HttpServletRequest request, String name) {
    if (request.getCookies()==null) return null;
    for (Cookie cookie : request.getCookies()) {
      if (name.equals(cookie.getName()) && cookie.getValue()!=null && !cookie.getValue().isBlank()) return cookie.getValue();
    }
    return null;
  }

  private Map<String,Object> principalMap(InstitutionAuthService.InstitutionPrincipal principal) {
    Map<String,Object> result = new LinkedHashMap<>();
    result.put("authenticated",true);
    result.put("workspace","INSTITUTION");
    result.put("institutionId",principal.institutionId());
    result.put("institutionCode",principal.tenantCode());
    result.put("institutionName",principal.institutionName());
    result.put("adminUsername",principal.username());
    result.put("adminDisplayName",principal.displayName());
    result.put("mustChangePassword",principal.mustChangePassword());
    result.put("capabilities",List.of("INSTITUTION_REPORTS_VIEW"));
    return result;
  }

  private Map<String,Object> brandingFor(long institutionId) {
    List<Map<String,Object>> rows = jdbc.queryForList("""
        select display_name,logo_asset_key,app_icon_asset_key,store_listing_url,theme_tokens::text as theme_tokens,version
        from institution_branding where institution_id=?
        """, institutionId);
    if (rows.isEmpty()) return Map.of();
    Map<String,Object> row = rows.getFirst();
    Map<String,Object> result = new LinkedHashMap<>();
    result.put("displayName",row.get("display_name"));
    result.put("logoAssetKey",row.get("logo_asset_key"));
    result.put("appIconAssetKey",row.get("app_icon_asset_key"));
    result.put("storeListingUrl",row.get("store_listing_url"));
    result.put("version",row.get("version"));
    result.put("themeTokens",parseJsonObject(row.get("theme_tokens")));
    return result;
  }

  private Object parseJsonObject(Object raw) {
    if (raw==null || String.valueOf(raw).isBlank()) return Map.of();
    try { return objectMapper.readValue(String.valueOf(raw),Map.class); }
    catch (JsonProcessingException ex) { throw new IllegalStateException("Stored institution branding tokens are invalid.",ex); }
  }

  private void auditReport(InstitutionAuthService.InstitutionPrincipal principal, String report) {
    auditTenant(principal, "REPORT_VIEWED", report, null, Map.of("report",report));
    jdbc.update("""
        insert into institution_membership_event(institution_id,event_type,actor_institution_admin_id,details)
        values (?,'REPORT_VIEWED',?,jsonb_build_object('report',?))
        """, principal.institutionId(), principal.adminId(), report);
  }

  private void auditTenant(
      InstitutionAuthService.InstitutionPrincipal principal,String action,String targetType,String targetId,Map<String,Object> details) {
    try {
      String json = objectMapper.writeValueAsString(details == null ? Map.of() : details);
      jdbc.update("""
          insert into institution_audit_log(institution_id,actor_institution_admin_id,action,target_type,target_id,details)
          values (?,?,?,?,?,?::jsonb)
          """, principal.institutionId(), principal.adminId(), action, targetType, targetId, json);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("Unable to record institution audit event.",ex);
    }
  }

  private String value(Map<String,Object> body,String key) {
    Object raw = body == null ? null : body.get(key);
    return raw == null ? "" : String.valueOf(raw).trim();
  }
}
