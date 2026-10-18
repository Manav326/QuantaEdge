'use client';

import Link from 'next/link';
import { useCallback, useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import styles from './marketplace.module.css';
import { useTenantBranding } from '../../components/TenantBrandingProvider';

type Subject = { subject_code?: string; subject_name?: string; chapters?: number; lessons?: number };
type Offer = {
  course_id: number;
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
  subjects?: Subject[];
};
type VerifyDraft = { courseId: number; localOrderId: number; paymentId: string; orderId: string; signature: string };
type CheckoutResponse = { orderId: string; localOrderId: number; amount: number; currency: string; keyId: string; description?: string };

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
  const [offers, setOffers] = useState<Offer[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState('');
  const [busyCourseId, setBusyCourseId] = useState<number | null>(null);
  const [status, setStatus] = useState('');
  const [pendingVerify, setPendingVerify] = useState<VerifyDraft | null>(null);
  const [verifying, setVerifying] = useState(false);

  const loadOffers = useCallback(async () => {
    setLoading(true);
    setLoadError('');
    try {
      const body = await readJson(await fetch('/api/v1/marketplace/courses', {
        credentials: 'same-origin', cache: 'no-store',
      }));
      setOffers(Array.isArray(body) ? body : []);
    } catch (error: any) {
      setOffers([]);
      setLoadError(error?.message || 'Marketplace could not be loaded.');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { void loadOffers(); }, [loadOffers]);

  const verifyPurchase = useCallback(async (draft: VerifyDraft) => {
    setVerifying(true);
    setStatus('');
    try {
      await readJson(await fetch('/api/v1/marketplace/orders/' + draft.localOrderId + '/verify', {
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
      setStatus('Payment verified. Your course is now in All Courses.');
      await loadOffers();
      router.push('/student/courses?focus=' + encodeURIComponent(String(draft.courseId)));
    } catch (error: any) {
      setPendingVerify(draft);
      setStatus(error?.message || 'Payment verification could not be completed. Retry verification before paying again.');
    } finally {
      setVerifying(false);
      setBusyCourseId(null);
    }
  }, [loadOffers, router]);

  async function buyCourse(courseId: number) {
    if (!Number.isSafeInteger(courseId) || courseId <= 0 || busyCourseId !== null) return;
    if (institutionContextRequested && (!brand || isResolving)) {
      setStatus('We could not verify your school identity. Reopen the official link shared by your school before starting checkout.');
      return;
    }
    setBusyCourseId(courseId);
    setStatus('');
    try {
      const order = await readJson(await fetch('/api/v1/marketplace/courses/' + courseId + '/orders', {
        method: 'POST',
        credentials: 'same-origin',
        headers: {
          'Content-Type': 'application/json',
          'Idempotency-Key': crypto.randomUUID(),
        },
        body: '{}',
      })) as CheckoutResponse;
      if (!order.orderId || !Number.isSafeInteger(Number(order.localOrderId)) || Number(order.localOrderId) <= 0 ||
          !order.keyId || !Number.isFinite(Number(order.amount)) || Number(order.amount) <= 0 || order.currency !== 'INR') {
        throw new Error('The course order could not be prepared. Please retry.');
      }
      await ensureRazorpayCheckout();
      const RazorpayCtor = window.Razorpay;
      if (!RazorpayCtor) throw new Error('Payment checkout is not available. Please retry.');
      const checkout = new RazorpayCtor({
        key: order.keyId,
        amount: Number(order.amount),
        currency: order.currency,
        name: brand?.displayName || 'QuantaEdge',
        description: order.description || 'Course access',
        order_id: order.orderId,
        theme: { color: '#E87524' },
        handler: (response: any) => {
          const draft: VerifyDraft = {
            courseId,
            localOrderId: Number(order.localOrderId),
            paymentId: String(response.razorpay_payment_id || ''),
            orderId: String(response.razorpay_order_id || ''),
            signature: String(response.razorpay_signature || ''),
          };
          setPendingVerify(draft);
          if (!draft.paymentId || !draft.orderId || !draft.signature || draft.orderId !== order.orderId) {
            setStatus('The payment confirmation did not match this order. Retry verification or contact support if money was deducted.');
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
      setStatus(error?.message || 'Checkout could not be started. Please retry.');
    }
  }

  async function askParentToBuy(courseId: number) {
    if (!Number.isSafeInteger(courseId) || courseId <= 0 || busyCourseId !== null) return;
    setBusyCourseId(courseId);
    setStatus('');
    try {
      await readJson(await fetch('/api/v1/marketplace/courses/' + courseId + '/purchase-request', {
        method: 'POST',
        credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json' },
        body: '{}',
      }));
      setStatus('Purchase request sent. Your parent can review it from the family account and buy this course for you.');
    } catch (error: any) {
      setStatus(error?.message || 'The purchase request could not be sent. Please retry.');
    } finally {
      setBusyCourseId(null);
    }
  }

  async function joinFreeCourse(courseId: number) {
    if (!Number.isSafeInteger(courseId) || courseId <= 0 || busyCourseId !== null) return;
    setBusyCourseId(courseId);
    setStatus('');
    try {
      await readJson(await fetch('/api/v1/students/me/programs/id/' + courseId + '/enroll', {
        method: 'POST', credentials: 'same-origin', headers: { 'Content-Type': 'application/json' }, body: '{}',
      }));
      setStatus('Free course added to All Courses.');
      router.push('/student/courses?focus=' + encodeURIComponent(String(courseId)));
    } catch (error: any) {
      setStatus(error?.message || 'The course could not be added. Please retry.');
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
      {pendingVerify && (
        <section className={styles.verifyCard} aria-label="Retry payment verification">
          <div><strong>Complete course access</strong><p>The checkout response is saved for retry. Verify this payment before starting another checkout.</p></div>
          <button type="button" disabled={verifying} onClick={() => void verifyPurchase(pendingVerify)}>
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
        <div className={styles.stateCard}>
          <h2>No published course offers yet</h2>
          <p>Published courses will appear here when they are made available for purchase.</p>
        </div>
      )}

      {!loading && !loadError && offers.length > 0 && (
        <section className={styles.grid} aria-label="Available course offers">
          {offers.map((offer) => {
            const owned = offer.has_access || offer.purchased;
            const validPrice = Number.isSafeInteger(Number(offer.price_paise)) && offer.price_paise >= 0 && offer.currency === 'INR';
            return (
              <article className={styles.courseCard} key={offer.course_id}>
                <div className={styles.cardTop}>
                  <div className={styles.courseMark} aria-hidden="true">✦</div>
                  <div className={styles.price}>{owned ? 'Owned' : validPrice ? offer.price_paise === 0 ? 'Free' : money(offer.price_paise) : 'Unavailable'}</div>
                </div>
                <h2>{offer.title || offer.course_name}</h2>
                <p className={styles.meta}>{offer.curriculum_version || offer.course_code}</p>
                {offer.description && <p className={styles.description}>{offer.description}</p>}
                {!!offer.subjects?.length && (
                  <div className={styles.subjects}>
                    {offer.subjects.map((subject) => <span key={subject.subject_code}>{subject.subject_name || subject.subject_code}</span>)}
                  </div>
                )}
                <div className={styles.cardFooter}>
                  {owned ? (
                    <Link className={styles.primaryButton} href={'/student/courses?focus=' + encodeURIComponent(String(offer.course_id))}>Open course</Link>
                  ) : !validPrice ? (
                    <button className={styles.disabledButton} type="button" disabled>Not available for purchase</button>
                  ) : offer.parent_managed && offer.price_paise > 0 ? (
                    <button className={styles.primaryButton} type="button" disabled={busyCourseId !== null}
                      onClick={() => void askParentToBuy(offer.course_id)}>
                      {busyCourseId === offer.course_id ? 'Sending request…' : 'Ask parent to buy'}
                    </button>
                  ) : offer.price_paise === 0 ? (
                    <button className={styles.primaryButton} type="button" disabled={busyCourseId !== null} onClick={() => void joinFreeCourse(offer.course_id)}>
                      {busyCourseId === offer.course_id ? 'Adding…' : 'Add free course'}
                    </button>
                  ) : (
                    <button className={styles.primaryButton} type="button" disabled={busyCourseId !== null} onClick={() => void buyCourse(offer.course_id)}>
                      {busyCourseId === offer.course_id ? 'Opening checkout…' : 'Buy with Razorpay'}
                    </button>
                  )}
                </div>
              </article>
            );
          })}
        </section>
      )}
    </main>
  );
}
