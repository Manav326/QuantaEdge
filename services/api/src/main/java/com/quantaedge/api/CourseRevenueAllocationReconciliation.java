package com.quantaedge.api;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Read-only validation of a saved course sale's locked distribution against its allocation ledger.
 *
 * <p>This class does not write or repair financial records. It deliberately uses the order's
 * saved policy snapshot instead of today's policy, so historical sales remain reproducible.</p>
 */
final class CourseRevenueAllocationReconciliation {
  private static final long BASIS_POINTS = 10_000L;

  private CourseRevenueAllocationReconciliation() {}

  static Result reconcile(
      Map<String,Object> snapshot,
      Long orderPolicyId,
      long capturedAmountPaise,
      long outputTaxPaise,
      String orderCurrency,
      List<Map<String,Object>> recordedRows) {
    List<String> issues = new ArrayList<>();
    List<String> details = new ArrayList<>();
    Map<String,ExpectedAllocation> expected = new LinkedHashMap<>();
    List<Map<String,Object>> rows = recordedRows == null ? List.of() : recordedRows;

    if (snapshot == null || snapshot.isEmpty()) {
      issues.add("The sale has no readable locked revenue-share policy snapshot.");
      details.add("Locked revenue-share snapshot: missing or empty.");
    } else {
      long snapshotPolicyId = number(snapshot.get("policyId"));
      if (orderPolicyId == null || orderPolicyId <= 0 || snapshotPolicyId != orderPolicyId) {
        issues.add("The locked revenue-share policy ID does not match the order's policy ID.");
      }
      details.add("Locked policy ID: " + snapshotPolicyId + "; order policy ID: "
          + (orderPolicyId == null ? "missing" : orderPolicyId));
      String snapshotCurrency = text(snapshot.get("currency")).toUpperCase(Locale.ROOT);
      String normalizedOrderCurrency = text(orderCurrency).toUpperCase(Locale.ROOT);
      if (!snapshotCurrency.isBlank() && !normalizedOrderCurrency.isBlank()
          && !snapshotCurrency.equals(normalizedOrderCurrency)) {
        issues.add("The locked revenue-share policy currency does not match the order currency.");
      }
    }

    long distributablePoolPaise = 0L;
    boolean moneyBasisValid = true;
    if (capturedAmountPaise <= 0) {
      issues.add("The verified sale has no positive recorded captured amount.");
      moneyBasisValid = false;
    }
    if (outputTaxPaise < 0 || outputTaxPaise > capturedAmountPaise) {
      issues.add("The saved output-tax amount is outside the captured payment amount.");
      moneyBasisValid = false;
    }
    if (moneyBasisValid) {
      distributablePoolPaise = Math.subtractExact(capturedAmountPaise, outputTaxPaise);
    }
    details.add("Captured payment: " + capturedAmountPaise + " paise.");
    details.add("Saved output tax: " + outputTaxPaise + " paise.");
    details.add("Expected distributable pool before separately ledgered processor-fee events: "
        + distributablePoolPaise + " paise.");

    long explicitTotalPaise = 0L;
    List<?> recipients = snapshot != null && snapshot.get("recipients") instanceof List<?> list
        ? list : List.of();
    if (recipients.isEmpty()) {
      issues.add("The locked revenue-share snapshot has no explicit recipient rows.");
    }

    Set<String> seenPolicyRecipients = new LinkedHashSet<>();
    if (moneyBasisValid && snapshot != null) {
      for (Object raw : recipients) {
        if (!(raw instanceof Map<?,?> recipient)) {
          issues.add("The locked revenue-share snapshot contains a malformed recipient row.");
          continue;
        }
        String recipientType = text(recipient.get("recipientType")).toUpperCase(Locale.ROOT);
        if (recipientType.isBlank()) recipientType = "INSTITUTION"; // Legacy snapshots predate company recipients.
        if (!"INSTITUTION".equals(recipientType) && !"COMPANY".equals(recipientType)) {
          issues.add("The locked policy contains an unsupported recipient type: " + recipientType + ".");
          continue;
        }
        long recipientId = number(recipient.get("recipientId"));
        if (recipientId <= 0) recipientId = number(
            "COMPANY".equals(recipientType) ? recipient.get("legalEntityId") : recipient.get("institutionId"));
        if (recipientId <= 0) {
          issues.add("The locked policy contains a recipient without a valid ID.");
          continue;
        }

        final String normalizedRecipientType = recipientType;
        final long normalizedRecipientId = recipientId;
        String key = key(normalizedRecipientType, normalizedRecipientId);
        if (!seenPolicyRecipients.add(key)) {
          issues.add("The locked policy lists recipient " + key + " more than once.");
          continue;
        }

        String calculationType = text(recipient.get("calculationType")).toUpperCase(Locale.ROOT);
        long amountPaise;
        if ("PERCENTAGE".equals(calculationType)) {
          long bps = number(recipient.get("percentageBasisPoints"));
          if (bps < 1 || bps > BASIS_POINTS) {
            issues.add("The percentage for recipient " + key + " is invalid.");
            continue;
          }
          amountPaise = percentageOf(distributablePoolPaise, bps);
        } else if ("FLAT".equals(calculationType)) {
          amountPaise = number(recipient.get("flatAmountPaise"));
          if (amountPaise <= 0) {
            issues.add("The fixed amount for recipient " + key + " is invalid.");
            continue;
          }
        } else {
          issues.add("The calculation type for recipient " + key + " is unsupported.");
          continue;
        }

        explicitTotalPaise = Math.addExact(explicitTotalPaise, amountPaise);
        ExpectedAllocation allocation = expected.computeIfAbsent(key,
            ignored -> new ExpectedAllocation(normalizedRecipientType, normalizedRecipientId));
        allocation.explicitAmountPaise = Math.addExact(allocation.explicitAmountPaise, amountPaise);
        allocation.amountPaise = Math.addExact(allocation.amountPaise, amountPaise);
        allocation.policyRecipientId = number(recipient.get("policyRecipientId"));
        allocation.name = text(recipient.get("recipientName"));
      }
    }

    long remainderPaise = 0L;
    long unassignedRemainderPaise = 0L;
    if (moneyBasisValid) {
      if (explicitTotalPaise > distributablePoolPaise) {
        issues.add("The explicit policy shares exceed the distributable pool.");
      } else {
        remainderPaise = distributablePoolPaise - explicitTotalPaise;
      }
    }

    String remainderType = snapshot == null ? "" :
        text(snapshot.get("remainderRecipientType")).toUpperCase(Locale.ROOT);
    long remainderId = snapshot == null ? 0L : number(snapshot.get("remainderRecipientId"));
    boolean remainderRecipientValid = ("INSTITUTION".equals(remainderType) || "COMPANY".equals(remainderType))
        && remainderId > 0;
    if (!remainderRecipientValid) {
      issues.add("The locked policy snapshot has no valid institution or company assigned to receive the remainder.");
      unassignedRemainderPaise = remainderPaise;
    } else if (remainderPaise > 0 && explicitTotalPaise <= distributablePoolPaise && moneyBasisValid) {
      String remainderKey = key(remainderType, remainderId);
      ExpectedAllocation allocation = expected.computeIfAbsent(remainderKey,
          ignored -> new ExpectedAllocation(remainderType, remainderId));
      allocation.remainderAmountPaise = Math.addExact(allocation.remainderAmountPaise, remainderPaise);
      allocation.amountPaise = Math.addExact(allocation.amountPaise, remainderPaise);
      if (allocation.name.isBlank()) allocation.name = text(snapshot.get("remainderRecipientName"));
    }

    long expectedTotalPaise = 0L;
    for (ExpectedAllocation allocation : expected.values()) {
      allocation.expectedAmountPaise = allocation.amountPaise;
      expectedTotalPaise = Math.addExact(expectedTotalPaise, allocation.amountPaise);
    }

    Map<String,List<Map<String,Object>>> recordedByRecipient = new LinkedHashMap<>();
    long recordedTotalPaise = 0L;
    long recordedEarnedEventsPaise = 0L;
    for (Map<String,Object> row : rows) {
      String recipientType = text(row.get("recipient_type")).toUpperCase(Locale.ROOT);
      long recipientId = number("COMPANY".equals(recipientType)
          ? row.get("legal_entity_id") : row.get("institution_id"));
      if (!"INSTITUTION".equals(recipientType) && !"COMPANY".equals(recipientType) || recipientId <= 0) {
        issues.add("Allocation #" + number(row.get("id")) + " has an invalid recipient identity.");
        continue;
      }
      String key = key(recipientType, recipientId);
      recordedByRecipient.computeIfAbsent(key, ignored -> new ArrayList<>()).add(row);
      String recordedCurrency = text(row.get("currency")).toUpperCase(Locale.ROOT);
      if (!text(orderCurrency).isBlank() && !recordedCurrency.equalsIgnoreCase(text(orderCurrency))) {
        issues.add("Allocation #" + number(row.get("id")) + " has a different currency from the sale.");
      }
      ExpectedAllocation expectedRecipient = expected.get(key);
      if (expectedRecipient != null && expectedRecipient.policyRecipientId > 0
          && number(row.get("policy_recipient_id")) != expectedRecipient.policyRecipientId) {
        issues.add("Allocation #" + number(row.get("id")) + " points to a different policy-recipient row.");
      }
      recordedTotalPaise = Math.addExact(recordedTotalPaise, number(row.get("allocated_amount_paise")));
      recordedEarnedEventsPaise = Math.addExact(recordedEarnedEventsPaise,
          number(row.get("earned_event_amount_paise")));

      if (orderPolicyId == null || number(row.get("policy_id")) != orderPolicyId) {
        issues.add("Allocation #" + number(row.get("id")) + " references a different revenue-share policy.");
      }
      if (capturedAmountPaise > 0 && number(row.get("actual_paid_paise")) != capturedAmountPaise) {
        issues.add("Allocation #" + number(row.get("id")) + " has a different captured-payment basis.");
      }
      if (number(row.get("output_tax_paise")) != outputTaxPaise) {
        issues.add("Allocation #" + number(row.get("id")) + " has a different output-tax basis.");
      }
      if (moneyBasisValid && number(row.get("distributable_pool_paise")) != distributablePoolPaise) {
        issues.add("Allocation #" + number(row.get("id")) + " has a different distributable-pool amount.");
      }
      long earnedCount = number(row.get("earned_event_count"));
      long earnedAmount = number(row.get("earned_event_amount_paise"));
      if (earnedCount != 1 || earnedAmount != number(row.get("allocated_amount_paise"))) {
        issues.add("Allocation #" + number(row.get("id"))
            + " does not have exactly one matching EARNED ledger event.");
      }
      if (snapshot != null && !snapshot.isEmpty() && number(snapshot.get("policyId")) != number(row.get("policy_id"))) {
        issues.add("Allocation #" + number(row.get("id")) + " does not match the order's locked policy snapshot.");
      }
    }

    Set<String> allKeys = new LinkedHashSet<>();
    allKeys.addAll(expected.keySet());
    allKeys.addAll(recordedByRecipient.keySet());
    for (String key : allKeys) {
      ExpectedAllocation expectation = expected.get(key);
      List<Map<String,Object>> actualRows = recordedByRecipient.getOrDefault(key, List.of());
      long actualAmount = actualRows.stream()
          .mapToLong(row -> number(row.get("allocated_amount_paise"))).sum();
      long expectedAmount = expectation == null ? 0L : expectation.expectedAmountPaise;
      if (expectation == null) {
        issues.add("Unexpected allocation exists for recipient " + key + ".");
      } else if (actualRows.isEmpty()) {
        issues.add("Expected recipient " + key + " has no allocation row.");
      } else if (actualAmount != expectedAmount) {
        issues.add("Recipient " + key + " is allocated " + actualAmount
            + " paise but the locked policy expects " + expectedAmount + " paise.");
      }
      if (actualRows.size() > 1) {
        issues.add("Recipient " + key + " has " + actualRows.size()
            + " allocation rows; one recipient must have one sale allocation.");
      }
      String name = expectation == null ? "" : expectation.name;
      details.add((name.isBlank() ? key : name + " (" + key + ")")
          + ": expected " + expectedAmount + " paise; recorded " + actualAmount
          + " paise; allocation rows " + actualRows.size() + ".");
    }

    if (remainderRecipientValid && moneyBasisValid && explicitTotalPaise <= distributablePoolPaise
        && expectedTotalPaise != distributablePoolPaise) {
      issues.add("Expected allocations do not consume the complete distributable pool.");
    }
    if (recordedTotalPaise != expectedTotalPaise) {
      issues.add("Recorded allocation total " + recordedTotalPaise
          + " paise does not match expected allocation total " + expectedTotalPaise + " paise.");
    }
    if (remainderRecipientValid && moneyBasisValid && explicitTotalPaise <= distributablePoolPaise
        && recordedTotalPaise != distributablePoolPaise) {
      issues.add("The recorded allocation ledger does not account for the complete distributable pool.");
    }

    details.add("Expected recipient count: " + expected.size() + "; recorded recipient count: "
        + recordedByRecipient.size() + ".");
    details.add("Explicit shares: " + explicitTotalPaise + " paise; remainder: " + remainderPaise
        + " paise; unassigned remainder: " + unassignedRemainderPaise + " paise.");
    details.add("Expected allocated total: " + expectedTotalPaise + " paise; recorded allocated total: "
        + recordedTotalPaise + " paise; recorded EARNED events: " + recordedEarnedEventsPaise + " paise.");

    // Keep the response actionable while avoiding an unbounded diagnostic payload.
    List<String> uniqueIssues = new ArrayList<>(new LinkedHashSet<>(issues));
    details.addAll(uniqueIssues.stream().limit(20).map(issue -> "EXCEPTION: " + issue).toList());
    return new Result(
        uniqueIssues.isEmpty(), distributablePoolPaise, explicitTotalPaise, remainderPaise,
        unassignedRemainderPaise, expectedTotalPaise, recordedTotalPaise,
        recordedEarnedEventsPaise, expected.size(), recordedByRecipient.size(),
        uniqueIssues, details);
  }

