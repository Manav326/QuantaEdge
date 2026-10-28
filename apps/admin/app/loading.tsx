import styles from "./loading.module.css";

export default function Loading() {
  return (
    <div className={styles.loading} aria-busy="true">
      <span className={styles.srOnly} role="status">{description}</span>
      <div className={styles.content} aria-hidden="true">
        <div className={styles.header}>
          <span className={`${styles.placeholder} ${styles.title}`} />
          <span className={`${styles.placeholder} ${styles.subtitle}`} />
        </div>
        <div className={styles.toolbar}>
          <span className={`${styles.placeholder} ${styles.chip}`} />
          <span className={`${styles.placeholder} ${styles.chip}`} />
          <span className={`${styles.placeholder} ${styles.chip}`} />
        </div>
        <div className={styles.cards}>
          {Array.from({ length: 3 }, (_, index) => (
            <section className={styles.card} key={index}>
              <span className={`${styles.placeholder} ${styles.metric}`} />
              <span className={`${styles.placeholder} ${styles.line}`} />
              <span className={`${styles.placeholder} ${styles.lineShort}`} />
            </section>
          ))}
        </div>
        <section className={styles.panel}>
          {Array.from({ length: 4 }, (_, index) => (
            <div className={styles.tableRow} key={index}>
              <span className={`${styles.placeholder} ${styles.tableCell}`} />
              <span className={`${styles.placeholder} ${styles.tableCell}`} />
              <span className={`${styles.placeholder} ${styles.tableCellNarrow}`} />
            </div>
          ))}
        </section>
      </div>
    </div>
  );
}
