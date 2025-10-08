# Improvement Roadmap for Capella AI Assistant

**Document Purpose**: Concrete, actionable recommendations for the development team to address technical debt and improve product quality.

**Priority System**:
- 🔴 **P0 (Critical)**: Blocks production release, breaks functionality, legal/security risk
- 🟡 **P1 (High)**: Significantly impacts quality, maintainability, or user experience
- 🟢 **P2 (Nice-to-Have)**: Polish, optimization, future-proofing

---

## Phase 1: Critical Fixes (1 Week Sprint)

### 🔴 P0-1: Fix Plugin Integration Issues
**File**: `plugins/ai-capella/plugin.xml` ✅ FIXED
**File**: `plugins/ai-capella/META-INF/MANIFEST.MF`

**What**: The typo in plugin.xml has been corrected. Now add version constraints to prevent compatibility issues.

**Action Items**:
```xml
<!-- META-INF/MANIFEST.MF -->
Require-Bundle: org.eclipse.ui;bundle-version="[3.0.0,4.0.0)",
 org.eclipse.core.runtime;bundle-version="[3.0.0,4.0.0)",
 org.eclipse.sirius;bundle-version="[7.0.0,8.0.0)",
 org.eclipse.sirius.diagram;bundle-version="[7.0.0,8.0.0)",
 org.polarsys.capella.core.data.gen;bundle-version="[7.0.0,8.0.0)",
 org.eclipse.emf.transaction;bundle-version="[1.0.0,2.0.0)",
 org.eclipse.equinox.security;bundle-version="[1.0.0,2.0.0)"
```

**Test**:
```bash
mvn -f plugins clean verify
# Verify build succeeds with version constraints
# Test installation in Capella 7.0.1 and 7.0.2 (if available)
```

**Estimated Effort**: 2 hours (add constraints, test builds)
**Owner**: Build/Release Engineer
**Success Metric**: Plugin builds successfully, manifest validation passes

---

### 🔴 P0-2: Create Icons or Remove References
**File**: `plugins/ai-capella/plugin.xml`

**What**: Plugin references `icons/chat.png` but directory doesn't exist, causing view registration warnings.

**Option A - Add Icons** (Recommended):
```bash
# Create icons directory
mkdir -p plugins/ai-capella/icons

# Add placeholder 16x16 PNG icons (get from designer or use Eclipse defaults)
# - chat.png (for ChatView)
# - diagrams.png (for DiagramsView, optional)
```

Update `build.properties`:
```properties
bin.includes = plugin.xml,\
               META-INF/,\
               icons/,\
               .
```

**Option B - Remove Icon References**:
```xml
<!-- Remove icon attributes if no icons available -->
<view
    allowMultiple="false"
    category="org.polarsys.capella.ai"
    class="org.polarsys.capella.ai.ChatView"
    id="org.polarsys.capella.ai.chatview"
    name="Capella AI Assistant"/>
```

**Test**: Install plugin, verify views appear in Show View menu without warnings.

**Estimated Effort**: 1 hour (Option B) or 4 hours (Option A with icon design)
**Owner**: UI Developer
**Success Metric**: No Eclipse log warnings about missing icons

---

### 🔴 P0-3: Fix Unsafe Transaction Access in DiagramCommands
**File**: `plugins/ai-capella/src/org/polarsys/capella/ai/tools/DiagramCommands.java`

**What**: Direct command execution bypasses `SessionSafeguards`, risking thread safety violations.

**Current Code** (lines 114, 148):
```java
domain.getCommandStack().execute((org.eclipse.emf.common.command.Command) cmd);
```

**Fixed Code**:
```java
// In createNode() method (around line 113)
if (cmd != null) {
    SessionSafeguards.runWrite(session, "Create diagram node", () -> {
        domain.getCommandStack().execute((org.eclipse.emf.common.command.Command) cmd);
        return null;
    });
    DialectManager.INSTANCE.refresh(rep, new NullProgressMonitor());
    return true;
}

// In createEdge() method (around line 147)
if (cmd != null) {
    SessionSafeguards.runWrite(session, "Create diagram edge", () -> {
        domain.getCommandStack().execute((org.eclipse.emf.common.command.Command) cmd);
        return null;
    });
    DialectManager.INSTANCE.refresh(rep, new NullProgressMonitor());
    return true;
}
```

**Test**:
1. Create diagram via AI assistant
2. Add nodes/edges using diagram templates
3. Verify undo/redo works correctly
4. Test from background thread (simulate async plan execution)

