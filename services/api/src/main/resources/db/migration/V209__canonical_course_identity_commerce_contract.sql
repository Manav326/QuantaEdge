-- V209: persist the canonical logical-course identity on commercial records.
-- Course versions and Marketplace offer revisions remain immutable historical facts.
-- This migration deliberately retains ambiguous legacy rows without guessing an identity.

ALTER TABLE course_payment_order
  ADD COLUMN course_identity_id bigint REFERENCES institution_course_identity(id) ON DELETE RESTRICT,
  ADD COLUMN identity_contract_version smallint NOT NULL DEFAULT 0
    CHECK (identity_contract_version IN (0,1));

ALTER TABLE course_purchase
  ADD COLUMN course_identity_id bigint REFERENCES institution_course_identity(id) ON DELETE RESTRICT,
  ADD COLUMN identity_contract_version smallint NOT NULL DEFAULT 0
    CHECK (identity_contract_version IN (0,1));

ALTER TABLE course_entitlement
  ADD COLUMN course_identity_id bigint REFERENCES institution_course_identity(id) ON DELETE RESTRICT,
  ADD COLUMN identity_contract_version smallint NOT NULL DEFAULT 0
    CHECK (identity_contract_version IN (0,1));

-- Backfill only through the explicit version -> identity FK. Rows with no trusted identity
-- remain unlinked (contract version 0) and require explicit reconciliation before new use.
UPDATE course_payment_order order_row
SET course_identity_id = program.course_identity_id
FROM curriculum_program program
WHERE program.id = order_row.course_id
  AND program.course_identity_id IS NOT NULL
  AND order_row.course_identity_id IS NULL;

UPDATE course_purchase purchase
SET course_identity_id = program.course_identity_id
FROM curriculum_program program
WHERE program.id = purchase.course_id
  AND program.course_identity_id IS NOT NULL
  AND purchase.course_identity_id IS NULL;

UPDATE course_entitlement entitlement
SET course_identity_id = program.course_identity_id
FROM curriculum_program program
WHERE program.id = entitlement.course_id
  AND program.course_identity_id IS NOT NULL
  AND entitlement.course_identity_id IS NULL;

CREATE INDEX idx_course_payment_order_identity
  ON course_payment_order(institution_id, course_identity_id, student_id, created_at DESC)
  WHERE course_identity_id IS NOT NULL;

CREATE INDEX idx_course_purchase_identity
  ON course_purchase(institution_id, course_identity_id, student_id, purchased_at DESC)
  WHERE course_identity_id IS NOT NULL;

CREATE INDEX idx_course_entitlement_identity_student
  ON course_entitlement(institution_id, course_identity_id, holder_student_id)
  WHERE course_identity_id IS NOT NULL AND holder_student_id IS NOT NULL;

CREATE INDEX idx_course_entitlement_identity_user
  ON course_entitlement(institution_id, course_identity_id, holder_user_id)
  WHERE course_identity_id IS NOT NULL AND holder_user_id IS NOT NULL;

-- New-contract records gain logical identity uniqueness without silently rewriting historical
-- duplicate transactions. The old rows stay queryable/auditable under contract version 0.
-- A new open checkout is unique per institution + logical identity + target learner regardless
-- of content version, release version, or offer revision.
CREATE UNIQUE INDEX uq_course_payment_order_identity_open_v1
  ON course_payment_order(institution_id, student_id, course_identity_id)
  WHERE identity_contract_version = 1
    AND institution_id IS NOT NULL
    AND student_id IS NOT NULL
    AND course_identity_id IS NOT NULL
    AND status IN ('CREATING','PENDING');

CREATE UNIQUE INDEX uq_course_payment_order_identity_idempotency_v1
  ON course_payment_order(institution_id, student_id, course_identity_id, client_request_key)
  WHERE identity_contract_version = 1
    AND institution_id IS NOT NULL
    AND student_id IS NOT NULL
    AND course_identity_id IS NOT NULL;

CREATE UNIQUE INDEX uq_course_purchase_identity_student_v1
  ON course_purchase(institution_id, student_id, course_identity_id)
  WHERE identity_contract_version = 1
    AND institution_id IS NOT NULL
    AND student_id IS NOT NULL
    AND course_identity_id IS NOT NULL;

CREATE UNIQUE INDEX uq_course_entitlement_identity_student_v1
  ON course_entitlement(institution_id, holder_student_id, course_identity_id)
  WHERE identity_contract_version = 1
    AND institution_id IS NOT NULL
    AND holder_student_id IS NOT NULL
    AND course_identity_id IS NOT NULL;

