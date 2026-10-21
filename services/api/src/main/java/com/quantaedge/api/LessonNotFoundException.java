package com.quantaedge.api;

public class LessonNotFoundException extends RuntimeException {
  public LessonNotFoundException(long lessonId) {
    super("Lesson not found: " + lessonId);
  }
}
