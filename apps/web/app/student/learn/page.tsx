'use client';

import Link from 'next/link';
import { useState } from 'react';

export default function LearnPage() {
  const [help, setHelp] = useState('none');
  const [answered, setAnswered] = useState(false);

  return <main className="lesson-page">
    <header className="lesson-header"><Link href="/student" className="back">← आज</Link><span className="lesson-progress">Lesson 1 of 3 · 40%</span><span className="avatar">अ</span></header>
    <section className="lesson-wrap">
      <div className="lesson-meta"><span className="eyebrow">कक्षा 7 · गणित · Algebra</span><span>~12 min</span></div>
      <h1>Variable आखिर होता क्या है?</h1>
      <p className="lesson-intro">जब किसी संख्या की value हमें पता नहीं होती, तो हम उसे एक letter से दिखा सकते हैं। यही variable है।</p>
      <div className="concept-card"><span className="concept-kicker">एक आसान example</span><div className="equation"><span>□</span><b> + 3 = 8</b></div><p>यहाँ खाली जगह में कौन-सी संख्या आएगी?</p><div className="answer-row">{[3,5,8,11].map(n=><button key={n} className={answered&&n===5?'correct':''} onClick={()=>setAnswered(true)}>{n}</button>)}</div>{answered&&<div className="feedback"><b>बहुत बढ़िया! ✓</b><span>5 + 3 = 8, इसलिए unknown value 5 है।</span></div>}</div>
      <div className="ai-help"><div className="ai-icon">✦</div><div><strong>समझ नहीं आया?</strong><p>{help==='easy'?'Variable को ऐसे सोचो जैसे एक खाली डिब्बा, जिसमें कोई भी संख्या हो सकती है।':help==='example'?'अगर x + 2 = 5 है, तो x वह संख्या है जो 2 के साथ मिलकर 5 बनाए।':help==='steps'?'पहले 3 को दूसरी तरफ ले जाओ। 8 − 3 = 5, इसलिए खाली जगह 5 है।':'तुरंत answer नहीं देंगे — पहले hint से सोचेंगे।'}</p><div className="hint-row"><button onClick={()=>setHelp('easy')}>आसान भाषा में समझाओ</button><button onClick={()=>setHelp('example')}>Example से समझाओ</button><button onClick={()=>setHelp('steps')}>Step-by-step</button></div></div></div>
      <div className="lesson-next"><span>अगला: Algebraic expressions</span><Link href="/student/practice" className="button button-dark button-small">आगे बढ़ें →</Link></div>
    </section>
  </main>;
}
