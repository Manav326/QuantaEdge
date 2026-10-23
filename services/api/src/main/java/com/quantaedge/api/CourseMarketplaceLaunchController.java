package com.quantaedge.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
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
import org.springframework.web.server.ResponseStatusException;

/**
 * HRMS-owned per-institution Marketplace launch workflow. All mutations require the platform-owner
 * capability and all publication paths reuse the server-side financial/content readiness checklist.
 */
@RestController
@RequestMapping("/api/v1/hrms/platform/institutions")
public class CourseMarketplaceLaunchController {
  private final JdbcTemplate jdbc;
  private final AuthorizationService authorization;
  private final CourseMarketplaceLaunchReadinessService readiness;
  private final ObjectMapper mapper;

  public CourseMarketplaceLaunchController(
      JdbcTemplate jdbc, AuthorizationService authorization,
      CourseMarketplaceLaunchReadinessService readiness, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.authorization = authorization;
    this.readiness = readiness;
    this.mapper = mapper;
  }

  @GetMapping("/{institutionId}/marketplace-launches")
  public Map<String,Object> marketplaceLaunches(
      @PathVariable long institutionId,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    authorization.requirePermission(context,"QE_PLATFORM_OWNER");
    assertInstitution(institutionId);
    List<Long> assignments=jdbc.queryForList("""
        select a.id from institution_course_assignment a
        where a.institution_id=? and a.status='ASSIGNED'
        order by a.assigned_at desc,a.id desc
        """,Long.class,institutionId);
    List<Map<String,Object>> items=new ArrayList<>();
    int readyCount=0;
    for(Long assignmentId:assignments) {
      Map<String,Object> item=readiness.inspectAssignment(assignmentId,institutionId);
      items.add(item);
      if(Boolean.TRUE.equals(item.get("ready"))) readyCount++;
    }
    List<Map<String,Object>> availableReleases=jdbc.queryForList("""
        select r.id as release_id,r.program_id as course_id,r.release_version,r.content_fingerprint,
          p.code as course_code,p.display_name as course_name,
          source.id as source_institution_id,source.tenant_code as source_institution_code,
          source.display_name as source_institution_name
        from institution_course_release r
        join curriculum_program p on p.id=r.program_id and p.status='ACTIVE' and p.code<>'GENERAL'
        join institution_tenant source on source.id=r.source_institution_id and source.status='ACTIVE'
        where r.release_state='SHAREABLE' and r.source_institution_id<>?
          and not exists(select 1 from institution_course_assignment a
            where a.institution_id=? and a.course_release_id=r.id and a.status='ASSIGNED')
        order by p.display_name,source.display_name,r.release_version desc,r.id desc
        """,institutionId,institutionId);
    for(Map<String,Object> release:availableReleases) {
      release.put("contentCurrent",readiness.contentFingerprintMatches(
          number(release.get("course_id")),text(release.get("content_fingerprint"))));
    }
    List<Map<String,Object>> qualityReviewItems=jdbc.queryForList("""
        select r.id as release_id,r.program_id as course_id,r.release_version,r.release_state,
          r.content_fingerprint,r.submitted_at,p.code as course_code,p.display_name as course_name,
          t.id as source_institution_id,t.tenant_code as source_institution_code,
          t.display_name as source_institution_name,
          count(distinct s.id) as active_subject_count,
          count(distinct case when l.id is not null then s.id end) as subjects_with_published_lessons,
          count(distinct ch.id) as published_chapters,count(distinct l.id) as published_lessons
        from institution_course_release r
        join curriculum_program p on p.id=r.program_id and p.status='ACTIVE' and p.code<>'GENERAL'
        join institution_tenant t on t.id=r.source_institution_id and t.status='ACTIVE'
        left join curriculum_program_subject ps on ps.program_id=p.id
        left join curriculum_subject s on s.id=ps.subject_id and s.class_id=ps.class_id and s.active=true
          and (s.program_id=ps.program_id or s.program_id is null)
        left join curriculum_chapter ch on ch.subject_id=s.id and ch.active=true and ch.content_status='PUBLISHED'
        left join lesson l on l.chapter_id=ch.id and l.active=true and l.status='PUBLISHED'
        where r.source_institution_id=? and r.release_state='IN_QUALITY_REVIEW'
        group by r.id,r.program_id,r.release_version,r.release_state,r.content_fingerprint,r.submitted_at,
          p.code,p.display_name,t.id,t.tenant_code,t.display_name
        order by r.submitted_at,r.id
        """,institutionId);
    for(Map<String,Object> release:qualityReviewItems) {
      release.put("contentCurrent",readiness.contentFingerprintMatches(
          number(release.get("course_id")),text(release.get("content_fingerprint"))));
    }
    Map<String,Object> result=new LinkedHashMap<>();
    result.put("institutionId",institutionId);
    result.put("institutionName",institutionName(institutionId));
    result.put("items",items);
    result.put("total",items.size());
    result.put("readyCount",readyCount);
    result.put("blockedCount",items.size()-readyCount);
    result.put("availableReleases",availableReleases);
    result.put("availableCount",availableReleases.size());
    result.put("qualityReviewItems",qualityReviewItems);
    result.put("qualityReviewCount",qualityReviewItems.size());
    return result;
  }

