import { test, expect } from '@playwright/test';
import { appBaseUrl } from '../support/manifest';
import { openQaDatabase, readPrivateJson } from '../support/fixtures';

const WEB = appBaseUrl('web');
const ROTATED_PASSWORD = 'QE-E2E-Institution-Next#2026';

type InstitutionCredential = {
  institutionCode: string;
  username: string;
  password: string;
};

type InstitutionFixture = {
  credentials: {
    institutionAdminA: InstitutionCredential;
    institutionAdminB: InstitutionCredential;
  };
};

async function apiJson(page: import('@playwright/test').Page, path: string, institutionCode?: string) {
  return page.evaluate(async ({ path, institutionCode }) => {
    const headers: Record<string, string> = { Accept: 'application/json', 'X-QE-Workspace': 'INSTITUTION' };
    if (institutionCode) headers['X-QE-Institution-Code'] = institutionCode;
    const response = await fetch(path, { credentials: 'same-origin', cache: 'no-store', headers });
    return { status: response.status, body: await response.json().catch(() => ({})) as any };
  }, { path, institutionCode });
}

test('[TEN-004][TEN-007] institution admin first-login password rotation, session revocation, and tenant-bound reports', async ({ browser }) => {
  test.setTimeout(120_000);
  const fixture = await readPrivateJson<InstitutionFixture>('qa-fixture.json');
  const adminA = fixture.credentials.institutionAdminA;
  const adminB = fixture.credentials.institutionAdminB;
  expect(adminA.institutionCode).not.toBe(adminB.institutionCode);

  const context = await browser.newContext({ viewport: { width: 1280, height: 900 } });
  const page = await context.newPage();
  const authResponses: Array<{ path: string; status: number }> = [];
  const onResponse = (response: import('@playwright/test').Response) => {
    const url = new URL(response.url());
    if (url.pathname.startsWith('/api/v1/institution/')) {
      authResponses.push({ path: url.pathname, status: response.status() });
    }
  };
  page.on('response', onResponse);

  try {
    await page.goto(WEB + '/institution', { waitUntil: 'domcontentloaded' });
    await expect(page.getByRole('heading', { name: 'Institution sign in' })).toBeVisible({ timeout: 20_000 });
    await page.locator('input[autocomplete="organization"]').fill(adminA.institutionCode);
    await page.locator('input[autocomplete="username"]').fill(adminA.username);
    await page.locator('input[autocomplete="current-password"]').fill(adminA.password);

    const badLoginWaiter = page.waitForResponse(response =>
      response.request().method() === 'POST' &&
      new URL(response.url()).pathname === '/api/v1/institution/auth/login');
    await page.locator('input[autocomplete="current-password"]').fill(adminA.password + '-wrong');
    await page.getByRole('button', { name: 'Sign in securely' }).click();
    const badLogin = await badLoginWaiter;
    expect([400, 401, 403]).toContain(badLogin.status());
    await expect(page.getByRole('alert')).toContainText(/incorrect|sign.?in failed/i);
    const unauthenticated = await apiJson(page, '/api/v1/institution/auth/me', adminA.institutionCode);
    expect([401, 403]).toContain(unauthenticated.status);

    await page.locator('input[autocomplete="current-password"]').fill(adminA.password);
    const firstLoginWaiter = page.waitForResponse(response =>
      response.request().method() === 'POST' &&
      new URL(response.url()).pathname === '/api/v1/institution/auth/login');
    await page.getByRole('button', { name: 'Sign in securely' }).click();
    const firstLoginResponse = await firstLoginWaiter;
    expect(firstLoginResponse.status(), 'Valid synthetic institution-admin credentials must create a real institution session.').toBe(200);
    const firstLogin = await firstLoginResponse.json();
    expect(firstLogin.authenticated).toBe(true);
    expect(firstLogin.institutionCode).toBe(adminA.institutionCode);
    expect(firstLogin.mustChangePassword).toBe(true);
    await expect(page.getByRole('heading', { name: 'Protect your administrator account.' })).toBeVisible();

    await page.getByLabel('Current password').fill(adminA.password);
    await page.getByLabel('New password').fill(ROTATED_PASSWORD);
    const rotateWaiter = page.waitForResponse(response =>
      response.request().method() === 'POST' &&
      new URL(response.url()).pathname === '/api/v1/institution/auth/change-password');
    await page.getByRole('button', { name: 'Change password' }).click();
    const rotateResponse = await rotateWaiter;
    expect(rotateResponse.status(), 'First-login password rotation must be handled by the live API.').toBe(200);
    await expect(page.getByRole('status')).toContainText(/sign in again/i);

    const revoked = await apiJson(page, '/api/v1/institution/auth/me', adminA.institutionCode);
    expect([401, 403]).toContain(revoked.status);
    await expect(page.getByRole('heading', { name: 'Institution sign in' })).toBeVisible();

    // The one-time password is no longer a valid credential after rotation.
    await page.locator('input[autocomplete="organization"]').fill(adminA.institutionCode);
    await page.locator('input[autocomplete="username"]').fill(adminA.username);
    await page.locator('input[autocomplete="current-password"]').fill(adminA.password);
    const oldPasswordWaiter = page.waitForResponse(response =>
      response.request().method() === 'POST' &&
      new URL(response.url()).pathname === '/api/v1/institution/auth/login');
    await page.getByRole('button', { name: 'Sign in securely' }).click();
    const oldPasswordResponse = await oldPasswordWaiter;
    expect([400, 401, 403]).toContain(oldPasswordResponse.status());

    // Register report waiters before submitting the new credential. Each read is served
    // by the running backend, not an in-memory UI fixture.
    await page.locator('input[autocomplete="current-password"]').fill(ROTATED_PASSWORD);
    const loginWaiter = page.waitForResponse(response =>
      response.request().method() === 'POST' &&
      new URL(response.url()).pathname === '/api/v1/institution/auth/login');
    const overviewWaiter = page.waitForResponse(response =>
      response.request().method() === 'GET' &&
      new URL(response.url()).pathname === '/api/v1/institution/reports/overview');
    const rosterWaiter = page.waitForResponse(response =>
      response.request().method() === 'GET' &&
      new URL(response.url()).pathname === '/api/v1/institution/reports/roster');
    const coursesWaiter = page.waitForResponse(response =>
      response.request().method() === 'GET' &&
      new URL(response.url()).pathname === '/api/v1/institution/reports/courses');
    await page.getByRole('button', { name: 'Sign in securely' }).click();
    const [loginResponse, overviewResponse, rosterResponse, coursesResponse] = await Promise.all([
      loginWaiter, overviewWaiter, rosterWaiter, coursesWaiter
    ]);
    expect(loginResponse.status()).toBe(200);
    const loginBody = await loginResponse.json();
    expect(loginBody.authenticated).toBe(true);
    expect(loginBody.mustChangePassword).toBe(false);
    expect(loginBody.institutionCode).toBe(adminA.institutionCode);
    expect(overviewResponse.status()).toBe(200);
    expect(rosterResponse.status()).toBe(200);
    expect(coursesResponse.status()).toBe(200);
    const overview = await overviewResponse.json();
    const roster = await rosterResponse.json();
    const courseRows = await coursesResponse.json();
    expect(Array.isArray(roster.learners)).toBe(true);
    expect(Array.isArray(courseRows)).toBe(true);
    await expect(page.getByRole('navigation', { name: 'Institution report sections' })).toBeVisible();
    await expect(page.getByRole('heading', { name: loginBody.institutionName, exact: true })).toBeVisible();

    const pool = await openQaDatabase();
    try {
      const identity = await pool.query(`
        select a.id::int as admin_id,a.institution_id::int as institution_id,
               a.must_change_password,a.active,t.tenant_code
        from institution_admin_account a
        join institution_tenant t on t.id=a.institution_id
        where lower(a.username)=lower($1)
      `, [adminA.username]);
      expect(identity.rowCount).toBe(1);
      expect(identity.rows[0].tenant_code).toBe(adminA.institutionCode);
      expect(identity.rows[0].active).toBe(true);
      expect(identity.rows[0].must_change_password).toBe(false);
      const institutionId = Number(identity.rows[0].institution_id);
      expect(Number(loginBody.institutionId)).toBe(institutionId);

      const [expectedRoster, expectedCourses] = await Promise.all([
        pool.query(`
          select st.public_user_id as learner_reference
          from institution_membership m
          join student st on st.id=m.student_id and st.active=true and st.environment='PRODUCTION'
          where m.institution_id=$1
          order by st.public_user_id
        `, [institutionId]),
        pool.query(`
          select p.code,p.display_name
          from institution_course_entitlement e
          join curriculum_program p on p.id=e.program_id and p.status='ACTIVE' and p.code<>'GENERAL'
          where e.institution_id=$1 and e.status='ACTIVE' and e.valid_from<=now()
            and (e.valid_until is null or e.valid_until>now())
          order by p.display_name,p.code
        `, [institutionId])
      ]);
      expect(Number(roster.total)).toBe(expectedRoster.rowCount);
      expect(roster.learners.map((x: any) => String(x.learner_reference)).sort())
        .toEqual(expectedRoster.rows.map((x: any) => String(x.learner_reference)).sort());
      expect(courseRows.map((x: any) => String(x.code)))
        .toEqual(expectedCourses.rows.map((x: any) => String(x.code)));
      expect(Number(overview.authorized_courses)).toBe(expectedCourses.rowCount);

      const otherTenant = await pool.query(
        'select id from institution_tenant where tenant_code=$1 and status=\'ACTIVE\'',
        [adminB.institutionCode]
      );
      expect(otherTenant.rowCount).toBe(1);
      const otherTenantId = Number(otherTenant.rows[0].id);
      const otherRoster = await pool.query(`
        select st.public_user_id as learner_reference
        from institution_membership m
        join student st on st.id=m.student_id and st.active=true and st.environment='PRODUCTION'
        where m.institution_id=$1
        order by st.public_user_id
      `, [otherTenantId]);
      expect(expectedRoster.rows.map((x: any) => String(x.learner_reference)))
        .not.toEqual(otherRoster.rows.map((x: any) => String(x.learner_reference)));

      // Spoofing the institution header must not switch this authenticated admin's
      // tenant authority. The same cookie must still return the owning tenant's roster.
      const forgedTenantResponse = await apiJson(page, '/api/v1/institution/reports/roster?page=1&pageSize=200', adminB.institutionCode);
      expect(forgedTenantResponse.status).toBe(200);
      expect(forgedTenantResponse.body.learners.map((x: any) => String(x.learner_reference)).sort())
        .toEqual(expectedRoster.rows.map((x: any) => String(x.learner_reference)).sort());

      await page.getByRole('button', { name: 'Learners', exact: true }).click();
      const learnersSection = page.locator('.institution-dashboard');
      await expect(learnersSection).toContainText(String(adminA.institutionCode));
      await expect(learnersSection).not.toContainText(String(adminB.institutionCode));
      await page.getByRole('button', { name: 'Authorised courses', exact: true }).click();
      await expect(page.locator('.institution-dashboard')).toContainText(String(expectedCourses.rows[0]?.display_name ?? ''));
    } finally {
      await pool.end();
    }

    await page.getByRole('button', { name: 'Sign out' }).click();
    const signedOut = await apiJson(page, '/api/v1/institution/auth/me', adminA.institutionCode);
    expect([401, 403]).toContain(signedOut.status);
    expect(authResponses.some(x => x.path === '/api/v1/institution/auth/change-password' && x.status === 200)).toBe(true);
  } finally {
    page.off('response', onResponse);
    await context.close();
  }
});
