FROM node:22-alpine AS frontend

WORKDIR /workspace/manager/frontend
COPY manager/frontend/package*.json ./
RUN npm ci
COPY manager/frontend/ ./
RUN npm run build

FROM golang:1.22-alpine AS agent-build

WORKDIR /workspace/agent
COPY agent/go.mod agent/go.sum ./
RUN go mod download
COPY agent/ ./
RUN CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -trimpath -ldflags="-s -w" -o /out/castrelyx-agent-linux-amd64 ./cmd/castrelyx-agent
RUN CGO_ENABLED=0 GOOS=windows GOARCH=amd64 go build -trimpath -ldflags="-s -w" -o /out/castrelyx-agent-windows-amd64.exe ./cmd/castrelyx-agent

FROM gradle:9.1.0-jdk21 AS build

WORKDIR /workspace
COPY manager/ ./manager/
COPY --from=frontend /workspace/manager/src/main/resources/static ./manager/src/main/resources/static
COPY --from=agent-build /out/ ./manager/src/main/resources/agent-binaries/
WORKDIR /workspace/manager
RUN gradle bootJar -PskipFrontend=true --no-daemon

FROM eclipse-temurin:21.0.7_6-jre-ubi9-minimal

WORKDIR /app
COPY --from=build /workspace/manager/build/libs/*.jar /app/manager.jar
EXPOSE 8780
ENTRYPOINT ["java","-jar","/app/manager.jar"]