  @PostMapping("/{institutionId}/marketplace-launches/{assignmentId}/offer")
  @Transactional
  public Map<String,Object> saveDraftOffer(
      @PathVariable long institutionId,@PathVariable long assignmentId,
      @RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    context=authorization.requirePermission(context,"QE_PLATFORM_OWNER");
    requireStaff(context);
    assertInstitution(institutionId);
    if(body==null||!(body.get("pricePaise") instanceof Number))
      throw new IllegalArgumentException("Enter an explicit INR price in rupees; use zero only for an intentionally free course.");
    long pricePaise=number(body.get("pricePaise"));
    if(pricePaise<0) throw new IllegalArgumentException("Course price cannot be negative.");
    String currency=optionalText(body.get("currency"),"INR",3,"Currency").toUpperCase(Locale.ROOT);
    if(!"INR".equals(currency)) throw new IllegalArgumentException("Marketplace course offers currently support INR only.");
    String title=optionalText(body.get("title"),"",200,"Course listing title");
    String description=optionalText(body.get("description"),"",5000,"Course listing description");
    if(title.isBlank()) title=null;
    if(description.isBlank()) description=null;

    Map<String,Object> assignment=lockAssignment(institutionId,assignmentId);
    if(!"ASSIGNED".equals(text(assignment.get("assignment_status"))))
      throw conflict("The course assignment is not active. No offer draft was saved.");
    if(!"ACTIVE".equals(text(assignment.get("institution_status")))
        ||!"ACTIVE".equals(text(assignment.get("course_status")))
        ||"GENERAL".equalsIgnoreCase(text(assignment.get("course_code"))))
      throw conflict("Only an active institution and active standalone course product can be configured for Marketplace.");
    if(!"SHAREABLE".equals(text(assignment.get("release_state"))))
      throw conflict("The course release must pass origin approval and platform content-quality review before an offer can be drafted.");
    Map<String,Object> base=readiness.inspectAssignment(assignmentId,institutionId);
    @SuppressWarnings("unchecked")
    List<Map<String,Object>> checks=(List<Map<String,Object>>)base.get("checks");
    List<String> contentBlockers=new ArrayList<>();
    List<String> courseReadinessCodes=List.of(
        "INSTITUTION_ACTIVE","COURSE_ACTIVE","CONTENT_QUALITY_REVIEW",
        "ORIGIN_RELEASE_APPROVAL","ASSIGNMENT_AND_RELEASE",
        "CONTENT_REVIEW_CURRENT","PUBLISHED_LEARNING_CONTENT","SOURCE_RIGHTS_CLEARANCE");
    for(Map<String,Object> check:checks) {
      if(courseReadinessCodes.contains(text(check.get("code")))
          && !Boolean.TRUE.equals(check.get("passed"))) {
        contentBlockers.add(text(check.get("label"))+": "+text(check.get("message")));
      }
    }
    if(!contentBlockers.isEmpty())
      throw conflict("Course is not ready for an offer draft. Resolve the following first: "+String.join(" ",contentBlockers));

    List<Map<String,Object>> existing=jdbc.queryForList("""
        select id,status from institution_marketplace_offer
        where institution_id=? and assignment_id=? for update
        """,institutionId,assignmentId);
    long offerId;
    String oldStatus=existing.isEmpty()?"":text(existing.getFirst().get("status"));
    if(!existing.isEmpty()&&!List.of("DRAFT","REJECTED","ARCHIVED").contains(oldStatus))
      throw conflict("This offer is already "+oldStatus+". Use its review/publish steps instead of replacing its terms.");
    if(existing.isEmpty()) {
      offerId=jdbc.queryForObject("""
          insert into institution_marketplace_offer(
            institution_id,assignment_id,course_release_id,course_id,title_override,description,
            price_paise,currency,status,created_at,updated_at
          ) values(?,?,?,?,?,?,?,?,'DRAFT',now(),now()) returning id
          """,Long.class,institutionId,assignmentId,number(assignment.get("course_release_id")),
          number(assignment.get("course_id")),title,description,pricePaise,currency);
    } else {
      offerId=number(existing.getFirst().get("id"));
      jdbc.update("""
          update institution_marketplace_offer set
            title_override=?,description=?,price_paise=?,currency=?,
            status='DRAFT',submitted_by_admin_id=null,submitted_at=null,
            approved_by_admin_id=null,approved_at=null,published_by_admin_id=null,published_at=null,
            hrms_approved_by_staff_id=null,hrms_approved_at=null,
            hrms_published_by_staff_id=null,hrms_published_at=null,
            rejected_by_admin_id=null,rejected_at=null,rejection_reason=null,
            updated_by_admin_id=null,updated_at=now()
          where id=? and institution_id=? and assignment_id=?
            and status in ('DRAFT','REJECTED','ARCHIVED')
          """,title,description,pricePaise,currency,offerId,institutionId,assignmentId);
    }
    record(institutionId,number(assignment.get("release_id")),assignmentId,offerId,
        "OFFER_DRAFTED",context.staffId(),
        Map.of("pricePaise",pricePaise,"currency",currency,"previousStatus",oldStatus.isBlank()?"NONE":oldStatus));
    return Map.of("saved",true,"offerId",offerId,"assignmentId",assignmentId,
        "status","DRAFT","message","Offer draft saved. Re-run the launch checklist before submission or publication.");
  }

