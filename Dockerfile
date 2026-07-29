FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY pom.xml .
COPY .mvn .mvn
COPY mvnw .
RUN mvn dependency:go-offline -B
COPY src src
RUN mvn package -DskipTests -B && \
    mv target/financial-service-app-*.jar target/app.jar

FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
COPY --from=build /src/target/app.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
