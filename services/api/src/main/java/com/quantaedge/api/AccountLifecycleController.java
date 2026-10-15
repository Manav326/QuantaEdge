package com.quantaedge.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class AccountLifecycleController {
  private final JdbcTemplate jdbc;
  private final AuthorizationService authorization;
  private final StaffAuditService staffAudit;

  public AccountLifecycleController(JdbcTemplate jdbc, AuthorizationService authorization, StaffAuditService staffAudit) {
    this.jdbc=jdbc;
    this.authorization=authorization;
    this.staffAudit=staffAudit;
  }

  @PatchMapping("/admin/account-lifecycle/students/{studentId}/adult-verification")
  @Transactional
  public Map<String,Object> reviewAdultVerification(@PathVariable long studentId,@RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    context=reviewer(context);
    boolean adultOver18=Boolean.TRUE.equals(body.get("adultOver18Confirmed"));
    String reference=requiredReference(body.get("verificationReference"));
    List<Map<String,Object>> students=jdbc.queryForList("""
      select st.id,u.role from student st left join user_account u on u.id=st.user_account_id
      where st.id=? and st.active=true
      """,studentId);
    if(students.isEmpty()) throw new IllegalArgumentException("Active student profile not found.");
    Object role=students.getFirst().get("role");
    if(!adultOver18 && ("LEARNER".equals(role)||"PARENT".equals(role)))
      throw new SecurityException("A linked independent account cannot have its verified adult status silently downgraded.");
    String status=adultOver18?"VERIFIED":"REJECTED";
    String referenceHash=hashReference(reference);
    jdbc.update("""
      insert into student_adult_verification(student_id,status,verification_reference_hash,reviewed_by_staff_id,reviewed_at,expires_at,updated_at)
      values (?,?,?,?,now(),case when ? then now()+interval '180 days' else null end,now())
      on conflict(student_id) do update set status=excluded.status,
        verification_reference_hash=excluded.verification_reference_hash,reviewed_by_staff_id=excluded.reviewed_by_staff_id,
        reviewed_at=now(),expires_at=excluded.expires_at,updated_at=now()
      """,studentId,status,referenceHash,context.staffId(),adultOver18);
    jdbc.update("""
      insert into account_lifecycle_audit(event_type,student_id,actor_staff_id,verification_reference_hash,details)
      values ('ADULT_VERIFICATION_UPDATED',?,?,?,jsonb_build_object('status',?,'over18',?))
      """,studentId,context.staffId(),referenceHash,status,adultOver18);
    staffAudit.recordAction(context,"/api/v1/admin/account-lifecycle/students/"+studentId+"/adult-verification",
        "Reviewed student adult verification: "+status+".");
    return Map.of("studentId",studentId,"status",status,"expiresInDays",adultOver18?180:0);
  }

  @PatchMapping("/admin/account-lifecycle/users/{userId}/parent-eligibility")
  @Transactional
  public Map<String,Object> reviewParentEligibility(@PathVariable long userId,@RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    context=reviewer(context);
    boolean eligible=Boolean.TRUE.equals(body.get("eligible"));
    String reference=requiredReference(body.get("verificationReference"));
    List<Map<String,Object>> linked=jdbc.queryForList("""
      select u.role,st.id as student_id from user_account u
      join student st on st.user_account_id=u.id and st.active=true
      where u.id=? and u.active=true
      """,userId);
    if(linked.isEmpty() || !"LEARNER".equals(String.valueOf(linked.getFirst().get("role"))))
      throw new IllegalArgumentException("Only an active independent learner can be reviewed for parent eligibility.");
    long studentId=((Number)linked.getFirst().get("student_id")).longValue();
    if(eligible) {
      Long adultVerified=jdbc.queryForObject("""
        select count(*) from student_adult_verification
        where student_id=? and status='VERIFIED' and expires_at>now()
        """,Long.class,studentId);
      if(adultVerified==null || adultVerified==0)
        throw new SecurityException("Verified-over-18 status is required before parent eligibility can be approved.");
    }
    String status=eligible?"VERIFIED":"REJECTED";
    String referenceHash=hashReference(reference);
    jdbc.update("""
      insert into user_parent_eligibility(user_id,status,verification_reference_hash,reviewed_by_staff_id,reviewed_at,expires_at,updated_at)
      values (?,?,?,?,now(),case when ? then now()+interval '180 days' else null end,now())
      on conflict(user_id) do update set status=excluded.status,
        verification_reference_hash=excluded.verification_reference_hash,reviewed_by_staff_id=excluded.reviewed_by_staff_id,
        reviewed_at=now(),expires_at=excluded.expires_at,updated_at=now()
      """,userId,status,referenceHash,context.staffId(),eligible);
    jdbc.update("""
      insert into account_lifecycle_audit(event_type,student_id,user_id,actor_staff_id,verification_reference_hash,details)
      values ('PARENT_ELIGIBILITY_UPDATED',?,?,?,?,jsonb_build_object('status',?,'eligible',?))
      """,studentId,userId,context.staffId(),referenceHash,status,eligible);
    staffAudit.recordAction(context,"/api/v1/admin/account-lifecycle/users/"+userId+"/parent-eligibility",
        "Reviewed independent learner parent eligibility: "+status+".");
    return Map.of("userId",userId,"status",status,"expiresInDays",eligible?180:0);
  }

  @PostMapping("/account/me/transition-to-parent")
  @Transactional
  public Map<String,Object> transitionToParent(@RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    context=authorization.requireStudent(context);
    if(!Boolean.TRUE.equals(body.get("confirmParentTransition")))
      throw new SecurityException("Explicit confirmation is required to transition this account to parent.");
    long studentId=context.studentId();
    List<Map<String,Object>> rows=jdbc.queryForList("""
      select st.user_account_id,u.role
      from student st join user_account u on u.id=st.user_account_id and u.active=true
      join student_adult_verification av on av.student_id=st.id and av.status='VERIFIED' and av.expires_at>now()
      join user_parent_eligibility pe on pe.user_id=u.id and pe.status='VERIFIED' and pe.expires_at>now()
      where st.id=? and st.active=true and st.environment='PRODUCTION'
      for update of st,u
      """,studentId);
    if(rows.isEmpty()) throw new SecurityException("Verified adult status and parent eligibility are both required.");
    Map<String,Object> row=rows.getFirst();
    long userId=((Number)row.get("user_account_id")).longValue();
    if(!"LEARNER".equals(String.valueOf(row.get("role"))))
      throw new IllegalStateException("Only an independent learner account may transition to parent.");
    int changed=jdbc.update("update user_account set role='PARENT',updated_at=now() where id=? and role='LEARNER' and active=true",userId);
    if(changed!=1) throw new IllegalStateException("Account role changed concurrently; sign in again.");
    jdbc.update("update auth_session set revoked_at=now() where revoked_at is null and (student_id=? or user_id=?)",studentId,userId);
    jdbc.update("""
      insert into account_lifecycle_audit(event_type,student_id,user_id,actor_user_id,actor_student_id,old_role,new_role,details)
      values ('LEARNER_BECAME_PARENT',?,?,?,?, 'LEARNER','PARENT',
        jsonb_build_object('sameUserAccount',true,'sameStudentProfile',true))
      """,studentId,userId,userId,studentId);
    return Map.of("transitioned",true,"role","PARENT","userId",userId,"requiresReauthentication",true);
  }

  @GetMapping("/account/me/lifecycle")
  public Map<String,Object> lifecycle(@RequestAttribute(value="authContext",required=false) AuthContext context) {
    context=authorization.requireStudent(context);
    List<Map<String,Object>> rows=jdbc.queryForList("""
      select st.user_account_id,u.role,av.status as adult_verification_status,av.expires_at as adult_verification_expires_at,
        pe.status as parent_eligibility_status,pe.expires_at as parent_eligibility_expires_at
      from student st left join user_account u on u.id=st.user_account_id
      left join student_adult_verification av on av.student_id=st.id
      left join user_parent_eligibility pe on pe.user_id=u.id
      where st.id=?
      """,context.studentId());
    if(rows.isEmpty()) throw new SecurityException("Student profile not found.");
    Map<String,Object> result=new LinkedHashMap<>(rows.getFirst());
    result.put("independentAccountLinked",result.get("user_account_id")!=null);
    result.put("canTransitionToParent",
      "LEARNER".equals(String.valueOf(result.get("role")))
        && "VERIFIED".equals(String.valueOf(result.get("adult_verification_status")))
        && "VERIFIED".equals(String.valueOf(result.get("parent_eligibility_status"))));
    return result;
  }

  private AuthContext reviewer(AuthContext context) {
    context=authorization.requirePermission(context,"ACCOUNT_LIFECYCLE_REVIEW");
    if(context.staffId()==null) throw new SecurityException("Authorized staff review access is required.");
    return context;
  }

  private String requiredReference(Object value) {
    String reference=value==null?"":String.valueOf(value).trim();
    if(reference.length()<4 || reference.length()>200 || reference.chars().anyMatch(Character::isISOControl))
      throw new IllegalArgumentException("A valid external verification reference is required.");
    return reference;
  }

  private String hashReference(String reference) {
    try {
      byte[] digest=MessageDigest.getInstance("SHA-256").digest(reference.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch(Exception ex) { throw new IllegalStateException("Unable to hash verification reference.",ex); }
  }
}
