# syntax=docker/dockerfile:1
# Backend image (M1 step 11). Built by deploy/compose.yaml. Base images are pinned to exact versions; Dependabot
# proposes updates.

# --- Build: compile (Error Prone + NullAway run here too) and package; tests and the verify-phase gates run in CI.
FROM eclipse-temurin:26.0.2.1_1-jdk-noble AS build
WORKDIR /workspace
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY config/ config/
COPY src/ src/
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -ntp -q -DskipTests package \
 && cp target/devmind-backend-*.jar app.jar \
 && java -Djarmode=tools -jar app.jar extract --layers --destination extracted

# --- Runtime: JRE only, non-root. Layers go from least to most often changed, so code changes rebuild one layer.
FROM eclipse-temurin:26.0.2.1_1-jre-noble
RUN useradd --uid 10001 --no-create-home --shell /usr/sbin/nologin devmind
WORKDIR /app
COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./
USER 10001
# 8080: API (only reachable through the web container). 8081: actuator, internal (SPRING-5).
EXPOSE 8080 8081
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
