package com.quantaedge.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/v1/learning/practice")
public class PracticeController {
  private final JdbcTemplate jdbc;
  private final AuthorizationService authorization;
  private final QuestionHistoryService questionHistory;
  private final ObjectMapper mapper;

  public PracticeController(JdbcTemplate jdbc, AuthorizationService authorization,
      QuestionHistoryService questionHistory, ObjectMapper mapper) {
    this.jdbc=jdbc;
    this.authorization=authorization;
    this.questionHistory=questionHistory;
    this.mapper=mapper;
  }

  @GetMapping("/catalog")
  public List<Map<String,Object>> catalog(
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    context=authorization.requireStudent(context);
    return jdbc.queryForList("""
      select ch.id as chapter_id,ch.code as chapter_code,ch.display_name as chapter_name,
             s.code as subject_code,s.display_name as subject_name,
             coalesce(nullif(btrim(q.topic),''),ch.display_name) as topic_name,
             coalesce(nullif(btrim(q.subtopic),''),'') as subtopic_name,
             count(distinct q.id) as question_count,
             coalesce(jsonb_agg(distinct q.question_type order by q.question_type),'[]'::jsonb)::text as question_types
      from student st
      join curriculum_class c on c.code=st.class_code and c.active=true
      join curriculum_subject s on s.class_id=c.id and s.active=true
      join curriculum_chapter ch on ch.subject_id=s.id and ch.active=true and ch.content_status='PUBLISHED'
      join lesson l on l.chapter_id=ch.id and l.active=true and l.status='PUBLISHED'
      join question q on q.lesson_id=l.id and q.active=true and q.review_status in ('APPROVED','PUBLISHED')
      where st.id=? and st.active=true
        and exists(select 1 from student_track_enrollment ste
                   where ste.student_id=st.id and ste.subject_id=s.id and ste.status='ACTIVE')
      group by ch.id,ch.code,ch.display_name,s.code,s.display_name,
               coalesce(nullif(btrim(q.topic),''),ch.display_name),
               coalesce(nullif(btrim(q.subtopic),''),'')
      order by s.display_name,coalesce(ch.teaching_order,ch.sort_order),
               ch.display_name,topic_name,subtopic_name
      """,context.studentId());
  }

