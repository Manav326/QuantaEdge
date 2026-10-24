-- V182: allow the centralized QA engine to own and remove course identities it creates.
-- The run marker is not sufficient by itself; cleanup still requires exact manifest IDs.
ALTER TABLE institution_course_identity
  ADD COLUMN qa_test_run_id uuid REFERENCES qa_test_run(run_id) ON DELETE RESTRICT;

CREATE INDEX idx_qa_course_identity_run
  ON institution_course_identity(qa_test_run_id)
  WHERE qa_test_run_id IS NOT NULL;

ALTER TABLE qa_test_run_resource
  DROP CONSTRAINT IF EXISTS qa_test_run_resource_resource_kind_check;

ALTER TABLE qa_test_run_resource
  ADD CONSTRAINT qa_test_run_resource_resource_kind_check
  CHECK (resource_kind IN (
    'INSTITUTION','INSTITUTION_BRANDING','INSTITUTION_ADMIN_ACCOUNT','STAFF_ACCOUNT','USER_ACCOUNT','STUDENT','GUARDIAN_STUDENT',
    'INSTITUTION_USER_MEMBERSHIP','INSTITUTION_MEMBERSHIP','STUDENT_LOGIN_CREDENTIAL',
    'INSTITUTION_ADMIN','COURSE','COURSE_IDENTITY','COURSE_SUBJECT','PROGRAM_SUBJECT_MAP','COURSE_CHAPTER',
    'MARKETPLACE_OFFER','STUDENT_COURSE_ENROLLMENT','STUDENT_TRACK_ENROLLMENT','STUDENT_LESSON_PROGRESS',
    'STUDENT_QUESTION_ATTEMPT','COURSE_RELEASE','COURSE_ASSIGNMENT','COURSE_ENTITLEMENT','LESSON','QUESTION','QUESTION_OPTION'
  ));

INSERT INTO app_metadata(key,value)
VALUES ('schema','qa-owned-course-identity-cleanup-v182')
ON CONFLICT(key) DO UPDATE SET value=EXCLUDED.value;
