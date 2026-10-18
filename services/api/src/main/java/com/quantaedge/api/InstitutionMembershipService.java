package com.quantaedge.api;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the account-level and learner-profile-level membership facts.
 * A selected tenant is not a membership; only signup/onboarding or the institution join flow
 * may establish one. Suspended/left relationships never get silently reactivated here.
 */
@Service
public class InstitutionMembershipService {
  private final JdbcTemplate jdbc;

  public InstitutionMembershipService(JdbcTemplate jdbc) {
    this.jdbc=jdbc;
  }

  @Transactional
  public void establishForNewAccount(AuthContext context,InstitutionTenantContext tenant,String source) {
    if(context==null || tenant==null || tenant.institutionId()<1) {
      throw missingInstitution();
    }
    requireActiveInstitution(tenant.institutionId());
    if(context.isParent()) {
      ensureUserMembership(context.userId(),tenant.institutionId(),source,null);
      return;
    }
    if(context.isStudent()) {
      ensureStudentMembership(context.studentId(),tenant.institutionId(),source,context.userId());
      return;
    }
    throw new SecurityException("Only a learner or parent account can join an institution through signup.");
  }

  @Transactional
  public void ensureStudentMembership(long studentId,long institutionId,String source,Long actorUserId) {
    requireActiveInstitution(institutionId);
    List<Map<String,Object>> learner=jdbc.queryForList("""
      select id,user_account_id,active,environment from student where id=? for update
      """,studentId);
    if(learner.isEmpty() || !Boolean.TRUE.equals(learner.getFirst().get("active"))
        || !"PRODUCTION".equals(String.valueOf(learner.getFirst().get("environment")))) {
      throw new SecurityException("An active production learner profile is required.");
    }
    List<Map<String,Object>> previous=jdbc.queryForList("""
      select id,status from institution_membership
      where institution_id=? and student_id=? for update
      """,institutionId,studentId);
    if(!previous.isEmpty()) {
      String status=String.valueOf(previous.getFirst().get("status"));
      if(!"ACTIVE".equals(status)) {
        throw new SecurityException("This learner's institution membership is not active. Contact the institution administrator.");
      }
    } else {
      Long membershipId=jdbc.queryForObject("""
        insert into institution_membership(
          institution_id,student_id,join_code_id,status,joined_at,left_at,created_at,updated_at
        ) values(?,?,null,'ACTIVE',now(),null,now(),now())
        returning id
        """,Long.class,institutionId,studentId);
      jdbc.update("""
        insert into institution_membership_event(
          institution_id,membership_id,student_id,event_type,details
        ) values(?,?,?,'MEMBERSHIP_CREATED',jsonb_build_object('source',?,'actorUserId',?))
        """,institutionId,membershipId,studentId,normalSource(source),actorUserId);
    }
    Object rawUserId=learner.getFirst().get("user_account_id");
    if(rawUserId instanceof Number n) {
      ensureUserMembership(n.longValue(),institutionId,source,actorUserId);
    }
  }

  @Transactional
  public void ensureUserMembership(long userId,long institutionId,String source,Long actorUserId) {
    requireActiveInstitution(institutionId);
    List<Map<String,Object>> account=jdbc.queryForList("""
      select id,active from user_account where id=? for update
      """,userId);
    if(account.isEmpty() || !Boolean.TRUE.equals(account.getFirst().get("active"))) {
      throw new SecurityException("An active account is required for institution membership.");
    }
    List<Map<String,Object>> previous=jdbc.queryForList("""
      select id,status from institution_user_membership
      where institution_id=? and user_id=? for update
      """,institutionId,userId);
    if(!previous.isEmpty()) {
      String status=String.valueOf(previous.getFirst().get("status"));
      if(!"ACTIVE".equals(status)) {
        throw new SecurityException("This account's membership is not active in the selected institution.");
      }
      return;
    }
    Long membershipId=jdbc.queryForObject("""
      insert into institution_user_membership(
        institution_id,user_id,status,joined_at,left_at,created_at,updated_at
      ) values(?,?,'ACTIVE',now(),null,now(),now())
      returning id
      """,Long.class,institutionId,userId);
    jdbc.update("""
      insert into institution_user_membership_event(
        institution_id,membership_id,user_id,event_type,actor_user_id,details
      ) values(?,?,?,'USER_MEMBERSHIP_CREATED',?,jsonb_build_object('source',?))
      """,institutionId,membershipId,userId,actorUserId,normalSource(source));
  }

