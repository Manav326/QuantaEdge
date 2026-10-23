-- V168: bootstrap the normal QuantaEdge-owned TRE-4 Computer Science course shell.
-- This is ordinary institution/course/chapter data, not a separate assessment engine.
-- Assessment content, prices/offers, learner enrolments and learner entitlements are deliberately
-- not seeded here. Question packages must pass source/key/editorial review before import or release.

INSERT INTO curriculum_class(code,display_name,board,sort_order,active)
VALUES ('TRE4','TRE-4','BPSC TRE',99,true)
ON CONFLICT (code) DO NOTHING;

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM curriculum_class
    WHERE code='TRE4' AND display_name='TRE-4' AND board='BPSC TRE' AND active=true
  ) THEN
    RAISE EXCEPTION 'TRE4 curriculum class must be active and represent the TRE-4 course scope';
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM institution_tenant WHERE tenant_code='QUANTA-EDGE'
  ) THEN
    RAISE EXCEPTION 'QUANTA-EDGE institution must exist before TRE-4 course bootstrap';
  END IF;
END;
$$;

-- Match the existing InstitutionCourseLifecycleController naming convention.
INSERT INTO curriculum_program(
  class_id,code,display_name,stream_code,curriculum_version,status,notes,public_id_prefix
)
SELECT c.id,'I'||t.id::text||'_TRE4_CS','TRE-4 · Computer Science','COMPUTER_SCIENCE',
       'TRE4-2026','ACTIVE',
       'Institution-origin course for QUANTA-EDGE; the PYQ chapter and assessments remain review-gated.',
       'INS'
FROM curriculum_class c
CROSS JOIN institution_tenant t
WHERE c.code='TRE4' AND c.active=true AND t.tenant_code='QUANTA-EDGE'
ON CONFLICT (class_id,code,curriculum_version) DO UPDATE
SET display_name=EXCLUDED.display_name,
    stream_code=EXCLUDED.stream_code,
    status='ACTIVE',
    notes=EXCLUDED.notes,
    updated_at=now();

INSERT INTO curriculum_subject(class_id,code,display_name,sort_order,active,program_id)
SELECT p.class_id,'computer-science','Computer Science',1,true,p.id
FROM curriculum_program p
JOIN institution_tenant t ON p.code='I'||t.id::text||'_TRE4_CS'
WHERE t.tenant_code='QUANTA-EDGE' AND p.curriculum_version='TRE4-2026' AND p.status='ACTIVE'
ON CONFLICT (program_id,code) WHERE program_id IS NOT NULL
DO UPDATE SET display_name=EXCLUDED.display_name,sort_order=EXCLUDED.sort_order,active=true;

INSERT INTO curriculum_program_subject(program_id,class_id,subject_id,is_required,sort_order)
SELECT p.id,p.class_id,s.id,true,1
FROM curriculum_program p
JOIN institution_tenant t ON p.code='I'||t.id::text||'_TRE4_CS'
JOIN curriculum_subject s ON s.program_id=p.id AND s.class_id=p.class_id
  AND s.code='computer-science' AND s.active=true
WHERE t.tenant_code='QUANTA-EDGE' AND p.curriculum_version='TRE4-2026' AND p.status='ACTIVE'
ON CONFLICT (program_id,subject_id) DO UPDATE
SET is_required=true,sort_order=EXCLUDED.sort_order;

INSERT INTO curriculum_chapter(
  subject_id,code,display_name,description,sort_order,teaching_order,active,content_status,
  curriculum_source,curriculum_source_url,curriculum_source_edition,curriculum_source_pages,
  curriculum_source_verified,textbook_chapter_no,pedagogy_note
)
SELECT s.id,'prev-years','Previous-Year Papers (PYQ)',
       'Draft chapter for separately selectable TRE 1.0, TRE 2.0 and TRE 3.0 practice assessments.',
       1,1,true,'DRAFT',
       'BPSC TRE Computer Science source register',
       'https://csestudy247.com/download-bpsc-tre-1-tre-2-tre-3-computer-science-question-paper-pdf/',
       'TRE 1.0 / TRE 2.0 / TRE 3.0',NULL,false,NULL,
       'Keep question wording, options, proposed keys, explanations and scoring rules in review until independently approved.'
