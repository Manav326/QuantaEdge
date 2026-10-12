package com.quantaedge.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/v1/diagnostic")
public class DiagnosticController {
  private final JdbcTemplate jdbc;
  private final AuthorizationService authorization;
  private final QuestionHistoryService questionHistory;
  private final QuestionAnswerService answerService;
  private final LearningStateService learningState;
  private final ObjectMapper mapper;

  public DiagnosticController(JdbcTemplate jdbc,AuthorizationService authorization,
      QuestionHistoryService questionHistory,QuestionAnswerService answerService,
      LearningStateService learningState,ObjectMapper mapper) {
    this.jdbc=jdbc;this.authorization=authorization;this.questionHistory=questionHistory;
    this.answerService=answerService;this.learningState=learningState;this.mapper=mapper;
  }

  /** A refresh resumes the existing frozen attempt; only explicit submit marks it complete. */
  @GetMapping
  @Transactional
  public Map<String,Object> get(@RequestAttribute(value="authContext",required=false) AuthContext context) {
    context=authorization.requireStudent(context);
    long studentId=context.studentId();
    List<Map<String,Object>> active=jdbc.queryForList("""
      select id,status,question_count,started_at from student_diagnostic_attempt
      where student_id=? and status='IN_PROGRESS'
      order by started_at desc,id desc limit 1 for update
      """,studentId);
    if(active.isEmpty()) {
      Long submitted=jdbc.queryForObject(
          "select count(*) from student_diagnostic_attempt where student_id=? and status='SUBMITTED'",
          Long.class,studentId);
      if(submitted!=null&&submitted>0) return Map.of("completed",true,"available",true,"questions",List.of());
      List<Map<String,Object>> candidates=jdbc.queryForList("""
        select q.id
        from question q
        join lesson l on l.id=q.lesson_id
        join curriculum_chapter ch on ch.id=l.chapter_id
        join curriculum_subject s on s.id=ch.subject_id
        join curriculum_class c on c.id=s.class_id
        join student_track_enrollment ste on ste.student_id=? and ste.subject_id=s.id and ste.status='ACTIVE'
        where q.active=true and q.review_status in ('APPROVED','PUBLISHED')
          and l.active=true and l.status='PUBLISHED'
          and ch.active=true and ch.content_status='PUBLISHED'
          and c.active=true and s.active=true
          and c.code=(select class_code from student where id=? and active=true)
          and q.question_type in ('MCQ','TRUE_FALSE')
        order by s.sort_order,coalesce(ch.teaching_order,ch.sort_order),l.sort_order,l.id,q.sort_order,q.id
        limit 10
        """,studentId,studentId);
      List<Long> ids=candidates.stream().map(row->number(row.get("id"))).toList();
      if(ids.isEmpty()) return Map.of("completed",false,"available",false,"questions",List.of());
      Long attemptId=jdbc.queryForObject("""
        insert into student_diagnostic_attempt(student_id,status,question_count)
        values(?,'IN_PROGRESS',?) returning id
        """,Long.class,studentId,ids.size());
      if(attemptId==null) throw new IllegalStateException("Diagnostic attempt could not be created.");
      int sequence=1;
      for(Long questionId:ids) {
        Map<String,Object> snapshot=questionHistory.snapshot(questionId);
        if(snapshot.isEmpty()) continue;
        jdbc.update("""
          insert into student_diagnostic_attempt_question(
            diagnostic_attempt_id,question_id,sequence_no,lesson_id,question_snapshot
          ) values(?,?,?,?,?::jsonb)
          """,attemptId,questionId,sequence++,number(snapshot.get("lesson_id")),questionHistory.json(snapshot));
      }
      jdbc.update("update student_diagnostic_attempt set question_count=? where id=?",sequence-1,attemptId);
      active=jdbc.queryForList("""
        select id,status,question_count,started_at from student_diagnostic_attempt where id=? and student_id=?
        """,attemptId,studentId);
    }
    Map<String,Object> attempt=active.getFirst();
    long attemptId=number(attempt.get("id"));
    List<Map<String,Object>> rows=jdbc.queryForList("""
      select question_id,sequence_no,question_snapshot::text as question_snapshot,
             submitted_answer::text as submitted_answer,is_correct,answered_at
      from student_diagnostic_attempt_question where diagnostic_attempt_id=? order by sequence_no
      """,attemptId);
    List<Map<String,Object>> questions=new ArrayList<>();
    for(Map<String,Object> row:rows) {
      Map<String,Object> question=publicQuestion(parseObject(String.valueOf(row.get("question_snapshot"))));
      question.put("answered",row.get("answered_at")!=null);
      question.put("lastCorrect",row.get("is_correct"));
      question.put("selectedAnswer",parseValue(row.get("submitted_answer")));
      questions.add(question);
    }
    Map<String,Object> result=new LinkedHashMap<>();
    result.put("completed",false);result.put("available",!questions.isEmpty());
    result.put("attemptId",attemptId);result.put("status",attempt.get("status"));
    result.put("questionCount",questions.size());result.put("questions",questions);
    return result;
  }

