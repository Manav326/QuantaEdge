# QuantaEdge

AI-powered learning platform for Hindi-medium students, starting with Bihar Board Classes 6–8.

## Product surfaces

- Web landing: http://localhost:3000
- Student home: http://localhost:3000/student
- Student learning tracks: http://localhost:3000/student/learn
- Interactive practice: http://localhost:3000/student/practice
- Student progress: http://localhost:3000/student/progress
- Parent view: http://localhost:3000/parent
- Login entry: http://localhost:3000/login
- Admin workspace: http://localhost:3001
- Curriculum API: http://localhost:8080/api/v1/curriculum
- API health: http://localhost:8080/actuator/health

## Curriculum and content status

The canonical curriculum outline contains 102 chapter rows across the six Class 6–8 Mathematics and Science tracks. Student pages and API requests are scoped to the authenticated student's class and the selected subject. The admin Content Studio lists chapters and lessons by class, subject, status and search; content can be created as draft, edited, reviewed and published/unpublished through authenticated admin access.

**Important:** chapter rows and lesson shells are not the same as completed teaching content. Generic generated lesson shells are kept unpublished until they contain original, reviewed teaching blocks and valid questions. In the clean-migration baseline, four active approved original questions exist (three MCQs and one True/False), all in Class 7 Mathematics. All six official textbook editions and chapter/page mappings still require editorial verification.

SCERT Bihar is the curriculum source of truth. The extraction workflow creates a page-addressable review bundle; a separate editor-reviewed authoring bundle is required for draft-only import. Extracted textbook text is never automatically published to students. See:

- Content sourcing, extraction and draft-only import: docs/product/curriculum-content-pipeline.md
- Actual question inventory vs planned targets: docs/product/curriculum-question-inventory.md
- Complete chapter × question-type dataset: docs/product/curriculum-question-targets.csv

The current minimum question-bank plan is 2,166 original questions across 100? no, across the canonical 102 chapter rows. This is a planning target, not content already prepared or published. No question rows are generated to make the numbers look complete.

## Local preview student

Local Docker development seeds exactly one student when APP_DEMO_SEED=true:

- Name: आर्यन
- Class: 7
- Board: Bihar Board
- Environment: LOCAL_PREVIEW
- Progress: every published lesson completed
- Practice: every published question has a correct preview attempt

This is a controlled QA fixture, not production customer data. The production compose file explicitly sets APP_DEMO_SEED=false.

## One-command local run

Linux / macOS / Git Bash / WSL:

```bash
bash scripts/quantaedge-local.sh up
```

Windows PowerShell:

```powershell
.\scripts\quantaedge-local.ps1 up
```

Useful actions:

```bash
bash scripts/quantaedge-local.sh rebuild
bash scripts/quantaedge-local.sh down
bash scripts/quantaedge-local.sh reset
bash scripts/quantaedge-local.sh logs
```

Use reset only when you intentionally want to remove the local PostgreSQL/Redis volumes and rebuild the preview database from migrations.

## Local runtime architecture

```text
Browser
  ↓
Next.js web/admin
  ↓  /api/* rewrite
Spring Boot API
  ↓
PostgreSQL + Redis
```

The browser does not need a hard-coded API host. Next.js proxies /api/* to the internal API service, which keeps local Docker and future production domains aligned.

## Production deployment shape

GitHub Actions validates the API, runs content-import safeguards, and builds/publishes immutable SHA-tagged images to GHCR on main. A separate workflow is manual-dispatch only; no deployment was performed as part of the current content audit.

The production compose configuration disables the local preview seed. The AI tutor is disabled until it is intentionally configured with real credentials.

## Market-readiness boundary

The app is not yet launch-ready. Remaining blockers include verified source/page mappings for all chapters, authoring and editorial review of the planned question bank, broader content coverage, production OTP/SMS and guardian verification, legal/privacy contact configuration, operational monitoring, version history and a dedicated structured block/question editor. Do not replace these with fake/demo values.

## Official reference sources

- SCERT Bihar textbook catalogue: https://scert.bihar.gov.in/textbooks
- SCERT Bihar e-resources: https://scert.bihar.gov.in/eresources
- Mathematics teacher handbook, Classes 6–8: https://scert.bihar.gov.in/eresources/mathematics-handbook-for-teachers-1753723347
- Science teacher handbook, Classes 6–8: https://scert.bihar.gov.in/eresources/science-handbook-for-teachers-class-6-8-1753723306

## CI

GitHub Actions runs Spring Boot API verification and the reviewed-content import safety tests when relevant paths change. It builds the affected Next.js and API images. Main publishes immutable SHA-tagged images to GHCR; the deploy workflow is separate and manual.
