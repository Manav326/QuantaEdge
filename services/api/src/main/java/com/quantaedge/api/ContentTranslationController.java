package com.quantaedge.api;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/v1/admin/translations")
public class ContentTranslationController {
  private static final Set<String> VIEW_PERMISSIONS = Set.of("CONTENT_VIEW", "CONTENT_REVIEW", "CONTENT_EDIT");
  private static final Set<String> TRANSLATION_STATUSES =
      Set.of("DRAFT", "IN_REVIEW", "APPROVED", "PUBLISHED", "REJECTED", "SUPERSEDED");
  private static final Set<String> ENTITY_TYPES = Set.of(
      "curriculum_class", "curriculum_program", "curriculum_subject", "curriculum_chapter",
      "learning_objective", "lesson", "lesson_block", "question", "assessment",
      "assessment_question", "learning_class_session", "recorded_class");
  private static final Set<String> PROTECTED_KEYS = Set.of(
      "id", "key", "code", "url", "href", "src", "assetid", "asset_id", "storagekey",
      "correctoption", "correct_option", "correctanswer", "correct_answer",
      "answerpayload", "answer_payload", "answerkey", "answer_key", "expectedanswer",
      "expected_answer", "kind", "type", "questiontype", "question_type",
      "formula", "latex", "mathml", "sha256", "source_ref", "source_year",
      "exam_format", "difficulty", "option_key", "is_correct", "correct",
      "response_mode", "status", "sequence_no", "sequence", "sort_order",
      "marks", "max_marks", "asset_url", "asset_alt", "image_url", "video_url",
      "audio_url", "mime_type");
  private static final Pattern NUMBER_PATTERN =
      Pattern.compile("(?<![A-Za-z])[-+]?\\d+(?:[.,]\\d+)?(?:\\s*/\\s*\\d+)?");
  private static final Pattern HTML_TAG_PATTERN = Pattern.compile("</?[A-Za-z][^>]*>");

  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;
  private final AuthorizationService authorization;
  private final StaffAuditService staffAudit;

  public ContentTranslationController(
      JdbcTemplate jdbc, ObjectMapper mapper, AuthorizationService authorization, StaffAuditService staffAudit) {
    this.jdbc = jdbc;
    this.mapper = mapper;
    this.authorization = authorization;
    this.staffAudit = staffAudit;
  }

  @GetMapping
  public Map<String, Object> list(
      @RequestParam(required = false) String classCode,
      @RequestParam(required = false) String subjectCode,
      @RequestParam(required = false, defaultValue = "en") String language,
      @RequestParam(required = false, defaultValue = "ALL") String status,
      @RequestParam(required = false, defaultValue = "ALL") String entityType,
      @RequestParam(required = false, defaultValue = "100") Integer limit,
      @RequestParam(required = false, defaultValue = "0") Integer offset,
      @RequestAttribute(value = "authContext", required = false) AuthContext context) {
    requireTranslationView(context);
    String normalizedLanguage = normalizeLanguageFilter(language);
    String normalizedStatus = normalizeStatusFilter(status);
    String normalizedEntity = normalizeEntityFilter(entityType);
    int rowLimit = Math.max(1, Math.min(limit == null ? 100 : limit, 250));
    int rowOffset = Math.max(0, Math.min(offset == null ? 0 : offset, 10_000_000));
    String normalizedClass = safeFilter(classCode, 30);
    String normalizedSubject = safeFilter(subjectCode, 40);

    String with = translationRowsSql();
    String where = """
      where (? = 'ALL' or language = ?)
        and ((? = 'ALL' and translation_status <> 'SUPERSEDED')
          or (? <> 'ALL' and translation_status = ?))
        and (? = '' or class_code = ?)
        and (? = '' or subject_code = ?)
        and (? = 'ALL' or entity_type = ?)
      """;
    List<Object> filterArgs = new ArrayList<>();
    filterArgs.add(normalizedLanguage); filterArgs.add(normalizedLanguage);
    filterArgs.add(normalizedStatus); filterArgs.add(normalizedStatus); filterArgs.add(normalizedStatus);
    filterArgs.add(normalizedClass); filterArgs.add(normalizedClass);
    filterArgs.add(normalizedSubject); filterArgs.add(normalizedSubject);
    filterArgs.add(normalizedEntity); filterArgs.add(normalizedEntity);

    List<Object> pageArgs = new ArrayList<>(filterArgs);
    pageArgs.add(rowLimit);
    pageArgs.add(rowOffset);
    List<Map<String, Object>> items = jdbc.queryForList(
        with + " select tr.*,tr.source_content::text as source_content_text,tr.translated_content::text as translated_content_text from translation_rows tr " + where
            + " order by case translation_status when 'IN_REVIEW' then 0 when 'REJECTED' then 1 "
            + "when 'DRAFT' then 2 when 'APPROVED' then 3 when 'PUBLISHED' then 4 else 5 end,"
            + " class_code,subject_code,chapter_code,lesson_title,entity_type,entity_id,version_no desc"
            + " limit ? offset ?",
        pageArgs.toArray());
    Long total = jdbc.queryForObject(
        with + " select count(*) from translation_rows " + where,
        Long.class, filterArgs.toArray());
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("items", items);
    response.put("total", total == null ? 0L : total);
    response.put("limit", rowLimit);
    response.put("offset", rowOffset);
    response.put("language", normalizedLanguage);
    return response;
  }


  /**
   * Returns current student-facing source records that do not yet have a translation
   * for the selected target language/source revision. These are shown as NOT_STARTED,
   * never as a translation or as student-ready content.
   */
  @GetMapping("/inventory")
  public Map<String, Object> inventory(
      @RequestParam(required = false, defaultValue = "ALL") String classCode,
      @RequestParam(required = false, defaultValue = "ALL") String subjectCode,
      @RequestParam(required = false, defaultValue = "ALL") String language,
      @RequestParam(required = false, defaultValue = "ALL") String entityType,
      @RequestParam(required = false, defaultValue = "100") Integer limit,
      @RequestParam(required = false, defaultValue = "0") Integer offset,
      @RequestAttribute(value = "authContext", required = false) AuthContext context) {
    requireTranslationView(context);
    String normalizedLanguage = normalizeLanguageFilter(language);
    String normalizedEntity = normalizeEntityFilter(entityType);
    String normalizedClass = safeFilter(classCode, 30);
    String normalizedSubject = safeFilter(subjectCode, 40);
    int rowLimit = Math.max(1, Math.min(limit == null ? 100 : limit, 250));
    int rowOffset = Math.max(0, Math.min(offset == null ? 0 : offset, 10_000_000));
    String base = translationInventorySql();
    String where = """
      where (? = 'ALL' or language = ?)
        and (? = '' or class_code = ?)
        and (? = '' or subject_code = ?)
        and (? = 'ALL' or entity_type = ?)
      """;
    List<Object> filterArgs = new ArrayList<>();
    filterArgs.add(normalizedLanguage); filterArgs.add(normalizedLanguage);
    filterArgs.add(normalizedClass); filterArgs.add(normalizedClass);
    filterArgs.add(normalizedSubject); filterArgs.add(normalizedSubject);
    filterArgs.add(normalizedEntity); filterArgs.add(normalizedEntity);
    List<Object> pageArgs = new ArrayList<>(filterArgs);
    pageArgs.add(rowLimit); pageArgs.add(rowOffset);
    List<Map<String, Object>> items = jdbc.queryForList(
        base + " select tr.*,tr.source_content::text as source_content_text,"
            + "tr.translated_content::text as translated_content_text from translation_inventory tr " + where
            + " order by class_code,subject_code,chapter_code,lesson_title,entity_type,entity_id,language limit ? offset ?",
        pageArgs.toArray());
    Long total = jdbc.queryForObject(
        base + " select count(*) from translation_inventory " + where,
        Long.class, filterArgs.toArray());
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("items", items);
    response.put("total", total == null ? 0L : total);
    response.put("limit", rowLimit);
    response.put("offset", rowOffset);
    response.put("language", normalizedLanguage);
    return response;
  }

