import { test, expect, type Browser, type Page } from '@playwright/test';
import bcrypt from 'bcryptjs';
import { join } from 'node:path';
import { appBaseUrl, loadUiManifest } from '../support/manifest';
import { newIdempotencyKey, openQaDatabase, preparePrivateDir, writePrivateJson, type QaFixture } from '../support/fixtures';

const WEB = appBaseUrl('web');
const ADMIN = appBaseUrl('admin');
const HRMS = appBaseUrl('people');
const ADMIN_MOBILE = process.env.QE_E2E_ADMIN_BOOTSTRAP_MOBILE || '7070107483';
const INITIAL_HRMS_PASSWORD = process.env.QE_E2E_INITIAL_HRMS_PASSWORD || 'QE-E2E-Initial-HRMS-2026!';
const FINAL_HRMS_PASSWORD = process.env.QE_E2E_HRMS_OPERATOR_PASSWORD || 'QE-E2E-HRMS#Operator-2026';

async function readDisposableOtp(mobile: string, purpose: string): Promise<string> {
  const baseUrl = process.env.QE_E2E_OTP_MOCK_URL;
  const token = process.env.QE_E2E_OTP_MOCK_TOKEN;
  if (!baseUrl || !token) {
    throw new Error('Disposable OTP mock URL/token are required for non-demo browser authentication.');
  }
  const digits = mobile.replace(/[^0-9]/g, '');
  const recipient = digits.length === 10 ? '+91' + digits : digits.startsWith('91') && digits.length === 12 ? '+' + digits : mobile;
  const url = new URL('/latest', baseUrl);
  url.searchParams.set('to', recipient);
  url.searchParams.set('purpose', purpose);
  const response = await fetch(url, {
    headers: { Authorization: 'Bearer ' + token, Accept: 'application/json' },
    cache: 'no-store',
  });
  if (!response.ok) {
    throw new Error('Disposable OTP mock lookup failed (' + response.status + ') for ' + purpose + '.');
  }
  const body = await response.json().catch(() => ({})) as { otp?: unknown };
  const otp = String(body.otp ?? '');
  if (!/^[0-9]{6}$/.test(otp)) {
    throw new Error('Disposable OTP mock returned an invalid code for ' + purpose + '.');
  }
  return otp;
}

async function seedOnlyDisposableHrmsBootstrap(): Promise<void> {
  const pool = await openQaDatabase();
  try {
    // Only a single, already-migrated bootstrap row is changed; no login/session bypass is created.
    // Its forced password-change flow is then exercised in the actual HRMS login UI.
    const hash = await bcrypt.hash(INITIAL_HRMS_PASSWORD, 12);
    const result = await pool.query(
      `update staff_account
         set password_hash=$1, must_change_password=true, password_changed_at=null,
             updated_at=now()
       where lower(username)='hrms_admin' and active=true`,
      [hash]
    );
    expect(result.rowCount, 'Fresh QA database must contain exactly one migrated hrms_admin bootstrap row.').toBe(1);
    const owner = await pool.query(
      `select count(*)::int as count
       from staff_permission_grant
       where staff_id=(select id from staff_account where lower(username)='hrms_admin')
         and workspace_key='HRMS' and permission_key='QE_PLATFORM_OWNER'`
    );
    expect(owner.rows[0]?.count, 'Bootstrap account must receive its explicit HRMS platform-owner permission from migrations.').toBe(1);
  } finally {
    await pool.end();
  }
}

async function loginHrmsThroughUi(page: Page, password: string): Promise<void> {
  // Each persona must begin with a clean cookie jar. Browser origins on localhost share
  // cookies across ports, so explicitly clear any inherited session before authentication.
  await page.context().clearCookies();
  await page.goto(HRMS + '/login', { waitUntil: 'domcontentloaded' });
  await expect(page).toHaveURL(url => url.origin === new URL(HRMS).origin && url.pathname === '/login');
  await expect(page.locator('#username'), 'A fresh HRMS context must show the actual login form.').toBeVisible();
  await page.locator('#username').fill('hrms_admin');
  await page.locator('#password').fill(password);
  const loginWaiter = page.waitForResponse(response =>
    response.request().method() === 'POST' &&
    response.url().includes('/api/v1/auth/hrms-login'));
  await page.locator('form button.qe-people-button').click();
  const loginResponse = await loginWaiter;
  expect(loginResponse.status(), 'HRMS username/password login must be accepted before the forced password-change flow.').toBe(200);
}

