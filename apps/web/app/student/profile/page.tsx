'use client';

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
    if (!lifecycleConsent) { setError(tx('आगे बढ़ने से पहले अपनी सहमति दें।','Please confirm your consent before continuing.')); return; }
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
        ? tx('आपकी adult verification पहले से verified है।','Your adult verification is already current.')
        : tx('Adult verification review का request भेज दिया गया है।','Your adult verification review request has been submitted.'));
    } catch (actionError) {
      setError(actionError instanceof Error ? actionError.message : 'Unable to request adult verification.');
    } finally { setLifecycleBusy(false); }
  }

  async function requestTransitionOtp() {
    setError(''); setNotice('');
    if (!lifecycleConsent) { setError(tx('आगे बढ़ने से पहले अपनी सहमति दें।','Please confirm your consent before continuing.')); return; }
    if (!transitionMobile.trim()) { setError(tx('अपना verified mobile number डालें।','Enter your mobile number.')); return; }
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
        ? tx('Demo verification code: ','Demo verification code: ') + body.devCode
        : tx('Verification code भेज दिया गया है।','Verification code sent.'));
    } catch (actionError) {
      setError(actionError instanceof Error ? actionError.message : 'Unable to send verification code.');
    } finally { setLifecycleBusy(false); }
  }

  async function completeIndependentAccount() {
    setError(''); setNotice('');
    if (!transitionOtp.trim() || transitionPassword.length < 8) {
      setError(tx('OTP डालें और कम से कम 8 characters का password चुनें।','Enter the OTP and choose a password with at least 8 characters.'));
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
      setNotice(tx('आपका independent account बन गया है। आपकी learning history और progress उसी profile पर सुरक्षित हैं।','Your independent account is ready. Your learning history and progress remain on the same learner profile.'));
    } catch (actionError) {
      setError(actionError instanceof Error ? actionError.message : 'Unable to create your independent account.');
    } finally { setLifecycleBusy(false); }
  }

  async function requestParentEligibility() {
    setError(''); setNotice('');
    if (!lifecycleConsent) { setError(tx('आगे बढ़ने से पहले अपनी सहमति दें।','Please confirm your consent before continuing.')); return; }
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
        ? tx('आपकी parent eligibility पहले से verified है।','Your parent eligibility is already current.')
        : tx('Parent eligibility review का request भेज दिया गया है।','Your parent eligibility review request has been submitted.'));
    } catch (actionError) {
      setError(actionError instanceof Error ? actionError.message : 'Unable to request parent eligibility review.');
    } finally { setLifecycleBusy(false); }
  }

  async function transitionToParent() {
    if (!window.confirm(tx('आपका account parent role में बदलेगा। आपकी learner profile और history सुरक्षित रहेंगी। जारी रखें?','Your account will switch to the parent role. Your learner profile and history will be preserved. Continue?'))) return;
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
      setNotice(tx('आपकी photo profile में save हो गई है।','Your photo has been saved to your profile.'));
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
      setNotice(tx('आपकी profile save हो गई है।','Your profile has been saved.'));
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
      setNotice(tx('आपकी photo profile से हटा दी गई है।','Your photo has been removed from your profile.'));
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
          <Link href="/student"><LocaleText hinglish="← Dashboard पर वापस" english="← Back to dashboard" /></Link>
          <span><LocaleText hinglish="ACCOUNT" english="ACCOUNT" /></span>
        </div>
        <div className="student-profile-page__heading">
          <div>
            <span className="eyebrow"><LocaleText hinglish="आपकी learning space · आपकी journey" english="YOUR SPACE · YOUR JOURNEY" /></span>
            <h1><LocaleText hinglish="Profile और settings" english="Profile and settings" /></h1>
            <p><LocaleText hinglish="अपनी learning space को personalise करें। Photo और details update कर सकते हैं।" english="Personalise your learning space by updating your photo and details." /></p>
          </div>
          <div className="student-profile-page__class-pill"><span><LocaleText hinglish="आपकी Class" english="YOUR CLASS" /></span><strong>{student?.class_code ? `Class ${student.class_code}` : '—'}</strong><small>{student?.board || 'School board'}</small></div>
        </div>

        {error && <div className="student-profile-page__alert is-error" role="alert">{error}</div>}
        {notice && <div className="student-profile-page__alert is-success" role="status">{notice}</div>}

        {loading ? <div className="student-profile-page__loading" role="status" aria-live="polite"><span className="student-profile-page__spinner" aria-hidden="true" /> <LocaleText hinglish="आपकी profile load हो रही है…" english="Loading your profile…" /></div> : (
          <form className="student-profile-form" onSubmit={saveProfile}>
            <section className="student-profile-card student-profile-photo-card">
              <div className="student-profile-card__intro"><span className="student-profile-card__number">01</span><div><h2><LocaleText hinglish="आपकी profile photo" english="Your profile photo" /></h2><p><LocaleText hinglish="आपकी learning space के लिए एक friendly photo।" english="A friendly photo for your learning space." /></p></div></div>
              <div className="student-profile-photo-editor">
                <div className="student-profile-photo-editor__preview">
                  <img src={profileImageUrl || '/branding/student-avatar.svg'} alt="Student profile preview" />
                  <span className="student-profile-photo-editor__camera" aria-hidden="true">↗</span>
                </div>
                <div className="student-profile-photo-editor__copy">
                  <strong>{profileImageUrl ? <LocaleText hinglish="अच्छा लग रहा है!" english="Looking good!" /> : <LocaleText hinglish="अपनी photo जोड़ें" english="Add your photo" />}</strong>
                  <p><LocaleText hinglish="अपनी clear photo चुनें। Save करने से पहले इसे automatically resize किया जाएगा।" english="Choose a clear photo. We’ll resize it automatically before saving." /></p>
                  {photoName && <small className="student-profile-photo-editor__filename">{photoName}</small>}
                  <div className="student-profile-photo-editor__actions">
                    <input ref={fileInputRef} className="student-profile-photo-editor__file" type="file" accept="image/*" aria-label="Choose profile photo" onChange={handlePhotoChange} />
                    <button className="button button-dark button-small" type="button" disabled={photoBusy} onClick={() => fileInputRef.current?.click()}>{photoBusy ? <LocaleText hinglish="Photo तैयार हो रही है…" english="Preparing photo…" /> : profileImageUrl ? <LocaleText hinglish="Photo बदलें" english="Change photo" /> : <LocaleText hinglish="Photo चुनें" english="Choose photo" />}</button>
                    {profileImageUrl && <button className="student-profile-quiet-button" type="button" disabled={photoBusy} onClick={resetPhoto}><LocaleText hinglish="Photo हटाएँ" english="Remove photo" /></button>}
                  </div>
                </div>
              </div>
            </section>

            <section className="student-profile-card">
              <div className="student-profile-card__intro"><span className="student-profile-card__number">02</span><div><h2><LocaleText hinglish="आपके बारे में" english="About you" /></h2><p><LocaleText hinglish="अपनी basic details updated रखें।" english="Keep your basic details up to date." /></p></div></div>
              <div className="student-profile-fields">
                <label className="student-profile-field student-profile-field--wide"><LocaleText hinglish="आपका User ID" english="Your user ID" /><input value={student?.public_user_id || ''} readOnly aria-readonly="true" /><small><LocaleText hinglish="यह 12 अक्षरों का स्थायी ID है। इसे बदला नहीं जा सकता।" english="This 12-character ID is permanent and cannot be changed." /></small></label>
                <label className="student-profile-field student-profile-field--wide"><LocaleText hinglish="आपका नाम" english="Your name" /><input required minLength={2} maxLength={120} value={draft.displayName} onChange={e => setDraft({ ...draft, displayName: e.target.value })} placeholder={tx('आपको किस नाम से बुलाएँ?','What should we call you?')} autoComplete="name" /></label>
                <label className="student-profile-field"><LocaleText hinglish="शहर या town" english="City or town" /><input maxLength={100} value={draft.city} onChange={e => setDraft({ ...draft, city: e.target.value })} placeholder={tx('जैसे Patna','e.g. Patna')} autoComplete="address-level2" /></label>
                <label className="student-profile-field"><LocaleText hinglish="State" english="State" /><input maxLength={100} value={draft.state} onChange={e => setDraft({ ...draft, state: e.target.value })} placeholder={tx('जैसे Bihar','e.g. Bihar')} autoComplete="address-level1" /></label>
              </div>
              <p className="student-profile-privacy-note"><span aria-hidden="true">ⓘ</span> <LocaleText hinglish="City और state काफ़ी हैं। कृपया घर का पूरा address न डालें।" english="City and state are enough. Please do not enter your full home address." /></p>
            </section>

            <section className="student-profile-card">
              <div className="student-profile-card__intro"><span className="student-profile-card__number">03</span><div><h2><LocaleText hinglish="School और learning" english="School and learning" /></h2><p><LocaleText hinglish="अपनी learning को और relevant बनाने में मदद करें।" english="Help us make your learning more relevant." /></p></div></div>
              <div className="student-profile-fields">
                <label className="student-profile-field student-profile-field--wide"><LocaleText hinglish="School का नाम" english="School name" /><input maxLength={180} value={draft.schoolName} onChange={e => setDraft({ ...draft, schoolName: e.target.value })} placeholder={tx('School का नाम डालें','Enter your school name')} autoComplete="organization" /></label>
                <label className="student-profile-field"><LocaleText hinglish="School medium" english="School medium" /><select value={draft.schoolMedium} onChange={e => setDraft({ ...draft, schoolMedium: e.target.value })}><option value=""><LocaleText hinglish="अगर पता हो तो चुनें" english="Choose if you know" /></option><option value="Hindi"><LocaleText hinglish="हिन्दी" english="Hindi" /></option><option value="English"><LocaleText hinglish="English" english="English" /></option><option value="Hindi & English"><LocaleText hinglish="Hinglish (हिन्दी + English)" english="Hinglish (Hindi + English)" /></option><option value="Other">{locale==='english'?'Other':'अन्य'}</option></select></label>
                <label className="student-profile-field"><LocaleText hinglish="पसंदीदा subject" english="Favourite subject" /><select value={draft.favoriteSubject} onChange={e => setDraft({ ...draft, favoriteSubject: e.target.value })}><option value=""><LocaleText hinglish="Subject चुनें" english="Choose a subject" /></option><option value="maths"><LocaleText hinglish="गणित · Maths" english="Maths" /></option><option value="science"><LocaleText hinglish="विज्ञान · Science" english="Science" /></option><option value="both"><LocaleText hinglish="दोनों" english="Both" /></option><option value="other"><LocaleText hinglish="कोई और subject" english="Another subject" /></option><option value="not_sure"><LocaleText hinglish="अभी explore कर रहे हैं" english="Still exploring" /></option></select></label>
                <label className="student-profile-field student-profile-field--wide"><LocaleText hinglish="मेरा learning goal" english="My learning goal" /><textarea rows={3} maxLength={300} value={draft.learningGoal} onChange={e => setDraft({ ...draft, learningGoal: e.target.value })} placeholder={tx('आप किस चीज़ में बेहतर होना चाहते हैं? जैसे, fractions solve करने में confidence बढ़ाना।','What would you like to improve? For example, build confidence in solving fractions.')} /><small>{draft.learningGoal.length}/300 characters</small></label>
              </div>
            </section>

            <section className="student-profile-card">
              <div className="student-profile-card__intro"><span className="student-profile-card__number">04</span><div><h2><LocaleText hinglish="Account और eligibility" english="Account and eligibility" /></h2><p><LocaleText hinglish="आपकी learner identity और learning history को सुरक्षित रखते हुए account transition manage करें।" english="Manage account transitions while keeping your learner identity and learning history intact." /></p></div></div>
              {!lifecycle?.independentAccountLinked ? (
                <>
                  <p><strong><LocaleText hinglish="Adult verification" english="Adult verification" />:</strong> {lifecycle?.adult_verification_status || 'NOT_REQUESTED'}</p>
                  {lifecycle?.adult_verification_current ? (
                    <>
                      <p><LocaleText hinglish="आपकी age eligibility verified है। अपना mobile verify करके independent account बना सकते हैं।" english="Your adult eligibility is verified. Verify your mobile to create an independent account." /></p>
                      {!transitionOtpSent ? (
                        <div className="student-profile-fields">
                          <label className="student-profile-field student-profile-field--wide"><LocaleText hinglish="आपका mobile number" english="Your mobile number" /><input type="tel" value={transitionMobile} onChange={e=>setTransitionMobile(e.target.value)} placeholder="+91…" autoComplete="tel" /></label>
                          <label className="student-profile-field student-profile-field--wide"><span><input type="checkbox" checked={lifecycleConsent} onChange={e=>setLifecycleConsent(e.target.checked)} /> <LocaleText hinglish="मैं independent account बनाने और guardian access सीमित करने के लिए सहमत हूँ।" english="I consent to creating an independent account and limiting existing guardian access." /></span></label>
                          <button type="button" className="button button-dark button-small" disabled={lifecycleBusy} onClick={requestTransitionOtp}><LocaleText hinglish="Verification code भेजें" english="Send verification code" /></button>
                        </div>
                      ) : (
                        <div className="student-profile-fields">
                          <label className="student-profile-field"><LocaleText hinglish="OTP" english="OTP" /><input inputMode="numeric" autoComplete="one-time-code" value={transitionOtp} onChange={e=>setTransitionOtp(e.target.value)} /></label>
                          <label className="student-profile-field"><LocaleText hinglish="नया password" english="New password" /><input type="password" autoComplete="new-password" minLength={8} value={transitionPassword} onChange={e=>setTransitionPassword(e.target.value)} /></label>
                          <button type="button" className="button button-dark button-small" disabled={lifecycleBusy} onClick={completeIndependentAccount}><LocaleText hinglish="Independent account बनाएं" english="Create independent account" /></button>
                        </div>
                      )}
                    </>
                  ) : lifecycle?.adult_verification_status === 'PENDING' ? (
                    <p><LocaleText hinglish="आपकी adult verification review में है। Review पूरा होने पर यहाँ status update होगा।" english="Your adult verification is under review. This status will update when the review is complete." /></p>
                  ) : (
                    <div className="student-profile-fields">
                      <p><LocaleText hinglish="Independent account बनाने के लिए staff द्वारा verified-over-18 review ज़रूरी है। जन्मतिथि यहाँ store नहीं की जाती।" english="An authorized staff review confirming you are strictly over 18 is required. Your date of birth is not stored here." /></p>
                      <label className="student-profile-field student-profile-field--wide"><span><input type="checkbox" checked={lifecycleConsent} onChange={e=>setLifecycleConsent(e.target.checked)} /> <LocaleText hinglish="मैं adult verification review का अनुरोध करने के लिए सहमत हूँ।" english="I consent to requesting adult verification review." /></span></label>
                      <button type="button" className="button button-dark button-small" disabled={lifecycleBusy} onClick={requestAdultVerification}><LocaleText hinglish="Adult verification का अनुरोध करें" english="Request adult verification" /></button>
                    </div>
                  )}
                </>
              ) : (
                <>
                  <p><strong><LocaleText hinglish="Account role" english="Account role" />:</strong> {lifecycle?.role || 'LEARNER'}</p>
                  <p><strong><LocaleText hinglish="Parent eligibility" english="Parent eligibility" />:</strong> {lifecycle?.parent_eligibility_status || 'NOT_REQUESTED'}</p>
                  {lifecycle?.canTransitionToParent ? (
                    <button type="button" className="button button-dark button-small" disabled={lifecycleBusy} onClick={transitionToParent}><LocaleText hinglish="Parent account में बदलें" english="Transition to parent account" /></button>
                  ) : lifecycle?.role === 'LEARNER' && lifecycle?.adult_verification_current ? (
                    <>
                      {lifecycle?.parent_eligibility_status === 'PENDING' ? <p><LocaleText hinglish="Parent eligibility review pending है।" english="Parent eligibility review is pending." /></p> : (
                        <div className="student-profile-fields">
                          <label className="student-profile-field student-profile-field--wide"><span><input type="checkbox" checked={lifecycleConsent} onChange={e=>setLifecycleConsent(e.target.checked)} /> <LocaleText hinglish="मैं parent eligibility review का अनुरोध करने के लिए सहमत हूँ।" english="I consent to requesting parent eligibility review." /></span></label>
                          <button type="button" className="button button-dark button-small" disabled={lifecycleBusy} onClick={requestParentEligibility}><LocaleText hinglish="Parent eligibility का अनुरोध करें" english="Request parent eligibility" /></button>
                        </div>
                      )}
                    </>
                  ) : <p><LocaleText hinglish="Parent transition के लिए current adult verification और अलग parent eligibility approval ज़रूरी है।" english="Parent transition requires current adult verification and separate parent eligibility approval." /></p>}
                </>
              )}
            </section>

            <div className="student-profile-form__footer">
              <p><LocaleText hinglish="ये details update करने पर भी आपकी progress और practice records save रहेंगे।" english="Your progress and practice records remain saved when you update these details." /></p>
              <div><Link href="/student" className="student-profile-quiet-button"><LocaleText hinglish="रद्द करें" english="Cancel" /></Link><button type="submit" className="button button-dark" disabled={saving || photoBusy}>{saving ? <LocaleText hinglish="Changes save हो रहे हैं…" english="Saving changes…" /> : <LocaleText hinglish="Changes save करें" english="Save changes" />}</button></div>
            </div>
          </form>
        )}
      </div>
    </main>
  );
}
