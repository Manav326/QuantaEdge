package com.quantaedge.api;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
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
  public Map<String,Object> requestOtp(
      @RequestBody Map<String,Object> body,
      @RequestHeader(value=AuthService.WORKSPACE_HEADER, required=false) String workspaceHeader) {
    String mobile=String.valueOf(body.getOrDefault("mobile",""));
    String purpose=String.valueOf(body.getOrDefault("purpose","LOGIN"));
    if ("HRMS".equals(AuthService.normalizeWorkspace(workspaceHeader))) {
      throw new SecurityException("HRMS uses username and password sign-in, not OTP.");
    }
    String devCode=auth.requestOtp(mobile,purpose);
    var result=new LinkedHashMap<String,Object>();
    result.put("accepted",true); result.put("expiresInSeconds",300);
    if(devCode!=null) result.put("devCode",devCode);
    if ("STAFF_LOGIN".equals(purpose)) result.put("firstAccess",auth.isStaffFirstAccess(mobile));
    return result;
  }

  @PostMapping("/learner-transition/request-otp")
  public Map<String,Object> requestLearnerTransitionOtp(
      @RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    if(context==null || !context.isStudent() || context.studentId()==null)
      throw new SecurityException("An authenticated student session is required.");
    if(!Boolean.TRUE.equals(body.get("consentAccepted")))
      throw new SecurityException("Explicit confirmation is required to request independent account conversion.");
    String devCode=auth.requestStudentTransitionOtp(context.studentId(),String.valueOf(body.getOrDefault("mobile","")));
    var result=new LinkedHashMap<String,Object>();
    result.put("accepted",true); result.put("expiresInSeconds",300);
    if(devCode!=null) result.put("devCode",devCode);
    return result;
  }

  @PostMapping("/verify-otp")
  public ResponseEntity<Map<String,Object>> verifyOtp(
      @RequestBody Map<String,Object> body,
      @RequestHeader(value=AuthService.WORKSPACE_HEADER, required=false) String workspaceHeader,
      @RequestHeader(value="X-QE-Client", required=false) String clientHeader) {
    if ("HRMS".equals(AuthService.normalizeWorkspace(workspaceHeader))) {
      throw new SecurityException("HRMS uses username and password sign-in, not OTP.");
    }
    AuthContext context=auth.verifyOtp(String.valueOf(body.getOrDefault("mobile","")),
        String.valueOf(body.getOrDefault("otp","")),
        String.valueOf(body.getOrDefault("purpose","LOGIN")),
        body.get("displayName")==null?null:String.valueOf(body.get("displayName")),
        Boolean.TRUE.equals(body.get("firstAccess")),
        body.get("password")==null?null:String.valueOf(body.get("password")));
    String workspace;
    if (context.isEmployee()) {
      workspace = AuthService.normalizeWorkspace(workspaceHeader);
      if (workspace == null) workspace = "STAFF";
      if ("HRMS".equals(workspace)) {
        throw new SecurityException("HRMS uses username and password sign-in, not OTP.");
      }
      auth.requireEducationAccess(context.staffId());
      staffAudit.recordAction(context,"/api/v1/auth/verify-otp","Education Admin staff sign-in succeeded.");
    } else if (context.isStudent()) {
      workspace = "STUDENT";
    } else if (context.isParent()) {
      workspace = "PARENT";
    } else {
      workspace = "LEARNING";
    }
    boolean androidClient = isAndroidClient(clientHeader);
    return withCookie(auth.issueSession(context, workspace, androidClient), workspace, androidClient);
  }

  @PostMapping("/hrms-login")
  public ResponseEntity<Map<String,Object>> hrmsLogin(@RequestBody Map<String,Object> body) {
    AuthContext context = auth.loginHrms(
        String.valueOf(body.getOrDefault("username", "")),
        String.valueOf(body.getOrDefault("password", "")));
    staffAudit.recordAction(context, "/api/v1/auth/hrms-login", "HRMS password sign-in succeeded.");
    return withCookie(auth.issueSession(context, "HRMS"), "HRMS");
  }

  @PostMapping("/hrms/change-password")
  public ResponseEntity<Map<String,Object>> changeHrmsPassword(
      @RequestBody Map<String,Object> body,
      HttpServletRequest request,
      @RequestAttribute(value="authContext", required=false) AuthContext context) {
    if (context == null || !context.isEmployee()
        || !"HRMS".equals(AuthService.normalizeWorkspace(
            (String) request.getAttribute("authWorkspace")))) {
      throw new SecurityException("An authenticated HRMS employee session is required.");
    }
    auth.changeHrmsPassword(context.staffId(),
        String.valueOf(body.getOrDefault("currentPassword", "")),
        String.valueOf(body.getOrDefault("newPassword", "")),
        cookieValue(request, AuthService.HRMS_COOKIE));
    staffAudit.recordAction(context, "/api/v1/auth/hrms/change-password", "HRMS password changed.");
    return ResponseEntity.ok(Map.of("changed", true, "mustChangePassword", false));
  }

  @PostMapping("/parent-login")
  public ResponseEntity<Map<String,Object>> parentLogin(
      @RequestBody Map<String,Object> body,
      @RequestHeader(value="X-QE-Client", required=false) String clientHeader) {
    AuthContext context=auth.loginParent(String.valueOf(body.getOrDefault("mobile","")),
        String.valueOf(body.getOrDefault("password","")));
    boolean androidClient = isAndroidClient(clientHeader);
    return withCookie(auth.issueSession(context, "PARENT", androidClient), "PARENT", androidClient);
  }

  @PostMapping("/student-login")
  public ResponseEntity<Map<String,Object>> studentLogin(
      @RequestBody Map<String,Object> body,
      @RequestHeader(value="X-QE-Client", required=false) String clientHeader) {
    AuthContext context=auth.loginStudentByParent(String.valueOf(body.getOrDefault("parentMobile","")),
        String.valueOf(body.getOrDefault("username","")),String.valueOf(body.getOrDefault("password","")));
    boolean androidClient = isAndroidClient(clientHeader);
    return withCookie(auth.issueSession(context, "STUDENT", androidClient), "STUDENT", androidClient);
  }

  @PostMapping("/learner-login")
  public ResponseEntity<Map<String,Object>> learnerLogin(
      @RequestBody Map<String,Object> body,
      @RequestHeader(value="X-QE-Client", required=false) String clientHeader) {
    AuthContext context=auth.loginIndependentLearner(String.valueOf(body.getOrDefault("mobile","")),
        String.valueOf(body.getOrDefault("password","")));
    boolean androidClient = isAndroidClient(clientHeader);
    return withCookie(auth.issueSession(context,"STUDENT",androidClient),"STUDENT",androidClient);
  }

  @PostMapping("/preview-login")
  public ResponseEntity<Map<String,Object>> previewLogin(
      @RequestHeader(value="X-QE-Client", required=false) String clientHeader) {
    AuthContext context=auth.previewLogin();
    String workspace = AuthService.workspaceForContext(context);
    boolean androidClient = isAndroidClient(clientHeader);
    return withCookie(auth.issueSession(context, workspace, androidClient), workspace, androidClient);
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
  public Map<String,Object> me(
      @RequestAttribute(value="authContext",required=false) AuthContext context,
      @RequestHeader(value=AuthService.WORKSPACE_HEADER, required=false) String workspaceHeader) {
    if(context==null) throw new SecurityException("Authentication required");
    String workspace = AuthService.normalizeWorkspace(workspaceHeader);
    var result=new LinkedHashMap<String,Object>();
    result.put("authenticated",true);
    result.put("publicUserId",auth.publicUserId(context));
    result.put("role",context.role());
    result.put("studentId",context.studentId());
    result.put("userId",context.userId());
    result.put("displayName",context.displayName());
    result.put("staffId",context.staffId());
    result.put("workspace", workspace == null ? AuthService.workspaceForContext(context) : workspace);
    result.put("permissions",context.staffId()==null?java.util.List.of():
        auth.permissionsForStaff(context.staffId(), workspace == null ? context.workspace() : workspace));
    result.put("mustChangePassword",context.staffId()!=null && "HRMS".equals(workspace)
        && auth.staffMustChangePassword(context.staffId()));
    return result;
  }

  @PostMapping("/refresh")
  public ResponseEntity<Map<String,Object>> refresh(
      @RequestHeader(value=AuthService.WORKSPACE_HEADER,required=false) String workspaceHeader,
      @RequestHeader(value="X-QE-Client", required=false) String clientHeader,
      HttpServletRequest request) {
    String workspace = AuthService.normalizeWorkspace(workspaceHeader);
    if (workspace == null) {
      throw new SecurityException("Application workspace context is required to refresh a session.");
    }
    String refreshToken = firstNonBlank(
        cookieValue(request, AuthService.refreshCookieForWorkspace(workspace)),
        cookieValue(request, ("PARENT".equals(workspace) || "STUDENT".equals(workspace))
            ? AuthService.LEARNING_REFRESH_COOKIE : AuthService.REFRESH_COOKIE),
        cookieValue(request, AuthService.REFRESH_COOKIE));
    boolean androidClient = isAndroidClient(clientHeader);
    return withCookie(auth.refreshSession(refreshToken, workspace), workspace, androidClient);
  }

  @PostMapping("/logout")
  public ResponseEntity<Map<String,Object>> logout(
      @RequestHeader(value=AuthService.WORKSPACE_HEADER,required=false) String workspaceHeader,
      HttpServletRequest request,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    String workspace = AuthService.normalizeWorkspace(workspaceHeader);
    if (workspace == null) {
      throw new SecurityException("Application workspace context is required to sign out safely.");
    }

    int legacyRevoked = auth.revokeForWorkspace(
        cookieValue(request, AuthService.sessionCookieForWorkspace(workspace)),
        cookieValue(request, AuthService.refreshCookieForWorkspace(workspace)), workspace);

    // Legacy cookies are revoked only when their stored session identity matches this
    // workspace. This lets older clients sign out safely without affecting a different portal.
    legacyRevoked += auth.revokeForWorkspace(
        cookieValue(request, AuthService.COOKIE),
        cookieValue(request, AuthService.REFRESH_COOKIE), workspace);

    boolean learnerWorkspace = "PARENT".equals(workspace) || "STUDENT".equals(workspace);
    if (learnerWorkspace) {
      legacyRevoked += auth.revokeForWorkspace(
          cookieValue(request, AuthService.LEARNING_COOKIE),
          cookieValue(request, AuthService.LEARNING_REFRESH_COOKIE), workspace);
    }

    if (context != null && context.isEmployee()) {
      staffAudit.recordAction(context, "/api/v1/auth/logout", "Staff signed out.");
    }

    ResponseEntity.BodyBuilder result = ResponseEntity.ok();
    result.header("Set-Cookie", expiredCookie(AuthService.sessionCookieForWorkspace(workspace)).toString());
    result.header("Set-Cookie", expiredCookie(AuthService.refreshCookieForWorkspace(workspace)).toString());
    if (legacyRevoked > 0) {
      result.header("Set-Cookie", expiredCookie(AuthService.COOKIE).toString());
      result.header("Set-Cookie", expiredCookie(AuthService.REFRESH_COOKIE).toString());
    }
    if (learnerWorkspace) {
      // The former learner cookies are shared, so expire them only after scoped server-side
      // revocation checks have been attempted. Other application cookies remain untouched.
      result.header("Set-Cookie", expiredCookie(AuthService.LEARNING_COOKIE).toString());
      result.header("Set-Cookie", expiredCookie(AuthService.LEARNING_REFRESH_COOKIE).toString());
    }
    return result.body(Map.of("loggedOut", true));
  }

  private ResponseEntity<Map<String,Object>> withCookie(AuthService.SessionTokens tokens) {
    return withCookie(tokens, AuthService.workspaceForContext(tokens.context()));
  }

  private ResponseEntity<Map<String,Object>> withCookie(AuthService.SessionTokens tokens, String requestedWorkspace) {
    return withCookie(tokens, requestedWorkspace, false);
  }

  private ResponseEntity<Map<String,Object>> withCookie(
      AuthService.SessionTokens tokens, String requestedWorkspace, boolean androidClient) {
    AuthContext context=tokens.context();
    String workspace = AuthService.normalizeWorkspace(requestedWorkspace);
    if (workspace == null) workspace = AuthService.workspaceForContext(context);
    boolean persistentLearnerSession = androidClient
        && ("LEARNING".equals(workspace) || "PARENT".equals(workspace) || "STUDENT".equals(workspace));
    Duration refreshLifetime = persistentLearnerSession
        ? Duration.ofDays(auth.getLearningSessionDays())
        : Duration.ofHours(auth.getSessionHours());
    ResponseCookie cookie=ResponseCookie.from(AuthService.sessionCookieForWorkspace(workspace),tokens.accessToken())
        .httpOnly(true).secure(secureCookies).sameSite("Strict").path("/")
        .maxAge(Duration.ofMinutes(AuthService.ACCESS_TOKEN_MINUTES)).build();
    ResponseCookie refreshCookie=ResponseCookie.from(AuthService.refreshCookieForWorkspace(workspace),tokens.refreshToken())
        .httpOnly(true).secure(secureCookies).sameSite("Strict").path("/")
        .maxAge(refreshLifetime).build();
    var body = new LinkedHashMap<String,Object>();
    body.put("authenticated",true);
    body.put("publicUserId",auth.publicUserId(context));
    body.put("role",context.role());
    body.put("studentId",context.studentId()==null?0:context.studentId());
    body.put("userId",context.userId()==null?0:context.userId());
    body.put("staffId",context.staffId()==null?0:context.staffId());
    body.put("displayName",context.displayName()==null?"":context.displayName());
    body.put("workspace",workspace);
    body.put("mustChangePassword",context.staffId()!=null && "HRMS".equals(workspace)
        && auth.staffMustChangePassword(context.staffId()));
    return ResponseEntity.ok().header("Set-Cookie",cookie.toString()).header("Set-Cookie",refreshCookie.toString())
        .body(body);
  }

  private boolean isAndroidClient(String clientHeader) {
    return clientHeader != null && "ANDROID".equalsIgnoreCase(clientHeader.trim());
  }

  private ResponseCookie expiredCookie(String name) {
    return ResponseCookie.from(name,"").httpOnly(true).secure(secureCookies).sameSite("Strict").path("/")
        .maxAge(Duration.ZERO).build();
  }

  private String cookieValue(HttpServletRequest request, String name) {
    if (request.getCookies() == null) return null;
    for (Cookie cookie : request.getCookies()) {
      if (name.equals(cookie.getName()) && cookie.getValue() != null && !cookie.getValue().isBlank()) {
        return cookie.getValue();
      }
    }
    return null;
  }

  private String firstNonBlank(String... values) {
    for (String value : values) if (value != null && !value.isBlank()) return value;
    return null;
  }
}
