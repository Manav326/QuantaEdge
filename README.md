# QuantaEdge

AI-powered learning platform for Hindi-medium students, starting with Bihar Board Classes 6–8.

## Local development

Prerequisites: Docker Desktop / Docker Engine with Compose.

```bash
docker compose up --build
```

- Web: http://localhost:3000
- Admin: http://localhost:3001
- API: http://localhost:8080
- API health: http://localhost:8080/actuator/health
- PostgreSQL: localhost:5432
- Redis: localhost:6379

This repository intentionally starts with a small, production-shaped foundation. Product features are added only after the foundation is green.
