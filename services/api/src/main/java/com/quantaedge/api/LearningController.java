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
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    context=authorization.requireStudent(context);
    if (!"maths".equals(subjectCode) && !"science".equals(subjectCode)) throw new IllegalArgumentException("Unknown subject track");
    String enrolledClass=jdbc.queryForObject("select class_code from student where id=? and active=true",String.class,context.studentId());
    if(!classCode.equals(enrolledClass)) throw new SecurityException("Class access denied");
    return jdbc.queryForList("""
      select l.id,l.code,l.title,l.summary,l.estimated_minutes,l.status,
             c.code as class_code,s.code as subject_code,s.display_name as subject_name,
             ch.code as chapter_code,ch.display_name as chapter_name,
             o.code as objective_code,o.title as objective_title
      from lesson l join curriculum_chapter ch on ch.id=l.chapter_id
      join curriculum_subject s on s.id=ch.subject_id join curriculum_class c on c.id=s.class_id
      join student_track_enrollment ste on ste.student_id=? and ste.subject_id=s.id and ste.status='ACTIVE'
      left join learning_objective o on o.id=l.objective_id
      where c.code=? and s.code=? and c.active=true and s.active=true
        and ch.active=true and ch.content_status='PUBLISHED' and l.active=true and l.status='PUBLISHED'
      order by coalesce(ch.teaching_order,ch.sort_order),l.sort_order,l.id
      """,context.studentId(),classCode,subjectCode);
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
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    context=authorization.requireStudent(context);
    List<Map<String,Object>> lessons=jdbc.queryForList("""
      select l.id,l.code,l.title,l.summary,l.estimated_minutes,l.status,
             ch.code as chapter_code,ch.display_name as chapter_name,
             c.code as class_code,c.display_name as class_name,
             s.code as subject_code,s.display_name as subject_name,
             o.code as objective_code,o.title as objective_title,
             coalesce(p.progress_percent,0) as progress_percent,
             coalesce(p.status,'NOT_STARTED') as progress_status
      from lesson l join curriculum_chapter ch on ch.id=l.chapter_id
      join curriculum_subject s on s.id=ch.subject_id join curriculum_class c on c.id=s.class_id
      join student_track_enrollment ste on ste.student_id=? and ste.subject_id=s.id and ste.status='ACTIVE'
      left join learning_objective o on o.id=l.objective_id
      left join student_lesson_progress p on p.lesson_id=l.id and p.student_id=?
      where l.id=? and l.active=true and l.status='PUBLISHED'
        and ch.active=true and ch.content_status='PUBLISHED' and c.active=true and s.active=true
        and c.code=(select class_code from student where id=?)
      """,context.studentId(),context.studentId(),lessonId,context.studentId());
    if(lessons.isEmpty()) throw new LessonNotFoundException(lessonId);
    Map<String,Object> result=new LinkedHashMap<>(lessons.getFirst());
    result.put("blocks",jdbc.queryForList("""
      select b.id,b.sequence_no,b.block_type,b.asset_id,
             strip_answer_keys(b.content) as content,
             a.url as asset_url,a.alt_text as asset_alt
      from lesson_block b left join content_asset a on a.id=b.asset_id and a.status='PUBLISHED'
      where b.lesson_id=? and b.active=true order by b.sequence_no,b.id
      """,lessonId));
    result.put("questions",publicQuestions(lessonId));
    return result;
  }

  @GetMapping("/lessons/{lessonId}/questions")
  public List<Map<String,Object>> questions(@PathVariable long lessonId,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    context=authorization.requireStudent(context);
    ensureStudentLessonAccess(context.studentId(),lessonId);
    return publicQuestions(lessonId);
  }

  @PostMapping("/questions/{questionId}/answer")
  @Transactional
  public Map<String,Object> answer(@PathVariable long questionId,
      @RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    context=authorization.requireStudent(context);
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
      select q.id,q.lesson_id,q.question_type,q.explanation,q.answer_payload::text as answer_payload
      from question q join lesson l on l.id=q.lesson_id
      join curriculum_chapter ch on ch.id=l.chapter_id
      where q.id=? and q.active=true and q.review_status in ('APPROVED','PUBLISHED') and l.active=true and l.status='PUBLISHED'
        and ch.active=true and ch.content_status='PUBLISHED'
      """,questionId);
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
    result.put("feedback",evaluation.autoGraded()
      ? (Boolean.TRUE.equals(evaluation.correct())?"सही। अब अपने उत्तर का कारण बताइए।":"अभी सही नहीं। समाधान दोबारा देखें और फिर प्रयास करें।")
      : "उत्तर सेव हो गया। इस प्रश्न के लिए teacher/rubric आधारित जाँच आवश्यक है।");
    return result;
  }

  private List<Map<String,Object>> publicQuestions(long lessonId){
    return jdbc.queryForList("""
      select q.id,q.question_type,q.prompt,q.difficulty,q.sort_order,q.concept_id,
             q.source_kind,q.source_title,q.source_ref,q.source_year,q.board,q.marks,q.exam_format,
             q.topic,q.subtopic,q.skill,q.tags::text as tags,
             case when q.question_type in ('MCQ','TRUE_FALSE') then
               coalesce((select jsonb_agg(jsonb_build_object('key',qo.option_key,'label',qo.label)
                 order by qo.sort_order) from question_option qo where qo.question_id=q.id),'[]'::jsonb)
               else '[]'::jsonb end::text as options,
             case when q.question_type in ('INPUT','NUMERICAL','SHORT_ANSWER','LONG_ANSWER','MATCH','ORDER',
                 'ASSERTION_REASON','CASE_BASED','DIAGRAM','MAP','SOURCE_BASED') then 'text'
               else 'choice' end as response_mode
      from question q where q.lesson_id=? and q.active=true and q.review_status in ('APPROVED','PUBLISHED') order by q.sort_order
      """,lessonId);
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