CREATE UNIQUE INDEX uq_course_entitlement_identity_user_v1
  ON course_entitlement(institution_id, holder_user_id, course_identity_id)
  WHERE identity_contract_version = 1
    AND institution_id IS NOT NULL
    AND holder_user_id IS NOT NULL
    AND course_identity_id IS NOT NULL;

-- One canonical SQL predicate for course identity comparisons. A legacy row without an identity
-- matches only itself; names, course codes and titles are never considered.
CREATE OR REPLACE FUNCTION qe_same_logical_course(p_left_program_id bigint, p_right_program_id bigint)
RETURNS boolean
LANGUAGE plpgsql
STABLE
AS $function$
DECLARE
  left_identity bigint;
  right_identity bigint;
BEGIN
  IF p_left_program_id IS NULL OR p_right_program_id IS NULL THEN
    RETURN false;
  END IF;

  IF p_left_program_id = p_right_program_id THEN
    RETURN EXISTS (SELECT 1 FROM curriculum_program WHERE id = p_left_program_id);
  END IF;

  SELECT course_identity_id INTO left_identity
  FROM curriculum_program WHERE id = p_left_program_id;
  IF NOT FOUND OR left_identity IS NULL THEN
    RETURN false;
  END IF;

  SELECT course_identity_id INTO right_identity
  FROM curriculum_program WHERE id = p_right_program_id;
  IF NOT FOUND OR right_identity IS NULL THEN
    RETURN false;
  END IF;

  RETURN left_identity = right_identity;
END
$function$;

-- A commercial record's identity must always agree with its exact content graph. New-contract
-- rows cannot be created for identity-less legacy course graphs.
CREATE OR REPLACE FUNCTION qe_validate_commercial_course_identity()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
DECLARE
  expected_identity bigint;
BEGIN
  SELECT program.course_identity_id
  INTO expected_identity
  FROM curriculum_program program
  WHERE program.id = NEW.course_id;

  IF NOT FOUND THEN
    RAISE EXCEPTION 'COURSE_IDENTITY_VERSION_NOT_FOUND: commercial record references a missing course version.'
      USING ERRCODE = '23503';
  END IF;

  IF NEW.course_identity_id IS NOT NULL
     AND NEW.course_identity_id IS DISTINCT FROM expected_identity THEN
    RAISE EXCEPTION 'COURSE_IDENTITY_MISMATCH: commercial identity must match the exact course version FK.'
      USING ERRCODE = '23514';
  END IF;

  IF NEW.identity_contract_version = 1
     AND (expected_identity IS NULL OR NEW.course_identity_id IS DISTINCT FROM expected_identity) THEN
    RAISE EXCEPTION 'COURSE_IDENTITY_REQUIRED: new commercial records require an explicit canonical course identity.'
      USING ERRCODE = '23514';
  END IF;

  RETURN NEW;
END
$function$;

DROP TRIGGER IF EXISTS trg_course_payment_order_identity_consistency ON course_payment_order;
CREATE TRIGGER trg_course_payment_order_identity_consistency
BEFORE INSERT OR UPDATE OF course_id, course_identity_id, identity_contract_version
ON course_payment_order
FOR EACH ROW EXECUTE FUNCTION qe_validate_commercial_course_identity();

DROP TRIGGER IF EXISTS trg_course_purchase_identity_consistency ON course_purchase;
CREATE TRIGGER trg_course_purchase_identity_consistency
BEFORE INSERT OR UPDATE OF course_id, course_identity_id, identity_contract_version
ON course_purchase
FOR EACH ROW EXECUTE FUNCTION qe_validate_commercial_course_identity();

DROP TRIGGER IF EXISTS trg_course_entitlement_identity_consistency ON course_entitlement;
CREATE TRIGGER trg_course_entitlement_identity_consistency
BEFORE INSERT OR UPDATE OF course_id, course_identity_id, identity_contract_version
ON course_entitlement
FOR EACH ROW EXECUTE FUNCTION qe_validate_commercial_course_identity();

-- The partial indexes cover new-contract rows; these guards also consult backfilled
-- historical rows. An old purchased version therefore cannot be bypassed by creating a new
-- version/offer row. Advisory locks serialize concurrent checkout and ownership attempts.
CREATE OR REPLACE FUNCTION qe_guard_logical_course_commerce_uniqueness()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
DECLARE
  tenant_id bigint := NEW.institution_id;
  identity_id bigint := NEW.course_identity_id;
  version_program_id bigint := NEW.course_id;
  v_student_id bigint;
  v_holder_user_id bigint;
  v_holder_student_id bigint;
