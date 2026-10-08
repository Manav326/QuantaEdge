# QuantaEdge

AI-powered learning platform for Hindi-medium students, starting with Bihar Board Classes 6–8.

## Product surfaces

- Web landing: http://localhost:3000
- Student home: http://localhost:3000/student
- Student lesson: http://localhost:3000/student/learn
- Interactive practice: http://localhost:3000/student/practice
- Student progress: http://localhost:3000/student/progress
- Parent view: http://localhost:3000/parent
- Login entry: http://localhost:3000/login
- Admin workspace: http://localhost:3001
- Curriculum API: http://localhost:8080/api/v1/curriculum
- API health: http://localhost:8080/actuator/health

## Curriculum/content

The MVP curriculum foundation covers **Bihar Board / SCERT Class 6, 7 and 8 Maths + Science** in the canonical textbook order. The audited path contains **103 published curriculum sections**: Class 6 Maths 15 + Science 18; Class 7 Maths 16 + Science 19 (18 official chapters plus two explicit supplementary reference sections, with one legacy duplicate excluded); Class 8 Maths 16 + Science 19.

Every canonical chapter follows the same teacher-shaped learning arc rather than a disconnected screen sequence:

1. पहले समझें — activate prior knowledge and build the core idea
2. उदाहरण के साथ करें — teacher-modeled worked example and guided practice
3. खुद करके पक्का करें — independent application and reasoning

The system generates a consistent lesson shell for every canonical chapter, while keeping curriculum order and chapter metadata in the database. QuantaEdge-authored content is not a copy of SCERT textbook text. The `/api/v1/curriculum/audit` endpoint exposes the expected chapter counts and actual teaching order for QA.

SCERT Bihar officially publishes the Class 6–8 textbooks and teacher handbooks used as the curriculum reference for this mapping. The repository keeps the curriculum reference separate from QuantaEdge-authored lesson content.

## Local preview student

Local Docker development seeds **exactly one** student when `APP_DEMO_SEED=true`:

- Name: आर्यन
- Class: 7
- Board: Bihar Board
- Environment: LOCAL_PREVIEW
- Progress: every published lesson completed
- Practice: every published question has a correct preview attempt

This is a controlled QA fixture, not production customer data. The production compose file explicitly sets `APP_DEMO_SEED=false`.

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

Use `reset` only when you intentionally want to remove the local PostgreSQL/Redis volumes and rebuild the preview database from migrations.

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

The browser does not need a hard-coded API host. Next.js proxies `/api/*` to the internal API service, which keeps local Docker and future production domains aligned.

## Production deployment shape

GitHub Actions builds immutable SHA-tagged images and publishes them to GHCR on `main`. The production compose file consumes those images.

The production compose configuration disables the local preview seed. No synthetic student is created there.

## Important market-readiness boundary

The current branch is a production-shaped MVP, but external launch dependencies still need real credentials/configuration before accepting customers: real parent/guardian verification, SMS/OTP delivery, legal/privacy contact configuration, AI provider credentials and operational monitoring. These must not be replaced with fake/demo values.

## Reference sources

- SCERT Bihar textbook catalogue: https://scert.bihar.gov.in/textbooks
- SCERT Bihar e-resources: https://scert.bihar.gov.in/eresources
- SCERT Bihar Class 6 Maths: https://scert.bihar.gov.in/eresources/गणित-भाग-6-1707973215
- SCERT Bihar Class 7 Maths: https://scert.bihar.gov.in/eresources/गणित-भाग-7-1708060463
- SCERT Bihar Class 8 Maths: https://scert.bihar.gov.in/eresources/गणित-भाग-8-1708403831
- SCERT Bihar Class 6 Science: https://scert.bihar.gov.in/eresources/विज्ञान-भाग-1-1707973674

## CI

GitHub Actions validates the Spring Boot API and both Next.js applications, then builds Docker images. Main publishes the immutable SHA-tagged images to GHCR.