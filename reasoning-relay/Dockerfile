# Compile with the JDK, run on the JRE. No dependencies, no Maven.
FROM eclipse-temurin:21.0.13_9-jdk-alpine AS build
WORKDIR /src
COPY ReasoningRelay.java .
RUN javac -d out ReasoningRelay.java && jar --create --file app.jar --main-class ReasoningRelay -C out .

FROM eclipse-temurin:21.0.13_9-jre-alpine
WORKDIR /app
COPY --from=build /src/app.jar .
EXPOSE 11435
CMD ["java", "-jar", "app.jar"]
