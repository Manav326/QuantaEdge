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
  private final boolean secureCookies;

  public AuthController(AuthService auth,@Value("${app.auth.secure-cookies:true}") boolean secureCookies) {
    this.auth=auth; this.secureCookies=secureCookies;
  }

  @PostMapping("/request-otp")
  public Map<String,Object> requestOtp(@RequestBody Map<String,Object> body) {
    String mobile=String.valueOf(body.getOrDefault("mobile",""));
    String purpose=String.valueOf(body.getOrDefault("purpose","LOGIN"));
    String devCode=auth.requestOtp(mobile,purpose);
    var result=new LinkedHashMap<String,Object>();
    result.put("accepted",true); result.put("expiresInSeconds",300);
    if(devCode!=null) result.put("devCode",devCode);
    return result;
  }

  @PostMapping("/verify-otp")
  public ResponseEntity<Map<String,Object>> verifyOtp(@RequestBody Map<String,Object> body) {
    AuthContext context=auth.verifyOtp(String.valueOf(body.getOrDefault("mobile","")),
        String.valueOf(body.getOrDefault("otp","")),
        body.get("displayName")==null?null:String.valueOf(body.get("displayName")));
    return withCookie(auth.issueToken(context),context);
  }

  @PostMapping("/student-login")
  public ResponseEntity<Map<String,Object>> studentLogin(@RequestBody Map<String,Object> body) {
    AuthContext context=auth.loginStudent(String.valueOf(body.getOrDefault("studentId","")),
        String.valueOf(body.getOrDefault("pin","")));
    return withCookie(auth.issueToken(context),context);
  }

  @PostMapping("/preview-login")
  public ResponseEntity<Map<String,Object>> previewLogin() {
    AuthContext context=auth.previewLogin();
    return withCookie(auth.issueToken(context),context);
  }

  @PostMapping("/select-student")
  public ResponseEntity<Map<String,Object>> selectStudent(
      @RequestAttribute(value="authContext",required=false) AuthContext context,
      @RequestBody Map<String,Object> body) {
    if(context==null || context.userId()==null) throw new SecurityException("Authentication required");
    AuthContext selected=auth.selectStudent(context.userId(),Long.parseLong(String.valueOf(body.get("studentId"))));
    return withCookie(auth.issueToken(selected),selected);
  }

  @PostMapping("/switch-parent")
  public ResponseEntity<Map<String,Object>> switchParent(
      @RequestAttribute(value="authContext",required=false) AuthContext context){
    if(context==null || context.userId()==null || context.studentId()==null)
      throw new SecurityException("Parent session unavailable");
    AuthContext parent=auth.contextForUser(context.userId());
    if(!"PARENT".equals(parent.role()) && !"ADMIN".equals(parent.role()))
      throw new SecurityException("Parent session unavailable");
    return withCookie(auth.issueToken(parent),parent);
  }

  @GetMapping("/me")
  public Map<String,Object> me(@RequestAttribute(value="authContext",required=false) AuthContext context) {
    if(context==null) throw new SecurityException("Authentication required");
    return Map.of("authenticated",true,"role",context.role(),"studentId",context.studentId(),"userId",context.userId(),"displayName",context.displayName());
  }

  @PostMapping("/logout")
  public ResponseEntity<Map<String,Object>> logout(@CookieValue(value=AuthService.COOKIE,required=false) String token) {
    auth.revoke(token);
    ResponseCookie cookie=ResponseCookie.from(AuthService.COOKIE,"").httpOnly(true).secure(secureCookies).sameSite("Strict").path("/").maxAge(Duration.ZERO).build();
    return ResponseEntity.ok().header("Set-Cookie",cookie.toString()).body(Map.of("loggedOut",true));
  }

  private ResponseEntity<Map<String,Object>> withCookie(String token,AuthContext context) {
    ResponseCookie cookie=ResponseCookie.from(AuthService.COOKIE,token).httpOnly(true).secure(secureCookies).sameSite("Lax").path("/")
        .maxAge(Duration.ofDays(7)).build();
    return ResponseEntity.ok().header("Set-Cookie",cookie.toString())
        .body(Map.of("authenticated",true,"role",context.role(),
            "studentId",context.studentId()==null?0:context.studentId(),
            "userId",context.userId()==null?0:context.userId(),
            "displayName",context.displayName()==null?"":context.displayName()));
  }
}
