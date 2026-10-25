package com.quantaedge.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/** HRMS-only cross-tenant control plane. Every mutating method requires QE_PLATFORM_OWNER. */
@RestController
@RequestMapping("/api/v1/hrms/platform/institutions")
public class PlatformInstitutionController {
  private static final ZoneId BILLING_ZONE = ZoneId.of("Asia/Kolkata");

  private final JdbcTemplate jdbc;
  private final AuthorizationService authorization;
  private final InstitutionAuthService institutionAuth;
  private final ObjectMapper objectMapper;
  @org.springframework.beans.factory.annotation.Value("${app.auth.google-client-id:}") private String googleClientId;
  @org.springframework.beans.factory.annotation.Value("${app.razorpay.key-id:}") private String razorpayKeyId;
  @org.springframework.beans.factory.annotation.Value("${app.razorpay.key-secret:}") private String razorpayKeySecret;
  @org.springframework.beans.factory.annotation.Value("${app.razorpay.webhook-secret:}") private String razorpayWebhookSecret;
  @org.springframework.beans.factory.annotation.Value("${app.google-meet.client-id:}") private String googleMeetClientId;
  @org.springframework.beans.factory.annotation.Value("${app.google-meet.client-secret:}") private String googleMeetClientSecret;

  public PlatformInstitutionController(
      JdbcTemplate jdbc,
      AuthorizationService authorization,
      InstitutionAuthService institutionAuth,
      ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.authorization = authorization;
    this.institutionAuth = institutionAuth;
    this.objectMapper = objectMapper;
  }

  /**
   * Non-secret integration readiness for HRMS/platform operators. This endpoint reports only
   * whether centrally-managed configuration exists; provider secrets and client secrets are
   * never returned. Tenant administrators are not expected to supply any of these credentials.
   */
  @GetMapping("/integration-readiness")
  public Map<String,Object> integrationReadiness(
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    AuthContext owner=requirePlatformOwner(context);
    List<Map<String,Object>> checks=new ArrayList<>();
    addReadinessCheck(checks,"GOOGLE_OAUTH",googleClientId!=null&&!googleClientId.isBlank(),
        true,"HRMS configures the shared verified Google identity client and registers enabled tenant origins centrally.");
    addReadinessCheck(checks,"RAZORPAY",razorpayKeyId!=null&&!razorpayKeyId.isBlank()
        &&razorpayKeySecret!=null&&!razorpayKeySecret.isBlank(),
        true,"HRMS configures the platform payment credentials; each order remains institution-scoped.");
    addReadinessCheck(checks,"RAZORPAY_COURSE_WEBHOOK",razorpayWebhookSecret!=null&&!razorpayWebhookSecret.isBlank(),
        true,"Configure the Razorpay course-payment webhook at the approved API origin and store its signing secret in the deployment secret store.");
    String otpUrl=System.getenv("APP_OTP_PROVIDER_URL");
    String otpToken=System.getenv("APP_OTP_PROVIDER_TOKEN");
    addReadinessCheck(checks,"OTP_DELIVERY",otpUrl!=null&&!otpUrl.isBlank()
        &&otpToken!=null&&!otpToken.isBlank(),true,
        "HRMS configures the shared OTP delivery provider; tenant admins do not manage delivery credentials.");
    String cors=System.getenv("APP_CORS_ALLOWED_ORIGINS");
    String learningOrigins=System.getenv("APP_AUTH_LEARNING_ORIGINS");
    addReadinessCheck(checks,"TENANT_WEB_ORIGINS",cors!=null&&!cors.isBlank()
        &&learningOrigins!=null&&!learningOrigins.isBlank(),true,
        "HRMS registers each active institution's learner origin with the API and OAuth provider before activation.");
    addReadinessCheck(checks,"GOOGLE_MEET",googleMeetClientId!=null&&!googleMeetClientId.isBlank()
        &&googleMeetClientSecret!=null&&!googleMeetClientSecret.isBlank(),
        false,"Optional until the platform enables Meet-based sessions; credentials remain HRMS-managed.");
    boolean requiredReady=checks.stream().filter(c->Boolean.TRUE.equals(c.get("required")))
        .allMatch(c->Boolean.TRUE.equals(c.get("configured")));
    Map<String,Object> result=new LinkedHashMap<>();
    result.put("owner","HRMS");
    result.put("ready",requiredReady);
    result.put("checks",checks);
    result.put("secretValuesReturned",false);
    result.put("asOf",Instant.now().toString());
    audit(owner,null,"INTEGRATION_READINESS_VIEWED","PLATFORM_INTEGRATIONS",null,
        Map.of("ready",requiredReady));
    return result;
  }

  private void addReadinessCheck(List<Map<String,Object>> checks,String integration,
      boolean configured,boolean required,String operatorAction) {
    Map<String,Object> item=new LinkedHashMap<>();
    item.put("integration",integration);
    item.put("configured",configured);
    item.put("required",required);
    item.put("owner","HRMS");
    item.put("tenantCredentialsRequired",false);
    item.put("operatorAction",configured?"Configuration values are present. End-to-end provider verification is still required.":operatorAction);
    checks.add(item);
  }

  @GetMapping("/theme-presets")
  public List<Map<String,Object>> themePresets(
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    AuthContext owner = requirePlatformOwner(context);
    audit(owner, null, "THEME_PRESETS_VIEWED", "THEME_PRESETS", null, Map.of());
    return InstitutionThemeTokens.presets();
  }

  @PostMapping("/theme-preview")
  public Map<String,Object> previewTheme(
      @RequestBody(required=false) Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    requirePlatformOwner(context);
    Object raw = body == null ? null : body.get("themeTokens");
    return Map.of("themeTokens", InstitutionThemeTokens.normalize(raw));
  }

  @GetMapping("/overview")
  public Map<String,Object> overview(@RequestAttribute(value="authContext",required=false) AuthContext context) {
    AuthContext owner = requirePlatformOwner(context);
    List<Map<String,Object>> totals = jdbc.queryForList("""
        select count(*) as institutions_total,
          count(*) filter (where status='ACTIVE') as institutions_active,
          count(*) filter (where status='SUSPENDED') as institutions_suspended
        from institution_tenant
        """);
    List<Map<String,Object>> billing = jdbc.queryForList("""
        select
          (select count(distinct be.membership_id)
            from institution_billable_event be
            join institution_billing_contract c on c.id=be.contract_id
            where be.event_type='CHARGE'
              and be.billing_period_start=case when c.billing_cadence='ANNUAL'
                then date_trunc('year',current_date)::date else date_trunc('month',current_date)::date end
          ) as current_period_billable_enrollments,
          coalesce((
            select jsonb_object_agg(t.currency,t.total_paise)::text
            from (
              select be.currency,sum(be.amount_paise)::bigint as total_paise
              from institution_billable_event be
              join institution_billing_contract c on c.id=be.contract_id
              where be.event_type='CHARGE'
                and be.billing_period_start=case when c.billing_cadence='ANNUAL'
                  then date_trunc('year',current_date)::date else date_trunc('month',current_date)::date end
              group by be.currency
            ) t
          ),'{}') as amount_by_currency
        """);
    Map<String,Object> result = new LinkedHashMap<>();
    result.put("institutions", totals.isEmpty()?Map.of():totals.getFirst());
    result.put("billing", billing.isEmpty()?Map.of():billing.getFirst());
    result.put("asOf", Instant.now().toString());
    audit(owner, null, "PLATFORM_OVERVIEW_VIEWED", "OVERVIEW", null, Map.of());
    return result;
  }

  /** Shared catalogue exposes only releases that passed content quality and origin approval. */
  @GetMapping("/catalog/courses")
  public List<Map<String,Object>> courseCatalog(@RequestAttribute(value="authContext",required=false) AuthContext context) {
    AuthContext owner=requirePlatformOwner(context);
    audit(owner,null,"COURSE_CATALOG_VIEWED","COURSE_RELEASE_CATALOG",null,Map.of());
    return jdbc.queryForList("""
        select r.id as release_id,p.id as course_id,p.public_id,p.code,p.display_name,p.curriculum_version,
          r.release_version,r.release_state,r.source_institution_id,
          origin.tenant_code as source_institution_code,
          coalesce(branding.display_name,origin.display_name) as source_institution_name,
          c.code as class_code,r.shareable_at as released_at
        from institution_course_release r
        join curriculum_program p on p.id=r.program_id and p.status='ACTIVE' and p.code<>'GENERAL'
        join institution_tenant origin on origin.id=r.source_institution_id and origin.status='ACTIVE'
        left join institution_branding branding on branding.institution_id=origin.id
        left join curriculum_class c on c.id=p.class_id and c.active=true
        where r.release_state='SHAREABLE'
        order by p.display_name,p.code,r.release_version desc
        """);
  }

  @GetMapping
  public List<Map<String,Object>> listInstitutions(@RequestAttribute(value="authContext",required=false) AuthContext context) {
    AuthContext owner = requirePlatformOwner(context);
    audit(owner,null,"INSTITUTIONS_LIST_VIEWED","INSTITUTION_LIST",null,Map.of());
    return institutionSummaries();
  }

