package com.quantaedge.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/learning")
public class LearningController {
  private final JdbcTemplate jdbc;
  private final AuthorizationService authorization;
  private final LearningStateService state;
  private final QuestionAnswerService answerService;

  public LearningController(JdbcTemplate jdbc, AuthorizationService authorization, LearningStateService state, QuestionAnswerService answerService) {
    this.jdbc=jdbc; this.authorization=authorization; this.state=state; this.answerService=answerService;
  }

  @GetMapping("/lessons")
  public List<Map<String,Object>> lessons(@RequestParam String classCode,@RequestParam String subjectCode,
      @RequestParam(required=false,defaultValue="hi") String language,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    context=authorization.requireStudent(context);
    language=normalizeLanguage(language);
    if (!"maths".equals(subjectCode) && !"science".equals(subjectCode)) throw new IllegalArgumentException("Unknown subject track");
    String enrolledClass=jdbc.queryForObject("select class_code from student where id=? and active=true",String.class,context.studentId());
    if(!classCode.equals(enrolledClass)) throw new SecurityException("Class access denied");
    return jdbc.queryForList("""
      with learner as (select id from student where id=? and active=true)
      select l.id,l.code,coalesce(lt.title,l.title) as title,coalesce(lt.summary,l.summary) as summary,
             l.estimated_minutes,l.status,
             c.code as class_code,s.code as subject_code,coalesce(st.display_name,s.display_name) as subject_name,
             ch.code as chapter_code,coalesce(ct.display_name,ch.display_name) as chapter_name,
             o.code as objective_code,coalesce(ot.title,o.title) as objective_title,
             (lt.id is not null) as lesson_translated,
             (st.id is not null) as subject_translated,
             (ct.id is not null) as chapter_translated,
             (ot.id is not null) as objective_translated,
             coalesce(mcq.mcq_count,0) as mcq_count,
             coalesce(mcq.mcq_answered_count,0) as mcq_answered_count,
             coalesce(practice_count.completed_attempt_count,0) as practice_completed_attempts,
             best.correct_count as practice_best_correct_count,
             best.graded_count as practice_best_graded_count,
             best.percent as practice_best_percent,
             active_practice.answered_count as practice_active_answered_count,
             active_practice.question_count as practice_active_question_count
      from learner
      join lesson l on true
      join curriculum_chapter ch on ch.id=l.chapter_id
      join curriculum_subject s on s.id=ch.subject_id
      join curriculum_class c on c.id=s.class_id
      join student_track_enrollment ste on ste.student_id=learner.id and ste.subject_id=s.id and ste.status='ACTIVE'
      left join learning_objective o on o.id=l.objective_id
      left join lateral (
        select t.id,t.title,t.summary from lesson_translation t
        where t.lesson_id=l.id and t.language=? and t.translation_status='PUBLISHED'
          and t.source_hash::text=md5(jsonb_build_object('title',l.title,'summary',l.summary)::text)
        order by t.version_no desc,t.id desc limit 1
      ) lt on true
      left join lateral (
        select t.id,t.display_name from curriculum_subject_translation t
        where t.subject_id=s.id and t.language=? and t.translation_status='PUBLISHED'
          and t.source_hash::text=md5(jsonb_build_object('display_name',s.display_name)::text)
        order by t.version_no desc,t.id desc limit 1
      ) st on true
      left join lateral (
        select t.id,t.display_name from curriculum_chapter_translation t
        where t.chapter_id=ch.id and t.language=? and t.translation_status='PUBLISHED'
          and t.source_hash::text=md5(jsonb_build_object('display_name',ch.display_name,'description',ch.description)::text)
        order by t.version_no desc,t.id desc limit 1
      ) ct on true
      left join lateral (
        select t.id,t.title from learning_objective_translation t
        where o.id is not null and t.objective_id=o.id and t.language=? and t.translation_status='PUBLISHED'
          and t.source_hash::text=md5(jsonb_build_object('title',o.title,'description',o.description)::text)
        order by t.version_no desc,t.id desc limit 1
      ) ot on true
      left join lateral (
        select count(*) filter(where q.question_type='MCQ') as mcq_count,
               count(*) filter(where q.question_type='MCQ' and exists(
                 select 1 from student_question_attempt a
                 where a.student_id=learner.id and a.question_id=q.id and a.activity_kind='LESSON_INLINE'
               )) as mcq_answered_count
        from question q
        where q.lesson_id=l.id and q.active=true and q.review_status in ('APPROVED','PUBLISHED')
      ) mcq on true
      left join lateral (
        select count(*) as completed_attempt_count
        from student_practice_session ps
        where ps.student_id=learner.id and ps.status='COMPLETED'
          and ps.topic_selections @> jsonb_build_array(jsonb_build_object('sourceLessonId',l.id))
      ) practice_count on true
      left join lateral (
        select score.correct_count,score.graded_count,
               case when score.graded_count>0
                 then round(100.0*score.correct_count/nullif(score.graded_count,0))::int
                 else null end as percent
        from student_practice_session ps
        cross join lateral (
          select count(*) filter(where latest.correct is true) as correct_count,
                 count(*) filter(where latest.correct is not null) as graded_count
          from student_practice_session_question psq
          left join lateral (
            select a.correct
            from student_question_attempt a
            where a.practice_session_id=ps.id and a.question_id=psq.question_id
            order by a.answered_at desc,a.id desc limit 1
          ) latest on true
          where psq.practice_session_id=ps.id
        ) score
        where ps.student_id=learner.id and ps.status='COMPLETED'
          and ps.topic_selections @> jsonb_build_array(jsonb_build_object('sourceLessonId',l.id))
        order by case when score.graded_count>0
                      then score.correct_count::numeric/score.graded_count end desc nulls last,
                 ps.completed_at desc,ps.id desc
        limit 1
      ) best on true
      left join lateral (
        select session_progress.answered_count,session_progress.question_count
        from student_practice_session ps
        cross join lateral (
          select count(*) as question_count,
                 count(*) filter(where psq.answered_at is not null) as answered_count
          from student_practice_session_question psq
          where psq.practice_session_id=ps.id
        ) session_progress
        where ps.student_id=learner.id and ps.status='IN_PROGRESS'
          and ps.topic_selections @> jsonb_build_array(jsonb_build_object('sourceLessonId',l.id))
        order by ps.created_at desc,ps.id desc
        limit 1
      ) active_practice on true
      where c.code=? and s.code=? and c.active=true and s.active=true
        and ch.active=true and ch.content_status='PUBLISHED' and l.active=true and l.status='PUBLISHED'
      order by coalesce(ch.teaching_order,ch.sort_order),l.sort_order,l.id
      """,context.studentId(),language,language,language,language,classCode,subjectCode);
  }

