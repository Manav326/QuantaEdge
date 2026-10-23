import { test, expect, type Browser, type Page } from '@playwright/test';
import { join } from 'node:path';
import { appBaseUrl } from '../support/manifest';
import { privateDir, readPrivateJson, type QaFixture } from '../support/fixtures';

const WEB = appBaseUrl('web');
const HRMS = appBaseUrl('people');
type ApiResult = { status: number; body: any };

async function api(
  page: Page,
  path: string,
  workspace?: string,
  institutionCode?: string
): Promise<ApiResult> {
  return page.evaluate(async ({ path, workspace, institutionCode }) => {
    const headers: Record<string, string> = { Accept: 'application/json' };
    if (workspace) headers['X-QE-Workspace'] = workspace;
    // These tests deliberately use raw fetch to probe authorization. Supply the selected
    // tenant explicitly so the request reaches the resource/permission guard rather than
    // failing earlier at the required-institution-context guard.
    if (institutionCode) headers['X-QE-Institution-Code'] = institutionCode;
    const response = await fetch(path, { credentials: 'same-origin', cache: 'no-store', headers });
    const raw = await response.text();
    let body: any = {};
    try { body = raw ? JSON.parse(raw) : {}; } catch { body = { unreadable: true }; }
    return { status: response.status, body };
  }, { path, workspace, institutionCode });
}

async function contextPage(browser: Browser, storageState: string, url: string) {
  const context = await browser.newContext({ storageState });
  const page = await context.newPage();
  await page.goto(url);
  return { context, page };
}

test('[FAM-003][TEN-002] parent and child API sessions cannot cross guardian boundaries', async ({ browser }) => {
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

test('[AUTH-006][AUTH-007] logout revokes the current parent session and protected reads reject it', async ({ browser }) => {
  const fixture = await readPrivateJson<QaFixture>('qa-fixture.json');
  const institutionCode = fixture.credentials.parentA.institutionCode;
  const parent = await contextPage(browser, join(privateDir(), 'parent-a.json'), WEB + '/parent');

  try {
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

test('[CAT-001][CAT-002][TEN-002][TEN-003] learner course lists and subjects follow explicit institution assignment', async ({ browser }) => {
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

    // An attacker-controlled tenant header must not transfer access. Depending on the
    // authorization guard, a wrong-tenant request may be denied or safely return no rows.
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
