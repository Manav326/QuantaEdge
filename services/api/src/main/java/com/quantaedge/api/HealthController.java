package com.quantaedge.api;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {
  @GetMapping("/api/v1/system/status")
  public Map<String, String> status() {
    return Map.of("service", "quantaedge-api", "status", "ok", "version", "0.1.0");
  }
}
