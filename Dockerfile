FROM eclipse-temurin:21-jre
WORKDIR /app

RUN groupadd -r botmanager && useradd -r -g botmanager botmanager
RUN mkdir -p /app/logs && chown -R botmanager:botmanager /app

COPY --chown=botmanager:botmanager target/Bot-1.0.jar Bot.jar

USER botmanager

VOLUME ["/app/logs"]
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD curl -f http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", \
    "-XX:MaxRAMPercentage=75.0", \
    "-jar", "Bot.jar"]
