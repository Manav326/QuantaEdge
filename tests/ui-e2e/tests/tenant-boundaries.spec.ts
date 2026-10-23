import { test, expect, type Browser, type Page } from '@playwright/test';
import { join } from 'node:path';
import { appBaseUrl } from '../support/manifest';
import { openQaDatabase, privateDir, readPrivateJson, type QaFixture } from '../support/fixtures';

const WEB = appBaseUrl('web');
const HRMS = appBaseUrl('people');
type ApiResult = { status: number; body: any };

async function api(
  page: Page,
  path: string,
  workspace?: string,
  institutionCode?: string
): Promise<ApiResult> {
  const headers: Record<string, string> = { Accept: 'application/json' };
  // APIRequestContext does not run the application's apiFetch wrapper, so it must send
  // the workspace selector itself. Infer only from the known UI origin/route when a test
  // deliberately omits the argument to exercise another header (for example tenant omission).
  const currentUrl = new URL(page.url());
  const inferredWorkspace =
    currentUrl.port === '3002' ? 'HRMS' :
    currentUrl.port === '3001' ? 'EDUCATION_ADMIN' :
    currentUrl.pathname.startsWith('/parent') ? 'PARENT' :
    currentUrl.pathname.startsWith('/student') ? 'STUDENT' : undefined;
  const selectedWorkspace = workspace || inferredWorkspace;
  if (selectedWorkspace) headers['X-QE-Workspace'] = selectedWorkspace;
  // Use Playwright's context-bound request client rather than window.fetch. This bypasses
  // client-side session recovery/header injection so each security probe sends exactly the
  // caller-selected workspace and institution headers while still sharing this context's cookies.
  if (institutionCode) headers['X-QE-Institution-Code'] = institutionCode;
  const response = await page.context().request.get(new URL(path, page.url()).toString(), {
    headers,
    failOnStatusCode: false
  });
  const raw = await response.text();
  let body: any = {};
  try { body = raw ? JSON.parse(raw) : {}; } catch { body = { unreadable: true }; }
  return { status: response.status(), body };
}

async function contextPage(browser: Browser, storageState: string, url: string) {
  const context = await browser.newContext({ storageState });
  const page = await context.newPage();
  await page.goto(url);
  return { context, page };
}

async function authoritativeReportMetrics(studentId: number, institutionCode: string, courseId: number) {
  const pool = await openQaDatabase();
  try {
    const institution = await pool.query(
      'select id from institution_tenant where tenant_code=$1 and status=\'ACTIVE\'',
      [institutionCode]
    );
    expect(institution.rowCount).toBe(1);
    const institutionId = Number(institution.rows[0].id);
    const lesson = await pool.query(
      `select count(distinct l.id)::int as total_lessons,
              count(distinct l.id) filter(where progress.status='COMPLETED')::int as completed_lessons,
              coalesce(round(100.0*count(distinct l.id) filter(where progress.status='COMPLETED')/
                nullif(count(distinct l.id),0),1),0)::numeric as completion_percent
       from student st
       join curriculum_program_subject mapped on mapped.program_id=$1
       join curriculum_program selected_program on selected_program.id=mapped.program_id and selected_program.status='ACTIVE'
       join curriculum_subject s on s.id=mapped.subject_id and s.class_id=mapped.class_id and s.active=true
         and (s.program_id=mapped.program_id or
              (s.program_id is null and selected_program.class_id=s.class_id and mapped.class_id=s.class_id))
       left join curriculum_chapter ch on ch.subject_id=s.id and ch.active=true and ch.content_status='PUBLISHED'
       left join lesson l on l.chapter_id=ch.id and l.active=true and l.status='PUBLISHED'
       left join student_lesson_progress progress on progress.lesson_id=l.id
         and progress.student_id=st.id and progress.institution_id=$2 and progress.course_id=$1
       where st.id=$3 and st.active=true`,
      [courseId, institutionId, studentId]
    );
    const questions = await pool.query(
      `select count(*)::int as attempts,
              count(*) filter(where qa.correct=true)::int as correct,
              count(*) filter(where qa.correct is not null)::int as graded_attempts,
              coalesce(round(100.0*count(*) filter(where qa.correct=true)/
                nullif(count(*) filter(where qa.correct is not null),0),1),0)::numeric as accuracy_percent
       from student_question_attempt qa
       join question q on q.id=qa.question_id
       join lesson l on l.id=q.lesson_id
       join curriculum_chapter ch on ch.id=l.chapter_id
       join curriculum_subject s on s.id=ch.subject_id
       where qa.student_id=$1 and qa.institution_id=$2 and qa.course_id=$3
         and qa.activity_kind in ('LESSON_INLINE','PRACTICE')
         and exists(
           select 1 from curriculum_program_subject mapped
           join curriculum_program selected_program on selected_program.id=mapped.program_id and selected_program.status='ACTIVE'
           where mapped.program_id=$3 and mapped.subject_id=s.id and mapped.class_id=s.class_id
             and (s.program_id=mapped.program_id or
                  (s.program_id is null and selected_program.class_id=s.class_id and mapped.class_id=s.class_id))
         )`,
      [studentId, institutionId, courseId]
    );
    return { lessonStats: lesson.rows[0], questionStats: questions.rows[0] };
  } finally {
    await pool.end();
  }
}


