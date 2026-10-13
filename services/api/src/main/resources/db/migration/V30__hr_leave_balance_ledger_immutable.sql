-- A leave balance transaction is an accounting fact. Correct it with a
-- compensating/reversal transaction; never mutate or delete a posted entry.
CREATE FUNCTION prevent_hr_leave_balance_transaction_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'Leave balance ledger is immutable; post a compensating transaction instead.';
END;
$$;

CREATE TRIGGER trg_hr_leave_balance_transaction_immutable
BEFORE UPDATE OR DELETE ON hr_leave_balance_transaction
FOR EACH ROW EXECUTE FUNCTION prevent_hr_leave_balance_transaction_mutation();
