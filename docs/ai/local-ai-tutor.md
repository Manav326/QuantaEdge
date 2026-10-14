# QuantaEdge AI tutor: local inference and provider routing

QuantaEdge's API remains the only route from web/Android to model inference. Clients never select provider endpoints directly. The API authorizes the student and lesson, assembles localized curriculum context, applies usage limits, and persists complete turns only after a usable reply.

## Local development

The `ai` action reuses a model already installed in host Ollama:

```powershell
.\scripts\quantaedge-local.ps1 ai
```

The script detects a local model (preferring `llama*`) and configures the API to use `http://host.docker.internal:11434`. On Bash:

```bash
bash scripts/quantaedge-local.sh ai
```

To intentionally run a separate Docker Ollama with `qwen2.5:0.5b`:

```powershell
.\scripts\quantaedge-local.ps1 ai -DockerOllama
```

```bash
bash scripts/quantaedge-local.sh ai --docker-ollama
```

The Docker option uses the persistent `quantaedge_ollama` volume and retries failed model pulls up to five times. Never use `docker compose down -v` unless you intentionally want to delete local database/model volumes.

## Provider order and optional fallback

The API uses explicit settings instead of choosing an API protocol from a URL:

- `APP_AI_TUTOR_PRIMARY_BASE_URL`, `APP_AI_TUTOR_PRIMARY_API_KEY`, `APP_AI_TUTOR_PRIMARY_MODEL`, and `APP_AI_TUTOR_PRIMARY_API_MODE` configure the in-house endpoint.
- `APP_AI_TUTOR_PRIMARY_API_MODE` must be `CHAT_COMPLETIONS` for Ollama-compatible endpoints or `RESPONSES` for the OpenAI Responses API.
- `APP_AI_TUTOR_FALLBACK_ENABLED=false` is the default. If an owner explicitly opts in with `true`, and configures `OPENAI_API_KEY`, the hosted fallback may be used only if the primary provider fails or its response fails the conservative structural/language checks.
- `APP_AI_TUTOR_FALLBACK_BASE_URL`, `APP_AI_TUTOR_FALLBACK_MODEL`, and `APP_AI_TUTOR_FALLBACK_API_MODE` configure that fallback.
- `APP_AI_TUTOR_MIN_OUTPUT_CHARS` sets a minimum response-length signal. It does not prove the answer is relevant or correct.

The local Compose overlays set the local Ollama endpoint as primary and keep hosted fallback off unless `APP_AI_TUTOR_FALLBACK_ENABLED=true` is explicitly configured. No hosted API key is needed for local-only inference.

## Context, authorization, and analytics

The service restricts inference to active students with active enrollment in the lesson's subject, published lesson content, and approved questions from that same lesson. It uses current-language lesson/chapter/subject metadata and approved translations when their source content has not changed. Question options are included where relevant, while answer keys and solutions are stripped from lesson blocks before they are sent to the model.

Provider calls occur outside the database transaction. After a usable reply, the API atomically records the learner message and assistant reply with actual provider/model labels and available token usage. Admin tutor analytics expose reply counts and token totals by provider/model.

## Readiness and limitations

Tutor availability checks a lightweight provider model-list endpoint and caches the result briefly; it does not send student prompts during health probes. A provider may still fail after a successful readiness check.

The quality gate catches some unusable output and strong response-language mismatches. It cannot reliably detect factual mistakes, irrelevant but fluent answers, or incorrect mathematics. Both in-house and hosted answers still require teacher-reviewed evaluation against real Bihar Board/SCERT/NCERT learning objectives before production rollout.

The production Compose stack does not launch an inference service. Configure `APP_AI_TUTOR_PRIMARY_BASE_URL` to a private, reachable, appropriately provisioned in-house inference endpoint. Do not expose Ollama's unauthenticated port to the public internet. Keep keys in deployment secrets and never commit them to Git.
