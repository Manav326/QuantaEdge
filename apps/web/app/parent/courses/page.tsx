'use client';


import { apiFetch } from '../../components/apiClient';
import Link from 'next/link';
import { useCallback, useEffect, useRef, useState } from 'react';
import { useRouter } from 'next/navigation';
import { LocaleText, useLocale } from '../../components/LanguageProvider';
import { tenantPrimaryColor, useTenantBranding } from '../../components/TenantBrandingProvider';
import BillingProfileEditor, { BillingAddressSelection, type BillingAddressRecord } from '../../components/BillingProfileEditor';
import InvoiceDownloads from '../../components/InvoiceDownloads';
import MarketplaceCourseCard from '../../components/MarketplaceCourseCard';
import { localizeMarketplaceError } from '../../components/marketplaceI18n';
import { formatUserTimestamp } from '../../components/formatUserTimestamp';

type Child = { id: number; display_name: string; class_code?: string; board?: string };
type Course = {
  course_id: number; public_id?: string; title: string; course_name: string; course_code: string;
  curriculum_version?: string; course_type_code?: string; test_pack_count?: number;
  assessment_count?: number; assessment_question_count?: number;
  description?: string | null; price_paise: number; currency: string;
  owned_by_parent?: boolean; owned_by_child?: boolean; owned_child_ids?: string | null;
  transferable_by_parent?: boolean;
  subjects?: Array<{ subject_code?: string; subject_name?: string; chapters?: number; lessons?: number }>;
};
type PurchaseRequest = {
  request_id: number; student_id: number; course_id: number; status: string;
  student_name: string; course_name: string; price_paise: number; currency: string;
  requested_at?: string;
};
type Entitlement = {
  entitlement_id: number; course_id: number; course_name: string; current_learner_name: string;
  holder_student_id: number; transfer_count: number;
};
type VerifyDraft = { courseId: number; localOrderId: number; paymentId: string; orderId: string; signature: string };
type PaidCheckoutIntent = { courseId: number; recipientType: 'SELF' | 'CHILD'; studentId?: number };
const PENDING_VERIFY_STORAGE_KEY = 'qe:marketplace:pending-payment:parent';

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

