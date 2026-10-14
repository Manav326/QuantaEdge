# Run the QuantaEdge AI tutor locally without an API key

The default `ai` action reuses a model already installed in Ollama on the host. It does **not** download a second model/runtime. The existing authenticated QuantaEdge tutor API still handles student authorization, rate limits, and conversation persistence.

## Default: reuse your existing local model

Start Ollama on the host and confirm `ollama list` shows at least one installed model. From the repository root, run:

```powershell
.\scripts\quantaedge-local.ps1 ai
```

The script reads `http://127.0.0.1:11434/api/tags`, prefers an installed model whose name starts with `llama`, and otherwise chooses the first installed model. To select another installed model, set `$env:QUANTAEDGE_OLLAMA_MODEL` to its exact name before running the script.

The API container calls `http://host.docker.internal:11434`. Docker Desktop provides this host name so containers can reach services on the host. Ollama commonly binds to `127.0.0.1:11434` by default; depending on the machine's networking setup, a container may not be able to reach a loopback-only listener. If tutor requests fail with connection refused, adjust Ollama's bind address only after considering firewall restrictions. Ollama's API has no built-in authentication: do not expose port 11434 to your LAN or the public internet. See the [Ollama server configuration FAQ](https://github.com/ollama/ollama/blob/main/docs/faq.mdx) and [Docker Desktop host networking guide](https://docs.docker.com/desktop/features/networking/networking-how-tos/).

## Optional: run a separate Docker Ollama

Use this only when you intentionally want a separate Ollama runtime and the small Qwen model:

```powershell
.\scripts\quantaedge-local.ps1 ai -DockerOllama
```

Bash:

```bash
bash scripts/quantaedge-local.sh ai --docker-ollama
```

This option downloads the Ollama runtime if missing and pulls `qwen2.5:0.5b` into the persistent `quantaedge_ollama` volume. Failed model pulls are retried up to five times. It is separate from any model installed on the host.

## Normal mode and stopping

The ordinary `up` action leaves the tutor disabled unless an AI configuration is explicitly set. The student site and other features can still run; tutor requests are unavailable in normal mode.

Open `http://localhost:3000/student`, sign in to the local preview student, open a **published** lesson, and select the tutor dock after `ai` starts successfully. Only published lessons are eligible; a lesson with no published teaching content cannot open the tutor.

Use `.\scripts\quantaedge-local.ps1 down` to stop the application stack. Do not use `reset` or `docker compose down -v` unless you intentionally want to delete local database/cache volumes. The fallback model's named volume is persistent.

## Limitations

Local model answers can be incorrect and are not verified SCERT/NCERT content; they require teacher review. The tutor uses lesson metadata, sanitized lesson blocks, selected question context, limited mastery context, and recent tutor conversation assembled by the existing tutor service. For production, use a suitably provisioned model service or an approved hosted provider; never commit API keys to Git.
