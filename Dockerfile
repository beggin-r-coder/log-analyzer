# syntax=docker/dockerfile:1

# ---------- build ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
# dependencies first, so code changes don't re-download them
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package -DskipTests && cp target/log-analyzer-*.jar /src/app.jar

# ---------- run ----------
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN groupadd --system app && useradd --system --gid app --uid 10001 app \
	&& mkdir -p /app/data && chown -R app:app /app
COPY --from=build --chown=app:app /src/app.jar /app/app.jar
USER app

ENV SPRING_PROFILES_ACTIVE=prod \
	PORT=8080 \
	DATA_DIR=/app/data \
	JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

# the H2 database lives here: mount a volume to keep data across deploys
VOLUME ["/app/data"]
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
