'use client';

import Link from 'next/link';
import { useState } from 'react';

export default function PracticePage() {
  const [selected, setSelected] = useState<number | null>(null);
  const correct = selected === 5;
  return <main className="practice-page">
    <header className="lesson-header"><Link href="/student" className="back">← आज</Link><span>Practice · 2 / 10</span><span className="avatar">अ</span></header>
    <section className="practice-wrap"><div className="eyebrow">Algebra · आसान से शुरू</div><h1>अगर x + 7 = 12 है,<br />तो x कितना होगा?</h1><p>जल्दी मत करो। पहले सोचो: 7 को हटाने के लिए क्या करना पड़ेगा?</p>
      <div className="option-grid">{[3,5,7,19].map(n=><button key={n} className={selected===n?'selected':''} onClick={()=>setSelected(n)}>{n}</button>)}</div>
      {selected!==null&&<div className={`practice-feedback ${correct?'':'wrong'}`}><strong>{correct?'✓ सही जवाब':'अभी नहीं'}</strong><span>{correct?'x = 5, क्योंकि 5 + 7 = 12.':'एक बार फिर सोचें: 7 को हटाने के लिए 12 में से 7 घटाइए।'}</span></div>}
      <div className="practice-footer"><span>Accuracy <b>{correct?'100%':'—'}</b> · Streak 🔥 2</span><Link href={correct?'/student/progress':'/student/learn'} className="button button-dark">{correct?'अगला सवाल →':'Hint लें →'}</Link></div>
    </section>
  </main>;
}
