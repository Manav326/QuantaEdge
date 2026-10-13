-- Preserve the previous and requested values whenever an attendance row is corrected.
-- History is append-only; corrections never erase the prior approved value.
CREATE TABLE hr_attendance_correction_history (
  id BIGSERIAL PRIMARY KEY,
  attendance_record_id BIGINT NOT NULL REFERENCES hr_attendance_record(id) ON DELETE RESTRICT,
  employee_id BIGINT NOT NULL REFERENCES hr_employee(id) ON DELETE RESTRICT,
  work_date DATE NOT NULL,
  previous_status VARCHAR(16) NOT NULL,
  previous_check_in_at TIMESTAMPTZ,
  previous_check_out_at TIMESTAMPTZ,
  previous_notes VARCHAR(500),
  corrected_status VARCHAR(16) NOT NULL,
  corrected_check_in_at TIMESTAMPTZ,
  corrected_check_out_at TIMESTAMPTZ,
  corrected_notes VARCHAR(500),
  correction_reason VARCHAR(1000) NOT NULL CHECK (length(trim(correction_reason)) > 0),
  changed_by_staff_id BIGINT NOT NULL,
  changed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_hr_attendance_correction_employee_date
  ON hr_attendance_correction_history(employee_id, work_date, changed_at DESC);
CREATE FUNCTION prevent_hr_attendance_correction_history_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'Attendance correction history is immutable; record a compensating correction instead.';
END;
$$;
CREATE TRIGGER trg_hr_attendance_correction_history_immutable
BEFORE UPDATE OR DELETE ON hr_attendance_correction_history
FOR EACH ROW EXECUTE FUNCTION prevent_hr_attendance_correction_history_mutation();
