@echo off
REM Local Java Service Runner
REM This script builds and runs the Capella diagram service locally (no Docker needed)

echo ==========================================
echo   Capella Java Service - Local Runner
echo ==========================================

cd /d "<local-path>"

echo.
echo 🏗️  Building Java service with Maven...
call mvn clean compile

if %errorlevel% neq 0 (
    echo ❌ Maven build failed! 
    echo 💡 Make sure Maven is installed and in PATH
    pause
    exit /b 1
)

echo.
echo ✅ Build successful!

echo.
echo 🚀 Starting Capella Diagram Service locally...
echo    Service will be available at: http://localhost:8080
echo    Health check: http://localhost:8080/actuator/health
echo.
echo ⚠️  Keep this window open while service is running
echo    Press Ctrl+C to stop the service
echo.

REM Set environment variables for local development
set CAPELLA_MODEL_PATH=<local-path>
set SPRING_PROFILES_ACTIVE=development

REM Run the service
call mvn spring-boot:run

echo.
echo 🛑 Service stopped
pause