async function setUpHrmsOwner(browser: Browser): Promise<string> {
  const context = await browser.newContext();
  const page = await context.newPage();

  await loginHrmsThroughUi(page, INITIAL_HRMS_PASSWORD);
  await expect(page).toHaveURL(/\/change-password(?:\?.*)?$/);

  await page.locator('#current-password').fill(INITIAL_HRMS_PASSWORD);
  await page.locator('#new-password').fill(FINAL_HRMS_PASSWORD);
  await page.locator('#confirm-password').fill(FINAL_HRMS_PASSWORD);
  await page.locator('form button.qe-people-button').click();
  await page.waitForURL(url => !url.pathname.endsWith('/change-password'), { timeout: 20_000 });

  // Password change can revoke the just-used session; authenticate again with the replacement.
  await loginHrmsThroughUi(page, FINAL_HRMS_PASSWORD);
  await expect(page).toHaveURL(url => url.origin === HRMS && !url.pathname.endsWith('/login') && !url.pathname.endsWith('/change-password'));
  const meResponse = await page.evaluate(async () => {
    const response = await fetch('/api/v1/auth/me', {
      credentials: 'same-origin', cache: 'no-store',
      headers: { 'X-QE-Workspace': 'HRMS' }
    });
    return { status: response.status, body: await response.json().catch(() => ({})) };
  });
  expect(meResponse.status, 'HRMS UI login must establish a server-authenticated session.').toBe(200);
  expect(meResponse.body.workspace).toBe('HRMS');
  expect(Number(meResponse.body.staffId)).toBeGreaterThan(0);
  const storagePath = join(await preparePrivateDir(), 'hrms-owner.json');
  await context.storageState({ path: storagePath });
  await context.close();
  return storagePath;
}

async function setUpEducationAdmin(browser: Browser): Promise<string> {
  const context = await browser.newContext();
  const page = await context.newPage();
  const requestOtp = page.waitForResponse(response =>
    response.request().method() === 'POST' &&
    response.url().includes('/api/v1/auth/request-otp'));
  await page.goto(ADMIN + '/login');
  await page.locator('#staff-mobile').fill(ADMIN_MOBILE);
  await page.locator('#staff-mobile').locator('xpath=ancestor::form').locator('button[type="submit"]').click();
  const otpResponse = await requestOtp;
  const otpResponseBody = await otpResponse.text();
  expect(otpResponse.status(), 'Education Admin bootstrap OTP request must succeed through the local-only provider. Body: ' + otpResponseBody.slice(0, 250)).toBe(200);
  const otpBody = JSON.parse(otpResponseBody || '{}');
  expect(otpBody.firstAccess).toBe(true);

  const validOtp = String(otpBody.devCode || await readDisposableOtp(ADMIN_MOBILE, 'STAFF_LOGIN'));
  const invalidOtp = validOtp === '000000' ? '000001' : '000000';

  // Exercise the negative authentication path before the valid code. A failed OTP
  // must not issue a staff session, and the still-live valid code must remain usable.
  await page.locator('#staff-otp').fill(invalidOtp);
  const invalidVerifyOtp = page.waitForResponse(response =>
    response.request().method() === 'POST' &&
    response.url().includes('/api/v1/auth/verify-otp'));
  await page.locator('#staff-otp').locator('xpath=ancestor::form').locator('button[type="submit"]').click();
  const invalidVerifyResponse = await invalidVerifyOtp;
  expect([400, 401, 403], 'An incorrect one-time code must be rejected without authenticating the staff account.').toContain(invalidVerifyResponse.status());

  const sessionAfterInvalidOtp = await page.evaluate(async () => {
    const response = await fetch('/api/v1/auth/me', {
      credentials: 'same-origin', cache: 'no-store',
      headers: { 'X-QE-Workspace': 'EDUCATION_ADMIN' }
    });
    return { status: response.status };
  });
  expect([401, 403], 'An incorrect OTP must not establish an Education Admin session.').toContain(sessionAfterInvalidOtp.status);

  await page.locator('#staff-otp').fill(validOtp);
  const verifyOtp = page.waitForResponse(response =>
    response.request().method() === 'POST' &&
    response.url().includes('/api/v1/auth/verify-otp'));
  await page.locator('#staff-otp').locator('xpath=ancestor::form').locator('button[type="submit"]').click();
  const verifyResponse = await verifyOtp;
  expect(verifyResponse.status(), 'Education Admin first-access OTP verification must succeed.').toBe(200);
  await page.waitForURL(url => url.origin === ADMIN && url.pathname !== '/login');
  const meResponse = await page.evaluate(async () => {
    const response = await fetch('/api/v1/auth/me', {
      credentials: 'same-origin', cache: 'no-store',
      headers: { 'X-QE-Workspace': 'EDUCATION_ADMIN' }
    });
    return { status: response.status, body: await response.json().catch(() => ({})) };
  });
  expect(meResponse.status).toBe(200);
  expect(meResponse.body.role).toBe('ADMIN');
  const storagePath = join(await preparePrivateDir(), 'education-admin.json');
  await context.storageState({ path: storagePath });
  await context.close();
  return storagePath;
}