  @PostMapping("/{institutionId}/marketplace-launches/{offerId}/submit")
  @Transactional
  public Map<String,Object> submitOffer(
      @PathVariable long institutionId,@PathVariable long offerId,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    return transition(institutionId,offerId,"SUBMIT",null,context);
  }

  @PostMapping("/{institutionId}/marketplace-launches/{offerId}/approve")
  @Transactional
  public Map<String,Object> approveOffer(
      @PathVariable long institutionId,@PathVariable long offerId,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    return transition(institutionId,offerId,"APPROVE",null,context);
  }

  @PostMapping("/{institutionId}/marketplace-launches/{offerId}/publish")
  @Transactional
  public Map<String,Object> publishOffer(
      @PathVariable long institutionId,@PathVariable long offerId,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    return transition(institutionId,offerId,"PUBLISH",null,context);
  }

  @PostMapping("/{institutionId}/marketplace-launches/{offerId}/return-to-draft")
  @Transactional
  public Map<String,Object> returnToDraft(
      @PathVariable long institutionId,@PathVariable long offerId,@RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    String reason=optionalText(body==null?null:body.get("reason"),"",1000,"Reason");
    if(reason.length()<3) throw new IllegalArgumentException("Enter at least 3 characters explaining why the offer is being returned to draft.");
    return transition(institutionId,offerId,"RETURN_TO_DRAFT",reason,context);
  }

  @PostMapping("/{institutionId}/marketplace-launches/{offerId}/archive")
  @Transactional
  public Map<String,Object> archiveOffer(
      @PathVariable long institutionId,@PathVariable long offerId,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    return transition(institutionId,offerId,"ARCHIVE",null,context);
  }

