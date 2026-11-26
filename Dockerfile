# Unified Single-Container Production Build for Render / Cloud Run / Fly.io
# Bundles React + TypeScript static build inside Spring Boot 3.5 (Java 21 LTS)

FROM node:22-alpine AS frontend-build
WORKDIR /frontend
COPY frontend/package*.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

FROM eclipse-temurin:21-jdk-alpine AS backend-build
WORKDIR /backend
COPY backend/.mvn .mvn
COPY backend/mvnw backend/pom.xml ./
RUN chmod +x mvnw && ./mvnw dependency:go-offline -B
COPY backend/src src
COPY --from=frontend-build /frontend/dist src/main/resources/static
RUN ./mvnw package -DskipTests -B

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S forge && adduser -S forge -G forge
COPY --from=backend-build /backend/target/forge-platform-1.0.0.jar app.jar
RUN mkdir -p /app/data && chown -R forge:forge /app
USER forge
EXPOSE 10000
HEALTHCHECK --interval=30s --timeout=5s --retries=3 CMD wget -qO- http://127.0.0.1:${PORT:-10000}/actuator/health/readiness || exit 1
ENTRYPOINT ["sh", "-c", "exec java -XX:+UseG1GC -XX:MaxRAMPercentage=72.0 -jar app.jar"]
