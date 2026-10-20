package com.quantaedge.api;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Deterministic calculator for institution shares from confirmed course-sale proceeds.
 *
 * <p>The caller must supply the amount actually verified as captured and the output-tax
 * amount from the same approved tax snapshot used by invoicing. Catalogue/list price is
 * intentionally not an input. This class performs no payment, payout, persistence or
 * tax classification; those actions require their own authorization and transaction flow.</p>
 */
public final class RevenueDistributionCalculator {
  private static final BigDecimal BASIS_POINTS = BigDecimal.valueOf(10_000L);

  private RevenueDistributionCalculator() {}

  public enum PaymentConfirmation {
    UNCONFIRMED,
    VERIFIED_CAPTURED
  }

  public enum CalculationType {
    PERCENTAGE,
    FLAT
  }

  public record Share(
      long institutionId,
      CalculationType calculationType,
      Integer percentageBasisPoints,
      Long flatAmountPaise) {

    public Share {
      if (institutionId <= 0) {
        throw new IllegalArgumentException("A revenue-share recipient must be a valid institution.");
      }
      if (calculationType == null) {
        throw new IllegalArgumentException("A revenue-share calculation type is required.");
      }
      if (calculationType == CalculationType.PERCENTAGE) {
        if (percentageBasisPoints == null
            || percentageBasisPoints < 1
            || percentageBasisPoints > 10_000
            || flatAmountPaise != null) {
          throw new IllegalArgumentException(
              "Percentage shares require 1–10000 basis points and no flat amount.");
        }
      } else if (percentageBasisPoints != null
          || flatAmountPaise == null
          || flatAmountPaise <= 0) {
        throw new IllegalArgumentException(
            "Flat shares require a positive amount in paise and no percentage.");
      }
    }

    public static Share percentage(long institutionId, int basisPoints) {
      return new Share(institutionId, CalculationType.PERCENTAGE, basisPoints, null);
    }

    public static Share flat(long institutionId, long amountPaise) {
      return new Share(institutionId, CalculationType.FLAT, null, amountPaise);
    }
  }

  public record Allocation(long institutionId, CalculationType calculationType, long amountPaise) {}

  public record Result(
      long actualPaidPaise,
      long outputTaxPaise,
      long distributablePoolPaise,
      long totalInstitutionAllocatedPaise,
      long platformRemainderPaise,
      List<Allocation> allocations) {
    public Result {
      allocations = List.copyOf(allocations);
    }
  }

  /**
   * Calculates allocations only for an authoritative captured payment. The tax value must
   * come from the same approved tax snapshot used by the invoice for this payment.
   */
  public static Result calculate(
      PaymentConfirmation confirmation,
      long actualPaidPaise,
      long outputTaxPaise,
      List<Share> shares) {

    if (confirmation != PaymentConfirmation.VERIFIED_CAPTURED) {
      throw new IllegalStateException(
          "Revenue cannot be allocated until the payment is verified as captured.");
    }
    if (actualPaidPaise < 0 || outputTaxPaise < 0) {
      throw new IllegalArgumentException("Payment and tax amounts cannot be negative.");
    }
    if (outputTaxPaise > actualPaidPaise) {
      throw new IllegalArgumentException(
          "The tax amount cannot exceed the actual payment received.");
    }
    if (shares == null) {
      throw new IllegalArgumentException("The approved revenue-share recipients are required.");
    }

    long pool = actualPaidPaise - outputTaxPaise;
    Set<Long> recipients = new HashSet<>();
    List<Allocation> allocations = new ArrayList<>();
    long totalAllocated = 0L;

    for (Share share : shares) {
      if (share == null) {
        throw new IllegalArgumentException("Revenue-share policy contains an empty recipient.");
      }
      if (!recipients.add(share.institutionId())) {
        throw new IllegalArgumentException(
            "An institution can receive only one allocation in a revenue-share policy version.");
      }

      long amount = share.calculationType() == CalculationType.PERCENTAGE
          ? percentageOf(pool, share.percentageBasisPoints())
          : share.flatAmountPaise();

      try {
        totalAllocated = Math.addExact(totalAllocated, amount);
      } catch (ArithmeticException overflow) {
        throw new IllegalStateException("Revenue-share total exceeded supported money precision.", overflow);
      }
      allocations.add(new Allocation(share.institutionId(), share.calculationType(), amount));
    }

    if (totalAllocated > pool) {
      throw new IllegalStateException(
          "Approved institutional allocations exceed the distributable pool; no distribution may be posted.");
    }

    return new Result(
        actualPaidPaise,
        outputTaxPaise,
        pool,
        totalAllocated,
        pool - totalAllocated,
        allocations);
  }

  private static long percentageOf(long poolPaise, int percentageBasisPoints) {
    return BigDecimal.valueOf(poolPaise)
        .multiply(BigDecimal.valueOf(percentageBasisPoints))
        .divide(BASIS_POINTS, 0, RoundingMode.HALF_UP)
        .longValueExact();
  }
}
