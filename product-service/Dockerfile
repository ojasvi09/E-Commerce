FROM maven:3.9-eclipse-temurin-21-alpine AS build
WORKDIR /workspace
COPY pom.xml .
COPY product-service/pom.xml product-service/pom.xml
RUN mvn -q -N install
COPY product-service/src product-service/src
RUN mvn -q -f /workspace/pom.xml -pl product-service package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /workspace/product-service/target/*.jar app.jar
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "app.jar"]
