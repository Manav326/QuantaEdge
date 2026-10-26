'use client';


import { apiFetch } from '../../components/apiClient';
import Link from 'next/link';
import { useCallback, useEffect, useRef, useState } from 'react';
import { useRouter } from 'next/navigation';
import styles from './marketplace.module.css';
import { tenantPrimaryColor, useTenantBranding } from '../../components/TenantBrandingProvider';
import { formatUserTimestamp } from '../../components/formatUserTimestamp';
import { UiText } from '../../i18n/useUiTranslation';
import { useLocale } from '../../components/LanguageProvider';
import { localizeMarketplaceError } from '../../components/marketplaceI18n';
import StudentEmptyState from '../components/StudentEmptyState';
import BillingProfileEditor, { BillingAddressSelection, type BillingAddressRecord } from '../../components/BillingProfileEditor';
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
  course_type_code?: string;
  test_pack_count?: number;
  assessment_count?: number;
  assessment_question_count?: number;
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

function parsePendingVerifyDrafts(raw: string | null): VerifyDraft[] {
  if (!raw) return [];
  try {
    const parsed = JSON.parse(raw);
    const values = Array.isArray(parsed) ? parsed : [parsed]; // Accept the old single-order format.
    const unique = new Map<number, VerifyDraft>();
    for (const value of values) {
      const draft: VerifyDraft = {
        courseId: Number(value?.courseId),
        localOrderId: Number(value?.localOrderId),
        paymentId: String(value?.paymentId || ''),
        orderId: String(value?.orderId || ''),
        signature: String(value?.signature || ''),
      };
      const hasPaymentCallback = draft.paymentId.length > 0 || draft.signature.length > 0;
      const callbackIsValid = !hasPaymentCallback ||
        (/^pay_[A-Za-z0-9]+$/.test(draft.paymentId) && /^[A-Fa-f0-9]{64}$/.test(draft.signature));
      if (Number.isSafeInteger(draft.courseId) && draft.courseId > 0
          && Number.isSafeInteger(draft.localOrderId) && draft.localOrderId > 0
          && /^order_[A-Za-z0-9]+$/.test(draft.orderId) && callbackIsValid) {
        unique.set(draft.localOrderId, draft);
      }
    }
    return [...unique.values()];
  } catch {
    return [];
  }
}
type CheckoutResponse = { orderId: string; localOrderId: number; amount: number; currency: string; keyId: string; description?: string; listedPricePaise?: number; discountPaise?: number; discountCode?: string | null; status?: string; entitled?: boolean; courseId?: number; providerOrderId?: string; message?: string };

declare global {
  interface Window {
    Razorpay?: new (options: Record<string, unknown>) => { open: () => void };
  }
}

async function readJson(response: Response, fallbackMessage = 'The request could not be completed.'): Promise<any> {
  const body = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(body.message || body.detail || body.error || fallbackMessage);
  return body;
}

async function ensureRazorpayCheckout(english: boolean): Promise<void> {
  if (window.Razorpay) return;
  const existing = document.querySelector<HTMLScriptElement>('script[data-qe-razorpay="true"]');
  if (existing) {
    await new Promise<void>((resolve, reject) => {
      existing.addEventListener('load', () => resolve(), { once: true });
      existing.addEventListener('error', () => reject(new Error(english ? 'Payment checkout could not be loaded.' : 'भुगतान checkout लोड नहीं हो सका।')), { once: true });
    });
    if (window.Razorpay) return;
    throw new Error(english ? 'Payment checkout could not be loaded. Please retry.' : 'भुगतान checkout लोड नहीं हो सका। फिर प्रयास करें।');
  }
  await new Promise<void>((resolve, reject) => {
    const script = document.createElement('script');
    script.src = 'https://checkout.razorpay.com/v1/checkout.js';
    script.async = true;
    script.dataset.qeRazorpay = 'true';
    script.onload = () => resolve();
    script.onerror = () => reject(new Error(english ? 'Payment checkout could not be loaded. Please retry.' : 'भुगतान checkout लोड नहीं हो सका। फिर प्रयास करें।'));
    document.head.appendChild(script);
  });
  if (!window.Razorpay) throw new Error(english ? 'Payment checkout could not be loaded. Please retry.' : 'भुगतान checkout लोड नहीं हो सका। फिर प्रयास करें।');
}

