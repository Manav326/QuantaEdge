import { test, expect, type BrowserContext } from '@playwright/test';
import { randomUUID } from 'node:crypto';
import { appBaseUrl } from '../support/manifest';
import { openQaDatabase, readPrivateJson } from '../support/fixtures';

const HRMS = appBaseUrl('people');
const OWNED_TABLES = [
  'curriculum_program', 'curriculum_subject', 'curriculum_program_subject',
  'curriculum_chapter', 'institution_marketplace_offer', 'lesson', 'question',
  'question_option', 'institution_admin_account', 'staff_account', 'institution_tenant',
  'institution_branding', 'user_account', 'student', 'guardian_student',
  'student_login_credential', 'institution_user_membership', 'institution_membership',
  'institution_course_release', 'institution_course_assignment', 'institution_course_entitlement',
  'student_program_enrollment', 'student_track_enrollment', 'student_lesson_progress',
  'student_question_attempt'
] as const;

type ApiResult = { status: number; body: any };

async function requestJson(
  context: BrowserContext,
  method: 'GET' | 'POST',
  path: string,
  body?: unknown
): Promise<ApiResult> {
  const url = new URL(path, HRMS).toString();
  const options: Record<string, unknown> = {
    headers: { 'X-QE-Workspace': 'HRMS', Accept: 'application/json' },
    failOnStatusCode: false
  };
  if (body !== undefined) options.data = body;
  const response = method === 'GET'
    ? await context.request.get(url, options as any)
    : await context.request.post(url, options as any);
  const raw = await response.text();
  let parsed: any = {};
  try { parsed = raw ? JSON.parse(raw) : {}; } catch { parsed = { unreadable: true }; }
  return { status: response.status(), body: parsed };
}

async function markedCounts(runId: string): Promise<Record<string, number>> {
  const pool = await openQaDatabase();
  try {
    const union = OWNED_TABLES.map(table =>
      `select '${table}' as table_name, count(*)::int as row_count from ${table} where qa_test_run_id=$1`
    ).join('\nunion all\n');
    const result = await pool.query(union, [runId]);
    return Object.fromEntries(result.rows.map(row => [String(row.table_name), Number(row.row_count)]));
  } finally {
    await pool.end();
  }
}

async function manifestRows(runId: string) {
  const pool = await openQaDatabase();
  try {
    const result = await pool.query(
      'select resource_key,resource_kind,resource_id,state from qa_test_run_resource where run_id=$1 order by resource_key',
      [runId]
    );
    return result.rows;
  } finally {
    await pool.end();
  }
}

async function protectedCourseOwnership() {
  const pool = await openQaDatabase();
  try {
    const result = await pool.query(`
      select count(distinct p.code)::int as course_count,
             string_agg(distinct p.code, ',' order by p.code) as course_codes
      from curriculum_program p
      join institution_course_release r on r.program_id=p.id and r.source_institution_id=(
        select id from institution_tenant where tenant_code='QUANTA-EDGE' and status='ACTIVE'
      )
      join institution_course_assignment a on a.course_release_id=r.id
        and a.institution_id=r.source_institution_id and a.status='ASSIGNED'
      join institution_course_entitlement e on e.program_id=p.id
        and e.institution_id=r.source_institution_id and e.status='ACTIVE'
      where p.code in ('CLASS_6','CLASS_7','CLASS_8') and p.status='ACTIVE'
    `);
    return { count: Number(result.rows[0].course_count), codes: String(result.rows[0].course_codes || '') };
  } finally {
    await pool.end();
  }
}

