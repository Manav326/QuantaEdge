'use client';

import AdminSidebar from '../components/AdminSidebar';
import { useCallback, useEffect, useState } from 'react';
import styles from './Marketplace.module.css';

type OfferDraft = {
  course_id: number;
  code: string;
  display_name: string;
  curriculum_version?: string | null;
  course_status: string;
  title_override?: string | null;
  description?: string | null;
  price_paise?: number | null;
  currency?: string | null;
  offer_status?: 'DRAFT' | 'PUBLISHED' | 'ARCHIVED' | null;
  updated_at?: string | null;
};
type Draft = { title: string; description: string; priceRupees: string; status: 'DRAFT' | 'PUBLISHED' | 'ARCHIVED' };

async function api(url: string, init: RequestInit = {}) {
  const response = await fetch(url, {
    ...init,
    credentials: 'same-origin',
    cache: 'no-store',
    headers: { ...(init.body ? { 'Content-Type': 'application/json' } : {}), ...(init.headers || {}) },
  });
  const body = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(body.message || body.detail || body.error || 'This request could not be completed.');
  return body;
}

function toDraft(row: OfferDraft): Draft {
  return {
    title: row.title_override || row.display_name || '',
    description: row.description || '',
    priceRupees: row.price_paise == null ? '' : (Number(row.price_paise) / 100).toFixed(2).replace(/\.00$/, ''),
    status: row.offer_status || 'DRAFT',
  };
}

export default function CourseMarketplaceAdminPage() {
  const [rows, setRows] = useState<OfferDraft[]>([]);
  const [drafts, setDrafts] = useState<Record<number, Draft>>({});
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState('');
  const [savingId, setSavingId] = useState<number | null>(null);
  const [notice, setNotice] = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    setLoadError('');
    try {
      const body = await api('/api/v1/marketplace/admin/courses');
      const list = Array.isArray(body) ? body as OfferDraft[] : [];
      setRows(list);
      const next: Record<number, Draft> = {};
      list.forEach(row => { next[row.course_id] = toDraft(row); });
      setDrafts(next);
    } catch (error: any) {
      setLoadError(error?.message || 'Course offers could not be loaded.');
      setRows([]);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { void load(); }, [load]);

  function edit(courseId: number, patch: Partial<Draft>) {
    setDrafts(current => ({ ...current, [courseId]: { ...current[courseId], ...patch } }));
  }

  async function save(row: OfferDraft) {
    const draft = drafts[row.course_id];
    if (!draft) return;
    const price = draft.priceRupees.trim() === '' ? NaN : Number(draft.priceRupees);
    if (!Number.isFinite(price) || price < 0 || !Number.isSafeInteger(Math.round(price * 100))) {
      setNotice('Enter a valid price in rupees. Use 0 only when the course is intended to be free.');
      return;
    }
    if (draft.title.trim().length > 200 || draft.description.length > 4000) {
      setNotice('The title must be 200 characters or fewer and the description 4,000 characters or fewer.');
      return;
    }
    setSavingId(row.course_id);
    setNotice('');
    try {
      await api('/api/v1/marketplace/admin/courses/' + row.course_id, {
        method: 'PUT',
        body: JSON.stringify({
          title: draft.title.trim() || row.display_name,
          description: draft.description.trim(),
          pricePaise: Math.round(price * 100),
          currency: 'INR',
          status: draft.status,
        }),
      });
      setNotice(draft.status === 'PUBLISHED'
        ? 'Offer saved and published. It is now eligible to appear in the student Marketplace.'
        : 'Offer saved.');
      await load();
    } catch (error: any) {
      setNotice(error?.message || 'The offer could not be saved.');
    } finally {
      setSavingId(null);
    }
  }

  return (
    <div className={styles.workspace}>
      <AdminSidebar active="marketplace" />
      <main className={styles.main}>
        <header className={styles.hero}>
          <div className={styles.mark} aria-hidden="true">₹</div>
          <div>
            <span className={styles.eyebrow}>COURSE ACCESS & PRICING</span>
            <h1>Course Marketplace</h1>
            <p>Choose which active courses can be purchased, set their INR price and publish an offer for students.</p>
          </div>
        </header>

        {notice && <div className={styles.notice} role="status">{notice}</div>}
        {loadError && <div className={styles.error} role="alert"><span>{loadError}</span><button type="button" onClick={() => void load()}>Try again</button></div>}

        <section className={styles.panel}>
          <div className={styles.sectionHead}>
            <div><h2>Course offers</h2><p>Unlisted courses remain unavailable in the student Marketplace. No prices are assumed or generated automatically.</p></div>
            <button type="button" className={styles.refresh} onClick={() => void load()} disabled={loading} title="Reload offers" aria-label="Reload offers">↻</button>
          </div>

          {loading ? <div className={styles.empty}>Loading active courses…</div>
            : rows.length === 0 ? <div className={styles.empty}>No active courses are available to configure.</div>
            : <div className={styles.offerList}>
              {rows.map(row => {
                const draft = drafts[row.course_id] || toDraft(row);
                return (
                  <article className={styles.offer} key={row.course_id}>
                    <div className={styles.offerHead}>
                      <div className={styles.offerIcon} aria-hidden="true">✦</div>
                      <div className={styles.offerIdentity}>
                        <h3>{row.display_name}</h3>
                        <p>{row.code} · {row.curriculum_version || 'Course curriculum'}</p>
                      </div>
                      <span className={styles.statusPill + ' ' + (draft.status === 'PUBLISHED' ? styles.published : draft.status === 'ARCHIVED' ? styles.archived : styles.draft)}>
                        {draft.status === 'PUBLISHED' ? 'Published' : draft.status === 'ARCHIVED' ? 'Archived' : 'Draft'}
                      </span>
                    </div>

                    <div className={styles.formGrid}>
                      <label className={styles.field}>
                        Display title
                        <input value={draft.title} maxLength={200} onChange={event => edit(row.course_id, { title: event.target.value })} />
                      </label>
                      <label className={styles.field}>
                        Price (INR)
                        <input inputMode="decimal" type="number" min="0" step="0.01" value={draft.priceRupees} placeholder="Enter price" onChange={event => edit(row.course_id, { priceRupees: event.target.value })} />
                        <small>Set 0 only for an intentionally free offer.</small>
                      </label>
                      <label className={styles.field + ' ' + styles.wide}>
                        Student-facing description
                        <textarea rows={3} maxLength={4000} value={draft.description} onChange={event => edit(row.course_id, { description: event.target.value })} placeholder="Describe what the course includes." />
                      </label>
                      <label className={styles.field}>
                        Marketplace status
                        <select value={draft.status} onChange={event => edit(row.course_id, { status: event.target.value as Draft['status'] })}>
                          <option value="DRAFT">Draft — hidden from students</option>
                          <option value="PUBLISHED">Published — visible to students</option>
                          <option value="ARCHIVED">Archived — unavailable</option>
                        </select>
                      </label>
                    </div>

                    <div className={styles.offerFooter}>
                      <span>{row.offer_status === 'PUBLISHED' ? 'Student listing is currently published' : 'Not currently published to students'}</span>
                      <button type="button" className={styles.save} disabled={savingId !== null} onClick={() => void save(row)}>
                        {savingId === row.course_id ? 'Saving…' : draft.status === 'PUBLISHED' ? 'Save & publish' : 'Save offer'}
                      </button>
                    </div>
                  </article>
                );
              })}
            </div>}
        </section>
      </main>
    </div>
  );
}