export default function ParentCoursesPage() {
  const router = useRouter();
  const { brand } = useTenantBranding();
  const { locale } = useLocale();
  const english = locale === 'english';
  const language = english ? 'en' : 'hi';
  const [courses, setCourses] = useState<Course[]>([]);
  const [discountCode, setDiscountCode] = useState('');
  const [personalLearningAvailable, setPersonalLearningAvailable] = useState(false);
  const [children, setChildren] = useState<Child[]>([]);
  const [requests, setRequests] = useState<PurchaseRequest[]>([]);
  const [entitlements, setEntitlements] = useState<Entitlement[]>([]);
  const [childByCourse, setChildByCourse] = useState<Record<number, number>>({});
  const [transferTo, setTransferTo] = useState<Record<number, number>>({});
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState('');
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
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
  const [billingAddresses, setBillingAddresses] = useState<BillingAddressRecord[]>([]);
  const [billingAddressesLoading, setBillingAddressesLoading] = useState(true);
  const [checkoutIntent, setCheckoutIntent] = useState<PaidCheckoutIntent | null>(null);
  const [selectedBillingAddressId, setSelectedBillingAddressId] = useState<number | null>(null);
  const syncBillingAddresses = useCallback((rows: BillingAddressRecord[], addressLoading: boolean) => {
    setBillingAddresses(rows);
    setBillingAddressesLoading(addressLoading);
  }, []);

  const refreshBillingAddressBook = useCallback(async () => {
    setBillingAddressesLoading(true);
    try {
      const rows = await readJson(await apiFetch('/api/v1/marketplace/family/billing-addresses', {
        credentials: 'same-origin', cache: 'no-store',
        headers: { 'Accept': 'application/json', 'X-QE-Workspace': 'PARENT' },
      }));
      setBillingAddresses(Array.isArray(rows) ? rows as BillingAddressRecord[] : []);
    } catch (error: any) {
      setError(localizeMarketplaceError(error?.message, language,
        'Billing addresses could not be refreshed.', 'Billing addresses refresh नहीं हो सके।'));
    } finally {
      setBillingAddressesLoading(false);
    }
  }, [language]);


  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const session = await readJson(await apiFetch('/api/v1/auth/me', { credentials: 'same-origin', cache: 'no-store' }));
      if (session.role !== 'PARENT' || session.workspace !== 'PARENT' || Number(session.userId) <= 0) {
        router.replace('/login');
        return;
      }
      const personalAccessCheck = apiFetch('/api/v1/auth/my-learning-access', {
        credentials: 'same-origin', cache: 'no-store',
      }).then(readJson).then(body => setPersonalLearningAvailable(body.available === true))
        .catch(() => setPersonalLearningAvailable(false));
      const responses = await Promise.all([
        apiFetch('/api/v1/marketplace/family/courses', { credentials: 'same-origin', cache: 'no-store' }),
        apiFetch('/api/v1/guardians/children', { credentials: 'same-origin', cache: 'no-store' }),
        apiFetch('/api/v1/parent/course-requests', { credentials: 'same-origin', cache: 'no-store' }),
        apiFetch('/api/v1/marketplace/family/entitlements', { credentials: 'same-origin', cache: 'no-store' }),
      ]);
      const results = await Promise.all(responses.map(response => readJson(response)));
      await personalAccessCheck;
      setCourses(Array.isArray(results[0]) ? results[0] : []);
      window.dispatchEvent(new Event('qe:parent-courses-updated'));
      setChildren(Array.isArray(results[1]) ? results[1] : []);
      setRequests(Array.isArray(results[2]) ? results[2] : []);
      setEntitlements(Array.isArray(results[3]) ? results[3] : []);
    } catch (e) {
      setError(localizeMarketplaceError(e, language, 'Family courses could not be loaded.', 'परिवार के कोर्स लोड नहीं हो सके।'));
    } finally {
      setLoading(false);
    }
  }, [router, language]);

  useEffect(() => { void load(); }, [load]);

  // Retain the exact provider callback for same-order recovery after a refresh.
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
      // Keep the checkout usable if browser session storage is unavailable.
    }
  }, []);

  const money = (paise: number) => new Intl.NumberFormat(english ? 'en-IN' : 'hi-IN', {
    style: 'currency', currency: 'INR', maximumFractionDigits: 2,
  }).format(paise / 100);

  async function verifyPayment(draft: VerifyDraft) {
    setBusy('verify');
    setError('');
    setNotice('');
    savePendingVerify(draft);
    try {
      const verification = await readJson(await apiFetch('/api/v1/marketplace/family/orders/' + draft.localOrderId + '/verify', {
        method: 'POST', credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ paymentId: draft.paymentId, orderId: draft.orderId, signature: draft.signature }),
      }));
      clearPendingVerify(draft.localOrderId);
      setNotice(verification.financeReconciliationRequired === true
        ? english
          ? 'Payment confirmed and course access granted. Invoice and revenue-share finalization is still being reconciled; do not pay again.'
          : 'भुगतान की पुष्टि हो गई और कोर्स की पहुँच मिल गई है। इनवॉइस और revenue-share का अंतिम मिलान अभी जारी है; दोबारा भुगतान न करें।'
        : String(verification.message || '').trim() || (english
          ? 'Payment verified. Course access has been updated.'
          : 'भुगतान सत्यापित। कोर्स की पहुँच अपडेट हो गई है।'));
      await load();
    } catch (e) {
      setError(localizeMarketplaceError(e, language, 'Payment verification could not be completed. Retry verification before paying again.', 'भुगतान की पुष्टि पूरी नहीं हो सकी। दोबारा भुगतान करने से पहले इसी order की पुष्टि फिर करें।'));
    } finally { setBusy(''); }
  }

  async function reconcilePayment(draft: VerifyDraft) {
    setBusy('verify');
    setError('');
    setNotice('');
    savePendingVerify(draft);
    try {
      const result = await readJson(await apiFetch('/api/v1/marketplace/family/orders/' + draft.localOrderId + '/reconcile', {
        method: 'POST', credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json' }, body: '{}',
      }));
      if (String(result.status || '').toUpperCase() === 'VERIFIED' || result.entitled === true) {
        clearPendingVerify(draft.localOrderId);
        setNotice(result.financeReconciliationRequired === true
          ? english
            ? 'Payment confirmed and course access granted. Invoice and revenue-share finalization is still being reconciled; do not pay again.'
            : 'भुगतान की पुष्टि हो गई और कोर्स की पहुँच मिल गई है। इनवॉइस और revenue-share का अंतिम मिलान अभी जारी है; दोबारा भुगतान न करें।'
          : String(result.message || '').trim() || (english
            ? 'Payment verified. Course access has been updated.'
            : 'भुगतान सत्यापित। कोर्स की पहुँच अपडेट हो गई है।'));
        await load();
        return;
      }
      if (result.canRetryCheckout === true) {
        clearPendingVerify(draft.localOrderId);
      }
      setError(localizeMarketplaceError(result.message, language, 'Payment status is still being reconciled. Do not pay again while verification is pending.', 'भुगतान की जाँच जारी है। पुष्टि पूरी होने तक दोबारा भुगतान न करें।'));
      await load();
    } catch (e) {
      savePendingVerify(draft);
      setError(localizeMarketplaceError(e, language, 'Payment status could not be reconciled. Keep this order and retry verification; do not pay again.', 'भुगतान की स्थिति की पुष्टि नहीं हो सकी। यही order सुरक्षित रखें और फिर जाँचें; दोबारा भुगतान न करें।'));
    } finally {
      setBusy('');
    }
  }

  function requestPaidCheckout(courseId: number, recipientType: 'SELF' | 'CHILD', studentId?: number) {
    if (billingAddressesLoading) {
      setError(english ? 'Billing addresses are still loading. Please retry in a moment.' : 'Billing addresses अभी load हो रहे हैं। थोड़ी देर बाद फिर कोशिश करें।');
      return;
    }
    if (billingAddresses.length === 0) {
      setError(english ? 'Add a complete billing address in My Profile before checkout.' : 'Checkout से पहले My Profile में पूरा billing address जोड़ें।');
      return;
    }
    if (billingAddresses.length === 1) {
      const address = billingAddresses[0].billingAddress || {};
      if (!address.line1?.trim() || !address.city?.trim() || !address.state?.trim() || !address.postalCode?.trim()) {
        setError(english ? 'Complete your billing address in My Profile before checkout.' : 'Checkout से पहले My Profile में billing address पूरा करें।');
        return;
      }
      void startPaidCheckout(courseId, recipientType, studentId, billingAddresses[0].id);
      return;
    }
    setCheckoutIntent({ courseId, recipientType, studentId });
    setSelectedBillingAddressId((billingAddresses.find(address => address.is_default) || billingAddresses[0]).id);
  }

  async function startPaidCheckout(courseId: number, recipientType: 'SELF' | 'CHILD', studentId: number | undefined, billingAddressId: number) {
    if (!Number.isSafeInteger(courseId) || courseId <= 0 || busy) return;
    if (recipientType === 'CHILD' && (!studentId || studentId <= 0)) {
      setError(english ? 'Choose a child first.' : 'पहले बच्चे का चयन करें।');
      return;
    }
    setBusy('checkout-' + courseId + '-' + recipientType + '-' + (recipientType === 'CHILD' ? String(studentId) : 'self'));
    setError(''); setNotice('');
    try {
      const body = await readJson(await apiFetch('/api/v1/marketplace/family/courses/' + courseId + '/orders', {
        method: 'POST', credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
        body: JSON.stringify({ recipientType, studentId: recipientType === 'CHILD' ? studentId : undefined, discountCode: discountCode.trim() || null, billingAddressId }),
      }));
      const orderStatus = String(body.status || '').toUpperCase();
      if (body.entitled === true || orderStatus === 'VERIFIED' || orderStatus === 'PAYMENT_VERIFIED_ACCESS_GRANTED') {
        const recoveredLocalOrderId = Number(body.localOrderId ?? body.orderId);
        if (Number.isSafeInteger(recoveredLocalOrderId) && recoveredLocalOrderId > 0) {
          clearPendingVerify(recoveredLocalOrderId);
        }
        setNotice(body.financeReconciliationRequired === true
          ? english
            ? 'Payment confirmed and course access granted. Invoice and revenue-share finalization is still being reconciled; do not pay again.'
            : 'भुगतान की पुष्टि हो गई और कोर्स की पहुँच मिल गई है। इनवॉइस और revenue-share का अंतिम मिलान अभी जारी है; दोबारा भुगतान न करें।'
          : String(body.message || '').trim() || (english
            ? 'The existing payment was reconciled. Course access has been updated.'
            : 'मौजूदा भुगतान सत्यापित हो गया। कोर्स की पहुँच अपडेट हो गई है।'));
        await load();
        return;
      }
      if (orderStatus === 'RECONCILIATION_REQUIRED') {
        const recoveredLocalOrderId = Number(body.localOrderId);
        const savedProviderOrderId = String(body.providerOrderId || '');
        if (Number.isSafeInteger(recoveredLocalOrderId) && recoveredLocalOrderId > 0 &&
            /^order_[A-Za-z0-9]+$/.test(savedProviderOrderId)) {
          savePendingVerify({
            courseId,
            localOrderId: recoveredLocalOrderId,
            paymentId: '',
            orderId: savedProviderOrderId,
            signature: '',
          });
        }
        setError(localizeMarketplaceError(body.message, language,
          'The original order is still being reconciled. Retry verification for that order; do not pay again.',
          'पुराने order की जाँच अभी जारी है। उसी order को फिर सत्यापित करें; दोबारा भुगतान न करें।'));
        setBusy('');
        return;
      }
      if (!body.orderId || !Number.isSafeInteger(Number(body.localOrderId)) || Number(body.localOrderId) <= 0
          || !body.keyId || !Number.isFinite(Number(body.amount)) || Number(body.amount) <= 0 || body.currency !== 'INR') {
        throw new Error(english ? 'The course order could not be prepared. Please retry.' : 'कोर्स का order तैयार नहीं हो सका। फिर प्रयास करें।');
      }
      if (Number(body.discountPaise || 0) > 0) {
        const saved = new Intl.NumberFormat('en-IN',{style:'currency',currency:'INR'}).format(Number(body.discountPaise)/100);
        setNotice(english
          ? 'Offer code '+String(body.discountCode || discountCode.trim())+' applied. You save '+saved+'. The displayed payment amount includes this server-calculated discount.'
          : 'Offer code '+String(body.discountCode || discountCode.trim())+' लागू हुआ। आपकी बचत '+saved+' है। भुगतान राशि में server-calculated discount शामिल है।');
      }
      const recoverableDraft: VerifyDraft = {
        courseId,
        localOrderId: Number(body.localOrderId),
        paymentId: '',
        orderId: String(body.orderId),
        signature: '',
      };
      savePendingVerify(recoverableDraft);
      await ensureRazorpayCheckout();
      const RazorpayCtor = window.Razorpay;
      if (!RazorpayCtor) throw new Error(english ? 'Payment checkout is not available. Please retry.' : 'भुगतान checkout उपलब्ध नहीं है। फिर प्रयास करें।');
      const checkout = new RazorpayCtor({
        key: body.keyId,
        amount: Number(body.amount),
        currency: body.currency,
        name: brand?.displayName || 'QuantaEdge',
        description: body.description || 'Course access',
        order_id: body.orderId,
        theme: { color: tenantPrimaryColor(brand) },
        handler: (response: any) => {
          const draft: VerifyDraft = {
            courseId,
            localOrderId: Number(body.localOrderId),
            paymentId: String(response.razorpay_payment_id || ''),
            orderId: String(response.razorpay_order_id || ''),
            signature: String(response.razorpay_signature || ''),
          };
          if (!draft.paymentId || !draft.orderId || !draft.signature || draft.orderId !== body.orderId) {
            setError(english ? 'The payment confirmation did not match this order. Reconcile this order before starting another checkout.' : 'भुगतान की पुष्टि इस order से मेल नहीं खाती। नया checkout शुरू करने से पहले इसी order की जाँच करें।');
            savePendingVerify(recoverableDraft);
            setBusy('');
            return;
          }
          void verifyPayment(draft);
        },
        modal: { ondismiss: () => setBusy('') },
      });
      checkout.open();
    } catch (e) {
      setError(localizeMarketplaceError(e, language, 'Checkout could not be started.', 'Checkout शुरू नहीं हो सका।'));
      setBusy('');
    }
  }

  async function addFreeCourse(courseId: number, recipientType: 'SELF' | 'CHILD', studentId?: number) {
    if (!Number.isSafeInteger(courseId) || courseId <= 0 || busy) return;
    if (recipientType === 'CHILD' && (!studentId || studentId <= 0)) {
      setError(english ? 'Choose a child first.' : 'पहले बच्चे का चयन करें।');
      return;
    }
    setBusy('free-' + courseId + '-' + recipientType + '-' + (recipientType === 'CHILD' ? String(studentId) : 'self')); setError(''); setNotice('');
    try {
      await readJson(await apiFetch('/api/v1/marketplace/family/courses/' + courseId + '/enroll-free', {
        method: 'POST', credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ recipientType, studentId: recipientType === 'CHILD' ? studentId : undefined }),
      }));
      setNotice(english ? 'Free course added to the selected learning account.' : 'निःशुल्क कोर्स चुने गए learning खाते में जोड़ दिया गया।');
      await load();
    } catch (e) {
      setError(localizeMarketplaceError(e, language, 'The free course could not be added.', 'निःशुल्क कोर्स नहीं जोड़ा जा सका।'));
    } finally { setBusy(''); }
  }

  async function dismissRequest(requestId: number) {
    setBusy('request-' + requestId); setError(''); setNotice('');
    try {
      await readJson(await apiFetch('/api/v1/parent/course-requests/' + requestId + '/dismiss', {
        method: 'POST', credentials: 'same-origin', headers: { 'Content-Type': 'application/json' }, body: '{}',
      }));
      setNotice(english ? 'Purchase request dismissed.' : 'कोर्स खरीद अनुरोध हटाया गया।');
      await load();
    } catch (e) {
      setError(localizeMarketplaceError(e, language, 'The request could not be dismissed.', 'अनुरोध खारिज नहीं हो सका।'));
    } finally { setBusy(''); }
  }

  async function transferCourse(entitlement: Entitlement) {
    const studentId = Number(transferTo[entitlement.entitlement_id] || 0);
    if (!studentId) {
      setError(english ? 'Choose the child who will receive this course.' : 'कोर्स पाने वाले बच्चे का चयन करें।');
      return;
    }
    const child = children.find(item => item.id === studentId);
    const confirmed = window.confirm(english
      ? 'Permanently transfer “' + entitlement.course_name + '” to ' + (child?.display_name || 'this child') + '? You will not be able to reclaim or revoke this course.'
      : '“' + entitlement.course_name + '” को ' + (child?.display_name || 'इस बच्चे') + ' को स्थायी रूप से दें? आप इस कोर्स को वापस नहीं ले पाएँगे।');
    if (!confirmed) return;
    setBusy('transfer-' + entitlement.entitlement_id); setError(''); setNotice('');
    try {
      await readJson(await apiFetch('/api/v1/marketplace/family/entitlements/' + entitlement.entitlement_id + '/transfer', {
        method: 'POST', credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ studentId }),
      }));
      setNotice(english ? 'Course transferred permanently. The parent no longer owns this entitlement.' : 'कोर्स स्थायी रूप से ट्रांसफर हो गया। अब यह entitlement माता-पिता के पास नहीं है।');
      await load();
    } catch (e) {
      setError(localizeMarketplaceError(e, language, 'The course transfer could not be completed.', 'कोर्स ट्रांसफर पूरा नहीं हो सका।'));
    } finally { setBusy(''); }
  }

  async function openMyLearning() {
    setBusy('my-learning'); setError('');
    try {
      const body = await readJson(await apiFetch('/api/v1/auth/my-learning-session', {
        method: 'POST', credentials: 'same-origin', headers: { 'Content-Type': 'application/json' }, body: '{}',
      }));
      if (body.role !== 'STUDENT' || body.workspace !== 'STUDENT' || Number(body.studentId) <= 0) {
        throw new Error(english ? 'Your personal learning session could not be confirmed.' : 'आपका personal learning session सत्यापित नहीं हो सका।');
      }
      window.location.assign('/parent/learning');
    } catch (e) {
      setError(localizeMarketplaceError(e, language, 'Could not open personal learning.', 'व्यक्तिगत पढ़ाई नहीं खुल सकी।'));
      setBusy('');
    }
  }

  return (
    <main className="parent-app parent-courses-page" lang={language}>
      <div className="parent-courses-page__wrap">
        <div className="parent-courses-page__heading">
          <div>
            <span className="eyebrow"><LocaleText hinglish="परिवार का कोर्स मार्केटप्लेस" english="FAMILY COURSE MARKETPLACE" /></span>
            <h1><LocaleText hinglish="कोर्स आपके लिए, या आपके बच्चे के लिए" english="Courses for you or your child" /></h1>
            <p><LocaleText hinglish="खरीद से पहले प्राप्तकर्ता चुनें। अपने लिए खरीदा कोर्स बाद में बच्चे को स्थायी रूप से ट्रांसफर किया जा सकता है।" english="Choose the recipient before checkout. A course purchased for yourself can later be permanently transferred to a linked child." /></p>
          </div>
          <div className="parent-courses-page__heading-actions">
            {personalLearningAvailable ? (
              <button className="button button-dark" type="button" disabled={busy !== ''} onClick={() => void openMyLearning()}>
                <LocaleText hinglish="मेरी पढ़ाई खोलें" english="My learning" />
              </button>
            ) : null}
            <Link className="button button-light" href="/parent/children"><LocaleText hinglish="बच्चों के खाते" english="Child accounts" /></Link>
          </div>
        </div>
        {error && <div className="parent-courses-page__alert is-error" role="alert">{error}</div>}
        {notice && <div className="parent-courses-page__alert" role="status">{notice}</div>}
        <section className="parent-courses-page__discount" aria-label={english ? "Course offer code" : "कोर्स ऑफर कोड"}>
          <label htmlFor="qe-parent-discount-code"><LocaleText hinglish="Offer code (वैकल्पिक)" english="Offer code (optional)" /></label>
          <input id="qe-parent-discount-code" autoComplete="off" maxLength={40}
            value={discountCode} onChange={event=>setDiscountCode(event.target.value.toUpperCase())}
            placeholder={english?"Enter a code supplied by your institution":"आपकी संस्था द्वारा दिया गया code डालें"}
            disabled={busy!==''}/>
          <p><LocaleText hinglish="कोड केवल उसी course और institution पर लागू होगा जिसके लिए HRMS ने approval दिया है। कीमत और tax की गणना server पर होती है।"
            english="Codes apply only to the course and institution approved by HRMS. The server calculates the discount, payable amount and tax." /></p>
        </section>
        {courses.some(course => Number(course.price_paise) > 0 && course.currency === 'INR') &&
          <BillingProfileEditor workspace="PARENT" placement="marketplace" onAddressesChange={syncBillingAddresses} />}
        <InvoiceDownloads workspace="PARENT" english={english} />
        {checkoutIntent !== null && (
          <BillingAddressSelection
            addresses={billingAddresses}
            selectedAddressId={selectedBillingAddressId}
            onSelect={setSelectedBillingAddressId}
            profileHref="/parent/profile"
            onRefreshAddresses={() => { void refreshBillingAddressBook(); }}
            onCancel={() => setCheckoutIntent(null)}
            onConfirm={() => {
              const intent = checkoutIntent;
              const addressId = selectedBillingAddressId;
              setCheckoutIntent(null);
              if (addressId !== null) void startPaidCheckout(intent.courseId, intent.recipientType, intent.studentId, addressId);
            }}
          />
        )}
        {pendingVerifies.map(draft => <section className="parent-courses-page__verify" key={draft.localOrderId}>
          <div><strong><LocaleText hinglish={`भुगतान पुष्टि बाकी है — कोर्स #${draft.courseId}`} english={`Payment verification pending — course #${draft.courseId}`} /></strong>
            <p><LocaleText hinglish="इस order की अलग से जाँच हो सकती है। अन्य कोर्स खरीदने के लिए उपलब्ध हैं।" english="This saved order can be reconciled independently. Other courses remain available to purchase." /></p></div>
          <button className="button button-dark button-small" type="button" disabled={busy !== ''}
            onClick={() => void reconcilePayment(draft)}><LocaleText hinglish="फिर से सत्यापित करें" english="Retry verification" /></button>
        </section>)}

        <section className="parent-courses-page__section">
          <div className="parent-courses-page__section-heading">
            <div><h2><LocaleText hinglish="बच्चों के कोर्स अनुरोध" english="Course requests from your children" /></h2>
              <p><LocaleText hinglish="बच्चा किसी कोर्स के लिए अनुरोध भेजे तो वह यहाँ दिखेगा।" english="When a child asks you to purchase a course, the request appears here." /></p></div>
            <span className="parent-courses-page__count">{requests.length}</span>
          </div>
          {loading ? <p className="parent-courses-page__empty"><LocaleText hinglish="अनुरोध लोड हो रहे हैं…" english="Loading requests…" /></p>
            : requests.length === 0 ? <p className="parent-courses-page__empty"><LocaleText hinglish="अभी कोई लंबित अनुरोध नहीं है।" english="There are no pending requests." /></p>
            : <div className="parent-courses-page__requests">{requests.map(request => (
              <article className="parent-courses-page__request" key={request.request_id}>
                <div><strong>{request.student_name}</strong><span>{request.course_name}</span>
                  <small>{money(Number(request.price_paise))} · <LocaleText hinglish="कोर्स खरीदने का अनुरोध" english="Purchase request" /></small>
                  {request.requested_at && <small><LocaleText hinglish="अनुरोध भेजा गया" english="Requested" /> · {formatUserTimestamp(request.requested_at, language)}</small>}
                </div>
                <div className="parent-courses-page__request-actions">
                  <button type="button" className="button button-dark button-small" disabled={busy !== ''}
                    onClick={() => {
                      const kid = children.find(child => child.id === request.student_id);
                      if (!kid) { setError(english ? 'This child is no longer linked to your account.' : 'यह बच्चा अब आपके खाते से लिंक नहीं है।'); return; }
                      if (Number(request.price_paise) === 0) void addFreeCourse(request.course_id, 'CHILD', request.student_id);
                      else void requestPaidCheckout(request.course_id, 'CHILD', request.student_id);
                    }}>
                    <LocaleText hinglish="इस बच्चे के लिए खरीदें" english="Buy for this child" />
                  </button>
                  <button type="button" className="parent-courses-page__quiet" disabled={busy !== ''}
                    onClick={() => void dismissRequest(request.request_id)}><LocaleText hinglish="खारिज करें" english="Dismiss" /></button>
                </div>
              </article>
            ))}</div>}
        </section>

        <section className="parent-courses-page__section">
          <div className="parent-courses-page__section-heading">
            <div><h2><LocaleText hinglish="उपलब्ध कोर्स" english="Available courses" /></h2>
              <p><LocaleText hinglish="अपने लिए खरीदें या जुड़े बच्चे को सीधे दें।" english="Buy for yourself or purchase directly for a linked child." /></p></div>
            <button type="button" className="parent-courses-page__quiet" onClick={() => void load()} disabled={loading || busy !== ''}><LocaleText hinglish="रीलोड" english="Refresh" /></button>
          </div>
          {loading ? <p className="parent-courses-page__empty"><LocaleText hinglish="कोर्स लोड हो रहे हैं…" english="Loading courses…" /></p>
            : courses.length === 0 ? <p className="parent-courses-page__empty"><LocaleText hinglish="अभी कोई प्रकाशित कोर्स ऑफर नहीं है।" english="There are no published course offers yet." /></p>
            : <div className="parent-courses-page__grid">{courses.map(course => {
              const ownedByParent = course.owned_by_parent === true;
              const transferableByParent = course.transferable_by_parent === true;
              const isFree = Number(course.price_paise) === 0;
              const selectedChildValue = Number(childByCourse[course.course_id] || 0);
              const ownedChildIds = new Set(String(course.owned_child_ids || '').split(',')
                .map(value => Number(value.trim())).filter(value => Number.isSafeInteger(value) && value > 0));
              const selectedChildAlreadyOwns = selectedChildValue > 0 && ownedChildIds.has(selectedChildValue);
              const selectedChild = selectedChildAlreadyOwns ? 0 : selectedChildValue;
              // A course already owned by the parent must be transferred, not purchased again.
              // Otherwise, keep other linked children eligible while excluding existing owners.
              const eligibleChildren = ownedByParent ? [] : children.filter(child => !ownedChildIds.has(child.id));
              return <MarketplaceCourseCard
                key={course.course_id}
                title={course.title || course.course_name}
                courseCode={course.course_code}
                publicId={course.public_id}
                curriculumVersion={course.curriculum_version}
                courseTypeCode={course.course_type_code}
                testPackCount={course.test_pack_count}
                assessmentCount={course.assessment_count}
                assessmentQuestionCount={course.assessment_question_count}
                language={english ? 'en' : 'hi'}
                priceLabel={ownedByParent && !isFree
                  ? (english ? 'Owned by you' : 'आपके पास उपलब्ध')
                  : course.owned_by_child === true
                    ? (english ? 'A child has access' : 'एक बच्चे के पास पहुँच है')
                    : isFree ? (english ? 'Free' : 'निःशुल्क') : money(Number(course.price_paise))}
                description={course.description}
                subjects={course.subjects}
                status={(ownedByParent && !isFree) || selectedChildAlreadyOwns ? (
                  <>
                    {ownedByParent && !isFree && <p className="parent-courses-page__owned-note">
                      {transferableByParent
                        ? <LocaleText hinglish="यह paid कोर्स आपके पास है। चाहें तो नीचे से किसी जुड़े बच्चे को स्थायी रूप से ट्रांसफर करें।"
                            english="You own this paid course. You can permanently transfer it to a linked child below." />
                        : <LocaleText hinglish="यह कोर्स आपकी व्यक्तिगत learning profile में पहले से उपलब्ध है।"
                            english="This course is already available in your personal learning profile." />}
                    </p>}
                    {selectedChildAlreadyOwns && <p className="parent-courses-page__owned-note">
                      <LocaleText hinglish="चुने गए बच्चे के पास इस कोर्स की पहुँच पहले से है। किसी दूसरे बच्चे को चुनें।"
                        english="The selected child already has access to this course. Choose another child." />
                    </p>}
                  </>
                ) : null}
              >
                <div className="parent-courses-page__buy-actions">
                  <button className="button button-dark button-small" type="button"
                    disabled={busy !== '' || ownedByParent}
                    onClick={() => isFree ? void addFreeCourse(course.course_id, 'SELF') : void requestPaidCheckout(course.course_id, 'SELF')}>
                    {busy === 'checkout-' + course.course_id + '-SELF-self' || busy === 'free-' + course.course_id + '-SELF-self'
                      ? (english ? 'Working…' : 'प्रक्रिया जारी…')
                      : ownedByParent
                        ? (english ? 'Already in your courses' : 'आपके कोर्स में पहले से है')
                        : <LocaleText hinglish="अपने लिए" english="Buy for me" />}
                  </button>
                  <label><span><LocaleText hinglish="बच्चे के लिए" english="For child" /></span>
                    <select value={selectedChild || ''} onChange={e => setChildByCourse(current => ({ ...current, [course.course_id]: Number(e.target.value) || 0 }))}>
                      <option value="">{ownedByParent
                        ? (english ? 'Course is yours — transfer it instead' : 'कोर्स आपके पास है — इसे ट्रांसफर करें')
                        : eligibleChildren.length
                          ? (english ? 'Choose child' : 'बच्चा चुनें')
                          : (english ? 'All children already have access' : 'सभी बच्चों के पास पहले से पहुँच है')}</option>
                      {eligibleChildren.map(child => <option key={child.id} value={child.id}>{child.display_name}</option>)}
                    </select>
                  </label>
                  <button className="button button-light button-small" type="button"
                    disabled={busy !== '' || ownedByParent || !selectedChild || selectedChildAlreadyOwns}
                    onClick={() => isFree ? void addFreeCourse(course.course_id, 'CHILD', selectedChild) : void requestPaidCheckout(course.course_id, 'CHILD', selectedChild)}>
                    {busy === 'checkout-' + course.course_id + '-CHILD-' + selectedChild || busy === 'free-' + course.course_id + '-CHILD-' + selectedChild
                      ? (english ? 'Working…' : 'प्रक्रिया जारी…')
                      : selectedChildAlreadyOwns
                        ? (english ? 'Already has access' : 'पहले से पहुँच है')
                        : <LocaleText hinglish="बच्चे के लिए जोड़ें" english={isFree ? 'Add for child' : 'Buy for child'} />}
                  </button>
                </div>
              </MarketplaceCourseCard>;
            })}</div>}
        </section>

        <section className="parent-courses-page__section">
          <div className="parent-courses-page__section-heading">
            <div><h2><LocaleText hinglish="आपके ट्रांसफर किए जा सकने वाले कोर्स" english="Courses you can transfer" /></h2>
              <p><LocaleText hinglish="केवल आपके लिए खरीदे गए कोर्स यहाँ होंगे। ट्रांसफर स्थायी है और वापस नहीं लिया जा सकता।" english="Only courses you own personally appear here. A transfer is permanent and cannot be revoked." /></p></div>
            <span className="parent-courses-page__count">{entitlements.length}</span>
          </div>
          {loading ? <p className="parent-courses-page__empty"><LocaleText hinglish="कोर्स लोड हो रहे हैं…" english="Loading your entitlements…" /></p>
            : entitlements.length === 0 ? <p className="parent-courses-page__empty"><LocaleText hinglish="अभी कोई व्यक्तिगत कोर्स ट्रांसफर के लिए उपलब्ध नहीं है।" english="You do not currently have any personally owned courses to transfer." /></p>
            : <div className="parent-courses-page__requests">{entitlements.map(entitlement => {
              const selected = Number(transferTo[entitlement.entitlement_id] || 0);
              return <article className="parent-courses-page__request" key={entitlement.entitlement_id}>
                <div><strong>{entitlement.course_name}</strong><span>{english ? 'Currently assigned to your personal learning profile' : 'अभी आपके personal learning profile में है'}</span></div>
                <div className="parent-courses-page__transfer-actions">
                  <select aria-label={english ? 'Child receiving the course' : 'कोर्स पाने वाला बच्चा'}
                    value={selected || ''} onChange={e => setTransferTo(current => ({ ...current, [entitlement.entitlement_id]: Number(e.target.value) || 0 }))}>
                    <option value="">{english ? 'Choose child' : 'बच्चा चुनें'}</option>
                    {children.filter(child => child.id !== entitlement.holder_student_id).map(child => <option key={child.id} value={child.id}>{child.display_name}</option>)}
                  </select>
                  <button type="button" className="button button-dark button-small" disabled={busy !== '' || !selected}
                    onClick={() => void transferCourse(entitlement)}>
                    {busy === 'transfer-' + entitlement.entitlement_id
                      ? (english ? 'Transferring…' : 'ट्रांसफर हो रहा है…')
                      : <LocaleText hinglish="स्थायी ट्रांसफर" english="Transfer permanently" />}
                  </button>
                </div>
              </article>;
            })}</div>}
        </section>
        <p className="parent-courses-page__footnote"><LocaleText hinglish="भुगतान और कोर्स का अधिकार सर्वर पर सत्यापित होता है। ट्रांसफर के बाद मूल अभिभावक इस कोर्स को वापस नहीं ले सकता।" english="Payments and course ownership are verified on the server. Once transferred, the original parent cannot reclaim or revoke the course." /></p>
      </div>
    </main>
  );
}
