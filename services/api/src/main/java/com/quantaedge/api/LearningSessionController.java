package com.quantaedge.api;

import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/learning/sessions")
public class LearningSessionController {
  private final JdbcTemplate jdbc;
  private final AuthorizationService authorization;

  public LearningSessionController(JdbcTemplate jdbc,AuthorizationService authorization){
    this.jdbc=jdbc;this.authorization=authorization;
  }

  private long requireInstitution(InstitutionTenantContext tenant) {
    if (tenant == null || tenant.institutionId() < 1) {
      throw new InstitutionTenantContextService.InvalidInstitutionContextException(
          400, "INSTITUTION_CONTEXT_REQUIRED", "Select the institution for this learning session.");
    }
    return tenant.institutionId();
  }

  @PostMapping("/start")
  public Map<String,Object> start(@RequestAttribute(value="authContext",required=false) AuthContext context,
      @RequestBody Map<String,Object> body,
      @RequestAttribute(value=InstitutionTenantContextService.REQUEST_ATTRIBUTE,required=false)
          InstitutionTenantContext tenant){
    context=authorization.requireStudent(context);
    long institutionId=requireInstitution(tenant);
    String source=String.valueOf(body.getOrDefault("source","LESSON"));
    Long id=jdbc.queryForObject("""
      insert into learning_session(student_id,institution_id,source) values (?,?,?) returning id
      """,Long.class,context.studentId(),institutionId,source);
    return Map.of("sessionId",id);
  }

  @PostMapping("/{sessionId}/end")
  public Map<String,Object> end(@PathVariable long sessionId,
      @RequestAttribute(value="authContext",required=false) AuthContext context,
      @RequestBody Map<String,Object> body,
      @RequestAttribute(value=InstitutionTenantContextService.REQUEST_ATTRIBUTE,required=false)
          InstitutionTenantContext tenant){
    context=authorization.requireStudent(context);
    long institutionId=requireInstitution(tenant);
    Integer minutes=body.get("minutes")==null?0:Integer.valueOf(String.valueOf(body.get("minutes")));
    if(minutes<0||minutes>240) throw new IllegalArgumentException("Invalid session duration");
    int changed=jdbc.update("""
      update learning_session set ended_at=now(),minutes=?
      where id=? and student_id=? and institution_id=? and ended_at is null
      """,minutes,sessionId,context.studentId(),institutionId);
    return Map.of("ended",changed>0,"sessionId",sessionId,"minutes",minutes);
  }
}
