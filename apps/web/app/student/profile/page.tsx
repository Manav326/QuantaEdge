'use client';

import { UiText } from '../../i18n/useUiTranslation';
import { translateUi } from '../../i18n/catalog';
import { LocaleText, useLocale } from '../../components/LanguageProvider';

import Link from 'next/link';
import { ChangeEvent, useEffect, useRef, useState } from 'react';
import { useRouter } from 'next/navigation';

type LifecycleStatus = {
  user_account_id?: number | null;
  role?: string | null;
  independentAccountLinked?: boolean;
  adult_verification_status?: string | null;
  adult_verification_current?: boolean;
  parent_eligibility_status?: string | null;
  parent_eligibility_current?: boolean;
  canTransitionToParent?: boolean;
};

type ProfileDraft = {
  displayName: string;
  city: string;
  state: string;
  schoolName: string;
  schoolMedium: string;
  favoriteSubject: string;
  learningGoal: string;
};

const emptyProfile: ProfileDraft = {
  displayName: '',
  city: '',
  state: '',
  schoolName: '',
  schoolMedium: '',
  favoriteSubject: '',
  learningGoal: '',
};

async function readJson(response: Response): Promise<any> {
  const body = await response.text();
  try {
    return body ? JSON.parse(body) : {};
  } catch {
    throw new Error(`Server returned an unexpected response (HTTP ${response.status}).`);
  }
}

function canvasBlob(canvas: HTMLCanvasElement, quality: number): Promise<Blob> {
  return new Promise((resolve, reject) => {
    canvas.toBlob(blob => blob ? resolve(blob) : reject(new Error('Could not prepare this photo. Try another image.')), 'image/jpeg', quality);
  });
}

function blobToDataUrl(blob: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => typeof reader.result === 'string' ? resolve(reader.result) : reject(new Error('Could not read the photo.'));
    reader.onerror = () => reject(new Error('Could not read the photo.'));
    reader.readAsDataURL(blob);
  });
}

async function prepareProfilePhoto(file: File): Promise<string> {
  if (!file.type.startsWith('image/')) throw new Error('Choose an image file such as JPG, PNG or WebP.');
  if (file.size > 10 * 1024 * 1024) throw new Error('Choose a photo smaller than 10 MB.');
  let bitmap: ImageBitmap;
  try {
    bitmap = await createImageBitmap(file);
  } catch {
    throw new Error('This photo could not be opened. Choose another image.');
  }
  try {
    const longest = Math.max(bitmap.width, bitmap.height);
    const scale = Math.min(1, 512 / longest);
    const canvas = document.createElement('canvas');
    canvas.width = Math.max(1, Math.round(bitmap.width * scale));
    canvas.height = Math.max(1, Math.round(bitmap.height * scale));
    const context = canvas.getContext('2d');
    if (!context) throw new Error('Photo editing is not supported by this browser.');
    context.fillStyle = '#ffffff';
    context.fillRect(0, 0, canvas.width, canvas.height);
    context.drawImage(bitmap, 0, 0, canvas.width, canvas.height);
    let blob = await canvasBlob(canvas, 0.82);
    if (blob.size > 350 * 1024) blob = await canvasBlob(canvas, 0.62);
    if (blob.size > 350 * 1024) {
      const smaller = document.createElement('canvas');
      smaller.width = Math.max(1, Math.round(canvas.width * 0.75));
      smaller.height = Math.max(1, Math.round(canvas.height * 0.75));
      const smallerContext = smaller.getContext('2d');
      if (!smallerContext) throw new Error('Photo editing is not supported by this browser.');
      smallerContext.drawImage(canvas, 0, 0, smaller.width, smaller.height);
      blob = await canvasBlob(smaller, 0.5);
    }
    if (blob.size > 350 * 1024) throw new Error('This photo is still too large. Choose a smaller image.');
    return await blobToDataUrl(blob);
  } finally {
    bitmap.close();
  }
}

