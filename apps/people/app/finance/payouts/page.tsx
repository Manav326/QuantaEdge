'use client';

import PayoutsPanel from '../PayoutsPanel';
import styles from '../Finance.module.css';

/** Permission-scoped entry point into the same payout panel embedded in HRMS Finance. */
export default function FinancePayoutsPage(){
  return <main className={styles.page}>
    <header className={styles.header}>
      <div>
        <span className={styles.eyebrow}>HRMS · FINANCE OPERATIONS</span>
        <h1>Payouts & settlements</h1>
        <p>Review approved beneficiaries, reconcile confirmed transaction charges, and approve monthly RazorpayX transfers. No transfer is sent until the required permissions and legal-account checks are satisfied.</p>
      </div>
    </header>
    <PayoutsPanel/>
  </main>;
}