**Estimated Effort**: 2 hours (fix + testing)
**Owner**: Core Developer
**Success Metric**: Diagram operations work correctly, undo/redo reliable

---

### 🟡 P1-1: Add Basic Accessibility Support
**File**: `plugins/ai-capella/src/org/polarsys/capella/ai/ChatView.java`
**File**: `plugins/ai-capella/src/org/polarsys/capella/ai/ui/PlanEditorDialog.java`

**What**: Add keyboard navigation and non-color-only visual cues for accessibility compliance.

**Action Items**:

**1. Add Mnemonics to Common Buttons**:
```java
// ChatView.java - around line 232
sendButton.setText("&Send");  // Alt+S

// Around line 277
explainModel.setText("&Explain model");  // Alt+E

// Around line 221
commandsButton.setText("&Commands");  // Alt+C
```

**2. Add Keyboard Shortcuts**:
```java
// In createConversationSection() after prompt field creation
prompt.addKeyListener(new KeyAdapter() {
    @Override
    public void keyPressed(KeyEvent e) {
        if ((e.stateMask & SWT.MOD1) != 0 && e.keyCode == 'e') {
            onExplainModel();
            e.doit = false;
        }
        // Existing Enter and Ctrl+P handlers...
    }
});
```

**3. Add Symbols to Diff Preview (not just colors)**:
```java
// PlanEditorDialog.java - around line 329
String prefix = switch(category) {
    case CREATE -> "[+]";   // Green + symbol
    case UPDATE -> "[~]";   // Blue ~ symbol
    case DELETE -> "[-]";   // Red - symbol
    default -> "[ ]";
};
appendStyledLine(buffer, styles, " " + prefix + " " + item.getDiffSummary(), colors.primary);
```

**4. Add Accessible Labels**:
```java
// ChatView.java - context cards
contextSummary.setData("name", "Model Context Summary");  // Screen reader label
chatLog.setData("name", "Conversation History");
```

**Test**:
- Tab through interface, verify logical focus order
- Use screen reader (NVDA/JAWS on Windows, VoiceOver on Mac)
- Test with high-contrast themes
- Verify symbols appear in diff preview

**Estimated Effort**: 6 hours
**Owner**: UI Developer
**Success Metric**: WCAG Level A compliance, keyboard-navigable

---

## Phase 2: Code Quality & Maintainability (2 Week Sprint)

### 🟡 P1-2: Refactor ChatView God Class
**File**: `plugins/ai-capella/src/org/polarsys/capella/ai/ChatView.java` (2,608 lines)

**What**: Split monolithic view into focused, testable components.

**Target Architecture**:
```
ChatView.java (300 lines)
├── ConversationController.java (400 lines)
│   ├── handleSend()
│   ├── handleStreaming()
│   ├── updateChatLog()
│   └── manageHistory()
├── TemplateActionManager.java (600 lines)
│   ├── OperationalTemplates
│   ├── SystemTemplates
│   ├── FunctionalChainTemplates
│   └── RequirementTemplates
├── ModelToolsController.java (300 lines)
│   ├── DiagramOperations
│   ├── SearchOperations
│   └── ImportOperations
└── SettingsPanel.java (200 lines)
    ├── LLM Configuration UI
    └── Preference Binding
```

**Step-by-Step Refactoring**:

**Step 1: Extract TemplateActionManager** (Day 1-2)
```java
// New file: TemplateActionManager.java
public class TemplateActionManager {
    private final Session session;
    private final Consumer<String> chatLogger;

    public TemplateActionManager(Session session, Consumer<String> chatLogger) {
        this.session = session;
        this.chatLogger = chatLogger;
    }

    public void createOperationalMission() {
        // Move onCreateMission() logic here
    }

    public void createSystemComponent() {
        // Move onCreateSystemComponent() logic here
    }

    // ... all template methods
}

// In ChatView.java, replace direct implementations:
private TemplateActionManager templateManager;

@Override
public void createPartControl(Composite parent) {
    templateManager = new TemplateActionManager(
        SessionBridge::getActiveSession,
        this::appendToChatLog
    );
    // ...
}

// Replace button handlers:
createMission.addSelectionListener(e -> templateManager.createOperationalMission());
```

