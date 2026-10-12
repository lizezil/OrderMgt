# syntax=docker/dockerfile:1
#
# Three stages: build the SPA, build the jar with that SPA inside it, then a slim JRE.
# Node never appears in the final image, and so never in the default Maven build either.

# ---------- 1. frontend ----------
FROM node:22-alpine AS frontend
WORKDIR /build

# pnpm is pinned by package.json's "packageManager", so corepack needs no version here.
RUN corepack enable

COPY frontend/package.json frontend/pnpm-lock.yaml frontend/pnpm-workspace.yaml ./frontend/
RUN cd frontend && pnpm install --frozen-lockfile

COPY frontend/ ./frontend/
# vite.config.ts writes to ../backend/src/main/resources/static, so that path must exist.
RUN mkdir -p /build/backend/src/main/resources/static && cd frontend && pnpm build

# ---------- 2. backend ----------
FROM maven:3.9-eclipse-temurin-17 AS backend
WORKDIR /build

# Poms first: dependency resolution is the slow part, so it gets its own layer.
COPY pom.xml ./
COPY backend/pom.xml ./backend/
RUN mvn -B -pl backend -am -DskipTests dependency:resolve

COPY backend/src ./backend/src
COPY --from=frontend /build/backend/src/main/resources/static ./backend/src/main/resources/static
RUN mvn -B -pl backend -am -DskipTests package

# ---------- 3. runtime ----------
FROM eclipse-temurin:17-jre-alpine
RUN addgroup -S app && adduser -S -G app app
WORKDIR /app

COPY --from=backend /build/backend/target/*.jar app.jar
RUN chown -R app:app /app
USER app

ENV SPRING_PROFILES_ACTIVE=mysql
# MaxRAMPercentage instead of a fixed -Xmx: the container limit decides, not a guess.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseSerialGC"

EXPOSE 8081

HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=12 \
  CMD wget -qO- http://localhost:8081/actuator/health | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