test('[FAM-002] child credentials cannot be used with a different guardian', async ({ browser }) => {
  const fixture = await readPrivateJson<QaFixture>('qa-fixture.json');
  const context = await browser.newContext();
  const page = await context.newPage();

  try {
    // Parent B's valid mobile must not authenticate Child A in Institution A.
    // This exercises a negative login path and must not mutate fixture ownership.
    await page.goto(WEB + '/login/student?institutionCode=' + encodeURIComponent(fixture.credentials.childA.institutionCode));
    await expect.poll(
      () => page.evaluate(() => window.sessionStorage.getItem('qe.institution.brand-code.v1')),
      { timeout: 20_000 }
    ).toBe(fixture.credentials.childA.institutionCode);

    const identifiers = page.locator('input[autocomplete="username"]');
    await identifiers.nth(0).fill(fixture.credentials.parentB.mobile);
    await identifiers.nth(1).fill(fixture.credentials.childA.username);
    await page.locator('input[autocomplete="current-password"]').fill(fixture.credentials.childA.password);

    const before = await openQaDatabase();
    let credentialCountBefore = 0;
    try {
      const count = await before.query(
        'select count(*)::int as count from student_login_credential where qa_test_run_id=$1',
        [fixture.runId]
      );
      credentialCountBefore = Number(count.rows[0]?.count ?? 0);
      expect(credentialCountBefore).toBe(2);
    } finally {
      await before.end();
    }

    const loginWaiter = page.waitForResponse(response =>
      response.request().method() === 'POST' &&
      response.url().includes('/api/v1/auth/student-login'));
    await page.locator('form button[type="submit"]').click();
    const loginResponse = await loginWaiter;
    expect([400, 401, 403, 404], 'A child credential must not authenticate when paired with a different guardian mobile.').toContain(loginResponse.status());
    await expect(page).toHaveURL(/\/login\/student(?:\?.*)?$/);

    const sessionResponse = await page.evaluate(async (institutionCode) => {
      const response = await fetch('/api/v1/auth/me', {
        credentials: 'same-origin',
        cache: 'no-store',
        headers: { 'X-QE-Workspace': 'STUDENT', 'X-QE-Institution-Code': institutionCode }
      });
      return { status: response.status };
    }, fixture.credentials.childA.institutionCode);
    expect([401, 403], 'Failed child login must not establish a student session.').toContain(sessionResponse.status);

    const after = await openQaDatabase();
    try {
      const count = await after.query(
        'select count(*)::int as count from student_login_credential where qa_test_run_id=$1',
        [fixture.runId]
      );
      expect(Number(count.rows[0]?.count ?? 0), 'Invalid login must not create/change child credentials.').toBe(credentialCountBefore);
    } finally {
      await after.end();
    }
  } finally {
    await context.close();
  }
});

