import { test, expect, type Page } from '@playwright/test';
import { appBaseUrl } from '../support/manifest';
import { openQaDatabase, readPrivateJson, type QaFixture } from '../support/fixtures';

const HRMS = appBaseUrl('people', 'http://127.0.0.1:3002');

async function waitForApi(page: Page, method: string, pathPart: string) {
  return page.waitForResponse(response =>
    response.request().method() === method &&
    response.url().includes(pathPart)
  );
}

test('preview and uninstall only this run, then prove owned rows are cleaned', async ({ browser }) => {
  const fixture = await readPrivateJson<QaFixture>('qa-fixture.json');
  const ownerState = await readPrivateJson<{ statePath: string }>('hrms-owner-state-path.json');

  expect(fixture.runId).toMatch(/^[0-9a-f-]{36}$/i);
  expect(ownerState.statePath).toBeTruthy();

  const context = await browser.newContext({ storageState: ownerState.statePath });
  const page = await context.newPage();
  try {
    const runsWaiter = waitForApi(page, 'GET', '/api/v1/hrms/platform/qa/runs');
    await page.goto(HRMS + '/qa');
    const runsResponse = await runsWaiter;
    expect(runsResponse.status()).toBe(200);

    const runRow = page.locator('section[aria-labelledby="qa-runs-heading"] article')
      .filter({ hasText: fixture.runId });
    await expect(runRow).toBeVisible();
    const inspectWaiter = waitForApi(page, 'GET', '/api/v1/hrms/platform/qa/runs/' + fixture.runId);
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
        [fixture.runId]
      );
      expect(before.rows[0].tenants).toBe(3);
      expect(before.rows[0].users).toBeGreaterThan(0);
      expect(before.rows[0].students).toBeGreaterThan(0);

      const previewWaiter = waitForApi(page, 'GET', '/api/v1/hrms/platform/qa/runs/' + fixture.runId + '/uninstall-plan');
      await page.getByRole('button', { name: /uninstall plan/i }).click();
      const previewResponse = await previewWaiter;
      expect(previewResponse.status()).toBe(200);
      const preview = await previewResponse.json();
      expect(Number(preview.resourceCount)).toBeGreaterThan(0);
      await expect(page.getByRole('heading', { name: /uninstall plan/i })).toBeVisible();

      // A preview must not mutate the run or any owned resource.
      const afterPreview = await pool.query(
        'select count(*)::int as count from institution_tenant where qa_test_run_id=$1',
        [fixture.runId]
      );
      expect(afterPreview.rows[0].count).toBe(3);
    } finally {
      await pool.end();
    }

    const confirmInput = page.locator('section[aria-labelledby="qa-uninstall-heading"] input');
    const confirmButton = page.getByRole('button', { name: /confirm uninstall/i });
    await expect(confirmButton).toBeDisabled();
    await confirmInput.fill('not-the-run-id');
    await expect(confirmButton).toBeDisabled();
    await confirmInput.fill(fixture.runId);

    const uninstallWaiter = waitForApi(page, 'POST', '/api/v1/hrms/platform/qa/runs/' + fixture.runId + '/uninstall');
    await confirmButton.click();
    const uninstallResponse = await uninstallWaiter;
    expect(uninstallResponse.status(), 'The HRMS UI must submit the exact run-ID confirmation to the live API.').toBe(200);
    const uninstalled = await uninstallResponse.json();
    expect(uninstalled.state).toBe('UNINSTALLED');

    const pool = await openQaDatabase();
    try {
      const run = await pool.query(
        'select state, cleaned_resource_count, created_resource_count from qa_test_run where run_id=$1',
        [fixture.runId]
      );
      expect(run.rowCount).toBe(1);
      expect(run.rows[0].state).toBe('UNINSTALLED');

      const ownedRows = await pool.query(
        `select
          (select count(*)::int from institution_tenant where qa_test_run_id=$1) as tenants,
          (select count(*)::int from user_account where qa_test_run_id=$1) as users,
          (select count(*)::int from student where qa_test_run_id=$1) as students,
          (select count(*)::int from guardian_student where qa_test_run_id=$1) as guardian_links,
          (select count(*)::int from institution_course_assignment where qa_test_run_id=$1) as assignments,
          (select count(*)::int from qa_test_run_resource where run_id=$1 and state not in ('CLEANED','RETAINED','REVERSED')) as unresolved_resources
        `,
        [fixture.runId]
      );
      expect(ownedRows.rows[0]).toMatchObject({
        tenants: 0, users: 0, students: 0, guardian_links: 0, assignments: 0, unresolved_resources: 0
      });
      expect(Number(run.rows[0].cleaned_resource_count)).toBeGreaterThan(0);
    } finally {
      await pool.end();
    }

    await expect(page.getByRole('alert')).toHaveCount(0);
  } finally {
    await context.close();
  }
});