async function installAndVerifyQaPack(browser: Browser, hrmsStatePath: string): Promise<QaFixture> {
  const context = await browser.newContext({ storageState: hrmsStatePath });
  const page = await context.newPage();

  // Register all network waiters before navigation: the page loads status, pack metadata,
  // and run history concurrently, so awaiting one response before observing another can race.
  const gateWaiter = page.waitForResponse(response =>
    response.url().includes('/api/v1/hrms/platform/qa/status') && response.request().method() === 'GET');
  const packsWaiter = page.waitForResponse(response =>
    response.url().includes('/api/v1/hrms/platform/qa/packs') && response.request().method() === 'GET');
  const runsWaiter = page.waitForResponse(response =>
    response.url().includes('/api/v1/hrms/platform/qa/runs') &&
    !response.url().includes('/api/v1/hrms/platform/qa/runs/') &&
    response.request().method() === 'GET');
  await page.goto(HRMS + '/qa');
  const [gateResponse, packsResponse, runsResponse] = await Promise.all([gateWaiter, packsWaiter, runsWaiter]);
  expect(gateResponse.status(), 'The HRMS QA Center gate must come from the running API.').toBe(200);
  expect(packsResponse.status(), 'The HRMS QA Center pack list must come from the running API.').toBe(200);
  expect(runsResponse.status(), 'The HRMS QA Center run history must come from the running API.').toBe(200);
  const gate = await gateResponse.json();
  expect(gate.writeAllowed, 'Only the explicitly approved disposable QA database may accept fixture writes.').toBe(true);
  expect(gate.currentDatabase).toBe(process.env.QE_E2E_TARGET_DATABASE || 'quantaedge_qa');
  expect(gate.productionProfileDetected).toBe(false);
  const packsBody = await packsResponse.json();
  const pack = (packsBody.items || []).find((item: any) => item.packKey === 'TENANT_LEARNER_BOUNDARY_V1');
  expect(pack, 'The server must advertise the supported versioned tenant/learner QA pack.').toBeTruthy();
  expect(Number(pack.minimumInstitutions)).toBeGreaterThanOrEqual(3);

  const idempotencyKey = newIdempotencyKey();
  const card = page.locator('article').filter({ hasText: pack.packKey }).first();
  await expect(card).toBeVisible();
  await card.locator('input[maxlength="120"]').fill(idempotencyKey);

  // The password-bearing response is consumed in memory only. Tracing is deliberately off,
  // and the rendered credentials panel is visually redacted before any failure screenshot.
  await page.addStyleTag({ content: 'pre { filter: blur(12px) !important; }' });
  // Journal the run identity before the mutating request, so cleanup can recover a
  // run even when a browser assertion fails after the API commits.
  await writePrivateJson('qa-install-intent.json', {
    sourceSha: process.env.GITHUB_SHA || process.env.QE_E2E_SOURCE_SHA || 'local-unpinned',
    packKey: String(pack.packKey),
    packVersion: Number(pack.version),
    idempotencyKey,
    installStarted: true
  });
  const installWaiter = page.waitForResponse(response =>
    response.request().method() === 'POST' &&
    response.url().includes('/api/v1/hrms/platform/qa/runs/install'));
  await card.locator('button').first().click();
  const installResponse = await installWaiter;
  expect(installResponse.status(), 'Installing a QA pack must use the HRMS UI and live API.').toBe(200);
  const installed = await installResponse.json();
  const runId = String(installed.run_id || installed.runId || '');
  // Persist the primary cleanup key and one-time credentials before any further assertions.
  await writePrivateJson('qa-fixture.json', {
    sourceSha: process.env.GITHUB_SHA || process.env.QE_E2E_SOURCE_SHA || 'local-unpinned',
    runId,
    packKey: String(pack.packKey),
    idempotencyKey,
    state: installed.state,
    errorCode: installed.error_code ?? null,
    errorSummary: installed.error_summary ?? null,
    credentials: installed.oneTimeSyntheticCredentials || {}
  });
  // Fail here with the service's actual persisted error summary, rather than later presenting
  // a failed installation as an idempotency-replay defect.
  expect(
    installed.state,
    'The QA fixture transaction must finish before idempotency replay. Service result: ' +
      String(installed.error_code ?? 'NO_ERROR_CODE') + ' — ' +
      String(installed.error_summary ?? 'no installation error summary')
  ).toBe('INSTALLED');
  expect(installed.credentialsIncluded).toBe(true);
  expect(installed.oneTimeSyntheticCredentials).toBeTruthy();
  expect(runId).toMatch(/^[0-9a-f-]{36}$/i);

  // Replay the same install key after a successful UI install. This must return the same
  // run without creating duplicates, and must not disclose one-time credentials a second time.
  const replay = await page.evaluate(async ({ packKey, idempotencyKey }) => {
    const response = await fetch('/api/v1/hrms/platform/qa/runs/install', {
      method: 'POST', credentials: 'same-origin', cache: 'no-store',
      headers: { 'X-QE-Workspace': 'HRMS', 'Content-Type': 'application/json', Accept: 'application/json' },
      body: JSON.stringify({ packKey, idempotencyKey })
    });
    return { status: response.status, body: await response.json().catch(() => ({})) };
  }, { packKey: String(pack.packKey), idempotencyKey });
  expect(replay.status, 'Replaying an installed pack with the same idempotency key must be safe.').toBe(200);
  expect(replay.body.replayed).toBe(true);
  expect(String(replay.body.run_id || replay.body.runId)).toBe(runId);
  expect(replay.body.credentialsIncluded).toBe(false);
  expect(replay.body.oneTimeSyntheticCredentials).toBeUndefined();

  const runPool = await openQaDatabase();
  try {
    const run = await runPool.query(
      'select run_id::text, state, planned_resource_count, created_resource_count from qa_test_run where run_id=$1',
      [runId]
    );
    expect(run.rowCount).toBe(1);
    expect(run.rows[0].state).toBe('INSTALLED');
    expect(run.rows[0].planned_resource_count).toBe(run.rows[0].created_resource_count);
    const tenants = await runPool.query('select count(*)::int as count from institution_tenant where qa_test_run_id=$1', [runId]);
    expect(tenants.rows[0].count, 'The QA pack must create exactly three run-owned tenant institutions.').toBe(3);
    const manifest = await runPool.query(
      `select count(*)::int as manifest_count,
              count(*) filter (where state='OWNED')::int as owned_count
       from qa_test_run_resource where run_id=$1`,
      [runId]
    );
    expect(manifest.rows[0].manifest_count).toBeGreaterThan(0);
    expect(manifest.rows[0].manifest_count).toBe(manifest.rows[0].owned_count);
    const idempotentRuns = await runPool.query(
      'select count(*)::int as count from qa_test_run where pack_key=$1 and pack_version=$2 and idempotency_key=$3',
      [String(pack.packKey), Number(pack.version), idempotencyKey]
    );
    expect(idempotentRuns.rows[0].count, 'Same-key replay must retain one run row.').toBe(1);
  } finally {
    await runPool.end();
  }

  const verifyWaiter = page.waitForResponse(response =>
    response.request().method() === 'POST' &&
    response.url().includes('/api/v1/hrms/platform/qa/runs/' + runId + '/verify'));
  // This is the second button in the selected-run action row: Refresh, Run checks, Uninstall plan.
  await page.locator('section[aria-labelledby="qa-run-detail-heading"] button').nth(1).click();
  const verificationResponse = await verifyWaiter;
  expect(verificationResponse.status(), 'QA verification must query live PostgreSQL rows and their ownership links.').toBe(200);
  const verified = await verificationResponse.json();
  expect(verified.verification?.overallStatus, 'Every starter-pack relational ownership invariant must pass.').toBe('PASS');
  expect(Number(verified.verification?.failedChecks)).toBe(0);

  const fixture: QaFixture = {
    sourceSha: process.env.GITHUB_SHA || process.env.QE_E2E_SOURCE_SHA || 'local-unpinned',
    runId,
    packKey: String(pack.packKey),
    idempotencyKey,
    credentials: installed.oneTimeSyntheticCredentials
  };
  await writePrivateJson('qa-fixture.json', fixture);
  await context.close();
  return fixture;
}

