package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class TutorProviderRouterTest {
  private HttpServer server(int status, String responseBody, AtomicInteger requests) throws Exception {
    HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    server.createContext("/",exchange -> {
      requests.incrementAndGet();
      exchange.getRequestBody().readAllBytes();
      byte[] bytes=responseBody.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type","application/json");
      exchange.sendResponseHeaders(status,bytes.length);
      exchange.getResponseBody().write(bytes);
      exchange.close();
    });
    server.start();
    return server;
  }

  private MockEnvironment properties(int primaryPort, int fallbackPort, boolean fallbackEnabled, int minChars) {
    return new MockEnvironment()
        .withProperty("app.ai-tutor.enabled","true")
        .withProperty("app.ai-tutor.max-output-tokens","100")
        .withProperty("app.ai-tutor.min-output-chars",String.valueOf(minChars))
        .withProperty("app.ai-tutor.primary.api-key","local-test-key")
        .withProperty("app.ai-tutor.primary.model","private-test-model")
        .withProperty("app.ai-tutor.primary.base-url","http://127.0.0.1:"+primaryPort)
        .withProperty("app.ai-tutor.primary.api-mode","CHAT_COMPLETIONS")
        .withProperty("app.ai-tutor.fallback.enabled",String.valueOf(fallbackEnabled))
        .withProperty("app.ai-tutor.fallback.api-key","api-test-key")
        .withProperty("app.ai-tutor.fallback.model","fallback-test-model")
        .withProperty("app.ai-tutor.fallback.base-url","http://127.0.0.1:"+fallbackPort)
        .withProperty("app.ai-tutor.fallback.api-mode","RESPONSES");
  }

  private String chatReply(String text) {
    return "{\"id\":\"local-id\",\"choices\":[{\"message\":{\"content\":\""+text+"\"}}],"
        +"\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":12}}";
  }

  private String apiReply(String text) {
    return "{\"id\":\"api-id\",\"output_text\":\""+text+"\","
        +"\"usage\":{\"input_tokens\":10,\"output_tokens\":12}}";
  }

  @Test
  void reportsAvailableWhenPrivateProviderIsReachableAndCachesTheProbe() throws Exception {
    AtomicInteger primaryRequests=new AtomicInteger();
    AtomicInteger fallbackRequests=new AtomicInteger();
    HttpServer primary=server(200,chatReply("private model is reachable"),primaryRequests);
    HttpServer fallback=server(200,apiReply("fallback should not be probed"),fallbackRequests);
    try {
      TutorProviderRouter router=new TutorProviderRouter(new ObjectMapper(),
          properties(primary.getAddress().getPort(),fallback.getAddress().getPort(),true,8));
      assertTrue(router.isAvailable());
      assertTrue(router.isAvailable());
      assertEquals(1,primaryRequests.get());
      assertEquals(0,fallbackRequests.get());
    } finally { primary.stop(0); fallback.stop(0); }
  }

  @Test
  void reportsUnavailableWhenPrivateProviderFailsAndFallbackIsDisabled() throws Exception {
    AtomicInteger primaryRequests=new AtomicInteger();
    AtomicInteger fallbackRequests=new AtomicInteger();
    HttpServer primary=server(503,"{\"error\":{\"message\":\"private model unavailable\"}}",primaryRequests);
    HttpServer fallback=server(200,apiReply("fallback should remain unused"),fallbackRequests);
    try {
      TutorProviderRouter router=new TutorProviderRouter(new ObjectMapper(),
          properties(primary.getAddress().getPort(),fallback.getAddress().getPort(),false,8));
      assertFalse(router.isAvailable());
      assertEquals(1,primaryRequests.get());
      assertEquals(0,fallbackRequests.get());
    } finally { primary.stop(0); fallback.stop(0); }
  }

  @Test
  void usesPrivateModelFirstAndDoesNotCallFallbackOnSuccess() throws Exception {
    AtomicInteger primaryRequests=new AtomicInteger();
    AtomicInteger fallbackRequests=new AtomicInteger();
    HttpServer primary=server(200,chatReply("Let's work through the first step carefully."),primaryRequests);
    HttpServer fallback=server(200,apiReply("Fallback answer with enough detail."),fallbackRequests);
    try {
      TutorProviderRouter router=new TutorProviderRouter(new ObjectMapper(),
          properties(primary.getAddress().getPort(),fallback.getAddress().getPort(),true,8));
      TutorReply reply=router.generate("tutor rules","learner question");
      assertEquals("Let's work through the first step carefully.",reply.text());
      assertEquals("in-house/private-test-model",reply.model());
      assertEquals(1,primaryRequests.get());
      assertEquals(0,fallbackRequests.get());
    } finally { primary.stop(0); fallback.stop(0); }
  }

  @Test
  void neverCallsExternalFallbackUnlessExplicitlyEnabled() throws Exception {
    AtomicInteger primaryRequests=new AtomicInteger();
    AtomicInteger fallbackRequests=new AtomicInteger();
    HttpServer primary=server(503,"{\"error\":{\"message\":\"private model unavailable\"}}",primaryRequests);
    HttpServer fallback=server(200,apiReply("Fallback answer with enough detail."),fallbackRequests);
    try {
      TutorProviderRouter router=new TutorProviderRouter(new ObjectMapper(),
          properties(primary.getAddress().getPort(),fallback.getAddress().getPort(),false,8));
      assertThrows(TutorProviderException.class,()->router.generate("rules","question"));
      assertEquals(1,primaryRequests.get());
      assertEquals(0,fallbackRequests.get());
    } finally { primary.stop(0); fallback.stop(0); }
  }

  @Test
  void fallsBackAfterPrivateModelProviderFailureWhenEnabled() throws Exception {
    AtomicInteger primaryRequests=new AtomicInteger();
    AtomicInteger fallbackRequests=new AtomicInteger();
    HttpServer primary=server(503,"{\"error\":{\"message\":\"private model unavailable\"}}",primaryRequests);
    HttpServer fallback=server(200,apiReply("Let's split the problem into two clear steps."),fallbackRequests);
    try {
      TutorProviderRouter router=new TutorProviderRouter(new ObjectMapper(),
          properties(primary.getAddress().getPort(),fallback.getAddress().getPort(),true,8));
      TutorReply reply=router.generate("rules","question");
      assertEquals("Let's split the problem into two clear steps.",reply.text());
      assertEquals("api-fallback/fallback-test-model",reply.model());
      assertEquals(1,primaryRequests.get());
      assertEquals(1,fallbackRequests.get());
    } finally { primary.stop(0); fallback.stop(0); }
  }

  @Test
  void fallsBackWhenPrivateResponseFailsConfiguredStructuralQualityGate() throws Exception {
    AtomicInteger primaryRequests=new AtomicInteger();
    AtomicInteger fallbackRequests=new AtomicInteger();
    HttpServer primary=server(200,chatReply("No."),primaryRequests);
    HttpServer fallback=server(200,apiReply("Let's first identify what the question is asking."),fallbackRequests);
    try {
      TutorProviderRouter router=new TutorProviderRouter(new ObjectMapper(),
          properties(primary.getAddress().getPort(),fallback.getAddress().getPort(),true,8));
      TutorReply reply=router.generate("rules","question");
      assertEquals("Let's first identify what the question is asking.",reply.text());
      assertEquals("api-fallback/fallback-test-model",reply.model());
      assertEquals(1,fallbackRequests.get());
    } finally { primary.stop(0); fallback.stop(0); }
  }

  @Test
  void usesPrivateModelResponseWhenFallbackIsDisabledEvenIfStructuralGateWouldRejectIt() throws Exception {
    AtomicInteger primaryRequests=new AtomicInteger();
    AtomicInteger fallbackRequests=new AtomicInteger();
    HttpServer primary=server(200,chatReply("No."),primaryRequests);
    HttpServer fallback=server(200,apiReply("Fallback answer with enough detail."),fallbackRequests);
    try {
      TutorProviderRouter router=new TutorProviderRouter(new ObjectMapper(),
          properties(primary.getAddress().getPort(),fallback.getAddress().getPort(),false,8));
      TutorReply reply=router.generate("rules","question");
      assertEquals("in-house/private-test-model",reply.model());
      assertEquals("No.",reply.text());
      assertEquals(0,fallbackRequests.get());
    } finally { primary.stop(0); fallback.stop(0); }
  }
}
