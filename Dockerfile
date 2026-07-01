FROM eclipse-temurin:21-jre
WORKDIR /app

RUN groupadd -r -g 1007 botmanager && useradd -r -u 1006 -g botmanager botmanager

RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*

RUN mkdir -p /app/logs && chown -R botmanager:botmanager /app

COPY --chown=botmanager:botmanager target/Bot-1.0.jar Bot.jar

USER botmanager

EXPOSE 8080

ENTRYPOINT ["java", \
    "-XX:MaxRAMPercentage=75.0", \
    "-XX:+ExitOnOutOfMemoryError", \
    "-jar", "Bot.jar"]