-- V185: keep correct answers out of the learner-facing question-delivery SQL.
-- For legacy ORDER and MATCH questions, expose only the unordered choice inventory; do not
-- expose the JSON payload's sequence or key/value pairing to the client.
CREATE OR REPLACE FUNCTION qe_student_safe_question_options(
  p_question_id bigint,
  p_question_type text
) RETURNS jsonb
LANGUAGE sql
STABLE
AS $function$
  SELECT CASE
    WHEN p_question_type IN ('MCQ','TRUE_FALSE') THEN
      COALESCE((
        SELECT jsonb_agg(jsonb_build_object('key',qo.option_key,'label',qo.label)
          ORDER BY qo.sort_order)
        FROM question_option qo
        WHERE qo.question_id=p_question_id
      ),'[]'::jsonb)
    WHEN p_question_type='ORDER' THEN
      COALESCE((
        SELECT jsonb_agg(jsonb_build_object('key',items.option_key,
          'label',COALESCE(qo.label,items.option_key)) ORDER BY items.option_key)
        FROM (
          SELECT qo.option_key
          FROM question_option qo
          WHERE qo.question_id=p_question_id
          UNION
          SELECT item.value
          FROM question q
          CROSS JOIN LATERAL jsonb_array_elements_text(
            CASE WHEN jsonb_typeof(q.answer_payload->'value')='array'
              THEN q.answer_payload->'value' ELSE '[]'::jsonb END
          ) AS item(value)
          WHERE q.id=p_question_id
        ) items
        LEFT JOIN question_option qo
          ON qo.question_id=p_question_id AND qo.option_key=items.option_key
      ),'[]'::jsonb)
    WHEN p_question_type='MATCH' THEN
      COALESCE((
        SELECT jsonb_agg(jsonb_build_object('key',items.option_key,
          'label',COALESCE(qo.label,items.option_key)) ORDER BY items.option_key)
        FROM (
          SELECT qo.option_key
          FROM question_option qo
          WHERE qo.question_id=p_question_id
          UNION
          SELECT pair.key
          FROM question q
          CROSS JOIN LATERAL jsonb_each_text(
            CASE WHEN jsonb_typeof(q.answer_payload->'value')='object'
              THEN q.answer_payload->'value' ELSE '{}'::jsonb END
          ) AS pair(key,value)
          WHERE q.id=p_question_id
          UNION
          SELECT pair.value
          FROM question q
          CROSS JOIN LATERAL jsonb_each_text(
            CASE WHEN jsonb_typeof(q.answer_payload->'value')='object'
              THEN q.answer_payload->'value' ELSE '{}'::jsonb END
          ) AS pair(key,value)
          WHERE q.id=p_question_id
        ) items
        LEFT JOIN question_option qo
          ON qo.question_id=p_question_id AND qo.option_key=items.option_key
      ),'[]'::jsonb)
    ELSE '[]'::jsonb
  END
$function$;

COMMENT ON FUNCTION qe_student_safe_question_options(bigint,text) IS
  'Returns display-only choices. ORDER values are intentionally unordered; MATCH sides are merged without their answer pairing. Never returns answer correctness or the answer payload.';
