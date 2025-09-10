#!/usr/bin/env python3
"""
Java Service Setup Validator

This script validates that the Java service is properly configured
and ready to run locally without Docker.
"""

import os
import subprocess
import sys
from pathlib import Path

def check_java_installation():
    """Check if Java 11+ is installed"""
    print("☕ Checking Java installation...")
    try:
        result = subprocess.run(['java', '-version'], capture_output=True, text=True)
        if result.returncode == 0:
            version_output = result.stderr.split('\n')[0]  # Java version goes to stderr
            print(f"✅ Java found: {version_output}")
            
            # Check if it's Java 11+
            if 'version "1.8' in version_output or 'version "8"' in version_output:
                print("⚠️  Warning: Java 8 detected. Java 11+ recommended for Spring Boot 2.7+")
            return True
        else:
            print("❌ Java not found in PATH")
            return False
    except FileNotFoundError:
        print("❌ Java not installed or not in PATH")
        return False

def check_maven_installation():
    """Check if Maven is installed"""
    print("\n🔨 Checking Maven installation...")
    try:
        result = subprocess.run(['mvn', '--version'], capture_output=True, text=True)
        if result.returncode == 0:
            version_line = result.stdout.split('\n')[0]
            print(f"✅ Maven found: {version_line}")
            return True
        else:
            print("❌ Maven not found")
            return False
    except FileNotFoundError:
        print("❌ Maven not installed or not in PATH")
        return False

def check_project_structure():
    """Check if project structure is correct"""
    print("\n📁 Checking project structure...")
    
    base_path = Path("<local-path>")
    
    required_files = [
        "pom.xml",
        "org/polarsys/capella/mcp/CapellaDiagramServiceApplication.java",
        "org/polarsys/capella/mcp/service/CapellaDiagramService.java"
    ]
    
    all_found = True
    for file_path in required_files:
        full_path = base_path / file_path
        if full_path.exists():
            print(f"✅ Found: {file_path}")
        else:
            print(f"❌ Missing: {file_path}")
            all_found = False
    
    return all_found

def check_test_model():
    """Check if test model is available"""
    print("\n🎯 Checking test model availability...")
    
    model_path = Path("<local-path>")
    
    if model_path.exists():
        print(f"✅ Test model found: {model_path.name}")
        print(f"   Size: {model_path.stat().st_size / (1024*1024):.1f} MB")
        return True
    else:
        print(f"❌ Test model not found at: {model_path}")
        print("   Java service will start but diagram creation tests may fail")
        return False

def validate_pom_dependencies():
    """Check if pom.xml has required dependencies"""
    print("\n📦 Validating Maven dependencies...")
    
    pom_path = Path("<local-path>")
    
    if not pom_path.exists():
        print("❌ pom.xml not found")
        return False
    
    try:
        with open(pom_path, 'r') as f:
            pom_content = f.read()
        
        required_deps = [
            "spring-boot-starter-web",
            "spring-boot-starter-actuator", 
            "org.eclipse.sirius",
            "org.polarsys.capella"
        ]
        
        missing_deps = []
        for dep in required_deps:
            if dep in pom_content:
                print(f"✅ Dependency found: {dep}")
            else:
                print(f"❌ Dependency missing: {dep}")
                missing_deps.append(dep)
        
        return len(missing_deps) == 0
        
    except Exception as e:
        print(f"❌ Error reading pom.xml: {e}")
        return False

def check_port_availability():
    """Check if port 8080 is available"""
    print("\n🔌 Checking port availability...")
    
    import socket
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
            s.bind(('localhost', 8080))
            print("✅ Port 8080 is available")
            return True
    except OSError:
        print("⚠️  Port 8080 is in use")
        print("   You may need to stop other services or use a different port")
        return False

def main():
    """Run all validation checks"""
    print("🔍 Java Service Setup Validator")
    print("=" * 50)
    
    checks = [
        ("Java Installation", check_java_installation),
        ("Maven Installation", check_maven_installation), 
        ("Project Structure", check_project_structure),
        ("Maven Dependencies", validate_pom_dependencies),
        ("Test Model", check_test_model),
        ("Port Availability", check_port_availability)
    ]
    
    results = {}
    for check_name, check_func in checks:
        try:
            results[check_name] = check_func()
        except Exception as e:
            print(f"❌ {check_name}: Error - {e}")
            results[check_name] = False
    
    # Summary
    print("\n" + "=" * 50)
    print("📊 VALIDATION SUMMARY:")
    
    passed = sum(1 for result in results.values() if result)
    total = len(results)
    
    for check_name, result in results.items():
        status = "✅ PASS" if result else "❌ FAIL"
        print(f"   {check_name}: {status}")
    
    print(f"\nOverall: {passed}/{total} checks passed")
    
    if passed == total:
        print("\n🎉 ALL CHECKS PASSED!")
        print("   Ready to run Java service locally")
        print("   Next steps:")
        print("   1. Run: run_java_service_locally.bat")
        print("   2. Test: python test_java_service_locally.py")
    elif passed >= total - 2:
        print("\n⚠️  MOSTLY READY")
        print("   Some minor issues found but service should still work")
        print("   Review failed checks above")
    else:
        print("\n🚨 SETUP ISSUES DETECTED")
        print("   Please fix the failed checks before proceeding")
        
    return passed >= total - 1  # Allow 1 minor failure

if __name__ == "__main__":
    success = main()
    sys.exit(0 if success else 1)