async function waitForSelectedInstitution(page: Page, institutionCode: string): Promise<void> {
  await expect.poll(
    () => page.evaluate(() => window.sessionStorage.getItem('qe.institution.brand-code.v1')),
    { timeout: 20_000 }
  ).toBe(institutionCode);
}

async function loginParent(browser: Browser, name: 'parentA' | 'parentB', credentials: QaFixture['credentials'][ 'parentA' ] | QaFixture['credentials'][ 'parentB' ]): Promise<void> {
  const context = await browser.newContext();
  const page = await context.newPage();
  const destination = WEB + '/login?institutionCode=' + encodeURIComponent(credentials.institutionCode);
  await page.goto(destination);
  await waitForSelectedInstitution(page, credentials.institutionCode);
  await page.getByRole('tab').nth(1).click();
  await page.locator('input[autocomplete="username"]').fill(credentials.username);
  await page.locator('input[autocomplete="current-password"]').fill(credentials.password);
  const childrenDataWaiter = page.waitForResponse(response =>
    response.request().method() === 'GET' &&
    response.url().includes('/api/v1/guardians/children'));
  const loginResponse = page.waitForResponse(response =>
    response.request().method() === 'POST' &&
    response.url().includes('/api/v1/auth/account-login'));
  await page.locator('form button[type="submit"]').click();
  const response = await loginResponse;
  expect(response.status(), name + ' must authenticate through the live account-login endpoint.').toBe(200);
  expect(response.request().headers()['x-qe-institution-code']).toBe(credentials.institutionCode);
  await page.waitForURL(url => url.origin === WEB && url.pathname === '/parent');
  const childrenResponse = await childrenDataWaiter;
  expect(childrenResponse.status(), 'The parent UI must load children from the live API.').toBe(200);
  expect(childrenResponse.request().headers()['x-qe-institution-code']).toBe(credentials.institutionCode);
  const loadedChildren = await childrenResponse.json();
  expect(Array.isArray(loadedChildren)).toBe(true);
  expect(loadedChildren).toHaveLength(1);
  const statePath = join(await preparePrivateDir(), name.replace(/[A-Z]/g, m => '-' + m.toLowerCase()) + '.json');
  await context.storageState({ path: statePath });
  await context.close();
}

