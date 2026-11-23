# Imagem do backend do Argos: compila com Maven e executa em uma JRE enxuta.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
# Espelho opcional do Maven Central (o CI passa o do Google, porque o Maven Central responde 429 aos runners
# do GitHub). Vazio, como no build local, usa o Maven Central.
ARG MAVEN_MIRROR=""
RUN if [ -n "$MAVEN_MIRROR" ]; then mkdir -p /root/.m2 && printf '<settings><mirrors><mirror><id>ci-mirror</id><mirrorOf>central</mirrorOf><url>%s</url></mirror></mirrors></settings>\n' "$MAVEN_MIRROR" > /root/.m2/settings.xml; fi
COPY pom.xml .
# Baixa as dependencias em uma camada propria: so refaz quando o pom.xml muda.
RUN mvn -B -ntp -q dependency:resolve dependency:resolve-plugins || true
COPY src ./src
RUN mvn -B -ntp -q -DskipTests package && cp target/argos-api-*.jar /build/app.jar

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 1001 argos
USER argos
WORKDIR /app
COPY --from=build /build/app.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
