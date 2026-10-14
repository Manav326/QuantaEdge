'use client';

import { useEffect, useRef, useState } from 'react';
import { useLocale } from '../../components/LanguageProvider';

type TutorMessage = { id?: number; role: 'USER' | 'ASSISTANT'; mode?: string; message: string };
type Mode = 'HINT' | 'EXPLAIN' | 'EXAMPLE' | 'STEP_BY_STEP' | 'CHECK_MY_WORK';
type ApiBody = Record<string, unknown>;

const englishModeLabels: Record<Mode, string> = {
  HINT: 'Hint',
  EXPLAIN: 'Explain',
  EXAMPLE: 'Example',
  STEP_BY_STEP: 'Step-by-step',
  CHECK_MY_WORK: 'Check my work',
};
const hindiModeLabels: Record<Mode, string> = {
  HINT: 'संकेत',
  EXPLAIN: 'समझाएँ',
  EXAMPLE: 'उदाहरण',
  STEP_BY_STEP: 'चरण-दर-चरण',
  CHECK_MY_WORK: 'मेरी जाँच करें',
};

async function readBody(response: Response): Promise<ApiBody> {
  const raw = await response.text();
  if (!raw.trim()) return {};
  try {
    const parsed: unknown = JSON.parse(raw);
    if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
      return parsed as ApiBody;
    }
    return { value: parsed };
  } catch {
    // Reverse proxies and framework error handlers sometimes return plain text
    // (for example, "Internal Server Error") instead of the API JSON contract.
    // Keep this as data so callers can display a localized, friendly message.
    return { rawText: raw.slice(0, 500) };
  }
}

function responseError(response: Response, body: ApiBody, english: boolean, fallbackEn: string, fallbackHi: string): string {
  if (response.status >= 500 || typeof body.rawText === 'string') {
    return english
      ? 'The AI tutor is temporarily unavailable. Please try again in a moment.'
      : 'AI ट्यूटर अभी उपलब्ध नहीं है। कृपया थोड़ी देर बाद फिर प्रयास करें।';
  }
  if (typeof body.message === 'string' && body.message.trim()) return body.message;
  return english ? fallbackEn : fallbackHi;
}

function requestError(english: boolean, fallbackEn: string, fallbackHi: string): string {
  return english ? fallbackEn : fallbackHi;
}

