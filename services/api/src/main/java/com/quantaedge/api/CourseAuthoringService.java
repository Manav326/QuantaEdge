package com.quantaedge.api;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

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
    if(!"K12".equals(type))
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
          "This course creation endpoint currently supports K12 only. Competitive and standalone assessment courses require their type-specific hierarchy importer.");

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
    Long courseIdentityId;
    Long courseId;
    String publicId;
    Long releaseId;
    Long assignmentId;
    try {
      // Stable logical identity belongs to the source institution. Every later content edit must
      // fork a new curriculum_program graph linked to this identity instead of mutating a release.
      courseIdentityId=jdbc.queryForObject("""
        insert into institution_course_identity(
          source_institution_id,stable_course_key,display_name,course_type_code,
          created_by_staff_id,created_by_institution_admin_id
        ) values(?,?,?,?,?,?) returning id
        """,Long.class,institutionId,internalCode,displayName,type,actorStaffId,actorInstitutionAdminId);
      if(courseIdentityId==null) throw new IllegalStateException("The stable course identity could not be created.");

      courseId=jdbc.queryForObject("""
        insert into curriculum_program(
          class_id,code,display_name,stream_code,curriculum_version,status,notes,public_id_prefix,
          course_type_code,course_identity_id,content_version_no
        ) values(?,?,?,?,?,'ACTIVE',?,'INS',?,?,1) returning id
        """,Long.class,classId,internalCode,displayName,stream,version,
        "Institution-origin "+type+" course for tenant "+tenantCode,type,courseIdentityId);
      if(courseId==null) throw new IllegalStateException("The course could not be created.");
      publicId=jdbc.queryForObject("select public_id from curriculum_program where id=?",String.class,courseId);

      for(int i=0;i<subjects.size();i++) {
        Map<String,Object> subject=subjects.get(i);
        int order=((Number)subject.get("sortOrder")).intValue();
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
          "courseTypeCode",type,"subjectCount",subjects.size(),"courseIdentityId",courseIdentityId,
          "courseIdentity","SOURCED","contentVersionNo",1)));
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


  /**
   * Validate the portable nested course package without creating database records.
   * Version 1 is intentionally K12-shaped; other course types need their registered type-specific
   * hierarchy importer, not an accidental mapping into school subjects and chapters.
   */
  public Map<String,Object> validateCoursePackage(Map<String,Object> body) {
    List<String> errors=coursePackageErrors(body);
    Map<String,Object> result=new LinkedHashMap<>();
    result.put("valid",errors.isEmpty());
    result.put("schema","quantaedge.course-package.v1");
    result.put("errors",errors);
    if(errors.isEmpty()) {
      @SuppressWarnings("unchecked")
      Map<String,Object> course=(Map<String,Object>)body.get("course");
      @SuppressWarnings("unchecked")
      List<Map<String,Object>> subjects=(List<Map<String,Object>>)body.get("subjects");
      int chapters=0,lessons=0,blocks=0;
      for(Map<String,Object> subject:subjects) {
        @SuppressWarnings("unchecked")
        List<Map<String,Object>> subjectChapters=(List<Map<String,Object>>)subject.get("chapters");
        chapters+=subjectChapters.size();
        for(Map<String,Object> chapter:subjectChapters) {
          @SuppressWarnings("unchecked")
          List<Map<String,Object>> chapterLessons=(List<Map<String,Object>>)chapter.get("lessons");
          lessons+=chapterLessons.size();
          for(Map<String,Object> lesson:chapterLessons) {
            @SuppressWarnings("unchecked")
            List<Map<String,Object>> lessonBlocks=(List<Map<String,Object>>)lesson.get("blocks");
            blocks+=lessonBlocks.size();
          }
        }
      }
      result.put("packageId",body.get("package_id"));
      result.put("packageVersion",body.get("package_version"));
      result.put("courseName",course.get("display_name"));
      result.put("courseTypeCode",course.get("course_type_code"));
      result.put("subjectCount",subjects.size());
      result.put("chapterCount",chapters);
      result.put("lessonCount",lessons);
      result.put("blockCount",blocks);
      result.put("releaseStatus","DRAFT");
      result.put("studentDeliveryAllowed",false);
      result.put("publicationRequiresHRMS",true);
    }
    return result;
  }

  /**
   * Package JSON is authored outside the HRMS approval workflow. Only informational notes are
   * retained at import; the authoritative rights state always starts unconfirmed. HRMS alone
   * can change it after recording reviewer attribution, notes and HTTPS evidence.
   */
  static Map<String,Object> editorialMetadataForImport(Object raw) {
    Map<String,Object> supplied;
    if(raw instanceof Map<?,?> map) supplied=stringMap(map);
    else supplied=Map.of();
    Map<String,Object> trusted=new LinkedHashMap<>();
    Object notes=supplied.get("notes");
    if(notes instanceof String) trusted.put("notes",notes);
    trusted.put("source_rights_status","UNCONFIRMED");
    return trusted;
  }

  @Transactional
  public Map<String,Object> importCoursePackage(
      long institutionId,String tenantCode,Long actorStaffId,Map<String,Object> body) {
    Map<String,Object> validation=validateCoursePackage(body);
    if(!Boolean.TRUE.equals(validation.get("valid"))) {
      throw badRequest("Course package validation failed: "+String.join(" ",(List<String>)validation.get("errors")));
    }
    String packageId=String.valueOf(body.get("package_id"));
    String packageVersion=String.valueOf(body.get("package_version"));
    String digest=sha256(canonicalJson(body));
    int reserved=jdbc.update("""
      insert into institution_course_package_import(
        source_institution_id,package_id,package_version,package_sha256,state,imported_by_staff_id,editorial_metadata
      ) values(?,?,?,?,'IMPORTING',?,?::jsonb)
      on conflict(source_institution_id,package_id,package_version) do nothing
      """,institutionId,packageId,packageVersion,digest,actorStaffId,json(editorialMetadataForImport(body.get("editorial"))));
    if(reserved==0) {
      List<Map<String,Object>> prior=jdbc.queryForList("""
        select package_sha256,state,course_id,release_id,import_summary
        from institution_course_package_import
        where source_institution_id=? and package_id=? and package_version=?
      """,institutionId,packageId,packageVersion);
      if(prior.isEmpty()) throw conflict("The package idempotency reservation changed unexpectedly; retry this import.");
      Map<String,Object> row=prior.getFirst();
      if(!digest.equalsIgnoreCase(String.valueOf(row.get("package_sha256"))))
        throw conflict("This package ID/version was already used for different content. Increment package_version to import a changed package.");
      if(!"IMPORTED".equals(String.valueOf(row.get("state"))))
        throw conflict("This package import is already in progress.");
      Map<String,Object> replay=new LinkedHashMap<>();
      replay.put("created",false);
      replay.put("replayed",true);
      replay.put("courseId",row.get("course_id"));
      replay.put("releaseId",row.get("release_id"));
      replay.put("importSummary",row.get("import_summary"));
      replay.put("releaseStatus","DRAFT");
      replay.put("studentDeliveryAllowed",false);
      return replay;
    }

    @SuppressWarnings("unchecked")
    Map<String,Object> course=(Map<String,Object>)body.get("course");
    @SuppressWarnings("unchecked")
    List<Map<String,Object>> packageSubjects=(List<Map<String,Object>>)body.get("subjects");
    List<Map<String,Object>> simpleSubjects=new ArrayList<>();
    for(Map<String,Object> subject:packageSubjects) {
      simpleSubjects.add(Map.of("code",subject.get("code"),"displayName",subject.get("display_name"),
          "sortOrder",subject.get("sort_order")==null?simpleSubjects.size()+1:subject.get("sort_order")));
    }
    Map<String,Object> createBody=new LinkedHashMap<>();
    createBody.put("code",course.get("code"));
    createBody.put("displayName",course.get("display_name"));
    createBody.put("courseTypeCode",course.get("course_type_code"));
    createBody.put("curriculumVersion",course.get("curriculum_version"));
    createBody.put("classCode",course.get("class_code"));
    createBody.put("streamCode",course.get("stream_code"));
    createBody.put("subjects",simpleSubjects);
    Map<String,Object> created=createSourceCourse(
        institutionId,tenantCode,actorStaffId,null,createBody);
    long courseId=number(created.get("courseId"));
    long releaseId=number(created.get("releaseId"));

    int chapterCount=0,lessonCount=0,blockCount=0;
    for(int subjectIndex=0;subjectIndex<packageSubjects.size();subjectIndex++) {
      Map<String,Object> subject=packageSubjects.get(subjectIndex);
      Long subjectId=jdbc.queryForObject("""
        select id from curriculum_subject where program_id=? and code=?
      """,Long.class,courseId,subject.get("code"));
      @SuppressWarnings("unchecked")
      List<Map<String,Object>> chapters=(List<Map<String,Object>>)subject.get("chapters");
      for(int chapterIndex=0;chapterIndex<chapters.size();chapterIndex++) {
        Map<String,Object> chapter=chapters.get(chapterIndex);
        int chapterOrder=intOrDefault(chapter.get("sort_order"),chapterIndex+1,1,10000,"chapter.sort_order");
        Map<String,Object> source=optionalSource(chapter.get("source"),"chapter.source");
        Long chapterId=jdbc.queryForObject("""
          insert into curriculum_chapter(
            subject_id,code,display_name,description,sort_order,teaching_order,active,content_status,
            curriculum_source,curriculum_source_url,curriculum_source_edition,curriculum_source_pages,
            curriculum_source_verified
          ) values(?,?,?,?,?,?,true,'DRAFT',?,?,?, ?,false)
          returning id
        """,Long.class,subjectId,chapter.get("code"),chapter.get("display_name"),chapter.get("description"),
          chapterOrder,chapterOrder,source.get("title"),source.get("url"),source.get("edition"),source.get("pages"));
        chapterCount++;
        @SuppressWarnings("unchecked")
        List<Map<String,Object>> lessons=(List<Map<String,Object>>)chapter.get("lessons");
        for(int lessonIndex=0;lessonIndex<lessons.size();lessonIndex++) {
          Map<String,Object> lesson=lessons.get(lessonIndex);
          int lessonOrder=intOrDefault(lesson.get("sort_order"),lessonIndex+1,1,10000,"lesson.sort_order");
          int minutes=intOrDefault(lesson.get("estimated_minutes"),10,1,120,"lesson.estimated_minutes");
          Map<String,Object> lessonSource=optionalSource(lesson.get("source"),"lesson.source");
          Long lessonId=jdbc.queryForObject("""
            insert into lesson(
              chapter_id,code,title,summary,estimated_minutes,status,sort_order,active,
              alignment_source_title,alignment_source_url,alignment_source_edition,alignment_page_range,
              alignment_source_verified
            ) values(?,?,?,?,?,'DRAFT',?,true,?,?,?,?,false)
            returning id
          """,Long.class,chapterId,lesson.get("code"),lesson.get("title"),lesson.get("summary"),
            minutes,lessonOrder,lessonSource.get("title"),lessonSource.get("url"),
            lessonSource.get("edition"),lessonSource.get("pages"));
          lessonCount++;
          @SuppressWarnings("unchecked")
          List<Map<String,Object>> blocks=(List<Map<String,Object>>)lesson.get("blocks");
          for(Map<String,Object> block:blocks) {
            jdbc.update("""
              insert into lesson_block(lesson_id,sequence_no,block_type,content,active)
              values(?,?,?,?::jsonb,?)
            """,lessonId,block.get("sequence_no"),block.get("block_type"),
              json(block.get("content")),!Boolean.FALSE.equals(block.get("active")));
            blockCount++;
          }
        }
      }
    }

    String fingerprint=jdbc.queryForObject("select course_content_fingerprint(?)",String.class,courseId);
    int releaseUpdated=jdbc.update("""
      update institution_course_release
      set content_fingerprint=?,updated_at=now()
      where id=? and source_institution_id=? and release_state='DRAFT'
    """,fingerprint==null?"":fingerprint.trim(),releaseId,institutionId);
    if(releaseUpdated!=1) throw conflict("The new course draft changed state during package import.");

    Map<String,Object> summary=new LinkedHashMap<>();
    summary.put("packageId",packageId);
    summary.put("packageVersion",packageVersion);
    summary.put("subjectCount",packageSubjects.size());
    summary.put("chapterCount",chapterCount);
    summary.put("lessonCount",lessonCount);
    summary.put("blockCount",blockCount);
    summary.put("courseId",courseId);
    summary.put("releaseId",releaseId);
    jdbc.update("""
      update institution_course_package_import
      set state='IMPORTED',course_id=?,release_id=?,import_summary=?::jsonb,imported_at=now()
      where source_institution_id=? and package_id=? and package_version=? and state='IMPORTING'
    """,courseId,releaseId,json(summary),institutionId,packageId,packageVersion);
    jdbc.update("""
      insert into institution_course_lifecycle_event(
        institution_id,course_release_id,assignment_id,offer_id,event_type,
        actor_platform_staff_id,actor_institution_admin_id,details
      ) values(?,?,?,null,'COURSE_PACKAGE_IMPORTED',?,null,?::jsonb)
    """,institutionId,releaseId,created.get("assignmentId"),actorStaffId,json(summary));

    Map<String,Object> result=new LinkedHashMap<>();
    result.put("created",true);
    result.put("replayed",false);
    result.put("packageId",packageId);
    result.put("packageVersion",packageVersion);
    result.put("courseId",courseId);
    result.put("releaseId",releaseId);
    result.put("importSummary",summary);
    result.put("releaseStatus","DRAFT");
    result.put("studentDeliveryAllowed",false);
    result.put("marketplacePublicationRequired",true);
    result.put("message","The nested course package was imported as a draft. HRMS quality review and Marketplace publication are still required.");
    return result;
  }

  private List<String> coursePackageErrors(Map<String,Object> body) {
    List<String> errors=new ArrayList<>();
    validateKeys(body,Set.of("schema","package_id","package_version","course","subjects","editorial"),"root",errors);
    if(body==null) return List.of("The package root must be a JSON object.");
    if(canonicalJson(body).getBytes(java.nio.charset.StandardCharsets.UTF_8).length>10_485_760)
      return List.of("The course package exceeds the 10 MiB validation limit.");
    if(!"quantaedge.course-package.v1".equals(body.get("schema"))) errors.add("schema must be quantaedge.course-package.v1.");
    if(!matches(body.get("package_id"),"^[a-z0-9][a-z0-9._-]{2,119}$")) errors.add("package_id must be a stable lowercase slug of 3–120 characters.");
    if(!matches(body.get("package_version"),"^[A-Za-z0-9][A-Za-z0-9._+-]{0,39}$")) errors.add("package_version must be a valid 1–40 character version label.");
    if(!(body.get("course") instanceof Map<?,?> courseRaw)) { errors.add("course must be an object."); return errors; }
    Map<String,Object> course=stringMap(courseRaw);
    validateKeys(course,Set.of("code","display_name","course_type_code","curriculum_version","class_code","stream_code"),"course",errors);
    if(!matches(course.get("code"),"^[A-Za-z0-9][A-Za-z0-9_-]{1,37}$")) errors.add("course.code must be a valid 2–38 character code.");
    if(!textLength(course.get("display_name"),2,200)) errors.add("course.display_name must be 2–200 characters.");
    if(!textLength(course.get("curriculum_version"),1,120)) errors.add("course.curriculum_version must be 1–120 characters.");
    if(!"K12".equals(course.get("course_type_code"))) errors.add("This nested course package version supports K12 hierarchy only. Competitive and standalone assessment imports must use their registered type-specific package workflow.");
    if(!textLength(course.get("class_code"),1,20)) errors.add("course.class_code must name an active school class for a K12 course.");
    if(course.get("stream_code")!=null&&!textLength(course.get("stream_code"),1,60)) errors.add("course.stream_code must be at most 60 non-empty characters.");

    Object subjectsRaw=body.get("subjects");
    if(!(subjectsRaw instanceof List<?> subjects)||subjects.isEmpty()||subjects.size()>30) {
      errors.add("subjects must contain 1–30 subject entries.");
      return errors;
    }
    Set<String> subjectCodes=new HashSet<>();
    int totalChapters=0,totalLessons=0,totalBlocks=0;
    for(int si=0;si<subjects.size();si++) {
      if(!(subjects.get(si) instanceof Map<?,?> subjectRaw)){errors.add("subjects["+si+"] must be an object.");continue;}
      Map<String,Object> subject=stringMap(subjectRaw);
      String sp="subjects["+si+"]";
      validateKeys(subject,Set.of("code","display_name","sort_order","chapters"),sp,errors);
      String subjectCode=stringValue(subject.get("code"));
      if(!matches(subjectCode,"^[a-z0-9][a-z0-9_-]{1,39}$")) errors.add(sp+".code is invalid.");
      else if(!subjectCodes.add(subjectCode)) errors.add(sp+".code must be unique.");
      if(!textLength(subject.get("display_name"),2,100)) errors.add(sp+".display_name must be 2–100 characters.");
      validateOptionalOrder(subject.get("sort_order"),sp+".sort_order",errors);
      if(!(subject.get("chapters") instanceof List<?> chapters)||chapters.isEmpty()||chapters.size()>1000) {
        errors.add(sp+".chapters must contain 1–1000 chapters.");
        continue;
      }
      Set<String> chapterCodes=new HashSet<>();
      for(int ci=0;ci<chapters.size();ci++) {
        if(!(chapters.get(ci) instanceof Map<?,?> chapterRaw)){errors.add(sp+".chapters["+ci+"] must be an object.");continue;}
        Map<String,Object> chapter=stringMap(chapterRaw);
        String cp=sp+".chapters["+ci+"]";
        validateKeys(chapter,Set.of("code","display_name","description","sort_order","source","lessons"),cp,errors);
        String chapterCode=stringValue(chapter.get("code"));
        if(!matches(chapterCode,"^[a-z0-9]+(?:-[a-z0-9]+)*$")) errors.add(cp+".code is invalid.");
        else if(!chapterCodes.add(chapterCode)) errors.add(cp+".code must be unique within its subject.");
        if(!textLength(chapter.get("display_name"),1,160)) errors.add(cp+".display_name must be 1–160 characters.");
        if(chapter.get("description")!=null&&!textLength(chapter.get("description"),0,500)) errors.add(cp+".description must be at most 500 characters.");
        validateOptionalOrder(chapter.get("sort_order"),cp+".sort_order",errors);
        validateSource(chapter.get("source"),cp+".source",errors);
        if(!(chapter.get("lessons") instanceof List<?> lessons)||lessons.isEmpty()||lessons.size()>2000) {
          errors.add(cp+".lessons must contain 1–2000 lessons.");
          continue;
        }
        totalChapters++;
        if(totalChapters>5000){errors.add("The package exceeds 5,000 total chapters.");return errors;}
        Set<String> lessonCodes=new HashSet<>();
        for(int li=0;li<lessons.size();li++) {
          if(!(lessons.get(li) instanceof Map<?,?> lessonRaw)){errors.add(cp+".lessons["+li+"] must be an object.");continue;}
          Map<String,Object> lesson=stringMap(lessonRaw);
          String lp=cp+".lessons["+li+"]";
          validateKeys(lesson,Set.of("code","title","summary","estimated_minutes","sort_order","source","blocks"),lp,errors);
          String lessonCode=stringValue(lesson.get("code"));
          if(!matches(lessonCode,"^[a-z0-9]+(?:-[a-z0-9]+)*$")) errors.add(lp+".code is invalid.");
          else if(!lessonCodes.add(lessonCode)) errors.add(lp+".code must be unique within its chapter.");
          if(!textLength(lesson.get("title"),1,240)) errors.add(lp+".title must be 1–240 characters.");
          if(lesson.get("summary")!=null&&!textLength(lesson.get("summary"),0,800)) errors.add(lp+".summary must be at most 800 characters.");
          if(lesson.containsKey("estimated_minutes"))validateInt(lesson.get("estimated_minutes"),1,120,lp+".estimated_minutes",errors);
          validateOptionalOrder(lesson.get("sort_order"),lp+".sort_order",errors);
          validateSource(lesson.get("source"),lp+".source",errors);
          if(!(lesson.get("blocks") instanceof List<?> blocks)||blocks.isEmpty()||blocks.size()>200) {
            errors.add(lp+".blocks must contain 1–200 teaching blocks.");
            continue;
          }
          totalLessons++;
          if(totalLessons>20000){errors.add("The package exceeds 20,000 total lessons.");return errors;}
          Set<Integer> sequences=new HashSet<>();
          for(int bi=0;bi<blocks.size();bi++) {
            if(!(blocks.get(bi) instanceof Map<?,?> blockRaw)){errors.add(lp+".blocks["+bi+"] must be an object.");continue;}
            Map<String,Object> block=stringMap(blockRaw);
            String bp=lp+".blocks["+bi+"]";
            validateKeys(block,Set.of("sequence_no","block_type","content","active"),bp,errors);
            Object sequence=block.get("sequence_no");
            validateInt(sequence,1,10000,bp+".sequence_no",errors);
            if(sequence instanceof Number n&&!sequences.add(n.intValue()))errors.add(bp+".sequence_no must be unique within the lesson.");
            String blockType=stringValue(block.get("block_type"));
            if(!BLOCK_TYPES.contains(blockType))errors.add(bp+".block_type is not supported by this importer: "+blockType+".");
            if(!(block.get("content") instanceof Map<?,?>))errors.add(bp+".content must be an object.");
            if(block.containsKey("active")&&!(block.get("active") instanceof Boolean))errors.add(bp+".active must be boolean.");
            totalBlocks++;
            if(totalBlocks>100000){errors.add("The package exceeds 100,000 total blocks.");return errors;}
          }
        }
      }
    }
    if(body.containsKey("editorial")) {
      Object editorialRaw=body.get("editorial");
      if(!(editorialRaw instanceof Map<?,?> editorial)){errors.add("editorial must be an object when supplied.");}
      else {
        Map<String,Object> e=stringMap(editorial);
        validateKeys(e,Set.of("notes","source_rights_status"),"editorial",errors);
        if(e.get("notes")!=null&&!textLength(e.get("notes"),0,2000))errors.add("editorial.notes must be at most 2000 characters.");
        Object rights=e.get("source_rights_status");
        if(rights!=null&&!Set.of("UNCONFIRMED","CLEARED_FOR_INTERNAL_USE","CLEARED_FOR_PUBLIC_REPOSITORY","DO_NOT_REDISTRIBUTE").contains(rights))
          errors.add("editorial.source_rights_status is not a supported rights state.");
      }
    }
    return errors;
  }

  private static final Set<String> BLOCK_TYPES=Set.of(
    "EXPLANATION","IMAGE","DIAGRAM","VIDEO","QUESTION","MCQ","TRUE_FALSE","MATCH","ORDER","INPUT",
    "HINT","AI_HELP","SUMMARY","CHALLENGE","PREREQUISITE","WORKED_EXAMPLE","GUIDED_PRACTICE",
    "INDEPENDENT_PRACTICE","RECAP","AUDIO","ANIMATION");

  private static void validateKeys(Map<String,Object> map,Set<String> allowed,String where,List<String> errors) {
    if(map==null)return;
    for(String key:map.keySet())if(!allowed.contains(key))errors.add(where+" contains unsupported property '"+key+"'.");
  }
  private static Map<String,Object> stringMap(Map<?,?> raw) {
    Map<String,Object> map=new LinkedHashMap<>();
    raw.forEach((key,value)->map.put(String.valueOf(key),value));
    return map;
  }
  private static void validateSource(Object raw,String where,List<String> errors) {
    if(raw==null)return;
    if(!(raw instanceof Map<?,?> source)){errors.add(where+" must be an object or null.");return;}
    Map<String,Object> map=stringMap(source);
    validateKeys(map,Set.of("title","url","edition","pages"),where,errors);
    if(map.get("title")!=null&&!textLength(map.get("title"),0,300))errors.add(where+".title must be at most 300 characters.");
    if(map.get("url")!=null&&!textLength(map.get("url"),0,2000))errors.add(where+".url must be at most 2000 characters.");
    if(map.get("edition")!=null&&!textLength(map.get("edition"),0,160))errors.add(where+".edition must be at most 160 characters.");
    if(map.get("pages")!=null&&!textLength(map.get("pages"),0,160))errors.add(where+".pages must be at most 160 characters.");
  }
  private static Map<String,Object> optionalSource(Object raw,String where) {
    Map<String,Object> source=new LinkedHashMap<>();
    if(raw instanceof Map<?,?> map)source.putAll(stringMap(map));
    source.putIfAbsent("title",null);source.putIfAbsent("url",null);
    source.putIfAbsent("edition",null);source.putIfAbsent("pages",null);
    return source;
  }
  private static void validateOptionalOrder(Object raw,String where,List<String> errors) {
    if(raw!=null)validateInt(raw,1,10000,where,errors);
  }
  private static void validateInt(Object raw,int min,int max,String where,List<String> errors) {
    if(!(raw instanceof Number number)||raw instanceof Boolean||number.doubleValue()!=number.intValue()
        ||number.intValue()<min||number.intValue()>max)errors.add(where+" must be an integer from "+min+" to "+max+".");
  }
  private static int intOrDefault(Object raw,int fallback,int min,int max,String where) {
    if(raw==null)return fallback;
    if(!(raw instanceof Number number)||raw instanceof Boolean||number.doubleValue()!=number.intValue()
        ||number.intValue()<min||number.intValue()>max)throw badRequest(where+" must be an integer from "+min+" to "+max+".");
    return number.intValue();
  }
  private static boolean textLength(Object raw,int min,int max) {
    return raw instanceof String value && value.trim().length()>=min && value.trim().length()<=max;
  }
  private static boolean matches(Object raw,String regex) {
    return raw instanceof String value && value.matches(regex);
  }
  private static String stringValue(Object raw) { return raw instanceof String value?value:""; }
  private String canonicalJson(Object value) {
    try { return mapper.writeValueAsString(canonicalize(value)); }
    catch(JacksonException ex) { throw new IllegalArgumentException("Course package could not be normalized for idempotency.",ex); }
  }
  private static Object canonicalize(Object value) {
    if(value instanceof Map<?,?> map) {
      Map<String,Object> sorted=new java.util.TreeMap<>();
      map.forEach((key,item)->sorted.put(String.valueOf(key),canonicalize(item)));
      return sorted;
    }
    if(value instanceof List<?> list) {
      List<Object> result=new ArrayList<>();
      for(Object item:list)result.add(canonicalize(item));
      return result;
    }
    return value;
  }
  private String sha256(String value) {
    try {
      byte[] hash=java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      StringBuilder result=new StringBuilder(64);
      for(byte b:hash)result.append(String.format(java.util.Locale.ROOT,"%02x",b&0xff));
      return result.toString();
    } catch(java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException("SHA-256 is unavailable.",ex); }
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
      int sortOrder=intOrDefault(map.get("sortOrder"),result.size()+1,1,10000,"subject.sortOrder");
      result.add(Map.of("code",code,"displayName",name,"sortOrder",sortOrder));
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

  private static long number(Object value) {
    if(value instanceof Number number)return number.longValue();
    throw new IllegalStateException("The imported course ID was not numeric.");
  }

  private static ResponseStatusException conflict(String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT,message);
  }

  private static ResponseStatusException badRequest(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);
  }
}