**Step 2: Extract ConversationController** (Day 3-4)
```java
// New file: ConversationController.java
public class ConversationController {
    private final List<LLMService.Message> history = new ArrayList<>();
    private final Text chatLog;
    private final Text promptField;
    private volatile ToolRouter.ToolStream activeStream;

    public ConversationController(Text chatLog, Text promptField) {
        this.chatLog = chatLog;
        this.promptField = promptField;
    }

    public void sendMessage(String message) {
        // Move onSend() logic here
    }

    public void cancelStreaming() {
        // Move streaming cancellation logic here
    }

    // ... conversation management
}
```

**Step 3: Extract ModelToolsController** (Day 5-6)
```java
// New file: ModelToolsController.java
public class ModelToolsController {
    public void listDiagrams() { /* ... */ }
    public void createLAB(EObject target) { /* ... */ }
    public void importDrawio(File file) { /* ... */ }
    public void searchModel(String query) { /* ... */ }
}
```

**Step 4: Extract SettingsPanel** (Day 7)
```java
// New file: SettingsPanel.java
public class SettingsPanel {
    public Composite createSettingsUI(Composite parent) {
        // Move createSettingsSection() logic here
        // Move all preference binding logic here
    }

    public void saveSettings() { /* ... */ }
    public void loadSettings() { /* ... */ }
}
```

**Step 5: Simplify ChatView** (Day 8-9)
```java
// Refactored ChatView.java (~300 lines)
public class ChatView extends ViewPart {
    private ConversationController conversation;
    private TemplateActionManager templates;
    private ModelToolsController tools;
    private SettingsPanel settings;

    @Override
    public void createPartControl(Composite parent) {
        // Create UI sections
        Composite conversationArea = createConversationArea(parent);
        Composite templateArea = createTemplateArea(parent);
        Composite toolsArea = createToolsArea(parent);
        Composite settingsArea = createSettingsArea(parent);

        // Initialize controllers
        conversation = new ConversationController(chatLog, prompt);
        templates = new TemplateActionManager(SessionBridge::getActiveSession, conversation::log);
        tools = new ModelToolsController();
        settings = new SettingsPanel();

        // Wire up event handlers
        sendButton.addSelectionListener(e -> conversation.sendMessage(prompt.getText()));
        // ... minimal wiring code
    }
}
```

**Test Strategy**:
1. Write unit tests for each extracted class **before** extraction
2. Use test doubles (mocks) for SWT widgets during testing
3. Integration test: ensure full workflow still works
4. Verify no regressions in UI behavior

**Estimated Effort**: 10 days (8 dev + 2 testing)
**Owner**: Senior Developer + Code Reviewer
**Success Metric**:
- ChatView.java < 500 lines
- 80%+ test coverage on controllers
- All existing features work identically

---

### 🟡 P1-3: Replace Reflection-Based Sirius API Calls
**File**: `plugins/ai-capella/src/org/polarsys/capella/ai/tools/DiagramCommands.java`

**What**: Remove brittle `Class.forName()` calls, use official Sirius APIs or declare dependency.

**Current Problem** (lines 101-161):
```java
Class<?> cFactory = Class.forName("org.eclipse.sirius.diagram.ui.tools.api.command.CreateNodeCommand");
// ... reflection magic
```

**Option A - Use Official DialectManager APIs** (Recommended):
```java
public static boolean createNode(Session session, DRepresentation rep, EObject semantic) {
    if (!(rep instanceof DDiagram)) return false;
    DDiagram diagram = (DDiagram) rep;

    return SessionSafeguards.runWrite(session, "Add element to diagram", () -> {
        // Use Sirius refresh to auto-create nodes for semantic elements
        diagram.getActivatedFilters().clear();  // Temporarily disable filters
        DialectManager.INSTANCE.refresh(rep, true, new NullProgressMonitor());

        // Alternative: Use DDiagramEditor API if available
        // This requires org.eclipse.sirius.diagram.ui dependency
        return true;
    });
}
```

**Option B - Declare Dependency on Sirius UI** (If reflection is necessary):
```xml
<!-- META-INF/MANIFEST.MF -->
Require-Bundle: ...existing...,
 org.eclipse.sirius.diagram.ui;bundle-version="[7.0.0,8.0.0)";resolution:=optional
```

Then use direct imports instead of reflection:
```java
import org.eclipse.sirius.diagram.ui.tools.api.command.CreateNodeCommand;

public static boolean createNode(Session session, DRepresentation rep, EObject semantic) {
    // Direct instantiation instead of reflection
    CreateNodeCommand cmd = new CreateNodeCommand(domain, dd, semantic, 100, 100, 0, 0);
    return SessionSafeguards.runWrite(session, "Create node", () -> {
        domain.getCommandStack().execute(cmd);
        return true;
    });
}
```