test('[FAM-003][FAM-005][CERT-003][TEN-002] parent and child API sessions cannot cross guardian boundaries', async ({ browser }) => {
  const fixture = await readPrivateJson<QaFixture>('qa-fixture.json');
  const parentA = await contextPage(browser, join(privateDir(), 'parent-a.json'), WEB + '/parent');
  const parentB = await contextPage(browser, join(privateDir(), 'parent-b.json'), WEB + '/parent');
  const childA = await contextPage(browser, join(privateDir(), 'child-a.json'), WEB + '/student');

  try {
    const [sessionA, sessionB, sessionChild] = await Promise.all([
      api(parentA.page, '/api/v1/auth/me', undefined, fixture.credentials.parentA.institutionCode),
      api(parentB.page, '/api/v1/auth/me', undefined, fixture.credentials.parentB.institutionCode),
      api(childA.page, '/api/v1/auth/me', undefined, fixture.credentials.childA.institutionCode)
    ]);
    expect(sessionA.status).toBe(200);
    expect(sessionB.status).toBe(200);
    expect(sessionChild.status).toBe(200);
    expect(sessionA.body.role).toBe('PARENT');
    expect(sessionB.body.role).toBe('PARENT');
    expect(sessionChild.body.role).toBe('STUDENT');
    expect(Number(sessionA.body.userId)).toBeGreaterThan(0);
    expect(Number(sessionB.body.userId)).toBeGreaterThan(0);
    expect(Number(sessionChild.body.studentId)).toBeGreaterThan(0);
    expect(Number(sessionA.body.userId)).not.toBe(Number(sessionB.body.userId));

    const [childrenA, childrenB] = await Promise.all([
      api(parentA.page, '/api/v1/guardians/children', undefined, fixture.credentials.parentA.institutionCode),
      api(parentB.page, '/api/v1/guardians/children', undefined, fixture.credentials.parentB.institutionCode)
    ]);
    expect(childrenA.status).toBe(200);
    expect(childrenB.status).toBe(200);
    expect(Array.isArray(childrenA.body)).toBe(true);
    expect(Array.isArray(childrenB.body)).toBe(true);
    expect(childrenA.body).toHaveLength(1);
    expect(childrenB.body).toHaveLength(1);
    const childAId = Number(childrenA.body[0].id);
    const childBId = Number(childrenB.body[0].id);
    expect(childAId).toBeGreaterThan(0);
    expect(childBId).toBeGreaterThan(0);
    expect(childAId).not.toBe(childBId);

    // Positive guardian reporting and certificate-list access must be tied to the exact
    // consented child and an enrolled course in the same selected institution.
    const reportA = await api(
      parentA.page,
      '/api/v1/guardians/children/' + encodeURIComponent(String(childAId)) + '/report',
      undefined,
      fixture.credentials.parentA.institutionCode
    );
    expect(reportA.status, 'A parent may read the report for their own consented child.').toBe(200);
    expect(Number(reportA.body.id)).toBe(childAId);
    const reportCourseId = Number(reportA.body.courseId || reportA.body.course_id);
    expect(reportCourseId).toBeGreaterThan(0);
    expect(Array.isArray(reportA.body.availableCourses)).toBe(true);
    expect(reportA.body.availableCourses).toHaveLength(1);
    expect(reportA.body.availableCourses[0].course_code).toBe('CLASS_7');
    expect(reportA.body.lessonStats).toBeTruthy();
    expect(reportA.body.questionStats).toBeTruthy();
    const dbMetrics = await authoritativeReportMetrics(
      childAId,
      fixture.credentials.parentA.institutionCode,
      reportCourseId
    );
    for (const key of ['total_lessons', 'completed_lessons', 'completion_percent']) {
      expect(
        Number(reportA.body.lessonStats[key]),
        'Parent report lesson aggregate ' + key + ' must match PostgreSQL for the exact child/course/institution.'
      ).toBeCloseTo(Number(dbMetrics.lessonStats[key]), 1);
    }
    for (const key of ['attempts', 'correct', 'graded_attempts', 'accuracy_percent']) {
      expect(
        Number(reportA.body.questionStats[key]),
        'Parent report question aggregate ' + key + ' must match PostgreSQL for the exact child/course/institution.'
      ).toBeCloseTo(Number(dbMetrics.questionStats[key]), 1);
    }

    const certificatesA = await api(
      parentA.page,
      '/api/v1/guardians/children/' + encodeURIComponent(String(childAId)) +
        '/certificates?courseId=' + encodeURIComponent(String(reportCourseId)),
      undefined,
      fixture.credentials.parentA.institutionCode
    );
    expect(certificatesA.status, 'A parent may read the own child certificate list for the selected enrolled course. Response: ' + JSON.stringify(certificatesA.body)).toBe(200);
    expect(Number(certificatesA.body.studentId)).toBe(childAId);
    expect(Number(certificatesA.body.courseId)).toBe(reportCourseId);
    expect(Array.isArray(certificatesA.body.items)).toBe(true);

    // Another parent cannot obtain a report or certificate list by guessing this child's ID,
    // even when their own institution has an active learner with a matching course class.
    const crossGuardianReport = await api(
      parentB.page,
      '/api/v1/guardians/children/' + encodeURIComponent(String(childAId)) + '/report',
      undefined,
      fixture.credentials.parentB.institutionCode
    );
    expect([401, 403, 404]).toContain(crossGuardianReport.status);
    const crossGuardianCertificates = await api(
      parentB.page,
      '/api/v1/guardians/children/' + encodeURIComponent(String(childAId)) +
        '/certificates?courseId=' + encodeURIComponent(String(reportCourseId)),
      undefined,
      fixture.credentials.parentB.institutionCode
    );
    expect([401, 403, 404]).toContain(crossGuardianCertificates.status);

    // A cannot access B's child through an ID substitution; 404 is acceptable to
    // avoid confirming the existence of another institution's private record.
    const crossTenant = await api(parentA.page,
      '/api/v1/guardians/children/' + encodeURIComponent(String(childBId)) + '/programs?includeSubjects=false', undefined, fixture.credentials.parentA.institutionCode);
    expect([401, 403, 404]).toContain(crossTenant.status);
    const crossGuardian = await api(parentB.page,
      '/api/v1/guardians/children/' + encodeURIComponent(String(childAId)) + '/programs?includeSubjects=false', undefined, fixture.credentials.parentB.institutionCode);
    expect([401, 403, 404]).toContain(crossGuardian.status);

    const parentCannotUseStudentProfile = await api(parentA.page, '/api/v1/students/me/profile', undefined, fixture.credentials.parentA.institutionCode);
    expect([401, 403, 404]).toContain(parentCannotUseStudentProfile.status);
    const childCannotReadGuardianChildren = await api(childA.page, '/api/v1/guardians/children', undefined, fixture.credentials.childA.institutionCode);
    expect([401, 403, 404]).toContain(childCannotReadGuardianChildren.status);

    expect(fixture.credentials.parentA.institutionCode).not.toBe(fixture.credentials.parentB.institutionCode);
    expect(fixture.credentials.childA.institutionCode).toBe(fixture.credentials.parentA.institutionCode);
    expect(fixture.credentials.childB.institutionCode).toBe(fixture.credentials.parentB.institutionCode);
  } finally {
    await parentA.context.close();
    await parentB.context.close();
    await childA.context.close();
  }
});

