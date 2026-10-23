import { defineConfig } from '@playwright/test';

const isCi = Boolean(process.env.CI);
const productionGate = process.env.QE_E2E_PRODUCTION_GATE === 'true';

if (productionGate) {
  const testedSha = process.env.GITHUB_SHA || process.env.QE_E2E_SOURCE_SHA || '';
  if (process.env.GITHUB_ACTIONS !== 'true' || !/^[0-9a-f]{40}$/i.test(testedSha)) {
    throw new Error('Production-gate E2E requires GitHub Actions and an exact 40-character tested commit SHA.');
  }
  if (process.env.QE_E2E_ENVIRONMENT !== 'disposable-qa' || !process.env.QE_E2E_DATABASE_URL) {
    throw new Error('Production-gate E2E requires an explicitly disposable QA environment and direct database assertions.');
  }
  if (!process.env.QE_E2E_SECRET_DIR || process.env.QE_E2E_EXTERNAL_PROVIDER_MODE === 'live') {
    throw new Error('Production-gate E2E requires private fixture storage and forbids live external-provider mode.');
  }
}

export default defineConfig({
  testDir: './tests',
  testMatch: /.*\.spec\.ts/,
  fullyParallel: false,
  forbidOnly: isCi,
  retries: 0,
  workers: 1,
  timeout: 90_000,
  expect: { timeout: 12_000 },
  outputDir: process.env.QE_E2E_ARTIFACTS || 'test-results',
  reporter: [
    ['line'],
    ['html', { outputFolder: process.env.QE_E2E_HTML_REPORT || 'playwright-report', open: 'never' }],
    ['json', { outputFile: process.env.QE_E2E_JSON_REPORT || 'test-results/results.json' }]
  ],
  use: {
    browserName: 'chromium',
    headless: true,
    viewport: { width: 1440, height: 900 },
    actionTimeout: 12_000,
    navigationTimeout: 30_000,
    screenshot: 'only-on-failure',
    trace: 'off',
    video: 'off'
  },
  projects: [
    {
      name: 'setup',
      testMatch: /.*\/setup\.spec\.ts/,
      teardown: 'cleanup'
    },
    {
      name: 'browser-e2e',
      dependencies: ['setup'],
      testIgnore: [/.*\/setup\.spec\.ts/, /.*\/cleanup\.spec\.ts/]
    },
    {
      name: 'cleanup',
      testMatch: /.*\/cleanup\.spec\.ts/
    }
  ]
});
