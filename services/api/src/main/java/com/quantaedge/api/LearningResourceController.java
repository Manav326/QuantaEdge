package com.quantaedge.api;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/** Provider-neutral resource library. Existing source bytes are referenced, never copied. */
@RestController
@RequestMapping("/api/v1")
public class LearningResourceController {
  private static final Set<String> RESOURCE_TYPES=Set.of("VIDEO","PDF","WORKSHEET","SLIDES","AUDIO","IMAGE","EXTERNAL_LINK","OTHER");
  private static final Set<String> SOURCE_KINDS=Set.of("RECORDED_CLASS","LEARNING_DOCUMENT","CONTENT_ASSET","PRIVATE_UPLOAD","EXTERNAL_URL");
  private static final Set<String> RESOURCE_STATUSES=Set.of("DRAFT","REVIEW","PUBLISHED","ARCHIVED");
  private static final Set<String> ASSIGNMENT_STATUSES=Set.of("DRAFT","PUBLISHED","ARCHIVED");
  private static final Set<String> TARGET_KINDS=Set.of("CHAPTER","LESSON","STRUCTURED_ARTIFACT");

  private final JdbcTemplate jdbc;
  private final AuthorizationService authorization;
  private final StaffAuditService staffAudit;
  private final PrivateLearningResourceStorageService resourceStorage;
  private final PrivateMediaStorageService recordingStorage;
  private final StructuredLessonArtifactService artifacts;

  public LearningResourceController(JdbcTemplate jdbc,AuthorizationService authorization,
      StaffAuditService staffAudit,PrivateLearningResourceStorageService resourceStorage,
      PrivateMediaStorageService recordingStorage,StructuredLessonArtifactService artifacts) {
    this.jdbc=jdbc;this.authorization=authorization;this.staffAudit=staffAudit;
    this.resourceStorage=resourceStorage;this.recordingStorage=recordingStorage;this.artifacts=artifacts;
  }

  @GetMapping("/admin/learning-resources")
  public Map<String,Object> adminResources(
      @RequestParam(required=false) String status,@RequestParam(required=false) String resourceType,
      @RequestParam(defaultValue="50") Integer limit,@RequestParam(defaultValue="0") Integer offset,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    requireContentView(context);
    String statusFilter=normalizeFilter(status,RESOURCE_STATUSES),typeFilter=normalizeFilter(resourceType,RESOURCE_TYPES);
    int rowLimit=Math.max(1,Math.min(limit==null?50:limit,100)),rowOffset=Math.max(0,Math.min(offset==null?0:offset,10_000_000));
    List<Map<String,Object>> items=jdbc.queryForList("""
      select r.id as resource_id,r.resource_type,r.title,r.description,r.language,r.source_kind,
        r.recorded_class_id,r.learning_document_id,r.content_asset_id,r.external_url,
        r.media_type,r.file_size_bytes,r.sha256,r.duration_seconds,r.source_reference,
        r.download_allowed,r.status,r.version_no,r.supersedes_resource_id,r.created_at,r.updated_at,r.published_at,
        coalesce(rc.title,ld.title,ca.title,r.title) as source_title,
        (select count(*) from learning_resource_assignment a where a.resource_id=r.id and a.status<>'ARCHIVED') as assignment_count
      from learning_resource r left join recorded_class rc on rc.id=r.recorded_class_id
      left join learning_document ld on ld.id=r.learning_document_id left join content_asset ca on ca.id=r.content_asset_id
      where (?='' or r.status=?) and (?='' or r.resource_type=?)
      order by r.created_at desc,r.id desc limit ? offset ?
      """,statusFilter,statusFilter,typeFilter,typeFilter,rowLimit,rowOffset);
    Long total=jdbc.queryForObject("select count(*) from learning_resource r where (?='' or r.status=?) and (?='' or r.resource_type=?)",
        Long.class,statusFilter,statusFilter,typeFilter,typeFilter);
    return Map.of("items",items,"total",total==null?0L:total,"limit",rowLimit,"offset",rowOffset);
  }

  @PostMapping("/admin/learning-resources")
  @Transactional
  public Map<String,Object> createResource(@RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    context=authorization.requirePermission(context,"CONTENT_EDIT");
    String title=requiredText(body.get("title"),"Resource title",240);
    String description=optionalText(body.get("description"),1600);
    String resourceType=requiredEnum(body.get("resourceType"),"Resource type",RESOURCE_TYPES);
    String sourceKind=requiredEnum(body.get("sourceKind"),"Source kind",SOURCE_KINDS);
    if("PRIVATE_UPLOAD".equals(sourceKind))throw badRequest("Private uploads must use the multipart upload endpoint.");
    String language=normalizeLanguage(body.getOrDefault("language","mul"));
    boolean downloadAllowed=booleanValue(body.get("downloadAllowed"),false);
    if(downloadAllowed)context=authorization.requirePermission(context,"CONTENT_PUBLISH");
    if(downloadAllowed&&!"RECORDED_CLASS".equals(sourceKind))
      throw badRequest("Downloads can only be enabled for QuantaEdge recorded videos and private uploads.");

    Long recordedClassId=null,learningDocumentId=null,contentAssetId=null;
    String externalUrl=null,sourceReference=optionalText(body.get("sourceReference"),500);
    switch(sourceKind) {
      case "RECORDED_CLASS" -> {
        recordedClassId=positiveLong(body.get("recordedClassId"),"Recorded class");
        List<Map<String,Object>> rows=jdbc.queryForList("select id,title from recorded_class where id=?",recordedClassId);
        if(rows.isEmpty())throw notFound("Recorded class",recordedClassId);
        if(!"VIDEO".equals(resourceType))throw badRequest("An existing recorded class must be catalogued as VIDEO.");
        if(sourceReference==null)sourceReference="recorded_class:"+recordedClassId;
      }
      case "LEARNING_DOCUMENT" -> {
        learningDocumentId=positiveLong(body.get("learningDocumentId"),"Learning document");
        List<Map<String,Object>> rows=jdbc.queryForList("select id,title,language from learning_document where id=?",learningDocumentId);
        if(rows.isEmpty())throw notFound("Learning document",learningDocumentId);
        if(!"PDF".equals(resourceType))throw badRequest("An existing textbook document must be catalogued as PDF.");
        if(body.get("language")==null)language=normalizeLanguage(rows.getFirst().get("language"));
        if(sourceReference==null)sourceReference="learning_document:"+learningDocumentId;
      }
      case "CONTENT_ASSET" -> {
        contentAssetId=positiveLong(body.get("contentAssetId"),"Content asset");
        List<Map<String,Object>> rows=jdbc.queryForList("select id,title,asset_type from content_asset where id=?",contentAssetId);
        if(rows.isEmpty())throw notFound("Content asset",contentAssetId);
        String assetType=String.valueOf(rows.getFirst().get("asset_type")).toUpperCase(Locale.ROOT);
        String expected=switch(assetType){case "VIDEO"->"VIDEO";case "AUDIO"->"AUDIO";case "IMAGE","DIAGRAM"->"IMAGE";default->"OTHER";};
        if(!expected.equals(resourceType))throw badRequest("Resource type must match the selected content asset type.");
        if(sourceReference==null)sourceReference="content_asset:"+contentAssetId;
      }
      case "EXTERNAL_URL" -> {
        externalUrl=validExternalUrl(body.get("externalUrl"));
        if(downloadAllowed)throw badRequest("An external URL cannot grant a QuantaEdge-managed download permission.");
        if(sourceReference==null)sourceReference=externalUrl;
      }
      default -> throw badRequest("Choose a supported resource source.");
    }
    Long id=jdbc.queryForObject("""
      insert into learning_resource(resource_type,title,description,language,source_kind,recorded_class_id,
        learning_document_id,content_asset_id,external_url,source_reference,download_allowed,status,version_no,created_by_staff_id)
      values(?,?,?,?,?,?,?,?,?,?,?,'DRAFT',1,?) returning id
      """,(rs,rowNum)->rs.getLong(1),resourceType,title,description,language,sourceKind,
      recordedClassId,learningDocumentId,contentAssetId,externalUrl,sourceReference,downloadAllowed,context.staffId());
    staffAudit.recordAction(context,"/api/v1/admin/learning-resources/"+id+"/created",
        "Created resource #"+id+" ("+resourceType+", source="+sourceKind+").");
    return adminResourceById(id);
  }

