FROM eclipse-temurin:23-jdk AS build
WORKDIR /app
COPY . /app
RUN ./mvnw -f /app/pom.xml clean package

FROM eclipse-temurin:23-jre
RUN apt-get update && apt-get install -y libstdc++6 && rm -rf /var/lib/apt/lists/*
RUN groupadd -r guest && useradd -r -g guest guest
USER guest
COPY --from=build /app/target/*.jar /app/runner.jar
EXPOSE 3001
CMD ["java", "-jar", "/app/runner.jar"]