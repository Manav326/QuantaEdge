-- Institution-owned approved branding assets. SVG is deliberately excluded: only raster image payloads are served.
CREATE TABLE institution_branding_asset (
  asset_id varchar(36) PRIMARY KEY,
  institution_id bigint NOT NULL REFERENCES institution_tenant(id) ON DELETE CASCADE,
  asset_type varchar(12) NOT NULL CHECK (asset_type IN ('LOGO','APP_ICON')),
  content_type varchar(32) NOT NULL CHECK (content_type IN ('image/png','image/jpeg')),
  file_name varchar(180) NOT NULL,
  content_sha256 varchar(64) NOT NULL CHECK (content_sha256 ~ '^[0-9a-f]{64}$'),
  image_bytes bytea NOT NULL,
  byte_length integer NOT NULL CHECK (byte_length > 0 AND byte_length <= 2097152),
  active boolean NOT NULL DEFAULT true,
  created_by_staff_id bigint REFERENCES staff_account(id) ON DELETE SET NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  CHECK (octet_length(image_bytes) = byte_length),
  UNIQUE(institution_id, asset_type, asset_id)
);

CREATE INDEX idx_institution_branding_asset_lookup
  ON institution_branding_asset(institution_id, asset_type, active, created_at DESC);

CREATE INDEX idx_institution_branding_asset_sha
  ON institution_branding_asset(content_sha256);
