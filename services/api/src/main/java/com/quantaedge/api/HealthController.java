package com.quantaedge.api;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class HealthController {
  private final OpenAiTutorClient tutor;
  public HealthController(OpenAiTutorClient tutor){this.tutor=tutor;}

  @GetMapping("/api/v1/system/status")
  public ResponseEntity<Map<String,Object>> status() {
    var result=new LinkedHashMap<String,Object>();
    result.put("service","quantaedge-api");
    result.put("status",tutor.isEnabled() && !tutor.isConfigured() ? "degraded" : "ok");
    result.put("version","0.1.0");
    result.put("aiTutorEnabled",tutor.isEnabled());
    result.put("aiTutorConfigured",tutor.isConfigured());
    if(tutor.isEnabled() && !tutor.isConfigured()) return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(result);
    return ResponseEntity.ok(result);
  }
}
