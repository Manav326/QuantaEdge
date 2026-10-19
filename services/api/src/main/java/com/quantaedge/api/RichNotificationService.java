package com.quantaedge.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Durable notification business operations. PostgreSQL recipient rows are the source of truth;
 * provider channels must never be required for an in-app notification to exist.
 */
@Service
public class RichNotificationService {
  private static final int MAX_RECIPIENTS = 10000;

  public record Recipient(String kind, long identityId, Long institutionId) {}
  public record Actor(String workspace, Long staffId, Long institutionAdminId, Long institutionId) {}

  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;
  private final NotificationContentValidator validator;
  private final AuthorizationService authorization;

  public RichNotificationService(JdbcTemplate jdbc, ObjectMapper mapper, NotificationContentValidator validator,
      AuthorizationService authorization) {
    this.jdbc = jdbc;
    this.mapper = mapper;
    this.validator = validator;
    this.authorization = authorization;
  }

  public JsonNode validateDocument(Object raw) {
    return validator.validate(raw);
  }

  public List<Map<String,Object>> listTemplates(Actor actor) {
    String sql;
    Object[] args;
    if ("HRMS".equals(actor.workspace())) {
      sql = """
        select id,public_id,template_family_id,owner_scope,institution_id,template_name,description,content_version,
          content_document::text as content_document,status,created_at,updated_at
        from notification_template where owner_scope='PLATFORM' and status<>'ARCHIVED'
        order by lower(template_name),content_version desc
        """;
      args = new Object[0];
    } else {
      sql = """
        select id,public_id,template_family_id,owner_scope,institution_id,template_name,description,content_version,
          content_document::text as content_document,status,created_at,updated_at
        from notification_template
        where status<>'ARCHIVED' and
          ((owner_scope='INSTITUTION' and institution_id=?) or owner_scope='PLATFORM')
        order by owner_scope desc,lower(template_name),content_version desc
        """;
      args = new Object[]{actor.institutionId()};
    }
    List<Map<String,Object>> result = new ArrayList<>();
    for (Map<String,Object> row : jdbc.queryForList(sql,args)) result.add(templateView(row));
    return result;
  }

  @Transactional
  public Map<String,Object> createTemplate(Actor actor, String name, String description, Object rawDocument) {
    String templateName = requiredText(name,"Template name",120);
    String detail = optionalText(description,500);
    JsonNode document = validator.validate(rawDocument);
    String ownerScope = "HRMS".equals(actor.workspace()) ? "PLATFORM" : "INSTITUTION";
    Long institutionId = "PLATFORM".equals(ownerScope) ? null : actor.institutionId();
    if (institutionId == null && "INSTITUTION".equals(ownerScope)) {
      throw new SecurityException("Select an institution before saving an institution template.");
    }
    String json = writeJson(document);
    Long id = jdbc.queryForObject("""
      insert into notification_template(
        owner_scope,institution_id,template_name,description,content_version,content_document,status,
        created_by_staff_id,created_by_institution_admin_id,updated_by_staff_id,updated_by_institution_admin_id)
      values (?,?,?,?,1,?::jsonb,'ACTIVE',?,?,?,?) returning id
      """,Long.class,ownerScope,institutionId,templateName,emptyToNull(detail),json,
      actor.staffId(),actor.institutionAdminId(),actor.staffId(),actor.institutionAdminId());
    audit(actor,institutionId,null,id,"TEMPLATE_CREATED",Map.of("templateName",templateName,"version",1));
    return getTemplateForActor(actor,id);
  }

  @Transactional
  public Map<String,Object> updateTemplate(Actor actor,long templateId,String name,String description,Object rawDocument) {
    Map<String,Object> existing = findTemplateRow(templateId);
    assertTemplateEditable(actor,existing);
    if(!"ACTIVE".equals(String.valueOf(existing.get("status")))) {
      throw new IllegalArgumentException("Only the current active template version can be edited. Open the latest version first.");
    }
    String familyId = String.valueOf(existing.get("template_family_id"));
    Integer latestVersion = jdbc.queryForObject(
      "select max(content_version) from notification_template where template_family_id=?::uuid",
      Integer.class,familyId);
    int existingVersion = ((Number)existing.get("content_version")).intValue();
    if(latestVersion==null||latestVersion.intValue()!=existingVersion) {
      throw new IllegalArgumentException("This template version is out of date. Reload the library and edit the latest version.");
    }
    String templateName = requiredText(name,"Template name",120);
    String detail = optionalText(description,500);
    JsonNode document = validator.validate(rawDocument);
    int version = existingVersion+1;
    String json=writeJson(document);
    Long id=jdbc.queryForObject("""
      insert into notification_template(
        template_family_id,owner_scope,institution_id,template_name,description,content_version,
        content_document,status,created_by_staff_id,created_by_institution_admin_id,
        updated_by_staff_id,updated_by_institution_admin_id)
      values (?::uuid,?,?,?,?,?,?::jsonb,'ACTIVE',?,?,?,?) returning id
      """,Long.class,familyId,
      String.valueOf(existing.get("owner_scope")),existing.get("institution_id"),templateName,
      emptyToNull(detail),version,json,actor.staffId(),actor.institutionAdminId(),
      actor.staffId(),actor.institutionAdminId());
    // The older version is retained, and only its selectable status changes.
    jdbc.update("update notification_template set status='ARCHIVED',updated_at=now() where id=?",templateId);
    audit(actor,actor.institutionId(),null,id,"TEMPLATE_VERSION_CREATED",
        Map.of("familyId",existing.get("template_family_id"),"previousVersion",version-1,"version",version));
    return getTemplateForActor(actor,id);
  }

  public Map<String,Object> getTemplateForActor(Actor actor,long templateId) {
    Map<String,Object> row=findTemplateRow(templateId);
    assertTemplateVisible(actor,row);
    return templateView(row);
  }

  private Map<String,Object> findTemplateRow(long id) {
    List<Map<String,Object>> rows=jdbc.queryForList("""
      select id,public_id,template_family_id,owner_scope,institution_id,template_name,description,
        content_version,content_document::text as content_document,status,created_by_staff_id,
        created_by_institution_admin_id,created_at,updated_at
      from notification_template where id=?
      """,id);
    if(rows.isEmpty()) throw new IllegalArgumentException("Notification template was not found.");
    return rows.getFirst();
  }

  private void assertTemplateVisible(Actor actor,Map<String,Object> row) {
    String owner=String.valueOf(row.get("owner_scope"));
    if("PLATFORM".equals(owner)) return;
    if(actor.institutionId()==null || !(row.get("institution_id") instanceof Number n)
        || n.longValue()!=actor.institutionId()) {
      throw new SecurityException("This notification template belongs to another institution.");
    }
  }

