# Compile with the JDK, run on the JRE. No dependencies, no Maven.
FROM eclipse-temurin:21.0.13_9-jdk-alpine AS build
WORKDIR /src
COPY Checkout.java .
RUN javac -d out Checkout.java && jar --create --file app.jar --main-class Checkout -C out .

FROM eclipse-temurin:21.0.13_9-jre-alpine
WORKDIR /app
COPY --from=build /src/app.jar .
EXPOSE 8080
CMD ["java", "-jar", "app.jar"]
