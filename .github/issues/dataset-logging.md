# Automatic Dataset Logging for Fine-Tuning

## Issue Details
- **ID**: #115 (Proposed)
- **Title**: Implement `DatasetLogger` to capture "Prompt-Code" pairs from successful executions
- **Description**: 
  To eventually fine-tune a specialized "Capella LLM," we need a high-quality dataset of user requests and the correct Groovy scripts that satisfy them. Manually creating this dataset is prohibitively expensive.
  
  We should implement an "Online Data Collection" mechanism that passively logs every **successful** interaction with the Assistant.
  
  **The Core Logic:**
  If `User Prompt` + `Generated Script` results in `ExecutionResult.success`, save the pair to a local `dataset.jsonl` file. This acts as a filter, ensuring we only train on code that actually compiles and runs against the Capella API.

- **Steps to Reproduce (Usage Flow)**:
  1. User asks: "Create a Logical Component named 'Server'."
  2. LLM generates: `api.create("LA", "LogicalComponent", "Server")`.
  3. Code executes successfully.
  4. **New Behavior**: A new line is appended to `~/.capella-ai/dataset.jsonl`.

- **Expected Result**: 
  A structured log file accumulates over time, providing a "Gold Standard" dataset of real-world usage patterns.

- **Actual Result**: 
  Currently, successful interactions are ephemeral and lost after the session ends.

- **Severity**: **Minor** (Feature is invisible to user)
- **Priority**: **High** (Critical for long-term AI strategy)
- **Environment**: All OS. Local file system storage only (Privacy first).

## Linking
- **Related Issues**: 
  - #70 Skill Analytics (Telemetry is about metrics; this is about *content*)
  - #58 Validation Integration (Only log if validation passes?)
- **Dependencies**: 
  - None.

## Acceptance Criteria

### 1. JSONL Logging
- **Specific**: Create `org.polarsys.capella.ai.telemetry.DatasetLogger`.
- **Measurable**: Appends a valid JSON object to `dataset.jsonl` on every success.
- **Format**:
  ```json
  {
    "timestamp": 1700000000,
    "prompt": "Create component X",
    "code": "api.create(...)",
    "execution_time": 150,
    "model": "gpt-4"
  }
  ```

### 2. Success Filter
- **Specific**: Only log if `ExecutionResult.isSuccess()` is true.
- **Measurable**: Failed scripts (compilation errors, runtime exceptions) must **not** be logged. We don't want to train the model on failures.

### 3. Privacy / Opt-Out
- **Specific**: Check a preference (e.g., `CapellaAIPreferences.isLoggingEnabled()`) before writing.
- **Measurable**: Defaults to `true` (or `false` depending on policy), but must be toggleable in UI.

### 4. Anonymization (Basic)
- **Specific**: (Optional for V1) Consider stripping PII or project names, though for a local file this is less critical than for cloud telemetry.

## Implementation Notes
- Hook into `CodeExecutionRouter.execute` method.
- Use `java.nio.file` for efficient appending.
- Ensure file locking handles concurrent writes (though Capella is mostly single-threaded).
