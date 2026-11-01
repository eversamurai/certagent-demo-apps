# Compile with the JDK, run on the JRE. No dependencies, no Maven. index.html is packed into the jar.
FROM eclipse-temurin:21.0.13_9-jdk-alpine AS build
WORKDIR /src
COPY TrafficProbe.java index.html ./
RUN javac -d out TrafficProbe.java && cp index.html out/ \
 && jar --create --file app.jar --main-class TrafficProbe -C out .

FROM eclipse-temurin:21.0.13_9-jre-alpine
WORKDIR /app
COPY --from=build /src/app.jar .
EXPOSE 8090
CMD ["java", "-jar", "app.jar"]
