FROM node:22-alpine AS frontend
WORKDIR /ui
COPY frontend/package.json frontend/package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci
COPY frontend/ ./
RUN npm run build -- --outDir=/ui/dist

FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /app
COPY backend/pom.xml backend/maven-settings.xml ./
COPY backend/src src
COPY --from=frontend /ui/dist/ src/main/resources/static/
RUN --mount=type=cache,target=/root/.m2 mvn -s maven-settings.xml -B -Daether.connector.connectTimeout=10000 -Daether.connector.requestTimeout=30000 -DskipTests package

FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache curl && addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build /app/target/vinyl-store-backend-1.0.0.jar app.jar
USER app
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=5s --start-period=40s --retries=20 CMD-SHELL curl -fsS "http://localhost:${PORT:-8080}/actuator/health" || exit 1
CMD ["sh", "-c", "exec java -Dserver.port=${PORT:-8080} -jar app.jar"]
