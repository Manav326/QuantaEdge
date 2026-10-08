package com.quantaedge.api;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {
  private final JdbcTemplate jdbc;
  private final AuthorizationService authorization;

  public AdminController(JdbcTemplate jdbc,AuthorizationService authorization){this.jdbc=jdbc;this.authorization=authorization;}

  @GetMapping("/overview")
  public Map<String,Object> overview(@RequestAttribute(value="authContext",required=false) AuthContext context){
    authorization.requireAdmin(context);
    Map<String,Object> result=new LinkedHashMap<>();
    result.put("summary",jdbc.queryForMap("""
      select
        (select count(*) from curriculum_class where active=true) as classes,
        (select count(*) from curriculum_chapter where active=true and content_status='PUBLISHED') as chapters,
        (select count(*) from lesson where active=true and status='PUBLISHED') as published_lessons,
        (select count(*) from question where active=true) as questions,
        (select count(*) from student where active=true and environment='PRODUCTION') as students
      """));
    result.put("review",jdbc.queryForList("select review_status,count(*) as count from question group by review_status order by review_status"));
    result.put("sources",jdbc.queryForList("select source_kind,count(*) as count from question group by source_kind order by source_kind"));
    result.put("assets",jdbc.queryForList("select asset_type,status,count(*) as count from content_asset group by asset_type,status order by asset_type,status"));
    return result;
  }

  @GetMapping("/lessons/{lessonId}")
  public Map<String,Object> lesson(@PathVariable long lessonId,@RequestAttribute(value="authContext",required=false) AuthContext context){
    authorization.requireAdmin(context);
    Map<String,Object> result=new LinkedHashMap<>();
    result.put("lesson",jdbc.queryForMap("""
      select l.*,ch.display_name as chapter_name,c.code as class_code,s.code as subject_code
      from lesson l join curriculum_chapter ch on ch.id=l.chapter_id
      join curriculum_subject s on s.id=ch.subject_id join curriculum_class c on c.id=s.class_id
      where l.id=?
      """,lessonId));
    result.put("blocks",jdbc.queryForList("""
      select b.id,b.sequence_no,b.block_type,b.content,b.asset_id,a.url as asset_url
      from lesson_block b left join content_asset a on a.id=b.asset_id where b.lesson_id=? order by b.sequence_no
      """,lessonId));
    result.put("questions",jdbc.queryForList("""
      select q.id,q.question_type,q.prompt,q.explanation,q.difficulty,q.marks,q.exam_format,
             q.source_kind,q.source_title,q.source_ref,q.source_year,q.board,q.topic,q.subtopic,q.skill,
             q.tags::text as tags,q.answer_payload::text as answer_payload,q.review_status
      from question q where q.lesson_id=? and q.active=true order by q.sort_order
      """,lessonId));
    return result;
  }

  @GetMapping("/questions")
  public List<Map<String,Object>> questions(@RequestParam(required=false) Long lessonId,
      @RequestAttribute(value="authContext",required=false) AuthContext context){
    authorization.requireAdmin(context);
    if(lessonId==null) return jdbc.queryForList("select id,lesson_id,question_type,prompt,review_status,source_kind,source_year,board from question where active=true order by lesson_id,sort_order");
    return jdbc.queryForList("select id,lesson_id,question_type,prompt,explanation,difficulty,marks,exam_format,source_kind,source_title,source_ref,source_year,board,topic,subtopic,skill,tags::text as tags,answer_payload::text as answer_payload,review_status from question where lesson_id=? and active=true order by sort_order",lessonId);
  }

  @PutMapping("/lessons/{lessonId}/status")
  @Transactional
  public Map<String,Object> updateLessonStatus(@PathVariable long lessonId,@RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context){
    AuthContext admin=authorization.requireAdmin(context);
    String status=String.valueOf(body.getOrDefault("status","DRAFT"));
    if(!Set.of("DRAFT","REVIEW","PUBLISHED","ARCHIVED").contains(status)) throw new IllegalArgumentException("Invalid lesson status");
    int changed=jdbc.update("update lesson set status=? where id=?",status,lessonId);
    log(admin,"LESSON_STATUS",String.valueOf(lessonId),status);
    return Map.of("updated",changed>0,"lessonId",lessonId,"status",status);
  }

  @PutMapping("/questions/{questionId}")
  @Transactional
  public Map<String,Object> updateQuestion(@PathVariable long questionId,@RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context){
    AuthContext admin=authorization.requireAdmin(context);
    String sourceKind=String.valueOf(body.getOrDefault("sourceKind","AUTHOR_CREATED"));
    Integer sourceYear=body.get("sourceYear")==null?null:Integer.valueOf(String.valueOf(body.get("sourceYear")));
    String sourceRef=body.get("sourceRef")==null?null:String.valueOf(body.get("sourceRef"));
    if(!"AUTHOR_CREATED".equals(sourceKind) && (sourceRef==null || sourceRef.isBlank() || sourceYear==null))
      throw new IllegalArgumentException("Source-backed questions require sourceRef and sourceYear");
    int changed=jdbc.update("""
      update question set prompt=?,explanation=?,difficulty=?,marks=?,exam_format=?,source_kind=?,
        source_title=?,source_ref=?,source_year=?,board=?,topic=?,subtopic=?,skill=?,review_status=?
      where id=?
      """,
      body.get("prompt"),body.get("explanation"),String.valueOf(body.getOrDefault("difficulty","CORE")),
      body.get("marks")==null?null:Integer.valueOf(String.valueOf(body.get("marks"))),
      body.get("examFormat"),sourceKind,body.get("sourceTitle"),sourceRef,sourceYear,body.get("board"),
      body.get("topic"),body.get("subtopic"),body.get("skill"),
      String.valueOf(body.getOrDefault("reviewStatus","REVIEW")),questionId);
    log(admin,"QUESTION_UPDATE",String.valueOf(questionId),sourceKind+"/"+sourceRef);
    return Map.of("updated",changed>0,"questionId",questionId);
  }

  @PostMapping("/questions/import")
  @Transactional
  public Map<String,Object> importQuestions(@RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context){
    AuthContext admin=authorization.requireAdmin(context);
    long lessonId=Long.parseLong(String.valueOf(body.get("lessonId")));
    String sourceKind=String.valueOf(body.getOrDefault("sourceKind","AUTHOR_CREATED"));
    String sourceTitle=body.get("sourceTitle")==null?null:String.valueOf(body.get("sourceTitle"));
    String sourceRef=body.get("sourceRef")==null?null:String.valueOf(body.get("sourceRef"));
    Integer sourceYear=body.get("sourceYear")==null?null:Integer.valueOf(String.valueOf(body.get("sourceYear")));
    String board=body.get("board")==null?null:String.valueOf(body.get("board"));
    if(!"AUTHOR_CREATED".equals(sourceKind) && (sourceRef==null||sourceRef.isBlank()||sourceYear==null||board==null||board.isBlank()))
      throw new IllegalArgumentException("Source-backed imports require sourceRef, sourceYear and board");

    List<Map<String,Object>> items=(List<Map<String,Object>>)body.getOrDefault("questions",List.of());
    int imported=0;
    for(Map<String,Object> item:items){
      Long qid=jdbc.queryForObject("""
        insert into question(lesson_id,question_type,prompt,explanation,difficulty,sort_order,source_kind,source_title,source_ref,source_year,board,marks,exam_format,topic,subtopic,skill,tags,review_status)
        values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'DRAFT') returning id
        """,Long.class,lessonId,String.valueOf(item.getOrDefault("questionType","SHORT_ANSWER")),
        item.get("prompt"),item.get("explanation"),String.valueOf(item.getOrDefault("difficulty","CORE")),
        Integer.valueOf(String.valueOf(item.getOrDefault("sortOrder",imported+1))),sourceKind,sourceTitle,sourceRef,sourceYear,board,
        item.get("marks")==null?null:Integer.valueOf(String.valueOf(item.get("marks"))),item.get("examFormat"),
        item.get("topic"),item.get("subtopic"),item.get("skill"),item.getOrDefault("tags","[]"));
      Object options=item.get("options");
      if(options instanceof List<?> list){
        int ord=1; for(Object opt:list){
          if(opt instanceof Map<?,?> om){
            jdbc.update("insert into question_option(question_id,option_key,label,is_correct,sort_order) values (?,?,?,?,?)",
                qid,String.valueOf(om.get("key")),String.valueOf(om.get("label")),Boolean.parseBoolean(String.valueOf(om.containsKey("correct") ? om.get("correct") : Boolean.FALSE)),ord++);
          }
        }
      }
      imported++;
    }
    log(admin,"QUESTION_IMPORT",String.valueOf(lessonId),"count="+imported+"/"+sourceKind);
    return Map.of("imported",imported,"lessonId",lessonId);
  }

  @PostMapping("/assets")
  public Map<String,Object> createAsset(@RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context){
    AuthContext admin=authorization.requireAdmin(context);
    Long id=jdbc.queryForObject("""
      insert into content_asset(asset_type,title,alt_text,url,source_kind,source_title,source_ref,license_note,status)
      values (?,?,?,?,?,?,?,?,?)
      returning id
      """,Long.class,String.valueOf(body.getOrDefault("assetType","IMAGE")),body.get("title"),body.get("altText"),
      body.get("url"),String.valueOf(body.getOrDefault("sourceKind","AUTHOR_CREATED")),body.get("sourceTitle"),
      body.get("sourceRef"),body.get("licenseNote"),String.valueOf(body.getOrDefault("status","DRAFT")));
    log(admin,"ASSET_CREATE",String.valueOf(id),String.valueOf(body.get("title")));
    return Map.of("id",id);
  }

  @PutMapping("/assets/{assetId}/status")
  public Map<String,Object> assetStatus(@PathVariable long assetId,@RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context){
    AuthContext admin=authorization.requireAdmin(context);
    String status=String.valueOf(body.getOrDefault("status","REVIEW"));
    int changed=jdbc.update("update content_asset set status=?,updated_at=now() where id=?",status,assetId);
    log(admin,"ASSET_STATUS",String.valueOf(assetId),status);
    return Map.of("updated",changed>0,"status",status);
  }

  private void log(AuthContext admin,String action,String resource,String detail){
    if(admin.userId()!=null) jdbc.update("insert into admin_action_log(admin_user_id,action,resource,detail) values (?,?,?,?)",admin.userId(),action,resource,detail);
  }
}