test('[AUTH-006][AUTH-007] logout revokes only its fresh parent session and protected reads reject it', async ({ browser }) => {
  const fixture = await readPrivateJson<QaFixture>('qa-fixture.json');
  const institutionCode = fixture.credentials.parentA.institutionCode;

  // Never log out using parent-a.json: the responsive-surface test intentionally reuses
  // that saved fixture session later in this run. Create a distinct server session so
  // logout validates revocation without invalidating another test's persisted credentials.
  const context = await browser.newContext();
  const page = await context.newPage();
  const parent = { context, page };

  try {
    await page.goto(WEB + '/login?institutionCode=' + encodeURIComponent(institutionCode));
    await expect.poll(
      () => page.evaluate(() => window.sessionStorage.getItem('qe.institution.brand-code.v1')),
      { timeout: 20_000 }
    ).toBe(institutionCode);
    await page.getByRole('tab').nth(1).click();
    await page.locator('input[autocomplete="username"]').fill(fixture.credentials.parentA.username);
    await page.locator('input[autocomplete="current-password"]').fill(fixture.credentials.parentA.password);
    const loginWaiter = page.waitForResponse(response =>
      response.request().method() === 'POST' &&
      response.url().includes('/api/v1/auth/account-login'));
    await page.locator('form button[type="submit"]').click();
    const loginResponse = await loginWaiter;
    expect(loginResponse.status(), 'The isolated parent session must be created through the UI login flow.').toBe(200);
    expect(loginResponse.request().headers()['x-qe-institution-code']).toBe(institutionCode);
    await page.waitForURL(url => url.origin === WEB && url.pathname === '/parent');

    const before = await api(parent.page, '/api/v1/auth/me', undefined, institutionCode);
    expect(before.status, 'Parent must have an authenticated session before logout.').toBe(200);
    expect(before.body.role).toBe('PARENT');

    const logoutResponse = await parent.context.request.post(WEB + '/api/v1/auth/logout', {
      headers: {
        'X-QE-Workspace': 'PARENT',
        'X-QE-Institution-Code': institutionCode,
        Accept: 'application/json'
      }
    });
    expect(logoutResponse.status(), 'Logout must go through the server and revoke the selected workspace session.').toBe(200);
    expect(await logoutResponse.json()).toMatchObject({ loggedOut: true });

    const sessionAfterLogout = await parent.context.request.get(WEB + '/api/v1/auth/me', {
      headers: { 'X-QE-Workspace': 'PARENT', 'X-QE-Institution-Code': institutionCode, Accept: 'application/json' }
    });
    expect([401, 403], 'The old parent cookie must not continue to authenticate after logout.').toContain(sessionAfterLogout.status());

    const childrenAfterLogout = await parent.context.request.get(WEB + '/api/v1/guardians/children', {
      headers: { 'X-QE-Workspace': 'PARENT', 'X-QE-Institution-Code': institutionCode, Accept: 'application/json' }
    });
    expect([401, 403], 'A revoked session must not continue reading guardian-owned resources.').toContain(childrenAfterLogout.status());
  } finally {
    await parent.context.close();
  }
});