  @PostMapping(value="/admin/learning-resources/upload",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
  @Transactional
  public Map<String,Object> uploadResource(
      @RequestPart("file") MultipartFile file,@RequestParam("title") String suppliedTitle,
      @RequestParam("resourceType") String suppliedType,@RequestParam(value="description",required=false) String description,
      @RequestParam(value="language",defaultValue="mul") String language,
      @RequestParam(value="downloadAllowed",defaultValue="false") boolean downloadAllowed,
      @RequestParam(value="supersedesResourceId",required=false) Long supersedesResourceId,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    context=authorization.requirePermission(context,"CONTENT_EDIT");
    if(downloadAllowed)context=authorization.requirePermission(context,"CONTENT_PUBLISH");
    String title=requiredText(suppliedTitle,"Resource title",240);
    String kind=requiredEnum(suppliedType,"Resource type",Set.of("VIDEO","PDF","WORKSHEET","SLIDES","AUDIO","IMAGE","OTHER"));
    String normalizedLanguage=normalizeLanguage(language);
    int versionNo=1;
    if(supersedesResourceId!=null) {
      if(supersedesResourceId<=0)throw badRequest("Replacement resource ID must be positive.");
      List<Map<String,Object>> old=jdbc.queryForList("select id,resource_type,version_no from learning_resource where id=?",supersedesResourceId);
      if(old.isEmpty())throw notFound("Resource to replace",supersedesResourceId);
      if(!kind.equals(String.valueOf(old.getFirst().get("resource_type"))))throw badRequest("A replacement resource must keep the same resource type.");
      versionNo=((Number)old.getFirst().get("version_no")).intValue()+1;
    }
    PrivateLearningResourceStorageService.StoredResource stored=resourceStorage.store(file,kind);
    try {
      Long id=jdbc.queryForObject("""
        insert into learning_resource(resource_type,title,description,language,source_kind,storage_key,original_filename,
          media_type,file_size_bytes,sha256,download_allowed,status,version_no,supersedes_resource_id,created_by_staff_id)
        values(?,?,?,?, 'PRIVATE_UPLOAD',?,?,?,?,?,?,'DRAFT',?,?,?) returning id
        """,(rs,rowNum)->rs.getLong(1),kind,title,optionalText(description,1600),normalizedLanguage,
        stored.storageKey(),stored.originalFilename(),stored.mediaType(),stored.sizeBytes(),stored.sha256(),
        downloadAllowed,versionNo,supersedesResourceId,context.staffId());
      staffAudit.recordAction(context,"/api/v1/admin/learning-resources/"+id+"/uploaded",
          "Uploaded private resource #"+id+" ("+kind+", "+stored.sizeBytes()+" bytes, SHA-256 "+stored.sha256()+").");
      return adminResourceById(id);
    } catch(RuntimeException ex) {
      resourceStorage.deleteQuietly(resourceStorage.resolve(stored.storageKey()));throw ex;
    }
  }

  @PatchMapping("/admin/learning-resources/{resourceId}/status")
  @Transactional
  public Map<String,Object> setResourceStatus(@PathVariable long resourceId,@RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    context=authorization.requireAuth(context);
    String next=requiredEnum(body.get("status"),"Resource status",RESOURCE_STATUSES);
    if(Set.of("PUBLISHED","ARCHIVED").contains(next))authorization.requirePermission(context,"CONTENT_PUBLISH");
    else authorization.requirePermission(context,"CONTENT_EDIT");
    Map<String,Object> row=resourceRow(resourceId);
    String previous=String.valueOf(row.get("status"));
    if("PUBLISHED".equals(next))requireResourceReady(row);
    jdbc.update("""
      update learning_resource set status=?,updated_at=now(),
        published_by_staff_id=case when ?='PUBLISHED' then ? when ?='DRAFT' then null else published_by_staff_id end,
        published_at=case when ?='PUBLISHED' then now() when ?='DRAFT' then null else published_at end
      where id=?
      """,next,next,context.staffId(),next,next,next,resourceId);
    staffAudit.recordAction(context,"/api/v1/admin/learning-resources/"+resourceId+"/status","Changed resource status from "+previous+" to "+next+".");
    return adminResourceById(resourceId);
  }

  @PutMapping("/admin/learning-resources/{resourceId}/download-policy")
  @Transactional
  public Map<String,Object> setDownloadPolicy(@PathVariable long resourceId,@RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    context=authorization.requireAuth(context);
    boolean allow=booleanValue(body.get("downloadAllowed"),false);
    if(allow)authorization.requirePermission(context,"CONTENT_PUBLISH");
    else authorization.requirePermission(context,"CONTENT_EDIT");
    Map<String,Object> row=resourceRow(resourceId);
    String sourceKind=String.valueOf(row.get("source_kind"));
    if(allow&&!Set.of("PRIVATE_UPLOAD","RECORDED_CLASS").contains(sourceKind))
      throw badRequest("Only private uploads and QuantaEdge recorded videos can allow managed downloads.");
    jdbc.update("update learning_resource set download_allowed=?,updated_at=now() where id=?",allow,resourceId);
    staffAudit.recordAction(context,"/api/v1/admin/learning-resources/"+resourceId+"/download-policy",
        "Set managed download permission to "+allow+" for resource #"+resourceId+".");
    return adminResourceById(resourceId);
  }

  @GetMapping("/admin/learning-resource-targets")
  public Map<String,Object> resourceTargets(@RequestParam(required=false) String classCode,
      @RequestParam(required=false) String subjectCode,@RequestAttribute(value="authContext",required=false) AuthContext context) {
    requireContentView(context);
    String cc=optionalText(classCode,30),sc=optionalText(subjectCode,40);
    List<Map<String,Object>> chapters=jdbc.queryForList("""
      select ch.id as chapter_id,ch.code as chapter_code,ch.display_name as chapter_name,ch.textbook_chapter_no,
        ch.teaching_order,ch.sort_order,ch.active,ch.content_status,c.code as class_code,c.display_name as class_name,
        s.code as subject_code,s.display_name as subject_name
      from curriculum_chapter ch join curriculum_subject s on s.id=ch.subject_id
      join curriculum_class c on c.id=s.class_id
      where ch.active=true and c.active=true and s.active=true
        and (?='' or c.code=?) and (?='' or s.code=?)
      order by c.sort_order,s.sort_order,coalesce(ch.teaching_order,ch.sort_order),ch.id
      """,cc==null?"":cc,cc==null?"":cc,sc==null?"":sc,sc==null?"":sc);
    List<Map<String,Object>> lessons=jdbc.queryForList("""
      select l.id as lesson_id,l.code as lesson_code,l.title as lesson_title,l.status as lesson_status,
        l.chapter_id,ch.code as chapter_code,ch.display_name as chapter_name,c.code as class_code,s.code as subject_code
      from lesson l join curriculum_chapter ch on ch.id=l.chapter_id
      join curriculum_subject s on s.id=ch.subject_id join curriculum_class c on c.id=s.class_id
      where l.active=true and ch.active=true and s.active=true and c.active=true
        and (?='' or c.code=?) and (?='' or s.code=?)
      order by c.sort_order,s.sort_order,coalesce(ch.teaching_order,ch.sort_order),l.sort_order,l.id
      """,cc==null?"":cc,cc==null?"":cc,sc==null?"":sc,sc==null?"":sc);
    List<Map<String,Object>> artifactItems=new ArrayList<>();
    for(StructuredLessonArtifactService.PublishedArtifact artifact:artifacts.listPublishedArtifacts()) {
      int classNumber=artifacts.classNumber(artifact);String subject=artifacts.subjectCode(artifact);
      if(cc!=null&&!cc.isBlank()&&!String.valueOf(classNumber).equals(cc))continue;
      if(sc!=null&&!sc.isBlank()&&!subject.equalsIgnoreCase(sc))continue;
      Map<String,Object> item=new LinkedHashMap<>();
      item.put("artifact_id",artifact.artifactId());item.put("class_code",String.valueOf(classNumber));item.put("subject_code",subject);
      item.put("chapter_number",artifact.source().path("chapter").path("chapter_number").asInt(0));
      item.put("chapter_title",localizedTitle(artifact.source().path("chapter").path("title_by_locale")));
      item.put("title",localizedTitle(artifact.source().path("topic").path("title_by_locale")));
      List<Map<String,Object>> mapping=jdbc.queryForList("select chapter_id from learning_resource_artifact_scope where artifact_id=?",artifact.artifactId());
      item.put("mapped_chapter_id",mapping.isEmpty()?null:mapping.getFirst().get("chapter_id"));artifactItems.add(item);
    }
    return Map.of("chapters",chapters,"lessons",lessons,"structuredArtifacts",artifactItems);
  }

  @GetMapping("/admin/learning-resource-assignments")
  public Map<String,Object> adminAssignments(@RequestParam(required=false) Long resourceId,@RequestParam(required=false) String status,
      @RequestParam(defaultValue="100") Integer limit,@RequestParam(defaultValue="0") Integer offset,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    requireContentView(context);
    String statusFilter=normalizeFilter(status,ASSIGNMENT_STATUSES);
    int rowLimit=Math.max(1,Math.min(limit==null?100:limit,200)),rowOffset=Math.max(0,Math.min(offset==null?0:offset,10_000_000));
    List<Map<String,Object>> items=jdbc.queryForList("""
      select a.id as assignment_id,a.resource_id,a.target_kind,a.chapter_id,a.lesson_id,a.artifact_id,a.sort_order,a.status,
        a.created_at,a.updated_at,a.published_at,r.resource_type,r.title as resource_title,r.language as resource_language,
        r.source_kind,r.download_allowed,ch.code as chapter_code,ch.display_name as chapter_name,
        l.code as lesson_code,l.title as lesson_title,c.code as class_code,s.code as subject_code
      from learning_resource_assignment a join learning_resource r on r.id=a.resource_id
      join curriculum_chapter ch on ch.id=a.chapter_id
      join curriculum_subject s on s.id=ch.subject_id join curriculum_class c on c.id=s.class_id
      left join lesson l on l.id=a.lesson_id
      where (?::bigint is null or a.resource_id=?) and (?='' or a.status=?)
      order by a.chapter_id,a.sort_order,a.id limit ? offset ?
      """,resourceId,resourceId,statusFilter,statusFilter,rowLimit,rowOffset);
    Long total=jdbc.queryForObject("select count(*) from learning_resource_assignment a where (?::bigint is null or a.resource_id=?) and (?='' or a.status=?)",
        Long.class,resourceId,resourceId,statusFilter,statusFilter);
    return Map.of("items",items,"total",total==null?0L:total,"limit",rowLimit,"offset",rowOffset);
  }

  @PostMapping("/admin/learning-resource-assignments")
  @Transactional
  public Map<String,Object> createAssignment(@RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    context=authorization.requirePermission(context,"CONTENT_EDIT");
    long resourceId=positiveLong(body.get("resourceId"),"Resource");
    String targetKind=requiredEnum(body.get("targetKind"),"Target kind",TARGET_KINDS);
    int sortOrder=integerValue(body.getOrDefault("sortOrder",0),"Sort order",0,1_000_000);
    Map<String,Object> resource=resourceRow(resourceId);
    long chapterId;Long lessonId=null;String artifactId=null;
    if("CHAPTER".equals(targetKind)) {
      if(body.get("lessonId")!=null||body.get("artifactId")!=null)throw badRequest("A chapter assignment cannot also name a lesson or structured artifact.");
      chapterId=positiveLong(body.get("chapterId"),"Chapter");chapterRow(chapterId);
    } else if("LESSON".equals(targetKind)) {
      if(body.get("artifactId")!=null)throw badRequest("A lesson assignment cannot also name a structured artifact.");
      lessonId=positiveLong(body.get("lessonId"),"Lesson");
      List<Map<String,Object>> rows=jdbc.queryForList("select l.id,l.chapter_id,l.active,l.status from lesson l where l.id=?",lessonId);
      if(rows.isEmpty())throw notFound("Lesson",lessonId);
      Map<String,Object> lesson=rows.getFirst();
      if(!Boolean.TRUE.equals(lesson.get("active"))||"ARCHIVED".equals(String.valueOf(lesson.get("status"))))throw badRequest("Choose an active, non-archived lesson.");
      chapterId=((Number)lesson.get("chapter_id")).longValue();
      if(body.get("chapterId")!=null&&positiveLong(body.get("chapterId"),"Chapter")!=chapterId)throw badRequest("The selected lesson does not belong to the selected chapter.");
      chapterRow(chapterId);
    } else {
      if(body.get("lessonId")!=null)throw badRequest("A structured-artifact assignment cannot also name a legacy lesson.");
      artifactId=requiredText(body.get("artifactId"),"Structured artifact ID",120);
      StructuredLessonArtifactService.PublishedArtifact artifact=artifacts.requirePublished(artifactId);
      Long supplied=body.get("chapterId")==null?null:positiveLong(body.get("chapterId"),"Chapter");
      List<Map<String,Object>> mapping=jdbc.queryForList("select chapter_id from learning_resource_artifact_scope where artifact_id=?",artifactId);
      if(!mapping.isEmpty()) {
        chapterId=((Number)mapping.getFirst().get("chapter_id")).longValue();
        if(supplied!=null&&supplied.longValue()!=chapterId)throw conflict("This structured artifact is already mapped to a different canonical chapter. Review the existing mapping before attaching it elsewhere.");
        verifyArtifactChapter(artifact,chapterId);
        jdbc.update("update learning_resource_artifact_scope set artifact_sha256=?,updated_by_staff_id=?,updated_at=now() where artifact_id=?",
            artifact.sha256(),context.staffId(),artifactId);
      } else {
        if(supplied==null)throw badRequest("Select the canonical curriculum chapter for this structured artifact.");
        chapterId=supplied;verifyArtifactChapter(artifact,chapterId);
        jdbc.update("""
          insert into learning_resource_artifact_scope(artifact_id,chapter_id,artifact_sha256,created_by_staff_id,updated_by_staff_id)
          values(?,?,?,?,?) on conflict(artifact_id) do nothing
          """,artifactId,chapterId,artifact.sha256(),context.staffId(),context.staffId());
        List<Map<String,Object>> after=jdbc.queryForList("select chapter_id from learning_resource_artifact_scope where artifact_id=?",artifactId);
        if(after.isEmpty()||((Number)after.getFirst().get("chapter_id")).longValue()!=chapterId)
          throw conflict("This structured artifact was mapped to a different canonical chapter concurrently.");
      }
    }
    validateResourceTargetScope(resource,chapterId);
    try {
      Long id=jdbc.queryForObject("""
        insert into learning_resource_assignment(resource_id,target_kind,chapter_id,lesson_id,artifact_id,sort_order,status,created_by_staff_id)
        values(?,?,?,?,?,?,'DRAFT',?) returning id
        """,(rs,rowNum)->rs.getLong(1),resourceId,targetKind,chapterId,lessonId,artifactId,sortOrder,context.staffId());
      staffAudit.recordAction(context,"/api/v1/admin/learning-resource-assignments/"+id+"/created",
          "Assigned resource #"+resourceId+" to "+targetKind+" target "+(lessonId==null?(artifactId==null?"chapter #"+chapterId:"artifact "+artifactId):"lesson #"+lessonId)+".");
      return assignmentById(id);
    } catch(DataIntegrityViolationException ex) {
      throw conflict("This resource already has an active assignment to the selected target.");
    }
  }

  @PatchMapping("/admin/learning-resource-assignments/{assignmentId}/status")
  @Transactional
  public Map<String,Object> setAssignmentStatus(@PathVariable long assignmentId,@RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    context=authorization.requireAuth(context);
    String next=requiredEnum(body.get("status"),"Assignment status",ASSIGNMENT_STATUSES);
    if(Set.of("PUBLISHED","ARCHIVED").contains(next))authorization.requirePermission(context,"CONTENT_PUBLISH");
    else authorization.requirePermission(context,"CONTENT_EDIT");
    Map<String,Object> row=assignmentRow(assignmentId);String previous=String.valueOf(row.get("status"));
    if("PUBLISHED".equals(next)) {
      Map<String,Object> resource=resourceRow(((Number)row.get("resource_id")).longValue());
      if(!"PUBLISHED".equals(String.valueOf(resource.get("status"))))throw conflict("Publish the resource before publishing its assignment.");
      requireResourceReady(resource);validateAssignmentTarget(row,true);
    }
    jdbc.update("""
      update learning_resource_assignment set status=?,updated_at=now(),
        published_by_staff_id=case when ?='PUBLISHED' then ? when ?='DRAFT' then null else published_by_staff_id end,
        published_at=case when ?='PUBLISHED' then now() when ?='DRAFT' then null else published_at end
      where id=?
      """,next,next,context.staffId(),next,next,next,assignmentId);
    staffAudit.recordAction(context,"/api/v1/admin/learning-resource-assignments/"+assignmentId+"/status","Changed assignment status from "+previous+" to "+next+".");
    return assignmentById(assignmentId);
  }

  @PutMapping("/admin/learning-resource-assignments/{assignmentId}/order")
  @Transactional
  public Map<String,Object> reorderAssignment(@PathVariable long assignmentId,@RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    context=authorization.requirePermission(context,"CONTENT_EDIT");
    int order=integerValue(body.get("sortOrder"),"Sort order",0,1_000_000);
    Map<String,Object> row=assignmentRow(assignmentId);
    if("ARCHIVED".equals(String.valueOf(row.get("status"))))throw conflict("Archived assignments cannot be reordered.");
    jdbc.update("update learning_resource_assignment set sort_order=?,updated_at=now() where id=?",order,assignmentId);
    staffAudit.recordAction(context,"/api/v1/admin/learning-resource-assignments/"+assignmentId+"/order","Changed resource assignment order to "+order+".");
    return assignmentById(assignmentId);
  }

  @GetMapping("/learning/resources")
  public List<Map<String,Object>> studentResources(@RequestParam(required=false) Long chapterId,
      @RequestParam(required=false) Long lessonId,@RequestParam(required=false) String artifactId,
      @RequestParam(defaultValue="hi") String language,@RequestAttribute(value="authContext",required=false) AuthContext context) {
    context=authorization.requireStudent(context);
    String locale=normalizeLanguage(language);
    int targets=(chapterId==null?0:1)+(lessonId==null?0:1)+(artifactId==null||artifactId.isBlank()?0:1);
    if(targets!=1)throw badRequest("Supply exactly one chapterId, lessonId, or artifactId.");
    long canonicalChapter;String requestedArtifact=null;
    if(chapterId!=null) {
      canonicalChapter=chapterId;Map<String,Object> chapter=chapterRow(canonicalChapter);
      ensureStudentCanAccessChapter(context.studentId(),chapter);ensureChapterPublished(chapter);
    } else if(lessonId!=null) {
      List<Map<String,Object>> lessons=jdbc.queryForList("""
        select l.id,l.chapter_id,l.active as lesson_active,l.status as lesson_status
        from lesson l where l.id=?
        """,lessonId);
      if(lessons.isEmpty())throw notFound("Lesson",lessonId);
      Map<String,Object> lesson=lessons.getFirst();
      if(!Boolean.TRUE.equals(lesson.get("lesson_active"))||!"PUBLISHED".equals(String.valueOf(lesson.get("lesson_status"))))
        throw notFound("Published lesson",lessonId);
      canonicalChapter=((Number)lesson.get("chapter_id")).longValue();
      Map<String,Object> chapter=chapterRow(canonicalChapter);ensureChapterPublished(chapter);ensureStudentCanAccessChapter(context.studentId(),chapter);
    } else {
      requestedArtifact=requiredText(artifactId,"Structured artifact ID",120);
      StructuredLessonArtifactService.PublishedArtifact artifact=artifacts.requirePublished(requestedArtifact);
      List<Map<String,Object>> mappings=jdbc.queryForList("select chapter_id from learning_resource_artifact_scope where artifact_id=?",requestedArtifact);
      if(mappings.isEmpty())return List.of();
      canonicalChapter=((Number)mappings.getFirst().get("chapter_id")).longValue();
      verifyArtifactChapter(artifact,canonicalChapter);
      Map<String,Object> chapter=chapterRow(canonicalChapter);ensureChapterPublished(chapter);ensureStudentCanAccessChapter(context.studentId(),chapter);
    }
    List<Map<String,Object>> rows=jdbc.queryForList("""
      select r.id as resource_id,a.id as assignment_id,r.resource_type,r.title,r.description,r.language,r.source_kind,
        r.recorded_class_id,r.learning_document_id,r.content_asset_id,r.external_url,r.media_type,r.file_size_bytes,
        r.sha256,r.duration_seconds,r.download_allowed,a.target_kind,a.sort_order,
        coalesce(progress.last_position_seconds,0) as last_position_seconds,
        coalesce(progress.watched_seconds,0) as watched_seconds,coalesce(progress.completed,false) as completed,
        rc.status as recording_status,rc.media_type as recording_media_type,rc.file_size_bytes as recording_file_size_bytes,
        ld.status as document_status,pa.review_status as pdf_review_status,ca.status as content_asset_status,ca.url as content_asset_url
      from learning_resource_assignment a join learning_resource r on r.id=a.resource_id
      left join recorded_class rc on rc.id=r.recorded_class_id
      left join learning_document ld on ld.id=r.learning_document_id
      left join learning_pdf_asset pa on pa.id=ld.pdf_asset_id
      left join content_asset ca on ca.id=r.content_asset_id
      left join student_learning_resource_progress progress on progress.resource_id=r.id and progress.student_id=?
      where a.chapter_id=? and a.status='PUBLISHED' and r.status='PUBLISHED' and r.language in ('mul',?)
        and (
          a.target_kind='CHAPTER'
          or (?::bigint is not null and a.target_kind='LESSON' and a.lesson_id=?)
          or (?::varchar is not null and a.target_kind='STRUCTURED_ARTIFACT' and a.artifact_id=?)
        )
        and (
          (r.source_kind='RECORDED_CLASS' and rc.status='PUBLISHED')
          or (r.source_kind='LEARNING_DOCUMENT' and ld.status='PUBLISHED' and pa.review_status='APPROVED')
          or (r.source_kind='CONTENT_ASSET' and ca.status='PUBLISHED')
          or r.source_kind in ('PRIVATE_UPLOAD','EXTERNAL_URL')
        )
      order by case when a.target_kind='CHAPTER' then 0 else 1 end,a.sort_order,a.id
      """,context.studentId(),canonicalChapter,locale,lessonId,lessonId,requestedArtifact,requestedArtifact);
    List<Map<String,Object>> result=new ArrayList<>();
    for(Map<String,Object> raw:rows) {
      Map<String,Object> item=new LinkedHashMap<>();
      item.put("resourceId",raw.get("resource_id"));item.put("assignmentId",raw.get("assignment_id"));
      item.put("resourceType",raw.get("resource_type"));item.put("title",raw.get("title"));item.put("description",raw.get("description"));
      item.put("language",raw.get("language"));item.put("sourceKind",raw.get("source_kind"));
      item.put("mediaType",raw.get("media_type")!=null?raw.get("media_type"):raw.get("recording_media_type"));
      item.put("fileSizeBytes",raw.get("file_size_bytes")!=null?raw.get("file_size_bytes"):raw.get("recording_file_size_bytes"));
      item.put("durationSeconds",raw.get("duration_seconds"));item.put("sha256",raw.get("sha256"));item.put("downloadAllowed",Boolean.TRUE.equals(raw.get("download_allowed")));
      item.put("lastPositionSeconds",raw.get("last_position_seconds"));item.put("watchedSeconds",raw.get("watched_seconds"));item.put("completed",raw.get("completed"));
      String sourceKind=String.valueOf(raw.get("source_kind"));
      long id=((Number)raw.get("resource_id")).longValue(),assignment=((Number)raw.get("assignment_id")).longValue();
      if("PRIVATE_UPLOAD".equals(sourceKind)||"RECORDED_CLASS".equals(sourceKind)) {
        item.put("streamUrl","/api/v1/learning/resources/"+id+"/stream?assignmentId="+assignment);
        if(Boolean.TRUE.equals(raw.get("download_allowed")))item.put("downloadUrl","/api/v1/learning/resources/"+id+"/download?assignmentId="+assignment);
      } else if("LEARNING_DOCUMENT".equals(sourceKind)) {
        item.put("learningDocumentId",raw.get("learning_document_id"));item.put("readerApiUrl","/api/v1/learning/documents/"+raw.get("learning_document_id"));
      } else if("CONTENT_ASSET".equals(sourceKind)) {
        Object url=raw.get("content_asset_url");if(isSafeClientAssetUrl(url==null?null:String.valueOf(url)))item.put("assetUrl",url);
      } else if("EXTERNAL_URL".equals(sourceKind))item.put("externalUrl",raw.get("external_url"));
      result.add(item);
    }
    return List.copyOf(result);
  }

  @GetMapping("/learning/resources/{resourceId}/stream")
  public ResponseEntity<StreamingResponseBody> streamResource(@PathVariable long resourceId,@RequestParam long assignmentId,
      @RequestHeader(value="Range",required=false) String rangeHeader,@RequestAttribute(value="authContext",required=false) AuthContext context) {
    context=authorization.requireStudent(context);
    return streamPrivateResource(readableAssignedResource(resourceId,assignmentId,context.studentId()),rangeHeader,false);
  }

  @GetMapping("/learning/resources/{resourceId}/download")
  public ResponseEntity<StreamingResponseBody> downloadResource(@PathVariable long resourceId,@RequestParam long assignmentId,
      @RequestHeader(value="Range",required=false) String rangeHeader,@RequestAttribute(value="authContext",required=false) AuthContext context) {
    context=authorization.requireStudent(context);
    Map<String,Object> resource=readableAssignedResource(resourceId,assignmentId,context.studentId());
    if(!Boolean.TRUE.equals(resource.get("download_allowed")))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Downloads are disabled for this resource.");
    if(!Set.of("PRIVATE_UPLOAD","RECORDED_CLASS").contains(String.valueOf(resource.get("source_kind"))))
      throw new ResponseStatusException(HttpStatus.FORBIDDEN,"This resource does not support managed downloads.");
    return streamPrivateResource(resource,rangeHeader,true);
  }

  @PutMapping("/learning/resources/{resourceId}/progress")
  @Transactional
  public Map<String,Object> saveStudentProgress(@PathVariable long resourceId,@RequestParam long assignmentId,
      @RequestBody Map<String,Object> body,@RequestAttribute(value="authContext",required=false) AuthContext context) {
    context=authorization.requireStudent(context);
    Map<String,Object> resource=readableAssignedResource(resourceId,assignmentId,context.studentId());
    if(!Set.of("VIDEO","AUDIO").contains(String.valueOf(resource.get("resource_type"))))throw badRequest("Progress is supported only for video and audio resources.");
    long position=longValue(body.getOrDefault("lastPositionSeconds",0),"Playback position");
    long watched=longValue(body.getOrDefault("watchedSeconds",0),"Watched seconds");
    boolean completed=booleanValue(body.get("completed"),false);
    jdbc.update("""
      insert into student_learning_resource_progress(student_id,resource_id,last_position_seconds,watched_seconds,completed,last_opened_at,updated_at)
      values(?,?,?,?,?,now(),now())
      on conflict(student_id,resource_id) do update set last_position_seconds=excluded.last_position_seconds,
        watched_seconds=greatest(student_learning_resource_progress.watched_seconds,excluded.watched_seconds),
        completed=excluded.completed,last_opened_at=now(),updated_at=now()
      """,context.studentId(),resourceId,position,watched,completed);
    return Map.of("resourceId",resourceId,"lastPositionSeconds",position,"watchedSeconds",watched,"completed",completed);
  }

  private Map<String,Object> readableAssignedResource(long resourceId,long assignmentId,long studentId) {
    List<Map<String,Object>> rows=jdbc.queryForList("""
      select r.id as resource_id,r.resource_type,r.source_kind,r.storage_key,r.original_filename,r.media_type,r.file_size_bytes,
        r.sha256,r.download_allowed,r.external_url,r.status as resource_status,a.id as assignment_id,a.target_kind,
        a.chapter_id,a.lesson_id,a.artifact_id,a.status as assignment_status,
        rc.status as recording_status,rc.storage_key as recording_storage_key,rc.original_filename as recording_filename,
        rc.media_type as recording_media_type,rc.file_size_bytes as recording_file_size_bytes,rc.sha256 as recording_sha256,
        ld.status as document_status,pa.review_status as pdf_review_status,ca.status as content_asset_status
      from learning_resource_assignment a join learning_resource r on r.id=a.resource_id
      left join recorded_class rc on rc.id=r.recorded_class_id
      left join learning_document ld on ld.id=r.learning_document_id
      left join learning_pdf_asset pa on pa.id=ld.pdf_asset_id
      left join content_asset ca on ca.id=r.content_asset_id
      where r.id=? and a.id=? and r.status='PUBLISHED' and a.status='PUBLISHED'
        and ((r.source_kind='RECORDED_CLASS' and rc.status='PUBLISHED')
          or (r.source_kind='LEARNING_DOCUMENT' and ld.status='PUBLISHED' and pa.review_status='APPROVED')
          or (r.source_kind='CONTENT_ASSET' and ca.status='PUBLISHED') or r.source_kind in ('PRIVATE_UPLOAD','EXTERNAL_URL'))
      """,resourceId,assignmentId);
    if(rows.isEmpty())throw notFound("Assigned learning resource",resourceId);
    Map<String,Object> row=new LinkedHashMap<>(rows.getFirst());
    long chapterId=((Number)row.get("chapter_id")).longValue();
    if("LESSON".equals(row.get("target_kind"))) {
      List<Map<String,Object>> lessons=jdbc.queryForList("select l.id,l.chapter_id,l.active,l.status from lesson l where l.id=?",row.get("lesson_id"));
      if(lessons.isEmpty()||!Boolean.TRUE.equals(lessons.getFirst().get("active"))||!"PUBLISHED".equals(String.valueOf(lessons.getFirst().get("status")))
          ||((Number)lessons.getFirst().get("chapter_id")).longValue()!=chapterId)throw notFound("Published learning resource target",assignmentId);
    }
    Map<String,Object> chapter=chapterRow(chapterId);ensureChapterPublished(chapter);ensureStudentCanAccessChapter(studentId,chapter);
    if("STRUCTURED_ARTIFACT".equals(row.get("target_kind")))verifyArtifactChapter(artifacts.requirePublished(String.valueOf(row.get("artifact_id"))),chapterId);
    if("PRIVATE_UPLOAD".equals(row.get("source_kind"))) {
      Path path=resourceStorage.resolve(String.valueOf(row.get("storage_key")));
      try {
        if(!Files.isRegularFile(path)||Files.size(path)!=((Number)row.get("file_size_bytes")).longValue())
          throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Learning resource file not found.");
      } catch(IOException ex) {throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,"Learning resource file could not be read.",ex);}
    }
    return row;
  }

