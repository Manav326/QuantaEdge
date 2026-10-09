package com.quantaedge.api;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class HealthController {
  private final OpenAiTutorClient tutor;
  private final boolean demoSeed;
  private final String adminMobiles;

  public HealthController(OpenAiTutorClient tutor,
      @Value("${app.demo-seed:false}") boolean demoSeed,
      @Value("${app.auth.admin-mobiles:}") String adminMobiles) {
    this.tutor=tutor;
    this.demoSeed=demoSeed;
    this.adminMobiles=adminMobiles;
  }

  @GetMapping("/api/v1/system/status")
  public ResponseEntity<Map<String,Object>> status() {
    boolean adminConfigured=demoSeed || (adminMobiles!=null && !adminMobiles.isBlank());
    String otpUrl=System.getenv("APP_OTP_PROVIDER_URL");
    String otpToken=System.getenv("APP_OTP_PROVIDER_TOKEN");
    boolean otpConfigured=demoSeed
        || (otpUrl!=null && !otpUrl.isBlank() && otpToken!=null && !otpToken.isBlank());

    boolean aiConfigured=!tutor.isEnabled() || tutor.isConfigured();
    boolean healthy=adminConfigured && otpConfigured && aiConfigured;

    var result=new LinkedHashMap<String,Object>();
    result.put("service","quantaedge-api");
    result.put("status",healthy ? "ok" : "degraded");
    result.put("version","0.1.0");
    result.put("aiTutorEnabled",tutor.isEnabled());
    result.put("aiTutorConfigured",tutor.isConfigured());
    result.put("adminConfigured",adminConfigured);
    result.put("otpConfigured",otpConfigured);
    if(!healthy) return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(result);
    return ResponseEntity.ok(result);
  }
}
