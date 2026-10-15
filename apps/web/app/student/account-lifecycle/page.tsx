'use client';

import { LocaleText, useLocale } from '../../components/LanguageProvider';
import Link from 'next/link';
import {useCallback,useEffect,useState} from 'react';
import {useRouter} from 'next/navigation';

type Lifecycle = {
  user_account_id?:number|null;
  role?:string|null;
  adult_verification_status?:string|null;
  adult_verification_current?:boolean;
  parent_eligibility_status?:string|null;
  parent_eligibility_current?:boolean;
  independentAccountLinked?:boolean;
  canTransitionToParent?:boolean;
};

async function readBody(response:Response):Promise<any>{
  const body=await response.json().catch(()=>({}));
  if(!response.ok)throw new Error(body.message||body.error||'Request could not be completed.');
  return body;
}

export default function StudentAccountLifecyclePage(){
  const router=useRouter();
  const {locale}=useLocale();
  const en=locale==='english';
  const [data,setData]=useState<Lifecycle|null>(null);
  const [mobile,setMobile]=useState('');
  const [otp,setOtp]=useState('');
  const [password,setPassword]=useState('');
  const [devCode,setDevCode]=useState('');
  const [busy,setBusy]=useState(false);
  const [error,setError]=useState('');
  const [notice,setNotice]=useState('');

  const refresh=useCallback(async()=>{
    const response=await fetch('/api/v1/account/me/lifecycle',{credentials:'same-origin',cache:'no-store'});
    if(response.status===401||response.status===403){router.replace('/login/student');return;}
    const body=await readBody(response);
    setData(body);
  },[router]);

  useEffect(()=>{refresh().catch((e:any)=>setError(e.message||'Unable to load account lifecycle status.'));},[refresh]);

  async function run(action:()=>Promise<void>){
    setBusy(true);setError('');setNotice('');
    try{await action();}
    catch(e:any){setError(e.message||'Request could not be completed.');}
    finally{setBusy(false);}
  }

  function requestAdultReview(){
    return run(async()=>{
      await readBody(await fetch('/api/v1/account/me/adult-verification-request',{
        method:'POST',credentials:'same-origin',headers:{'Content-Type':'application/json'},
        body:JSON.stringify({consentAccepted:true})
      }));
      setNotice(en?'Adult verification request sent for staff review.':'वयस्कता सत्यापन अनुरोध staff review के लिए भेज दिया गया है।');
      await refresh();
    });
  }

  function requestConversionOtp(){
    return run(async()=>{
      const body=await readBody(await fetch('/api/v1/auth/learner-transition/request-otp',{
        method:'POST',credentials:'same-origin',headers:{'Content-Type':'application/json'},
        body:JSON.stringify({mobile,consentAccepted:true})
      }));
      setDevCode(String(body.devCode||''));
      setNotice(en?'Verification code requested. It expires in five minutes.':'सत्यापन कोड माँगा गया है। यह पाँच मिनट में expire होगा।');
    });
  }

  function completeConversion(){
    return run(async()=>{
      await readBody(await fetch('/api/v1/auth/verify-otp',{
        method:'POST',credentials:'same-origin',headers:{'Content-Type':'application/json'},
        body:JSON.stringify({mobile,otp,purpose:'LEARNER_TRANSITION',password})
      }));
      setNotice(en?'Independent account created. Your learner history is retained; redirecting…':'Independent account बन गया है। आपकी learning history सुरक्षित है; redirect हो रहा है…');
      router.replace('/student');
      router.refresh();
    });
  }

  function requestParentReview(){
    return run(async()=>{
      await readBody(await fetch('/api/v1/account/me/parent-eligibility-request',{
        method:'POST',credentials:'same-origin',headers:{'Content-Type':'application/json'},
        body:JSON.stringify({consentAccepted:true})
      }));
      setNotice(en?'Parent eligibility request sent for staff review.':'Parent eligibility अनुरोध staff review के लिए भेज दिया गया है।');
      await refresh();
    });
  }

  function transitionToParent(){
    if(!window.confirm(en
      ?'Confirm transition to a parent account? Your user identity and learner history stay linked. You will need to sign in again.'
      :'क्या आप parent account में transition की पुष्टि करते हैं? आपकी user identity और learning history linked रहेंगी। आपको फिर से sign in करना होगा।'))return;
    void run(async()=>{
      await readBody(await fetch('/api/v1/account/me/transition-to-parent',{
        method:'POST',credentials:'same-origin',headers:{'Content-Type':'application/json'},
        body:JSON.stringify({confirmParentTransition:true})
      }));
      router.replace('/login');
      router.refresh();
    });
  }

  const adultCurrent=Boolean(data?.adult_verification_current);
  const linked=Boolean(data?.independentAccountLinked);
  const role=String(data?.role||'');
  return <main className="app-shell">
    <section className="app-content">
      <div className="content-heading"><div><span className="eyebrow"><LocaleText hinglish="ACCOUNT & ELIGIBILITY" english="ACCOUNT & ELIGIBILITY" /></span><h1><LocaleText hinglish="Account lifecycle" english="Account lifecycle" /></h1></div><Link href="/student" className="text-link"><LocaleText hinglish="← Student home" english="← Student home" /></Link></div>
      <p><LocaleText hinglish="Account type बदलने से पहले verification आवश्यक है। आपकी मौजूदा learner identity, enrolments, progress, attempts, results और certificates replace नहीं किए जाएँगे।" english="Verification is required before changing account type. Your existing learner identity, enrolments, progress, attempts, results, and certificates will not be replaced." /></p>
      {error&&<div className="feedback" role="alert">{error}</div>}
      {notice&&<div className="feedback" role="status">{notice}</div>}
      {!data?<p className="eyebrow"><LocaleText hinglish="Status load हो रहा है…" english="Loading status…" /></p>:<>
        <article className="app-task">
          <div><strong><LocaleText hinglish="वयस्कता सत्यापन" english="Adult verification" /></strong>
            <small>{en?'Status: ':'स्थिति: '}{data.adult_verification_status||'NOT_REQUESTED'}{adultCurrent?(en?' · Current':' · मान्य'):''}</small>
            <small><LocaleText hinglish="Independent account conversion के लिए उम्र 18 से अधिक होने की staff verification आवश्यक है।" english="Staff verification that you are older than 18 is required before independent account conversion." /></small>
          </div>
          {!adultCurrent&&<button className="button button-dark" disabled={busy||data.adult_verification_status==='PENDING'} onClick={requestAdultReview}><LocaleText hinglish="Verification request करें" english="Request verification" /></button>}
        </article>
        {!linked&&<article className="app-task">
          <div><strong><LocaleText hinglish="Independent learner account" english="Independent learner account" /></strong>
            <small><LocaleText hinglish="Verified होने के बाद अपना mobile verify करें और password बनाएँ। आपकी मौजूदा learner profile ही linked रहेगी।" english="After verification, verify your mobile and create a password. Your existing learner profile remains linked." /></small>
          </div>
          <div className="parent-child-form">
            <label><LocaleText hinglish="आपका mobile number" english="Your mobile number" /><input inputMode="tel" autoComplete="tel" value={mobile} onChange={e=>setMobile(e.target.value)} placeholder="10-digit Indian mobile" /></label>
            {adultCurrent&&<button type="button" className="button button-light button-small" disabled={busy||mobile.replace(/\D/g,'').length<10} onClick={requestConversionOtp}><LocaleText hinglish="OTP भेजें" english="Send OTP" /></button>}
            {devCode&&<p className="feedback" role="status">{en?'Development OTP: ':'Development OTP: '}{devCode}</p>}
            {devCode!==''||notice.toLowerCase().includes('verification code')||notice.includes('सत्यापन कोड')?<><label><LocaleText hinglish="OTP" english="OTP" /><input inputMode="numeric" autoComplete="one-time-code" value={otp} onChange={e=>setOtp(e.target.value)} maxLength={6} /></label><label><LocaleText hinglish="नया password (कम से कम 8 characters)" english="New password (at least 8 characters)" /><input type="password" autoComplete="new-password" value={password} onChange={e=>setPassword(e.target.value)} /></label><button type="button" className="button button-dark" disabled={busy||otp.trim().length<6||password.length<8} onClick={completeConversion}><LocaleText hinglish="Independent account बनाएँ" english="Create independent account" /></button></>:null}
          </div>
        </article>}
        {linked&&role==='LEARNER'&&<article className="app-task">
          <div><strong><LocaleText hinglish="Parent eligibility" english="Parent eligibility" /></strong>
            <small>{en?'Status: ':'स्थिति: '}{data.parent_eligibility_status||'NOT_REQUESTED'}{data.parent_eligibility_current?(en?' · Current':' · मान्य'):''}</small>
            <small><LocaleText hinglish="Parent बनने के लिए current verified adult status और अलग parent eligibility approval दोनों आवश्यक हैं।" english="Becoming a parent requires current adult verification and separate parent eligibility approval." /></small>
          </div>
          {!data.parent_eligibility_current&&<button className="button button-dark" disabled={busy||data.parent_eligibility_status==='PENDING'} onClick={requestParentReview}><LocaleText hinglish="Parent eligibility request करें" english="Request parent eligibility" /></button>}
          {data.canTransitionToParent&&<button className="button button-dark" disabled={busy} onClick={transitionToParent}><LocaleText hinglish="Parent account में transition करें" english="Transition to parent account" /></button>}
        </article>}
      </>}
    </section>
  </main>;
}
