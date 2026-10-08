const { test, expect } = require('@playwright/test');

const MOBILE = '7070107483';

async function loginGuardian(page) {
  await page.goto('http://localhost:3000/login', { waitUntil: 'domcontentloaded' });
  await expect(page.getByRole('heading', { name: 'अपनी पढ़ाई वहीं से शुरू करें।' })).toBeVisible();

  await page.getByLabel('Mobile number').fill(MOBILE);
  await page.getByLabel('Parent name').fill('UI CI Parent');
  await page.getByRole('button', { name: 'OTP भेजें' }).click();

  await expect(page.getByText('Local QA OTP')).toBeVisible();
  await page.getByLabel('OTP').fill('000000');
  await page.getByRole('button', { name: 'OTP सत्यापित करें' }).click();

  const setup = page.getByRole('heading', { name: 'बच्चे का learning profile बनाएं।' });
  if (await setup.isVisible().catch(() => false)) {
    await page.getByLabel('बच्चे का नाम').fill('UI CI Student');
    await page.getByLabel('कक्षा').selectOption('7');
    await page.getByLabel('Student PIN').fill('1234');

    const consent = page.getByRole('checkbox');
    await expect(consent).toBeVisible();
    await expect(consent).toBeEnabled();
    await consent.check();

    const create = page.getByRole('button', { name: 'Student profile बनाएं' });
    await expect(create).toBeEnabled();
    await create.click();
    await page.waitForURL('**/student', { waitUntil: 'domcontentloaded' });
  } else {
    await expect(page).toHaveURL(/\/student/);
  }

  await expect(page.getByText(/नमस्ते,/)).toBeVisible();
}

test.describe('QuantaEdge rendered UI smoke', () => {
  test('guardian onboarding and student dashboard work on mobile', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await loginGuardian(page);

    const overflow = await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1);
    expect(overflow).toBe(true);

    await page.goto('http://localhost:3000/student/progress', { waitUntil: 'networkidle' });
    await expect(page.getByRole('heading', { name: 'जो सीखा, वह सच में दिख रहा है।' })).toBeVisible();
  });

  test('student can render a real published lesson', async ({ page }) => {
    await loginGuardian(page);

    const lesson = await page.evaluate(async () => {
      const response = await fetch('/api/v1/learning/lessons?classCode=7&subjectCode=maths');
      if (!response.ok) throw new Error('Unable to load lessons');
      const lessons = await response.json();
      if (!lessons.length) throw new Error('No published lessons');
      return lessons[0];
    });

    await page.goto('http://localhost:3000/student/learn?lessonId=' + lesson.id, { waitUntil: 'networkidle' });
    await expect(page.getByText('Learning path')).toBeVisible();
    await expect(page.getByRole('heading', { name: lesson.title })).toBeVisible();
    await expect(page.locator('.concept-card').first()).toBeVisible();

    const overflow = await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1);
    expect(overflow).toBe(true);
  });

  test('admin can authenticate and render operations dashboard', async ({ page }) => {
    await page.goto('http://localhost:3001/login', { waitUntil: 'domcontentloaded' });

    await page.getByLabel('Mobile').fill(MOBILE);
    await page.getByRole('button', { name: 'OTP भेजें' }).click();
    await expect(page.getByText('Local QA OTP')).toBeVisible();

    await page.getByLabel('OTP').fill('000000');
    await page.getByRole('button', { name: 'Verify' }).click();

    await page.waitForURL('**/', { waitUntil: 'networkidle' });
    await expect(page.getByRole('heading', { name: 'Content operations' })).toBeVisible();
    await expect(page.getByText('Authorized operator')).toBeVisible();
  });
});
