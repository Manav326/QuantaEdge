-- V171: self-service account-deletion request lifecycle.
-- Requests are deliberately distinct from completed erasure: finance, certificate,
-- guardian and audit dependencies require a verified retention-aware fulfillment process.
CREATE TABLE account_deletion_request (
  request_id uuid PRIMARY KEY,
  subject_user_id bigint REFERENCES user_account(id) ON DELETE SET NULL,
  subject_user_public_id varchar(12) NOT NULL CHECK (subject_user_public_id ~ '^[A-Z0-9]{12}$'),
  subject_student_id bigint REFERENCES student(id) ON DELETE SET NULL,
  subject_student_public_user_id varchar(12)
    CHECK (subject_student_public_user_id IS NULL OR subject_student_public_user_id ~ '^[A-Z0-9]{12}$'),
  request_source varchar(12) NOT NULL CHECK (request_source IN ('WEB','ANDROID','OTHER')),
  status varchar(32) NOT NULL DEFAULT 'REQUESTED'
    CHECK (status IN ('REQUESTED','UNDER_REVIEW','AWAITING_LINKED_CHILD_DECISION','CANCELLED','COMPLETED','REJECTED')),
  includes_linked_children boolean NOT NULL DEFAULT false,
  linked_children_count integer NOT NULL DEFAULT 0 CHECK (linked_children_count >= 0),
  requested_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  reviewed_by_staff_id bigint REFERENCES staff_account(id) ON DELETE SET NULL,
  reviewed_at timestamptz,
  cancelled_at timestamptz,
  completed_at timestamptz,
  CHECK ((status='CANCELLED') = (cancelled_at IS NOT NULL)),
  CHECK ((status='COMPLETED') = (completed_at IS NOT NULL))
);

CREATE UNIQUE INDEX uq_account_deletion_open_user_request
  ON account_deletion_request(subject_user_id)
  WHERE subject_user_id IS NOT NULL
    AND status IN ('REQUESTED','UNDER_REVIEW','AWAITING_LINKED_CHILD_DECISION');

CREATE UNIQUE INDEX uq_account_deletion_open_student_request
  ON account_deletion_request(subject_student_id)
  WHERE subject_student_id IS NOT NULL
    AND status IN ('REQUESTED','UNDER_REVIEW','AWAITING_LINKED_CHILD_DECISION');

CREATE INDEX idx_account_deletion_queue
  ON account_deletion_request(status,requested_at,request_id);

CREATE TABLE account_deletion_request_child (
  request_id uuid NOT NULL REFERENCES account_deletion_request(request_id) ON DELETE CASCADE,
  student_id bigint REFERENCES student(id) ON DELETE SET NULL,
  student_public_user_id varchar(12) NOT NULL CHECK (student_public_user_id ~ '^[A-Z0-9]{12}$'),
  resolution varchar(24) NOT NULL DEFAULT 'DELETE_REQUESTED'
    CHECK (resolution IN ('DELETE_REQUESTED','TRANSFER_REQUIRED','TRANSFER_VERIFIED','DELETION_COMPLETED','BLOCKED')),
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (request_id,student_public_user_id)
);

CREATE INDEX idx_account_deletion_child_student
  ON account_deletion_request_child(student_id)
  WHERE student_id IS NOT NULL;

INSERT INTO app_metadata(key,value)
VALUES ('schema','account-deletion-request-lifecycle-v171')
ON CONFLICT(key) DO UPDATE SET value=excluded.value;
