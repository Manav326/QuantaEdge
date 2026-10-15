-- V99: additive immutable public identities for classes and independent course/batches.
-- Numeric primary keys and all existing relationships remain unchanged.
-- The registry is global across entity types, so an 8-character ID cannot collide
-- between a class and a batch. Registry rows are deliberately not cascade-deleted.

CREATE TABLE public_identity_prefix_registry (
  prefix varchar(3) PRIMARY KEY,
  entity_type varchar(16) NOT NULL CHECK (entity_type IN ('CLASS','PROGRAM')),
  description varchar(120) NOT NULL,
  active boolean NOT NULL DEFAULT true,
  created_at timestamptz NOT NULL DEFAULT now(),
  CHECK (prefix ~ '^[A-Z]{3}$')
);

INSERT INTO public_identity_prefix_registry(prefix, entity_type, description)
VALUES
  ('SCH','CLASS','School class'),
  ('NAV','PROGRAM','Navodaya batch');

CREATE TABLE public_identity_registry (
  public_id varchar(8) PRIMARY KEY,
  prefix varchar(3) NOT NULL REFERENCES public_identity_prefix_registry(prefix) ON DELETE RESTRICT,
  entity_type varchar(16) NOT NULL CHECK (entity_type IN ('CLASS','PROGRAM')),
  entity_id bigint NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(entity_type, entity_id),
  CHECK (public_id ~ '^[A-Z]{3}[A-Z0-9]{5}$'),
  CHECK (left(public_id, 3) = prefix)
);

ALTER TABLE curriculum_class ADD COLUMN public_id varchar(8);
ALTER TABLE curriculum_program ADD COLUMN public_id varchar(8);
ALTER TABLE curriculum_program ADD COLUMN public_id_prefix varchar(3)
  REFERENCES public_identity_prefix_registry(prefix) ON DELETE RESTRICT;

CREATE OR REPLACE FUNCTION allocate_public_identity(
  p_prefix varchar,
  p_entity_type varchar,
  p_entity_id bigint
) RETURNS varchar
LANGUAGE plpgsql
AS $$
DECLARE
  existing_id varchar(8);
  candidate varchar(8);
  inserted_count integer;
  attempt integer;
  prefix_type varchar(16);
BEGIN
  SELECT entity_type INTO prefix_type
  FROM public_identity_prefix_registry
  WHERE prefix = p_prefix AND active = true;

  IF prefix_type IS NULL OR prefix_type <> p_entity_type THEN
    RAISE EXCEPTION 'Public identity prefix % is not registered for entity type %', p_prefix, p_entity_type
      USING ERRCODE = '23514';
  END IF;

  SELECT public_id INTO existing_id
  FROM public_identity_registry
  WHERE entity_type = p_entity_type AND entity_id = p_entity_id;

  IF existing_id IS NOT NULL THEN
    IF left(existing_id, 3) <> p_prefix THEN
      RAISE EXCEPTION 'Entity %/% already has public identity % with a different prefix',
        p_entity_type, p_entity_id, existing_id USING ERRCODE = '23514';
    END IF;
    RETURN existing_id;
  END IF;

  FOR attempt IN 1..100 LOOP
    -- Five uppercase hexadecimal characters are a valid alphanumeric suffix.
    candidate := p_prefix || upper(substr(md5(
      clock_timestamp()::text || random()::text || txid_current()::text ||
      p_entity_type || p_entity_id::text || attempt::text
    ), 1, 5));

    INSERT INTO public_identity_registry(public_id, prefix, entity_type, entity_id)
    VALUES (candidate, p_prefix, p_entity_type, p_entity_id)
    ON CONFLICT DO NOTHING;

    GET DIAGNOSTICS inserted_count = ROW_COUNT;
    IF inserted_count = 1 THEN
      RETURN candidate;
    END IF;
  END LOOP;

  RAISE EXCEPTION 'Could not allocate a unique public identity for %/% after 100 attempts',
    p_entity_type, p_entity_id USING ERRCODE = '23505';
END;
$$;

-- Backfill without touching numeric IDs or foreign keys. Existing independent program
-- types must explicitly register their prefix rather than being guessed from display names.
DO $$
DECLARE
  row_item record;
  chosen_prefix varchar(3);
