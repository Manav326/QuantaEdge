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

Workflow: [textbook-visual-analysis.yml](../../.github/workflows/textbook-visual-analysis.yml). Run it from **Actions → Resumable textbook visual extraction and analysis → Run workflow**. Supply the exact existing GHCR source-book image and the absolute paths of the original PDF and its matching manifest inside that image. The manifest's sibling page JSONL is copied automatically. Use a stable book_slug for the book across reruns.

The workflow downloads the source from GHCR, verifies it through the existing inventory script, runs Ollama vision analysis on the GitHub runner, executes focused regression tests, publishes a full asset/preview bundle to GHCR under a run-specific tag and a stable per-book tag, then commits only the small inventory, analysis checkpoint and bundle index to main.

The output index is under data/textbook-visuals/<book_slug>/bundle-index.v1.json. Full image binaries/page previews are kept in GHCR to avoid oversized Git history; the repository contains the resumable JSON checkpoint and exact package/tag/checksum pointer. Pull main, then authenticate to GHCR and pull the package tag from the index to restore the complete visual bundle.

The runner caches Ollama model files when GitHub's cache service retains them. Analysis checkpoints are persisted to main after each run and each completed asset is atomically checkpointed during processing. Keep the same book slug and model to resume; use restart_analysis=true only when deliberately discarding the checkpoint. If a run is interrupted before it can commit the latest checkpoint, its last committed checkpoint is restored and only the in-flight asset may need repeating.


## Restore a complete bundle after Git pull

After the first successful workflow run, the small index/checkpoint is committed to main. To restore all binaries and previews from the exact versioned GHCR package:

```powershell
oras login ghcr.io
powershell -ExecutionPolicy Bypass -File .\\scripts\\pull_textbook_visual_bundle.ps1 -BookSlug class6-curiosity-en
```

Replace the slug with the exact one used for the workflow run. The script reads the committed index, pulls the immutable package tag, verifies the tarball SHA-256, checks required sidecars, and restores the bundle under visual-bundles/<book_slug>. It refuses an unindexed or checksum-mismatched package. Since full-resolution media is kept in GHCR rather than Git history, a repository pull plus GHCR authentication and this restore command is the supported reproducible setup.
