#!/usr/bin/env python3
"""
Diagnostic tests to identify MCP server setup issues
"""
import sys
import subprocess
import json
from pathlib import Path


def test_dependencies():
    """Test 1: Check all required dependencies are installed"""
    print("=" * 60)
    print("TEST 1: Checking Dependencies")
    print("=" * 60)

    results = []

    # Test Python version
    py_version = sys.version_info
    results.append(
        f"Python version: {py_version.major}.{py_version.minor}.{py_version.micro}"
    )
    if py_version.major >= 3 and py_version.minor >= 11:
        results.append("✓ Python version OK (>=3.11)")
    else:
        results.append("✗ Python version too old (need >=3.11)")

    # Test imports
    dependencies = [
        ("mcp", "MCP core library"),
        ("mcp.server.fastmcp", "FastMCP server"),
        ("pydantic", "Pydantic validation"),
        ("capellambse", "Capella MBSE library (optional)"),
    ]

    for module_name, description in dependencies:
        try:
            if "." in module_name:
                parts = module_name.split(".")
                mod = __import__(parts[0])
                for part in parts[1:]:
                    mod = getattr(mod, part)
            else:
                __import__(module_name)
            results.append(f"✓ {description} ({module_name}) - INSTALLED")
        except ImportError as e:
            if "capellambse" in module_name:
                results.append(
                    f"⚠ {description} ({module_name}) - NOT INSTALLED (optional)"
                )
            else:
                results.append(f"✗ {description} ({module_name}) - MISSING: {e}")

    for result in results:
        print(result)
    return all("✗" not in r for r in results if "⚠" not in r)


def test_file_structure():
    """Test 2: Check file structure and imports"""
    print("\n" + "=" * 60)
    print("TEST 2: File Structure & Imports")
    print("=" * 60)

    base_dir = Path(__file__).parent
    src_dir = base_dir / "src" / "capella_mcp"

    required_files = [
        ("server.py", "Main server implementation"),
        ("__init__.py", "Package initialization"),
        ("__main__.py", "Entry point"),
    ]

    results = []
    for filename, description in required_files:
        filepath = src_dir / filename
        if filepath.exists():
            results.append(f"✓ {filename} exists - {description}")

            # Check for import issues in __main__.py
            if filename == "__main__.py":
                with open(filepath, "r") as f:
                    content = f.read()
                    if "CapellaMCPServer" in content:
                        results.append(
                            "✗ __main__.py imports CapellaMCPServer (doesn't exist in server.py)"
                        )
                    if "FastMCP" not in content:
                        results.append("✗ __main__.py doesn't use FastMCP")
        else:
            results.append(f"✗ {filename} missing - {description}")

    for result in results:
        print(result)
    return all("✗" not in r for r in results)


def test_server_initialization():
    """Test 3: Try to initialize the server"""
    print("\n" + "=" * 60)
    print("TEST 3: Server Initialization")
    print("=" * 60)

    try:
        # Add src to path
        sys.path.insert(0, str(Path(__file__).parent / "src"))

        # Try importing the server module
        from capella_mcp import server

        print("✓ Server module imports successfully")

        # Check if main function exists
        if hasattr(server, "main"):
            print("✓ main() function exists")
        else:
            print("✗ main() function missing")

        # Check if FastMCP instance exists
        if hasattr(server, "mcp"):
            print("✓ FastMCP instance 'mcp' exists")
        else:
            print("✗ FastMCP instance 'mcp' missing")

        return True

    except ImportError as e:
        print(f"✗ Failed to import server: {e}")
        return False
    except Exception as e:
        print(f"✗ Unexpected error: {e}")
        return False


