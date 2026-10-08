package com.quantaedge.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class OpenAiTutorClient {
  private final ObjectMapper mapper;
  private final HttpClient http;
  private final boolean enabled;
  private final String apiKey;
  private final String model;
  private final String baseUrl;
  private final int maxOutputTokens;

  public OpenAiTutorClient(
      ObjectMapper mapper,
      @Value("${app.ai-tutor.enabled:false}") boolean enabled,
      @Value("${app.ai-tutor.api-key:}") String apiKey,
      @Value("${app.ai-tutor.model:gpt-5.6-luna}") String model,
      @Value("${app.ai-tutor.base-url:https://api.openai.com}") String baseUrl,
      @Value("${app.ai-tutor.max-output-tokens:500}") int maxOutputTokens) {
    this.mapper=mapper; this.enabled=enabled; this.apiKey=apiKey; this.model=model;
    this.baseUrl=baseUrl.replaceAll("/+$",""); this.maxOutputTokens=maxOutputTokens;
    this.http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
  }

  public boolean isConfigured(){return enabled && apiKey!=null && !apiKey.isBlank();}
  public String model(){return model;}

  public TutorReply generate(String instructions,String input){
    if(!isConfigured()) throw new TutorUnavailableException("AI tutor is not configured");
    try{
      var root=mapper.createObjectNode();
      root.put("model",model);
      root.put("instructions",instructions);
      root.put("max_output_tokens",maxOutputTokens);
      var message=root.putArray("input").addObject();
      message.put("role","user");
      message.putArray("content").addObject().put("type","input_text").put("text",input);

      HttpRequest req=HttpRequest.newBuilder()
          .uri(URI.create(baseUrl+"/v1/responses"))
          .timeout(Duration.ofSeconds(30))
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
      String text=body.path("output_text").asText("");
      if(text.isBlank() && body.path("output").isArray()){
        StringBuilder out=new StringBuilder();
        for(JsonNode item:body.path("output")) for(JsonNode part:item.path("content")){
          if("output_text".equals(part.path("type").asText())){if(out.length()>0)out.append("\n");out.append(part.path("text").asText(""));}
        }
        text=out.toString();
      }
      if(text.isBlank()) throw new TutorProviderException("Tutor returned no text");
      JsonNode usage=body.path("usage");
      return new TutorReply(text.trim(),body.path("id").asText(null),
          usage.has("input_tokens")?usage.path("input_tokens").asInt():null,
          usage.has("output_tokens")?usage.path("output_tokens").asInt():null);
    }catch(TutorUnavailableException|TutorProviderException e){throw e;}
    catch(Exception e){throw new TutorProviderException("Tutor provider request failed",e);}
  }
}
