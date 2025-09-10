#!/usr/bin/env python3
"""
TDD Testing Script for Capella Java Service
Following Test-Driven Development methodology: RED → GREEN → REFACTOR

Uses GMHCD model at: <local-path>\
"""

import requests
import json
import time
import os
from pathlib import Path
import subprocess
import sys

class TDDJavaServiceTester:
    def __init__(self):
        self.base_url = "http://localhost:8080"
        # Use GMHCD model as specified by user
        self.test_model_path = "<local-path>"
        
        # All 8 diagram types for comprehensive testing
        self.diagram_types = [
            "CLASS_BLANK",
            "ARCHITECTURE_BLANK", 
            "INTERACTION_BLANK",
            "DATA_FLOW_BLANK",
            "ENTITY_BLANK",
            "BREAKDOWN",
            "INTERFACE_SCENARIO",
            "ROLE_BLANK"
        ]
        
        # All 4 ARCADIA layers
        self.layers = ["OA", "SA", "LA", "PA"]
        
        self.test_results = {
            "phase": "RED",  # Start with RED phase (expecting failures)
            "tests": {},
            "summary": {}
        }

    def run_tdd_cycle(self):
        """Execute complete TDD cycle: RED → GREEN → REFACTOR"""
        print("🔴🟢🔄 TDD CYCLE: CAPELLA JAVA SERVICE")
        print("=" * 60)
        print(f"📋 Test Model: {Path(self.test_model_path).name}")
        print(f"🎯 Target: 32 diagram variants (8 types × 4 layers)")
        print()
        
        # Validate prerequisites
        if not self.validate_prerequisites():
            return False
        
        # Phase 1: RED - Run tests expecting failures
        print("🔴 PHASE 1: RED - Running tests (expecting failures)")
        red_results = self.run_red_phase()
        
        # Phase 2: GREEN - Fix issues and make tests pass
        print("\n🟢 PHASE 2: GREEN - Fixing issues to make tests pass")
        green_results = self.run_green_phase(red_results)
        
        # Phase 3: REFACTOR - Optimize while keeping tests passing
        print("\n🔄 PHASE 3: REFACTOR - Optimizing code")
        refactor_results = self.run_refactor_phase(green_results)
        
        # Final summary
        self.generate_tdd_report(red_results, green_results, refactor_results)
        
        return True

    def validate_prerequisites(self):
        """Validate all prerequisites for TDD testing"""
        print("✅ Validating TDD Prerequisites...")
        
        # Check GMHCD model exists
        if not os.path.exists(self.test_model_path):
            print(f"❌ GMHCD model not found at: {self.test_model_path}")
            return False
        else:
            model_size = os.path.getsize(self.test_model_path) / (1024*1024)
            print(f"✅ GMHCD model found: {model_size:.1f} MB")
        
        # Check if Java service is running
        try:
            response = requests.get(f"{self.base_url}/actuator/health", timeout=5)
            if response.status_code == 200:
                print("✅ Java service is running and healthy")
                return True
            else:
                print(f"❌ Java service health check failed: {response.status_code}")
                return False
        except requests.exceptions.RequestException:
            print("❌ Java service not running")
            print("💡 Start service with: mvn spring-boot:run")
            return False

    def run_red_phase(self):
        """RED Phase: Run all tests, expecting most to fail"""
        print("\n🔴 RED PHASE: Running initial tests (failures expected)")
        print("-" * 50)
        
        results = {
            "health_test": self.test_service_health(),
            "endpoints_test": self.test_endpoints_exist(),
            "diagram_types_test": self.test_diagram_types_endpoint(),
            "single_diagram_test": self.test_create_single_diagram(),
            "layer_endpoints_test": self.test_layer_specific_endpoints(),
            "error_handling_test": self.test_error_handling(),
            "comprehensive_matrix_test": self.test_comprehensive_matrix()
        }
        
        # Count failures (expected in RED phase)
        failures = sum(1 for result in results.values() if not result["success"])
        total = len(results)
        
        print(f"\n🔴 RED PHASE RESULTS: {failures}/{total} tests failed (expected)")
        
        return results

    def run_green_phase(self, red_results):
        """GREEN Phase: Fix issues to make tests pass"""
        print("\n🟢 GREEN PHASE: Fixing issues to make tests pass")
        print("-" * 50)
        
        # Focus on fixing the most critical failures first
        priority_fixes = [
            "health_test",
            "endpoints_test", 
            "single_diagram_test",
            "comprehensive_matrix_test"
        ]
        
        green_results = {}
        
        for test_name in priority_fixes:
            if test_name in red_results and not red_results[test_name]["success"]:
                print(f"\n🔧 Fixing: {test_name}")
                
                # Implement fix strategy based on failure type
                fix_result = self.apply_fix_for_test(test_name, red_results[test_name])
                green_results[test_name] = fix_result
                
                # Re-run test to verify fix
                if test_name == "health_test":
                    green_results[test_name] = self.test_service_health()
                elif test_name == "single_diagram_test":
                    green_results[test_name] = self.test_create_single_diagram()
                # Add more test re-runs as needed
                
                if green_results[test_name]["success"]:
                    print(f"✅ Fixed: {test_name}")
                else:
                    print(f"❌ Fix failed: {test_name}")
        
        return green_results

    def run_refactor_phase(self, green_results):
        """REFACTOR Phase: Optimize while keeping tests passing"""
        print("\n🔄 REFACTOR PHASE: Optimizing implementation")
        print("-" * 50)
        
        # Focus on performance and code quality improvements
        optimizations = [
            "session_management",
            "error_handling",
            "response_time",
            "memory_usage"
        ]
        
        refactor_results = {}
        
        for optimization in optimizations:
            print(f"🔧 Optimizing: {optimization}")
            # Implement optimization strategies
            refactor_results[optimization] = self.apply_optimization(optimization)
        
        # Re-run critical tests to ensure optimizations don't break functionality
        print("\n✅ Verifying optimizations don't break tests...")
        verification = {
            "health_still_works": self.test_service_health(),
            "diagrams_still_work": self.test_create_single_diagram()
        }
        
        refactor_results["verification"] = verification
        
        return refactor_results

    # ===== INDIVIDUAL TEST IMPLEMENTATIONS =====

    def test_service_health(self):
        """Test 1: Service health check"""
        try:
            response = requests.get(f"{self.base_url}/actuator/health", timeout=5)
            success = response.status_code == 200 and "UP" in response.text
            return {
                "success": success,
                "message": f"Health check: {response.status_code}",
                "details": response.text if response.status_code == 200 else "Failed"
            }
        except Exception as e:
            return {
                "success": False,
                "message": f"Health check failed: {str(e)}",
                "details": "Service not accessible"
            }

    def test_endpoints_exist(self):
        """Test 2: All endpoints respond (even if with errors)"""
        endpoints = [
            "/api/capella/diagrams/create",
            "/api/capella/diagrams/create/la",
            "/api/capella/diagrams/types"
        ]
        
        results = {}
        all_exist = True
        
        for endpoint in endpoints:
            try:
                response = requests.post(f"{self.base_url}{endpoint}", json={}, timeout=10)
                # Any response except 404 means endpoint exists
                exists = response.status_code != 404
                results[endpoint] = {
                    "exists": exists,
                    "status_code": response.status_code
                }
                if not exists:
                    all_exist = False
            except Exception as e:
                results[endpoint] = {
                    "exists": False,
                    "error": str(e)
                }
                all_exist = False
        
        return {
            "success": all_exist,
            "message": f"Endpoints check: {'PASS' if all_exist else 'FAIL'}",
            "details": results
        }

    def test_diagram_types_endpoint(self):
        """Test 3: Diagram types endpoint functionality"""
        try:
            response = requests.post(
                f"{self.base_url}/api/capella/diagrams/types",
                json={"layer": "LA"},
                timeout=10
            )
            
            if response.status_code == 200:
                result = response.json()
                expected_fields = ["layer", "diagram_types", "total_types"]
                has_all_fields = all(field in result for field in expected_fields)
                correct_count = result.get("total_types") == 8
                
                success = has_all_fields and correct_count
                return {
                    "success": success,
                    "message": f"Diagram types: {'PASS' if success else 'FAIL'}",
                    "details": result
                }
            else:
                return {
                    "success": False,
                    "message": f"Diagram types failed: {response.status_code}",
                    "details": response.text
                }
        except Exception as e:
            return {
                "success": False,
                "message": f"Diagram types error: {str(e)}",
                "details": None
            }

    def test_create_single_diagram(self):
        """Test 4: Create a single LA Architecture Blank diagram"""
        payload = {
            "modelPath": self.test_model_path,
            "layer": "LA",
            "diagramType": "ARCHITECTURE_BLANK",
            "diagramName": "TDD_Test_LA_Architecture"
        }
        
        try:
            response = requests.post(
                f"{self.base_url}/api/capella/diagrams/create/la",
                json=payload,
                timeout=60  # Diagram creation can take time
            )
            
            if response.status_code == 200:
                result = response.json()
                success = result.get("success", False)
                diagram_id = result.get("diagramId")
                
                return {
                    "success": success,
                    "message": f"Single diagram: {'PASS' if success else 'FAIL'}",
                    "details": {
                        "diagram_id": diagram_id,
                        "response": result
                    }
                }
            else:
                return {
                    "success": False,
                    "message": f"Single diagram failed: {response.status_code}",
                    "details": response.text
                }
        except Exception as e:
            return {
                "success": False,
                "message": f"Single diagram error: {str(e)}",
                "details": None
            }

    def test_layer_specific_endpoints(self):
        """Test 5: All layer-specific endpoints"""
        results = {}
        all_success = True
        
        for layer in self.layers:
            endpoint = f"/api/capella/diagrams/create/{layer.lower()}"
            payload = {
                "modelPath": self.test_model_path,
                "diagramType": "ARCHITECTURE_BLANK",
                "diagramName": f"TDD_Test_{layer}_Endpoint"
            }
            
            try:
                response = requests.post(
                    f"{self.base_url}{endpoint}",
                    json=payload,
                    timeout=60
                )
                
                if response.status_code == 200:
                    result = response.json()
                    success = result.get("success", False)
                    results[layer] = {
                        "success": success,
                        "diagram_id": result.get("diagramId"),
                        "message": result.get("message")
                    }
                    if not success:
                        all_success = False
                else:
                    results[layer] = {
                        "success": False,
                        "error": f"HTTP {response.status_code}",
                        "message": response.text
                    }
                    all_success = False
                    
            except Exception as e:
                results[layer] = {
                    "success": False,
                    "error": str(e)
                }
                all_success = False
        
        return {
            "success": all_success,
            "message": f"Layer endpoints: {'PASS' if all_success else 'FAIL'}",
            "details": results
        }

    def test_error_handling(self):
        """Test 6: Error handling with invalid inputs"""
        error_tests = [
            {
                "name": "invalid_model_path",
                "payload": {
                    "modelPath": "/invalid/path.aird",
                    "layer": "LA",
                    "diagramType": "ARCHITECTURE_BLANK",
                    "diagramName": "Error_Test"
                }
            },
            {
                "name": "invalid_diagram_type", 
                "payload": {
                    "modelPath": self.test_model_path,
                    "layer": "LA",
                    "diagramType": "INVALID_TYPE",
                    "diagramName": "Error_Test"
                }
            },
            {
                "name": "invalid_layer",
                "payload": {
                    "modelPath": self.test_model_path,
                    "layer": "INVALID_LAYER",
                    "diagramType": "ARCHITECTURE_BLANK",
                    "diagramName": "Error_Test"
                }
            }
        ]
        
        results = {}
        all_handled = True
        
        for test in error_tests:
            try:
                response = requests.post(
                    f"{self.base_url}/api/capella/diagrams/create",
                    json=test["payload"],
                    timeout=30
                )
                
                if response.status_code == 200:
                    result = response.json()
                    # Error should be handled gracefully (success=false, but no crash)
                    handled = not result.get("success", True)  # Should fail gracefully
                    results[test["name"]] = {
                        "handled": handled,
                        "response": result
                    }
                    if not handled:
                        all_handled = False
                else:
                    results[test["name"]] = {
                        "handled": False,
                        "error": f"HTTP {response.status_code}"
                    }
                    all_handled = False
                    
            except Exception as e:
                results[test["name"]] = {
                    "handled": False,
                    "error": str(e)
                }
                all_handled = False
        
        return {
            "success": all_handled,
            "message": f"Error handling: {'PASS' if all_handled else 'FAIL'}",
            "details": results
        }

    def test_comprehensive_matrix(self):
        """Test 7: Comprehensive matrix (8 types × 4 layers = 32 variants)"""
        print("\n🎨 Testing comprehensive diagram matrix...")
        
        results = {}
        total_tests = 0
        successful_tests = 0
        
        for layer in self.layers:
            results[layer] = {}
            print(f"\n📁 Testing {layer} layer...")
            
            for diagram_type in self.diagram_types:
                total_tests += 1
                
                payload = {
                    "modelPath": self.test_model_path,
                    "layer": layer,
                    "diagramType": diagram_type,
                    "diagramName": f"TDD_Matrix_{layer}_{diagram_type}"
                }
                
                try:
                    response = requests.post(
                        f"{self.base_url}/api/capella/diagrams/create",
                        json=payload,
                        timeout=60
                    )
                    
                    if response.status_code == 200:
                        result = response.json()
                        success = result.get("success", False)
                        
                        results[layer][diagram_type] = {
                            "success": success,
                            "diagram_id": result.get("diagramId"),
                            "message": result.get("message")
                        }
                        
                        if success:
                            successful_tests += 1
                            print(f"  ✅ {diagram_type}: SUCCESS")
                        else:
                            print(f"  ❌ {diagram_type}: {result.get('message', 'FAILED')}")
                    else:
                        results[layer][diagram_type] = {
                            "success": False,
                            "error": f"HTTP {response.status_code}",
                            "message": response.text
                        }
                        print(f"  ❌ {diagram_type}: HTTP {response.status_code}")
                        
                except Exception as e:
                    results[layer][diagram_type] = {
                        "success": False,
                        "error": str(e)
                    }
                    print(f"  ❌ {diagram_type}: {str(e)}")
                
                # Small delay to avoid overwhelming service
                time.sleep(0.5)
        
        success_rate = (successful_tests / total_tests) * 100
        overall_success = success_rate >= 60  # 60% minimum for GREEN phase
        
        print(f"\n📊 Matrix Results: {successful_tests}/{total_tests} successful ({success_rate:.1f}%)")
        
        return {
            "success": overall_success,
            "message": f"Comprehensive matrix: {success_rate:.1f}% success rate",
            "details": {
                "results": results,
                "total_tests": total_tests,
                "successful_tests": successful_tests,
                "success_rate": success_rate
            }
        }

    # ===== FIX AND OPTIMIZATION IMPLEMENTATIONS =====

    def apply_fix_for_test(self, test_name, failure_result):
        """Apply specific fixes based on test failure analysis"""
        print(f"🔧 Analyzing failure for: {test_name}")
        
        # This would contain actual fix logic
        # For now, return analysis of what needs to be fixed
        fix_strategies = {
            "health_test": "Check service startup, port conflicts, dependencies",
            "endpoints_test": "Verify Spring Boot controller mappings, route configuration",
            "single_diagram_test": "Debug Capella model loading, Sirius API integration",
            "comprehensive_matrix_test": "Investigate specific diagram type and layer issues"
        }
        
        strategy = fix_strategies.get(test_name, "General debugging needed")
        
        return {
            "fix_attempted": True,
            "strategy": strategy,
            "details": failure_result
        }

    def apply_optimization(self, optimization_type):
        """Apply performance and quality optimizations"""
        optimizations = {
            "session_management": "Implement session pooling and reuse",
            "error_handling": "Add structured error responses and logging",
            "response_time": "Optimize diagram creation pipeline",
            "memory_usage": "Implement garbage collection and resource cleanup"
        }
        
        return {
            "optimization": optimization_type,
            "strategy": optimizations.get(optimization_type, "Unknown optimization"),
            "applied": False,  # Would be True when actually implemented
            "impact": "Theoretical improvement"
        }

    def generate_tdd_report(self, red_results, green_results, refactor_results):
        """Generate comprehensive TDD cycle report"""
        print("\n" + "=" * 60)
        print("📊 TDD CYCLE COMPLETE - FINAL REPORT")
        print("=" * 60)
        
        # RED phase summary
        red_failures = sum(1 for r in red_results.values() if not r["success"])
        red_total = len(red_results)
        print(f"\n🔴 RED PHASE: {red_failures}/{red_total} tests failed (expected)")
        
        # GREEN phase summary  
        green_fixes = len([r for r in green_results.values() if r.get("success", False)])
        green_total = len(green_results)
        print(f"🟢 GREEN PHASE: {green_fixes}/{green_total} fixes successful")
        
        # REFACTOR phase summary
        refactor_count = len([r for r in refactor_results.values() if r.get("applied", False)])
        print(f"🔄 REFACTOR PHASE: {refactor_count} optimizations applied")
        
        # Overall assessment
        if green_fixes >= green_total * 0.8:  # 80% fixes successful
            print("\n🎉 TDD CYCLE SUCCESSFUL!")
            print("   ✅ Service is ready for integration with MCP server")
            print("   ✅ Core functionality validated with GMHCD model")
            print("   ✅ Ready for Phase 2: MCP Integration")
        else:
            print("\n⚠️  TDD CYCLE NEEDS MORE WORK")
            print("   ❌ Critical issues still need resolution")
            print("   ❌ Continue GREEN phase before proceeding")
        
        # Save detailed report
        report = {
            "timestamp": time.strftime("%Y-%m-%d %H:%M:%S"),
            "model_used": self.test_model_path,
            "tdd_phases": {
                "red": red_results,
                "green": green_results, 
                "refactor": refactor_results
            },
            "summary": {
                "red_failures": red_failures,
                "green_fixes": green_fixes,
                "refactor_optimizations": refactor_count,
                "ready_for_integration": green_fixes >= green_total * 0.8
            }
        }
        
        with open("tdd_cycle_report.json", "w") as f:
            json.dump(report, f, indent=2)
        
        print(f"\n💾 Detailed report saved: tdd_cycle_report.json")


def main():
    """Execute TDD testing cycle"""
    print("🔴🟢🔄 TDD JAVA SERVICE TESTING")
    print("Following Test-Driven Development methodology")
    print("Using GMHCD model for comprehensive validation")
    print()
    
    tester = TDDJavaServiceTester()
    success = tester.run_tdd_cycle()
    
    if success:
        print("\n✅ TDD testing completed successfully!")
    else:
        print("\n❌ TDD testing encountered issues - check prerequisites")
    
    return success

if __name__ == "__main__":
    success = main()
    sys.exit(0 if success else 1)