export default function TutorDock({ lessonId, open, onClose, currentQuestionId }: {
  lessonId: number;
  open: boolean;
  onClose: () => void;
  currentQuestionId?: number;
}) {
  const { locale, learningLanguage } = useLocale();
  const english = locale === 'english';
  // The selected English UI must also receive English tutor guidance. If the UI
  // is Hindi, honour the separate learning-language preference when it is English.
  const responseLanguage = english || learningLanguage === 'en' ? 'en' : 'hi';
  const labels = english ? englishModeLabels : hindiModeLabels;

  const [sessionId, setSessionId] = useState<number | null>(null);
  const [available, setAvailable] = useState<boolean | null>(null);
  const [messages, setMessages] = useState<TutorMessage[]>([]);
  const [hasOlderMessages, setHasOlderMessages] = useState(false);
  const [olderBeforeId, setOlderBeforeId] = useState<number | null>(null);
  const [loadingOlder, setLoadingOlder] = useState(false);
  const preserveScrollRef = useRef(false);
  const [mode, setMode] = useState<Mode>('HINT');
  const [input, setInput] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const endRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    if (!open) return;
    let cancelled = false;
    setSessionId(null);
    setAvailable(null);
    setMessages([]);
    setHasOlderMessages(false);
    setOlderBeforeId(null);
    setError('');
    (async () => {
      try {
        const response = await fetch('/api/v1/tutor/sessions', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          credentials: 'same-origin',
          body: JSON.stringify({ lessonId, language: responseLanguage }),
        });
        const body = await readBody(response);
        if (cancelled) return;
        if (!response.ok) {
          setAvailable(false);
          setError(responseError(
            response, body, english,
            'Could not start the tutor session. Please try again.',
            'ट्यूटर सत्र शुरू नहीं हो सका। कृपया फिर प्रयास करें।',
          ));
          return;
        }
        const nextSessionId = Number(body.sessionId);
        if (!Number.isFinite(nextSessionId) || nextSessionId <= 0) {
          setAvailable(false);
          setError(english
            ? 'The tutor returned an invalid session. Please reopen the tutor.'
            : 'ट्यूटर से सही सत्र जानकारी नहीं मिली। कृपया इसे दोबारा खोलें।');
          return;
        }
        setAvailable(Boolean(body.available));
        setSessionId(nextSessionId);
        if (!body.available) {
          setError(english
            ? 'AI tutor is not enabled for this environment yet.'
            : 'इस वातावरण में AI ट्यूटर अभी चालू नहीं है।');
          return;
        }

        const historyResponse = await fetch('/api/v1/tutor/sessions/' + nextSessionId + '/messages?limit=30', {
          credentials: 'same-origin',
        });
        const historyBody = await readBody(historyResponse);
        if (cancelled) return;
        if (historyResponse.ok) {
          setMessages(Array.isArray(historyBody.items) ? historyBody.items as TutorMessage[] : []);
          setHasOlderMessages(Boolean(historyBody.hasMore));
          setOlderBeforeId(historyBody.nextBeforeId == null ? null : Number(historyBody.nextBeforeId));
        } else {
          setError(responseError(
            historyResponse, historyBody, english,
            'Could not load tutor history.',
            'ट्यूटर का पिछला संवाद लोड नहीं हो सका।',
          ));
        }
      } catch {
        if (!cancelled) {
          setAvailable(false);
          setError(requestError(
            english,
            'Could not start the tutor session. Check your connection and retry.',
            'ट्यूटर सत्र शुरू नहीं हो सका। कनेक्शन जाँचकर फिर प्रयास करें।',
          ));
        }
      }
    })();
    return () => { cancelled = true; };
  }, [open, lessonId, english, responseLanguage]);

  useEffect(() => {
    if (preserveScrollRef.current) {
      preserveScrollRef.current = false;
      return;
    }
    endRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages, busy]);

  async function loadOlderMessages() {
    if (!sessionId || !hasOlderMessages || olderBeforeId === null || loadingOlder) return;
    setLoadingOlder(true);
    setError('');
    try {
      const response = await fetch('/api/v1/tutor/sessions/' + sessionId + '/messages?limit=30&beforeId=' + olderBeforeId, {
        credentials: 'same-origin',
      });
      const body = await readBody(response);
      if (!response.ok) {
        setError(responseError(
          response, body, english,
          'Older tutor messages could not be loaded.',
          'ट्यूटर के पुराने संदेश लोड नहीं हो सके।',
        ));
        return;
      }
      const older = Array.isArray(body.items) ? body.items as TutorMessage[] : [];
      preserveScrollRef.current = true;
      setMessages(current => [...older, ...current]);
      setHasOlderMessages(Boolean(body.hasMore));
      setOlderBeforeId(body.nextBeforeId == null ? null : Number(body.nextBeforeId));
    } catch {
      setError(requestError(
        english,
        'Could not load older tutor messages. Check your connection and retry.',
        'पुराने संदेश लोड नहीं हो सके। कनेक्शन जाँचकर फिर प्रयास करें।',
      ));
    } finally {
      setLoadingOlder(false);
    }
  }

  async function send() {
    if (!input.trim() || !sessionId || busy || available !== true) return;
    const message = input.trim();
    setInput('');
    setBusy(true);
    setError('');
    setMessages(current => [...current, { role: 'USER', mode, message }]);
    try {
      const response = await fetch('/api/v1/tutor/sessions/' + sessionId + '/messages', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'same-origin',
        body: JSON.stringify({ message, mode, questionId: currentQuestionId || 0, language: responseLanguage }),
      });
      const body = await readBody(response);
      if (!response.ok) {
        setMessages(current => current.slice(0, -1));
        setInput(message);
        setError(responseError(
          response, body, english,
          'The tutor could not answer. Please try again.',
          'ट्यूटर उत्तर नहीं दे सका। कृपया फिर प्रयास करें।',
        ));
        return;
      }
      if (typeof body.message !== 'string' || !body.message.trim()) {
        setMessages(current => current.slice(0, -1));
        setInput(message);
        setError(english
          ? 'The tutor returned an empty answer. Please try again.'
          : 'ट्यूटर से खाली उत्तर मिला। कृपया फिर प्रयास करें।');
        return;
      }
      setMessages(current => [...current, { role: 'ASSISTANT', mode, message: body.message as string }]);
    } catch {
      setMessages(current => current.slice(0, -1));
      setInput(message);
      setError(requestError(
        english,
        'Could not reach the tutor. Your message is ready to retry.',
        'ट्यूटर से संपर्क नहीं हो पाया। आपका संदेश फिर भेजने के लिए तैयार है।',
      ));
    } finally {
      setBusy(false);
    }
  }

  return <div className={'tutor-dock ' + (open ? 'open' : '')} aria-hidden={!open}>
    <div className="tutor-head">
      <div><span className="eyebrow">QUANTAEDGE TUTOR</span><strong>{english ? 'Ask when you’re stuck' : 'अटकें तो पूछें'}</strong></div>
      <button aria-label={english ? 'Close tutor' : 'ट्यूटर बंद करें'} className="tutor-close" onClick={onClose}>×</button>
    </div>
    {available === false
      ? <div className="tutor-unavailable">
          <span className="tutor-icon">✦</span>
          <h3>{english ? 'Tutor is temporarily unavailable' : 'ट्यूटर अभी उपलब्ध नहीं है'}</h3>
          <p>{error || (english ? 'Please try again in a moment.' : 'कृपया थोड़ी देर बाद फिर प्रयास करें।')}</p>
        </div>
      : <>
          <div className="tutor-modes">
            {(Object.keys(labels) as Mode[]).map(currentMode =>
              <button type="button" key={currentMode} className={mode === currentMode ? 'selected' : ''} onClick={() => setMode(currentMode)}>
                {labels[currentMode]}
              </button>
            )}
          </div>
          <div className="tutor-messages">
            {hasOlderMessages && <button
              type="button"
              className="tutor-load-older"
              disabled={loadingOlder}
              onClick={() => void loadOlderMessages()}
              style={{ display: 'block', margin: '0 auto 12px', padding: '7px 12px', borderRadius: 10, border: '1px solid #d8dce5', background: '#fff', cursor: loadingOlder ? 'wait' : 'pointer' }}
            >{loadingOlder ? (english ? 'Loading older messages…' : 'पुराने संदेश लोड हो रहे हैं…') : (english ? '↑ Load older messages' : '↑ पुराने संदेश लोड करें')}</button>}
            {messages.length === 0 && <div className="tutor-welcome">
              <span className="tutor-icon">✦</span>
              <strong>{english ? 'I’ll help you in the context of this lesson.' : 'मैं इसी पाठ के संदर्भ में आपकी मदद करूँगा।'}</strong>
              <p>{english ? 'Start with a hint. Choose Explain or Step-by-step if you need more help.' : 'पहले संकेत से शुरू करें। ज़रूरत हो तो समझाएँ या चरण-दर-चरण चुनें।'}</p>
            </div>}
            {messages.map((item, index) => <div className={'tutor-message ' + item.role.toLowerCase()} key={item.id || index}>
              <span className="tutor-role">{item.role === 'ASSISTANT' ? 'Tutor' : (english ? 'You' : 'आप')}</span>
              <p>{item.message}</p>
            </div>)}
            {busy && <div className="tutor-message assistant"><span className="tutor-role">Tutor</span><p>{english ? 'Thinking…' : 'सोच रहा हूँ…'}</p></div>}
            <div ref={endRef} />
          </div>
          {error && <div className="tutor-error" role="alert">{error}</div>}
          <div className="tutor-input">
            <textarea
              value={input}
              maxLength={1200}
              onChange={event => setInput(event.target.value.slice(0, 1200))}
              onKeyDown={event => { if (event.key === 'Enter' && !event.shiftKey) { event.preventDefault(); void send(); } }}
              placeholder={english ? 'Where are you stuck?' : 'आप कहाँ अटके हैं?'}
            />
            <button className="button button-dark" disabled={!input.trim() || busy || available !== true} onClick={() => void send()}>
              {english ? 'Ask →' : 'पूछें →'}
            </button>
          </div>
          <small className="tutor-note">{english
            ? 'Lesson content is the source of truth; official claims require provenance.'
            : 'पाठ की सामग्री ही मूल स्रोत है; बिना स्रोत-प्रमाण के किसी बात को आधिकारिक नहीं बताया जाएगा।'}</small>
        </>}
  </div>;
}
