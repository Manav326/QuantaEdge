import { spawnSync } from 'node:child_process';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const cliPath = require.resolve('playwright/cli');
const result = spawnSync(process.execPath, [cliPath, 'test'], {
  stdio: 'inherit',
  env: { ...process.env, QE_E2E_PRODUCTION_GATE: 'true' }
});
if (result.error) {
  process.stderr.write('Unable to start Playwright production-gate tests: ' + result.error.message + '\n');
  process.exit(1);
}
process.exit(result.status ?? 1);