  @PostMapping("/{entityType}/{entityId}/draft")
  @Transactional
  public Map<String, Object> createDraft(
      @PathVariable String entityType,
      @PathVariable long entityId,
      @RequestParam(defaultValue = "en") String language,
      @RequestBody Map<String, Object> body,
      @RequestAttribute(value = "authContext", required = false) AuthContext context) {
    context = authorization.requireAuth(context);
    if (!authorization.hasPermission(context, "CONTENT_EDIT")
        && !authorization.hasPermission(context, "CONTENT_REVIEW")) {
      throw new SecurityException("Content translation editing access required.");
    }
    String type = normalizeEntityType(entityType);
    TableSpec spec = tableSpec(type);
    String targetLanguage = normalizeLanguage(language);
    SourceDescriptor source = currentSource(type, entityId);
    Object translatedRaw = body.get("translatedContent");
    if (!(translatedRaw instanceof Map<?, ?> translatedMap)) {
      throw badRequest("translatedContent must be a JSON object.");
    }
    @SuppressWarnings("unchecked")
    Map<String, Object> translated = (Map<String, Object>) translatedMap;
    validateContentShape(source.content(), translated, targetLanguage);
    validateRequiredFields(type, translated);
    String notes = optionalText(body.get("qualityNotes"), 2000);
    Integer maxVersion = jdbc.queryForObject(
        "select coalesce(max(version_no),0) from " + spec.table()
            + " where " + spec.foreignKey() + "=? and language=? and source_hash=?",
        Integer.class, entityId, targetLanguage, source.hash());
    int version = (maxVersion == null ? 0 : maxVersion) + 1;
    long translationId = insertDraft(spec, entityId, targetLanguage, "mixed", source.hash(),
        version, translated, notes, context.staffId());
    staffAudit.recordAction(context, "/api/v1/admin/translations/" + type + "/" + entityId + "/draft",
        "Created a " + targetLanguage + " translation draft for " + type + " #" + entityId + ".");
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("translationId", translationId);
    response.put("entityType", type);
    response.put("entityId", entityId);
    response.put("language", targetLanguage);
    response.put("sourceHash", source.hash());
    response.put("versionNo", version);
    response.put("translationStatus", "DRAFT");
    response.put("createdNewVersion", true);
    return response;
  }

  @PatchMapping("/{entityType}/{translationId}/draft")
  @Transactional
  public Map<String, Object> saveDraft(
      @PathVariable String entityType,
      @PathVariable long translationId,
      @RequestBody Map<String, Object> body,
      @RequestAttribute(value = "authContext", required = false) AuthContext context) {
    context = authorization.requireAuth(context);
    if (!authorization.hasPermission(context, "CONTENT_EDIT")
        && !authorization.hasPermission(context, "CONTENT_REVIEW")) {
      throw new SecurityException("Content translation editing access required.");
    }
    String type = normalizeEntityType(entityType);
    TableSpec spec = tableSpec(type);
    Map<String, Object> row = translationRow(spec, translationId, true);
    String currentStatus = String.valueOf(row.get("translation_status"));
    if ("SUPERSEDED".equals(currentStatus)) {
      throw conflict("This translation version is historical. Open the current version or generate a new draft.");
    }

    long entityId = ((Number) row.get("entity_id")).longValue();
    SourceDescriptor source = currentSource(type, entityId);
    String storedHash = String.valueOf(row.get("source_hash")).trim();
    if (!storedHash.equals(source.hash())) {
      throw conflict("The source content changed after this translation was generated. Regenerate a translation for the current source before editing or publishing it.");
    }

    Object translatedRaw = body.get("translatedContent");
    if (!(translatedRaw instanceof Map<?, ?> translatedMap)) {
      throw badRequest("translatedContent must be a JSON object.");
    }
    @SuppressWarnings("unchecked")
    Map<String, Object> translated = (Map<String, Object>) translatedMap;
    validateContentShape(source.content(), translated, String.valueOf(row.get("language")));
    validateRequiredFields(type, translated);
    String notes = optionalText(body.get("qualityNotes"), 2000);

    long savedId;
    int savedVersion;
    if ("PUBLISHED".equals(currentStatus) || "APPROVED".equals(currentStatus)) {
      Integer maxVersion = jdbc.queryForObject(
          "select coalesce(max(version_no),0) from " + spec.table()
              + " where " + spec.foreignKey() + "=? and language=? and source_hash=?",
          Integer.class, entityId, row.get("language"), storedHash);
      savedVersion = (maxVersion == null ? 0 : maxVersion) + 1;
      savedId = insertDraft(spec, entityId, String.valueOf(row.get("language")),
          String.valueOf(row.get("source_language")), storedHash, savedVersion, translated,
          notes, context.staffId());
    } else {
      savedId = translationId;
      savedVersion = ((Number) row.get("version_no")).intValue();
      List<Object> args = new ArrayList<>();
      StringBuilder sql = new StringBuilder("update ").append(spec.table())
          .append(" set ");
      for (int i = 0; i < spec.fields().size(); i++) {
        FieldSpec field = spec.fields().get(i);
        if (i > 0) sql.append(',');
        sql.append(field.column()).append("=?");
        if (field.jsonb()) sql.append("::jsonb");
        args.add(valueForField(translated.get(field.column()), field, true));
      }
      sql.append(",translation_status='DRAFT',translation_method='HUMAN',model_name=null,quality_notes=?,")
          .append("reviewed_by_staff_id=null,reviewed_at=null,published_by_staff_id=null,published_at=null,")
          .append("updated_at=now() where id=?");
      args.add(notes);
      args.add(translationId);
      jdbc.update(sql.toString(), args.toArray());
    }

    staffAudit.recordAction(context, "/api/v1/admin/translations/" + type + "/" + translationId + "/draft",
        "Saved a " + String.valueOf(row.get("language")) + " translation draft for " + type + " #" + entityId + ".");
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("translationId", savedId);
    response.put("entityType", type);
    response.put("entityId", entityId);
    response.put("language", row.get("language"));
    response.put("sourceHash", storedHash);
    response.put("versionNo", savedVersion);
    response.put("translationStatus", "DRAFT");
    response.put("createdNewVersion", savedId != translationId);
    return response;
  }

