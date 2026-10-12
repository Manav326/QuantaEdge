package com.quantaedge.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Materializes durable topic-end and chapter-end assessments from reviewed content. */
@Service
public class AssessmentCatalogService {
  private static final int TOPIC_MINIMUM=3, TOPIC_MAXIMUM=10, CHAPTER_MINIMUM=5, CHAPTER_MAXIMUM=20;
  private final JdbcTemplate jdbc;
  private final QuestionHistoryService questionHistory;
  public AssessmentCatalogService(JdbcTemplate jdbc,QuestionHistoryService questionHistory) {
    this.jdbc=jdbc; this.questionHistory=questionHistory;
  }

  @Transactional
  public void ensureGeneratedAssessments(long studentId) {
    String classCode=jdbc.queryForObject(
        "select class_code from student where id=? and active=true",String.class,studentId);
    ensureGeneratedAssessmentsForClass(classCode);
  }

  /**
   * Refresh just the topic and chapter affected by a lesson publication, revision, or question
   * review. Normal student reads stay read-only; catalogue generation is driven by content events.
   */
  @Transactional
  public void refreshGeneratedAssessmentsForLesson(long lessonId) {
    List<Map<String,Object>> rows=jdbc.queryForList("""
      select l.id as lesson_id,l.title as lesson_title,l.status as lesson_status,l.active as lesson_active,
             l.chapter_id,ch.display_name as chapter_name,ch.content_status as chapter_status,
             ch.active as chapter_active,s.id as subject_id,s.display_name as subject_name,
             s.active as subject_active,c.active as class_active
      from lesson l
      join curriculum_chapter ch on ch.id=l.chapter_id
      join curriculum_subject s on s.id=ch.subject_id
      join curriculum_class c on c.id=s.class_id
      where l.id=?
      """,lessonId);
    if(rows.isEmpty()) return;
    Map<String,Object> row=rows.getFirst();
    long chapterId=n(row.get("chapter_id"));
    long subjectId=n(row.get("subject_id"));
    String title=t(row.get("lesson_title"));
    String chapter=t(row.get("chapter_name"));
    String subject=t(row.get("subject_name"));
    boolean lessonEligible=Boolean.TRUE.equals(row.get("lesson_active"))
        && "PUBLISHED".equalsIgnoreCase(t(row.get("lesson_status")))
        && Boolean.TRUE.equals(row.get("chapter_active"))
        && "PUBLISHED".equalsIgnoreCase(t(row.get("chapter_status")))
        && Boolean.TRUE.equals(row.get("subject_active"))
        && Boolean.TRUE.equals(row.get("class_active"));

    if(lessonEligible) {
      List<Long> topicIds=topicQuestionIds(lessonId);
      if(topicIds.size()<TOPIC_MINIMUM) {
        archiveCurrent("TOPIC_END","lesson_id",lessonId);
      } else {
        materialize("TOPIC_END",subjectId,chapterId,lessonId,"Topic-end test · "+title,
            "A saved end-of-topic assessment for "+title+".",10,3,topicIds,fingerprint(title,topicIds),
            "Topic-end test · "+title+" · "+chapter+" · "+subject);
      }
    } else {
      archiveCurrent("TOPIC_END","lesson_id",lessonId);
    }

    if(Boolean.TRUE.equals(row.get("chapter_active"))
        && "PUBLISHED".equalsIgnoreCase(t(row.get("chapter_status")))
        && Boolean.TRUE.equals(row.get("subject_active"))
        && Boolean.TRUE.equals(row.get("class_active"))) {
      List<Long> chapterIds=chapterQuestionIds(chapterId);
      if(chapterIds.size()<CHAPTER_MINIMUM) {
        archiveCurrent("CHAPTER_END","chapter_id",chapterId);
      } else {
        materialize("CHAPTER_END",subjectId,chapterId,null,"Chapter-end test · "+chapter,
            "A cumulative end-of-chapter assessment for "+chapter+".",30,2,chapterIds,
            fingerprint(chapter,chapterIds),"Chapter-end test · "+chapter+" · "+subject);
      }
    } else {
      archiveCurrent("CHAPTER_END","chapter_id",chapterId);
    }
  }

