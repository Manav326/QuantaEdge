-- V119: preserve the legacy parent contact email separately from Google's verified identity email.
-- The email column is reserved for a Google-verified login identity; editing a contact address
-- must never grant a Gmail verification badge or change which Google subject owns an account.
ALTER TABLE user_account
  ADD COLUMN IF NOT EXISTS contact_email varchar(320);

UPDATE user_account
SET contact_email=email
WHERE contact_email IS NULL AND email IS NOT NULL;

INSERT INTO app_metadata(key,value)
VALUES ('schema','separate-contact-email-from-google-identity-v119')
ON CONFLICT(key) DO UPDATE SET value=excluded.value;