export default function StudentMarketplacePage() {
  const router = useRouter();
  const { brand, institutionContextRequested, isResolving } = useTenantBranding();
  const { locale } = useLocale();
  const english = locale === 'english';
  const language = english ? 'en' : 'hi';
  const tx = useCallback((en: string, hi: string, values: Record<string, string | number> = {}) =>
    (english ? en : hi).replace(/\{([a-zA-Z0-9_]+)\}/g, (_match, key: string) => String(values[key] ?? '')), [english]);
  const [offers, setOffers] = useState<Offer[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState('');
  const [busyCourseId, setBusyCourseId] = useState<number | null>(null);
  const [discountCode, setDiscountCode] = useState('');
  const [status, setStatus] = useState('');
  const [pendingVerifies, setPendingVerifies] = useState<VerifyDraft[]>([]);
  const pendingVerifiesRef = useRef<VerifyDraft[]>([]);
  const readPendingVerifies = useCallback((): VerifyDraft[] => {
    try {
      const raw = window.sessionStorage.getItem(PENDING_VERIFY_STORAGE_KEY);
      return raw ? parsePendingVerifyDrafts(raw) : pendingVerifiesRef.current;
    } catch {
      return pendingVerifiesRef.current;
    }
  }, []);
  const savePendingVerify = useCallback((draft: VerifyDraft) => {
    const next = [...readPendingVerifies().filter(item => item.localOrderId !== draft.localOrderId), draft];
    pendingVerifiesRef.current = next;
    setPendingVerifies(next);
    try { window.sessionStorage.setItem(PENDING_VERIFY_STORAGE_KEY, JSON.stringify(next)); } catch {
      // In-memory recovery still works if browser storage is disabled.
    }
  }, [readPendingVerifies]);
  const clearPendingVerify = useCallback((localOrderId: number) => {
    const next = readPendingVerifies().filter(item => item.localOrderId !== localOrderId);
    pendingVerifiesRef.current = next;
    setPendingVerifies(next);
    try {
      if (next.length) window.sessionStorage.setItem(PENDING_VERIFY_STORAGE_KEY, JSON.stringify(next));
      else window.sessionStorage.removeItem(PENDING_VERIFY_STORAGE_KEY);
    } catch {
      // In-memory state remains authoritative for this page.
    }
  }, [readPendingVerifies]);
  const [verifying, setVerifying] = useState(false);
  const [purchaseRequests, setPurchaseRequests] = useState<PurchaseRequestStatus[]>([]);
  const [billingAddresses, setBillingAddresses] = useState<BillingAddressRecord[]>([]);
  const [billingAddressesLoading, setBillingAddressesLoading] = useState(true);
  const [checkoutCourseId, setCheckoutCourseId] = useState<number | null>(null);
  const [selectedBillingAddressId, setSelectedBillingAddressId] = useState<number | null>(null);
  const syncBillingAddresses = useCallback((rows: BillingAddressRecord[], addressLoading: boolean) => {
    setBillingAddresses(rows);
    setBillingAddressesLoading(addressLoading);
  }, []);

  const refreshBillingAddressBook = useCallback(async () => {
    setBillingAddressesLoading(true);
    try {
      const rows = await readJson(await apiFetch('/api/v1/marketplace/billing-addresses', {
        credentials: 'same-origin', cache: 'no-store',
        headers: { 'Accept': 'application/json', 'X-QE-Workspace': 'STUDENT' },
      }));
      setBillingAddresses(Array.isArray(rows) ? rows as BillingAddressRecord[] : []);
    } catch (error: any) {
      setStatus(localizeMarketplaceError(error?.message, language,
        'Billing addresses could not be refreshed.', 'Billing addresses refresh नहीं हो सके।'));
    } finally {
      setBillingAddressesLoading(false);
    }
  }, [language]);


  const loadOffers = useCallback(async () => {
    setLoading(true);
    setLoadError('');
    try {
      const body = await readJson(await apiFetch('/api/v1/marketplace/courses', {
        credentials: 'same-origin', cache: 'no-store',
      }), tx('The request could not be completed.', 'अनुरोध पूरा नहीं हो सका।'));
      setOffers(Array.isArray(body) ? body : []);
    } catch (error: any) {
      setOffers([]);
      setLoadError(localizeMarketplaceError(error?.message, language, 'Marketplace could not be loaded.', 'Marketplace लोड नहीं हो पाया।'));
    } finally {
      setLoading(false);
    }
  }, [language, tx]);

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
      const drafts = parsePendingVerifyDrafts(saved);
      pendingVerifiesRef.current = drafts;
      setPendingVerifies(drafts);
      if (drafts.length) window.sessionStorage.setItem(PENDING_VERIFY_STORAGE_KEY, JSON.stringify(drafts));
      else window.sessionStorage.removeItem(PENDING_VERIFY_STORAGE_KEY);
    } catch {
      // Storage may be disabled by the browser; in-page retry still works.
    }
  }, []);

  const verifyPurchase = useCallback(async (draft: VerifyDraft) => {
    setVerifying(true);
    setStatus('');
    savePendingVerify(draft);
    try {
      const verification = await readJson(await apiFetch('/api/v1/marketplace/orders/' + draft.localOrderId + '/verify', {
        method: 'POST',
        credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          paymentId: draft.paymentId,
          orderId: draft.orderId,
          signature: draft.signature,
        }),
      }));
      clearPendingVerify(draft.localOrderId);
      setStatus(verification.financeReconciliationRequired === true
        ? tx('Payment confirmed and course access granted. Invoice and revenue-share finalization is still being reconciled; do not pay again.',
            'भुगतान की पुष्टि हो गई और कोर्स की पहुँच मिल गई है। इनवॉइस और revenue-share का अंतिम मिलान अभी जारी है; दोबारा भुगतान न करें।')
        : String(verification.message || '').trim() ||
          tx('Payment verified. Your course is now in All Courses.', 'भुगतान सत्यापित हो गया। आपका कोर्स All Courses में उपलब्ध है।'));
      await loadOffers();
      await loadPurchaseRequests();
      router.push('/student/courses?focus=' + encodeURIComponent(String(draft.courseId)));
    } catch (error: any) {
      savePendingVerify(draft);
      setStatus(localizeMarketplaceError(error?.message, language, 'Payment verification could not be completed. Retry verification before paying again.', 'भुगतान की पुष्टि पूरी नहीं हो सकी। दोबारा भुगतान करने से पहले इसी order की पुष्टि फिर करें।'));
    } finally {
      setVerifying(false);
      setBusyCourseId(null);
    }
  }, [loadOffers, loadPurchaseRequests, router, english, savePendingVerify, clearPendingVerify]);

  const reconcilePurchase = useCallback(async (draft: VerifyDraft) => {
    setVerifying(true);
    setStatus('');
    savePendingVerify(draft);
    try {
      const result = await readJson(await apiFetch('/api/v1/marketplace/orders/' + draft.localOrderId + '/reconcile', {
        method: 'POST', credentials: 'same-origin', headers: { 'Content-Type': 'application/json' }, body: '{}',
      }));
      if (String(result.status || '').toUpperCase() === 'VERIFIED' || result.entitled === true) {
        clearPendingVerify(draft.localOrderId);
        setStatus(result.financeReconciliationRequired === true
          ? tx('Payment confirmed and course access granted. Invoice and revenue-share finalization is still being reconciled; do not pay again.',
              'भुगतान की पुष्टि हो गई और कोर्स की पहुँच मिल गई है। इनवॉइस और revenue-share का अंतिम मिलान अभी जारी है; दोबारा भुगतान न करें।')
          : String(result.message || '').trim() ||
            tx('Payment verified. Your course is now in All Courses.', 'भुगतान सत्यापित हो गया। आपका कोर्स All Courses में उपलब्ध है।'));
        await loadOffers();
        await loadPurchaseRequests();
        router.push('/student/courses?focus=' + encodeURIComponent(String(draft.courseId)));
        return;
      }
      if (result.canRetryCheckout === true) {
        clearPendingVerify(draft.localOrderId);
      }
      setStatus(localizeMarketplaceError(result.message, language, 'Payment status is still being reconciled. Do not pay again while verification is pending.', 'भुगतान की जाँच अभी जारी है। पुष्टि पूरी होने तक दोबारा भुगतान न करें।'));
      await loadOffers();
    } catch (error: any) {
      savePendingVerify(draft);
      setStatus(localizeMarketplaceError(error?.message, language, 'Payment status could not be reconciled. Keep this order and retry verification; do not pay again.', 'भुगतान की स्थिति की पुष्टि नहीं हो सकी। यही order सुरक्षित रखें और फिर जाँचें; दोबारा भुगतान न करें।'));
    } finally {
      setVerifying(false);
      setBusyCourseId(null);
    }
  }, [loadOffers, loadPurchaseRequests, router, english, savePendingVerify, clearPendingVerify]);

  function requestCheckout(courseId: number) {
    if (billingAddressesLoading) {
      setStatus(tx('Billing addresses are still loading. Please retry in a moment.', 'Billing addresses अभी load हो रहे हैं। थोड़ी देर बाद फिर कोशिश करें।'));
      return;
    }
    if (billingAddresses.length === 0) {
      setStatus(tx('Add a complete billing address in My Profile before checkout.', 'Checkout से पहले My Profile में पूरा billing address जोड़ें।'));
      return;
    }
    if (billingAddresses.length === 1) {
      const address = billingAddresses[0].billingAddress || {};
      if (!address.line1?.trim() || !address.city?.trim() || !address.state?.trim() || !address.postalCode?.trim()) {
        setStatus(tx('Complete your billing address in My Profile before checkout.', 'Checkout से पहले My Profile में billing address पूरा करें।'));
        return;
      }
      void buyCourse(courseId, billingAddresses[0].id);
      return;
    }
    setCheckoutCourseId(courseId);
    setSelectedBillingAddressId((billingAddresses.find(address => address.is_default) || billingAddresses[0]).id);
  }

  async function buyCourse(courseId: number, billingAddressId: number) {
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
        body: JSON.stringify({ discountCode: discountCode.trim() || null, billingAddressId }),
      })) as CheckoutResponse;
      const orderStatus = String(order.status || '').toUpperCase();
      const recoveredCourseId = Number(order.courseId) > 0 ? Number(order.courseId) : courseId;
      if (order.entitled === true || orderStatus === 'VERIFIED' || orderStatus === 'PAYMENT_VERIFIED_ACCESS_GRANTED') {
        const recoveredLocalOrderId = Number(order.localOrderId ?? order.orderId);
        if (Number.isSafeInteger(recoveredLocalOrderId) && recoveredLocalOrderId > 0) {
          clearPendingVerify(recoveredLocalOrderId);
        }
        setStatus(order.financeReconciliationRequired === true
          ? tx('Payment confirmed and course access granted. Invoice and revenue-share finalization is still being reconciled; do not pay again.',
              'भुगतान की पुष्टि हो गई और कोर्स की पहुँच मिल गई है। इनवॉइस और revenue-share का अंतिम मिलान अभी जारी है; दोबारा भुगतान न करें।')
          : String(order.message || '').trim() ||
            tx('The existing payment was reconciled. Your course is now available in All Courses.', 'मौजूदा भुगतान सत्यापित हो गया। आपका कोर्स All Courses में उपलब्ध है।'));
        await loadOffers();
        await loadPurchaseRequests();
        router.push('/student/courses?focus=' + encodeURIComponent(String(recoveredCourseId)));
        return;
      }
      if (orderStatus === 'RECONCILIATION_REQUIRED') {
        const recoveredLocalOrderId = Number(order.localOrderId);
        const savedProviderOrderId = String(order.providerOrderId || '');
        if (Number.isSafeInteger(recoveredLocalOrderId) && recoveredLocalOrderId > 0 &&
            /^order_[A-Za-z0-9]+$/.test(savedProviderOrderId)) {
          savePendingVerify({
            courseId: recoveredCourseId,
            localOrderId: recoveredLocalOrderId,
            paymentId: '',
            orderId: savedProviderOrderId,
            signature: '',
          });
        }
        setStatus(localizeMarketplaceError(order.message, language,
          'The original order is still being reconciled. Retry verification for that order; do not pay again.',
          'पुराने order की जाँच अभी जारी है। उसी order को फिर सत्यापित करें; दोबारा भुगतान न करें।'));
        return;
      }
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
      savePendingVerify(recoverableDraft);
      await ensureRazorpayCheckout(english);
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
            savePendingVerify(recoverableDraft);
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
      setStatus(localizeMarketplaceError(error?.message, language, 'Checkout could not be started. Please retry.', 'Checkout शुरू नहीं हो सका। फिर प्रयास करें।'));
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
      setStatus(localizeMarketplaceError(error?.message, language, 'The purchase request could not be sent. Please retry.', 'खरीदने का अनुरोध नहीं भेजा जा सका। फिर प्रयास करें।'));
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
      setStatus(localizeMarketplaceError(error?.message, language, 'The course could not be added. Please retry.', 'कोर्स नहीं जोड़ा जा सका। फिर प्रयास करें।'));
    } finally {
      setBusyCourseId(null);
    }
  }

  const money = (paise: number) => new Intl.NumberFormat(english ? 'en-IN' : 'hi-IN', {
    style: 'currency', currency: 'INR', maximumFractionDigits: 2,
  }).format(paise / 100);

  return (
    <main className={styles.page} lang={language}>
      <header className={styles.hero}>
        <div className={styles.eyebrow}>{tx('LEARN SOMETHING NEW', 'कुछ नया सीखें')}</div>
        <h1>{tx('Course Marketplace', 'कोर्स मार्केटप्लेस')}</h1>
        <p>{tx('Choose a learning path that fits your goals. Your course price and access are confirmed securely before checkout.', 'अपने लक्ष्य के अनुसार कोर्स चुनें। भुगतान से पहले कीमत और पहुँच सुरक्षित रूप से सत्यापित होती है।')}</p>
        <div className={styles.heroActions}>
          <Link className={styles.secondaryButton} href="/student/courses">{tx('All Courses', 'सभी कोर्स')}</Link>
          <button className={styles.iconButton} type="button" onClick={() => void loadOffers()} disabled={loading} aria-label={tx("Reload course offers", "कोर्स ऑफर फिर लोड करें")} title={tx("Reload course offers", "कोर्स ऑफर फिर लोड करें")}>↻</button>
        </div>
      </header>

      {status && <p className={styles.status} role="status">{status}</p>}
      <section className={styles.discountCodeBox} aria-label={tx("Course offer code", "कोर्स ऑफर कोड")}>
        <label htmlFor="qe-student-discount-code">{tx('Offer code', 'ऑफर कोड')} <span>({tx('optional', 'वैकल्पिक')})</span></label>
        <div className={styles.discountCodeEntry}>
          <input id="qe-student-discount-code" autoComplete="off" maxLength={40} value={discountCode}
            onChange={event=>setDiscountCode(event.target.value.toUpperCase())}
            placeholder={tx("Enter a code supplied by your institution", "अपनी संस्था द्वारा दिया गया कोड डालें")} disabled={busyCourseId!==null}/>
          <span>{tx('Validated securely when you start checkout', 'Checkout शुरू करते समय सुरक्षित रूप से जाँचा जाएगा')}</span>
        </div>
        <p>{tx('Offer codes apply only to the course and institution for which HRMS approved them. The payable amount and tax calculation come from the server.', 'Offer code केवल उसी course और institution पर लागू होगा जिसे HRMS ने मंज़ूरी दी है। भुगतान और tax की गणना server पर होती है।')}</p>
      </section>
      {offers.some(offer => Number(offer.price_paise) > 0 && offer.currency === 'INR' &&
        !offer.parent_managed && !offer.has_access && !offer.purchased) &&
        <BillingProfileEditor workspace="STUDENT" placement="marketplace" onAddressesChange={syncBillingAddresses} />}
      <InvoiceDownloads workspace="STUDENT" english={english} />
      {checkoutCourseId !== null && (
        <BillingAddressSelection
          addresses={billingAddresses}
          selectedAddressId={selectedBillingAddressId}
          onSelect={setSelectedBillingAddressId}
          profileHref="/student/profile"
          onRefreshAddresses={() => { void refreshBillingAddressBook(); }}
          onCancel={() => setCheckoutCourseId(null)}
          onConfirm={() => {
            const courseId = checkoutCourseId;
            const addressId = selectedBillingAddressId;
            setCheckoutCourseId(null);
            if (addressId !== null) void buyCourse(courseId, addressId);
          }}
        />
      )}
      {purchaseRequests.length > 0 && (
        <section className={styles.requestHistory} aria-labelledby="qe-purchase-request-history">
          <div className={styles.requestHistoryHeading}>
            <div><h2 id="qe-purchase-request-history">{tx('Parent purchase requests', 'Parent से खरीद अनुरोध')}</h2>
              <p>{tx('Check when you asked and whether the request is still pending.', 'देखें अनुरोध कब भेजा गया था और क्या वह अभी लंबित है।')}</p></div>
            <button type="button" disabled={busyCourseId !== null} onClick={() => void loadPurchaseRequests()}>{tx('Refresh', 'रिफ्रेश करें')}</button>
          </div>
          <div className={styles.requestHistoryList}>
            {purchaseRequests.map(request => {
              const status = String(request.status || '').toUpperCase();
              const label = status === 'PENDING' ? tx('Waiting for parent', 'Parent की प्रतीक्षा में')
                : status === 'PURCHASED' ? tx('Course purchased', 'कोर्स खरीदा गया')
                : status === 'DISMISSED' ? tx('Request dismissed', 'अनुरोध खारिज किया गया') : status.replaceAll('_', ' ').toLowerCase();
              return <article className={styles.requestHistoryRow} key={request.request_id}>
                <div><strong>{request.course_name || tx('Course', 'कोर्स')}</strong>
                  <span>{tx('Request #{id} · Submitted {time}', 'अनुरोध #{id} · भेजने का समय {time}', {id:request.request_id,time:formatUserTimestamp(request.requested_at, language)})}</span></div>
                <div className={styles.requestHistoryState}>
                  <span className={status === 'PENDING' ? styles.requestPending : status === 'PURCHASED' ? styles.requestComplete : styles.requestOther}>{label}</span>
                  {request.resolved_at && <small>{tx('Updated {time}', '{time} पर अपडेट हुआ', {time:formatUserTimestamp(request.resolved_at, language)})}</small>}
                </div>
              </article>;
            })}
          </div>
        </section>
      )}
      {pendingVerifies.map(draft => (
        <section className={styles.verifyCard} key={draft.localOrderId} aria-label={tx("Retry payment verification", "भुगतान की पुष्टि फिर करें")}>
          <div><strong>{tx('Payment verification pending — course #{id}', 'भुगतान की पुष्टि बाकी — कोर्स #{id}', {id:draft.courseId})}</strong>
            <p>{tx('This saved order can be reconciled independently. Other courses remain available to purchase.', 'इस सुरक्षित order की अलग से जाँच हो सकती है। अन्य कोर्स खरीदने के लिए उपलब्ध हैं।')}</p></div>
          <button type="button" disabled={verifying} onClick={() => void reconcilePurchase(draft)}>
            {verifying ? tx('Verifying…', 'जाँच हो रही है…') : tx('Retry verification', 'फिर सत्यापित करें')}
          </button>
        </section>
      ))}

      {loading && <div className={styles.stateCard} role="status">{tx('Loading course offers…', 'कोर्स ऑफर लोड हो रहे हैं…')}</div>}
      {!loading && loadError && (
        <div className={styles.stateCard}>
          <h2>{tx('Marketplace could not be loaded', 'Marketplace लोड नहीं हो पाया')}</h2>
          <p>{loadError}</p>
          <button type="button" onClick={() => void loadOffers()}>{tx('Try again', 'फिर प्रयास करें')}</button>
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
        <section className={styles.grid} aria-label={tx("Available course offers", "उपलब्ध कोर्स ऑफर")}>
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
                courseTypeCode={offer.course_type_code}
                testPackCount={offer.test_pack_count}
                assessmentCount={offer.assessment_count}
                assessmentQuestionCount={offer.assessment_question_count}
                language={language}
                priceLabel={owned ? tx('Owned', 'पहले से उपलब्ध') : validPrice ? offer.price_paise === 0 ? tx('Free', 'निःशुल्क') : money(offer.price_paise) : tx('Unavailable', 'उपलब्ध नहीं')}
                description={offer.description}
                subjects={offer.subjects}
                status={offer.parent_request_status === 'PENDING' ? (
                  <p className={styles.requestStatus} role="status">
                    {tx('Parent purchase request pending', 'Parent से खरीद का अनुरोध लंबित है')}{offer.parent_request_requested_at
                      ? ' · ' + tx('sent', 'भेजा गया') + ' ' + formatUserTimestamp(offer.parent_request_requested_at, language) : ''}
                  </p>
                ) : null}
              >
                <div className={styles.cardFooter}>
                  {owned ? (
                    <Link className={styles.primaryButton} href={'/student/courses?focus=' + encodeURIComponent(String(offer.course_id))}>{tx('Open course', 'कोर्स खोलें')}</Link>
                  ) : !validPrice ? (
                    <button className={styles.disabledButton} type="button" disabled>{tx('Not available for purchase', 'खरीद के लिए उपलब्ध नहीं')}</button>
                  ) : offer.parent_managed && offer.price_paise > 0 ? (
                    <button className={offer.parent_request_status === 'PENDING' ? styles.disabledButton : styles.primaryButton} type="button"
                      disabled={busyCourseId !== null || offer.parent_request_status === 'PENDING'}
                      onClick={() => void askParentToBuy(offer.course_id)}>
                      {busyCourseId === offer.course_id ? tx('Sending request…', 'अनुरोध भेजा जा रहा है…') : offer.parent_request_status === 'PENDING'
                        ? tx('Parent request pending', 'Parent का अनुरोध लंबित है') : tx('Ask parent to buy', 'Parent से खरीदने को कहें')}
                    </button>
                  ) : offer.price_paise === 0 ? (
                    <button className={styles.primaryButton} type="button" disabled={busyCourseId !== null} onClick={() => void joinFreeCourse(offer.course_id)}>
                      {busyCourseId === offer.course_id ? tx('Adding…', 'जोड़ा जा रहा है…') : tx('Add free course', 'निःशुल्क कोर्स जोड़ें')}
                    </button>
                  ) : (
                    <button className={styles.primaryButton} type="button" disabled={busyCourseId !== null} onClick={() => requestCheckout(offer.course_id)}>
                      {busyCourseId === offer.course_id ? tx('Opening checkout…', 'Checkout खुल रहा है…') : tx('Buy course', 'कोर्स खरीदें')}
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
