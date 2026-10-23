package com.quantaedge.api;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Read-only runtime dependency census for account-deletion review.
 *
 * The table-name classification is intentionally a triage aid, not an approved data-retention
 * policy. This service never deletes or updates user data and always returns
 * automaticFulfillmentAllowed=false.
 */
@Service
public class AccountDeletionDependencyAssessmentService {
  private static final String FK_INVENTORY_SQL = """
      select source_ns.nspname as source_schema,
             source.relname as source_table,
             constraint_row.conname as constraint_name,
             target.relname as target_table,
             string_agg(source_column.attname, chr(31) order by source_key.ordinality) as source_columns,
             string_agg(target_column.attname, chr(31) order by source_key.ordinality) as target_columns,
             count(*) as column_count,
             constraint_row.confdeltype::text as delete_action
      from pg_constraint constraint_row
      join pg_class source on source.oid=constraint_row.conrelid
      join pg_namespace source_ns on source_ns.oid=source.relnamespace
      join pg_class target on target.oid=constraint_row.confrelid
      join pg_namespace target_ns on target_ns.oid=target.relnamespace
      join lateral unnest(constraint_row.conkey) with ordinality source_key(attnum,ordinality) on true
      join lateral unnest(constraint_row.confkey) with ordinality target_key(attnum,ordinality)
        on target_key.ordinality=source_key.ordinality
      join pg_attribute source_column
        on source_column.attrelid=source.oid and source_column.attnum=source_key.attnum
      join pg_attribute target_column
        on target_column.attrelid=target.oid and target_column.attnum=target_key.attnum
      where constraint_row.contype='f'
        and target_ns.nspname='public'
        and target.relname in ('user_account','student')
        and source_ns.nspname not in ('pg_catalog','information_schema')
        and source.relkind in ('r','p')
      group by source_ns.nspname,source.relname,constraint_row.conname,target.relname,
               target_ns.nspname,constraint_row.confdeltype
      order by source_ns.nspname,source.relname,constraint_row.conname
      """;

  private final JdbcTemplate jdbc;

