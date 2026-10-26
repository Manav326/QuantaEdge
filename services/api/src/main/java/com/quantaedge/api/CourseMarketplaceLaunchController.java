package com.quantaedge.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
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

  /**
   * Read-only HRMS preview of the exact release currently in quality review. Assessment
   * questions and answer-review metadata are read from the release's course graph; the source's
   * latest draft is never substituted for the submitted release.
   *
   * The release quality decision is submitted separately through PlatformInstitutionController
   * with reviewedContentFingerprint; this read-only endpoint only returns the fingerprint and
   * the exact submitted content to compare before making that decision.
   */
  @GetMapping("/{institutionId}/course-releases/{releaseId}/review-content")
  public Map<String,Object> reviewReleaseContent(
      @PathVariable long institutionId,
      @PathVariable long releaseId,
      @org.springframework.web.bind.annotation.RequestParam(required=false) Long chapterId,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    authorization.requirePermission(context,"QE_PLATFORM_OWNER");
    assertInstitution(institutionId);
    List<Map<String,Object>> rows=jdbc.queryForList("""
        select r.id as release_id,r.program_id as course_id,r.release_version,r.release_state,p.content_version_no,
          identity.public_id::text as course_identity_public_id,
          r.content_fingerprint,r.updated_at as release_updated_at,r.submitted_at,
          coalesce(identity.stable_course_key,p.code) as course_code,p.display_name as course_name,
          p.course_type_code,t.id as source_institution_id,t.tenant_code as source_institution_code,
          t.display_name as source_institution_name,
          coalesce((select mode.delivery_mode from curriculum_program_delivery_mode mode where mode.program_id=p.id),
                   'LEARNING_CONTENT') as delivery_mode
        from institution_course_release r
        join curriculum_program p on p.id=r.program_id and p.status='ACTIVE' and p.code<>'GENERAL'
        join institution_course_identity identity
          on identity.id=p.course_identity_id and identity.status='ACTIVE'
         and identity.source_institution_id=r.source_institution_id
         and identity.course_type_code=p.course_type_code
        join institution_tenant t on t.id=r.source_institution_id and t.status='ACTIVE'
        where r.id=? and r.source_institution_id=? and r.release_state='IN_QUALITY_REVIEW'
          and r.legacy_migration=false and r.obsolete_at is null
        """,releaseId,institutionId);
    if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,
        "The submitted course release is not available in this institution's HRMS quality-review queue.");
    Map<String,Object> result=new LinkedHashMap<>(rows.getFirst());
    long courseId=number(result.get("course_id"));
    if(chapterId!=null) {
      Boolean chapterBelongs=jdbc.queryForObject("""
          select exists(
            select 1 from curriculum_chapter ch
            join curriculum_subject s on s.id=ch.subject_id
            join curriculum_program_subject ps on ps.subject_id=s.id and ps.class_id=s.class_id
            where ch.id=? and ps.program_id=?
          )
          """,Boolean.class,chapterId,courseId);
      if(!Boolean.TRUE.equals(chapterBelongs))
        throw new ResponseStatusException(HttpStatus.NOT_FOUND,
            "The requested chapter is not part of this submitted release.");
    }
    String savedFingerprint=text(result.get("content_fingerprint"));
    boolean contentCurrent=Boolean.TRUE.equals(jdbc.queryForObject(
        "select course_release_fingerprint_current(?,?,?)",Boolean.class,
        courseId,savedFingerprint,result.get("release_updated_at")));
    result.put("contentCurrent",contentCurrent);
    result.put("currentFingerprint",savedFingerprint.trim());
    result.put("assessmentOnlyReady",Boolean.TRUE.equals(
        jdbc.queryForObject("select assessment_only_course_publish_ready(?)",Boolean.class,courseId)));
    result.put("courseTypePublishReady",Boolean.TRUE.equals(
        jdbc.queryForObject("select course_marketplace_content_type_ready(?)",Boolean.class,courseId)));
    result.put("courseTranslations",jdbc.queryForList("""
        select language,display_name,source_language,source_hash,version_no,
               translation_status,translation_method,quality_notes
        from curriculum_program_translation where program_id=?
        order by language,version_no desc,id desc
        """,courseId));

    List<Map<String,Object>> subjects=jdbc.queryForList("""
        select s.id,s.code,s.display_name,s.sort_order
        from curriculum_program_subject ps
        join curriculum_subject s on s.id=ps.subject_id and s.class_id=ps.class_id
          and s.active=true and (s.program_id=ps.program_id or s.program_id is null)
        where ps.program_id=? order by ps.sort_order,s.sort_order,s.id
        """,courseId);
    int lessonCount=0,lessonBlockCount=0,lessonQuestionCount=0,courseResourceCount=0;
    for(Map<String,Object> subject:subjects) {
      long subjectId=number(subject.get("id"));
      subject.put("translations",jdbc.queryForList("""
          select language,display_name,source_language,source_hash,version_no,
                 translation_status,translation_method,quality_notes
          from curriculum_subject_translation where subject_id=?
          order by language,version_no desc,id desc
          """,subjectId));
      List<Map<String,Object>> chapters=jdbc.queryForList("""
          select ch.id,ch.code,ch.display_name,ch.description,ch.sort_order,ch.content_status,
                 ch.curriculum_source,ch.curriculum_source_url,ch.curriculum_source_edition,
                 ch.curriculum_source_pages,ch.curriculum_source_verified,
                 (select count(*) from lesson l where l.chapter_id=ch.id) as lesson_count,
                 (select count(*) from lesson_block b join lesson l on l.id=b.lesson_id where l.chapter_id=ch.id) as lesson_block_count,
                 (select count(*) from question q join lesson l on l.id=q.lesson_id where l.chapter_id=ch.id) as lesson_question_count,
                 (select count(*) from learning_resource_assignment ra where ra.chapter_id=ch.id) as resource_count
          from curriculum_chapter ch where ch.subject_id=? and ch.active=true
          order by ch.sort_order,ch.id
          """,subjectId);
      for(Map<String,Object> chapter:chapters) {
        long chapterIdValue=number(chapter.get("id"));
        chapter.put("translations",jdbc.queryForList("""
            select language,display_name,description,source_language,source_hash,version_no,
                   translation_status,translation_method,quality_notes
            from curriculum_chapter_translation where chapter_id=?
            order by language,version_no desc,id desc
            """,chapterIdValue));
        lessonCount+=number(chapter.get("lesson_count"));
        lessonBlockCount+=number(chapter.get("lesson_block_count"));
        lessonQuestionCount+=number(chapter.get("lesson_question_count"));
        courseResourceCount+=number(chapter.get("resource_count"));
        if(chapterId==null || chapterIdValue!=chapterId.longValue()) continue;
        List<Map<String,Object>> lessons=jdbc.queryForList("""
            select l.id,l.code,l.title,l.summary,l.estimated_minutes,l.status,l.sort_order,l.active,
                   l.objective_id,o.code as objective_code,o.title as objective_title,
                   o.description as objective_description
            from lesson l
            left join learning_objective o on o.id=l.objective_id
            where l.chapter_id=? order by l.sort_order,l.id
            """,chapterIdValue);
        List<Map<String,Object>> resources=jdbc.queryForList("""
            select a.id as assignment_id,a.target_kind,a.sort_order,a.status as assignment_status,
                   r.id as resource_id,r.resource_type,r.title,r.description,r.language,r.source_kind,
                   r.external_url,r.source_reference,r.download_allowed,r.status as resource_status,
                   r.version_no,r.content_asset_id,r.sha256,r.file_size_bytes,r.media_type,r.duration_seconds
            from learning_resource_assignment a
            join learning_resource r on r.id=a.resource_id
            where a.chapter_id=? order by a.sort_order,a.id
            """,chapterId);

        chapter.put("resources",resources);
        for(Map<String,Object> lesson:lessons) {
          long lessonId=number(lesson.get("id"));
          lesson.put("translations",jdbc.queryForList("""
              select language,title,summary,source_language,source_hash,version_no,
                     translation_status,translation_method,quality_notes
              from lesson_translation where lesson_id=?
              order by language,version_no desc,id desc
              """,lessonId));
          if(lesson.get("objective_id")!=null) {
            lesson.put("objectiveTranslations",jdbc.queryForList("""
                select language,title,description,source_language,source_hash,version_no,
                       translation_status,translation_method,quality_notes
                from learning_objective_translation where objective_id=?
                order by language,version_no desc,id desc
                """,number(lesson.get("objective_id"))));
          } else {
            lesson.put("objectiveTranslations",List.of());
          }
          List<Map<String,Object>> blocks=jdbc.queryForList("""
              select b.id,b.sequence_no,b.block_type,b.content::text as content_json,b.asset_id,
                     a.url as asset_url,a.title as asset_title,a.alt_text as asset_alt,
                     a.asset_type,a.status as asset_status
              from lesson_block b
              left join content_asset a on a.id=b.asset_id
              where b.lesson_id=? order by b.sequence_no,b.id
              """,lessonId);
          for(Map<String,Object> block:blocks) {
            Object parsedContent;
            try {
              parsedContent=mapper.readValue(String.valueOf(block.remove("content_json")),Object.class);
            } catch(JsonProcessingException ex) {
              throw new IllegalStateException("A submitted lesson block could not be decoded for review.",ex);
            }
            block.put("content",parsedContent);
            List<Map<String,Object>> translations=jdbc.queryForList("""
                select language,content::text as content_json,source_language,source_hash,version_no,
                       translation_status,translation_method,quality_notes
                from lesson_block_translation where block_id=?
                order by language,version_no desc,id desc
                """,number(block.get("id")));
            for(Map<String,Object> translation:translations) {
              try {
                translation.put("content",mapper.readValue(String.valueOf(translation.remove("content_json")),Object.class));
              } catch(JsonProcessingException ex) {
                throw new IllegalStateException("A submitted lesson-block translation could not be decoded for review.",ex);
              }
            }
            block.put("translations",translations);
          }

          lesson.put("blocks",blocks);

          List<Map<String,Object>> questions=jdbc.queryForList("""
              select q.id,q.question_type,q.prompt,q.explanation,q.difficulty,q.sort_order,q.active
              from question q where q.lesson_id=? order by q.sort_order,q.id
              """,lessonId);
          for(Map<String,Object> question:questions) {
            long questionId=number(question.get("id"));
            question.put("options",jdbc.queryForList("""
                select option_key,label,is_correct,sort_order
                from question_option where question_id=? order by sort_order,id
                """,questionId));
            List<Map<String,Object>> translations=jdbc.queryForList("""
                select language,prompt,explanation,options::text as options_json,topic,subtopic,skill,
                       tags::text as tags_json,source_language,source_hash,version_no,
                       translation_status,translation_method,quality_notes
                from question_translation where question_id=?
                order by language,version_no desc,id desc
                """,questionId);
            for(Map<String,Object> translation:translations) {
              for(String jsonField:List.of("options_json","tags_json")) {
                Object raw=translation.remove(jsonField);
                if(raw==null) continue;
                try {
                  translation.put(jsonField.equals("options_json")?"options":"tags",
                      mapper.readValue(String.valueOf(raw),Object.class));
                } catch(JsonProcessingException ex) {
                  throw new IllegalStateException("A submitted question translation could not be decoded for review.",ex);
                }
              }
            }
            question.put("translations",translations);
          }

          lesson.put("questions",questions);
        }

        chapter.put("lessons",lessons);
      }
      subject.put("chapters",chapters);
    }
    result.put("subjects",subjects);
    result.put("lessonCount",lessonCount);
    result.put("lessonBlockCount",lessonBlockCount);
    result.put("lessonQuestionCount",lessonQuestionCount);
    result.put("courseResourceCount",courseResourceCount);

    List<Map<String,Object>> assessments=chapterId==null?jdbc.queryForList("""
        select a.id as assessment_id,a.title,a.description,a.assessment_type,a.portable_assessment_type,
          a.source_exam_cycle,a.source_package_id,a.source_package_version,a.source_package_sha256,
          a.source_url,a.status,a.duration_minutes,a.max_attempts,a.negative_marking_fraction,
          s.code as subject_code,s.display_name as subject_name,
          ch.code as chapter_code,ch.display_name as chapter_name,
          count(aq.id) as question_count,
          coalesce(sum(case
            when coalesce(aq.question_snapshot->>'section_result_policy','SCORED') in ('QUALIFYING_ONLY','PRACTICE_ONLY')
              or coalesce(aq.question_snapshot->>'section_code','')='LANGUAGE_QUALIFYING' then 0
            else aq.max_marks end),0) as max_score
        from assessment a
        left join curriculum_subject s on s.id=a.subject_id
        left join curriculum_chapter ch on ch.id=a.chapter_id
        left join assessment_question aq on aq.assessment_id=a.id
        where a.course_id=?
        group by a.id,s.code,s.display_name,ch.code,ch.display_name
        order by coalesce(a.source_exam_cycle,a.title),a.id
        """,courseId):new ArrayList<>();
    int totalQuestions=0;
    for(Map<String,Object> assessment:assessments) {
      long assessmentId=number(assessment.get("assessment_id"));
      List<Map<String,Object>> questions=jdbc.queryForList("""
          select sequence_no,max_marks,question_snapshot::text as question_snapshot
          from assessment_question where assessment_id=? order by sequence_no,id
          """,assessmentId);
      for(Map<String,Object> question:questions) {
        try {
          question.put("question_snapshot",mapper.readValue(
              String.valueOf(question.get("question_snapshot")),Map.class));
        } catch(JsonProcessingException ex) {
          throw new IllegalStateException("A submitted assessment question snapshot could not be read.",ex);
        }
      }
      assessment.put("questions",questions);
      assessment.put("questionCount",questions.size());
      totalQuestions+=questions.size();
    }
    result.put("assessments",assessments);
    result.put("assessmentCount",assessments.size());
    result.put("questionCount",totalQuestions);
    result.put("readOnly",true);
    result.put("publicationAllowed",false);
    return result;
  }

  @GetMapping("/{institutionId}/marketplace-launches")
  public Map<String,Object> marketplaceLaunches(
      @PathVariable long institutionId,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    authorization.requirePermission(context,"QE_PLATFORM_OWNER");
    assertInstitution(institutionId);
    List<Long> assignments=jdbc.queryForList("""
        with ranked_assignments as (
          select a.id,a.assigned_at,
            row_number() over (
              partition by identity.id
              order by p.content_version_no desc,r.release_version desc,r.id desc,
                a.assigned_at desc,a.id desc
            ) as identity_rank
          from institution_course_assignment a
          join institution_course_release r on r.id=a.course_release_id
            and r.legacy_migration=false and r.obsolete_at is null and r.release_state<>'LEGACY_MIGRATED'
          join curriculum_program p on p.id=r.program_id and p.status='ACTIVE' and p.code<>'GENERAL'
          join institution_course_identity identity
            on identity.id=p.course_identity_id and identity.status='ACTIVE'
           and identity.source_institution_id=r.source_institution_id
           and identity.course_type_code=p.course_type_code
          where a.institution_id=? and a.status='ASSIGNED'
            and a.assignment_source in ('ORIGIN','HRMS')
            and (a.assignment_source <> 'ORIGIN' or r.release_state='SHAREABLE')
        )
        select id from ranked_assignments where identity_rank=1
        order by assigned_at desc,id desc
        """,Long.class,institutionId);
    List<Map<String,Object>> items=new ArrayList<>();
    int readyCount=0;
    for(Long assignmentId:assignments) {
      Map<String,Object> item=readiness.inspectAssignment(assignmentId,institutionId);
      items.add(item);
      if(Boolean.TRUE.equals(item.get("ready"))) readyCount++;
    }
    List<Map<String,Object>> availableReleases=jdbc.queryForList("""
        select r.id as release_id,r.program_id as course_id,r.release_version,p.content_version_no,
          r.content_fingerprint,r.updated_at as release_updated_at,
          coalesce(identity.stable_course_key,p.code) as course_code,p.display_name as course_name,
          source.id as source_institution_id,source.tenant_code as source_institution_code,
          source.display_name as source_institution_name
        from institution_course_release r
        join curriculum_program p on p.id=r.program_id and p.status='ACTIVE' and p.code<>'GENERAL'
        join institution_course_identity identity
          on identity.id=p.course_identity_id and identity.status='ACTIVE'
         and identity.source_institution_id=r.source_institution_id
         and identity.course_type_code=p.course_type_code
        join institution_tenant source on source.id=r.source_institution_id and source.status='ACTIVE'
        where r.release_state='SHAREABLE' and r.legacy_migration=false and r.obsolete_at is null
          and r.source_institution_id<>?
          and course_marketplace_content_type_ready(p.id)
          and not exists(select 1 from institution_course_assignment a
            where a.institution_id=? and a.course_release_id=r.id and a.status='ASSIGNED')
          and not exists(
            select 1
            from institution_course_release newer_r
            join curriculum_program newer_p on newer_p.id=newer_r.program_id
              and newer_p.status='ACTIVE' and newer_p.code<>'GENERAL'
            join institution_course_identity newer_identity
              on newer_identity.id=newer_p.course_identity_id and newer_identity.status='ACTIVE'
             and newer_identity.source_institution_id=newer_r.source_institution_id
             and newer_identity.course_type_code=newer_p.course_type_code
            where newer_r.release_state='SHAREABLE' and newer_r.legacy_migration=false
              and newer_r.obsolete_at is null
              and newer_p.course_identity_id=p.course_identity_id
              and course_marketplace_content_type_ready(newer_p.id)
              and course_release_fingerprint_current(newer_r.program_id,newer_r.content_fingerprint::text,newer_r.updated_at)
              and (newer_p.content_version_no>p.content_version_no
                or (newer_p.id=p.id and newer_r.release_version>r.release_version))
          )
        order by p.display_name,source.display_name,r.release_version desc,r.id desc
        """,institutionId,institutionId);
    for(Map<String,Object> release:availableReleases) {
      release.put("contentCurrent",readiness.contentFingerprintMatches(
          number(release.get("course_id")),text(release.get("content_fingerprint")),release.get("release_updated_at")));
    }
    List<Map<String,Object>> qualityReviewItems=jdbc.queryForList("""
        select r.id as release_id,r.program_id as course_id,r.release_version,r.release_state,p.content_version_no,
          identity.public_id::text as course_identity_public_id,
          r.content_fingerprint,r.updated_at as release_updated_at,r.submitted_at,
          coalesce(identity.stable_course_key,p.code) as course_code,p.display_name as course_name,
          p.course_type_code,
          coalesce((select mode.delivery_mode from curriculum_program_delivery_mode mode where mode.program_id=p.id),
                   'LEARNING_CONTENT') as delivery_mode,
          assessment_only_course_publish_ready(p.id) as assessment_only_ready,
          course_marketplace_content_type_ready(p.id) as course_type_publish_ready,
          (select count(*) from assessment a where a.course_id=p.id) as assessment_count,
          (select count(*) from assessment_question aq join assessment a on a.id=aq.assessment_id where a.course_id=p.id) as assessment_question_count,
          t.id as source_institution_id,t.tenant_code as source_institution_code,
          t.display_name as source_institution_name,
          count(distinct s.id) as active_subject_count,
          count(distinct case when l.id is not null then s.id end) as subjects_with_published_lessons,
          count(distinct ch.id) as published_chapters,count(distinct l.id) as published_lessons
        from institution_course_release r
        join curriculum_program p on p.id=r.program_id and p.status='ACTIVE' and p.code<>'GENERAL'
        join institution_course_identity identity
          on identity.id=p.course_identity_id and identity.status='ACTIVE'
         and identity.source_institution_id=r.source_institution_id
         and identity.course_type_code=p.course_type_code
        join institution_tenant t on t.id=r.source_institution_id and t.status='ACTIVE'
        left join curriculum_program_subject ps on ps.program_id=p.id
        left join curriculum_subject s on s.id=ps.subject_id and s.class_id=ps.class_id and s.active=true
          and (s.program_id=ps.program_id or s.program_id is null)
        left join curriculum_chapter ch on ch.subject_id=s.id and ch.active=true and ch.content_status='PUBLISHED'
        left join lesson l on l.chapter_id=ch.id and l.active=true and l.status='PUBLISHED'
        where r.source_institution_id=? and r.release_state='IN_QUALITY_REVIEW'
          and r.legacy_migration=false and r.obsolete_at is null
        group by r.id,r.program_id,r.release_version,r.release_state,r.content_fingerprint,r.updated_at,r.submitted_at,
          p.id,p.code,p.display_name,p.course_type_code,p.content_version_no,
          identity.public_id,identity.stable_course_key,t.id,t.tenant_code,t.display_name
        order by r.submitted_at,r.id
        """,institutionId);
    for(Map<String,Object> release:qualityReviewItems) {
      release.put("contentCurrent",readiness.contentFingerprintMatches(
          number(release.get("course_id")),text(release.get("content_fingerprint")),release.get("release_updated_at")));
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

    List<Map<String,Object>> coursePackageImports=jdbc.queryForList("""
        select i.id as import_id,i.package_id,i.package_version,i.course_id,i.release_id,
          i.source_institution_id,source.tenant_code as source_institution_code,
          source.display_name as source_institution_name,p.display_name as course_name,
          coalesce(i.editorial_metadata->>'source_rights_status','UNCONFIRMED') as source_rights_status,
          i.editorial_metadata->>'rights_evidence_url' as rights_evidence_url,
          i.rights_reviewed_at,i.rights_review_notes
        from institution_course_package_import i
        join curriculum_program p on p.id=i.course_id
        join institution_tenant source on source.id=i.source_institution_id
        where i.state='IMPORTED'
          and exists (
            select 1
            from institution_course_assignment assigned
            join institution_course_release assigned_release
              on assigned_release.id=assigned.course_release_id
            join curriculum_program assigned_course on assigned_course.id=assigned_release.program_id
            where assigned.institution_id=? and assigned.status='ASSIGNED'
              and (
                assigned_course.id=i.course_id
                or (p.course_identity_id is not null
                  and assigned_course.course_identity_id=p.course_identity_id)
              )
          )
        order by i.imported_at desc,i.id desc
        """,institutionId);
    result.put("coursePackageImports",coursePackageImports);
    result.put("coursePackageImportCount",coursePackageImports.size());
    return result;
  }

  @PostMapping("/{institutionId}/course-package-imports/{importId}/rights-review")
  @Transactional
  public Map<String,Object> reviewCoursePackageRights(
      @PathVariable long institutionId,@PathVariable long importId,
      @RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext supplied) {
    AuthContext context=authorization.requirePermission(supplied,"QE_PLATFORM_OWNER");
    requireStaff(context);
    assertInstitution(institutionId);
    String status=optionalText(body==null?null:body.get("status"),"",40,"Rights decision")
        .toUpperCase(Locale.ROOT);
    if(!List.of("CLEARED_FOR_INTERNAL_USE","CLEARED_FOR_PUBLIC_REPOSITORY","DO_NOT_REDISTRIBUTE")
        .contains(status)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "Choose CLEARED_FOR_INTERNAL_USE, CLEARED_FOR_PUBLIC_REPOSITORY, or DO_NOT_REDISTRIBUTE.");
    }
    String notes=optionalText(body==null?null:body.get("notes"),"",1000,"Reviewer notes");
    if(notes.length()<5) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "Record at least five characters explaining the rights decision.");
    }
    String evidence=optionalText(body==null?null:body.get("evidenceUrl"),"",2000,"Rights evidence URL");
    if(!evidence.isBlank()) {
      try {
        URI uri=URI.create(evidence);
        if(!"https".equalsIgnoreCase(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null) {
          throw new IllegalArgumentException("Rights evidence must be an HTTPS URL without embedded credentials.");
        }
      } catch(IllegalArgumentException ex) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "Provide a valid HTTPS rights-evidence URL.",ex);
      }
    }
    if(!"DO_NOT_REDISTRIBUTE".equals(status)&&evidence.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "A cleared distribution-rights decision requires a valid HTTPS rights-evidence URL.");
    }

    List<Map<String,Object>> imports=jdbc.queryForList("""
        select i.id,i.package_id,i.package_version,i.source_institution_id,i.course_id,i.release_id,
          i.editorial_metadata->>'source_rights_status' as previous_rights_status
        from institution_course_package_import i
        join curriculum_program p on p.id=i.course_id
        where i.id=? and i.state='IMPORTED'
          and exists (
            select 1
            from institution_course_assignment assigned
            join institution_course_release assigned_release
              on assigned_release.id=assigned.course_release_id
            join curriculum_program assigned_course on assigned_course.id=assigned_release.program_id
            where assigned.institution_id=? and assigned.status='ASSIGNED'
              and (
                assigned_course.id=i.course_id
                or (p.course_identity_id is not null
                  and assigned_course.course_identity_id=p.course_identity_id)
              )
          )
        for update of i
        """,importId,institutionId);
    if(imports.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND,
          "This imported package is not assigned to the selected institution.");
    }
    Map<String,Object> imported=imports.getFirst();
    String previousStatus=text(imported.get("previous_rights_status"));
    int updated=jdbc.update("""
        update institution_course_package_import
        set editorial_metadata=editorial_metadata || jsonb_build_object(
              'source_rights_status',?,'rights_evidence_url',?,'rights_review_notes',?),
            rights_reviewed_by_staff_id=?,rights_reviewed_at=now(),rights_review_notes=?
        where id=? and state='IMPORTED'
        """,status,evidence.isBlank()?null:evidence,notes,context.staffId(),notes,importId);
    if(updated!=1) throw conflict("The imported package changed during rights review. Refresh the checklist and retry.");

    List<Long> sourceAssignments=jdbc.queryForList("""
        select id from institution_course_assignment
        where institution_id=? and course_release_id=?
        order by id desc limit 1
        """,Long.class,number(imported.get("source_institution_id")),number(imported.get("release_id")));
    Long sourceAssignmentId=sourceAssignments.isEmpty()?null:sourceAssignments.getFirst();
    record(number(imported.get("source_institution_id")),number(imported.get("release_id")),
        sourceAssignmentId,null,"COURSE_PACKAGE_RIGHTS_REVIEWED",context.staffId(),
        Map.of("packageImportId",importId,"packageId",text(imported.get("package_id")),
          "packageVersion",text(imported.get("package_version")),
          "reviewedForInstitutionId",institutionId,"previousStatus",previousStatus==null?"UNCONFIRMED":previousStatus,
          "decision",status,"evidenceUrl",evidence,"reviewNotes",notes));
    return Map.of("saved",true,"importId",importId,"status",status,
        "reviewedByStaffId",context.staffId(),
        "message","Package rights decision saved with HRMS audit evidence. Marketplace checks will use this decision across all versions of the same course identity.");
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
        "INSTITUTION_ACTIVE","COURSE_ACTIVE","COURSE_IDENTITY","CURRENT_COURSE_RELEASE",
        "CONTENT_QUALITY_REVIEW","ORIGIN_RELEASE_APPROVAL","ASSIGNMENT_AND_RELEASE",
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
        select id,status,obsolete_at from institution_marketplace_offer
        where institution_id=? and assignment_id=? for update
        """,institutionId,assignmentId);
    long offerId;
    String oldStatus=existing.isEmpty()?"":text(existing.getFirst().get("status"));
    if("ARCHIVED".equals(oldStatus) || (!existing.isEmpty() && existing.getFirst().get("obsolete_at")!=null))
      throw conflict("This listing revision is archived and cannot be reactivated. Create a new source-owned course release, complete HRMS review and then configure its listing.");
    if(!existing.isEmpty()&&!List.of("DRAFT","REJECTED").contains(oldStatus))
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
            and status in ('DRAFT','REJECTED')
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
              obsolete_at=coalesce(obsolete_at,now()),
              obsolete_reason=coalesce(obsolete_reason,'OBSOLETE: listing archived by HRMS; existing financial records are retained.'),
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

    // Swap the live publication atomically only after the replacement has passed every
    // HRMS gate and is actually PUBLISHED. Orders, invoices, and access remain bound to
    // the old offer/course graph and are intentionally not rewritten.
    List<Long> supersededOfferIds=new ArrayList<>();
    if("PUBLISH".equals(action)) {
      List<Map<String,Object>> superseded=jdbc.queryForList("""
          select previous_offer.id as offer_id,previous_offer.assignment_id,
            previous_offer.course_release_id
          from institution_marketplace_offer published_offer
          join curriculum_program published_program on published_program.id=published_offer.course_id
          join institution_course_identity published_identity
            on published_identity.id=published_program.course_identity_id
          join institution_marketplace_offer previous_offer
            on previous_offer.institution_id=published_offer.institution_id
           and previous_offer.id<>published_offer.id
           and previous_offer.status='PUBLISHED'
           and previous_offer.legacy_migration=false
          join institution_course_assignment previous_assignment
            on previous_assignment.id=previous_offer.assignment_id
           and previous_assignment.institution_id=previous_offer.institution_id
           and previous_assignment.status='ASSIGNED'
           and previous_assignment.assignment_source in ('ORIGIN','HRMS')
          join institution_course_release previous_release
            on previous_release.id=previous_offer.course_release_id
           and previous_release.program_id=previous_offer.course_id
          join curriculum_program previous_program on previous_program.id=previous_offer.course_id
          join institution_course_identity previous_identity
            on previous_identity.id=previous_program.course_identity_id
          where published_offer.id=? and published_offer.status='PUBLISHED'
            and published_offer.institution_id=?
            and published_offer.legacy_migration=false
            and previous_identity.id=published_identity.id
            and (
              previous_program.content_version_no<published_program.content_version_no
              or (
                previous_program.content_version_no=published_program.content_version_no
                and (
                  previous_release.release_version<
                    (select current_release.release_version
                     from institution_course_release current_release
                     where current_release.id=published_offer.course_release_id)
                  or (
                    previous_release.release_version=
                      (select current_release.release_version
                       from institution_course_release current_release
                       where current_release.id=published_offer.course_release_id)
                    and previous_release.id<published_offer.course_release_id
                  )
                )
              )
            )
          order by previous_offer.id
          for update of previous_offer
          """,offerId,institutionId);
      for(Map<String,Object> previous:superseded) {
        long previousOfferId=number(previous.get("offer_id"));
        long previousAssignmentId=number(previous.get("assignment_id"));
        long previousReleaseId=number(previous.get("course_release_id"));
        int archived=jdbc.update("""
            update institution_marketplace_offer
            set status='ARCHIVED',obsolete_at=coalesce(obsolete_at,now()),
              obsolete_reason=coalesce(obsolete_reason,'OBSOLETE: superseded by offer '||?::text),
              updated_at=now()
            where id=? and institution_id=? and status='PUBLISHED'
            """,offerId,previousOfferId,institutionId);
        if(archived==1) {
          supersededOfferIds.add(previousOfferId);
          record(institutionId,previousReleaseId,previousAssignmentId,previousOfferId,
              "OFFER_ARCHIVED_SUPERSEDED",context.staffId(),
              Map.of("supersededByOfferId",offerId,
                "reason","A newer content/release version completed HRMS approval and publication."));
        }
      }
    }
    return Map.of("updated",true,"offerId",offerId,"status",next,
        "supersededOfferIds",supersededOfferIds,
        "message","Marketplace listing status updated.");
  }

  private Map<String,Object> lockAssignment(long institutionId,long assignmentId) {
    List<Map<String,Object>> rows=jdbc.queryForList("""
        select a.id as assignment_id,a.id,a.institution_id,
          a.course_release_id,r.id as release_id,a.status as assignment_status,
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

  private void record(long institutionId,Long releaseId,Long assignmentId,Long offerId,
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
