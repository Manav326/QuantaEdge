'use client';

import Link from 'next/link';
import { useCallback, useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import { LocaleText, useLocale } from '../../components/LanguageProvider';

type Child = { id: number; display_name: string; class_code?: string; board?: string };
type Course = {
  course_id: number; title: string; course_name: string; course_code: string;
  description?: string | null; price_paise: number; currency: string;
  owned_by_parent?: boolean; owned_by_child?: boolean;
  subjects?: Array<{ subject_code?: string; subject_name?: string; chapters?: number; lessons?: number }>;
};
type PurchaseRequest = {
  request_id: number; student_id: number; course_id: number; status: string;
  student_name: string; course_name: string; price_paise: number; currency: string;
};
type Entitlement = {
  entitlement_id: number; course_id: number; course_name: string; current_learner_name: string;
  holder_student_id: number; transfer_count: number;
};
type VerifyDraft = { courseId: number; localOrderId: number; paymentId: string; orderId: string; signature: string };

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
  const { locale } = useLocale();
  const english = locale === 'english';
  const [courses, setCourses] = useState<Course[]>([]);
  const [children, setChildren] = useState<Child[]>([]);
  const [requests, setRequests] = useState<PurchaseRequest[]>([]);
  const [entitlements, setEntitlements] = useState<Entitlement[]>([]);
  const [childByCourse, setChildByCourse] = useState<Record<number, number>>({});
  const [transferTo, setTransferTo] = useState<Record<number, number>>({});
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState('');
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [pendingVerify, setPendingVerify] = useState<VerifyDraft | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const session = await readJson(await fetch('/api/v1/auth/me', { credentials: 'same-origin', cache: 'no-store' }));
      if (session.role !== 'PARENT' || session.workspace !== 'PARENT' || Number(session.userId) <= 0) {
        router.replace('/login');
        return;
      }
      const responses = await Promise.all([
        fetch('/api/v1/marketplace/family/courses', { credentials: 'same-origin', cache: 'no-store' }),
        fetch('/api/v1/guardians/children', { credentials: 'same-origin', cache: 'no-store' }),
        fetch('/api/v1/parent/course-requests', { credentials: 'same-origin', cache: 'no-store' }),
        fetch('/api/v1/marketplace/family/entitlements', { credentials: 'same-origin', cache: 'no-store' }),
      ]);
      const results = await Promise.all(responses.map(response => readJson(response)));
      setCourses(Array.isArray(results[0]) ? results[0] : []);
      setChildren(Array.isArray(results[1]) ? results[1] : []);
      setRequests(Array.isArray(results[2]) ? results[2] : []);
      setEntitlements(Array.isArray(results[3]) ? results[3] : []);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Family courses could not be loaded.');
    } finally {
      setLoading(false);
    }
  }, [router]);

  useEffect(() => { void load(); }, [load]);

  const money = (paise: number) => new Intl.NumberFormat('en-IN', {
    style: 'currency', currency: 'INR', maximumFractionDigits: 2,
  }).format(paise / 100);

  async function verifyPayment(draft: VerifyDraft) {
    setBusy('verify');
    setError('');
    setNotice('');
    setPendingVerify(draft);
    try {
      await readJson(await fetch('/api/v1/marketplace/family/orders/' + draft.localOrderId + '/verify', {
        method: 'POST', credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ paymentId: draft.paymentId, orderId: draft.orderId, signature: draft.signature }),
      }));
      setPendingVerify(null);
      setNotice(english ? 'Payment verified. Course access has been updated.' : 'भुगतान सत्यापित। कोर्स की पहुँच अपडेट हो गई है।');
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Payment verification could not be completed. Retry verification before paying again.');
    } finally { setBusy(''); }
  }

  async function startPaidCheckout(courseId: number, recipientType: 'SELF' | 'CHILD', studentId?: number) {
    if (!Number.isSafeInteger(courseId) || courseId <= 0 || busy) return;
    if (recipientType === 'CHILD' && (!studentId || studentId <= 0)) {
      setError(english ? 'Choose a child first.' : 'पहले बच्चे का चयन करें।');
      return;
    }
    setBusy('checkout-' + courseId);
    setError(''); setNotice('');
    try {
      const body = await readJson(await fetch('/api/v1/marketplace/family/courses/' + courseId + '/orders', {
        method: 'POST', credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
        body: JSON.stringify({ recipientType, studentId: recipientType === 'CHILD' ? studentId : undefined }),
      }));
      if (!body.orderId || !Number.isSafeInteger(Number(body.localOrderId)) || Number(body.localOrderId) <= 0
          || !body.keyId || !Number.isFinite(Number(body.amount)) || Number(body.amount) <= 0 || body.currency !== 'INR') {
        throw new Error('The course order could not be prepared. Please retry.');
      }
      await ensureRazorpayCheckout();
      const RazorpayCtor = window.Razorpay;
      if (!RazorpayCtor) throw new Error('Payment checkout is not available. Please retry.');
      const checkout = new RazorpayCtor({
        key: body.keyId,
        amount: Number(body.amount),
        currency: body.currency,
        name: 'QuantaEdge',
        description: body.description || 'Course access',
        order_id: body.orderId,
        theme: { color: '#E87524' },
        handler: (response: any) => {
          const draft: VerifyDraft = {
            courseId,
            localOrderId: Number(body.localOrderId),
            paymentId: String(response.razorpay_payment_id || ''),
            orderId: String(response.razorpay_order_id || ''),
            signature: String(response.razorpay_signature || ''),
          };
          if (!draft.paymentId || !draft.orderId || !draft.signature || draft.orderId !== body.orderId) {
            setError('The payment confirmation did not match this order. Do not pay again; contact support if money was deducted.');
            setBusy('');
            return;
          }
          setPendingVerify(draft);
          void verifyPayment(draft);
        },
        modal: { ondismiss: () => setBusy('') },
      });
      checkout.open();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Checkout could not be started.');
      setBusy('');
    }
  }

  async function addFreeCourse(courseId: number, recipientType: 'SELF' | 'CHILD', studentId?: number) {
    if (!Number.isSafeInteger(courseId) || courseId <= 0 || busy) return;
    if (recipientType === 'CHILD' && (!studentId || studentId <= 0)) {
      setError(english ? 'Choose a child first.' : 'पहले बच्चे का चयन करें।');
      return;
    }
    setBusy('free-' + courseId); setError(''); setNotice('');
    try {
      await readJson(await fetch('/api/v1/marketplace/family/courses/' + courseId + '/enroll-free', {
        method: 'POST', credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ recipientType, studentId: recipientType === 'CHILD' ? studentId : undefined }),
      }));
      setNotice(english ? 'Free course added to the selected learning account.' : 'निःशुल्क कोर्स चुने गए learning खाते में जोड़ दिया गया।');
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'The free course could not be added.');
    } finally { setBusy(''); }
  }

  async function dismissRequest(requestId: number) {
    setBusy('request-' + requestId); setError(''); setNotice('');
    try {
      await readJson(await fetch('/api/v1/parent/course-requests/' + requestId + '/dismiss', {
        method: 'POST', credentials: 'same-origin', headers: { 'Content-Type': 'application/json' }, body: '{}',
      }));
      setNotice(english ? 'Purchase request dismissed.' : 'कोर्स खरीद अनुरोध हटाया गया।');
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'The request could not be dismissed.');
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
      await readJson(await fetch('/api/v1/marketplace/family/entitlements/' + entitlement.entitlement_id + '/transfer', {
        method: 'POST', credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ studentId }),
      }));
      setNotice(english ? 'Course transferred permanently. The parent no longer owns this entitlement.' : 'कोर्स स्थायी रूप से ट्रांसफर हो गया। अब यह entitlement माता-पिता के पास नहीं है।');
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'The course transfer could not be completed.');
    } finally { setBusy(''); }
  }

  async function openMyLearning() {
    setBusy('my-learning'); setError('');
    try {
      const body = await readJson(await fetch('/api/v1/auth/my-learning-session', {
        method: 'POST', credentials: 'same-origin', headers: { 'Content-Type': 'application/json' }, body: '{}',
      }));
      if (body.role !== 'STUDENT' || body.workspace !== 'STUDENT' || Number(body.studentId) <= 0) {
        throw new Error('Your personal learning session could not be confirmed.');
      }
      window.location.assign('/student');
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Could not open personal learning.');
      setBusy('');
    }
  }

  return (
    <main className="parent-app parent-courses-page">
      <div className="parent-courses-page__wrap">
        <div className="parent-courses-page__heading">
          <div>
            <span className="eyebrow"><LocaleText hinglish="परिवार का कोर्स मार्केटप्लेस" english="FAMILY COURSE MARKETPLACE" /></span>
            <h1><LocaleText hinglish="कोर्स आपके लिए, या आपके बच्चे के लिए" english="Courses for you or your child" /></h1>
            <p><LocaleText hinglish="खरीद से पहले प्राप्तकर्ता चुनें। अपने लिए खरीदा कोर्स बाद में बच्चे को स्थायी रूप से ट्रांसफर किया जा सकता है।" english="Choose the recipient before checkout. A course purchased for yourself can later be permanently transferred to a linked child." /></p>
          </div>
          <div className="parent-courses-page__heading-actions">
            <button className="button button-dark" type="button" disabled={busy !== ''} onClick={() => void openMyLearning()}>
              <LocaleText hinglish="मेरी पढ़ाई खोलें" english="My learning" />
            </button>
            <Link className="button button-light" href="/parent/children"><LocaleText hinglish="बच्चों के खाते" english="Child accounts" /></Link>
          </div>
        </div>
        {error && <div className="parent-courses-page__alert is-error" role="alert">{error}</div>}
        {notice && <div className="parent-courses-page__alert" role="status">{notice}</div>}
        {pendingVerify && <section className="parent-courses-page__verify">
          <div><strong><LocaleText hinglish="भुगतान पुष्टि बाकी है" english="Payment verification is pending" /></strong>
            <p><LocaleText hinglish="दोबारा भुगतान न करें। इसी order की पुष्टि फिर से करें।" english="Do not pay again. Retry verification for this same order." /></p></div>
          <button className="button button-dark button-small" type="button" disabled={busy !== ''}
            onClick={() => void verifyPayment(pendingVerify)}><LocaleText hinglish="फिर से सत्यापित करें" english="Retry verification" /></button>
        </section>}

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
                  <small>{money(Number(request.price_paise))} · <LocaleText hinglish="कोर्स खरीदने का अनुरोध" english="Purchase request" /></small></div>
                <div className="parent-courses-page__request-actions">
                  <button type="button" className="button button-dark button-small" disabled={busy !== ''}
                    onClick={() => {
                      const kid = children.find(child => child.id === request.student_id);
                      if (!kid) { setError('This child is no longer linked to your account.'); return; }
                      if (Number(request.price_paise) === 0) void addFreeCourse(request.course_id, 'CHILD', request.student_id);
                      else void startPaidCheckout(request.course_id, 'CHILD', request.student_id);
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
              const owned = course.owned_by_parent === true || course.owned_by_child === true;
              const isFree = Number(course.price_paise) === 0;
              const selectedChild = Number(childByCourse[course.course_id] || 0);
              return <article className="parent-courses-page__course" key={course.course_id}>
                <div className="parent-courses-page__course-top"><span className="parent-courses-page__course-icon" aria-hidden="true">✦</span>
                  <span className="parent-courses-page__price">{owned
                    ? (english ? 'Already owned' : 'पहले से उपलब्ध')
                    : isFree ? (english ? 'Free' : 'निःशुल्क') : money(Number(course.price_paise))}</span></div>
                <h3>{course.title || course.course_name}</h3>
                <small className="parent-courses-page__course-code">{course.course_code}</small>
                {course.description && <p>{course.description}</p>}
                {!!course.subjects?.length && <div className="parent-courses-page__subjects">{course.subjects.map(subject =>
                  <span key={subject.subject_code}>{subject.subject_name || subject.subject_code}</span>)}</div>}
                {owned ? <p className="parent-courses-page__owned-note"><LocaleText hinglish="यह कोर्स पहले से आपके या आपके बच्चे के खाते में है।" english="This course is already held by you or a linked child." /></p>
                  : <div className="parent-courses-page__buy-actions">
                    <button className="button button-dark button-small" type="button" disabled={busy !== ''}
                      onClick={() => isFree ? void addFreeCourse(course.course_id, 'SELF') : void startPaidCheckout(course.course_id, 'SELF')}>
                      {busy === 'checkout-' + course.course_id || busy === 'free-' + course.course_id
                        ? (english ? 'Working…' : 'प्रक्रिया जारी…')
                        : <LocaleText hinglish="अपने लिए" english="Buy for me" />}
                    </button>
                    <label><span><LocaleText hinglish="बच्चे के लिए" english="For child" /></span>
                      <select value={selectedChild || ''} onChange={e => setChildByCourse(current => ({ ...current, [course.course_id]: Number(e.target.value) || 0 }))}>
                        <option value="">{english ? 'Choose child' : 'बच्चा चुनें'}</option>
                        {children.map(child => <option key={child.id} value={child.id}>{child.display_name}</option>)}
                      </select>
                    </label>
                    <button className="button button-light button-small" type="button" disabled={busy !== '' || !selectedChild}
                      onClick={() => isFree ? void addFreeCourse(course.course_id, 'CHILD', selectedChild) : void startPaidCheckout(course.course_id, 'CHILD', selectedChild)}>
                      <LocaleText hinglish="बच्चे के लिए जोड़ें" english={isFree ? 'Add for child' : 'Buy for child'} />
                    </button>
                  </div>}
              </article>;
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
