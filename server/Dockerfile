# Two stages, so the image is a JRE and one jar rather than a JDK and a Gradle cache.
FROM gradle:8.14-jdk21 AS build
WORKDIR /src
# The build files first, so a change to the source does not re-resolve dependencies.
COPY settings.gradle.kts build.gradle.kts ./
RUN gradle --no-daemon dependencies > /dev/null 2>&1 || true
COPY src ./src
RUN gradle --no-daemon jar

FROM eclipse-temurin:21-jre-alpine
# Not root. It serves a public endpoint, and there is nothing in here it needs to own
# except the database, which the volume gives it.
RUN addgroup -S tracker && adduser -S -G tracker tracker && mkdir -p /data && chown tracker:tracker /data
COPY --from=build /src/build/libs/*.jar /app/tracker.jar
USER tracker
VOLUME ["/data"]
EXPOSE 8080
ENV RAMPART_TRACKER_DB=/data/tracker.db
# No shell form: the JVM should be pid 1 so a docker stop reaches it rather than a shell.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-jar", "/app/tracker.jar"]