  private void assertTemplateEditable(Actor actor,Map<String,Object> row) {
    assertTemplateVisible(actor,row);
    if("HRMS".equals(actor.workspace()) && !"PLATFORM".equals(String.valueOf(row.get("owner_scope")))) {
      throw new SecurityException("HRMS template editing is limited to platform templates.");
    }
    if(!"HRMS".equals(actor.workspace()) && "PLATFORM".equals(String.valueOf(row.get("owner_scope")))) {
      throw new SecurityException("Platform templates are read-only in this workspace.");
    }
  }

  /**
   * Resolves the audience through current tenant memberships and course assignment/enrolment
   * contracts. Browser-provided counts are never trusted.
   */
  public List<Recipient> resolveAudience(
      String scope, Long institutionId, Long courseId, Map<String,Object> filter) {
    String normalized = scope == null ? "" : scope.trim().toUpperCase(Locale.ROOT);
    Set<String> types = stringSet(filter.get("recipientTypes"));
    if(types.isEmpty()) {
      types = switch(normalized) {
        case "USER" -> Set.of();
        case "COURSE" -> Boolean.TRUE.equals(filter.get("includeParents"))
            ? Set.of("STUDENT","PARENT") : Set.of("STUDENT");
        case "INSTITUTION" -> Set.of("STUDENT","PARENT","INSTITUTION_ADMINS");
        case "HRMS_PLATFORM" -> Set.of("STUDENT","PARENT","INSTITUTION_ADMINS");
        default -> Set.of();
      };
    }
    LinkedHashMap<String,Recipient> result=new LinkedHashMap<>();
    if("USER".equals(normalized)) {
      resolveDirectRecipients(institutionId,filter,result);
    } else {
      List<Long> targetInstitutions = new ArrayList<>();
      if("HRMS_PLATFORM".equals(normalized)) {
        if(Boolean.TRUE.equals(filter.get("allActiveInstitutions"))) {
          targetInstitutions=jdbc.queryForList(
            "select id from institution_tenant where status='ACTIVE' order by id",
            Long.class);
        } else {
          targetInstitutions=longList(filter.get("institutionIds"));
        }
        if(targetInstitutions.isEmpty()) {
          throw new IllegalArgumentException("Select one or more active institutions for the HRMS notification.");
        }
        if(targetInstitutions.size()>100) {
          throw new IllegalArgumentException("A single HRMS campaign may select at most 100 institutions.");
        }
        for(Long tenantId:targetInstitutions) {
          if(!isActiveInstitution(tenantId)) {
            throw new IllegalArgumentException("One selected institution is not active or does not exist.");
          }
        }
      } else {
        if(institutionId==null || institutionId<1) throw new IllegalArgumentException("An institution scope is required.");
        targetInstitutions=List.of(institutionId);
      }
      for(Long tenantId:targetInstitutions) {
        if(types.contains("STUDENT")) addStudents(tenantId,"COURSE".equals(normalized)?courseId:null,result);
        if(types.contains("PARENT")) addParents(tenantId,"COURSE".equals(normalized)?courseId:null,result);
        if(types.contains("INSTITUTION_ADMINS")) addInstitutionAdmins(tenantId,result);
      }
    }
    if(result.size()>MAX_RECIPIENTS) {
      throw new IllegalArgumentException("The resolved audience exceeds 10,000 recipients. Narrow the audience or split the campaign.");
    }
    return List.copyOf(result.values());
  }

  private void resolveDirectRecipients(Long institutionId,Map<String,Object> filter,Map<String,Recipient> target) {
    if(institutionId==null) throw new IllegalArgumentException("Individual notifications require an institution scope.");
    Object raw=filter.get("recipients");
    if(!(raw instanceof List<?> list)||list.isEmpty()) {
      throw new IllegalArgumentException("Select at least one recipient for an individual notification.");
    }
    if(list.size()>200) throw new IllegalArgumentException("Individual campaigns may target at most 200 recipients.");
    for(Object item:list) {
      if(!(item instanceof Map<?,?> map)) throw new IllegalArgumentException("A selected recipient is invalid.");
      String kind=String.valueOf(map.get("kind")).trim().toUpperCase(Locale.ROOT);
      long id;
      try{id=Long.parseLong(String.valueOf(map.get("id")));}catch(RuntimeException ex){throw new IllegalArgumentException("A selected recipient is invalid.");}
      if(id<1) throw new IllegalArgumentException("A selected recipient is invalid.");
      boolean eligible=switch(kind) {
        case "STUDENT" -> Boolean.TRUE.equals(jdbc.queryForObject("""
          select exists(select 1 from student st join institution_membership m
            on m.student_id=st.id and m.institution_id=? and m.status='ACTIVE'
          join institution_tenant t on t.id=m.institution_id and t.status='ACTIVE'
          where st.id=? and st.active=true)
          """,Boolean.class,institutionId,id));
        case "USER_ACCOUNT" -> Boolean.TRUE.equals(jdbc.queryForObject("""
          select exists(
            select 1 from user_account u
            join institution_user_membership um on um.user_id=u.id
              and um.institution_id=? and um.status='ACTIVE'
            join institution_tenant t on t.id=um.institution_id and t.status='ACTIVE'
            join guardian_student gs on gs.guardian_user_id=u.id and gs.active=true
              and gs.consent_status='CONSENTED' and gs.access_level='FULL'
            join student st on st.id=gs.student_id and st.active=true
            join institution_membership sm on sm.student_id=st.id
              and sm.institution_id=um.institution_id and sm.status='ACTIVE'
            where u.id=? and u.active=true)
          """,Boolean.class,institutionId,id));
        case "INSTITUTION_ADMIN" -> Boolean.TRUE.equals(jdbc.queryForObject("""
          select exists(select 1 from institution_admin_account where id=? and institution_id=? and active=true)
          """,Boolean.class,id,institutionId));
        default -> throw new IllegalArgumentException("This recipient type is not supported.");
      };
      if(!eligible) throw new SecurityException("A selected recipient is not an active member of the selected institution.");
      putRecipient(target,new Recipient(kind,id,institutionId));
    }
  }

