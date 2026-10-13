# QuantaEdge AI training data

This directory is the canonical GitHub location for generated QuantaEdge tutor-training data.

- generated/drafts.jsonl: generated, source-attributed drafts; not approved for production.
- datasets/: reviewed exports, split files and manifest.
- The pipeline may be run locally against private extraction bundles. Raw textbook PDFs, extracted source text, credentials and private review bundles must not be committed.
- Before committing any JSONL, verify rights to use and publicly redistribute the generated records. This repository is public.
- Never represent generated drafts as human-verified. See docs/ai/training-dataset-plan.md.

The initial manifest is intentionally empty until real source bundles are processed. Do not seed fake examples to make the target appear complete.
