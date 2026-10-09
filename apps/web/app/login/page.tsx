'use client';

import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useState } from 'react';

type Child={id:number;public_id:string;display_name:string;class_code:string;class_name?:string};

async function readApiJson(response: Response): Promise<any> {
  const body = await response.text();
  try {
    return body ? JSON.parse(body) : {};
  } catch {
    if (!response.ok) {
      throw new Error(`QuantaEdge server error (${response.status}). The API may be unavailable; please retry after the service is healthy.`);
    }
    throw new Error(`QuantaEdge returned an unexpected response (${response.status}). Please retry.`);
  }
}

export default function LoginPage(){
  const router=useRouter();
  const [mobile,setMobile]=useState('');
  const [otp,setOtp]=useState('');
  const [displayName,setDisplayName]=useState('');
  const [step,setStep]=useState<'mobile'|'otp'|'child'>('mobile');
  const [child,setChild]=useState({displayName:'',classCode:'7',language:'hi',pin:''});
  const [consentAccepted,setConsentAccepted]=useState(false);
  const [children,setChildren]=useState<Child[]>([]);
  const [busy,setBusy]=useState(false);
  const [error,setError]=useState('');
  const [devCode,setDevCode]=useState('');

  async function requestOtp(){
    setError(''); setBusy(true);
    try{
      const r=await fetch('/api/v1/auth/request-otp',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({mobile,purpose:'LOGIN'})});
      const b=await readApiJson(r); if(!r.ok) throw new Error(b.message||'OTP request failed');
      setDevCode(b.devCode||''); setStep('otp');
    }catch(e:any){setError(e.message||'OTP request failed')}finally{setBusy(false)}
  }

  async function verifyOtp(){
    setError(''); setBusy(true);
    try{
      const r=await fetch('/api/v1/auth/verify-otp',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({mobile,otp,displayName:displayName||undefined})});
      const b=await readApiJson(r); if(!r.ok) throw new Error(b.message||'OTP verification failed');
      const cr=await fetch('/api/v1/guardians/children'); const cb=await readApiJson(cr);
      if(!cr.ok) throw new Error(cb.message||'Unable to load children');
      setChildren(cb);
      if(cb.length){ await selectChild(cb[0].id); }
      else setStep('child');
    }catch(e:any){setError(e.message||'OTP verification failed')}finally{setBusy(false)}
  }

  async function createChild(){
    setError(''); setBusy(true);
    try{
      const r=await fetch('/api/v1/guardians/children',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({...child,consentAccepted})});
      const b=await readApiJson(r); if(!r.ok) throw new Error(b.message||'Child creation failed');
      await selectChild(Number(b.id));
    }catch(e:any){setError(e.message||'Child creation failed')}finally{setBusy(false)}
  }

  async function selectChild(id:number){
    const r=await fetch('/api/v1/auth/select-student',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({studentId:id})});
    const b=await readApiJson(r); if(!r.ok) throw new Error(b.message||'Unable to open student');
    router.replace('/student');
  }

  return <main className="auth-page">
    <div className="auth-brand"><Link href="/" className="brand"><span className="brand-mark">Q</span><span><strong>Quanta</strong>Edge<small>LEARNING</small></span></Link></div>
    <section className="auth-card">
      <span className="eyebrow">{step==='child'?'Student setup':'Secure sign in'}</span>
      {step!=='child' ? <>
        <h1>अपनी पढ़ाई वहीं से शुरू करें।</h1>
        <p>Parent mobile से OTP verify करें। फिर बच्चे का learning profile चुनें या बनाएं।</p>
        <label>Mobile number<input value={mobile} onChange={e=>setMobile(e.target.value)} placeholder="10 digit mobile number" inputMode="numeric"/></label>
        {step==='otp' && <>
          <label>OTP<input value={otp} onChange={e=>setOtp(e.target.value)} placeholder="6 digit OTP" inputMode="numeric" maxLength={6}/></label>
          {devCode && <div className="feedback"><span>Local QA OTP</span><strong>{devCode}</strong></div>}
        </>}
        {step==='mobile' && <label>Parent name <input value={displayName} onChange={e=>setDisplayName(e.target.value)} placeholder="आपका नाम"/></label>}
        {error && <div className="feedback"><strong>समस्या</strong><span>{error}</span></div>}
        {step==='mobile'
          ? <button className="button button-dark full" disabled={busy||mobile.replace(/\D/g,'').length!==10} onClick={requestOtp}>{busy?'OTP भेज रहे हैं…':'OTP भेजें →'}</button>
          : <button className="button button-dark full" disabled={busy||otp.length!==6} onClick={verifyOtp}>{busy?'सत्यापित कर रहे हैं…':'OTP सत्यापित करें →'}</button>}
        <button className="text-link" onClick={()=>step==='otp'?setStep('mobile'):router.replace('/')}>← वापस</button>
      </> : <>
        <h1>बच्चे का learning profile बनाएं।</h1>
        <p>Class, language और सुरक्षित student PIN सेट करें। Parent consent इसी account से जुड़ा रहेगा।</p>
        <label>बच्चे का नाम<input value={child.displayName} onChange={e=>setChild({...child,displayName:e.target.value})} placeholder="जैसे आर्यन"/></label>
        <label>कक्षा<select value={child.classCode} onChange={e=>setChild({...child,classCode:e.target.value})}><option value="6">कक्षा 6</option><option value="7">कक्षा 7</option><option value="8">कक्षा 8</option></select></label>
        <label>Student PIN<input value={child.pin} onChange={e=>setChild({...child,pin:e.target.value})} placeholder="4–8 digits" inputMode="numeric" maxLength={8}/></label>
        <label className="consent-row">
          <input type="checkbox" checked={consentAccepted} onChange={e=>setConsentAccepted(e.target.checked)}/>
          <span>मैं इस बच्चे का अधिकृत अभिभावक हूँ और उसकी learning profile बनाने तथा learning records रखने की सहमति देता/देती हूँ।</span>
        </label>
        {error && <div className="feedback"><strong>समस्या</strong><span>{error}</span></div>}
        <button className="button button-dark full" disabled={busy||!child.displayName.trim()||child.pin.length<4||!consentAccepted} onClick={createChild}>{busy?'Profile बना रहे हैं…':'Student profile बनाएं →'}</button>
      </>}
      <small className="auth-note">Production OTP provider और guardian records server-side configured हैं।</small>
    </section>
  </main>;
}
