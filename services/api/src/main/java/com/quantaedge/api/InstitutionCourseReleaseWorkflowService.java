package com.quantaedge.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Shared source-release state transitions. Both the legacy institution client and the
 * Education Admin workspace use these same server-authoritative checks and lifecycle events.
 * This service never approves or publishes a Marketplace offer.
 */
@Service
public class InstitutionCourseReleaseWorkflowService {
  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;
  private final TrustedCourseVersionDistributionService trustedCourseDistribution;

  public InstitutionCourseReleaseWorkflowService(JdbcTemplate jdbc, ObjectMapper objectMapper,
      TrustedCourseVersionDistributionService trustedCourseDistribution) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
    this.trustedCourseDistribution = trustedCourseDistribution;
  }

  @Transactional
  public Map<String,Object> submitRelease(long programId, long institutionId,
      Long actorStaffId, Long actorAdminId) {
    List<Map<String,Object>> owned=jdbc.queryForList("""
        select p.id,p.code,p.display_name,p.status,
          coalesce((select delivery_mode from curriculum_program_delivery_mode where program_id=p.id),'LEARNING_CONTENT') as delivery_model
        from curriculum_program p
        where p.id=? and p.status='ACTIVE' and p.code<>'GENERAL'
          and exists(select 1 from institution_course_release r
            where r.program_id=p.id and r.source_institution_id=?)
        for update of p
        """,programId,institutionId);
    if(owned.isEmpty()) throw new SecurityException("This active course is not owned by the current institution.");

    Map<String,Object> evidence=jdbc.queryForMap("""
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
    String deliveryMode=String.valueOf(owned.getFirst().get("delivery_model"));
    if("ASSESSMENT_ONLY".equals(deliveryMode)) {
      Boolean ready=jdbc.queryForObject("select assessment_only_course_ready(?)",Boolean.class,programId);
      if(!Boolean.TRUE.equals(ready)) {
        throw conflict("Assessment-only release requires the three complete, source-verified TRE paper sets. Tests remain draft until question/answer review passes.");
      }
      evidence.put("deliveryMode",deliveryMode);
      evidence.put("assessmentOnlyReady",true);
    } else {
      long subjectCount=number(evidence.get("subject_count"));
      long coveredSubjects=number(evidence.get("subjects_with_published_lessons"));
      if(subjectCount==0||coveredSubjects!=subjectCount||number(evidence.get("published_chapters"))==0
          ||number(evidence.get("published_lessons"))==0) {
        throw conflict("Every mapped active subject must have published, content-reviewed lesson content before release review.");
      }
    }

    List<Map<String,Object>> latestRows=jdbc.queryForList("""
        select id,release_version,release_state from institution_course_release
        where source_institution_id=? and program_id=?
        order by release_version desc limit 1 for update
        """,institutionId,programId);
    if(latestRows.isEmpty()) throw new SecurityException("This course has no institution-owned release.");
    Map<String,Object> latest=latestRows.getFirst();
    String state=String.valueOf(latest.get("release_state"));
    if(Set.of("IN_QUALITY_REVIEW","QUALITY_APPROVED").contains(state))
      throw conflict("This course already has a release in the quality review/approval workflow.");
    if(Set.of("SHAREABLE","WITHDRAWN","LEGACY_MIGRATED").contains(state))
      throw conflict("This release has already left the editable draft state. Create a separate draft content version before submitting an update.");

    List<Map<String,Object>> trustedRows=jdbc.queryForList("""
        select coalesce(trust.enabled,false) as trusted_auto_approval,
          trust.changed_by_staff_id as trusted_auto_approval_changed_by_staff_id,
          trust.changed_at as trusted_auto_approval_changed_at,
          trust.note as trusted_auto_approval_note
        from curriculum_program p
        join institution_course_identity i on i.id=p.course_identity_id
          and i.source_institution_id=? and i.status='ACTIVE'
        left join institution_course_trust_policy trust
          on trust.course_identity_id=i.id and trust.source_institution_id=i.source_institution_id
        where p.id=?
        """,institutionId,programId);
    boolean trustedAutoApproval=!trustedRows.isEmpty()
        && Boolean.TRUE.equals(trustedRows.getFirst().get("trusted_auto_approval"));
    Long trustedPolicyActorId=trustedAutoApproval
        && trustedRows.getFirst().get("trusted_auto_approval_changed_by_staff_id") instanceof Number n
        ? n.longValue() : null;
    String trustedPolicyNote=trustedAutoApproval
        ? String.valueOf(trustedRows.getFirst().getOrDefault("trusted_auto_approval_note","")) : "";
    if(trustedAutoApproval && trustedPolicyActorId==null)
      throw conflict("Trusted-course policy attribution is incomplete. Disable and re-enable the policy in HRMS before continuing.");

    String fingerprint=contentFingerprint(programId);
    evidence.put("capturedAt",Instant.now().toString());
    if(trustedAutoApproval) {
      evidence.put("approvalMode","TRUSTED_COURSE_AUTO_APPROVAL");
      evidence.put("trustedPolicyChangedAt",String.valueOf(trustedRows.getFirst().get("trusted_auto_approval_changed_at")));
      evidence.put("trustedPolicyNote",trustedPolicyNote);
      evidence.put("trustedPolicyActorStaffId",trustedPolicyActorId);
    }
    long releaseId;
    int version;
    if("DRAFT".equals(state)) {
      releaseId=((Number)latest.get("id")).longValue();
      version=((Number)latest.get("release_version")).intValue();
      if(trustedAutoApproval) {
        jdbc.update("""
            update institution_course_release set release_state='SHAREABLE',content_fingerprint=?,
              fingerprint_schema_version=2,
              submitted_by_institution_admin_id=?,submitted_by_staff_id=?,submitted_at=now(),
              quality_reviewed_by_staff_id=?,quality_reviewed_at=now(),quality_review_notes=?,
              origin_approved_by_admin_id=null,origin_approved_by_staff_id=null,
              origin_auto_approved_by_staff_id=?,origin_approval_mode='HRMS_TRUSTED_AUTO',
              origin_approved_at=now(),shareable_at=now(),
              origin_rejected_by_admin_id=null,origin_rejected_at=null,origin_rejection_reason=null,updated_at=now()
            where id=? and source_institution_id=? and release_state='DRAFT'
            """,fingerprint,actorAdminId,actorStaffId,trustedPolicyActorId,
            "Automatically approved under HRMS trusted-course policy. "+trustedPolicyNote,
            trustedPolicyActorId,releaseId,institutionId);
      } else {
        jdbc.update("""
            update institution_course_release set release_state='IN_QUALITY_REVIEW',content_fingerprint=?,
              fingerprint_schema_version=2,
              submitted_by_institution_admin_id=?,submitted_by_staff_id=?,submitted_at=now(),
              quality_reviewed_by_staff_id=null,quality_reviewed_at=null,quality_review_notes=null,
              origin_approved_by_admin_id=null,origin_approved_by_staff_id=null,
              origin_auto_approved_by_staff_id=null,origin_approval_mode='MANUAL',
              origin_approved_at=null,shareable_at=null,
              origin_rejected_by_admin_id=null,origin_rejected_at=null,origin_rejection_reason=null,updated_at=now()
            where id=? and source_institution_id=? and release_state='DRAFT'
            """,fingerprint,actorAdminId,actorStaffId,releaseId,institutionId);
      }
    } else {
      version=jdbc.queryForObject("""
          select coalesce(max(release_version),0)+1 from institution_course_release
          where source_institution_id=? and program_id=?
          """,Integer.class,institutionId,programId);
      if(trustedAutoApproval) {
        releaseId=jdbc.queryForObject("""
            insert into institution_course_release(
              source_institution_id,program_id,release_version,release_state,content_fingerprint,
              fingerprint_schema_version,submitted_by_institution_admin_id,submitted_by_staff_id,submitted_at,
              quality_reviewed_by_staff_id,quality_reviewed_at,quality_review_notes,
              origin_approved_by_admin_id,origin_approved_by_staff_id,origin_auto_approved_by_staff_id,
              origin_approval_mode,origin_approved_at,shareable_at,legacy_migration,created_at,updated_at
            ) values(?,?,?,'SHAREABLE',?,2,?,?,now(),?,now(),?,NULL,NULL,?,'HRMS_TRUSTED_AUTO',now(),now(),false,now(),now())
            returning id
            """,Long.class,institutionId,programId,version,fingerprint,actorAdminId,actorStaffId,
            trustedPolicyActorId,"Automatically approved under HRMS trusted-course policy. "+trustedPolicyNote,
            trustedPolicyActorId);
      } else {
        releaseId=jdbc.queryForObject("""
            insert into institution_course_release(
              source_institution_id,program_id,release_version,release_state,content_fingerprint,
              fingerprint_schema_version,submitted_by_institution_admin_id,submitted_by_staff_id,submitted_at,
              legacy_migration,created_at,updated_at
            ) values(?,?,?,'IN_QUALITY_REVIEW',?,2,?,?,now(),false,now(),now()) returning id
            """,Long.class,institutionId,programId,version,fingerprint,actorAdminId,actorStaffId);
      }
      jdbc.update("""
          insert into institution_course_assignment(
            institution_id,course_release_id,status,assignment_source,assigned_at,created_at,updated_at
          ) values(?,?,'ASSIGNED','ORIGIN',now(),now(),now())
          on conflict(institution_id,course_release_id) do update set status='ASSIGNED',
            assignment_source='ORIGIN',assigned_at=now(),revoked_by_staff_id=null,revoked_at=null,updated_at=now()
          """,institutionId,releaseId);
    }
    Long assignmentId=jdbc.queryForObject("""
        select id from institution_course_assignment
        where institution_id=? and course_release_id=? and status='ASSIGNED'
        """,Long.class,institutionId,releaseId);
    Map<String,Object> trustedDistribution=trustedAutoApproval
        ? trustedCourseDistribution.propagateTrustedCourseVersion(
            programId,releaseId,institutionId,trustedPolicyActorId,actorAdminId)
        : Map.of("autoPublishedOffers",0,"heldOffers",0,"skippedInstitutions",0,"learnerVersionGrants",0);
    evidence.put("trustedDistribution",trustedDistribution);
    if(trustedAutoApproval) {
      jdbc.update("""
        insert into institution_course_version_event(
          course_identity_id,source_program_id,version_program_id,content_version_no,event_type,
          actor_staff_id,actor_institution_admin_id,details
        )
        select p.course_identity_id,coalesce(p.supersedes_program_id,p.id),p.id,p.content_version_no,
          'VERSION_SHAREABLE',?, ?,?::jsonb
        from curriculum_program p
        where p.id=? and p.content_version_no>1
        on conflict(course_identity_id,version_program_id,event_type) do nothing
        """,trustedPolicyActorId,actorAdminId,json(evidence),programId);
    }
    recordEvent(institutionId,releaseId,assignmentId,
        trustedAutoApproval?"RELEASE_AUTO_APPROVED_TRUSTED_COURSE":"RELEASE_SUBMITTED",
        trustedAutoApproval?trustedPolicyActorId:actorStaffId,actorAdminId,evidence);
    return Map.of("submitted",true,"releaseId",releaseId,"programId",programId,
        "releaseVersion",version,"status",trustedAutoApproval?"SHAREABLE":"IN_QUALITY_REVIEW",
        "autoApprovedByTrustedCoursePolicy",trustedAutoApproval,"qualityEvidence",evidence,
        "trustedDistribution",trustedDistribution,
        "message",trustedAutoApproval
          ? "Content was automatically approved. Existing published destination offers were carried forward where their full access, rights and commercial readiness checks still pass; exceptions remain visible in HRMS for review."
          : "Release submitted for the standard HRMS content-quality workflow.");
  }


  @Transactional
  public Map<String,Object> approveRelease(long releaseId, long institutionId,
      Long actorStaffId, Long actorAdminId) {
    Map<String,Object> release=loadOriginRelease(releaseId,institutionId);
    if(!"QUALITY_APPROVED".equals(String.valueOf(release.get("release_state"))))
      throw conflict("Platform content-quality review must pass before origin-institution approval.");
    long courseId=((Number)release.get("program_id")).longValue();
    if(!String.valueOf(release.get("content_fingerprint")).trim().equals(contentFingerprint(courseId)))
      throw conflict("Course content changed after review. Submit a fresh release.");
    int changed=jdbc.update("""
        update institution_course_release set release_state='SHAREABLE',
          origin_approved_by_admin_id=?,origin_approved_by_staff_id=?,
          origin_approved_at=now(),shareable_at=now(),updated_at=now()
        where id=? and source_institution_id=? and release_state='QUALITY_APPROVED'
        """,actorAdminId,actorStaffId,releaseId,institutionId);
    if(changed!=1) throw conflict("The release changed during approval.");
    recordEvent(institutionId,releaseId,null,"RELEASE_ORIGIN_APPROVED",actorStaffId,actorAdminId,
        Map.of("programId",courseId,"actorWorkspace",actorStaffId==null?"INSTITUTION":"EDUCATION_ADMIN"));
    return Map.of("approved",true,"releaseId",releaseId,"programId",courseId,"status","SHAREABLE");
  }

  public List<Map<String,Object>> listSourceReleases(long institutionId) {
    return jdbc.queryForList("""
        select r.id as release_id,r.program_id as course_id,p.public_id,p.code as course_code,
          p.display_name,p.curriculum_version,r.release_version,r.release_state,r.content_fingerprint,
          r.legacy_migration,r.submitted_at,r.quality_reviewed_at,r.quality_review_notes,
          r.origin_approved_at,r.shareable_at,r.origin_rejection_reason
        from institution_course_release r join curriculum_program p on p.id=r.program_id
        where r.source_institution_id=?
        order by r.program_id,r.release_version desc,r.id desc
        """,institutionId);
  }

  private Map<String,Object> loadOriginRelease(long releaseId,long institutionId) {
    List<Map<String,Object>> rows=jdbc.queryForList("""
        select id,program_id,source_institution_id,release_version,release_state,content_fingerprint
        from institution_course_release
        where id=? and source_institution_id=? for update
        """,releaseId,institutionId);
    if(rows.isEmpty()) throw new SecurityException("This course release is not owned by the current institution.");
    return rows.getFirst();
  }

  private String contentFingerprint(long programId) {
    String value=jdbc.queryForObject("select course_content_fingerprint(?)",String.class,programId);
    return value==null?"d41d8cd98f00b204e9800998ecf8427e":value.trim();
  }

  private void recordEvent(long institutionId,Long releaseId,Long assignmentId,String type,
      Long actorStaffId,Long actorAdminId,Map<String,Object> details) {
    String json;
    try { json=objectMapper.writeValueAsString(details); }
    catch(JsonProcessingException ex) { throw new IllegalArgumentException("Lifecycle event details could not be serialized.",ex); }
    jdbc.update("""
        insert into institution_course_lifecycle_event(
          institution_id,course_release_id,assignment_id,offer_id,event_type,
          actor_platform_staff_id,actor_institution_admin_id,details
        ) values(?,?,?,?,?,?,?,?::jsonb)
        """,institutionId,releaseId,assignmentId,null,type,actorStaffId,actorAdminId,json);
  }

  private String json(Map<String,Object> value) {
    try { return objectMapper.writeValueAsString(value); }
    catch(JsonProcessingException ex) { throw new IllegalArgumentException("Lifecycle event details could not be serialized.",ex); }
  }

  private static long number(Object raw) {
    if(raw instanceof Number number) return number.longValue();
    try { return Long.parseLong(String.valueOf(raw)); }
    catch(RuntimeException ex) { throw new IllegalArgumentException("A numeric value was expected."); }
  }

  private static ResponseStatusException conflict(String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT,message);
  }
}
