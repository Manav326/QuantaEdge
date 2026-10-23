-- V187: sanitize translated options before they leave the learner API.
-- Even published translation JSON is treated as untrusted for delivery shape: keep only keys
-- already present in the safe source inventory and replace labels only. This excludes accidental
-- answer/correctness metadata and prevents translation array order or MATCH pairings being exposed.
CREATE OR REPLACE FUNCTION qe_student_safe_question_options(
  p_question_id bigint,
  p_question_type text,
  p_translation_options jsonb
) RETURNS jsonb
LANGUAGE sql
STABLE
AS $function$
  WITH safe_options AS (
    SELECT item.value->>'key' AS option_key,
           item.value->>'label' AS base_label,
           item.ordinality
    FROM jsonb_array_elements(
      qe_student_safe_question_options(p_question_id,p_question_type)
    ) WITH ORDINALITY AS item(value,ordinality)
  ),
  translated_options AS (
    SELECT DISTINCT ON (item.value->>'key')
           item.value->>'key' AS option_key,
           COALESCE(item.value->>'label',item.value->>'text') AS translated_label
    FROM jsonb_array_elements(
      CASE WHEN jsonb_typeof(p_translation_options)='array'
        THEN p_translation_options ELSE '[]'::jsonb END
    ) WITH ORDINALITY AS item(value,ordinality)
    WHERE NULLIF(item.value->>'key','') IS NOT NULL
    ORDER BY item.value->>'key',item.ordinality
  )
  SELECT COALESCE(jsonb_agg(
    jsonb_build_object(
      'key',safe_options.option_key,
      'label',COALESCE(translated_options.translated_label,safe_options.base_label,safe_options.option_key)
    ) ORDER BY safe_options.ordinality
  ),'[]'::jsonb)
  FROM safe_options
  LEFT JOIN translated_options USING(option_key)
$function$;

COMMENT ON FUNCTION qe_student_safe_question_options(bigint,text,jsonb) IS
  'Sanitizes published translation choices against the server-generated safe inventory. Output contains only key/label pairs; source inventory order is retained for MCQ and canonical unordered order is retained for ORDER/MATCH.';
