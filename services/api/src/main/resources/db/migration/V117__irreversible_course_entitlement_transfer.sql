-- V117: a parent's own purchase can have a personal learner profile, but transfer is one-way.
DO $holder_check$
DECLARE c record;
BEGIN
  FOR c IN
    SELECT conname FROM pg_constraint
    WHERE conrelid='public.course_entitlement'::regclass AND contype='c'
      AND pg_get_constraintdef(oid) ILIKE '%holder_user_id%'
      AND pg_get_constraintdef(oid) ILIKE '%holder_student_id%'
  LOOP
    EXECUTE format('ALTER TABLE public.course_entitlement DROP CONSTRAINT %I',c.conname);
  END LOOP;
END;
$holder_check$;

ALTER TABLE course_entitlement
  ADD CONSTRAINT ck_course_entitlement_has_holder
  CHECK (holder_user_id IS NOT NULL OR holder_student_id IS NOT NULL);

CREATE OR REPLACE FUNCTION protect_irreversible_course_entitlement_transfer()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF OLD.transfer_count=1 THEN
    IF NEW.holder_user_id IS NOT NULL
       OR NEW.holder_student_id IS DISTINCT FROM OLD.holder_student_id
       OR NEW.transfer_count<>1
       OR NEW.transferred_at IS DISTINCT FROM OLD.transferred_at
       OR NEW.course_id IS DISTINCT FROM OLD.course_id
       OR NEW.source_purchase_id IS DISTINCT FROM OLD.source_purchase_id THEN
      RAISE EXCEPTION 'Course entitlement transfer is permanent and cannot be revoked or reassigned';
    END IF;
  END IF;

  IF OLD.holder_user_id IS NOT NULL AND NEW.holder_user_id IS NULL THEN
    IF NEW.transfer_count<>1 OR NEW.transferred_at IS NULL
       OR NEW.holder_student_id IS NULL
       OR NEW.holder_student_id IS NOT DISTINCT FROM OLD.holder_student_id THEN
      RAISE EXCEPTION 'Course entitlement holder can only be changed through a permanent child transfer';
    END IF;
  END IF;
  RETURN NEW;
END;
$$;

CREATE TRIGGER trg_irreversible_course_entitlement_transfer
BEFORE UPDATE ON course_entitlement
FOR EACH ROW EXECUTE FUNCTION protect_irreversible_course_entitlement_transfer();

INSERT INTO app_metadata(key,value)
VALUES ('schema','irreversible-course-entitlement-transfer-v117')
ON CONFLICT(key) DO UPDATE SET value=excluded.value;
