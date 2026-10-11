# Offline English translation drafts

This workflow imports the offline English draft map for the Class 6–8 Bihar Board Maths and Science practice catalog. It requires no OpenAI key and does not generate new questions. It creates separate en translation records against the current question and curriculum-chapter IDs; it does not rewrite Hindi source text, options, answers, or publication status.

The translation pack contains 5,595 matching queue/map entries. The source catalog contains 76 chapter banks and 3,572 questions. Local source-catalog preflight resolved 3,572/3,572 question payloads and 76/76 chapter title/description payloads with no numeric-signature mismatches. This is structural validation, not subject-editor proofreading. All imported translations stay in DRAFT.

## Prepare the files

From D:\QuantaEdge\QuantaEdge:

    git switch main
    git pull --ff-only origin main
    Expand-Archive .\QuantaEdge_English_Offline_Translation_Import.zip -DestinationPath .\_offline-english-import -Force
    Copy-Item .\_offline-english-import\translation-queue.jsonl .\translation-queue.jsonl -Force
    Copy-Item .\_offline-english-import\english-translation-map-complete.jsonl .\english-translation-map-complete.jsonl -Force

Make sure QuantaEdge_Classes6-8_Hindi_Practice_Banks_ALL.json is also in the repository root. The script will validate and idempotently import missing Hindi source questions first; if they are already in the database, it skips duplicates.

## One-command import

Start with a small English draft pilot:

    .\scripts\import_quantaedge_offline_translation_bundle.ps1 -PilotLimit 10

The script starts/rebuilds Postgres, API, and Admin; checks /actuator/health; validates the Hindi catalog; applies any missing Hindi question drafts; and creates up to 10 ready English chapter/question translation drafts. PilotLimit limits English translations, not the idempotent source-catalog import.

Review the pilot in http://localhost:3001/translations with language English and status Draft. If it looks correct, run the full import:

    .\scripts\import_quantaedge_offline_translation_bundle.ps1

For a database where the source Hindi questions are already present and you only want to add English drafts, use:

    .\scripts\import_quantaedge_offline_translation_bundle.ps1 -SkipHindiSourceImport -PilotLimit 10

The script uses the staff access cookie `QE_STAFF_SESSION` and the `X-QE-Workspace: STAFF` header. It asks for the cookie with hidden input if `QUANTAEDGE_ADMIN_SESSION` or `QE_SESSION` is not already set, then validates it against `/api/v1/auth/me` after Docker startup. Staff access cookies expire after 15 minutes; if a saved value has expired during startup, the script prompts for a fresh value before any content writes. The Admin account needs CONTENT_EDIT or CONTENT_REVIEW permission. The cookie is never included in command arguments or the report. Existing English drafts for the same source hash are skipped, so the full run can resume after a partial run.

Reports are written under reports\offline-translation-imports. Nothing is approved or published automatically; staff must review the language, question intent, option alignment, and explanations in Content Studio.

## OpenAI for future generated questions

This offline pack is not OpenAI-generated and does not need a key. It supplies an offline-assisted first English draft that has not been professionally proofread. For future AI-generated question creation, configure the key only in the approved server-side environment and retain the draft/review/publish process.
