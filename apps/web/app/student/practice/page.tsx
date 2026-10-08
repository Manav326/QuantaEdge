import Link from 'next/link';

export default function PracticePage() {
  return <main className="practice-page">
    <header className="lesson-header"><Link href="/student" className="back">← आज</Link><span>Practice · 2 / 10</span><span className="avatar">अ</span></header>
    <section className="practice-wrap"><div className="eyebrow">Algebra · आसान से शुरू</div><h1>अगर x + 7 = 12 है,<br />तो x कितना होगा?</h1><p>जल्दी मत करो। पहले सोचो: 7 को हटाने के लिए क्या करना पड़ेगा?</p>
      <div className="option-grid"><button>3</button><button className="selected">5</button><button>7</button><button>19</button></div>
      <div className="practice-feedback"><strong>✓ सही जवाब</strong><span>x = 5, क्योंकि 5 + 7 = 12.</span></div>
      <div className="practice-footer"><span>Accuracy <b>100%</b> · Streak 🔥 2</span><Link href="/student/progress" className="button button-dark">अगला सवाल →</Link></div>
    </section>
  </main>;
}