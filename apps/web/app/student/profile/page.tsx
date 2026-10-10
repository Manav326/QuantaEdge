'use client';

import { LocaleText } from '../../components/LanguageProvider';

import Link from 'next/link';
import { ChangeEvent, useEffect, useRef, useState } from 'react';
import { useRouter } from 'next/navigation';
import QuantaEdgeBrand from '../../components/QuantaEdgeBrand';
import StudentAccountMenu from '../../components/StudentAccountMenu';

type ProfileDraft = {
  displayName: string;
  city: string;
  state: string;
  schoolName: string;
  schoolMedium: string;
  favoriteSubject: string;
  learningGoal: string;
  profileImageDataUrl: string;
};

const emptyProfile: ProfileDraft = {
  displayName: '',
  city: '',
  state: '',
  schoolName: '',
  schoolMedium: '',
  favoriteSubject: '',
  learningGoal: '',
  profileImageDataUrl: '',
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
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [draft, setDraft] = useState<ProfileDraft>(emptyProfile);
  const [student, setStudent] = useState<{class_code: string; board: string} | null>(null);
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
        setStudent({ class_code: String(body.class_code || ''), board: String(body.board || '') });
        setDraft({
          displayName: body.display_name || '',
          city: body.city || '',
          state: body.state || '',
          schoolName: body.school_name || '',
          schoolMedium: body.school_medium || '',
          favoriteSubject: body.favorite_subject || '',
          learningGoal: body.learning_goal || '',
          profileImageDataUrl: body.profile_image_data_url || '',
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
      setPhotoName(file.name);
      setNotice('Photo ready. Changes save karein taaki photo profile par rahe.');
    } catch (photoError) {
      setError(photoError instanceof Error ? photoError.message : 'Unable to use this photo.');
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
      setStudent({ class_code: String(body.class_code || ''), board: String(body.board || '') });
      setDraft({
        displayName: body.display_name || '',
        city: body.city || '',
        state: body.state || '',
        schoolName: body.school_name || '',
        schoolMedium: body.school_medium || '',
        favoriteSubject: body.favorite_subject || '',
        learningGoal: body.learning_goal || '',
        profileImageDataUrl: body.profile_image_data_url || '',
      });
      setNotice('Aapki profile save ho gayi hai.');
    } catch (saveError) {
      setError(saveError instanceof Error ? saveError.message : 'Your profile could not be saved. Please try again.');
    } finally {
      setSaving(false);
    }
  }

  function resetPhoto() {
    setDraft(current => ({ ...current, profileImageDataUrl: '' }));
    setPhotoName('');
    setNotice('Draft se photo hat gayi hai. Confirm karne ke liye changes save karein.');
    setError('');
  }

  return (
    <main className="app-shell student-profile-page">
      <header className="app-header">
        <QuantaEdgeBrand variant="compact" />
        <StudentAccountMenu displayName={draft.displayName || 'Student'} classCode={student?.class_code || '—'} profileImageUrl={draft.profileImageDataUrl} />
      </header>
      <div className="student-profile-page__wrap">
        <div className="student-profile-page__breadcrumb">
          <Link href="/student">← Dashboard par wapas</Link>
          <span>ACCOUNT</span>
        </div>
        <div className="student-profile-page__heading">
          <div>
            <span className="eyebrow">AAPKI SPACE · AAPKI JOURNEY</span>
            <h1>Profile aur settings</h1>
            <p>Apni learning space ko personalise karein. Photo aur details update kar sakte hain.</p>
          </div>
          <div className="student-profile-page__class-pill"><span>AAPKI CLASS</span><strong>{student?.class_code ? `Class ${student.class_code}` : '—'}</strong><small>{student?.board || 'School board'}</small></div>
        </div>

        {error && <div className="student-profile-page__alert is-error" role="alert">{error}</div>}
        {notice && <div className="student-profile-page__alert is-success" role="status">{notice}</div>}

        {loading ? <div className="student-profile-page__loading"><span className="student-profile-page__spinner" /> Loading your profile…</div> : (
          <form className="student-profile-form" onSubmit={saveProfile}>
            <section className="student-profile-card student-profile-photo-card">
              <div className="student-profile-card__intro"><span className="student-profile-card__number">01</span><div><h2>Aapki profile photo</h2><p>Aapki learning space ke liye ek friendly photo.</p></div></div>
              <div className="student-profile-photo-editor">
                <div className="student-profile-photo-editor__preview">
                  <img src={draft.profileImageDataUrl || '/branding/student-avatar.svg'} alt="Student profile preview" />
                  <span className="student-profile-photo-editor__camera" aria-hidden="true">↗</span>
                </div>
                <div className="student-profile-photo-editor__copy">
                  <strong>{draft.profileImageDataUrl ? 'Looking good!' : 'Add your photo'}</strong>
                  <p>Apni clear photo choose karein. Save karne se pehle hum ise automatically resize kar denge.</p>
                  {photoName && <small className="student-profile-photo-editor__filename">{photoName}</small>}
                  <div className="student-profile-photo-editor__actions">
                    <input ref={fileInputRef} className="student-profile-photo-editor__file" type="file" accept="image/*" onChange={handlePhotoChange} />
                    <button className="button button-dark button-small" type="button" disabled={photoBusy} onClick={() => fileInputRef.current?.click()}>{photoBusy ? 'Photo ready ho rahi hai…' : 'Photo upload karein'}</button>
                    {draft.profileImageDataUrl && <button className="student-profile-quiet-button" type="button" onClick={resetPhoto}>Photo hataayein</button>}
                  </div>
                </div>
              </div>
            </section>

            <section className="student-profile-card">
              <div className="student-profile-card__intro"><span className="student-profile-card__number">02</span><div><h2>Aapke baare mein</h2><p>Apni basic details updated rakhein.</p></div></div>
              <div className="student-profile-fields">
                <label className="student-profile-field student-profile-field--wide">Aapka naam<input required minLength={2} maxLength={120} value={draft.displayName} onChange={e => setDraft({ ...draft, displayName: e.target.value })} placeholder="Aapko kis naam se bulayein?" autoComplete="name" /></label>
                <label className="student-profile-field">Shehar ya town<input maxLength={100} value={draft.city} onChange={e => setDraft({ ...draft, city: e.target.value })} placeholder="e.g. Patna" autoComplete="address-level2" /></label>
                <label className="student-profile-field">State<input maxLength={100} value={draft.state} onChange={e => setDraft({ ...draft, state: e.target.value })} placeholder="e.g. Bihar" autoComplete="address-level1" /></label>
              </div>
              <p className="student-profile-privacy-note"><span aria-hidden="true">ⓘ</span> City and state are enough. Please do not enter your full home address.</p>
            </section>

            <section className="student-profile-card">
              <div className="student-profile-card__intro"><span className="student-profile-card__number">03</span><div><h2>School aur learning</h2><p>Apni learning ko aur relevant banane mein help karein.</p></div></div>
              <div className="student-profile-fields">
                <label className="student-profile-field student-profile-field--wide">School ka naam<input maxLength={180} value={draft.schoolName} onChange={e => setDraft({ ...draft, schoolName: e.target.value })} placeholder="School ka naam daalein" autoComplete="organization" /></label>
                <label className="student-profile-field">School medium<select value={draft.schoolMedium} onChange={e => setDraft({ ...draft, schoolMedium: e.target.value })}><option value="">Agar pata ho to choose karein</option><option value="Hindi">Hindi</option><option value="English">English</option><option value="Hindi & English">Hinglish (Hindi + English)</option><option value="Other">Other</option></select></label>
                <label className="student-profile-field">Favourite subject<select value={draft.favoriteSubject} onChange={e => setDraft({ ...draft, favoriteSubject: e.target.value })}><option value="">Subject choose karein</option><option value="maths">Maths · गणित</option><option value="science">Science · विज्ञान</option><option value="both">Both · दोनों</option><option value="other">Koi aur subject</option><option value="not_sure">Abhi explore kar rahe hain</option></select></label>
                <label className="student-profile-field student-profile-field--wide">Mera learning goal<textarea rows={3} maxLength={300} value={draft.learningGoal} onChange={e => setDraft({ ...draft, learningGoal: e.target.value })} placeholder="Aap kis cheez mein better hona chahte hain? Jaise, fractions solve karne mein confidence badhana." /><small>{draft.learningGoal.length}/300 characters</small></label>
              </div>
            </section>

            <div className="student-profile-form__footer">
              <p>Ye details update karne par bhi aapki progress aur practice records save rahenge.</p>
              <div><Link href="/student" className="student-profile-quiet-button">Cancel</Link><button type="submit" className="button button-dark" disabled={saving || photoBusy}>{saving ? 'Changes save ho rahe hain…' : 'Changes save karein'}</button></div>
            </div>
          </form>
        )}
      </div>
    </main>
  );
}
