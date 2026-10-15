-- V102: additive 12-character public user IDs.
-- Preserve user_account.id and all existing foreign keys as the internal canonical key.
-- public_user_id is an immutable, database-generated external identifier.
ALTER TABLE user_account ADD COLUMN public_user_id varchar(12);

CREATE OR REPLACE FUNCTION generate_public_user_id()
RETURNS varchar(12) LANGUAGE plpgsql VOLATILE AS $$
DECLARE candidate varchar(12);
BEGIN
  LOOP
    -- Hexadecimal characters are alphanumeric; uniqueness is enforced by the column constraint.
    candidate := upper(substr(md5(random()::text || clock_timestamp()::text || pg_backend_pid()::text),1,12));
    IF NOT EXISTS (SELECT 1 FROM user_account WHERE public_user_id=candidate) THEN
      RETURN candidate;
    END IF;
  END LOOP;
END;
$$;

UPDATE user_account
SET public_user_id=generate_public_user_id()
WHERE public_user_id IS NULL;

ALTER TABLE user_account
  ALTER COLUMN public_user_id SET DEFAULT generate_public_user_id(),
  ALTER COLUMN public_user_id SET NOT NULL;

ALTER TABLE user_account
  ADD CONSTRAINT uq_user_account_public_user_id UNIQUE(public_user_id),
  ADD CONSTRAINT ck_user_account_public_user_id_format
    CHECK (public_user_id ~ '^[A-Z0-9]{12}$');

CREATE OR REPLACE FUNCTION assign_public_user_id()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.public_user_id IS NULL OR btrim(NEW.public_user_id)='' THEN
    NEW.public_user_id := generate_public_user_id();
  END IF;
  RETURN NEW;
END;
$$;

CREATE TRIGGER trg_user_account_public_user_id
BEFORE INSERT ON user_account
FOR EACH ROW EXECUTE FUNCTION assign_public_user_id();

INSERT INTO app_metadata(key,value)
VALUES ('schema','additive-public-user-id-v102')
ON CONFLICT(key) DO UPDATE SET value=excluded.value;
