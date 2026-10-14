FROM docker.io/library/maven:3.9.16-amazoncorretto-25-alpine@sha256:5efcc6faba78451290a355763d260f990361e21084ea2125a81ba1d7b5f545f2 AS build
WORKDIR /workspace

COPY pom.xml ./
COPY swirlapp-common/pom.xml swirlapp-common/pom.xml
COPY swirlapp-user/pom.xml swirlapp-user/pom.xml
COPY swirlapp-order/pom.xml swirlapp-order/pom.xml
RUN mvn -B -pl swirlapp-order -am dependency:go-offline -DskipTests

COPY swirlapp-common/src swirlapp-common/src
COPY swirlapp-order/src swirlapp-order/src
RUN mvn -B -pl swirlapp-order -am package -DskipTests

FROM docker.io/library/amazoncorretto:25-alpine@sha256:dc20d7066848919d19d2a7b5f1f4e2a48f8e32fc7328a5ae683d579bafa2f818
RUN apk upgrade --no-cache \
    && addgroup -S -g 10001 app && adduser -S -D -H -u 10001 -G app app
WORKDIR /app
COPY --from=build --chown=10001:10001 /workspace/swirlapp-order/target/swirlapp-order.jar ./swirlapp-order.jar
USER 10001:10001
EXPOSE 8081
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "swirlapp-order.jar"]
