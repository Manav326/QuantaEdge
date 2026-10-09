import Link from 'next/link';

export const dynamic = 'force-dynamic';

type Track = { class_code:string; subject_code:string; subject_name:string; chapters:number; lessons:number; completed:number };
async function getPreview() {
  const base = process.env.BACKEND_INTERNAL_URL ?? 'http://localhost:8080';
  const response = await fetch(base + '/api/v1/students/preview', { cache:'no-store' });
  if (!response.ok) throw new Error('Preview student unavailable');
  return response.json();
}

export default async function StudentHome() {
  const data = await getPreview();
  const stats = data.lessonStats as {total_lessons:number;completed_lessons:number;completion_percent:number};
  const questions = data.questionStats as {attempts:number;accuracy_percent:number};
  const tracks = data.curriculum as Track[];
  return <main className="app-shell">
    <header className="app-header"><Link href="/" className="brand compact"><span className="brand-mark">Q</span><span><strong>Quanta</strong>Edge<small>LEARNING</small></span></Link><div className="student-profile"><span>{data.display_name} · कक्षा {data.class_code}</span><span className="avatar">अ</span></div></header>
    <div className="app-layout">
      <aside className="side-nav"><Link className="side-active" href="/student">⌂ <span>आज</span></Link><Link href={'/student/learn?classCode='+encodeURIComponent(String(data.class_code))+'&subjectCode=maths'}>▣ <span>पढ़ाई</span></Link><Link href={'/student/practice?classCode='+encodeURIComponent(String(data.class_code))+'&subjectCode=maths'}>✦ <span>अभ्यास</span></Link><Link href="/student/progress">↗ <span>मेरी प्रगति</span></Link></aside>
      <section className="app-content">
        <div className="welcome-row"><div><span className="eyebrow">बिहार बोर्ड · कक्षा {data.class_code}</span><h1>नमस्ते, {data.display_name} 👋</h1><p>आज किस विषय में आगे बढ़ना है?</p></div><div className="streak-card"><strong>{Math.round(Number(stats.completion_percent))}%</strong><span>इस कक्षा के lessons पूरे</span></div></div>
        <div className="goal-card"><div><span>तुम्हारी learning journey</span><strong>{stats.completed_lessons} / {stats.total_lessons} lessons</strong><small>{questions.attempts} अभ्यास प्रयास · {questions.accuracy_percent}% सही जवाब</small></div><div className="goal-ring">{Math.round(Number(stats.completion_percent))}%</div></div>
        <div className="content-heading track-heading"><div><h2>तुम्हारे दो learning tracks</h2><p>गणित और विज्ञान की सामग्री अलग-अलग, अध्याय के सही क्रम में।</p></div><span>Class {data.class_code}</span></div>
        <div className="track-grid">{tracks.map(track => {
          const count = Number(track.lessons || 0), complete = Number(track.completed || 0);
          const percent = count ? Math.round(100*complete/count) : 0;
          const href = '/student/learn?classCode='+encodeURIComponent(String(data.class_code))+'&subjectCode='+encodeURIComponent(track.subject_code);
          return <Link key={track.subject_code} href={href} className="track-card">
            <div className="track-card-top"><span className={'track-icon '+(track.subject_code==='maths'?'track-maths':'track-science')}>{track.subject_code==='maths'?'∑':'⚗'}</span><span className="track-open">खोलें ↗</span></div>
            <span className="eyebrow">कक्षा {data.class_code} · बिहार बोर्ड</span><h3>{track.subject_name}</h3>
            <p>{Number(track.chapters||0)} प्रकाशित अध्याय · {count} lessons</p>
            <div className="track-progress"><span style={{width:percent+'%'}} /></div><div className="track-card-bottom"><span>{complete} lessons पूरे</span><strong>{percent}%</strong></div>
          </Link>;
        })}</div>
        <div className="content-heading"><h2>जारी रखें</h2><span>तुम्हारी पढ़ाई</span></div>
        <div className="task-list">
          <Link href={'/student/learn?classCode='+encodeURIComponent(String(data.class_code))+'&subjectCode=maths'} className="app-task next"><span className="task-icon">∑</span><div><strong>गणित की पढ़ाई</strong><small>अध्याय, lessons और अभ्यास खोलें</small></div><span className="task-action">→</span></Link>
          <Link href={'/student/learn?classCode='+encodeURIComponent(String(data.class_code))+'&subjectCode=science'} className="app-task"><span className="task-icon">⚗</span><div><strong>विज्ञान की पढ़ाई</strong><small>अवधारणाएँ, उदाहरण और अभ्यास</small></div><span className="task-action">→</span></Link>
          <Link href="/student/progress" className="app-task"><span className="task-icon">↗</span><div><strong>मेरी प्रगति</strong><small>पूरे किए गए lessons और अभ्यास के परिणाम</small></div><span className="task-action">→</span></Link>
        </div>
        <div className="help-card"><div className="help-bubble">✓</div><div><strong>स्थानीय preview</strong><p>नियंत्रित test student local development के लिए है; production seed बंद रहता है।</p></div></div>
      </section>
    </div>
    <nav className="mobile-nav"><Link className="side-active" href="/student">⌂<span>आज</span></Link><Link href={'/student/learn?classCode='+encodeURIComponent(String(data.class_code))+'&subjectCode=maths'}>▣<span>पढ़ाई</span></Link><Link href={'/student/practice?classCode='+encodeURIComponent(String(data.class_code))+'&subjectCode=maths'}>✦<span>अभ्यास</span></Link><Link href="/student/progress">↗<span>प्रगति</span></Link></nav>
  </main>;
}
