package com.quantaedge.api;

import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/tutor")
public class TutorController {
  private final TutorService tutor;
  public TutorController(TutorService tutor){this.tutor=tutor;}

  @PostMapping("/sessions")
  public Map<String,Object> start(@RequestAttribute(value="authContext",required=false) AuthContext context,@RequestBody Map<String,Object> body){
    return tutor.startSession(context,Long.parseLong(String.valueOf(body.get("lessonId"))));
  }

  @GetMapping("/sessions/{sessionId}/messages")
  public List<Map<String,Object>> messages(@RequestAttribute(value="authContext",required=false) AuthContext context,@PathVariable long sessionId){
    return tutor.messages(context,sessionId);
  }

  @PostMapping("/sessions/{sessionId}/messages")
  public Map<String,Object> send(@RequestAttribute(value="authContext",required=false) AuthContext context,@PathVariable long sessionId,@RequestBody Map<String,Object> body){
    return tutor.send(context,sessionId,String.valueOf(body.getOrDefault("message","")),
        String.valueOf(body.getOrDefault("mode","HINT")),
        body.get("questionId")==null?0L:Long.parseLong(String.valueOf(body.get("questionId"))));
  }
}
