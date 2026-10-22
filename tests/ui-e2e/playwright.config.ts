import { defineConfig } from '@playwright/test';

const isCi = Boolean(process.env.CI);

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
