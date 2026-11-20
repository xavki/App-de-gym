# Imagen del servidor GymFlow: API (Ktor) + panel web compilado.
# Se construye desde la raíz del repositorio (necesita panel/ y server/):
#   docker compose -f deploy/docker-compose.yml build

# 1) Panel web
FROM node:22-alpine AS panel
WORKDIR /panel
COPY panel/package.json panel/package-lock.json ./
RUN npm ci
COPY panel/ ./
RUN npm run build

# 2) Servidor (jar con todas las dependencias)
FROM gradle:8.11.1-jdk17 AS server
WORKDIR /server
COPY server/settings.gradle.kts server/build.gradle.kts ./
COPY server/src ./src
RUN gradle --no-daemon buildFatJar -x test

# 3) Imagen final, solo con Java
FROM eclipse-temurin:17-jre
RUN useradd --system --uid 1001 gymflow
WORKDIR /app
COPY --from=server /server/build/libs/gymflow-server-all.jar /app/server.jar
COPY --from=panel /panel/dist /app/static
ENV PORT=8080 STATIC_DIR=/app/static
USER gymflow
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=60", "-jar", "/app/server.jar"]
