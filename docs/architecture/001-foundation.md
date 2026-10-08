# QuantaEdge Foundation Architecture

## Decisions
- Web: Next.js 16 + TypeScript, App Router.
- API: Spring Boot 4.1 + Java 21, Maven.
- Database: PostgreSQL 17.
- Cache: Redis 8.
- Local orchestration: Docker Compose.
- Curriculum is data, not frontend code.
- AI is isolated behind an API gateway/context layer and never directly exposed to the browser.
- Student, guardian and admin experiences remain separate application surfaces.

## Initial bounded domains
Auth, users, guardians, students, curriculum, content, lessons, questions, learning, progress, revision, AI, notifications, analytics and admin.

## MVP learning loop
Diagnostic -> recommended starting concept -> lesson -> interactive practice -> mastery update -> next lesson/revision.

## Non-goals for foundation
Payments, live classes, social feed, teacher marketplace, native Android, school management and unrestricted AI chat.
