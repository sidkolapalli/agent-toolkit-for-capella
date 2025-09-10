# Claude Code Setup for Capella MCP Server

## Installation

1. **Install the package:**
   ```bash
   cd capella-mcp-server
   uv pip install -e .
   ```

2. **Test the installation:**
   ```bash
   uv run capella-mcp-server
   # Should wait for input (Ctrl+C to exit)
   ```

## Claude Code Configuration

Add this to your Claude Code configuration:

### Option 1: Using uv (Recommended)
```json
{
  "mcpServers": {
    "capella": {
      "command": "uv",
      "args": ["run", "capella-mcp-server"],
      "cwd": "<local-path>",
      "env": {
        "CAPELLA_MODEL_PATH": "<local-path>"
      }
    }
  }
}
```

### Option 2: Using Python directly
```json
{
  "mcpServers": {
    "capella": {
      "command": "python",
      "args": ["-m", "capella_mcp"],
      "cwd": "<local-path>",
      "env": {
        "CAPELLA_MODEL_PATH": "<local-path>"
      }
    }
  }
}
```

## Available Tools

Once configured, Claude Code will have access to these tools:

- **load_model(path)** - Load a Capella model from a .aird file
- **list_models()** - List all loaded models
- **query_model(model_id, query)** - Query information from a loaded model
- **analyze_architecture(model_id)** - Analyze model architecture
- **get_components(model_id, type)** - Get components by type
- **get_functions(model_id, type)** - Get functions by type
- **get_interfaces(model_id)** - Get model interfaces
- **validate_model(model_id)** - Validate model consistency

## Environment Variables

- **CAPELLA_MODEL_PATH** - Path to auto-load a model on startup (optional)

## Troubleshooting

1. **Server doesn't start:**
   - Ensure uv is installed: `pip install uv`
   - Check the package is installed: `uv pip list | grep capella`
   - Test manually: `uv run python -m capella_mcp`

2. **Model loading fails:**
   - Verify the .aird file path exists
   - Ensure capellambse is installed: `uv pip install capellambse`

3. **Claude Code doesn't see the server:**
   - Restart Claude Code after configuration changes
   - Check the server logs in Claude Code's output panel
   - Verify the `cwd` path in configuration matches your installation