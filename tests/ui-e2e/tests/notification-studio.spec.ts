import { test, expect } from '@playwright/test';
import { openQaDatabase, readPrivateJson } from '../support/fixtures';
import { appBaseUrl } from '../support/manifest';

const HRMS = appBaseUrl('people');

test('[HRMS-NOTIF-001] Notification Studio loads live options and prevents invalid audience actions', async ({ browser }) => {
  const ownerState = await readPrivateJson<{ statePath: string }>('hrms-owner-state-path.json');
  const context = await browser.newContext({
    storageState: ownerState.statePath,
    viewport: { width: 1440, height: 900 }
  });
  const page = await context.newPage();
  const browserErrors: string[] = [];
  const notificationWrites: string[] = [];
  const onPageError = (error: Error) => browserErrors.push(error.message);
  page.on('pageerror', onPageError);
  page.on('request', request => {
    if (request.method() === 'POST' && request.url().includes('/api/v1/hrms/notifications/')) {
      notificationWrites.push(request.url());
    }
  });

  const pool = await openQaDatabase();
  const readNotificationCounts = async () => {
    const result = await pool.query(`
      select
        (select count(*)::int from notification_template) as templates,
        (select count(*)::int from notification_campaign) as campaigns,
        (select count(*)::int from notification_recipient) as recipients
    `);
    return result.rows[0];
  };

  try {
    // Confirm that this persisted HRMS owner session is still valid before navigating
    // to Notification Studio. Follow the actual sidebar link so route/session guards run
    // exactly as they do for an operator clicking through HRMS.
    await page.goto(HRMS + '/qa', { waitUntil: 'domcontentloaded' });
    // Probe the session explicitly in the HRMS workspace. A shell may issue other
    // auth/me probes during hydration; do not mistake a different-workspace 401 for
    // failure of the persisted HRMS owner session.
    const session = await page.evaluate(async () => {
      const response = await fetch('/api/v1/auth/me', {
        credentials: 'same-origin',
        cache: 'no-store',
        headers: { 'X-QE-Workspace': 'HRMS' }
      });
      return { status: response.status, body: await response.json().catch(() => ({})) };
    });
    expect(session.status, 'The saved HRMS owner session must still be valid in the HRMS workspace before opening Notification Studio.').toBe(200);
    expect(session.body.workspace).toBe('HRMS');
    expect(Number(session.body.staffId)).toBeGreaterThan(0);
    await page.waitForLoadState('networkidle', { timeout: 15_000 });
    await expect(page).toHaveURL(url => url.pathname === '/qa');

    const notificationLink = page.locator('a[href="/notifications"]').first();
    await expect(notificationLink, 'The QE platform owner should be offered the Notification Studio route in HRMS navigation.').toBeVisible();
    const templatesWaiter = page.waitForResponse(response =>
      response.request().method() === 'GET' &&
      response.url().includes('/api/v1/hrms/notifications/templates'));
    const optionsWaiter = page.waitForResponse(response =>
      response.request().method() === 'GET' &&
      response.url().includes('/api/v1/hrms/notifications/options'));
    await notificationLink.click();
    await page.waitForLoadState('networkidle', { timeout: 15_000 });
    await expect(page).toHaveURL(url => url.pathname === '/notifications');
    const [templatesResponse, optionsResponse] = await Promise.all([templatesWaiter, optionsWaiter]);
    expect(templatesResponse.status(), 'HRMS templates must come from the authenticated API.').toBe(200);
    expect(optionsResponse.status(), 'HRMS audience choices must come from the authenticated API.').toBe(200);

    const templatesBody = await templatesResponse.json();
    const optionsBody = await optionsResponse.json();
    expect(Array.isArray(templatesBody.templates)).toBe(true);
    expect(Array.isArray(optionsBody.institutions)).toBe(true);
    expect(Array.isArray(optionsBody.courses)).toBe(true);

    await expect(page.getByRole('heading', { name: 'Create a modern notification' })).toBeVisible();
    await expect(page.getByRole('navigation', { name: 'Notification workspace' })).toBeVisible();
    const previewButton = page.getByRole('button', { name: 'Preview & validate audience', exact: true });
    const sendButton = page.getByRole('button', { name: 'Send notification', exact: true });
    await expect(previewButton).toBeEnabled();
    await expect(sendButton, 'Delivery must remain disabled until a valid server-side preview exists.').toBeDisabled();

    const before = await readNotificationCounts();
    await previewButton.click();
    await expect(page.getByText('Select at least one institution.', { exact: true })).toBeVisible();
    await expect(sendButton).toBeDisabled();

    expect(notificationWrites, 'Invalid audience input must stop locally; it must not create a preview token, template version or campaign.').toEqual([]);
    const after = await readNotificationCounts();
    expect(after, 'Invalid audience handling must not mutate notification templates, campaigns or recipients.').toEqual(before);
    expect(browserErrors, 'Notification Studio must not throw uncaught browser exceptions during validation.').toEqual([]);
  } finally {
    page.off('pageerror', onPageError);
    await context.close();
    await pool.end();
  }
});