  private void addStudents(Long institutionId,Long courseId,Map<String,Recipient> target) {
    if(courseId!=null && !isAssignedCourse(institutionId,courseId)) {
      throw new SecurityException("The selected course is not actively assigned to this institution.");
    }
    List<Map<String,Object>> rows;
    if(courseId==null) {
      rows=jdbc.queryForList("""
        select distinct st.id as recipient_id,m.institution_id
        from student st
        join institution_membership m on m.student_id=st.id and m.status='ACTIVE'
        join institution_tenant t on t.id=m.institution_id and t.status='ACTIVE'
        where st.active=true and m.institution_id=?
        """,institutionId);
    } else {
      rows=jdbc.queryForList("""
        select distinct st.id as recipient_id,m.institution_id
        from student st
        join institution_membership m on m.student_id=st.id and m.status='ACTIVE'
        join institution_tenant t on t.id=m.institution_id and t.status='ACTIVE'
        join student_program_enrollment spe on spe.student_id=st.id
          and spe.institution_id=m.institution_id and spe.status='ACTIVE'
          and spe.program_id=?
        join institution_course_assignment a on a.institution_id=m.institution_id and a.status='ASSIGNED'
        join institution_course_release r on r.id=a.course_release_id and r.program_id=spe.program_id
        where st.active=true and m.institution_id=?
        """,courseId,institutionId);
    }
    for(Map<String,Object> row:rows) putRecipient(target,new Recipient("STUDENT",
        ((Number)row.get("recipient_id")).longValue(),((Number)row.get("institution_id")).longValue()));
  }

  private void addParents(Long institutionId,Long courseId,Map<String,Recipient> target) {
    if(courseId!=null && !isAssignedCourse(institutionId,courseId)) {
      throw new SecurityException("The selected course is not actively assigned to this institution.");
    }
    String sql = """
      select distinct gs.guardian_user_id as recipient_id,m.institution_id
      from guardian_student gs
      join student st on st.id=gs.student_id and st.active=true
      join institution_membership m on m.student_id=st.id and m.status='ACTIVE'
      join institution_tenant t on t.id=m.institution_id and t.status='ACTIVE'
      join institution_user_membership um on um.user_id=gs.guardian_user_id
        and um.institution_id=m.institution_id and um.status='ACTIVE'
      join user_account u on u.id=gs.guardian_user_id and u.active=true
      """+(courseId==null?"":"""
      join student_program_enrollment spe on spe.student_id=st.id
        and spe.institution_id=m.institution_id and spe.status='ACTIVE' and spe.program_id=?
      join institution_course_assignment a on a.institution_id=m.institution_id and a.status='ASSIGNED'
      join institution_course_release r on r.id=a.course_release_id and r.program_id=spe.program_id
      """)+"""
      where m.institution_id=? and gs.active=true and gs.consent_status='CONSENTED'
        and gs.access_level='FULL'
      """;
    List<Map<String,Object>> rows;
    if(courseId==null) rows=jdbc.queryForList(sql,institutionId);
    else rows=jdbc.queryForList(sql,courseId,institutionId);
    for(Map<String,Object> row:rows) putRecipient(target,new Recipient("USER_ACCOUNT",
      ((Number)row.get("recipient_id")).longValue(),((Number)row.get("institution_id")).longValue()));
  }

  private void addInstitutionAdmins(Long institutionId,Map<String,Recipient> target) {
    List<Map<String,Object>> rows=jdbc.queryForList("""
      select a.id,t.id as institution_id from institution_admin_account a
      join institution_tenant t on t.id=a.institution_id and t.status='ACTIVE'
      where a.institution_id=? and a.active=true
      """,institutionId);
    for(Map<String,Object> row:rows) putRecipient(target,new Recipient("INSTITUTION_ADMIN",
      ((Number)row.get("id")).longValue(),((Number)row.get("institution_id")).longValue()));
  }

  private boolean isAssignedCourse(long institutionId,long courseId) {
    return Boolean.TRUE.equals(jdbc.queryForObject("""
      select exists(
        select 1 from institution_course_assignment a
        join institution_course_release r on r.id=a.course_release_id and r.program_id=?
        join institution_tenant t on t.id=a.institution_id and t.status='ACTIVE'
        where a.institution_id=? and a.status='ASSIGNED'
      )
      """,Boolean.class,courseId,institutionId));
  }

  private boolean isActiveInstitution(long institutionId) {
    return Boolean.TRUE.equals(jdbc.queryForObject(
      "select exists(select 1 from institution_tenant where id=? and status='ACTIVE')",Boolean.class,institutionId));
  }

  private List<Long> targetInstitutionIds(String scope,Map<String,Object> filter,List<Recipient> recipients) {
    if ("HRMS_PLATFORM".equals(scope)) {
      if (Boolean.TRUE.equals(filter.get("allActiveInstitutions"))) {
        return jdbc.queryForList("select id from institution_tenant where status='ACTIVE' order by id",Long.class);
      }
      return longList(filter.get("institutionIds"));
    }
    return recipients.stream().map(Recipient::institutionId).filter(java.util.Objects::nonNull)
      .distinct().sorted().toList();
  }
  private static void putRecipient(Map<String,Recipient> target,Recipient recipient) {
    String key=recipient.kind()+":"+recipient.identityId();
    if(!target.containsKey(key)&&target.size()>=MAX_RECIPIENTS) {
      throw new IllegalArgumentException("The resolved audience exceeds 10,000 recipients. Narrow the audience or split the campaign.");
    }
    // HRMS campaigns spanning several tenants send once to each account. Keep the first
    // selected tenant context for inbox scope while recording all target institutions in campaign snapshot.
    target.putIfAbsent(key,recipient);
  }

  public Map<String,Object> preview(
      Actor actor,String scope,Long institutionId,Long courseId,Map<String,Object> filter,
      String title,String summary,Object rawDocument) {
    String checkedTitle=requiredText(title,"Notification title",180);
    String checkedSummary=requiredText(summary,"Notification summary",400);
    JsonNode document=validator.validate(rawDocument);
    assertScopeAllowed(actor,scope,institutionId,courseId,filter);
    List<Recipient> recipients=resolveAudience(scope,institutionId,courseId,filter);
    Map<String,Integer> counts=countByKind(recipients);
    Map<String,Object> result=new LinkedHashMap<>();
    result.put("valid",true);
    result.put("title",checkedTitle);
    result.put("summary",checkedSummary);
    result.put("contentDocument",document);
    result.put("scope",scope);
    result.put("recipientCount",recipients.size());
    result.put("recipientCounts",counts);
    result.put("previewTokenRequired",false);
    result.put("note","The send operation resolves and validates the audience again.");
    return result;
  }

