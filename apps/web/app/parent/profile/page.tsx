'use client';

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
      setNotice('Photo ready. Select Save changes to keep it on your profile.');
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
      setNotice('Your parent profile has been saved.');
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
    setNotice('Photo removed from this draft. Save changes to confirm.');
    setError('');
  }

  return (
    <main className="parent-app parent-profile-page">
      <header className="parent-header">
        <QuantaEdgeBrand variant="compact" />
        <span>Parent account</span>
        <ParentAccountMenu displayName={draft.displayName || 'Parent account'} mobile={identity.mobile} profileImageUrl={draft.profileImageDataUrl || identity.profileImageUrl || undefined} />
      </header>
      <div className="parent-profile-page__wrap">
        <div className="parent-profile-page__breadcrumb">
          <Link href="/parent">← Back to family dashboard</Link>
          <span>ACCOUNT</span>
        </div>
        <div className="parent-profile-page__heading">
          <div>
            <span className="eyebrow">YOUR FAMILY · YOUR ACCOUNT</span>
            <h1>Parent profile &amp; settings</h1>
            <p>Keep your contact and personal details current so managing your family’s learning is easier.</p>
          </div>
          <div className="parent-profile-page__account-pill"><span>ACCOUNT STATUS</span><strong>Verified mobile</strong><small>{identity.mobile || 'Mobile used for sign-in'}</small></div>
        </div>

        {error && <div className="parent-profile-page__alert is-error" role="alert">{error}</div>}
        {notice && <div className="parent-profile-page__alert is-success" role="status">{notice}</div>}

        {loading ? <div className="parent-profile-page__loading"><span className="parent-profile-page__spinner" /> Loading your profile…</div> : (
          <form className="parent-profile-form" onSubmit={saveProfile}>
            <section className="parent-profile-card parent-profile-photo-card">
              <div className="parent-profile-card__intro"><span className="parent-profile-card__number">01</span><div><h2>Your profile photo</h2><p>A familiar face for your family account.</p></div></div>
              <div className="parent-profile-photo-editor">
                <div className="parent-profile-photo-editor__preview">
                  <img src={draft.profileImageDataUrl || identity.profileImageUrl || '/branding/parent-avatar.svg'} alt="Parent profile preview" />
                  <span className="parent-profile-photo-editor__camera" aria-hidden="true">↗</span>
                </div>
                <div className="parent-profile-photo-editor__copy">
                  <strong>{draft.profileImageDataUrl ? 'Photo selected' : 'Add your photo'}</strong>
                  <p>Choose a clear photo. It is resized and compressed before being saved.</p>
                  {photoName && <small className="parent-profile-photo-editor__filename">{photoName}</small>}
                  <div className="parent-profile-photo-editor__actions">
                    <input ref={fileInputRef} className="parent-profile-photo-editor__file" type="file" accept="image/*" onChange={handlePhotoChange} />
                    <button className="button button-dark button-small" type="button" disabled={photoBusy} onClick={() => fileInputRef.current?.click()}>{photoBusy ? 'Preparing photo…' : 'Upload photo'}</button>
                    {draft.profileImageDataUrl && <button className="parent-profile-quiet-button" type="button" onClick={removePhoto}>Remove photo</button>}
                  </div>
                </div>
              </div>
            </section>

            <section className="parent-profile-card">
              <div className="parent-profile-card__intro"><span className="parent-profile-card__number">02</span><div><h2>Personal details</h2><p>Information for your parent account.</p></div></div>
              <div className="parent-profile-fields">
                <label className="parent-profile-field parent-profile-field--wide">Full name<input required minLength={2} maxLength={120} value={draft.displayName} onChange={e => setDraft({ ...draft, displayName: e.target.value })} placeholder="Your name" autoComplete="name" /></label>
                <label className="parent-profile-field">Email address <span className="parent-profile-field__optional">Optional</span><input type="email" maxLength={254} value={draft.email} onChange={e => setDraft({ ...draft, email: e.target.value })} placeholder="you@example.com" autoComplete="email" /></label>
                <label className="parent-profile-field">Mobile number<input value={identity.mobile} readOnly aria-readonly="true"/><small>Used for OTP sign-in. Contact support if it needs to change.</small></label>
                <label className="parent-profile-field">City or town<input maxLength={100} value={draft.city} onChange={e => setDraft({ ...draft, city: e.target.value })} placeholder="e.g. Patna" autoComplete="address-level2" /></label>
                <label className="parent-profile-field">State<input maxLength={100} value={draft.state} onChange={e => setDraft({ ...draft, state: e.target.value })} placeholder="e.g. Bihar" autoComplete="address-level1" /></label>
              </div>
              <p className="parent-profile-privacy-note"><span aria-hidden="true">ⓘ</span> City and state are enough. Please do not enter your full home address.</p>
            </section>

            <section className="parent-profile-card">
              <div className="parent-profile-card__intro"><span className="parent-profile-card__number">03</span><div><h2>About you</h2><p>Optional details to help personalise your account.</p></div></div>
              <div className="parent-profile-fields">
                <label className="parent-profile-field">Occupation<input maxLength={120} value={draft.occupation} onChange={e => setDraft({ ...draft, occupation: e.target.value })} placeholder="e.g. Teacher, business owner" autoComplete="organization-title" /></label>
                <label className="parent-profile-field">Organisation or workplace<input maxLength={180} value={draft.organization} onChange={e => setDraft({ ...draft, organization: e.target.value })} placeholder="Optional" autoComplete="organization" /></label>
                <label className="parent-profile-field parent-profile-field--wide">Preferred language<select value={draft.preferredLanguage} onChange={e => setDraft({ ...draft, preferredLanguage: e.target.value })}><option value="English">English</option><option value="Hindi">हिन्दी</option><option value="Hindi & English">Hindi &amp; English</option><option value="Other">Other</option></select></label>
              </div>
            </section>

            <div className="parent-profile-form__footer">
              <p>Your child’s learning history and subject settings are not changed by these updates.</p>
              <div><Link href="/parent" className="parent-profile-quiet-button">Cancel</Link><button type="submit" className="button button-dark" disabled={saving || photoBusy}>{saving ? 'Saving changes…' : 'Save changes'}</button></div>
            </div>
          </form>
        )}
      </div>
    </main>
  );
}
