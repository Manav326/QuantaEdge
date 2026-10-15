# syntax=docker/dockerfile:1.7
FROM maven:3.9.12-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml ./
# Keep Maven artifacts in BuildKit's persistent cache across rebuilds.
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -q -DskipTests dependency:go-offline
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -q -DskipTests package

FROM eclipse-temurin:21-jre
# Hindi and English OCR language packs are used only when an admin explicitly requests OCR review.
RUN apt-get update \
    && apt-get install -y --no-install-recommends fontconfig fonts-noto-core tesseract-ocr tesseract-ocr-eng tesseract-ocr-hin \
    && fc-cache -f \
    && fc-match --format='%{family}\n' 'Noto Sans Devanagari' | grep -qi 'Noto Sans Devanagari' \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /app/target/quantaedge-api-0.1.0-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar"]
