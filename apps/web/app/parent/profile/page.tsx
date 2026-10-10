'use client';

import { LocaleText, useLocale } from '../../components/LanguageProvider';

import Link from 'next/link';
import { ChangeEvent, FormEvent, useEffect, useRef, useState } from 'react';
import { useRouter } from 'next/navigation';
import QuantaEdgeBrand from '../../components/QuantaEdgeBrand';
import ParentAccountMenu from '../../components/ParentAccountMenu';

type ParentProfileDraft = {
  displayName: string;
  email: string;
  city: string;
  state: string;
  occupation: string;
  organization: string;
  preferredLanguage: string;
  profileImageDataUrl: string;
};

type ParentIdentity = { mobile: string; profileImageUrl: string | null };

const emptyProfile: ParentProfileDraft = {
  displayName: '',
  email: '',
  city: '',
  state: '',
  occupation: '',
  organization: '',
  preferredLanguage: 'English',
  profileImageDataUrl: '',
};

async function readJson(response: Response): Promise<any> {
  const raw = await response.text();
  try {
    return raw ? JSON.parse(raw) : {};
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
    const scale = Math.min(1, 512 / Math.max(bitmap.width, bitmap.height));
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

export default function ParentProfilePage() {
  const router = useRouter();
  const { locale } = useLocale();
  const tx = (hinglish: string, english: string) => locale === 'english' ? english : hinglish;
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [draft, setDraft] = useState<ParentProfileDraft>(emptyProfile);
  const [identity, setIdentity] = useState<ParentIdentity>({ mobile: '', profileImageUrl: null });
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [photoBusy, setPhotoBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [photoName, setPhotoName] = useState('');
  const [photoChanged, setPhotoChanged] = useState(false);

  useEffect(() => {
    let active = true;
    async function loadProfile() {
      try {
        const meResponse = await fetch('/api/v1/auth/me', { cache: 'no-store' });
        if (!meResponse.ok) {
          router.replace('/login');
          return;
        }
        let me = await readJson(meResponse);
        if (me.role === 'STUDENT' && me.userId) {
          const switched = await fetch('/api/v1/auth/switch-parent', { method: 'POST' });
          if (!switched.ok) {
            router.replace('/login');
            return;
          }
          const refreshed = await fetch('/api/v1/auth/me', { cache: 'no-store' });
          if (!refreshed.ok) {
            router.replace('/login');
            return;
          }
          me = await readJson(refreshed);
        }
        if (me.role !== 'PARENT') {
          router.replace('/login');
          return;
        }

        const response = await fetch('/api/v1/guardians/profile', { cache: 'no-store' });
        const body = await readJson(response);
        if (!response.ok) throw new Error(body.message || 'Unable to load the parent profile.');
        if (!active) return;
        const url = body.profile_image_url || null;
        setIdentity({ mobile: body.mobile_e164 || '', profileImageUrl: url });
        setDraft({
          displayName: body.display_name || '',
          email: body.email || '',
          city: body.city || '',
          state: body.state || '',
          occupation: body.occupation || '',
          organization: body.organization || '',
          preferredLanguage: body.preferred_language || 'English',
          profileImageDataUrl: '',
        });
      } catch (loadError) {
        if (active) setError(loadError instanceof Error ? loadError.message : 'Unable to load your profile.');
      } finally {
        if (active) setLoading(false);
      }
    }
    void loadProfile();
    return () => { active = false; };
  }, [router]);

  async function handlePhotoChange(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0];
    if (!file) return;
    setError('');
    setNotice('');
    setPhotoBusy(true);
    try {
      const profileImageDataUrl = await prepareProfilePhoto(file);
      setDraft(current => ({ ...current, profileImageDataUrl }));
      setPhotoChanged(true);
      setPhotoName(file.name);
      setNotice(tx('Photo ready है। Profile पर रखने के लिए changes save करें।','Photo is ready. Save your changes to keep it on your profile.'));
    } catch (photoError) {
      setError(photoError instanceof Error ? photoError.message : 'Unable to use this photo.');
    } finally {
      setPhotoBusy(false);
      event.target.value = '';
    }
  }

  async function saveProfile(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError('');
    setNotice('');
    setSaving(true);
    try {
      const response = await fetch('/api/v1/guardians/profile', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          displayName: draft.displayName,
          email: draft.email,
          city: draft.city,
          state: draft.state,
          occupation: draft.occupation,
          organization: draft.organization,
          preferredLanguage: draft.preferredLanguage,
          ...(photoChanged ? { profileImageDataUrl: draft.profileImageDataUrl } : {}),
        }),
      });
      const body = await readJson(response);
      if (!response.ok) throw new Error(body.message || 'Your profile could not be saved.');
      const url = body.profile_image_url || null;
      setIdentity({ mobile: body.mobile_e164 || '', profileImageUrl: url });
      setDraft({
        displayName: body.display_name || '',
        email: body.email || '',
        city: body.city || '',
        state: body.state || '',
        occupation: body.occupation || '',
        organization: body.organization || '',
        preferredLanguage: body.preferred_language || 'English',
        profileImageDataUrl: '',
      });
      setNotice(tx('आपकी parent profile save हो गई है।','Your parent profile has been saved.'));
      setPhotoName('');
      setPhotoChanged(false);
    } catch (saveError) {
      setError(saveError instanceof Error ? saveError.message : 'Your profile could not be saved. Please try again.');
    } finally {
      setSaving(false);
    }
  }

  function removePhoto() {
    setDraft(current => ({ ...current, profileImageDataUrl: '' }));
    setPhotoChanged(true);
    setPhotoName('');
    setNotice(tx('Draft से photo हट गई है। Confirm करने के लिए changes save करें।','The photo has been removed from the draft. Save your changes to confirm.'));
    setError('');
  }

  return (
    <main className="parent-app parent-profile-page">
      <header className="parent-header">
        <QuantaEdgeBrand variant="compact" />
        <span><LocaleText hinglish="Parent account" english="Parent account" /></span>
        <ParentAccountMenu displayName={draft.displayName || 'Parent account'} mobile={identity.mobile} profileImageUrl={photoChanged ? (draft.profileImageDataUrl || undefined) : (identity.profileImageUrl || undefined)} />
      </header>
      <div className="parent-profile-page__wrap">
        <div className="parent-profile-page__breadcrumb">
          <Link href="/parent"><LocaleText hinglish="← Family dashboard पर वापस" english="← Back to family dashboard" /></Link>
          <span><LocaleText hinglish="ACCOUNT" english="ACCOUNT" /></span>
        </div>
        <div className="parent-profile-page__heading">
          <div>
            <span className="eyebrow"><LocaleText hinglish="आपकी FAMILY · आपका ACCOUNT" english="YOUR FAMILY · YOUR ACCOUNT" /></span>
            <h1><LocaleText hinglish="Parent Profile और settings" english="Parent profile and settings" /></h1>
            <p><LocaleText hinglish="Contact और personal details updated रखें, ताकि family की learning manage करना आसान रहे।" english="Keep your contact and personal details up to date to manage your family’s learning easily." /></p>
          </div>
          <div className="parent-profile-page__account-pill"><span><LocaleText hinglish="ACCOUNT STATUS" english="ACCOUNT STATUS" /></span><strong><LocaleText hinglish="Mobile verified" english="Mobile verified" /></strong><small>{identity.mobile || 'Sign in ke liye use hone wala mobile'}</small></div>
        </div>

        {error && <div className="parent-profile-page__alert is-error" role="alert">{error}</div>}
        {notice && <div className="parent-profile-page__alert is-success" role="status">{notice}</div>}

        {loading ? <div className="parent-profile-page__loading"><span className="parent-profile-page__spinner" /> <LocaleText hinglish="आपकी profile load हो रही है…" english="Loading your profile…" /></div> : (
          <form className="parent-profile-form" onSubmit={saveProfile}>
            <section className="parent-profile-card parent-profile-photo-card">
              <div className="parent-profile-card__intro"><span className="parent-profile-card__number">01</span><div><h2><LocaleText hinglish="आपकी profile photo" english="Your profile photo" /></h2><p><LocaleText hinglish="आपके family account के लिए एक पहचान वाली photo।" english="A familiar photo for your family account." /></p></div></div>
              <div className="parent-profile-photo-editor">
                <div className="parent-profile-photo-editor__preview">
                  <img src={(photoChanged ? draft.profileImageDataUrl : (identity.profileImageUrl || draft.profileImageDataUrl)) || '/branding/parent-avatar.svg'} alt="Parent profile preview" />
                  <span className="parent-profile-photo-editor__camera" aria-hidden="true">↗</span>
                </div>
                <div className="parent-profile-photo-editor__copy">
                  <strong>{draft.profileImageDataUrl ? <LocaleText hinglish="Photo select हो गई" english="Photo selected" /> : <LocaleText hinglish="अपनी photo जोड़ें" english="Add your photo" />}</strong>
                  <p><LocaleText hinglish="Clear photo चुनें। Save करने से पहले photo resize और compress हो जाएगी।" english="Choose a clear photo. It will be resized and compressed before saving." /></p>
                  {photoName && <small className="parent-profile-photo-editor__filename">{photoName}</small>}
                  <div className="parent-profile-photo-editor__actions">
                    <input ref={fileInputRef} className="parent-profile-photo-editor__file" type="file" accept="image/*" onChange={handlePhotoChange} />
                    <button className="button button-dark button-small" type="button" disabled={photoBusy} onClick={() => fileInputRef.current?.click()}>{photoBusy ? <LocaleText hinglish="Photo तैयार हो रही है…" english="Preparing photo…" /> : <LocaleText hinglish="Photo upload करें" english="Upload photo" />}</button>
                    {draft.profileImageDataUrl && <button className="parent-profile-quiet-button" type="button" onClick={removePhoto}><LocaleText hinglish="Photo हटाएँ" english="Remove photo" /></button>}
                  </div>
                </div>
              </div>
            </section>

            <section className="parent-profile-card">
              <div className="parent-profile-card__intro"><span className="parent-profile-card__number">02</span><div><h2><LocaleText hinglish="Personal details" english="Personal details" /></h2><p><LocaleText hinglish="आपके parent account की details।" english="Your parent account details." /></p></div></div>
              <div className="parent-profile-fields">
                <label className="parent-profile-field parent-profile-field--wide"><LocaleText hinglish="पूरा नाम" english="Full name" /><input required minLength={2} maxLength={120} value={draft.displayName} onChange={e => setDraft({ ...draft, displayName: e.target.value })} placeholder={tx('अपना नाम डालें','Enter your name')} autoComplete="name" /></label>
                <label className="parent-profile-field"><LocaleText hinglish="Email address" english="Email address" /> <span className="parent-profile-field__optional"><LocaleText hinglish="Optional" english="Optional" /></span><input type="email" maxLength={254} value={draft.email} onChange={e => setDraft({ ...draft, email: e.target.value })} placeholder="you@example.com" autoComplete="email" /></label>
                <label className="parent-profile-field"><LocaleText hinglish="Mobile number" english="Mobile number" /><input value={identity.mobile} readOnly aria-readonly="true"/><small><LocaleText hinglish="OTP sign-in के लिए use होता है। इसे बदलने के लिए support से contact करें।" english="Used for OTP sign-in. Contact support if you need to change it." /></small></label>
                <label className="parent-profile-field"><LocaleText hinglish="शहर या town" english="City or town" /><input maxLength={100} value={draft.city} onChange={e => setDraft({ ...draft, city: e.target.value })} placeholder={tx('जैसे Patna','e.g. Patna')} autoComplete="address-level2" /></label>
                <label className="parent-profile-field"><LocaleText hinglish="State" english="State" /><input maxLength={100} value={draft.state} onChange={e => setDraft({ ...draft, state: e.target.value })} placeholder={tx('जैसे Bihar','e.g. Bihar')} autoComplete="address-level1" /></label>
              </div>
              <p className="parent-profile-privacy-note"><span aria-hidden="true">ⓘ</span> <LocaleText hinglish="City और state काफ़ी हैं। कृपया घर का पूरा address न डालें।" english="City and state are enough. Please do not enter your full home address." /></p>
            </section>

            <section className="parent-profile-card">
              <div className="parent-profile-card__intro"><span className="parent-profile-card__number">03</span><div><h2><LocaleText hinglish="आपके बारे में" english="About you" /></h2><p><LocaleText hinglish="Account को personalise करने के लिए optional details।" english="Optional details to personalise your account." /></p></div></div>
              <div className="parent-profile-fields">
                <label className="parent-profile-field"><LocaleText hinglish="आप क्या काम करते हैं?" english="What is your occupation?" /><input maxLength={120} value={draft.occupation} onChange={e => setDraft({ ...draft, occupation: e.target.value })} placeholder={tx('जैसे Teacher, business owner','e.g. Teacher, business owner')} autoComplete="organization-title" /></label>
                <label className="parent-profile-field"><LocaleText hinglish="Organisation या workplace" english="Organisation or workplace" /><input maxLength={180} value={draft.organization} onChange={e => setDraft({ ...draft, organization: e.target.value })} placeholder={tx('Optional','Optional')} autoComplete="organization" /></label>
                <label className="parent-profile-field parent-profile-field--wide"><LocaleText hinglish="Preferred language" english="Preferred language" /><select value={draft.preferredLanguage} onChange={e => setDraft({ ...draft, preferredLanguage: e.target.value })}><option value="English"><LocaleText hinglish="English" english="English" /></option><option value="Hindi">हिन्दी</option><option value="Hindi & English"><LocaleText hinglish="Hinglish (हिन्दी + English)" english="Hinglish (Hindi + English)" /></option><option value="Other"><LocaleText hinglish="Other" english="Other" /></option></select></label>
              </div>
            </section>

            <div className="parent-profile-form__footer">
              <p><LocaleText hinglish="इन updates से बच्चे की learning history या subject settings नहीं बदलेंगी।" english="These updates will not change your child’s learning history or subject settings." /></p>
              <div><Link href="/parent" className="parent-profile-quiet-button"><LocaleText hinglish="रद्द करें" english="Cancel" /></Link><button type="submit" className="button button-dark" disabled={saving || photoBusy}>{saving ? <LocaleText hinglish="Changes save हो रहे हैं…" english="Saving changes…" /> : <LocaleText hinglish="Changes save करें" english="Save changes" />}</button></div>
            </div>
          </form>
        )}
      </div>
    </main>
  );
}