  @Transactional
  public Map<String,Object> send(
      Actor actor,String scope,Long institutionId,Long courseId,Map<String,Object> filter,
      String title,String summary,Object rawDocument,Long templateId,Integer templateVersion,
      String idempotencyKey,Instant scheduledAt,Instant expiresAt) {
    String normalizedScope=scope==null?"":scope.trim().toUpperCase(Locale.ROOT);
    String checkedTitle=requiredText(title,"Notification title",180);
    String checkedSummary=requiredText(summary,"Notification summary",400);
    String key=scopedIdempotencyKey(actor,requiredText(idempotencyKey,"Idempotency key",180));
    JsonNode document=validator.validate(rawDocument);
    assertScopeAllowed(actor,normalizedScope,institutionId,courseId,filter);
    Instant now=Instant.now();
    if(scheduledAt!=null && !scheduledAt.isAfter(now.plusSeconds(2))) {
      throw new IllegalArgumentException("Choose a future time to schedule this notification, or clear the schedule to send now.");
    }
    if(expiresAt!=null && !expiresAt.isAfter(scheduledAt==null?now:scheduledAt)) {
      throw new IllegalArgumentException("Expiry must be in the future and after the scheduled send time.");
    }
    List<Map<String,Object>> duplicate=jdbc.queryForList("""
      select id,public_id,status,resolved_recipient_count,dispatched_at
      from notification_campaign where sender_workspace=? and idempotency_key=?
      """,actor.workspace(),key);
    if(!duplicate.isEmpty()) {
      Map<String,Object> row=duplicate.getFirst();
      Map<String,Object> response=new LinkedHashMap<>(row);
      response.put("idempotentReplay",true);
      return response;
    }
    String audienceJson=writeJson(filter==null?Map.of():filter);
    List<Recipient> recipients=resolveAudience(normalizedScope,institutionId,courseId,filter==null?Map.of():filter);
    if(recipients.isEmpty()) throw new IllegalArgumentException("No eligible recipients match this notification audience.");
    if(scheduledAt!=null) {
      Map<String,Object> scheduledFilter=new LinkedHashMap<>(filter==null?Map.of():filter);
      // Freeze the institution set for a future HRMS send; re-resolve eligibility within
      // these institutions at dispatch rather than accidentally adding newly onboarded tenants.
      if("HRMS_PLATFORM".equals(normalizedScope)) {
        List<Long> frozenInstitutionIds=targetInstitutionIds(normalizedScope,scheduledFilter,recipients);
        scheduledFilter.put("allActiveInstitutions",false);
        scheduledFilter.put("institutionIds",frozenInstitutionIds);
      }
      Map<String,Object> scheduledSnapshot=new LinkedHashMap<>();
      scheduledSnapshot.put("recipientCount",recipients.size());
      scheduledSnapshot.put("recipientCounts",countByKind(recipients));
      scheduledSnapshot.put("resolvedAt",Instant.now().toString());
      scheduledSnapshot.put("institutionIds",targetInstitutionIds(normalizedScope,scheduledFilter,recipients));
      Long id=jdbc.queryForObject("""
        insert into notification_campaign(
          scope,institution_id,course_id,template_id,template_version,title,summary,content_document,
          audience_filter,audience_snapshot,sender_workspace,sender_staff_id,sender_institution_admin_id,
          idempotency_key,status,scheduled_at,expires_at,resolved_recipient_count)
        values (?,?,?,?,?,?,?,?::jsonb,?::jsonb,?::jsonb,?,?,?,?,'SCHEDULED',?,?,?)
        returning id
        """,Long.class,normalizedScope,institutionId,courseId,templateId,templateVersion,checkedTitle,checkedSummary,
        writeJson(document),writeJson(scheduledFilter),writeJson(scheduledSnapshot),actor.workspace(),
        actor.staffId(),actor.institutionAdminId(),key,scheduledAt,expiresAt,recipients.size());
      audit(actor,institutionId,id,templateId,"CAMPAIGN_SCHEDULED",
          Map.of("scope",normalizedScope,"recipientCount",recipients.size(),"scheduledAt",scheduledAt.toString(),
              "institutionIds",scheduledSnapshot.get("institutionIds")));
      return campaignResult(id,false);
    }
    return dispatchNewCampaign(actor,normalizedScope,institutionId,courseId,filter==null?Map.of():filter,
        checkedTitle,checkedSummary,document,templateId,templateVersion,key,scheduledAt,expiresAt,recipients);
  }

  @Transactional
  public Map<String,Object> cancelCampaign(Actor actor,long campaignId) {
    List<Map<String,Object>> rows=jdbc.queryForList("""
      select id,sender_workspace,sender_staff_id,sender_institution_admin_id,institution_id,status
      from notification_campaign where id=? for update
      """,campaignId);
    if(rows.isEmpty())throw new IllegalArgumentException("Notification campaign was not found.");
    Map<String,Object> row=rows.getFirst();
    String senderWorkspace=String.valueOf(row.get("sender_workspace"));
    Long senderStaff=row.get("sender_staff_id") instanceof Number n?n.longValue():null;
    Long senderAdmin=row.get("sender_institution_admin_id") instanceof Number n?n.longValue():null;
    Long institution=row.get("institution_id") instanceof Number n?n.longValue():null;
    boolean owner="HRMS".equals(actor.workspace())
        ? "HRMS".equals(senderWorkspace)
        : "INSTITUTION_ADMIN".equals(actor.workspace())
            ? "INSTITUTION_ADMIN".equals(senderWorkspace) && java.util.Objects.equals(senderAdmin,actor.institutionAdminId())
                && java.util.Objects.equals(institution,actor.institutionId())
            : "EDUCATION_ADMIN".equals(senderWorkspace) && java.util.Objects.equals(institution,actor.institutionId())
                && (java.util.Objects.equals(senderStaff,actor.staffId()) || actor.staffId()!=null
                    && Boolean.TRUE.equals(jdbc.queryForObject(
                        "select exists(select 1 from staff_account where id=? and role='ADMIN' and active=true)",
                        Boolean.class,actor.staffId())));
    if(!owner)throw new SecurityException("Only the campaign owner or authorized platform owner can cancel this campaign.");
    if(!"SCHEDULED".equals(String.valueOf(row.get("status")))) {
      throw new IllegalArgumentException("Only scheduled notifications can be cancelled before delivery.");
    }
    jdbc.update("update notification_campaign set status='CANCELLED',updated_at=now() where id=? and status='SCHEDULED'",campaignId);
    audit(actor,institution,campaignId,null,"CAMPAIGN_CANCELLED",Map.of("previousStatus","SCHEDULED"));
    return campaignResult(campaignId,false);
  }

