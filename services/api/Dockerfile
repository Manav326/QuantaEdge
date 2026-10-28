# syntax=docker/dockerfile:1.7
FROM maven:3.9.12-eclipse-temurin-21 AS build
# The build-only publication gate filters draft content before it enters the JAR.
RUN apt-get update \
    && apt-get install -y --no-install-recommends python3 fontconfig fonts-noto-core \
    && fc-cache -f \
    && fc-match --format='%{family}\\n' 'Noto Sans Devanagari' | grep -qi 'Noto Sans Devanagari' \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY services/api/pom.xml ./pom.xml
# Keep Maven artifacts in BuildKit's persistent cache across rebuilds.
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -q -DskipTests dependency:go-offline
COPY services/api/src ./src
# Keep canonical drafts in data/, but include only explicitly approved/published lesson JSON in the API JAR.
COPY scripts/package_published_structured_lessons.py ./scripts/package_published_structured_lessons.py
COPY data/structured-lessons/ ./data/structured-lessons/
RUN --mount=type=cache,target=/root/.m2 \
    python3 ./scripts/package_published_structured_lessons.py \
      --source ./data/structured-lessons \
      --destination ./src/main/resources/structured-lessons \
    && mvn -B -ntp -q -DskipTests package

FROM eclipse-temurin:21-jre
# Hindi and English OCR language packs are used only when an admin explicitly requests OCR review.
RUN apt-get update \
    && apt-get install -y --no-install-recommends fontconfig fonts-noto-core tesseract-ocr tesseract-ocr-eng tesseract-ocr-hin \
    && fc-cache -f \
    && fc-match --format='%{family}\n' 'Noto Sans Devanagari' | grep -qi 'Noto Sans Devanagari' \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
# Google OAuth audience is injected at runtime by Compose, not baked into published images.
COPY --from=build /app/target/quantaedge-api-0.1.0-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar"]