  @GetMapping("/{institutionId}")
  public Map<String,Object> getInstitution(
      @PathVariable long institutionId,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    AuthContext owner = requirePlatformOwner(context);
    List<Map<String,Object>> rows = jdbc.queryForList("""
        select t.id,t.public_id,t.tenant_code,t.display_name,t.legal_name,t.status,t.status_reason,
          t.created_at,t.updated_at,t.activated_at,t.suspended_at,t.closed_at,
          b.display_name as brand_display_name,b.tagline,b.logo_asset_key,b.app_icon_asset_key,b.store_listing_url,
          b.theme_tokens::text as theme_tokens,b.version as branding_version,
          c.id as contract_id,c.currency,c.unit_price_paise,c.billing_cadence,c.effective_from,c.effective_until
        from institution_tenant t
        left join institution_branding b on b.institution_id=t.id
        left join institution_billing_contract c on c.institution_id=t.id and c.active=true
        where t.id=?
        """,institutionId);
    if(rows.isEmpty()) throw new IllegalArgumentException("Institution not found.");
    Map<String,Object> result = new LinkedHashMap<>(rows.getFirst());
    result.put("themeTokens", InstitutionThemeTokens.normalize(parseJsonObject(result.remove("theme_tokens"))));
    String publicCode = String.valueOf(result.get("tenant_code"));
    long brandVersion = result.get("branding_version") instanceof Number n ? n.longValue() : 1L;
    result.put("logo_url", publicAssetUrl(publicCode, "logo", brandVersion, result.get("logo_asset_key")));
    result.put("app_icon_url", publicAssetUrl(publicCode, "app-icon", brandVersion, result.get("app_icon_asset_key")));
    result.put("admins", jdbc.queryForList("""
        select id,username,display_name,active,must_change_password,last_login_at,created_at
        from institution_admin_account where institution_id=? order by created_at,id
        """,institutionId));
    result.put("courses", institutionCourses(institutionId));
    audit(owner,institutionId,"INSTITUTION_VIEWED","INSTITUTION",String.valueOf(institutionId),Map.of());
    return result;
  }

  /**
   * Education Admin technical accounts are separate from HR employees and institution-admin
   * logins. HRMS explicitly binds each technical staff account to one institution here.
   */
  @GetMapping("/{institutionId}/education-staff-bindings")
  public Map<String,Object> educationStaffBindings(
      @PathVariable long institutionId,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    AuthContext owner=requirePlatformOwner(context);
    assertInstitutionExists(institutionId);
    List<Map<String,Object>> staff=jdbc.queryForList("""
        select s.id as staff_id,s.display_name,s.role,s.active,
          b.id as binding_id,b.status as binding_status,b.assigned_at,b.assigned_by_staff_id,
          b.institution_id as bound_institution_id,t.tenant_code as bound_tenant_code,
          t.display_name as bound_institution_name,
          case when b.id is null then 'UNASSIGNED'
               when b.institution_id=? then 'ASSIGNED_HERE'
               else 'ASSIGNED_ELSEWHERE' end as institution_binding_state
        from staff_account s
        left join institution_staff_binding b on b.staff_id=s.id and b.status='ACTIVE'
        left join institution_tenant t on t.id=b.institution_id
        where s.active=true
        order by lower(s.display_name),s.id
        """,institutionId);
    audit(owner,institutionId,"EDUCATION_STAFF_BINDINGS_VIEWED",
        "INSTITUTION_STAFF_BINDING",String.valueOf(institutionId),
        Map.of("activeStaffCount",staff.size()));
    return Map.of("institutionId",institutionId,"staff",staff,
        "assignedHereCount",staff.stream().filter(row->
            "ASSIGNED_HERE".equals(String.valueOf(row.get("institution_binding_state")))).count(),
        "unassignedCount",staff.stream().filter(row->
            "UNASSIGNED".equals(String.valueOf(row.get("institution_binding_state")))).count());
  }

