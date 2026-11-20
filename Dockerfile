FROM maven:3.9-eclipse-temurin-25 AS plugin-build

WORKDIR /build/plugin

COPY plugin/pom.xml ./pom.xml
COPY plugin/src ./src
RUN mvn --batch-mode --no-transfer-progress -U clean package

FROM itzg/minecraft-server:java25

COPY --from=plugin-build /build/plugin/target/seedserver-1.0.0.jar /plugins/SeedServer.jar
