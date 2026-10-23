package com.quantaedge.api;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Shared source-course creation path for Education Admin and the institution portal.
 * The authenticated caller supplies the institution and actor; request JSON cannot supply either.
 * Course type configuration is read from course_type_registry, while the source release always
 * starts as DRAFT and requires the ordinary HRMS release/Marketplace lifecycle before distribution.
 */
@Service
public class CourseAuthoringService {
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;

  public CourseAuthoringService(JdbcTemplate jdbc,ObjectMapper mapper) {
    this.jdbc=jdbc;
    this.mapper=mapper;
  }

  @Transactional
  public Map<String,Object> createSourceCourse(
      long institutionId,String tenantCode,Long actorStaffId,Long actorInstitutionAdminId,
      Map<String,Object> body) {
    if(institutionId<1L) throw badRequest("A valid authenticated institution is required.");
    if(body==null) throw badRequest("Provide the course definition.");
    String type=optionalText(body.get("courseTypeCode"),"K12",32,"Course type").toUpperCase(java.util.Locale.ROOT);
    List<Map<String,Object>> types=jdbc.queryForList("""
      select code,requires_curriculum_class,status
      from course_type_registry where code=? and status='ACTIVE'
      """,type);
    if(types.isEmpty()) throw badRequest("Select an active course type registered in HRMS.");
    boolean requiresClass=Boolean.TRUE.equals(types.getFirst().get("requires_curriculum_class"));

    String requestedCode=requiredText(body.get("code"),2,38,"Course code").toUpperCase(java.util.Locale.ROOT);
    if(!requestedCode.matches("^[A-Z0-9][A-Z0-9_-]{1,37}$"))
      throw badRequest("Course code must use 2–38 letters, digits, hyphens or underscores.");
    String displayName=requiredText(body.get("displayName"),2,200,"Course name");
    String version=optionalText(body.get("curriculumVersion"),"CURRENT",120,"Curriculum version");
    String stream=optionalText(body.get("streamCode"),null,60,"Stream code");
    String classCode=optionalText(body.get("classCode"),null,20,"Class code");

    Long classId=null;
    if(classCode!=null) {
      List<Map<String,Object>> classRows=jdbc.queryForList(
          "select id from curriculum_class where code=? and active=true",classCode);
      if(classRows.isEmpty()) throw badRequest("Select an active curriculum class.");
      classId=((Number)classRows.getFirst().get("id")).longValue();
    }
    if(requiresClass&&classId==null)
      throw badRequest("The "+type+" course type requires an active curriculum class.");

    List<Map<String,Object>> subjects=parseSubjects(body.get("subjects"));
    if("K12".equals(type)&&subjects.isEmpty())
      throw badRequest("A K–12 course needs at least one subject track.");
    if(subjects.size()>30)
      throw badRequest("Add no more than 30 subject tracks to one course.");
    if(classId==null&&!subjects.isEmpty())
      throw badRequest("Subject tracks require a curriculum class. For an independent course, use its type-defined sections, topics, question banks and test packs instead.");

    String internalCode="I"+institutionId+"_"+requestedCode;
    Long courseId;
    String publicId;
    Long releaseId;
    Long assignmentId;
    try {
      courseId=jdbc.queryForObject("""
        insert into curriculum_program(
          class_id,code,display_name,stream_code,curriculum_version,status,notes,public_id_prefix,course_type_code
        ) values(?,?,?,?,?,'ACTIVE',?,'INS',?) returning id
        """,Long.class,classId,internalCode,displayName,stream,version,
        "Institution-origin "+type+" course for tenant "+tenantCode,type);
      if(courseId==null) throw new IllegalStateException("The course could not be created.");
      publicId=jdbc.queryForObject("select public_id from curriculum_program where id=?",String.class,courseId);

      for(int i=0;i<subjects.size();i++) {
        Map<String,Object> subject=subjects.get(i);
        int order=i+1;
        Long subjectId=jdbc.queryForObject("""
          insert into curriculum_subject(class_id,code,display_name,sort_order,active,program_id)
          values(?,?,?,?,true,?) returning id
          """,Long.class,classId,subject.get("code"),subject.get("displayName"),order,courseId);
        jdbc.update("""
          insert into curriculum_program_subject(program_id,class_id,subject_id,is_required,sort_order)
          values(?,?,?,true,?)
          """,courseId,classId,subjectId,order);
      }

      String fingerprint=jdbc.queryForObject("select course_content_fingerprint(?)",String.class,courseId);
      releaseId=jdbc.queryForObject("""
        insert into institution_course_release(
          source_institution_id,program_id,release_version,release_state,content_fingerprint,
          legacy_migration,created_at,updated_at
        ) values(?,?,1,'DRAFT',?,false,now(),now()) returning id
        """,Long.class,institutionId,courseId,fingerprint==null?"":fingerprint.trim());
      assignmentId=jdbc.queryForObject("""
        insert into institution_course_assignment(
          institution_id,course_release_id,status,assignment_source,assigned_at,created_at,updated_at
        ) values(?,?,'ASSIGNED','ORIGIN',now(),now(),now()) returning id
        """,Long.class,institutionId,releaseId);

      jdbc.update("""
        insert into institution_course_lifecycle_event(
          institution_id,course_release_id,assignment_id,offer_id,event_type,
          actor_platform_staff_id,actor_institution_admin_id,details
        ) values(?,?,?,null,'COURSE_CREATED',?,?,?::jsonb)
        """,institutionId,releaseId,assignmentId,actorStaffId,actorInstitutionAdminId,
        json(Map.of("courseId",courseId,"courseCode",internalCode,"publicId",publicId,
          "courseTypeCode",type,"subjectCount",subjects.size(),"courseIdentity","SOURCED")));
    } catch(DuplicateKeyException ex) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "A course with that code and curriculum version already exists for this institution.",ex);
    }

    Map<String,Object> result=new LinkedHashMap<>();
    result.put("created",true);
    result.put("courseId",courseId);
    result.put("publicId",publicId);
    result.put("courseCode",internalCode);
    result.put("displayName",displayName);
    result.put("courseTypeCode",type);
    result.put("curriculumClassCode",classCode);
    result.put("curriculumVersion",version);
    result.put("subjectCount",subjects.size());
    result.put("releaseId",releaseId);
    result.put("releaseVersion",1);
    result.put("releaseStatus","DRAFT");
    result.put("courseRelationship","SOURCED");
    result.put("assignmentId",assignmentId);
    result.put("message","Course created as a sourced draft. It is not listed in any Marketplace until the HRMS release and Marketplace approval steps are completed.");
    return result;
  }

  private List<Map<String,Object>> parseSubjects(Object raw) {
    if(raw==null)return List.of();
    if(!(raw instanceof List<?> values))throw badRequest("subjects must be a JSON array.");
    if(values.size()>30)throw badRequest("Add no more than 30 subject tracks to one course.");
    List<Map<String,Object>> result=new ArrayList<>();
    Set<String> seen=new HashSet<>();
    for(Object item:values) {
      if(!(item instanceof Map<?,?> map))throw badRequest("Each subject needs a code and display name.");
      String code=requiredText(map.get("code"),2,40,"Subject code").toLowerCase(java.util.Locale.ROOT);
      if(!code.matches("^[a-z0-9][a-z0-9_-]{1,39}$")||!seen.add(code))
        throw badRequest("Subject codes must be unique and use lowercase letters, digits, hyphens or underscores.");
      String name=requiredText(map.get("displayName"),2,100,"Subject name");
      result.add(Map.of("code",code,"displayName",name));
    }
    return result;
  }

  private String json(Object value) {
    try { return mapper.writeValueAsString(value); }
    catch(JacksonException ex) { throw new IllegalArgumentException("Course lifecycle details could not be serialized.",ex); }
  }

  private static String requiredText(Object raw,int min,int max,String name) {
    String value=raw==null?"":String.valueOf(raw).trim();
    if(value.length()<min||value.length()>max)
      throw badRequest(name+" must contain "+min+"–"+max+" characters.");
    return value;
  }

  private static String optionalText(Object raw,String fallback,int max,String name) {
    if(raw==null)return fallback;
    String value=String.valueOf(raw).trim();
    if(value.isEmpty())return fallback;
    if(value.length()>max)throw badRequest(name+" must be no longer than "+max+" characters.");
    return value;
  }

  private static ResponseStatusException badRequest(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);
  }
}
