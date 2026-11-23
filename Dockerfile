FROM --platform=$BUILDPLATFORM maven:3-eclipse-temurin-27-resolute AS build
WORKDIR /build
COPY pom.xml .
COPY src src
RUN mvn -B -ntp package

FROM eclipse-temurin:27-jre-resolute
WORKDIR /app
RUN groupadd -g 10001 monitor && useradd -u 10001 -g monitor monitor \
    && install -d -o monitor -g monitor /app/data
COPY --from=build /build/target/laura-schule.jar /app/app.jar
ENV MONITOR_DATA_DIR=/app/data
EXPOSE 8080
USER 10001:10001
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