  @Transactional
  public void dispatchScheduledCampaign(long campaignId) {
    List<Map<String,Object>> rows=jdbc.queryForList("""
      select * from notification_campaign
      where id=? and status='SCHEDULED' and scheduled_at<=now()
      for update skip locked
      """,campaignId);
    if(rows.isEmpty())return;
    Map<String,Object> row=rows.getFirst();
    if(row.get("expires_at")!=null && asInstant(row.get("expires_at")).isBefore(Instant.now())) {
      jdbc.update("update notification_campaign set status='EXPIRED',updated_at=now() where id=?",campaignId);
      return;
    }
    String workspace=String.valueOf(row.get("sender_workspace"));
    Long staffId=row.get("sender_staff_id") instanceof Number n?n.longValue():null;
    Long institutionAdminId=row.get("sender_institution_admin_id") instanceof Number n?n.longValue():null;
    Long institutionId=row.get("institution_id") instanceof Number n?n.longValue():null;
    Long courseId=row.get("course_id") instanceof Number n?n.longValue():null;
    Actor actor=new Actor(workspace,staffId,institutionAdminId,institutionId);
    if("INSTITUTION_ADMIN".equals(workspace)) {
      Boolean active=jdbc.queryForObject("""
        select exists(select 1 from institution_admin_account a
        join institution_tenant t on t.id=a.institution_id and t.status='ACTIVE'
        where a.id=? and a.institution_id=? and a.active=true)
        """,Boolean.class,institutionAdminId,institutionId);
      if(!Boolean.TRUE.equals(active)) {
        jdbc.update("update notification_campaign set status='CANCELLED',updated_at=now() where id=?",campaignId);
        audit(actor,institutionId,campaignId,null,"SCHEDULE_CANCELLED_SENDER_INACTIVE",Map.of());
        return;
      }
    } else {
      List<Map<String,Object>> staff=jdbc.queryForList("select id,role,active from staff_account where id=?",staffId);
      if(staff.isEmpty()||!Boolean.TRUE.equals(staff.getFirst().get("active"))) {
        jdbc.update("update notification_campaign set status='CANCELLED',updated_at=now() where id=?",campaignId);
        audit(actor,institutionId,campaignId,null,"SCHEDULE_CANCELLED_SENDER_INACTIVE",Map.of());
        return;
      }
      AuthContext context=new AuthContext(null,null,staffId,String.valueOf(staff.getFirst().get("role")),
          "Scheduled notification sender",workspace);
      String needed="HRMS".equals(workspace)?"QE_PLATFORM_OWNER":
          ("COURSE".equals(row.get("scope"))?"NOTIFICATION_COURSE_SEND":"NOTIFICATION_INSTITUTION_SEND");
      if(!authorization.hasPermission(context,needed)) {
        jdbc.update("update notification_campaign set status='CANCELLED',updated_at=now() where id=?",campaignId);
        audit(actor,institutionId,campaignId,null,"SCHEDULE_CANCELLED_PERMISSION_REVOKED",Map.of());
        return;
      }
    }
    Map<String,Object> filter=parseObject(String.valueOf(row.get("audience_filter")));
    List<Recipient> recipients=resolveAudience(String.valueOf(row.get("scope")),institutionId,courseId,filter);
    JsonNode document=parseJson(String.valueOf(row.get("content_document")));
    String summary=String.valueOf(row.get("summary"));
    dispatchExistingCampaign(actor,campaignId,recipients,document,String.valueOf(row.get("scope")),filter);
    audit(actor,institutionId,campaignId,row.get("template_id") instanceof Number n?n.longValue():null,
        "CAMPAIGN_DISPATCHED",Map.of("recipientCount",recipients.size(),"scheduled",true));
  }

  private Map<String,Object> dispatchNewCampaign(
      Actor actor,String scope,Long institutionId,Long courseId,Map<String,Object> filter,
      String title,String summary,JsonNode document,Long templateId,Integer templateVersion,
      String key,Instant scheduledAt,Instant expiresAt,List<Recipient> recipients) {
    Map<String,Object> counts=countByKind(recipients);
    Map<String,Object> snapshot=new LinkedHashMap<>();
    snapshot.put("recipientCount",recipients.size());
    snapshot.put("recipientCounts",counts);
    snapshot.put("resolvedAt",Instant.now().toString());
    snapshot.put("institutionIds",targetInstitutionIds(scope,filter,recipients));
    Long id=jdbc.queryForObject("""
      insert into notification_campaign(
        scope,institution_id,course_id,template_id,template_version,title,summary,content_document,
        audience_filter,audience_snapshot,sender_workspace,sender_staff_id,sender_institution_admin_id,
        idempotency_key,status,scheduled_at,expires_at,resolved_recipient_count,dispatched_at)
      values (?,?,?,?,?,?,?,?::jsonb,?::jsonb,?::jsonb,?,?,?,?, 'PROCESSING',?,?,?,now())
      returning id
      """,Long.class,scope,institutionId,courseId,templateId,templateVersion,title,summary,
      writeJson(document),writeJson(filter),writeJson(snapshot),actor.workspace(),actor.staffId(),
      actor.institutionAdminId(),key,scheduledAt,expiresAt,recipients.size());
    dispatchExistingCampaign(actor,id,recipients,document,scope,filter);
    audit(actor,institutionId,id,templateId,"CAMPAIGN_SENT",Map.of(
      "scope",scope,"recipientCount",recipients.size(),"recipientCounts",counts));
    return campaignResult(id,false);
  }

  private void dispatchExistingCampaign(Actor actor,long campaignId,List<Recipient> recipients,JsonNode document,String scope,Map<String,Object> filter) {
    if(recipients.isEmpty()) {
      jdbc.update("update notification_campaign set status='FAILED',updated_at=now() where id=?",campaignId);
      return;
    }
    List<Object[]> batch=new ArrayList<>(recipients.size());
    for(Recipient recipient:recipients) {
      Long student="STUDENT".equals(recipient.kind())?recipient.identityId():null;
      Long user="USER_ACCOUNT".equals(recipient.kind())?recipient.identityId():null;
      Long staff="STAFF_ACCOUNT".equals(recipient.kind())?recipient.identityId():null;
      Long institutionAdmin="INSTITUTION_ADMIN".equals(recipient.kind())?recipient.identityId():null;
      Long deliveryInstitutionId="HRMS_PLATFORM".equals(scope)?null:recipient.institutionId();
      batch.add(new Object[]{campaignId,deliveryInstitutionId,recipient.kind(),student,user,staff,institutionAdmin});
    }
    jdbc.batchUpdate("""
      insert into notification_recipient(campaign_id,institution_id,recipient_kind,
        student_id,user_account_id,staff_account_id,institution_admin_id,state)
      values (?,?,?,?,?,?,?,'CREATED')
      on conflict do nothing
      """,batch);
    jdbc.update("""
      insert into notification_delivery_attempt(recipient_id,channel,attempt_number,status,attempted_at,acknowledged_at)
      select id,'IN_APP',1,'SENT',now(),now() from notification_recipient
      where campaign_id=? on conflict(recipient_id,channel,attempt_number) do nothing
      """,campaignId);
    Map<String,Object> snapshot=new LinkedHashMap<>();
    snapshot.put("recipientCount",recipients.size());
    snapshot.put("recipientCounts",countByKind(recipients));
    snapshot.put("resolvedAt",Instant.now().toString());
    snapshot.put("institutionIds",targetInstitutionIds(scope,filter,recipients));
    jdbc.update("""
      update notification_campaign set status='SENT',dispatched_at=now(),updated_at=now(),
        resolved_recipient_count=?,audience_snapshot=?::jsonb where id=?
      """,recipients.size(),writeJson(snapshot),campaignId);
  }