  @PostMapping("/lessons/{lessonId}/start")
  public Map<String,Object> start(@PathVariable long lessonId,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    context=authorization.requireStudent(context);
    ensureStudentLessonAccess(context.studentId(),lessonId);
    state.startLesson(context.studentId(),lessonId);
    return Map.of("started",true,"lessonId",lessonId);
  }

  @GetMapping("/lessons/{lessonId}")
  public Map<String,Object> lesson(@PathVariable long lessonId,
      @RequestParam(required=false,defaultValue="hi") String language,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    context=authorization.requireStudent(context);
    language=normalizeLanguage(language);
    List<Map<String,Object>> lessons=jdbc.queryForList("""
      select l.id,l.code,coalesce(lt.title,l.title) as title,coalesce(lt.summary,l.summary) as summary,
             l.estimated_minutes,l.status,
             ch.code as chapter_code,coalesce(ct.display_name,ch.display_name) as chapter_name,
             c.code as class_code,coalesce(cct.display_name,c.display_name) as class_name,
             s.code as subject_code,coalesce(st.display_name,s.display_name) as subject_name,
             o.code as objective_code,coalesce(ot.title,o.title) as objective_title,
             (lt.id is not null) as lesson_translated,
             (ct.id is not null) as chapter_translated,
             (cct.id is not null) as class_translated,
             (st.id is not null) as subject_translated,
             (ot.id is not null) as objective_translated,
             coalesce(p.progress_percent,0) as progress_percent,
             coalesce(p.status,'NOT_STARTED') as progress_status
      from lesson l join curriculum_chapter ch on ch.id=l.chapter_id
      join curriculum_subject s on s.id=ch.subject_id join curriculum_class c on c.id=s.class_id
      join student_track_enrollment ste on ste.student_id=? and ste.subject_id=s.id and ste.status='ACTIVE'
      left join learning_objective o on o.id=l.objective_id
      left join student_lesson_progress p on p.lesson_id=l.id and p.student_id=?
      left join lateral (
        select t.id,t.title,t.summary from lesson_translation t
        where t.lesson_id=l.id and t.language=? and t.translation_status='PUBLISHED'
          and t.source_hash::text=md5(jsonb_build_object('title',l.title,'summary',l.summary)::text)
        order by t.version_no desc,t.id desc limit 1
      ) lt on true
      left join lateral (
        select t.id,t.display_name from curriculum_chapter_translation t
        where t.chapter_id=ch.id and t.language=? and t.translation_status='PUBLISHED'
          and t.source_hash::text=md5(jsonb_build_object('display_name',ch.display_name,'description',ch.description)::text)
        order by t.version_no desc,t.id desc limit 1
      ) ct on true
      left join lateral (
        select t.id,t.display_name from curriculum_class_translation t
        where t.class_id=c.id and t.language=? and t.translation_status='PUBLISHED'
          and t.source_hash::text=md5(jsonb_build_object('display_name',c.display_name)::text)
        order by t.version_no desc,t.id desc limit 1
      ) cct on true
      left join lateral (
        select t.id,t.display_name from curriculum_subject_translation t
        where t.subject_id=s.id and t.language=? and t.translation_status='PUBLISHED'
          and t.source_hash::text=md5(jsonb_build_object('display_name',s.display_name)::text)
        order by t.version_no desc,t.id desc limit 1
      ) st on true
      left join lateral (
        select t.id,t.title from learning_objective_translation t
        where o.id is not null and t.objective_id=o.id and t.language=? and t.translation_status='PUBLISHED'
          and t.source_hash::text=md5(jsonb_build_object('title',o.title,'description',o.description)::text)
        order by t.version_no desc,t.id desc limit 1
      ) ot on true
      where l.id=? and l.active=true and l.status='PUBLISHED'
        and ch.active=true and ch.content_status='PUBLISHED' and c.active=true and s.active=true
        and c.code=(select class_code from student where id=?)
      """,context.studentId(),context.studentId(),language,language,language,language,language,lessonId,context.studentId());
    if(lessons.isEmpty()) throw new LessonNotFoundException(lessonId);
    Map<String,Object> result=new LinkedHashMap<>(lessons.getFirst());
    result.put("requested_language",language);
    List<Map<String,Object>> blocks=jdbc.queryForList("""
      select b.id,b.sequence_no,b.block_type,b.asset_id,
             strip_answer_keys(coalesce(bt.content,b.content))::text as content,
             (bt.id is not null) as translated,
             a.url as asset_url,a.alt_text as asset_alt
      from lesson_block b left join content_asset a on a.id=b.asset_id and a.status='PUBLISHED'
      left join lateral (
        select t.id,t.content from lesson_block_translation t
        where t.block_id=b.id and t.language=? and t.translation_status='PUBLISHED'
          and t.source_hash::text=md5(b.content::text)
        order by t.version_no desc,t.id desc limit 1
      ) bt on true
      where b.lesson_id=? and b.active=true order by b.sequence_no,b.id
      """,language,lessonId);
    List<Map<String,Object>> questions=publicQuestions(lessonId,context.studentId(),language);
    result.put("blocks",blocks);
    result.put("questions",questions);
    boolean translationComplete=Boolean.TRUE.equals(result.get("lesson_translated"))
        && Boolean.TRUE.equals(result.get("chapter_translated"))
        && Boolean.TRUE.equals(result.get("subject_translated"))
        && Boolean.TRUE.equals(result.get("objective_translated"))
        && blocks.stream().allMatch(block -> Boolean.TRUE.equals(block.get("translated")))
        && questions.stream().allMatch(question -> Boolean.TRUE.equals(question.get("translated")));
    result.put("translation_complete",translationComplete);
    return result;
  }

