'use client';

import {
  useEffect, useRef, useState,
  type CSSProperties,
  type PointerEvent as ReactPointerEvent,
  type ReactNode,
} from 'react';
import { useLocale } from '../../components/LanguageProvider';

type TutorMessage = { id?: number; clientId?: string; role: 'USER' | 'ASSISTANT'; mode?: string; message: string };
type Mode = 'HINT' | 'EXPLAIN' | 'EXAMPLE' | 'STEP_BY_STEP' | 'CHECK_MY_WORK';
type ApiBody = Record<string, unknown>;
type PanelFrame = { left: number; top: number; width: number; height: number };
type DragStart = { pointerId: number; startX: number; startY: number; left: number; top: number; width: number; height: number };
type Point = { left: number; top: number };
type MessageBlock =
  | { kind: 'paragraph' | 'heading' | 'equation' | 'code'; text: string; level?: number; language?: string }
  | { kind: 'ordered' | 'unordered'; text: string; items: string[] }
  | { kind: 'table'; text: string; headers: string[]; rows: string[][] };

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
const modeIcons: Record<Mode, string> = {
  HINT: '💡',
  EXPLAIN: '☷',
  EXAMPLE: '▦',
  STEP_BY_STEP: '1→2',
  CHECK_MY_WORK: '✓',
};

async function readBody(response: Response): Promise<ApiBody> {
  const raw = await response.text();
  if (!raw.trim()) return {};
  try {
    const parsed: unknown = JSON.parse(raw);
    if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) return parsed as ApiBody;
    return { value: parsed };
  } catch {
    return { rawText: raw.slice(0, 500) };
  }
}

/**
 * A slow local model can finish and persist a turn after the web proxy has
 * already returned an HTTP 5xx/network error. Reconcile with persisted history
 * before telling the learner their question failed.
 */
const TUTOR_RECOVERY_ATTEMPTS = 46;
const TUTOR_RECOVERY_INTERVAL_MS = 2000;

async function recoverPersistedTutorTurn(sessionId: number, userMessage: string): Promise<TutorMessage[] | null> {
  // The local CPU model can take 65–90 seconds. Gateway errors can arrive before
  // inference finishes, so keep reconciling persisted history long enough for the
  // in-flight API request to finish instead of prematurely discarding the turn.
  for (let attempt = 0; attempt < TUTOR_RECOVERY_ATTEMPTS; attempt += 1) {
    if (attempt > 0) await new Promise(resolve => window.setTimeout(resolve, TUTOR_RECOVERY_INTERVAL_MS));
    try {
      const response = await fetch('/api/v1/tutor/sessions/' + sessionId + '/messages?limit=30', {
        credentials: 'same-origin',
        cache: 'no-store',
      });
      if (!response.ok) continue;
      const body = await readBody(response);
      const items = Array.isArray(body.items) ? body.items as TutorMessage[] : [];
      let matchedUserIndex = -1;
      for (let index = items.length - 1; index >= 0; index -= 1) {
        if (items[index].role === 'USER' && items[index].message === userMessage) {
          matchedUserIndex = index;
          break;
        }
      }
      if (matchedUserIndex >= 0
        && items.slice(matchedUserIndex + 1).some(item => item.role === 'ASSISTANT')) {
        return items;
      }
    } catch {
      // A temporary history-fetch failure should not replace the original tutor error.
    }
  }
  return null;
}

