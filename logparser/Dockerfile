FROM node:22-bookworm-slim AS frontend
WORKDIR /ui
COPY logparser/frontend/package*.json ./
RUN npm ci --no-audit --no-fund
COPY logparser/frontend/ ./
RUN npm run build

FROM gradle:9.1.0-jdk21 AS build

WORKDIR /workspace
COPY logparser/ ./logparser/
WORKDIR /workspace/logparser
COPY --from=frontend /build/frontend ./build/frontend
RUN gradle bootJar --no-daemon -PskipFrontend=true

FROM eclipse-temurin:21.0.7_6-jre-ubi9-minimal

WORKDIR /app

COPY --from=build /workspace/logparser/build/libs/logparser-0.3.1.jar /app/logparser.jar
COPY --from=build /workspace/logparser/README.md /app/README.md
COPY --from=build /workspace/logparser/AGENTS.md /app/AGENTS.md
COPY --from=build /workspace/logparser/readme/ /app/readme/

ENTRYPOINT ["java","-jar","/app/logparser.jar"]
