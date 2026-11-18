FROM docker.io/library/maven:3.10.0-amazoncorretto-25-alpine@sha256:a697e2f303fca5675052a656daefab51d845c79e6121a26edbdf8e3e4832ed97 AS build
WORKDIR /workspace

COPY pom.xml ./
COPY swirlapp-common/pom.xml swirlapp-common/pom.xml
COPY swirlapp-user/pom.xml swirlapp-user/pom.xml
COPY swirlapp-order/pom.xml swirlapp-order/pom.xml
RUN mvn -B -pl swirlapp-order -am dependency:go-offline -DskipTests

COPY swirlapp-common/src swirlapp-common/src
COPY swirlapp-order/src swirlapp-order/src
RUN mvn -B -pl swirlapp-order -am package -DskipTests

FROM docker.io/library/amazoncorretto:25-alpine@sha256:1124f698f69d2185828dbd1a1ebeb9aee21884ac64bed7af3deeafd20b21a9dc
RUN apk upgrade --no-cache \
    && addgroup -S -g 10001 app && adduser -S -D -H -u 10001 -G app app
WORKDIR /app
COPY --from=build --chown=10001:10001 /workspace/swirlapp-order/target/swirlapp-order.jar ./swirlapp-order.jar
USER 10001:10001
EXPOSE 8081
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "swirlapp-order.jar"]
