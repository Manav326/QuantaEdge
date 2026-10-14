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