async function loginStudent(browser: Browser, name: 'childA' | 'childB' | 'independentLearner', credentials: any, parentIdentifier?: string): Promise<void> {
  const context = await browser.newContext();
  const page = await context.newPage();
  if (name === 'independentLearner') {
    await page.goto(WEB + '/login?institutionCode=' + encodeURIComponent(credentials.institutionCode));
    await waitForSelectedInstitution(page, credentials.institutionCode);
    await page.getByRole('tab').nth(1).click();
    await page.locator('input[autocomplete="username"]').fill(credentials.username);
    await page.locator('input[autocomplete="current-password"]').fill(credentials.password);
    const studentDataWaiter = page.waitForResponse(response =>
      response.request().method() === 'GET' &&
      response.url().includes('/api/v1/students/me'));
    const loginResponse = page.waitForResponse(response =>
      response.request().method() === 'POST' &&
      response.url().includes('/api/v1/auth/account-login'));
    await page.locator('form button[type="submit"]').click();
    const response = await loginResponse;
    expect(response.status(), 'The independent learner must authenticate through the live account-login endpoint.').toBe(200);
    expect(response.request().headers()['x-qe-institution-code']).toBe(credentials.institutionCode);
    const studentDataResponse = await studentDataWaiter;
    expect(studentDataResponse.status(), 'The independent learner UI must load its student profile from the live API.').toBe(200);
    expect(studentDataResponse.request().headers()['x-qe-institution-code']).toBe(credentials.institutionCode);
  } else {
    await page.goto(WEB + '/login/student?institutionCode=' + encodeURIComponent(credentials.institutionCode));
    await waitForSelectedInstitution(page, credentials.institutionCode);
    const identifiers = page.locator('input[autocomplete="username"]');
    await identifiers.nth(0).fill(String(parentIdentifier || credentials.parentMobile));
    await identifiers.nth(1).fill(credentials.username);
    await page.locator('input[autocomplete="current-password"]').fill(credentials.password);
    const studentDataWaiter = page.waitForResponse(response =>
      response.request().method() === 'GET' &&
      response.url().includes('/api/v1/students/me'));
    const loginResponse = page.waitForResponse(response =>
      response.request().method() === 'POST' &&
      response.url().includes('/api/v1/auth/student-login'));
    await page.locator('form button[type="submit"]').click();
    const response = await loginResponse;
    expect(response.status(), name + ' must authenticate through the live parent/child session endpoint.').toBe(200);
    expect(response.request().headers()['x-qe-institution-code']).toBe(credentials.institutionCode);
    const studentDataResponse = await studentDataWaiter;
    expect(studentDataResponse.status(), 'The child UI must load the authenticated student profile from the live API.').toBe(200);
    expect(studentDataResponse.request().headers()['x-qe-institution-code']).toBe(credentials.institutionCode);
  }
  await page.waitForURL(url => url.origin === WEB && url.pathname === '/student');
  const statePath = join(await preparePrivateDir(), name === 'independentLearner' ? 'adult-c.json' : name.replace(/[A-Z]/g, m => '-' + m.toLowerCase()) + '.json');
  await context.storageState({ path: statePath });
  await context.close();
}