test('[QA-002][QA-005] uninstalling one QA run preserves the other run and protected QuantaEdge baseline', async ({ browser }) => {
  const owner = await readPrivateJson<{ statePath: string }>('hrms-owner-state-path.json');
  const context = await browser.newContext({ storageState: owner.statePath });
  const page = await context.newPage();
  const runIds: string[] = [];
  let assertionsCompleted = false;
  try {
    await page.goto(HRMS + '/qa');
    const gate = await requestJson(context, 'GET', '/api/v1/hrms/platform/qa/status');
    expect(gate.status).toBe(200);
    expect(gate.body.writeAllowed).toBe(true);
    expect(gate.body.productionProfileDetected).toBe(false);
    expect(gate.body.currentDatabase).toBe(process.env.QE_E2E_TARGET_DATABASE || 'quantaedge_qa');

    const protectedBefore = await protectedCourseOwnership();
    expect(protectedBefore).toEqual({ count: 3, codes: 'CLASS_6,CLASS_7,CLASS_8' });

    const install = async () => {
      const idempotencyKey = 'qa-isolation-' + randomUUID();
      const result = await requestJson(context, 'POST', '/api/v1/hrms/platform/qa/runs/install', {
        packKey: 'TENANT_LEARNER_BOUNDARY_V1',
        idempotencyKey
      });
      expect(result.status, 'Each isolation run must install through the approved HRMS API.').toBe(200);
      const runId = String(result.body.run_id || result.body.runId || '');
      expect(runId).toMatch(/^[0-9a-f-]{36}$/i);
      runIds.push(runId);
      expect(result.body.state, 'The QA fixture transaction must complete before isolation assertions.').toBe('INSTALLED');
      return runId;
    };

    const runA = await install();
    const runB = await install();
    expect(runB).not.toBe(runA);

    const [aBefore, bBefore, bManifestBefore] = await Promise.all([
      markedCounts(runA),
      markedCounts(runB),
      manifestRows(runB)
    ]);
    expect(bBefore['institution_tenant']).toBe(3);
    expect(bBefore['institution_course_assignment']).toBe(3);
    expect(bBefore['student_program_enrollment']).toBe(3);
    expect(bManifestBefore.length).toBeGreaterThan(0);
    expect(bManifestBefore.every(row => row.state === 'OWNED')).toBe(true);

    const runAInstall = await requestJson(context, 'GET', '/api/v1/hrms/platform/qa/runs/' + encodeURIComponent(runA));
    expect(runAInstall.status).toBe(200);
    const uninstallA = await requestJson(context, 'POST',
      '/api/v1/hrms/platform/qa/runs/' + encodeURIComponent(runA) + '/uninstall',
      { confirmRunId: runA });
    expect(uninstallA.status, 'The service must accept exact run-ID confirmation for run A.').toBe(200);
    expect(uninstallA.body.state).toBe('UNINSTALLED');
    expect(uninstallA.body.cleanup_report?.baselineCheckPassed).toBe(true);
    expect(Number(uninstallA.body.cleanup_report?.resourcesRemaining)).toBe(0);

    const aAfter = await markedCounts(runA);
    expect(Object.values(aAfter).every(count => count === 0), 'No domain row owned by run A may remain.').toBe(true);
    const aManifestAfter = await manifestRows(runA);
    expect(aManifestAfter.length).toBeGreaterThan(0);
    expect(aManifestAfter.every(row => row.state === 'CLEANED')).toBe(true);

    const runBStillInstalled = await requestJson(context, 'GET', '/api/v1/hrms/platform/qa/runs/' + encodeURIComponent(runB));
    expect(runBStillInstalled.status).toBe(200);
    expect(runBStillInstalled.body.state).toBe('INSTALLED');
    const [bAfter, bManifestAfter, protectedAfter] = await Promise.all([
      markedCounts(runB),
      manifestRows(runB),
      protectedCourseOwnership()
    ]);
    expect(bAfter, 'Uninstalling A must not remove or change B-owned rows.').toEqual(bBefore);
    expect(bManifestAfter, 'Run B manifest/resource state must remain untouched while A is cleaned.').toEqual(bManifestBefore);
    expect(protectedAfter, 'QuantaEdge ownership of Classes 6–8 must remain unchanged.').toEqual(protectedBefore);

    assertionsCompleted = true;
  } finally {
    // Remove only these locally created runs. The surrounding job independently destroys
    // the disposable PostgreSQL volume even if this best-effort cleanup is interrupted.
    const cleanupErrors: string[] = [];
    for (const runId of [...runIds].reverse()) {
      try {
        const result = await requestJson(context, 'POST',
          '/api/v1/hrms/platform/qa/runs/' + encodeURIComponent(runId) + '/uninstall',
          { confirmRunId: runId });
        if (result.status !== 200 || (result.body.state !== 'UNINSTALLED' && result.body.state !== 'UNINSTALL_BLOCKED')) {
          cleanupErrors.push('run cleanup did not complete: ' + runId);
        }
      } catch {
        cleanupErrors.push('run cleanup request failed: ' + runId);
      }
    }
    await context.close();
    if (assertionsCompleted && cleanupErrors.length) {
      throw new Error('QA-005 isolation probe could not fully clean its disposable test runs: ' + cleanupErrors.join(', '));
    }
  }
});
