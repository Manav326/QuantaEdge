import { LanguageSwitcher, LocaleText } from '../components/LanguageProvider';

export default function TermsPage(){
  return <main className="legal-page">
    <div className="qe-language-toolbar"><LanguageSwitcher /></div>
    <h1><LocaleText hinglish="Terms of use" english="Terms of use" /></h1>
    <p><LocaleText hinglish="QuantaEdge Classes 6–8 के लिए educational learning software देता है। इसका content learning support के लिए है और school instruction या official assessment की जगह नहीं लेता।" english="QuantaEdge provides educational learning software for Classes 6–8. Content is intended as educational support and does not replace school instruction or official assessment." /></p>
    <p><LocaleText hinglish="Parent/guardian अपने बनाए student profile की information के लिए और student access PIN सुरक्षित रखने के लिए ज़िम्मेदार हैं।" english="Parents/guardians are responsible for the student profile information they create and for maintaining the student access PIN." /></p>
    <p><LocaleText hinglish="Student या administrative data सिर्फ authorized users ही access कर सकते हैं।" english="Only authorized users may access student or administrative data." /></p>
  </main>;
}
