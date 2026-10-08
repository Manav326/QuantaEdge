package com.quantaedge.api;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component
public class TutorHealthIndicator implements HealthIndicator {
  private final OpenAiTutorClient tutor;
  public TutorHealthIndicator(OpenAiTutorClient tutor){this.tutor=tutor;}

  @Override
  public Health health(){
    if(tutor.isConfigured()){
      return Health.up().withDetail("configured",true).withDetail("model",tutor.model()).build();
    }
    return Health.down().withDetail("configured",false).withDetail("reason","AI tutor enabled without provider configuration").build();
  }
}
