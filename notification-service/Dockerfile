FROM maven:3.9-eclipse-temurin-21-alpine AS build
WORKDIR /workspace
COPY pom.xml .
COPY notification-service/pom.xml notification-service/pom.xml
RUN mvn -q -N install
COPY notification-service/src notification-service/src
RUN mvn -q -f /workspace/pom.xml -pl notification-service package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /workspace/notification-service/target/*.jar app.jar
EXPOSE 8086
ENTRYPOINT ["java", "-jar", "app.jar"]
