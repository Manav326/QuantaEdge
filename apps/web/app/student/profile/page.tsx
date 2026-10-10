'use client';

import { LocaleText, useLocale } from '../../components/LanguageProvider';

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
  const { locale } = useLocale();
  const tx = (hinglish: string, english: string) => locale === 'english' ? english : hinglish;
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
      setNotice(tx('Photo ready है। Profile पर रखने के लिए changes save करें।','Photo is ready. Save your changes to keep it on your profile.'));
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
      setNotice(tx('आपकी profile save हो गई है।','Your profile has been saved.'));
    } catch (saveError) {
      setError(saveError instanceof Error ? saveError.message : 'Your profile could not be saved. Please try again.');
    } finally {
      setSaving(false);
    }
  }

  function resetPhoto() {
    setDraft(current => ({ ...current, profileImageDataUrl: '' }));
    setPhotoName('');
    setNotice(tx('Draft से photo हट गई है। Confirm करने के लिए changes save करें।','The photo has been removed from the draft. Save your changes to confirm.'));
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

        {loading ? <div className="student-profile-page__loading"><span className="student-profile-page__spinner" /> <LocaleText hinglish="आपकी profile load हो रही है…" english="Loading your profile…" /></div> : (
          <form className="student-profile-form" onSubmit={saveProfile}>
            <section className="student-profile-card student-profile-photo-card">
              <div className="student-profile-card__intro"><span className="student-profile-card__number">01</span><div><h2><LocaleText hinglish="आपकी profile photo" english="Your profile photo" /></h2><p><LocaleText hinglish="आपकी learning space के लिए एक friendly photo।" english="A friendly photo for your learning space." /></p></div></div>
              <div className="student-profile-photo-editor">
                <div className="student-profile-photo-editor__preview">
                  <img src={draft.profileImageDataUrl || '/branding/student-avatar.svg'} alt="Student profile preview" />
                  <span className="student-profile-photo-editor__camera" aria-hidden="true">↗</span>
                </div>
                <div className="student-profile-photo-editor__copy">
                  <strong>{draft.profileImageDataUrl ? <LocaleText hinglish="अच्छा लग रहा है!" english="Looking good!" /> : <LocaleText hinglish="अपनी photo जोड़ें" english="Add your photo" />}</strong>
                  <p><LocaleText hinglish="अपनी clear photo चुनें। Save करने से पहले इसे automatically resize किया जाएगा।" english="Choose a clear photo. We’ll resize it automatically before saving." /></p>
                  {photoName && <small className="student-profile-photo-editor__filename">{photoName}</small>}
                  <div className="student-profile-photo-editor__actions">
                    <input ref={fileInputRef} className="student-profile-photo-editor__file" type="file" accept="image/*" onChange={handlePhotoChange} />
                    <button className="button button-dark button-small" type="button" disabled={photoBusy} onClick={() => fileInputRef.current?.click()}>{photoBusy ? <LocaleText hinglish="Photo तैयार हो रही है…" english="Preparing photo…" /> : <LocaleText hinglish="Photo upload करें" english="Upload photo" />}</button>
                    {draft.profileImageDataUrl && <button className="student-profile-quiet-button" type="button" onClick={resetPhoto}><LocaleText hinglish="Photo हटाएँ" english="Remove photo" /></button>}
                  </div>
                </div>
              </div>
            </section>

            <section className="student-profile-card">
              <div className="student-profile-card__intro"><span className="student-profile-card__number">02</span><div><h2><LocaleText hinglish="आपके बारे में" english="About you" /></h2><p><LocaleText hinglish="अपनी basic details updated रखें।" english="Keep your basic details up to date." /></p></div></div>
              <div className="student-profile-fields">
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
