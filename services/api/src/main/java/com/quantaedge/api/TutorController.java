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
    return tutor.startSession(context,Long.parseLong(String.valueOf(body.get("lessonId"))),
        Boolean.parseBoolean(String.valueOf(body.getOrDefault("newChat",false))),requireInstitution(tenant));
  }

  @GetMapping("/sessions/{sessionId}/messages")
  public Map<String,Object> messages(
      @RequestAttribute(value="authContext",required=false) AuthContext context,
      @RequestAttribute(value=InstitutionTenantContextService.REQUEST_ATTRIBUTE,required=false)
          InstitutionTenantContext tenant,
      @PathVariable long sessionId,
      @RequestParam(defaultValue="30") Integer limit,@RequestParam(required=false) Long beforeId){
    return tutor.messages(context,sessionId,limit,beforeId,requireInstitution(tenant));
  }

  @PostMapping("/sessions/{sessionId}/messages")
  public Map<String,Object> send(
      @RequestAttribute(value="authContext",required=false) AuthContext context,
      @RequestAttribute(value=InstitutionTenantContextService.REQUEST_ATTRIBUTE,required=false)
          InstitutionTenantContext tenant,
      @PathVariable long sessionId,@RequestBody Map<String,Object> body){
    return tutor.send(context,sessionId,String.valueOf(body.getOrDefault("message","")),
        String.valueOf(body.getOrDefault("mode","HINT")),
        body.get("questionId")==null?0L:Long.parseLong(String.valueOf(body.get("questionId"))),
        body.get("language")==null?"hi":String.valueOf(body.get("language")),requireInstitution(tenant));
  }
  private long requireInstitution(InstitutionTenantContext tenant) {
    if (tenant == null || tenant.institutionId() < 1) {
      throw new InstitutionTenantContextService.InvalidInstitutionContextException(
          400,"INSTITUTION_CONTEXT_REQUIRED","Select the institution for the AI tutor.");
    }
    return tenant.institutionId();
  }
}