BEGIN
  IF NEW.identity_contract_version <> 1 THEN
    RETURN NEW;
  END IF;

  IF tenant_id IS NULL OR identity_id IS NULL THEN
    RAISE EXCEPTION 'COURSE_IDENTITY_REQUIRED: new commercial records require institution and logical course identity.'
      USING ERRCODE = '23514';
  END IF;

  IF TG_TABLE_NAME = 'course_payment_order' THEN
    IF NEW.status NOT IN ('CREATING','PENDING') THEN
      RETURN NEW;
    END IF;
    v_student_id := NEW.student_id;
    IF v_student_id IS NULL THEN
      RAISE EXCEPTION 'COURSE_IDENTITY_HOLDER_REQUIRED: a new checkout requires its beneficiary learner.'
        USING ERRCODE = '23514';
    END IF;

    PERFORM pg_advisory_xact_lock(hashtextextended(
      format('qe:course-checkout:%s:%s:%s',tenant_id,identity_id,v_student_id),0));

    IF EXISTS (
      SELECT 1
      FROM course_payment_order prior
      WHERE prior.id IS DISTINCT FROM NEW.id
        AND prior.institution_id=tenant_id
        AND prior.student_id=v_student_id
        AND prior.status IN ('CREATING','PENDING')
        AND (
          prior.course_identity_id=identity_id
          OR qe_same_logical_course(prior.course_id,version_program_id)
        )
    ) THEN
      RAISE EXCEPTION 'COURSE_CHECKOUT_ALREADY_OPEN: an open checkout already exists for this learner and logical course.'
        USING ERRCODE = '23505';
    END IF;

    RETURN NEW;
  ELSIF TG_TABLE_NAME = 'course_purchase' THEN
    v_student_id := NEW.student_id;
    v_holder_user_id := NEW.purchaser_user_id;

    IF v_student_id IS NULL AND v_holder_user_id IS NULL THEN
      RAISE EXCEPTION 'COURSE_IDENTITY_HOLDER_REQUIRED: a new purchase requires a learner or purchaser.'
        USING ERRCODE = '23514';
    END IF;

    PERFORM pg_advisory_xact_lock(hashtextextended(
      format('qe:course-purchase:%s:%s:%s:%s',
        tenant_id,identity_id,coalesce(v_student_id::text,'-'),coalesce(v_holder_user_id::text,'-')),0));

    IF EXISTS (
      SELECT 1
      FROM course_purchase prior
      WHERE prior.id IS DISTINCT FROM NEW.id
        AND prior.institution_id=tenant_id
        AND (
          prior.course_identity_id=identity_id
          OR qe_same_logical_course(prior.course_id,version_program_id)
        )
        AND (
          (v_student_id IS NOT NULL AND prior.student_id=v_student_id)
          OR (v_student_id IS NULL AND v_holder_user_id IS NOT NULL
              AND prior.student_id IS NULL AND prior.purchaser_user_id=v_holder_user_id)
        )
    ) THEN
      RAISE EXCEPTION 'COURSE_ALREADY_PURCHASED: this learner or purchaser already has a purchase for the logical course in this institution.'
        USING ERRCODE = '23505';
    END IF;

    RETURN NEW;
  ELSIF TG_TABLE_NAME = 'course_entitlement' THEN
    v_holder_user_id := NEW.holder_user_id;
    v_holder_student_id := NEW.holder_student_id;

    IF NEW.source_purchase_id IS NOT NULL AND EXISTS (
      SELECT 1
      FROM course_entitlement prior
      WHERE prior.source_purchase_id=NEW.source_purchase_id
    ) THEN
      IF EXISTS (
        SELECT 1
        FROM course_entitlement prior
        WHERE prior.source_purchase_id=NEW.source_purchase_id
          AND prior.institution_id IS NOT DISTINCT FROM tenant_id
          AND prior.course_id=version_program_id
          AND prior.course_identity_id=identity_id
          AND prior.holder_user_id IS NOT DISTINCT FROM v_holder_user_id
          AND prior.holder_student_id IS NOT DISTINCT FROM v_holder_student_id
      ) THEN
        -- Idempotent retry of the same entitlement insert; ON CONFLICT handles the no-op.
        RETURN NEW;
      END IF;
      RAISE EXCEPTION 'COURSE_ENTITLEMENT_SOURCE_PURCHASE_MISMATCH: an existing purchase cannot be linked to different ownership facts.'
        USING ERRCODE = '23514';
    END IF;

    IF v_holder_student_id IS NULL AND v_holder_user_id IS NULL THEN
      RAISE EXCEPTION 'COURSE_IDENTITY_HOLDER_REQUIRED: a new entitlement requires a holder.'
        USING ERRCODE = '23514';
    END IF;

    IF v_holder_student_id IS NOT NULL THEN
      PERFORM pg_advisory_xact_lock(hashtextextended(
        format('qe:course-holder-student:%s:%s:%s',tenant_id,identity_id,v_holder_student_id),0));
    END IF;
    IF v_holder_user_id IS NOT NULL THEN
      PERFORM pg_advisory_xact_lock(hashtextextended(
        format('qe:course-holder-user:%s:%s:%s',tenant_id,identity_id,v_holder_user_id),0));
    END IF;

    IF EXISTS (
      SELECT 1
      FROM course_entitlement prior
      WHERE prior.id IS DISTINCT FROM NEW.id
        AND prior.institution_id=tenant_id
        AND (
          prior.course_identity_id=identity_id
          OR qe_same_logical_course(prior.course_id,version_program_id)
        )
        AND (
          (v_holder_student_id IS NOT NULL AND (
            prior.holder_student_id=v_holder_student_id
            OR prior.holder_user_id=(
              SELECT st.user_account_id FROM student st WHERE st.id=v_holder_student_id
            )
          ))
          OR
          (v_holder_user_id IS NOT NULL AND (
            prior.holder_user_id=v_holder_user_id
            OR EXISTS(
              SELECT 1 FROM student held_student
              WHERE held_student.id=prior.holder_student_id
                AND held_student.user_account_id=v_holder_user_id
            )
          ))
        )
    ) THEN
      RAISE EXCEPTION 'COURSE_ALREADY_OWNED: an entitlement already exists for this holder and logical course in this institution.'
        USING ERRCODE = '23505';
    END IF;

    RETURN NEW;
  END IF;

  RETURN NEW;