function responseError(response: Response, body: ApiBody, english: boolean, fallbackEn: string, fallbackHi: string): string {
  const code = typeof body.error === 'string' ? body.error : '';
  const message = typeof body.message === 'string' ? body.message.toLowerCase() : '';
  if (code === 'TUTOR_PROVIDER_ERROR' || response.status === 502) {
    return english
      ? 'The tutor could not generate a reliable reply this time. Your question is back in the input so you can retry.'
      : 'ट्यूटर इस बार सही उत्तर नहीं बना सका। आपका प्रश्न इनपुट में वापस है; आप फिर प्रयास कर सकते हैं।';
  }
  if (response.status >= 500 || typeof body.rawText === 'string') {
    return english
      ? 'The AI tutor service had a temporary error. Your question is back in the input so you can retry.'
      : 'AI ट्यूटर सेवा में अस्थायी समस्या आई। आपका प्रश्न इनपुट में वापस है; आप फिर प्रयास कर सकते हैं।';
  }
  if (response.status === 401 || code === 'UNAUTHENTICATED') {
    return english ? 'Your session may have expired. Please sign in again.' : 'आपका सत्र समाप्त हो गया हो सकता है। कृपया फिर से लॉग इन करें।';
  }
  if (response.status === 403 || code === 'FORBIDDEN') {
    return english ? 'You do not have access to this lesson or tutor session.' : 'आपको इस पाठ या ट्यूटर सत्र की अनुमति नहीं है।';
  }
  if (message.includes('usage limit') || message.includes('daily tutor')) {
    return english ? 'You have reached the tutor usage limit. Please try again later.' : 'ट्यूटर उपयोग की सीमा पूरी हो गई है। कृपया बाद में फिर प्रयास करें।';
  }
  if (message.includes('too long')) {
    return english ? 'Please shorten your message and try again.' : 'कृपया अपना संदेश छोटा करके फिर भेजें।';
  }
  if (code === 'TUTOR_UNAVAILABLE' || response.status === 503) {
    return english ? 'The AI tutor is not enabled right now. Please try again later.' : 'AI ट्यूटर अभी चालू नहीं है। कृपया बाद में फिर प्रयास करें।';
  }
  return english ? fallbackEn : fallbackHi;
}

function requestError(english: boolean, fallbackEn: string, fallbackHi: string): string {
  return english ? fallbackEn : fallbackHi;
}

