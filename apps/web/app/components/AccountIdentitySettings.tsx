'use client';

import { useCallback, useEffect, useState, type FormEvent } from 'react';
import Link from 'next/link';
import { useLocale } from './LanguageProvider';

type AccountProfile = {
  displayName?: string | null;
  accountType?: string | null;
  role?: string | null;
  email?: string | null;
  gmailVerified?: boolean;
  mobile?: string | null;
  mobileVerified?: boolean;
  verifiedMethods?: string[];
  profileImageUrl?: string | null;
  googleGivenName?: string | null;
  googleFamilyName?: string | null;
  googleLocale?: string | null;
  username?: string | null;
  usernameCanChange?: boolean;
  usernameCanChangeAt?: string | null;
  passwordSet?: boolean;
  preferredLocale?: string | null;
  credentialsManagedByParent?: boolean;
  canRequestParentAccount?: boolean;
};

async function readJson(response: Response): Promise<any> {
  const body = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(body.message || body.detail || body.error || 'The request could not be completed.');
  return body;
}

export default function AccountIdentitySettings() {
  const { locale, setLocale } = useLocale();
  const english = locale === 'english';
  const [profile, setProfile] = useState<AccountProfile | null>(null);
  const [displayName, setDisplayName] = useState('');
  const [username, setUsername] = useState('');
  const [currentPassword, setCurrentPassword] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState('');
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const body = await readJson(await fetch('/api/v1/account/me/profile', {
        credentials: 'same-origin', cache: 'no-store',
      }));
      setProfile(body);
      setDisplayName(String(body.displayName || ''));
      setUsername(String(body.username || ''));
      const savedLocale = String(body.preferredLocale || '');
      if (savedLocale && savedLocale !== locale) {
        // The browser preference remains authoritative for this device. Account language
        // is updated when the user explicitly saves the profile.
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Account details could not be loaded.');
    } finally {
      setLoading(false);
    }
  }, [locale]);

  useEffect(() => { void load(); }, [load]);

  async function saveProfile(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setBusy('profile'); setError(''); setNotice('');
    try {
      await readJson(await fetch('/api/v1/account/me/profile', {
        method: 'PUT', credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ displayName: displayName.trim(), preferredLocale: locale }),
      }));
      setNotice(english ? 'Profile details saved.' : 'प्रोफ़ाइल विवरण सुरक्षित हो गए।');
      window.dispatchEvent(new Event('qe:profile-updated'));
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Profile details could not be saved.');
    } finally { setBusy(''); }
  }

  async function saveUsername(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setBusy('username'); setError(''); setNotice('');
    try {
      const body = await readJson(await fetch('/api/v1/account/me/username', {
        method: 'PUT', credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username: username.trim() }),
      }));
      setNotice(body.unchanged
        ? (english ? 'This is already your username.' : 'यह पहले से आपका यूज़रनेम है।')
        : (english ? 'Username saved. A new change is allowed after 15 days.' : 'यूज़रनेम सुरक्षित। अगला बदलाव 15 दिन बाद कर सकते हैं।'));
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Username could not be saved.');
    } finally { setBusy(''); }
  }

  async function savePassword(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError(''); setNotice('');
    if (newPassword.length < 8) {
      setError(english ? 'Use a password with at least 8 characters.' : 'कम से कम 8 अक्षरों का पासवर्ड रखें।');
      return;
    }
    if (newPassword !== confirmPassword) {
      setError(english ? 'The passwords do not match.' : 'दोनों पासवर्ड एक जैसे नहीं हैं।');
      return;
    }
    setBusy('password');
    try {
      const body = await readJson(await fetch('/api/v1/account/me/password', {
        method: 'PUT', credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ currentPassword, newPassword }),
      }));
      setCurrentPassword(''); setNewPassword(''); setConfirmPassword('');
      setNotice(english
        ? 'Password saved. For security, sign in again to continue.'
        : 'पासवर्ड सुरक्षित हो गया। सुरक्षा के लिए दोबारा साइन इन करें।');
      if (body.reauthenticationRequired) {
        window.setTimeout(() => { window.location.assign('/login'); }, 900);
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Password could not be saved.');
    } finally { setBusy(''); }
  }

  const changeDate = profile?.usernameCanChangeAt
    ? new Date(profile.usernameCanChangeAt).toLocaleString(english ? 'en-IN' : 'hi-IN', { dateStyle: 'medium', timeStyle: 'short' })
    : '';
  const accountType = String(profile?.accountType || 'USER');

  return (
    <section className="qe-account-identity-settings" aria-labelledby="qe-account-identity-title">
      <header className="qe-account-identity-settings__header">
        <div>
          <span className="eyebrow">{english ? 'ACCOUNT & SIGN-IN' : 'खाता और साइन-इन'}</span>
          <h2 id="qe-account-identity-title">{english ? 'Account identity' : 'खाते की पहचान'}</h2>
          <p>{english ? 'Your verified sign-in methods, username and password live here.' : 'सत्यापित साइन-इन तरीके, यूज़रनेम और पासवर्ड यहाँ प्रबंधित करें।'}</p>
        </div>
        {profile?.profileImageUrl
          ? <img className="qe-account-identity-settings__avatar" src={profile.profileImageUrl} alt={english ? 'Google profile photo' : 'Google प्रोफ़ाइल फ़ोटो'} referrerPolicy="no-referrer" />
          : <div className="qe-account-identity-settings__avatar qe-account-identity-settings__avatar--empty" aria-hidden="true">{(profile?.displayName || 'Q').trim().slice(0, 1).toUpperCase()}</div>}
      </header>
      {error && <div className="auth-message is-error" role="alert">{error}</div>}
      {notice && <div className="auth-message" role="status">{notice}</div>}
      {loading && <p role="status" className="auth-note">{english ? 'Loading account settings…' : 'खाते की सेटिंग लोड हो रही हैं…'}</p>}
      {!loading && profile && <>
        <div className="qe-account-identity-settings__verification">
          <div className="qe-account-identity-settings__verification-row">
            <span className="qe-account-identity-settings__provider-mark" aria-hidden="true">G</span>
            <div className="qe-account-identity-settings__identity-copy">
              <strong>Gmail</strong>
              <span>{profile.email || (english ? 'No Gmail linked' : 'Gmail लिंक नहीं है')}</span>
            </div>
            <span className={profile.gmailVerified ? 'qe-verified-state is-verified' : 'qe-verified-state'}>
              {profile.gmailVerified ? '✓ ' + (english ? 'Verified' : 'सत्यापित') : (english ? 'Not verified' : 'सत्यापित नहीं')}
            </span>
          </div>
          <div className="qe-account-identity-settings__verification-row">
            <span className="qe-account-identity-settings__provider-mark qe-account-identity-settings__provider-mark--mobile" aria-hidden="true">⌕</span>
            <div className="qe-account-identity-settings__identity-copy">
              <strong>{english ? 'Mobile number' : 'मोबाइल नंबर'}</strong>
              <span>{profile.mobile || (english ? 'No mobile linked' : 'मोबाइल नंबर लिंक नहीं है')}</span>
            </div>
            <span className={profile.mobileVerified ? 'qe-verified-state is-verified' : 'qe-verified-state'}>
              {profile.mobileVerified ? '✓ ' + (english ? 'Verified' : 'सत्यापित') : (english ? 'Not verified' : 'सत्यापित नहीं')}
            </span>
          </div>
          <p className="auth-note">{english
            ? 'Verification is recorded separately for each method. A Google photo or name does not verify a mobile number, and a mobile OTP does not verify Gmail.'
            : 'हर तरीके का सत्यापन अलग दर्ज होता है। Google की फ़ोटो या नाम मोबाइल सत्यापित नहीं करता और मोबाइल OTP Gmail को सत्यापित नहीं करता।'}</p>
        </div>

        {profile.credentialsManagedByParent ? (
          <div className="qe-account-identity-settings__managed" role="note">
            <strong>{english ? 'Parent-managed learner account' : 'अभिभावक द्वारा प्रबंधित learner खाता'}</strong>
            <p>{english ? 'Only your parent can change this username or password. Ask them to update it from your child profile.' : 'इस यूज़रनेम या पासवर्ड को केवल आपके अभिभावक बदल सकते हैं। उनसे आपकी child profile में इसे अपडेट करने को कहें।'}</p>
          </div>
        ) : <>
          <form className="qe-account-identity-settings__form" onSubmit={saveProfile}>
            <h3>{english ? 'Profile details' : 'प्रोफ़ाइल विवरण'}</h3>
            <label>{english ? 'Full name' : 'पूरा नाम'}
              <input value={displayName} onChange={e => setDisplayName(e.target.value)} minLength={2} maxLength={120} required />
            </label>
            <label>{english ? 'Interface language' : 'इंटरफ़ेस भाषा'}
              <select value={locale} onChange={e => setLocale(e.target.value as typeof locale)}>
                <option value="hinglish">हिन्दी</option>
                <option value="english">English</option>
                <option value="bhojpuri">भोजपुरी</option>
              </select>
            </label>
            <button type="submit" className="button button-dark button-small" disabled={busy !== '' || displayName.trim().length < 2}>
              {busy === 'profile' ? (english ? 'Saving…' : 'सहेज रहे हैं…') : (english ? 'Save profile' : 'प्रोफ़ाइल सुरक्षित करें')}
            </button>
          </form>

          <form className="qe-account-identity-settings__form" onSubmit={saveUsername}>
            <h3>{english ? 'Username' : 'यूज़रनेम'}</h3>
            <label>{english ? 'Your username' : 'आपका यूज़रनेम'}
              <input value={username} onChange={e => setUsername(e.target.value.toLowerCase().replace(/[^a-z0-9._-]/g, '').slice(0, 32))}
                minLength={3} maxLength={32} autoComplete="username" required />
            </label>
            {profile.username && !profile.usernameCanChange && changeDate && <p className="auth-note">
              {english ? 'Next username change: ' : 'अगला यूज़रनेम बदलाव: '}{changeDate}
            </p>}
            <p className="auth-note">{english ? 'Choose a unique username. After each change, you must wait at least 15 days before changing it again.' : 'अलग यूज़रनेम चुनें। हर बदलाव के बाद अगला बदलाव करने से पहले कम से कम 15 दिन प्रतीक्षा करनी होगी।'}</p>
            <button type="submit" className="button button-light button-small"
              disabled={busy !== '' || !profile.usernameCanChange || username.trim().length < 3 || username.trim() === profile.username}>
              {busy === 'username' ? (english ? 'Saving…' : 'सहेज रहे हैं…') : (english ? 'Save username' : 'यूज़रनेम सुरक्षित करें')}
            </button>
          </form>

          <form className="qe-account-identity-settings__form" onSubmit={savePassword}>
            <h3>{profile.passwordSet ? (english ? 'Change password' : 'पासवर्ड बदलें') : (english ? 'Set a password' : 'पासवर्ड सेट करें')}</h3>
            {profile.passwordSet && <label>{english ? 'Current password' : 'मौजूदा पासवर्ड'}
              <input type="password" value={currentPassword} onChange={e => setCurrentPassword(e.target.value)}
                autoComplete="current-password" required />
            </label>}
            <label>{english ? 'New password' : 'नया पासवर्ड'}
              <input type="password" value={newPassword} onChange={e => setNewPassword(e.target.value)}
                minLength={8} autoComplete="new-password" required />
            </label>
            <label>{english ? 'Confirm new password' : 'नए पासवर्ड की पुष्टि करें'}
              <input type="password" value={confirmPassword} onChange={e => setConfirmPassword(e.target.value)}
                minLength={8} autoComplete="new-password" required />
            </label>
            <button type="submit" className="button button-dark button-small"
              disabled={busy !== '' || newPassword.length < 8 || newPassword !== confirmPassword || (profile.passwordSet === true && !currentPassword)}>
              {busy === 'password' ? (english ? 'Saving…' : 'सहेज रहे हैं…') : (english ? 'Save password' : 'पासवर्ड सुरक्षित करें')}
            </button>
          </form>
        </>}

        <div className="qe-account-identity-settings__account-type">
          <span>{english ? 'Account type' : 'खाते का प्रकार'}</span>
          <strong>{accountType === 'PARENT' ? (english ? 'Parent' : 'अभिभावक') : accountType === 'CHILD' ? (english ? 'Young Learner' : 'बाल शिक्षार्थी') : (english ? 'Regular user' : 'सामान्य उपयोगकर्ता')}</strong>
          {profile.canRequestParentAccount && accountType !== 'PARENT' && <p>{english
            ? 'A regular learner can request parent-account eligibility from the account-lifecycle page. The required adult verification and review still apply.'
            : 'सामान्य learner खाता account-lifecycle पेज से parent eligibility माँग सकता है। आवश्यक वयस्कता सत्यापन और समीक्षा लागू रहेंगे।'} <Link href="/student/account-lifecycle">{english ? 'Review eligibility' : 'पात्रता देखें'}</Link></p>}
          {accountType === 'PARENT' && <p>{english
            ? 'You can purchase courses for your own learning or a linked child from the family course marketplace.'
            : 'आप family course marketplace से अपने लिए या जुड़े बच्चे के लिए कोर्स खरीद सकते हैं।'} <Link href="/parent/courses">{english ? 'Family courses' : 'परिवार के कोर्स'}</Link></p>}
        </div>
      </>}
    </section>
  );
}
