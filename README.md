# QuantaEdge

AI-powered learning platform for Hindi-medium students, starting with Bihar Board Classes 6–8.

## Product surfaces

- Web landing: http://localhost:3000
- Student home: http://localhost:3000/student
- Student lesson: http://localhost:3000/student/learn
- Interactive practice: http://localhost:3000/student/practice
- Student progress: http://localhost:3000/student/progress
- Parent weekly view: http://localhost:3000/parent
- Login entry: http://localhost:3000/login
- Admin workspace: http://localhost:3001
- Curriculum API: http://localhost:8080/api/v1/curriculum
- API health: http://localhost:8080/actuator/health

## Current MVP implementation

The first product layer now includes the QuantaEdge brand system, premium responsive landing page, student daily-learning shell, lesson experience with contextual help, interactive practice, progress view, parent weekly summary, and an admin curriculum/learning operations dashboard.

The API now has a database-backed curriculum foundation for Bihar Board Classes 6–8, with initial Maths and Science structure and sample chapters.

## Local development

Prerequisites: Docker Desktop / Docker Engine with Compose.

```bash
docker compose up --build
```

The application is intentionally being built in production-shaped layers: curriculum data remains backend-controlled, student/parent/admin surfaces stay separated, and AI assistance is designed to remain behind a controlled learning gateway rather than unrestricted browser chat.
