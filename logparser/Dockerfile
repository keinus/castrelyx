FROM gradle:9.1.0-jdk21 AS build

WORKDIR /workspace
COPY logparser/ ./logparser/
WORKDIR /workspace/logparser
RUN gradle bootJar --no-daemon

FROM eclipse-temurin:21.0.7_6-jre-ubi9-minimal

WORKDIR /app

COPY --from=build /workspace/logparser/build/libs/logparser-0.3.1.jar /app/logparser.jar

ENTRYPOINT ["java","-jar","/app/logparser.jar"]
