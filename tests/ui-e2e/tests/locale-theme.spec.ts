import { test, expect } from '@playwright/test';
import { join } from 'node:path';
import { appBaseUrl } from '../support/manifest';
import { privateDir, readPrivateJson } from '../support/fixtures';

const WEB = appBaseUrl('web');

test('[UX-002] learner UI locale persists across navigation without changing learning-language preference or tenant brand', async ({ browser }) => {
  test.setTimeout(90_000);
  const fixture = await readPrivateJson<{
    credentials: { childA: { institutionCode: string } };
  }>('qa-fixture.json');
  const statePath = join(privateDir(), 'child-a.json');
  const context = await browser.newContext({
    storageState: statePath,
    viewport: { width: 1280, height: 900 }
  });
  const institutionCode = fixture.credentials.childA.institutionCode;
  const learningLanguageWrites: string[] = [];
  context.on('request', request => {
    if (request.method() === 'PATCH' && new URL(request.url()).pathname === '/api/v1/students/me/language') {
      learningLanguageWrites.push(request.postData() || '');
    }
  });
  const page = await context.newPage();

  try {
    await page.addInitScript((code: string) => {
      window.sessionStorage.setItem('qe.institution.brand-code.v1', code);
      window.localStorage.removeItem('qe-locale');
      window.localStorage.removeItem('qe-learning-language');
    }, institutionCode);

    await page.goto(WEB + '/student/courses', { waitUntil: 'domcontentloaded' });
    await expect(page.getByRole('heading', { name: 'All Courses' })).toBeVisible({ timeout: 25_000 });
    await expect.poll(() => page.locator('html').getAttribute('data-tenant-brand-code')).toBe(institutionCode);

    const uiLocale = page.locator('label.qe-language-switcher:not(.qe-learning-language-switcher) select').first();
    await expect(uiLocale, 'The learner shell must expose the shared UI language selector.').toBeVisible();

    const profileResponse = await page.evaluate(async () => {
      const response = await fetch('/api/v1/students/me/profile', {
        credentials: 'same-origin', cache: 'no-store',
        headers: { 'X-QE-Workspace': 'STUDENT' }
      });
      return { status: response.status, body: await response.json().catch(() => ({})) };
    });
    expect(profileResponse.status).toBe(200);
    const originalLearningLanguage = String(
      profileResponse.body.learningLanguage ?? profileResponse.body.learning_language ?? profileResponse.body.language ?? ''
    ).toLowerCase();

    const brandOrangeBefore = await page.evaluate(() => getComputedStyle(document.documentElement).getPropertyValue('--qe-brand-orange').trim());
    await expect(uiLocale).toHaveValue('hinglish');
    await uiLocale.selectOption('english');
    await expect(page.locator('html')).toHaveAttribute('lang', 'en');
    await expect(uiLocale).toHaveValue('english');
    expect(await page.evaluate(() => localStorage.getItem('qe-locale'))).toBe('english');
    expect(await page.evaluate(() => document.cookie.split(';').some(cookie => cookie.trim() === 'qe-locale=english'))).toBe(true);

    await page.goto(WEB + '/student/marketplace', { waitUntil: 'domcontentloaded' });
    await expect(page.getByRole('heading', { name: /Marketplace/i })).toBeVisible({ timeout: 25_000 });
    const localeOnSecondRoute = page.locator('label.qe-language-switcher:not(.qe-learning-language-switcher) select').first();
    await expect(localeOnSecondRoute).toHaveValue('english');
    await expect(page.locator('html')).toHaveAttribute('lang', 'en');
    await localeOnSecondRoute.selectOption('hinglish');
    await expect(page.locator('html')).toHaveAttribute('lang', 'hi');
    await expect(localeOnSecondRoute).toHaveValue('hinglish');

    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page.locator('html')).toHaveAttribute('lang', 'hi');
    await expect(page.locator('label.qe-language-switcher:not(.qe-learning-language-switcher) select').first()).toHaveValue('hinglish');

    const prefs = await page.evaluate(() => ({
      uiLocale: localStorage.getItem('qe-locale'),
      learningLanguage: localStorage.getItem('qe-learning-language'),
      localeCookie: document.cookie.split(';').find(cookie => cookie.trim().startsWith('qe-locale='))?.trim() || '',
      tenantBrandCode: document.documentElement.getAttribute('data-tenant-brand-code'),
      brandOrange: getComputedStyle(document.documentElement).getPropertyValue('--qe-brand-orange').trim()
    }));
    expect(prefs.uiLocale).toBe('hinglish');
    expect(prefs.localeCookie).toBe('qe-locale=hinglish');
    expect(prefs.tenantBrandCode).toBe(institutionCode);
    expect(prefs.brandOrange).toBe(brandOrangeBefore);
    expect(learningLanguageWrites, 'Changing the UI locale must not write the independent account learning-language field.').toEqual([]);

    const profileAfter = await page.evaluate(async () => {
      const response = await fetch('/api/v1/students/me/profile', {
        credentials: 'same-origin', cache: 'no-store',
        headers: { 'X-QE-Workspace': 'STUDENT' }
      });
      return { status: response.status, body: await response.json().catch(() => ({})) };
    });
    expect(profileAfter.status).toBe(200);
    if (originalLearningLanguage) {
      const after = String(profileAfter.body.learningLanguage ?? profileAfter.body.learning_language ?? profileAfter.body.language ?? '').toLowerCase();
      expect(after, 'The UI language setting must not overwrite learning-language profile state.').toBe(originalLearningLanguage);
    }
  } finally {
    await context.close();
  }
});