  @PatchMapping("/{entityType}/{translationId}/status")
  @Transactional
  public Map<String, Object> changeStatus(
      @PathVariable String entityType,
      @PathVariable long translationId,
      @RequestBody Map<String, Object> body,
      @RequestAttribute(value = "authContext", required = false) AuthContext context) {
    context = authorization.requireAuth(context);
    String type = normalizeEntityType(entityType);
    TableSpec spec = tableSpec(type);
    String next = requiredText(body.get("status"), 20).toUpperCase(Locale.ROOT);
    if (!TRANSLATION_STATUSES.contains(next) || "SUPERSEDED".equals(next)) {
      throw badRequest("Choose DRAFT, IN_REVIEW, APPROVED, REJECTED, or PUBLISHED.");
    }
    Map<String, Object> row = translationRow(spec, translationId, true);
    String previous = String.valueOf(row.get("translation_status"));
    long entityId = ((Number) row.get("entity_id")).longValue();
    String language = String.valueOf(row.get("language"));
    String sourceHash = String.valueOf(row.get("source_hash")).trim();

    if (Set.of("APPROVED", "PUBLISHED").contains(next)) {
      SourceDescriptor source = currentSource(type, entityId);
      if (!source.hash().equals(sourceHash)) {
        throw conflict("The translated source has changed. A stale translation cannot be approved or published.");
      }
    }

    switch (next) {
      case "IN_REVIEW" -> {
        if (!Set.of("DRAFT", "REJECTED").contains(previous)) {
          throw conflict("Only a draft or rejected translation can be submitted for review.");
        }
        if (!authorization.hasPermission(context, "CONTENT_SUBMIT")
            && !authorization.hasPermission(context, "CONTENT_REVIEW")) {
          throw new SecurityException("Content submission permission required.");
        }
      }
      case "APPROVED", "REJECTED" -> {
        if (!"IN_REVIEW".equals(previous)) {
          throw conflict("A translation must be in review before a reviewer can approve or reject it.");
        }
        authorization.requirePermission(context, "CONTENT_REVIEW");
      }
      case "PUBLISHED" -> {
        if (!"APPROVED".equals(previous)) {
          throw conflict("Only an approved translation can be published.");
        }
        authorization.requirePermission(context, "CONTENT_PUBLISH");
      }
      case "DRAFT" -> {
        if ("PUBLISHED".equals(previous)) {
          throw conflict("Published translations cannot be moved back to draft. Save a new version instead.");
        }
        if (!authorization.hasPermission(context, "CONTENT_EDIT")
            && !authorization.hasPermission(context, "CONTENT_REVIEW")) {
          throw new SecurityException("Content editing permission required.");
        }
      }
      default -> throw badRequest("Unsupported translation status.");
    }

    if ("PUBLISHED".equals(next)) {
      jdbc.update("update " + spec.table()
              + " set translation_status='SUPERSEDED',updated_at=now() where "
              + spec.foreignKey() + "=? and language=? and source_hash=? and translation_status='PUBLISHED' and id<>?",
          entityId, language, sourceHash, translationId);
    }

    if ("APPROVED".equals(next) || "REJECTED".equals(next)) {
      jdbc.update("update " + spec.table() + """
          set translation_status=?,reviewed_by_staff_id=?,reviewed_at=now(),quality_notes=coalesce(?,quality_notes),updated_at=now()
          where id=?
          """, next, context.staffId(), optionalText(body.get("qualityNotes"), 2000), translationId);
    } else if ("PUBLISHED".equals(next)) {
      jdbc.update("update " + spec.table() + """
          set translation_status='PUBLISHED',published_by_staff_id=?,published_at=now(),updated_at=now()
          where id=?
          """, context.staffId(), translationId);
    } else {
      jdbc.update("update " + spec.table() + " set translation_status=?,updated_at=now() where id=?",
          next, translationId);
    }

    staffAudit.recordAction(context, "/api/v1/admin/translations/" + type + "/" + translationId + "/status",
        "Changed " + language + " " + type + " translation #" + translationId + " from " + previous + " to " + next + ".");
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("translationId", translationId);
    response.put("entityType", type);
    response.put("entityId", entityId);
    response.put("language", language);
    response.put("previousStatus", previous);
    response.put("status", next);
    return response;
  }


