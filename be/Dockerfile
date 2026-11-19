# ---- Stage 1: Build ứng dụng bằng Maven ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

# Copy pom.xml trước để tận dụng Docker layer cache (chỉ tải lại dependency khi pom.xml đổi)
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copy source code và build ra file jar
COPY src ./src
RUN mvn clean package -DskipTests

# ---- Stage 2: Chạy ứng dụng bằng JRE (nhẹ hơn JDK, không cần Maven) ----
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

COPY --from=build /app/target/*.jar app.jar

EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
