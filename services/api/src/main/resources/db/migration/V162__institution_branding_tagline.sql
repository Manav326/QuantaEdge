-- V162: institution brand taglines are first-class text rendered by shared, theme-aware brand components.
ALTER TABLE institution_branding
  ADD COLUMN tagline varchar(240) NOT NULL DEFAULT ''
  CHECK (tagline !~ '[[:cntrl:]]');
