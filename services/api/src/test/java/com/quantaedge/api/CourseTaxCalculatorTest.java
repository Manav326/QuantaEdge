package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class CourseTaxCalculatorTest {
  @Test
  void extractsTaxFromTaxInclusivePrice() {
    CourseTaxCalculator.Result result = CourseTaxCalculator.calculate(
        11_800L, 0L, CourseTaxCalculator.Treatment.TAXABLE,
        1_800, 0, true, 11_800L);

    assertEquals(10_000L, result.taxableValuePaise());
    assertEquals(1_800L, result.outputTaxPaise());
    assertEquals(11_800L, result.customerPayablePaise());
  }

  @Test
  void appliesUpfrontDiscountBeforeTaxForTaxInclusivePrice() {
    // ₹118 list price less ₹11.80 discount = ₹106.20 actual all-in consideration.
    CourseTaxCalculator.Result result = CourseTaxCalculator.calculate(
        11_800L, 1_180L, CourseTaxCalculator.Treatment.TAXABLE,
        1_800, 0, true, 10_620L);

    assertEquals(10_620L, result.discountedPricePaise());
    assertEquals(9_000L, result.taxableValuePaise());
    assertEquals(1_620L, result.outputTaxPaise());
    assertEquals(10_620L, result.customerPayablePaise());
  }

  @Test
  void addsTaxToDiscountedTaxExclusivePrice() {
    CourseTaxCalculator.Result result = CourseTaxCalculator.calculate(
        11_800L, 1_800L, CourseTaxCalculator.Treatment.TAXABLE,
        1_800, 0, false, 11_800L);

    assertEquals(10_000L, result.taxableValuePaise());
    assertEquals(1_800L, result.outputTaxPaise());
    assertEquals(11_800L, result.customerPayablePaise());
  }

  @Test
  void nonTaxableTreatmentCannotAccidentallyChargeGst() {
    CourseTaxCalculator.Result result = CourseTaxCalculator.calculate(
        10_000L, 1_000L, CourseTaxCalculator.Treatment.EXEMPT,
        0, 0, true, 9_000L);

    assertEquals(0L, result.outputTaxPaise());
    assertEquals(9_000L, result.customerPayablePaise());
    assertThrows(IllegalArgumentException.class, () -> CourseTaxCalculator.calculate(
        10_000L, 1_000L, CourseTaxCalculator.Treatment.EXEMPT,
        1_800, 0, true, 9_000L));
  }

  @Test
  void rejectsPaymentAmountThatDoesNotMatchTheTaxSnapshot() {
    assertThrows(IllegalArgumentException.class, () -> CourseTaxCalculator.calculate(
        11_800L, 1_800L, CourseTaxCalculator.Treatment.TAXABLE,
        1_800, 0, false, 9_999L));
  }

  @Test
  void rejectsDiscountGreaterThanListPrice() {
    assertThrows(IllegalArgumentException.class, () -> CourseTaxCalculator.calculate(
        100L, 101L, CourseTaxCalculator.Treatment.EXEMPT, 0, 0, true, 0L));
  }
}
