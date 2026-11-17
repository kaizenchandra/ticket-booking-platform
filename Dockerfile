FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY src src
RUN --mount=type=cache,target=/root/.m2 mvn -B -DskipTests package
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN groupadd -r ticketing && useradd -r -g ticketing ticketing
COPY --from=build /build/target/ticket-booking-platform-*.jar /app/app.jar
USER ticketing
EXPOSE 8080
ENTRYPOINT ["java","-XX:MaxRAMPercentage=70","-jar","/app/app.jar"]
