-- V118: keep the request history while allowing a new pending request after an earlier request is resolved.
ALTER TABLE parent_course_purchase_request
  DROP CONSTRAINT IF EXISTS parent_course_purchase_request_student_id_course_id_status_key;

CREATE UNIQUE INDEX uq_parent_course_purchase_request_pending
  ON parent_course_purchase_request(guardian_user_id, student_id, course_id)
  WHERE status='PENDING';

INSERT INTO app_metadata(key,value)
VALUES ('schema','retryable-parent-course-purchase-requests-v118')
ON CONFLICT(key) DO UPDATE SET value=excluded.value;