test('[LRN-002] lesson MCQ records incorrect feedback and allows a correct retry', async ({ browser }) => {
  const fixture = await readPrivateJson<QaFixture>('qa-fixture.json');
  const institutionCode = fixture.credentials.childA.institutionCode;
  const learner = await contextPage(browser, join(privateDir(), 'child-a.json'), WEB + '/student');
  const pool = await openQaDatabase();

  try {
    const programs = await api(learner.page, '/api/v1/students/me/programs', 'STUDENT', institutionCode);
    expect(programs.status, 'The signed-in learner must retrieve assigned courses before opening a lesson.').toBe(200);
    const class7 = (programs.body as any[]).find(course => course.code === 'CLASS_7');
    expect(class7, 'The child fixture must be enrolled in its assigned Class 7 course.').toBeTruthy();
    const courseId = Number(class7.id || class7.course_id);
    expect(courseId).toBeGreaterThan(0);

    const session = await api(learner.page, '/api/v1/auth/me', 'STUDENT', institutionCode);
    expect(session.status).toBe(200);
    const studentId = Number(session.body.studentId);
    expect(studentId).toBeGreaterThan(0);

    const institution = await pool.query(
      "select id from institution_tenant where tenant_code=$1 and status='ACTIVE'",
      [institutionCode]
    );
    expect(institution.rowCount).toBe(1);
    const institutionId = Number(institution.rows[0].id);

    const ownedContent = await pool.query(
      `select l.id::int as lesson_id, q.id::int as question_id
       from lesson l
       join curriculum_chapter ch on ch.id=l.chapter_id and ch.qa_test_run_id=$1 and ch.content_status='PUBLISHED'
       join curriculum_subject s on s.id=ch.subject_id and s.active=true
       join curriculum_program_subject mapped
         on mapped.program_id=$2 and mapped.subject_id=s.id and mapped.class_id=s.class_id
       join question q on q.lesson_id=l.id and q.qa_test_run_id=$1 and q.active=true
         and q.question_type='MCQ' and q.review_status in ('APPROVED','PUBLISHED')
       where l.qa_test_run_id=$1 and l.active=true and l.status='PUBLISHED'
       order by l.id, q.id limit 1`,
      [fixture.runId, courseId]
    );
    expect(ownedContent.rowCount, 'The disposable QA pack must have one published MCQ in the assigned course.').toBe(1);
    const lessonId = Number(ownedContent.rows[0].lesson_id);
    const questionId = Number(ownedContent.rows[0].question_id);

    const before = await pool.query(
      `select count(*)::int as total
       from student_question_attempt
       where student_id=$1 and institution_id=$2 and course_id=$3 and question_id=$4
         and activity_kind='LESSON_INLINE'`,
      [studentId, institutionId, courseId, questionId]
    );
    expect(Number(before.rows[0].total), 'A new QA fixture question must start without learner attempts.').toBe(0);

    await learner.page.goto(
      WEB + '/student/learn?courseId=' + encodeURIComponent(String(courseId)) +
        '&lessonId=' + encodeURIComponent(String(lessonId)),
      { waitUntil: 'domcontentloaded' }
    );
    const prompt = 'QA fixture: choose the correct lifecycle answer.';
    const questionCard = learner.page.locator('.qe-question-card').filter({ hasText: prompt }).first();
    await expect(questionCard, 'The real learner lesson UI must render its published QA question.').toBeVisible({ timeout: 25_000 });

    const wrongOption = questionCard.getByRole('button', { name: /Incorrect synthetic option/ });
    const wrongAnswerWaiter = learner.page.waitForResponse(response =>
      response.request().method() === 'POST' &&
      new URL(response.url()).pathname === '/api/v1/learning/questions/' + questionId + '/answer');
    await wrongOption.click();
    const wrongAnswerResponse = await wrongAnswerWaiter;
    expect(wrongAnswerResponse.status(), 'Submitting a wrong answer must be accepted and graded by the API.').toBe(200);
    expect(await wrongAnswerResponse.json()).toMatchObject({ questionId, correct: false, autoGraded: true });
    await expect(questionCard).toHaveClass(/has-answer-incorrect/);
    await expect(questionCard.locator('.qe-answer-feedback.is-incorrect')).toBeVisible();

    const afterWrong = await pool.query(
      `select count(*)::int as total,
              count(*) filter (where correct=false)::int as incorrect
       from student_question_attempt
       where student_id=$1 and institution_id=$2 and course_id=$3 and question_id=$4
         and activity_kind='LESSON_INLINE'`,
      [studentId, institutionId, courseId, questionId]
    );
    expect(Number(afterWrong.rows[0].total)).toBeGreaterThanOrEqual(1);
    expect(Number(afterWrong.rows[0].incorrect)).toBeGreaterThanOrEqual(1);

    const correctOption = questionCard.getByRole('button', { name: /Correct synthetic option/ });
    const correctAnswerWaiter = learner.page.waitForResponse(response =>
      response.request().method() === 'POST' &&
      new URL(response.url()).pathname === '/api/v1/learning/questions/' + questionId + '/answer');
    await correctOption.click();
    const correctAnswerResponse = await correctAnswerWaiter;
    expect(correctAnswerResponse.status(), 'The learner must be allowed to retry after an incorrect answer.').toBe(200);
    expect(await correctAnswerResponse.json()).toMatchObject({ questionId, correct: true, autoGraded: true });
    await expect(questionCard).toHaveClass(/has-answer-correct/);
    await expect(questionCard.locator('.qe-answer-feedback.is-correct')).toBeVisible();
    await expect(correctOption).toBeDisabled();

    const finalAttempts = await pool.query(
      `select count(*)::int as total,
              count(*) filter (where correct=false)::int as incorrect,
              count(*) filter (where correct=true)::int as correct
       from student_question_attempt
       where student_id=$1 and institution_id=$2 and course_id=$3 and question_id=$4
         and activity_kind='LESSON_INLINE'`,
      [studentId, institutionId, courseId, questionId]
    );
    expect(Number(finalAttempts.rows[0].total)).toBeGreaterThanOrEqual(2);
    expect(Number(finalAttempts.rows[0].incorrect)).toBeGreaterThanOrEqual(1);
    expect(Number(finalAttempts.rows[0].correct)).toBeGreaterThanOrEqual(1);
  } finally {
    await pool.end();
    await learner.context.close();
  }
});

