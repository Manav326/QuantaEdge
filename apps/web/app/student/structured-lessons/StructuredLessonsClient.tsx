'use client';
import Link from 'next/link';
import { useCallback, useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import { LocaleText, useLocale } from '../../components/LanguageProvider';

type LessonItem = {
  artifactId: string; classNumber: number; subjectCode: string; chapterNumber: number;
  chapterTitle: string; topicId: string; topicNumber: number; title: string; estimatedMinutes: number;
};
export default function StructuredLessonsClient() {
  const router = useRouter();
  const { locale, learningLanguage } = useLocale();
  const [items, setItems] = useState<LessonItem[]>([]);
  const [busy, setBusy] = useState(true);
  const [error, setError] = useState('');
  const tx = (hinglish: string, english: string) => locale === 'english' ? english : hinglish;
  const loadCatalogue = useCallback(async () => {
    setBusy(true); setError('');
    try {
      const response = await fetch('/api/v1/learning/structured-lessons?language=' + learningLanguage,
        { credentials: 'same-origin', cache: 'no-store' });
      if (response.status === 401 || response.status === 403) { router.replace('/login/student'); return; }
      const body = await response.json();
      if (!response.ok) throw new Error(body.message || body.detail || body.error || tx('Structured lessons load नहीं हो पाए।', 'Structured lessons could not be loaded.'));
      setItems(Array.isArray(body) ? body : []);
    } catch (error) {
      setError(error instanceof Error ? error.message : tx('Structured lessons load नहीं हो पाए।', 'Structured lessons could not be loaded.'));
    } finally { setBusy(false); }
  }, [learningLanguage, locale, router]);
  useEffect(() => { void loadCatalogue(); }, [loadCatalogue]);
  return <main className="app-shell"><div className="app-layout"><section className="app-content">
    <header className="content-heading"><div>
      <span className="eyebrow"><LocaleText hinglish="बाइलिंगुअल lesson format" english="Bilingual lesson format" /></span>
      <h1><LocaleText hinglish="Structured lessons" english="Structured lessons" /></h1>
      <p><LocaleText hinglish="Explain, visuals, activities और एक-एक करके practice questions।" english="Explain, visuals, activities and one-question-at-a-time practice." /></p>
    </div>
    <button type="button" className="button button-secondary" onClick={() => void loadCatalogue()} disabled={busy}><LocaleText hinglish="Refresh" english="Refresh" /></button></header>
    {error && <p role="alert" className="error-message">{error}</p>}
    {busy ? <p role="status" className="eyebrow">{tx('Published lessons खोज रहे हैं…', 'Finding published lessons…')}</p> :
      items.length === 0 ? <section className="auth-card" role="status">
        <h2>{tx('अभी कोई approved structured lesson उपलब्ध नहीं है', 'No approved structured lessons are available yet')}</h2>
        <p>{tx('Lessons तभी दिखेंगे जब content, source, visual और bilingual review पूरा करके publication approve होगा।', 'Lessons appear here only after content, source, visual and bilingual reviews are approved for publication.')}</p>
        <Link href="/student/learn" className="button button-dark">{tx('Existing lessons खोलें', 'Open existing lessons')}</Link>
      </section> :
      <div className="task-list">{items.map(item => <article className="task-card" key={item.artifactId}>
        <div><span className="eyebrow">{item.subjectCode.toUpperCase()} · {tx('कक्षा', 'Class')} {item.classNumber}</span>
          <h2>{item.title}</h2><p>{tx('अध्याय', 'Chapter')} {item.chapterNumber}: {item.chapterTitle} · {tx('विषय', 'Topic')} {item.topicNumber}</p>
          {item.estimatedMinutes > 0 && <small>{item.estimatedMinutes} {tx('मिनट', 'min')}</small>}</div>
        <Link href={'/student/structured-lessons/' + encodeURIComponent(item.artifactId)} className="button button-dark">{tx('Lesson खोलें', 'Open lesson')}</Link>
      </article>)}</div>}
  </section></div></main>;
}
