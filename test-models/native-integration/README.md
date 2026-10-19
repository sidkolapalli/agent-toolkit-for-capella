These scripts test the installed plug-in inside a real Capella 7.0 Sirius session. They intentionally require a disposable project whose session URI contains `capella-ai-native-integration`; they never select a different model.

Create the project through Capella's native project wizard with Operational Analysis and EPBS enabled. The equivalent native service is `ProjectSessionCreationHelper(true, true).createFullProject(...)`; it runs `CreateCapellaProjectCmd`, the native model skeleton and viewpoint activation.

Start the plug-in Dev Server, then run this helper from PowerShell 7:

```powershell
./Invoke-NativeIntegration.ps1 -Phases inspect,create-edit,verify
./Invoke-NativeIntegration.ps1 -Phases diagram -DiagramLayer OA -DiagramKind architecture
./Invoke-NativeIntegration.ps1 -Phases diagram -DiagramLayer OA -DiagramKind flow
# Repeat architecture/flow for SA, LA and PA. EPBS supports architecture only.
./Invoke-NativeIntegration.ps1 -Phases diagram -DiagramLayer EPBS
./Invoke-NativeIntegration.ps1 -Phases verify-diagrams
```

Create/edit covers all five perspectives, function and component exchanges, native allocation views, cross-layer realizations, physical behavior/node kinds, configuration item kinds/identifiers and Parts. `verify` checks persisted semantic links, native derived views and stored reference closure. Close and reopen the disposable native session, then run `verify,verify-diagrams` again to check disk persistence.

The SA fixture creates SystemActor elements in SystemComponentPkg. The native System remains a black box; decomposition belongs in LA and PA. For the earlier marked fixture that contained the two SA test components inside the System, `repair-sa` moves only those named components and their existing Parts into the package, sets their actor flags and refreshes the SA test diagrams. Run this repair only when explicitly coordinating that disposable fixture; new fixtures already use the valid structure.

Run `rename`, then use native Capella Undo immediately and check `verify-rename`. Repeat Undo/Redo before making other changes: script execution can itself be an undoable command. `delete` removes functions/components and dependent exchanges/allocations/realizations across the four functional perspectives, plus an EPBS CI and its artifact realization. Use native Undo/Redo to verify the whole deletion batch, then reopen the saved model and run `verify-deleted`.

`delete-diagram` deletes the LA architecture test representation and confirms that its descriptor disappears while semantic components survive. Use it before the semantic deletion phase.

`70-native-lifecycle.groovy` performs native function rename and representation deletion Undo/Redo, then deletes the marked semantic fixture across all five layers, undoes the whole batch, and redoes it. Submit it to `/execute` and keep the disposable model idle until `workspace/native-model-lifecycle-results.json` appears in the configured execution workspace. Run `verify-deleted,verify-diagrams` afterward; obsolete views and their incident notation edges must disappear with semantic deletion. Close and reopen Capella, then repeat those verification phases to check persistence.

Each HTTP response is saved under the helper's report directory. A successful script verifies an operation in the actual installed application. Run `native-validation` before and after fixture creation to compare Capella's own batch validation results, including live constraints. It reports errors and warnings without treating an incomplete test architecture as a finished design. `api.validate()` is also included in the verification report.