FROM curriculum_program p
JOIN institution_tenant t ON p.code='I'||t.id::text||'_TRE4_CS'
JOIN curriculum_subject s ON s.program_id=p.id AND s.class_id=p.class_id
  AND s.code='computer-science' AND s.active=true
WHERE t.tenant_code='QUANTA-EDGE' AND p.curriculum_version='TRE4-2026' AND p.status='ACTIVE'
ON CONFLICT (subject_id,code) DO NOTHING;

-- A draft release and assignment makes the course discoverable to QuantaEdge institution admins.
-- It does not make a student-facing marketplace offer public.
INSERT INTO institution_course_release(
  source_institution_id,program_id,release_version,release_state,content_fingerprint,
  legacy_migration,created_at,updated_at
)
SELECT t.id,p.id,1,'DRAFT',
       md5(concat_ws('|',p.id::text,p.code,p.display_name,p.curriculum_version)),
       false,now(),now()
FROM institution_tenant t
JOIN curriculum_program p ON p.code='I'||t.id::text||'_TRE4_CS'
  AND p.curriculum_version='TRE4-2026' AND p.status='ACTIVE'
WHERE t.tenant_code='QUANTA-EDGE'
ON CONFLICT (source_institution_id,program_id,release_version) DO NOTHING;

INSERT INTO institution_course_assignment(
  institution_id,course_release_id,status,assignment_source,assigned_at,created_at,updated_at
)
SELECT t.id,r.id,'ASSIGNED','ORIGIN',now(),now(),now()
FROM institution_tenant t
JOIN curriculum_program p ON p.code='I'||t.id::text||'_TRE4_CS'
  AND p.curriculum_version='TRE4-2026' AND p.status='ACTIVE'
JOIN institution_course_release r ON r.source_institution_id=t.id
  AND r.program_id=p.id AND r.release_version=1
WHERE t.tenant_code='QUANTA-EDGE'
ON CONFLICT (institution_id,course_release_id) DO NOTHING;

-- Institution-level authorization is separate from learner-level course ownership.
INSERT INTO institution_course_entitlement(institution_id,program_id,status,valid_from,granted_by_staff_id)
SELECT t.id,p.id,'ACTIVE',now(),NULL
FROM institution_tenant t
JOIN curriculum_program p ON p.code='I'||t.id::text||'_TRE4_CS'
  AND p.curriculum_version='TRE4-2026' AND p.status='ACTIVE'
WHERE t.tenant_code='QUANTA-EDGE'
ON CONFLICT (institution_id,program_id) DO NOTHING;

INSERT INTO institution_course_lifecycle_event(
  institution_id,course_release_id,assignment_id,event_type,details
)
SELECT t.id,r.id,a.id,'COURSE_BOOTSTRAPPED',
       jsonb_build_object(
         'source','V168_TRE4_COURSE_BOOTSTRAP',
         'courseCode',p.code,
         'courseTitle',p.display_name,
         'chapterCode','prev-years',
         'publicationAllowed',false
       )
FROM institution_tenant t
JOIN curriculum_program p ON p.code='I'||t.id::text||'_TRE4_CS'
  AND p.curriculum_version='TRE4-2026' AND p.status='ACTIVE'
JOIN institution_course_release r ON r.source_institution_id=t.id
  AND r.program_id=p.id AND r.release_version=1
JOIN institution_course_assignment a ON a.institution_id=t.id
  AND a.course_release_id=r.id AND a.status='ASSIGNED'
WHERE t.tenant_code='QUANTA-EDGE'
  AND NOT EXISTS (
    SELECT 1 FROM institution_course_lifecycle_event e
    WHERE e.institution_id=t.id AND e.course_release_id=r.id
      AND e.event_type='COURSE_BOOTSTRAPPED'
  );
