# Stage 1: Build the application
FROM gradle:8.4-jdk17 AS builder
WORKDIR /app

# Copy project files
COPY . .

# Build the Spring Boot jar
RUN gradle clean build --no-daemon

# Stage 2: Run the application
FROM eclipse-temurin:17-jdk-jammy
WORKDIR /app

# Copy the jar from the builder stage
COPY --from=builder /app/build/libs/*.jar app.jar

# Expose Spring Boot default port
EXPOSE 8080

# Run the application
ENTRYPOINT ["java", "-jar", "app.jar"]