import { spawnSync } from 'node:child_process';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const require = createRequire(import.meta.url);
// Resolve the package entry point, then locate its CLI file by path. Playwright
// intentionally does not export the `playwright/cli` subpath in package exports.
const cliPath = join(dirname(require.resolve('playwright')), 'cli.js');
const validatorPath = fileURLToPath(new URL('./validate-coverage.mjs', import.meta.url));
const env = { ...process.env, QE_E2E_PRODUCTION_GATE: 'true' };

const tests = spawnSync(process.execPath, [cliPath, 'test'], { stdio: 'inherit', env });
if (tests.error) {
  process.stderr.write('Unable to start Playwright production-gate tests: ' + tests.error.message + '\n');
}

const coverage = spawnSync(process.execPath, [validatorPath, '--strict'], { stdio: 'inherit', env });
if (coverage.error) {
  process.stderr.write('Unable to start exact-commit coverage validator: ' + coverage.error.message + '\n');
}

const testPassed = !tests.error && tests.status === 0;
const coveragePassed = !coverage.error && coverage.status === 0;
if (!testPassed || !coveragePassed) {
  process.stderr.write('PRODUCTION GATE BLOCKED: browser tests and full current-commit scenario coverage must both pass.\n');
  process.exit(1);
}
process.stdout.write('PRODUCTION GATE PASS: browser tests and exact-commit scenario coverage passed.\n');
