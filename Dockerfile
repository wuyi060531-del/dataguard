# Fixed Java patch tags; update only after reviewing official image release notes.
FROM eclipse-temurin:24.0.2_12-jdk-noble AS build
# Wrapper validates the ZIP checksum; without unzip it silently downloads TAR.GZ instead.
RUN apt-get update && apt-get install -y --no-install-recommends unzip \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /build
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY src/main/ src/main/
# Tests run in CI before the image smoke job. Packaging alone is not test evidence.
RUN chmod 0755 mvnw && ./mvnw -B -ntp -Dmaven.test.skip=true package

FROM eclipse-temurin:24.0.2_12-jre-noble AS runtime
WORKDIR /app
RUN groupadd --system --gid 10001 dataguard \
    && useradd --system --uid 10001 --gid 10001 --home-dir /app --shell /usr/sbin/nologin dataguard \
    && mkdir -p /app/var/uploads \
    && chown -R 10001:10001 /app/var
COPY --from=build --chown=10001:10001 /build/target/dataguard-1.1.0.jar /app/app.jar
COPY data/raw/orders.csv /app/data/raw/orders.csv
ENV DATAGUARD_BIND_ADDRESS=0.0.0.0 \
    DATAGUARD_STORAGE_ROOT=/app/var/uploads \
    DATAGUARD_IMPORT_ROOT=/app/data/raw \
    JAVA_TOOL_OPTIONS="-Xms64m -Xmx256m -XX:ActiveProcessorCount=2 -XX:+ExitOnOutOfMemoryError"
USER 10001:10001
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=45s --retries=6 \
    CMD curl --fail --silent --show-error --max-time 2 http://127.0.0.1:8080/api/tasks?limit=1 >/dev/null || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
