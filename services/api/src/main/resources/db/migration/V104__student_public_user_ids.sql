-- V104: give every learner profile a stable 12-character public user ID.
-- Independent learners share the exact same ID between student and user_account.
-- Parent-provisioned student profiles receive their own ID without changing internal PKs.
ALTER TABLE student ADD COLUMN public_user_id varchar(12);

-- Serialize ID allocation and avoid collisions between unrelated student and account identities.
-- A student linked to a user_account intentionally shares that same identity in both tables.
CREATE OR REPLACE FUNCTION generate_public_user_id()
RETURNS varchar(12) LANGUAGE plpgsql VOLATILE AS $$
DECLARE candidate varchar(12);
BEGIN
  PERFORM pg_advisory_xact_lock(74120311::bigint);
  LOOP
    candidate := upper(substr(md5(random()::text || clock_timestamp()::text || pg_backend_pid()::text),1,12));
    IF NOT EXISTS (SELECT 1 FROM user_account WHERE public_user_id=candidate)
       AND NOT EXISTS (SELECT 1 FROM student WHERE public_user_id=candidate) THEN
      RETURN candidate;
    END IF;
  END LOOP;
END;
$$;

-- Linked learners reuse their account's ID; unlinked learners get a newly generated ID.
UPDATE student st
SET public_user_id=u.public_user_id
FROM user_account u
WHERE st.user_account_id=u.id AND st.public_user_id IS NULL;

DO $$
DECLARE learner record;
BEGIN
  FOR learner IN SELECT id FROM student WHERE public_user_id IS NULL ORDER BY id LOOP
    UPDATE student SET public_user_id=generate_public_user_id() WHERE id=learner.id;
  END LOOP;
END;
$$;

ALTER TABLE student
  ALTER COLUMN public_user_id SET NOT NULL,
  ADD CONSTRAINT uq_student_public_user_id UNIQUE(public_user_id),
  ADD CONSTRAINT ck_student_public_user_id_format
    CHECK (public_user_id ~ '^[A-Z0-9]{12}$');

CREATE OR REPLACE FUNCTION assign_student_public_user_id()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE linked_public_id varchar(12);
BEGIN
  IF NEW.user_account_id IS NOT NULL THEN
    SELECT public_user_id INTO linked_public_id
    FROM user_account WHERE id=NEW.user_account_id;

    IF linked_public_id IS NULL THEN
      RAISE EXCEPTION 'A student can only link to an account with a public user ID';
    END IF;
    IF TG_OP='UPDATE' AND OLD.user_account_id IS NOT NULL
       AND OLD.user_account_id IS DISTINCT FROM NEW.user_account_id THEN
      RAISE EXCEPTION 'A student user-account link is immutable';
    END IF;
    IF TG_OP='UPDATE' AND OLD.user_account_id IS NULL
       AND OLD.public_user_id IS DISTINCT FROM linked_public_id THEN
      RAISE EXCEPTION 'An independent learner account must retain the student public user ID';
    END IF;
    NEW.public_user_id := linked_public_id;
  ELSIF NEW.public_user_id IS NULL OR btrim(NEW.public_user_id)='' THEN
    NEW.public_user_id := generate_public_user_id();
  END IF;
  RETURN NEW;
END;
$$;

CREATE TRIGGER trg_student_public_user_id
BEFORE INSERT OR UPDATE ON student
FOR EACH ROW EXECUTE FUNCTION assign_student_public_user_id();

INSERT INTO app_metadata(key,value)
VALUES ('schema','student-public-user-ids-v104')
ON CONFLICT(key) DO UPDATE SET value=excluded.value;
