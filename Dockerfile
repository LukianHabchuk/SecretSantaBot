FROM maven:3.9.11-eclipse-temurin-21 AS maven

FROM eclipse-temurin:27-jdk AS build
COPY --from=maven /usr/share/maven /usr/share/maven
ENV MAVEN_HOME=/usr/share/maven
ENV PATH="${MAVEN_HOME}/bin:${PATH}"

WORKDIR /workspace
COPY pom.xml .
COPY src ./src
RUN mvn -B clean package dependency:copy-dependencies \
    -DincludeScope=runtime \
    -DoutputDirectory=target/dependency

FROM eclipse-temurin:27-jre AS runtime
RUN groupadd --gid 10001 bot \
    && useradd --uid 10001 --gid bot --home-dir /home/bot --create-home --shell /usr/sbin/nologin bot \
    && mkdir -p /home/bot/.secretsanta-telegram-bot \
    && chown -R bot:bot /home/bot

WORKDIR /app
COPY --from=build --chown=bot:bot /workspace/target/secret-santa-bot-*.jar /app/app.jar
COPY --from=build --chown=bot:bot /workspace/target/dependency /app/lib

ENV HOME=/home/bot
USER bot
ENTRYPOINT ["java", "-cp", "/app/app.jar:/app/lib/*", "com.secretsanta.SecretSantaBot"]
