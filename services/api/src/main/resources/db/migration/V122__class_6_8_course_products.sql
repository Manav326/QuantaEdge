-- V122: make the existing Class 6/7/8 curricula first-class course products.
-- Reuse the class-specific legacy subject/chapter/lesson IDs through an explicit mapping;
-- do not duplicate authored content, source provenance, assessments or progress rows.
-- Marketplace offer prices/publication and learner enrolment are deliberately not seeded.

INSERT INTO public_identity_prefix_registry(prefix, entity_type, description)
VALUES ('CRS', 'PROGRAM', 'Class curriculum course product')
ON CONFLICT (prefix) DO NOTHING;

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM public_identity_prefix_registry
    WHERE prefix='CRS' AND entity_type='PROGRAM' AND active=true
  ) THEN
    RAISE EXCEPTION 'Public identity prefix CRS must be active and registered for PROGRAM';
  END IF;
END;
$$;

INSERT INTO curriculum_program(
  class_id, code, display_name, stream_code, curriculum_version, status, notes, public_id_prefix
)
SELECT c.id, 'CLASS_' || c.code, 'Class ' || c.code, NULL, 'CURRENT', 'ACTIVE',
       'First-class Class ' || c.code || ' course product mapped to the existing class-specific curriculum; offers and learner access are configured separately.',
       'CRS'
FROM curriculum_class c
WHERE c.code IN ('6','7','8') AND c.active=true
ON CONFLICT (class_id, code, curriculum_version) DO UPDATE
  SET display_name=EXCLUDED.display_name,
      status='ACTIVE',
      notes=EXCLUDED.notes,
      updated_at=now();

INSERT INTO curriculum_program_subject(
  program_id, class_id, subject_id, is_required, sort_order
)
SELECT p.id, c.id, s.id, true, s.sort_order
FROM curriculum_program p
JOIN curriculum_class c ON c.id=p.class_id AND c.code IN ('6','7','8') AND c.active=true
JOIN curriculum_subject s ON s.class_id=c.id AND s.program_id IS NULL AND s.active=true
WHERE p.code IN ('CLASS_6','CLASS_7','CLASS_8')
  AND p.curriculum_version='CURRENT' AND p.status='ACTIVE'
ON CONFLICT (program_id, subject_id) DO UPDATE
  SET is_required=EXCLUDED.is_required,
      sort_order=EXCLUDED.sort_order;

INSERT INTO app_metadata(key,value)
VALUES ('schema','class-6-8-course-products-v122')
ON CONFLICT (key) DO UPDATE SET value=EXCLUDED.value;
