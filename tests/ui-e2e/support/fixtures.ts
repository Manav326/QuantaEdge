import { mkdir, readFile, writeFile, chmod } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { randomUUID } from 'node:crypto';
import { Pool } from 'pg';

export type QaCredentials = {
  parentA: { institutionCode: string; mobile: string; username: string; password: string };
  parentB: { institutionCode: string; mobile: string; username: string; password: string };
  childA: { institutionCode: string; parentMobile: string; username: string; password: string };
  childB: { institutionCode: string; parentMobile: string; username: string; password: string };
  independentLearner: { institutionCode: string; mobile: string; username: string; password: string };
  hrmsCourseReviewer?: { role: string; scope: string; notLoginEnabled: boolean; institutionCode: string };
};

export type QaFixture = {
  sourceSha: string;
  runId: string;
  packKey: string;
  idempotencyKey: string;
  credentials: QaCredentials;
};

export function privateDir(): string {
  // Playwright setup, browser tests and teardown run in separate worker processes.
  // Never key shared fixtures by PID; require an explicitly stable directory locally.
  if (process.env.QE_E2E_SECRET_DIR) return process.env.QE_E2E_SECRET_DIR;
  const runId = process.env.GITHUB_RUN_ID;
  if (!runId) {
    throw new Error('QE_E2E_SECRET_DIR is required outside GitHub Actions so setup and cleanup share one private fixture directory.');
  }
  const attempt = process.env.GITHUB_RUN_ATTEMPT || '1';
  return join(tmpdir(), 'quantaedge-ui-e2e-' + runId + '-' + attempt);
}

export async function preparePrivateDir(): Promise<string> {
  const path = privateDir();
  await mkdir(path, { recursive: true, mode: 0o700 });
  await chmod(path, 0o700);
  return path;
}

export async function writePrivateJson(filename: string, value: unknown): Promise<void> {
  const path = join(await preparePrivateDir(), filename);
  await writeFile(path, JSON.stringify(value, null, 2) + '\n', { encoding: 'utf8', mode: 0o600 });
  await chmod(path, 0o600);
}

export async function readPrivateJson<T>(filename: string): Promise<T> {
  return JSON.parse(await readFile(join(privateDir(), filename), 'utf8')) as T;
}

export function newIdempotencyKey(prefix = 'qe-e2e'): string {
  return prefix + '-' + randomUUID();
}

export async function openQaDatabase(): Promise<Pool> {
  if (process.env.QE_E2E_ENVIRONMENT !== 'disposable-qa') {
    throw new Error('Refusing UI E2E: QE_E2E_ENVIRONMENT must be exactly disposable-qa.');
  }
  const connectionString = process.env.QE_E2E_DATABASE_URL;
  if (!connectionString) throw new Error('QE_E2E_DATABASE_URL is required for connected-data assertions.');
  const url = new URL(connectionString);
  const host = url.hostname.toLowerCase();
  if (!['localhost', '127.0.0.1', '::1'].includes(host)) {
    throw new Error('Refusing direct database access to a non-local host. No connection credentials were logged.');
  }

  const target = process.env.QE_E2E_TARGET_DATABASE || 'quantaedge_qa';
  if (!/^[a-z0-9][a-z0-9_-]*(?:_qa|_test|_ci|_integration)(?:_[a-z0-9]+)?$/i.test(target)) {
    throw new Error('Refusing a target database name without an approved QA/test/CI suffix.');
  }
  if (decodeURIComponent(url.pathname.replace(/^\//, '')) !== target) {
    throw new Error('The direct database URL does not match QE_E2E_TARGET_DATABASE.');
  }

  const pool = new Pool({ connectionString, max: 2, connectionTimeoutMillis: 10_000 });
  try {
    const identity = await pool.query('select current_database() as database_name, current_user as database_user');
    if (identity.rows[0]?.database_name !== target) {
      throw new Error('Connected database identity did not match the explicitly approved QA target.');
    }
    return pool;
  } catch (error) {
    await pool.end();
    throw error;
  }
}

export function baseUrl(key: 'QE_E2E_WEB_URL' | 'QE_E2E_ADMIN_URL' | 'QE_E2E_HRMS_URL', fallback: string): string {
  return (process.env[key] || fallback).replace(/\/$/, '');
}
