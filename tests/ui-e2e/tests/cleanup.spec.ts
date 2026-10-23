import { test, expect, type Page } from '@playwright/test';
import { appBaseUrl } from '../support/manifest';
import { openQaDatabase, readPrivateJson, type QaFixture } from '../support/fixtures';

const HRMS = appBaseUrl('people');

async function waitForApi(page: Page, method: string, pathPart: string) {
  return page.waitForResponse(response =>
    response.request().method() === method &&
    response.url().includes(pathPart)
  );
}

test('[QA-004] preview and uninstall only this run, then prove owned rows are cleaned', async ({ browser }) => {
  let fixture: Partial<QaFixture> | undefined;
  let intent: { packKey: string; packVersion: number; idempotencyKey: string; installStarted: boolean } | undefined;
  try { fixture = await readPrivateJson<QaFixture>('qa-fixture.json'); } catch (error) {
    if ((error as NodeJS.ErrnoException).code !== 'ENOENT') throw error;
  }
  try { intent = await readPrivateJson<typeof intent & {}>('qa-install-intent.json'); } catch (error) {
    if ((error as NodeJS.ErrnoException).code !== 'ENOENT') throw error;
  }

  // No intent means setup failed before the first fixture mutation was submitted.
  if (!intent?.installStarted) {
    expect(fixture?.runId, 'A run ID must not exist when no install intent was journaled.').toBeUndefined();
    return;
  }

  const poolForRecovery = await openQaDatabase();
  let runId = String(fixture?.runId || '');
  try {
    if (!runId && intent.idempotencyKey) {
      const recovered = await poolForRecovery.query(
        'select run_id::text from qa_test_run where pack_key=$1 and pack_version=$2 and idempotency_key=$3 order by created_at desc limit 1',
        [intent.packKey, intent.packVersion, intent.idempotencyKey]
      );
      runId = String(recovered.rows[0]?.run_id || '');
    }
    if (!runId) {
      const unexplainedRows = await poolForRecovery.query(
        'select count(*)::int as count from qa_test_run where pack_key=$1 and pack_version=$2 and idempotency_key=$3',
        [intent.packKey, intent.packVersion, intent.idempotencyKey]
      );
      expect(unexplainedRows.rows[0].count, 'A failed install must not leave an undiscoverable run behind.').toBe(0);
      return;
    }

    const runStatus = await poolForRecovery.query(
      'select state,created_resource_count,planned_resource_count from qa_test_run where run_id=$1',
      [runId]
    );
    expect(runStatus.rowCount, 'The install intent must resolve to one audit row.').toBe(1);
    if (runStatus.rows[0].state === 'INSTALL_FAILED') {
      // A failed seed transaction is expected to roll back all fixture rows and the resource
      // manifest while retaining only the run/event audit. Prove that before returning rather
      // than running the successful-install cleanup UI against a zero-row fixture.
      const leftovers = await poolForRecovery.query(`
        select
          (select count(*)::int from institution_tenant where qa_test_run_id=$1) as tenants,
          (select count(*)::int from institution_branding where qa_test_run_id=$1) as branding,
          (select count(*)::int from user_account where qa_test_run_id=$1) as users,
          (select count(*)::int from student where qa_test_run_id=$1) as students,
          (select count(*)::int from guardian_student where qa_test_run_id=$1) as guardian_links,
          (select count(*)::int from institution_membership where qa_test_run_id=$1) as student_memberships,
          (select count(*)::int from institution_user_membership where qa_test_run_id=$1) as user_memberships,
          (select count(*)::int from student_login_credential where qa_test_run_id=$1) as credentials,
          (select count(*)::int from institution_course_release where qa_test_run_id=$1) as releases,
          (select count(*)::int from institution_course_assignment where qa_test_run_id=$1) as assignments,
          (select count(*)::int from institution_course_entitlement where qa_test_run_id=$1) as entitlements,
          (select count(*)::int from student_program_enrollment where qa_test_run_id=$1) as enrollments,
          (select count(*)::int from student_track_enrollment where qa_test_run_id=$1) as tracks,
          (select count(*)::int from curriculum_chapter where qa_test_run_id=$1) as chapters,
          (select count(*)::int from lesson where qa_test_run_id=$1) as lessons,
          (select count(*)::int from question where qa_test_run_id=$1) as questions,
          (select count(*)::int from question_option where qa_test_run_id=$1) as options,
          (select count(*)::int from student_lesson_progress where qa_test_run_id=$1) as progress,
          (select count(*)::int from student_question_attempt where qa_test_run_id=$1) as attempts,
          (select count(*)::int from qa_test_run_resource where run_id=$1) as manifest_rows
      `, [runId]);
      expect(Number(runStatus.rows[0].created_resource_count)).toBe(0);
      expect(Number(runStatus.rows[0].planned_resource_count)).toBe(0);
      expect(leftovers.rows[0]).toMatchObject({
        tenants:0,branding:0,users:0,students:0,guardian_links:0,
        student_memberships:0,user_memberships:0,credentials:0,releases:0,
        assignments:0,entitlements:0,enrollments:0,tracks:0,chapters:0,lessons:0,
        questions:0,options:0,progress:0,attempts:0,manifest_rows:0
      });
      return;
    }
    expect(runStatus.rows[0].state, 'Only a committed INSTALLED fixture can enter UI cleanup checks.').toBe('INSTALLED');
  } finally {
    await poolForRecovery.end();
  }
  expect(runId).toMatch(/^[0-9a-f-]{36}$/i);
  const ownerState = await readPrivateJson<{ statePath: string }>('hrms-owner-state-path.json');
  expect(ownerState.statePath).toBeTruthy();

  const context = await browser.newContext({ storageState: ownerState.statePath });
  const page = await context.newPage();
  const serverErrors: string[] = [];
  page.on('response', async response => {
    if (response.status() < 500) return;
    const raw = await response.text().catch(() => '');
    serverErrors.push(response.status() + ' ' + new URL(response.url()).pathname + (raw ? ' ' + raw.slice(0, 500) : ''));
  });
  try {
    const runsWaiter = waitForApi(page, 'GET', '/api/v1/hrms/platform/qa/runs');
    await page.goto(HRMS + '/qa');
    const runsResponse = await runsWaiter;
    expect(runsResponse.status()).toBe(200);

    const runRow = page.locator('section[aria-labelledby="qa-runs-heading"] article')
      .filter({ hasText: runId });
    await expect(runRow).toBeVisible();
    const inspectWaiter = waitForApi(page, 'GET', '/api/v1/hrms/platform/qa/runs/' + runId);
    await runRow.getByRole('button').click();
    const inspectResponse = await inspectWaiter;
    expect(inspectResponse.status()).toBe(200);

    const pool = await openQaDatabase();
    try {
      const before = await pool.query(
        `select
          (select count(*)::int from institution_tenant where qa_test_run_id=$1) as tenants,
          (select count(*)::int from user_account where qa_test_run_id=$1) as users,
          (select count(*)::int from student where qa_test_run_id=$1) as students
        `,
        [runId]
      );
      expect(before.rows[0].tenants).toBe(3);
      expect(before.rows[0].users).toBeGreaterThan(0);
      expect(before.rows[0].students).toBeGreaterThan(0);

      const previewButton = page.getByTestId('qa-preview-uninstall');
      await expect(previewButton, 'Inspecting a run must render the preview action independently of the selected UI language.').toBeVisible();
      await expect(previewButton).toBeEnabled();
      const previewWaiter = waitForApi(page, 'GET', '/api/v1/hrms/platform/qa/runs/' + runId + '/uninstall-plan');
      await previewButton.click();
      const previewResponse = await previewWaiter;
      expect(previewResponse.status()).toBe(200);
      const preview = await previewResponse.json();
      expect(Number(preview.resourceCount)).toBeGreaterThan(0);
      await expect(page.getByTestId('qa-uninstall-heading')).toBeVisible();

      // A preview must not mutate the run or any owned resource.
      const afterPreview = await pool.query(
        'select count(*)::int as count from institution_tenant where qa_test_run_id=$1',
        [runId]
      );
      expect(afterPreview.rows[0].count).toBe(3);
    } finally {
      await pool.end();
    }

    const confirmInput = page.locator('section[aria-labelledby="qa-uninstall-heading"] input');
    const confirmButton = page.getByTestId('qa-confirm-uninstall');
    await expect(confirmButton).toBeDisabled();
    await confirmInput.fill('not-the-run-id');
    await expect(confirmButton).toBeDisabled();
    await confirmInput.fill(runId);

    const uninstallWaiter = waitForApi(page, 'POST', '/api/v1/hrms/platform/qa/runs/' + runId + '/uninstall');
    await confirmButton.click();
    const uninstallResponse = await uninstallWaiter;
    expect(uninstallResponse.status(), 'The HRMS UI must submit the exact run-ID confirmation to the live API.').toBe(200);
    const uninstalled = await uninstallResponse.json();
    expect(uninstalled.state).toBe('UNINSTALLED');

    const poolAfterUninstall = await openQaDatabase();
    try {
      const run = await poolAfterUninstall.query(
        'select state, cleaned_resource_count, created_resource_count from qa_test_run where run_id=$1',
        [runId]
      );
      expect(run.rowCount).toBe(1);
      expect(run.rows[0].state).toBe('UNINSTALLED');

      const ownedRows = await poolAfterUninstall.query(
        `select
          (select count(*)::int from institution_tenant where qa_test_run_id=$1) as tenants,
          (select count(*)::int from user_account where qa_test_run_id=$1) as users,
          (select count(*)::int from student where qa_test_run_id=$1) as students,
          (select count(*)::int from guardian_student where qa_test_run_id=$1) as guardian_links,
          (select count(*)::int from institution_course_assignment where qa_test_run_id=$1) as assignments,
          (select count(*)::int from qa_test_run_resource where run_id=$1 and state not in ('CLEANED','RETAINED','REVERSED')) as unresolved_resources
        `,
        [runId]
      );
      expect(ownedRows.rows[0]).toMatchObject({
        tenants: 0, users: 0, students: 0, guardian_links: 0, assignments: 0, unresolved_resources: 0
      });
      expect(Number(run.rows[0].cleaned_resource_count)).toBeGreaterThan(0);
    } finally {
      await poolAfterUninstall.end();
    }

    const visibleAlerts = (await page.getByRole('alert').allTextContents())
      .map(text => text.trim())
      .filter(Boolean);
    expect(
      { visibleAlerts, serverErrors },
      'QA cleanup must complete without app error alerts or HTTP 5xx responses.'
    ).toEqual({ visibleAlerts: [], serverErrors: [] });
  } finally {
    await context.close();
  }
});
