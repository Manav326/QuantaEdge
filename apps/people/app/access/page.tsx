'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { useRouter } from 'next/navigation';
import { PeopleEmptyState, PeopleFeedback, PeopleMetricCard, PeoplePageHeader, PeopleStatusBadge, PeopleButton } from '../components/PeopleUI';

type Session = { staffId?: number; role?: string; displayName?: string; permissions?: string[]; workspace?: string };
type AccessUser = {
  employee_id: number;
  employee_number: string;
  display_name: string;
  employment_status: string;
  hrms_access_enabled: boolean;
  staff_id: number | null;
  username: string | null;
  identity_active: boolean | null;
  identity_role: string | null;
  last_login_at: string | null;
  roles: string[];
  permissions: string[];
  assigned_permissions: string[];
};
type Permission = { permission_key: string; display_name: string; description: string };

const ROLE_OPTIONS = [
  { value: 'EMPLOYEE', label: 'Employee', description: 'Own profile and permitted self-service only.' },
  { value: 'HR', label: 'Human Resources', description: 'Employee records, attendance, leave and HR workflows.' },
  { value: 'ACCOUNTANT', label: 'Accountant', description: 'Payroll information, payroll processing and approved payment tasks.' },
  { value: 'PEOPLE_MANAGER', label: 'People Manager', description: 'Permitted team information and manager-level approvals.' },
];