**Recommendation**: Try **Option A first** (DialectManager refresh). It's more stable across versions. Only use Option B if diagram layout control is critical.

**Test**:
1. Create diagrams with auto-layout
2. Add nodes via AI templates
3. Verify nodes appear correctly
4. Test across Sirius 7.4.x versions

**Estimated Effort**: 8 hours (research + implementation + testing)
**Owner**: Sirius Expert / Senior Developer
**Success Metric**: No reflection usage, works across Sirius 7.4.x versions

---

### 🟡 P1-4: Improve Error Logging and User Feedback
**Files**: Multiple

**What**: Replace silent exception swallowing with proper logging and user-facing error messages.

**Action Items**:

**1. Add Logging to SessionBridge** (lines 52-54):
```java
// SessionBridge.java
private static final Logger LOG = Logger.getLogger(SessionBridge.class.getName());

public static Session getActiveSession() {
    try {
        // ... existing logic ...
    } catch (Exception e) {
        LOG.log(Level.WARNING, "Failed to acquire active Capella session", e);
        // Optionally show user-friendly message
        return null;
    }
}
```

**2. Add Progress Monitors for Long Operations**:
```java
// ChatView.java - around onExplainModel()
private void onExplainModel() {
    Job job = new Job("Analyzing Capella model") {
        @Override
        protected IStatus run(IProgressMonitor monitor) {
            try {
                monitor.beginTask("Generating model summary", 100);
                Session session = SessionBridge.getActiveSession();
                if (session == null) {
                    return new Status(IStatus.ERROR, Activator.PLUGIN_ID,
                        "No active Capella session. Please open a model first.");
                }

                monitor.worked(30);
                String summary = ModelSummary.forSession(session);

                monitor.worked(70);
                Display.getDefault().asyncExec(() -> {
                    appendToChatLog("Model Summary:\n" + summary);
                });

                return Status.OK_STATUS;
            } catch (Exception e) {
                LOG.log(Level.SEVERE, "Model summary failed", e);
                return new Status(IStatus.ERROR, Activator.PLUGIN_ID,
                    "Failed to analyze model: " + e.getMessage(), e);
            } finally {
                monitor.done();
            }
        }
    };
    job.setUser(true);  // Shows progress dialog
    job.schedule();
}
```

**3. Add Empty State Messages**:
```java
// ChatView.java - in createConversationSection()
private void updateChatLogPlaceholder() {
    if (chatLog.getText().isEmpty()) {
        chatLog.setText(
            "Welcome to Capella AI Assistant!\n\n" +
            "Getting started:\n" +
            "• Type a message and press Send\n" +
            "• Press / for quick commands\n" +
            "• Press Ctrl+P (Cmd+P on Mac) for command palette\n" +
            "• Click 'Explain model' to analyze your Capella project\n\n" +
            "Example commands:\n" +
            "• /arcadia-next - Get methodology guidance\n" +
            "• /explain-selection - Analyze selected element\n" +
            "• /scaffold-component - Create logical component\n"
        );
    }
}

// Call in createPartControl() and when clearing history
```

**Estimated Effort**: 6 hours
**Owner**: All Developers (apply pattern across codebase)
**Success Metric**: No silent failures, user sees progress for >2 second operations

---

## Phase 3: UI/UX Improvements (2 Week Sprint)

### 🟢 P2-1: Redesign Main View Layout
**File**: `plugins/ai-capella/src/org/polarsys/capella/ai/ChatView.java`

**What**: Reduce clutter using tabbed interface or collapsible sections.

**Option A - Tabbed Interface** (Recommended):
```java
@Override
public void createPartControl(Composite parent) {
    TabFolder tabFolder = new TabFolder(parent, SWT.NONE);
    tabFolder.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

    // Tab 1: Conversation (primary)
    TabItem chatTab = new TabItem(tabFolder, SWT.NONE);
    chatTab.setText("Chat");
    chatTab.setControl(createConversationPanel(tabFolder));

    // Tab 2: Templates (power features)
    TabItem templatesTab = new TabItem(tabFolder, SWT.NONE);
    templatesTab.setText("Templates");
    templatesTab.setControl(createTemplatesPanel(tabFolder));

    // Tab 3: Tools (advanced)
    TabItem toolsTab = new TabItem(tabFolder, SWT.NONE);
    toolsTab.setText("Tools");
    toolsTab.setControl(createToolsPanel(tabFolder));

    // Tab 4: Settings
    TabItem settingsTab = new TabItem(tabFolder, SWT.NONE);
    settingsTab.setText("Settings");
    settingsTab.setControl(createSettingsPanel(tabFolder));
}
```

