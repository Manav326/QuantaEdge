package com.quantaedge.api;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class ApiExceptionHandler {
  @ExceptionHandler(LessonNotFoundException.class)
  @ResponseStatus(HttpStatus.NOT_FOUND)
  public Map<String,String> lessonNotFound(LessonNotFoundException ex){return Map.of("error","LESSON_NOT_FOUND","message",ex.getMessage());}
  @ExceptionHandler(SecurityException.class)
  public org.springframework.http.ResponseEntity<Map<String,String>> security(SecurityException ex){
    String message=ex.getMessage()==null?"Access denied":ex.getMessage();
    boolean unauthenticated="Authentication required".equalsIgnoreCase(message)
        || message.startsWith("Session expired");
    HttpStatus status=unauthenticated?HttpStatus.UNAUTHORIZED:HttpStatus.FORBIDDEN;
    return org.springframework.http.ResponseEntity.status(status)
        .body(Map.of("error",unauthenticated?"UNAUTHENTICATED":"FORBIDDEN","message",message));
  }
  @ExceptionHandler(TutorUnavailableException.class)
  @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
  public Map<String,String> tutorUnavailable(TutorUnavailableException ex){return Map.of("error","TUTOR_UNAVAILABLE","message",ex.getMessage());}
  @ExceptionHandler(TutorProviderException.class)
  @ResponseStatus(HttpStatus.BAD_GATEWAY)
  public Map<String,String> tutorProvider(TutorProviderException ex){return Map.of("error","TUTOR_PROVIDER_ERROR","message",ex.getMessage());}
  @ExceptionHandler({IllegalArgumentException.class,IllegalStateException.class})
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  public Map<String,String> badRequest(RuntimeException ex){return Map.of("error","BAD_REQUEST","message",ex.getMessage());}
}
