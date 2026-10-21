package com.quantaedge.api;

import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1")
public class LearningMessagingController {
  private final LearningMessagingService messaging;
  public LearningMessagingController(LearningMessagingService messaging) { this.messaging = messaging; }

  @GetMapping("/learning/live-classes/{sessionId}/conversations")
  public List<Map<String,Object>> conversations(@PathVariable long sessionId,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    return messaging.conversations(sessionId, context);
  }

  @PostMapping("/learning/live-classes/{sessionId}/conversations")
  public Map<String,Object> openConversation(@PathVariable long sessionId, @RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    Object type = body.get("conversationType");
    return messaging.openConversation(sessionId, type == null ? null : String.valueOf(type), context);
  }

  @GetMapping("/learning/conversations/{conversationId}/messages")
  public Map<String,Object> messages(@PathVariable long conversationId,
      @RequestParam(required=false) Long beforeId, @RequestParam(defaultValue="40") Integer limit,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    return messaging.messages(conversationId, beforeId, limit, context);
  }

  @PostMapping("/learning/conversations/{conversationId}/messages")
  public Map<String,Object> sendMessage(@PathVariable long conversationId, @RequestBody LearningMessageInput input,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    return messaging.sendMessage(conversationId, input, context);
  }

  @PostMapping("/learning/conversations/{conversationId}/read")
  public Map<String,Object> markRead(@PathVariable long conversationId, @RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    Object supplied = body.get("lastReadMessageId");
    if (!(supplied instanceof Number n)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "lastReadMessageId is required.");
    messaging.markRead(conversationId, n.longValue(), context);
    return Map.of("conversationId", conversationId, "lastReadMessageId", n.longValue(), "read", true);
  }

  @PostMapping("/learning/messages/{messageId}/reports")
  public Map<String,Object> reportMessage(@PathVariable long messageId, @RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    Object reason = body.get("reason");
    return messaging.reportMessage(messageId, reason == null ? null : String.valueOf(reason), context);
  }

  @PatchMapping("/learning/messages/{messageId}/moderation")
  public Map<String,Object> moderateMessage(@PathVariable long messageId, @RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    Object action = body.get("action");
    return messaging.moderateMessage(messageId, action == null ? null : String.valueOf(action), context);
  }

  @PatchMapping("/learning/conversations/{conversationId}/settings")
  public Map<String,Object> conversationSettings(@PathVariable long conversationId, @RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    Object seconds = body.get("slowModeSeconds");
    if (!(seconds instanceof Number n)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "slowModeSeconds is required.");
    return messaging.updateConversationSettings(conversationId, n.intValue(), context);
  }

  @PostMapping("/learning/conversations/{conversationId}/mute")
  public Map<String,Object> muteStudent(@PathVariable long conversationId, @RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    Object studentId = body.get("studentId");
    Object minutes = body.get("minutes");
    if (!(studentId instanceof Number student) || !(minutes instanceof Number duration)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "studentId and minutes are required.");
    }
    return messaging.muteStudent(conversationId, student.longValue(), duration.intValue(), context);
  }

  @GetMapping("/learning/conversations/{conversationId}/reports")
  public List<Map<String,Object>> reports(@PathVariable long conversationId,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    return messaging.reports(conversationId, context);
  }

  @PatchMapping("/learning/reports/{reportId}")
  public Map<String,Object> resolveReport(@PathVariable long reportId, @RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    Object status = body.get("status");
    return messaging.resolveReport(reportId, status == null ? null : String.valueOf(status), context);
  }
}