**Option B - Collapsible Sections** (ExpandBar):
```java
@Override
public void createPartControl(Composite parent) {
    ScrolledComposite scroller = new ScrolledComposite(parent, SWT.V_SCROLL);
    scroller.setExpandHorizontal(true);
    scroller.setExpandVertical(true);

    Composite content = new Composite(scroller, SWT.NONE);
    content.setLayout(new GridLayout(1, false));

    // Conversation always visible
    createConversationSection(content);

    // Collapsible template sections
    ExpandBar expandBar = new ExpandBar(content, SWT.V_SCROLL);
    expandBar.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

    ExpandItem operational = new ExpandItem(expandBar, SWT.NONE);
    operational.setText("Operational Templates");
    operational.setControl(createOperationalTemplates(expandBar));
    operational.setExpanded(false);  // Collapsed by default

    // Repeat for System, Functional Chain, Requirements, etc.
}
```

**Design Mockup** (Tabbed Interface):
```
┌─────────────────────────────────────────┐
│ Chat | Templates | Tools | Settings     │
├─────────────────────────────────────────┤
│ [Connected to gpt-4o • Actions enabled] │
│                                          │
│ ┌────────────────────────────────────┐ │
│ │ Conversation History               │ │
│ │                                     │ │
│ │ User: Create a mission              │ │
│ │ AI: Created mission "M1"            │ │
│ │                                     │ │
│ └────────────────────────────────────┘ │
│                                          │
│ ┌────────────────────────────────────┐ │
│ │ Context Cards                       │ │
│ │ Selected: Mission M1 (Operational)  │ │
│ │ Model: 234 elements, OA complete    │ │
│ └────────────────────────────────────┘ │
│                                          │
│ [ Type message... ] [Commands] [Send]   │
└─────────────────────────────────────────┘
```

**Test**: User study with 5 engineers, time-to-task for common operations.

**Estimated Effort**: 12 hours (UI restructuring + testing)
**Owner**: UI/UX Developer
**Success Metric**: 30% reduction in scrolling, improved task completion time

---

### 🟢 P2-2: Remove Duplicate LLM Settings from View
**File**: `plugins/ai-capella/src/org/polarsys/capella/ai/ChatView.java`

**What**: Settings UI already exists in `LLMPreferencePage`. Remove duplication from main view.

**Action**:
1. Delete `createSettingsSection()` method (lines ~680-780)
2. Add "Open Preferences" button instead:
```java
Group settingsGroup = new Group(parent, SWT.NONE);
settingsGroup.setText("LLM Configuration");
settingsGroup.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
settingsGroup.setLayout(new GridLayout(2, false));

connectionSummary = new Label(settingsGroup, SWT.WRAP);
connectionSummary.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
connectionSummary.setText("Provider: OpenAI • Model: gpt-4o");

Button openPreferences = new Button(settingsGroup, SWT.PUSH);
openPreferences.setText("Configure...");
openPreferences.addSelectionListener(e -> {
    PreferenceDialog dialog = PreferencesUtil.createPreferenceDialogOn(
        getSite().getShell(),
        "org.polarsys.capella.ai.preferences.llm",
        null, null);
    if (dialog != null) {
        dialog.open();
        updateConnectionSummary();  // Refresh after settings change
    }
});
```

**Estimated Effort**: 2 hours
**Owner**: UI Developer
**Success Metric**: Single source of truth for LLM settings

---

### 🟢 P2-3: Improve Visual Design
**Files**: Multiple UI files

**What**: Consistent spacing, theme-aware colors, proper icon usage.

**Action Items**:

**1. Define UI Constants**:
```java
// New file: UIConstants.java
public final class UIConstants {
    // Spacing
    public static final int SPACING_SMALL = 4;
    public static final int SPACING_MEDIUM = 8;
    public static final int SPACING_LARGE = 16;

    // Layout hints (use ratios, not fixed pixels)
    public static final int CHAT_LOG_HEIGHT_RATIO = 60;  // % of available height
    public static final int CONTEXT_HEIGHT_RATIO = 30;

    // Colors (use theme-aware approach)
    public static Color getCreateColor(Display display) {
        return display.getSystemColor(
            isLightTheme() ? SWT.COLOR_DARK_GREEN : SWT.COLOR_GREEN
        );
    }

    private static boolean isLightTheme() {
        Display display = Display.getCurrent();
        Color bg = display.getSystemColor(SWT.COLOR_WIDGET_BACKGROUND);
        return (bg.getRed() + bg.getGreen() + bg.getBlue()) > 384;  // Rough luminance
    }
}
```