  @Transactional
  public void ensureGeneratedAssessmentsForClass(String classCode) {
    String normalized=classCode==null?"":classCode.trim();
    if(normalized.isEmpty()) return;
    List<Map<String,Object>> topics=jdbc.queryForList("""
      select l.id as lesson_id,l.title as lesson_title,l.chapter_id,ch.display_name as chapter_name,
             s.id as subject_id,s.display_name as subject_name
      from curriculum_class c
      join curriculum_subject s on s.class_id=c.id and s.active=true
      join curriculum_chapter ch on ch.subject_id=s.id and ch.active=true and ch.content_status='PUBLISHED'
      join lesson l on l.chapter_id=ch.id and l.active=true and l.status='PUBLISHED'
      where c.code=? and c.active=true
      order by s.sort_order,coalesce(ch.teaching_order,ch.sort_order),l.sort_order,l.id
      """,normalized);
    for(Map<String,Object> topic:topics) {
      long lessonId=n(topic.get("lesson_id")),chapterId=n(topic.get("chapter_id")),subjectId=n(topic.get("subject_id"));
      String title=t(topic.get("lesson_title")),chapter=t(topic.get("chapter_name")),subject=t(topic.get("subject_name"));
      List<Long> ids=topicQuestionIds(lessonId);
      if(ids.size()<TOPIC_MINIMUM){archiveCurrent("TOPIC_END","lesson_id",lessonId);continue;}
      materialize("TOPIC_END",subjectId,chapterId,lessonId,"Topic-end test · "+title,
          "A saved end-of-topic assessment for "+title+".",10,3,ids,fingerprint(title,ids),
          "Topic-end test · "+title+" · "+chapter+" · "+subject);
    }
    Map<Long,Map<String,Object>> chapters=new LinkedHashMap<>();
    for(Map<String,Object> topic:topics) chapters.putIfAbsent(n(topic.get("chapter_id")),topic);
    for(Map.Entry<Long,Map<String,Object>> entry:chapters.entrySet()) {
      long chapterId=entry.getKey();Map<String,Object> row=entry.getValue();
      String name=t(row.get("chapter_name")),subject=t(row.get("subject_name"));
      List<Long> ids=chapterQuestionIds(chapterId);
      if(ids.size()<CHAPTER_MINIMUM){archiveCurrent("CHAPTER_END","chapter_id",chapterId);continue;}
      materialize("CHAPTER_END",n(row.get("subject_id")),chapterId,null,"Chapter-end test · "+name,
          "A cumulative end-of-chapter assessment for "+name+".",30,2,ids,fingerprint(name,ids),
          "Chapter-end test · "+name+" · "+subject);
    }
  }

  private List<Long> topicQuestionIds(long lessonId) {
    return jdbc.queryForList("""
      select q.id from question q
      where q.lesson_id=? and q.active=true and q.review_status in ('APPROVED','PUBLISHED')
      order by case q.question_type when 'MCQ' then 1 when 'TRUE_FALSE' then 2
        when 'INPUT' then 3 when 'NUMERICAL' then 4 else 5 end,q.sort_order,q.id limit ?
      """,Long.class,lessonId,TOPIC_MAXIMUM);
  }

  private List<Long> chapterQuestionIds(long chapterId) {
    return jdbc.queryForList("""
      select eligible.id from (
        select q.id,q.sort_order,l.sort_order as lesson_order,
               coalesce(ch.teaching_order,ch.sort_order) as chapter_order,
               row_number() over(partition by l.id order by q.sort_order,q.id) as within_topic
        from question q
        join lesson l on l.id=q.lesson_id and l.active=true and l.status='PUBLISHED'
        join curriculum_chapter ch on ch.id=l.chapter_id and ch.active=true and ch.content_status='PUBLISHED'
        where ch.id=? and q.active=true and q.review_status in ('APPROVED','PUBLISHED')
      ) eligible
      order by eligible.within_topic,eligible.chapter_order,eligible.lesson_order,eligible.sort_order,eligible.id
      limit ?
      """,Long.class,chapterId,CHAPTER_MAXIMUM);
  }

