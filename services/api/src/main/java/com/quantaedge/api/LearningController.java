package com.quantaedge.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/learning")
public class LearningController {
  private final JdbcTemplate jdbc;
  private final AuthorizationService authorization;
  private final LearningStateService state;

  public LearningController(JdbcTemplate jdbc, AuthorizationService authorization, LearningStateService state) {
    this.jdbc=jdbc; this.authorization=authorization; this.state=state;
  }

  @GetMapping("/lessons")
  public List<Map<String,Object>> lessons(@RequestParam String classCode,@RequestParam String subjectCode) {
    return jdbc.queryForList("""
      select l.id,l.code,l.title,l.summary,l.estimated_minutes,l.status,
             ch.code as chapter_code,ch.display_name as chapter_name,
             o.code as objective_code,o.title as objective_title
      from lesson l join curriculum_chapter ch on ch.id=l.chapter_id
      join curriculum_subject s on s.id=ch.subject_id join curriculum_class c on c.id=s.class_id
      left join learning_objective o on o.id=l.objective_id
      where c.code=? and s.code=? and c.active=true and s.active=true
        and ch.active=true and ch.content_status='PUBLISHED' and l.active=true and l.status='PUBLISHED'
      order by ch.sort_order,l.sort_order
      """,classCode,subjectCode);
  }

  @PostMapping("/lessons/{lessonId}/start")
  public Map<String,Object> start(@PathVariable long lessonId,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    context=authorization.requireStudent(context);
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
      left join learning_objective o on o.id=l.objective_id
      left join student_lesson_progress p on p.lesson_id=l.id and p.student_id=?
      where l.id=? and l.active=true and l.status='PUBLISHED'
        and ch.active=true and ch.content_status='PUBLISHED' and c.active=true and s.active=true
        and c.code=(select class_code from student where id=?)
      """,context.studentId(),lessonId,context.studentId());
    if(lessons.isEmpty()) throw new LessonNotFoundException(lessonId);
    Map<String,Object> result=new LinkedHashMap<>(lessons.getFirst());
    result.put("blocks",jdbc.queryForList("""
      select b.id,b.sequence_no,b.block_type,b.asset_id,
             case when b.block_type in ('QUESTION','MCQ','TRUE_FALSE','MATCH','ORDER','INPUT')
                  then (b.content-'correctOption'-'answer'-'answer_payload') else b.content end as content,
             a.url as asset_url,a.alt_text as asset_alt
      from lesson_block b left join content_asset a on a.id=b.asset_id and a.status='PUBLISHED'
      where b.lesson_id=? and b.active=true order by b.sequence_no
      """,lessonId));
    result.put("questions",publicQuestions(lessonId));
    return result;
  }

  @GetMapping("/lessons/{lessonId}/questions")
  public List<Map<String,Object>> questions(@PathVariable long lessonId,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    authorization.requireStudent(context);
    return publicQuestions(lessonId);
  }

  @PostMapping("/questions/{questionId}/answer")
  public Map<String,Object> answer(@PathVariable long questionId,
      @RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    context=authorization.requireStudent(context);
    var rows=jdbc.queryForList("""
      select q.id,q.lesson_id,q.question_type,q.explanation,q.answer_payload::text as answer_payload
      from question q join lesson l on l.id=q.lesson_id
      join curriculum_chapter ch on ch.id=l.chapter_id
      where q.id=? and q.active=true and l.active=true and l.status='PUBLISHED'
        and ch.active=true and ch.content_status='PUBLISHED'
      """,questionId);
    if(rows.isEmpty()) throw new IllegalArgumentException("Question not found");
    var q=rows.getFirst();
    long lessonId=((Number)q.get("lesson_id")).longValue();
    ensureStudentLessonAccess(context.studentId(),lessonId);
    String submitted=String.valueOf(body.getOrDefault("answer","")).trim();
    String payload=String.valueOf(q.get("answer_payload"));
    String expected=extractJsonString(payload,"value");
    String kind=extractJsonString(payload,"kind");
    boolean autoGraded=!"".equals(expected)&&("OPTION".equals(kind)||"TEXT".equals(kind));
    Boolean correct=autoGraded?normalize(submitted).equals(normalize(expected)):null;
    state.recordAttempt(context.studentId(),questionId,lessonId,submitted,autoGraded,correct);

    var result=new LinkedHashMap<String,Object>();
    result.put("questionId",questionId); result.put("questionType",q.get("question_type"));
    result.put("correct",correct); result.put("autoGraded",autoGraded); result.put("explanation",q.get("explanation"));
    result.put("feedback",autoGraded?(Boolean.TRUE.equals(correct)?"सही। अब अपने उत्तर का कारण बताइए।":"अभी सही नहीं। समाधान दोबारा देखें और फिर प्रयास करें।")
        :"उत्तर सेव हो गया। इस प्रश्न को rubric/teacher review से जाँचा जाएगा।");
    return result;
  }

  private List<Map<String,Object>> publicQuestions(long lessonId){
    return jdbc.queryForList("""
      select q.id,q.question_type,q.prompt,q.explanation,q.difficulty,q.sort_order,q.concept_id,
             q.source_kind,q.source_title,q.source_ref,q.source_year,q.board,q.marks,q.exam_format,
             q.topic,q.subtopic,q.skill,q.tags::text as tags,
             case when q.question_type in ('MCQ','TRUE_FALSE') then
               coalesce((select jsonb_agg(jsonb_build_object('key',qo.option_key,'label',qo.label)
                 order by qo.sort_order) from question_option qo where qo.question_id=q.id),'[]'::jsonb)
               else '[]'::jsonb end::text as options,
             case when q.question_type in ('INPUT','NUMERICAL','SHORT_ANSWER','LONG_ANSWER','MATCH','ORDER',
                 'ASSERTION_REASON','CASE_BASED','DIAGRAM','MAP','SOURCE_BASED') then 'text'
               else 'choice' end as response_mode
      from question q where q.lesson_id=? and q.active=true order by q.sort_order
      """,lessonId);
  }

  private void ensureStudentLessonAccess(long studentId,long lessonId){
    Long count=jdbc.queryForObject("""
      select count(*) from lesson l join curriculum_chapter ch on ch.id=l.chapter_id
      join curriculum_subject s on s.id=ch.subject_id join curriculum_class c on c.id=s.class_id
      where l.id=? and l.active=true and l.status='PUBLISHED' and ch.active=true and ch.content_status='PUBLISHED'
        and c.code=(select class_code from student where id=?)
      """,Long.class,lessonId,studentId);
    if(count==0) throw new SecurityException("Lesson access denied");
  }

  private String normalize(String value){ return value.trim().replaceAll("\\s+"," ").toLowerCase(Locale.ROOT); }
  private String extractJsonString(String json,String key){
    String marker="\"" + key + "\":\""; int start=json.indexOf(marker); if(start<0)return "";
    start+=marker.length(); int end=json.indexOf("\"",start); return end<0?"":json.substring(start,end);
  }
}
