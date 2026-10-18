FROM gradle:9.6.1-jdk21 AS build
WORKDIR /workspace
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
COPY backend backend
COPY project-mcp project-mcp
COPY support-contracts support-contracts
COPY support-mcp support-mcp
COPY workspace-contracts workspace-contracts
COPY workspace-mcp workspace-mcp
COPY cli cli
ARG MODULE=backend
RUN --mount=type=cache,id=code-assistants-gradle,sharing=locked,target=/home/gradle/.gradle gradle --no-daemon :${MODULE}:test :${MODULE}:bootJar
RUN cp ${MODULE}/build/libs/${MODULE}.jar /workspace/app.jar

FROM eclipse-temurin:21-jre
RUN apt-get update && apt-get install -y --no-install-recommends git curl && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /workspace/app.jar /app/app.jar
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=20s --retries=5 CMD curl -fsS http://127.0.0.1:8080/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
