FROM node:22-alpine AS frontend

WORKDIR /workspace/CastrelVault/frontend
COPY CastrelVault/frontend/package*.json ./
RUN npm ci
COPY CastrelVault/frontend/ ./
RUN npm run build

FROM gradle:9.1.0-jdk21 AS build

WORKDIR /workspace
COPY CastrelVault/ ./CastrelVault/
COPY --from=frontend /workspace/CastrelVault/src/main/resources/static ./CastrelVault/src/main/resources/static
WORKDIR /workspace/CastrelVault
RUN gradle bootJar -PskipFrontend=true --no-daemon

FROM eclipse-temurin:21.0.7_6-jre-ubi9-minimal

WORKDIR /app
COPY --from=build /workspace/CastrelVault/build/libs/*.jar /app/castrelvault.jar
EXPOSE 8781
ENTRYPOINT ["java","-jar","/app/castrelvault.jar"]