  @GetMapping("/lessons/{lessonId}/questions")
  public List<Map<String,Object>> questions(@PathVariable long lessonId,
      @RequestParam(required=false,defaultValue="hi") String language,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    context=authorization.requireStudent(context);
    language=normalizeLanguage(language);
    ensureStudentLessonAccess(context.studentId(),lessonId);
    return publicQuestions(lessonId,context.studentId(),language);
  }

  @PostMapping("/questions/{questionId}/answer")
  @Transactional
  public Map<String,Object> answer(@PathVariable long questionId,
      @RequestParam(required=false,defaultValue="hi") String language,
      @RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    context=authorization.requireStudent(context);
    language=normalizeLanguage(language);
    Long practiceSessionId=null;
    Object sessionValue=body.get("practiceSessionId");
    if(sessionValue!=null) {
      try { practiceSessionId=Long.valueOf(String.valueOf(sessionValue)); }
      catch(NumberFormatException ex) { throw new IllegalArgumentException("Practice session is invalid."); }
      List<Map<String,Object>> membership=jdbc.queryForList("""
        select psq.question_id
        from student_practice_session_question psq
        join student_practice_session ps on ps.id=psq.practice_session_id
        where ps.id=? and ps.student_id=? and psq.question_id=?
          and ps.status='IN_PROGRESS' and psq.answered_at is null
        for update of psq,ps
        """,practiceSessionId,context.studentId(),questionId);
      if(membership.isEmpty())throw new SecurityException("Question is not part of this active practice session or was already answered.");
    }
    var rows=jdbc.queryForList("""
      select q.id,q.lesson_id,q.question_type,coalesce(qt.explanation,q.explanation) as explanation,
             q.answer_payload::text as answer_payload
      from question q join lesson l on l.id=q.lesson_id
      join curriculum_chapter ch on ch.id=l.chapter_id
      left join lateral (
        select t.id,t.explanation from question_translation t
        where t.question_id=q.id and t.language=? and t.translation_status='PUBLISHED'
          and t.source_hash::text=md5(jsonb_build_object(
            'prompt',q.prompt,'explanation',q.explanation,
            'options',coalesce((select jsonb_agg(jsonb_build_object('key',qo.option_key,'label',qo.label) order by qo.sort_order)
                                from question_option qo where qo.question_id=q.id),'[]'::jsonb),
            'topic',q.topic,'subtopic',q.subtopic,'skill',q.skill,'tags',q.tags
          )::text)
        order by t.version_no desc,t.id desc limit 1
      ) qt on true
      where q.id=? and q.active=true and q.review_status in ('APPROVED','PUBLISHED') and l.active=true and l.status='PUBLISHED'
        and ch.active=true and ch.content_status='PUBLISHED'
      """,language,questionId);
    if(rows.isEmpty()) throw new IllegalArgumentException("Question not found or no longer eligible for student practice.");
    var q=rows.getFirst();
    long lessonId=((Number)q.get("lesson_id")).longValue();
    ensureStudentLessonAccess(context.studentId(),lessonId);
    Object submittedObject=body.get("answer");
    if(submittedObject==null)submittedObject="";
    String submitted=submittedObject instanceof String ? ((String)submittedObject).trim() : submittedObject.toString();
    String payload=String.valueOf(q.get("answer_payload"));
    QuestionAnswerService.Evaluation evaluation=answerService.evaluate(payload,submittedObject);
    if(practiceSessionId==null) {
      state.recordAttempt(context.studentId(),questionId,lessonId,submitted,evaluation.autoGraded(),evaluation.correct());
    } else {
      state.recordAttempt(context.studentId(),questionId,lessonId,submitted,evaluation.autoGraded(),evaluation.correct(),practiceSessionId);
    }

    var result=new LinkedHashMap<String,Object>();
    result.put("questionId",questionId); result.put("questionType",q.get("question_type"));
    result.put("correct",evaluation.correct()); result.put("autoGraded",evaluation.autoGraded()); result.put("gradingKind",evaluation.kind());
    result.put("explanation",q.get("explanation"));
    result.put("feedback",language.equals("en")
      ? (evaluation.autoGraded()
          ? (Boolean.TRUE.equals(evaluation.correct()) ? "Correct. Now explain why your answer works." : "Not quite. Review the solution and try again.")
          : "Your answer was saved. This response may need teacher or rubric-based review.")
      : (evaluation.autoGraded()
          ? (Boolean.TRUE.equals(evaluation.correct()) ? "सही। अब अपने उत्तर का कारण बताइए।" : "अभी सही नहीं है। समाधान दोबारा देखें और फिर प्रयास करें।")
          : "आपका उत्तर सुरक्षित कर लिया गया है। इस प्रश्न की शिक्षक या मूल्यांकन-निर्देश के अनुसार जाँच आवश्यक है।"));
    return result;
  }

