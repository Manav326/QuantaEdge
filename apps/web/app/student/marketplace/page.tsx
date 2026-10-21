'use client';


import { apiFetch } from '../../components/apiClient';
import Link from 'next/link';
import { useCallback, useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import styles from './marketplace.module.css';
import { tenantPrimaryColor, useTenantBranding } from '../../components/TenantBrandingProvider';
import { formatUserTimestamp } from '../../components/formatUserTimestamp';
import { UiText } from '../../i18n/useUiTranslation';
import { useLocale } from '../../components/LanguageProvider';
import StudentEmptyState from '../components/StudentEmptyState';
import BillingProfileEditor from '../../components/BillingProfileEditor';
import InvoiceDownloads from '../../components/InvoiceDownloads';
import MarketplaceCourseCard from '../../components/MarketplaceCourseCard';

type Subject = { subject_code?: string; subject_name?: string; chapters?: number; lessons?: number };
type Offer = {
  course_id: number;
  public_id?: string;
  title: string;
  course_name: string;
  course_code: string;
  curriculum_version?: string;
  description?: string | null;
  price_paise: number;
  currency: string;
  has_access: boolean;
  purchased: boolean;
  parent_managed?: boolean;
  parent_request_status?: string | null;
  parent_request_requested_at?: string | null;
  subjects?: Subject[];
};
type PurchaseRequestStatus = {
  request_id: number;
  course_id: number;
  status: 'PENDING' | 'PURCHASED' | 'DISMISSED' | string;
  requested_at: string;
  updated_at?: string;
  resolved_at?: string | null;
  course_name: string;
  price_paise: number;
  currency: string;
};
type VerifyDraft = { courseId: number; localOrderId: number; paymentId: string; orderId: string; signature: string };
const PENDING_VERIFY_STORAGE_KEY = 'qe:marketplace:pending-payment:student';
type CheckoutResponse = { orderId: string; localOrderId: number; amount: number; currency: string; keyId: string; description?: string; listedPricePaise?: number; discountPaise?: number; discountCode?: string | null };

declare global {
  interface Window {
    Razorpay?: new (options: Record<string, unknown>) => { open: () => void };
  }
}

async function readJson(response: Response): Promise<any> {
  const body = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(body.message || body.detail || body.error || 'The request could not be completed.');
  return body;
}

async function ensureRazorpayCheckout(): Promise<void> {
  if (window.Razorpay) return;
  const existing = document.querySelector<HTMLScriptElement>('script[data-qe-razorpay="true"]');
  if (existing) {
    await new Promise<void>((resolve, reject) => {
      existing.addEventListener('load', () => resolve(), { once: true });
      existing.addEventListener('error', () => reject(new Error('Payment checkout could not be loaded.')), { once: true });
    });
    if (window.Razorpay) return;
    throw new Error('Payment checkout could not be loaded. Please retry.');
  }
  await new Promise<void>((resolve, reject) => {
    const script = document.createElement('script');
    script.src = 'https://checkout.razorpay.com/v1/checkout.js';
    script.async = true;
    script.dataset.qeRazorpay = 'true';
    script.onload = () => resolve();
    script.onerror = () => reject(new Error('Payment checkout could not be loaded. Please retry.'));
    document.head.appendChild(script);
  });
  if (!window.Razorpay) throw new Error('Payment checkout could not be loaded. Please retry.');
}

export default function StudentMarketplacePage() {
  const router = useRouter();
  const { brand, institutionContextRequested, isResolving } = useTenantBranding();
  const { locale } = useLocale();
  const english = locale === 'english';
  const tx = (en: string, hi: string, values: Record<string, string | number> = {}) =>
    (english ? en : hi).replace(/\{([a-zA-Z0-9_]+)\}/g, (_match, key: string) => String(values[key] ?? ''));
  const [offers, setOffers] = useState<Offer[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState('');
  const [busyCourseId, setBusyCourseId] = useState<number | null>(null);
  const [discountCode, setDiscountCode] = useState('');
  const [status, setStatus] = useState('');
  const [pendingVerify, setPendingVerify] = useState<VerifyDraft | null>(null);
  const [verifying, setVerifying] = useState(false);
  const [purchaseRequests, setPurchaseRequests] = useState<PurchaseRequestStatus[]>([]);

  const loadOffers = useCallback(async () => {
    setLoading(true);
    setLoadError('');
    try {
      const body = await readJson(await apiFetch('/api/v1/marketplace/courses', {
        credentials: 'same-origin', cache: 'no-store',
      }));
      setOffers(Array.isArray(body) ? body : []);
    } catch (error: any) {
      setOffers([]);
      setLoadError(error?.message || tx('Marketplace could not be loaded.', 'Marketplace लोड नहीं हो पाया।'));
    } finally {
      setLoading(false);
    }
  }, [english]);

  const loadPurchaseRequests = useCallback(async () => {
    try {
      const response = await apiFetch('/api/v1/marketplace/student/purchase-requests', {
        credentials: 'same-origin', cache: 'no-store',
        headers: { 'X-QE-Workspace': 'STUDENT' },
      });
      if (!response.ok) return;
      const body = await response.json().catch(() => []);
      setPurchaseRequests(Array.isArray(body) ? body : []);
    } catch { /* Marketplace offers remain usable if request history is temporarily unavailable. */ }
  }, []);

  useEffect(() => { void loadOffers(); void loadPurchaseRequests(); }, [loadOffers, loadPurchaseRequests]);

  // Keep the signed checkout response for this tab so a refresh/deployment cannot
  // erase the only data that can safely verify a payment already made by the learner.
  useEffect(() => {
    try {
      const saved = window.sessionStorage.getItem(PENDING_VERIFY_STORAGE_KEY);
      if (!saved) return;
      const value = JSON.parse(saved) as Partial<VerifyDraft>;
      const draft: VerifyDraft = {
        courseId: Number(value.courseId),
        localOrderId: Number(value.localOrderId),
        paymentId: String(value.paymentId || ''),
        orderId: String(value.orderId || ''),
        signature: String(value.signature || ''),
      };
      const hasPaymentCallback = draft.paymentId.length > 0 || draft.signature.length > 0;
      const callbackIsValid = !hasPaymentCallback ||
        (/^pay_[A-Za-z0-9]+$/.test(draft.paymentId) && /^[A-Fa-f0-9]{64}$/.test(draft.signature));
      if (Number.isSafeInteger(draft.courseId) && draft.courseId > 0
          && Number.isSafeInteger(draft.localOrderId) && draft.localOrderId > 0
          && /^order_[A-Za-z0-9]+$/.test(draft.orderId) && callbackIsValid) {
        setPendingVerify(draft);
      } else {
        window.sessionStorage.removeItem(PENDING_VERIFY_STORAGE_KEY);
      }
    } catch {
      // Storage may be disabled by the browser; in-page retry still works.
    }
  }, []);

  const verifyPurchase = useCallback(async (draft: VerifyDraft) => {
    setVerifying(true);
    setStatus('');
    setPendingVerify(draft);
    try {
      window.sessionStorage.setItem(PENDING_VERIFY_STORAGE_KEY, JSON.stringify(draft));
    } catch {
      // Keep the in-memory retry state even when session storage is unavailable.
    }
    try {
      await readJson(await apiFetch('/api/v1/marketplace/orders/' + draft.localOrderId + '/verify', {
        method: 'POST',
        credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          paymentId: draft.paymentId,
          orderId: draft.orderId,
          signature: draft.signature,
        }),
      }));
      setPendingVerify(null);
      try { window.sessionStorage.removeItem(PENDING_VERIFY_STORAGE_KEY); } catch { /* Ignore blocked storage. */ }
      setStatus(tx('Payment verified. Your course is now in All Courses.', 'भुगतान सत्यापित हो गया। आपका कोर्स All Courses में उपलब्ध है।'));
      await loadOffers();
      await loadPurchaseRequests();
      router.push('/student/courses?focus=' + encodeURIComponent(String(draft.courseId)));
    } catch (error: any) {
      setPendingVerify(draft);
      setStatus(error?.message || tx('Payment verification could not be completed. Retry verification before paying again.', 'भुगतान की पुष्टि पूरी नहीं हो सकी। दोबारा भुगतान करने से पहले इसी order की पुष्टि फिर करें।'));
    } finally {
      setVerifying(false);
      setBusyCourseId(null);
    }
  }, [loadOffers, loadPurchaseRequests, router, english]);

  const reconcilePurchase = useCallback(async (draft: VerifyDraft) => {
    setVerifying(true);
    setStatus('');
    setPendingVerify(draft);
    try {
      window.sessionStorage.setItem(PENDING_VERIFY_STORAGE_KEY, JSON.stringify(draft));
    } catch {
      // In-page recovery remains available if browser storage is blocked.
    }
    try {
      const result = await readJson(await apiFetch('/api/v1/marketplace/orders/' + draft.localOrderId + '/reconcile', {
        method: 'POST', credentials: 'same-origin', headers: { 'Content-Type': 'application/json' }, body: '{}',
      }));
      if (String(result.status || '').toUpperCase() === 'VERIFIED' || result.entitled === true) {
        setPendingVerify(null);
        try { window.sessionStorage.removeItem(PENDING_VERIFY_STORAGE_KEY); } catch { /* Ignore blocked storage. */ }
        setStatus(tx('Payment verified. Your course is now in All Courses.', 'भुगतान सत्यापित हो गया। आपका कोर्स All Courses में उपलब्ध है।'));
        await loadOffers();
        await loadPurchaseRequests();
        router.push('/student/courses?focus=' + encodeURIComponent(String(draft.courseId)));
        return;
      }
      if (result.canRetryCheckout === true) {
        setPendingVerify(null);
        try { window.sessionStorage.removeItem(PENDING_VERIFY_STORAGE_KEY); } catch { /* Ignore blocked storage. */ }
      }
      setStatus(String(result.message || tx('Payment status is still being reconciled. Do not pay again while verification is pending.', 'भुगतान की जाँच अभी जारी है। पुष्टि पूरी होने तक दोबारा भुगतान न करें।')));
      await loadOffers();
    } catch (error: any) {
      setPendingVerify(draft);
      setStatus(error?.message || tx('Payment status could not be reconciled. Keep this order and retry verification; do not pay again.', 'भुगतान की स्थिति की पुष्टि नहीं हो सकी। यही order सुरक्षित रखें और फिर जाँचें; दोबारा भुगतान न करें।'));
    } finally {
      setVerifying(false);
      setBusyCourseId(null);
    }
  }, [loadOffers, loadPurchaseRequests, router, english]);

  async function buyCourse(courseId: number) {
    if (pendingVerify) {
      setStatus(tx('A previous payment still needs verification. Retry verification before starting another checkout.', 'पिछले भुगतान की पुष्टि बाकी है। नया checkout शुरू करने से पहले उसी order की जाँच करें।'));
      return;
    }
    if (!Number.isSafeInteger(courseId) || courseId <= 0 || busyCourseId !== null) return;
    if (institutionContextRequested && (!brand || isResolving)) {
      setStatus(tx('We could not verify your school identity. Reopen the official link shared by your school before starting checkout.', 'आपकी संस्था की पहचान सत्यापित नहीं हो सकी। भुगतान शुरू करने से पहले संस्था द्वारा भेजा गया आधिकारिक लिंक फिर खोलें।'));
      return;
    }
    setBusyCourseId(courseId);
    setStatus('');
    try {
      const order = await readJson(await apiFetch('/api/v1/marketplace/courses/' + courseId + '/orders', {
        method: 'POST',
        credentials: 'same-origin',
        headers: {
          'Content-Type': 'application/json',
          'Idempotency-Key': crypto.randomUUID(),
        },
        body: JSON.stringify({ discountCode: discountCode.trim() || null }),
      })) as CheckoutResponse;
      if (!order.orderId || !Number.isSafeInteger(Number(order.localOrderId)) || Number(order.localOrderId) <= 0 ||
          !order.keyId || !Number.isFinite(Number(order.amount)) || Number(order.amount) <= 0 || order.currency !== 'INR') {
        throw new Error(tx('The course order could not be prepared. Please retry.', 'कोर्स का order तैयार नहीं हो सका। फिर प्रयास करें।'));
      }
      if (Number(order.discountPaise || 0) > 0) {
        setStatus(tx('Offer code {code} applied. You save {amount}. The displayed Razorpay amount includes this server-calculated discount.', 'Offer code {code} लागू हुआ। आपकी बचत {amount} है। Razorpay में दिखाई गई राशि में server-calculated discount शामिल है.', {code:String(order.discountCode || discountCode.trim()),amount:money(Number(order.discountPaise))}));
      }
      const recoverableDraft: VerifyDraft = {
        courseId,
        localOrderId: Number(order.localOrderId),
        paymentId: '',
        orderId: String(order.orderId),
        signature: '',
      };
      setPendingVerify(recoverableDraft);
      try { window.sessionStorage.setItem(PENDING_VERIFY_STORAGE_KEY, JSON.stringify(recoverableDraft)); }
      catch { /* In-page recovery still works when storage is disabled. */ }
      await ensureRazorpayCheckout();
      const RazorpayCtor = window.Razorpay;
      if (!RazorpayCtor) throw new Error(tx('Payment checkout is not available. Please retry.', 'भुगतान checkout उपलब्ध नहीं है। फिर प्रयास करें।'));
      const checkout = new RazorpayCtor({
        key: order.keyId,
        amount: Number(order.amount),
        currency: order.currency,
        name: brand?.displayName || 'QuantaEdge',
        description: order.description || 'Course access',
        order_id: order.orderId,
        theme: { color: tenantPrimaryColor(brand) },
        handler: (response: any) => {
          const draft: VerifyDraft = {
            courseId,
            localOrderId: Number(order.localOrderId),
            paymentId: String(response.razorpay_payment_id || ''),
            orderId: String(response.razorpay_order_id || ''),
            signature: String(response.razorpay_signature || ''),
          };
          if (!draft.paymentId || !draft.orderId || !draft.signature || draft.orderId !== order.orderId) {
            setStatus(tx('The payment confirmation did not match this order. Reconcile this order before starting another checkout.', 'भुगतान की पुष्टि इस order से मेल नहीं खाती। नया checkout शुरू करने से पहले इसी order की जाँच करें।'));
            setPendingVerify(recoverableDraft);
            try { window.sessionStorage.setItem(PENDING_VERIFY_STORAGE_KEY, JSON.stringify(recoverableDraft)); } catch { /* Keep in-memory draft. */ }
            setBusyCourseId(null);
            return;
          }
          void verifyPurchase(draft);
        },
        modal: { ondismiss: () => setBusyCourseId(null) },
      });
      checkout.open();
    } catch (error: any) {
      setBusyCourseId(null);
      setStatus(error?.message || tx('Checkout could not be started. Please retry.', 'Checkout शुरू नहीं हो सका। फिर प्रयास करें।'));
    }
  }

  async function askParentToBuy(courseId: number) {
    if (!Number.isSafeInteger(courseId) || courseId <= 0 || busyCourseId !== null) return;
    setBusyCourseId(courseId);
    setStatus('');
    try {
      const request = await readJson(await apiFetch('/api/v1/marketplace/courses/' + courseId + '/purchase-request', {
        method: 'POST',
        credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json', 'X-QE-Workspace': 'STUDENT' },
        body: '{}',
      }));
      const requestedAt = request.requestedAt || request.requestDetails?.[0]?.requestedAt || new Date().toISOString();
      setOffers(current => current.map(offer => Number(offer.course_id) === courseId
        ? { ...offer, parent_request_status: request.status || 'PENDING', parent_request_requested_at: requestedAt }
        : offer));
      setStatus(tx('Purchase request sent at {time}. Your parent can review it from the family account.', '{time} पर खरीदने का अनुरोध भेजा गया। आपके parent इसे family account में देख सकते हैं।', {time:formatUserTimestamp(requestedAt)}));
    } catch (error: any) {
      setStatus(error?.message || tx('The purchase request could not be sent. Please retry.', 'खरीदने का अनुरोध नहीं भेजा जा सका। फिर प्रयास करें।'));
    } finally {
      setBusyCourseId(null);
    }
  }

  async function joinFreeCourse(courseId: number) {
    if (!Number.isSafeInteger(courseId) || courseId <= 0 || busyCourseId !== null) return;
    setBusyCourseId(courseId);
    setStatus('');
    try {
      await readJson(await apiFetch('/api/v1/students/me/programs/id/' + courseId + '/enroll', {
        method: 'POST', credentials: 'same-origin', headers: { 'Content-Type': 'application/json' }, body: '{}',
      }));
      setStatus(tx('Free course added to All Courses.', 'निःशुल्क कोर्स All Courses में जोड़ दिया गया।'));
      router.push('/student/courses?focus=' + encodeURIComponent(String(courseId)));
    } catch (error: any) {
      setStatus(error?.message || tx('The course could not be added. Please retry.', 'कोर्स नहीं जोड़ा जा सका। फिर प्रयास करें।'));
    } finally {
      setBusyCourseId(null);
    }
  }

  const money = (paise: number) => new Intl.NumberFormat('en-IN', {
    style: 'currency', currency: 'INR', maximumFractionDigits: 2,
  }).format(paise / 100);

  return (
    <main className={styles.page}>
      <header className={styles.hero}>
        <div className={styles.eyebrow}>LEARN SOMETHING NEW</div>
        <h1>Course Marketplace</h1>
        <p>Choose a learning path that fits your goals. Your course price and access are confirmed securely before checkout.</p>
        <div className={styles.heroActions}>
          <Link className={styles.secondaryButton} href="/student/courses">All Courses</Link>
          <button className={styles.iconButton} type="button" onClick={() => void loadOffers()} disabled={loading} aria-label="Reload course offers" title="Reload course offers">↻</button>
        </div>
      </header>

      {status && <p className={styles.status} role="status">{status}</p>}
      <section className={styles.discountCodeBox} aria-label="Course offer code">
        <label htmlFor="qe-student-discount-code">Offer code <span>(optional)</span></label>
        <div className={styles.discountCodeEntry}>
          <input id="qe-student-discount-code" autoComplete="off" maxLength={40} value={discountCode}
            onChange={event=>setDiscountCode(event.target.value.toUpperCase())}
            placeholder="Enter a code supplied by your institution" disabled={busyCourseId!==null||!!pendingVerify}/>
          <span>Validated securely when you start checkout</span>
        </div>
        <p>Offer codes apply only to the course and institution for which HRMS approved them. The payable amount and tax calculation come from the server.</p>
      </section>
      <BillingProfileEditor workspace="STUDENT" english />
      <InvoiceDownloads workspace="STUDENT" english />
      {purchaseRequests.length > 0 && (
        <section className={styles.requestHistory} aria-labelledby="qe-purchase-request-history">
          <div className={styles.requestHistoryHeading}>
            <div><h2 id="qe-purchase-request-history">Parent purchase requests</h2>
              <p>Check when you asked and whether the request is still pending.</p></div>
            <button type="button" disabled={busyCourseId !== null} onClick={() => void loadPurchaseRequests()}>Refresh</button>
          </div>
          <div className={styles.requestHistoryList}>
            {purchaseRequests.map(request => {
              const status = String(request.status || '').toUpperCase();
              const label = status === 'PENDING' ? 'Waiting for parent'
                : status === 'PURCHASED' ? 'Course purchased'
                : status === 'DISMISSED' ? 'Request dismissed' : status.replaceAll('_', ' ').toLowerCase();
              return <article className={styles.requestHistoryRow} key={request.request_id}>
                <div><strong>{request.course_name || 'Course'}</strong>
                  <span>Request #{request.request_id} · Submitted {formatUserTimestamp(request.requested_at)}</span></div>
                <div className={styles.requestHistoryState}>
                  <span className={status === 'PENDING' ? styles.requestPending : status === 'PURCHASED' ? styles.requestComplete : styles.requestOther}>{label}</span>
                  {request.resolved_at && <small>Updated {formatUserTimestamp(request.resolved_at)}</small>}
                </div>
              </article>;
            })}
          </div>
        </section>
      )}
      {pendingVerify && (
        <section className={styles.verifyCard} aria-label="Retry payment verification">
          <div><strong>Complete course access</strong><p>The checkout response is saved for retry. Verify this payment before starting another checkout.</p></div>
          <button type="button" disabled={verifying} onClick={() => void reconcilePurchase(pendingVerify)}>
            {verifying ? 'Verifying…' : 'Retry verification'}
          </button>
        </section>
      )}

      {loading && <div className={styles.stateCard} role="status">Loading course offers…</div>}
      {!loading && loadError && (
        <div className={styles.stateCard}>
          <h2>Marketplace could not be loaded</h2>
          <p>{loadError}</p>
          <button type="button" onClick={() => void loadOffers()}>Try again</button>
        </div>
      )}
      {!loading && !loadError && offers.length === 0 && (
        <StudentEmptyState
          icon="marketplace"
          title={<UiText message="student.empty.marketplace.title" />}
          message={<UiText message="student.empty.marketplace.message" />}
          onRetry={() => void loadOffers()}
          retryLabel={<UiText message="student.empty.marketplace.refresh" />}
          retryDisabled={loading}
          secondaryActionHref="/student/courses"
          secondaryActionLabel={<UiText message="web.navigation.student.allCourses" />}
        />
      )}

      {!loading && !loadError && offers.length > 0 && (
        <section className={styles.grid} aria-label="Available course offers">
          {offers.map((offer) => {
            const owned = offer.has_access || offer.purchased;
            const validPrice = Number.isSafeInteger(Number(offer.price_paise)) && offer.price_paise >= 0 && offer.currency === 'INR';
            return (
              <MarketplaceCourseCard
                key={offer.course_id}
                title={offer.title || offer.course_name}
                courseCode={offer.course_code}
                publicId={offer.public_id}
                curriculumVersion={offer.curriculum_version}
                priceLabel={owned ? 'Owned' : validPrice ? offer.price_paise === 0 ? 'Free' : money(offer.price_paise) : 'Unavailable'}
                description={offer.description}
                subjects={offer.subjects}
                status={offer.parent_request_status === 'PENDING' ? (
                  <p className={styles.requestStatus} role="status">
                    Parent purchase request pending{offer.parent_request_requested_at
                      ? ' · sent ' + formatUserTimestamp(offer.parent_request_requested_at) : ''}
                  </p>
                ) : null}
              >
                <div className={styles.cardFooter}>
                  {owned ? (
                    <Link className={styles.primaryButton} href={'/student/courses?focus=' + encodeURIComponent(String(offer.course_id))}>Open course</Link>
                  ) : !validPrice ? (
                    <button className={styles.disabledButton} type="button" disabled>Not available for purchase</button>
                  ) : offer.parent_managed && offer.price_paise > 0 ? (
                    <button className={offer.parent_request_status === 'PENDING' ? styles.disabledButton : styles.primaryButton} type="button"
                      disabled={busyCourseId !== null || offer.parent_request_status === 'PENDING'}
                      onClick={() => void askParentToBuy(offer.course_id)}>
                      {busyCourseId === offer.course_id ? 'Sending request…' : offer.parent_request_status === 'PENDING'
                        ? 'Parent request pending' : 'Ask parent to buy'}
                    </button>
                  ) : offer.price_paise === 0 ? (
                    <button className={styles.primaryButton} type="button" disabled={busyCourseId !== null} onClick={() => void joinFreeCourse(offer.course_id)}>
                      {busyCourseId === offer.course_id ? 'Adding…' : 'Add free course'}
                    </button>
                  ) : (
                    <button className={styles.primaryButton} type="button" disabled={busyCourseId !== null || pendingVerify !== null} onClick={() => void buyCourse(offer.course_id)}>
                      {pendingVerify ? 'Verify previous payment first' : busyCourseId === offer.course_id ? 'Opening checkout…' : 'Buy with Razorpay'}
                    </button>
                  )}
                </div>
              </MarketplaceCourseCard>
            );
          })}
        </section>
      )}
    </main>
  );
}
