# --- Compilación ---
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -q -B dependency:go-offline || true
COPY src ./src
# Las pruebas se ejecutan aparte con "mvn test" (ver README); aquí solo se empaqueta.
RUN mvn -q -B -Dmaven.test.skip=true package

# --- Ejecución ---
FROM eclipse-temurin:21-jre
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/* \
    && useradd -r -u 10001 app
WORKDIR /app
COPY --from=build /src/target/helpdesk-api-1.0.0.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
