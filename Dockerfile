FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -q dependency:go-offline
COPY src src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /src/target/app.jar app.jar
EXPOSE 8080
# Render free = 512MB total and 0.1 CPU. Heap capped at 50% (live data is tiny) so heap + metaspace +
# thread stacks + per-socket kernel buffers stay under 512MB during a burst; small stacks and glibc arenas
# trim native memory; serial GC and C1-only JIT keep GC/compiler threads off the CPU the requests need.
ENV MALLOC_ARENA_MAX=2
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=50", "-Xss512k", "-XX:MaxMetaspaceSize=128m", "-XX:ReservedCodeCacheSize=48m", \
            "-XX:MaxDirectMemorySize=64m", \
            "-XX:+UseSerialGC", "-XX:TieredStopAtLevel=1", "-XX:+ExitOnOutOfMemoryError", "-jar", "app.jar"]