  private static long percentageOf(long poolPaise, long basisPoints) {
    return BigDecimal.valueOf(poolPaise)
        .multiply(BigDecimal.valueOf(basisPoints))
        .divide(BigDecimal.valueOf(BASIS_POINTS), 0, RoundingMode.HALF_UP)
        .longValueExact();
  }

  private static long number(Object value) {
    if (value instanceof Number number) return number.longValue();
    if (value == null) return 0L;
    try {
      return Long.parseLong(String.valueOf(value).trim());
    } catch (NumberFormatException ignored) {
      return 0L;
    }
  }

  private static String text(Object value) {
    return value == null ? "" : String.valueOf(value).trim();
  }

  private static String key(String recipientType, long recipientId) {
    return recipientType + ":" + recipientId;
  }

  private static final class ExpectedAllocation {
    private final String recipientType;
    private final long recipientId;
    private long explicitAmountPaise;
    private long remainderAmountPaise;
    private long amountPaise;
    private long expectedAmountPaise;
    private long policyRecipientId;
    private String name = "";

    private ExpectedAllocation(String recipientType, long recipientId) {
      this.recipientType = recipientType;
      this.recipientId = recipientId;
    }
  }

  record Result(
      boolean complete,
      long distributablePoolPaise,
      long explicitAmountPaise,
      long remainderPaise,
      long unassignedRemainderPaise,
      long expectedTotalPaise,
      long recordedTotalPaise,
      long recordedEarnedEventsPaise,
      int expectedRecipientCount,
      int recordedRecipientCount,
      List<String> issues,
      List<String> details) {
    Result {
      issues = List.copyOf(issues);
      details = List.copyOf(details);
    }

    Map<String,Object> asMap() {
      Map<String,Object> result = new LinkedHashMap<>();
      result.put("status", complete ? "RECONCILED" : "REVIEW_REQUIRED");
      result.put("complete", complete);
      result.put("distributablePoolPaise", distributablePoolPaise);
      result.put("explicitAmountPaise", explicitAmountPaise);
      result.put("remainderPaise", remainderPaise);
      result.put("unassignedRemainderPaise", unassignedRemainderPaise);
      result.put("expectedTotalPaise", expectedTotalPaise);
      result.put("recordedTotalPaise", recordedTotalPaise);
      result.put("recordedEarnedEventsPaise", recordedEarnedEventsPaise);
      result.put("expectedRecipientCount", expectedRecipientCount);
      result.put("recordedRecipientCount", recordedRecipientCount);
      result.put("issues", issues);
      result.put("details", details);
      return result;
    }
  }
}
