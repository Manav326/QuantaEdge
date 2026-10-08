package com.quantaedge.api;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/guardians")
public class GuardianController {
  private final JdbcTemplate jdbc; private final AuthorizationService authorization; private final AuthService auth;
  public GuardianController(JdbcTemplate jdbc,AuthorizationService authorization,AuthService auth){this.jdbc=jdbc;this.authorization=authorization;this.auth=auth;}

  @GetMapping("/children")
  public List<Map<String,Object>> children(@RequestAttribute(value="authContext",required=false) AuthContext context){
    context=authorization.requireParent(context);
    return jdbc.queryForList("""
      select st.id,st.public_id,st.display_name,st.class_code,cc.display_name as class_name,st.board,st.language,
             gs.relationship,gs.consent_status
      from guardian_student gs join student st on st.id=gs.student_id
      join curriculum_class cc on cc.code=st.class_code
      where gs.guardian_user_id=? and gs.active=true order by st.created_at
      """,context.userId());
  }

  @PostMapping("/children")
  public Map<String,Object> createChild(@RequestAttribute(value="authContext",required=false) AuthContext context,
      @RequestBody Map<String,Object> body){
    context=authorization.requireParent(context);
    AuthContext child=auth.createChild(context.userId(),String.valueOf(body.getOrDefault("displayName","")),
        String.valueOf(body.getOrDefault("classCode","7")),String.valueOf(body.getOrDefault("language","hi")),
        String.valueOf(body.getOrDefault("pin","")));
    return jdbc.queryForMap("select public_id,display_name,class_code,board,language from student where id=?",child.studentId());
  }
}
