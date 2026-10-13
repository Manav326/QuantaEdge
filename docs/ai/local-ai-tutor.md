# Run the QuantaEdge AI tutor locally without an API key

This optional Compose override runs a small local Ollama model and connects the existing authenticated tutor API to it. It does not modify the database schema or replace the existing hosted-provider configuration.

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

- No OpenAI API key is required for this local model mode.
- The API uses Ollama's OpenAI-compatible chat-completions endpoint; hosted OpenAI Responses API behavior remains unchanged.
- The model receives only the lesson metadata, sanitized lesson blocks, selected question context, limited mastery context, and recent tutor conversation assembled by the existing tutor service.
- Existing student authorization and rate limits remain in force.

## Important limitations

- This is a local MVP, not a production deployment. The first model download needs an internet connection.
- A small general-purpose model can make mistakes. Do not represent its answers as verified SCERT/NCERT content. Existing lesson content is the source of truth; curriculum and bilingual quality still require evaluation and teacher review.
- Only published lessons are eligible. If the lesson has no published content, the tutor cannot be opened for it.
- Do not expose Ollama's port publicly. In this Compose setup it is available only on the internal Docker network.
- To enable the tutor in production, deploy a suitably provisioned model service or configure an approved hosted provider through secret environment variables. Never commit API keys to Git.