  @PostMapping("/attempts/{attemptId}/answers/{questionId}")
  @Transactional
  public Map<String,Object> answer(@PathVariable long attemptId,@PathVariable long questionId,
      @RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    context=authorization.requireStudent(context);
    List<Map<String,Object>> rows=jdbc.queryForList("""
      select daq.lesson_id,daq.question_snapshot::text as question_snapshot,daq.answered_at
      from student_diagnostic_attempt_question daq
      join student_diagnostic_attempt da on da.id=daq.diagnostic_attempt_id
      where da.id=? and da.student_id=? and da.status='IN_PROGRESS' and daq.question_id=?
      for update of daq,da
      """,attemptId,context.studentId(),questionId);
    if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Diagnostic question was not found in this active attempt.");
    if(rows.getFirst().get("answered_at")!=null)
      throw new ResponseStatusException(HttpStatus.CONFLICT,"This diagnostic question has already been answered.");
    Map<String,Object> snapshot=parseObject(String.valueOf(rows.getFirst().get("question_snapshot")));
    Object submitted=body.get("answer");
    if(submitted==null||String.valueOf(submitted).isBlank())
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Provide an answer before continuing.");
    QuestionAnswerService.Evaluation evaluation=answerService.evaluate(
        String.valueOf(snapshot.getOrDefault("answer_payload","{}")),submitted);
    String serialized;
    try { serialized=mapper.writeValueAsString(submitted); }
    catch(JacksonException ex) { throw new IllegalArgumentException("The answer could not be saved.",ex); }
    jdbc.update("""
      update student_diagnostic_attempt_question
      set submitted_answer=?::jsonb,is_correct=?,answered_at=now()
      where diagnostic_attempt_id=? and question_id=?
      """,serialized,evaluation.correct(),attemptId,questionId);
    learningState.recordAttempt(context.studentId(),questionId,number(rows.getFirst().get("lesson_id")),
        String.valueOf(submitted),evaluation.autoGraded(),evaluation.correct(),null,"DIAGNOSTIC");
    Map<String,Object> response=new LinkedHashMap<>();
    response.put("questionId",questionId);response.put("correct",evaluation.correct());
    response.put("graded",evaluation.autoGraded());
    response.put("feedback",evaluation.autoGraded()
      ?(Boolean.TRUE.equals(evaluation.correct())?"Correct.":"Review the explanation and continue.")
      :"Answer saved for review.");
    return response;
  }

  @PostMapping("/attempts/{attemptId}/submit")
  @Transactional
  public Map<String,Object> submit(@PathVariable long attemptId,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    context=authorization.requireStudent(context);
    List<Map<String,Object>> rows=jdbc.queryForList("""
      select id,question_count,status from student_diagnostic_attempt
      where id=? and student_id=? for update
      """,attemptId,context.studentId());
    if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Diagnostic attempt not found.");
    String status=String.valueOf(rows.getFirst().get("status"));
    if("SUBMITTED".equals(status)) return Map.of("submitted",true,"attemptId",attemptId,"status",status);
    if(!"IN_PROGRESS".equals(status)) throw new ResponseStatusException(HttpStatus.CONFLICT,"This diagnostic attempt is closed.");
    Long answered=jdbc.queryForObject("""
      select count(*) from student_diagnostic_attempt_question
      where diagnostic_attempt_id=? and answered_at is not null
      """,Long.class,attemptId);
    int required=((Number)rows.getFirst().get("question_count")).intValue();
    if(answered==null||answered<required)
      throw new ResponseStatusException(HttpStatus.CONFLICT,"Answer all diagnostic questions before submitting.");
    jdbc.update("""
      update student_diagnostic_attempt set status='SUBMITTED',submitted_at=now(),updated_at=now()
      where id=? and student_id=? and status='IN_PROGRESS'
      """,attemptId,context.studentId());
    return Map.of("submitted",true,"attemptId",attemptId,"status","SUBMITTED","answeredCount",answered);
  }

  private Map<String,Object> publicQuestion(Map<String,Object> snapshot) {
    Map<String,Object> result=new LinkedHashMap<>();
    for(String key:List.of("id","lesson_id","question_type","prompt","explanation","marks","exam_format","source_kind","source_year","board"))
      if(snapshot.containsKey(key)) result.put(key,snapshot.get(key));
    List<Map<String,Object>> options=new ArrayList<>();
    Object raw=snapshot.get("options");
    if(raw instanceof List<?> entries) for(Object entry:entries) {
      if(!(entry instanceof Map<?,?> option)) continue;
      Map<String,Object> item=new LinkedHashMap<>();
      item.put("key",option.containsKey("option_key")?option.get("option_key"):option.get("key"));
      item.put("label",option.get("label"));options.add(item);
    }
    try { result.put("options",mapper.writeValueAsString(options)); }
    catch(JacksonException ex) { throw new IllegalStateException("Diagnostic options could not be serialized.",ex); }
    return result;
  }
  private Map<String,Object> parseObject(String source) {
    try {
      @SuppressWarnings("unchecked") Map<String,Object> parsed=(Map<String,Object>)mapper.readValue(source,Map.class);
      return parsed;
    } catch(JacksonException ex) { throw new IllegalStateException("Stored diagnostic question could not be read.",ex); }
  }
  private Object parseValue(Object raw) {
    if(raw==null) return null;
    try { return mapper.readValue(String.valueOf(raw),Object.class); }
    catch(JacksonException ex) { return null; }
  }
  private long number(Object value) { return value instanceof Number n?n.longValue():Long.parseLong(String.valueOf(value)); }
}
