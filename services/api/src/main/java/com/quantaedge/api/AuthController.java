package com.quantaedge.api;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
  private final AuthService auth;
  private final StaffAuditService staffAudit;
  private final boolean secureCookies;

  public AuthController(AuthService auth, StaffAuditService staffAudit,
      @Value("${app.auth.secure-cookies:true}") boolean secureCookies) {
    this.auth=auth; this.staffAudit=staffAudit; this.secureCookies=secureCookies;
  }

  @PostMapping("/request-otp")
  public Map<String,Object> requestOtp(@RequestBody Map<String,Object> body) {
    String mobile=String.valueOf(body.getOrDefault("mobile",""));
    String purpose=String.valueOf(body.getOrDefault("purpose","LOGIN"));
    String devCode=auth.requestOtp(mobile,purpose);
    var result=new LinkedHashMap<String,Object>();
    result.put("accepted",true); result.put("expiresInSeconds",300);
    if(devCode!=null) result.put("devCode",devCode);
    if ("STAFF_LOGIN".equals(purpose)) result.put("firstAccess",auth.isStaffFirstAccess(mobile));
    return result;
  }

  @PostMapping("/verify-otp")
  public ResponseEntity<Map<String,Object>> verifyOtp(@RequestBody Map<String,Object> body) {
    AuthContext context=auth.verifyOtp(String.valueOf(body.getOrDefault("mobile","")),
        String.valueOf(body.getOrDefault("otp","")),
        String.valueOf(body.getOrDefault("purpose","LOGIN")),
        body.get("displayName")==null?null:String.valueOf(body.get("displayName")),
        Boolean.TRUE.equals(body.get("firstAccess")),
        body.get("password")==null?null:String.valueOf(body.get("password")));
    String token=auth.issueToken(context);
    if(context.isEmployee()) staffAudit.recordAction(context,"/api/v1/auth/verify-otp","Staff sign-in succeeded.");
    return withCookie(token,context);
  }

  @PostMapping("/parent-login")
  public ResponseEntity<Map<String,Object>> parentLogin(@RequestBody Map<String,Object> body) {
    AuthContext context=auth.loginParent(String.valueOf(body.getOrDefault("mobile","")),
        String.valueOf(body.getOrDefault("password","")));
    return withCookie(auth.issueToken(context),context);
  }

  @PostMapping("/student-login")
  public ResponseEntity<Map<String,Object>> studentLogin(@RequestBody Map<String,Object> body) {
    AuthContext context=auth.loginStudentByParent(String.valueOf(body.getOrDefault("parentMobile","")),
        String.valueOf(body.getOrDefault("username","")),String.valueOf(body.getOrDefault("password","")));
    return withCookie(auth.issueToken(context),context);
  }

  @PostMapping("/preview-login")
  public ResponseEntity<Map<String,Object>> previewLogin() {
    AuthContext context=auth.previewLogin();
    return withCookie(auth.issueToken(context),context);
  }

  @PostMapping("/select-student")
  public ResponseEntity<Map<String,Object>> selectStudent() {
    return ResponseEntity.status(403).body(Map.of(
        "message","Parent profiles are read-only. Students must sign in with their own parent-created credentials."));
  }

  @PostMapping("/switch-parent")
  public ResponseEntity<Map<String,Object>> switchParent() {
    return ResponseEntity.status(403).body(Map.of(
        "message","Parent and student accounts are separate. Sign out and sign in to the correct account."));
  }

  @GetMapping("/me")
  public Map<String,Object> me(@RequestAttribute(value="authContext",required=false) AuthContext context) {
    if(context==null) throw new SecurityException("Authentication required");
    var result=new LinkedHashMap<String,Object>();
    result.put("authenticated",true);
    result.put("role",context.role());
    result.put("studentId",context.studentId());
    result.put("userId",context.userId());
    result.put("displayName",context.displayName());
    result.put("staffId",context.staffId());
    result.put("permissions",context.staffId()==null?java.util.List.of():auth.permissionsForStaff(context.staffId()));
    return result;
  }

  @PostMapping("/logout")
  public ResponseEntity<Map<String,Object>> logout(
      @CookieValue(value=AuthService.COOKIE,required=false) String token,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    auth.revoke(token);
    if(context!=null&&context.isEmployee()) staffAudit.recordAction(context,"/api/v1/auth/logout","Staff signed out.");
    ResponseCookie cookie=ResponseCookie.from(AuthService.COOKIE,"").httpOnly(true).secure(secureCookies).sameSite("Strict").path("/").maxAge(Duration.ZERO).build();
    return ResponseEntity.ok().header("Set-Cookie",cookie.toString()).body(Map.of("loggedOut",true));
  }

  private ResponseEntity<Map<String,Object>> withCookie(String token,AuthContext context) {
    ResponseCookie cookie=ResponseCookie.from(AuthService.COOKIE,token).httpOnly(true).secure(secureCookies).sameSite("Strict").path("/")
        .maxAge(Duration.ofHours(auth.getSessionHours())).build();
    return ResponseEntity.ok().header("Set-Cookie",cookie.toString())
        .body(Map.of("authenticated",true,"role",context.role(),
            "studentId",context.studentId()==null?0:context.studentId(),
            "userId",context.userId()==null?0:context.userId(),
            "staffId",context.staffId()==null?0:context.staffId(),
            "displayName",context.displayName()==null?"":context.displayName()));
  }
}