  private List<Map<String,Object>> publicQuestions(long lessonId,long studentId,String language){
    return jdbc.queryForList("""
      select q.id,q.question_type,coalesce(qt.prompt,q.prompt) as prompt,q.difficulty,q.sort_order,q.concept_id,
             q.source_kind,q.source_title,q.source_ref,q.source_year,q.board,q.marks,q.exam_format,
             coalesce(qt.topic,q.topic) as topic,coalesce(qt.subtopic,q.subtopic) as subtopic,
             coalesce(qt.skill,q.skill) as skill,coalesce(qt.tags::text,q.tags::text) as tags,
             (qt.id is not null) as translated,
             exists(select 1 from student_question_attempt a
                    where a.student_id=? and a.question_id=q.id and a.activity_kind='LESSON_INLINE') as attempted,
             case when qt.id is not null then coalesce(qt.options,'[]'::jsonb)
                  when q.question_type in ('MCQ','TRUE_FALSE') then
                    coalesce((select jsonb_agg(jsonb_build_object('key',qo.option_key,'label',qo.label)
                      order by qo.sort_order) from question_option qo where qo.question_id=q.id),'[]'::jsonb)
               else '[]'::jsonb end::text as options,
             case when q.question_type in ('INPUT','NUMERICAL','SHORT_ANSWER','LONG_ANSWER','MATCH','ORDER',
                 'ASSERTION_REASON','CASE_BASED','DIAGRAM','MAP','SOURCE_BASED') then 'text'
               else 'choice' end as response_mode
      from question q
      left join lateral (
        select t.id,t.prompt,t.explanation,t.options,t.topic,t.subtopic,t.skill,t.tags
        from question_translation t
        where t.question_id=q.id and t.language=? and t.translation_status='PUBLISHED'
          and t.source_hash::text=md5(jsonb_build_object(
            'prompt',q.prompt,'explanation',q.explanation,
            'options',coalesce((select jsonb_agg(jsonb_build_object('key',qo.option_key,'label',qo.label) order by qo.sort_order)
                                from question_option qo where qo.question_id=q.id),'[]'::jsonb),
            'topic',q.topic,'subtopic',q.subtopic,'skill',q.skill,'tags',q.tags
          )::text)
        order by t.version_no desc,t.id desc limit 1
      ) qt on true
      where q.lesson_id=? and q.active=true and q.review_status in ('APPROVED','PUBLISHED') order by q.sort_order
      """,studentId,language,lessonId);
  }

