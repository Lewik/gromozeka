FROM eclipse-temurin:21-jre-noble@sha256:373787d1d45a87f084fda43e7de0e9acf5eedee049446efac738f13587ec4c64

RUN groupadd --gid 10001 gromozeka \
    && useradd --uid 10001 --gid gromozeka --create-home gromozeka

WORKDIR /app

COPY --chown=gromozeka:gromozeka worker/build/libs/gromozeka-worker.jar /app/gromozeka-worker.jar
COPY --chown=gromozeka:gromozeka LICENSE /app/LICENSE

ENV GROMOZEKA_MODE=prod \
    GROMOZEKA_HOME=/var/lib/gromozeka

RUN mkdir -p /var/lib/gromozeka \
    && chown gromozeka:gromozeka /var/lib/gromozeka

USER gromozeka

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/gromozeka-worker.jar"]
