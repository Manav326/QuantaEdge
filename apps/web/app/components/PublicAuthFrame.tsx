'use client';

import type { ReactNode } from 'react';
import Link from 'next/link';
import QuantaEdgeBrand from './QuantaEdgeBrand';
import { AppearanceModeButton } from './ColorThemeProvider';
import { LanguageSwitcher, useLocale } from './LanguageProvider';
import { UiText } from '../i18n/useUiTranslation';

type PublicAuthFrameProps = {
  children: ReactNode;
  story?: 'account' | 'student';
};

export default function PublicAuthFrame({ children, story = 'account' }: PublicAuthFrameProps) {
  const { locale } = useLocale();
  const studentStory = story === 'student';
  const english = locale === 'english';

  return (
    <main className={'auth-page qe-auth-split' + (studentStory ? ' qe-auth-split--student' : '')}>
      <aside className="qe-auth-story" aria-label={english ? 'About this learning experience' : 'इस learning experience के बारे में'}>
        <div className="qe-auth-story-brand">
          <QuantaEdgeBrand variant="auth" />
        </div>
        <div className="qe-auth-story-copy">
          <span className="eyebrow">
            <UiText message={studentStory ? 'public.auth.student.eyebrow' : 'public.auth.story.eyebrow'} />
          </span>
          <h2><UiText message={studentStory ? 'public.auth.student.title' : 'public.auth.story.title'} /></h2>
          <p><UiText message={studentStory ? 'public.auth.student.body' : 'public.auth.story.body'} /></p>
          <ul className="qe-auth-story-points">
            <li><span aria-hidden="true">✓</span><UiText message={studentStory ? 'public.auth.student.pointOne' : 'public.auth.story.pointOne'} /></li>
            <li><span aria-hidden="true">✓</span><UiText message={studentStory ? 'public.auth.student.pointTwo' : 'public.auth.story.pointTwo'} /></li>
            <li><span aria-hidden="true">✓</span><UiText message={studentStory ? 'public.auth.student.pointThree' : 'public.auth.story.pointThree'} /></li>
          </ul>
        </div>
        <div className="qe-auth-art" aria-hidden="true">
          <span className="qe-auth-art-orbit qe-auth-art-orbit--one" />
          <span className="qe-auth-art-orbit qe-auth-art-orbit--two" />
          <div className="qe-auth-art-card qe-auth-art-card--back"><i /><i /><i /></div>
          <div className="qe-auth-art-card qe-auth-art-card--front">
            <span className="qe-auth-art-kicker">NEXT STEP</span>
            <span className="qe-auth-art-line qe-auth-art-line--wide" />
            <span className="qe-auth-art-line" />
            <span className="qe-auth-art-progress"><i /></span>
            <span className="qe-auth-art-dots"><i /><i /><i /></span>
          </div>
          <span className="qe-auth-art-spark qe-auth-art-spark--one">✦</span>
          <span className="qe-auth-art-spark qe-auth-art-spark--two">✧</span>
        </div>
        <p className="qe-auth-story-footer"><UiText message="public.footer.tagline" /></p>
      </aside>

      <div className="qe-auth-content">
        <div className="qe-public-controls" aria-label={english ? 'Display preferences' : 'दिखावट की सेटिंग'}>
          <AppearanceModeButton />
          <LanguageSwitcher compact />
        </div>
        <div className="qe-auth-mobile-brand"><QuantaEdgeBrand variant="auth" /></div>
        {children}
        <footer className="qe-auth-legal">
          <nav aria-label={english ? 'Legal information' : 'कानूनी जानकारी'}>
            <Link href="/privacy"><UiText message="legacy.web.privacy.323f3a35" /></Link>
            <Link href="/terms"><UiText message="legacy.web.terms.c9f30225" /></Link>
          </nav>
        </footer>
      </div>
    </main>
  );
}