BEGIN
  FOR row_item IN SELECT id FROM curriculum_class ORDER BY id LOOP
    UPDATE curriculum_class
      SET public_id = allocate_public_identity('SCH', 'CLASS', row_item.id)
      WHERE id = row_item.id;
  END LOOP;

  FOR row_item IN
    SELECT id, code FROM curriculum_program WHERE code <> 'GENERAL' ORDER BY id
  LOOP
    IF row_item.code = 'NAVODAYA_BATCH' THEN
      chosen_prefix := 'NAV';
    ELSE
      RAISE EXCEPTION
        'Cannot backfill public ID for existing independent program %. Register its batch-type prefix before applying V99.',
        row_item.code;
    END IF;

    UPDATE curriculum_program
      SET public_id_prefix = chosen_prefix,
          public_id = allocate_public_identity(chosen_prefix, 'PROGRAM', row_item.id)
      WHERE id = row_item.id;
  END LOOP;
END;
$$;

ALTER TABLE curriculum_class
  ALTER COLUMN public_id SET NOT NULL,
  ADD CONSTRAINT uq_curriculum_class_public_id UNIQUE(public_id),
  ADD CONSTRAINT fk_curriculum_class_public_identity
    FOREIGN KEY(public_id) REFERENCES public_identity_registry(public_id) ON DELETE RESTRICT;

ALTER TABLE curriculum_program
  ADD CONSTRAINT uq_curriculum_program_public_id UNIQUE(public_id),
  ADD CONSTRAINT fk_curriculum_program_public_identity
    FOREIGN KEY(public_id) REFERENCES public_identity_registry(public_id) ON DELETE RESTRICT;

CREATE INDEX idx_curriculum_program_public_id
  ON curriculum_program(public_id) WHERE public_id IS NOT NULL;

CREATE OR REPLACE FUNCTION protect_class_public_identity()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'INSERT' THEN
    NEW.public_id := allocate_public_identity('SCH', 'CLASS', NEW.id);
  ELSIF NEW.public_id IS DISTINCT FROM OLD.public_id THEN
    RAISE EXCEPTION 'Class public ID is immutable' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END;
$$;

CREATE TRIGGER trg_curriculum_class_public_identity
BEFORE INSERT OR UPDATE OF public_id ON curriculum_class
FOR EACH ROW EXECUTE FUNCTION protect_class_public_identity();

CREATE OR REPLACE FUNCTION protect_program_public_identity()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  chosen_prefix varchar(3);
BEGIN
  IF TG_OP = 'INSERT' THEN
    IF NEW.code = 'GENERAL' THEN
      NEW.public_id := NULL;
      NEW.public_id_prefix := NULL;
      RETURN NEW;
    END IF;

    chosen_prefix := NEW.public_id_prefix;
    IF chosen_prefix IS NULL AND NEW.code = 'NAVODAYA_BATCH' THEN
      chosen_prefix := 'NAV';
    END IF;
    IF chosen_prefix IS NULL THEN
      RAISE EXCEPTION 'Independent programs require a registered public_id_prefix' USING ERRCODE = '23514';
    END IF;
    NEW.public_id_prefix := chosen_prefix;
    NEW.public_id := allocate_public_identity(chosen_prefix, 'PROGRAM', NEW.id);
  ELSE
    IF NEW.public_id IS DISTINCT FROM OLD.public_id
       OR NEW.public_id_prefix IS DISTINCT FROM OLD.public_id_prefix THEN
      RAISE EXCEPTION 'Program public identity and prefix are immutable' USING ERRCODE = '23514';
    END IF;
  END IF;
  RETURN NEW;
END;
$$;

CREATE TRIGGER trg_curriculum_program_public_identity
BEFORE INSERT OR UPDATE OF public_id, public_id_prefix ON curriculum_program
FOR EACH ROW EXECUTE FUNCTION protect_program_public_identity();

INSERT INTO app_metadata(key,value)
VALUES ('schema','additive-public-identities-v99')
ON CONFLICT(key) DO UPDATE SET value=excluded.value;
