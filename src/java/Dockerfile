# Dockerfile for Capella Native Diagram Service
# Supports ALL 8 diagram types across ALL 4 ARCADIA layers (32 variants)

FROM openjdk:11-jre-slim

LABEL maintainer="AI Capella Integration Project"
LABEL description="Native Java service for Capella diagram creation with complete ARCADIA support"
LABEL version="1.0.0"

# Install system dependencies
RUN apt-get update && apt-get install -y \
    curl \
    wget \
    xvfb \
    libgtk-3-0 \
    libswt-gtk-4-java \
    && rm -rf /var/lib/apt/lists/*

# Create application directory
WORKDIR /app

# Copy Maven build artifact
COPY target/capella-diagram-service-1.0.0.jar /app/capella-diagram-service.jar

# Create directory for Capella models
RUN mkdir -p /app/models

# Expose port for REST API
EXPOSE 8080

# Health check endpoint
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:8080/actuator/health || exit 1

# Environment variables
ENV JAVA_OPTS="-Xmx2g -Xms512m"
ENV CAPELLA_WORKSPACE="/app/models"
ENV SPRING_PROFILES_ACTIVE="production"

# Run the service with virtual display for Eclipse/Sirius
CMD ["sh", "-c", "Xvfb :1 -ac -screen 0 1024x768x24 & DISPLAY=:1 java $JAVA_OPTS -jar capella-diagram-service.jar"]

# Usage:
# docker build -t capella-diagram-service .
# docker run -p 8080:8080 -v /path/to/models:/app/models capella-diagram-service
#
# API Examples:
# curl -X POST http://localhost:8080/api/capella/diagrams/create/la \
#   -H "Content-Type: application/json" \
#   -d '{"modelPath":"/app/models/GMHCD.aird","diagramType":"ARCHITECTURE_BLANK","diagramName":"My LA Diagram"}'
#
# curl http://localhost:8080/actuator/health