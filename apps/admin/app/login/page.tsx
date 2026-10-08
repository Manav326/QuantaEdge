'use client';

import Link from 'next/link';
import {useRouter} from 'next/navigation';
import {useState} from 'react';

export default function AdminLogin(){
  const router=useRouter(); const [mobile,setMobile]=useState(''); const [otp,setOtp]=useState(''); const [step,setStep]=useState(1); const [error,setError]=useState(''); const [busy,setBusy]=useState(false); const [dev,setDev]=useState('');
  async function requestOtp(){setError('');setBusy(true);try{const r=await fetch('/api/v1/auth/request-otp',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({mobile,purpose:'LOGIN'})});const b=await r.json();if(!r.ok)throw new Error(b.message||'OTP failed');setDev(b.devCode||'');setStep(2)}catch(e:any){setError(e.message||'OTP failed')}finally{setBusy(false)}}
  async function verify(){setError('');setBusy(true);try{const r=await fetch('/api/v1/auth/verify-otp',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({mobile,otp})});const b=await r.json();if(!r.ok)throw new Error(b.message||'Verification failed');if(b.role!=='ADMIN')throw new Error('यह mobile admin access के लिए configured नहीं है');router.replace('/')}catch(e:any){setError(e.message||'Verification failed')}finally{setBusy(false)}}
  return <main className="auth-page"><div className="auth-brand"><Link href="/" className="admin-brand">QuantaEdge · ADMIN</Link></div><section className="auth-card"><span className="eyebrow">Admin access</span><h1>Content operations</h1><p>Authorized admin mobile से OTP verify करें।</p>{step===1?<label>Mobile<input value={mobile} onChange={e=>setMobile(e.target.value)} inputMode="numeric" placeholder="Admin mobile"/></label>:<label>OTP<input value={otp} onChange={e=>setOtp(e.target.value)} inputMode="numeric" maxLength={6} placeholder="6 digit OTP"/></label>}{dev&&<div className="feedback"><span>Local QA OTP</span><strong>{dev}</strong></div>}{error&&<div className="feedback"><strong>Access denied</strong><span>{error}</span></div>}{step===1?<button className="button button-dark full" disabled={busy||mobile.replace(/\D/g,'').length!==10} onClick={requestOtp}>OTP भेजें →</button>:<button className="button button-dark full" disabled={busy||otp.length!==6} onClick={verify}>Verify →</button>}</section></main>;
}
