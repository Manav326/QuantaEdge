package com.quantaedge.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TrustedCourseVersionDistributionService {
  private final JdbcTemplate jdbc;
  private final CourseMarketplaceLaunchReadinessService launchReadiness;
  private final CourseVersionLearnerMigrationService versionLearnerMigration;
  private final ObjectMapper objectMapper;

  public TrustedCourseVersionDistributionService(JdbcTemplate jdbc,
      CourseMarketplaceLaunchReadinessService launchReadiness,
      CourseVersionLearnerMigrationService versionLearnerMigration,
      ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.launchReadiness = launchReadiness;
    this.versionLearnerMigration = versionLearnerMigration;
    this.objectMapper = objectMapper;
  }

  @Transactional
  private Map<String,Object> propagateTrustedCourseVersion(
      long programId,long releaseId,long sourceInstitutionId,long policyActorStaffId,Long submittingAdminId) {
    Map<String,Object> version=jdbc.queryForMap("""
        select p.course_identity_id,p.content_version_no,p.supersedes_program_id
        from curriculum_program p
        where p.id=? and p.course_identity_id is not null
        """,programId);
    long identityId=number(version.get("course_identity_id"));
    int versionNo=(int)number(version.get("content_version_no"));
    List<Map<String,Object>> priorOffers=jdbc.queryForList("""
        select distinct on (o.institution_id)
          o.id as prior_offer_id,o.institution_id,o.assignment_id as prior_assignment_id,
          o.course_id as prior_course_id,o.title_override,o.description,o.price_paise,o.currency,
          old_program.content_version_no as prior_content_version_no
        from institution_marketplace_offer o
        join institution_course_assignment old_assignment
          on old_assignment.id=o.assignment_id and old_assignment.institution_id=o.institution_id
             and old_assignment.status='ASSIGNED'
        join institution_course_release old_release
          on old_release.id=o.course_release_id and old_release.program_id=o.course_id
             and old_release.release_state='SHAREABLE'
             and old_release.content_fingerprint=course_content_fingerprint(old_release.program_id)
        join curriculum_program old_program on old_program.id=o.course_id
          and old_program.course_identity_id=? and old_program.content_version_no<?
        join institution_tenant tenant on tenant.id=o.institution_id and tenant.status='ACTIVE'
        where o.status='PUBLISHED' and o.hrms_published_by_staff_id is not null
        order by o.institution_id,old_program.content_version_no desc,o.id desc
        """,identityId,versionNo);

    int autoPublished=0,held=0,skipped=0,learnerGrants=0;
    List<Map<String,Object>> destinationResults=new ArrayList<>();
    for(Map<String,Object> previous:priorOffers) {
      long tenantId=number(previous.get("institution_id"));
      long priorOfferId=number(previous.get("prior_offer_id"));
      List<Map<String,Object>> assignments=jdbc.queryForList("""
          select id,status from institution_course_assignment
          where institution_id=? and course_release_id=?
          for update
          """,tenantId,releaseId);
      long assignmentId;
      if(!assignments.isEmpty()) {
        if(!"ASSIGNED".equals(text(assignments.getFirst().get("status")))) {
          skipped++;
          recordEvent(tenantId,releaseId,number(assignments.getFirst().get("id")),null,
              "TRUSTED_UPDATE_ASSIGNMENT_SKIPPED",policyActorStaffId,null,
              Map.of("reason","An HRMS-revoked assignment is never reactivated by trusted-course automation.",
                "priorOfferId",priorOfferId));
          destinationResults.add(Map.of("institutionId",tenantId,"status","SKIPPED_REVOKED_ASSIGNMENT"));
          continue;
        }
        assignmentId=number(assignments.getFirst().get("id"));
      } else {
        String assignmentSource=tenantId==sourceInstitutionId?"ORIGIN":"HRMS";
        Long assignedBy=tenantId==sourceInstitutionId?null:policyActorStaffId;
        assignmentId=jdbc.queryForObject("""
            insert into institution_course_assignment(
              institution_id,course_release_id,status,assignment_source,assigned_by_staff_id,
              assigned_at,created_at,updated_at
            ) values(?,?,'ASSIGNED',?,?,now(),now(),now()) returning id
            """,Long.class,tenantId,releaseId,assignmentSource,assignedBy);
      }

      List<Map<String,Object>> existingOffer=jdbc.queryForList("""
          select id,status from institution_marketplace_offer
          where institution_id=? and assignment_id=?
          for update
          """,tenantId,assignmentId);
      if(!existingOffer.isEmpty()) {
        // Never overwrite an offer HRMS has already started configuring for the new version.
        skipped++;
        destinationResults.add(Map.of("institutionId",tenantId,
            "status","EXISTING_OFFER_PRESERVED","offerId",number(existingOffer.getFirst().get("id"))));
        continue;
      }

      long offerId=jdbc.queryForObject("""
          insert into institution_marketplace_offer(
            institution_id,assignment_id,course_release_id,course_id,title_override,description,
            price_paise,currency,status,created_at,updated_at
          ) values(?,?,?,?,?,?,?,?,'DRAFT',now(),now()) returning id
          """,Long.class,tenantId,assignmentId,releaseId,programId,
          previous.get("title_override"),previous.get("description"),
          number(previous.get("price_paise"),0L),text(previous.get("currency")).isBlank()?"INR":text(previous.get("currency")));

      String continuityBlock="";
      if(number(previous.get("price_paise"),0L)>0) {
        copyCurrentTaxProfile(priorOfferId,offerId,policyActorStaffId);
        copyCurrentRevenueSharePolicy(priorOfferId,offerId,policyActorStaffId);
        continuityBlock=copySafeDiscounts(priorOfferId,offerId,policyActorStaffId);
      }
      if(!continuityBlock.isBlank()) {
        jdbc.update("""
            update institution_marketplace_offer
            set trusted_update_block_reason=?,updated_at=now()
            where id=? and institution_id=? and status='DRAFT'
            """,continuityBlock,offerId,tenantId);
      }

      Map<String,Object> readinessResult=launchReadiness.inspect(offerId,tenantId);
      boolean ready=Boolean.TRUE.equals(readinessResult.get("ready"));
      boolean canAutoPublish=ready&&continuityBlock.isBlank();
      List<String> blockers=readinessBlockers(readinessResult);
      Map<String,Object> eventDetails=new LinkedHashMap<>();
      eventDetails.put("trustedPolicyActorStaffId",policyActorStaffId);
      eventDetails.put("priorOfferId",priorOfferId);
      eventDetails.put("sourceCourseIdentityId",identityId);
      eventDetails.put("sourceContentVersionNo",versionNo);
      eventDetails.put("readinessBlockers",blockers);
      eventDetails.put("commercialContinuityBlock",continuityBlock);

      if(canAutoPublish) {
        int changed=jdbc.update("""
            update institution_marketplace_offer
            set status='PUBLISHED',approved_at=now(),published_at=now(),
                hrms_approved_by_staff_id=?,hrms_approved_at=now(),
                hrms_published_by_staff_id=?,hrms_published_at=now(),
                trusted_update_block_reason=null,updated_at=now()
            where id=? and institution_id=? and assignment_id=? and status='DRAFT'
            """,policyActorStaffId,policyActorStaffId,offerId,tenantId,assignmentId);
        if(changed!=1) throw conflict("A trusted version offer changed during atomic publication; retry after refreshing the launch checklist.");
        eventDetails.put("autoPublished",true);
        recordEvent(tenantId,releaseId,assignmentId,offerId,"OFFER_APPROVED",policyActorStaffId,null,eventDetails);
        recordEvent(tenantId,releaseId,assignmentId,offerId,"OFFER_PUBLISHED",policyActorStaffId,null,eventDetails);
        int grants=versionLearnerMigration.grantExistingLearnersToVersion(tenantId,identityId,versionNo,programId);
        learnerGrants+=grants;
        autoPublished++;
        destinationResults.add(Map.of("institutionId",tenantId,"offerId",offerId,
            "status","PUBLISHED","learnerVersionGrants",grants));
      } else {
        eventDetails.put("autoPublished",false);
        recordEvent(tenantId,releaseId,assignmentId,offerId,"TRUSTED_UPDATE_OFFER_HELD",
            policyActorStaffId,null,eventDetails);
        held++;
        destinationResults.add(Map.of("institutionId",tenantId,"offerId",offerId,
            "status","DRAFT_REVIEW_REQUIRED","blockingIssues",blockers,
            "commercialContinuityBlock",continuityBlock));
      }
    }

    Map<String,Object> result=new LinkedHashMap<>();
    result.put("priorPublishedInstitutions",priorOffers.size());
    result.put("autoPublishedOffers",autoPublished);
    result.put("heldOffers",held);
    result.put("skippedInstitutions",skipped);
    result.put("learnerVersionGrants",learnerGrants);
    result.put("destinations",destinationResults);
    return result;
  }

  private void copyCurrentTaxProfile(long priorOfferId,long newOfferId,long policyActorStaffId) {
    List<Map<String,Object>> profiles=jdbc.queryForList("""
        select id,legal_entity_id,service_classification_code,tax_treatment,gst_rate_basis_points,
          cess_rate_basis_points,price_includes_tax,place_of_supply_rule,rounding_rule,
          tax_components::text as tax_components_json,effective_until,approved_by_staff_id,approved_at
        from course_tax_profile
        where institution_offer_id=? and status='APPROVED' and effective_from<=now()
          and (effective_until is null or effective_until>now())
        order by version desc,id desc limit 2
        """,priorOfferId);
    if(profiles.size()!=1) return;
    Map<String,Object> profile=profiles.getFirst();
    jdbc.update("""
        insert into course_tax_profile(
          institution_offer_id,legal_entity_id,version,service_classification_code,tax_treatment,
          gst_rate_basis_points,cess_rate_basis_points,price_includes_tax,place_of_supply_rule,
          rounding_rule,tax_components,status,effective_from,effective_until,
          created_by_staff_id,approved_by_staff_id,approved_at,created_at
        ) values(?,?,1,?,?,?,?,?,?,?,?::jsonb,'APPROVED',now(),?,?,?,?,now())
        """,newOfferId,profile.get("legal_entity_id"),profile.get("service_classification_code"),
        profile.get("tax_treatment"),profile.get("gst_rate_basis_points"),profile.get("cess_rate_basis_points"),
        profile.get("price_includes_tax"),profile.get("place_of_supply_rule"),profile.get("rounding_rule"),
        text(profile.get("tax_components_json")),profile.get("effective_until"),policyActorStaffId,
        profile.get("approved_by_staff_id"),profile.get("approved_at"));
  }

  private void copyCurrentRevenueSharePolicy(long priorOfferId,long newOfferId,long policyActorStaffId) {
    List<Map<String,Object>> policies=jdbc.queryForList("""
        select id,currency,effective_until,contractual_reference,notes,approved_by_staff_id,approved_at
        from revenue_share_policy
        where institution_offer_id=? and status='APPROVED' and effective_from<=now()
          and (effective_until is null or effective_until>now())
        order by policy_version desc,id desc limit 2
        """,priorOfferId);
    if(policies.size()!=1) return;
    Map<String,Object> policy=policies.getFirst();
    long oldPolicyId=number(policy.get("id"));
    String oldNotes=text(policy.get("notes"));
    String inheritedNote="Inherited unchanged from published offer "+priorOfferId+" under the HRMS trusted-course version policy.";
    String notes=(oldNotes.isBlank()?inheritedNote:oldNotes+" | "+inheritedNote);
    if(notes.length()>2000) notes=notes.substring(0,2000);
    long newPolicyId=jdbc.queryForObject("""
        insert into revenue_share_policy(
          institution_offer_id,policy_version,currency,status,effective_from,effective_until,
          contractual_reference,notes,created_by_staff_id,approved_by_staff_id,approved_at,created_at
        ) values(?,1,?,'APPROVED',now(),?,?,?,?,?,?,now()) returning id
        """,Long.class,newOfferId,policy.get("currency"),policy.get("effective_until"),
        policy.get("contractual_reference"),notes,policyActorStaffId,
        policy.get("approved_by_staff_id"),policy.get("approved_at"));
    jdbc.update("""
        insert into revenue_share_policy_recipient(
          policy_id,institution_id,calculation_type,percentage_basis_points,flat_amount_paise
        )
        select ?,institution_id,calculation_type,percentage_basis_points,flat_amount_paise
        from revenue_share_policy_recipient where policy_id=?
        """,newPolicyId,oldPolicyId);
  }

  private String copySafeDiscounts(long priorOfferId,long newOfferId,long policyActorStaffId) {
    List<Map<String,Object>> discounts=jdbc.queryForList("""
        select d.id,d.code,d.version,d.currency,d.discount_type,d.percentage_basis_points,
          d.flat_amount_paise,d.max_discount_paise,d.min_listed_price_paise,d.max_redemptions,
          d.max_redemptions_per_purchaser,d.effective_until,d.approved_by_staff_id,d.approved_at,
          (select count(*) from course_discount_redemption red
            where red.discount_offer_id=d.id and red.status in ('RESERVED','REDEEMED')) as used_count
        from course_discount_offer d
        where d.institution_offer_id=? and d.status='APPROVED'
          and d.effective_from<=now() and (d.effective_until is null or d.effective_until>now())
        order by d.code,d.version desc,d.id desc
        """,priorOfferId);
    Map<String,Object> restricted=discounts.stream()
        .filter(d->d.get("max_redemptions_per_purchaser")!=null)
        .findFirst().orElse(null);
    if(restricted!=null) {
      return "Prior offer discount "+text(restricted.get("code"))
          +" has a per-purchaser redemption cap. HRMS must review and carry forward that redemption policy for the new version before it can auto-publish.";
    }
    for(Map<String,Object> discount:discounts) {
      Object maximum=discount.get("max_redemptions");
      long used=number(discount.get("used_count"),0L);
      if(maximum instanceof Number cap && cap.longValue()-used<=0L) continue;
      Object remaining=maximum instanceof Number cap?Math.max(0L,cap.longValue()-used):null;
      jdbc.update("""
          insert into course_discount_offer(
            institution_offer_id,code,version,currency,discount_type,percentage_basis_points,
            flat_amount_paise,max_discount_paise,min_listed_price_paise,max_redemptions,
            max_redemptions_per_purchaser,effective_from,effective_until,status,
            created_by_staff_id,approved_by_staff_id,approved_at,created_at,updated_at
          ) values(?,?,1,?,?,?,?,?,?,?,null,now(),?,'APPROVED',?,?,?,now(),now())
          """,newOfferId,discount.get("code"),discount.get("currency"),discount.get("discount_type"),
          discount.get("percentage_basis_points"),discount.get("flat_amount_paise"),
          discount.get("max_discount_paise"),discount.get("min_listed_price_paise"),
          remaining,discount.get("effective_until"),policyActorStaffId,
          discount.get("approved_by_staff_id"),discount.get("approved_at"));
    }
    return "";
  }

  @SuppressWarnings("unchecked")
  private List<String> readinessBlockers(Map<String,Object> readinessResult) {
    Object raw=readinessResult.get("blockingIssues");
    if(raw instanceof List<?> rows) return rows.stream().map(String::valueOf).toList();
    return List.of();
  }

  private void recordEvent(long institutionId,Long releaseId,Long assignmentId,Long offerId,
      String eventType,Long actorStaffId,Long actorAdminId,Map<String,Object> details) {
    String json;
    try { json=objectMapper.writeValueAsString(details); }
    catch(JsonProcessingException ex) { throw new IllegalArgumentException("Trusted-course audit details could not be serialized.",ex); }
    jdbc.update("""
        insert into institution_course_lifecycle_event(
          institution_id,course_release_id,assignment_id,offer_id,event_type,
          actor_platform_staff_id,actor_institution_admin_id,details
        ) values(?,?,?,?,?,?,?,?::jsonb)
        """,institutionId,releaseId,assignmentId,offerId,eventType,actorStaffId,actorAdminId,json);
  }

  private String json(Map<String,Object> value) {
    try { return objectMapper.writeValueAsString(value); }
    catch(JsonProcessingException ex) { throw new IllegalArgumentException("Trusted-course audit details could not be serialized.",ex); }
  }

  private static long number(Object raw) {
    if(raw instanceof Number number) return number.longValue();
    try { return Long.parseLong(String.valueOf(raw)); }
    catch(RuntimeException ex) { throw new IllegalArgumentException("A numeric value was expected."); }
  }

  private static long number(Object raw,long fallback) {
    return raw==null?fallback:number(raw);
  }

  private static String text(Object raw) { return raw==null?"":String.valueOf(raw); }

  private static ResponseStatusException conflict(String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT,message);
  }
}
