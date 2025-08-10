# syntax=docker/dockerfile:1

FROM maven:3.9.8-eclipse-temurin-21

# Set working directory
WORKDIR /app

# Copy Maven project descriptor first (layer caching for deps)
COPY pom.xml .

# Pre-fetch dependencies
RUN mvn -B -q dependency:go-offline

# Copy source code
COPY src/ src/

RUN mvn install

# Expose your app port
EXPOSE 4242:4242

# Compile and run with Maven Exec Plugin
# Replace your.main.Class with your app's main class
CMD ["java", "-jar", "dist/server.jar"]