  /**
   * Creates or resumes a practice session from the lesson the student just completed.
   * Students do not choose topics, question types, or question count; the server scopes
   * the pool to the lesson's objective/concept and prefers non-MCQ practice questions.
   */
  @PostMapping("/sessions/from-lesson")
  @Transactional
  public Map<String,Object> createSessionFromLesson(@RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    context=authorization.requireStudent(context);
    String language=normalizeLanguage(body.get("language"));
    int lessonId=integer(body.get("lessonId"),"lessonId",1,Integer.MAX_VALUE);

    // Serialize concurrent starts for the same student. This also makes React's development
    // Strict Mode / a double click idempotent when the first request is still in flight.
    List<Map<String,Object>> lockedStudent=jdbc.queryForList(
        "select id from student where id=? and active=true for update",context.studentId());
    if(lockedStudent.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Student profile not found.");

    String marker=json(List.of(Map.of("sourceLessonId",lessonId)));
    List<Map<String,Object>> existing=jdbc.queryForList("""
      select id from student_practice_session
      where student_id=? and status='IN_PROGRESS' and topic_selections @> ?::jsonb
      order by created_at desc,id desc limit 1
      """,context.studentId(),marker);
    if(!existing.isEmpty()) {
      long existingId=((Number)existing.getFirst().get("id")).longValue();
      return getSession(existingId,language,context);
    }

    List<Map<String,Object>> lessonRows=jdbc.queryForList("""
      select l.id,l.chapter_id,l.objective_id,l.title,
             ch.display_name as chapter_name,ch.code as chapter_code,
             s.code as subject_code,c.code as class_code
      from lesson l
      join curriculum_chapter ch on ch.id=l.chapter_id
      join curriculum_subject s on s.id=ch.subject_id
      join curriculum_class c on c.id=s.class_id
      join student st on st.id=? and st.active=true and st.class_code=c.code
      join student_track_enrollment ste on ste.student_id=st.id and ste.subject_id=s.id and ste.status='ACTIVE'
      where l.id=? and l.active=true and l.status='PUBLISHED'
        and ch.active=true and ch.content_status='PUBLISHED' and s.active=true and c.active=true
      """,context.studentId(),lessonId);
    if(lessonRows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Lesson not found or unavailable.");

    Map<String,Object> lesson=lessonRows.getFirst();
    long chapterId=((Number)lesson.get("chapter_id")).longValue();
    Object objective=lesson.get("objective_id");
    List<Long> concepts=jdbc.queryForList("""
      select distinct concept_id from question
      where lesson_id=? and concept_id is not null and active=true
        and review_status in ('APPROVED','PUBLISHED')
      """,Long.class,lessonId);

    // Imported draft bundles can have useful topic/subtopic labels without canonical
    // objective_id/concept_id mappings. Use those labels only as exact-match scope;
    // never broaden practice to arbitrary questions elsewhere in the chapter.
    List<String> topicNames=new ArrayList<>();
    List<String> subtopicNames=new ArrayList<>();
    String lessonTitleForScope=String.valueOf(lesson.get("title")).trim().toLowerCase(java.util.Locale.ROOT);
    if(!lessonTitleForScope.isBlank() && !"null".equals(lessonTitleForScope)) topicNames.add(lessonTitleForScope);
    List<Map<String,Object>> metadataRows=jdbc.queryForList("""
      select distinct nullif(lower(btrim(topic)),'') as topic,
             nullif(lower(btrim(subtopic)),'') as subtopic
      from question
      where lesson_id=? and active=true
      """,lessonId);
    for(Map<String,Object> row:metadataRows) {
      Object topic=row.get("topic");
      if(topic!=null && !String.valueOf(topic).isBlank() && !topicNames.contains(String.valueOf(topic))) {
        topicNames.add(String.valueOf(topic));
      }
      Object subtopic=row.get("subtopic");
      if(subtopic!=null && !String.valueOf(subtopic).isBlank() && !subtopicNames.contains(String.valueOf(subtopic))) {
        subtopicNames.add(String.valueOf(subtopic));
      }
    }

    String scopeFilter;
    List<Object> scopeArgs=new ArrayList<>();
    if(objective instanceof Number objectiveNumber) {
      if(concepts.isEmpty()) {
        scopeFilter="(q.objective_id=? or q.lesson_id=?)";
        scopeArgs.add(objectiveNumber.longValue());
        scopeArgs.add((long)lessonId);
      } else {
        String placeholders=String.join(",",java.util.Collections.nCopies(concepts.size(),"?"));
        scopeFilter="(q.objective_id=? or q.concept_id in ("+placeholders+") or q.lesson_id=?)";
        scopeArgs.add(objectiveNumber.longValue());
        scopeArgs.addAll(concepts);
        scopeArgs.add((long)lessonId);
      }
    } else if(!concepts.isEmpty()) {
      String placeholders=String.join(",",java.util.Collections.nCopies(concepts.size(),"?"));
      scopeFilter="(q.concept_id in ("+placeholders+") or q.lesson_id=?)";
      scopeArgs.addAll(concepts);
      scopeArgs.add((long)lessonId);
    } else {
      // No trustworthy objective/concept mapping: stay inside this lesson rather than
      // widening the set to unrelated questions from the same chapter.
      scopeFilter="q.lesson_id=?";
      scopeArgs.add((long)lessonId);
    }

    List<String> exactTagClauses=new ArrayList<>();
    List<Object> exactTagArgs=new ArrayList<>();
    if(!topicNames.isEmpty()) {
      exactTagClauses.add("lower(btrim(coalesce(q.topic,''))) in ("+
          String.join(",",java.util.Collections.nCopies(topicNames.size(),"?"))+")");
      exactTagArgs.addAll(topicNames);
    }
    if(!subtopicNames.isEmpty()) {
      exactTagClauses.add("lower(btrim(coalesce(q.subtopic,''))) in ("+
          String.join(",",java.util.Collections.nCopies(subtopicNames.size(),"?"))+")");
      exactTagArgs.addAll(subtopicNames);
    }
    if(!exactTagClauses.isEmpty()) {
      scopeFilter="("+scopeFilter+" or "+String.join(" or ",exactTagClauses)+")";
      scopeArgs.addAll(exactTagArgs);
    }

    int requestedCount=10;
    List<String> selectedTypes=List.of(
        "TRUE_FALSE","INPUT","NUMERICAL","MATCH","ORDER","ASSERTION_REASON","CASE_BASED",
        "SHORT_ANSWER","LONG_ANSWER","DIAGRAM","MAP","SOURCE_BASED");
    List<Long> selectedIds=selectAutoPracticeQuestions(context.studentId(),chapterId,selectedTypes,
        scopeFilter,scopeArgs,lessonId,requestedCount,false);

    // If this micro-topic has no non-MCQ assessment items, use unshown MCQs from another
    // lesson mapped to the same objective/concept. Never repeat the MCQs displayed on this lesson.
    if(selectedIds.isEmpty()) {
      selectedTypes=List.of("MCQ");
      selectedIds=selectAutoPracticeQuestions(context.studentId(),chapterId,selectedTypes,
          scopeFilter,scopeArgs,lessonId,requestedCount,true);
    }
    if(selectedIds.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "More approved practice questions are not published for this micro-topic yet. Your lesson answers are saved.");
    }

    String chapterName=String.valueOf(lesson.get("chapter_name"));
    String lessonTitle=String.valueOf(lesson.get("title"));
    List<Map<String,Object>> topicSelections=List.of(Map.of(
        "sourceLessonId",lessonId,"chapterId",chapterId,
        "chapterName",chapterName,"topic",lessonTitle));
    Long sessionId=jdbc.queryForObject("""
      insert into student_practice_session(
        student_id,status,requested_question_count,selected_question_count,question_types,topic_selections
      ) values(?,'IN_PROGRESS',?,?,?::jsonb,?::jsonb) returning id
      """,Long.class,context.studentId(),requestedCount,selectedIds.size(),json(selectedTypes),json(topicSelections));

    List<Map<String,Object>> questions=new ArrayList<>();
    int sequence=1;
    for(Long questionId:selectedIds) {
      Map<String,Object> snapshot=questionHistory.snapshot(questionId);
      if(snapshot.isEmpty()) continue;
      jdbc.update("""
        insert into student_practice_session_question(practice_session_id,question_id,sequence_no,question_snapshot)
        values(?,?,?,?::jsonb)
        """,sessionId,questionId,sequence++,json(snapshot));
      Map<String,Object> localized=localizeQuestionSnapshot(snapshot,language);
      questions.add(toPublicQuestion(localized));
    }
    if(questions.isEmpty()) throw badRequest("The selected questions are no longer available. Refresh the lesson and try again.");
    jdbc.update("update student_practice_session set selected_question_count=? where id=?",questions.size(),sessionId);

    Map<String,Object> result=new LinkedHashMap<>();
    result.put("sessionId",sessionId);
    result.put("status","IN_PROGRESS");
    result.put("requestedCount",requestedCount);
    result.put("selectedCount",questions.size());
    result.put("message",questions.size()<requestedCount
        ?"Only "+questions.size()+" approved practice question(s) are available for this micro-topic."
        :"Practice for "+lessonTitle+" is ready.");
    result.put("lessonId",lessonId);
    result.put("lessonTitle",lessonTitle);
    result.put("chapterName",chapterName);
    result.put("language",language);
    result.put("translationComplete",questions.stream().allMatch(q->Boolean.TRUE.equals(q.get("translated"))));
    result.put("questions",questions);
    return result;
  }

  private List<Long> selectAutoPracticeQuestions(long studentId,long chapterId,List<String> types,
      String scopeFilter,List<Object> scopeArgs,long currentLessonId,int requestedCount,boolean excludeCurrentLesson) {
    if(types.isEmpty()) return List.of();
    String typePlaceholders=String.join(",",java.util.Collections.nCopies(types.size(),"?"));
    String sql="""
      select q.id
      from question q
      join lesson l on l.id=q.lesson_id and l.active=true and l.status='PUBLISHED'
      join curriculum_chapter ch on ch.id=l.chapter_id and ch.active=true and ch.content_status='PUBLISHED'
      join curriculum_subject s on s.id=ch.subject_id and s.active=true
      join curriculum_class c on c.id=s.class_id and c.active=true
      join student st on st.id=? and st.active=true and st.class_code=c.code
      where ch.id=? and q.active=true and q.review_status in ('APPROVED','PUBLISHED')
        and exists(select 1 from student_track_enrollment ste
                   where ste.student_id=st.id and ste.subject_id=s.id and ste.status='ACTIVE')
        and q.question_type in (__TYPE_PLACEHOLDERS__)
        and (__SCOPE_FILTER__)
      """.replace("__TYPE_PLACEHOLDERS__",typePlaceholders).replace("__SCOPE_FILTER__",scopeFilter);
    if(excludeCurrentLesson) sql+=" and q.lesson_id<>?";
    sql+=" order by random() limit ?";
    List<Object> args=new ArrayList<>();
    args.add(studentId);
    args.add(chapterId);
    args.addAll(types);
    args.addAll(scopeArgs);
    if(excludeCurrentLesson) args.add(currentLessonId);
    args.add(requestedCount);
    return jdbc.queryForList(sql,Long.class,args.toArray());
  }

  @GetMapping("/sessions/{sessionId}")
  public Map<String,Object> getSession(@PathVariable long sessionId,
      @RequestParam(required=false,defaultValue="hi") String language,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    context=authorization.requireStudent(context);
    language=normalizeLanguage(language);
    List<Map<String,Object>> sessions=jdbc.queryForList("""
      select id,status,requested_question_count,selected_question_count,created_at,completed_at
      from student_practice_session where id=? and student_id=?
      """,sessionId,context.studentId());
    if(sessions.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Practice session not found.");
    List<Map<String,Object>> rows=jdbc.queryForList("""
      select psq.question_id,psq.sequence_no,psq.answered_at,psq.question_snapshot::text as question_snapshot,
             (select count(*) from student_question_attempt a
              where a.practice_session_id=ps.id and a.question_id=psq.question_id) as attempt_count,
             (select a.correct from student_question_attempt a
              where a.practice_session_id=ps.id and a.question_id=psq.question_id
              order by a.answered_at desc,a.id desc limit 1) as last_correct
      from student_practice_session_question psq
      join student_practice_session ps on ps.id=psq.practice_session_id
      where ps.id=? and ps.student_id=?
      order by psq.sequence_no
      """,sessionId,context.studentId());
    List<Map<String,Object>> questions=new ArrayList<>();
    for(Map<String,Object> row:rows) {
      try {
        @SuppressWarnings("unchecked")
        Map<String,Object> snapshot=(Map<String,Object>)mapper.readValue(String.valueOf(row.get("question_snapshot")),Map.class);
        Map<String,Object> localized=localizeQuestionSnapshot(snapshot,language);
        Map<String,Object> question=toPublicQuestion(localized);
        question.put("answered",row.get("answered_at")!=null);
        question.put("attemptCount",((Number)row.get("attempt_count")).intValue());
        question.put("lastCorrect",row.get("last_correct"));
        questions.add(question);
      } catch(JacksonException ex) {
        throw new IllegalStateException("Stored practice question could not be read.",ex);
      }
    }
    Map<String,Object> result=new LinkedHashMap<>(sessions.getFirst());
    result.put("sessionId",sessionId);
    result.put("language",language);
    result.put("translationComplete",questions.stream().allMatch(q->Boolean.TRUE.equals(q.get("translated"))));
    result.put("questions",questions);
    result.put("answeredCount",questions.stream().filter(q->Boolean.TRUE.equals(q.get("answered"))).count());
    return result;
  }

  private Map<String,Object> toPublicQuestion(Map<String,Object> snapshot) {
    Map<String,Object> result=new LinkedHashMap<>();
    copy(snapshot,result,"id","lesson_id","question_type","prompt","explanation","difficulty","sort_order",
        "source_kind","source_title","source_ref","source_year","board","marks","exam_format",
        "topic","subtopic","skill","tags");
    Object rawOptions=snapshot.get("options");
    List<Map<String,Object>> options=new ArrayList<>();
    if(rawOptions instanceof List<?> values) for(Object raw:values) {
      if(raw instanceof Map<?,?> option) {
        Map<String,Object> safe=new LinkedHashMap<>();
        Object key=option.get("option_key");
        if(key==null)key=option.get("key");
        safe.put("key",key);
        safe.put("label",option.get("label"));
        options.add(safe);
      }
    }
    result.put("options",json(options));
    result.put("translated",Boolean.TRUE.equals(snapshot.get("_translation_applied")));
    String type=String.valueOf(snapshot.get("question_type"));
    result.put("response_mode",Set.of("INPUT","NUMERICAL","SHORT_ANSWER","LONG_ANSWER","MATCH","ORDER",
        "ASSERTION_REASON","CASE_BASED","DIAGRAM","MAP","SOURCE_BASED").contains(type)?"text":"choice");
    return result;
  }

  private Map<String,Object> localizeQuestionSnapshot(Map<String,Object> source,String language) {
    Map<String,Object> localized=new LinkedHashMap<>(source);
    localized.put("_translation_applied",false);
    Object questionId=source.get("id");
    Object sourceHash=source.get("translation_source_hash");
    if(!(questionId instanceof Number) || sourceHash==null || String.valueOf(sourceHash).isBlank()) return localized;
    List<Map<String,Object>> translations=jdbc.queryForList("""
      select prompt,explanation,options::text as options,topic,subtopic,skill,tags::text as tags
      from question_translation
      where question_id=? and language=? and source_hash=? and translation_status='PUBLISHED'
      order by version_no desc,id desc
      limit 1
      """,((Number)questionId).longValue(),language,String.valueOf(sourceHash).trim());
    if(translations.isEmpty()) return localized;
    Map<String,Object> translated=translations.getFirst();
    for(String key:List.of("prompt","explanation","topic","subtopic","skill","tags")) {
      if(translated.get(key)!=null) localized.put(key,translated.get(key));
    }
    Object rawOptions=translated.get("options");
    if(rawOptions!=null) {
      try {
        @SuppressWarnings("unchecked")
        List<Map<String,Object>> translatedOptions=(List<Map<String,Object>>)mapper.readValue(String.valueOf(rawOptions),List.class);
        localized.put("options",translatedOptions);
      } catch(JacksonException ex) {
        throw new IllegalStateException("Published question translation options could not be read.",ex);
      }
    }
    localized.put("_translation_applied",true);
    return localized;
  }

  private String normalizeLanguage(Object value) {
    String language=value==null?"hi":String.valueOf(value).trim().toLowerCase(java.util.Locale.ROOT);
    if("hi".equals(language)||"en".equals(language)) return language;
    throw badRequest("Language must be 'hi' or 'en'.");
  }

  private void copy(Map<String,Object> source,Map<String,Object> target,String... keys) {
    for(String key:keys)if(source.containsKey(key))target.put(key,source.get(key));
  }

  private int integer(Object value,String name,int min,int max) {
    try {
      int parsed=value instanceof Number number?number.intValue():Integer.parseInt(String.valueOf(value));
      if(parsed<min||parsed>max)throw badRequest(name+" must be between "+min+" and "+max+".");
      return parsed;
    } catch(NumberFormatException ex) {
      throw badRequest(name+" must be a valid number.");
    }
  }

  private String text(Object value,String name,int max) {
    String text=value==null?"":String.valueOf(value).trim();
    if(text.isEmpty()||text.length()>max)throw badRequest(name+" is required and must be "+max+" characters or fewer.");
    return text;
  }

  private String optionalText(Object value,int max) {
    String text=value==null?"":String.valueOf(value).trim();
    if(text.length()>max)throw badRequest("Topic subheading is too long.");
    return text;
  }

  private String json(Object value) {
    try{return mapper.writeValueAsString(value);}
    catch(JacksonException ex){throw new IllegalStateException("Could not save practice session data.",ex);}
  }

  private ResponseStatusException badRequest(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);
  }
}
