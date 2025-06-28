FROM maven:3.9.10-eclipse-temurin-24 AS build
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN mvn clean package

FROM openjdk:24-slim
WORKDIR /app
COPY --from=build /build/target/p2p-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8080
CMD ["java", "-jar", "app.jar"]