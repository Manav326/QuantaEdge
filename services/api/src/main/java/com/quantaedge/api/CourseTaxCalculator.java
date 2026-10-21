package com.quantaedge.api;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Pure tax amount arithmetic for an already-approved tax profile.
 *
 * <p>This class does not decide whether a supply is exempt, what rate applies, who the
 * supplier is, or whether GST registration is required. Those are policy/authorization
 * decisions. The caller must load those inputs from the approved, effective-dated profile.
 * All monetary amounts are integer minor units (paise for INR), never floating point.</p>
 */
public final class CourseTaxCalculator {
  private static final BigDecimal BASIS_POINTS = BigDecimal.valueOf(10_000L);

  private CourseTaxCalculator() {}

  public enum Treatment {
    TAXABLE,
    EXEMPT,
    NIL_RATED,
    NON_GST,
    COMPOSITION
  }

  public record Result(
      long listedPricePaise,
      long discountPaise,
      long discountedPricePaise,
      long taxableValuePaise,
      long outputTaxPaise,
      long customerPayablePaise,
      boolean priceIncludesTax,
      Treatment treatment,
      int gstRateBasisPoints,
      int cessRateBasisPoints) {}

  /**
   * Calculates a sale from list price and an explicit up-front discount. This assumes the
   * approved policy allows the discount to reduce consideration before tax calculation.
   * Post-supply discounts need their own statutory adjustment workflow.
   *
   * @param actualPaidPaise amount verified by the server against the captured provider payment
   */
  public static Result calculate(
      long listedPricePaise,
      long discountPaise,
      Treatment treatment,
      int gstRateBasisPoints,
      int cessRateBasisPoints,
      boolean priceIncludesTax,
      long actualPaidPaise) {
    Result quoted = quote(
        listedPricePaise, discountPaise, treatment, gstRateBasisPoints,
        cessRateBasisPoints, priceIncludesTax);
    if (actualPaidPaise != quoted.customerPayablePaise()) {
      throw new IllegalArgumentException(
          "Captured payment does not match the approved course price and tax calculation.");
    }
    return quoted;
  }

  /**
   * Produces a pre-checkout quote from an already-approved tax profile. It does not claim that
   * money has been captured. The caller must compare customerPayablePaise with the provider's
   * exact captured amount before recording a sale.
   */
  public static Result quote(
      long listedPricePaise,
      long discountPaise,
      Treatment treatment,
      int gstRateBasisPoints,
      int cessRateBasisPoints,
      boolean priceIncludesTax) {
    if (listedPricePaise < 0 || discountPaise < 0) {
      throw new IllegalArgumentException("Price and discount amounts cannot be negative.");
    }
    if (discountPaise > listedPricePaise) {
      throw new IllegalArgumentException("Discount cannot exceed the listed price.");
    }
    if (treatment == null) {
      throw new IllegalArgumentException("An approved tax treatment is required.");
    }
    if (gstRateBasisPoints < 0 || gstRateBasisPoints > 10_000
        || cessRateBasisPoints < 0 || cessRateBasisPoints > 10_000) {
      throw new IllegalArgumentException("Tax rates must be expressed as valid basis points.");
    }
    if (treatment == Treatment.TAXABLE) {
      if (gstRateBasisPoints <= 0) {
        throw new IllegalArgumentException("A taxable supply needs an approved positive GST rate.");
      }
    } else if (gstRateBasisPoints != 0 || cessRateBasisPoints != 0) {
      throw new IllegalArgumentException("A non-taxable treatment cannot carry GST or cess rates.");
    }

    long discounted = listedPricePaise - discountPaise;
    long taxable;
    long tax;
    long payable;

    if (treatment != Treatment.TAXABLE) {
      taxable = discounted;
      tax = 0L;
      payable = discounted;
    } else if (priceIncludesTax) {
      long combinedRate = Math.addExact((long) gstRateBasisPoints, (long) cessRateBasisPoints);
      taxable = BigDecimal.valueOf(discounted)
          .multiply(BASIS_POINTS)
          .divide(BASIS_POINTS.add(BigDecimal.valueOf(combinedRate)), 0, RoundingMode.HALF_UP)
          .longValueExact();
      tax = discounted - taxable;
      payable = discounted;
    } else {
      taxable = discounted;
      long gst = percentageOf(taxable, gstRateBasisPoints);
      long cess = percentageOf(taxable, cessRateBasisPoints);
      tax = Math.addExact(gst, cess);
      payable = Math.addExact(taxable, tax);
    }

    return new Result(
        listedPricePaise,
        discountPaise,
        discounted,
        taxable,
        tax,
        payable,
        priceIncludesTax,
        treatment,
        gstRateBasisPoints,
        cessRateBasisPoints);
  }

  private static long percentageOf(long taxablePaise, int rateBasisPoints) {
    return BigDecimal.valueOf(taxablePaise)
        .multiply(BigDecimal.valueOf(rateBasisPoints))
        .divide(BASIS_POINTS, 0, RoundingMode.HALF_UP)
        .longValueExact();
  }
}
