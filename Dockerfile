# syntax=docker/dockerfile:1
# One build, two images: ForgetMe itself (target "orchestrator") and the demo company (target "demo").

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY . .
# The cache mount keeps downloaded libraries between builds, so only the first build is slow.
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -DskipTests package

FROM eclipse-temurin:21-jre AS orchestrator
COPY --from=build /src/orchestrator/target/orchestrator-*.jar /app.jar
USER 1000:1000
ENTRYPOINT ["java", "-jar", "/app.jar"]

FROM eclipse-temurin:21-jre AS demo
COPY --from=build /src/demo/target/demo-*.jar /app.jar
USER 1000:1000
ENTRYPOINT ["java", "-jar", "/app.jar"]
