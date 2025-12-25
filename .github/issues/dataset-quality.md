# Improve Dataset Quality Filter (Exclude Logical Errors)

## Issue Details
- **ID**: #128
- **Title**: Filter Dataset Logging to Exclude Scripts with Logical Errors
- **Description**: 
  Currently, `DatasetLogger` logs any script execution where `result.success()` is true. However, a script can "succeed" (run without crashing) while still failing logically (e.g., printing `[ERROR] Target not found`).
  
  Including these "failed" interactions in the fine-tuning dataset will train the model to generate broken scripts.

- **Steps to Reproduce**:
  1. Ask: "Connect 'A' to 'B'." (where A and B do not exist).
  2. LLM Script: `if (a == null) log.error("Not found");`
  3. Execution Result: `Success` (Code ran).
  4. Dataset Log: Contains the prompt and the script that failed to do the job.

- **Expected Result**: 
  The logger should scan the `logs` output for `[ERROR]` or `[WARN]` (configurable) and **drop** the entry if found.

- **Actual Result**: 
  Junk data enters the training set.

- **Severity**: **Minor** (Data quality issue)
- **Priority**: **Medium**
- **Environment**: All

## Linking
- **Related Issues**: #115 (Dataset Logging)
- **Dependencies**: None.

## Acceptance Criteria
- [ ] `Telemetry.recordDatasetEntry` checks the logs/output.
- [ ] If output contains `[ERROR]`, the entry is skipped.
- [ ] Unit test verifies that a successful script with error logs is NOT recorded.