test('[CAT-001][CAT-002][TEN-002][TEN-003][CNT-002][LRN-001] learner course lists and subjects follow explicit institution assignment', async ({ browser }) => {
  const fixture = await readPrivateJson<QaFixture>('qa-fixture.json');
  const childA = await contextPage(browser, join(privateDir(), 'child-a.json'), WEB + '/student');
  const childB = await contextPage(browser, join(privateDir(), 'child-b.json'), WEB + '/student');
  const adultC = await contextPage(browser, join(privateDir(), 'adult-c.json'), WEB + '/student');

  try {
    const [coursesA, coursesB, coursesC] = await Promise.all([
      api(childA.page, '/api/v1/students/me/programs', 'STUDENT', fixture.credentials.childA.institutionCode),
      api(childB.page, '/api/v1/students/me/programs', 'STUDENT', fixture.credentials.childB.institutionCode),
      api(adultC.page, '/api/v1/students/me/programs', 'STUDENT', fixture.credentials.independentLearner.institutionCode)
    ]);

    expect(coursesA.status, 'Child A course catalogue must load with the institution selected at sign-in.').toBe(200);
    expect(coursesB.status, 'Child B course catalogue must load with the institution selected at sign-in.').toBe(200);
    expect(coursesC.status, 'Adult learner course catalogue must load with its own institution.').toBe(200);
    for (const result of [coursesA, coursesB, coursesC]) {
      expect(Array.isArray(result.body)).toBe(true);
      expect(result.body.length).toBeGreaterThan(0);
      for (const course of result.body) {
        expect(course.has_access).toBe(true);
        expect(course.is_enrolled).toBe(true);
        expect(Array.isArray(course.subjects)).toBe(true);
      }
    }

    expect(coursesA.body.map((course: any) => course.code)).toEqual(['CLASS_7']);
    expect(coursesB.body.map((course: any) => course.code)).toEqual(['CLASS_7']);
    expect(coursesC.body.map((course: any) => course.code)).toEqual(['CLASS_6']);
    expect(coursesA.body[0].subjects.length).toBeGreaterThan(0);
    expect(coursesB.body[0].subjects.length).toBeGreaterThan(0);
    expect(coursesC.body[0].subjects.length).toBeGreaterThan(0);

    // Verify a real learner surface and its course-scoped lesson API against the same
    // source rows. The pack's synthetic lessons are run-owned and removed at teardown.
    await childA.page.goto(WEB + '/student/courses', { waitUntil: 'domcontentloaded' });
    await expect(childA.page.getByRole('heading', { name: /all courses/i })).toBeVisible();
    const ownedCoursesSection = childA.page.getByRole('region', { name: /your enrolled courses/i });
    await expect(ownedCoursesSection).toContainText(String(coursesA.body[0].display_name));
    await expect(ownedCoursesSection).not.toContainText(String(coursesC.body[0].display_name));

    const class7CourseId = Number(coursesA.body[0].id || coursesA.body[0].course_id);
    const class6CourseId = Number(coursesC.body[0].id || coursesC.body[0].course_id);
    const [class7LessonRows, class6LessonRows] = await Promise.all([
      api(childA.page, '/api/v1/learning/lessons?courseId=' + encodeURIComponent(String(class7CourseId)) + '&subjectCode=maths&language=en', 'STUDENT', fixture.credentials.childA.institutionCode),
      api(adultC.page, '/api/v1/learning/lessons?courseId=' + encodeURIComponent(String(class6CourseId)) + '&subjectCode=maths&language=en', 'STUDENT', fixture.credentials.independentLearner.institutionCode)
    ]);
    expect(class7LessonRows.status, 'Authorized Class 7 learner must load lessons within the assigned course.').toBe(200);
    expect(class6LessonRows.status, 'Authorized Class 6 learner must load lessons within the assigned course.').toBe(200);
    expect(Array.isArray(class7LessonRows.body)).toBe(true);
    expect(Array.isArray(class6LessonRows.body)).toBe(true);

    const poolForLessons = await openQaDatabase();
    try {
      const fixtureLessons = await poolForLessons.query(
        `select l.id::int as lesson_id, cps.program_id::int as course_id
         from lesson l
         join curriculum_chapter ch on ch.id=l.chapter_id and ch.active=true
         join curriculum_subject s on s.id=ch.subject_id and s.active=true
         join curriculum_program_subject cps on cps.subject_id=s.id and cps.class_id=s.class_id
         where l.qa_test_run_id=$1 and l.status='PUBLISHED' and s.code='maths'
           and cps.program_id in ($2,$3)
         order by cps.program_id,l.id`,
        [fixture.runId, class7CourseId, class6CourseId]
      );
      const expected7 = fixtureLessons.rows.filter(row => Number(row.course_id) === class7CourseId).map(row => Number(row.lesson_id));
      const expected6 = fixtureLessons.rows.filter(row => Number(row.course_id) === class6CourseId).map(row => Number(row.lesson_id));
      expect(expected7.length, 'Run-owned Class 7 lesson should exist in the mapped subject.').toBeGreaterThan(0);
      expect(expected6.length, 'Run-owned Class 6 lesson should exist in the mapped subject.').toBeGreaterThan(0);
      expect(class7LessonRows.body.map((row: any) => Number(row.id))).toEqual(expect.arrayContaining(expected7));
      expect(class6LessonRows.body.map((row: any) => Number(row.id))).toEqual(expect.arrayContaining(expected6));
      expect(class7LessonRows.body.map((row: any) => Number(row.id))).not.toEqual(expect.arrayContaining(expected6));
      expect(class6LessonRows.body.map((row: any) => Number(row.id))).not.toEqual(expect.arrayContaining(expected7));
    } finally {
      await poolForLessons.end();
    }

    const foreignContentRead = await api(
      adultC.page,
      '/api/v1/learning/lessons?courseId=' + encodeURIComponent(String(class7CourseId)) + '&subjectCode=maths&language=en',
      'STUDENT',
      fixture.credentials.independentLearner.institutionCode
    );
    expect([400, 403, 404], 'A valid global course ID must not expose its lessons from an institution without that assigned enrollment.').toContain(foreignContentRead.status);

    // Institution C has an assigned/enrolled Class 6 only. A valid Class 7 program ID
    // copied from Institution A must not allow enrollment or create any access ledger rows.
    const adultSession = await api(
      adultC.page,
      '/api/v1/auth/me',
      'STUDENT',
      fixture.credentials.independentLearner.institutionCode
    );
    expect(adultSession.status).toBe(200);
    const forgedEnrollment = await adultC.context.request.post(
      new URL('/api/v1/students/me/programs/id/' + encodeURIComponent(String(class7CourseId)) + '/enroll', adultC.page.url()).toString(),
      {
        headers: {
          'X-QE-Workspace': 'STUDENT',
          'X-QE-Institution-Code': fixture.credentials.independentLearner.institutionCode,
          Accept: 'application/json'
        },
        data: {},
        failOnStatusCode: false
      }
    );
    expect([400, 403, 404], 'A globally active course ID is not authority to enroll it in another institution.').toContain(forgedEnrollment.status());
    const ownershipPool = await openQaDatabase();
    try {
      const noForeignAccess = await ownershipPool.query(`
        select
          (select count(*)::int from student_program_enrollment
             where student_id=$1 and institution_id=(select id from institution_tenant where tenant_code=$2)
               and program_id=$3) as enrollments,
          (select count(*)::int from course_entitlement
             where institution_id=(select id from institution_tenant where tenant_code=$2)
               and course_id=$3 and (holder_student_id=$1 or holder_user_id=$4)) as entitlements,
          (select count(*)::int from course_purchase
             where institution_id=(select id from institution_tenant where tenant_code=$2)
               and course_id=$3 and student_id=$1) as purchases
      `, [
        Number(adultSession.body.studentId),
        fixture.credentials.independentLearner.institutionCode,
        class7CourseId,
        Number(adultSession.body.userId)
      ]);
      expect(noForeignAccess.rows[0]).toEqual({ enrollments: 0, entitlements: 0, purchases: 0 });
    } finally {
      await ownershipPool.end();
    }

    // Missing/invalid/unavailable tenant selectors must fail closed; no platform-wide
    // default catalogue is allowed to leak into a routed learner session.
    const missingTenant = await api(childA.page, '/api/v1/students/me/programs', 'STUDENT');
    expect([400, 403], 'A learner course catalogue request without its institution selector or membership must fail closed.').toContain(missingTenant.status);
    expect(Array.isArray(missingTenant.body), 'A rejected tenantless request must never return a catalogue.').toBe(false);

    const malformedTenant = await api(
      childA.page,
      '/api/v1/students/me/programs',
      'STUDENT',
      'bad code'
    );
    expect(malformedTenant.status).toBe(400);
    expect(String(malformedTenant.body.error || malformedTenant.body.errorCode || malformedTenant.body.code || '')).toContain('INVALID_INSTITUTION_CODE');

    const unavailableTenant = await api(
      childA.page,
      '/api/v1/students/me/programs',
      'STUDENT',
      'QAT-UNKNOWN'
    );
    expect(unavailableTenant.status).toBe(403);
    expect(String(unavailableTenant.body.error || unavailableTenant.body.errorCode || unavailableTenant.body.code || '')).toContain('INSTITUTION_UNAVAILABLE');

    // A valid institution code still grants nothing by itself. Parent B's active tenant
    // cannot be selected using Child A's session unless the backend proves membership there.
    const forgedTenant = await api(
      childA.page,
      '/api/v1/students/me/programs',
      'STUDENT',
      fixture.credentials.parentB.institutionCode
    );
    if (forgedTenant.status === 200) {
      expect(Array.isArray(forgedTenant.body)).toBe(true);
      expect(forgedTenant.body, 'A foreign institution selector must never expose another tenant\'s course access.').toHaveLength(0);
    } else {
      expect([401, 403, 404]).toContain(forgedTenant.status);
    }
  } finally {
    await childA.context.close();
    await childB.context.close();
    await adultC.context.close();
  }
});