export default function StudentProfilePage() {
  const router = useRouter();
  const { locale } = useLocale();
  const tx = (hinglish: string, english: string) => locale === 'english' ? english : hinglish;
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [draft, setDraft] = useState<ProfileDraft>(emptyProfile);
  const [profileImageUrl, setProfileImageUrl] = useState<string | null>(null);
  const [student, setStudent] = useState<{class_code: string; board: string; public_user_id: string} | null>(null);
  const [lifecycle, setLifecycle] = useState<LifecycleStatus | null>(null);
  const [lifecycleBusy, setLifecycleBusy] = useState(false);
  const [lifecycleConsent, setLifecycleConsent] = useState(false);
  const [transitionMobile, setTransitionMobile] = useState('');
  const [transitionOtp, setTransitionOtp] = useState('');
  const [transitionPassword, setTransitionPassword] = useState('');
  const [transitionOtpSent, setTransitionOtpSent] = useState(false);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [photoBusy, setPhotoBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [photoName, setPhotoName] = useState('');

  useEffect(() => {
    let active = true;
    async function loadProfile() {
      try {
        const response = await fetch('/api/v1/students/me/profile');
        if (response.status === 401 || response.status === 403) {
          router.replace('/login/student');
          return;
        }
        const body = await readJson(response);
        if (!response.ok) throw new Error(body.message || 'Unable to load your profile.');
        if (!active) return;
        setStudent({ class_code: String(body.class_code || ''), board: String(body.board || ''), public_user_id: String(body.public_user_id || body.publicUserId || '') });
        setProfileImageUrl(body.profile_image_url || null);
        setDraft({
          displayName: body.display_name || '',
          city: body.city || '',
          state: body.state || '',
          schoolName: body.school_name || '',
          schoolMedium: body.school_medium || '',
          favoriteSubject: body.favorite_subject || '',
          learningGoal: body.learning_goal || '',
        });
        try {
          const lifecycleResponse = await fetch('/api/v1/account/me/lifecycle');
          if (lifecycleResponse.ok) setLifecycle(await readJson(lifecycleResponse));
        } catch {
          // Profile editing remains available if the lifecycle status endpoint is temporarily unavailable.
        }
      } catch (loadError) {
        if (active) setError(loadError instanceof Error ? loadError.message : 'Unable to load your profile.');
      } finally {
        if (active) setLoading(false);
      }
    }
    void loadProfile();
    return () => { active = false; };
  }, [router]);

  async function refreshLifecycle() {
    const response = await fetch('/api/v1/account/me/lifecycle');
    const body = await readJson(response);
    if (!response.ok) throw new Error(body.message || 'Unable to refresh account status.');
    setLifecycle(body);
  }

  async function requestAdultVerification() {
    setError(''); setNotice('');
    if (!lifecycleConsent) { setError(translateUi(locale, 'legacy.web.please.confirm.your.consent.before.con.ed6835eb')); return; }
    setLifecycleBusy(true);
    try {
      const response = await fetch('/api/v1/account/me/adult-verification-request', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ consentAccepted: true }),
      });
      const body = await readJson(response);
      if (!response.ok) throw new Error(body.message || 'Unable to request adult verification.');
      await refreshLifecycle();
      setNotice(body.status === 'VERIFIED'
        ? translateUi(locale, 'legacy.web.your.adult.verification.is.already.cur.831c32ca')
        : translateUi(locale, 'legacy.web.your.adult.verification.review.request.e5c82db3'));
    } catch (actionError) {
      setError(actionError instanceof Error ? actionError.message : 'Unable to request adult verification.');
    } finally { setLifecycleBusy(false); }
  }

  async function requestTransitionOtp() {
    setError(''); setNotice('');
    if (!lifecycleConsent) { setError(translateUi(locale, 'legacy.web.please.confirm.your.consent.before.con.ed6835eb')); return; }
    if (!transitionMobile.trim()) { setError(translateUi(locale, 'legacy.web.enter.your.mobile.number.e521108b')); return; }
    setLifecycleBusy(true);
    try {
      const response = await fetch('/api/v1/auth/learner-transition/request-otp', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ mobile: transitionMobile, consentAccepted: true }),
      });
      const body = await readJson(response);
      if (!response.ok) throw new Error(body.message || 'Unable to send verification code.');
      setTransitionOtpSent(true);
      setNotice(body.devCode
        ? translateUi(locale, 'legacy.web.demo.verification.code.64a8cb3f') + body.devCode
        : translateUi(locale, 'legacy.web.verification.code.sent.5eb39c00'));
    } catch (actionError) {
      setError(actionError instanceof Error ? actionError.message : 'Unable to send verification code.');
    } finally { setLifecycleBusy(false); }
  }

  async function completeIndependentAccount() {
    setError(''); setNotice('');
    if (!transitionOtp.trim() || transitionPassword.length < 8) {
      setError(translateUi(locale, 'legacy.web.enter.the.otp.and.choose.a.password.wi.c59b110f'));
      return;
    }
    setLifecycleBusy(true);
    try {
      const response = await fetch('/api/v1/auth/verify-otp', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          mobile: transitionMobile, otp: transitionOtp, purpose: 'LEARNER_TRANSITION',
          password: transitionPassword, displayName: draft.displayName,
        }),
      });
      const body = await readJson(response);
      if (!response.ok) throw new Error(body.message || 'Unable to create your independent account.');
      setTransitionOtp(''); setTransitionPassword(''); setTransitionOtpSent(false);
      setLifecycleConsent(false);
      await refreshLifecycle();
      setNotice(translateUi(locale, 'legacy.web.your.independent.account.is.ready.your.a9258d86'));
    } catch (actionError) {
      setError(actionError instanceof Error ? actionError.message : 'Unable to create your independent account.');
    } finally { setLifecycleBusy(false); }
  }

  async function requestParentEligibility() {
    setError(''); setNotice('');
    if (!lifecycleConsent) { setError(translateUi(locale, 'legacy.web.please.confirm.your.consent.before.con.ed6835eb')); return; }
    setLifecycleBusy(true);
    try {
      const response = await fetch('/api/v1/account/me/parent-eligibility-request', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ consentAccepted: true }),
      });
      const body = await readJson(response);
      if (!response.ok) throw new Error(body.message || 'Unable to request parent eligibility review.');
      await refreshLifecycle();
      setNotice(body.status === 'VERIFIED'
        ? translateUi(locale, 'legacy.web.your.parent.eligibility.is.already.cur.97fa156a')
        : translateUi(locale, 'legacy.web.your.parent.eligibility.review.request.3523d9b3'));
    } catch (actionError) {
      setError(actionError instanceof Error ? actionError.message : 'Unable to request parent eligibility review.');
    } finally { setLifecycleBusy(false); }
  }

  async function transitionToParent() {
    if (!window.confirm(translateUi(locale, 'legacy.web.your.account.will.switch.to.the.parent.f21cb2f0'))) return;
    setError(''); setNotice(''); setLifecycleBusy(true);
    try {
      const response = await fetch('/api/v1/account/me/transition-to-parent', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ confirmParentTransition: true }),
      });
      const body = await readJson(response);
      if (!response.ok) throw new Error(body.message || 'Unable to transition to parent.');
      router.replace('/login');
    } catch (actionError) {
      setError(actionError instanceof Error ? actionError.message : 'Unable to transition to parent.');
    } finally { setLifecycleBusy(false); }
  }

  async function handlePhotoChange(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0];
    if (!file) return;
    setError('');
    setNotice('');
    setPhotoBusy(true);
    try {
      const profileImageDataUrl = await prepareProfilePhoto(file);
      const response = await fetch('/api/v1/students/me/profile-photo', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ profileImageDataUrl }),
      });
      const body = await readJson(response);
      if (!response.ok) throw new Error(body.message || 'Your photo could not be saved.');
      setProfileImageUrl(body.profile_image_url || null);
      setPhotoName(file.name);
      setNotice(translateUi(locale, 'legacy.web.your.photo.has.been.saved.to.your.prof.9c5a5ef0'));
      window.dispatchEvent(new Event('qe:profile-updated'));
    } catch (photoError) {
      setError(photoError instanceof Error ? photoError.message : 'Unable to save this photo.');
    } finally {
      setPhotoBusy(false);
      event.target.value = '';
    }
  }

  async function saveProfile(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError('');
    setNotice('');
    setSaving(true);
    try {
      const response = await fetch('/api/v1/students/me/profile', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(draft),
      });
      const body = await readJson(response);
      if (!response.ok) throw new Error(body.message || 'Your profile could not be saved.');
      setStudent({ class_code: String(body.class_code || ''), board: String(body.board || ''), public_user_id: String(body.public_user_id || body.publicUserId || '') });
      setProfileImageUrl(body.profile_image_url || null);
      setDraft({
        displayName: body.display_name || '',
        city: body.city || '',
        state: body.state || '',
        schoolName: body.school_name || '',
        schoolMedium: body.school_medium || '',
        favoriteSubject: body.favorite_subject || '',
        learningGoal: body.learning_goal || '',
      });
      setNotice(translateUi(locale, 'legacy.web.your.profile.has.been.saved.c2201d87'));
      window.dispatchEvent(new Event('qe:profile-updated'));
    } catch (saveError) {
      setError(saveError instanceof Error ? saveError.message : 'Your profile could not be saved. Please try again.');
    } finally {
      setSaving(false);
    }
  }

  async function resetPhoto() {
    setError('');
    setNotice('');
    setPhotoBusy(true);
    try {
      const response = await fetch('/api/v1/students/me/profile-photo', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ profileImageDataUrl: '' }),
      });
      const body = await readJson(response);
      if (!response.ok) throw new Error(body.message || 'Your photo could not be removed.');
      setProfileImageUrl(body.profile_image_url || null);
      setPhotoName('');
      setNotice(translateUi(locale, 'legacy.web.your.photo.has.been.removed.from.your..2b92c4f9'));
      window.dispatchEvent(new Event('qe:profile-updated'));
    } catch (photoError) {
      setError(photoError instanceof Error ? photoError.message : 'Your photo could not be removed.');
    } finally {
      setPhotoBusy(false);
    }
  }

  return (
    <main className="app-shell student-profile-page">
      <div className="student-profile-page__wrap">
        <div className="student-profile-page__breadcrumb">
          <Link href="/student"><UiText message="legacy.web.back.to.dashboard.78462162" /></Link>
          <span><UiText message="legacy.web.account.c25755c1" /></span>
        </div>
        <div className="student-profile-page__heading">
          <div>
            <span className="eyebrow"><UiText message="legacy.web.your.space.your.journey.58306bbb" /></span>
            <h1><UiText message="legacy.web.profile.and.settings.fee038f0" /></h1>
            <p><UiText message="legacy.web.personalise.your.learning.space.by.upd.2f56c645" /></p>
          </div>
          <div className="student-profile-page__class-pill"><span><UiText message="legacy.web.your.class.1cae66ed" /></span><strong>{student?.class_code ? `Class ${student.class_code}` : '—'}</strong><small>{student?.board || 'School board'}</small></div>
        </div>

        {error && <div className="student-profile-page__alert is-error" role="alert">{error}</div>}
        {notice && <div className="student-profile-page__alert is-success" role="status">{notice}</div>}

        {loading ? <div className="student-profile-page__loading" role="status" aria-live="polite"><span className="student-profile-page__spinner" aria-hidden="true" /> <UiText message="legacy.web.loading.your.profile.81ab5bfd" /></div> : (
          <form className="student-profile-form" onSubmit={saveProfile}>
            <section className="student-profile-card student-profile-photo-card">
              <div className="student-profile-card__intro"><span className="student-profile-card__number">01</span><div><h2><UiText message="legacy.web.your.profile.photo.feca3523" /></h2><p><UiText message="legacy.web.a.friendly.photo.for.your.learning.spa.436cd565" /></p></div></div>
              <div className="student-profile-photo-editor">
                <div className="student-profile-photo-editor__preview">
                  <img src={profileImageUrl || '/branding/student-avatar.svg'} alt="Student profile preview" />
                  <span className="student-profile-photo-editor__camera" aria-hidden="true">↗</span>
                </div>
                <div className="student-profile-photo-editor__copy">
                  <strong>{profileImageUrl ? <UiText message="legacy.web.looking.good.82368f53" /> : <UiText message="legacy.web.add.your.photo.4ad345e3" />}</strong>
                  <p><UiText message="legacy.web.choose.a.clear.photo.we.ll.resize.it.a.96c6a10c" /></p>
                  {photoName && <small className="student-profile-photo-editor__filename">{photoName}</small>}
                  <div className="student-profile-photo-editor__actions">
                    <input ref={fileInputRef} className="student-profile-photo-editor__file" type="file" accept="image/*" aria-label="Choose profile photo" onChange={handlePhotoChange} />
                    <button className="button button-dark button-small" type="button" disabled={photoBusy} onClick={() => fileInputRef.current?.click()}>{photoBusy ? <UiText message="legacy.web.preparing.photo.75f074e6" /> : profileImageUrl ? <UiText message="legacy.web.change.photo.5b1bc1d0" /> : <UiText message="legacy.web.choose.photo.de5a3f02" />}</button>
                    {profileImageUrl && <button className="student-profile-quiet-button" type="button" disabled={photoBusy} onClick={resetPhoto}><UiText message="legacy.web.remove.photo.89a3c097" /></button>}
                  </div>
                </div>
              </div>
            </section>

            <section className="student-profile-card">
              <div className="student-profile-card__intro"><span className="student-profile-card__number">02</span><div><h2><UiText message="legacy.web.about.you.fdc8ac97" /></h2><p><UiText message="legacy.web.keep.your.basic.details.up.to.date.482d2614" /></p></div></div>
              <div className="student-profile-fields">
                <label className="student-profile-field student-profile-field--wide"><UiText message="legacy.web.your.user.id.fc35660b" /><input value={student?.public_user_id || ''} readOnly aria-readonly="true" /><small><UiText message="legacy.web.this.12.character.id.is.permanent.and..17b68e20" /></small></label>
                <label className="student-profile-field student-profile-field--wide"><UiText message="legacy.web.your.name.7d67d7b4" /><input required minLength={2} maxLength={120} value={draft.displayName} onChange={e => setDraft({ ...draft, displayName: e.target.value })} placeholder={translateUi(locale, 'legacy.web.what.should.we.call.you.1b8ed077')} autoComplete="name" /></label>
                <label className="student-profile-field"><UiText message="legacy.web.city.or.town.70475d4d" /><input maxLength={100} value={draft.city} onChange={e => setDraft({ ...draft, city: e.target.value })} placeholder={translateUi(locale, 'legacy.web.e.g.patna.e53ea62e')} autoComplete="address-level2" /></label>
                <label className="student-profile-field"><UiText message="legacy.web.state.a0c7035d" /><input maxLength={100} value={draft.state} onChange={e => setDraft({ ...draft, state: e.target.value })} placeholder={translateUi(locale, 'legacy.web.e.g.bihar.928d6032')} autoComplete="address-level1" /></label>
              </div>
              <p className="student-profile-privacy-note"><span aria-hidden="true">ⓘ</span> <UiText message="legacy.web.city.and.state.are.enough.please.do.no.8802cd0a" /></p>
            </section>

            <section className="student-profile-card">
              <div className="student-profile-card__intro"><span className="student-profile-card__number">03</span><div><h2><UiText message="legacy.web.school.and.learning.998c3fe4" /></h2><p><UiText message="legacy.web.help.us.make.your.learning.more.releva.938b8ffd" /></p></div></div>
              <div className="student-profile-fields">
                <label className="student-profile-field student-profile-field--wide"><UiText message="legacy.web.school.name.c4fef1c5" /><input maxLength={180} value={draft.schoolName} onChange={e => setDraft({ ...draft, schoolName: e.target.value })} placeholder={translateUi(locale, 'legacy.web.enter.your.school.name.48c04e62')} autoComplete="organization" /></label>
                <label className="student-profile-field"><UiText message="legacy.web.school.medium.cfa6de85" /><select value={draft.schoolMedium} onChange={e => setDraft({ ...draft, schoolMedium: e.target.value })}><option value=""><UiText message="legacy.web.choose.if.you.know.e5c58168" /></option><option value="Hindi"><UiText message="legacy.web.hindi.197af92c" /></option><option value="English"><UiText message="common.language.english" /></option><option value="Hindi & English"><UiText message="legacy.web.hinglish.hindi.english.d8cec8ba" /></option><option value="Other">{locale==='english'?'Other':'अन्य'}</option></select></label>
                <label className="student-profile-field"><UiText message="legacy.web.favourite.subject.be48a154" /><select value={draft.favoriteSubject} onChange={e => setDraft({ ...draft, favoriteSubject: e.target.value })}><option value=""><UiText message="legacy.web.choose.a.subject.518e940d" /></option><option value="maths"><UiText message="legacy.web.maths.a163a4bb" /></option><option value="science"><UiText message="legacy.web.science.4d80727d" /></option><option value="both"><UiText message="legacy.web.both.20542d4e" /></option><option value="other"><UiText message="legacy.web.another.subject.693bc278" /></option><option value="not_sure"><UiText message="legacy.web.still.exploring.595d12f0" /></option></select></label>
                <label className="student-profile-field student-profile-field--wide"><UiText message="legacy.web.my.learning.goal.92dbad6c" /><textarea rows={3} maxLength={300} value={draft.learningGoal} onChange={e => setDraft({ ...draft, learningGoal: e.target.value })} placeholder={translateUi(locale, 'legacy.web.what.would.you.like.to.improve.for.exa.31332678')} /><small>{draft.learningGoal.length}/300 characters</small></label>
              </div>
            </section>

            <section className="student-profile-card">
              <div className="student-profile-card__intro"><span className="student-profile-card__number">04</span><div><h2><UiText message="legacy.web.account.and.eligibility.94499de8" /></h2><p><UiText message="legacy.web.manage.account.transitions.while.keepi.77f5f331" /></p></div></div>
              {!lifecycle?.independentAccountLinked ? (
                <>
                  <p><strong><UiText message="legacy.web.adult.verification.dbbc059b" />:</strong> {lifecycle?.adult_verification_status || 'NOT_REQUESTED'}</p>
                  {lifecycle?.adult_verification_current ? (
                    <>
                      <p><UiText message="legacy.web.your.adult.eligibility.is.verified.ver.5fde9e63" /></p>
                      {!transitionOtpSent ? (
                        <div className="student-profile-fields">
                          <label className="student-profile-field student-profile-field--wide"><UiText message="legacy.web.your.mobile.number.835da1ed" /><input type="tel" value={transitionMobile} onChange={e=>setTransitionMobile(e.target.value)} placeholder="+91…" autoComplete="tel" /></label>
                          <label className="student-profile-field student-profile-field--wide"><span><input type="checkbox" checked={lifecycleConsent} onChange={e=>setLifecycleConsent(e.target.checked)} /> <UiText message="legacy.web.i.consent.to.creating.an.independent.a.0c170fcf" /></span></label>
                          <button type="button" className="button button-dark button-small" disabled={lifecycleBusy} onClick={requestTransitionOtp}><UiText message="legacy.web.send.verification.code.533abf96" /></button>
                        </div>
                      ) : (
                        <div className="student-profile-fields">
                          <label className="student-profile-field"><UiText message="legacy.web.otp.257fad4d" /><input inputMode="numeric" autoComplete="one-time-code" value={transitionOtp} onChange={e=>setTransitionOtp(e.target.value)} /></label>
                          <label className="student-profile-field"><UiText message="legacy.web.new.password.32a2f216" /><input type="password" autoComplete="new-password" minLength={8} value={transitionPassword} onChange={e=>setTransitionPassword(e.target.value)} /></label>
                          <button type="button" className="button button-dark button-small" disabled={lifecycleBusy} onClick={completeIndependentAccount}><UiText message="legacy.web.create.independent.account.7b195fde" /></button>
                        </div>
                      )}
                    </>
                  ) : lifecycle?.adult_verification_status === 'PENDING' ? (
                    <p><UiText message="legacy.web.your.adult.verification.is.under.revie.d8b4d04e" /></p>
                  ) : (
                    <div className="student-profile-fields">
                      <p><UiText message="legacy.web.an.authorized.staff.review.confirming..ad1fba1b" /></p>
                      <label className="student-profile-field student-profile-field--wide"><span><input type="checkbox" checked={lifecycleConsent} onChange={e=>setLifecycleConsent(e.target.checked)} /> <UiText message="legacy.web.i.consent.to.requesting.adult.verifica.bb696e60" /></span></label>
                      <button type="button" className="button button-dark button-small" disabled={lifecycleBusy} onClick={requestAdultVerification}><UiText message="legacy.web.request.adult.verification.1e2bcfc5" /></button>
                    </div>
                  )}
                </>
              ) : (
                <>
                  <p><strong><UiText message="legacy.web.account.role.6b333543" />:</strong> {lifecycle?.role || 'LEARNER'}</p>
                  <p><strong><UiText message="legacy.web.parent.eligibility.0f44293b" />:</strong> {lifecycle?.parent_eligibility_status || 'NOT_REQUESTED'}</p>
                  {lifecycle?.canTransitionToParent ? (
                    <button type="button" className="button button-dark button-small" disabled={lifecycleBusy} onClick={transitionToParent}><UiText message="legacy.web.transition.to.parent.account.99ad6dc1" /></button>
                  ) : lifecycle?.role === 'LEARNER' && lifecycle?.adult_verification_current ? (
                    <>
                      {lifecycle?.parent_eligibility_status === 'PENDING' ? <p><UiText message="legacy.web.parent.eligibility.review.is.pending.659105be" /></p> : (
                        <div className="student-profile-fields">
                          <label className="student-profile-field student-profile-field--wide"><span><input type="checkbox" checked={lifecycleConsent} onChange={e=>setLifecycleConsent(e.target.checked)} /> <UiText message="legacy.web.i.consent.to.requesting.parent.eligibi.4f6223f0" /></span></label>
                          <button type="button" className="button button-dark button-small" disabled={lifecycleBusy} onClick={requestParentEligibility}><UiText message="legacy.web.request.parent.eligibility.54fe8ee5" /></button>
                        </div>
                      )}
                    </>
                  ) : <p><UiText message="legacy.web.parent.transition.requires.current.adu.4684d2f0" /></p>}
                </>
              )}
            </section>

            <div className="student-profile-form__footer">
              <p><UiText message="legacy.web.your.progress.and.practice.records.rem.110744bb" /></p>
              <div><Link href="/student" className="student-profile-quiet-button"><UiText message="legacy.web.cancel.843e86d4" /></Link><button type="submit" className="button button-dark" disabled={saving || photoBusy}>{saving ? <UiText message="legacy.web.saving.changes.b6c09973" /> : <UiText message="legacy.web.save.changes.a38830f7" />}</button></div>
            </div>
          </form>
        )}
      </div>
    </main>
  );
}