  private void materialize(String type,long subjectId,long chapterId,Long lessonId,String title,
      String description,int duration,int maxAttempts,List<Long> ids,String fingerprint,String note) {
    String column="TOPIC_END".equals(type)?"lesson_id":"chapter_id";
    long scope=lessonId==null?chapterId:lessonId;
    // Lock the scope row even when no assessment currently exists, avoiding concurrent
    // requests creating duplicate current versions for the same topic or chapter.
    String scopeTable="TOPIC_END".equals(type)?"lesson":"curriculum_chapter";
    List<Map<String,Object>> lockedScope=jdbc.queryForList(
        "select id from "+scopeTable+" where id=? for update",scope);
    if(lockedScope.isEmpty()) return;
    List<Map<String,Object>> current=jdbc.queryForList(
        "select id,generation_version,content_fingerprint from assessment where assessment_type=? and is_generated=true and is_current=true and "+column+"=? for update",
        type,scope);
    if(!current.isEmpty()) {
      Map<String,Object> active=current.getFirst();long id=n(active.get("id"));
      boolean sameFingerprint=fingerprint.equals(t(active.get("content_fingerprint")));
      String currentStatus=t(active.get("status"));
      if(sameFingerprint&&"PUBLISHED".equals(currentStatus)) return;
      if("ARCHIVED".equals(currentStatus)) {
        // Respect an explicit archive. A later regeneration will create a new version.
        jdbc.update("update assessment set is_current=false where id=?",id);
      } else {
        Integer attempts=jdbc.queryForObject("select count(*) from student_assessment_attempt where assessment_id=?",Integer.class,id);
        int version=((Number)active.get("generation_version")).intValue();
        if(attempts!=null&&attempts>0) {
          jdbc.update("update assessment set is_current=false,status='ARCHIVED',updated_at=now() where id=?",id);
          audit(id,"PUBLISHED","ARCHIVED","Superseded content; historical attempts remain unchanged.");
          insert(type,subjectId,chapterId,lessonId,title,description,duration,maxAttempts,version+1,fingerprint,ids,note);
        } else {
          jdbc.update("delete from assessment_question where assessment_id=?",id);
          jdbc.update("""
            update assessment set title=?,description=?,duration_minutes=?,max_attempts=?,generation_version=?,
              content_fingerprint=?,status='PUBLISHED',is_current=true,published_at=now(),updated_at=now()
            where id=?
            """,title,description,duration,maxAttempts,version+1,fingerprint,id);
          insertSnapshots(id,ids);audit(id,"PUBLISHED","PUBLISHED","Refreshed before the first attempt.");
        }
        return;
      }
    }
    Integer latest=jdbc.queryForObject(
        "select coalesce(max(generation_version),0) from assessment where assessment_type=? and is_generated=true and "+column+"=?",
        Integer.class,type,scope);
    insert(type,subjectId,chapterId,lessonId,title,description,duration,maxAttempts,(latest==null?0:latest)+1,fingerprint,ids,note);
  }

  private void insert(String type,long subjectId,long chapterId,Long lessonId,String title,String description,
      int duration,int maxAttempts,int version,String fingerprint,List<Long> ids,String note) {
    Long id=jdbc.queryForObject("""
      insert into assessment(title,description,subject_id,chapter_id,lesson_id,duration_minutes,max_attempts,status,
        assessment_type,is_generated,is_current,generation_version,content_fingerprint,published_at)
      values(?,?,?,?,?,?,?,'PUBLISHED',?,true,true,?,?,now()) returning id
      """,Long.class,title,description,subjectId,chapterId,lessonId,duration,maxAttempts,type,version,fingerprint);
    if(id==null)throw new IllegalStateException("Generated assessment was not persisted.");
    insertSnapshots(id,ids);audit(id,null,"PUBLISHED",note);
  }

  private void insertSnapshots(long assessmentId,List<Long> ids) {
    int seq=1;
    for(Long qid:ids) {
      Map<String,Object> snapshot=questionHistory.snapshot(qid);if(snapshot.isEmpty())continue;
      Object raw=snapshot.get("marks");String marksText=raw instanceof Number?String.valueOf(raw):"1";
      java.math.BigDecimal marks=new java.math.BigDecimal(marksText);
      if(marks.signum()<0||marks.compareTo(new java.math.BigDecimal("1000"))>0)
        throw new IllegalArgumentException("Question marks must be between 0 and 1,000.");
      jdbc.update("insert into assessment_question(assessment_id,source_question_id,sequence_no,max_marks,question_snapshot) values(?,?,?,?,?::jsonb)",
          assessmentId,qid,seq++,marks,questionHistory.json(snapshot));
    }
    if(seq==1)throw new IllegalStateException("Generated assessment contains no question snapshots.");
  }

  private void archiveCurrent(String type,String column,long scope) {
    List<Map<String,Object>> rows=jdbc.queryForList(
        "select id from assessment where assessment_type=? and is_generated=true and is_current=true and "+column+"=?",type,scope);
    for(Map<String,Object> row:rows) {
      long id=n(row.get("id"));jdbc.update("update assessment set is_current=false,status='ARCHIVED',updated_at=now() where id=?",id);
      audit(id,"PUBLISHED","ARCHIVED","Not enough approved questions remain to offer this generated assessment.");
    }
  }
  private void audit(long id,String oldStatus,String newStatus,String note) {
    String text=t(note);if(text.length()>1200)text=text.substring(0,1200);
    jdbc.update("insert into assessment_audit_log(assessment_id,actor_role,action,previous_status,new_status,note) values(?,'SYSTEM','SYSTEM_GENERATED',?,?,?)",
        id,oldStatus,newStatus,text);
  }
  private String fingerprint(String title,List<Long> ids) {
    List<String> rows=new ArrayList<>();rows.add(title);
    for(Long id:ids)rows.add(questionHistory.json(questionHistory.snapshot(id)));
    try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(String.join("\n",rows).getBytes(StandardCharsets.UTF_8)));}
    catch(NoSuchAlgorithmException e){throw new IllegalStateException("SHA-256 is unavailable.",e);}
  }
  private long n(Object o){return o instanceof Number x?x.longValue():Long.parseLong(String.valueOf(o));}
  private String t(Object o){return o==null?"":String.valueOf(o).trim();}
}
