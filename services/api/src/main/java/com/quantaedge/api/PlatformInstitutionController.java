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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** HRMS-only cross-tenant control plane. Every mutating method requires QE_PLATFORM_OWNER. */
@RestController
@RequestMapping("/api/v1/hrms/platform/institutions")
public class PlatformInstitutionController {
  private static final ZoneId BILLING_ZONE = ZoneId.of("Asia/Kolkata");
  private static final Set<String> THEME_KEYS = Set.of("primary","accent","background","surface","text");

  private final JdbcTemplate jdbc;
  private final AuthorizationService authorization;
  private final InstitutionAuthService institutionAuth;
  private final ObjectMapper objectMapper;

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

  @GetMapping("/catalog/courses")
  public List<Map<String,Object>> courseCatalog(@RequestAttribute(value="authContext",required=false) AuthContext context) {
    AuthContext owner = requirePlatformOwner(context);
    audit(owner,null,"COURSE_CATALOG_VIEWED","COURSE_CATALOG",null,Map.of());
    return jdbc.queryForList("""
        select p.id,p.code,p.display_name,
          string_agg(distinct c.code, ', ' order by c.code) as class_code
        from curriculum_program p
        left join curriculum_program_subject ps on ps.program_id=p.id
        left join curriculum_subject s on s.id=ps.subject_id and s.program_id=ps.program_id
          and s.class_id=ps.class_id and s.active=true
        left join curriculum_class c on c.id=s.class_id and c.active=true
        where p.status='ACTIVE' and p.code<>'GENERAL'
        group by p.id,p.code,p.display_name
        order by p.display_name,p.code
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
          b.display_name as brand_display_name,b.logo_asset_key,b.app_icon_asset_key,b.store_listing_url,
          b.theme_tokens::text as theme_tokens,b.version as branding_version,
          c.id as contract_id,c.currency,c.unit_price_paise,c.billing_cadence,c.effective_from,c.effective_until
        from institution_tenant t
        left join institution_branding b on b.institution_id=t.id
        left join institution_billing_contract c on c.institution_id=t.id and c.active=true
        where t.id=?
        """,institutionId);
    if(rows.isEmpty()) throw new IllegalArgumentException("Institution not found.");
    Map<String,Object> result = new LinkedHashMap<>(rows.getFirst());
    result.put("themeTokens", parseJsonObject(result.remove("theme_tokens")));
    result.put("admins", jdbc.queryForList("""
        select id,username,display_name,active,must_change_password,last_login_at,created_at
        from institution_admin_account where institution_id=? order by created_at,id
        """,institutionId));
    result.put("courses", institutionCourses(institutionId));
    audit(owner,institutionId,"INSTITUTION_VIEWED","INSTITUTION",String.valueOf(institutionId),Map.of());
    return result;
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
      InstitutionAuthService.IssuedJoinCode issued = institutionAuth.rotateJoinCode(
          id,owner.staffId(),Instant.now().plus(java.time.Duration.ofDays(90)),null);
      audit(owner,id,"INSTITUTION_CREATED","INSTITUTION",String.valueOf(id),
          Map.of("tenantCode",tenantCode,"adminUsername",username));
      return Map.of("institutionId",id,"tenantCode",tenantCode,"displayName",displayName,
          "status","ACTIVE","adminUsername",username,"initialPasswordChangeRequired",true,
          "joinCode",issued.code(),"joinCodeExpiresAt",issued.expiresAt().toString());
    } catch(DuplicateKeyException ex) {
      throw new IllegalArgumentException("Institution code or administrator username already exists.");
    }
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

