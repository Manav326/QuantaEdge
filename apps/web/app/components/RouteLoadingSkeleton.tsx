import styles from "../loading.module.css";

export type RouteLoadingVariant = "dashboard" | "lesson" | "practice" | "list";

export function RouteLoadingSkeleton({
  description,
  variant = "dashboard",
}: {
  description: string;
  variant?: RouteLoadingVariant;
}) {
  return (
    <div className={styles.loading} aria-busy="true">
      <span className={styles.srOnly} role="status">{description}</span>
      <div className={styles.content} aria-hidden="true">
        <div className={styles.header}>
          <span className={`${styles.placeholder} ${styles.title}`} />
          <span className={`${styles.placeholder} ${styles.subtitle}`} />
        </div>

        {variant === "dashboard" ? (
          <>
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
            <LoadingRows />
          </>
        ) : null}

        {variant === "list" ? (
          <>
            <div className={styles.toolbar}>
              <span className={`${styles.placeholder} ${styles.chip}`} />
              <span className={`${styles.placeholder} ${styles.chip}`} />
            </div>
            <LoadingRows />
          </>
        ) : null}

        {variant === "lesson" ? (
          <div className={styles.lessonLayout}>
            <section className={styles.lessonPanel}>
              <span className={`${styles.placeholder} ${styles.line}`} />
              <span className={`${styles.placeholder} ${styles.line}`} />
              <span className={`${styles.placeholder} ${styles.lineShort}`} />
              <span className={`${styles.placeholder} ${styles.line}`} />
            </section>
            <section className={styles.lessonPanel}>
              <span className={`${styles.placeholder} ${styles.lineShort}`} />
              <span className={`${styles.placeholder} ${styles.line}`} />
              <span className={`${styles.placeholder} ${styles.line}`} />
            </section>
          </div>
        ) : null}

        {variant === "practice" ? (
          <section className={styles.practicePanel}>
            <span className={`${styles.placeholder} ${styles.lineShort}`} />
            <span className={`${styles.placeholder} ${styles.questionLine}`} />
            <span className={`${styles.placeholder} ${styles.line}`} />
            {Array.from({ length: 4 }, (_, index) => (
              <div className={styles.optionRow} key={index}>
                <span className={styles.optionDot} />
                <span className={`${styles.placeholder} ${styles.optionLine}`} />
              </div>
            ))}
            <div className={styles.actionRow}>
              <span className={`${styles.placeholder} ${styles.actionButton}`} />
              <span className={`${styles.placeholder} ${styles.actionButton}`} />
            </div>
          </section>
        ) : null}
      </div>
    </div>
  );
}

function LoadingRows() {
  return (
    <section className={styles.panel}>
      {Array.from({ length: 4 }, (_, index) => (
        <div className={styles.tableRow} key={index}>
          <span className={`${styles.placeholder} ${styles.tableCell}`} />
          <span className={`${styles.placeholder} ${styles.tableCell}`} />
          <span className={`${styles.placeholder} ${styles.tableCellNarrow}`} />
        </div>
      ))}
    </section>
  );
}
