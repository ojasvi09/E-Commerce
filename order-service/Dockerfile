FROM maven:3.9-eclipse-temurin-21-alpine AS build
WORKDIR /workspace
COPY pom.xml .
COPY order-service/pom.xml order-service/pom.xml
RUN mvn -q -N install
COPY order-service/src order-service/src
RUN mvn -q -f /workspace/pom.xml -pl order-service package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /workspace/order-service/target/*.jar app.jar
EXPOSE 8084
ENTRYPOINT ["java", "-jar", "app.jar"]
