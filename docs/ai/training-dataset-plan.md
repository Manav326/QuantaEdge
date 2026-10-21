# QuantaEdge AI tutor training dataset

## Canonical GitHub locations

- Generated draft records: data/ai-training/generated/drafts.jsonl
- Versioned dataset exports: data/ai-training/datasets/
- Dataset generation/build/validation: scripts/ai_training_dataset.py
- Fine-tuning notebook: notebooks/quantaedge-finetune-colab.ipynb
- Source PDFs and extraction bundles: local private-review/ and source-pdfs/ only; never commit these files.

## Target

25,000 unique examples for Bihar Board Classes 6, 7 and 8; Mathematics and Science; Hindi and English. Initial allocation: 4,000 concept explanations, 4,000 worked examples, 5,000 questions with answers/explanations, 4,000 tutor conversations, 2,500 misconception corrections, 2,500 practice-generation examples, 1,500 science activities and 1,500 bilingual terminology examples. Rebalance to actual verified source coverage; do not invent coverage to fill a quota.

## Provenance and rights

Each generated record carries class, subject, language, chapter, source URL, exact edition, source PDF SHA-256, source page numbers, generation model, review status and rights status. Examples must be original explanations grounded in the cited pages, not copies of textbook passages/questions. Check permissions for model training and public redistribution before committing a dataset. Do not store raw textbook text, scans, API keys, student data or private extraction bundles in Git. The repo is public, so committed generated data is public.

## Workflow

1. Download the exact permitted SCERT/NCERT edition and verify the edition and rights.
2. Extract with scripts/scert_extract_review.py into local private-review. Map page ranges and visually review OCR.
3. Set OPENAI_API_KEY, OPENAI_API_BASE_URL (optional, defaults to https://api.openai.com) and QUANTAEDGE_TRAINING_DATA_MODEL in a local environment. Never put credentials in a file.
4. Run a dry run first:
   python scripts/ai_training_dataset.py generate --input-dir private-review --target 200
5. After checking provider privacy and rights, explicitly allow external processing:
   python scripts/ai_training_dataset.py generate --input-dir private-review --target 25000 --batch-size 5 --max-api-calls 5000 --generate --confirm-external-processing --commit-and-push
   This resumes from drafts.jsonl. The generation script outputs only original messages and provenance metadata, not source excerpts. With --commit-and-push, it commits only this JSONL file and pushes to origin/main after generation; it refuses to push if the local branch is not main or is not exactly aligned with origin/main. Review generated content before training.
6. Review and approve examples with curriculum editors. Change review_status only after actual review; never bulk-mark generated records approved without review.
7. Export approved data:
   python scripts/ai_training_dataset.py build --input data/ai-training/generated/drafts.jsonl
   The default refuses machine-validated drafts. For a controlled, explicitly experimental pilot only, use --include-drafts; its manifest is marked DRAFT_EXPERIMENT.
8. Validate:
   python scripts/ai_training_dataset.py validate --dataset-dir data/ai-training/datasets --require-approved
9. Commit only rights-cleared original generated records, manifests and evaluation artifacts to GitHub. Do not commit private source files. Keep datasets versioned under data/ai-training/datasets/ and push to main using the normal repository workflow.

## Splits and deduplication

Exact duplicate assistant conversations and exact normalized prompts are removed. Near-duplicate prompts are flagged by SimHash candidate buckets plus similarity comparison within class/subject/language. Train/validation/test assignment is deterministic by class + subject + source + chapter group, so related examples from a source chapter cannot leak across splits. The approximate split ratios are 80/10/10 and may vary because whole groups remain together. Review the manifest for class/subject/language coverage.

## Quality gate

Automated checks verify schema, class/subject/language, source URL, edition, PDF hash, cited pages, duplicate IDs and split overlap. These checks do not prove factual correctness. Math calculations and science explanations need independent verification; Hindi and English need language review. Only genuinely approved examples enter the default training export.

## Model training

Use notebooks/quantaedge-finetune-colab.ipynb for a small Qwen3-0.6B LoRA pilot. Keep train/validation/test separate, save checkpoints to persistent Drive, record base-model revision, dataset manifest hash and hyperparameters. Do not deploy a fine-tuned adapter until it beats the baseline on held-out bilingual teaching and factual-accuracy evaluations.
