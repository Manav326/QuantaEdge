package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class CourseDiscountCalculatorTest {
  @Test void percentageUsesHalfUpRounding() {
    assertEquals(1_180L, CourseDiscountCalculator.calculate(11_800L,"PERCENTAGE",1_000,null,null));
    assertEquals(1L, CourseDiscountCalculator.calculate(1L,"PERCENTAGE",5_000,null,null));
  }
  @Test void maximumDiscountCapsPercentageRule() {
    assertEquals(500L, CourseDiscountCalculator.calculate(10_000L,"PERCENTAGE",2_000,null,500L));
  }
  @Test void flatDiscountUsesIntegerPaise() {
    assertEquals(250L, CourseDiscountCalculator.calculate(10_000L,"FLAT",null,250L,null));
  }
  @Test void discountCannotExceedListPrice() {
    assertEquals(100L, CourseDiscountCalculator.calculate(100L,"FLAT",null,10_000L,null));
  }
  @Test void rejectsInvalidRules() {
    assertThrows(IllegalArgumentException.class, () -> CourseDiscountCalculator.calculate(100L,"PERCENTAGE",0,null,null));
    assertThrows(IllegalArgumentException.class, () -> CourseDiscountCalculator.calculate(100L,"FLAT",100,100L,null));
    assertThrows(IllegalArgumentException.class, () -> CourseDiscountCalculator.calculate(100L,"UNKNOWN",null,null,null));
  }
}
