FROM eclipse-temurin:21.0.7_6-jre-ubi9-minimal

WORKDIR /app
ARG APP_VERSION

COPY build/libs/logparser-${APP_VERSION}.jar /app/logparser.jar

ENTRYPOINT ["java","-jar","/app/logparser.jar"]
