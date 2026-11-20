FROM maven:3.9-eclipse-temurin-25 AS plugin-build

ARG PAPER_API_VERSION=26.3-R0.1-SNAPSHOT
WORKDIR /build/plugin

COPY plugin/pom.xml ./pom.xml
COPY plugin/src ./src
RUN mvn --batch-mode --no-transfer-progress -Dpaper.version=${PAPER_API_VERSION} clean package

FROM itzg/minecraft-server:java25

COPY --from=plugin-build /build/plugin/target/seedserver-1.0.0.jar /plugins/SeedServer.jar