  private String translationInventorySql() {
    return """
      with sources as (
        select 'curriculum_class'::text entity_type,c.id::bigint entity_id,
          md5(jsonb_build_object('display_name',c.display_name)::text) source_hash,
          jsonb_build_object('display_name',c.display_name) source_content,
          c.code::text class_code,null::text subject_code,null::text chapter_code,null::text chapter_name,null::text lesson_title
        from curriculum_class c where c.active=true

        union all
        select 'curriculum_program',p.id,md5(jsonb_build_object('display_name',p.display_name)::text),
          jsonb_build_object('display_name',p.display_name),c.code::text,null::text,null::text,p.display_name::text,null::text
        from curriculum_program p join curriculum_class c on c.id=p.class_id
        where c.active=true and p.status<>'ARCHIVED'

        union all
        select 'curriculum_subject',s.id,md5(jsonb_build_object('display_name',s.display_name)::text),
          jsonb_build_object('display_name',s.display_name),c.code::text,s.code::text,null::text,null::text,null::text
        from curriculum_subject s join curriculum_class c on c.id=s.class_id
        where c.active=true and s.active=true and s.code in ('maths','science')

        union all
        select 'curriculum_chapter',ch.id,md5(jsonb_build_object('display_name',ch.display_name,'description',ch.description)::text),
          jsonb_build_object('display_name',ch.display_name,'description',ch.description),
          c.code::text,s.code::text,ch.code::text,ch.display_name::text,null::text
        from curriculum_chapter ch join curriculum_subject s on s.id=ch.subject_id
        join curriculum_class c on c.id=s.class_id
        where c.active=true and s.active=true and s.code in ('maths','science')
          and ch.active=true and ch.content_status<>'ARCHIVED'

        union all
        select 'learning_objective',o.id,md5(jsonb_build_object('title',o.title,'description',o.description)::text),
          jsonb_build_object('title',o.title,'description',o.description),
          c.code::text,s.code::text,ch.code::text,ch.display_name::text,o.title::text
        from learning_objective o join curriculum_chapter ch on ch.id=o.chapter_id
        join curriculum_subject s on s.id=ch.subject_id join curriculum_class c on c.id=s.class_id
        where c.active=true and s.active=true and s.code in ('maths','science') and ch.active=true
          and ch.content_status<>'ARCHIVED' and o.active=true

        union all
        select 'lesson',l.id,md5(jsonb_build_object('title',l.title,'summary',l.summary)::text),
          jsonb_build_object('title',l.title,'summary',l.summary),
          c.code::text,s.code::text,ch.code::text,ch.display_name::text,l.title::text
        from lesson l join curriculum_chapter ch on ch.id=l.chapter_id
        join curriculum_subject s on s.id=ch.subject_id join curriculum_class c on c.id=s.class_id
        where c.active=true and s.active=true and s.code in ('maths','science') and ch.active=true
          and ch.content_status<>'ARCHIVED' and l.active=true and l.status<>'ARCHIVED'

        union all
        select 'lesson_block',b.id,md5(b.content::text),jsonb_build_object('content',b.content),
          c.code::text,s.code::text,ch.code::text,ch.display_name::text,l.title::text
        from lesson_block b join lesson l on l.id=b.lesson_id
        join curriculum_chapter ch on ch.id=l.chapter_id
        join curriculum_subject s on s.id=ch.subject_id join curriculum_class c on c.id=s.class_id
        where c.active=true and s.active=true and s.code in ('maths','science') and ch.active=true
          and ch.content_status<>'ARCHIVED' and l.active=true and l.status<>'ARCHIVED' and b.active=true

        union all
        select 'question',q.id,
          md5(jsonb_build_object('prompt',q.prompt,'explanation',q.explanation,'options',coalesce((
            select jsonb_agg(jsonb_build_object('key',qo.option_key,'label',qo.label) order by qo.sort_order)
            from question_option qo where qo.question_id=q.id),'[]'::jsonb),
            'topic',q.topic,'subtopic',q.subtopic,'skill',q.skill,'tags',q.tags)::text),
          jsonb_build_object('prompt',q.prompt,'explanation',q.explanation,'options',coalesce((
            select jsonb_agg(jsonb_build_object('key',qo.option_key,'label',qo.label) order by qo.sort_order)
            from question_option qo where qo.question_id=q.id),'[]'::jsonb),
            'topic',q.topic,'subtopic',q.subtopic,'skill',q.skill,'tags',q.tags),
          c.code::text,s.code::text,ch.code::text,ch.display_name::text,l.title::text
        from question q join lesson l on l.id=q.lesson_id
        join curriculum_chapter ch on ch.id=l.chapter_id join curriculum_subject s on s.id=ch.subject_id
        join curriculum_class c on c.id=s.class_id
        where c.active=true and s.active=true and s.code in ('maths','science') and ch.active=true
          and ch.content_status<>'ARCHIVED' and l.active=true and l.status<>'ARCHIVED'
          and q.active=true and q.review_status not in ('REJECTED','ARCHIVED')

        union all
        select 'assessment',a.id,md5(jsonb_build_object('title',a.title,'description',a.description)::text),
          jsonb_build_object('title',a.title,'description',a.description),
          c.code::text,s.code::text,ch.code::text,ch.display_name::text,a.title::text
        from assessment a join curriculum_subject s on s.id=a.subject_id
        join curriculum_class c on c.id=s.class_id left join curriculum_chapter ch on ch.id=a.chapter_id
        where c.active=true and s.active=true and s.code in ('maths','science') and a.status<>'ARCHIVED'
          and (ch.id is null or (ch.active=true and ch.content_status<>'ARCHIVED'))

        union all
        select 'assessment_question',aq.id,
          md5(jsonb_build_object('prompt',aq.question_snapshot->>'prompt','explanation',aq.question_snapshot->>'explanation',
            'options',coalesce((select jsonb_agg(jsonb_build_object(
              'key',coalesce(opt.value->>'key',opt.value->>'option_key'),'label',opt.value->>'label')
              order by opt.ordinality)
              from jsonb_array_elements(case when jsonb_typeof(aq.question_snapshot->'options')='array'
                then aq.question_snapshot->'options' else '[]'::jsonb end) with ordinality as opt(value,ordinality)
            ),'[]'::jsonb))::text,
          jsonb_build_object('prompt',aq.question_snapshot->>'prompt','explanation',aq.question_snapshot->>'explanation',
            'options',coalesce((select jsonb_agg(jsonb_build_object(
              'key',coalesce(opt.value->>'key',opt.value->>'option_key'),'label',opt.value->>'label')
              order by opt.ordinality)
              from jsonb_array_elements(case when jsonb_typeof(aq.question_snapshot->'options')='array'
                then aq.question_snapshot->'options' else '[]'::jsonb end) with ordinality as opt(value,ordinality)
            ),'[]'::jsonb)),
          c.code::text,s.code::text,ch.code::text,ch.display_name::text,a.title::text
        from assessment_question aq join assessment a on a.id=aq.assessment_id
        join curriculum_subject s on s.id=a.subject_id join curriculum_class c on c.id=s.class_id
        left join curriculum_chapter ch on ch.id=a.chapter_id
        where c.active=true and s.active=true and s.code in ('maths','science') and a.status<>'ARCHIVED'
          and (ch.id is null or (ch.active=true and ch.content_status<>'ARCHIVED'))

        union all
        select 'learning_class_session',cs.id,md5(jsonb_build_object('title',cs.title,'description',cs.description)::text),
          jsonb_build_object('title',cs.title,'description',cs.description),
          c.code::text,s.code::text,ch.code::text,ch.display_name::text,cs.title::text
        from learning_class_session cs join curriculum_subject s on s.id=cs.subject_id
        join curriculum_class c on c.id=s.class_id left join curriculum_chapter ch on ch.id=cs.chapter_id
        where c.active=true and s.active=true and s.code in ('maths','science') and cs.status<>'CANCELLED'
          and (ch.id is null or (ch.active=true and ch.content_status<>'ARCHIVED'))

        union all
        select 'recorded_class',rc.id,md5(jsonb_build_object('title',rc.title,'description',rc.description)::text),
          jsonb_build_object('title',rc.title,'description',rc.description),
          c.code::text,s.code::text,ch.code::text,ch.display_name::text,rc.title::text
        from recorded_class rc join curriculum_subject s on s.id=rc.subject_id
        join curriculum_class c on c.id=s.class_id left join curriculum_chapter ch on ch.id=rc.chapter_id
        where c.active=true and s.active=true and s.code in ('maths','science') and rc.status<>'ARCHIVED'
          and (ch.id is null or (ch.active=true and ch.content_status<>'ARCHIVED'))
      ), existing as (
        select 'curriculum_class'::text entity_type,class_id entity_id,language,source_hash::text source_hash,translation_status from curriculum_class_translation
        union all select 'curriculum_program',program_id,language,source_hash::text,translation_status from curriculum_program_translation
        union all select 'curriculum_subject',subject_id,language,source_hash::text,translation_status from curriculum_subject_translation
        union all select 'curriculum_chapter',chapter_id,language,source_hash::text,translation_status from curriculum_chapter_translation
        union all select 'learning_objective',objective_id,language,source_hash::text,translation_status from learning_objective_translation
        union all select 'lesson',lesson_id,language,source_hash::text,translation_status from lesson_translation
        union all select 'lesson_block',block_id,language,source_hash::text,translation_status from lesson_block_translation
        union all select 'question',question_id,language,source_hash::text,translation_status from question_translation
        union all select 'assessment',assessment_id,language,source_hash::text,translation_status from assessment_translation
        union all select 'assessment_question',assessment_question_id,language,source_hash::text,translation_status from assessment_question_translation
        union all select 'learning_class_session',session_id,language,source_hash::text,translation_status from learning_class_session_translation
        union all select 'recorded_class',recorded_class_id,language,source_hash::text,translation_status from recorded_class_translation
      ), targets as (
        select 'hi'::text language union all select 'en'::text
      ), translation_inventory as (
        select s.entity_type, null::bigint translation_id,s.entity_id,t.language,
          'mixed'::text source_language,s.source_hash,0::integer version_no,
          'NOT_STARTED'::text translation_status,'NONE'::text translation_method,
          null::text model_name,null::text quality_notes,null::timestamptz created_at,null::timestamptz updated_at,
          null::bigint reviewed_by_staff_id,null::timestamptz reviewed_at,
          null::bigint published_by_staff_id,null::timestamptz published_at,
          s.class_code,s.subject_code,s.chapter_code,s.chapter_name,s.lesson_title,
          s.source_content,null::jsonb translated_content,s.source_hash current_source_hash,true is_current_revision
        from sources s cross join targets t
        where not exists (
          select 1 from existing e where e.entity_type=s.entity_type and e.entity_id=s.entity_id
            and e.language=t.language and e.source_hash=s.source_hash and e.translation_status<>'SUPERSEDED'
        )
      )
      """;
  }

