package com.quantaedge.api;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;

public class OpenAiTutorClient {
  // A cold local Ollama model can spend most of the first request loading weights.
  // Keep this longer than the model-load + prompt-evaluation time on CPU-only machines.
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(120);
  private final ObjectMapper mapper;
  private final HttpClient http;
  private final boolean enabled;
  private final String apiKey;
  private final String model;
  private final String baseUrl;
  private final int maxOutputTokens;
  private final String apiMode;

  /** Compatibility constructor for existing tests and callers. New wiring should pass apiMode explicitly. */
  public OpenAiTutorClient(ObjectMapper mapper, boolean enabled, String apiKey, String model,
      String baseUrl, int maxOutputTokens) {
    this(mapper, enabled, apiKey, model, baseUrl, maxOutputTokens, inferApiMode(baseUrl));
  }

  public OpenAiTutorClient(ObjectMapper mapper, boolean enabled, String apiKey, String model,
      String baseUrl, int maxOutputTokens, String apiMode) {
    this.mapper=mapper; this.enabled=enabled; this.apiKey=apiKey; this.model=model;
    this.baseUrl=baseUrl==null?"":baseUrl.replaceAll("/+$","");
    this.maxOutputTokens=maxOutputTokens;
    this.apiMode=normalizeApiMode(apiMode);
    this.http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
  }

  private static String inferApiMode(String baseUrl) {
    String url=baseUrl==null?"":baseUrl.toLowerCase(java.util.Locale.ROOT);
    return url.contains("ollama") || url.matches(".*:11434(?:/.*)?$")
        ? "CHAT_COMPLETIONS" : "RESPONSES";
  }

  private static String normalizeApiMode(String raw) {
    String mode=raw==null?"":raw.trim().toUpperCase(java.util.Locale.ROOT).replace('-', '_');
    if (!"CHAT_COMPLETIONS".equals(mode) && !"RESPONSES".equals(mode)) {
      throw new IllegalArgumentException("Tutor API mode must be CHAT_COMPLETIONS or RESPONSES");
    }
    return mode;
  }

  public boolean isEnabled(){return enabled;}
  public boolean isConfigured(){return enabled && apiKey!=null && !apiKey.isBlank() && !baseUrl.isBlank();}
  public String model(){return model;}

  /** Lightweight OpenAI-compatible readiness probe; it never sends learner content. */
  public boolean isReachable(){
    if(!isConfigured()) return false;
    try{
      HttpRequest req=HttpRequest.newBuilder()
          .uri(URI.create(baseUrl+"/v1/models"))
          .timeout(Duration.ofSeconds(2))
          .header("Authorization","Bearer "+apiKey)
          .header("Accept","application/json")
          .GET().build();
      HttpResponse<String> response=http.send(req,HttpResponse.BodyHandlers.ofString());
      if(response.statusCode()/100!=2) return false;

      // A healthy Ollama/OpenAI-compatible endpoint is not enough: /v1/models
      // can be reachable while the configured local model is missing. Treat that
      // as unavailable before offering Tutor, rather than letting the first learner
      // message fail with a generic provider 5xx.
      JsonNode catalog=mapper.readTree(response.body()).path("data");
      if(!catalog.isArray()) return false;
      for(JsonNode item:catalog) {
        String id=item.path("id").asText("");
        String name=item.path("name").asText("");
        if(model.equalsIgnoreCase(id.trim()) || model.equalsIgnoreCase(name.trim())) return true;
      }
      return false;
    }catch(Exception ignored){return false;}
  }

  public TutorReply generate(String instructions,String input){
    if(!isConfigured()) throw new TutorUnavailableException("AI tutor is not configured");
    try{
      boolean chatCompletions = "CHAT_COMPLETIONS".equals(apiMode);
      var root=mapper.createObjectNode();
      root.put("model",model);
      if(chatCompletions) {
        root.put("max_tokens",maxOutputTokens);
        root.put("stream",false);
        var messages=root.putArray("messages");
        messages.addObject().put("role","system").put("content",instructions);
        messages.addObject().put("role","user").put("content",input);
      } else {
        root.put("instructions",instructions);
        root.put("max_output_tokens",maxOutputTokens);
        var message=root.putArray("input").addObject();
        message.put("role","user");
        message.putArray("content").addObject().put("type","input_text").put("text",input);
      }

      HttpRequest req=HttpRequest.newBuilder()
          .uri(URI.create(baseUrl+(chatCompletions?"/v1/chat/completions":"/v1/responses")))
          .timeout(REQUEST_TIMEOUT)
          .header("Authorization","Bearer "+apiKey)
          .header("Content-Type","application/json")
          .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(root)))
          .build();

      HttpResponse<String> res=http.send(req,HttpResponse.BodyHandlers.ofString());
      if(res.statusCode()/100!=2){
        String detail="";
        try{detail=mapper.readTree(res.body()).path("error").path("message").asText("");}catch(Exception ignored){}
        throw new TutorProviderException("Tutor provider rejected request"+(detail.isBlank()?"":": "+detail));
      }
      JsonNode body=mapper.readTree(res.body());
      String text=chatCompletions?body.path("choices").path(0).path("message").path("content").asText(""):body.path("output_text").asText("");
      if(text.isBlank() && body.path("output").isArray()){
        StringBuilder out=new StringBuilder();
        for(JsonNode item:body.path("output")) for(JsonNode part:item.path("content")){
          if("output_text".equals(part.path("type").asText())){if(out.length()>0)out.append("\n");out.append(part.path("text").asText(""));}
        }
        text=out.toString();
      }
      if(text.isBlank()) throw new TutorProviderException("Tutor returned no text");
      JsonNode usage=body.path("usage");
      int inputTokens=chatCompletions?usage.path("prompt_tokens").asInt(-1):usage.path("input_tokens").asInt(-1);
      int outputTokens=chatCompletions?usage.path("completion_tokens").asInt(-1):usage.path("output_tokens").asInt(-1);
      return new TutorReply(text.trim(),body.path("id").asText(null),
          inputTokens<0?null:inputTokens,outputTokens<0?null:outputTokens,model);
    }catch(HttpTimeoutException e){
      throw new TutorProviderException("Tutor inference timed out after "+REQUEST_TIMEOUT.toSeconds()+" seconds",e);
    }catch(TutorUnavailableException|TutorProviderException e){throw e;}
    catch(Exception e){throw new TutorProviderException("Tutor provider request failed",e);}
  }
}
