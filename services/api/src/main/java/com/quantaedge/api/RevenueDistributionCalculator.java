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

  /** Generic beneficiary share used by the existing course ledger for institutions and companies. */
  public record BeneficiaryShare(
      String recipientType,long recipientId,CalculationType calculationType,
      Integer percentageBasisPoints,Long flatAmountPaise) {
    public BeneficiaryShare {
      if(!"INSTITUTION".equals(recipientType)&&!"COMPANY".equals(recipientType))
        throw new IllegalArgumentException("A revenue recipient must be an institution or company.");
      if(recipientId<=0) throw new IllegalArgumentException("A revenue recipient ID must be positive.");
      if(calculationType==null) throw new IllegalArgumentException("A revenue-share calculation type is required.");
      if(calculationType==CalculationType.PERCENTAGE) {
        if(percentageBasisPoints==null||percentageBasisPoints<1||percentageBasisPoints>10_000||flatAmountPaise!=null)
          throw new IllegalArgumentException("Percentage shares require 1–10000 basis points and no flat amount.");
      } else if(percentageBasisPoints!=null||flatAmountPaise==null||flatAmountPaise<=0) {
        throw new IllegalArgumentException("Flat shares require a positive amount in paise and no percentage.");
      }
    }
    public static BeneficiaryShare percentage(String type,long id,int basisPoints) {
      return new BeneficiaryShare(type,id,CalculationType.PERCENTAGE,basisPoints,null);
    }
    public static BeneficiaryShare flat(String type,long id,long amountPaise) {
      return new BeneficiaryShare(type,id,CalculationType.FLAT,null,amountPaise);
    }
    public String key(){return recipientType+":"+recipientId;}
  }

  /** Both components are retained so a company can receive an explicit share plus the remainder. */
  public record BeneficiaryAllocation(
      String recipientType,long recipientId,CalculationType calculationType,
      long explicitAmountPaise,long remainderAmountPaise,long amountPaise) {}

  public record BeneficiaryResult(
      long actualPaidPaise,long outputTaxPaise,long processingChargesPaise,long distributablePoolPaise,
      long explicitAllocationsPaise,long remainderPaise,long unassignedRemainderPaise,
      List<BeneficiaryAllocation> allocations) {
    public BeneficiaryResult { allocations=List.copyOf(allocations); }
  }

  /**
   * Applies confirmed processor charges before allocating the pool. A null remainder recipient
   * preserves the legacy platform-remainder behavior; a selected beneficiary receives the full
   * residual in addition to any explicit share it already has.
   */
  public static BeneficiaryResult calculateBeneficiaries(
      PaymentConfirmation confirmation,long actualPaidPaise,long outputTaxPaise,long processingChargesPaise,
      List<BeneficiaryShare> shares,String remainderRecipientType,Long remainderRecipientId) {
    if(confirmation!=PaymentConfirmation.VERIFIED_CAPTURED)
      throw new IllegalStateException("Revenue cannot be allocated until the payment is verified as captured.");
    if(actualPaidPaise<0||outputTaxPaise<0||processingChargesPaise<0)
      throw new IllegalArgumentException("Payment, tax and processor charges cannot be negative.");
    if(outputTaxPaise>actualPaidPaise||processingChargesPaise>actualPaidPaise-outputTaxPaise)
      throw new IllegalArgumentException("Tax and processor charges cannot exceed the money available before distribution.");
    if(shares==null) throw new IllegalArgumentException("The approved revenue-share recipients are required.");
    boolean hasRemainderType=remainderRecipientType!=null&&!remainderRecipientType.isBlank();
    boolean hasRemainderId=remainderRecipientId!=null;
    if(hasRemainderType!=hasRemainderId)
      throw new IllegalArgumentException("The remainder recipient type and ID must be supplied together.");
    if(hasRemainderType&&!"INSTITUTION".equals(remainderRecipientType)&&!"COMPANY".equals(remainderRecipientType))
      throw new IllegalArgumentException("The remainder recipient must be an institution or company.");

    long pool=actualPaidPaise-outputTaxPaise-processingChargesPaise;
    Set<String> recipients=new HashSet<>();
    List<BeneficiaryAllocation> allocations=new ArrayList<>();
    long totalExplicit=0L;
    for(BeneficiaryShare share:shares) {
      if(share==null) throw new IllegalArgumentException("Revenue-share policy contains an empty recipient.");
      if(!recipients.add(share.key()))
        throw new IllegalArgumentException("A beneficiary can appear only once in a revenue-share policy.");
      long amount=share.calculationType()==CalculationType.PERCENTAGE
          ?percentageOf(pool,share.percentageBasisPoints()):share.flatAmountPaise();
      try { totalExplicit=Math.addExact(totalExplicit,amount); }
      catch(ArithmeticException overflow) {
        throw new IllegalStateException("Revenue-share total exceeded supported money precision.",overflow);
      }
      allocations.add(new BeneficiaryAllocation(share.recipientType(),share.recipientId(),
          share.calculationType(),amount,0L,amount));
    }
    if(totalExplicit>pool)
      throw new IllegalStateException("Approved shares exceed the pool after tax and processor charges; no distribution may be posted.");
    long remainder=pool-totalExplicit;
    long unassignedRemainder=hasRemainderType?0L:remainder;
    if(hasRemainderType&&remainder>0) {
      String key=remainderRecipientType+":"+remainderRecipientId;
      int existingIndex=-1;
      for(int i=0;i<allocations.size();i++) {
        BeneficiaryAllocation a=allocations.get(i);
        if((a.recipientType()+":"+a.recipientId()).equals(key)){existingIndex=i;break;}
      }
      if(existingIndex>=0) {
        BeneficiaryAllocation a=allocations.get(existingIndex);
        allocations.set(existingIndex,new BeneficiaryAllocation(a.recipientType(),a.recipientId(),
            a.calculationType(),a.explicitAmountPaise(),remainder,
            Math.addExact(a.explicitAmountPaise(),remainder)));
      } else {
        allocations.add(new BeneficiaryAllocation(remainderRecipientType,remainderRecipientId,
            null,0L,remainder,remainder));
      }
    }
    return new BeneficiaryResult(actualPaidPaise,outputTaxPaise,processingChargesPaise,pool,
        totalExplicit,remainder,unassignedRemainder,allocations);
  }

  private static long percentageOf(long poolPaise, int percentageBasisPoints) {
    return BigDecimal.valueOf(poolPaise)
        .multiply(BigDecimal.valueOf(percentageBasisPoints))
        .divide(BASIS_POINTS, 0, RoundingMode.HALF_UP)
        .longValueExact();
  }
}