  public AccountDeletionDependencyAssessmentService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Map<String,Object> assess(UUID requestId) {
    List<Map<String,Object>> requestRows = jdbc.queryForList("""
        select request_id,subject_user_id,subject_user_public_id,subject_student_id,
               subject_student_public_user_id,status,includes_linked_children,linked_children_count
        from account_deletion_request where request_id=?
        """, requestId);
    if (requestRows.isEmpty()) {
      throw new IllegalArgumentException("Account-deletion request was not found.");
    }

    Map<String,Object> request = requestRows.getFirst();
    long userId = number(request.get("subject_user_id"));
    LinkedHashSet<Long> studentIds = new LinkedHashSet<>();
    addIfPresent(studentIds, request.get("subject_student_id"));

    List<Map<String,Object>> snapshots = jdbc.queryForList("""
        select student_id,student_public_user_id,resolution
        from account_deletion_request_child where request_id=?
        order by student_public_user_id
        """, requestId);
    for (Map<String,Object> child : snapshots) addIfPresent(studentIds, child.get("student_id"));

    // Include newly linked children as well as the request-time snapshot. A child added after
    // the request must not silently fall outside the impact assessment.
    List<Map<String,Object>> currentChildren = jdbc.queryForList("""
        select distinct st.id as student_id
        from student st
        where st.active=true and st.user_account_id is null
          and (
            exists (select 1 from guardian_student gs
                    where gs.guardian_user_id=? and gs.student_id=st.id
                      and gs.active=true and gs.consent_status<>'REVOKED')
            or exists (select 1 from student_login_credential slc
                       where slc.guardian_user_id=? and slc.student_id=st.id and slc.active=true)
          )
        order by st.id
        """, userId, userId);
    for (Map<String,Object> child : currentChildren) addIfPresent(studentIds, child.get("student_id"));

    List<Map<String,Object>> inventory = jdbc.queryForList(FK_INVENTORY_SQL);
    List<Map<String,Object>> affected = new ArrayList<>();
    int uncounted = 0;
    int unclassified = 0;
    int withRows = 0;
    long totalRelatedRows = 0L;

    for (Map<String,Object> edge : inventory) {
      String targetTable = String.valueOf(edge.get("target_table"));
      List<Long> targetIds = "user_account".equals(targetTable)
          ? List.of(userId)
          : List.copyOf(studentIds);
      if (targetIds.isEmpty()) continue;

      String[] sourceColumns = columns(edge.get("source_columns"));
      String[] targetColumns = columns(edge.get("target_columns"));
      long columnCount = number(edge.get("column_count"));
      String sourceSchema = String.valueOf(edge.get("source_schema"));
      String sourceTable = String.valueOf(edge.get("source_table"));
      String constraintName = String.valueOf(edge.get("constraint_name"));
      Long rowCount = null;
      String countIssue = null;

      if (sourceColumns.length == 0 || sourceColumns.length != targetColumns.length
          || sourceColumns.length != columnCount
          || Arrays.stream(targetColumns).filter("id"::equals).count() != 1) {
        countIssue = "The foreign key does not reference a single identifiable row key; manual review required.";
        uncounted++;
      } else {
        StringBuilder join = new StringBuilder();
        for (int i = 0; i < sourceColumns.length; i++) {
          if (i > 0) join.append(" and ");
          join.append("src.").append(quote(sourceColumns[i]))
              .append("=dst.").append(quote(targetColumns[i]));
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(targetIds.size(), "?"));
        String sql = "select count(*) from " + quote(sourceSchema) + "." + quote(sourceTable)
            + " src join public." + quote(targetTable) + " dst on " + join
            + " where dst.\"id\" in (" + placeholders + ")";
        List<Object> args = new ArrayList<>(targetIds);
        try {
          Number count = jdbc.queryForObject(sql, Long.class, args.toArray());
          rowCount = count == null ? 0L : count.longValue();
        } catch (RuntimeException ex) {
          // Fail closed in the assessment: a permission/query problem must never look like zero
          // dependencies. Do not return database values or exception text to the UI.
          countIssue = "Row count could not be verified; manual review required.";
          uncounted++;
        }
      }

      String classification = classify(sourceTable);
      if (rowCount == null || rowCount > 0) {
        Map<String,Object> item = new LinkedHashMap<>();
        item.put("schema", sourceSchema);
        item.put("table", sourceTable);
        item.put("constraint", constraintName);
        item.put("target", targetTable);
        item.put("sourceColumns", List.of(sourceColumns));
        item.put("targetColumns", List.of(targetColumns));
        item.put("deleteRule", deleteRule(edge.get("delete_action")));
        item.put("classification", classification);
        item.put("rowCount", rowCount);
        item.put("countIssue", countIssue);
        item.put("manualReviewRequired", true);
        affected.add(item);
        if (rowCount == null || rowCount > 0) {
          if (rowCount == null || "UNKNOWN".equals(classification)) unclassified++;
        }
        if (rowCount != null && rowCount > 0) {
          withRows++;
          totalRelatedRows += rowCount;
        }
      }
    }

    Map<String,Object> summary = new LinkedHashMap<>();
    summary.put("foreignKeyRelationshipsInspected", inventory.size());
    summary.put("affectedRelationships", affected.size());
    summary.put("relationshipsWithRows", withRows);
    summary.put("relatedRowReferences", totalRelatedRows);
    summary.put("uncountedRelationships", uncounted);
    summary.put("unclassifiedRelationships", unclassified);
    summary.put("linkedChildSnapshotCount", snapshots.size());
    summary.put("currentlyLinkedChildCount", currentChildren.size());
    summary.put("childSetChangedSinceRequest", snapshots.size() != currentChildren.size()
        || !sameChildSet(snapshots, currentChildren));

    Map<String,Object> result = new LinkedHashMap<>();
    result.put("requestId", requestId.toString());
    result.put("requestStatus", request.get("status"));
    result.put("subjectPublicUserId", request.get("subject_user_public_id"));
    result.put("includesLinkedChildren", Boolean.TRUE.equals(request.get("includes_linked_children")));
    result.put("assessmentStatus", uncounted > 0 || unclassified > 0
        ? "MANUAL_REVIEW_REQUIRED" : "RETENTION_AND_ERASURE_REVIEW_REQUIRED");
    result.put("automaticFulfillmentAllowed", false);
    result.put("canMarkCompleted", false);
    result.put("erasureExecuted", false);
    result.put("classificationIsHeuristic", true);
    result.put("message", "This is a read-only database dependency census. Table classifications are triage hints, not legal retention decisions. No account, learner, session, certificate, payment or history data was changed.");
    result.put("summary", summary);
    result.put("dependencies", affected);
    return result;
  }