async function api(path: string, init: RequestInit = {}) {
  const headers = new Headers(init.headers);
  headers.set('X-QE-Workspace', 'HRMS');
  if (init.body && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json');
  const response = await fetch(path, { ...init, headers, credentials: 'same-origin', cache: 'no-store' });
  const raw = await response.text();
  let data: any = {};
  try { data = raw ? JSON.parse(raw) : {}; } catch { data = { message: raw }; }
  if (!response.ok) throw new Error(data.message || data.detail || data.error || `Request failed (${response.status}).`);
  return data;
}

const pretty = (value: unknown) => String(value ?? '—').replaceAll('_', ' ').toLowerCase().replace(/\b\w/g, (c) => c.toUpperCase());
const reserved = (row: AccessUser) => row.username?.toLowerCase() === 'hrms_admin';

export default function HrmsAccessPage() {
  const router = useRouter();
  const [session, setSession] = useState<Session | null>(null);
  const [rows, setRows] = useState<AccessUser[]>([]);
  const [permissionOptions, setPermissionOptions] = useState<Permission[]>([]);
  const [assignedPermissions, setAssignedPermissions] = useState<string[]>([]);
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [search, setSearch] = useState('');
  const [username, setUsername] = useState('');
  const [initialPassword, setInitialPassword] = useState('');
  const [roles, setRoles] = useState<string[]>(['EMPLOYEE']);
  const [showCredentials, setShowCredentials] = useState(false);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const me = await api('/api/v1/auth/me');
      if (!me.staffId || me.workspace !== 'HRMS') { router.replace('/login'); return; }
      setSession(me);
      if (!Array.isArray(me.permissions) || !me.permissions.includes('HR_ACCESS_MANAGE')) {
        setRows([]);
        setError('Your account cannot manage HRMS login access. Ask the People Operations Super Admin to grant HR_ACCESS_MANAGE.');
        return;
      }
      const [result, options] = await Promise.all([
        api('/api/v1/hrms/access-users'),
        api('/api/v1/hrms/access-users/permissions'),
      ]);
      setRows(Array.isArray(result) ? result : []);
      setPermissionOptions(Array.isArray(options) ? options : []);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Employee access records could not be loaded.');
      if (String(cause).toLowerCase().includes('authentication required')) router.replace('/login');
    } finally {
      setLoading(false);
    }
  }, [router]);

  useEffect(() => { void load(); }, [load]);

  const filtered = useMemo(() => rows.filter((row) => [
    row.employee_number, row.display_name, row.username, row.employment_status, ...(row.roles || []),
  ].some((value) => String(value ?? '').toLowerCase().includes(search.toLowerCase()))), [rows, search]);

  const selected = rows.find((row) => row.employee_id === selectedId) || null;
  const isSelf = Boolean(selected?.staff_id && session?.staffId === selected.staff_id);
  const isReserved = Boolean(selected && reserved(selected));

  function selectEmployee(row: AccessUser) {
    setSelectedId(row.employee_id);
    setUsername(row.username || '');
    setInitialPassword('');
    setRoles(Array.isArray(row.roles) && row.roles.length ? [...row.roles] : ['EMPLOYEE']);
    setAssignedPermissions(Array.isArray(row.assigned_permissions) ? [...row.assigned_permissions] : []);
    setShowCredentials(!row.username || !row.hrms_access_enabled);
    setError('');
    setNotice('');
  }

  function toggleRole(role: string, enabled: boolean) {
    setRoles((current) => {
      if (enabled) return [...new Set([...current, role])];
      const next = current.filter((item) => item !== role);
      return next.length ? next : ['EMPLOYEE'];
    });
  }

  async function provision() {
    if (!selected) return;
    if (initialPassword.length < 12) {
      setError('The initial password must contain at least 12 characters.');
      return;
    }
    setSaving(true); setError(''); setNotice('');
    try {
      await api('/api/v1/hrms/access-users/' + selected.employee_id, {
        method: 'POST',
        body: JSON.stringify({ username: username.trim(), initialPassword, roles, permissions: assignedPermissions }),
      });
      setNotice('Login credentials saved. The employee must change the initial password at first sign-in; previous HRMS sessions have been revoked.');
      setInitialPassword('');
      await load();
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Login credentials could not be saved.');
    } finally { setSaving(false); }
  }

  async function saveRoles() {
    if (!selected) return;
    setSaving(true); setError(''); setNotice('');
    try {
      await api('/api/v1/hrms/access-users/' + selected.employee_id + '/roles', {
        method: 'PUT', body: JSON.stringify({ roles, permissions: assignedPermissions }),
      });
      setNotice('Roles updated. The API enforces these permissions immediately; active HRMS sessions were revoked for a fresh sign-in.');
      await load();
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Role assignments could not be saved.');
    } finally { setSaving(false); }
  }

  async function toggleAccess(row: AccessUser) {
    if (!row.staff_id) { selectEmployee(row); setShowCredentials(true); setNotice('Create a username and initial password to enable this employee login.'); return; }
    if (row.username?.toLowerCase() === 'hrms_admin' || row.staff_id === session?.staffId) return;
    const enabled = !row.hrms_access_enabled;
    setSaving(true); setError(''); setNotice('');
    try {
      await api('/api/v1/hrms/access-users/' + row.employee_id + '/status', {
        method: 'PUT', body: JSON.stringify({ enabled }),
      });
      setNotice(enabled ? 'HRMS access enabled.' : 'HRMS access disabled and the employee’s active HRMS sessions revoked.');
      await load();
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'HRMS access status could not be updated.');
    } finally { setSaving(false); }
  }

    return (
    <div className="shell">
      

      <main className="main">
        <PeoplePageHeader eyebrow="ACCESS GOVERNANCE · HRMS ONLY" title="Employee login access" description="Create employee logins, assign multiple HRMS roles, and revoke HRMS access without signing the employee out of Educational Admin." />

        {error && <PeopleFeedback tone="danger" role="alert">{error}</PeopleFeedback>}
        {notice && <PeopleFeedback tone="success" role="status">{notice}</PeopleFeedback>}

        <div className="cards">
          <PeopleMetricCard label="Employee records" value={loading ? '—' : rows.length} help="Canonical HR records" />
          <PeopleMetricCard label="HRMS access enabled" value={loading ? '—' : rows.filter((r) => r.hrms_access_enabled).length} help="Access may still expire or be revoked" />
          <PeopleMetricCard label="No login linked" value={loading ? '—' : rows.filter((r) => !r.staff_id).length} help="Employees awaiting credentials" />
        </div>

        <section className="panel">
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 12, flexWrap: 'wrap' }}>
            <div><div className="eyebrow">EMPLOYEE DIRECTORY</div><h2>Access and role assignments</h2><p className="small">Only authorised HRMS access managers can open this screen. Permission checks are also enforced by the API.</p></div>
            <PeopleButton variant="secondary" type="button" onClick={() => void load()} disabled={loading || saving}>Refresh</PeopleButton>
          </div>
          <label style={{ display: 'block', margin: '16px 0' }}>Search employees
            <input className="input" value={search} onChange={(event) => setSearch(event.target.value)} placeholder="Name, employee number, username or role" />
          </label>
          {loading ? <div className="empty" role="status" aria-live="polite">Loading employee access records…</div> : (
            <div className="table-wrap">
              <table>
                <thead><tr><th>Employee</th><th>HRMS username</th><th>Assigned roles</th><th>HRMS access</th><th>Last sign-in</th><th>Action</th></tr></thead>
                <tbody>
                  {filtered.map((row) => (
                    <tr key={row.employee_id}>
                      <td><strong>{row.display_name}</strong><div className="small">{row.employee_number} · <PeopleStatusBadge status={pretty(row.employment_status)} /></div></td>
                      <td>{row.username || 'Not created'}{reserved(row) && <div className="small">People Operations Super Admin</div>}</td>
                      <td>{(row.roles || []).map(pretty).join(', ') || 'Employee'}<div className="small">{(row.permissions || []).length} effective permissions</div></td>
                      <td><PeopleStatusBadge status={row.hrms_access_enabled ? 'Enabled' : 'Disabled'} /></td>
                      <td>{row.last_login_at ? new Date(row.last_login_at).toLocaleString() : 'Never'}</td>
                      <td style={{ minWidth: 180 }}>
                        <PeopleButton variant="secondary" type="button" onClick={() => selectEmployee(row)} disabled={saving}>{reserved(row) ? 'View account' : 'Manage access'}</PeopleButton>
                        {!reserved(row) && row.staff_id !== session?.staffId && <PeopleButton variant="secondary" type="button" onClick={() => void toggleAccess(row)} disabled={saving || !row.staff_id}>{row.hrms_access_enabled ? 'Disable' : 'Enable'}</PeopleButton>}
                        {!row.staff_id && <PeopleButton variant="secondary" type="button" onClick={() => selectEmployee(row)} disabled={saving}>Create login</PeopleButton>}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
              {!filtered.length && <PeopleEmptyState title="No employees match the search." />}
            </div>
          )}
        </section>

        {selected && (
          <section className="panel">
            <div style={{ display: 'flex', justifyContent: 'space-between', gap: 12, alignItems: 'start' }}>
              <div><div className="eyebrow">EMPLOYEE ACCESS CONFIGURATION</div><h2>{selected.display_name}</h2><p className="small">{selected.employee_number} · <PeopleStatusBadge status={pretty(selected.employment_status)} /></p></div>
              <PeopleButton variant="secondary" type="button" onClick={() => { setSelectedId(null); setShowCredentials(false); }} disabled={saving}>Close</PeopleButton>
            </div>

            <div className="cards">
              {ROLE_OPTIONS.map((role) => (
                <label className="card" key={role.value} style={{ cursor: 'pointer', display: 'flex', gap: 10, alignItems: 'start' }}>
                  <input type="checkbox" checked={roles.includes(role.value)} onChange={(event) => toggleRole(role.value, event.target.checked)} disabled={saving || isReserved || isSelf} />
                  <span><strong>{role.label}</strong><div className="small">{role.description}</div></span>
                </label>
              ))}
            </div>
            <p className="small">Roles combine by union. Additional permissions below are HRMS-scoped and cannot grant education content access. The API checks every permission server-side.</p>
            <div className="cards">
              {permissionOptions.map((permission) => (
                <label className="card" key={permission.permission_key} style={{ cursor: 'pointer', display: 'flex', gap: 10, alignItems: 'start' }}>
                  <input
                    type="checkbox"
                    checked={assignedPermissions.includes(permission.permission_key)}
                    onChange={(event) => setAssignedPermissions((current) => event.target.checked
                      ? [...new Set([...current, permission.permission_key])]
                      : current.filter((key) => key !== permission.permission_key))}
                    disabled={saving || isReserved || isSelf}
                  />
                  <span><strong>{permission.display_name}</strong><div className="small">{permission.description}</div><div className="small">{permission.permission_key}</div></span>
                </label>
              ))}
            </div>

            {selected.staff_id && selected.hrms_access_enabled && !isReserved && !isSelf && (
              <PeopleButton variant="primary" type="button" onClick={() => void saveRoles()} disabled={saving}>
                {saving ? 'Saving…' : 'Save roles and permissions'}
              </PeopleButton>
            )}

            {!isReserved && !isSelf && (
              <div style={{ marginTop: 18 }}>
                <PeopleButton variant="secondary" type="button" onClick={() => setShowCredentials((value) => !value)} disabled={saving}>
                  {showCredentials ? 'Hide credential form' : selected.username ? 'Set / reset password' : 'Create username and password'}
                </PeopleButton>
                {showCredentials && (
                  <form className="card" style={{ marginTop: 12 }} onSubmit={(event) => { event.preventDefault(); void provision(); }}>
                    <h3>{selected.username ? 'Reset HRMS credentials' : 'Create HRMS credentials'}</h3>
                    <p className="small">The initial password is temporary. The employee must replace it before accessing any other HRMS screen. Resetting credentials revokes active HRMS sessions.</p>
                    <label>Username
                      <input className="input" required minLength={3} maxLength={64} autoComplete="off" value={username} onChange={(event) => setUsername(event.target.value)} placeholder="employee.username" />
                    </label>
                    <label>Temporary password (at least 12 characters)
                      <input className="input" required type="password" minLength={12} maxLength={128} autoComplete="new-password" value={initialPassword} onChange={(event) => setInitialPassword(event.target.value)} />
                    </label>
                    <PeopleButton variant="primary" disabled={saving || !username.trim() || initialPassword.length < 12}>{saving ? 'Saving securely…' : 'Save credentials and roles'}</PeopleButton>
                  </form>
                )}
              </div>
            )}
            {isReserved && <p className="small">This bootstrap account is protected from routine role, password and access changes in this screen. Manage recovery through the approved system-administrator process.</p>}
            {isSelf && <p className="small">You cannot change your own HRMS roles or access status. Use a different authorised access manager.</p>}
          </section>
        )}
      </main>
    </div>
  );
}
