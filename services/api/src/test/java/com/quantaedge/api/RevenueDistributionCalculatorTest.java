package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class RevenueDistributionCalculatorTest {
  @Test
  void appliesPercentAndFlatSharesOnlyToActualPaidAmountAfterTax() {
    // Discounted consideration is ₹800 (80,000 paise), tax from the approved
    // invoice snapshot is ₹80 (8,000 paise), so the distributable pool is ₹720.
    RevenueDistributionCalculator.Result result = RevenueDistributionCalculator.calculate(
        RevenueDistributionCalculator.PaymentConfirmation.VERIFIED_CAPTURED,
        80_000L,
        8_000L,
        List.of(
            RevenueDistributionCalculator.Share.percentage(11L, 3_000),
            RevenueDistributionCalculator.Share.flat(12L, 10_000L)));

    assertEquals(80_000L, result.actualPaidPaise());
    assertEquals(8_000L, result.outputTaxPaise());
    assertEquals(72_000L, result.distributablePoolPaise());
    assertEquals(21_600L, result.allocations().get(0).amountPaise());
    assertEquals(10_000L, result.allocations().get(1).amountPaise());
    assertEquals(31_600L, result.totalInstitutionAllocatedPaise());
    assertEquals(40_400L, result.platformRemainderPaise());
  }

  @Test
  void doesNotUseTheUndiscountedListPriceAsTheDistributionBasis() {
    // List price intentionally is not a calculator input. Only the amount confirmed
    // as actually paid after any discount can be supplied to this API.
    RevenueDistributionCalculator.Result result = RevenueDistributionCalculator.calculate(
        RevenueDistributionCalculator.PaymentConfirmation.VERIFIED_CAPTURED,
        60_000L,
        6_000L,
        List.of(RevenueDistributionCalculator.Share.percentage(7L, 5_000)));

    assertEquals(54_000L, result.distributablePoolPaise());
    assertEquals(27_000L, result.totalInstitutionAllocatedPaise());
    assertEquals(27_000L, result.platformRemainderPaise());
  }

  @Test
  void roundsPercentageAmountsHalfUpToTheNearestPaise() {
    RevenueDistributionCalculator.Result result = RevenueDistributionCalculator.calculate(
        RevenueDistributionCalculator.PaymentConfirmation.VERIFIED_CAPTURED,
        1L,
        0L,
        List.of(RevenueDistributionCalculator.Share.percentage(7L, 5_000)));

    assertEquals(1L, result.totalInstitutionAllocatedPaise());
    assertEquals(0L, result.platformRemainderPaise());
  }

  @Test
  void refusesToAllocateBeforePaymentIsAuthoritativelyCaptured() {
    assertThrows(IllegalStateException.class, () ->
        RevenueDistributionCalculator.calculate(
            RevenueDistributionCalculator.PaymentConfirmation.UNCONFIRMED,
            80_000L,
            8_000L,
            List.of(RevenueDistributionCalculator.Share.flat(11L, 1_000L))));
  }

  @Test
  void rejectsTaxGreaterThanActualMoneyReceived() {
    assertThrows(IllegalArgumentException.class, () ->
        RevenueDistributionCalculator.calculate(
            RevenueDistributionCalculator.PaymentConfirmation.VERIFIED_CAPTURED,
            5_000L,
            5_001L,
            List.of(RevenueDistributionCalculator.Share.flat(11L, 100L))));
  }

  @Test
  void rejectsACombinedPercentageAndFlatDistributionLargerThanThePool() {
    assertThrows(IllegalStateException.class, () ->
        RevenueDistributionCalculator.calculate(
            RevenueDistributionCalculator.PaymentConfirmation.VERIFIED_CAPTURED,
            10_000L,
            1_000L,
            List.of(
                RevenueDistributionCalculator.Share.percentage(11L, 9_000),
                RevenueDistributionCalculator.Share.flat(12L, 2_000L))));
  }

  @Test
  void rejectsDuplicateInstitutionRecipients() {
    assertThrows(IllegalArgumentException.class, () ->
        RevenueDistributionCalculator.calculate(
            RevenueDistributionCalculator.PaymentConfirmation.VERIFIED_CAPTURED,
            10_000L,
            0L,
            List.of(
                RevenueDistributionCalculator.Share.flat(11L, 100L),
                RevenueDistributionCalculator.Share.percentage(11L, 1_000))));
  }

  @Test
  void rejectsMalformedShareValues() {
    assertThrows(IllegalArgumentException.class,
        () -> RevenueDistributionCalculator.Share.percentage(11L, 0));
    assertThrows(IllegalArgumentException.class,
        () -> RevenueDistributionCalculator.Share.percentage(11L, 10_001));
    assertThrows(IllegalArgumentException.class,
        () -> RevenueDistributionCalculator.Share.flat(11L, 0L));
    assertThrows(IllegalArgumentException.class,
        () -> RevenueDistributionCalculator.Share.flat(0L, 100L));
  }

  @Test
  void processorChargesAreRemovedBeforeAnyBeneficiaryShareIsCalculated() {
    RevenueDistributionCalculator.BeneficiaryResult result =
        RevenueDistributionCalculator.calculateBeneficiaries(
            RevenueDistributionCalculator.PaymentConfirmation.VERIFIED_CAPTURED,
            100_000L, 10_000L, 2_000L,
            List.of(RevenueDistributionCalculator.BeneficiaryShare.percentage("INSTITUTION",11L,5_000)),
            "COMPANY",99L);

    assertEquals(88_000L,result.distributablePoolPaise());
    assertEquals(44_000L,result.allocations().get(0).amountPaise());
    assertEquals(44_000L,result.allocations().get(1).remainderAmountPaise());
    assertEquals(0L,result.unassignedRemainderPaise());
  }

  @Test
  void companyCanReceiveExplicitShareAndRemainderWithoutDoubleCounting() {
    RevenueDistributionCalculator.BeneficiaryResult result =
        RevenueDistributionCalculator.calculateBeneficiaries(
            RevenueDistributionCalculator.PaymentConfirmation.VERIFIED_CAPTURED,
            100_000L, 10_000L, 0L,
            List.of(
                RevenueDistributionCalculator.BeneficiaryShare.percentage("INSTITUTION",11L,4_000),
                RevenueDistributionCalculator.BeneficiaryShare.flat("COMPANY",99L,10_000L)),
            "COMPANY",99L);

    assertEquals(90_000L,result.distributablePoolPaise());
    assertEquals(36_000L,result.allocations().get(0).amountPaise());
    assertEquals(44_000L,result.allocations().get(1).remainderAmountPaise());
    assertEquals(54_000L,result.allocations().get(1).amountPaise());
    assertEquals(100_000L,result.allocations().stream().mapToLong(
        RevenueDistributionCalculator.BeneficiaryAllocation::amountPaise).sum());
  }

  @Test
  void rejectsProcessorChargesThatExceedThePostTaxPool() {
    assertThrows(IllegalArgumentException.class,()->RevenueDistributionCalculator.calculateBeneficiaries(
        RevenueDistributionCalculator.PaymentConfirmation.VERIFIED_CAPTURED,
        10_000L,9_000L,1_001L,List.of(),
        "COMPANY",99L));
  }

  @Test
  void emptyApprovedRecipientListLeavesTheWholePoolWithThePlatform() {
    RevenueDistributionCalculator.Result result = RevenueDistributionCalculator.calculate(
        RevenueDistributionCalculator.PaymentConfirmation.VERIFIED_CAPTURED,
        10_000L,
        1_000L,
        List.of());

    assertEquals(9_000L, result.distributablePoolPaise());
    assertEquals(0L, result.totalInstitutionAllocatedPaise());
    assertEquals(9_000L, result.platformRemainderPaise());
  }
}
