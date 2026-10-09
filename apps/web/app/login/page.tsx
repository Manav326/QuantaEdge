'use client';

import QuantaEdgeBrand from '../components/QuantaEdgeBrand';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useState } from 'react';

type AuthMode='register'|'login';

async function readApiJson(response: Response): Promise<any> {
  const body = await response.text();
  try {
    return body ? JSON.parse(body) : {};
  } catch {
    if (!response.ok) {
      if (response.status === 403) {
        throw new Error('Request blocked (403). The local API may not allow this browser origin. Pull the latest main and run the local rebuild script, then retry.');
      }
      throw new Error(`QuantaEdge request failed (HTTP ${response.status}). Please retry.`);
    }
    throw new Error(`QuantaEdge returned an unexpected response (${response.status}). Please retry.`);
  }
}

export default function LoginPage(){
  const router=useRouter();
  const [mode,setMode]=useState<AuthMode>('register');
  const [mobile,setMobile]=useState('');
  const [otp,setOtp]=useState('');
  const [displayName,setDisplayName]=useState('');
  const [step,setStep]=useState<'mobile'|'otp'|'child'>('mobile');
  const [child,setChild]=useState({displayName:'',classCode:'7',language:'hi',pin:'',trackCodes:['maths','science'] as string[]});
  const [consentAccepted,setConsentAccepted]=useState(false);
  const [busy,setBusy]=useState(false);
  const [error,setError]=useState('');
  const [devCode,setDevCode]=useState('');

  function switchMode(nextMode:AuthMode){
    setMode(nextMode);
    setStep('mobile');
    setOtp('');
    setDevCode('');
    setError('');
    setDisplayName('');
  }

  async function requestOtp(){
    setError(''); setBusy(true);
    try{
      const purpose=mode==='register'?'SIGNUP':'LOGIN';
      const r=await fetch('/api/v1/auth/request-otp',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({mobile:mobile.trim(),purpose})});
      const b=await readApiJson(r);
      if(!r.ok){
        const fallback = r.status === 400
          ? (mode === 'login'
              ? 'No existing account was found or the OTP request is temporarily limited. If this is your first visit, choose Register.'
              : 'Check the mobile number and retry after any OTP cooldown.')
          : `OTP request failed (HTTP ${r.status})`;
        throw new Error(b.message || fallback);
      }
      setDevCode(b.devCode||''); setOtp(''); setStep('otp');
    }catch(e:any){setError(e.message||'OTP request failed')}finally{setBusy(false)}
  }

  async function verifyOtp(){
    setError(''); setBusy(true);
    try{
      const purpose=mode==='register'?'SIGNUP':'LOGIN';
      const payload:Record<string,string>={mobile:mobile.trim(),otp:otp.trim(),purpose};
      if(mode==='register') payload.displayName=displayName.trim();
      const r=await fetch('/api/v1/auth/verify-otp',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(payload)});
      const b=await readApiJson(r);
      if(!r.ok) throw new Error(b.message||`OTP verification failed (HTTP ${r.status})`);
      const cr=await fetch('/api/v1/guardians/children');
      const cb=await readApiJson(cr);
      if(!cr.ok) throw new Error(cb.message||`Unable to load children (HTTP ${cr.status})`);
      if(Array.isArray(cb)&&cb.length){ await selectChild(cb[0].id); }
      else setStep('child');
    }catch(e:any){setError(e.message||'OTP verification failed')}finally{setBusy(false)}
  }

  async function createChild(){
    setError(''); setBusy(true);
    try{
      const r=await fetch('/api/v1/guardians/children',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({...child,consentAccepted})});
      const b=await readApiJson(r); if(!r.ok) throw new Error(b.message||`Child creation failed (HTTP ${r.status})`);
      await selectChild(Number(b.id));
    }catch(e:any){setError(e.message||'Child creation failed')}finally{setBusy(false)}
  }

  async function selectChild(id:number){
    const r=await fetch('/api/v1/auth/select-student',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({studentId:id})});
    const b=await readApiJson(r); if(!r.ok) throw new Error(b.message||`Unable to open student (HTTP ${r.status})`);
    router.replace('/student');
  }

  return <main className="auth-page">
    <div className="auth-brand"><QuantaEdgeBrand variant="auth" /></div>
    <section className="auth-card">
      <span className="eyebrow">{step==='child'?'Student setup':mode==='register'?'Create parent account':'Secure sign in'}</span>
      {step!=='child' ? <>
        <h1>{mode==='register'?'पहली बार आए हैं? अकाउंट बनाएं।':'अपने अकाउंट में वापस आएं।'}</h1>
        <p>{mode==='register'?'पहले Parent account verify करें। OTP सही होने के बाद बच्चे का learning profile बना सकेंगे।':'Registered parent mobile पर OTP लेकर सुरक्षित रूप से लॉग इन करें।'}</p>
        {step==='mobile' && <div role="group" aria-label="Account type" style={{display:'flex',gap:8,margin:'14px 0 18px'}}>
          <button type="button" className={`button button-small ${mode==='register'?'button-dark':'button-light'}`} aria-pressed={mode==='register'} onClick={()=>switchMode('register')}>नया अकाउंट</button>
          <button type="button" className={`button button-small ${mode==='login'?'button-dark':'button-light'}`} aria-pressed={mode==='login'} onClick={()=>switchMode('login')}>पहले से अकाउंट है? Login</button>
        </div>}
        {mode==='register' && step==='mobile' && <label>Parent name<input value={displayName} onChange={e=>setDisplayName(e.target.value)} placeholder="आपका नाम" autoComplete="name"/></label>}
        <label>Mobile number<input value={mobile} onChange={e=>setMobile(e.target.value)} placeholder="10 digit mobile number" inputMode="numeric" autoComplete="tel" /></label>
        {step==='otp' && <>
          <label>OTP<input value={otp} onChange={e=>setOtp(e.target.value)} placeholder="6 digit OTP" inputMode="numeric" maxLength={6} autoComplete="one-time-code"/></label>
          {devCode && <div className="feedback"><span>Local QA OTP</span><strong>{devCode}</strong></div>}
        </>}
        {error && <div className="feedback"><strong>{error.includes('403')?'Request blocked':'समस्या'}</strong><span>{error}</span></div>}
        {step==='mobile'
          ? <button className="button button-dark full" disabled={busy||mobile.replace(/\D/g,'').length!==10||(mode==='register'&&displayName.trim().length<2)} onClick={requestOtp}>{busy?'OTP भेज रहे हैं…':mode==='register'?'Register करें और OTP लें →':'Login OTP भेजें →'}</button>
          : <button className="button button-dark full" disabled={busy||otp.trim().length!==6} onClick={verifyOtp}>{busy?'सत्यापित कर रहे हैं…':mode==='register'?'Verify OTP और अकाउंट बनाएं →':'Verify OTP और Login करें →'}</button>}
        <button type="button" className="text-link" onClick={()=>step==='otp'?(setStep('mobile'),setOtp(''),setDevCode(''),setError('')):router.replace('/')}>← वापस</button>
      </> : <>
        <h1>बच्चे का learning profile बनाएं।</h1>
        <p>Class, language और सुरक्षित student PIN सेट करें। Parent consent इसी account से जुड़ा रहेगा।</p>
        <label>बच्चे का नाम<input value={child.displayName} onChange={e=>setChild({...child,displayName:e.target.value})} placeholder="जैसे आर्यन" autoComplete="off"/></label>
        <label>कक्षा<select value={child.classCode} onChange={e=>setChild({...child,classCode:e.target.value})}><option value="6">कक्षा 6</option><option value="7">कक्षा 7</option><option value="8">कक्षा 8</option></select></label>
        <label>Student PIN<input value={child.pin} onChange={e=>setChild({...child,pin:e.target.value})} placeholder="4–8 digits" inputMode="numeric" maxLength={8} autoComplete="new-password"/></label>
        <fieldset className="track-choices" style={{border:'1px solid var(--line)',borderRadius:12,padding:'12px 14px',margin:'0 0 16px'}}>
          <legend style={{fontSize:13,fontWeight:700,padding:'0 5px'}}>Learning tracks</legend>
          <p style={{fontSize:12,color:'var(--muted)',margin:'2px 0 10px'}}>Choose subjects this child can access. You can change this later.</p>
          {[{code:'maths',label:'गणित · Maths'},{code:'science',label:'विज्ञान · Science'}].map(track=><label key={track.code} style={{display:'flex',alignItems:'center',gap:9,margin:'8px 0',fontSize:14}}>
            <input type="checkbox" checked={child.trackCodes.includes(track.code)} onChange={e=>setChild({...child,trackCodes:e.target.checked?[...child.trackCodes,track.code]:child.trackCodes.filter(code=>code!==track.code)})}/>
            <span>{track.label}</span>
          </label>)}
        </fieldset>
        <label className="consent-row">
          <input type="checkbox" checked={consentAccepted} onChange={e=>setConsentAccepted(e.target.checked)}/>
          <span>मैं इस बच्चे का अधिकृत अभिभावक हूँ और उसकी learning profile बनाने तथा learning records रखने की सहमति देता/देती हूँ।</span>
        </label>
        {error && <div className="feedback"><strong>समस्या</strong><span>{error}</span></div>}
        <button className="button button-dark full" disabled={busy||!child.displayName.trim()||child.pin.length<4||child.trackCodes.length<1||!consentAccepted} onClick={createChild}>{busy?'Profile बना रहे हैं…':'Student profile बनाएं →'}</button>
      </>}
      <small className="auth-note">OTP verification is required before a parent account is created. Local preview mode displays a development OTP.</small>
    </section>
  </main>;
}
