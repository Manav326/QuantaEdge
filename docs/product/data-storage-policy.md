# Data Storage and Git Commit Policy

**Status:** Canonical repository policy  
**Applies to:** Textbook ingestion, extraction, AI Tutor training, lesson/question generation, validation, database import, and all CI workflows.

## 1. Non-negotiable rule

Store only durable, intentional outputs that the QuantaEdge application, database importer, or AI Tutor training pipeline is meant to consume in the repository's `data/` directory.

Do **not** commit intermediate, temporary, cache, scratch, debug, or reproducible processing data merely so it is available after `git pull`. A pull should deliver code, schemas/contracts, configuration templates, documentation, and the approved/usable datasets intentionally maintained in `data/`—not a runner's working files.

Every pipeline stage must declare its input, output, format, retention location, and whether that output is eligible for Git. A stage must not silently promote its intermediate output into a committed dataset.

## 2. Repository storage map

| Data category | Required location | Commit to Git? | Rule |
|---|---|---:|---|
| Durable data ready for the database importer (for example, reviewed lesson/question records with the current database contract) | `data/database-import/` (use subject/class/language/version subfolders as needed) | Yes, when intentionally approved for repository distribution | Include the exact schema/version and provenance needed to import reproducibly. Drafts must be explicitly labelled and must never be mistaken for publishable content. |
| Durable AI Tutor training/fine-tuning corpus and its required train/validation/test splits | `data/ai-tutor/` | Yes, when intentionally approved for repository distribution | Keep the required format (for example JSONL), split metadata, provenance, deduplication results, and schema/version information with the dataset. Do not mix temporary generation prompts or raw model responses into the training corpus unless they are deliberately part of the defined dataset. |
| Durable book-related data consumed by the application/importer (for example approved book catalogue records, reviewed chapter mappings, or final structured book content) | `data/books/` | Yes, when intentionally approved for repository distribution | Store structured, consumable outputs—not disposable extraction work. Keep source-book identity, edition/language, checksum, and mapping references where required by the contract. |
| Original source textbook PDFs | Existing approved source location, such as the configured GHCR package; local copies may be kept in the developer's `books/` folder | Normally no | Do not duplicate large source books into Git unless a separate explicit decision authorizes it. |
| Intermediate page images/text, OCR output, raw page JSONL, temporary chapter splits, unreviewed chapter maps, candidate generations, temporary model outputs, scratch bundles | Runner temporary workspace or ignored local workspace such as `private-review/`, `.cache/`, or a job-specific temporary directory | **No** | Re-create when needed. Do not make a later stage depend on a developer machine's untracked files. |
| CI-only intermediate artifacts needed between jobs | Explicitly named GitHub Actions artifact with a defined retention period | No | Pass artifacts between jobs through workflow artifacts or job outputs. Download/regenerate them when needed; do not commit them to make the next job work. |
| Logs, diagnostic dumps, transient reports, retry state, checkpoints, downloaded caches | Runner temporary workspace / cache / Actions artifact, according to lifetime | No | Keep only for debugging or the configured retention window. Never treat a transient report as an application dataset. |
| Code, schemas, validators, importer/exporter scripts, workflow definitions, data contracts, documentation, small non-secret examples | Normal source paths such as `scripts/`, `docs/`, and application paths | Yes | These describe how to create, validate, and consume data. Never commit credentials, session cookies, private keys, or environment files. |

The paths above establish the policy's canonical destinations for new durable data. If an existing pipeline uses another path, update that pipeline to conform rather than introducing a second source of truth. Do not move or delete existing data blindly: first classify it as final/consumable or intermediate, then migrate it deliberately.

## 3. What qualifies for `data/`

A file belongs in `data/` only when all applicable conditions are true:

1. It is an intentional, durable output—not a temporary by-product of a pipeline run.
2. Its consumer is known: database import, AI Tutor training, or the book/content application path.
3. It conforms to the **current** consumer schema/data contract. Additional useful keys may be retained, but required consumer keys and types must be present and unambiguous.
4. Its source, class/subject, language, edition/version, and generation or review status are identifiable where applicable.
5. It has passed the appropriate structural and semantic validation. AI-generated content must not be treated as approved merely because it parses.
6. Its inclusion in Git is intentional, and its size/licensing/privacy implications permit repository storage.

If a file is needed only to produce another file, it is an intermediate unless the pipeline contract explicitly identifies it as a durable input for a later independently executed stage.

## 4. Pipeline hand-off contract

Every layer must document the following fields in its pipeline documentation or manifest:

