package com.quantaedge.api;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

/** Deterministic arithmetic for an HRMS-approved up-front course discount. */
public final class CourseDiscountCalculator {
  private CourseDiscountCalculator() {}

  public static long calculate(long listedPricePaise, String discountType,
      Integer percentageBasisPoints, Long flatAmountPaise, Long maximumDiscountPaise) {
    if (listedPricePaise <= 0) throw new IllegalArgumentException("A discount requires a positive list price.");
    if (maximumDiscountPaise != null && maximumDiscountPaise <= 0)
      throw new IllegalArgumentException("Maximum discount must be positive when configured.");
    String type = discountType == null ? "" : discountType.trim().toUpperCase(Locale.ROOT);
    long raw;
    if ("PERCENTAGE".equals(type)) {
      if (percentageBasisPoints == null || percentageBasisPoints < 1 || percentageBasisPoints > 10_000
          || flatAmountPaise != null)
        throw new IllegalArgumentException("Percentage discounts require 1–10000 basis points only.");
      raw = BigDecimal.valueOf(listedPricePaise)
          .multiply(BigDecimal.valueOf(percentageBasisPoints))
          .divide(BigDecimal.valueOf(10_000L), 0, RoundingMode.HALF_UP)
          .longValueExact();
    } else if ("FLAT".equals(type)) {
      if (flatAmountPaise == null || flatAmountPaise <= 0 || percentageBasisPoints != null)
        throw new IllegalArgumentException("Flat discounts require a positive amount in paise only.");
      raw = flatAmountPaise;
    } else {
      throw new IllegalArgumentException("Unsupported discount type.");
    }
    if (maximumDiscountPaise != null) raw = Math.min(raw, maximumDiscountPaise);
    return Math.min(raw, listedPricePaise);
  }
}
