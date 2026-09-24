# The fat jars are produced on the host by `./mvnw -DskipTests package`
# (see README). Keeping the build out of the image avoids shipping a JDK +
# a second copy of the Maven repository just to run the app.
#
# One image definition, two services: `--build-arg MODULE=messager-simulator`
# builds the participant applications instead of the agent.
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

RUN apk add --no-cache curl \
    && addgroup -S spring \
    && adduser -S -G spring spring

ARG MODULE=messager-broker
COPY ${MODULE}/target/*.jar app.jar
RUN chown spring:spring /app/app.jar

USER spring
EXPOSE 8080

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