  @PostMapping("/{institutionId}/education-staff-bindings")
  @Transactional
  public Map<String,Object> assignEducationStaff(
      @PathVariable long institutionId,@RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    AuthContext owner=requirePlatformOwner(context);
    assertInstitutionExists(institutionId);
    long staffId=positiveId(body==null?null:body.get("staffId"),"staffId");
    String reason=value(body,"reason");
    if(reason.length()>500) throw new IllegalArgumentException("Assignment reason must be 500 characters or fewer.");
    Boolean institutionActive=jdbc.queryForObject(
        "select exists(select 1 from institution_tenant where id=? and status='ACTIVE')",
        Boolean.class,institutionId);
    if(!Boolean.TRUE.equals(institutionActive)) throw new ResponseStatusException(
        HttpStatus.CONFLICT,"Only an active institution can receive Education Admin staff.");
    Boolean staffActive=jdbc.queryForObject(
        "select exists(select 1 from staff_account where id=? and active=true)",
        Boolean.class,staffId);
    if(!Boolean.TRUE.equals(staffActive)) throw new ResponseStatusException(
        HttpStatus.CONFLICT,"Choose an active Education Admin staff account.");
    List<Map<String,Object>> activeBindings=jdbc.queryForList("""
        select id,institution_id from institution_staff_binding
        where staff_id=? and status='ACTIVE'
        for update
        """,staffId);
    if(!activeBindings.isEmpty()) {
      long existingInstitution=((Number)activeBindings.getFirst().get("institution_id")).longValue();
      if(existingInstitution==institutionId) {
        return Map.of("assigned",true,"unchanged",true,"staffId",staffId,
            "institutionId",institutionId,"status","ACTIVE");
      }
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "This staff account is already bound to another institution. Revoke the existing HRMS binding before reassignment.");
    }
    Long bindingId=jdbc.queryForObject("""
        insert into institution_staff_binding(
          staff_id,institution_id,status,assigned_by_staff_id,assigned_at,reason,created_at
        ) values(?,?,'ACTIVE',?,now(),?,now()) returning id
        """,Long.class,staffId,institutionId,owner.staffId(),emptyToNull(reason));
    audit(owner,institutionId,"EDUCATION_ADMIN_STAFF_ASSIGNED",
        "INSTITUTION_STAFF_BINDING",String.valueOf(bindingId),
        Map.of("staffId",staffId,"bindingId",bindingId,"reason",reason));
    return Map.of("assigned",true,"unchanged",false,"bindingId",bindingId,
        "staffId",staffId,"institutionId",institutionId,"status","ACTIVE");
  }

  @DeleteMapping("/{institutionId}/education-staff-bindings/{staffId}")
  @Transactional
  public Map<String,Object> revokeEducationStaffBinding(
      @PathVariable long institutionId,@PathVariable long staffId,
      @RequestBody(required=false) Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    AuthContext owner=requirePlatformOwner(context);
    assertInstitutionExists(institutionId);
    String reason=value(body,"reason");
    requireText(reason,3,500,"Revocation reason");
    List<Map<String,Object>> rows=jdbc.queryForList("""
        select id from institution_staff_binding
        where staff_id=? and institution_id=? and status='ACTIVE'
        for update
        """,staffId,institutionId);
    if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,
        "No active binding exists for this staff account in the selected institution.");
    long bindingId=((Number)rows.getFirst().get("id")).longValue();
    int changed=jdbc.update("""
        update institution_staff_binding
        set status='REVOKED',revoked_by_staff_id=?,revoked_at=now(),reason=?
        where id=? and status='ACTIVE'
        """,owner.staffId(),reason,bindingId);
    if(changed!=1) throw new ResponseStatusException(HttpStatus.CONFLICT,
        "The staff binding changed before revocation completed. Refresh and retry.");
    audit(owner,institutionId,"EDUCATION_ADMIN_STAFF_REVOKED",
        "INSTITUTION_STAFF_BINDING",String.valueOf(bindingId),
        Map.of("staffId",staffId,"bindingId",bindingId,"reason",reason));
    return Map.of("revoked",true,"bindingId",bindingId,"staffId",staffId,
        "institutionId",institutionId,"status","REVOKED");
  }

  @PostMapping
  @Transactional
  public Map<String,Object> createInstitution(
      @RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    AuthContext owner = requirePlatformOwner(context);
    String tenantCode = value(body,"tenantCode").toUpperCase(Locale.ROOT);
    String displayName = value(body,"displayName");
    String legalName = value(body,"legalName");
    String username = value(body,"adminUsername").toLowerCase(Locale.ROOT);
    String adminDisplayName = value(body,"adminDisplayName");
    String initialPassword = value(body,"initialPassword");
    if(!tenantCode.matches("^[A-Z0-9][A-Z0-9_-]{2,31}$")) {
      throw new IllegalArgumentException("Institution code must be 3–32 characters using letters, numbers, hyphens or underscores.");
    }
    requireText(displayName,2,180,"Institution display name");
    if(legalName.length()>220) throw new IllegalArgumentException("Legal name must be no more than 220 characters.");
    requireText(adminDisplayName,2,120,"Institution administrator name");
    if(!username.matches("^[a-z0-9][a-z0-9._-]{2,63}$")) {
      throw new IllegalArgumentException("Admin username must be 3–64 characters using letters, numbers, dots, underscores or hyphens.");
    }
    institutionAuth.encodePassword(initialPassword);

    try {
      Long institutionId = jdbc.queryForObject("""
          insert into institution_tenant(public_id,tenant_code,display_name,legal_name,status,created_by_staff_id,updated_by_staff_id,activated_at)
          values (?,?,?,?,'ACTIVE',?,?,now()) returning id
          """,Long.class,UUID.randomUUID(),tenantCode,displayName,emptyToNull(legalName),owner.staffId(),owner.staffId());
      long id = java.util.Objects.requireNonNull(institutionId);
      jdbc.update("""
          insert into institution_branding(institution_id,display_name,theme_tokens,updated_by_staff_id)
          values (?,?,'{}'::jsonb,?)
          """,id,displayName,owner.staffId());
      jdbc.update("""
          insert into institution_admin_account(institution_id,username,display_name,password_hash,active,must_change_password,created_by_staff_id)
          values (?,?,?,?,true,true,?)
          """,id,username,adminDisplayName,institutionAuth.encodePassword(initialPassword),owner.staffId());
      audit(owner,id,"INSTITUTION_CREATED","INSTITUTION",String.valueOf(id),
          Map.of("tenantCode",tenantCode,"adminUsername",username));
      return Map.of("institutionId",id,"tenantCode",tenantCode,"displayName",displayName,
          "status","ACTIVE","adminUsername",username,"initialPasswordChangeRequired",true,
          "nextStep","Configure a billing contract, then issue the learner join code.");
    } catch(DuplicateKeyException ex) {
      throw new IllegalArgumentException("Institution code or administrator username already exists.");
    }
  }

  @PutMapping("/{institutionId}/profile")
  @Transactional
  public Map<String,Object> updateInstitutionProfile(
      @PathVariable long institutionId,
      @RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    AuthContext owner = requirePlatformOwner(context);
    String displayName = value(body,"displayName");
    String legalName = value(body,"legalName");
    requireText(displayName,2,180,"Institution display name");
    if(legalName.length()>220 || legalName.chars().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException("Institution legal name must be no more than 220 characters and cannot contain control characters.");
    }
    List<Map<String,Object>> rows = jdbc.queryForList(
        "select display_name,legal_name from institution_tenant where id=? for update",institutionId);
    if(rows.isEmpty()) throw new IllegalArgumentException("Institution not found.");
    Map<String,Object> current = rows.getFirst();
    String previousDisplayName = String.valueOf(current.get("display_name"));
    String previousLegalName = nullableString(current.get("legal_name"));
    jdbc.update("""
        update institution_tenant set display_name=?,legal_name=?,updated_by_staff_id=?,updated_at=now()
        where id=?
        """,displayName,emptyToNull(legalName),owner.staffId(),institutionId);
    // If the white-label name still follows the institution name, keep it in sync.
    // A deliberately customised brand display name is preserved.
    int renamedDefaultBrand = jdbc.update("""
        update institution_branding set display_name=?,version=version+1,updated_by_staff_id=?,updated_at=now()
        where institution_id=? and display_name=?
        """,displayName,owner.staffId(),institutionId,previousDisplayName);
    jdbc.update("""
        insert into institution_membership_event(institution_id,event_type,actor_platform_staff_id,details)
        values (?,'INSTITUTION_PROFILE_CHANGED',?,jsonb_build_object(
          'displayNameChanged',?,'legalNameChanged',?))
        """,institutionId,owner.staffId(),!previousDisplayName.equals(displayName),
        !java.util.Objects.equals(previousLegalName,emptyToNull(legalName)));
    Map<String,Object> details = new LinkedHashMap<>();
    details.put("previousDisplayName",previousDisplayName);
    details.put("displayName",displayName);
    details.put("previousLegalName",previousLegalName==null?"":previousLegalName);
    details.put("legalName",legalName);
    details.put("defaultBrandNameSynchronized",renamedDefaultBrand>0);
    audit(owner,institutionId,"INSTITUTION_PROFILE_CHANGED","INSTITUTION",String.valueOf(institutionId),details);
    return Map.of("institutionId",institutionId,"displayName",displayName,
        "legalName",legalName,"defaultBrandNameSynchronized",renamedDefaultBrand>0);
  }

  @PutMapping("/{institutionId}/status")
  @Transactional
  public Map<String,Object> updateStatus(
      @PathVariable long institutionId,
      @RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    AuthContext owner = requirePlatformOwner(context);
    String status = value(body,"status").toUpperCase(Locale.ROOT);
    String reason = value(body,"reason");
    if(!Set.of("ACTIVE","SUSPENDED","CLOSED").contains(status)) {
      throw new IllegalArgumentException("Status must be ACTIVE, SUSPENDED or CLOSED.");
    }
    List<String> currentRows = jdbc.queryForList(
        "select status from institution_tenant where id=? for update",String.class,institutionId);
    if(currentRows.isEmpty()) throw new IllegalArgumentException("Institution not found.");
    String previous = currentRows.getFirst();
    if("CLOSED".equals(previous) && !"CLOSED".equals(status)) {
      throw new IllegalStateException("A closed institution cannot be reactivated; create a new institution record.");
    }
    if(reason.length()>500) throw new IllegalArgumentException("Status reason must be no more than 500 characters.");
    jdbc.update("""
        update institution_tenant set status=?,status_reason=?,updated_by_staff_id=?,updated_at=now(),
          activated_at=case when ?='ACTIVE' then coalesce(activated_at,now()) else activated_at end,
          suspended_at=case when ?='SUSPENDED' then now() else suspended_at end,
          closed_at=case when ?='CLOSED' then now() else closed_at end
        where id=?
        """,status,emptyToNull(reason),owner.staffId(),status,status,status,institutionId);
    if(!"ACTIVE".equals(status)) {
      jdbc.update("""
          update institution_admin_session set revoked_at=now()
          where institution_id=? and revoked_at is null
          """,institutionId);
    }
    jdbc.update("""
        insert into institution_membership_event(institution_id,event_type,actor_platform_staff_id,details)
        values (?,'TENANT_STATUS_CHANGED',?,jsonb_build_object('from',?,'to',?,'reason',?))
        """,institutionId,owner.staffId(),previous,status,reason);
    audit(owner,institutionId,"INSTITUTION_STATUS_CHANGED","INSTITUTION",String.valueOf(institutionId),
        Map.of("from",previous,"to",status,"reason",reason));
    return Map.of("institutionId",institutionId,"previousStatus",previous,"status",status);
  }

  @PostMapping("/{institutionId}/admins")
  @Transactional
  public Map<String,Object> provisionAdmin(
      @PathVariable long institutionId,
      @RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    AuthContext owner = requirePlatformOwner(context);
    assertInstitutionExists(institutionId);
    String username = value(body,"username").toLowerCase(Locale.ROOT);
    String displayName = value(body,"displayName");
    String initialPassword = value(body,"initialPassword");
    if(!username.matches("^[a-z0-9][a-z0-9._-]{2,63}$")) {
      throw new IllegalArgumentException("Username must be 3–64 characters using letters, numbers, dots, underscores or hyphens.");
    }
    requireText(displayName,2,120,"Administrator name");
    String passwordHash = institutionAuth.encodePassword(initialPassword);
    try {
      Long id = jdbc.queryForObject("""
          insert into institution_admin_account(institution_id,username,display_name,password_hash,active,must_change_password,created_by_staff_id)
          values (?,?,?,?,true,true,?) returning id
          """,Long.class,institutionId,username,displayName,passwordHash,owner.staffId());
      audit(owner,institutionId,"INSTITUTION_ADMIN_CREATED","INSTITUTION_ADMIN",String.valueOf(id),
          Map.of("username",username));
      return Map.of("adminId",java.util.Objects.requireNonNull(id),"username",username,"displayName",displayName,
          "active",true,"initialPasswordChangeRequired",true);
    } catch(DuplicateKeyException ex) {
      throw new IllegalArgumentException("That institution administrator username is already used.");
    }
  }

  @PutMapping("/{institutionId}/admins/{adminId}/status")
  @Transactional
  public Map<String,Object> updateAdminStatus(
      @PathVariable long institutionId,
      @PathVariable long adminId,
      @RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    AuthContext owner = requirePlatformOwner(context);
    Object raw = body==null?null:body.get("active");
    if(!(raw instanceof Boolean active)) throw new IllegalArgumentException("The active field must be true or false.");
    int changed = jdbc.update("""
        update institution_admin_account set active=?,updated_at=now()
        where id=? and institution_id=?
        """,active,adminId,institutionId);
    if(changed!=1) throw new IllegalArgumentException("Institution administrator not found.");
    if(!active) {
      jdbc.update("update institution_admin_session set revoked_at=now() where institution_admin_id=? and revoked_at is null",adminId);
    }
    audit(owner,institutionId,active?"INSTITUTION_ADMIN_ACTIVATED":"INSTITUTION_ADMIN_SUSPENDED",
        "INSTITUTION_ADMIN",String.valueOf(adminId),Map.of("active",active));
    return Map.of("adminId",adminId,"active",active);
  }

  /**
   * Legacy course-wide entitlement writes bypass the release and local-offer lifecycle.
   * Kept as a stable route for old clients, but intentionally no longer grants access.
   */
  @PutMapping("/{institutionId}/courses/{programId}")
  public Map<String,Object> updateCourseEntitlement(
      @PathVariable long institutionId,
      @PathVariable long programId,
      @RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    AuthContext owner=requirePlatformOwner(context);
    assertInstitutionExists(institutionId);
    throw new ResponseStatusException(HttpStatus.CONFLICT,
        "Course assignment now requires an origin-approved release. Use /{institutionId}/course-releases/{releaseId}/assignment.");
  }

  /** HRMS assigns an origin-approved release to another institution; this never publishes an offer. */
  @PutMapping("/{institutionId}/course-releases/{releaseId}/assignment")
  @Transactional
  public Map<String,Object> updateCourseReleaseAssignment(
      @PathVariable long institutionId,
      @PathVariable long releaseId,
      @RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    AuthContext owner=requirePlatformOwner(context);
    assertInstitutionExists(institutionId);
    Object raw=body==null?null:body.get("enabled");
    if(!(raw instanceof Boolean enabled)) throw new IllegalArgumentException("The enabled field must be true or false.");
    List<Map<String,Object>> releases=jdbc.queryForList("""
        select r.id,r.program_id,r.source_institution_id,r.release_version,r.release_state,
          r.content_fingerprint,r.updated_at,p.code,p.display_name
        from institution_course_release r
        join curriculum_program p on p.id=r.program_id and p.status='ACTIVE' and p.code<>'GENERAL'
        where r.id=?
        """,releaseId);
    if(releases.isEmpty()) throw new IllegalArgumentException("The course release is unavailable.");
    Map<String,Object> release=releases.getFirst();
    if(!"SHAREABLE".equals(String.valueOf(release.get("release_state"))))
      throw new IllegalArgumentException("Only an origin-approved shareable course release can be assigned.");
    long sourceInstitutionId=((Number)release.get("source_institution_id")).longValue();
    if(sourceInstitutionId==institutionId)
      throw new IllegalArgumentException("The origin institution already owns this course release; cross-institution assignment is for destination institutions.");
    if(enabled) {
      long courseId=((Number)release.get("program_id")).longValue();
      String savedFingerprint=String.valueOf(release.get("content_fingerprint")).trim();
      if(!Boolean.TRUE.equals(jdbc.queryForObject(
          "select course_release_fingerprint_current(?,?,?)",Boolean.class,
          courseId,savedFingerprint,release.get("updated_at"))))
        throw new ResponseStatusException(HttpStatus.CONFLICT,
            "Course content changed after quality review. The source institution must submit a fresh release before HRMS can assign it.");
      if("ASSESSMENT_ONLY".equals(courseDeliveryMode(courseId))) {
        if(!assessmentOnlyCourseReady(courseId))
          throw new ResponseStatusException(HttpStatus.CONFLICT,
              "The assessment-only release no longer satisfies the generic published test-pack readiness checks.");
      } else {
        Map<String,Object> contentReadiness=jdbc.queryForMap("""
            select count(distinct s.id) as subject_count,
              count(distinct case when l.id is not null then s.id end) as subjects_with_published_lessons,
              count(distinct ch.id) as published_chapters,count(distinct l.id) as published_lessons
            from curriculum_program_subject ps
            join curriculum_subject s on s.id=ps.subject_id and s.class_id=ps.class_id and s.active=true
              and (s.program_id=ps.program_id or s.program_id is null)
            left join curriculum_chapter ch on ch.subject_id=s.id and ch.active=true and ch.content_status='PUBLISHED'
            left join lesson l on l.chapter_id=ch.id and l.active=true and l.status='PUBLISHED'
            where ps.program_id=?
            """,courseId);
        long subjects=((Number)contentReadiness.get("subject_count")).longValue();
        if(subjects==0||((Number)contentReadiness.get("subjects_with_published_lessons")).longValue()!=subjects
            ||((Number)contentReadiness.get("published_chapters")).longValue()==0
            ||((Number)contentReadiness.get("published_lessons")).longValue()==0)
          throw new ResponseStatusException(HttpStatus.CONFLICT,
              "Course content is not ready for assignment. Publish reviewed lessons for every mapped active subject first.");
      }
    }
    Long assignmentId=null;
    if(enabled) {
      assignmentId=jdbc.queryForObject("""
          insert into institution_course_assignment(
            institution_id,course_release_id,status,assignment_source,assigned_by_staff_id,assigned_at,
            revoked_by_staff_id,revoked_at,created_at,updated_at
          ) values(?,?,'ASSIGNED','HRMS',?,now(),null,null,now(),now())
          on conflict(institution_id,course_release_id) do update set status='ASSIGNED',
            assignment_source='HRMS',assigned_by_staff_id=excluded.assigned_by_staff_id,
            assigned_at=now(),revoked_by_staff_id=null,revoked_at=null,updated_at=now()
          returning id
          """,Long.class,institutionId,releaseId,owner.staffId());
    } else {
      List<Map<String,Object>> prior=jdbc.queryForList("""
          select id from institution_course_assignment
          where institution_id=? and course_release_id=? and assignment_source='HRMS' for update
          """,institutionId,releaseId);
      if(!prior.isEmpty()) assignmentId=((Number)prior.getFirst().get("id")).longValue();
      jdbc.update("""
          update institution_course_assignment
          set status='REVOKED',revoked_by_staff_id=?,revoked_at=now(),updated_at=now()
          where institution_id=? and course_release_id=? and assignment_source='HRMS' and status='ASSIGNED'
          """,owner.staffId(),institutionId,releaseId);
      jdbc.update("""
          update institution_marketplace_offer set status='ARCHIVED',updated_at=now()
          where institution_id=? and course_release_id=? and status<>'ARCHIVED'
          """,institutionId,releaseId);
    }
    jdbc.update("""
        insert into institution_course_lifecycle_event(
          institution_id,course_release_id,assignment_id,event_type,actor_platform_staff_id,details
        ) values(?,?,?,?,?,jsonb_build_object('enabled',?,'sourceInstitutionId',?,'paymentReceiver','HRMS_PLATFORM_ACCOUNT'))
        """,institutionId,releaseId,assignmentId,enabled?"COURSE_ASSIGNED":"COURSE_UNASSIGNED",
        owner.staffId(),enabled,sourceInstitutionId);
    audit(owner,institutionId,"COURSE_RELEASE_ASSIGNMENT_CHANGED","COURSE_RELEASE",
        String.valueOf(releaseId),Map.of("enabled",enabled,"assignmentId",assignmentId==null?0L:assignmentId));
    return Map.of("institutionId",institutionId,"releaseId",releaseId,"courseId",release.get("program_id"),
        "assignmentId",assignmentId==null?0L:assignmentId,"enabled",enabled,"offerPublished",false,
        "paymentReceiver","HRMS_PLATFORM_ACCOUNT");
  }


  @GetMapping("/course-releases/review-queue")
  public List<Map<String,Object>> courseReleaseReviewQueue(
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    AuthContext owner=requirePlatformOwner(context);
    audit(owner,null,"COURSE_RELEASE_REVIEW_QUEUE_VIEWED","COURSE_RELEASE",null,Map.of());
    return jdbc.queryForList("""
        select r.id as release_id,r.program_id as course_id,r.release_version,r.release_state,
          r.content_fingerprint,r.submitted_at,p.public_id,p.code as course_code,p.display_name,
          coalesce((select delivery_mode from curriculum_program_delivery_mode where program_id=p.id),'LEARNING_CONTENT') as delivery_model,
          (select count(*) from assessment da where da.course_id=p.id and da.assessment_type='PREVIOUS_YEAR_PAPER' and da.status='DRAFT') as draft_assessment_count,
          (select count(*) from assessment da join assessment_question dqa on dqa.assessment_id=da.id
            where da.course_id=p.id and da.assessment_type='PREVIOUS_YEAR_PAPER' and da.status='DRAFT') as draft_assessment_question_count,
          assessment_only_course_publish_ready(p.id) as assessment_only_ready,
          t.id as source_institution_id,t.tenant_code as source_institution_code,
          coalesce(b.display_name,t.display_name) as source_institution_name,
          count(distinct s.id) as active_subject_count,
          count(distinct case when l.id is not null then s.id end) as subjects_with_published_lessons,
          count(distinct ch.id) as published_chapters,count(distinct l.id) as published_lessons
        from institution_course_release r
        join curriculum_program p on p.id=r.program_id and p.status='ACTIVE'
        join institution_tenant t on t.id=r.source_institution_id and t.status='ACTIVE'
        left join institution_branding b on b.institution_id=t.id
        left join curriculum_program_subject ps on ps.program_id=p.id
        left join curriculum_subject s on s.id=ps.subject_id and s.class_id=ps.class_id and s.active=true
            and (s.program_id=ps.program_id or s.program_id is null)
        left join curriculum_chapter ch on ch.subject_id=s.id and ch.active=true and ch.content_status='PUBLISHED'
        left join lesson l on l.chapter_id=ch.id and l.active=true and l.status='PUBLISHED'
        where r.release_state='IN_QUALITY_REVIEW'
        group by r.id,r.program_id,r.release_version,r.release_state,r.content_fingerprint,r.submitted_at,
          p.public_id,p.code,p.display_name,t.id,t.tenant_code,b.display_name,t.display_name
        order by r.submitted_at,r.id
        """);
  }

  @PostMapping("/course-releases/{releaseId}/quality-review")
  @Transactional
  public Map<String,Object> reviewCourseRelease(
      @PathVariable long releaseId,@RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    AuthContext reviewer=requirePlatformOwner(context);
    Object raw=body==null?null:body.get("approved");
    if(!(raw instanceof Boolean approved)) throw new IllegalArgumentException("The approved field must be true or false.");
    String notes=body.get("notes")==null?"":String.valueOf(body.get("notes")).trim();
    if(notes.length()<3||notes.length()>1000) throw new IllegalArgumentException("Quality review notes must be 3–1,000 characters.");
    List<Map<String,Object>> releases=jdbc.queryForList("""
        select r.id,r.source_institution_id,r.program_id,r.release_state,r.content_fingerprint,r.updated_at,
          r.release_version,p.code,p.display_name
        from institution_course_release r join curriculum_program p on p.id=r.program_id
        where r.id=? for update of r
        """,releaseId);
    if(releases.isEmpty()) throw new IllegalArgumentException("The course release is unavailable.");
    Map<String,Object> release=releases.getFirst();
    if(!"IN_QUALITY_REVIEW".equals(String.valueOf(release.get("release_state"))))
      throw new ResponseStatusException(HttpStatus.CONFLICT,"Only a release awaiting content-quality review can be reviewed.");
    long programId=((Number)release.get("program_id")).longValue();
    String savedFingerprint=String.valueOf(release.get("content_fingerprint")).trim();
    if(!Boolean.TRUE.equals(jdbc.queryForObject(
        "select course_release_fingerprint_current(?,?,?)",Boolean.class,
        programId,savedFingerprint,release.get("updated_at"))))
      throw new ResponseStatusException(HttpStatus.CONFLICT,"Course content changed after submission. The origin institution must submit a fresh release.");
    if(approved) {
      String reviewedFingerprint=body.get("reviewedContentFingerprint")==null
          ?"":String.valueOf(body.get("reviewedContentFingerprint")).trim();
      if(!savedFingerprint.equals(reviewedFingerprint))
        throw new ResponseStatusException(HttpStatus.CONFLICT,
            "HRMS must inspect the exact submitted release content and confirm its fingerprint before quality approval.");
    }
    if(approved && "ASSESSMENT_ONLY".equals(courseDeliveryMode(programId))) {
      if(!assessmentOnlyCourseReady(programId))
        throw new ResponseStatusException(HttpStatus.CONFLICT,
            "Before approving this frozen release, publish every source-owned test pack and assessment after question/answer review. Reject this release to return it for correction, then resubmit the reviewed content.");
    } else if(approved) {
      Map<String,Object> readiness=jdbc.queryForMap("""
          select count(distinct s.id) as subject_count,
            count(distinct case when l.id is not null then s.id end) as subjects_with_published_lessons,
            count(distinct ch.id) as published_chapters,count(distinct l.id) as published_lessons
          from curriculum_program_subject ps
          join curriculum_subject s on s.id=ps.subject_id and s.class_id=ps.class_id and s.active=true
              and (s.program_id=ps.program_id or s.program_id is null)
          left join curriculum_chapter ch on ch.subject_id=s.id and ch.active=true and ch.content_status='PUBLISHED'
          left join lesson l on l.chapter_id=ch.id and l.active=true and l.status='PUBLISHED'
          where ps.program_id=?
          """,programId);
      long subjects=number(readiness.get("subject_count"));
      if(subjects==0||number(readiness.get("subjects_with_published_lessons"))!=subjects
          ||number(readiness.get("published_chapters"))==0||number(readiness.get("published_lessons"))==0)
        throw new ResponseStatusException(HttpStatus.CONFLICT,"Every mapped active subject must retain published content reviewed lessons.");
    }
    String next=approved?"QUALITY_APPROVED":"REJECTED";
    int changed=jdbc.update("""
        update institution_course_release set release_state=?,quality_reviewed_by_staff_id=?,
          quality_reviewed_at=now(),quality_review_notes=?,updated_at=now()
        where id=? and release_state='IN_QUALITY_REVIEW'
        """,next,reviewer.staffId(),notes,releaseId);
    if(changed!=1) throw new ResponseStatusException(HttpStatus.CONFLICT,"The release changed during quality review.");
    long sourceInstitutionId=((Number)release.get("source_institution_id")).longValue();
    jdbc.update("""
        insert into institution_course_lifecycle_event(
          institution_id,course_release_id,event_type,actor_platform_staff_id,details
        ) values(?,?,?,?,jsonb_build_object('approved',?,'notes',?,'courseId',?))
        """,sourceInstitutionId,releaseId,approved?"RELEASE_QUALITY_APPROVED":"RELEASE_QUALITY_REJECTED",
        reviewer.staffId(),approved,notes,programId);
    audit(reviewer,sourceInstitutionId,"COURSE_RELEASE_QUALITY_REVIEWED","COURSE_RELEASE",
        String.valueOf(releaseId),Map.of("approved",approved,"courseId",programId));
    return Map.of("updated",true,"releaseId",releaseId,"status",next,"qualityReviewNotes",notes);
  }

  private String courseReleaseFingerprint(long programId) {
    String value=jdbc.queryForObject("select course_content_fingerprint(?)",String.class,programId);
    return value==null?"d41d8cd98f00b204e9800998ecf8427e":value.trim();
  }

  private String courseDeliveryMode(long programId) {
    return jdbc.queryForObject("""
        select coalesce((select delivery_mode from curriculum_program_delivery_mode where program_id=?),'LEARNING_CONTENT')
        """,String.class,programId);
  }

  private boolean assessmentOnlyCourseReady(long programId) {
    Boolean ready=jdbc.queryForObject("select assessment_only_course_publish_ready(?)",Boolean.class,programId);
    return Boolean.TRUE.equals(ready);
  }

  @PutMapping("/{institutionId}/branding")
  @Transactional
  public Map<String,Object> updateBranding(
      @PathVariable long institutionId,
      @RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    AuthContext owner = requirePlatformOwner(context);
    assertInstitutionExists(institutionId);
    Map<String,Object> existing = jdbc.queryForMap("""
        select display_name,tagline,logo_asset_key,app_icon_asset_key,store_listing_url,theme_tokens::text as theme_tokens
        from institution_branding where institution_id=?
        """,institutionId);
    if (body != null && (body.containsKey("logoAssetKey") || body.containsKey("appIconAssetKey"))) {
      throw new IllegalArgumentException("Use the validated branding asset upload or remove endpoint to change logos and app icons.");
    }
    String displayName = body!=null && body.containsKey("displayName")
        ? value(body,"displayName") : String.valueOf(existing.get("display_name"));
    requireText(displayName,2,180,"Branded display name");
    String tagline = body!=null && body.containsKey("tagline")
        ? normalizeBrandTagline(value(body,"tagline"))
        : normalizeBrandTagline(existing.get("tagline")==null?"":String.valueOf(existing.get("tagline")));
    // Asset references are write-protected here. Only the validated upload/remove lifecycle
    // may change these keys, mark old binaries inactive, bump versions and audit the action.
    String logo = nullableString(existing.get("logo_asset_key"));
    String icon = nullableString(existing.get("app_icon_asset_key"));
    String storeListing = body!=null && body.containsKey("storeListingUrl")
        ? safeStoreListing(value(body,"storeListingUrl")) : nullableString(existing.get("store_listing_url"));
    String tokens = body!=null && body.containsKey("themeTokens")
        ? validateThemeTokens(body.get("themeTokens")) : String.valueOf(existing.get("theme_tokens"));
    jdbc.update("""
        update institution_branding set display_name=?,tagline=?,logo_asset_key=?,app_icon_asset_key=?,store_listing_url=?,
          theme_tokens=?::jsonb,version=version+1,updated_by_staff_id=?,updated_at=now()
        where institution_id=?
        """,displayName,tagline,logo,icon,storeListing,tokens,owner.staffId(),institutionId);
    jdbc.update("""
        insert into institution_membership_event(institution_id,event_type,actor_platform_staff_id,details)
        values (?,'BRANDING_CHANGED',?,jsonb_build_object('brandingVersionIncremented',true))
        """,institutionId,owner.staffId());
    audit(owner,institutionId,"BRANDING_CHANGED","BRANDING",String.valueOf(institutionId),
        Map.of("displayName",displayName,"hasLogo",logo!=null,"hasAppIcon",icon!=null));
    Map<String,Object> saved = new LinkedHashMap<>();
    saved.put("institutionId",institutionId);
    saved.put("displayName",displayName);
    saved.put("tagline",tagline);
    saved.put("logoAssetKey",logo==null?"":logo);
    saved.put("appIconAssetKey",icon==null?"":icon);
    saved.put("storeListingUrl",storeListing==null?"":storeListing);
    saved.put("themeTokens",InstitutionThemeTokens.normalize(parseJsonObject(tokens)));
    saved.put("brandingVersion",jdbc.queryForObject(
        "select version from institution_branding where institution_id=?",Long.class,institutionId));
    return saved;
  }

  /**
   * Atomically publish a prepared branding template to one HRMS-managed institution.
   * The browser creates platform renditions; this endpoint still revalidates both files,
   * checks platform-owner access, stores immutable asset history and bumps one shared version.
   */
  @PostMapping(value="/{institutionId}/branding/template-assets", consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
  @Transactional
  public Map<String,Object> applyBrandingTemplate(
      @PathVariable long institutionId,
      @RequestParam("logo") MultipartFile logoFile,
      @RequestParam("appIcon") MultipartFile appIconFile,
      @RequestParam("displayName") String displayNameInput,
      @RequestParam(value="tagline",required=false) String taglineInput,
      @RequestParam(value="storeListingUrl",required=false) String storeListingInput,
      @RequestParam(value="themeTokens",required=false) String themeTokensInput,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    AuthContext owner=requirePlatformOwner(context);
    assertInstitutionExists(institutionId);
    String displayName=displayNameInput==null?"":displayNameInput.trim();
    requireText(displayName,2,180,"Branded display name");
    String tagline=normalizeBrandTagline(taglineInput);

    InstitutionBrandingAssetValidator.ValidatedAsset logo=InstitutionBrandingAssetValidator.validate(logoFile);
    InstitutionBrandingAssetValidator.ValidatedAsset appIcon=InstitutionBrandingAssetValidator.validate(appIconFile);
    Map<String,Object> existing=jdbc.queryForMap("""
        select display_name,tagline,logo_asset_key,app_icon_asset_key,store_listing_url,theme_tokens::text as theme_tokens
        from institution_branding where institution_id=?
        """,institutionId);
    String storeListing=safeStoreListing(storeListingInput==null?"":storeListingInput);
    String tokens=String.valueOf(existing.get("theme_tokens"));
    if(themeTokensInput!=null&&!themeTokensInput.isBlank()) {
      try {
        tokens=validateThemeTokens(objectMapper.readValue(themeTokensInput,Map.class));
      } catch(JsonProcessingException ex) {
        throw new IllegalArgumentException("The branding theme settings are invalid JSON.");
      }
    }

    String logoId=UUID.randomUUID().toString();
    String iconId=UUID.randomUUID().toString();
    insertBrandingAsset(institutionId,"LOGO",logoId,logo,owner.staffId());
    insertBrandingAsset(institutionId,"APP_ICON",iconId,appIcon,owner.staffId());
    jdbc.update("update institution_branding_asset set active=false where institution_id=? and asset_type='LOGO' and asset_id<>? and active=true",
        institutionId,logoId);
    jdbc.update("update institution_branding_asset set active=false where institution_id=? and asset_type='APP_ICON' and asset_id<>? and active=true",
        institutionId,iconId);

    jdbc.update("""
        update institution_branding set display_name=?,tagline=?,logo_asset_key=?,app_icon_asset_key=?,
          store_listing_url=?,theme_tokens=?::jsonb,version=version+1,updated_by_staff_id=?,updated_at=now()
        where institution_id=?
        """,displayName,tagline,logoId,iconId,storeListing,tokens,owner.staffId(),institutionId);
    jdbc.update("""
        insert into institution_membership_event(institution_id,event_type,actor_platform_staff_id,details)
        values (?,'BRANDING_CHANGED',?,jsonb_build_object(
          'templatePackApplied',true,'logoSha256',?,'appIconSha256',?,'taglineChanged',true))
        """,institutionId,owner.staffId(),logo.sha256(),appIcon.sha256());
    Long version=jdbc.queryForObject("select version from institution_branding where institution_id=?",Long.class,institutionId);
    String tenantCode=jdbc.queryForObject("select tenant_code from institution_tenant where id=?",String.class,institutionId);
    audit(owner,institutionId,"BRANDING_TEMPLATE_APPLIED","BRANDING",String.valueOf(institutionId),
        Map.of("displayName",displayName,"tagline",tagline,"brandingVersion",version,
            "logoSha256",logo.sha256(),"appIconSha256",appIcon.sha256()));

    Map<String,Object> result=new LinkedHashMap<>();
    result.put("institutionId",institutionId);
    result.put("displayName",displayName);
    result.put("tagline",tagline);
    result.put("logoUrl",publicAssetUrl(tenantCode,"logo",version,logoId));
    result.put("appIconUrl",publicAssetUrl(tenantCode,"app-icon",version,iconId));
    result.put("brandingVersion",version);
    result.put("themeTokens",InstitutionThemeTokens.normalize(parseJsonObject(tokens)));
    return result;
  }

  private void insertBrandingAsset(long institutionId,String type,String assetId,
      InstitutionBrandingAssetValidator.ValidatedAsset asset,long staffId) {
    jdbc.update("""
        insert into institution_branding_asset(
          asset_id,institution_id,asset_type,content_type,file_name,content_sha256,image_bytes,byte_length,created_by_staff_id)
        values (?,?,?,?,?,?,?,?,?)
        """,assetId,institutionId,type,asset.contentType(),asset.fileName(),asset.sha256(),
        asset.bytes(),asset.bytes().length,staffId);
  }

  @PostMapping(value="/{institutionId}/branding/assets/{assetType}", consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
  @Transactional
  public Map<String,Object> uploadBrandingAsset(
      @PathVariable long institutionId,
      @PathVariable String assetType,
      @RequestPart("file") MultipartFile file,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    AuthContext owner = requirePlatformOwner(context);
    assertInstitutionExists(institutionId);
    String type = assetType == null ? "" : assetType.trim().toLowerCase(Locale.ROOT);
    String databaseType;
    String keyColumn;
    String urlSegment;
    switch(type) {
      case "logo" -> { databaseType="LOGO"; keyColumn="logo_asset_key"; urlSegment="logo"; }
      case "app-icon" -> { databaseType="APP_ICON"; keyColumn="app_icon_asset_key"; urlSegment="app-icon"; }
      default -> throw new IllegalArgumentException("Asset type must be logo or app-icon.");
    }

    InstitutionBrandingAssetValidator.ValidatedAsset asset = InstitutionBrandingAssetValidator.validate(file);
    String assetId=UUID.randomUUID().toString();
    jdbc.update("""
        insert into institution_branding_asset(
          asset_id,institution_id,asset_type,content_type,file_name,content_sha256,image_bytes,byte_length,created_by_staff_id)
        values (?,?,?,?,?,?,?,?,?)
        """,assetId,institutionId,databaseType,asset.contentType(),asset.fileName(),
        asset.sha256(),asset.bytes(),asset.bytes().length,owner.staffId());

    // Deactivate previous assets but retain their immutable audit/provenance rows.
    jdbc.update("update institution_branding_asset set active=false where institution_id=? and asset_type=? and asset_id<>? and active=true",
        institutionId,databaseType,assetId);
    int updated=jdbc.update("update institution_branding set "+keyColumn+"=?,version=version+1,updated_by_staff_id=?,updated_at=now() where institution_id=?",
        assetId,owner.staffId(),institutionId);
    if(updated!=1) throw new IllegalStateException("Institution branding settings are not initialized.");

    Long version=jdbc.queryForObject("select version from institution_branding where institution_id=?",Long.class,institutionId);
    String tenantCode=jdbc.queryForObject("select tenant_code from institution_tenant where id=?",String.class,institutionId);
    jdbc.update("""
        insert into institution_membership_event(institution_id,event_type,actor_platform_staff_id,details)
        values (?,'BRANDING_CHANGED',?,jsonb_build_object('assetType',?,'assetId',?,'sha256',?))
        """,institutionId,owner.staffId(),databaseType,assetId,asset.sha256());
    audit(owner,institutionId,"BRANDING_ASSET_UPLOADED","BRANDING_ASSET",assetId,
        Map.of("assetType",databaseType,"contentType",asset.contentType(),"byteLength",asset.bytes().length,
            "width",asset.width(),"height",asset.height(),"sha256",asset.sha256()));
    return Map.of("assetId",assetId,"assetType",databaseType,"contentType",asset.contentType(),
        "byteLength",asset.bytes().length,
        "assetUrl","/api/v1/institution/public/branding/"+tenantCode+"/assets/"+urlSegment+"?v="+version,
        "brandingVersion",version);
  }

  @DeleteMapping("/{institutionId}/branding/assets/{assetType}")
  @Transactional
  public Map<String,Object> removeBrandingAsset(
      @PathVariable long institutionId,
      @PathVariable String assetType,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    AuthContext owner = requirePlatformOwner(context);
    assertInstitutionExists(institutionId);
    String type = assetType == null ? "" : assetType.trim().toLowerCase(Locale.ROOT);
    String databaseType;
    String keyColumn;
    switch(type) {
      case "logo" -> { databaseType="LOGO"; keyColumn="logo_asset_key"; }
      case "app-icon" -> { databaseType="APP_ICON"; keyColumn="app_icon_asset_key"; }
      default -> throw new IllegalArgumentException("Asset type must be logo or app-icon.");
    }

    Map<String,Object> current = jdbc.queryForMap(
        "select "+keyColumn+" as asset_id from institution_branding where institution_id=?",institutionId);
    Object rawAssetId = current.get("asset_id");
    String assetId = rawAssetId == null ? "" : String.valueOf(rawAssetId).trim();
    if (assetId.isEmpty()) {
      Long currentVersion = jdbc.queryForObject(
          "select version from institution_branding where institution_id=?",Long.class,institutionId);
      return Map.of("removed",false,"assetType",databaseType,"brandingVersion",currentVersion);
    }

    int updated=jdbc.update("update institution_branding set "+keyColumn+"=null,version=version+1,updated_by_staff_id=?,updated_at=now() where institution_id=? and "+keyColumn+"=?",
        owner.staffId(),institutionId,assetId);
    if(updated!=1) throw new IllegalStateException("The branding asset changed during removal. Refresh and try again.");
    jdbc.update("update institution_branding_asset set active=false where institution_id=? and asset_type=? and asset_id=? and active=true",
        institutionId,databaseType,assetId);
    Long version=jdbc.queryForObject("select version from institution_branding where institution_id=?",Long.class,institutionId);
    jdbc.update("""
        insert into institution_membership_event(institution_id,event_type,actor_platform_staff_id,details)
        values (?,'BRANDING_CHANGED',?,jsonb_build_object('assetType',?,'assetId',?,'removed',true))
        """,institutionId,owner.staffId(),databaseType,assetId);
    audit(owner,institutionId,"BRANDING_ASSET_REMOVED","BRANDING_ASSET",assetId,
        Map.of("assetType",databaseType,"brandingVersion",version));
    return Map.of("removed",true,"assetType",databaseType,"brandingVersion",version);
  }

  @PutMapping("/{institutionId}/billing-contract")
  @Transactional
  public Map<String,Object> setBillingContract(
      @PathVariable long institutionId,
      @RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    AuthContext owner = requirePlatformOwner(context);
    assertInstitutionExists(institutionId);
    String currency = value(body,"currency").isBlank()?"INR":value(body,"currency").toUpperCase(Locale.ROOT);
    if(!"INR".equals(currency)) throw new IllegalArgumentException(
        "The current billing ledger stores amounts in INR paise. INR is the only supported billing currency until currency-specific minor-unit handling is implemented.");
    BigDecimal unitPrice;
    try { unitPrice = new BigDecimal(value(body,"unitPriceRupees")); }
    catch(Exception ex) { throw new IllegalArgumentException("Enter a valid price in rupees."); }
    if(unitPrice.signum()<0 || unitPrice.scale()>2) throw new IllegalArgumentException("Price must be non-negative with at most two decimal places.");
    long pricePaise;
    try { pricePaise=unitPrice.movePointRight(2).longValueExact(); }
    catch(ArithmeticException ex) { throw new IllegalArgumentException("Price is out of range."); }
    String cadence = value(body,"billingCadence").isBlank()?"MONTHLY":value(body,"billingCadence").toUpperCase(Locale.ROOT);
    if(!Set.of("MONTHLY","ANNUAL").contains(cadence)) throw new IllegalArgumentException("Billing cadence must be MONTHLY or ANNUAL.");
    LocalDate effectiveFrom = value(body,"effectiveFrom").isBlank()
        ? LocalDate.now(BILLING_ZONE) : parseDate(value(body,"effectiveFrom"),"effectiveFrom");
    LocalDate today = LocalDate.now(BILLING_ZONE);
    if(effectiveFrom.isAfter(today)) {
      throw new IllegalArgumentException("This version of the billing workflow activates contracts immediately; effectiveFrom cannot be in the future.");
    }
    LocalDate effectiveUntil = value(body,"effectiveUntil").isBlank()?null:parseDate(value(body,"effectiveUntil"),"effectiveUntil");
    if(effectiveUntil!=null && effectiveUntil.isBefore(effectiveFrom)) throw new IllegalArgumentException("effectiveUntil must be on or after effectiveFrom.");
    Boolean periodAlreadyBilled=jdbc.queryForObject("""
        select exists(select 1 from institution_billable_event
          where institution_id=? and event_type='CHARGE'
            and billing_period_start in (date_trunc('month',current_date)::date,date_trunc('year',current_date)::date))
        """,Boolean.class,institutionId);
    if(Boolean.TRUE.equals(periodAlreadyBilled)) {
      throw new IllegalStateException("A billable enrollment is already recorded for the current period. Contract price or currency cannot be changed mid-period; update it at the next period boundary.");
    }
    jdbc.update("""
        update institution_billing_contract
        set active=false,effective_until=greatest(effective_from,?::date)
        where institution_id=? and active=true
        """,today.minusDays(1),institutionId);
    Long contractId = jdbc.queryForObject("""
        insert into institution_billing_contract(
          institution_id,currency,unit_price_paise,billing_cadence,billable_rule,effective_from,effective_until,active,created_by_staff_id)
        values (?,?,?,?,'FIRST_ACTIVE_MEMBERSHIP_PER_PERIOD',?,?,true,?) returning id
        """,Long.class,institutionId,currency,pricePaise,cadence,effectiveFrom,effectiveUntil,owner.staffId());
    audit(owner,institutionId,"BILLING_CONTRACT_CHANGED","BILLING_CONTRACT",String.valueOf(contractId),
        Map.of("currency",currency,"unitPricePaise",pricePaise,"billingCadence",cadence,
          "effectiveFrom",effectiveFrom.toString(),"effectiveUntil",effectiveUntil==null?"":effectiveUntil.toString()));
    return Map.of("contractId",java.util.Objects.requireNonNull(contractId),"currency",currency,
        "unitPriceRupees",unitPrice,"unitPricePaise",pricePaise,"billingCadence",cadence,
        "effectiveFrom",effectiveFrom.toString(),"effectiveUntil",effectiveUntil==null?"":effectiveUntil.toString());
  }

  @PostMapping("/{institutionId}/billing/reconcile")
  @Transactional
  public Map<String,Object> reconcileBilling(
      @PathVariable long institutionId,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    AuthContext owner=requirePlatformOwner(context);
    assertInstitutionExists(institutionId);
    LocalDate today=LocalDate.now(BILLING_ZONE);
    List<Map<String,Object>> contracts=jdbc.queryForList("""
        select id,currency,unit_price_paise,billing_cadence
        from institution_billing_contract
        where institution_id=? and active=true and effective_from<=?
          and (effective_until is null or effective_until>=?)
        order by effective_from desc,id desc limit 1
        """,institutionId,today,today);
    if(contracts.isEmpty()) {
      throw new IllegalStateException("Configure an active billing contract before reconciling or allowing institution enrollments.");
    }
    Map<String,Object> contract=contracts.getFirst();
    LocalDate periodStart;
    LocalDate periodEnd;
    if("ANNUAL".equals(String.valueOf(contract.get("billing_cadence")))) {
      periodStart=today.withDayOfYear(1);
      periodEnd=periodStart.plusYears(1).minusDays(1);
    } else {
      periodStart=today.withDayOfMonth(1);
      periodEnd=periodStart.plusMonths(1).minusDays(1);
    }
    Integer before=jdbc.queryForObject("""
        select count(*) from institution_billable_event
        where institution_id=? and event_type='CHARGE' and billing_period_start=?
        """,Integer.class,institutionId,periodStart);
    int inserted=jdbc.update("""
        insert into institution_billable_event(
          institution_id,membership_id,student_id,contract_id,billing_period_start,billing_period_end,
          event_type,amount_paise,currency,idempotency_key,description)
        select ?,m.id,m.student_id,?,?,?,'CHARGE',?,?, 
          'institution:'||?::text||':membership:'||m.id||':period:'||?::text||':charge',
          'Active institution enrollment'
        from institution_membership m
        where m.institution_id=? and m.joined_at<=now()
          and (m.left_at is null or m.left_at>=?::date)
        on conflict do nothing
        """,institutionId,contract.get("id"),periodStart,periodEnd,contract.get("unit_price_paise"),
        contract.get("currency"),institutionId,periodStart,institutionId,periodStart);
    Integer after=jdbc.queryForObject("""
        select count(*) from institution_billable_event
        where institution_id=? and event_type='CHARGE' and billing_period_start=?
        """,Integer.class,institutionId,periodStart);
    audit(owner,institutionId,"BILLING_LEDGER_RECONCILED","BILLING_PERIOD",periodStart.toString(),
        Map.of("insertedEvents",inserted,"existingEvents",before==null?0:before,"totalChargeEvents",after==null?0:after,
          "periodEnd",periodEnd.toString(),"currency",String.valueOf(contract.get("currency"))));
    return Map.of("institutionId",institutionId,"periodStart",periodStart.toString(),"periodEnd",periodEnd.toString(),
        "insertedEvents",inserted,"totalChargeEvents",after==null?0:after,"currency",contract.get("currency"));
  }

  @PostMapping("/{institutionId}/join-code/rotate")
  @Transactional
  public Map<String,Object> rotateJoinCode(
      @PathVariable long institutionId,
      @RequestBody(required=false) Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    AuthContext owner = requirePlatformOwner(context);
    assertInstitutionExists(institutionId);
    Instant expires = Instant.now().plus(java.time.Duration.ofDays(90));
    if(body!=null && !value(body,"expiresAt").isBlank()) {
      expires = parseInstantOptional(value(body,"expiresAt"),"expiresAt");
      if(expires==null || !expires.isAfter(Instant.now())) throw new IllegalArgumentException("expiresAt must be in the future.");
    }
    Long useLimit = null;
    if(body!=null && !value(body,"useLimit").isBlank()) {
      try { useLimit=Long.parseLong(value(body,"useLimit")); }
      catch(NumberFormatException ex) { throw new IllegalArgumentException("useLimit must be a positive integer."); }
      if(useLimit<1) throw new IllegalArgumentException("useLimit must be a positive integer.");
    }
    Boolean issuanceAllowed=jdbc.queryForObject("""
        select exists(
          select 1 from institution_tenant t
          where t.id=? and t.status='ACTIVE'
            and exists(select 1 from institution_billing_contract bc
              where bc.institution_id=t.id and bc.active=true and bc.effective_from<=?
                and (bc.effective_until is null or bc.effective_until>=?))
        )
        """,Boolean.class,institutionId,LocalDate.now(BILLING_ZONE),LocalDate.now(BILLING_ZONE));
    if(!Boolean.TRUE.equals(issuanceAllowed)) {
      throw new IllegalStateException("Activate the institution and configure an effective billing contract before issuing a learner join code.");
    }
    InstitutionAuthService.IssuedJoinCode issued = institutionAuth.rotateJoinCode(institutionId,owner.staffId(),expires,useLimit);
    audit(owner,institutionId,"JOIN_CODE_ROTATED","JOIN_CODE",String.valueOf(issued.id()),
        Map.of("expiresAt",expires.toString(),"useLimit",useLimit==null?"unlimited":useLimit));
    return Map.of("joinCodeId",issued.id(),"joinCode",issued.code(),"codeHint",issued.hint(),
        "expiresAt",expires.toString(),"useLimit",useLimit==null?"unlimited":useLimit);
  }

  @GetMapping("/{institutionId}/roster")
  public Map<String,Object> platformRoster(
      @PathVariable long institutionId,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    AuthContext owner = requirePlatformOwner(context);
    assertInstitutionExists(institutionId);
    List<Map<String,Object>> learners = jdbc.queryForList("""
        select st.public_user_id as learner_reference,st.display_name,st.class_code,st.board,st.language,
          st.city,st.state,st.school_name,m.status as membership_status,m.joined_at,
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
        where m.institution_id=?
        group by st.id,m.id
        order by st.display_name,st.id
        """,institutionId);
    audit(owner,institutionId,"LEARNER_ROSTER_VIEWED","LEARNER_ROSTER",String.valueOf(institutionId),
        Map.of("rowCount",learners.size()));
    return Map.of("institutionId",institutionId,"learners",learners,
        "addressFieldsNote","Only city and state are stored in learner profiles; exact home addresses are not collected in this profile.");
  }

  @GetMapping("/{institutionId}/courses")
  public List<Map<String,Object>> getInstitutionCourses(
      @PathVariable long institutionId,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    AuthContext owner = requirePlatformOwner(context);
    assertInstitutionExists(institutionId);
    List<Map<String,Object>> result = institutionCourses(institutionId);
    audit(owner,institutionId,"INSTITUTION_COURSES_VIEWED","COURSE_LIST",String.valueOf(institutionId),
        Map.of("rowCount",result.size()));
    return result;
  }

  @GetMapping("/{institutionId}/audit")
  public List<Map<String,Object>> auditEvents(
      @PathVariable long institutionId,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    AuthContext owner = requirePlatformOwner(context);
    assertInstitutionExists(institutionId);
    audit(owner,institutionId,"AUDIT_VIEWED","AUDIT_LOG",String.valueOf(institutionId),Map.of());
    return jdbc.queryForList("""
        select id,actor_platform_staff_id,actor_institution_admin_id,action,target_type,target_id,details,created_at
        from institution_audit_log where institution_id=? order by created_at desc,id desc limit 200
        """,institutionId);
  }

  private List<Map<String,Object>> institutionSummaries() {
    return jdbc.queryForList("""
        select t.id,t.tenant_code,t.display_name,t.legal_name,t.status,t.created_at,t.activated_at,
          coalesce(b.display_name,t.display_name) as brand_display_name,b.version as branding_version,
          (select count(*) from institution_membership m where m.institution_id=t.id and m.status='ACTIVE') as active_memberships,
          (select count(*) from institution_course_entitlement e
            join curriculum_program p on p.id=e.program_id and p.status='ACTIVE' and p.code<>'GENERAL'
            where e.institution_id=t.id and e.status='ACTIVE' and e.valid_from<=now()
              and (e.valid_until is null or e.valid_until>now())) as authorized_courses,
          (select count(*) from institution_billable_event be
            join institution_billing_contract c on c.id=be.contract_id
            where be.institution_id=t.id and be.event_type='CHARGE'
              and be.billing_period_start=case when c.billing_cadence='ANNUAL'
                then date_trunc('year',current_date)::date else date_trunc('month',current_date)::date end) as current_period_billable_events,
          (select coalesce(sum(be.amount_paise),0) from institution_billable_event be
            join institution_billing_contract c on c.id=be.contract_id
            where be.institution_id=t.id and be.event_type='CHARGE'
              and be.billing_period_start=case when c.billing_cadence='ANNUAL'
                then date_trunc('year',current_date)::date else date_trunc('month',current_date)::date end) as current_period_amount_paise,
          (select c.currency from institution_billing_contract c where c.institution_id=t.id and c.active=true limit 1) as contract_currency
        from institution_tenant t
        left join institution_branding b on b.institution_id=t.id
        order by t.created_at desc,t.id desc
        """);
  }

  private List<Map<String,Object>> institutionCourses(long institutionId) {
    return jdbc.queryForList("""
        select p.id as program_id,p.code,p.display_name,e.status as entitlement_status,
          e.valid_from,e.valid_until,
          (select count(distinct l.id) from curriculum_subject sub
            join curriculum_chapter ch on ch.subject_id=sub.id and ch.active=true
            join lesson l on l.chapter_id=ch.id and l.active=true and l.status='PUBLISHED'
            where sub.program_id=p.id and sub.active=true) as published_lessons
        from institution_course_entitlement e
        join curriculum_program p on p.id=e.program_id
        where e.institution_id=?
        order by p.display_name,p.code
        """,institutionId);
  }

  private AuthContext requirePlatformOwner(AuthContext context) {
    return authorization.requirePermission(context,"QE_PLATFORM_OWNER");
  }

  private void assertInstitutionExists(long institutionId) {
    Boolean found = jdbc.queryForObject("select exists(select 1 from institution_tenant where id=?)",
        Boolean.class,institutionId);
    if(!Boolean.TRUE.equals(found)) throw new IllegalArgumentException("Institution not found.");
  }

  private void audit(AuthContext owner,Long institutionId,String action,String targetType,String targetId,Map<String,Object> details) {
    try {
      jdbc.update("""
          insert into institution_audit_log(institution_id,actor_platform_staff_id,action,target_type,target_id,details)
          values (?,?,?,?,?,?::jsonb)
          """,institutionId,owner.staffId(),action,targetType,targetId,objectMapper.writeValueAsString(details==null?Map.of():details));
    } catch(JsonProcessingException ex) {
      throw new IllegalStateException("Unable to record platform audit event.",ex);
    }
  }

  private String validateThemeTokens(Object raw) {
    try {
      return objectMapper.writeValueAsString(InstitutionThemeTokens.normalize(raw));
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("Unable to serialize validated institution theme tokens.", ex);
    }
  }

  private String publicAssetUrl(String tenantCode,String assetType,long version,Object assetKey) {
    if(assetKey==null || String.valueOf(assetKey).isBlank()) return "";
    return "/api/v1/institution/public/branding/"+tenantCode+"/assets/"+assetType+"?v="+version;
  }

  private String safeStoreListing(String raw) {
    if(raw==null || raw.isBlank()) return null;
    try {
      URI uri=URI.create(raw);
      if(!"https".equalsIgnoreCase(uri.getScheme()) || !"play.google.com".equalsIgnoreCase(uri.getHost())
          || uri.getRawPath()==null || !uri.getRawPath().startsWith("/store/apps/details")) {
        throw new IllegalArgumentException("Store listing must be an HTTPS Google Play listing URL.");
      }
      return uri.toString();
    } catch(IllegalArgumentException ex) {
      throw new IllegalArgumentException("Store listing must be an HTTPS Google Play listing URL.");
    }
  }

  private Object parseJsonObject(Object raw) {
    if(raw==null || String.valueOf(raw).isBlank()) return Map.of();
    try { return objectMapper.readValue(String.valueOf(raw),Map.class); }
    catch(JsonProcessingException ex) { throw new IllegalStateException("Stored branding tokens are invalid.",ex); }
  }

  private Instant parseInstantOptional(String raw,String name) {
    if(raw==null || raw.isBlank()) return null;
    try { return Instant.parse(raw); }
    catch(Exception ex) { throw new IllegalArgumentException(name+" must be an ISO-8601 UTC timestamp."); }
  }

  private LocalDate parseDate(String raw,String name) {
    try { return LocalDate.parse(raw); }
    catch(Exception ex) { throw new IllegalArgumentException(name+" must be an ISO date (YYYY-MM-DD)."); }
  }

  private long positiveId(Object raw,String label) {
    if(!(raw instanceof Number numeric) || numeric.longValue()<1L
        || numeric.doubleValue()!=numeric.longValue()) {
      throw new IllegalArgumentException(label+" must be a positive integer.");
    }
    return numeric.longValue();
  }

  private String value(Map<String,Object> body,String key) {
    Object raw=body==null?null:body.get(key);
    return raw==null?"":String.valueOf(raw).trim();
  }

  private String normalizeBrandTagline(String raw) {
    String tagline=raw==null?"":raw.trim();
    if(tagline.length()>240 || tagline.chars().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException("Brand tagline must be 240 characters or fewer and contain no control characters.");
    }
    return tagline;
  }

  private void requireText(String value,int min,int max,String label) {
    if(value==null || value.trim().length()<min || value.trim().length()>max
        || value.chars().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException(label+" must be between "+min+" and "+max+" characters.");
    }
  }

  private long number(Object value) {
    if (value instanceof Number numeric) return numeric.longValue();
    if (value == null) return 0L;
    try { return Long.parseLong(String.valueOf(value).trim()); }
    catch (NumberFormatException ex) {
      throw new IllegalStateException("Expected a numeric database readiness value.", ex);
    }
  }

  private String emptyToNull(String value) { return value==null || value.isBlank()?null:value; }
  private String nullableString(Object value) { return value==null?null:String.valueOf(value); }
}
