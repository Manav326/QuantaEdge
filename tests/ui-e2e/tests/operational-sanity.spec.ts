import { test, expect } from '@playwright/test';
import { openQaDatabase } from '../support/fixtures';

test('[OPS-001][OPS-002] runtime health contract and fresh PostgreSQL migration invariants are safe', async ({ request }) => {
  const healthResponse = await request.get('http://127.0.0.1:8080/actuator/health', { failOnStatusCode: false });
  expect(healthResponse.status(), 'The API health endpoint must be reachable before E2E tests are considered healthy.').toBe(200);
  const actuatorHealth = await healthResponse.json();
  expect(actuatorHealth.status).toBe('UP');

  const statusResponse = await request.get('http://127.0.0.1:8080/api/v1/system/status', { failOnStatusCode: false });
  expect([200, 503]).toContain(statusResponse.status());
  const status = await statusResponse.json();
  expect(status.service).toBe('quantaedge-api');
  expect(['ok', 'degraded']).toContain(status.status);
  expect(typeof status.aiTutorEnabled).toBe('boolean');
  expect(typeof status.aiTutorConfigured).toBe('boolean');
  expect(typeof status.aiTutorAvailable).toBe('boolean');
  expect(typeof status.adminConfigured).toBe('boolean');
  expect(typeof status.otpConfigured).toBe('boolean');
  expect(statusResponse.status() === 200).toBe(status.status === 'ok');
  if (status.status === 'ok') {
    expect(status.adminConfigured).toBe(true);
    expect(status.otpConfigured).toBe(true);
  }
  const serializedStatus = JSON.stringify(status);
  expect(serializedStatus).not.toMatch(/password|secret|access.?token|refresh.?token|api.?key/i);
  const configuredMockToken = process.env.QE_E2E_OTP_MOCK_TOKEN || '';
  if (configuredMockToken) expect(serializedStatus).not.toContain(configuredMockToken);

  const pool = await openQaDatabase();
  try {
    const migrationSummary = await pool.query(`
      select count(*)::int as migrations,
             count(*) filter(where success=false)::int as failed_migrations,
             bool_or(version='133' and success=true) as qa_lifecycle_migration_applied,
             max(installed_rank)::int as latest_installed_rank
      from flyway_schema_history
    `);
    expect(migrationSummary.rows[0].migrations).toBeGreaterThan(0);
    expect(migrationSummary.rows[0].failed_migrations).toBe(0);
    expect(migrationSummary.rows[0].qa_lifecycle_migration_applied).toBe(true);
    expect(Number(migrationSummary.rows[0].latest_installed_rank)).toBeGreaterThan(0);

    const relationCheck = await pool.query(`
      select
        to_regclass('public.institution_tenant') is not null as institutions,
        to_regclass('public.institution_membership') is not null as student_memberships,
        to_regclass('public.institution_course_assignment') is not null as course_assignments,
        to_regclass('public.qa_test_run') is not null as qa_runs,
        to_regclass('public.qa_test_run_resource') is not null as qa_resource_manifest,
        to_regclass('public.institution_course_release') is not null as course_releases
    `);
    expect(relationCheck.rows[0]).toEqual({
      institutions: true,
      student_memberships: true,
      course_assignments: true,
      qa_runs: true,
      qa_resource_manifest: true,
      course_releases: true
    });

    const constraints = await pool.query(`
      select
        (select count(*)::int from pg_constraint where conrelid='institution_tenant'::regclass and contype in ('p','u')) as institution_keys,
        (select count(*)::int from pg_constraint where conrelid='institution_membership'::regclass and contype='f') as membership_foreign_keys,
        (select count(*)::int from pg_constraint where conrelid='qa_test_run_resource'::regclass and contype='f') as manifest_foreign_keys
    `);
    expect(Number(constraints.rows[0].institution_keys)).toBeGreaterThan(0);
    expect(Number(constraints.rows[0].membership_foreign_keys)).toBeGreaterThan(0);
    expect(Number(constraints.rows[0].manifest_foreign_keys)).toBeGreaterThan(0);
  } finally {
    await pool.end();
  }
});