test('[CNT-002] direct lesson URL refuses a course not enrolled in the learner institution', async ({ browser }) => {
  const fixture = await readPrivateJson<QaFixture>('qa-fixture.json');
  const childA = await contextPage(browser, join(privateDir(), 'child-a.json'), WEB + '/student');
  const adultC = await contextPage(browser, join(privateDir(), 'adult-c.json'), WEB + '/student');
  const pool = await openQaDatabase();

  try {
    const [coursesA, coursesC, childSession] = await Promise.all([
      api(childA.page, '/api/v1/students/me/programs', 'STUDENT', fixture.credentials.childA.institutionCode),
      api(adultC.page, '/api/v1/students/me/programs', 'STUDENT', fixture.credentials.independentLearner.institutionCode),
      api(childA.page, '/api/v1/auth/me', 'STUDENT', fixture.credentials.childA.institutionCode)
    ]);
    expect(coursesA.status).toBe(200);
    expect(coursesC.status).toBe(200);
    expect(childSession.status).toBe(200);
    const childCourseIds = (coursesA.body as any[]).map(course => Number(course.id || course.course_id));
    const foreignClass6 = (coursesC.body as any[]).find(course => course.code === 'CLASS_6');
    expect(foreignClass6, 'The independent learner fixture must own the Class 6 course in its own institution.').toBeTruthy();
    const foreignCourseId = Number(foreignClass6.id || foreignClass6.course_id);
    expect(childCourseIds).not.toContain(foreignCourseId);

    const foreignLesson = await pool.query(
      `select l.id::int as lesson_id, l.title
       from lesson l
       join curriculum_chapter ch on ch.id=l.chapter_id
         and ch.qa_test_run_id=$1 and ch.content_status='PUBLISHED'
       join curriculum_subject s on s.id=ch.subject_id and s.active=true
       join curriculum_program_subject mapped
         on mapped.program_id=$2 and mapped.subject_id=s.id and mapped.class_id=s.class_id
       where l.qa_test_run_id=$1 and l.active=true and l.status='PUBLISHED'
       order by l.id limit 1`,
      [fixture.runId, foreignCourseId]
    );
    expect(foreignLesson.rowCount, 'The QA run must own published content in the foreign course for a direct-link denial probe.').toBe(1);
    const lessonId = Number(foreignLesson.rows[0].lesson_id);
    const foreignLessonTitle = String(foreignLesson.rows[0].title);

    const institution = await pool.query(
      "select id from institution_tenant where tenant_code=$1 and status='ACTIVE'",
      [fixture.credentials.childA.institutionCode]
    );
    expect(institution.rowCount).toBe(1);
    const institutionId = Number(institution.rows[0].id);
    const studentId = Number(childSession.body.studentId);

    await childA.page.goto(
      WEB + '/student/learn?courseId=' + encodeURIComponent(String(foreignCourseId)) +
        '&lessonId=' + encodeURIComponent(String(lessonId)),
      { waitUntil: 'domcontentloaded' }
    );
    await expect(childA.page.locator('.auth-card')).toContainText(
      'This course is not enrolled in your current institution account.',
      { timeout: 25_000 }
    );
    await expect(
      childA.page.getByRole('heading', { name: foreignLessonTitle, exact: true })
    ).toHaveCount(0);

    const directApiRead = await api(
      childA.page,
      '/api/v1/learning/lessons/' + encodeURIComponent(String(lessonId)) +
        '?courseId=' + encodeURIComponent(String(foreignCourseId)) + '&language=en',
      'STUDENT',
      fixture.credentials.childA.institutionCode
    );
    expect([400, 403, 404], 'The API must independently deny the same foreign-course deep link.').toContain(directApiRead.status);

    const noProgress = await pool.query(
      `select count(*)::int as total
       from student_lesson_progress
       where student_id=$1 and institution_id=$2 and course_id=$3 and lesson_id=$4`,
      [studentId, institutionId, foreignCourseId, lessonId]
    );
    expect(Number(noProgress.rows[0].total), 'A denied deep link must not create progress outside the learner institution.').toBe(0);
  } finally {
    await pool.end();
    await childA.context.close();
    await adultC.context.close();
  }
});

