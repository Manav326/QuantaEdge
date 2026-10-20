package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class CourseRefundLedgerServiceTest {
  @Test
  void cumulativeProrationRetainsExactTotalsWhenRefundsArriveInParts() {
    long originalTotal=10_000L;
    long originalTax=1_800L;

    long firstTarget=CourseRefundLedgerService.proportional(originalTax,3_333L,originalTotal);
    long secondTarget=CourseRefundLedgerService.proportional(originalTax,6_666L,originalTotal);
    long finalTarget=CourseRefundLedgerService.proportional(originalTax,10_000L,originalTotal);

    long firstTax=firstTarget;
    long secondTax=secondTarget-firstTarget;
    long finalTax=finalTarget-secondTarget;

    assertEquals(1_800L,firstTax+secondTax+finalTax);
    assertEquals(10_000L,3_333L+3_333L+3_334L);
    assertEquals(600L,firstTax);
    assertEquals(600L,secondTax);
    assertEquals(600L,finalTax);
  }

  @Test
  void fullRefundRestoresEachOriginalTaxComponentExactly() {
    long cgst=900L, sgst=900L, originalTotal=10_000L;
    assertEquals(cgst,CourseRefundLedgerService.proportional(cgst,originalTotal,originalTotal));
    assertEquals(sgst,CourseRefundLedgerService.proportional(sgst,originalTotal,originalTotal));
  }

  @Test
  void rejectsProrationOutsideTheOriginalCapturedAmount() {
    assertThrows(IllegalArgumentException.class,
        () -> CourseRefundLedgerService.proportional(1_800L,10_001L,10_000L));
    assertThrows(IllegalArgumentException.class,
        () -> CourseRefundLedgerService.proportional(1_800L,1L,0L));
  }
}