test('[AUTH-001][AUTH-002][AUTH-006][AUTH-007][TEN-003][QA-001][QA-002][QA-007] bootstrap test personas, exercise sign-in/QA Center UI, and seed owned relational fixtures', async ({ browser }) => {
  // Route/CSV drift must stop the run before the first database or account mutation.
  const manifest = await loadUiManifest();
  expect(manifest.summary.registeredWebPages).toBe(manifest.summary.sourceWebPages);
  expect(manifest.summary.scenarios).toBeGreaterThan(0);
  await seedOnlyDisposableHrmsBootstrap();
  const hrmsStatePath = await setUpHrmsOwner(browser);
  await writePrivateJson('hrms-owner-state-path.json', { statePath: hrmsStatePath });

  const adminStatePath = await setUpEducationAdmin(browser);
  await writePrivateJson('education-admin-state-path.json', { statePath: adminStatePath });

  const fixture = await installAndVerifyQaPack(browser, hrmsStatePath);
  await loginParent(browser, 'parentA', fixture.credentials.parentA);
  await loginParent(browser, 'parentB', fixture.credentials.parentB);
  await loginStudent(browser, 'childA', fixture.credentials.childA, fixture.credentials.parentA.mobile);
  await loginStudent(browser, 'childB', fixture.credentials.childB, fixture.credentials.parentB.mobile);
  await loginStudent(browser, 'independentLearner', fixture.credentials.independentLearner);

  const pool = await openQaDatabase();
  try {
    const identityCounts = await pool.query(
      `select
        (select count(*)::int from user_account where qa_test_run_id=$1 and active=true) as users,
        (select count(*)::int from student where qa_test_run_id=$1 and active=true) as students,
        (select count(*)::int from guardian_student where qa_test_run_id=$1 and active=true and consent_status='CONSENTED') as guardian_links,
        (select count(*)::int from institution_course_assignment where qa_test_run_id=$1 and status='ASSIGNED') as assignments`,
      [fixture.runId]
    );
    expect(identityCounts.rows[0]).toMatchObject({ users: 3, students: 3, guardian_links: 2, assignments: 3 });
  } finally {
    await pool.end();
  }
});
