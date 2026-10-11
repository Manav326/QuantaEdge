# QuantaEdge bilingual content and curriculum architecture

## Scope

Migration V51 adds the foundation for contextual Hindi/English versions of existing student-facing content and an academic-program layer for future stream-based classes. It is additive: it does not delete curriculum, lesson, question, progress, assessment, PDF, or media rows.

## Language model

- Stable curriculum, lesson, block, question, option and assessment identities remain language-independent.
- Translations are stored against the entity they localize and target the explicit `hi` or `en` locale.
- Every translation stores the MD5 source revision fingerprint, a version number, generation method/model, review metadata and publication metadata. A translation for an older source revision is kept as history and must not be served for changed source text.
- The publication workflow is `DRAFT → IN_REVIEW → APPROVED → PUBLISHED`. The database prevents a row from being marked approved/published without reviewer metadata, and prevents publication without publisher metadata.
- The script generates draft text only, using curriculum-aware pedagogical localization rather than word-for-word replacement. Prompts include class, subject, chapter, lesson and entity-type context, Hindi Devanagari and age-appropriate Indian textbook English rules, and a context-sensitive subject glossary. It never changes the source, answer payload, answer keys, assessment scores, progress, or publication status.

Translations cover the structured curriculum JSON and text: class/program/subject/chapter names and descriptions, objectives, lesson titles/summaries, lesson-block JSON, question prompts/options/explanations/topic labels, assessment names/descriptions, assessment question snapshots, and live/recorded class titles and descriptions. The translation script's direct source of truth is the structured content already stored in the local database: JSONB lesson blocks and question options plus their associated learner-facing text and curriculum metadata. It does not open standalone ZIP/JSON source packages or PDFs directly. Chapter/lesson JSON bundles must first pass the existing draft importer so the CMS creates stable chapter, lesson, block and question IDs; the translator can then add language revisions against those records. The separate `QuantaEdge_Classes6-8_Hindi_Practice_Banks_ALL.json` catalog is a file-only source until it is imported and mapped to CMS question records; it must not be silently treated as translated by a database-only run. PDFs and textbook assets are deliberately out of scope. The script deliberately excludes private answer payloads and answer keys from question and assessment-question translations.

## Academic program model

- `curriculum_program` adds a class-level program/stream scope and curriculum-version label.
- `curriculum_program_subject` maps subjects to a program with composite class constraints, so a subject cannot accidentally be attached to a program belonging to another class.
- `student_program_enrollment` is the future program/stream enrollment layer. Existing students are backfilled into an active or paused `GENERAL` program. Existing `student_track_enrollment` records remain intact and continue to drive the current application.
- A stream is optional; primary and middle-school classes do not need a stream.
- This release establishes the database foundation. Admin program selection and program-aware student access must be integrated before enabling new streams for real students.

## Media language

Live sessions and recorded classes have an optional primary language. `recorded_class_language_asset` can represent language-specific subtitle files, transcripts, dubbed audio and audio descriptions. The existing recorded-video and live-session data is retained.

## Local workflow

After the V51 migration and student delivery/CMS changes are deployed, use Python 3.12 and the local Docker Compose services.

1. **Import the standalone practice-bank JSON into existing CMS lessons first.** The importer validates answer keys and class/subject/chapter/lesson mappings, then appends only missing questions as DRAFT. It never publishes. Run a no-write file validation first:

       & "D:\Tools\Python312\python.exe" .\scripts\import_quantaedge_practice_banks.py --package ".\QuantaEdge_Classes6-8_Hindi_Practice_Banks_ALL.json"

   For database mapping preflight and safe import, sign in to local Admin, set QUANTAEDGE_ADMIN_SESSION from the QE_SESSION cookie in the current PowerShell session (never paste it into chat or put it on the command line), then run:

       & "D:\Tools\Python312\python.exe" .\scripts\import_quantaedge_practice_banks.py --package ".\QuantaEdge_Classes6-8_Hindi_Practice_Banks_ALL.json" --apply --api-url "http://localhost:8080"

   The report lists newly added questions and exact duplicates skipped. If mapping preflight fails, it writes nothing. If a later network error interrupts the run, rerunning is safe because each question has a stable qe-practice-id tag and content-aware duplicate checking.

2. **Run a read-only inventory of structured records in the local database:**

       & "D:\Tools\Python312\python.exe" .\scripts\translate_quantaedge_content.py --audit-only

3. **Generate a representative translation pilot across Hindi and English.** The script reads OPENAI_API_KEY, OPENAI_API_BASE_URL, and the model from the root .env file when present. Ensure a valid OPENAI_API_KEY and either OPENAI_TRANSLATION_MODEL or APP_AI_TUTOR_MODEL are configured. The --max-items cap is balanced across languages and content types:

       & "D:\Tools\Python312\python.exe" .\scripts\translate_quantaedge_content.py --classes 6 --subjects maths --languages hi,en --max-items 40

   This writes a local JSONL draft bundle and does not write to the database. Inspect the translated prose, Devanagari quality, numeric values, options, and answer-preservation checks.

4. **After the pilot looks correct, insert it as DRAFT translations:**

       & "D:\Tools\Python312\python.exe" .\scripts\translate_quantaedge_content.py --classes 6 --subjects maths --languages hi,en --max-items 40 --apply

   Drafts become visible in the Language review panel for staff editing/review; they are not student-visible until separately approved and published.

5. **Run the full structured-content translation.** Once staff accepts the pilot, omit --max-items to process all eligible active Class 6–8 Maths and Science records in both locales:

       & "D:\Tools\Python312\python.exe" .\scripts\translate_quantaedge_content.py --apply --batch-size 20 --max-retries 5

   This covers curriculum labels, chapters, objectives, lessons, JSON teaching blocks, questions/options/explanations, assessments and available live/recorded class metadata. It does not translate PDFs or textbook assets; those are a separate phase. Use --include-archived only when legacy/archived learning paths are explicitly required.

The translator is resumable: current-source translations already in DRAFT, IN_REVIEW, APPROVED, or PUBLISHED are skipped; failures are logged for retry. Reports and JSONL files are written under QuantaEdge_Bilingual_Draft_Bundle, which is ignored by Git. The translation process never publishes. The staff workflow remains review → approve → publish.
 
## Acceptance gates

- Every required active lesson, block, question, option, assessment and media text has a current approved/published Hindi and English version.
- Source-hash mismatch never serves a stale translation.
- Correct-answer logic, question ordering, numerical values, formulas, units and assessment marking remain unchanged.
- UI/API results indicate when the selected-language version is missing rather than claiming full bilingual coverage.
- Translation review is visible and actionable in Content Studio.
- The class/subject curriculum and learner progress remain stable for existing users.
