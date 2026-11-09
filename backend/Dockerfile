# Imagem do backend do Argos: compila com Maven e executa em uma JRE enxuta.
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
# Baixa as dependencias em uma camada propria: so refaz quando o pom.xml muda.
RUN mvn -B -ntp -q dependency:resolve dependency:resolve-plugins || true
COPY src ./src
RUN mvn -B -ntp -q -DskipTests package && cp target/argos-api-*.jar /build/app.jar

FROM eclipse-temurin:17-jre
RUN useradd --system --uid 1001 argos
USER argos
WORKDIR /app
COPY --from=build /build/app.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