test('[HR-002] HRMS QA Center requires the platform-owner permission, not just an authenticated session', async ({ browser }) => {
  const ownerPath = await readPrivateJson<{ statePath: string }>('hrms-owner-state-path.json');
  const adminPath = await readPrivateJson<{ statePath: string }>('education-admin-state-path.json');
  const parentStatePath = join(privateDir(), 'parent-a.json');
  const owner = await contextPage(browser, ownerPath.statePath, HRMS + '/qa');
  const educationAdmin = await contextPage(browser, adminPath.statePath, HRMS + '/qa');
  const parent = await contextPage(browser, parentStatePath, WEB + '/parent');

  try {
    const ownerGate = await api(owner.page, '/api/v1/hrms/platform/qa/status', 'HRMS');
    expect(ownerGate.status).toBe(200);
    expect(ownerGate.body.writeAllowed).toBe(true);
    expect(ownerGate.body.currentDatabase).toBe(process.env.QE_E2E_TARGET_DATABASE || 'quantaedge_qa');
    expect(ownerGate.body.productionProfileDetected).toBe(false);

    const adminGate = await api(educationAdmin.page, '/api/v1/hrms/platform/qa/status', 'HRMS');
    expect([401, 403]).toContain(adminGate.status);

    const parentGate = await api(parent.page, '/api/v1/hrms/platform/qa/status', 'HRMS');
    expect([401, 403]).toContain(parentGate.status);
  } finally {
    await owner.context.close();
    await educationAdmin.context.close();
    await parent.context.close();
  }
});
