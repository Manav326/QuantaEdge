'use client';

import AccountIdentitySettings from '../../components/AccountIdentitySettings';
import { UiText, useUiTranslation } from '../../i18n/useUiTranslation';
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

type ParentIdentity = { mobile: string; publicUserId: string; profileImageUrl: string | null };

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
  const { locale, setLocale } = useLocale();
  const t = useUiTranslation();
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [draft, setDraft] = useState<ParentProfileDraft>(emptyProfile);
  const [identity, setIdentity] = useState<ParentIdentity>({ mobile: '', publicUserId: '', profileImageUrl: null });
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
        setIdentity({ mobile: body.mobile_e164 || '', publicUserId: body.public_user_id || body.publicUserId || '', profileImageUrl: url });
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
      const response = await fetch('/api/v1/guardians/profile-photo', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ profileImageDataUrl }),
      });
      const body = await readJson(response);
      if (!response.ok) throw new Error(body.message || 'Your photo could not be saved.');
      setIdentity(current => ({ ...current, profileImageUrl: body.profile_image_url || null }));
      setDraft(current => ({ ...current, profileImageDataUrl: '' }));
      setPhotoChanged(false);
      setPhotoName(file.name);
      setNotice(t('parent.profile.photoSaved'));
      window.dispatchEvent(new Event('qe:profile-updated'));
    } catch (photoError) {
      setError(photoError instanceof Error ? photoError.message : 'Unable to save this photo.');
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
      setIdentity({ mobile: body.mobile_e164 || '', publicUserId: body.public_user_id || body.publicUserId || '', profileImageUrl: url });
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
      if (body.preferred_language === 'English') setLocale('english');
      else if (body.preferred_language === 'Hindi' || body.preferred_language === 'Hindi & English') setLocale('hinglish');
      setNotice(t('parent.profile.saved'));
      window.dispatchEvent(new Event('qe:profile-updated'));
      setPhotoName('');
      setPhotoChanged(false);
    } catch (saveError) {
      setError(saveError instanceof Error ? saveError.message : 'Your profile could not be saved. Please try again.');
    } finally {
      setSaving(false);
    }
  }

  async function removePhoto() {
    setError('');
    setNotice('');
    setPhotoBusy(true);
    try {
      const response = await fetch('/api/v1/guardians/profile-photo', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ profileImageDataUrl: '' }),
      });
      const body = await readJson(response);
      if (!response.ok) throw new Error(body.message || 'Your photo could not be removed.');
      setIdentity(current => ({ ...current, profileImageUrl: body.profile_image_url || null }));
      setDraft(current => ({ ...current, profileImageDataUrl: '' }));
      setPhotoChanged(false);
      setPhotoName('');
      setNotice(t('parent.profile.photoRemoved'));
      window.dispatchEvent(new Event('qe:profile-updated'));
    } catch (photoError) {
      setError(photoError instanceof Error ? photoError.message : 'Your photo could not be removed.');
    } finally {
      setPhotoBusy(false);
    }
  }

  return (
    <main className="parent-app parent-profile-page">
      <div className="parent-profile-page__wrap">
        <div className="parent-profile-page__breadcrumb">
          <Link href="/parent"><UiText message="legacy.web.back.to.family.dashboard.81f4e6f2" /></Link>
          <span><UiText message="legacy.web.account.c25755c1" /></span>
        </div>
        <div className="parent-profile-page__heading">
          <div>
            <span className="eyebrow"><UiText message="legacy.web.your.family.your.account.329be01f" /></span>
            <h1><UiText message="legacy.web.parent.profile.and.settings.72a419fa" /></h1>
            <p><UiText message="legacy.web.keep.your.contact.and.personal.details.93d90adb" /></p>
          </div>
          <div className="parent-profile-page__account-pill"><span><UiText message="legacy.web.account.status.3bcb293f" /></span><strong><UiText message="legacy.web.mobile.verified.c2cc5255" /></strong><small>{identity.mobile || 'Sign in ke liye use hone wala mobile'}</small></div>
        </div>

        {error && <div className="parent-profile-page__alert is-error" role="alert">{error}</div>}
        {notice && <div className="parent-profile-page__alert is-success" role="status">{notice}</div>}

        {loading ? <div className="parent-profile-page__loading"><span className="parent-profile-page__spinner" /> <UiText message="legacy.web.loading.your.profile.81ab5bfd" /></div> : (
          <form className="parent-profile-form" onSubmit={saveProfile}>
            <section className="parent-profile-card parent-profile-photo-card">
              <div className="parent-profile-card__intro"><span className="parent-profile-card__number">01</span><div><h2><UiText message="legacy.web.your.profile.photo.feca3523" /></h2><p><UiText message="legacy.web.a.familiar.photo.for.your.family.accou.409a3461" /></p></div></div>
              <div className="parent-profile-photo-editor">
                <div className="parent-profile-photo-editor__preview">
                  <img src={(photoChanged ? draft.profileImageDataUrl : (identity.profileImageUrl || draft.profileImageDataUrl)) || '/branding/parent-avatar.svg'} alt="Parent profile preview" />
                  <span className="parent-profile-photo-editor__camera" aria-hidden="true">↗</span>
                </div>
                <div className="parent-profile-photo-editor__copy">
                  <strong>{((photoChanged && draft.profileImageDataUrl) || (!photoChanged && identity.profileImageUrl)) ? <UiText message="legacy.web.your.photo.is.set.6e00a1b6" /> : <UiText message="legacy.web.add.your.photo.4ad345e3" />}</strong>
                  <p><UiText message="legacy.web.choose.a.clear.photo.it.will.be.resize.846a62f4" /></p>
                  {photoName && <small className="parent-profile-photo-editor__filename">{photoName}</small>}
                  <div className="parent-profile-photo-editor__actions">
                    <input ref={fileInputRef} className="parent-profile-photo-editor__file" type="file" accept="image/*" onChange={handlePhotoChange} />
                    <button className="button button-dark button-small" type="button" disabled={photoBusy} onClick={() => fileInputRef.current?.click()}>{photoBusy ? <UiText message="legacy.web.preparing.photo.75f074e6" /> : (photoChanged ? Boolean(draft.profileImageDataUrl) : Boolean(identity.profileImageUrl)) ? <UiText message="legacy.web.change.photo.5b1bc1d0" /> : <UiText message="legacy.web.choose.photo.de5a3f02" />}</button>
                    {identity.profileImageUrl && <button className="parent-profile-quiet-button" type="button" disabled={photoBusy} onClick={removePhoto}><UiText message="legacy.web.remove.photo.89a3c097" /></button>}
                  </div>
                </div>
              </div>
            </section>

            <section className="parent-profile-card">
              <div className="parent-profile-card__intro"><span className="parent-profile-card__number">02</span><div><h2><UiText message="legacy.web.personal.details.2c540009" /></h2><p><UiText message="legacy.web.your.parent.account.details.32242915" /></p></div></div>
              <div className="parent-profile-fields">
                <label className="parent-profile-field parent-profile-field--wide"><UiText message="legacy.web.full.name.f7576097" /><input required minLength={2} maxLength={120} value={draft.displayName} onChange={e => setDraft({ ...draft, displayName: e.target.value })} placeholder={t('parent.profile.namePlaceholder')} autoComplete="name" /></label>
                <label className="parent-profile-field"><UiText message="legacy.web.email.address.8779888d" /> <span className="parent-profile-field__optional"><UiText message="legacy.web.optional.7371513d" /></span><input type="email" maxLength={254} value={draft.email} onChange={e => setDraft({ ...draft, email: e.target.value })} placeholder="you@example.com" autoComplete="email" /></label>
                <label className="parent-profile-field"><UiText message="legacy.web.mobile.number.678acd99" /><input value={identity.mobile} readOnly aria-readonly="true"/><small><UiText message="legacy.web.used.for.otp.sign.in.contact.support.i.c11e2aed" /></small></label>
                <label className="parent-profile-field"><UiText message="legacy.web.your.user.id.fc35660b" /><input value={identity.publicUserId} readOnly aria-readonly="true" /><small><UiText message="legacy.web.this.12.character.id.is.permanent.and..17b68e20" /></small></label>
                <label className="parent-profile-field"><UiText message="legacy.web.city.or.town.70475d4d" /><input maxLength={100} value={draft.city} onChange={e => setDraft({ ...draft, city: e.target.value })} placeholder={t('parent.profile.cityPlaceholder')} autoComplete="address-level2" /></label>
                <label className="parent-profile-field"><UiText message="legacy.web.state.a0c7035d" /><input maxLength={100} value={draft.state} onChange={e => setDraft({ ...draft, state: e.target.value })} placeholder={t('parent.profile.statePlaceholder')} autoComplete="address-level1" /></label>
              </div>
              <p className="parent-profile-privacy-note"><span aria-hidden="true">ⓘ</span> <UiText message="legacy.web.city.and.state.are.enough.please.do.no.8802cd0a" /></p>
            </section>

            <section className="parent-profile-card">
              <div className="parent-profile-card__intro"><span className="parent-profile-card__number">03</span><div><h2><UiText message="legacy.web.about.you.fdc8ac97" /></h2><p><UiText message="legacy.web.optional.details.to.personalise.your.a.31049107" /></p></div></div>
              <div className="parent-profile-fields">
                <label className="parent-profile-field"><UiText message="legacy.web.what.is.your.occupation.d860ed36" /><input maxLength={120} value={draft.occupation} onChange={e => setDraft({ ...draft, occupation: e.target.value })} placeholder={t('parent.profile.occupationPlaceholder')} autoComplete="organization-title" /></label>
                <label className="parent-profile-field"><UiText message="legacy.web.organisation.or.workplace.2ab67251" /><input maxLength={180} value={draft.organization} onChange={e => setDraft({ ...draft, organization: e.target.value })} placeholder={t('parent.profile.optionalPlaceholder')} autoComplete="organization" /></label>
                <label className="parent-profile-field parent-profile-field--wide"><UiText message="legacy.web.preferred.learning.language.7005a1e9" /><select value={draft.preferredLanguage} onChange={e => setDraft({ ...draft, preferredLanguage: e.target.value })}><option value="English"><UiText message="common.language.english" /></option><option value="Hindi">हिन्दी</option><option value="Hindi & English"><UiText message="legacy.web.hinglish.hindi.english.d8cec8ba" /></option><option value="Other"><UiText message="legacy.web.other.affe01d9" /></option></select></label>
              </div>
            </section>

            <div className="parent-profile-form__footer">
              <p><UiText message="legacy.web.these.updates.will.not.change.your.chi.70c5c76e" /></p>
              <div><Link href="/parent" className="parent-profile-quiet-button"><UiText message="legacy.web.cancel.843e86d4" /></Link><button type="submit" className="button button-dark" disabled={saving || photoBusy}>{saving ? <UiText message="legacy.web.saving.changes.b6c09973" /> : <UiText message="legacy.web.save.changes.a38830f7" />}</button></div>
            </div>
          </form>
        )}
        <AccountIdentitySettings />
      </div>
    </main>
  );
}
