package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class OpenAiTutorClientHttpTest {
  private HttpServer server(String response, int status, AtomicReference<String> requestBody,
      AtomicReference<String> authorization) throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/v1/responses", exchange -> {
      requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
      byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(status, bytes.length);
      exchange.getResponseBody().write(bytes);
      exchange.close();
    });
    server.start();
    return server;
  }

  @Test
  void sendsPromptAndReturnsParsedTutorReply() throws Exception {
    AtomicReference<String> requestBody = new AtomicReference<>();
    AtomicReference<String> authorization = new AtomicReference<>();
    HttpServer server = server(
        """
        {"id":"resp_test","output_text":"नमस्ते! चलिए चरणों में समझते हैं।","usage":{"input_tokens":12,"output_tokens":9}}
        """, 200, requestBody, authorization);
    try {
      var client = new OpenAiTutorClient(new ObjectMapper(), true, "test-key", "test-model",
          "http://127.0.0.1:" + server.getAddress().getPort(), 100);
      TutorReply reply = client.generate("Hindi tutor rules", "2 + 2 कितना है?");

      assertEquals("नमस्ते! चलिए चरणों में समझते हैं।", reply.text());
      assertEquals("resp_test", reply.providerResponseId());
      assertEquals(12, reply.inputTokens());
      assertEquals(9, reply.outputTokens());
      assertEquals("Bearer test-key", authorization.get());
      assertTrue(requestBody.get().contains("Hindi tutor rules"));
      assertTrue(requestBody.get().contains("2 + 2 कितना है?"));
      assertTrue(requestBody.get().contains("test-model"));
    } finally {
      server.stop(0);
    }
  }

  @Test
  void providerHttpErrorIsReportedAsTutorProviderFailure() throws Exception {
    AtomicReference<String> requestBody = new AtomicReference<>();
    AtomicReference<String> authorization = new AtomicReference<>();
    HttpServer server = server(
        """
        {"error":{"message":"simulated provider outage"}}
        """, 503, requestBody, authorization);
    try {
      var client = new OpenAiTutorClient(new ObjectMapper(), true, "test-key", "test-model",
          "http://127.0.0.1:" + server.getAddress().getPort(), 100);
      TutorProviderException error = assertThrows(TutorProviderException.class,
          () -> client.generate("rules", "question"));
      assertTrue(error.getMessage().contains("simulated provider outage"));
    } finally {
      server.stop(0);
    }
  }

  @Test
  void emptyProviderReplyIsRejected() throws Exception {
    AtomicReference<String> requestBody = new AtomicReference<>();
    AtomicReference<String> authorization = new AtomicReference<>();
    HttpServer server = server(
        """
        {"id":"resp_empty","output_text":"  ","usage":{"input_tokens":1,"output_tokens":0}}
        """, 200, requestBody, authorization);
    try {
      var client = new OpenAiTutorClient(new ObjectMapper(), true, "test-key", "test-model",
          "http://127.0.0.1:" + server.getAddress().getPort(), 100);
      TutorProviderException error = assertThrows(TutorProviderException.class,
          () -> client.generate("rules", "question"));
      assertTrue(error.getMessage().contains("no text"));
    } finally {
      server.stop(0);
    }
  }
}
