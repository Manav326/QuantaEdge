-- V168: provision the TRE-4 Computer Science course shell inside the QuantaEdge tenant.
-- This is ordinary course/catalogue data. It deliberately creates no learner, purchase,
-- personal course_entitlement, marketplace price, questions, answers, or published test.
-- Source booklet review remains required before any assessment is published.

-- TRE is a registered course-ID namespace, not a new assessment engine.
INSERT INTO public_identity_prefix_registry(prefix,entity_type,description,active)
VALUES ('TRE','PROGRAM','TRE exam-preparation course',true)
ON CONFLICT(prefix) DO NOTHING;

DO $tre_prefix$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM public_identity_prefix_registry
    WHERE prefix='TRE' AND entity_type='PROGRAM' AND active=true
  ) THEN
    RAISE EXCEPTION 'TRE must be registered as an active PROGRAM public-ID prefix before TRE-4 bootstrap';
  END IF;
END
$tre_prefix$;

-- curriculum_subject.class_id describes the subject's source curriculum level; it does not
-- constrain which learners may take a course. The actual TRE-4 program remains class-neutral
-- (curriculum_program.class_id IS NULL), like other independent course/batch products.
INSERT INTO curriculum_class(code,display_name,board,sort_order,active)
VALUES ('12','Class 12','Bihar Board',12,true)
ON CONFLICT(code) DO UPDATE SET active=true;

INSERT INTO curriculum_program(
  class_id,code,display_name,stream_code,curriculum_version,status,notes,public_id_prefix
)
VALUES (
  NULL,'TRE4_CS','TRE-4 · Computer Science','COMPUTER_SCIENCE','TRE4-2026','ACTIVE',
  'Independent TRE-4 preparation course. Student access is governed by QuantaEdge institution assignment and normal course access controls; class_id is intentionally NULL.',
  'TRE'
)
ON CONFLICT (code,curriculum_version) WHERE class_id IS NULL
DO UPDATE SET
  display_name=EXCLUDED.display_name,
  stream_code=EXCLUDED.stream_code,
  status='ACTIVE',
  notes=EXCLUDED.notes,
  updated_at=now();

INSERT INTO curriculum_subject(class_id,code,display_name,sort_order,active,program_id)
SELECT c.id,'computer_science','Computer Science',1,true,p.id
FROM curriculum_class c
JOIN curriculum_program p ON p.code='TRE4_CS'
  AND p.curriculum_version='TRE4-2026' AND p.class_id IS NULL
WHERE c.code='12'
ON CONFLICT (program_id,code) WHERE program_id IS NOT NULL
DO UPDATE SET display_name=EXCLUDED.display_name,active=true,updated_at=now();

INSERT INTO curriculum_program_subject(program_id,class_id,subject_id,is_required,sort_order)
SELECT p.id,s.class_id,s.id,true,1
FROM curriculum_program p
JOIN curriculum_subject s ON s.program_id=p.id AND s.code='computer_science' AND s.active=true
WHERE p.code='TRE4_CS' AND p.curriculum_version='TRE4-2026' AND p.class_id IS NULL
ON CONFLICT(program_id,subject_id)
DO UPDATE SET is_required=true,sort_order=1;

INSERT INTO curriculum_chapter(
  subject_id,code,display_name,description,sort_order,active,content_status,
  curriculum_source,curriculum_source_url,curriculum_source_edition,curriculum_source_verified
)
SELECT s.id,'prev-years','Prev years',
  'Previous-year practice assessments for BPSC TRE Computer Science. Questions and answer keys are not yet imported or approved.',
  1,true,'DRAFT','BPSC TRE question booklets',
  'https://csestudy247.com/download-bpsc-tre-1-tre-2-tre-3-computer-science-question-paper-pdf/',
  'TRE 1.0 / TRE 2.0 / TRE 3.0',false
FROM curriculum_subject s
JOIN curriculum_program p ON p.id=s.program_id
WHERE p.code='TRE4_CS' AND p.curriculum_version='TRE4-2026' AND s.code='computer_science'
ON CONFLICT(subject_id,code)
DO UPDATE SET
  display_name=EXCLUDED.display_name,
  description=EXCLUDED.description,
  active=true,
  content_status='DRAFT',
  curriculum_source=EXCLUDED.curriculum_source,
  curriculum_source_url=EXCLUDED.curriculum_source_url,
  curriculum_source_edition=EXCLUDED.curriculum_source_edition,
  curriculum_source_verified=false;

-- A local institution-origin course starts with a DRAFT release plus explicit assignment.
-- It remains unpublished to learners until the reviewed content and course offer/access path
-- are intentionally released through the existing product workflows.
INSERT INTO institution_course_release(
  source_institution_id,program_id,release_version,release_state,content_fingerprint,
  legacy_migration,legacy_migration_note,created_at,updated_at
)
SELECT i.id,p.id,1,'DRAFT',md5(concat_ws('|',p.id::text,p.code,p.display_name,p.curriculum_version)),
  false,'TRE-4 bootstrap: course structure only; question/answer import and release review pending.',
  now(),now()
FROM institution_tenant i
JOIN curriculum_program p ON p.code='TRE4_CS'
  AND p.curriculum_version='TRE4-2026' AND p.class_id IS NULL
WHERE i.tenant_code='QUANTA-EDGE' AND i.status='ACTIVE'
ON CONFLICT(source_institution_id,program_id,release_version) DO NOTHING;

