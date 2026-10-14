# Run the QuantaEdge AI tutor locally without an API key

This optional Compose override runs a small local Ollama model as the primary tutor. The API fallback remains disabled by default and can only be used by setting `APP_AI_TUTOR_FALLBACK_ENABLED=true` and supplying a secret API key. Both web and Android use the same server-side provider router; clients never choose providers directly.

## Start

From the repository root, with Docker Desktop running:

```powershell
docker compose -f docker-compose.yml -f docker-compose.ai.yml up -d --build
```

The first start downloads the Ollama image and `qwen2.5:0.5b` model. Allow extra time and disk space for the first download. The model remains in the named `quantaedge_ollama` volume. The model is intentionally small for a local proof of concept; response quality and speed will vary with available RAM/CPU and it is not a substitute for a validated, fine-tuned tutor model.

Open the student site at `http://localhost:3000/student`, sign in to the local preview student, open a **published** lesson and open the tutor dock. The tutor is scoped to the selected lesson and uses the existing student authorization, message limits, and conversation persistence.

## Stop / return to normal mode

Stop the stack:

```powershell
docker compose -f docker-compose.yml -f docker-compose.ai.yml down
```

Then start the ordinary stack with `.scripts\quantaedge-local.ps1 up`. Do not use `down -v` unless you intentionally want to delete database and model volumes.

## What this enables

- No external API key is required for the private model.
- The API routes to the private Ollama-compatible chat-completions endpoint first; the configured hosted API fallback is disabled unless explicitly turned on.
- If fallback is enabled, it is used only after the private provider fails or the response fails conservative quality checks for unusable output or a strong mismatch with the selected Hindi/English language. These checks cannot prove academic correctness; semantic quality requires teacher-reviewed evaluation.
- The model receives the selected language, translated lesson and chapter metadata where a reviewed translation exists, sanitized lesson blocks, approved question context and recent tutor conversation.
- Tutor session authorization checks the learner's active class and subject enrollment as well as publication status; usage limits remain in force.

## Important limitations

- This is a local MVP, not a production deployment. The first model download needs an internet connection.
- A small general-purpose model can make mistakes. Do not represent its answers as verified SCERT/NCERT content. Existing lesson content is the source of truth; curriculum and bilingual quality still require evaluation and teacher review.
- Only published lessons are eligible. If the lesson has no published content, the tutor cannot be opened for it.
- Do not expose Ollama's port publicly. In this Compose setup it is available only on the internal Docker network.
- In production, deploy the in-house inference service on a private network and set `APP_AI_TUTOR_PRIMARY_BASE_URL`, `APP_AI_TUTOR_PRIMARY_MODEL`, and `APP_AI_TUTOR_PRIMARY_API_MODE` to match it. The production Compose stack does not start a model server automatically; `http://ollama:11434` works only when that service exists on the same Docker network.
- Keep `APP_AI_TUTOR_FALLBACK_ENABLED=false` for in-house-only operation. To opt in to the API fallback, explicitly set the flag to `true` and store `OPENAI_API_KEY` in deployment secrets. It is never automatically enabled merely because a key exists.
- Never commit API keys to Git. Validate semantic quality with teacher-reviewed curriculum evaluation sets before production rollout.
