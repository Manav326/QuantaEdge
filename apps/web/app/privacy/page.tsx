import { UiText } from '../i18n/useUiTranslation';
import { LanguageSwitcher, LocaleText } from '../components/LanguageProvider';

export default function PrivacyPage(){
  return <main className="legal-page">
    <div className="qe-language-toolbar"><LanguageSwitcher /></div>
    <h1><UiText message="legacy.web.privacy.323f3a35" /></h1>
    <p><UiText message="legacy.web.quantaedge.stores.only.the.information.f40752c6" /></p>
    <p><UiText message="legacy.web.student.learning.records.are.linked.to.cbdf9bda" /></p>
    <p><UiText message="legacy.web.when.the.ai.tutor.is.enabled.the.learn.650d7d45" /></p>
    <p><UiText message="legacy.web.for.deletion.or.privacy.requests.use.t.ae8d398e" /></p>
  </main>;
}
