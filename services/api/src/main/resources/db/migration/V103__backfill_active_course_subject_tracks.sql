-- V103: repair incomplete course-to-subject track links for already-enrolled learners.
-- This is additive and deliberately inserts only missing rows. Existing ACTIVE, PAUSED,
-- and ENDED track states are never overwritten; no user or learning-history identity changes.
INSERT INTO student_track_enrollment(student_id,subject_id,status)
SELECT spe.student_id,s.id,'ACTIVE'
FROM student_program_enrollment spe
JOIN curriculum_program_subject ps ON ps.program_id=spe.program_id
JOIN curriculum_subject s ON s.id=ps.subject_id
  AND s.program_id=spe.program_id AND s.active=true
WHERE spe.status='ACTIVE'
ON CONFLICT(student_id,subject_id) DO NOTHING;

INSERT INTO app_metadata(key,value)
VALUES ('schema','active-course-subject-track-backfill-v103')
ON CONFLICT(key) DO UPDATE SET value=excluded.value;
