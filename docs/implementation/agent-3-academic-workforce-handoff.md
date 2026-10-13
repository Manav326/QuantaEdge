# Agent 3 handoff — Academic workforce, scheduling and Google Meet

**Status:** implementation committed to the assigned working branch; local builds, automated tests, database migration execution, and live Google Meet creation have **not** been verified in this session. Do not merge or describe this as production-verified until the test suite and migration run successfully.

- Repository: [Manav326/QuantaEdge](https://github.com/Manav326/QuantaEdge)
- Baseline branch: `main`
- Audited main commit: `0d71946b3854a68c894001193a5174a41fa9a45b`
- Working branch: `agent/academic-workforce-google-meet`
- Code checkpoint immediately before this handoff document: `5d404de810df9dc2fbef4aa0055ca32c2e6a7153`
- Pull request: none opened, as requested.

## 1. Baseline audit

The latest `main` tree was inspected before implementation. It already contained the HRMS employee model and permission framework, academic-period and teaching-assignment tables/controller, assignment approval and history flows, canonical curriculum class/subject/chapter records, student enrollment records, live-class sessions, recording support, and admin/People Operations class pages.

The implementation builds on those records instead of creating duplicate employee, identity, class, subject, or student systems. Relevant source files are:

- `services/api/src/main/java/com/quantaedge/api/HrTeachingAssignmentController.java`
- `services/api/src/main/java/com/quantaedge/api/LearningClassController.java`
- `apps/people/app/assignments/page.tsx`
- `apps/admin/app/classes/page.tsx`
- `services/api/src/main/resources/db/migration/V67__hrms_teaching_assignments.sql`
- `services/api/src/main/resources/db/migration/V68__live_class_teaching_assignment_link.sql`

The main observed gaps were: a manually pasted Meet URL was being treated as though a Google meeting existed; external Meet creation status and retry state were not persisted; schedule overlap checks were incomplete; there were no teacher-owned class views; the assignment UI lacked clear proposal-amendment and cancellation actions; and the reporting UI did not bring together assignment coverage, workload, and persisted class data.

## 2. Canonical models and lifecycle

### Academic periods

Reuses `hr_academic_period`. Creation validates date order, limits period-code characters, enforces unique organisation/code and organisation/name constraints supplied by the schema, and rejects intersecting date ranges for the same organisation (except cancelled periods).

Added `PATCH /api/v1/hr/academic-periods/{id}/status`:

- `PLANNED → ACTIVE` is permitted while the period has not ended.
- `PLANNED → CANCELLED` requires a reason and is refused while pending or approved assignments still refer to the period.
- `ACTIVE → CLOSED` is permitted only after the effective end date, requires a reason, and is refused while future scheduled/live classes remain.
- Closed and cancelled periods are terminal.

Assignment effectiveness is based on the saved effective start/end dates and the scheduled class date in `Asia/Kolkata`; the database record is not deleted when a period ends.

### Teacher assignments

The authoritative reference is the existing `hr_teaching_assignment` row connecting an HR employee, canonical curriculum subject/class, academic period, and effective dates. The existing proposal, approval/rejection, replacement, and history mechanisms are reused.

Additional behavior:

- `PUT /api/v1/hr/teaching-assignments/{id}` permits amendment of a pending proposal only; the amendment stays pending and receives history/audit entries.
- `PATCH /api/v1/hr/teaching-assignments/{id}/status` supports reasoned cancellation for pending or approved assignments while preserving the original row and history.
- Existing approval/replacement behavior remains responsible for approval decisions and effective-dating the former assignment; the prior record is not deleted.
- New live-class scheduling requires an approved, effective teaching assignment for the chosen class and subject.
- Staff employment eligibility is checked against the existing employee lifecycle status. Current code permits `ACTIVE`, `ON_LEAVE`, and `NOTICE_PERIOD`; verify that this matches the final HR policy during integration.
- Scheduled class teacher replacement is available through `PUT /api/v1/admin/live-classes/{sessionId}/teacher-assignment`. It accepts only an approved/effective assignment for the same subject and eligible employee, checks overlapping sessions, changes the session's assignment reference, and writes audit history. Live/past classes are not silently reassigned.

The frontend now exposes pending proposal amendment, cancellation with a reason, academic period lifecycle actions, and links to a teacher's My Classes workspace.

## 3. API and contract inventory

All endpoints below are under the existing API context shown by the application.

### HR and academic workforce

| Method | Route | Purpose |
|---|---|---|
| GET / POST | `/api/v1/hr/academic-periods` | List/create periods (existing routes, creation validation extended) |
| PATCH | `/api/v1/hr/academic-periods/{id}/status` | Activate, cancel or close with lifecycle checks |
| POST | `/api/v1/hr/teaching-assignments` | Propose an assignment (existing) |
| PUT | `/api/v1/hr/teaching-assignments/{id}` | Amend a pending proposal |
| PATCH | `/api/v1/hr/teaching-assignments/{id}/status` | Cancel an assignment with a reason |
| GET | `/api/v1/hr/teaching-assignments/{id}/history` | Existing assignment history (reused) |
| GET / PUT | `/api/v1/hr/teaching-workload-policy` | Read/update configured organisation workload threshold |
| GET | `/api/v1/hr/academic-reporting` | Persisted coverage, workload, class statuses and overlap counts |

The existing approval/decision and list routes remain in place.

### Scheduling and live classes

| Method | Route | Purpose |
|---|---|---|
| POST | `/api/v1/admin/live-classes` | Persist a class, then request/check its external Meet when selected |
| GET | `/api/v1/admin/approved-teaching-assignments` | Return approved assignments effective for a class/subject/date |
| PATCH | `/api/v1/admin/live-classes/{sessionId}/status` | Validated status transitions, external cancellation, audit |
| PUT | `/api/v1/admin/live-classes/{sessionId}/teacher-assignment` | Assign/replace teacher on a scheduled class |
| PUT | `/api/v1/admin/live-classes/{sessionId}/schedule` | Conflict-checked rescheduling; Google event is changed before local times are updated |
| POST | `/api/v1/admin/live-classes/{sessionId}/google-meet/retry` | Recheck/retry Meet creation or cancellation |
| GET | `/api/v1/admin/live-classes/{sessionId}/meeting-link` | Staff-only link disclosure; unverified Google Meet URLs are suppressed |
| GET | `/api/v1/teaching/my-live-classes` | List a logged-in employee's eligible assigned classes |
| GET | `/api/v1/teaching/live-classes/{sessionId}/meeting-link` | Reveal link only to the assigned and eligible teacher |
| GET / POST | `/api/v1/learning/live-classes`, `/api/v1/learning/live-classes/{sessionId}/join` | Existing student list/join, tightened with enrollment/assignment/eligibility/link checks |

The admin class form uses the actual assignment picker. Google Meet is not created by accepting a URL from the browser.

## 4. Permissions and eligibility rules

- Assignment proposals, decisions, amendment, cancellation, period lifecycle, policy changes, reports, class scheduling, teacher reassignment, retries, status transitions, and staff meeting-link disclosure are server-authorized through the existing authorization services.
- Class-management actions use the existing `CLASS_MANAGE` policy; they do not grant a global administrator role.
- Assignment management and review continue to use the existing HR permissions and separation of proposal/review authority.
- Workload-policy writes require `CLASS_SCHEDULE_POLICY_MANAGE`; the academic reporting API requires the existing HR academic-assignment view permission.
- Student class access continues to require the actual student identity, active student record, matching class and active subject enrollment from `student_track_enrollment`. It additionally checks that the linked assignment is approved, the employee is eligible, the assignment covers the class date, and (for Google Meet) the managed integration state is `CREATED`.
- Teacher endpoints derive the teacher from the authenticated staff account's `hr_employee.linked_staff_account_id`, then require an approved, effective assignment and an eligible employee record. Guessing a session ID is insufficient to obtain a meeting link.
- The join window opens 15 minutes before start time and closes when the class ends. Existing private recording checks and student attendance model are retained.
- Newly created live classes without a canonical teacher assignment are rejected. Historical unassigned sessions can still appear in the admin's records for reconciliation, but student join/list access is withheld until an approved assignment is attached. The scheduled-class teacher assignment endpoint provides that correction path.

**HRMS integration dependency:** an employee who needs the teacher workspace must be linked to the correct staff identity. The authoritative employee eligibility/status contract should be confirmed with Agent 1 before merging; this branch intentionally does not rewrite the HRMS foundation.

## 5. Scheduling and workload validation

- Start/end ordering and maximum duration are validated; effective dates are checked using the scheduled class date in `Asia/Kolkata`.
- Scheduled/live overlaps for the same assigned HR employee or staff host are hard conflicts and are rejected server-side, including during teacher replacement and rescheduling. The frontend presents the server error.
- Rescheduling is allowed only for scheduled classes. For a verified Google Meet, the external Calendar event must be updated successfully before local start/end times are changed; failures return an explicit error and leave the local time unchanged.
- A teacher replacement must match the session subject, be approved and effective for the session date, refer to an eligible employee, and not conflict with another scheduled/live session.
- Workload thresholds are stored in `hr_teaching_workload_policy` and configurable per organisation (60–6000 minutes/week). No arbitrary default limit is applied. The report compares persisted scheduled/live/completed classes with the configured threshold and labels breaches as **warnings**, distinct from hard schedule-overlap conflicts.
- The reporting endpoint returns saved assignment coverage (including active subjects with no approved covering assignment), per-employee weekly scheduled workload, class status counts, and detected teacher/host overlaps. It does not fabricate attendance or learning metrics.

## 6. Google Meet implementation and external setup

The integration uses Google Calendar API event conference data instead of saving a placeholder Meet URL:

1. Refresh a server-side OAuth access token from the configured refresh token.
2. Insert a Calendar event with `conferenceDataVersion=1`, a unique conference `createRequest.requestId`, the `hangoutsMeet` conference solution, start/end timestamps, and an explicit time zone.
3. Persist the provider event ID, conference request ID, operation status, attempts, and provider error state.
4. Treat conference generation as asynchronous. A valid `https://meet.google.com` URL plus a successful conference response is needed before marking the integration `CREATED`; otherwise the state remains `PENDING` or `FAILED`.
5. Retry by retrieving the stable managed event ID and checking conference status. A new request ID is used for a genuine new conference request. Stable event IDs make recovery after a lost create response idempotent.
6. Rescheduling patches the existing event; cancellation deletes it. Missing event IDs after an uncertain create attempt are recovered using the deterministic managed event ID.
7. Credentials, access tokens and refresh tokens are not committed to source. External errors are mapped to safe configuration/auth/permission/quota/network/provider messages; the local scheduled class is retained when Meet creation fails.

### Required deployment configuration

Set these variables in the API deployment's secret configuration (not in a committed environment file):

- `GOOGLE_MEET_CLIENT_ID`
- `GOOGLE_MEET_CLIENT_SECRET`
- `GOOGLE_MEET_REFRESH_TOKEN`
- `GOOGLE_MEET_CALENDAR_ID` (defaults to `primary`)
- `GOOGLE_MEET_TIME_ZONE` (defaults to `Asia/Kolkata`)

Enable Google Calendar API on the selected Google Cloud project and authorize a dedicated integration account for event creation/update/delete. For an owned primary calendar, use the least-privilege event-write scope `https://www.googleapis.com/auth/calendar.events.owned`; use `https://www.googleapis.com/auth/calendar.events` only where access to shared calendars requires it. Ensure the refresh token was issued with the selected scope and that token persistence is secured by the deployment secret store. A browser login or pasted link does not replace server-side authorization.

Official documentation consulted:
- [Events: insert](https://developers.google.com/workspace/calendar/api/v3/reference/events/insert)
- [Calendar Events resource](https://developers.google.com/workspace/calendar/api/v3/reference/events)
- [Events: update](https://developers.google.com/workspace/calendar/api/v3/reference/events/update)

A real Google Meet session has **not** been created or verified in this implementation session because deployment credentials and a live authorized Google account were not provided. Automated tests use mocks and must not be represented as live Google verification.

### Migration and legacy data

Migration `V71__managed_google_meet_and_workload_policy.sql` adds provider lifecycle/error/attempt fields, makes `private_join_url` nullable so failed/pending Meet creation can be represented accurately, and adds the configurable workload-policy table and workload-policy permission catalog entry.

Existing `GOOGLE_MEET` records are marked `FAILED / LEGACY_UNVERIFIED`, because a legacy stored URL alone does not establish that a managed Calendar conference exists. Their old URL is not disclosed through staff/student Meet-link paths while unverified. An admin can link an eligible assignment to a scheduled record and retry managed Meet setup.

## 7. Tests added and actual results

Added or amended tests include:

- Google Meet with missing credentials fails closed with an actionable configuration error.
- Mocked Google Calendar event insertion returns only a verified `meet.google.com` URL.
- Google permission-denial errors are mapped to an explicit setup message.
- A failed conference request is retried with a new conference request ID.
- Staff meeting-link response suppresses a legacy/unverified Google Meet URL.
- Academic-period overlap is rejected.

Existing class status test stubbing was updated for the expanded persisted class/assignment query.

**Execution result: NOT RUN / NOT VERIFIED.** The requested Maven verification command is `cd services/api && mvn -B -ntp verify`; it was not executed in this session because no working repository checkout/build environment was available. A container clone attempt failed at DNS resolution. No GitHub Actions run exists for this branch at handoff time. The checked-in workflow runs on pushes to `main`, pull requests targeting `main`, or manual `workflow_dispatch`; the assigned branch was not covered by an automatic run, and no pull request was opened. The People/Admin production builds and the V71 migration also remain unexecuted. Do not infer passing tests from the presence of test source files.

Recommended verification once a runner is available:
1. Manually dispatch `.github/workflows/ci.yml` against `agent/academic-workforce-google-meet` to exercise API verification and affected frontend builds.
2. Run the V67–V71 migrations against a disposable PostgreSQL 17 database and verify existing class/assignment data, especially legacy Google Meet records and unassigned scheduled sessions.
3. Run mocked tests and inspect API permission failures, effective-date boundaries, teacher replacements, concurrent schedule conflicts, retry-after-timeout, rescheduling and cancellation.
4. With real secret-store configuration, create one managed Calendar/Meet event, verify the returned URL and conference status, reschedule it, cancel it, and confirm student/teacher authorization using actual test accounts.

## 8. Known integration assumptions and remaining blockers

- HRMS remains the owner of employee lifecycle, organization and identity linkage. The allowed employee statuses in academic scheduling must be confirmed with the HRMS owner.
- Student eligibility relies on existing active student/class identity and active subject enrollment; this work does not add a new enrollment model.
- Google Calendar's configured integration account creates/owns the external Calendar event. Teacher access in QuantaEdge is tied to the approved assignment; deployment owners must confirm the organization's preferred Meet host/co-host policy if individual teachers need organizer-level meeting controls.
- An unconfigured or unauthorized Google integration leaves the local schedule visible to administrators with explicit `FAILED` state and provides retry after configuration; it never pretends the Meet link exists.
- Weekly workload overruns are advisory warnings, whereas actual timetable overlaps are blocking conflicts.
- Assignment effective dates protect access even if the assignment row retains an approved historical status. The records are not deleted.
- Builds, unit tests, migration execution, real OAuth, real event creation, event cancellation/rescheduling, and end-to-end student/teacher link access remain unverified until the recommended verification runs complete.

## 9. Branch and commit record

Working branch: `agent/academic-workforce-google-meet`

Key committed changes on the branch include:

- `3e123bde54a0e88885707fde04d5b84f12891ed0` — enforce approved/effective assignment state for class access.
- `f76ba19532af3f92be74bf9510ec67a2768a2ff8` — assignment amendment and workload reporting API.
- `e663ddf16e7fe998e4dab4de74d1bc806f356f74` — People assignment amendment/cancellation UI.
- `32a77f7c9b49f59f745c49c7f17333b8e22cc087` — academic coverage/workload reporting UI.
- `15342d6f4a2ffbbfca1cc4d690bfb09bf1726835` — Google configuration cleanup and live status checks.
- `2eefcd1c0ed060301f17a2c1ba7f0b4e667028b5` — authorized teacher live-class endpoints.
- `07dcffd7fd5461462cb136f87a2cc7ae4715a690` — People My Classes workspace.
- `fd37a51bd24d757e24e5f5e30492e16a398d5b74` — Meet/assignment safeguards tests.
- `8a7b39a2dd31df3299bcfbc3a030e5858fe84a09` — require canonical teacher assignment for new/live student access.
- `0e313c7df4322d1bb596e14950a30267d89a6cfd` — scheduled teacher replacement endpoint.
- `7813c556e068c8e33210802239cb5017415d4a96` — admin teacher replacement controls.
- `d7f0aa27062f9490f3fdd6ba3079fa5bd90eab64` — idempotent Meet cancellation/retry behavior.
- `5d404de810df9dc2fbef4aa0055ca32c2e6a7153` — academic period lifecycle controls.
- `527558df2913aedb72542d51be016c705842b0ea` — employee-eligibility gates for student class links.
- `64bc8340e0b12007e54c6dbead42d04df3a896cd` — restore legacy non-Meet cancellation UI.
- `3f7ca5dc058cf4dc74cf54f1300fa7bb52dc399f` — least-privilege Calendar scope guidance.
- `b26168a46746748d08c89151b64099d34d7b8149` — initial handoff document commit.

All listed commits were created on the remote feature branch. This handoff document is committed separately; inspect the branch head for the exact final revision. No commit was made to `main`, no force-push was performed, and no pull request was opened.