**2. Apply Consistent Spacing**:
```java
// Replace magic numbers with constants
GridLayout layout = new GridLayout(1, false);
layout.marginHeight = UIConstants.SPACING_MEDIUM;
layout.marginWidth = UIConstants.SPACING_MEDIUM;
layout.verticalSpacing = UIConstants.SPACING_SMALL;
```

**3. Use Relative Heights**:
```java
// Instead of: logData.heightHint = 280;
GridData logData = new GridData(SWT.FILL, SWT.FILL, true, true);
logData.heightHint = SWT.DEFAULT;  // Let layout manager decide
logData.minimumHeight = 200;       // Set minimum only
```

**4. Add Icons to Buttons**:
```java
// Use Eclipse/Capella icon sets
Image sendIcon = PlatformUI.getWorkbench()
    .getSharedImages()
    .getImage(ISharedImages.IMG_TOOL_FORWARD);
sendButton.setImage(sendIcon);
```

**Estimated Effort**: 8 hours
**Owner**: UI Developer
**Success Metric**: Consistent look across light/dark themes, no hardcoded dimensions

---

## Phase 4: Testing & Quality Assurance (Ongoing)

### 🟡 P1-5: Establish Automated Test Suite
**New Files**: `plugins/ai-capella.tests/`

**What**: Add JUnit tests for core business logic, eventually SWTBot for UI.

**Step 1: Create Test Fragment** (Day 1)
```xml
<!-- plugins/ai-capella.tests/META-INF/MANIFEST.MF -->
Manifest-Version: 1.0
Bundle-ManifestVersion: 2
Bundle-Name: Capella AI Tests
Bundle-SymbolicName: org.polarsys.capella.ai.tests
Bundle-Version: 0.1.0.qualifier
Fragment-Host: org.polarsys.capella.ai
Require-Bundle: org.junit;bundle-version="4.13.0",
 org.mockito;bundle-version="4.0.0"
```

**Step 2: Write Core Logic Tests** (Week 1)
```java
// SessionSafeguardsTest.java
public class SessionSafeguardsTest {
    @Test
    public void testRunWrite_executesOnUIThread() {
        Session mockSession = mock(Session.class);
        TransactionalEditingDomain domain = mock(TransactionalEditingDomain.class);
        CommandStack stack = mock(CommandStack.class);

        when(mockSession.getTransactionalEditingDomain()).thenReturn(domain);
        when(domain.getCommandStack()).thenReturn(stack);

        AtomicBoolean executed = new AtomicBoolean(false);
        SessionSafeguards.runWrite(mockSession, "test", () -> {
            executed.set(true);
            return null;
        });

        assertTrue("Write operation should execute", executed.get());
        verify(stack).execute(any(RecordingCommand.class));
    }

    @Test
    public void testDeferSaves_batches() {
        // Test that multiple nested operations defer saves
    }
}

// ToolRouterTest.java
public class ToolRouterTest {
    @Test
    public void testParsePlan_validJson() throws Exception {
        String json = "{\"tool\":\"TOOL_PLAN\",\"actions\":[{\"type\":\"CREATE_MISSION\",\"name\":\"M1\"}]}";
        ToolPlan plan = ToolRouter.parsePlan(json);
        assertEquals(1, plan.size());
        assertEquals("CREATE_MISSION", plan.get(0).getTypeId());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testParsePlan_malformedJson() {
        ToolRouter.parsePlan("not json");
    }
}

// ModelOpsTest.java
public class ModelOpsTest {
    @Test
    public void testRenameByUuid_updatesNameFeature() {
        // Mock EMF resource set
        // Test rename operation
        // Verify name changed, session saved
    }
}
```

**Step 3: Add Integration Tests** (Week 2)
```java
// PlanExecutionIntegrationTest.java
@RunWith(SWTBotJunit4ClassRunner.class)
public class PlanExecutionIntegrationTest {
    private static SWTWorkbenchBot bot;

    @BeforeClass
    public static void setup() {
        bot = new SWTWorkbenchBot();
    }

    @Test
    public void testCreateMission_viaUI() {
        // Open ChatView
        bot.viewByTitle("Capella AI Assistant").show();

        // Click Create Mission template
        bot.button("Create mission").click();

        // Fill dialog
        bot.textWithLabel("Mission name").setText("Test Mission");
        bot.button("OK").click();

        // Verify plan preview appears
        // Verify execution succeeds
    }
}
```

