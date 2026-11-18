# syntax=docker/dockerfile:1

# ---------- Build stage: JDK 25 + Maven Wrapper ----------
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace

# Copy phần khai báo build trước để tận dụng cache layer khi chỉ sửa source
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN --mount=type=cache,target=/root/.m2 sh ./mvnw -B -q dependency:go-offline

COPY src/ src/
# Test chạy riêng qua service `maven` trong docker-compose (cần MySQL), không chạy lúc build image
RUN --mount=type=cache,target=/root/.m2 sh ./mvnw -B -q package -DskipTests \
    && cp target/*.jar app.jar

# ---------- Runtime stage: chỉ JRE 25, chạy bằng user không phải root ----------
FROM eclipse-temurin:25-jre
WORKDIR /app

# OrderService/OrderStatisticsScheduler dùng LocalDateTime.now()/LocalDate.now() (múi giờ JVM): đặt sẵn giờ VN trong image
# để chạy ở đâu (docker run, server) cũng tính ngày/tháng đúng. JVM đọc TZ và tự có dữ liệu múi giờ, không cần tzdata.
# Đây là giá trị mặc định: compose ghi đè bằng APP_TZ trong .env, docker run thì -e TZ=... — mọi instance phải dùng chung một giá trị.
ENV TZ=Asia/Ho_Chi_Minh

# logs/ (profile prod ghi file log) và uploads/ (FileStorageService, UPLOAD_DIR) phải ghi được bởi user spring.
# docker-compose ghi đè user bằng UID/GID của máy host (HOST_UID/HOST_GID) vì 2 thư mục này là bind mount.
RUN groupadd --system spring && useradd --system --gid spring --no-create-home spring \
    && mkdir -p /app/logs /app/uploads && chown spring:spring /app/logs /app/uploads
COPY --from=build --chown=spring:spring /workspace/app.jar app.jar
USER spring

EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
