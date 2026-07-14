FROM maven:3.9-eclipse-temurin-21-alpine AS build
WORKDIR /workspace
COPY pom.xml .
COPY payment-service/pom.xml payment-service/pom.xml
RUN mvn -q -N install
COPY payment-service/src payment-service/src
RUN mvn -q -f /workspace/pom.xml -pl payment-service package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /workspace/payment-service/target/*.jar app.jar
EXPOSE 8085
ENTRYPOINT ["java", "-jar", "app.jar"]
