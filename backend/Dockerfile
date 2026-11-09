# Backend image for a hosted deployment (profile: mysql,public).
# Build context is the backend/ directory. Tests are not run here: CI runs them on every push.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn --batch-mode -q dependency:go-offline
COPY src ./src
RUN mvn --batch-mode -q -DskipTests package && cp target/obfds-backend-*.jar /app.jar

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S obfds && adduser -S -G obfds obfds
COPY --from=build /app.jar /opt/obfds/app.jar
COPY docker-entrypoint.sh /opt/obfds/docker-entrypoint.sh
RUN chmod 0555 /opt/obfds/docker-entrypoint.sh
USER obfds
WORKDIR /opt/obfds

# Sized for a 512 MB host: serial GC, a bounded heap and metaspace, no JIT warm-up tier,
# and exit (so the platform restarts the container) instead of limping after an OOM.
ENV JAVA_OPTS="-XX:+UseSerialGC -XX:MaxRAMPercentage=55 -XX:MaxMetaspaceSize=128m -Xss512k -XX:TieredStopAtLevel=1 -XX:+ExitOnOutOfMemoryError" \
    SPRING_PROFILES_ACTIVE="mysql,public"
EXPOSE 8080
ENTRYPOINT ["/opt/obfds/docker-entrypoint.sh"]
