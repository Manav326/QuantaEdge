package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.*;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class OpenAiTutorClientTest {
  @Test void enabledWithoutKeyRemainsUnconfigured(){
    var client=new OpenAiTutorClient(new ObjectMapper(),true,"","gpt-test","https://api.openai.com",100);
    assertTrue(client.isEnabled());
    assertFalse(client.isConfigured());
  }

  @Test void disabledWhenFeatureFlagOff(){
    var client=new OpenAiTutorClient(new ObjectMapper(),false,"test","gpt-test","https://api.openai.com",100);
    assertFalse(client.isEnabled());
    assertFalse(client.isConfigured());
  }
}
