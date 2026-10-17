'use client';
import { UiText } from '../../i18n/useUiTranslation';
import { translateUi } from '../../i18n/catalog';
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
      if (!response.ok) throw new Error(body.message || body.detail || body.error || translateUi(locale === 'english' ? 'english' : 'hinglish', 'legacy.web.structured.lessons.could.not.be.loaded.607c9597'));
      setItems(Array.isArray(body) ? body : []);
    } catch (error) {
      setError(error instanceof Error ? error.message : translateUi(locale === 'english' ? 'english' : 'hinglish', 'legacy.web.structured.lessons.could.not.be.loaded.607c9597'));
    } finally { setBusy(false); }
  }, [learningLanguage, locale, router]);
  useEffect(() => { void loadCatalogue(); }, [loadCatalogue]);
  return <main className="app-shell"><div className="app-layout"><section className="app-content">
    <header className="content-heading"><div>
      <span className="eyebrow"><UiText message="legacy.web.bilingual.lesson.format.349a3c45" /></span>
      <h1><UiText message="web.navigation.student.structuredLessons" /></h1>
      <p><UiText message="legacy.web.explain.visuals.activities.and.one.que.c789dbec" /></p>
    </div>
    <button type="button" className="button button-secondary" onClick={() => void loadCatalogue()} disabled={busy}><UiText message="legacy.web.refresh.45078d05" /></button></header>
    {error && <p role="alert" className="error-message">{error}</p>}
    {busy ? <p role="status" className="eyebrow">{translateUi(locale === 'english' ? 'english' : 'hinglish', 'legacy.web.finding.published.lessons.9f844f1e')}</p> :
      items.length === 0 ? <section className="auth-card" role="status">
        <h2>{translateUi(locale === 'english' ? 'english' : 'hinglish', 'legacy.web.no.approved.structured.lessons.are.ava.a3717bf5')}</h2>
        <p>{translateUi(locale === 'english' ? 'english' : 'hinglish', 'legacy.web.lessons.appear.here.only.after.content.88a83834')}</p>
        <Link href="/student/learn" className="button button-dark">{translateUi(locale === 'english' ? 'english' : 'hinglish', 'legacy.web.open.existing.lessons.67cef7a3')}</Link>
      </section> :
      <div className="task-list">{items.map(item => <article className="task-card" key={item.artifactId}>
        <div><span className="eyebrow">{item.subjectCode.toUpperCase()} · {translateUi(locale === 'english' ? 'english' : 'hinglish', 'legacy.web.class.98aaaa31')} {item.classNumber}</span>
          <h2>{item.title}</h2><p>{translateUi(locale === 'english' ? 'english' : 'hinglish', 'legacy.web.chapter.0a3bff81')} {item.chapterNumber}: {item.chapterTitle} · {translateUi(locale === 'english' ? 'english' : 'hinglish', 'legacy.web.topic.1a9696fe')} {item.topicNumber}</p>
          {item.estimatedMinutes > 0 && <small>{item.estimatedMinutes} {translateUi(locale === 'english' ? 'english' : 'hinglish', 'legacy.web.min.72242533')}</small>}</div>
        <Link href={'/student/structured-lessons/' + encodeURIComponent(item.artifactId)} className="button button-dark">{translateUi(locale === 'english' ? 'english' : 'hinglish', 'legacy.web.open.lesson.bca8f87b')}</Link>
      </article>)}</div>}
  </section></div></main>;
}
