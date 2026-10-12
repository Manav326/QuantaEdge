# QuantaEdge learning and assessment data model

## Three different kinds of learning record

QuantaEdge deliberately keeps learning activity, completion, and mastery separate.

- **Lesson activity**: opening a lesson updates `student_lesson_progress`; inline checks write `student_question_attempt.activity_kind='LESSON_INLINE'`.
- **Repeatable practice**: `student_practice_session` is one practice run; `student_practice_session_question` freezes the chosen question order and snapshots; `student_question_attempt` records every answer/retry with `activity_kind='PRACTICE'` and the practice-session ID. Practice can repeat without spending formal test attempts.
- **Initial diagnostic**: `student_diagnostic_attempt` stores the persistent attempt lifecycle, and `student_diagnostic_attempt_question` stores each chosen question snapshot, saved answer and grade. Diagnostic attempts resume after refresh and can only be submitted when every required question has an answer. Diagnostic activity is not included in ordinary practice accuracy/mastery.

## One durable system for all tests

Topic-end tests, chapter-end tests and admin-created tests share the existing assessment engine rather than introducing separate result tables.

| Purpose | Persistent record |
|---|---|
| Test definition/type/status/duration/attempt limit | `assessment` |
| Exact question set and marks published for a test | `assessment_question` with immutable question snapshots |
| Each learner’s attempt, status, start/deadline, scores and release time | `student_assessment_attempt` |
| The question order and snapshot for that learner's attempt | `student_assessment_attempt_question` |
| Saved response, grading state, marks and teacher feedback | `student_assessment_answer` |
| Publication, generation, grading and release events | `assessment_audit_log` |

`assessment.assessment_type` distinguishes `TOPIC_END`, `CHAPTER_END`, and `ADMIN_CREATED`. Manual admin-authored tests remain authored, reviewed and published by staff; new records default to `ADMIN_CREATED`.

Generated topic/chapter tests are materialized only from active, published lessons and approved/published questions. Their question set is snapshotted in `assessment_question`. Once students have attempts, an approved-content change archives that generated definition and produces a new version; historic attempts keep their old snapshots, submitted answers, scores and results. An in-progress attempt always resumes against its own immutable attempt-question snapshots.

The initial thresholds are three eligible questions to offer a topic-end test and five to offer a chapter-end test. The generator selects up to ten topic questions and up to twenty chapter questions. If a scope lacks enough eligible questions, it does not invent questions or publish an incomplete test.

## Rules used for meaningful reporting

1. **Completion** is a workflow milestone. A lesson requires all of its published inline MCQs to have been answered and a linked practice session to be completed. An incorrect answer may count as an attempt but does not count as mastery.
2. **Accuracy** uses graded answers from lesson-inline MCQs and ordinary practice. Diagnostic responses are deliberately excluded.
3. **Concept mastery** is recomputed using the latest graded outcome per distinct question, not by treating unlimited retries as independent evidence.
4. **Formal-test results** only contribute to released-test statistics and recommendations after submission, any required teacher grading, and release. Pending written answers are never represented as a final score.
5. **Student and parent dashboards** use the same `LearningMetricsService` definitions. The parent endpoints remain scoped to the linked child and are read-only.
6. **Recommendation** starts with an explicit submitted diagnostic, then prioritizes incomplete lessons and may bring a completed lesson back into the review queue when a released assessment or recorded concept mastery shows a weak result.

## Important rollout note

Flyway migration V53 adds fields and tables without deleting the existing attempt history. Legacy question attempts cannot always be classified accurately as diagnostic versus lesson-inline because older diagnostic records did not persist a diagnostic-attempt ID on each answer; the new flow records this distinction explicitly.