  @PutMapping("/{institutionId}/courses/{programId}")
  @Transactional
  public Map<String,Object> updateCourseEntitlement(
      @PathVariable long institutionId,
      @PathVariable long programId,
      @RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    AuthContext owner = requirePlatformOwner(context);
    assertInstitutionExists(institutionId);
    Object raw = body==null?null:body.get("enabled");
    if(!(raw instanceof Boolean enabled)) throw new IllegalArgumentException("The enabled field must be true or false.");
    if(enabled) {
      Boolean exists = jdbc.queryForObject("""
          select exists(select 1 from curriculum_program where id=? and status='ACTIVE' and code<>'GENERAL')
          """,Boolean.class,programId);
      if(!Boolean.TRUE.equals(exists)) throw new IllegalArgumentException("Only an active, specifically identified course can be authorised.");
      Instant validUntil = parseInstantOptional(value(body,"validUntil"),"validUntil");
      if(validUntil!=null && !validUntil.isAfter(Instant.now())) throw new IllegalArgumentException("validUntil must be in the future.");
      jdbc.update("""
          insert into institution_course_entitlement(institution_id,program_id,status,valid_from,valid_until,granted_by_staff_id)
          values (?,?,'ACTIVE',now(),?,?)
          on conflict(institution_id,program_id) do update set status='ACTIVE',valid_from=now(),
            valid_until=excluded.valid_until,granted_by_staff_id=excluded.granted_by_staff_id,
            revoked_by_staff_id=null,updated_at=now()
          """,institutionId,programId,validUntil==null?null:java.sql.Timestamp.from(validUntil),owner.staffId());
    } else {
      jdbc.update("""
          update institution_course_entitlement
          set status='REVOKED',revoked_by_staff_id=?,updated_at=now()
          where institution_id=? and program_id=?
          """,owner.staffId(),institutionId,programId);
    }
    jdbc.update("""
        insert into institution_membership_event(institution_id,event_type,actor_platform_staff_id,details)
        values (?,'COURSE_ENTITLEMENT_CHANGED',?,jsonb_build_object('programId',?,'enabled',?))
        """,institutionId,owner.staffId(),programId,enabled);
    audit(owner,institutionId,"COURSE_ENTITLEMENT_CHANGED","COURSE",String.valueOf(programId),Map.of("enabled",enabled));
    return Map.of("institutionId",institutionId,"programId",programId,"enabled",enabled);
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
        select display_name,logo_asset_key,app_icon_asset_key,store_listing_url,theme_tokens::text as theme_tokens
        from institution_branding where institution_id=?
        """,institutionId);
    String displayName = body!=null && body.containsKey("displayName")
        ? value(body,"displayName") : String.valueOf(existing.get("display_name"));
    requireText(displayName,2,180,"Branded display name");
    String logo = body!=null && body.containsKey("logoAssetKey")
        ? safeAssetKey(value(body,"logoAssetKey")) : nullableString(existing.get("logo_asset_key"));
    String icon = body!=null && body.containsKey("appIconAssetKey")
        ? safeAssetKey(value(body,"appIconAssetKey")) : nullableString(existing.get("app_icon_asset_key"));
    String storeListing = body!=null && body.containsKey("storeListingUrl")
        ? safeStoreListing(value(body,"storeListingUrl")) : nullableString(existing.get("store_listing_url"));
    String tokens = body!=null && body.containsKey("themeTokens")
        ? validateThemeTokens(body.get("themeTokens")) : String.valueOf(existing.get("theme_tokens"));
    jdbc.update("""
        update institution_branding set display_name=?,logo_asset_key=?,app_icon_asset_key=?,store_listing_url=?,
          theme_tokens=?::jsonb,version=version+1,updated_by_staff_id=?,updated_at=now()
        where institution_id=?
        """,displayName,logo,icon,storeListing,tokens,owner.staffId(),institutionId);
    jdbc.update("""
        insert into institution_membership_event(institution_id,event_type,actor_platform_staff_id,details)
        values (?,'BRANDING_CHANGED',?,jsonb_build_object('brandingVersionIncremented',true))
        """,institutionId,owner.staffId());
    audit(owner,institutionId,"BRANDING_CHANGED","BRANDING",String.valueOf(institutionId),
        Map.of("displayName",displayName,"hasLogo",logo!=null,"hasAppIcon",icon!=null));
    return Map.of("institutionId",institutionId,"displayName",displayName,"logoAssetKey",logo==null?"":logo,
        "appIconAssetKey",icon==null?"":icon,"storeListingUrl",storeListing==null?"":storeListing,
        "themeTokens",parseJsonObject(tokens),"brandingVersion",jdbc.queryForObject(
          "select version from institution_branding where institution_id=?",Long.class,institutionId));
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
    if(!currency.matches("^[A-Z]{3}$")) throw new IllegalArgumentException("Currency must be a three-letter ISO currency code.");
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
    if(!(raw instanceof Map<?,?> input)) throw new IllegalArgumentException("themeTokens must be an object of approved colour tokens.");
    Map<String,String> clean = new LinkedHashMap<>();
    for(Map.Entry<?,?> item:input.entrySet()) {
      String key=String.valueOf(item.getKey());
      if(!THEME_KEYS.contains(key)) throw new IllegalArgumentException("Unsupported theme token: "+key);
      String color=String.valueOf(item.getValue());
      if(!color.matches("^#[0-9A-Fa-f]{6}$")) throw new IllegalArgumentException("Theme colours must use #RRGGBB format.");
      clean.put(key,color.toUpperCase(Locale.ROOT));
    }
    try { return objectMapper.writeValueAsString(clean); }
    catch(JsonProcessingException ex) { throw new IllegalStateException("Unable to validate theme tokens.",ex); }
  }

  private String safeAssetKey(String raw) {
    if(raw==null || raw.isBlank()) return null;
    if(raw.length()>500 || raw.startsWith("/") || raw.contains("..") || raw.contains("\\")
        || raw.contains("://") || raw.chars().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException("Asset keys must be safe relative storage keys; arbitrary URLs are not accepted.");
    }
    return raw;
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

  private String value(Map<String,Object> body,String key) {
    Object raw=body==null?null:body.get(key);
    return raw==null?"":String.valueOf(raw).trim();
  }

  private void requireText(String value,int min,int max,String label) {
    if(value==null || value.trim().length()<min || value.trim().length()>max
        || value.chars().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException(label+" must be between "+min+" and "+max+" characters.");
    }
  }

  private String emptyToNull(String value) { return value==null || value.isBlank()?null:value; }
  private String nullableString(Object value) { return value==null?null:String.valueOf(value); }
}
