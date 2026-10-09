package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class QuestionAnswerServiceTest {
  private final QuestionAnswerService service=new QuestionAnswerService(new ObjectMapper());

  @Test void optionIsGraded(){
    var e=service.evaluate("{\"kind\":\"OPTION\",\"value\":\"B\"}","B");
    assertTrue(e.autoGraded()); assertTrue(e.correct());
  }

  @Test void numericToleranceWorks(){
    var e=service.evaluate("{\"kind\":\"NUMERIC\",\"value\":\"10.0\",\"tolerance\":\"0.1\"}","10.05");
    assertTrue(e.autoGraded()); assertTrue(e.correct());
  }

  @Test void orderRequiresExactSequence(){
    var e=service.evaluate("{\"kind\":\"ORDER\",\"value\":[\"B\",\"C\",\"A\"]}", "[\"B\",\"C\",\"A\"]");
    assertTrue(e.autoGraded()); assertTrue(e.correct());
  }

  @Test void subjectiveQuestionRemainsUnGraded(){
    var e=service.evaluate("{}", "anything");
    assertFalse(e.autoGraded()); assertNull(e.correct());
  }

  @Test void incorrectAnswerDoesNotBecomeCorrect(){
    var e=service.evaluate("{\"kind\":\"OPTION\",\"value\":\"A\"}","B");
    assertTrue(e.autoGraded()); assertFalse(e.correct());
  }
}