function parseMessageBlocks(message: string): MessageBlock[] {
  const lines = message.replace(/\r\n?/g, '\n').split('\n');
  const blocks: MessageBlock[] = [];
  let paragraph: string[] = [];
  let listKind: 'ordered' | 'unordered' | null = null;
  let listItems: string[] = [];
  const flushParagraph = () => {
    if (paragraph.length) blocks.push({ kind: 'paragraph', text: paragraph.join('\n') });
    paragraph = [];
  };
  const flushList = () => {
    if (listKind && listItems.length) blocks.push({ kind: listKind, text: '', items: listItems });
    listKind = null;
    listItems = [];
  };

  for (let index = 0; index < lines.length; index += 1) {
    const trimmed = lines[index].trim();
    if (!trimmed) { flushParagraph(); flushList(); continue; }
    if (trimmed.startsWith('\x60\x60\x60')) {
      flushParagraph(); flushList();
      const language = trimmed.slice(3).trim();
      const code: string[] = [];
      index += 1;
      while (index < lines.length && !lines[index].trim().startsWith('\x60\x60\x60')) {
        code.push(lines[index]);
        index += 1;
      }
      blocks.push({ kind: 'code', text: code.join('\n'), language });
      continue;
    }
    if (trimmed === '$$' || /^\$\$[\s\S]*\$\$$/.test(trimmed)) {
      flushParagraph(); flushList();
      if (trimmed === '$$') {
        const equation: string[] = [];
        index += 1;
        while (index < lines.length && lines[index].trim() !== '$$') {
          equation.push(lines[index]);
          index += 1;
        }
        blocks.push({ kind: 'equation', text: equation.join('\n') });
      } else {
        blocks.push({ kind: 'equation', text: trimmed.slice(2, -2).trim() });
      }
      continue;
    }
    const nextLine = index + 1 < lines.length ? lines[index + 1].trim() : '';
    const isTableSeparator = /^\|?\s*:?-{3,}:?\s*(?:\|\s*:?-{3,}:?\s*)+\|?$/.test(nextLine);
    if (trimmed.includes('|') && isTableSeparator) {
      flushParagraph(); flushList();
      const cells = (line: string) => line.trim().replace(/^\|/, '').replace(/\|$/, '').split('|').map(cell => cell.trim());
      const headers = cells(trimmed);
      const rows: string[][] = [];
      index += 2;
      while (index < lines.length && lines[index].trim() && lines[index].includes('|')) {
        rows.push(cells(lines[index]));
        index += 1;
      }
      blocks.push({ kind: 'table', text: '', headers, rows });
      index -= 1;
      continue;
    }
    const heading = trimmed.match(/^(#{1,3})\s+(.+)$/);
    if (heading) {
      flushParagraph(); flushList();
      blocks.push({ kind: 'heading', text: heading[2], level: heading[1].length });
      continue;
    }
    const listItem = trimmed.match(/^(?:([-*+•])|(\d+)[.)])\s+(.+)$/);
    if (listItem) {
      flushParagraph();
      const nextKind = listItem[2] ? 'ordered' : 'unordered';
      if (listKind && listKind !== nextKind) flushList();
      listKind = nextKind;
      listItems.push(listItem[3]);
      continue;
    }
    flushList();
    paragraph.push(trimmed);
  }
  flushParagraph(); flushList();
  return blocks;
}

function renderInlineText(text: string, english: boolean): ReactNode[] {
  const pieces = text.split(/(\*\*[^*]+\*\*|\x60[^\x60]+\x60|\$[^$\n]+\$|\*[^*\n]+\*)/g);
  return pieces.filter(Boolean).map((piece, index) => {
    const key = 'inline-' + index;
    if (piece.startsWith('**') && piece.endsWith('**')) return <strong key={key}>{piece.slice(2, -2)}</strong>;
    if (piece.charCodeAt(0) === 96 && piece.charCodeAt(piece.length - 1) === 96) {
      return <code key={key} className="tutor-inline-code">{piece.slice(1, -1)}</code>;
    }
    if (piece.startsWith('$') && piece.endsWith('$')) {
      return <span key={key} className="tutor-inline-math" role="math" aria-label={english ? 'Math expression' : 'गणितीय व्यंजक'}>{piece.slice(1, -1)}</span>;
    }
    if (piece.startsWith('*') && piece.endsWith('*')) return <em key={key}>{piece.slice(1, -1)}</em>;
    return <span key={key}>{piece}</span>;
  });
}

function TutorMessageBody({ message, english }: { message: string; english: boolean }) {
  const blocks = parseMessageBlocks(message);
  return <div className="tutor-message-content">
    {blocks.map((block, index) => {
      if (block.kind === 'heading') {
        return <h4 className={'tutor-message-heading level-' + (block.level || 2)} key={index}>{renderInlineText(block.text, english)}</h4>;
      }
      if (block.kind === 'ordered' || block.kind === 'unordered') {
        const List = block.kind === 'ordered' ? 'ol' : 'ul';
        return <List className="tutor-message-list" key={index}>{block.items.map((item, itemIndex) => <li key={itemIndex}>{renderInlineText(item, english)}</li>)}</List>;
      }
      if (block.kind === 'table') {
        return <div className="tutor-table-wrap" key={index}><table className="tutor-table"><thead><tr>{block.headers.map((cell, cellIndex) => <th scope="col" key={cellIndex}>{renderInlineText(cell, english)}</th>)}</tr></thead><tbody>{block.rows.map((row, rowIndex) => <tr key={rowIndex}>{block.headers.map((_, cellIndex) => <td key={cellIndex}>{renderInlineText(row[cellIndex] || '', english)}</td>)}</tr>)}</tbody></table></div>;
      }
      if (block.kind === 'equation') {
        return <div className="tutor-equation" role="math" aria-label={english ? 'Math expression' : 'गणितीय व्यंजक'} key={index}>{block.text}</div>;
      }
      if (block.kind === 'code') return <pre className="tutor-code" key={index}><code>{block.text}</code></pre>;
      return <p key={index}>{renderInlineText(block.text, english)}</p>;
    })}
  </div>;
}

export default function TutorDock({ lessonId, open, onClose, currentQuestionId }: {
  lessonId: number;
  open: boolean;
  onClose: () => void;
  currentQuestionId?: number;
}) {
  const { locale, learningLanguage, languageReady } = useLocale();
  // Keep the two header selectors independent:
  // app UI labels follow locale; lesson content and tutor answers follow learningLanguage.
  const english = locale === 'english';
  const responseLanguage = learningLanguage;
  const languageRef = useRef({ english, responseLanguage });
  languageRef.current = { english, responseLanguage };
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
  const sendInFlightRef = useRef(false);
  const pendingMessageCounterRef = useRef(0);
  const endRef = useRef<HTMLDivElement | null>(null);

  const [minimized, setMinimized] = useState(false);
  const [mobileViewport, setMobileViewport] = useState(false);
  const [retryAttempt, setRetryAttempt] = useState(0);
  const [frame, setFrame] = useState<PanelFrame | null>(null);
  const [bubblePosition, setBubblePosition] = useState<Point | null>(null);
  const panelRef = useRef<HTMLElement | null>(null);
  const panelDragRef = useRef<DragStart | null>(null);
  const panelResizeRef = useRef<DragStart | null>(null);
  const bubbleDragRef = useRef<{ pointerId: number; startX: number; startY: number; left: number; top: number; moved: boolean } | null>(null);
  const suppressBubbleClickRef = useRef(false);

  useEffect(() => {
    if (!open) { setMinimized(false); setFrame(null); setBubblePosition(null); }
  }, [open]);

  useEffect(() => {
    if (!open || !languageReady) return;
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
          body: JSON.stringify({ lessonId, language: languageRef.current.responseLanguage }),
        });
        const body = await readBody(response);
        if (cancelled) return;
        if (!response.ok) {
          setAvailable(false);
          const current = languageRef.current;
          setError(responseError(response, body, current.english,
            'Could not start the tutor session. Please try again.',
            'ट्यूटर सत्र शुरू नहीं हो सका। कृपया फिर प्रयास करें।'));
          return;
        }
        const nextSessionId = Number(body.sessionId);
        if (!Number.isFinite(nextSessionId) || nextSessionId <= 0) {
          setAvailable(false);
          setError(languageRef.current.english
            ? 'The tutor returned an invalid session. Please reopen the tutor.'
            : 'ट्यूटर से सही सत्र जानकारी नहीं मिली। कृपया इसे दोबारा खोलें।');
          return;
        }
        setAvailable(Boolean(body.available));
        setSessionId(nextSessionId);
        if (!body.available) {
          setError(languageRef.current.english
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
          const current = languageRef.current;
          setError(responseError(historyResponse, historyBody, current.english,
            'Could not load tutor history.',
            'ट्यूटर का पिछला संवाद लोड नहीं हो सका।'));
        }
      } catch {
        if (!cancelled) {
          const current = languageRef.current;
          setAvailable(false);
          setError(requestError(current.english,
            'Could not start the tutor session. Check your connection and retry.',
            'ट्यूटर सत्र शुरू नहीं हो सका। कनेक्शन जाँचकर फिर प्रयास करें।'));
        }
      }
    })();
    return () => { cancelled = true; };
  }, [open, lessonId, languageReady, retryAttempt]);

  useEffect(() => {
    if (!open || !languageReady) return;
    const onResize = () => {
      const isMobile = window.innerWidth <= 700;
      setMobileViewport(isMobile);
      if (isMobile) { setFrame(null); return; }
      setFrame(current => {
        if (!current) return current;
        const width = Math.min(current.width, Math.max(320, window.innerWidth - 16));
        const height = Math.min(current.height, Math.max(360, window.innerHeight - 16));
        return {
          left: Math.max(8, Math.min(current.left, window.innerWidth - width - 8)),
          top: Math.max(8, Math.min(current.top, window.innerHeight - height - 8)),
          width, height,
        };
      });
    };
    onResize();
    window.addEventListener('resize', onResize);
    return () => window.removeEventListener('resize', onResize);
  }, [open, languageReady]);

  useEffect(() => {
    if (preserveScrollRef.current) { preserveScrollRef.current = false; return; }
    endRef.current?.scrollIntoView({ behavior: 'smooth', block: 'end' });
  }, [messages, busy]);

  useEffect(() => { setError(''); }, [english, responseLanguage]);

  function beginPanelMove(event: ReactPointerEvent<HTMLDivElement>) {
    if (window.innerWidth <= 700 || event.button !== 0 || (event.target as HTMLElement).closest('button')) return;
    const rect = panelRef.current?.getBoundingClientRect();
    if (!rect) return;
    setFrame({ left: rect.left, top: rect.top, width: rect.width, height: rect.height });
    panelDragRef.current = {
      pointerId: event.pointerId, startX: event.clientX, startY: event.clientY,
      left: rect.left, top: rect.top, width: rect.width, height: rect.height,
    };
    event.currentTarget.setPointerCapture(event.pointerId);
  }

  function movePanel(event: ReactPointerEvent<HTMLDivElement>) {
    const start = panelDragRef.current;
    if (!start || event.pointerId !== start.pointerId) return;
    setFrame({
      left: Math.max(8, Math.min(window.innerWidth - start.width - 8, start.left + event.clientX - start.startX)),
      top: Math.max(8, Math.min(window.innerHeight - start.height - 8, start.top + event.clientY - start.startY)),
      width: start.width, height: start.height,
    });
  }

  function endPanelMove(event: ReactPointerEvent<HTMLDivElement>) {
    if (panelDragRef.current?.pointerId === event.pointerId) panelDragRef.current = null;
  }

  function beginPanelResize(event: ReactPointerEvent<HTMLButtonElement>) {
    if (window.innerWidth <= 700 || event.button !== 0) return;
    event.preventDefault();
    event.stopPropagation();
    const rect = panelRef.current?.getBoundingClientRect();
    if (!rect) return;
    setFrame({ left: rect.left, top: rect.top, width: rect.width, height: rect.height });
    panelResizeRef.current = {
      pointerId: event.pointerId, startX: event.clientX, startY: event.clientY,
      left: rect.left, top: rect.top, width: rect.width, height: rect.height,
    };
    event.currentTarget.setPointerCapture(event.pointerId);
  }

  function resizePanel(event: ReactPointerEvent<HTMLButtonElement>) {
    const start = panelResizeRef.current;
    if (!start || event.pointerId !== start.pointerId) return;
    const maxWidth = Math.max(320, window.innerWidth - start.left - 8);
    const maxHeight = Math.max(360, window.innerHeight - start.top - 8);
    setFrame({
      left: start.left, top: start.top,
      width: Math.max(320, Math.min(maxWidth, start.width + event.clientX - start.startX)),
      height: Math.max(360, Math.min(maxHeight, start.height + event.clientY - start.startY)),
    });
  }

  function endPanelResize(event: ReactPointerEvent<HTMLButtonElement>) {
    if (panelResizeRef.current?.pointerId === event.pointerId) panelResizeRef.current = null;
  }

  function beginBubbleDrag(event: ReactPointerEvent<HTMLButtonElement>) {
    if (event.button !== 0) return;
    const rect = event.currentTarget.getBoundingClientRect();
    suppressBubbleClickRef.current = false;
    bubbleDragRef.current = {
      pointerId: event.pointerId, startX: event.clientX, startY: event.clientY,
      left: rect.left, top: rect.top, moved: false,
    };
    setBubblePosition({ left: rect.left, top: rect.top });
    event.currentTarget.setPointerCapture(event.pointerId);
  }

  function moveBubble(event: ReactPointerEvent<HTMLButtonElement>) {
    const start = bubbleDragRef.current;
    if (!start || event.pointerId !== start.pointerId) return;
    const dx = event.clientX - start.startX;
    const dy = event.clientY - start.startY;
    if (Math.abs(dx) > 4 || Math.abs(dy) > 4) start.moved = true;
    if (!start.moved) return;
    setBubblePosition({
      left: Math.max(8, Math.min(window.innerWidth - 66, start.left + dx)),
      top: Math.max(8, Math.min(window.innerHeight - 66, start.top + dy)),
    });
  }

  function endBubbleDrag(event: ReactPointerEvent<HTMLButtonElement>) {
    const start = bubbleDragRef.current;
    if (!start || start.pointerId !== event.pointerId) return;
    suppressBubbleClickRef.current = start.moved;
    bubbleDragRef.current = null;
  }

  async function loadOlderMessages() {
    if (!sessionId || !hasOlderMessages || olderBeforeId === null || loadingOlder) return;
    setLoadingOlder(true);
    setError('');
    try {
      const response = await fetch('/api/v1/tutor/sessions/' + sessionId + '/messages?limit=30&beforeId=' + olderBeforeId, {
        credentials: 'same-origin',
      });
      const body = await readBody(response);
      const current = languageRef.current;
      if (!response.ok) {
        setError(responseError(response, body, current.english,
          'Older tutor messages could not be loaded.',
          'ट्यूटर के पुराने संदेश लोड नहीं हो सके।'));
        return;
      }
      const older = Array.isArray(body.items) ? body.items as TutorMessage[] : [];
      preserveScrollRef.current = true;
      setMessages(currentMessages => [...older, ...currentMessages]);
      setHasOlderMessages(Boolean(body.hasMore));
      setOlderBeforeId(body.nextBeforeId == null ? null : Number(body.nextBeforeId));
    } catch {
      const current = languageRef.current;
      setError(requestError(current.english,
        'Could not load older messages. Check your connection and retry.',
        'पुराने संदेश लोड नहीं हो सके। कनेक्शन जाँचकर फिर प्रयास करें।'));
    } finally { setLoadingOlder(false); }
  }

  async function startNewChat() {
    if (busy || sendInFlightRef.current || !languageReady) return;
    sendInFlightRef.current = true;
    setBusy(true);
    setError('');
    try {
      const response = await fetch('/api/v1/tutor/sessions', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'same-origin',
        body: JSON.stringify({ lessonId, language: languageRef.current.responseLanguage, newChat: true }),
      });
      const body = await readBody(response);
      const nextSessionId = Number(body.sessionId);
      if (!response.ok || !Number.isFinite(nextSessionId) || nextSessionId <= 0) {
        const current = languageRef.current;
        setError(responseError(response, body, current.english,
          'Could not start a new chat. Please try again.',
          'नई चैट शुरू नहीं हो सकी। कृपया फिर प्रयास करें।'));
        return;
      }
      setSessionId(nextSessionId);
      setAvailable(Boolean(body.available));
      setMessages([]);
      setHasOlderMessages(false);
      setOlderBeforeId(null);
      setInput('');
      setMode('HINT');
      if (!body.available) {
        const current = languageRef.current;
        setError(current.english
          ? 'A new chat was created, but the tutor is currently unavailable.'
          : 'नई चैट बन गई है, लेकिन ट्यूटर अभी उपलब्ध नहीं है।');
      }
    } catch {
      const current = languageRef.current;
      setError(requestError(current.english,
        'Could not start a new chat. Check your connection and retry.',
        'नई चैट शुरू नहीं हो सकी। कनेक्शन जाँचकर फिर प्रयास करें।'));
    } finally {
      sendInFlightRef.current = false;
      setBusy(false);
    }
  }

  async function send() {
    if (!input.trim() || !sessionId || busy || available !== true || sendInFlightRef.current) return;
    // State updates are asynchronous; this ref prevents Enter + click / rapid clicks
    // from starting parallel requests for the same composer value.
    sendInFlightRef.current = true;
    const message = input.trim();
    const selectedMode = mode;
    const optimisticMessageId = 'pending-' + (++pendingMessageCounterRef.current);
    setInput('');
    setBusy(true);
    setError('');
    setMessages(current => [...current, { clientId: optimisticMessageId, role: 'USER', mode: selectedMode, message }]);
    try {
      const response = await fetch('/api/v1/tutor/sessions/' + sessionId + '/messages', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'same-origin',
        body: JSON.stringify({ message, mode: selectedMode, questionId: currentQuestionId || 0, language: languageRef.current.responseLanguage }),
      });
      const body = await readBody(response);
      const current = languageRef.current;
      if (!response.ok) {
        if (response.status >= 500 || typeof body.rawText === 'string') {
          const recovered = await recoverPersistedTutorTurn(sessionId, message);
          if (recovered) {
            setMessages(recovered);
            setError('');
            return;
          }
        }
        setMessages(currentMessages => currentMessages.filter(item => item.clientId !== optimisticMessageId));
        setInput(message);
        setError(responseError(response, body, current.english,
          'The tutor could not answer. Please try again.',
          'ट्यूटर उत्तर नहीं दे सका। कृपया फिर प्रयास करें।'));
        return;
      }
      if (typeof body.message !== 'string' || !body.message.trim()) {
        setMessages(currentMessages => currentMessages.filter(item => item.clientId !== optimisticMessageId));
        setInput(message);
        setError(current.english
          ? 'The tutor returned an empty answer. Please try again.'
          : 'ट्यूटर से खाली उत्तर मिला। कृपया फिर प्रयास करें।');
        return;
      }
      setMessages(currentMessages => [...currentMessages, { role: 'ASSISTANT', mode: selectedMode, message: body.message as string }]);
    } catch {
      const recovered = await recoverPersistedTutorTurn(sessionId, message);
      if (recovered) {
        setMessages(recovered);
        setError('');
        return;
      }
      setMessages(currentMessages => currentMessages.filter(item => item.clientId !== optimisticMessageId));
      setInput(message);
      const current = languageRef.current;
      setError(requestError(current.english,
        'Could not reach the tutor. Your message is ready to retry.',
        'ट्यूटर से संपर्क नहीं हो पाया। आपका संदेश फिर भेजने के लिए तैयार है।'));
    } finally {
      sendInFlightRef.current = false;
      setBusy(false);
    }
  }

  if (!open) return null;

  const panelStyle: CSSProperties | undefined = frame ? {
    left: frame.left, top: frame.top, width: frame.width, height: frame.height,
    right: 'auto', bottom: 'auto',
  } : undefined;
  const bubbleStyle: CSSProperties | undefined = bubblePosition ? {
    left: bubblePosition.left, top: bubblePosition.top, right: 'auto', bottom: 'auto',
  } : undefined;

  if (minimized) {
    return <button
      type="button"
      className="tutor-minimized"
      style={bubbleStyle}
      aria-label={english ? 'Restore QuantaEdge Tutor' : 'क्वांटाएज ट्यूटर फिर खोलें'}
      title={english ? 'Restore tutor' : 'ट्यूटर फिर खोलें'}
      onPointerDown={beginBubbleDrag}
      onPointerMove={moveBubble}
      onPointerUp={endBubbleDrag}
      onPointerCancel={endBubbleDrag}
      onClick={() => {
        if (suppressBubbleClickRef.current) { suppressBubbleClickRef.current = false; return; }
        setMinimized(false);
      }}
    >
      <span className="tutor-minimized-mark" aria-hidden="true">✦</span>
    </button>;
  }

  if (!languageReady) {
    return <section className="tutor-dock open tutor-language-loading" aria-label="AI tutor">
      <div className="tutor-head tutor-head--compact">
        <div className="tutor-head-main">
          <img className="tutor-q-mark" src="/branding/quantaedge-icon.png" alt="" />
          <strong className="tutor-head-title">{english ? 'Your tutor' : 'आपका ट्यूटर'}</strong>
          <span className="tutor-ai-badge" aria-label="AI tutor"><span aria-hidden="true">✦</span><small>AI</small></span>
        </div>
        <div className="tutor-head-controls">
          <button type="button" className="tutor-control-btn tutor-close" aria-label={english ? 'Close tutor' : 'ट्यूटर बंद करें'} title={english ? 'Close' : 'बंद करें'} onClick={onClose}>×</button>
        </div>
      </div>
      <div className="tutor-language-loading-body"><span className="tutor-spinner" aria-hidden="true" /></div>
    </section>;
  }

  return <section
    ref={panelRef}
    className="tutor-dock open"
    role="dialog"
    aria-modal={mobileViewport ? true : undefined}
    aria-label="QUANTAEDGE TUTOR"
    style={panelStyle}
  >
    <div
      className="tutor-head"
      onPointerDown={beginPanelMove}
      onPointerMove={movePanel}
      onPointerUp={endPanelMove}
      onPointerCancel={endPanelMove}
    >
      <div className="tutor-head-main">
        <img className="tutor-q-mark" src="/branding/quantaedge-icon.png" alt="" />
        <strong className="tutor-head-title">{english ? 'Your tutor' : 'आपका ट्यूटर'}</strong>
        <span className="tutor-ai-badge" aria-label="AI tutor" title={english ? 'AI tutor' : 'AI ट्यूटर'}><span aria-hidden="true">✦</span><small>AI</small></span>
      </div>
      <div className="tutor-head-controls">
        <button type="button" className="tutor-control-btn" aria-label={english ? 'Start a new chat' : 'नई चैट शुरू करें'} title={english ? 'New chat' : 'नई चैट'} disabled={busy || available !== true} onClick={() => void startNewChat()}>＋</button>
        <button type="button" className="tutor-control-btn" aria-label={english ? 'Minimize tutor' : 'ट्यूटर छोटा करें'} title={english ? 'Minimize' : 'छोटा करें'} onClick={() => setMinimized(true)}>−</button>
        <button type="button" className="tutor-control-btn tutor-close" aria-label={english ? 'Close tutor' : 'ट्यूटर बंद करें'} title={english ? 'Close' : 'बंद करें'} onClick={onClose}>×</button>
      </div>
    </div>

    {available === null
      ? <div className="tutor-connecting"><span className="tutor-spinner" aria-hidden="true" /><strong>{english ? 'Connecting to your lesson…' : 'आपके पाठ से जुड़ रहे हैं…'}</strong></div>
      : available === false
        ? <div className="tutor-unavailable">
            <span className="tutor-icon" aria-hidden="true">✦</span>
            <h3>{english ? 'Tutor is temporarily unavailable' : 'ट्यूटर अभी उपलब्ध नहीं है'}</h3>
            <p>{error || (english ? 'Please try again in a moment.' : 'कृपया थोड़ी देर बाद फिर प्रयास करें।')}</p>
            <button type="button" className="tutor-retry" onClick={() => { setAvailable(null); setError(''); setSessionId(null); setRetryAttempt(value => value + 1); }}>{english ? 'Retry connection' : 'फिर से जोड़ें'}</button>
          </div>
        : <>
            <div className="tutor-modes" aria-label={english ? 'Tutor help mode' : 'ट्यूटर सहायता का तरीका'}>
              {(Object.keys(labels) as Mode[]).map(currentMode =>
                <button
                  type="button"
                  key={currentMode}
                  className={mode === currentMode ? 'selected' : ''}
                  aria-label={labels[currentMode]}
                  aria-pressed={mode === currentMode}
                  title={labels[currentMode]}
                  onClick={() => setMode(currentMode)}
                >
                  <span className="tutor-mode-icon" aria-hidden="true">{modeIcons[currentMode]}</span>
                  <span className="tutor-mode-tooltip" role="tooltip">{labels[currentMode]}</span>
                </button>
              )}
            </div>
            <div className="tutor-messages" aria-live="polite" aria-relevant="additions text">
              {hasOlderMessages && <button type="button" className="tutor-load-older" disabled={loadingOlder} onClick={() => void loadOlderMessages()}>
                {loadingOlder ? (english ? 'Loading older messages…' : 'पुराने संदेश लोड हो रहे हैं…') : (english ? '↑ Load older messages' : '↑ पुराने संदेश लोड करें')}
              </button>}
              {messages.length === 0 && <div className="tutor-welcome">
                <strong>{english ? 'Ask me about this lesson.' : 'इस पाठ के बारे में पूछें।'}</strong>
              </div>}
              {messages.map((item, index) => <article className={'tutor-message ' + item.role.toLowerCase()} key={item.id || item.clientId || index}>
                <span className="tutor-role">{item.role === 'ASSISTANT' ? (english ? 'Tutor' : 'ट्यूटर') : (english ? 'You' : 'आप')}</span>
                <TutorMessageBody message={item.message} english={english} />
              </article>)}
              {busy && <article className="tutor-message assistant tutor-thinking"><span className="tutor-role">{english ? 'Tutor' : 'ट्यूटर'}</span><p>{english ? 'Thinking…' : 'सोच रहा हूँ…'} <span className="tutor-thinking-dots" aria-hidden="true"><i /><i /><i /></span></p></article>}
              <div ref={endRef} />
            </div>
            {error && <div className="tutor-error" role="alert">{error}</div>}
            <form className="tutor-input" onSubmit={event => { event.preventDefault(); void send(); }}>
              <label className="tutor-composer" htmlFor="qe-tutor-message">
                <textarea
                  id="qe-tutor-message"
                  value={input}
                  maxLength={1200}
                  onChange={event => setInput(event.target.value.slice(0, 1200))}
                  onKeyDown={event => { if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) { event.preventDefault(); void send(); } }}
                  placeholder={english ? 'Ask about this lesson…' : 'इस पाठ के बारे में पूछें…'}
                  aria-label={english ? 'Your message to the tutor' : 'ट्यूटर के लिए आपका संदेश'}
                />
              </label>
              <button className="tutor-send" type="submit" disabled={!input.trim() || busy || available !== true} aria-label={english ? 'Send message' : 'संदेश भेजें'} title={english ? 'Send' : 'भेजें'}>
                <span aria-hidden="true">{busy ? '…' : '↑'}</span>
              </button>
            </form>
          </>}
    {!mobileViewport && <button
      className="tutor-resize-grip"
      type="button"
      aria-label={english ? 'Resize tutor window' : 'ट्यूटर विंडो का आकार बदलें'}
      title={english ? 'Resize window' : 'विंडो का आकार बदलें'}
      onPointerDown={beginPanelResize}
      onPointerMove={resizePanel}
      onPointerUp={endPanelResize}
      onPointerCancel={endPanelResize}
      onKeyDown={event => {
        if (!['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown'].includes(event.key)) return;
        const rect = panelRef.current?.getBoundingClientRect();
        if (!frame && !rect) return;
        event.preventDefault();
        const base = frame || { left: rect!.left, top: rect!.top, width: rect!.width, height: rect!.height };
        const delta = event.shiftKey ? 40 : 16;
        const maxWidth = Math.max(320, window.innerWidth - base.left - 8);
        const maxHeight = Math.max(360, window.innerHeight - base.top - 8);
        setFrame({
          ...base,
          width: Math.max(320, Math.min(maxWidth, base.width + (event.key === 'ArrowRight' ? delta : event.key === 'ArrowLeft' ? -delta : 0))),
          height: Math.max(360, Math.min(maxHeight, base.height + (event.key === 'ArrowDown' ? delta : event.key === 'ArrowUp' ? -delta : 0))),
        });
      }}
    ><span aria-hidden="true">⌟</span></button>}
  </section>;
}
