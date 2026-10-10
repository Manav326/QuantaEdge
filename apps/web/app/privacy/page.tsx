import { LanguageSwitcher, LocaleText } from '../components/LanguageProvider';

export default function PrivacyPage(){
  return <main className="legal-page">
    <div className="qe-language-toolbar"><LanguageSwitcher /></div>
    <h1><LocaleText hinglish="Privacy" english="Privacy" /></h1>
    <p><LocaleText hinglish="QuantaEdge सिर्फ वही information store करता है जो learning, authentication, guardian access, progress और support features देने के लिए ज़रूरी है।" english="QuantaEdge stores only the information needed to provide learning, authentication, guardian access, progress and support features." /></p>
    <p><LocaleText hinglish="Student learning records authenticated student और authorized guardian से जुड़े रहते हैं। Production में demo/preview data बंद रहता है।" english="Student learning records are linked to the authenticated student and authorized guardian. Demo/preview data is disabled in production." /></p>
    <p><LocaleText hinglish="जब AI tutor enabled हो, learner का tutor message और lesson का relevant context configured AI provider को response बनाने के लिए भेजा जाता है। QuantaEdge जान-बूझकर passwords, OTPs या account credentials tutor को नहीं भेजता। Learning continuity, safety, support और operational analytics के लिए tutor messages और usage records QuantaEdge में रखे जाते हैं।" english="When the AI tutor is enabled, the learner's tutor message and relevant lesson context are sent to the configured AI provider to generate a response. QuantaEdge does not intentionally send passwords, OTPs, or account credentials to the tutor. Tutor messages and usage records are retained in QuantaEdge for learning continuity, safety, support, and operational analytics." /></p>
    <p><LocaleText hinglish="Data deletion या privacy request के लिए अपने QuantaEdge deployment में configured support/contact channel का इस्तेमाल करें।" english="For deletion or privacy requests, use the support/contact channel configured for your QuantaEdge deployment." /></p>
  </main>;
}
