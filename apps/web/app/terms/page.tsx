import { UiText } from '../i18n/useUiTranslation';
import { LanguageSwitcher, LocaleText } from '../components/LanguageProvider';

export default function TermsPage(){
  return <main className="legal-page">
    <div className="qe-language-toolbar"><LanguageSwitcher /></div>
    <h1><UiText message="legacy.web.terms.of.use.ca39f84f" /></h1>
    <p><UiText message="legacy.web.quantaedge.provides.educational.learni.66df2427" /></p>
    <p><UiText message="legacy.web.parents.guardians.are.responsible.for..ebc1ff25" /></p>
    <p><UiText message="legacy.web.only.authorized.users.may.access.stude.5ccec004" /></p>
  </main>;
}
