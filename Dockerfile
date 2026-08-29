FROM maven:3.9.11-eclipse-temurin-21 AS build
ARG SERVICE
WORKDIR /src
COPY pom.xml ./
COPY common ./common
COPY services ./services
COPY benchmarks ./benchmarks
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -DskipTests -pl services/${SERVICE} -am package

FROM eclipse-temurin:21-jdk
ARG SERVICE
COPY --from=build /src/services/${SERVICE}/target/${SERVICE}-*.jar /app.jar
USER 1001:1001
ENV JAVA_OPTS=""
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app.jar"]