  private String translationRowsSql() {
    return """
      with translation_rows as (
        select 'curriculum_class'::text entity_type,t.id translation_id,t.class_id entity_id,t.language,
          t.source_hash::text source_hash,t.version_no,t.translation_status,t.translation_method,t.model_name,t.quality_notes,
          t.created_at,t.updated_at,t.reviewed_by_staff_id,t.reviewed_at,t.published_by_staff_id,t.published_at,
          c.code::text class_code,null::text subject_code,null::text chapter_code,null::text chapter_name,null::text lesson_title,
          jsonb_build_object('display_name',c.display_name) source_content,
          jsonb_build_object('display_name',t.display_name) translated_content,
          md5(jsonb_build_object('display_name',c.display_name)::text) current_source_hash,
          t.source_hash::text=md5(jsonb_build_object('display_name',c.display_name)::text) is_current_revision
        from curriculum_class_translation t join curriculum_class c on c.id=t.class_id

        union all
        select 'curriculum_program',t.id,t.program_id,t.language,t.source_hash::text,t.version_no,t.translation_status,
          t.translation_method,t.model_name,t.quality_notes,t.created_at,t.updated_at,t.reviewed_by_staff_id,t.reviewed_at,
          t.published_by_staff_id,t.published_at,c.code::text,null::text,null::text,p.display_name::text,null::text,
          jsonb_build_object('display_name',p.display_name),jsonb_build_object('display_name',t.display_name),
          md5(jsonb_build_object('display_name',p.display_name)::text),
          t.source_hash::text=md5(jsonb_build_object('display_name',p.display_name)::text)
        from curriculum_program_translation t join curriculum_program p on p.id=t.program_id
        join curriculum_class c on c.id=p.class_id

        union all
        select 'curriculum_subject',t.id,t.subject_id,t.language,t.source_hash::text,t.version_no,t.translation_status,
          t.translation_method,t.model_name,t.quality_notes,t.created_at,t.updated_at,t.reviewed_by_staff_id,t.reviewed_at,
          t.published_by_staff_id,t.published_at,c.code::text,s.code::text,null::text,null::text,null::text,
          jsonb_build_object('display_name',s.display_name),jsonb_build_object('display_name',t.display_name),
          md5(jsonb_build_object('display_name',s.display_name)::text),
          t.source_hash::text=md5(jsonb_build_object('display_name',s.display_name)::text)
        from curriculum_subject_translation t join curriculum_subject s on s.id=t.subject_id
        join curriculum_class c on c.id=s.class_id

        union all
        select 'curriculum_chapter',t.id,t.chapter_id,t.language,t.source_hash::text,t.version_no,t.translation_status,
          t.translation_method,t.model_name,t.quality_notes,t.created_at,t.updated_at,t.reviewed_by_staff_id,t.reviewed_at,
          t.published_by_staff_id,t.published_at,c.code::text,s.code::text,ch.code::text,ch.display_name::text,null::text,
          jsonb_build_object('display_name',ch.display_name,'description',ch.description),
          jsonb_build_object('display_name',t.display_name,'description',t.description),
          md5(jsonb_build_object('display_name',ch.display_name,'description',ch.description)::text),
          t.source_hash::text=md5(jsonb_build_object('display_name',ch.display_name,'description',ch.description)::text)
        from curriculum_chapter_translation t join curriculum_chapter ch on ch.id=t.chapter_id
        join curriculum_subject s on s.id=ch.subject_id join curriculum_class c on c.id=s.class_id

        union all
        select 'learning_objective',t.id,t.objective_id,t.language,t.source_hash::text,t.version_no,t.translation_status,
          t.translation_method,t.model_name,t.quality_notes,t.created_at,t.updated_at,t.reviewed_by_staff_id,t.reviewed_at,
          t.published_by_staff_id,t.published_at,c.code::text,s.code::text,ch.code::text,ch.display_name::text,o.title::text,
          jsonb_build_object('title',o.title,'description',o.description),
          jsonb_build_object('title',t.title,'description',t.description),
          md5(jsonb_build_object('title',o.title,'description',o.description)::text),
          t.source_hash::text=md5(jsonb_build_object('title',o.title,'description',o.description)::text)
        from learning_objective_translation t join learning_objective o on o.id=t.objective_id
        join curriculum_chapter ch on ch.id=o.chapter_id join curriculum_subject s on s.id=ch.subject_id
        join curriculum_class c on c.id=s.class_id

        union all
        select 'lesson',t.id,t.lesson_id,t.language,t.source_hash::text,t.version_no,t.translation_status,
          t.translation_method,t.model_name,t.quality_notes,t.created_at,t.updated_at,t.reviewed_by_staff_id,t.reviewed_at,
          t.published_by_staff_id,t.published_at,c.code::text,s.code::text,ch.code::text,ch.display_name::text,l.title::text,
          jsonb_build_object('title',l.title,'summary',l.summary),
          jsonb_build_object('title',t.title,'summary',t.summary),
          md5(jsonb_build_object('title',l.title,'summary',l.summary)::text),
          t.source_hash::text=md5(jsonb_build_object('title',l.title,'summary',l.summary)::text)
        from lesson_translation t join lesson l on l.id=t.lesson_id
        join curriculum_chapter ch on ch.id=l.chapter_id join curriculum_subject s on s.id=ch.subject_id
        join curriculum_class c on c.id=s.class_id

        union all
        select 'lesson_block',t.id,t.block_id,t.language,t.source_hash::text,t.version_no,t.translation_status,
          t.translation_method,t.model_name,t.quality_notes,t.created_at,t.updated_at,t.reviewed_by_staff_id,t.reviewed_at,
          t.published_by_staff_id,t.published_at,c.code::text,s.code::text,ch.code::text,ch.display_name::text,l.title::text,
          jsonb_build_object('content',b.content),jsonb_build_object('content',t.content),
          md5(b.content::text),t.source_hash::text=md5(b.content::text)
        from lesson_block_translation t join lesson_block b on b.id=t.block_id
        join lesson l on l.id=b.lesson_id join curriculum_chapter ch on ch.id=l.chapter_id
        join curriculum_subject s on s.id=ch.subject_id join curriculum_class c on c.id=s.class_id

        union all
        select 'question',t.id,t.question_id,t.language,t.source_hash::text,t.version_no,t.translation_status,
          t.translation_method,t.model_name,t.quality_notes,t.created_at,t.updated_at,t.reviewed_by_staff_id,t.reviewed_at,
          t.published_by_staff_id,t.published_at,c.code::text,s.code::text,ch.code::text,ch.display_name::text,l.title::text,
          jsonb_build_object('prompt',q.prompt,'explanation',q.explanation,
            'options',coalesce((select jsonb_agg(jsonb_build_object('key',qo.option_key,'label',qo.label) order by qo.sort_order)
              from question_option qo where qo.question_id=q.id),'[]'::jsonb),
            'topic',q.topic,'subtopic',q.subtopic,'skill',q.skill,'tags',q.tags),
          jsonb_build_object('prompt',t.prompt,'explanation',t.explanation,'options',t.options,
            'topic',t.topic,'subtopic',t.subtopic,'skill',t.skill,'tags',t.tags),
          md5(jsonb_build_object('prompt',q.prompt,'explanation',q.explanation,
            'options',coalesce((select jsonb_agg(jsonb_build_object('key',qo.option_key,'label',qo.label) order by qo.sort_order)
              from question_option qo where qo.question_id=q.id),'[]'::jsonb),
            'topic',q.topic,'subtopic',q.subtopic,'skill',q.skill,'tags',q.tags)::text),
          t.source_hash::text=md5(jsonb_build_object('prompt',q.prompt,'explanation',q.explanation,
            'options',coalesce((select jsonb_agg(jsonb_build_object('key',qo.option_key,'label',qo.label) order by qo.sort_order)
              from question_option qo where qo.question_id=q.id),'[]'::jsonb),
            'topic',q.topic,'subtopic',q.subtopic,'skill',q.skill,'tags',q.tags)::text)
        from question_translation t join question q on q.id=t.question_id
        join lesson l on l.id=q.lesson_id join curriculum_chapter ch on ch.id=l.chapter_id
        join curriculum_subject s on s.id=ch.subject_id join curriculum_class c on c.id=s.class_id

        union all
        select 'assessment',t.id,t.assessment_id,t.language,t.source_hash::text,t.version_no,t.translation_status,
          t.translation_method,t.model_name,t.quality_notes,t.created_at,t.updated_at,t.reviewed_by_staff_id,t.reviewed_at,
          t.published_by_staff_id,t.published_at,c.code::text,s.code::text,ch.code::text,ch.display_name::text,a.title::text,
          jsonb_build_object('title',a.title,'description',a.description),
          jsonb_build_object('title',t.title,'description',t.description),
          md5(jsonb_build_object('title',a.title,'description',a.description)::text),
          t.source_hash::text=md5(jsonb_build_object('title',a.title,'description',a.description)::text)
        from assessment_translation t join assessment a on a.id=t.assessment_id
        join curriculum_subject s on s.id=a.subject_id join curriculum_class c on c.id=s.class_id
        left join curriculum_chapter ch on ch.id=a.chapter_id

        union all
        select 'assessment_question',t.id,t.assessment_question_id,t.language,t.source_hash::text,t.version_no,t.translation_status,
          t.translation_method,t.model_name,t.quality_notes,t.created_at,t.updated_at,t.reviewed_by_staff_id,t.reviewed_at,
          t.published_by_staff_id,t.published_at,c.code::text,s.code::text,ch.code::text,ch.display_name::text,a.title::text,
          jsonb_build_object('prompt',aq.question_snapshot->>'prompt','explanation',aq.question_snapshot->>'explanation',
            'options',coalesce((select jsonb_agg(jsonb_build_object('key',coalesce(opt.value->>'key',opt.value->>'option_key'),
              'label',opt.value->>'label') order by opt.ordinality)
              from jsonb_array_elements(case when jsonb_typeof(aq.question_snapshot->'options')='array'
                then aq.question_snapshot->'options' else '[]'::jsonb end) with ordinality as opt(value,ordinality)),'[]'::jsonb)),
          jsonb_build_object('prompt',t.prompt,'explanation',t.explanation,'options',t.options),
          md5(jsonb_build_object('prompt',aq.question_snapshot->>'prompt','explanation',aq.question_snapshot->>'explanation',
            'options',coalesce((select jsonb_agg(jsonb_build_object('key',coalesce(opt.value->>'key',opt.value->>'option_key'),
              'label',opt.value->>'label') order by opt.ordinality)
              from jsonb_array_elements(case when jsonb_typeof(aq.question_snapshot->'options')='array'
                then aq.question_snapshot->'options' else '[]'::jsonb end) with ordinality as opt(value,ordinality)),'[]'::jsonb))::text),
          t.source_hash::text=md5(jsonb_build_object('prompt',aq.question_snapshot->>'prompt',
            'explanation',aq.question_snapshot->>'explanation','options',coalesce((select jsonb_agg(jsonb_build_object(
              'key',coalesce(opt.value->>'key',opt.value->>'option_key'),'label',opt.value->>'label') order by opt.ordinality)
              from jsonb_array_elements(case when jsonb_typeof(aq.question_snapshot->'options')='array'
                then aq.question_snapshot->'options' else '[]'::jsonb end) with ordinality as opt(value,ordinality)),'[]'::jsonb))::text)
        from assessment_question_translation t join assessment_question aq on aq.id=t.assessment_question_id
        join assessment a on a.id=aq.assessment_id join curriculum_subject s on s.id=a.subject_id
        join curriculum_class c on c.id=s.class_id left join curriculum_chapter ch on ch.id=a.chapter_id

        union all
        select 'learning_class_session',t.id,t.session_id,t.language,t.source_hash::text,t.version_no,t.translation_status,
          t.translation_method,t.model_name,t.quality_notes,t.created_at,t.updated_at,t.reviewed_by_staff_id,t.reviewed_at,
          t.published_by_staff_id,t.published_at,c.code::text,s.code::text,ch.code::text,ch.display_name::text,cs.title::text,
          jsonb_build_object('title',cs.title,'description',cs.description),
          jsonb_build_object('title',t.title,'description',t.description),
          md5(jsonb_build_object('title',cs.title,'description',cs.description)::text),
          t.source_hash::text=md5(jsonb_build_object('title',cs.title,'description',cs.description)::text)
        from learning_class_session_translation t join learning_class_session cs on cs.id=t.session_id
        join curriculum_subject s on s.id=cs.subject_id join curriculum_class c on c.id=s.class_id
        left join curriculum_chapter ch on ch.id=cs.chapter_id

        union all
        select 'recorded_class',t.id,t.recorded_class_id,t.language,t.source_hash::text,t.version_no,t.translation_status,
          t.translation_method,t.model_name,t.quality_notes,t.created_at,t.updated_at,t.reviewed_by_staff_id,t.reviewed_at,
          t.published_by_staff_id,t.published_at,c.code::text,s.code::text,ch.code::text,ch.display_name::text,rc.title::text,
          jsonb_build_object('title',rc.title,'description',rc.description),
          jsonb_build_object('title',t.title,'description',t.description),
          md5(jsonb_build_object('title',rc.title,'description',rc.description)::text),
          t.source_hash::text=md5(jsonb_build_object('title',rc.title,'description',rc.description)::text)
        from recorded_class_translation t join recorded_class rc on rc.id=t.recorded_class_id
        join curriculum_subject s on s.id=rc.subject_id join curriculum_class c on c.id=s.class_id
        left join curriculum_chapter ch on ch.id=rc.chapter_id
      )
      """;
  }

