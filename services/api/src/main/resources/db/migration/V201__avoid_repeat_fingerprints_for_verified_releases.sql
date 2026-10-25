-- V201: keep the canonical content fingerprint as a release-transition integrity check,
-- not a full graph scan on every HRMS/learner Marketplace read or payment verification.
--
-- V196 refreshes the fingerprint and updated_at for current reviewed releases. A release created,
-- submitted, quality-approved or made shareable after that cutoff is also validated at its
-- transition, and V180 makes the corresponding content graph immutable from IN_QUALITY_REVIEW
-- onward. We can therefore trust a well-formed saved fingerprint for those releases without
-- recomputing the full content graph for every listing, checkout and payment verification.
--
-- V196 intentionally leaves stale reviewed releases' fingerprints and updated_at untouched.
-- A pre-cutoff release is therefore treated as stale and fails closed until its source submits a
-- fresh version. Do not recompute its full graph here: it could be the same memory-heavy legacy
-- course that caused PostgreSQL to kill a backend process.
CREATE OR REPLACE FUNCTION course_release_fingerprint_current(
  p_program_id bigint,
  p_saved_fingerprint text,
  p_release_updated_at timestamptz
)
RETURNS boolean
LANGUAGE plpgsql
STABLE
AS $$
DECLARE
  v_v196_cutoff timestamptz;
BEGIN
  IF p_program_id IS NULL
     OR p_saved_fingerprint IS NULL
     OR btrim(p_saved_fingerprint) !~ '^[0-9a-f]{32}$'
     OR p_release_updated_at IS NULL THEN
    RETURN false;
  END IF;

  SELECT installed_on::timestamptz
    INTO v_v196_cutoff
  FROM public.flyway_schema_history
  WHERE version='196' AND success=true
  ORDER BY installed_rank DESC
  LIMIT 1;

  IF v_v196_cutoff IS NULL THEN
    RETURN false;
  END IF;

  IF p_release_updated_at >= v_v196_cutoff - interval '1 minute' THEN
    RETURN true;
  END IF;

  -- V196 deliberately did not refresh stale reviewed rows. Fail closed without invoking the
  -- expensive graph hash; re-submission computes a current fingerprint and opens a new release.
  RETURN false;
END;
$$;

COMMENT ON FUNCTION course_release_fingerprint_current(bigint,text,timestamptz) IS
  'Trusts canonical fingerprints refreshed by V196 or stored by later validated release transitions because V180 freezes released course graphs. Pre-V196 rows left untouched by V196 fail closed and require fresh source submission rather than triggering a potentially memory-heavy legacy hash.';
