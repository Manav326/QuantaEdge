package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Regression tests for confirmed provider fees that cannot safely reduce every locked beneficiary share. */
class CourseOrderFeeOverageTest {

  @Test
  void platformAbsorbsFeeOverageRatherThanBreakingAnApprovedFlatShare() {
    List<RevenueDistributionCalculator.BeneficiaryShare> shares = List.of(
        RevenueDistributionCalculator.BeneficiaryShare.flat("INSTITUTION", 11L, 90_000L));

    RevenueDistributionCalculator.BeneficiaryResult result =
        CourseOrderFinancialService.calculateWithMaximumSafeFeeDeduction(
            100_000L, 0L, 15_000L, shares, "COMPANY", 99L);

    assertEquals(10_000L, result.processingChargesPaise());
    assertEquals(90_000L, result.allocations().getFirst().amountPaise());
    assertEquals(5_000L, 15_000L - result.processingChargesPaise());
    assertEquals(90_000L, result.allocations().stream()
        .mapToLong(RevenueDistributionCalculator.BeneficiaryAllocation::amountPaise).sum());
  }

  @Test
  void percentageSharesCanAbsorbTheFullConfirmedFeeWhenThePolicyAllowsIt() {
    List<RevenueDistributionCalculator.BeneficiaryShare> shares = List.of(
        RevenueDistributionCalculator.BeneficiaryShare.percentage("INSTITUTION", 11L, 5_000));

    RevenueDistributionCalculator.BeneficiaryResult result =
        CourseOrderFinancialService.calculateWithMaximumSafeFeeDeduction(
            100_000L, 0L, 15_000L, shares, "COMPANY", 99L);

    assertEquals(15_000L, result.processingChargesPaise());
    assertEquals(85_000L, result.distributablePoolPaise());
    assertEquals(0L, 15_000L - result.processingChargesPaise());
    assertTrue(result.allocations().stream()
        .mapToLong(RevenueDistributionCalculator.BeneficiaryAllocation::amountPaise).sum()
        <= result.distributablePoolPaise());
  }

  @Test
  void providerFeeLargerThanTheEntirePreTaxPoolDoesNotCreateNegativeShares() {
    List<RevenueDistributionCalculator.BeneficiaryShare> shares = List.of(
        RevenueDistributionCalculator.BeneficiaryShare.flat("INSTITUTION", 11L, 90_000L));

    long actualProviderChargePaise=150_000L;
    long availableBeforeFeesPaise=100_000L-10_000L;
    RevenueDistributionCalculator.BeneficiaryResult result =
        CourseOrderFinancialService.calculateWithMaximumSafeFeeDeduction(
            100_000L, 10_000L, Math.min(actualProviderChargePaise,availableBeforeFeesPaise),
            shares, "COMPANY", 99L);

    assertEquals(0L, result.processingChargesPaise());
    assertEquals(90_000L, result.allocations().getFirst().amountPaise());
    assertEquals(actualProviderChargePaise, actualProviderChargePaise - result.processingChargesPaise());
  }
}
