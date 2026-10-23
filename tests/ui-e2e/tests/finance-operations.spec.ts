import { test, expect } from '@playwright/test';
import { appBaseUrl } from '../support/manifest';
import { openQaDatabase, readPrivateJson } from '../support/fixtures';

const HRMS = appBaseUrl('people');

type SavedState = { statePath: string };

async function countFinanceTables(): Promise<Array<{ table: string; rows: number }>> {
  const pool = await openQaDatabase();
  try {
    const result = await pool.query(`
      select table_name
      from information_schema.tables
      where table_schema='public' and table_type='BASE TABLE'
        and (
          table_name ilike '%course%payment%'
          or table_name ilike '%provider%event%'
          or table_name ilike '%finance%'
          or table_name ilike '%invoice%'
          or table_name ilike '%refund%'
        )
      order by table_name
    `);
    const values: Array<{ table: string; rows: number }> = [];
    for (const row of result.rows as Array<{ table_name: string }>) {
      const table = String(row.table_name).replace(/"/g, '""');
      const count = await pool.query('select count(*)::int as count from "public"."' + table + '"');
      values.push({ table: String(row.table_name), rows: Number(count.rows[0].count) });
    }
    return values;
  } finally {
    await pool.end();
  }
}

test('[HRMS-FIN-001][PAY-008][PAY-009] finance workspace exposes read-only payment reconciliation and signed-event inbox to platform owner only', async ({ browser }) => {
  test.setTimeout(90_000);
  const ownerState = await readPrivateJson<SavedState>('hrms-owner-state-path.json');
  const adminState = await readPrivateJson<SavedState>('education-admin-state-path.json');
  const ownerContext = await browser.newContext({ storageState: ownerState.statePath, viewport: { width: 1440, height: 900 } });
  const ownerPage = await ownerContext.newPage();
  const financeMutations: string[] = [];
  const financeResponses: Array<{ method: string; path: string; status: number }> = [];
  ownerPage.on('request', request => {
    const url = new URL(request.url());
    if (!url.pathname.startsWith('/api/v1/hrms/platform/finance')) return;
    if (!['GET', 'HEAD', 'OPTIONS'].includes(request.method())) {
      financeMutations.push(request.method() + ' ' + url.pathname);
    }
  });
  ownerPage.on('response', response => {
    const url = new URL(response.url());
    if (url.pathname.startsWith('/api/v1/hrms/platform/finance')) {
      financeResponses.push({ method: response.request().method(), path: url.pathname, status: response.status() });
    }
  });

  let before: Array<{ table: string; rows: number }> = [];
  try {
    before = await countFinanceTables();
    const catalogWaiter = ownerPage.waitForResponse(response =>
      response.request().method() === 'GET' &&
      new URL(response.url()).pathname === '/api/v1/hrms/platform/finance/catalog'
    );
    const overviewWaiter = ownerPage.waitForResponse(response =>
      response.request().method() === 'GET' &&
      new URL(response.url()).pathname === '/api/v1/hrms/platform/finance/overview'
    );
    await ownerPage.goto(HRMS + '/finance', { waitUntil: 'domcontentloaded' });
    await expect(ownerPage.getByRole('heading', { name: 'Financial control centre' })).toBeVisible({ timeout: 25_000 });
    await expect(ownerPage.getByRole('navigation', { name: 'Finance workspace' })).toBeVisible();
    await expect(ownerPage.getByText('QE_PLATFORM_OWNER', { exact: false }).first()).toBeVisible();

    const [catalog, overview] = await Promise.all([catalogWaiter, overviewWaiter]);
    expect(catalog.status(), 'Finance catalog is loaded from the live authenticated API.').toBe(200);
    expect(overview.status(), 'Finance overview is loaded from the live authenticated API.').toBe(200);

    const paymentsTab = ownerPage.getByRole('navigation', { name: 'Finance workspace' })
      .getByRole('button', { name: /Payments & reconciliation/ });
    const ordersWaiter = ownerPage.waitForResponse(response =>
      response.request().method() === 'GET' &&
      new URL(response.url()).pathname === '/api/v1/hrms/platform/finance/course-orders'
    );
    const eventsWaiter = ownerPage.waitForResponse(response =>
      response.request().method() === 'GET' &&
      new URL(response.url()).pathname === '/api/v1/hrms/platform/finance/course-orders/provider-events'
    );
    await paymentsTab.click();
    await expect(ownerPage.getByRole('heading', { name: 'Course payments & reconciliation' })).toBeVisible();
    await expect(ownerPage.getByRole('heading', { name: 'Razorpay provider-event inbox' })).toBeVisible();

    const [ordersResponse, eventsResponse] = await Promise.all([ordersWaiter, eventsWaiter]);
    expect(ordersResponse.status(), 'Course order history must be readable in the platform-owner HRMS workspace.').toBe(200);
    expect(eventsResponse.status(), 'The signed provider-event inbox must be readable in the platform-owner HRMS workspace.').toBe(200);
    const ordersBody = await ordersResponse.json();
    const eventsBody = await eventsResponse.json();
    expect(Array.isArray(ordersBody.items)).toBe(true);
    expect(Number.isInteger(Number(ordersBody.total))).toBe(true);
    expect(Number(ordersBody.limit)).toBeGreaterThan(0);
    expect(Number(ordersBody.offset)).toBeGreaterThanOrEqual(0);
    expect(Array.isArray(eventsBody.items)).toBe(true);
    expect(Number.isInteger(Number(eventsBody.total))).toBe(true);
    if (eventsBody.items.length) {
      for (const event of eventsBody.items) {
        expect(String(event.provider_event_id || '')).not.toBe('');
        expect(String(event.payload_sha256 || '')).toMatch(/^[a-f0-9]{64}$/i);
        expect(String(event.processing_status || '')).not.toBe('');
        // Stored diagnostics must not reveal raw signed request bodies or webhook secrets.
        expect(JSON.stringify(event)).not.toMatch(/webhook_secret|signature_secret|raw_body|raw_payload/i);
      }
    }

    const searchInput = ownerPage.getByPlaceholder('Order ID, school, course, learner, Razorpay ID or failure code');
    await searchInput.fill('QE-E2E-NO-SUCH-ORDER');
    const searchWaiter = ownerPage.waitForResponse(response =>
      response.request().method() === 'GET' &&
      new URL(response.url()).pathname === '/api/v1/hrms/platform/finance/course-orders'
    );
    await ownerPage.getByRole('button', { name: 'Search', exact: true }).first().click();
    const searchResponse = await searchWaiter;
    expect(searchResponse.status()).toBe(200);
    const searchedOrders = await searchResponse.json();
    expect(Array.isArray(searchedOrders.items)).toBe(true);
    expect(searchedOrders.items).toHaveLength(0);
    await expect(ownerPage.getByText('No course payment orders found')).toBeVisible();

    expect(financeMutations, 'Visiting and searching finance history must not create or mutate payment, invoice, refund, or webhook records.').toEqual([]);
    expect(financeResponses.filter(r => r.status >= 500), 'Finance APIs must not emit server errors during owner read-only inspection.').toEqual([]);

    const after = await countFinanceTables();
    expect(after, 'Finance history/counts must remain unchanged during read-only inspection.').toEqual(before);
  } finally {
    await ownerContext.close();
  }

  const deniedContext = await browser.newContext({ storageState: adminState.statePath });
  try {
    const base = new URL(HRMS);
    const deniedOrders = await deniedContext.request.get(new URL('/api/v1/hrms/platform/finance/course-orders?limit=50&offset=0', base).toString(), {
      headers: { 'X-QE-Workspace': 'HRMS', Accept: 'application/json' },
      failOnStatusCode: false
    });
    expect([401, 403], 'An Education Admin identity must not read HRMS course payment orders without the platform-owner permission.').toContain(deniedOrders.status());
    const deniedEvents = await deniedContext.request.get(new URL('/api/v1/hrms/platform/finance/course-orders/provider-events?limit=50&offset=0', base).toString(), {
      headers: { 'X-QE-Workspace': 'HRMS', Accept: 'application/json' },
      failOnStatusCode: false
    });
    expect([401, 403], 'An Education Admin identity must not inspect the HRMS signed webhook inbox.').toContain(deniedEvents.status());
  } finally {
    await deniedContext.close();
  }
});
