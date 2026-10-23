import { test, expect, type BrowserContext, type Page } from '@playwright/test';
import { appBaseUrl, expandRoute, loadUiManifest, routeApp } from '../support/manifest';

test('[UX-001][UX-003][UI-005] smoke every current source-discovered web route', async ({ browser }) => {
  const manifest = await loadUiManifest();
  const appOrigins = new Map<string, string>();
  for (const app of ['web', 'admin', 'people'] as const) {
    appOrigins.set(app, appBaseUrl(app));
  }

  const contexts = new Map<string, BrowserContext>();
  const errors: string[] = [];
  let visited = 0;

  test.setTimeout(12 * 60 * 1000);
  try {
    for (const route of manifest.routes) {
      const app = routeApp(route.file);
      let context = contexts.get(app);
      if (!context) {
        context = await browser.newContext({ viewport: { width: 1440, height: 900 } });
        contexts.set(app, context);
      }
      // Give every route its own page. Auth redirects and client-side middleware can
      // trigger a follow-up navigation after DOMContentLoaded; reusing the same Page made
      // the next smoke URL abort that redirect and produced false navigation failures.
      const page = await context.newPage();
      const destination = appOrigins.get(app)! + expandRoute(route.route);
      const pageErrors: string[] = [];
      const onPageError = (error: Error) => pageErrors.push(error.message);
      page.on('pageerror', onPageError);
      let status = 0;
      try {
        const response = await page.goto(destination, { waitUntil: 'domcontentloaded', timeout: 30_000 });
        status = response?.status() ?? 0;
        await page.locator('body').waitFor({ state: 'attached', timeout: 8_000 });
        if (!response) errors.push(app + ' ' + route.route + ': navigation returned no main-document response');
        else if (status >= 500) errors.push(app + ' ' + route.route + ': HTTP ' + status);
      } catch (error) {
        const navigationError = error instanceof Error ? error.message : String(error);
        errors.push(app + ' ' + route.route + ': navigation failed: ' + navigationError.slice(0, 250));
      } finally {
        page.off('pageerror', onPageError);
        await page.close().catch(() => undefined);
      }
      if (pageErrors.length) {
        errors.push(app + ' ' + route.route + ': uncaught browser error: ' + pageErrors.join(' | ').slice(0, 350));
      }
      visited += 1;
    }

    expect(visited, 'Every current source-derived primary web route must be visited.').toBe(manifest.summary.sourceWebPages);
    expect(new Set(manifest.routes.map(route => route.app)).size).toBe(3);
    expect(errors, 'Route smoke must report all failing pages, not silently skip them.').toEqual([]);
  } finally {
    await Promise.all([...contexts.values()].map(context => context.close()));
  }
});
