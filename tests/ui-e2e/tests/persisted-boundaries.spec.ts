import { test, expect } from '@playwright/test';
import { openQaDatabase, readPrivateJson, type QaFixture } from '../support/fixtures';

test('[TEN-002][QA-001] persisted QA rows prove institution membership and explicit course assignment', async () => {
  const fixture = await readPrivateJson<QaFixture>('qa-fixture.json');
  expect(fixture.runId).toMatch(/^[0-9a-f-]{36}$/i);

  const pool = await openQaDatabase();
  try {
    const assignments = await pool.query(
      `select t.tenant_code, p.code as program_code, a.status, a.assignment_source
       from institution_course_assignment a
       join institution_tenant t on t.id=a.institution_id
       join institution_course_release r on r.id=a.course_release_id
       join curriculum_program p on p.id=r.program_id
       where a.qa_test_run_id=$1
       order by t.tenant_code`,
      [fixture.runId]
    );
    expect(assignments.rowCount).toBe(3);
    const bySuffix = new Map(assignments.rows.map(row => [String(row.tenant_code).slice(-1), row]));
    expect(bySuffix.get('A')).toMatchObject({ program_code: 'CLASS_7', status: 'ASSIGNED', assignment_source: 'HRMS' });
    expect(bySuffix.get('B')).toMatchObject({ program_code: 'CLASS_7', status: 'ASSIGNED', assignment_source: 'HRMS' });
    expect(bySuffix.get('C')).toMatchObject({ program_code: 'CLASS_6', status: 'ASSIGNED', assignment_source: 'HRMS' });

    const relationships = await pool.query(
      `select
        (select count(*)::int from user_account u
         where u.qa_test_run_id=$1 and not exists(
           select 1 from institution_user_membership m
           where m.user_id=u.id and m.status='ACTIVE' and m.qa_test_run_id=$1
         )) as users_without_active_institution,
        (select count(*)::int from student s
         where s.qa_test_run_id=$1 and not exists(
           select 1 from institution_membership m
           where m.student_id=s.id and m.status='ACTIVE' and m.qa_test_run_id=$1
         )) as students_without_active_institution,
        (select count(*)::int from guardian_student gs
         join institution_user_membership um on um.user_id=gs.guardian_user_id and um.status='ACTIVE'
         join institution_membership sm on sm.student_id=gs.student_id and sm.status='ACTIVE'
         where gs.qa_test_run_id=$1 and um.qa_test_run_id=$1 and sm.qa_test_run_id=$1
           and um.institution_id<>sm.institution_id) as cross_institution_guardian_links,
        (select count(*)::int from student_program_enrollment e
         where e.qa_test_run_id=$1 and (
           not exists(select 1 from institution_membership m
             where m.student_id=e.student_id and m.institution_id=e.institution_id
               and m.status='ACTIVE' and m.qa_test_run_id=$1)
           or not exists(select 1 from institution_course_assignment a
             join institution_course_release r on r.id=a.course_release_id
             where a.institution_id=e.institution_id and a.status='ASSIGNED'
               and a.qa_test_run_id=$1 and r.program_id=e.program_id and r.release_state='SHAREABLE')
         )) as invalid_tenant_course_enrollments,
        (select count(*)::int from student_program_enrollment e where e.qa_test_run_id=$1) as enrollment_count
      `,
      [fixture.runId]
    );
    expect(relationships.rows[0]).toMatchObject({
      users_without_active_institution: 0,
      students_without_active_institution: 0,
      cross_institution_guardian_links: 0,
      invalid_tenant_course_enrollments: 0,
      enrollment_count: 3
    });
  } finally {
    await pool.end();
  }
});