  public String campaignScope(long campaignId) {
    List<String> scopes=jdbc.queryForList("select scope from notification_campaign where id=?",String.class,campaignId);
    if(scopes.isEmpty())throw new IllegalArgumentException("Notification campaign was not found.");
    return scopes.getFirst();
  }

  public Map<String,Object> campaignResult(long id,boolean idempotent) {
    Map<String,Object> row=jdbc.queryForMap("""
      select id,public_id,scope,institution_id,course_id,title,summary,status,
        resolved_recipient_count,scheduled_at,dispatched_at,created_at
      from notification_campaign where id=?
      """,id);
    row.put("idempotentReplay",idempotent);
    return row;
  }

  public List<Map<String,Object>> listCampaigns(Actor actor,int limit) {
    int size=Math.max(1,Math.min(limit,100));
    String sql;
    Object[] args;
    if("HRMS".equals(actor.workspace())) {
      sql="select id,public_id,scope,institution_id,course_id,title,summary,status,resolved_recipient_count,scheduled_at,dispatched_at,created_at from notification_campaign order by created_at desc,id desc limit ?";
      args=new Object[]{size};
    } else if("INSTITUTION_ADMIN".equals(actor.workspace())) {
      sql="""
        select id,public_id,scope,institution_id,course_id,title,summary,status,resolved_recipient_count,
          scheduled_at,dispatched_at,created_at
        from notification_campaign
        where institution_id=? or (scope='HRMS_PLATFORM'
          and audience_snapshot->'institutionIds' @> jsonb_build_array(?::bigint))
        order by created_at desc,id desc limit ?
        """;
      args=new Object[]{actor.institutionId(),actor.institutionId(),size};
    } else {
      sql="select id,public_id,scope,institution_id,course_id,title,summary,status,resolved_recipient_count,scheduled_at,dispatched_at,created_at from notification_campaign where institution_id=? order by created_at desc,id desc limit ?";
      args=new Object[]{actor.institutionId(),size};
    }
    return jdbc.queryForList(sql,args);
  }

  public List<Map<String,Object>> inbox(
      AuthContext context,long institutionId,int requestedLimit,Long beforeId,boolean unreadOnly) {
    int limit=Math.max(1,Math.min(requestedLimit,100));
    String kind; Long id;
    if(context.studentId()!=null) {kind="STUDENT";id=context.studentId();}
    else if(context.isParent()&&context.userId()!=null) {kind="USER_ACCOUNT";id=context.userId();}
    else if(context.staffId()!=null) {kind="STAFF_ACCOUNT";id=context.staffId();}
    else throw new SecurityException("A supported authenticated recipient identity is required.");
    String sql="""
      select r.id as recipient_id,r.state,r.created_at as delivered_at,r.read_at,r.dismissed_at,
        c.public_id as campaign_public_id,c.scope,c.title,c.summary,
        case when c.sender_workspace='HRMS' then 'HRMS Platform'
             when c.sender_workspace='INSTITUTION_ADMIN' then coalesce(sender_admin.display_name,tenant.display_name,'Institution Admin')
             else coalesce(sender_staff.display_name,tenant.display_name,'QuantaEdge Admin') end as sender_label,
        c.content_document::text as content_document,c.institution_id as campaign_institution_id,c.course_id,c.expires_at
      from notification_recipient r join notification_campaign c on c.id=r.campaign_id
      left join institution_tenant tenant on tenant.id=c.institution_id
      left join staff_account sender_staff on sender_staff.id=c.sender_staff_id
      left join institution_admin_account sender_admin on sender_admin.id=c.sender_institution_admin_id
      where r.recipient_kind=? and
        case when ?='STUDENT' then r.student_id=? when ?='USER_ACCOUNT' then r.user_account_id=?
             when ?='STAFF_ACCOUNT' then r.staff_account_id=? else false end
        and (r.institution_id=? or (r.institution_id is null and c.scope='HRMS_PLATFORM'
          and c.audience_snapshot->'institutionIds' @> jsonb_build_array(?::bigint)))
        and c.status='SENT' and (c.expires_at is null or c.expires_at>now())
        and (?=0 or r.id<?)
        and (?=false or r.state NOT IN ('READ','DISMISSED'))
      order by r.created_at desc,r.id desc limit ?
      """;
    List<Map<String,Object>> rows=jdbc.queryForList(sql,kind,kind,id,kind,id,kind,id,institutionId,institutionId,
        beforeId==null?0L:beforeId,beforeId==null?Long.MAX_VALUE:beforeId,unreadOnly,limit);
    for(Map<String,Object> row:rows) {
      row.put("contentDocument",parseJson(String.valueOf(row.remove("content_document"))));
      row.put("unread",!Set.of("READ","DISMISSED").contains(String.valueOf(row.get("state"))));
    }
    return rows;
  }

  public List<Map<String,Object>> inboxForInstitutionAdmin(
      long adminId,long institutionId,int requestedLimit,Long beforeId,boolean unreadOnly) {
    int limit=Math.max(1,Math.min(requestedLimit,100));
    return jdbc.queryForList("""
      select r.id as recipient_id,r.state,r.created_at as delivered_at,r.read_at,r.dismissed_at,
        c.public_id as campaign_public_id,c.scope,c.title,c.summary,
        case when c.sender_workspace='HRMS' then 'HRMS Platform'
             when c.sender_workspace='INSTITUTION_ADMIN' then coalesce(sender_admin.display_name,tenant.display_name,'Institution Admin')
             else coalesce(sender_staff.display_name,tenant.display_name,'QuantaEdge Admin') end as sender_label,
        c.content_document::text as content_document,c.institution_id as campaign_institution_id,c.course_id,c.expires_at
      from notification_recipient r join notification_campaign c on c.id=r.campaign_id
      left join institution_tenant tenant on tenant.id=c.institution_id
      left join staff_account sender_staff on sender_staff.id=c.sender_staff_id
      left join institution_admin_account sender_admin on sender_admin.id=c.sender_institution_admin_id
      where r.recipient_kind='INSTITUTION_ADMIN' and r.institution_admin_id=?
        and (r.institution_id=? or (r.institution_id is null and c.scope='HRMS_PLATFORM'
          and c.audience_snapshot->'institutionIds' @> jsonb_build_array(?::bigint))) and c.status='SENT'
        and (c.expires_at is null or c.expires_at>now())
        and (?=0 or r.id<?) and (?=false or r.state NOT IN ('READ','DISMISSED'))
      order by r.created_at desc,r.id desc limit ?
      """,adminId,institutionId,institutionId,beforeId==null?0L:beforeId,
        beforeId==null?Long.MAX_VALUE:beforeId,unreadOnly,limit)
      .stream().map(row->{
        Map<String,Object> item=new LinkedHashMap<>(row);
        item.put("contentDocument",parseJson(String.valueOf(item.remove("content_document"))));
        item.put("unread",!Set.of("READ","DISMISSED").contains(String.valueOf(item.get("state"))));
        return item;
      }).toList();
  }

