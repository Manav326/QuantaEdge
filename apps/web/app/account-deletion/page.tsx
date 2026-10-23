import { UiText } from '../i18n/useUiTranslation';
import { LanguageSwitcher } from '../components/LanguageProvider';
import { AppearanceModeButton } from '../components/ColorThemeProvider';
import AccountDeletionPanel from '../components/AccountDeletionPanel';

export default function AccountDeletionPage() {
  return (
    <main className="legal-page qe-account-deletion-page">
      <div className="qe-language-toolbar"><AppearanceModeButton /><LanguageSwitcher /></div>
      <h1><UiText message="accountDeletion.title" /></h1>
      <AccountDeletionPanel />
    </main>
  );
}