INSERT INTO institution_course_assignment(
  institution_id,course_release_id,status,assignment_source,assigned_at,created_at,updated_at
)
SELECT i.id,r.id,'ASSIGNED','ORIGIN',now(),now(),now()
FROM institution_tenant i
JOIN institution_course_release r ON r.source_institution_id=i.id
JOIN curriculum_program p ON p.id=r.program_id AND p.code='TRE4_CS'
  AND p.curriculum_version='TRE4-2026' AND p.class_id IS NULL
WHERE i.tenant_code='QUANTA-EDGE' AND i.status='ACTIVE' AND r.release_version=1
ON CONFLICT(institution_id,course_release_id) DO NOTHING;

-- This is the institution-level grant. Do not create a personal course_entitlement row here:
-- those belong to an individual learner/user after the normal entitlement or purchase flow.
INSERT INTO institution_course_entitlement(
  institution_id,program_id,status,valid_from,granted_by_staff_id
)
SELECT i.id,p.id,'ACTIVE',now(),NULL
FROM institution_tenant i
JOIN curriculum_program p ON p.code='TRE4_CS'
  AND p.curriculum_version='TRE4-2026' AND p.class_id IS NULL
WHERE i.tenant_code='QUANTA-EDGE' AND i.status='ACTIVE'
ON CONFLICT(institution_id,program_id) DO NOTHING;

INSERT INTO institution_course_lifecycle_event(
  institution_id,course_release_id,assignment_id,event_type,details
)
SELECT i.id,r.id,a.id,'TRE4_BOOTSTRAPPED',
  jsonb_build_object(
    'source','V168_TRE4_BOOTSTRAP',
    'courseCode','TRE4_CS',
    'chapterCode','prev-years',
    'releaseState','DRAFT',
    'assessmentImportState','NOT_IMPORTED',
    'studentDeliveryAllowed',false
  )
FROM institution_tenant i
JOIN institution_course_release r ON r.source_institution_id=i.id AND r.release_version=1
JOIN curriculum_program p ON p.id=r.program_id AND p.code='TRE4_CS' AND p.curriculum_version='TRE4-2026'
JOIN institution_course_assignment a ON a.institution_id=i.id AND a.course_release_id=r.id
WHERE i.tenant_code='QUANTA-EDGE'
  AND NOT EXISTS (
    SELECT 1 FROM institution_course_lifecycle_event prior
    WHERE prior.institution_id=i.id AND prior.course_release_id=r.id
      AND prior.event_type='TRE4_BOOTSTRAPPED'
  );

-- Create the normal assessment records as DRAFT shells. No question snapshots are fabricated.
INSERT INTO assessment(
  course_id,title,description,subject_id,chapter_id,duration_minutes,max_attempts,status,
  assessment_type,is_generated,is_current,generation_version,negative_marking_fraction,
  content_fingerprint,source_exam_cycle,source_url,created_at,updated_at
)
SELECT p.id, spec.title, spec.description, s.id, ch.id, spec.duration_minutes, 1, 'DRAFT',
  'PREVIOUS_YEAR_PAPER',false,true,1,spec.negative_marking_fraction,
  md5(spec.cycle || '|TRE4_CS|TRE4-2026'),spec.cycle,spec.source_url,now(),now()
FROM curriculum_program p
JOIN curriculum_subject s ON s.program_id=p.id AND s.code='computer_science' AND s.active=true
JOIN curriculum_chapter ch ON ch.subject_id=s.id AND ch.code='prev-years' AND ch.active=true
CROSS JOIN (VALUES
  ('TRE 1.0',
   'BPSC TRE 1.0 — Computer Science Previous-Year Practice Test',
   'DRAFT shell only. Source booklet inventory indicates 120 questions. Question snapshots, official answer-key comparison, explanations and final scoring review are pending.',
   120,0.25::numeric,
   'https://cdn-images.prepp.in/public/image/paper4-general-studies-and-computer-science-1787208700.pdf'),
  ('TRE 2.0',
   'BPSC TRE 2.0 — Computer Science Previous-Year Practice Test',
   'DRAFT shell only. Source booklet inventory indicates 150 questions, including the qualifying-language section. Question snapshots, official answer-key comparison, explanations and final scoring review are pending.',
   150,0.0::numeric,
   'https://cdn-images.prepp.in/public/image/language-general-studies-and-computer-science-1787208935.pdf'),
  ('TRE 3.0',
   'BPSC TRE 3.0 — Computer Science Previous-Year Practice Test',
   'DRAFT shell only. The supplied booklet currently contains 148 items (28 qualifying-language, 40 General Studies and 80 Computer Science); two expected language items E-1 and E-6 are absent. Do not fabricate them. Resolve the source/count decision before release.',
   150,0.0::numeric,
   'https://cdn-images.prepp.in/public/image/paper4-general-studies-and-computer-science-1787209824.pdf')
) AS spec(cycle,title,description,duration_minutes,negative_marking_fraction,source_url)
WHERE p.code='TRE4_CS' AND p.curriculum_version='TRE4-2026' AND p.class_id IS NULL
  AND NOT EXISTS (
    SELECT 1 FROM assessment existing
    WHERE existing.course_id=p.id
      AND existing.assessment_type='PREVIOUS_YEAR_PAPER'
      AND existing.source_exam_cycle=spec.cycle
  );

INSERT INTO app_metadata(key,value)
VALUES ('schema','tre4-course-launch-bootstrap-v168')
ON CONFLICT(key) DO UPDATE SET value=EXCLUDED.value;
