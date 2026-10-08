import Link from 'next/link';

export default function LearnPage() {
  return <main className="lesson-page">
    <header className="lesson-header"><Link href="/student" className="back">← आज</Link><span className="lesson-progress">Lesson 1 of 3 · 40%</span><span className="avatar">अ</span></header>
    <section className="lesson-wrap">
      <div className="lesson-meta"><span className="eyebrow">कक्षा 7 · गणित · Algebra</span><span>~12 min</span></div>
      <h1>Variable आखिर होता क्या है?</h1>
      <p className="lesson-intro">जब किसी संख्या की value हमें पता नहीं होती, तो हम उसे एक letter से दिखा सकते हैं। यही variable है।</p>
      <div className="concept-card"><span className="concept-kicker">एक आसान example</span><div className="equation"><span>□</span><b> + 3 = 8</b></div><p>यहाँ खाली जगह में कौन-सी संख्या आएगी?</p><div className="answer-row"><button>3</button><button className="correct">5</button><button>8</button><button>11</button></div><div className="feedback"><b>बहुत बढ़िया! ✓</b><span>5 + 3 = 8, इसलिए unknown value 5 है।</span></div></div>
      <div className="ai-help"><div className="ai-icon">✦</div><div><strong>समझ नहीं आया?</strong><p>तुरंत answer नहीं देंगे — पहले hint से सोचेंगे।</p><div className="hint-row"><button>आसान भाषा में समझाओ</button><button>Example से समझाओ</button><button>Step-by-step</button></div></div></div>
      <div className="lesson-next"><span>अगला: Algebraic expressions</span><Link href="/student/practice" className="button button-dark button-small">आगे बढ़ें →</Link></div>
    </section>
  </main>;
}