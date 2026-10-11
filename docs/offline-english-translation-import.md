# Offline English translation drafts

This workflow imports the offline English draft map for the Class 6–8 Bihar Board Maths and Science practice catalog. It requires no OpenAI key and does not generate new questions. It creates separate en translation records against the current question and curriculum-chapter IDs; it does not rewrite Hindi source text, options, answers, or publication status.

The pack contains 5,595 translation entries matched to 5,595 queue IDs. The source catalog contains 76 chapter banks and 3,572 questions. A local source-catalog preflight successfully built 3,572 question payloads and 76 chapter payloads with no numeric-signature mismatches. These are structural checks, not proofread-by-a-subject-editor checks. All imported translations stay in DRAFT.

## Prerequisites

- Switch to main and pull the latest commit.
- Postgres, API, and Admin services are running.
- The Hindi source questions have already been imported to the Admin database. If not, first validate and apply scripts/import_quantaedge_practice_banks.py.
- The user has CONTENT_EDIT or CONTENT_REVIEW permission.
- The downloaded pack's translation-queue.jsonl and english-translation-map-complete.jsonl are in the repository root.

## Import

    git switch main
    git pull --ff-only origin main
    docker compose up -d --build postgres api admin
    curl.exe -fsS http://localhost:8080/actuator/health

    # Read-only source/database preflight; no API writes
    & "D:\Tools\Python312\python.exe" .\scripts\import_quantaedge_offline_translations.py --limit 10

    # Small apply pilot; imports up to 10 chapters and 10 questions, DRAFT only
    & "D:\Tools\Python312\python.exe" .\scripts\import_quantaedge_offline_translations.py --apply --limit 10

    # After reviewing the English drafts in http://localhost:3001/translations:
    & "D:\Tools\Python312\python.exe" .\scripts\import_quantaedge_offline_translations.py --apply --delay 0.03

On --apply, the script reads QE_SESSION from the environment or asks for it with hidden input. It talks only to the configured local API by default. Each execution writes a JSON report under reports/offline-translation-imports. It skips translations already present for the current source hash, so successful rows can be resumed after an interrupted run.

## Review and later AI-generated questions

Filter the Admin translation screen to English and Draft, check question wording/options and chapter labels, then follow the existing review/approve/publish process. This import does not publish automatically.

When an OpenAI key is available later, configure it only in the application's approved server-side environment. The future AI question-generation/insertion flow is separate from this offline translation import and must still produce reviewable drafts.