  @Transactional
  public void requireExistingMembership(AuthContext context,InstitutionTenantContext tenant) {
    if(context==null || tenant==null) throw missingInstitution();
    if(context.isStudent()) {
      Boolean member=jdbc.queryForObject("""
        select exists(select 1 from institution_membership m
          join institution_tenant t on t.id=m.institution_id and t.status='ACTIVE'
          join student st on st.id=m.student_id and st.active=true
          where m.institution_id=? and m.student_id=? and m.status='ACTIVE')
        """,Boolean.class,tenant.institutionId(),context.studentId());
      if(Boolean.TRUE.equals(member)) return;

      // A legacy account may already have an audited institution-user membership while its
      // learner profile was created or repaired later. Mirror only that same institution
      // relationship; never infer membership from another tenant or from the login selector.
      List<Map<String,Object>> profile=jdbc.queryForList("""
        select user_account_id from student
        where id=? and active=true and environment='PRODUCTION'
        """,context.studentId());
      if(!profile.isEmpty() && profile.getFirst().get("user_account_id") instanceof Number userId) {
        Boolean accountAlreadyMember=jdbc.queryForObject("""
          select exists(select 1 from institution_user_membership um
            join institution_tenant t on t.id=um.institution_id and t.status='ACTIVE'
            join user_account u on u.id=um.user_id and u.active=true
            where um.institution_id=? and um.user_id=? and um.status='ACTIVE')
          """,Boolean.class,tenant.institutionId(),userId.longValue());
        if(Boolean.TRUE.equals(accountAlreadyMember)) {
          ensureStudentMembership(context.studentId(),tenant.institutionId(),
              "EXISTING_ACCOUNT_PROFILE_RECONCILIATION",userId.longValue());
          return;
        }
      }
      throw missingMembership();
    }
    if(context.isParent()) {
      Boolean member=jdbc.queryForObject("""
        select exists(select 1 from institution_user_membership m
          join institution_tenant t on t.id=m.institution_id and t.status='ACTIVE'
          join user_account u on u.id=m.user_id and u.active=true
          where m.institution_id=? and m.user_id=? and m.status='ACTIVE')
        """,Boolean.class,tenant.institutionId(),context.userId());
      if(!Boolean.TRUE.equals(member)) throw missingMembership();
      return;
    }
    throw missingMembership();
  }

  private void requireActiveInstitution(long institutionId) {
    Boolean active=jdbc.queryForObject("""
      select exists(select 1 from institution_tenant where id=? and status='ACTIVE')
      """,Boolean.class,institutionId);
    if(!Boolean.TRUE.equals(active)) {
      throw new InstitutionTenantContextService.InvalidInstitutionContextException(
          403,"INSTITUTION_UNAVAILABLE","The selected institution is unavailable.");
    }
  }

  private static String normalSource(String source) {
    if(source==null || source.isBlank()) return "INSTITUTION_SIGNUP";
    return source.length()>80?source.substring(0,80):source;
  }

  private InstitutionTenantContextService.InvalidInstitutionContextException missingInstitution() {
    return new InstitutionTenantContextService.InvalidInstitutionContextException(
        400,"INSTITUTION_CONTEXT_REQUIRED","An institution is required for account creation and access.");
  }

  private InstitutionTenantContextService.InvalidInstitutionContextException missingMembership() {
    return new InstitutionTenantContextService.InvalidInstitutionContextException(
        403,"INSTITUTION_MEMBERSHIP_REQUIRED","This account has no active membership in the selected institution.");
  }
}