  // Source-compatible overloads retain the original Java call signatures used by tests and services.
  // HTTP handlers continue to use the language-aware methods above.
  public List<Map<String,Object>> lessons(String classCode,String subjectCode,AuthContext context) {
    return lessons(classCode,subjectCode,"hi",context);
  }

  public Map<String,Object> lesson(long lessonId,AuthContext context) {
    return lesson(lessonId,"hi",context);
  }

  public List<Map<String,Object>> questions(long lessonId,AuthContext context) {
    return questions(lessonId,"hi",context);
  }

  public Map<String,Object> answer(long questionId,Map<String,Object> body,AuthContext context) {
    return answer(questionId,"hi",body,context);
  }

  private String normalizeLanguage(String language) {
    String normalized = language == null ? "hi" : language.trim().toLowerCase(Locale.ROOT);
    if ("hi".equals(normalized) || "en".equals(normalized)) return normalized;
    throw new IllegalArgumentException("Language must be 'hi' or 'en'.");
  }

  private void ensureStudentLessonAccess(long studentId,long lessonId){
    Long count=jdbc.queryForObject("""
      select count(*) from lesson l
      join curriculum_chapter ch on ch.id=l.chapter_id
      join curriculum_subject s on s.id=ch.subject_id
      join curriculum_class c on c.id=s.class_id
      join student st on st.id=? and st.active=true
      join student_track_enrollment ste on ste.student_id=st.id and ste.subject_id=s.id and ste.status='ACTIVE'
      where l.id=? and l.active=true and l.status='PUBLISHED'
        and ch.active=true and ch.content_status='PUBLISHED'
        and c.active=true and s.active=true and c.code=st.class_code
      """,Long.class,studentId,lessonId);
    if(count==null||count==0) throw new SecurityException("Lesson access denied");
  }

}
