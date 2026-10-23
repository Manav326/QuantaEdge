import { test, expect } from '@playwright/test';
import { openQaDatabase, readPrivateJson } from '../support/fixtures';

const HRMS = process.env.QE_E2E_HRMS_URL || 'http://127.0.0.1:3002';

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
    const templatesWaiter = page.waitForResponse(response =>
      response.request().method() === 'GET' &&
      response.url().includes('/api/v1/hrms/notifications/templates'));
    const optionsWaiter = page.waitForResponse(response =>
      response.request().method() === 'GET' &&
      response.url().includes('/api/v1/hrms/notifications/options'));

    await page.goto(HRMS + '/notifications', { waitUntil: 'domcontentloaded' });
    const [templatesResponse, optionsResponse] = await Promise.all([templatesWaiter, optionsWaiter]);
    expect(templatesResponse.status(), 'HRMS templates must come from the authenticated API.').toBe(200);
    expect(optionsResponse.status(), 'HRMS audience choices must come from the authenticated API.').toBe(200);
    expect(templatesResponse.request().headers()['x-qe-workspace']).toBe('HRMS');
    expect(optionsResponse.request().headers()['x-qe-workspace']).toBe('HRMS');

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
    await expect(page.getByRole('alert')).toContainText('Select at least one institution.');
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