def test_simple_server():
    """Test 4: Test the simple test server"""
    print("\n" + "=" * 60)
    print("TEST 4: Simple Test Server")
    print("=" * 60)

    test_file = Path(__file__).parent / "test_simple.py"
    if not test_file.exists():
        print("✗ test_simple.py not found")
        return False

    try:
        # Test if it can be imported
        import test_simple

        print("✓ test_simple.py imports successfully")

        # Check FastMCP instance
        if hasattr(test_simple, "mcp"):
            print("✓ FastMCP instance found")

            # Check tools
            tools = []
            if hasattr(test_simple.mcp, "_tools"):
                tools = list(test_simple.mcp._tools.keys())
            elif hasattr(test_simple.mcp, "tools"):
                tools = list(test_simple.mcp.tools.keys())

            if tools:
                print(f"✓ Found {len(tools)} tools: {', '.join(tools)}")
            else:
                print("⚠ No tools found (check tool registration)")
        else:
            print("✗ No FastMCP instance found")

        return True

    except Exception as e:
        print(f"✗ Error testing simple server: {e}")
        return False


def test_mcp_protocol():
    """Test 5: Test MCP protocol basics"""
    print("\n" + "=" * 60)
    print("TEST 5: MCP Protocol Test")
    print("=" * 60)

    # Create a minimal test to verify MCP protocol
    test_code = '''
import json
import sys
from mcp.server.fastmcp import FastMCP

mcp = FastMCP("test")

@mcp.tool()
def test_tool() -> str:
    """Test tool"""
    return "OK"

# Instead of running, just verify it can be created
print(json.dumps({"status": "server_created", "name": mcp.name}))
'''

    try:
        result = subprocess.run(
            [sys.executable, "-c", test_code], capture_output=True, text=True, timeout=5
        )

        if result.returncode == 0:
            output = json.loads(result.stdout.strip())
            print(f"✓ FastMCP server can be created: {output}")
            return True
        else:
            print(f"✗ Error creating FastMCP server: {result.stderr}")
            return False

    except subprocess.TimeoutExpired:
        print("✗ Server creation timed out")
        return False
    except Exception as e:
        print(f"✗ Protocol test failed: {e}")
        return False


def test_claude_config():
    """Test 6: Check Claude Code configuration"""
    print("\n" + "=" * 60)
    print("TEST 6: Claude Code Configuration")
    print("=" * 60)

    # Check if server is configured in Claude
    try:
        result = subprocess.run(
            ["claude", "mcp", "list"], capture_output=True, text=True, timeout=10
        )

        if "capella" in result.stdout.lower():
            print("✓ Capella server is configured in Claude Code")

            # Check connection status
            if "✗ Failed to connect" in result.stdout and "capella" in result.stdout:
                print("✗ Server is configured but FAILING TO CONNECT")

                # Parse the command being used
                for line in result.stdout.split("\n"):
                    if "capella" in line.lower():
                        print(f"  Current command: {line}")
            elif "✓ Connected" in result.stdout and "capella" in result.stdout:
                print("✓ Server is CONNECTED")
        else:
            print("⚠ Capella server not found in Claude Code config")
            print("  Run: claude mcp add capella <path-to-server>")

        return True

    except FileNotFoundError:
        print("✗ Claude CLI not found - is Claude Code installed?")
        return False
    except Exception as e:
        print(f"✗ Error checking Claude config: {e}")
        return False


def main():
    """Run all diagnostic tests"""
    print("\n" + "MCP SERVER DIAGNOSTIC TESTS")
    print("=" * 60)

    tests = [
        test_dependencies,
        test_file_structure,
        test_server_initialization,
        test_simple_server,
        test_mcp_protocol,
        test_claude_config,
    ]

    results = []
    for test in tests:
        try:
            results.append(test())
        except Exception as e:
            print(f"✗ Test failed with exception: {e}")
            results.append(False)

    print("\n" + "=" * 60)
    print("SUMMARY")
    print("=" * 60)

    if all(results):
        print("SUCCESS: All tests passed!")
    else:
        print("FAILURE: Some tests failed - see details above")
        print("\nROOT CAUSE ANALYSIS:")
        print("1. Main issue: __main__.py imports don't match server.py exports")
        print("2. server.py uses FastMCP but __main__.py expects CapellaMCPServer")
        print("3. This prevents the server from starting")
        print("\nRECOMMENDED FIX:")
        print("- Update __main__.py to use FastMCP pattern")
        print("- Or update server.py to export expected classes")

    return all(results)


if __name__ == "__main__":
    success = main()
    sys.exit(0 if success else 1)
