import { test, expect, type Browser, type Page } from '@playwright/test';
import { join } from 'node:path';
import { appBaseUrl } from '../support/manifest';
import { privateDir, readPrivateJson, type QaFixture } from '../support/fixtures';

const WEB = appBaseUrl('web', 'http://127.0.0.1:3000');
const HRMS = appBaseUrl('people', 'http://127.0.0.1:3002');
type ApiResult = { status: number; body: any };

async function api(page: Page, path: string, workspace?: string): Promise<ApiResult> {
  return page.evaluate(async ({ path, workspace }) => {
    const headers: Record<string, string> = { Accept: 'application/json' };
    if (workspace) headers['X-QE-Workspace'] = workspace;
    const response = await fetch(path, { credentials: 'same-origin', cache: 'no-store', headers });
    const raw = await response.text();
    let body: any = {};
    try { body = raw ? JSON.parse(raw) : {}; } catch { body = { unreadable: true }; }
    return { status: response.status, body };
  }, { path, workspace });
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
      api(parentA.page, '/api/v1/auth/me'),
      api(parentB.page, '/api/v1/auth/me'),
      api(childA.page, '/api/v1/auth/me')
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
      api(parentA.page, '/api/v1/guardians/children'),
      api(parentB.page, '/api/v1/guardians/children')
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
      '/api/v1/guardians/children/' + encodeURIComponent(String(childBId)) + '/programs?includeSubjects=false');
    expect([401, 403, 404]).toContain(crossTenant.status);
    const crossGuardian = await api(parentB.page,
      '/api/v1/guardians/children/' + encodeURIComponent(String(childAId)) + '/programs?includeSubjects=false');
    expect([401, 403, 404]).toContain(crossGuardian.status);

    const parentCannotUseStudentProfile = await api(parentA.page, '/api/v1/students/me/profile');
    expect([401, 403, 404]).toContain(parentCannotUseStudentProfile.status);
    const childCannotReadGuardianChildren = await api(childA.page, '/api/v1/guardians/children');
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

test('HRMS QA Center requires the platform-owner permission, not just an authenticated session', async ({ browser }) => {
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