  private Map<String,Object> transition(
      long institutionId,long offerId,String action,String reason,AuthContext supplied) {
    AuthContext context=authorization.requirePermission(supplied,"QE_PLATFORM_OWNER");
    requireStaff(context);
    assertInstitution(institutionId);
    List<Map<String,Object>> offers=jdbc.queryForList("""
        select o.id,o.institution_id,o.assignment_id,o.course_release_id,o.course_id,o.status,
          o.price_paise,o.currency,a.status as assignment_status,r.release_state
        from institution_marketplace_offer o
        join institution_course_assignment a on a.id=o.assignment_id and a.institution_id=o.institution_id
        join institution_course_release r on r.id=o.course_release_id and r.program_id=o.course_id
        where o.id=? and o.institution_id=?
        for update of o,a,r
        """,offerId,institutionId);
    if(offers.isEmpty()) throw new org.springframework.web.server.ResponseStatusException(
        HttpStatus.NOT_FOUND,"Marketplace offer not found for this institution.");
    Map<String,Object> row=offers.getFirst();
    String oldStatus=text(row.get("status"));
    long assignmentId=number(row.get("assignment_id"));
    long releaseId=number(row.get("course_release_id"));
    String next;
    switch(action) {
      case "SUBMIT" -> {
        if(!List.of("DRAFT","REJECTED").contains(oldStatus))
          throw conflict("Only a draft or rejected offer can be submitted.");
        readiness.requireReady(offerId,institutionId);
        jdbc.update("""
            update institution_marketplace_offer set status='IN_REVIEW',
              submitted_by_admin_id=null,submitted_at=now(),approved_by_admin_id=null,approved_at=null,
              hrms_approved_by_staff_id=null,hrms_approved_at=null,
              hrms_published_by_staff_id=null,hrms_published_at=null,
              rejected_by_admin_id=null,rejected_at=null,rejection_reason=null,
              updated_by_admin_id=null,updated_at=now()
            where id=? and institution_id=? and status=?
            """,offerId,institutionId,oldStatus);
        next="IN_REVIEW";
      }
      case "APPROVE" -> {
        if(!"IN_REVIEW".equals(oldStatus)) throw conflict("Only a listing in review can be approved.");
        readiness.requireReady(offerId,institutionId);
        jdbc.update("""
            update institution_marketplace_offer set status='APPROVED',
              approved_by_admin_id=null,approved_at=now(),
              hrms_approved_by_staff_id=?,hrms_approved_at=now(),
              hrms_published_by_staff_id=null,hrms_published_at=null,
              updated_by_admin_id=null,updated_at=now()
            where id=? and institution_id=? and status='IN_REVIEW'
            """,context.staffId(),offerId,institutionId);
        next="APPROVED";
      }
      case "PUBLISH" -> {
        if(!"APPROVED".equals(oldStatus)) throw conflict("An offer must be approved before publication.");
        readiness.requireReady(offerId,institutionId);
        jdbc.update("""
            update institution_marketplace_offer set status='PUBLISHED',
              published_by_admin_id=null,published_at=now(),
              hrms_published_by_staff_id=?,hrms_published_at=now(),
              updated_by_admin_id=null,updated_at=now()
            where id=? and institution_id=? and status='APPROVED'
            """,context.staffId(),offerId,institutionId);
        next="PUBLISHED";
      }
      case "RETURN_TO_DRAFT" -> {
        if(!List.of("IN_REVIEW","APPROVED").contains(oldStatus))
          throw conflict("Only an offer under review or approved but not published can be returned to draft.");
        jdbc.update("""
            update institution_marketplace_offer set status='DRAFT',
              submitted_by_admin_id=null,submitted_at=null,approved_by_admin_id=null,approved_at=null,
              published_by_admin_id=null,published_at=null,
              hrms_approved_by_staff_id=null,hrms_approved_at=null,
              hrms_published_by_staff_id=null,hrms_published_at=null,
              rejected_by_admin_id=null,rejected_at=null,rejection_reason=null,
              updated_by_admin_id=null,updated_at=now()
            where id=? and institution_id=? and status=?
            """,offerId,institutionId,oldStatus);
        next="DRAFT";
      }
      case "ARCHIVE" -> {
        if("ARCHIVED".equals(oldStatus)) return Map.of("updated",true,"offerId",offerId,"status","ARCHIVED");
        jdbc.update("""
            update institution_marketplace_offer set status='ARCHIVED',
              updated_by_admin_id=null,updated_at=now()
            where id=? and institution_id=? and status=?
            """,offerId,institutionId,oldStatus);
        next="ARCHIVED";
      }
      default -> throw new IllegalArgumentException("Unsupported Marketplace launch transition.");
    }
    Map<String,Object> details=new LinkedHashMap<>();
    details.put("from",oldStatus);details.put("to",next);details.put("action",action);
    details.put("pricePaise",number(row.get("price_paise")));details.put("currency",text(row.get("currency")));
    if(reason!=null)details.put("reason",reason);
    record(institutionId,releaseId,assignmentId,offerId,
        switch(action) {
          case "SUBMIT" -> "OFFER_SUBMITTED";
          case "APPROVE" -> "OFFER_APPROVED";
          case "PUBLISH" -> "OFFER_PUBLISHED";
          case "RETURN_TO_DRAFT" -> "OFFER_RETURNED_TO_DRAFT";
          default -> "OFFER_ARCHIVED";
        },context.staffId(),details);
    return Map.of("updated",true,"offerId",offerId,"status",next,
        "message","Marketplace listing status updated.");
  }

