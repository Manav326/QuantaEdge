# Resumable vision analysis for textbook visual inventories

The analyzer uses Ollama's native `/api/chat` endpoint and supports both a local Ollama service and a runner-managed Ollama service. The model tag is explicit. The GitHub Actions workflow provisions the configured model on the runner; it does not require Ollama to be installed on the user's computer. No hosted API fallback is used.

## Run a small evaluation

First build the visual inventory as described in `visual-inventory-execution.md`. Check which vision-capable model is already installed with:

```powershell
ollama list
```

Choose a vision model tag that is actually present in that output, then run a five-asset evaluation:

```powershell
python scripts/analyze_visual_inventory_with_ollama.py `
  --inventory "D:\QuantaEdge\visual-inventory\class-6-curiosity\visual-inventory.v1.json" `
  --model "YOUR_INSTALLED_VISION_MODEL_TAG" `
  --max-assets 100 --include-page-previews
```

Replace the model placeholder with the exact installed tag. The tool uses local Ollama's `/api/chat` endpoint and sends the image plus available page text. The endpoint is restricted to loopback HTTP for safety. On GitHub Actions, start Ollama on the runner and use `http://127.0.0.1:11434`. It creates `visual-analysis.v1.json` beside the inventory and does not change the source inventory.

Use `--include-page-previews` to include full-page images; otherwise the tool analyzes extracted embedded images only. If the PDF page contains vector art and no embedded image, a page preview can be analyzed by opting in. Start with five assets, inspect whether descriptions and labels are faithful, then decide whether to increase the sample.

## Output safety

Each result is a draft with source book, PDF hash, page number, asset hash, model tag, limitations, and human-review flag. Errors are recorded per asset so one unsupported or corrupt image does not discard other results. Output is never publishable automatically. The model must not guess illegible text; OCR and human review remain necessary.

## Tests

```powershell
python -m unittest discover -s tests -p "test_analyze_visual_inventory_with_ollama.py" -v
```


## GitHub-hosted execution (no local Ollama required)

Workflow: [textbook-visual-analysis.yml](../../.github/workflows/textbook-visual-analysis.yml). Run it from **Actions → Resumable textbook visual extraction and analysis → Run workflow**. The workflow automatically derives the existing class-wise image name (`ghcr.io/manav326/quantaedge-textbooks-class-<class>-<language>:latest`), pulls the whole-book PDF by book ID, and locates the matching committed source manifest/page JSONL by book ID and SHA-256. The default pilot is Class 6 English Science, `ncert-c6-english-fecu1`; use the stable slug `class6-curiosity-en` across reruns. No local PDF or local Ollama installation is needed.

The workflow downloads the source from GHCR, verifies it against the extraction manifest/index SHA-256, restores prior per-book state from `automation/textbook-pipeline-state`, and runs bounded Ollama batches. Completed analysis and bundle metadata are checkpointed to that state branch and the GHCR checkpoint/versioned package; application `main` is not modified. The specialist workflow shares the core visual workflow's concurrency group to prevent duplicate inference.

The output index is under `data/textbook-visuals/<book_slug>/bundle-index.v1.json` on `automation/textbook-pipeline-state`. Generated binaries and previews are retained in the GHCR bundle; the isolated state branch carries the resumable metadata and selected review artefacts. A normal application `git pull` does not bring pipeline state into `main`; restore it explicitly with `scripts/persist_textbook_pipeline_state.py restore`. GHCR remains the versioned registry copy with immutable run tags, a stable latest tag and a resumable checkpoint tag.

The runner caches Ollama model files when GitHub's cache service retains them. Results are atomically written locally per asset and durably committed to the isolated state branch at the configured savepoint interval. Keep the same book slug and model to resume; use `restart_analysis=true` only when deliberately discarding that model's checkpoint. In the core pipeline, transient inference failures are retried on later runs up to three attempts per asset; terminal failures stop for review.


## Restore a complete bundle after Git pull

After the first successful workflow run, the small index/checkpoint is committed to the isolated state branch. To restore all binaries and previews from the exact versioned GHCR package:

```powershell
oras login ghcr.io
powershell -ExecutionPolicy Bypass -File .\\scripts\\pull_textbook_visual_bundle.ps1 -BookSlug class6-curiosity-en
```

Replace the slug with the exact one used for the workflow run. The script reads the committed index, pulls the immutable package tag, verifies the tarball SHA-256, checks required sidecars, and restores the bundle under visual-bundles/<book_slug>. It refuses an unindexed or checksum-mismatched package. The restore script verifies and restores the exact immutable GHCR bundle into a separate local folder. Pipeline-generated state remains separate from application `main`.