  static String classify(String table) {
    String value = table == null ? "" : table.toLowerCase(java.util.Locale.ROOT);
    if (value.contains("payment") || value.contains("purchase") || value.contains("entitlement")
        || value.contains("billing") || value.contains("fiscal_document") || value.contains("refund")
        || value.contains("revenue_share") || value.contains("billable_event") || value.contains("discount")) {
      return "FINANCIAL_OR_PURCHASE_RECORD";
    }
    if (value.contains("certificate")) return "CERTIFICATE_RECORD";
    if (value.contains("audit") || value.endsWith("_event") || value.endsWith("_log")
        || value.contains("webhook") || value.contains("deletion_request")) {
      return "AUDIT_OR_OPERATIONAL_RECORD";
    }
    if (Set.of("user_account", "student", "auth_session", "otp_challenge",
        "student_login_credential", "guardian_student", "notification_device_registration",
        "institution_membership", "institution_user_membership", "user_parent_eligibility",
        "student_adult_verification").contains(value)) {
      return "IDENTITY_OR_ACCESS_RECORD";
    }
    if (value.startsWith("student_") || value.startsWith("tutor_")
        || value.contains("assessment") || value.contains("learning_session")
        || value.contains("concept_mastery") || value.contains("practice_session")) {
      return "LEARNING_HISTORY";
    }
    return "UNKNOWN";
  }

  private static String deleteRule(Object raw) {
    String value = String.valueOf(raw);
    return switch (value) {
      case "a" -> "NO_ACTION";
      case "r" -> "RESTRICT";
      case "c" -> "CASCADE";
      case "n" -> "SET_NULL";
      case "d" -> "SET_DEFAULT";
      default -> "UNKNOWN";
    };
  }

  private static String[] columns(Object raw) {
    if (raw == null) return new String[0];
    String value = String.valueOf(raw);
    return value.isBlank() ? new String[0] : value.split(Character.toString(31), -1);
  }

  private static String quote(String identifier) {
    return "\"" + identifier.replace("\"", "\"\"") + "\"";
  }

  private static long number(Object raw) {
    if (raw instanceof Number value) return value.longValue();
    if (raw == null) throw new IllegalStateException("The account-deletion request has no subject reference.");
    return Long.parseLong(String.valueOf(raw));
  }

  private static void addIfPresent(Set<Long> values, Object raw) {
    if (raw instanceof Number value) values.add(value.longValue());
    else if (raw != null) values.add(Long.parseLong(String.valueOf(raw)));
  }

  private static boolean sameChildSet(List<Map<String,Object>> snapshots, List<Map<String,Object>> current) {
    Set<Long> snapshotIds = new LinkedHashSet<>();
    for (Map<String,Object> child : snapshots) addIfPresent(snapshotIds, child.get("student_id"));
    Set<Long> currentIds = new LinkedHashSet<>();
    for (Map<String,Object> child : current) addIfPresent(currentIds, child.get("student_id"));
    return snapshotIds.equals(currentIds);
  }
}
