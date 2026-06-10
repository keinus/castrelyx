FROM gradle:9.1.0-jdk21 AS build

WORKDIR /workspace
COPY CastrelSign/ ./CastrelSign/
WORKDIR /workspace/CastrelSign
RUN gradle bootJar --no-daemon

FROM eclipse-temurin:21.0.7_6-jre-ubi9-minimal

WORKDIR /app
COPY --from=build /workspace/CastrelSign/build/libs/*.jar /app/castrelsign.jar
EXPOSE 8443
ENTRYPOINT ["java","-jar","/app/castrelsign.jar"]