  private Map<String, Object> translationRow(TableSpec spec, long translationId, boolean lock) {
    String sql = "select id as translation_id," + spec.foreignKey() + " as entity_id,language,source_language,"
        + "source_hash::text as source_hash,version_no,translation_status,translation_method,model_name,quality_notes "
        + "from " + spec.table() + " where id=?" + (lock ? " for update" : "");
    List<Map<String, Object>> rows = jdbc.queryForList(sql, translationId);
    if (rows.isEmpty()) throw notFound("Translation", translationId);
    return rows.getFirst();
  }

  private SourceDescriptor currentSource(String type, long entityId) {
    String sql = switch (type) {
      case "curriculum_class" -> """
        select md5(jsonb_build_object('display_name',display_name)::text) source_hash,
               jsonb_build_object('display_name',display_name)::text source_content
        from curriculum_class where id=?
        """;
      case "curriculum_program" -> """
        select md5(jsonb_build_object('display_name',display_name)::text) source_hash,
               jsonb_build_object('display_name',display_name)::text source_content
        from curriculum_program where id=?
        """;
      case "curriculum_subject" -> """
        select md5(jsonb_build_object('display_name',display_name)::text) source_hash,
               jsonb_build_object('display_name',display_name)::text source_content
        from curriculum_subject where id=?
        """;
      case "curriculum_chapter" -> """
        select md5(jsonb_build_object('display_name',display_name,'description',description)::text) source_hash,
               jsonb_build_object('display_name',display_name,'description',description)::text source_content
        from curriculum_chapter where id=?
        """;
      case "learning_objective" -> """
        select md5(jsonb_build_object('title',title,'description',description)::text) source_hash,
               jsonb_build_object('title',title,'description',description)::text source_content
        from learning_objective where id=?
        """;
      case "lesson" -> """
        select md5(jsonb_build_object('title',title,'summary',summary)::text) source_hash,
               jsonb_build_object('title',title,'summary',summary)::text source_content
        from lesson where id=?
        """;
      case "lesson_block" -> """
        select md5(content::text) source_hash,jsonb_build_object('content',content)::text source_content
        from lesson_block where id=?
        """;
      case "question" -> """
        select md5(jsonb_build_object('prompt',q.prompt,'explanation',q.explanation,
          'options',coalesce((select jsonb_agg(jsonb_build_object('key',qo.option_key,'label',qo.label) order by qo.sort_order)
            from question_option qo where qo.question_id=q.id),'[]'::jsonb),
          'topic',q.topic,'subtopic',q.subtopic,'skill',q.skill,'tags',q.tags)::text) source_hash,
          jsonb_build_object('prompt',q.prompt,'explanation',q.explanation,
          'options',coalesce((select jsonb_agg(jsonb_build_object('key',qo.option_key,'label',qo.label) order by qo.sort_order)
            from question_option qo where qo.question_id=q.id),'[]'::jsonb),
          'topic',q.topic,'subtopic',q.subtopic,'skill',q.skill,'tags',q.tags)::text source_content
        from question q where q.id=?
        """;
      case "assessment" -> """
        select md5(jsonb_build_object('title',title,'description',description)::text) source_hash,
               jsonb_build_object('title',title,'description',description)::text source_content
        from assessment where id=?
        """;
      case "assessment_question" -> """
        select md5(jsonb_build_object('prompt',aq.question_snapshot->>'prompt',
          'explanation',aq.question_snapshot->>'explanation','options',coalesce((
            select jsonb_agg(jsonb_build_object('key',coalesce(opt.value->>'key',opt.value->>'option_key'),
              'label',opt.value->>'label') order by opt.ordinality)
            from jsonb_array_elements(case when jsonb_typeof(aq.question_snapshot->'options')='array'
              then aq.question_snapshot->'options' else '[]'::jsonb end) with ordinality as opt(value,ordinality)
          ),'[]'::jsonb))::text) source_hash,
          jsonb_build_object('prompt',aq.question_snapshot->>'prompt',
          'explanation',aq.question_snapshot->>'explanation','options',coalesce((
            select jsonb_agg(jsonb_build_object('key',coalesce(opt.value->>'key',opt.value->>'option_key'),
              'label',opt.value->>'label') order by opt.ordinality)
            from jsonb_array_elements(case when jsonb_typeof(aq.question_snapshot->'options')='array'
              then aq.question_snapshot->'options' else '[]'::jsonb end) with ordinality as opt(value,ordinality)
          ),'[]'::jsonb))::text source_content
        from assessment_question aq where aq.id=?
        """;
      case "learning_class_session" -> """
        select md5(jsonb_build_object('title',title,'description',description)::text) source_hash,
               jsonb_build_object('title',title,'description',description)::text source_content
        from learning_class_session where id=?
        """;
      case "recorded_class" -> """
        select md5(jsonb_build_object('title',title,'description',description)::text) source_hash,
               jsonb_build_object('title',title,'description',description)::text source_content
        from recorded_class where id=?
        """;
      default -> throw badRequest("Unsupported translation entity type.");
    };
    List<Map<String, Object>> rows = jdbc.queryForList(sql, entityId);
    if (rows.isEmpty()) throw notFound("Source content", entityId);
    Map<String, Object> row = rows.getFirst();
    Object content;
    try {
      content = mapper.readValue(String.valueOf(row.get("source_content")), Object.class);
    } catch (JacksonException ex) {
      throw new IllegalStateException("Current source content could not be parsed.", ex);
    }
    return new SourceDescriptor(String.valueOf(row.get("source_hash")).trim(), content);
  }

