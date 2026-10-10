package com.quantaedge.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
  private static final Set<String> QUESTION_TYPES=Set.of(
      "MCQ","TRUE_FALSE","INPUT","NUMERICAL","MATCH","ORDER","ASSERTION_REASON",
      "CASE_BASED","SHORT_ANSWER","LONG_ANSWER","DIAGRAM","MAP","SOURCE_BASED");

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

  @PostMapping("/sessions")
  @Transactional
  public Map<String,Object> createSession(@RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    context=authorization.requireStudent(context);
    int requestedCount=integer(body.getOrDefault("questionCount",10),"questionCount",1,100);
    List<Map<String,Object>> topics=topicSelections(body.get("topicSelections"));
    if(topics.isEmpty()) throw badRequest("Select at least one topic before starting practice.");
    List<String> types=questionTypes(body.get("questionTypes"));
    String typePlaceholders=String.join(",",java.util.Collections.nCopies(types.size(),"?"));
    StringBuilder topicFilter=new StringBuilder();
    List<Object> args=new ArrayList<>();
    args.add(context.studentId());
    args.addAll(types);
    for(Map<String,Object> topic:topics){
      if(topicFilter.length()>0)topicFilter.append(" or ");
      topicFilter.append("(ch.id=? and coalesce(nullif(btrim(q.topic),''),ch.display_name)=? and coalesce(nullif(btrim(q.subtopic),''),'')=?)");
      args.add(topic.get("chapterId"));
      args.add(topic.get("topic"));
      args.add(topic.get("subtopic"));
    }
    args.add(requestedCount);
    String sql="""
      select q.id
      from question q
      join lesson l on l.id=q.lesson_id and l.active=true and l.status='PUBLISHED'
      join curriculum_chapter ch on ch.id=l.chapter_id and ch.active=true and ch.content_status='PUBLISHED'
      join curriculum_subject s on s.id=ch.subject_id and s.active=true
      join curriculum_class c on c.id=s.class_id and c.active=true
      join student st on st.id=? and st.active=true and st.class_code=c.code
      where q.active=true and q.review_status in ('APPROVED','PUBLISHED')
        and q.question_type in (__TYPE_PLACEHOLDERS__)
        and exists(select 1 from student_track_enrollment ste
                   where ste.student_id=st.id and ste.subject_id=s.id and ste.status='ACTIVE')
        and (__TOPIC_FILTER__)
      order by random()
      limit ?
      """.replace("__TYPE_PLACEHOLDERS__",typePlaceholders)
         .replace("__TOPIC_FILTER__",topicFilter.toString());
    List<Long> selectedIds=jdbc.queryForList(sql,Long.class,args.toArray());
    if(selectedIds.isEmpty()) {
      throw badRequest("No approved questions match the selected topics and question types. Choose additional topics or question types.");
    }

    Long sessionId=jdbc.queryForObject("""
      insert into student_practice_session(
        student_id,status,requested_question_count,selected_question_count,question_types,topic_selections
      ) values(?,'IN_PROGRESS',?,?,?::jsonb,?::jsonb) returning id
      """,Long.class,context.studentId(),requestedCount,selectedIds.size(),json(types),json(topics));
    int sequence=1;
    List<Map<String,Object>> questions=new ArrayList<>();
    for(Long questionId:selectedIds) {
      Map<String,Object> snapshot=questionHistory.snapshot(questionId);
      if(snapshot.isEmpty()) continue;
      jdbc.update("""
        insert into student_practice_session_question(practice_session_id,question_id,sequence_no,question_snapshot)
        values(?,?,?,?::jsonb)
        """,sessionId,questionId,sequence++,json(snapshot));
      questions.add(toPublicQuestion(snapshot));
    }
    if(questions.isEmpty())throw badRequest("The selected questions are no longer available. Refresh the topic catalogue and try again.");
    jdbc.update("update student_practice_session set selected_question_count=? where id=?",questions.size(),sessionId);
    Map<String,Object> result=new LinkedHashMap<>();
    result.put("sessionId",sessionId);
    result.put("status","IN_PROGRESS");
    result.put("requestedCount",requestedCount);
    result.put("selectedCount",questions.size());
    result.put("message",questions.size()<requestedCount
        ?"Only "+questions.size()+" eligible question(s) matched; this session uses the available questions."
        :"Practice set created.");
    result.put("questions",questions);
    return result;
  }

  @GetMapping("/sessions/{sessionId}")
  public Map<String,Object> getSession(@PathVariable long sessionId,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    context=authorization.requireStudent(context);
    List<Map<String,Object>> sessions=jdbc.queryForList("""
      select id,status,requested_question_count,selected_question_count,created_at,completed_at
      from student_practice_session where id=? and student_id=?
      """,sessionId,context.studentId());
    if(sessions.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Practice session not found.");
    List<Map<String,Object>> rows=jdbc.queryForList("""
      select psq.question_id,psq.sequence_no,psq.answered_at,psq.question_snapshot::text as question_snapshot
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
        Map<String,Object> question=toPublicQuestion(snapshot);
        question.put("answered",row.get("answered_at")!=null);
        questions.add(question);
      } catch(JacksonException ex) {
        throw new IllegalStateException("Stored practice question could not be read.",ex);
      }
    }
    Map<String,Object> result=new LinkedHashMap<>(sessions.getFirst());
    result.put("sessionId",sessionId);
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
    String type=String.valueOf(snapshot.get("question_type"));
    result.put("response_mode",Set.of("INPUT","NUMERICAL","SHORT_ANSWER","LONG_ANSWER","MATCH","ORDER",
        "ASSERTION_REASON","CASE_BASED","DIAGRAM","MAP","SOURCE_BASED").contains(type)?"text":"choice");
    return result;
  }

  private void copy(Map<String,Object> source,Map<String,Object> target,String... keys) {
    for(String key:keys)if(source.containsKey(key))target.put(key,source.get(key));
  }

  private List<Map<String,Object>> topicSelections(Object value) {
    if(!(value instanceof List<?> values))throw badRequest("Topic selections must be a list.");
    if(values.size()>100)throw badRequest("Select no more than 100 topics in one practice session.");
    List<Map<String,Object>> result=new ArrayList<>();
    Set<String> unique=new LinkedHashSet<>();
    for(Object item:values) {
      if(!(item instanceof Map<?,?> map))throw badRequest("Each topic selection must include its chapter and topic.");
      int chapterId=integer(map.get("chapterId"),"chapterId",1,Integer.MAX_VALUE);
      String topic=text(map.get("topic"),"topic",200);
      String subtopic=optionalText(map.get("subtopic"),200);
      String key=chapterId+"\u0000"+topic+"\u0000"+subtopic;
      if(unique.add(key)) {
        Map<String,Object> selection=new LinkedHashMap<>();
        selection.put("chapterId",chapterId);selection.put("topic",topic);selection.put("subtopic",subtopic);
        result.add(selection);
      }
    }
    return result;
  }

  private List<String> questionTypes(Object value) {
    if(!(value instanceof List<?> values)||values.isEmpty())
      throw badRequest("Choose at least one question type.");
    if(values.size()>QUESTION_TYPES.size())throw badRequest("Too many question types were selected.");
    Set<String> result=new LinkedHashSet<>();
    for(Object item:values) {
      String type=text(item,"question type",30).toUpperCase();
      if(!QUESTION_TYPES.contains(type))throw badRequest("Unsupported question type: "+type);
      result.add(type);
    }
    return new ArrayList<>(result);
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
