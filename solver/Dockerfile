FROM eclipse-temurin:23-jdk AS build
WORKDIR /app
COPY . /app
RUN ./mvnw -f /app/pom.xml clean package

FROM eclipse-temurin:23-jre-alpine-3.21
USER guest
COPY --from=build /app/target/*.jar /app/runner.jar
EXPOSE 3001
CMD ["java", "-jar", "/app/runner.jar"]