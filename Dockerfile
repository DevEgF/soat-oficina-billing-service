# syntax=docker/dockerfile:1
# Contexto de build: raiz do repositório. O CI roda `./gradlew check` antes deste empacotamento.

# ---------- Build (roda na arquitetura do host; o jar é independente de arquitetura) ----------
FROM --platform=$BUILDPLATFORM gradle:9.4.0-jdk17 AS build
WORKDIR /workspace
COPY . .
RUN --mount=type=cache,target=/home/gradle/.gradle,uid=1000,gid=1000 \
    chmod +x gradlew && ./gradlew --no-daemon bootJar \
    && find build/libs -maxdepth 1 -name '*.jar' ! -name '*-plain.jar' -exec cp {} /workspace/app.jar \; \
    && test -s /workspace/app.jar
# Agente Java do New Relic com checksum conferido.
RUN curl -fsSL https://repo.maven.apache.org/maven2/com/newrelic/agent/java/newrelic-agent/9.4.0/newrelic-agent-9.4.0.jar -o /workspace/newrelic.jar \
    && echo '1f8f42d25e6565a1a7088543deeeefacdd2b028761ac94743dd1715cf7ddf5f4  /workspace/newrelic.jar' | sha256sum -c -

# ---------- Runtime (índice multi-arquitetura: amd64 no CI, arm64 na máquina e no kind) ----------
FROM eclipse-temurin:17.0.20_8-jre-noble@sha256:76bf6d763e84e4d286d5ee3df61e4adeca214977f83c7d8d2327b93db582dd9d
RUN groupadd --gid 1001 app && useradd --uid 1001 --gid 1001 --no-create-home app
WORKDIR /app
COPY --from=build --chown=1001:1001 /workspace/app.jar /app/app.jar
COPY --from=build --chown=1001:1001 /workspace/newrelic.jar /app/newrelic.jar
USER 1001:1001
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70.0 -XX:+UseContainerSupport"
EXPOSE 8080
# O agente do New Relic só é ligado quando NEW_RELIC_LICENSE_KEY não está vazio.
# A configuração do agente vem das variáveis NEW_RELIC_* (ConfigMap do k8s).
ENTRYPOINT ["sh", "-c", "if [ -n \"${NEW_RELIC_LICENSE_KEY:-}\" ]; then exec java -javaagent:/app/newrelic.jar $JAVA_OPTS -jar /app/app.jar; else exec java $JAVA_OPTS -jar /app/app.jar; fi"]