**Test Coverage Goals**:
- **Phase 1**: Core business logic (SessionSafeguards, ToolRouter, ModelOps) - 80%
- **Phase 2**: Action classes (Operational, System, etc.) - 60%
- **Phase 3**: UI controllers (after refactoring) - 40%
- **Phase 4**: E2E workflows (SWTBot) - 20%

**Estimated Effort**:
- Week 1: Test infrastructure + core tests (20 hours)
- Week 2-4: Action tests + UI tests (40 hours)
- Ongoing: Maintain tests with features (2 hours/feature)

**Owner**: QA Engineer + All Developers (write tests for own code)
**Success Metric**: 60% overall coverage, CI builds include tests

---

### 🟢 P2-4: Pin Target Platform Versions
**File**: `releng/capella-target/capella.target`

**What**: Replace `version="0.0.0"` with specific versions for reproducible builds.

**Action**:
```xml
<!-- Replace line 131-137 -->
<unit id="org.polarsys.capella.rcp.feature.group" version="7.0.1.202503211540"/>
<unit id="org.polarsys.capella.core.feature.feature.group" version="7.0.1.202503211540"/>
<unit id="org.polarsys.capella.core.ui.feature.feature.group" version="7.0.1.202503211540"/>
<unit id="org.polarsys.capella.core.common.ui.feature.feature.group" version="7.0.1.202503211540"/>
<unit id="org.polarsys.capella.common.ui.feature.feature.group" version="7.0.1.202503211540"/>
<unit id="org.polarsys.capella.core.data.gen" version="7.0.1"/>
```

**Test**: Clean build from scratch, verify same artifacts produced.

**Estimated Effort**: 1 hour
**Owner**: Build Engineer
**Success Metric**: Reproducible builds across developer machines

---

## Phase 5: Documentation & Release Prep (1 Week Sprint)

### 🟡 P1-6: User Documentation
**New Files**: `docs/user-guide/`, `docs/videos/`

**What**: Help users get started and be productive quickly.

**Deliverables**:

**1. Getting Started Guide** (`docs/user-guide/getting-started.md`):
```markdown
# Getting Started with Capella AI Assistant

## Installation
1. Download update site from `plugins/ai-capella-repository/target/repository`
2. In Capella: Help → Install New Software → Add → Local
3. Select repository folder, install feature
4. Restart Capella

## Configuration
1. Open Preferences: Capella → Preferences → Capella AI → LLM
2. Choose provider (OpenAI, Anthropic, Azure, or Custom)
3. Enter API key (stored securely in Eclipse keyring)
4. Test connection

## Your First AI Interaction
1. Open a Capella model
2. Open view: Window → Show View → Other → Capella AI Assistant
3. Type: `/arcadia-next`
4. Review suggestions and click "Send"

## Quick Commands
- `/arcadia-next` - Get methodology guidance
- `/explain-selection` - Analyze selected element
- `/scaffold-component` - Create logical component template
```

**2. Video Tutorials** (3-5 minutes each):
- Installation & Setup
- Creating Operational Architecture with AI
- Using Templates for System Design
- Plan Review & Execution
- Troubleshooting Common Issues

**3. API Documentation** (if exposing for extensions):
```java
/**
 * Core API for programmatic access to Capella AI features.
 *
 * Example usage:
 * <pre>
 * Session session = SessionBridge.getActiveSession();
 * ToolPlan plan = new ToolPlan();
 * plan.add(new CreateMissionAction("M1", "Mission description"));
 * PlanExecutionResult result = ToolRouter.executePlan(session, plan);
 * </pre>
 *
 * @since 0.2.0
 */
```

**Estimated Effort**: 20 hours (writing + video production)
**Owner**: Technical Writer + Product Owner
**Success Metric**: <10 minute onboarding for new users

---

## Metrics & Success Criteria

### Code Quality Metrics
| Metric | Current | Target (3 months) |
|--------|---------|-------------------|
| Largest class size | 2,608 lines | <500 lines |
| Test coverage | 0% | 60% |
| Critical integration issues | 3 | 0 |
| High-priority issues | 3 | 0 |
| Accessibility compliance | WCAG F | WCAG Level A |
| Build reproducibility | No | Yes |

