FROM docker.io/library/maven:3.9.16-amazoncorretto-25-alpine@sha256:8df9a1dbc464977482f726f28a8a1985dcef4e4e68bdda1ae0330711bcb3a192 AS build
WORKDIR /workspace

COPY pom.xml ./
COPY swirlapp-common/pom.xml swirlapp-common/pom.xml
COPY swirlapp-user/pom.xml swirlapp-user/pom.xml
COPY swirlapp-order/pom.xml swirlapp-order/pom.xml
RUN mvn -B -pl swirlapp-order -am dependency:go-offline -DskipTests

COPY swirlapp-common/src swirlapp-common/src
COPY swirlapp-order/src swirlapp-order/src
RUN mvn -B -pl swirlapp-order -am package -DskipTests

FROM docker.io/library/amazoncorretto:27-alpine@sha256:6fba5f9f2f4730e2d3e81055ce0378a8587c12a48611ee545c5db04c3a28f889
RUN apk upgrade --no-cache \
    && addgroup -S -g 10001 app && adduser -S -D -H -u 10001 -G app app
WORKDIR /app
COPY --from=build --chown=10001:10001 /workspace/swirlapp-order/target/swirlapp-order.jar ./swirlapp-order.jar
USER 10001:10001
EXPOSE 8081
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "swirlapp-order.jar"]
