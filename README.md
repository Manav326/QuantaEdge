# QuantaEdge

> **Smarter Decisions. Greater Growth.**

AI-powered learning for Hindi-medium students, starting with Bihar Board Classes 6–8.

## Brand system

The visual identity, approved tagline, palette, logo assets and placement map are documented in [docs/branding.md](docs/branding.md).

AI-powered learning platform for Hindi-medium students, starting with Bihar Board Classes 6–8.

## Product surfaces

- Web landing: http://localhost:3000
- Student home: http://localhost:3000/student
- Student learning tracks: http://localhost:3000/student/learn
- Interactive practice: http://localhost:3000/student/practice
- Student progress: http://localhost:3000/student/progress
- Parent view: http://localhost:3000/parent
- Parent child management: http://localhost:3000/parent/children
- Login entry: http://localhost:3000/login
- Admin workspace: http://localhost:3001
- People Operations (HRMS): http://localhost:3002
- Curriculum API: http://localhost:8080/api/v1/curriculum
- API health: http://localhost:8080/actuator/health

## Parent, student and subject access

Parent registration collects the parent name and verifies the mobile OTP before creating the account. Local demo mode returns OTP `123456`. Each parent can manage a configurable number of active child profiles (default `APP_AUTH_MAX_CHILDREN_PER_PARENT=3`); increase this environment variable to expand the limit. Each child may subscribe to Maths, Science, or both, and the API enforces these subscriptions when listing, opening, starting, or practising lessons. Administrators can suspend/reactivate parent accounts and student profiles and manage subject tracks in the admin console under **Parents & families**. Suspension revokes current sessions; archiving a parent-child link retains learning history.

## Saved curriculum pipeline implementation plan

The execution checklist for the complete-book → chapter → original learning material → bilingual assessment workflow is saved in [docs/product/curriculum-pipeline-implementation-plan.md](docs/product/curriculum-pipeline-implementation-plan.md).

Run offline target validation with `python scripts/audit_curriculum_pipeline.py --skip-registry --strict`. After pulling the real class/language GHCR images, run the strict asset audit and retain its JSON report. The audit is read-only and does not publish content or change the database schema.

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

## Local preview student

Local Docker development seeds exactly one student when `APP_DEMO_SEED=true`:

- Name: आर्यन
- Class: 7
- Board: Bihar Board
- Environment: LOCAL_PREVIEW
- Progress: every published lesson completed
- Practice: every published question has a correct preview attempt

This is a controlled QA fixture, not production customer data. The production compose file explicitly sets `APP_DEMO_SEED=false`.

## One-command local run

The default is to pull the published application images from GHCR and run them locally. Docker Compose still uses the local-development configuration for ports, demo seed, environment, and persistent database/cache volumes.

Linux / macOS / Git Bash / WSL:

```bash
# Pull latest published app images, then start locally
bash scripts/quantaedge-local.sh up

# Build app images from the current checkout instead
bash scripts/quantaedge-local.sh --local up
# Equivalent flag position
bash scripts/quantaedge-local.sh up --local
```

Windows PowerShell:

```powershell
# Pull latest published app images, then start locally
.\scripts\quantaedge-local.ps1 up

# Build app images from the current checkout instead
.\scripts\quantaedge-local.ps1 up -BuildLocal
```

Choose a specific published image tag when needed (for example, a full SHA tag published by GitHub Actions):

```bash
bash scripts/quantaedge-local.sh up --tag sha-<full-commit-sha>
```

```powershell
.\scripts\quantaedge-local.ps1 up -ImageTag sha-<full-commit-sha>
```

Useful actions:

```bash
bash scripts/quantaedge-local.sh rebuild          # GHCR mode: pull and restart
bash scripts/quantaedge-local.sh rebuild --local  # local cached rebuild
bash scripts/quantaedge-local.sh clean-build --local
bash scripts/quantaedge-local.sh ai               # GHCR app images + local Ollama tutor
bash scripts/quantaedge-local.sh ai --local       # locally built app images + local Ollama tutor
bash scripts/quantaedge-local.sh down
bash scripts/quantaedge-local.sh logs
```

PowerShell equivalents accept `-BuildLocal`, e.g. `.\scripts\quantaedge-local.ps1 ai -BuildLocal`. Use `reset` only when you intentionally want to delete local PostgreSQL/Redis volumes and recreate the preview database from migrations. `clean-build` requires local mode because it deliberately disables Docker build-cache reuse.

GHCR images are `ghcr.io/manav326/quantaedge-api`, `quantaedge-web`, `quantaedge-admin` and `quantaedge-people`. If packages are private, sign in to GHCR with Docker before running the default mode.

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

## Run the AI tutor locally without an API key

Use the local script so the selected GHCR/local build mode is preserved:

```powershell
.\scripts\quantaedge-local.ps1 ai
# Or build application images locally:
.\scripts\quantaedge-local.ps1 ai -BuildLocal
```

On first start, Docker downloads the Ollama runtime and the small `qwen2.5:0.5b` model. Then open `http://localhost:3000/student`, sign in to the local preview student, and open a published lesson to use the tutor. The model is stored in a persistent Docker volume. First startup needs internet and additional disk/RAM; this small general-purpose model is an MVP, not a teacher-validated or fine-tuned model. It may make mistakes and must not be presented as official SCERT/NCERT content. See [docs/ai/local-ai-tutor.md](docs/ai/local-ai-tutor.md) for limitations and shutdown instructions.

## Production deployment shape

GitHub Actions validates the API, runs content-import safeguards, and builds/publishes immutable SHA-tagged images to GHCR on main. A separate workflow is manual-dispatch only; no deployment was performed as part of the current content audit.

The production compose configuration disables the local preview seed. The AI tutor is disabled until it is intentionally configured with real credentials.

## Market-readiness boundary

The platform and content pipeline are still being validated. Passing build/tests is not equivalent to teacher-reviewed curriculum completeness, and AI-generated learning content must remain review-gated before publication.
