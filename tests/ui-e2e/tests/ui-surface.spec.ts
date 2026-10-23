import { test, expect, type Browser, type Page } from '@playwright/test';
import { join } from 'node:path';
import { appBaseUrl } from '../support/manifest';
import { privateDir, readPrivateJson } from '../support/fixtures';

const WEB = appBaseUrl('web');
const ADMIN = appBaseUrl('admin');
const HRMS = appBaseUrl('people');
const VIEWPORTS = [
  { width: 320, height: 800 },
  { width: 375, height: 812 },
  { width: 430, height: 900 },
  { width: 768, height: 1024 },
  { width: 1280, height: 900 },
  { width: 1440, height: 900 }
];

type Surface = { app: 'web' | 'admin' | 'people'; path: string; statePath: string };

async function waitForOptionalNetworkIdle(page: Page): Promise<void> {
  try {
    // Network-idle is only a convenience here, not a route-readiness contract:
    // authenticated shells may keep polling after the load event has fired.
    await page.waitForLoadState('networkidle', { timeout: 3_000 });
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    if (!/timeout/i.test(message)) throw error;
  }
}

async function checkSurfaceAtViewports(browser: Browser, surface: Surface) {
  for (const viewport of VIEWPORTS) {
    const context = await browser.newContext({
      storageState: surface.statePath,
      viewport
    });
    const page = await context.newPage();
    const uncaughtErrors: string[] = [];
    page.on('pageerror', error => uncaughtErrors.push(error.message));
    try {
      const base = surface.app === 'web' ? WEB : surface.app === 'admin' ? ADMIN : HRMS;
      await page.goto(base + surface.path, { waitUntil: 'domcontentloaded' });
      await expect(page.locator('main').first(), surface.app + surface.path + ' should render a main landmark at ' + viewport.width + 'px').toBeVisible();
      // Authenticated shells may continue polling after the route has rendered. Prefer a
      // brief best-effort idle window, then assert the route and visible layout directly.
      await waitForOptionalNetworkIdle(page);
      expect(new URL(page.url()).pathname, 'Authenticated route should remain reachable after session checks at ' + viewport.width + 'px.').toBe(surface.path);

      let dimensions: { viewport: number; document: number; body: number } | null = null;
      for (let attempt = 0; attempt < 3; attempt += 1) {
        try {
          dimensions = await page.evaluate(() => ({
            viewport: window.innerWidth,
            document: document.documentElement.scrollWidth,
            body: document.body.scrollWidth
          }));
          break;
        } catch (error) {
          const message = error instanceof Error ? error.message : String(error);
          if (attempt === 2 || !/execution context was destroyed|context was destroyed/i.test(message)) throw error;
          await page.waitForLoadState('domcontentloaded', { timeout: 10_000 }).catch(() => undefined);
          await waitForOptionalNetworkIdle(page);
          expect(new URL(page.url()).pathname, 'Session/workspace routing must settle back on the requested route.').toBe(surface.path);
          await expect(page.locator('main').first()).toBeVisible();
        }
      }
      if (!dimensions) throw new Error(surface.app + surface.path + ' dimensions could not be measured after route settling.');
      expect(
        Math.max(dimensions.document, dimensions.body),
        surface.app + surface.path + ' must not cause page-level horizontal overflow at ' + viewport.width + 'px.'
      ).toBeLessThanOrEqual(dimensions.viewport + 2);
      expect(uncaughtErrors, surface.app + surface.path + ' must not raise uncaught browser errors at ' + viewport.width + 'px.').toEqual([]);
    } finally {
      await context.close();
    }
  }
}

test('[UX-001][CAT-001] core learner, parent, Education Admin and HRMS surfaces remain reachable without page overflow at responsive widths', async ({ browser }) => {
  test.setTimeout(8 * 60 * 1000);
  const ownerState = await readPrivateJson<{ statePath: string }>('hrms-owner-state-path.json');
  const adminState = await readPrivateJson<{ statePath: string }>('education-admin-state-path.json');
  const surfaces: Surface[] = [
    { app: 'web', path: '/student/courses', statePath: join(privateDir(), 'child-a.json') },
    { app: 'web', path: '/student/marketplace', statePath: join(privateDir(), 'child-a.json') },
    { app: 'web', path: '/parent', statePath: join(privateDir(), 'parent-a.json') },
    { app: 'admin', path: '/content', statePath: adminState.statePath },
    { app: 'people', path: '/qa', statePath: ownerState.statePath }
  ];
  for (const surface of surfaces) await checkSurfaceAtViewports(browser, surface);
});

test('[UI-005] Admin legacy content route redirects to Content Studio without a write side effect', async ({ browser }) => {
  const adminState = await readPrivateJson<{ statePath: string }>('education-admin-state-path.json');
  const context = await browser.newContext({ storageState: adminState.statePath });
  const page = await context.newPage();
  const mutatingApiRequests: string[] = [];
  page.on('request', request => {
    const url = new URL(request.url());
    if (url.pathname.startsWith('/api/') && !['GET', 'HEAD', 'OPTIONS'].includes(request.method())) {
      mutatingApiRequests.push(request.method() + ' ' + url.pathname);
    }
  });
  try {
    await page.goto(ADMIN + '/legacy-content', { waitUntil: 'domcontentloaded' });
    await expect(page).toHaveURL(/\/content\/?(?:\?.*)?$/);
    await expect(page.locator('main').first()).toBeVisible();
    expect(mutatingApiRequests, 'The route alias itself must not create or mutate content.').toEqual([]);
  } finally {
    await context.close();
  }
});