  @Transactional
  public boolean markReadForInstitutionAdmin(long adminId,long institutionId,long recipientId,boolean all) {
    if(all) {
      jdbc.update("""
        update notification_recipient set state='READ',read_at=coalesce(read_at,now())
        where recipient_kind='INSTITUTION_ADMIN' and institution_admin_id=?
          and (institution_id=? or (institution_id is null and exists(
            select 1 from notification_campaign c where c.id=notification_recipient.campaign_id and c.scope='HRMS_PLATFORM'
            and c.audience_snapshot->'institutionIds' @> jsonb_build_array(?::bigint))))
          and state in ('CREATED','QUEUED')
        """,adminId,institutionId,institutionId);
      return true;
    }
    return jdbc.update("""
      update notification_recipient set state='READ',read_at=coalesce(read_at,now())
      where id=? and recipient_kind='INSTITUTION_ADMIN' and institution_admin_id=?
        and (institution_id=? or (institution_id is null and exists(
          select 1 from notification_campaign c where c.id=notification_recipient.campaign_id and c.scope='HRMS_PLATFORM')))
        and state in ('CREATED','QUEUED')
      """,recipientId,adminId,institutionId,institutionId)==1;
  }

  @Transactional
  public boolean markUnread(AuthContext context,long institutionId,long recipientId) {
    return updateRecipientState(context,institutionId,recipientId,"CREATED");
  }

  @Transactional
  public boolean dismissNotification(AuthContext context,long institutionId,long recipientId) {
    return updateRecipientState(context,institutionId,recipientId,"DISMISSED");
  }

  private boolean updateRecipientState(AuthContext context,long institutionId,long recipientId,String nextState) {
    if(!Set.of("CREATED","READ","DISMISSED").contains(nextState)) {
      throw new IllegalArgumentException("Unsupported notification state change.");
    }
    String kind; Long id;
    if(context.studentId()!=null){kind="STUDENT";id=context.studentId();}
    else if(context.isParent()&&context.userId()!=null){kind="USER_ACCOUNT";id=context.userId();}
    else throw new SecurityException("A supported authenticated recipient identity is required.");
    return jdbc.update("""
      update notification_recipient set
        state=?,
        read_at=case when ? in ('READ','DISMISSED') then coalesce(read_at,now()) else null end,
        dismissed_at=case when ?='DISMISSED' then now() else null end
      where id=? and recipient_kind=? and
        case when ?='STUDENT' then student_id=? when ?='USER_ACCOUNT' then user_account_id=? else false end
        and state in ('CREATED','QUEUED','READ','DISMISSED')
        and exists(
          select 1 from notification_campaign c
          where c.id=notification_recipient.campaign_id and c.status='SENT'
            and (c.expires_at is null or c.expires_at>now())
            and (notification_recipient.institution_id=? or (
              notification_recipient.institution_id is null and c.scope='HRMS_PLATFORM'
              and c.audience_snapshot->'institutionIds' @> jsonb_build_array(?::bigint)))
        )
      """,nextState,nextState,nextState,recipientId,kind,kind,id,kind,id,institutionId,institutionId)==1;
  }

  @Transactional
  public boolean markUnreadForInstitutionAdmin(long adminId,long institutionId,long recipientId) {
    return updateInstitutionAdminNotificationState(adminId,institutionId,recipientId,"CREATED");
  }

  @Transactional
  public boolean dismissForInstitutionAdmin(long adminId,long institutionId,long recipientId) {
    return updateInstitutionAdminNotificationState(adminId,institutionId,recipientId,"DISMISSED");
  }

  private boolean updateInstitutionAdminNotificationState(long adminId,long institutionId,long recipientId,String nextState) {
    if(!Set.of("CREATED","DISMISSED").contains(nextState)) {
      throw new IllegalArgumentException("Unsupported notification state change.");
    }
    return jdbc.update("""
      update notification_recipient set
        state=?,
        read_at=case when ?='DISMISSED' then coalesce(read_at,now()) else null end,
        dismissed_at=case when ?='DISMISSED' then now() else null end
      where id=? and recipient_kind='INSTITUTION_ADMIN' and institution_admin_id=?
        and state in ('CREATED','QUEUED','READ','DISMISSED')
        and exists(
          select 1 from notification_campaign c
          where c.id=notification_recipient.campaign_id and c.status='SENT'
            and (c.expires_at is null or c.expires_at>now())
            and (notification_recipient.institution_id=? or (
              notification_recipient.institution_id is null and c.scope='HRMS_PLATFORM'
              and c.audience_snapshot->'institutionIds' @> jsonb_build_array(?::bigint)))
        )
      """,nextState,nextState,nextState,recipientId,adminId,institutionId,institutionId)==1;
  }

