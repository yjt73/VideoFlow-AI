FROM node:22-alpine AS frontend
WORKDIR /build/client
COPY client/package*.json ./
RUN npm ci
COPY client/ ./
RUN npm run build

FROM maven:3.9.12-eclipse-temurin-21 AS backend
WORKDIR /build/server
COPY server/ ./
COPY --from=frontend /build/client/dist/ src/main/resources/static/
RUN mvn -B -DskipTests package

FROM eclipse-temurin:21-jre-jammy
RUN rm -f /etc/apt/apt.conf.d/docker-clean \
    && sed -i 's|http://archive.ubuntu.com/ubuntu|https://archive.ubuntu.com/ubuntu|g; s|http://security.ubuntu.com/ubuntu|https://security.ubuntu.com/ubuntu|g' /etc/apt/sources.list
RUN --mount=type=cache,target=/var/cache/apt,sharing=locked \
    --mount=type=cache,target=/var/lib/apt/lists,sharing=locked \
    apt-get update -o Acquire::Retries=5 \
    && apt-get install -o Acquire::Retries=5 -y --no-install-recommends \
       ca-certificates curl ffmpeg python3-pip tesseract-ocr \
       tesseract-ocr-chi-sim tesseract-ocr-eng \
    && pip3 install --no-cache-dir yt-dlp
WORKDIR /app
COPY --from=backend /build/server/target/server-0.0.1-SNAPSHOT.jar app.jar
ENV SERVER_ADDRESS=0.0.0.0
EXPOSE 9090
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
