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

The current minimum question-bank plan is 2,166 original questions across the canonical 102 chapter rows. This is a planning target, not content already prepared or published. No question rows are generated to make the numbers look complete.

## AI-assisted question generation

The extraction-to-question-generation link is implemented as an explicit, review-gated workflow:

1. `scripts/scert_extract_review.py` creates a page-addressable `DRAFT_EXTRACTION_ONLY` review bundle.
2. `scripts/generate_questions_from_extraction.py` reads that bundle and, only when both `--generate` and `--confirm-external-processing` are passed, uses a configured OpenAI-compatible API to generate original question/lesson candidates. Without those flags it prints an offline plan.
3. Candidates are written as `AI_GENERATED_DRAFT` JSON, never imported or published automatically.
4. `scripts/review_generated_question_bundle.py` requires a named editor to complete five explicit review confirmations.
5. `scripts/import_reviewed_content.py` imports only the reviewed bundle as draft content. Use `--attach-to-existing-chapter` to add new draft lessons beneath the matching existing class/subject chapter instead of creating duplicate chapter rows.

The question-generation model is configured separately using `OPENAI_API_KEY`, `OPENAI_API_BASE_URL` and `QUANTAEDGE_QUESTION_MODEL`. Generation sends extracted page text to that provider, so review its privacy/retention terms before enabling external processing. The generator validates question structure, answer keys, allowed question types, page citations and duplicates, but AI output still requires independent subject-matter review. No question generation was executed as part of this repository change; the existing four-question baseline remains the actual content count until candidates are generated, reviewed, imported and approved.


## AI-assisted question generation

The extraction-to-question-generation link is implemented as an explicit, review-gated workflow:

1. **scripts/scert_extract_review.py** creates a page-addressable extraction review bundle.
2. **scripts/generate_questions_from_extraction.py** uses a configured OpenAI-compatible API to generate original lesson/question candidates only when both the explicit generation and external-processing confirmation flags are supplied. Without them, it prints an offline plan.
3. Candidates remain AI_GENERATED_DRAFT JSON and are never imported or published automatically.
4. **scripts/review_generated_question_bundle.py** requires a named editor to complete five explicit review confirmations.
5. **scripts/import_reviewed_content.py** imports reviewed bundles as drafts. Use --attach-to-existing-chapter to add lessons beneath a matching class/subject chapter rather than creating a duplicate chapter.

Configure the generation model separately with OPENAI_API_KEY, OPENAI_API_BASE_URL and QUANTAEDGE_QUESTION_MODEL. Generation sends extracted page text to the configured provider, so review its privacy/retention terms before enabling external processing. The generator validates structure, answer-key consistency, allowed question types, page citations and duplicates, but generated answers still require independent subject-matter review. No generation was executed as part of this code change; the existing four-question baseline remains the actual content count until candidates are generated, reviewed, imported and approved.

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
