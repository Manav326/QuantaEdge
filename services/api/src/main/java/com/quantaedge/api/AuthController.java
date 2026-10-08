package com.quantaedge.api;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
  private final AuthService auth;

  public AuthController(AuthService auth) { this.auth = auth; }

  @PostMapping("/request-otp")
  public Map<String,Object> requestOtp(@RequestBody Map<String,Object> body) {
    String mobile=String.valueOf(body.getOrDefault("mobile",""));
    String purpose=String.valueOf(body.getOrDefault("purpose","LOGIN"));
    String devCode=auth.requestOtp(mobile,purpose);
    var result=new LinkedHashMap<String,Object>();
    result.put("accepted",true);
    result.put("expiresInSeconds",300);
    if(devCode!=null) result.put("devCode",devCode);
    return result;
  }

  @PostMapping("/verify-otp")
  public ResponseEntity<Map<String,Object>> verifyOtp(@RequestBody Map<String,Object> body) {
    AuthContext context=auth.verifyOtp(
        String.valueOf(body.getOrDefault("mobile","")),
        String.valueOf(body.getOrDefault("otp","")),
        body.get("displayName")==null?null:String.valueOf(body.get("displayName")));
    String token=auth.issueToken(context);
    return withCookie(token,context);
  }

  @PostMapping("/student-login")
  public ResponseEntity<Map<String,Object>> studentLogin(@RequestBody Map<String,Object> body) {
    AuthContext context=auth.loginStudent(
        String.valueOf(body.getOrDefault("studentId","")),
        String.valueOf(body.getOrDefault("pin","")));
    String token=auth.issueToken(context);
    return withCookie(token,context);
  }

  @PostMapping("/preview-login")
  public ResponseEntity<Map<String,Object>> previewLogin() {
    AuthContext context=auth.previewLogin();
    String token=auth.issueToken(context);
    return withCookie(token,context);
  }

  @GetMapping("/me")
  public Map<String,Object> me(@RequestAttribute(value="authContext",required=false) AuthContext context) {
    if (context==null) throw new IllegalStateException("Authentication required");
    return Map.of("authenticated",true,"role",context.role(),"studentId",context.studentId(),"userId",context.userId(),"displayName",context.displayName());
  }

  @PostMapping("/logout")
  public ResponseEntity<Map<String,Object>> logout(@CookieValue(value=AuthService.COOKIE,required=false) String token) {
    auth.revoke(token);
    ResponseCookie cookie=ResponseCookie.from(AuthService.COOKIE,"").httpOnly(true).secure(false).sameSite("Lax").path("/").maxAge(Duration.ZERO).build();
    return ResponseEntity.ok().header("Set-Cookie",cookie.toString()).body(Map.of("loggedOut",true));
  }

  private ResponseEntity<Map<String,Object>> withCookie(String token,AuthContext context) {
    ResponseCookie cookie=ResponseCookie.from(AuthService.COOKIE,token)
        .httpOnly(true).secure(false).sameSite("Lax").path("/")
        .maxAge(Duration.ofDays(7)).build();
    return ResponseEntity.ok().header("Set-Cookie",cookie.toString())
        .body(Map.of("authenticated",true,"role",context.role(),"studentId",context.studentId(),"userId",context.userId()));
  }
}
