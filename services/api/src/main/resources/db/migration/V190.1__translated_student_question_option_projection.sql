-- V190.1: provide the translated-label overload used by learner-facing question delivery.
-- The two-argument function is the source of truth for allowed option identities. It intentionally
-- exposes ORDER items in an unordered inventory and MATCH sides without their key/value pairing.
-- This overload only overlays translated display labels onto that safe inventory. It never adds
-- client-supplied identities or returns grading/correctness metadata.
CREATE OR REPLACE FUNCTION qe_student_safe_question_options(
  p_question_id bigint,
  p_question_type text,
  p_translation_options jsonb
) RETURNS jsonb
LANGUAGE sql
STABLE
AS $function$
  SELECT COALESCE(
    jsonb_agg(
      jsonb_build_object(
        'key', base.option->>'key',
        'label', COALESCE(
          localized.label,
          NULLIF(BTRIM(base.option->>'label'), ''),
          base.option->>'key'
        )
      )
      ORDER BY base.ordinality
    ),
    '[]'::jsonb
  )
  FROM jsonb_array_elements(
    qe_student_safe_question_options(p_question_id, p_question_type)
  ) WITH ORDINALITY AS base(option, ordinality)
  LEFT JOIN LATERAL (
    SELECT NULLIF(BTRIM(candidate.option->>'label'), '') AS label
    FROM jsonb_array_elements(
      CASE
        WHEN jsonb_typeof(p_translation_options) = 'array' THEN p_translation_options
        ELSE '[]'::jsonb
      END
    ) WITH ORDINALITY AS candidate(option, ordinal)
    WHERE jsonb_typeof(candidate.option) = 'object'
      AND candidate.option->>'key' = base.option->>'key'
      AND NULLIF(BTRIM(candidate.option->>'label'), '') IS NOT NULL
    ORDER BY candidate.ordinal
    LIMIT 1
  ) localized ON TRUE
  WHERE jsonb_typeof(base.option) = 'object'
    AND NULLIF(BTRIM(base.option->>'key'), '') IS NOT NULL
$function$;

COMMENT ON FUNCTION qe_student_safe_question_options(bigint, text, jsonb) IS
  'Overlays translated display labels onto the source-controlled learner-safe option inventory. Never returns answer correctness or ORDER/MATCH answer relationships.';