END
$function$;

DROP TRIGGER IF EXISTS trg_qe_course_payment_order_identity_unique ON course_payment_order;
CREATE TRIGGER trg_qe_course_payment_order_identity_unique
BEFORE INSERT OR UPDATE OF course_id,course_identity_id,identity_contract_version,status,institution_id,v_student_id
ON course_payment_order
FOR EACH ROW EXECUTE FUNCTION qe_guard_logical_course_commerce_uniqueness();

DROP TRIGGER IF EXISTS trg_qe_course_purchase_identity_unique ON course_purchase;
CREATE TRIGGER trg_qe_course_purchase_identity_unique
BEFORE INSERT OR UPDATE OF course_id,course_identity_id,identity_contract_version,institution_id,v_student_id,purchaser_user_id
ON course_purchase
FOR EACH ROW EXECUTE FUNCTION qe_guard_logical_course_commerce_uniqueness();

DROP TRIGGER IF EXISTS trg_qe_course_entitlement_identity_unique ON course_entitlement;
CREATE TRIGGER trg_qe_course_entitlement_identity_unique
BEFORE INSERT OR UPDATE OF course_id,course_identity_id,identity_contract_version,institution_id,v_holder_user_id,v_holder_student_id,source_purchase_id
ON course_entitlement
FOR EACH ROW EXECUTE FUNCTION qe_guard_logical_course_commerce_uniqueness();

COMMENT ON COLUMN course_payment_order.course_identity_id IS
  'Stable logical course identity for this checkout. course_id and institution_offer_id still preserve the exact bought content graph and offer revision.';
COMMENT ON COLUMN course_payment_order.identity_contract_version IS
  '0=historical order; 1=created under canonical logical-course identity enforcement.';
COMMENT ON COLUMN course_purchase.course_identity_id IS
  'Stable logical product purchased; distinct from the exact content version referenced by course_id.';
COMMENT ON COLUMN course_entitlement.course_identity_id IS
  'Stable logical course owned by the holder; version-specific source_purchase_id and course_id remain historical evidence.';
COMMENT ON FUNCTION qe_same_logical_course(bigint,bigint) IS
  'Returns true only for the same curriculum_program row or two version rows linked to the same explicit institution_course_identity. Never compares names or codes.';
COMMENT ON FUNCTION qe_validate_commercial_course_identity() IS
  'Rejects commerce records whose stable logical-course identity disagrees with their exact content-version FK; new-contract records require an explicit identity.';

INSERT INTO app_metadata(key,value)
VALUES ('schema','canonical-course-identity-commerce-v209')
ON CONFLICT(key) DO UPDATE SET value=excluded.value;