### User Experience Metrics
| Metric | Target |
|--------|--------|
| Time to first successful AI interaction | <5 minutes |
| Task completion rate (create mission) | >90% |
| User satisfaction (1-10 scale) | >7.5 |
| Support tickets per 100 users | <5 |

### Development Velocity Metrics
| Metric | Target |
|--------|--------|
| Time to add new template | <4 hours (vs ~8 currently) |
| Bug fix cycle time | <2 days |
| Code review time | <1 day |

---

## Timeline Summary

| Phase | Duration | Key Deliverables |
|-------|----------|------------------|
| **Phase 1: Critical Fixes** | 1 week | Integration bugs fixed, basic accessibility |
| **Phase 2: Code Quality** | 2 weeks | ChatView refactored, reflection removed, logging improved |
| **Phase 3: UI/UX** | 2 weeks | Tabbed interface, visual polish, settings cleanup |
| **Phase 4: Testing** | Ongoing | 60% test coverage, CI integration |
| **Phase 5: Documentation** | 1 week | User guide, videos, API docs |

**Total Timeline**: 6 weeks core work + ongoing testing

---

## Resource Requirements

### Team Composition
- **1 Senior Developer** (lead refactoring, architecture)
- **2 Mid-level Developers** (implement fixes, write tests)
- **1 UI/UX Developer** (redesign interface, accessibility)
- **1 QA Engineer** (test automation, integration testing)
- **1 Technical Writer** (documentation, videos)
- **1 Product Owner** (prioritization, user feedback)

### Tools & Infrastructure
- Eclipse/Tycho build environment
- JUnit + Mockito for unit tests
- SWTBot for UI testing
- CI/CD pipeline (Jenkins/GitHub Actions)
- Code coverage tool (JaCoCo)
- Static analysis (SonarQube)

---

## Risk Mitigation

| Risk | Impact | Mitigation |
|------|--------|------------|
| Refactoring breaks existing features | High | Comprehensive tests before refactoring |
| Sirius API changes break diagram ops | Medium | Pin Sirius version, use official APIs |
| User resistance to UI changes | Medium | A/B testing, gradual rollout |
| Test writing slows feature dev | Low | Pair programming, test templates |
| Limited accessibility expertise | Medium | Hire consultant, use automated tools |

---

## Next Steps

**Week 1 Actions**:
1. ✅ Review this roadmap with team
2. ⬜ Set up test infrastructure (JUnit + Mockito)
3. ⬜ Fix critical integration issues (P0-1, P0-2, P0-3)
4. ⬜ Begin ChatView refactoring analysis
5. ⬜ Schedule user feedback sessions

**Immediate Owner Assignments**:
- **Build Engineer**: P0-1 (version constraints), P2-4 (target platform)
- **UI Developer**: P0-2 (icons), P1-1 (accessibility), P2-2 (settings cleanup)
- **Core Developer**: P0-3 (transaction safety), P1-3 (reflection removal)
- **Senior Developer**: P1-2 (ChatView refactoring lead)
- **QA Engineer**: P1-5 (test framework setup)

**Communication Plan**:
- Daily standups during Phase 1 (critical fixes)
- Weekly progress reviews
- Bi-weekly demos to stakeholders
- Monthly retrospectives

---

## Appendix: Code Review Checklist

Use this checklist for all pull requests:

### Functionality
- [ ] Feature works as specified
- [ ] No regressions in existing features
- [ ] Error cases handled gracefully

### Code Quality
- [ ] No classes >500 lines (refactor if needed)
- [ ] No methods >50 lines (extract if needed)
- [ ] No magic numbers (use named constants)
- [ ] No silent exception swallowing
- [ ] Proper logging with appropriate levels

### Testing
- [ ] Unit tests added for new logic
- [ ] Test coverage >60% for changed files
- [ ] Integration test added if applicable
- [ ] Manual testing performed

### UI/UX
- [ ] Keyboard navigation works
- [ ] High-contrast theme tested
- [ ] No hardcoded dimensions
- [ ] Progress indicators for >2s operations
- [ ] Error messages user-friendly

### Eclipse/EMF
- [ ] All model access wrapped in SessionSafeguards
- [ ] No direct transaction manipulation
- [ ] Resources disposed in dispose() methods
- [ ] Background jobs use proper progress monitors

### Documentation
- [ ] Public APIs have Javadoc
- [ ] User-facing changes documented
- [ ] CHANGELOG.md updated

---

**Document Version**: 1.0
**Last Updated**: 2025-10-04
**Next Review**: After Phase 1 completion