  private ResponseEntity<StreamingResponseBody> streamPrivateResource(Map<String,Object> resource,String rangeHeader,boolean attachment) {
    String source=String.valueOf(resource.get("source_kind"));
    Path file;String filename,type;long expectedSize;
    if("PRIVATE_UPLOAD".equals(source)) {
      file=resourceStorage.resolve(String.valueOf(resource.get("storage_key")));filename=String.valueOf(resource.get("original_filename"));
      type=String.valueOf(resource.get("media_type"));expectedSize=((Number)resource.get("file_size_bytes")).longValue();
    } else if("RECORDED_CLASS".equals(source)) {
      file=recordingStorage.resolve(String.valueOf(resource.get("recording_storage_key")));filename=String.valueOf(resource.get("recording_filename"));
      type=String.valueOf(resource.get("recording_media_type"));expectedSize=((Number)resource.get("recording_file_size_bytes")).longValue();
    } else throw new ResponseStatusException(HttpStatus.CONFLICT,"Use the linked resource viewer for this source type.");
    if(!Files.isRegularFile(file))throw notFound("Learning resource file",((Number)resource.get("resource_id")).longValue());
    final long size;
    try {size=Files.size(file);if(size!=expectedSize)throw conflict("Stored resource size does not match metadata.");}
    catch(IOException ex){throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,"The learning resource is unavailable.",ex);}
    long start=0,end=Math.max(0,size-1);boolean partial=rangeHeader!=null&&!rangeHeader.isBlank();
    if(partial) {
      long[] range=parseRange(rangeHeader,size);
      if(range==null)return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE).header(HttpHeaders.CONTENT_RANGE,"bytes */"+size)
          .header(HttpHeaders.ACCEPT_RANGES,"bytes").cacheControl(CacheControl.noStore()).build();
      start=range[0];end=range[1];
    }
    long length=end-start+1;final long byteStart=start,bytesToSend=length;
    StreamingResponseBody body=output->{
      try(RandomAccessFile input=new RandomAccessFile(file.toFile(),"r")) {
        input.seek(byteStart);byte[] buffer=new byte[64*1024];long remaining=bytesToSend;
        while(remaining>0) {int n=input.read(buffer,0,(int)Math.min(buffer.length,remaining));if(n<0)break;output.write(buffer,0,n);remaining-=n;}
        output.flush();
      }
    };
    HttpHeaders headers=new HttpHeaders();
    try{headers.setContentType(MediaType.parseMediaType(type));}catch(IllegalArgumentException ex){headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);}
    headers.setContentLength(length);headers.set(HttpHeaders.ACCEPT_RANGES,"bytes");headers.setCacheControl(CacheControl.noStore());
    headers.setPragma("no-cache");headers.set("X-Content-Type-Options","nosniff");headers.set("X-Robots-Tag","noindex, noarchive");
    headers.setContentDisposition((attachment?ContentDisposition.attachment():ContentDisposition.inline()).filename(filename,StandardCharsets.UTF_8).build());
    if(partial)headers.set(HttpHeaders.CONTENT_RANGE,"bytes "+start+"-"+end+"/"+size);
    return new ResponseEntity<>(body,headers,partial?HttpStatus.PARTIAL_CONTENT:HttpStatus.OK);
  }

  private Map<String,Object> resourceRow(long id) {
    List<Map<String,Object>> rows=jdbc.queryForList("""
      select r.id as resource_id,r.resource_type,r.title,r.description,r.language,r.source_kind,r.recorded_class_id,
        r.learning_document_id,r.content_asset_id,r.storage_key,r.external_url,r.original_filename,r.media_type,r.file_size_bytes,
        r.sha256,r.duration_seconds,r.source_reference,r.download_allowed,r.status,r.version_no,r.supersedes_resource_id,
        r.created_by_staff_id,r.published_by_staff_id,r.created_at,r.updated_at,r.published_at,
        rc.status as recording_status,rc.subject_id as recording_subject_id,rc.chapter_id as recording_chapter_id,
        ld.status as document_status,ld.subject_id as document_subject_id,ld.chapter_id as document_chapter_id,
        ld.scope as document_scope,pa.review_status as pdf_review_status,
        ca.status as content_asset_status,ca.asset_type as content_asset_type,ca.url as content_asset_url
      from learning_resource r left join recorded_class rc on rc.id=r.recorded_class_id
      left join learning_document ld on ld.id=r.learning_document_id left join learning_pdf_asset pa on pa.id=ld.pdf_asset_id
      left join content_asset ca on ca.id=r.content_asset_id where r.id=?
      """,id);
    if(rows.isEmpty())throw notFound("Learning resource",id);
    return new LinkedHashMap<>(rows.getFirst());
  }

  private Map<String,Object> adminResourceById(long id) {
    Map<String,Object> item=resourceRow(id);
    Long count=jdbc.queryForObject("select count(*) from learning_resource_assignment where resource_id=? and status<>'ARCHIVED'",Long.class,id);
    item.put("assignment_count",count==null?0L:count);item.remove("storage_key");return item;
  }

  private void requireResourceReady(Map<String,Object> resource) {
    switch(String.valueOf(resource.get("source_kind"))) {
      case "RECORDED_CLASS" -> {if(!"PUBLISHED".equals(String.valueOf(resource.get("recording_status"))) )throw conflict("Publish the linked recorded class before publishing this resource.");}
      case "LEARNING_DOCUMENT" -> {
        if(!"PUBLISHED".equals(String.valueOf(resource.get("document_status")))||!"APPROVED".equals(String.valueOf(resource.get("pdf_review_status"))))
          throw conflict("Publish the linked textbook document and approve its PDF asset first.");
      }
      case "CONTENT_ASSET" -> {
        if(!"PUBLISHED".equals(String.valueOf(resource.get("content_asset_status")))||!isSafeClientAssetUrl(String.valueOf(resource.get("content_asset_url"))))
          throw conflict("Publish the linked content asset and ensure it has a safe learner-facing URL.");
      }
      case "PRIVATE_UPLOAD" -> {
        if(!resourceStorage.matchesStoredFile(String.valueOf(resource.get("storage_key")),
            ((Number)resource.get("file_size_bytes")).longValue(),String.valueOf(resource.get("sha256"))))
          throw conflict("The private resource file is missing or its checksum does not match. Upload it again before publishing.");
      }
      case "EXTERNAL_URL" -> validExternalUrl(resource.get("external_url"));
      default -> throw badRequest("Unsupported learning resource source.");
    }
  }

  private Map<String,Object> assignmentRow(long id) {
    List<Map<String,Object>> rows=jdbc.queryForList("select a.id as assignment_id,a.resource_id,a.target_kind,a.chapter_id,a.lesson_id,a.artifact_id,a.sort_order,a.status,a.created_by_staff_id,a.published_by_staff_id,a.created_at,a.updated_at,a.published_at from learning_resource_assignment a where a.id=?",id);
    if(rows.isEmpty())throw notFound("Resource assignment",id);
    return new LinkedHashMap<>(rows.getFirst());
  }

  private Map<String,Object> assignmentById(long id) {
    List<Map<String,Object>> rows=jdbc.queryForList("""
      select a.id as assignment_id,a.resource_id,a.target_kind,a.chapter_id,a.lesson_id,a.artifact_id,a.sort_order,a.status,
        a.created_at,a.updated_at,a.published_at,r.resource_type,r.title as resource_title,r.language as resource_language,
        r.source_kind,r.download_allowed,ch.code as chapter_code,ch.display_name as chapter_name,
        l.code as lesson_code,l.title as lesson_title,c.code as class_code,s.code as subject_code
      from learning_resource_assignment a join learning_resource r on r.id=a.resource_id
      join curriculum_chapter ch on ch.id=a.chapter_id join curriculum_subject s on s.id=ch.subject_id
      join curriculum_class c on c.id=s.class_id left join lesson l on l.id=a.lesson_id where a.id=?
      """,id);
    if(rows.isEmpty())throw notFound("Resource assignment",id);
    return new LinkedHashMap<>(rows.getFirst());
  }

  private void validateResourceTargetScope(Map<String,Object> resource,long chapterId) {
    Map<String,Object> chapter=chapterRow(chapterId);long subjectId=((Number)chapter.get("subject_id")).longValue();
    String kind=String.valueOf(resource.get("source_kind"));
    if("RECORDED_CLASS".equals(kind)) {
      Object sid=resource.get("recording_subject_id"),cid=resource.get("recording_chapter_id");
      if(!(sid instanceof Number recordingSubjectId)||recordingSubjectId.longValue()!=subjectId)throw badRequest("The recording and target chapter must belong to the same subject.");
      if(cid instanceof Number recordingChapterId&&recordingChapterId.longValue()!=chapterId)throw badRequest("This recording is already chapter-specific and cannot be assigned to another chapter.");
    } else if("LEARNING_DOCUMENT".equals(kind)) {
      Object sid=resource.get("document_subject_id"),cid=resource.get("document_chapter_id");
      if(!(sid instanceof Number documentSubjectId)||documentSubjectId.longValue()!=subjectId)throw badRequest("The PDF document and target chapter must belong to the same subject.");
      if(cid instanceof Number documentChapterId&&documentChapterId.longValue()!=chapterId)throw badRequest("This chapter PDF can only be assigned to its canonical chapter.");
    }
  }

  private void validateAssignmentTarget(Map<String,Object> row,boolean requirePublished) {
    String kind=String.valueOf(row.get("target_kind"));long chapterId=((Number)row.get("chapter_id")).longValue();
    Map<String,Object> chapter=chapterRow(chapterId);if(requirePublished)ensureChapterPublished(chapter);
    if("LESSON".equals(kind)) {
      List<Map<String,Object>> lessons=jdbc.queryForList("select l.id,l.chapter_id,l.active,l.status from lesson l where l.id=?",row.get("lesson_id"));
      if(lessons.isEmpty())throw notFound("Lesson",((Number)row.get("lesson_id")).longValue());
      Map<String,Object> lesson=lessons.getFirst();
      if(!Boolean.TRUE.equals(lesson.get("active"))||((Number)lesson.get("chapter_id")).longValue()!=chapterId)
        throw conflict("The legacy lesson no longer matches its canonical chapter.");
      if(requirePublished&&!"PUBLISHED".equals(String.valueOf(lesson.get("status"))) )
        throw conflict("Publish the lesson before publishing its assigned resources.");
    } else if("STRUCTURED_ARTIFACT".equals(kind)) {
      verifyArtifactChapter(artifacts.requirePublished(String.valueOf(row.get("artifact_id"))),chapterId);
    }
  }

  private Map<String,Object> chapterRow(long id) {
    List<Map<String,Object>> rows=jdbc.queryForList("""
      select ch.id as chapter_id,ch.subject_id,ch.code as chapter_code,ch.display_name as chapter_name,
        ch.textbook_chapter_no,ch.active,ch.content_status,c.code as class_code,s.code as subject_code,
        s.program_id,p.code as program_code
      from curriculum_chapter ch join curriculum_subject s on s.id=ch.subject_id join curriculum_class c on c.id=s.class_id
      left join curriculum_program p on p.id=s.program_id where ch.id=? and s.active=true and c.active=true
      """,id);
    if(rows.isEmpty())throw notFound("Curriculum chapter",id);
    return new LinkedHashMap<>(rows.getFirst());
  }

  private void verifyArtifactChapter(StructuredLessonArtifactService.PublishedArtifact artifact,long chapterId) {
    Map<String,Object> chapter=chapterRow(chapterId);
    String digits=String.valueOf(chapter.get("class_code")).replaceAll("[^0-9]","");
    if(!String.valueOf(artifacts.classNumber(artifact)).equals(digits)
        ||!artifacts.subjectCode(artifact).equalsIgnoreCase(String.valueOf(chapter.get("subject_code"))))
      throw badRequest("The selected canonical chapter does not match the structured artifact's class and subject.");
    String artifactProgram=artifact.source().path("curriculum").path("program_code").asText("").trim();
    String chapterProgram=String.valueOf(chapter.get("program_code")==null?"":chapter.get("program_code")).trim();
    if(!artifactProgram.isEmpty()&&!artifactProgram.equalsIgnoreCase(chapterProgram))
      throw badRequest("The selected chapter does not match the structured artifact's course.");
    int artifactNumber=artifact.source().path("chapter").path("chapter_number").asInt(0);
    Object textbookNo=chapter.get("textbook_chapter_no");
    if(artifactNumber>0&&textbookNo instanceof Number n&&n.intValue()>0&&n.intValue()!=artifactNumber)
      throw badRequest("The selected chapter number conflicts with the structured artifact. Choose the matching canonical chapter.");
  }

  private void ensureChapterPublished(Map<String,Object> chapter) {
    if(!Boolean.TRUE.equals(chapter.get("active"))||!"PUBLISHED".equals(String.valueOf(chapter.get("content_status")))
        )throw notFound("Published curriculum chapter",((Number)chapter.get("chapter_id")).longValue());
  }

  private void ensureStudentCanAccessChapter(long studentId,Map<String,Object> chapter) {
    Boolean allowed=jdbc.queryForObject("""
      select exists(select 1 from curriculum_chapter ch
        join curriculum_subject s on s.id=ch.subject_id and s.active=true
        join curriculum_class c on c.id=s.class_id and c.active=true
        join student st on st.id=? and st.active=true
        join student_track_enrollment ste on ste.student_id=st.id and ste.subject_id=s.id and ste.status='ACTIVE'
        where ch.id=? and (
          (s.program_id is null and c.code=st.class_code)
          or (s.program_id is not null and exists(
            select 1 from student_program_enrollment spe join curriculum_program ep on ep.id=spe.program_id and ep.status='ACTIVE'
            where spe.student_id=st.id and spe.program_id=s.program_id and spe.status='ACTIVE'
          ))
        ))
      """,Boolean.class,studentId,chapter.get("chapter_id"));
    if(!Boolean.TRUE.equals(allowed))throw notFound("Learning resource target",((Number)chapter.get("chapter_id")).longValue());
  }

  private String normalizeFilter(String raw,Set<String> allowed) {
    String value=raw==null?"":raw.trim().toUpperCase(Locale.ROOT);
    if(value.isEmpty()||"ALL".equals(value))return "";
    if(!allowed.contains(value))throw badRequest("Choose a valid filter.");return value;
  }
  private String normalizeLanguage(Object raw) {
    String value=raw==null?"mul":String.valueOf(raw).trim().toLowerCase(Locale.ROOT);
    if(Set.of("hi","en","mul").contains(value))return value;throw badRequest("Resource language must be hi, en, or mul.");
  }
  private String requiredEnum(Object raw,String label,Set<String> values) {
    String value=requiredText(raw,label,40).toUpperCase(Locale.ROOT);
    if(!values.contains(value))throw badRequest("Choose a valid "+label.toLowerCase(Locale.ROOT)+".");return value;
  }
  private String requiredText(Object raw,String label,int max) {
    String value=raw==null?"":String.valueOf(raw).trim();
    if(value.isBlank())throw badRequest(label+" is required.");
    if(value.length()>max)throw badRequest(label+" is too long.");return value;
  }
  private String optionalText(Object raw,int max) {
    if(raw==null)return null;String value=String.valueOf(raw).trim();
    if(value.isBlank())return null;return value.length()>max?value.substring(0,max):value;
  }
  private boolean booleanValue(Object raw,boolean fallback) {
    if(raw==null)return fallback;if(raw instanceof Boolean value)return value;
    String value=String.valueOf(raw).trim();
    if("true".equalsIgnoreCase(value))return true;if("false".equalsIgnoreCase(value))return false;
    throw badRequest("A boolean value is required.");
  }
  private long positiveLong(Object raw,String label) {
    long value=longValue(raw,label);if(value<=0)throw badRequest(label+" ID must be positive.");return value;
  }
  private long longValue(Object raw,String label) {
    try {long value=raw instanceof Number n?n.longValue():Long.parseLong(String.valueOf(raw).trim());if(value<0)throw badRequest(label+" cannot be negative.");return value;}
    catch(NumberFormatException ex){throw badRequest(label+" must be a valid integer.");}
  }
  private int integerValue(Object raw,String label,int min,int max) {
    try {int value=raw instanceof Number n?n.intValue():Integer.parseInt(String.valueOf(raw).trim());if(value<min||value>max)throw badRequest(label+" must be between "+min+" and "+max+".");return value;}
    catch(NumberFormatException ex){throw badRequest(label+" must be a valid integer.");}
  }
  private String validExternalUrl(Object raw) {
    String value=requiredText(raw,"External resource URL",2000);
    try {
      URI uri=URI.create(value);
      if(!"https".equalsIgnoreCase(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null)
        throw badRequest("External resource URLs must use HTTPS and cannot contain embedded credentials.");
      String host=uri.getHost().toLowerCase(Locale.ROOT);
      if(host.equals("localhost")||host.endsWith(".local")||host.equals("127.0.0.1")||host.equals("0.0.0.0")||host.equals("::1"))
        throw badRequest("A local/private host cannot be used as a student-facing external resource.");
      return uri.toASCIIString();
    } catch(IllegalArgumentException ex){throw badRequest("Enter a valid HTTPS external URL.");}
  }
  private boolean isSafeClientAssetUrl(String value) {
    if(value==null||value.isBlank())return false;
    if(value.startsWith("/")&&!value.startsWith("//"))return true;
    try {URI uri=URI.create(value);return "https".equalsIgnoreCase(uri.getScheme())&&uri.getHost()!=null&&uri.getUserInfo()==null;}
    catch(IllegalArgumentException ex){return false;}
  }
  private String localizedTitle(tools.jackson.databind.JsonNode locales) {
    String value=locales.path("en").asText("");return value.isBlank()?locales.path("hi").asText(""):value;
  }
  private long[] parseRange(String header,long size) {
    if(size<=0||header==null||!header.startsWith("bytes=")||header.contains(","))return null;
    String[] parts=header.substring(6).trim().split("-", -1);
    if(parts.length!=2||(parts[0].isBlank()&&parts[1].isBlank()))return null;
    try {
      long start,end;
      if(parts[0].isBlank()){long suffix=Long.parseLong(parts[1]);if(suffix<=0)return null;start=Math.max(0,size-suffix);end=size-1;}
      else {start=Long.parseLong(parts[0]);end=parts[1].isBlank()?size-1:Long.parseLong(parts[1]);}
      if(start<0||start>=size||end<start)return null;
      return new long[]{start,Math.min(end,size-1)};
    } catch(NumberFormatException ex){return null;}
  }
  private void requireContentView(AuthContext context) {
    context=authorization.requireAuth(context);
    if(!authorization.hasPermission(context,"CONTENT_VIEW")&&!authorization.hasPermission(context,"CONTENT_EDIT")
        &&!authorization.hasPermission(context,"CONTENT_PUBLISH"))throw new SecurityException("Content view, edit, or publish permission required.");
  }
  private ResponseStatusException badRequest(String message){return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);}
  private ResponseStatusException conflict(String message){return new ResponseStatusException(HttpStatus.CONFLICT,message);}
  private ResponseStatusException notFound(String label,long id){return new ResponseStatusException(HttpStatus.NOT_FOUND,label+" not found: "+id);}
}
