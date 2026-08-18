# syntax=docker/dockerfile:1

# ── build ──────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

COPY pom.xml .
COPY src ./src

# 快取 ~/.m2：改一行程式不用重抓幾百個依賴。
# 跳過測試——測試要連 postgres / rabbitmq / ollama，那是 CI 的事不是建映像檔的事。
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp clean package -DskipTests

# ── runtime ────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre
WORKDIR /app

# healthcheck 要用
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

COPY --from=build /build/target/*.jar app.jar

EXPOSE 8080

# 容器有自己的記憶體上限，讓 JVM 依 cgroup 而不是實體機器決定堆積大小
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