- **Input:** exact source path, registry/package reference, or upstream artifact name.
- **Input format and contract:** schema/version, encoding, and required fields.
- **Transformation:** what the stage does, including model/runtime and relevant version.
- **Output:** exact path/name, format, schema/version, and consumer.
- **Storage class:** `repository-data`, `runner-temp`, `cache`, or `workflow-artifact`.
- **Git eligibility:** yes/no, with the reason. Intermediate outputs are always no.
- **Validation:** command/checks and failure behavior.
- **Lifecycle:** when it is deleted, expired, regenerated, reviewed, or promoted to durable data.

A later layer must consume an explicitly declared output from the preceding layer. It must not infer paths, rely on a runner's leftover files, silently switch formats, or assume that an artifact was committed. Fail the pipeline at the boundary when required data, keys, types, provenance, or schema versions are missing or incompatible; fix the producing/consuming contract at its source rather than masking the mismatch with ad-hoc fallback branches.

## 5. Promotion from intermediate to durable data

Promotion is a deliberate, reviewable step:

1. Generate intermediates in temporary storage.
2. Validate structure, completeness, source mapping, duplicates, and consumer-schema compatibility.
3. Perform subject-matter/editorial review where required.
4. Export the exact durable artifact into the appropriate `data/` subdirectory.
5. Run the same validation against the exported file that the importer/training job will use.
6. Commit only the intended durable file(s), with a commit message that identifies the dataset and scope.

Promotion does not automatically mean publishing lessons to students or importing records into a production database. Repository storage, review approval, database import, and student-facing publication are separate states.

## 6. CI and Git pull behavior

- `git pull` must provide the scripts, contracts, workflows, documentation, and deliberately committed durable datasets needed for the documented workflow.
- A workflow must generate intermediate files in its runner workspace (prefer a unique temporary directory) and pass them to dependent jobs using explicit workflow artifacts/outputs when required.
- Use caches only for performance; a cache miss must not make the pipeline incorrect.
- Set retention for workflow artifacts and avoid uploading secrets or sensitive student data.
- Do not commit artifacts from CI just to bridge jobs or preserve debugging output.
- Keep `.gitignore` rules for private workspaces, caches, logs, and generated intermediates. Do not ignore the intentional, reviewed files in `data/`.
- Before committing a new generated file, verify its destination and Git status. If it is an intermediate, move it to the appropriate temporary location and add/adjust an ignore rule rather than committing it.

## 7. Existing textbook extraction precedent

The existing `data/textbook-extractions/class-6/` location has previously been used for page-addressable extraction artifacts. Under this policy, **the directory name alone does not make an artifact final**. Each existing file must be classified:

- Keep in `data/` only if a current downstream consumer intentionally reads it as a durable, versioned input/output and its schema and provenance are documented.
- Otherwise move its generation to runner/local temporary storage and stop committing it; use workflow artifacts only when a later job needs the intermediate during the same workflow.
- Do not break an existing consumer by deleting files before its input contract has been migrated and tested.

The same classification applies to manifests, chapter maps, reports, draft question bundles, and generated book splits. A reviewed chapter map may be durable only if it is an intentional, versioned input to a later pipeline stage; temporary extraction reports and raw generation candidates are not final datasets by default.

## 8. Completion criteria

A pipeline complies only when:

- every layer's inputs and outputs, formats, storage locations, and consumers are documented;
- durable importer/training/book data is stored under the appropriate `data/` path;
- intermediate data is not committed and is available only in its proper local, runner, cache, or workflow-artifact location;
- a clean checkout plus documented source downloads/artifact hand-offs is sufficient to run the workflow;
- schema and content validation fails clearly at the responsible boundary; and
- no draft is imported or published automatically without its required review and approval.

This policy is the default for new work and the target for existing workflows. Update affected workflows and their documentation to match it; documentation alone does not prove that a pipeline already complies.



## 9. Structured lesson source versus API runtime

The canonical JSON under `data/structured-lessons/` is authoring/source data, not an indication that the corresponding records exist in PostgreSQL. Draft topics and questions remain available for review and resumability, but they must not be bundled into the API runtime or made available to learners before publication gates pass.

`services/api/Dockerfile` runs `scripts/package_published_structured_lessons.py` to stage only topic artifacts whose workflow status is `PUBLISHED`, `publication_allowed` is true, and content, source-completeness, bilingual, scientific and visual review statuses are explicitly approved. The staging directory is replaced on every build to prevent stale resources from surviving a later approval change. If there are no approved artifacts, zero structured topic JSON files are packaged. This process does not insert, delete or alter database rows; database import and student publication remain separate operations.