  private Map<String,Object> lockAssignment(long institutionId,long assignmentId) {
    List<Map<String,Object>> rows=jdbc.queryForList("""
        select a.id,a.institution_id,a.course_release_id,a.status as assignment_status,
          r.program_id as course_id,r.release_state,r.content_fingerprint,
          p.status as course_status,p.code as course_code,p.display_name as course_name,
          t.status as institution_status
        from institution_course_assignment a
        join institution_course_release r on r.id=a.course_release_id
        join curriculum_program p on p.id=r.program_id
        join institution_tenant t on t.id=a.institution_id
        where a.id=? and a.institution_id=?
        for update of a,r
        """,assignmentId,institutionId);
    if(rows.isEmpty()) throw new org.springframework.web.server.ResponseStatusException(
        HttpStatus.NOT_FOUND,"Course assignment not found for this institution.");
    return rows.getFirst();
  }

  private void assertInstitution(long institutionId) {
    Boolean exists=jdbc.queryForObject(
        "select exists(select 1 from institution_tenant where id=?)",Boolean.class,institutionId);
    if(!Boolean.TRUE.equals(exists)) throw new org.springframework.web.server.ResponseStatusException(
        HttpStatus.NOT_FOUND,"Institution not found.");
  }

  private String institutionName(long institutionId) {
    return jdbc.queryForObject("select display_name from institution_tenant where id=?",String.class,institutionId);
  }

  private void requireStaff(AuthContext context) {
    if(context==null||context.staffId()==null)
      throw new SecurityException("A verified HRMS staff identity is required for Marketplace launch changes.");
  }

  private void record(long institutionId,long releaseId,long assignmentId,long offerId,
      String eventType,long staffId,Map<String,Object> details) {
    try {
      jdbc.update("""
          insert into institution_course_lifecycle_event(
            institution_id,course_release_id,assignment_id,offer_id,event_type,actor_platform_staff_id,details
          ) values(?,?,?,?,?,?,?::jsonb)
          """,institutionId,releaseId,assignmentId,offerId,eventType,staffId,mapper.writeValueAsString(details));
    } catch(JsonProcessingException ex) {
      throw new IllegalStateException("Marketplace launch audit details could not be serialized.",ex);
    }
  }

  private static long number(Object raw) {
    if(raw instanceof Number number) return number.longValue();
    try{return Long.parseLong(String.valueOf(raw));}
    catch(RuntimeException ex){throw new IllegalArgumentException("A numeric value was expected.");}
  }
  private static String text(Object raw){return raw==null?"":String.valueOf(raw);}
  private static String optionalText(Object raw,String fallback,int max,String label) {
    if(raw==null) return fallback;
    String value=String.valueOf(raw).trim();
    if(value.length()>max) throw new IllegalArgumentException(label+" must be "+max+" characters or fewer.");
    return value;
  }
  private static ResponseStatusException conflict(String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT,message);
  }
}
