@echo off
REM TDD Testing Cycle for Capella Java Service
REM Follows Test-Driven Development: RED → GREEN → REFACTOR

echo ==========================================
echo   TDD CYCLE: CAPELLA JAVA SERVICE
echo ==========================================
echo   Using GMHCD model for comprehensive testing
echo   Following RED → GREEN → REFACTOR methodology
echo.

cd /d "<local-path>"

REM Step 1: Validate prerequisites
echo 🔍 Step 1: Validating prerequisites...
python validate_java_setup.py
if %errorlevel% neq 0 (
    echo ❌ Prerequisites failed! Fix issues before continuing.
    pause
    exit /b 1
)

REM Step 2: Start Java service in background
echo.
echo 🚀 Step 2: Starting Java service...
echo    Building and starting service (this may take a moment)...

cd src\java
start "Java Service" cmd /c "mvn spring-boot:run"

REM Wait for service to start
echo    Waiting for service to start...
timeout /t 30 /nobreak

REM Step 3: Run TDD testing cycle
echo.
echo 🔴🟢🔄 Step 3: Running TDD testing cycle...
cd ..\..\scripts\testing
python tdd_java_service_test.py

if %errorlevel% equ 0 (
    echo.
    echo 🎉 TDD CYCLE COMPLETED SUCCESSFULLY!
    echo    ✅ Service validated with GMHCD model
    echo    ✅ Ready for Phase 2: MCP Integration
) else (
    echo.
    echo ⚠️  TDD CYCLE NEEDS MORE WORK
    echo    ❌ Review test results and fix issues
    echo    ❌ Re-run cycle after fixes
)

echo.
echo 📋 Next Steps:
echo    1. Review tdd_cycle_report.json for detailed results
echo    2. If successful, proceed to MCP integration
echo    3. If failed, debug issues and re-run cycle
echo.

pause