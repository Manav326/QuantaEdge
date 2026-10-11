# QuantaEdge bilingual content and curriculum architecture

## Scope

Migration V51 adds the foundation for contextual Hindi/English versions of existing student-facing content and an academic-program layer for future stream-based classes. It is additive: it does not delete curriculum, lesson, question, progress, assessment, PDF, or media rows.

## Language model

- Stable curriculum, lesson, block, question, option and assessment identities remain language-independent.
- Translations are stored against the entity they localize and target the explicit `hi` or `en` locale.
- Every translation stores the MD5 source revision fingerprint, a version number, generation method/model, review metadata and publication metadata. A translation for an older source revision is kept as history and must not be served for changed source text.
- The publication workflow is `DRAFT → IN_REVIEW → APPROVED → PUBLISHED`. The database prevents a row from being marked approved/published without reviewer metadata, and prevents publication without publisher metadata.
- The script generates draft text only, using curriculum-aware pedagogical localization rather than word-for-word replacement. Prompts include class, subject, chapter, lesson and entity-type context, Hindi Devanagari and age-appropriate Indian textbook English rules, and a context-sensitive subject glossary. It never changes the source, answer payload, answer keys, assessment scores, progress, or publication status.

Translations cover the structured curriculum JSON and text: class/program/subject/chapter names and descriptions, objectives, lesson titles/summaries, lesson-block JSON, question prompts/options/explanations/topic labels, assessment names/descriptions, assessment question snapshots, and live/recorded class titles and descriptions. The existing JSON content is the source of truth for each translation; the translator receives its JSON structure and curriculum context. PDFs and textbook assets are deliberately out of scope for this translation run. The script deliberately excludes private answer payloads and answer keys from question and assessment-question translations.

## Academic program model

- `curriculum_program` adds a class-level program/stream scope and curriculum-version label.
- `curriculum_program_subject` maps subjects to a program with composite class constraints, so a subject cannot accidentally be attached to a program belonging to another class.
- `student_program_enrollment` is the future program/stream enrollment layer. Existing students are backfilled into an active or paused `GENERAL` program. Existing `student_track_enrollment` records remain intact and continue to drive the current application.
- A stream is optional; primary and middle-school classes do not need a stream.
- This release establishes the database foundation. Admin program selection and program-aware student access must be integrated before enabling new streams for real students.

## Media language

Live sessions and recorded classes have an optional primary language. `recorded_class_language_asset` can represent language-specific subtitle files, transcripts, dubbed audio and audio descriptions. The existing recorded-video and live-session data is retained.

## Local workflow

After the V51 migration and the student delivery/CMS changes have been deployed, use Python 3.12 and the repository's Docker Compose Postgres service.

1. Run a read-only inventory:
   ```powershell
   & "D:\Tools\Python312\python.exe" .\scripts\translate_quantaedge_content.py --audit-only
   ```

2. Test a representative pilot across Hindi and English and across the main entity types. Set `OPENAI_API_KEY` in your local process environment and set `OPENAI_TRANSLATION_MODEL` to a model supported by your configured API provider. `--max-items` is balanced across available languages and content types rather than taking only the first items:
   ```powershell
   & "D:\Tools\Python312\python.exe" .\scripts\translate_quantaedge_content.py --classes 6 --subjects maths --languages hi,en --max-items 40 --model "$env:OPENAI_TRANSLATION_MODEL"
   ```
   This writes a local JSONL draft bundle and does not write to the database.

3. After checking the pilot's JSONL output and counts, add `--apply` to insert generated records with `translation_status='DRAFT'`. This is the staff-review queue; nothing becomes student-visible until staff review and publish it:
   ```powershell
   & "D:\Tools\Python312\python.exe" .\scripts\translate_quantaedge_content.py --classes 6 --subjects maths --languages hi,en --max-items 40 --apply --model "$env:OPENAI_TRANSLATION_MODEL"
   ```

4. Once the pilot is accepted, run the same resumable command for all active Class 6–8 Maths and Science data in both locales, omitting `--max-items` to process all eligible structured records. Keep PDF translation separate for a later project. Use `--include-archived` only when translating legacy/archived learning paths is explicitly needed.

The script is resumable: current-source translations already in draft/review/approved/published states are skipped; failures are logged and can be retried. All outputs are written under `QuantaEdge_Bilingual_Draft_Bundle`, which is ignored by Git. The script does not publish translations. Do not treat draft generation as bilingual readiness until the CMS review, API delivery and coverage checks are complete.

## Acceptance gates

- Every required active lesson, block, question, option, assessment and media text has a current approved/published Hindi and English version.
- Source-hash mismatch never serves a stale translation.
- Correct-answer logic, question ordering, numerical values, formulas, units and assessment marking remain unchanged.
- UI/API results indicate when the selected-language version is missing rather than claiming full bilingual coverage.
- Translation review is visible and actionable in Content Studio.
- The class/subject curriculum and learner progress remain stable for existing users.