  private long insertDraft(TableSpec spec, long entityId, String language, String sourceLanguage, String sourceHash,
      int version, Map<String, Object> translated, String qualityNotes, Long staffId) {
    List<String> columns = new ArrayList<>(List.of(
        spec.foreignKey(), "language", "source_language", "source_hash", "version_no"));
    List<String> values = new ArrayList<>(List.of("?", "?", "?", "?", "?"));
    List<Object> args = new ArrayList<>(List.of(entityId, language, sourceLanguage, sourceHash, version));
    for (FieldSpec field : spec.fields()) {
      columns.add(field.column());
      values.add(field.jsonb() ? "?::jsonb" : "?");
      args.add(valueForField(translated.get(field.column()), field, true));
    }
    columns.addAll(List.of("translation_status", "translation_method", "created_by_staff_id", "model_name",
        "quality_notes", "created_at", "updated_at"));
    values.addAll(List.of("'DRAFT'", "'HUMAN'", "?", "null", "?", "now()", "now()"));
    args.add(staffId);
    args.add(qualityNotes);
    String sql = "insert into " + spec.table() + " (" + String.join(",", columns) + ") values ("
        + String.join(",", values) + ") returning id";
    Long id = jdbc.queryForObject(sql, Long.class, args.toArray());
    if (id == null) throw new IllegalStateException("Could not create translation draft.");
    return id;
  }

  private Object valueForField(Object raw, FieldSpec field, boolean requireText) {
    if (field.jsonb()) return json(raw);
    if (raw == null) return null;
    String value = String.valueOf(raw);
    if (requireText && !field.nullable() && value.isBlank()) {
      throw badRequest("Translated field '" + field.column() + "' cannot be blank.");
    }
    return value;
  }

  private void validateRequiredFields(String type, Map<String, Object> content) {
    TableSpec spec = tableSpec(type);
    for (FieldSpec field : spec.fields()) {
      if (field.nullable()) continue;
      Object value = content.get(field.column());
      if (value == null || (value instanceof String text && text.isBlank())) {
        throw badRequest("Translated field '" + field.column() + "' cannot be blank.");
      }
    }
    if ("lesson_block".equals(type) && !(content.get("content") instanceof Map<?, ?>)) {
      throw badRequest("A translated lesson block must remain a JSON object.");
    }
    if (("question".equals(type) || "assessment_question".equals(type))
        && !(content.get("options") instanceof List<?>)) {
      throw badRequest("Translated question options must remain an array.");
    }
  }

  private void validateContentShape(Object source, Object translated, String language) {
    if (!(source instanceof Map<?, ?> sourceMap) || !(translated instanceof Map<?, ?> translatedMap)) {
      throw badRequest("Translation content must be a JSON object.");
    }
    if (!new LinkedHashSet<>(sourceMap.keySet()).equals(new LinkedHashSet<>(translatedMap.keySet()))) {
      throw badRequest("The translated content must preserve every source field and its JSON structure.");
    }
    validateNode(source, translated, "$", "", language);
  }

