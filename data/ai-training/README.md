# QuantaEdge AI training data

This directory is the canonical GitHub location for generated QuantaEdge tutor-training data.

- generated/drafts.jsonl: generated, source-attributed drafts; not approved for production.
- datasets/: reviewed exports, split files and manifest.
- The pipeline may be run locally against private extraction bundles. Raw textbook PDFs, extracted source text, credentials and private review bundles must not be committed.
- Before committing any JSONL, verify rights to use and publicly redistribute the generated records. This repository is public.
- Never represent generated drafts as human-verified. See docs/ai/training-dataset-plan.md.

The initial manifest is intentionally empty until real source bundles are processed. Do not seed fake examples to make the target appear complete.

## API-key-free starter corpus

The repository now includes `scripts/ai_training_offline_seed.py` and a GitHub Actions workflow that generates `datasets/dataset_master.jsonl`, `train.jsonl`, `validation.jsonl`, `test.jsonl`, and `manifest.json` on `main` without an external model API key. The workflow is intended to create 25,000 unique draft records across Classes 6–8, Maths and Science, Hindi and English, then commit the output back to this directory.

**Important quality/provenance boundary:** this first corpus is generated from original deterministic teaching templates. Official SCERT/NCERT portal links in its metadata indicate curriculum-alignment references only; they do not claim a specific book edition, PDF, page number, or that an item is an official textbook question. It is a starter draft, not 25,000 source-grounded LLM-generated or teacher-approved examples. Review the manifest limitations and have teachers verify correctness, chapter alignment, Hindi quality, variety, and rights before using it for production fine-tuning.

The source-grounded generator remains available for private extracted bundles and a configured model provider. Never commit API keys, private extraction bundles, raw PDFs, or extracted textbook text.
