'use client';

import Link from 'next/link';
import {useRouter} from 'next/navigation';
import {useState} from 'react';

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

export default function AdminLogin(){
  const router=useRouter(); const [mobile,setMobile]=useState(''); const [otp,setOtp]=useState(''); const [step,setStep]=useState(1); const [error,setError]=useState(''); const [busy,setBusy]=useState(false); const [dev,setDev]=useState('');
  async function requestOtp(){setError('');setBusy(true);try{const r=await fetch('/api/v1/auth/request-otp',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({mobile,purpose:'STAFF_LOGIN'})});const b=await readApiJson(r);if(!r.ok)throw new Error(b.message||'OTP failed');setDev(b.devCode||'');setStep(2)}catch(e:any){setError(e.message||'OTP failed')}finally{setBusy(false)}}
  async function verify(){setError('');setBusy(true);try{const r=await fetch('/api/v1/auth/verify-otp',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({mobile,otp,purpose:'STAFF_LOGIN'})});const b=await readApiJson(r);if(!r.ok)throw new Error(b.message||'Verification failed');const mr=await fetch('/api/v1/auth/me',{cache:'no-store'});const me=await readApiJson(mr);if(!mr.ok||!me.staffId)throw new Error('This is not a staff session. Please use learner sign-in on the learning site.');const permissions=Array.isArray(me.permissions)?me.permissions:[];if(me.role==='ADMIN')router.replace('/');else if(permissions.includes('CONTENT_VIEW'))router.replace('/content');else if(permissions.includes('AUDIT_VIEW'))router.replace('/employees?view=audit');else throw new Error('This staff account has no workspace permissions. Contact an administrator.')}catch(e:any){setError(e.message||'Verification failed')}finally{setBusy(false)}}
  return <main className="auth-page"><div className="auth-brand"><Link href="/" className="admin-brand" aria-label="QuantaEdge staff console"><span className="brand-mark">Q</span><span><strong>QuantaEdge</strong><small>STAFF CONSOLE</small></span></Link></div><section className="auth-card"><span className="eyebrow">Staff access</span><h1>Content operations</h1><p>Use the mobile number assigned to your QuantaEdge staff account.</p>{step===1?<label>Mobile<input value={mobile} onChange={e=>setMobile(e.target.value)} inputMode="numeric" placeholder="Staff mobile number"/></label>:<label>OTP<input value={otp} onChange={e=>setOtp(e.target.value)} inputMode="numeric" maxLength={6} placeholder="6 digit OTP"/></label>}{dev&&<div className="feedback"><span>Local QA OTP</span><strong>{dev}</strong></div>}{error&&<div className="feedback"><strong>Access denied</strong><span>{error}</span></div>}{step===1?<button className="button button-dark full" disabled={busy||mobile.replace(/\D/g,'').length!==10} onClick={requestOtp}>OTP भेजें →</button>:<button className="button button-dark full" disabled={busy||otp.length!==6} onClick={verify}>Verify →</button>}</section></main>;
}