  private void validateNode(Object source, Object translated, String path, String parentKey, String language) {
    if (source instanceof Map<?, ?> sourceMap) {
      if (!(translated instanceof Map<?, ?> translatedMap)
          || !new LinkedHashSet<>(sourceMap.keySet()).equals(new LinkedHashSet<>(translatedMap.keySet()))) {
        throw badRequest("Translation JSON keys or structure changed at " + path + ".");
      }
      for (Object rawKey : sourceMap.keySet()) {
        String key = String.valueOf(rawKey);
        validateNode(sourceMap.get(rawKey), translatedMap.get(rawKey), path + "." + key, key, language);
      }
      return;
    }
    if (source instanceof List<?> sourceList) {
      if (!(translated instanceof List<?> translatedList) || sourceList.size() != translatedList.size()) {
        throw badRequest("Translation JSON array shape changed at " + path + ".");
      }
      for (int i = 0; i < sourceList.size(); i++) {
        validateNode(sourceList.get(i), translatedList.get(i), path + "[" + i + "]", parentKey, language);
      }
      return;
    }
    if (source == null || source instanceof Number || source instanceof Boolean) {
      if (!java.util.Objects.equals(source, translated) || (source != null && !source.getClass().equals(translated.getClass()))) {
        throw badRequest("A non-text value changed at " + path + ".");
      }
      return;
    }
    if (!(source instanceof String sourceText) || !(translated instanceof String translatedText)) {
      throw badRequest("Translation changed the value type at " + path + ".");
    }
    String normalizedKey = parentKey.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "");
    if ("language".equals(normalizedKey) || "locale".equals(normalizedKey)) {
      if (!language.equalsIgnoreCase(translatedText)) {
        throw badRequest("Language marker at " + path + " must match the selected translation language.");
      }
      return;
    }
    if (PROTECTED_KEYS.contains(normalizedKey) && !sourceText.equals(translatedText)) {
      throw badRequest("Protected structure at " + path + " cannot be changed.");
    }
    if ("html".equalsIgnoreCase(parentKey)) {
      List<String> sourceTags = tags(sourceText);
      List<String> translatedTags = tags(translatedText);
      if (!sourceTags.equals(translatedTags)) throw badRequest("HTML structure at " + path + " cannot be changed while translating.");
    }
    List<String> sourceNumbers = numbers(normalizeFractions(sourceText));
    List<String> translatedNumbers = numbers(normalizeFractions(translatedText));
    if (!sourceNumbers.equals(translatedNumbers)) {
      throw badRequest("Numerical values at " + path + " must be preserved during translation.");
    }
  }

  private List<String> tags(String text) {
    List<String> out = new ArrayList<>();
    Matcher matcher = HTML_TAG_PATTERN.matcher(text);
    while (matcher.find()) out.add(matcher.group());
    return out;
  }

  private String normalizeFractions(String text) {
    return text.replaceAll("(?i)(\\d+)\\s+over\\s+(\\d+)", "$1/$2")
        .replaceAll("(\\d+)\\s+बटा\\s+(\\d+)", "$1/$2");
  }

  private List<String> numbers(String text) {
    List<String> out = new ArrayList<>();
    Matcher matcher = NUMBER_PATTERN.matcher(text);
    while (matcher.find()) out.add(matcher.group().replaceAll("\\s+", ""));
    return out;
  }

  private void requireTranslationView(AuthContext context) {
    context = authorization.requireAuth(context);
    boolean allowed = false;
    for (String permission : VIEW_PERMISSIONS) {
      if (authorization.hasPermission(context, permission)) { allowed = true; break; }
    }
    if (!allowed) throw new SecurityException("Content translation viewing access required.");
  }

  private TableSpec tableSpec(String type) {
    return switch (type) {
      case "curriculum_class" -> new TableSpec("curriculum_class_translation", "class_id",
          List.of(new FieldSpec("display_name", false, false)));
      case "curriculum_program" -> new TableSpec("curriculum_program_translation", "program_id",
          List.of(new FieldSpec("display_name", false, false)));
      case "curriculum_subject" -> new TableSpec("curriculum_subject_translation", "subject_id",
          List.of(new FieldSpec("display_name", false, false)));
      case "curriculum_chapter" -> new TableSpec("curriculum_chapter_translation", "chapter_id",
          List.of(new FieldSpec("display_name", false, false), new FieldSpec("description", false, true)));
      case "learning_objective" -> new TableSpec("learning_objective_translation", "objective_id",
          List.of(new FieldSpec("title", false, false), new FieldSpec("description", false, true)));
      case "lesson" -> new TableSpec("lesson_translation", "lesson_id",
          List.of(new FieldSpec("title", false, false), new FieldSpec("summary", false, true)));
      case "lesson_block" -> new TableSpec("lesson_block_translation", "block_id",
          List.of(new FieldSpec("content", true, false)));
      case "question" -> new TableSpec("question_translation", "question_id",
          List.of(new FieldSpec("prompt", false, false), new FieldSpec("explanation", false, true),
              new FieldSpec("options", true, false), new FieldSpec("topic", false, true),
              new FieldSpec("subtopic", false, true), new FieldSpec("skill", false, true),
              new FieldSpec("tags", true, true)));
      case "assessment" -> new TableSpec("assessment_translation", "assessment_id",
          List.of(new FieldSpec("title", false, false), new FieldSpec("description", false, true)));
      case "assessment_question" -> new TableSpec("assessment_question_translation", "assessment_question_id",
          List.of(new FieldSpec("prompt", false, false), new FieldSpec("explanation", false, true),
              new FieldSpec("options", true, false)));
      case "learning_class_session" -> new TableSpec("learning_class_session_translation", "session_id",
          List.of(new FieldSpec("title", false, false), new FieldSpec("description", false, true)));
      case "recorded_class" -> new TableSpec("recorded_class_translation", "recorded_class_id",
          List.of(new FieldSpec("title", false, false), new FieldSpec("description", false, true)));
      default -> throw badRequest("Unsupported translation entity type.");
    };
  }

  private String normalizeLanguageFilter(String language) {
    String normalized = language == null ? "ALL" : language.trim().toLowerCase(Locale.ROOT);
    if ("ALL".equalsIgnoreCase(normalized) || "hi".equals(normalized) || "en".equals(normalized)) return normalized.toLowerCase(Locale.ROOT).equals("all") ? "ALL" : normalized;
    throw badRequest("Language filter must be ALL, hi, or en.");
  }

  private String normalizeStatusFilter(String status) {
    String normalized = status == null ? "ALL" : status.trim().toUpperCase(Locale.ROOT);
    if (Set.of("ALL", "DRAFT", "IN_REVIEW", "APPROVED", "PUBLISHED", "REJECTED", "SUPERSEDED").contains(normalized)) return normalized;
    throw badRequest("Unknown translation status filter.");
  }

  private String normalizeEntityFilter(String entityType) {
    if (entityType == null || entityType.isBlank() || "ALL".equalsIgnoreCase(entityType.trim())) return "ALL";
    return normalizeEntityType(entityType);
  }

  private String normalizeEntityType(String entityType) {
    String normalized = entityType == null ? "" : entityType.trim().toLowerCase(Locale.ROOT);
    if (!ENTITY_TYPES.contains(normalized)) throw badRequest("Unknown translation entity type.");
    return normalized;
  }

  private String normalizeStatusFilterString(String status) {
    return status == null ? "ALL" : status.trim().toUpperCase(Locale.ROOT);
  }

  private String normalizeLanguage(String language) {
    String normalized = language == null ? "hi" : language.trim().toLowerCase(Locale.ROOT);
    if ("hi".equals(normalized) || "en".equals(normalized)) return normalized;
    throw badRequest("Language must be 'hi' or 'en'.");
  }

  private String safeFilter(String value, int maxLength) {
    String normalized = value == null ? "" : value.trim();
    if (normalized.length() > maxLength) normalized = normalized.substring(0, maxLength);
    return normalized;
  }

  private String requiredText(Object raw, int maxLength) {
    String value = raw == null ? "" : String.valueOf(raw).trim();
    if (value.isBlank() || value.length() > maxLength) throw badRequest("A valid translation status is required.");
    return value;
  }

  private String optionalText(Object raw, int maxLength) {
    String value = raw == null ? "" : String.valueOf(raw).trim();
    if (value.length() > maxLength) value = value.substring(0, maxLength);
    return value.isBlank() ? null : value;
  }

  private String json(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (JacksonException ex) {
      throw new IllegalStateException("Could not serialize translation JSON.", ex);
    }
  }

  private ResponseStatusException notFound(String label, long id) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, label + " #" + id + " not found.");
  }

  private ResponseStatusException conflict(String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT, message);
  }

  private ResponseStatusException badRequest(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  private record FieldSpec(String column, boolean jsonb, boolean nullable) {}
  private record TableSpec(String table, String foreignKey, List<FieldSpec> fields) {}
  private record SourceDescriptor(String hash, Object content) {}
}
