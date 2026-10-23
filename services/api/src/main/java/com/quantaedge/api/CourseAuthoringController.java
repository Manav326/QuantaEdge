package com.quantaedge.api;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Education Admin course lifecycle actions. The AuthFilter supplies a tenant context derived
 * from the staff member's HRMS binding; request JSON and browser headers cannot choose the owner.
 */
@RestController
@RequestMapping("/api/v1/admin/content")
public class CourseAuthoringController {
  private final AuthorizationService authorization;
  private final CourseAuthoringService courseAuthoring;
  private final CourseVersionService courseVersionService;
  private final JdbcTemplate jdbc;

  public CourseAuthoringController(
      AuthorizationService authorization,CourseAuthoringService courseAuthoring,
      CourseVersionService courseVersionService,JdbcTemplate jdbc) {
    this.authorization=authorization;
    this.courseAuthoring=courseAuthoring;
    this.courseVersionService=courseVersionService;
    this.jdbc=jdbc;
  }

  @GetMapping("/course-types")
  public Map<String,Object> courseTypes(
      @RequestAttribute(value="authContext",required=false) AuthContext supplied,
      @RequestAttribute(value=InstitutionTenantContextService.REQUEST_ATTRIBUTE,required=false)
          InstitutionTenantContext tenant) {
    AuthContext context=requireEducationStaff(supplied,tenant,"CONTENT_VIEW");
    var types=jdbc.queryForList("""
      select code,display_name,layout_schema_version,
             hierarchy_schema::text as hierarchy_schema_json,requires_curriculum_class
      from course_type_registry
      where status='ACTIVE'
      order by code
      """);
    for(Map<String,Object> type:types) {
      Object hierarchy=type.get("hierarchy_schema_json");
      if(hierarchy!=null) {
        try {
          type.put("hierarchy_schema",new tools.jackson.databind.ObjectMapper().readValue(
              String.valueOf(hierarchy),Object.class));
        } catch(Exception ex) {
          throw new IllegalStateException("The registered course-type schema is invalid.",ex);
        }
      }
      type.remove("hierarchy_schema_json");
    }
    return Map.of("courseTypes",types,"institutionId",tenant.institutionId());
  }

  @PostMapping("/courses")
  public Map<String,Object> createSourcedCourse(
      @RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext supplied,
      @RequestAttribute(value=InstitutionTenantContextService.REQUEST_ATTRIBUTE,required=false)
          InstitutionTenantContext tenant) {
    AuthContext context=requireEducationStaff(supplied,tenant,"CONTENT_CREATE");
    authorization.requirePermission(context,"CONTENT_EDIT");
    return courseAuthoring.createSourceCourse(
        tenant.institutionId(),tenant.tenantCode(),context.staffId(),null,body);
  }

  @PostMapping("/courses/{courseId}/draft-version")
  public Map<String,Object> createDraftVersion(
      @PathVariable long courseId,
      @RequestBody(required=false) Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext supplied,
      @RequestAttribute(value=InstitutionTenantContextService.REQUEST_ATTRIBUTE,required=false)
          InstitutionTenantContext tenant) {
    AuthContext context=requireEducationStaff(supplied,tenant,"CONTENT_CREATE");
    authorization.requirePermission(context,"CONTENT_EDIT");
    Map<String,Object> result=courseVersionService.forkReleasedVersion(
        courseId,tenant.institutionId(),context.staffId(),null);
    result.put("sourceInstitutionCode",tenant.tenantCode());
    result.put("requestedAction",body==null?"FORK_RELEASED_VERSION":
        String.valueOf(body.getOrDefault("action","FORK_RELEASED_VERSION")));
    return result;
  }

  private AuthContext requireEducationStaff(
      AuthContext supplied,InstitutionTenantContext tenant,String permission) {
    AuthContext context=authorization.requirePermission(supplied,permission);
    String workspace=AuthService.normalizeWorkspace(context.workspace());
    if(!"EDUCATION_ADMIN".equals(workspace) || context.staffId()==null) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN,
          "This operation requires an Education Admin staff session.");
    }
    if(tenant==null) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN,
          "This staff member has no active HRMS institution binding.");
    }
    Boolean bound=jdbc.queryForObject("""
      select exists(
        select 1 from institution_staff_binding b
        join institution_tenant t on t.id=b.institution_id and t.status='ACTIVE'
        join staff_account s on s.id=b.staff_id and s.active=true
        where b.staff_id=? and b.institution_id=? and b.status='ACTIVE'
      )
      """,Boolean.class,context.staffId(),tenant.institutionId());
    if(!Boolean.TRUE.equals(bound)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN,
          "The current institution does not match this staff member's active HRMS binding.");
    }
    return context;
  }
}
