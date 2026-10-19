package com.quantaedge.api;

import java.util.*;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {
  private static final Logger LOGGER = LoggerFactory.getLogger(AdminController.class);
  private final JdbcTemplate jdbc;
  private final AuthorizationService authorization;
  private final ObjectMapper mapper;
  private final AuthService auth;
  private final AdminContentController contentController;
  private final StaffAuditService staffAudit;

  public AdminController(JdbcTemplate jdbc,AuthorizationService authorization,ObjectMapper mapper,
      AuthService auth,AdminContentController contentController,StaffAuditService staffAudit){
    this.jdbc=jdbc; this.authorization=authorization; this.mapper=mapper; this.auth=auth;
    this.contentController=contentController; this.staffAudit=staffAudit;
  }

  @GetMapping("/parents")
  public Map<String,Object> parents(
      @RequestParam(required=false) String query,
      @RequestParam(defaultValue="25") Integer limit,
      @RequestParam(defaultValue="0") Integer offset,
      @RequestAttribute(value="authContext",required=false) AuthContext context,
      @RequestAttribute(value=InstitutionTenantContextService.REQUEST_ATTRIBUTE,required=false)
          InstitutionTenantContext tenant){
    authorization.requireAdmin(context);
    long institutionId=requireInstitution(tenant);
    int rowLimit=Math.max(1,Math.min(limit==null?25:limit,100));
    int rowOffset=Math.max(0,Math.min(offset==null?0:offset,10_000_000));
    String term=query==null?"":query.trim();
    if(term.length()>120)term=term.substring(0,120);
    String pattern="%"+term+"%";
    List<Map<String,Object>> items=jdbc.queryForList("""
      select u.id,u.display_name,u.mobile_e164,u.contact_email,
        u.email as google_email,u.email_verified_at,u.google_subject,
        (u.email_verified_at is not null and u.google_subject is not null) as gmail_verified,
        (u.mobile_verified_at is not null and u.mobile_e164 is not null) as mobile_verified,
        u.username,u.active,u.created_at,u.updated_at,
        count(distinct case when gs.active=true and gs.consent_status='CONSENTED'
          and st.active=true and im.status='ACTIVE' then st.id end) as active_children,
        count(distinct case when gs.active=true and gs.consent_status='CONSENTED' then st.id end) as linked_children
      from user_account u
      join guardian_student gs on gs.guardian_user_id=u.id
      join student st on st.id=gs.student_id and st.environment='PRODUCTION'
      join institution_membership im on im.student_id=st.id and im.institution_id=?
        and im.status in ('ACTIVE','SUSPENDED')
      where u.role='PARENT'
        and (?='' or coalesce(u.display_name,'') ilike ? or coalesce(u.mobile_e164,'') ilike ?
          or coalesce(u.email,'') ilike ? or coalesce(u.contact_email,'') ilike ? or coalesce(u.username,'') ilike ?)
      group by u.id,u.display_name,u.mobile_e164,u.contact_email,u.email,u.email_verified_at,u.google_subject,
        u.mobile_verified_at,u.username,u.active,u.created_at,u.updated_at
      order by u.created_at desc,u.id desc limit ? offset ?
      """,institutionId,term,pattern,pattern,pattern,pattern,pattern,rowLimit,rowOffset);
    Long total=jdbc.queryForObject("""
      select count(distinct u.id) from user_account u
      join guardian_student gs on gs.guardian_user_id=u.id
      join student st on st.id=gs.student_id and st.environment='PRODUCTION'
      join institution_membership im on im.student_id=st.id and im.institution_id=?
        and im.status in ('ACTIVE','SUSPENDED')
      where u.role='PARENT' and (?='' or coalesce(u.display_name,'') ilike ? or coalesce(u.mobile_e164,'') ilike ?
        or coalesce(u.email,'') ilike ? or coalesce(u.contact_email,'') ilike ? or coalesce(u.username,'') ilike ?)
      """,Long.class,institutionId,term,pattern,pattern,pattern,pattern,pattern);
    return Map.of("items",items,"total",total==null?0L:total,"limit",rowLimit,"offset",rowOffset,
        "institutionId",institutionId);
  }

  @GetMapping("/parents/{parentId}/children")
  public List<Map<String,Object>> parentChildren(@PathVariable long parentId,
      @RequestAttribute(value="authContext",required=false) AuthContext context,
      @RequestAttribute(value=InstitutionTenantContextService.REQUEST_ATTRIBUTE,required=false)
          InstitutionTenantContext tenant){
    authorization.requireAdmin(context);
    long institutionId=requireInstitution(tenant);
    return jdbc.queryForList("""
      select st.id,st.public_id,st.display_name,st.board,st.language,st.active as student_account_active,
        (im.status='ACTIVE') as student_active,im.status as membership_status,
        st.created_at,gs.active as relationship_active,gs.consent_status,
        coalesce((select string_agg(distinct cp.display_name,' · ' order by cp.display_name)
          from student_program_enrollment spe
          join curriculum_program cp on cp.id=spe.program_id and cp.status='ACTIVE' and cp.code<>'GENERAL'
          join institution_course_assignment a on a.institution_id=spe.institution_id and a.status='ASSIGNED'
          join institution_course_release r on r.id=a.course_release_id and r.program_id=cp.id
          where spe.student_id=st.id and spe.institution_id=im.institution_id and spe.status='ACTIVE'),'') as course_names
      from guardian_student gs join student st on st.id=gs.student_id
      join institution_membership im on im.student_id=st.id and im.institution_id=?
        and im.status in ('ACTIVE','SUSPENDED')
      where gs.guardian_user_id=? and st.environment='PRODUCTION'
      order by st.created_at desc
      """,institutionId,parentId);
  }

  @PutMapping("/parents/{parentId}/status")
  @Transactional
  public Map<String,Object> updateParentStatus(@PathVariable long parentId,@RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context,
      @RequestAttribute(value=InstitutionTenantContextService.REQUEST_ATTRIBUTE,required=false)
          InstitutionTenantContext tenant){
    authorization.requireAdmin(context);
    long institutionId=requireInstitution(tenant);
    // A tenant administrator must not deactivate a shared parent identity in every institution.
    Long linked=jdbc.queryForObject("""
      select count(distinct st.id) from guardian_student gs
      join student st on st.id=gs.student_id
      join institution_membership im on im.student_id=st.id and im.institution_id=?
        and im.status in ('ACTIVE','SUSPENDED')
      where gs.guardian_user_id=? and st.environment='PRODUCTION'
      """,Long.class,institutionId,parentId);
    if(linked==null||linked==0L)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Parent is not linked to a learner in this institution.");
    throw new ResponseStatusException(HttpStatus.FORBIDDEN,
        "Parent identity activation is HRMS-managed; an institution administrator cannot suspend the shared account.");
  }

  @PutMapping("/students/{studentId}/status")
  @Transactional
  public Map<String,Object> updateProductionStudentStatus(@PathVariable long studentId,@RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context,
      @RequestAttribute(value=InstitutionTenantContextService.REQUEST_ATTRIBUTE,required=false)
          InstitutionTenantContext tenant){
    AuthContext admin=authorization.requireAdmin(context);
    long institutionId=requireInstitution(tenant);
    if(!(body.get("active") instanceof Boolean active)) throw new IllegalArgumentException("active must be true or false");
    int changed=jdbc.update("""
      update institution_membership m set status=?,left_at=case when ? then null else coalesce(left_at,now()) end,updated_at=now()
      from student st where m.student_id=st.id and st.id=? and st.environment='PRODUCTION'
        and m.institution_id=? and m.status in ('ACTIVE','SUSPENDED')
      """,active?"ACTIVE":"SUSPENDED",active,studentId,institutionId);
    if(changed==0) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Student has no membership in the active institution.");
    log(admin,active?"STUDENT_INSTITUTION_REACTIVATE":"STUDENT_INSTITUTION_SUSPEND",
        String.valueOf(studentId),"institutionId="+institutionId+"; active="+active);
    return Map.of("updated",true,"studentId",studentId,"institutionId",institutionId,"active",active);
  }

  @PutMapping("/students/{studentId}/tracks")
  public Map<String,Object> updateStudentTracks(@PathVariable long studentId,@RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context,
      @RequestAttribute(value=InstitutionTenantContextService.REQUEST_ATTRIBUTE,required=false)
          InstitutionTenantContext tenant){
    authorization.requireAdmin(context);
    long institutionId=requireInstitution(tenant);
    Long linked=jdbc.queryForObject("""
      select count(*) from institution_membership where student_id=? and institution_id=?
        and status in ('ACTIVE','SUSPENDED')
      """,Long.class,studentId,institutionId);
    if(linked==null||linked==0L)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Student has no membership in the active institution.");
    throw new ResponseStatusException(HttpStatus.GONE,
        "Legacy subject-track assignment is retired. Learner access is governed by institution-assigned course enrollment.");
  }

  @GetMapping("/lessons")
  public List<Map<String,Object>> lessons(
      @RequestParam Long courseId,
      @RequestParam(required=false) String subjectCode,
      @RequestAttribute(value="authContext",required=false) AuthContext context,
      @RequestAttribute(value=InstitutionTenantContextService.REQUEST_ATTRIBUTE,required=false)
          InstitutionTenantContext tenant){
    authorization.requireAdmin(context);
    long institutionId=requireInstitution(tenant);
    requireAssignedCourse(courseId,institutionId);
    String selectedSubject=subjectCode==null?"":subjectCode.trim();
    return jdbc.queryForList("""
      select l.id,l.code,l.title,l.status,l.sort_order,ch.display_name as chapter_name,
             cc.code as class_code,s.code as subject_code,s.display_name as subject_name,
             p.id as course_id,p.code as course_code,p.display_name as course_name
      from curriculum_program p
      join institution_course_assignment assignment on assignment.institution_id=?
        and assignment.status='ASSIGNED'
      join institution_course_release release on release.id=assignment.course_release_id
        and release.program_id=p.id
      join curriculum_program_subject cps on cps.program_id=p.id
      join curriculum_subject s on s.id=cps.subject_id and s.class_id=cps.class_id and s.active=true
        and (s.program_id=p.id or (s.program_id is null and p.class_id=s.class_id))
      join curriculum_class cc on cc.id=s.class_id and cc.active=true
      join curriculum_chapter ch on ch.subject_id=s.id and ch.active=true
      join lesson l on l.chapter_id=ch.id and l.active=true
      where p.id=? and p.status='ACTIVE' and p.code<>'GENERAL'
        and (?='' or s.code=?)
      order by cps.sort_order,s.sort_order,coalesce(ch.teaching_order,ch.sort_order),l.sort_order,l.id
      """,institutionId,courseId,selectedSubject,selectedSubject);
  }

  @GetMapping("/students")
  public Map<String,Object> students(
      @RequestParam Long courseId,
      @RequestParam(required=false) String query,
      @RequestParam(defaultValue="25") Integer limit,
      @RequestParam(defaultValue="0") Integer offset,
      @RequestAttribute(value="authContext",required=false) AuthContext context,
      @RequestAttribute(value=InstitutionTenantContextService.REQUEST_ATTRIBUTE,required=false)
          InstitutionTenantContext tenant){
    authorization.requireAdmin(context);
    long institutionId=requireInstitution(tenant);
    requireAssignedCourse(courseId,institutionId);
    int rowLimit=Math.max(1,Math.min(limit==null?25:limit,100));
    int rowOffset=Math.max(0,Math.min(offset==null?0:offset,10_000_000));
    String term=query==null?"":query.trim();
    if(term.length()>120)term=term.substring(0,120);
    String pattern="%"+term+"%";
    String tenantSearch="""
      and (?='' or coalesce(st.display_name,'') ilike ? or st.public_id::text ilike ?)
      """;
    List<Map<String,Object>> items=jdbc.queryForList("""
      select st.id,st.public_id,st.display_name,st.board,st.language,st.environment,
             ((im.status='ACTIVE') and st.active) as active,st.active as student_account_active,
             im.status as membership_status,st.created_at,
             coalesce((select cp.display_name from student_program_enrollment spe
               join curriculum_program cp on cp.id=spe.program_id and cp.status='ACTIVE' and cp.code<>'GENERAL'
               join institution_course_assignment a on a.institution_id=spe.institution_id and a.status='ASSIGNED'
               join institution_course_release r on r.id=a.course_release_id and r.program_id=cp.id
               where spe.student_id=st.id and spe.institution_id=im.institution_id
                 and spe.program_id=? and spe.status='ACTIVE' limit 1),'') as course_names,
             coalesce((select count(distinct l.id) from student_program_enrollment spe
               join curriculum_program cp on cp.id=spe.program_id and cp.status='ACTIVE' and cp.code<>'GENERAL'
               join institution_course_assignment a on a.institution_id=spe.institution_id and a.status='ASSIGNED'
               join institution_course_release r on r.id=a.course_release_id and r.program_id=cp.id
               join curriculum_program_subject cps on cps.program_id=cp.id
               join curriculum_subject s on s.id=cps.subject_id and s.class_id=cps.class_id and s.active=true
                 and (s.program_id=cp.id or (s.program_id is null and cp.class_id=s.class_id))
               join curriculum_chapter ch on ch.subject_id=s.id and ch.active=true
                 and ch.content_status='PUBLISHED'
               join lesson l on l.chapter_id=ch.id and l.active=true and l.status='PUBLISHED'
               join student_lesson_progress lp on lp.lesson_id=l.id and lp.student_id=st.id
                 and lp.institution_id=spe.institution_id and lp.status='COMPLETED'
               where spe.student_id=st.id and spe.institution_id=im.institution_id
                 and spe.program_id=? and spe.status='ACTIVE'),0) as completed_lessons
      from student st
      join institution_membership im on im.student_id=st.id and im.institution_id=?
        and im.status in ('ACTIVE','SUSPENDED')
      where st.environment='PRODUCTION'
        and exists(select 1 from student_program_enrollment selected
          where selected.student_id=st.id and selected.institution_id=im.institution_id
            and selected.program_id=? and selected.status='ACTIVE')
      """+tenantSearch+"""
      order by st.created_at desc,st.id desc limit ? offset ?
      """,courseId,courseId,institutionId,courseId,term,pattern,pattern,rowLimit,rowOffset);
    Long total=jdbc.queryForObject("""
      select count(*) from student st
      join institution_membership im on im.student_id=st.id and im.institution_id=?
        and im.status in ('ACTIVE','SUSPENDED')
      where st.environment='PRODUCTION'
        and exists(select 1 from student_program_enrollment selected
          where selected.student_id=st.id and selected.institution_id=im.institution_id
            and selected.program_id=? and selected.status='ACTIVE')
      """+tenantSearch,Long.class,institutionId,courseId,term,pattern,pattern);
    return Map.of("items",items,"total",total==null?0L:total,"limit",rowLimit,"offset",rowOffset,
        "institutionId",institutionId);
  }

  @GetMapping("/action-log")
  public List<Map<String,Object>> actionLog(@RequestParam(defaultValue="50") int limit,
      @RequestAttribute(value="authContext",required=false) AuthContext context){
    authorization.requireAdmin(context);
    int safe=Math.max(1,Math.min(limit,200));
    return jdbc.query("""
      select l.id,l.action,l.resource,l.detail,l.created_at,u.mobile_e164
      from admin_action_log l join user_account u on u.id=l.admin_user_id
      order by l.created_at desc limit ?
      """,(rs,n)->Map.of(
        "id",rs.getLong("id"),"action",rs.getString("action"),"resource",rs.getString("resource"),
        "detail",rs.getString("detail"),"createdAt",rs.getObject("created_at"),"adminMobile",rs.getString("mobile_e164")),safe);
  }

  @GetMapping("/sources")
  public List<Map<String,Object>> sources(@RequestAttribute(value="authContext",required=false) AuthContext context){
    authorization.requireAdmin(context);
    return jdbc.queryForList("""
      select id,source_kind,title,provider,source_url,edition,language,source_year,status,accessed_at
      from content_source order by source_kind,title
      """);
  }

  @PostMapping("/sources")
  public Map<String,Object> createSource(@RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context){
    AuthContext admin=authorization.requireAdmin(context);
    Long id=jdbc.queryForObject("""
      insert into content_source(source_kind,title,provider,source_url,edition,language,source_year,checksum,status)
      values (?,?,?,?,?,?,?,?,?)
      on conflict(source_kind,title,edition) do update set
        provider=excluded.provider,source_url=excluded.source_url,language=excluded.language,
        source_year=excluded.source_year,checksum=excluded.checksum,status=excluded.status
      returning id
      """,Long.class,
      String.valueOf(body.get("sourceKind")),body.get("title"),body.get("provider"),body.get("sourceUrl"),
      body.get("edition"),String.valueOf(body.getOrDefault("language","hi")),
      body.get("sourceYear")==null?null:Integer.valueOf(String.valueOf(body.get("sourceYear"))),
      body.get("checksum"),String.valueOf(body.getOrDefault("status","REFERENCE")));
    log(admin,"SOURCE_REGISTER",String.valueOf(id),String.valueOf(body.get("title")));
    return Map.of("id",id);
  }

  @GetMapping("/overview")
  public Map<String,Object> overview(
      @RequestParam Long courseId,
      @RequestAttribute(value="authContext",required=false) AuthContext context,
      @RequestAttribute(value=InstitutionTenantContextService.REQUEST_ATTRIBUTE,required=false)
          InstitutionTenantContext tenant){
    authorization.requireAdmin(context);
    long institutionId=requireInstitution(tenant);
    requireAssignedCourse(courseId,institutionId);
    Map<String,Object> result=new LinkedHashMap<>();
    result.put("courseId",courseId);
    result.put("institutionId",institutionId);
    result.put("summary",jdbc.queryForMap("""
      select count(distinct s.id) as classes,
        count(distinct ch.id) filter(where ch.active=true) as chapters,
        count(distinct l.id) filter(where l.active=true and l.status='PUBLISHED'
          and ch.content_status='PUBLISHED') as published_lessons,
        count(distinct q.id) as questions,
        (select count(distinct spe.student_id)
          from student_program_enrollment spe
          join institution_membership im on im.student_id=spe.student_id
            and im.institution_id=spe.institution_id and im.status='ACTIVE'
          join student st on st.id=spe.student_id and st.active=true and st.environment='PRODUCTION'
          where spe.institution_id=? and spe.program_id=? and spe.status='ACTIVE') as students
      from curriculum_program course
      join institution_course_assignment assignment on assignment.institution_id=?
        and assignment.status='ASSIGNED'
      join institution_course_release release on release.id=assignment.course_release_id
        and release.program_id=course.id
      join curriculum_program_subject cps on cps.program_id=course.id
      join curriculum_subject s on s.id=cps.subject_id and s.class_id=cps.class_id and s.active=true
        and (s.program_id=course.id or (s.program_id is null and course.class_id=s.class_id))
      left join curriculum_chapter ch on ch.subject_id=s.id and ch.active=true
      left join lesson l on l.chapter_id=ch.id and l.active=true
      left join question q on q.lesson_id=l.id and q.active=true
      where course.id=? and course.status='ACTIVE' and course.code<>'GENERAL'
      """,institutionId,courseId,institutionId,courseId));
    result.put("review",jdbc.queryForList("""
      select coalesce(nullif(upper(q.review_status),''),'DRAFT') as review_status,
             q.active,count(distinct q.id) as count
      from curriculum_program course
      join curriculum_program_subject cps on cps.program_id=course.id
      join curriculum_subject s on s.id=cps.subject_id and s.class_id=cps.class_id and s.active=true
        and (s.program_id=course.id or (s.program_id is null and course.class_id=s.class_id))
      join curriculum_chapter ch on ch.subject_id=s.id
      join lesson l on l.chapter_id=ch.id
      join question q on q.lesson_id=l.id
      where course.id=? and course.status='ACTIVE' and course.code<>'GENERAL'
      group by coalesce(nullif(upper(q.review_status),''),'DRAFT'),q.active
      order by review_status,q.active desc
      """,courseId));
    result.put("sources",jdbc.queryForList("""
      select coalesce(nullif(q.source_kind,''),'UNKNOWN') as source_kind,count(distinct q.id) as count
      from curriculum_program course
      join curriculum_program_subject cps on cps.program_id=course.id
      join curriculum_subject s on s.id=cps.subject_id and s.class_id=cps.class_id and s.active=true
        and (s.program_id=course.id or (s.program_id is null and course.class_id=s.class_id))
      join curriculum_chapter ch on ch.subject_id=s.id
      join lesson l on l.chapter_id=ch.id
      join question q on q.lesson_id=l.id
      where course.id=? and course.status='ACTIVE' and course.code<>'GENERAL'
      group by coalesce(nullif(q.source_kind,''),'UNKNOWN') order by source_kind
      """,courseId));
    result.put("assets",jdbc.queryForList("""
      select ca.asset_type,ca.status,count(distinct ca.id) as count
      from curriculum_program course
      join curriculum_program_subject cps on cps.program_id=course.id
      join curriculum_subject s on s.id=cps.subject_id and s.class_id=cps.class_id and s.active=true
        and (s.program_id=course.id or (s.program_id is null and course.class_id=s.class_id))
      join curriculum_chapter ch on ch.subject_id=s.id
      join lesson l on l.chapter_id=ch.id
      join lesson_block lb on lb.lesson_id=l.id
      join content_asset ca on ca.id=lb.asset_id
      where course.id=? and course.status='ACTIVE' and course.code<>'GENERAL'
      group by ca.asset_type,ca.status order by ca.asset_type,ca.status
      """,courseId));
    return result;
  }

  @GetMapping("/lessons/{lessonId}")
  public Map<String,Object> lesson(@PathVariable long lessonId,@RequestAttribute(value="authContext",required=false) AuthContext context){
    authorization.requireAdmin(context);
    Map<String,Object> result=new LinkedHashMap<>();
    result.put("lesson",jdbc.queryForMap("""
      select l.*,ch.display_name as chapter_name,c.code as class_code,s.code as subject_code
      from lesson l join curriculum_chapter ch on ch.id=l.chapter_id
      join curriculum_subject s on s.id=ch.subject_id join curriculum_class c on c.id=s.class_id
      where l.id=?
      """,lessonId));
    result.put("blocks",jdbc.queryForList("""
      select b.id,b.sequence_no,b.block_type,b.content,b.asset_id,a.url as asset_url
      from lesson_block b left join content_asset a on a.id=b.asset_id where b.lesson_id=? order by b.sequence_no
      """,lessonId));
    result.put("questions",jdbc.queryForList("""
      select q.id,q.question_type,q.prompt,q.explanation,q.difficulty,q.marks,q.exam_format,
             q.source_kind,q.source_title,q.source_ref,q.source_year,q.board,q.topic,q.subtopic,q.skill,
             q.tags::text as tags,q.answer_payload::text as answer_payload,q.review_status
      from question q where q.lesson_id=? and q.active=true order by q.sort_order
      """,lessonId));
    return result;
  }

  @GetMapping("/questions")
  public List<Map<String,Object>> questions(@RequestParam(required=false) Long lessonId,
      @RequestAttribute(value="authContext",required=false) AuthContext context){
    authorization.requireAdmin(context);
    if(lessonId==null) return jdbc.queryForList("select id,lesson_id,question_type,prompt,review_status,source_kind,source_year,board from question where active=true order by lesson_id,sort_order");
    return jdbc.queryForList("select id,lesson_id,question_type,prompt,explanation,difficulty,marks,exam_format,source_kind,source_title,source_ref,source_year,board,topic,subtopic,skill,tags::text as tags,answer_payload::text as answer_payload,review_status from question where lesson_id=? and active=true order by sort_order",lessonId);
  }

  @PutMapping("/lessons/{lessonId}/blocks/{blockId}/asset")
  public Map<String,Object> attachAsset(@PathVariable long lessonId,@PathVariable long blockId,
      @RequestBody Map<String,Object> body,@RequestAttribute(value="authContext",required=false) AuthContext context){
    Long assetId=null;
    LOGGER.info("ATTACH_ASSET_DIAGNOSTIC start lessonId={} blockId={}",lessonId,blockId);
    try {
      AuthContext admin=authorization.requireAdmin(context);
      LOGGER.info("ATTACH_ASSET_DIAGNOSTIC authorized adminUserId={}",admin.userId());
      assetId=Long.parseLong(String.valueOf(body.get("assetId")));
      Long assetCount=jdbc.queryForObject("select count(*) from content_asset where id=?",Long.class,assetId);
      LOGGER.info("ATTACH_ASSET_DIAGNOSTIC assetLookup assetId={} count={}",assetId,assetCount);
      if(assetCount==null||assetCount==0) throw new IllegalArgumentException("Asset not found");
      int changed=jdbc.update("""
        update lesson_block set asset_id=?
        where id=? and lesson_id=? and active=true
        """,assetId,blockId,lessonId);
      LOGGER.info("ATTACH_ASSET_DIAGNOSTIC blockUpdate lessonId={} blockId={} changed={}",lessonId,blockId,changed);
      if(changed==0) throw new IllegalArgumentException("Lesson block not found");
      log(admin,"BLOCK_ASSET_ATTACH",String.valueOf(blockId),"asset="+assetId);
      LOGGER.info("ATTACH_ASSET_DIAGNOSTIC auditLogged lessonId={} blockId={} assetId={}",lessonId,blockId,assetId);
      return Map.of("updated",true,"lessonId",lessonId,"blockId",blockId,"assetId",assetId);
    } catch (RuntimeException ex) {
      LOGGER.error("ATTACH_ASSET_DIAGNOSTIC failed lessonId={} blockId={} assetId={}",lessonId,blockId,assetId,ex);
      throw ex;
    }
  }

  @PutMapping("/lessons/{lessonId}/status")
  @Transactional
  public Map<String,Object> updateLessonStatus(@PathVariable long lessonId,@RequestParam Long courseId,
      @RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context,
      @RequestAttribute(value=InstitutionTenantContextService.REQUEST_ATTRIBUTE,required=false)
          InstitutionTenantContext tenant){
    AuthContext admin=authorization.requireAdmin(context);
    long institutionId=requireInstitution(tenant);
    requireAssignedCourse(courseId,institutionId);
    String status=String.valueOf(body.getOrDefault("status","DRAFT"));
    if(!Set.of("DRAFT","REVIEW","PUBLISHED","ARCHIVED").contains(status)) {
      throw new IllegalArgumentException("Invalid lesson status");
    }
    Long mapped=jdbc.query("""
      select l.id from lesson l
      join curriculum_chapter ch on ch.id=l.chapter_id and ch.active=true
      join curriculum_subject s on s.id=ch.subject_id and s.active=true
      join curriculum_program_subject cps on cps.subject_id=s.id and cps.program_id=?
      join student_program_enrollment spe on spe.program_id=cps.program_id and spe.institution_id=?
        and spe.status='ACTIVE'
      join institution_course_assignment a on a.institution_id=spe.institution_id and a.status='ASSIGNED'
      join institution_course_release rel on rel.id=a.course_release_id and rel.program_id=spe.program_id
      where l.id=? and l.active=true
      limit 1
      """,rs->rs.next()?rs.getLong(1):null,courseId,institutionId,lessonId);
    if(mapped==null) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Lesson is not part of the selected institution course.");
    if("PUBLISHED".equals(status)) {
      contentController.publishLesson(lessonId,courseId,context,tenant);
      log(admin,"LESSON_STATUS",String.valueOf(lessonId),"courseId="+courseId+";"+status);
      return Map.of("updated",true,"lessonId",lessonId,"courseId",courseId,"status",status);
    }
    if("REVIEW".equals(status)) {
      contentController.submitLessonForReview(lessonId,courseId,context,tenant);
      log(admin,"LESSON_STATUS",String.valueOf(lessonId),"courseId="+courseId+";"+status);
      return Map.of("updated",true,"lessonId",lessonId,"courseId",courseId,"status",status);
    }
    int changed=jdbc.update("""
      update lesson l set status=?,active=? where l.id=? and exists(
        select 1 from curriculum_chapter ch
        join curriculum_subject s on s.id=ch.subject_id
        join curriculum_program_subject cps on cps.subject_id=s.id and cps.program_id=?
        where ch.id=l.chapter_id)
      """,status,!"ARCHIVED".equals(status),lessonId,courseId);
    if(changed==0) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Lesson is not part of the selected course.");
    log(admin,"LESSON_STATUS",String.valueOf(lessonId),"courseId="+courseId+";"+status);
    return Map.of("updated",true,"lessonId",lessonId,"courseId",courseId,"status",status);
  }

  @PutMapping("/questions/{questionId}")
  @Transactional
  public Map<String,Object> updateQuestion(@PathVariable long questionId,@RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context){
    AuthContext admin=authorization.requireAdmin(context);
    String sourceKind=String.valueOf(body.getOrDefault("sourceKind","AUTHOR_CREATED"));
    Integer sourceYear=body.get("sourceYear")==null?null:Integer.valueOf(String.valueOf(body.get("sourceYear")));
    String sourceRef=body.get("sourceRef")==null?null:String.valueOf(body.get("sourceRef"));
    Long sourceId=body.get("sourceId")==null?null:Long.valueOf(String.valueOf(body.get("sourceId")));
    if(!"AUTHOR_CREATED".equals(sourceKind) && (sourceRef==null || sourceRef.isBlank() || sourceYear==null || sourceId==null))
      throw new IllegalArgumentException("Source-backed questions require sourceId, sourceRef and sourceYear");
    if(sourceId!=null && jdbc.queryForObject("select count(*) from content_source where id=?",Long.class,sourceId)==0)
      throw new IllegalArgumentException("Registered content source not found");
    int changed=jdbc.update("""
      update question set prompt=?,explanation=?,difficulty=?,marks=?,exam_format=?,source_kind=?,
        source_title=?,source_ref=?,source_year=?,source_id=?,board=?,topic=?,subtopic=?,skill=?,
        review_status='DRAFT',review_notes='Question content changed; review and approve again before publishing.',
        reviewed_at=null,reviewed_by_staff_id=null
      where id=?
      """,
      body.get("prompt"),body.get("explanation"),String.valueOf(body.getOrDefault("difficulty","CORE")),
      body.get("marks")==null?null:Integer.valueOf(String.valueOf(body.get("marks"))),
      body.get("examFormat"),sourceKind,body.get("sourceTitle"),sourceRef,sourceYear,sourceId,body.get("board"),
      body.get("topic"),body.get("subtopic"),body.get("skill"),questionId);
    log(admin,"QUESTION_UPDATE",String.valueOf(questionId),sourceKind+"/"+sourceRef);
    return Map.of("updated",changed>0,"questionId",questionId);
  }

  @PostMapping("/questions/import")
  @Transactional
  public Map<String,Object> importQuestions(@RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context){
    AuthContext admin=authorization.requireAdmin(context);
    long lessonId=Long.parseLong(String.valueOf(body.get("lessonId")));
    String sourceKind=String.valueOf(body.getOrDefault("sourceKind","AUTHOR_CREATED"));
    String sourceTitle=body.get("sourceTitle")==null?null:String.valueOf(body.get("sourceTitle"));
    String sourceRef=body.get("sourceRef")==null?null:String.valueOf(body.get("sourceRef"));
    Integer sourceYear=body.get("sourceYear")==null?null:Integer.valueOf(String.valueOf(body.get("sourceYear")));
    Long sourceId=body.get("sourceId")==null?null:Long.valueOf(String.valueOf(body.get("sourceId")));
    String board=body.get("board")==null?null:String.valueOf(body.get("board"));
    if(!"AUTHOR_CREATED".equals(sourceKind) && (sourceRef==null||sourceRef.isBlank()||sourceYear==null||board==null||board.isBlank()||sourceId==null))
      throw new IllegalArgumentException("Source-backed imports require sourceId, sourceRef, sourceYear and board");
    if(sourceId!=null && jdbc.queryForObject("select count(*) from content_source where id=?",Long.class,sourceId)==0)
      throw new IllegalArgumentException("Registered content source not found");

    List<Map<String,Object>> items=(List<Map<String,Object>>)body.getOrDefault("questions",List.of());
    int imported=0;
    for(Map<String,Object> item:items){
      String answerPayload=item.get("answerPayload")==null?"{}":(
          item.get("answerPayload") instanceof String s ? s : toJson(item.get("answerPayload")));
      String tags=toJson(item.getOrDefault("tags",List.of()));
      Long qid=jdbc.queryForObject("""
        insert into question(lesson_id,question_type,prompt,explanation,difficulty,sort_order,source_kind,source_title,source_ref,source_year,source_id,board,marks,exam_format,topic,subtopic,skill,tags,answer_payload,review_status)
        values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?::jsonb,'DRAFT') returning id
        """,Long.class,lessonId,String.valueOf(item.getOrDefault("questionType","SHORT_ANSWER")),
        item.get("prompt"),item.get("explanation"),String.valueOf(item.getOrDefault("difficulty","CORE")),
        Integer.valueOf(String.valueOf(item.getOrDefault("sortOrder",imported+1))),sourceKind,sourceTitle,sourceRef,sourceYear,sourceId,board,
        item.get("marks")==null?null:Integer.valueOf(String.valueOf(item.get("marks"))),item.get("examFormat"),
        item.get("topic"),item.get("subtopic"),item.get("skill"),tags,answerPayload);
      Object options=item.get("options");
      if(options instanceof List<?> list){
        int ord=1; for(Object opt:list){
          if(opt instanceof Map<?,?> om){
            jdbc.update("insert into question_option(question_id,option_key,label,is_correct,sort_order) values (?,?,?,?,?)",
                qid,String.valueOf(om.get("key")),String.valueOf(om.get("label")),Boolean.parseBoolean(String.valueOf(om.containsKey("correct") ? om.get("correct") : Boolean.FALSE)),ord++);
          }
        }
      }
      imported++;
    }
    log(admin,"QUESTION_IMPORT",String.valueOf(lessonId),"count="+imported+"/"+sourceKind);
    return Map.of("imported",imported,"lessonId",lessonId);
  }

  @GetMapping("/assets")
  public List<Map<String,Object>> assets(@RequestAttribute(value="authContext",required=false) AuthContext context){
    authorization.requireAdmin(context);
    return jdbc.queryForList("""
      select id,asset_type,title,alt_text,url,source_kind,source_title,source_ref,license_note,status,created_at
      from content_asset order by created_at desc
      """);
  }

  @PostMapping("/assets")
  public Map<String,Object> createAsset(@RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context){
    AuthContext admin=authorization.requireAdmin(context);
    Long id=jdbc.queryForObject("""
      insert into content_asset(asset_type,title,alt_text,url,source_kind,source_title,source_ref,license_note,status)
      values (?,?,?,?,?,?,?,?,?)
      returning id
      """,Long.class,String.valueOf(body.getOrDefault("assetType","IMAGE")),body.get("title"),body.get("altText"),
      body.get("url"),String.valueOf(body.getOrDefault("sourceKind","AUTHOR_CREATED")),body.get("sourceTitle"),
      body.get("sourceRef"),body.get("licenseNote"),String.valueOf(body.getOrDefault("status","DRAFT")));
    log(admin,"ASSET_CREATE",String.valueOf(id),String.valueOf(body.get("title")));
    return Map.of("id",id);
  }

  @PutMapping("/assets/{assetId}/status")
  public Map<String,Object> assetStatus(@PathVariable long assetId,@RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context){
    AuthContext admin=authorization.requireAdmin(context);
    String status=String.valueOf(body.getOrDefault("status","REVIEW"));
    int changed=jdbc.update("update content_asset set status=?,updated_at=now() where id=?",status,assetId);
    log(admin,"ASSET_STATUS",String.valueOf(assetId),status);
    return Map.of("updated",changed>0,"status",status);
  }

  private String toJson(Object value){
    try{
      return value instanceof String s ? s : mapper.writeValueAsString(value);
    }catch(JacksonException ex){
      throw new IllegalArgumentException("Invalid JSON content");
    }
  }

  private void requireAssignedCourse(long courseId,long institutionId) {
    Long allowed=jdbc.queryForObject("""
      select count(*) from curriculum_program p
      join institution_course_assignment assignment
        on assignment.institution_id=? and assignment.status='ASSIGNED'
      join institution_course_release release
        on release.id=assignment.course_release_id and release.program_id=p.id
      where p.id=? and p.status='ACTIVE' and p.code<>'GENERAL'
      """,Long.class,institutionId,courseId);
    if(allowed==null || allowed<1L) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND,
          "Selected course is not assigned and released to this institution.");
    }
  }

  private long requireInstitution(InstitutionTenantContext tenant) {
    if(tenant==null || tenant.institutionId()<1L) {
      throw new InstitutionTenantContextService.InvalidInstitutionContextException(
          400,"INSTITUTION_CONTEXT_REQUIRED","Select the institution before accessing administration data.");
    }
    return tenant.institutionId();
  }

  private void log(AuthContext admin,String action,String resource,String detail){
    if(admin.staffId()!=null) {
      staffAudit.recordAction(admin,"/api/v1/admin/"+action.toLowerCase(java.util.Locale.ROOT),
          action+" | resource="+resource+" | "+detail);
    } else if(admin.userId()!=null) {
      jdbc.update("insert into admin_action_log(admin_user_id,action,resource,detail) values (?,?,?,?)",
          admin.userId(),action,resource,detail);
    }
  }
}
