/**
 * Shared locale-aware helpers for the student and family marketplaces.
 * Known server errors are translated without hiding unexpected diagnostic details.
 */
export type MarketplaceLanguage = 'en' | 'hi';

const HINDI_ERROR_MESSAGES: Array<{ matches: (message: string) => boolean; message: string }> = [
  {
    matches: message => message.includes('checkout is not configured') &&
      message.includes('legal seller') && message.includes('tax profile'),
    message: 'इस कोर्स का checkout अभी कॉन्फ़िगर नहीं है। HRMS को कानूनी विक्रेता सक्रिय करके इस कोर्स के लिए लागू tax profile की मंज़ूरी देनी होगी।',
  },
  {
    matches: message => message.includes('previous payment attempt is still being reconciled') ||
      (message.includes('previous payment') && message.includes('still being reconciled')),
    message: 'पिछले भुगतान का मिलान अभी जारी है। कुछ देर बाद इसी order की पुष्टि फिर करें; दोबारा भुगतान न करें।',
  },
  {
    matches: message => message.includes('payment status is still being reconciled'),
    message: 'भुगतान की स्थिति की जाँच अभी जारी है। पुष्टि पूरी होने तक दोबारा भुगतान न करें।',
  },
  {
    matches: message => message.includes('payment verification could not be completed'),
    message: 'भुगतान की पुष्टि पूरी नहीं हो सकी। दोबारा भुगतान करने से पहले इसी order की पुष्टि फिर करें।',
  },
  {
    matches: message => message.includes('payment status could not be reconciled'),
    message: 'भुगतान की स्थिति की पुष्टि नहीं हो सकी। यही order सुरक्षित रखें और फिर जाँचें; दोबारा भुगतान न करें।',
  },
  {
    matches: message => message.includes('a previous payment still needs verification'),
    message: 'पिछले भुगतान की पुष्टि बाकी है। नया checkout शुरू करने से पहले उसी order की जाँच करें।',
  },
  {
    matches: message => message.includes('payment checkout could not be loaded'),
    message: 'भुगतान checkout लोड नहीं हो सका। फिर प्रयास करें।',
  },
  {
    matches: message => message.includes('payment checkout is not available'),
    message: 'भुगतान checkout उपलब्ध नहीं है। फिर प्रयास करें।',
  },
  {
    matches: message => message.includes('the course order could not be prepared'),
    message: 'कोर्स का order तैयार नहीं हो सका। फिर प्रयास करें।',
  },
  {
    matches: message => message.includes('the payment confirmation did not match this order'),
    message: 'भुगतान की पुष्टि इस order से मेल नहीं खाती। नया checkout शुरू करने से पहले इसी order की जाँच करें।',
  },
  {
    matches: message => message.includes('checkout could not be started'),
    message: 'Checkout शुरू नहीं हो सका। फिर प्रयास करें।',
  },
  {
    matches: message => message.includes('the request could not be completed'),
    message: 'अनुरोध पूरा नहीं हो सका। फिर प्रयास करें।',
  },
  {
    matches: message => message.includes('this child is no longer linked to your account'),
    message: 'यह बच्चा अब आपके खाते से लिंक नहीं है।',
  },
];

export function localizeMarketplaceError(
  value: unknown,
  language: MarketplaceLanguage,
  fallbackEnglish: string,
  fallbackHindi: string,
): string {
  const candidate = value instanceof Error ? value.message
    : typeof value === 'string' ? value
    : value && typeof value === 'object' && 'message' in value
      ? String((value as { message?: unknown }).message ?? '')
      : '';
  const message = candidate.trim();
  if (!message) return language === 'en' ? fallbackEnglish : fallbackHindi;
  if (language === 'en') return message;

  const normalized = message.toLowerCase();
  // If an Error contains the English fallback passed by the caller, use its paired translation.
  if (normalized === fallbackEnglish.trim().toLowerCase()) return fallbackHindi;
  const translation = HINDI_ERROR_MESSAGES.find(item => item.matches(normalized));
  return translation?.message ?? message;
}
