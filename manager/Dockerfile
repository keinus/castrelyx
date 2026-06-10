FROM node:22-alpine AS frontend

WORKDIR /workspace/manager/frontend
COPY manager/frontend/package*.json ./
RUN npm ci
COPY manager/frontend/ ./
RUN npm run build

FROM gradle:9.1.0-jdk21 AS build

WORKDIR /workspace
COPY manager/ ./manager/
COPY --from=frontend /workspace/manager/src/main/resources/static ./manager/src/main/resources/static
WORKDIR /workspace/manager
RUN gradle bootJar -PskipFrontend=true --no-daemon

FROM eclipse-temurin:21.0.7_6-jre-ubi9-minimal

WORKDIR /app
COPY --from=build /workspace/manager/build/libs/*.jar /app/manager.jar
EXPOSE 8780
ENTRYPOINT ["java","-jar","/app/manager.jar"]