  @Transactional
  public boolean markRead(AuthContext context,long institutionId,long recipientId,boolean all) {
    String kind; Long id;
    if(context.studentId()!=null){kind="STUDENT";id=context.studentId();}
    else if(context.isParent()&&context.userId()!=null){kind="USER_ACCOUNT";id=context.userId();}
    else if(context.staffId()!=null){kind="STAFF_ACCOUNT";id=context.staffId();}
    else throw new SecurityException("A supported authenticated recipient identity is required.");
    if(all) {
      jdbc.update("""
        update notification_recipient set state='READ',read_at=coalesce(read_at,now())
        where recipient_kind=? and case when ?='STUDENT' then student_id=?
          when ?='USER_ACCOUNT' then user_account_id=? when ?='STAFF_ACCOUNT' then staff_account_id=?
          else false end and (institution_id=? or (institution_id is null and exists(
          select 1 from notification_campaign c where c.id=notification_recipient.campaign_id and c.scope='HRMS_PLATFORM'
            and c.audience_snapshot->'institutionIds' @> jsonb_build_array(?::bigint)))) and state in ('CREATED','QUEUED')
        """,kind,kind,id,kind,id,kind,id,institutionId,institutionId);
      return true;
    }
    int changed=jdbc.update("""
      update notification_recipient set state='READ',read_at=coalesce(read_at,now())
      where id=? and recipient_kind=? and
        case when ?='STUDENT' then student_id=? when ?='USER_ACCOUNT' then user_account_id=?
             when ?='STAFF_ACCOUNT' then staff_account_id=? else false end
        and (institution_id=? or (institution_id is null and exists(
          select 1 from notification_campaign c where c.id=notification_recipient.campaign_id and c.scope='HRMS_PLATFORM')))
        and state in ('CREATED','QUEUED')
      """,recipientId,kind,kind,id,kind,id,kind,id,institutionId,institutionId);
    return changed==1;
  }

  private void assertScopeAllowed(Actor actor,String scope,Long institutionId,Long courseId,Map<String,Object> filter) {
    if(!Set.of("USER","COURSE","INSTITUTION","HRMS_PLATFORM").contains(scope)) {
      throw new IllegalArgumentException("Choose a supported notification scope.");
    }
    if("INSTITUTION_ADMIN".equals(actor.workspace())) {
      if(institutionId==null || !institutionId.equals(actor.institutionId())) {
        throw new SecurityException("Institution Admin cannot change the authenticated institution scope.");
      }
      if("HRMS_PLATFORM".equals(scope)) throw new SecurityException("Institution Admin cannot create HRMS platform campaigns.");
    }
    if("HRMS".equals(actor.workspace())) {
      if(!"HRMS_PLATFORM".equals(scope)) {
        throw new IllegalArgumentException("HRMS campaigns must use the HRMS platform scope and an explicit institution audience.");
      }
    } else if("HRMS_PLATFORM".equals(scope)) {
      throw new SecurityException("Only HRMS platform owners can send platform campaigns.");
    }
    if(("COURSE".equals(scope)||"INSTITUTION".equals(scope)||"USER".equals(scope))&&institutionId==null) {
      throw new IllegalArgumentException("An institution must be selected for this notification scope.");
    }
    if("COURSE".equals(scope)&&(courseId==null||courseId<1)) {
      throw new IllegalArgumentException("Select the canonical course ID before sending a course notification.");
    }
    if(!"COURSE".equals(scope)&&courseId!=null) {
      throw new IllegalArgumentException("A course ID is only valid for a course-level notification.");
    }
  }

  private void audit(Actor actor,Long institutionId,Long campaignId,Long templateId,String action,Map<String,?> detail) {
    jdbc.update("""
      insert into notification_audit(campaign_id,template_id,institution_id,actor_workspace,
        actor_staff_id,actor_institution_admin_id,action_code,details)
      values (?,?,?,?,?,?,?,?::jsonb)
      """,campaignId,templateId,institutionId,actor.workspace(),actor.staffId(),
      actor.institutionAdminId(),action,writeJson(detail));
  }

  private Map<String,Object> templateView(Map<String,Object> row) {
    Map<String,Object> view=new LinkedHashMap<>(row);
    Object raw=view.remove("content_document");
    view.put("contentDocument",parseJson(String.valueOf(raw)));
    return view;
  }

  private Map<String,Integer> countByKind(List<Recipient> recipients) {
    Map<String,Integer> result=new LinkedHashMap<>();
    for(Recipient recipient:recipients) result.merge(recipient.kind(),1,Integer::sum);
    return result;
  }

  private static Set<String> stringSet(Object raw) {
    if(!(raw instanceof List<?> list))return Set.of();
    Set<String> values=new LinkedHashSet<>();
    for(Object item:list) {
      String value=String.valueOf(item).trim().toUpperCase(Locale.ROOT);
      if(Set.of("STUDENT","PARENT","INSTITUTION_ADMINS").contains(value))values.add(value);
    }
    return values;
  }

  private static List<Long> longList(Object raw) {
    if(!(raw instanceof List<?> list))return List.of();
    LinkedHashSet<Long> result=new LinkedHashSet<>();
    for(Object item:list) {
      try {long id=Long.parseLong(String.valueOf(item)); if(id>0)result.add(id);}
      catch(RuntimeException ex){throw new IllegalArgumentException("An institution selection is invalid.");}
    }
    return List.copyOf(result);
  }

  private static String scopedIdempotencyKey(Actor actor,String requestKey) {
    String identity=actor.staffId()!=null?"staff:"+actor.staffId()
        :actor.institutionAdminId()!=null?"institution-admin:"+actor.institutionAdminId()
        :"institution:"+(actor.institutionId()==null?"platform":actor.institutionId());
    String input=actor.workspace()+"|"+identity+"|"+requestKey;
    try {
      return java.util.HexFormat.of().formatHex(
          java.security.MessageDigest.getInstance("SHA-256").digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch(java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is required for notification idempotency.",impossible);
    }
  }

  private static Instant asInstant(Object raw) {
    if(raw instanceof Instant value)return value;
    if(raw instanceof java.sql.Timestamp value)return value.toInstant();
    if(raw instanceof java.time.OffsetDateTime value)return value.toInstant();
    if(raw instanceof java.util.Date value)return value.toInstant();
    throw new IllegalStateException("Stored notification expiry timestamp has an unsupported type.");
  }

  private Map<String,Object> parseObject(String raw) {
    JsonNode node=parseJson(raw);
    if(!node.isObject())return Map.of();
    return mapper.convertValue(node,mapper.getTypeFactory().constructMapType(LinkedHashMap.class,String.class,Object.class));
  }

  private JsonNode parseJson(String value) {
    try{return mapper.readTree(value);}catch(Exception ex){throw new IllegalStateException("Stored notification data is invalid.",ex);}
  }

  private String writeJson(Object value) {
    try{return mapper.writeValueAsString(value);}catch(JsonProcessingException ex){throw new IllegalArgumentException("Notification content could not be encoded.",ex);}
  }

  private static String requiredText(String raw,String label,int max) {
    String value=raw==null?"":raw.trim();
    if(value.length()<1||value.length()>max)throw new IllegalArgumentException(label+" is required and must be at most "+max+" characters.");
    return value;
  }

  private static String optionalText(String raw,int max) {
    String value=raw==null?"":raw.trim();
    if(value.length()>max)throw new IllegalArgumentException("Description exceeds "+max+" characters.");
    return value;
  }

  private static Object emptyToNull(String value) {return value==null||value.isBlank()?null:value;}

}
