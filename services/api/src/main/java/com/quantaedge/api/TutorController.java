package com.quantaedge.api;

import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/tutor")
public class TutorController {
  private final TutorService tutor;
  public TutorController(TutorService tutor){this.tutor=tutor;}

  @PostMapping("/sessions")
  public Map<String,Object> start(
      @RequestAttribute(value="authContext",required=false) AuthContext context,
      @RequestAttribute(value=InstitutionTenantContextService.REQUEST_ATTRIBUTE,required=false)
          InstitutionTenantContext tenant,
      @RequestBody Map<String,Object> body){
    long institutionId=requireInstitution(tenant);
    long courseId=requirePositiveLong(body.get("courseId"),"courseId");
    boolean newChat=Boolean.parseBoolean(String.valueOf(body.getOrDefault("newChat",false)));
    String artifactId=body.get("artifactId")==null?"":String.valueOf(body.get("artifactId")).trim();
    if(!artifactId.isBlank()){
      Long databaseLessonId=RelationalStructuredLessonService.parseDatabaseLessonId(artifactId);
      if(databaseLessonId!=null)
        return tutor.startSession(context,databaseLessonId,newChat,institutionId,courseId);
      return tutor.startStructuredSession(context,artifactId,newChat,institutionId,courseId);
    }
    return tutor.startSession(context,requirePositiveLong(body.get("lessonId"),"lessonId"),
        newChat,institutionId,courseId);
  }

  @GetMapping("/sessions/{sessionId}/messages")
  public Map<String,Object> messages(
      @RequestAttribute(value="authContext",required=false) AuthContext context,
      @RequestAttribute(value=InstitutionTenantContextService.REQUEST_ATTRIBUTE,required=false)
          InstitutionTenantContext tenant,
      @PathVariable long sessionId,
      @RequestParam Long courseId,
      @RequestParam(defaultValue="30") Integer limit,@RequestParam(required=false) Long beforeId){
    return tutor.messages(context,sessionId,limit,beforeId,requireInstitution(tenant),courseId);
  }

  @PostMapping("/sessions/{sessionId}/messages")
  public Map<String,Object> send(
      @RequestAttribute(value="authContext",required=false) AuthContext context,
      @RequestAttribute(value=InstitutionTenantContextService.REQUEST_ATTRIBUTE,required=false)
          InstitutionTenantContext tenant,
      @PathVariable long sessionId,@RequestParam Long courseId,@RequestBody Map<String,Object> body){
    return tutor.send(context,sessionId,String.valueOf(body.getOrDefault("message","")),
        String.valueOf(body.getOrDefault("mode","HINT")),
        body.get("questionId")==null?0L:Long.parseLong(String.valueOf(body.get("questionId"))),
        body.get("structuredQuestionId")==null?null:String.valueOf(body.get("structuredQuestionId")),
        body.get("language")==null?"hi":String.valueOf(body.get("language")),requireInstitution(tenant),courseId);
  }
  private long requirePositiveLong(Object value,String field){
    if(value==null)throw new IllegalArgumentException("The tutor request requires "+field+".");
    try{long parsed=Long.parseLong(String.valueOf(value));if(parsed>0)return parsed;}
    catch(NumberFormatException ignored){}
    throw new IllegalArgumentException("The tutor request requires a valid "+field+".");
  }
  private long requireInstitution(InstitutionTenantContext tenant) {
    if (tenant == null || tenant.institutionId() < 1) {
      throw new InstitutionTenantContextService.InvalidInstitutionContextException(
          400,"INSTITUTION_CONTEXT_REQUIRED","Select the institution for the AI tutor.");
    }
    return tenant.institutionId();
  }
}
