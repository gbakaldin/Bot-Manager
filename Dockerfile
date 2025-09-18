# Stage 1: Build the application
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY target/Bot-1.0.jar Bot.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "Bot.jar"]