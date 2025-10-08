# UI Redesign Plan: Conversational Model Builder

**Vision**: Transform the Capella AI Assistant into a conversational interface where talking to the AI feels natural, and model building happens seamlessly through dialogue.

**Guiding Principle**: "It should feel like I'm talking to you in that UI and it builds the models with utmost detail."

---

## Design Philosophy

### Core Metaphor: **Conversational AI Partner**

Not a tool with buttons. Not a template library. A **conversation-first interface** where:
- The chat is primary, everything else is secondary
- Templates become suggestions, not manual buttons
- The AI proactively guides you through Arcadia methodology
- Model building happens through natural dialogue
- Visual feedback confirms what's being built

**Reference**: ChatGPT, GitHub Copilot Chat, but **specialized for systems engineering**

---

## Current Problems

### Problem 1: Identity Crisis
Current UI tries to be:
- Chat interface (ChatGPT-like)
- Template library (form builder)
- Model manipulation tool (property editor)
- Settings panel (configuration UI)

**Result**: User doesn't know where to look or what to do.

### Problem 2: Button Overload
29+ buttons demand attention, creating **decision paralysis**:
- "Should I click 'Create Mission' or type in chat?"
- "What's the difference between these buttons and commands?"
- "Why are there templates AND a chat interface?"

### Problem 3: Hidden Intelligence
The AI's power is buried under UI chrome:
- Methodology coach exists but is passive
- Context cards exist but feel separate
- Command palette exists but users won't discover it
- Plan editor is excellent but comes late in flow

---

## Redesigned Experience

### Primary Workflow: Pure Conversation

```
┌─────────────────────────────────────────────────────────────┐
│  Capella AI Assistant                          [Settings ⚙] │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  👋 Welcome back! You're working on the Vehicle System      │
│  model. The Operational Analysis is complete, and you       │
│  have 3 missions defined.                                   │
│                                                              │
│  💡 Suggested next step: Define System Functions to         │
│  implement the operational activities you've created.       │
│                                                              │
│  ┌──────────────────────────────────────────────────────┐  │
│  │ [Conversation]                                        │  │
│  │                                                        │  │
│  │ You: Create a mission called "Emergency Response"     │  │
│  │                                                        │  │
│  │ AI: I'll create an Operational Analysis mission       │  │
│  │ called "Emergency Response". Here's what I'm          │  │
│  │ planning:                                              │  │
│  │                                                        │  │
│  │ 📋 Planned Changes:                                   │  │
│  │ ├─ [+] Mission: Emergency Response                    │  │
│  │ │   Location: Operational Analysis > Missions        │  │
│  │ │   Description: (empty - would you like to add?)    │  │
│  │ └─ [~] Model Summary: Update mission count           │  │
│  │                                                        │  │
│  │ [Execute Plan] [Modify] [Cancel]                      │  │
│  │                                                        │  │
│  │ You: Yes, add description "Coordinate emergency      │  │
│  │ response activities across all agencies"              │  │
│  │                                                        │  │
│  │ AI: ✅ Created Mission "Emergency Response" with      │  │
│  │ description. The mission is now in your model.        │  │
│  │                                                        │  │
│  │ 🎯 What's next? I can help you:                      │  │
│  │ • Define activities for this mission                  │  │
│  │ • Create operational actors                           │  │
│  │ • Link this mission to capabilities                   │  │
│  │                                                        │  │
│  │ Or just tell me what you'd like to do.               │  │
│  │                                                        │  │
│  ├──────────────────────────────────────────────────────┤  │
│  │                                                        │  │
│  │ Type your message...                      [Send ↑]   │  │
│  │                                                        │  │
│  │ 💬 Try: "What should I do next?" or press / for      │  │
│  │ quick commands                                        │  │
│  └──────────────────────────────────────────────────────┘  │
│                                                              │
│  ┌────────────────────────────────────────────────────┐    │
│  │ 📊 Model Context                    [Expand ▼]     │    │
│  │ ─────────────────────────────────────────────────  │    │
│  │ Selected: Mission M1 (Operational Analysis)         │    │
│  │ Model Status: 234 elements • OA complete • SA       │    │
│  │ in progress                                         │    │
│  │ Arcadia Progress: [████████░░] 80%                  │    │
│  └────────────────────────────────────────────────────┘    │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

---

## Detailed Design Specification

### Layout: Single-Column Conversational

```
┌─────────────────────────────────────────────┐
│ Header (60px)                                │
│ - App name                                   │
│ - Connection status                          │
│ - Settings gear                              │
├─────────────────────────────────────────────┤
│                                              │
│ Welcome Panel (auto-hides after first msg)  │
│ - Personalized greeting                      │
│ - Model status summary                       │
│ - Suggested next action                      │
│                                              │
├─────────────────────────────────────────────┤
│                                              │
│ Conversation Area (flex, grows)             │
│ - Message bubbles (user vs AI)              │
│ - Inline plan previews                       │
│ - Action buttons contextual to plans         │
│ - Proactive suggestions                      │
│                                              │
│ [Scrollable - majority of screen real estate]│
│                                              │
├─────────────────────────────────────────────┤
│ Input Area (120px fixed)                     │
│ - Text field (expandable)                    │
│ - Send button                                │
│ - Shortcut hints                             │
├─────────────────────────────────────────────┤
│ Context Panel (collapsible, 150px)          │
│ - Selected element info                      │
│ - Model health snapshot                      │
│ - Arcadia progress bar                       │
└─────────────────────────────────────────────┘
```

**Key Changes**:
- **No tabs** - single focused view (conversation is everything)
- **No button grid** - all actions through conversation or inline suggestions
- **Auto-expanding** - conversation grows, context collapses
- **Visual hierarchy** - clear separation of concerns

---

## Conversation Design Patterns

### Pattern 1: Greeting & Context Setting

**When**: View opens
**What**: AI greets user, summarizes model state, suggests next action

```
┌─────────────────────────────────────────────┐
│ 👋 Welcome back, Alex!                 │
│                                              │
│ You're working on: Vehicle Control System   │
│                                              │
│ 📊 Current state:                           │
│ • Operational Analysis: ✅ Complete         │
│ • System Analysis: 🔄 In Progress (60%)    │
│ • Logical Architecture: ⏳ Not Started      │
│                                              │
│ 💡 Recommended next: Define System          │
│ Functions to implement your operational     │
│ activities.                                  │
│                                              │
│ [Start System Functions] [Different Task]   │
└─────────────────────────────────────────────┘
```

**Interaction**:
- Clicking suggestions auto-fills prompt
- User can type freely to override
- Panel auto-hides after first message

---

### Pattern 2: Conversational Model Building

**When**: User requests model changes
**What**: AI shows plan inline, user confirms/modifies

```
┌─────────────────────────────────────────────┐
│ You                                     Now │
│ ┌─────────────────────────────────────────┐ │
│ │ Create a system function called         │ │
│ │ "ProcessSensorData" that handles all    │ │
│ │ vehicle sensor inputs                   │ │
│ └─────────────────────────────────────────┘ │
│                                              │
│ AI                                      Now │
│ ┌─────────────────────────────────────────┐ │
│ │ I'll create that system function. Let   │ │
│ │ me show you what I'm planning:          │ │
│ │                                          │ │
│ │ ┏━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┓ │ │
│ │ ┃ 📋 Plan Preview                    ┃ │ │
│ │ ┣━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┫ │ │
│ │ ┃                                     ┃ │ │
│ │ ┃ [+] System Function                 ┃ │ │
│ │ ┃     Name: ProcessSensorData         ┃ │ │
│ │ ┃     Location: System Analysis >     ┃ │ │
│ │ ┃               System Functions      ┃ │ │
│ │ ┃     Description: Handles all        ┃ │ │
│ │ ┃     vehicle sensor inputs           ┃ │ │
│ │ ┃                                     ┃ │ │
│ │ ┃ 💡 Suggestion: Should I also create┃ │ │
│ │ ┃ input/output ports for common       ┃ │ │
│ │ ┃ sensors? (speed, temperature, etc.) ┃ │ │
│ │ ┃                                     ┃ │ │
│ │ ┗━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┛ │ │
│ │                                          │ │
│ │ [✓ Execute] [✎ Modify] [+ Add Ports]   │ │
│ │ [✕ Cancel]                              │ │
│ └─────────────────────────────────────────┘ │
└─────────────────────────────────────────────┘
```

**Design Elements**:
- **Visual distinction**: User messages right-aligned (like iMessage), AI left
- **Plan boxes**: Highlighted background, clear visual separation
- **Action buttons**: Inline, contextual to the plan
- **Proactive suggestions**: AI anticipates next steps
- **Icons**: Visual anchors (📋 plan, 💡 suggestion, ✅ success)

---

### Pattern 3: Execution Feedback

**When**: Plan executes
**What**: Immediate visual confirmation with next steps

```
┌─────────────────────────────────────────────┐
│ AI                                   Now    │
│ ┌─────────────────────────────────────────┐ │
│ │ ✅ Done! I've created:                  │ │
│ │                                          │ │
│ │ • System Function: ProcessSensorData    │ │
│ │ • Input Ports: speedSensor,             │ │
│ │   temperatureSensor, pressureSensor     │ │
│ │ • Output Port: processedSensorData      │ │
│ │                                          │ │
│ │ [View in Model 🔍] [Open Diagram 📊]   │ │
│ │                                          │ │
│ │ ───────────────────────────────────────  │ │
│ │                                          │ │
│ │ 🎯 What's next?                         │ │
│ │                                          │ │
│ │ • Allocate this function to a           │ │
│ │   component                              │ │
│ │ • Define functional exchanges            │ │
│ │ • Create scenarios using this function  │ │
│ │ • Add more system functions             │ │
│ │                                          │ │
│ │ Just tell me what you'd like to do, or │ │
│ │ I can recommend based on Arcadia        │ │
│ │ methodology.                             │ │
│ └─────────────────────────────────────────┘ │
└─────────────────────────────────────────────┘
```

**Key Features**:
- **Success confirmation**: Clear ✅ with summary
- **Quick actions**: View/open links to see results
- **Proactive guidance**: Suggests logical next steps
- **Methodology awareness**: References Arcadia best practices

---

### Pattern 4: Multi-Turn Refinement

**When**: User wants to modify plan before execution
**What**: Conversational back-and-forth to refine details

```
┌─────────────────────────────────────────────┐
│ You                                     Now │
│ ┌─────────────────────────────────────────┐ │
│ │ Actually, add a description explaining  │ │
│ │ this processes data from both internal  │ │
│ │ and external sensors                    │ │
│ └─────────────────────────────────────────┘ │
│                                              │
│ AI                                      Now │
│ ┌─────────────────────────────────────────┐ │
│ │ Got it. Updated the plan:               │ │
│ │                                          │ │
│ │ ┏━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┓ │ │
│ │ ┃ [~] System Function                 ┃ │ │
│ │ ┃     Name: ProcessSensorData         ┃ │ │
│ │ ┃     Description: Processes data     ┃ │ │
│ │ ┃     from both internal and external ┃ │ │
│ │ ┃     sensors (UPDATED)               ┃ │ │
│ │ ┗━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┛ │ │
│ │                                          │ │
│ │ [✓ Execute] [✎ More Changes]           │ │
│ └─────────────────────────────────────────┘ │
└─────────────────────────────────────────────┘
```

**Interaction Model**:
- Plan stays editable until executed
- AI tracks changes conversationally
- User can refine multiple times
- Visual indicators show what changed (UPDATED, NEW, REMOVED)

---

### Pattern 5: Error Recovery & Clarification

**When**: AI doesn't understand or needs more info
**What**: Ask clarifying questions, offer suggestions

```
┌─────────────────────────────────────────────┐
│ You                                     Now │
│ ┌─────────────────────────────────────────┐ │
│ │ Add the sensors to the thing            │ │
│ └─────────────────────────────────────────┘ │
│                                              │
│ AI                                      Now │
│ ┌─────────────────────────────────────────┐ │
│ │ I want to help, but I need a bit more   │ │
│ │ clarity:                                 │ │
│ │                                          │ │
│ │ ❓ Which sensors would you like to add? │ │
│ │                                          │ │
│ │ ❓ What should they be added to?        │ │
│ │ I see these options in your current     │ │
│ │ context:                                 │ │
│ │ • ProcessSensorData (System Function)   │ │
│ │ • VehicleController (Component)         │ │
│ │ • SensorArray (Component)               │ │
│ │                                          │ │
│ │ [ProcessSensorData] [VehicleController] │ │
│ │ [SensorArray] [Something else...]       │ │
│ └─────────────────────────────────────────┘ │
└─────────────────────────────────────────────┘
```

**Smart Features**:
- AI uses model context to suggest options
- Quick-select buttons for common answers
- Maintains conversation flow (doesn't fail harshly)

---

### Pattern 6: Methodology Coaching (Proactive)

**When**: AI detects Arcadia violations or opportunities
**What**: Gentle guidance without interrupting flow

```
┌─────────────────────────────────────────────┐
│ AI                                      Now │
│ ┌─────────────────────────────────────────┐ │
│ │ ✅ Created 5 system functions.          │ │
│ │                                          │ │
│ │ 💡 Arcadia Tip: You've defined System  │ │
│ │ Functions but haven't allocated them    │ │
│ │ to System Components yet. This is       │ │
│ │ typically the next step in System       │ │
│ │ Analysis.                                │ │
│ │                                          │ │
│ │ Would you like me to:                   │ │
│ │ • Help allocate these functions now     │ │
│ │ • Continue adding more functions first  │ │
│ │ • Skip this for now                     │ │
│ │                                          │ │
│ │ [Allocate Functions] [Add More]         │ │
│ │ [Skip]                                   │ │
│ └─────────────────────────────────────────┘ │
└─────────────────────────────────────────────┘
```

**Coaching Philosophy**:
- **Non-intrusive**: Suggestions, not demands
- **Educational**: Explains "why" (Arcadia methodology)
- **Actionable**: Offers to do it, not just point out
- **Dismissible**: User can skip without penalty

---

### Pattern 7: Batch Operations

**When**: User wants to do complex multi-step work
**What**: AI breaks it down, shows full plan upfront

```
┌─────────────────────────────────────────────┐
│ You                                     Now │
│ ┌─────────────────────────────────────────┐ │
│ │ Create a complete operational scenario  │ │
│ │ for emergency response with all the     │ │
│ │ actors and interactions                 │ │
│ └─────────────────────────────────────────┘ │
│                                              │
│ AI                                      Now │
│ ┌─────────────────────────────────────────┐ │
│ │ That's a complex scenario! Let me break │ │
│ │ it down:                                 │ │
│ │                                          │ │
│ │ ┏━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┓ │ │
│ │ ┃ 📋 Multi-Step Plan (7 actions)     ┃ │ │
│ │ ┣━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┫ │ │
│ │ ┃                                     ┃ │ │
│ │ ┃ [+] Operational Scenario            ┃ │ │
│ │ ┃     Name: Emergency Response        ┃ │ │
│ │ ┃                                     ┃ │ │
│ │ ┃ [+] Operational Actor: Dispatcher   ┃ │ │
│ │ ┃ [+] Operational Actor: First Resp.  ┃ │ │
│ │ ┃ [+] Operational Actor: Coordinator  ┃ │ │
│ │ ┃                                     ┃ │ │
│ │ ┃ [+] Interaction: Alert Sent         ┃ │ │
│ │ ┃ [+] Interaction: Status Update      ┃ │ │
│ │ ┃ [+] Interaction: Coordination       ┃ │ │
│ │ ┃                                     ┃ │ │
│ │ ┃ [Details ▼]                        ┃ │ │
│ │ ┗━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┛ │ │
│ │                                          │ │
│ │ This will create a complete emergency   │ │
│ │ response scenario with 3 actors and     │ │
│ │ their interactions.                      │ │
│ │                                          │ │
│ │ [✓ Execute All] [✎ Customize]          │ │
│ │ [📋 Review Details]                     │ │
│ └─────────────────────────────────────────┘ │
└─────────────────────────────────────────────┘
```

**Complex Plan Features**:
- **Summary view**: High-level action count
- **Expandable details**: Click to see full plan
- **Batch execution**: One-click to do everything
- **Customization option**: Modify before running

---

## Command Palette Integration

### Trigger: `/` key or Ctrl/Cmd+Space

**Instead of showing basic commands, show contextualized suggestions:**

```
┌─────────────────────────────────────────────┐
│ 🔍 Quick Commands                           │
├─────────────────────────────────────────────┤
│                                              │
│ 🎯 Suggested for Current Context            │
│ ─────────────────────────────────────────── │
│ /allocate-function                           │
│   Allocate ProcessSensorData to a component │
│                                              │
│ /create-exchange                             │
│   Define functional exchange between funcs   │
│                                              │
│ ⚡ Arcadia Next Steps                       │
│ ─────────────────────────────────────────── │
│ /arcadia-next                                │
│   Get methodology guidance for current phase │
│                                              │
│ /complete-system-analysis                    │
│   Finish System Analysis checklist           │
│                                              │
│ 📚 Model Operations                         │
│ ─────────────────────────────────────────── │
│ /explain-selection                           │
│   Analyze selected element in detail         │
│                                              │
│ /show-dependencies                           │
│   Visualize element dependencies             │
│                                              │
│ 🛠️ Advanced                                 │
│ ─────────────────────────────────────────── │
│ /import-diagram                              │
│ /search-model                                │
│ /validate-arcadia                            │
│                                              │
└─────────────────────────────────────────────┘
```

**Key Improvements**:
- **Context-aware**: Top suggestions based on current work
- **Categorized**: Grouped by purpose
- **Descriptive**: Each command explains what it does
- **Dynamic**: Changes based on model state

---

## Settings Panel (Minimal, Hidden by Default)

### Access: Settings gear icon in header

**Philosophy**: Configuration should be simple and rare

```
┌─────────────────────────────────────────────┐
│ ⚙️ Settings                          [✕]    │
├─────────────────────────────────────────────┤
│                                              │
│ 🤖 AI Configuration                         │
│ ───────────────────────────────────────────  │
│                                              │
│ Provider:        [OpenAI ▼]                 │
│ Model:           [gpt-4o ▼]                 │
│ Temperature:     [0.7 ━━━━━○━━━━ 1.0]      │
│                                              │
│ API Key:         [••••••••••••••] [Change]  │
│ Status:          ✅ Connected                │
│                                              │
│ [Test Connection]                            │
│                                              │
│ ───────────────────────────────────────────  │
│                                              │
│ 🎨 Interface                                │
│ ───────────────────────────────────────────  │
│                                              │
│ ☑ Show methodology coaching                 │
│ ☑ Auto-expand plan previews                 │
│ ☑ Show suggested next steps                 │
│ ☐ Compact mode (less spacing)               │
│                                              │
│ ───────────────────────────────────────────  │
│                                              │
│ ⚡ Behavior                                  │
│ ───────────────────────────────────────────  │
│                                              │
│ ☑ Enable tool actions (plan execution)      │
│ ☐ Auto-execute simple plans                 │
│ ☑ Confirm before model changes               │
│                                              │
│ ───────────────────────────────────────────  │
│                                              │
│ 📊 Telemetry                                │
│ ───────────────────────────────────────────  │
│                                              │
│ ☐ Send anonymous usage data to help         │
│   improve the assistant                      │
│                                              │
│ ───────────────────────────────────────────  │
│                                              │
│               [Save] [Cancel]                │
│                                              │
└─────────────────────────────────────────────┘
```

**Design Principles**:
- **Modal overlay**: Doesn't take permanent screen space
- **Grouped logically**: AI, Interface, Behavior, Telemetry
- **Sensible defaults**: Most users never need to change
- **Visual feedback**: Connection status visible

---

## Context Panel (Collapsible)

### Location: Bottom of view, collapses when not needed

```
┌─────────────────────────────────────────────┐
│ 📊 Model Context               [Collapse ▲] │
├─────────────────────────────────────────────┤
│                                              │
│ 🎯 Current Selection                        │
│ System Function: ProcessSensorData          │
│ Location: System Analysis > System Functions│
│ [View Details] [Open Diagram]                │
│                                              │
│ ─────────────────────────────────────────── │
│                                              │
│ 📈 Model Health                             │
│ Total Elements: 234                          │
│ Validation: ✅ No errors, ⚠️ 3 warnings     │
│ [View Issues]                                │
│                                              │
│ ─────────────────────────────────────────── │
│                                              │
│ 🏗️ Arcadia Progress                         │
│ Operational Analysis:    [████████] 100%    │
│ System Analysis:         [█████░░░] 60%     │
│ Logical Architecture:    [░░░░░░░░] 0%      │
│ Physical Architecture:   [░░░░░░░░] 0%      │
│                                              │
│ 💡 Next recommended: Complete System        │
│ Analysis by allocating functions to         │
│ components                                   │
│                                              │
└─────────────────────────────────────────────┘
```

**Smart Behaviors**:
- **Auto-collapse** when conversation gets long (save space)
- **Update live** when selection changes in Capella
- **Clickable elements** to navigate model
- **Progress visualization** shows Arcadia methodology status

---

## Message Design System

### Visual Language

#### User Messages (Right-Aligned)
```
                              ┌─────────────────┐
                              │ Create a mission│
                              │ called "Test"   │
                              └─────────────────┘
                                            Now
```

**Styling**:
- Background: Light blue/gray (theme-aware)
- Alignment: Right
- Max width: 70% of conversation area
- Rounded corners
- Subtle shadow

#### AI Messages (Left-Aligned)
```
Now
┌───────────────────────────────────┐
│ I'll create that mission for you. │
│                                    │
│ [Plan preview appears here]        │
│                                    │
│ [Action buttons]                   │
└───────────────────────────────────┘
```

**Styling**:
- Background: White (light theme) / Dark gray (dark theme)
- Alignment: Left
- Max width: 80% of conversation area
- Border: 1px subtle line

#### Plan Preview Boxes (Embedded in AI Messages)
```
┏━━━━━━━━━━━━━━━━━━━━━━━━━━━┓
┃ 📋 Plan Preview           ┃
┣━━━━━━━━━━━━━━━━━━━━━━━━━━━┫
┃                            ┃
┃ [+] Mission: Test          ┃
┃     Location: OA > Missions┃
┃                            ┃
┗━━━━━━━━━━━━━━━━━━━━━━━━━━━┛
```

**Styling**:
- Background: Slightly darker than AI message
- Border: Thicker, colored (green for creates, blue for updates)
- Icons: Color-coded (green [+], blue [~], red [-])
- Monospace font for structure preservation

#### Success Confirmations
```
┌─────────────────────────────┐
│ ✅ Success! Created:        │
│ • Mission: Test             │
│ • Location: OA > Missions   │
│                             │
│ [View in Model]             │
└─────────────────────────────┘
```

**Styling**:
- Background: Subtle green tint
- ✅ Icon: Large, prominent
- Bullet list: Clear hierarchy
- Action links: Underlined, clickable

#### Suggestions/Tips
```
┌─────────────────────────────┐
│ 💡 Tip: In Arcadia, you     │
│ typically define activities │
│ after creating missions.    │
│                             │
│ [Create Activities Now]     │
└─────────────────────────────┘
```

**Styling**:
- Background: Subtle yellow tint
- 💡 Icon: Attention-grabbing
- Dismissible: Small [x] in corner
- Action button: Optional

---

## Interaction Patterns

### Smart Auto-Complete

As user types, show contextual suggestions:

```
User typing: "Create a sys"

┌─────────────────────────────────────────────┐
│ Create a sys█                                │
├─────────────────────────────────────────────┤
│ 💡 Did you mean:                            │
│                                              │
│ • Create a system function                   │
│ • Create a system component                  │
│ • Create a system capability                 │
│ • Create a system interface                  │
│                                              │
│ [Tab to complete] [Esc to dismiss]          │
└─────────────────────────────────────────────┘
```

### Inline Editing

Plans can be edited before execution:

```
┏━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┓
┃ 📋 Plan Preview                    ┃
┣━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┫
┃                                     ┃
┃ [+] Mission: [Test____________]    ┃  ← Editable field
┃     Location: OA > Missions        ┃
┃     Description: [____________]    ┃  ← Click to add
┃                                     ┃
┗━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┛

[✓ Execute] [✎ Edit in Chat] [✕ Cancel]
```

### Progressive Disclosure

Complex plans start collapsed:

```
┏━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┓
┃ 📋 Multi-Step Plan (12 actions)    ┃
┣━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┫
┃                                     ┃
┃ Summary:                            ┃
┃ • 5 components to create            ┃
┃ • 7 functions to allocate           ┃
┃ • 2 diagrams to update              ┃
┃                                     ┃
┃ [Show Details ▼]                   ┃
┃                                     ┃
┗━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┛

[✓ Execute All] [✎ Review Each Step]
```

Click "Show Details" to expand:

```
┏━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┓
┃ 📋 Multi-Step Plan (12 actions)    ┃
┣━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┫
┃                                     ┃
┃ [+] Component: SensorModule         ┃
┃ [+] Component: ProcessingUnit       ┃
┃ [+] Component: OutputController     ┃
┃ ...                                 ┃
┃                                     ┃
┃ [Hide Details ▲]                   ┃
┃                                     ┃
┗━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┛
```

---

## Advanced Features

### 1. Diagram Integration

When AI creates diagram-able elements, offer to visualize:

```
AI: ✅ Created 3 system functions and allocated
    them to components.

    [View as Diagram 📊]

    → Opens/creates LAB diagram with new elements
    → Auto-arranges layout
    → Highlights new additions
```

### 2. Validation Integration

AI proactively checks model consistency:

```
AI: ⚠️ I noticed something:

    The function "ProcessData" is allocated to
    "Controller" but they're in different layers
    (System vs Logical). This might cause issues.

    Would you like me to:
    • Move the function to the correct layer
    • Create a trace link instead
    • Explain the issue in detail

    [Fix Automatically] [Explain] [Ignore]
```

### 3. Search Integration

Natural language search:

```
You: Where did we define the emergency response
     mission?

AI: Found it! The "Emergency Response" mission
    is in:

    📍 Operational Analysis
       └─ Missions
          └─ Emergency Response

    It has:
    • 3 activities linked
    • 2 actors involved
    • Created on: 2025-09-15

    [View in Model] [Show Activities]
```

### 4. Import/Export

Conversational file operations:

```
You: Import the diagram from draw.io

AI: I'll help you import a draw.io diagram.

    [Select File]

    → User selects file

AI: Analyzing diagram... Found:
    • 5 boxes (will map to components)
    • 8 arrows (will map to exchanges)

    How should I interpret this?
    • As System Components
    • As Logical Components
    • As Operational Actors

    [System] [Logical] [Operational] [Custom...]
```

---

## Responsive Behaviors

### Auto-Save Conversation

```
[Every 30 seconds, auto-save conversation state]

→ User can close view and return later
→ Conversation history persists across sessions
→ Incomplete plans saved as drafts
```

### Session Recovery

```
On view open after crash:

AI: 👋 Welcome back!

    I noticed we were in the middle of creating
    a system function before Capella closed.

    Would you like to:
    • Resume where we left off
    • Start fresh
    • Review what we did

    [Resume] [Start Fresh] [Review]
```

### Network Resilience

```
If LLM connection fails:

AI: ⚠️ Connection lost

    I can't reach the AI service right now.
    Your conversation is saved locally.

    [Retry] [Work Offline] [Settings]

Offline mode:
→ Show recent conversation history
→ Allow browsing model context
→ Queue messages for when connection returns
```

---

## Accessibility

### Keyboard Navigation

```
Tab:           Navigate between input and buttons
Ctrl/Cmd+L:    Focus conversation input
Ctrl/Cmd+K:    Open command palette
Ctrl/Cmd+/:    Toggle context panel
Esc:           Cancel current operation
Enter:         Send message (when input focused)
Shift+Enter:   New line in input
```

### Screen Reader Support

```
<!-- Every message has ARIA labels -->

<div role="log" aria-live="polite" aria-label="Conversation">
  <div role="article" aria-label="User message">
    Create a mission called Test
  </div>
  <div role="article" aria-label="AI response">
    I'll create that mission. Plan preview:
    Create mission Test in Operational Analysis.
    <button aria-label="Execute plan">Execute</button>
    <button aria-label="Cancel plan">Cancel</button>
  </div>
</div>
```

### High Contrast Mode

```
Detect system theme and adjust:

Light Mode:
- User messages: #E3F2FD (light blue)
- AI messages: #FFFFFF (white)
- Plan boxes: #F5F5F5 (light gray)
- Success: #E8F5E9 (light green)

Dark Mode:
- User messages: #1E3A5F (dark blue)
- AI messages: #2C2C2C (dark gray)
- Plan boxes: #3C3C3C (darker gray)
- Success: #1B5E20 (dark green)

High Contrast Mode:
- Strong borders
- Higher color contrast ratios (WCAG AAA)
- No reliance on color alone (icons + text)
```

---

## Technical Implementation

### Architecture

```
ChatView (300 lines) - Main view coordinator
├── ConversationPanel (400 lines)
│   ├── MessageList (renders conversation)
│   ├── PlanPreview (inline plan display)
│   ├── MessageInput (text field + send)
│   └── ActionButtons (contextual to messages)
├── ContextPanel (150 lines)
│   ├── SelectionCard (current element info)
│   ├── ModelHealthCard (validation status)
│   └── ArcadiaProgressCard (methodology tracking)
├── SettingsDialog (200 lines)
│   └── LLMConfigPanel (moved from main view)
└── ConversationController (400 lines)
    ├── StreamingHandler (LLM response handling)
    ├── PlanParser (extract TOOL_PLAN from responses)
    └── HistoryManager (conversation persistence)
```

### Data Flow

```
User Input
    ↓
ConversationController.sendMessage()
    ↓
LLMService.streamChat()
    ↓
[Streaming responses arrive]
    ↓
ConversationController.onToken()
    ↓
MessageList.appendToken()
    ↓
[If TOOL_PLAN detected]
    ↓
PlanPreview.show()
    ↓
[User clicks Execute]
    ↓
SessionSafeguards.runWrite()
    ↓
ToolRouter.executePlan()
    ↓
[Success]
    ↓
MessageList.appendSuccess()
    ↓
ConversationController.suggestNextSteps()
```

### State Management

```java
public class ConversationState {
    // Persisted state
    private List<Message> history;
    private ToolPlan pendingPlan;
    private String modelId;
    private ArcadiaPhase currentPhase;

    // UI state (transient)
    private boolean streaming;
    private String currentDraft;
    private boolean contextPanelExpanded;

    // Auto-save every 30 seconds
    public void persist() {
        Preferences prefs = LLMPreferences.getPreferences();
        prefs.put("conversation.history", serializeHistory());
        prefs.put("conversation.pendingPlan", serializePlan());
        // ...
    }

    // Load on view open
    public static ConversationState restore() {
        Preferences prefs = LLMPreferences.getPreferences();
        // ... deserialize
    }
}
```

---

## Migration Strategy

### Phase 1: Conversation-First UI (Week 1)

**Goal**: Get basic conversational interface working

**Changes**:
1. Simplify ChatView layout:
   - Remove all template button groups
   - Keep only conversation area + input
   - Move settings to modal dialog
2. Improve message rendering:
   - User messages right-aligned
   - AI messages left-aligned
   - Add plan preview boxes
3. Add basic action buttons:
   - Execute/Cancel inline with plans
   - View/Open links in success messages

**Testing**: Verify conversation flow feels natural

---

### Phase 2: Smart Suggestions (Week 2)

**Goal**: AI proactively guides user

**Changes**:
1. Add welcome panel:
   - Personalized greeting
   - Model summary
   - Suggested next action
2. Add methodology coaching:
   - Detect Arcadia phase violations
   - Suggest next steps after operations
   - Offer to complete missing work
3. Improve command palette:
   - Context-aware suggestions
   - Dynamic based on model state

**Testing**: Verify AI guidance is helpful, not annoying

---

### Phase 3: Context Integration (Week 3)

**Goal**: Deep model awareness

**Changes**:
1. Context panel:
   - Selection tracking
   - Model health dashboard
   - Arcadia progress bars
2. Live updates:
   - Auto-refresh when model changes
   - Highlight new elements
   - Show validation status
3. Diagram integration:
   - Offer to visualize created elements
   - Auto-open relevant diagrams

**Testing**: Verify context stays synchronized

---

### Phase 4: Polish & Refinement (Week 4)

**Goal**: Professional, delightful experience

**Changes**:
1. Visual refinement:
   - Consistent spacing/colors
   - Theme support (light/dark)
   - Icon integration
2. Interaction polish:
   - Smooth scrolling
   - Auto-focus management
   - Keyboard shortcuts
3. Error handling:
   - Network resilience
   - Graceful degradation
   - Session recovery

**Testing**: User acceptance testing with 5+ engineers

---

## Success Metrics

### Quantitative

| Metric | Before | Target | Measurement |
|--------|--------|--------|-------------|
| Time to create mission | 45s | 15s | User study |
| Clicks to complete task | 8 | 2 | Interaction log |
| Scrolling required | High | Minimal | UI recordings |
| User errors | 30% | 5% | Error rate |
| Task abandonment | 25% | <10% | Completion rate |

### Qualitative

**User Feedback Survey** (1-10 scale):
- "The interface feels conversational" → Target: 8+
- "I know what to do next" → Target: 9+
- "The AI understands my intent" → Target: 8+
- "I feel in control of the model" → Target: 9+
- "I would use this daily" → Target: 8+

---

## Visual Mockups (ASCII Art)

### Complete View - Empty State

```
┌───────────────────────────────────────────────────────────────┐
│ Capella AI Assistant          Connected to gpt-4o     [⚙]   │
├───────────────────────────────────────────────────────────────┤
│                                                                │
│  👋 Welcome to Capella AI Assistant!                         │
│                                                                │
│  I'm here to help you build systems using the Arcadia        │
│  methodology. Let's start by understanding where you are:    │
│                                                                │
│  ┌──────────────────────────────────────────────────────┐   │
│  │ No active model detected                              │   │
│  │                                                        │   │
│  │ Please open a Capella model to get started, or:      │   │
│  │                                                        │   │
│  │ • Tell me what you're working on                      │   │
│  │ • Ask me about Arcadia methodology                    │   │
│  │ • Try a command (type / to see options)              │   │
│  │                                                        │   │
│  └──────────────────────────────────────────────────────┘   │
│                                                                │
│  ┌──────────────────────────────────────────────────────┐   │
│  │                                                        │   │
│  │ Type your message...                        [Send ↑] │   │
│  │                                                        │   │
│  │ 💬 Try: "Help me get started" or press /             │   │
│  └──────────────────────────────────────────────────────┘   │
│                                                                │
└───────────────────────────────────────────────────────────────┘
```

### Complete View - Active Conversation

```
┌───────────────────────────────────────────────────────────────┐
│ Capella AI Assistant          Connected to gpt-4o     [⚙]   │
├───────────────────────────────────────────────────────────────┤
│                                                                │
│  👋 Working on: Vehicle Control System                       │
│  💡 Next: Complete System Analysis by allocating functions   │
│                                                                │
│ ┌────────────────────────────────────────────────────────┐  │
│ │ [Conversation - scrollable]                             │  │
│ │                                                          │  │
│ │ You                                              2:30pm │  │
│ │          ┌──────────────────────────────────────┐      │  │
│ │          │ Create a mission called Emergency    │      │  │
│ │          │ Response                              │      │  │
│ │          └──────────────────────────────────────┘      │  │
│ │                                                          │  │
│ │ AI                                               2:30pm │  │
│ │ ┌────────────────────────────────────────────────────┐ │  │
│ │ │ I'll create an Operational Analysis mission        │ │  │
│ │ │ called "Emergency Response".                       │ │  │
│ │ │                                                     │ │  │
│ │ │ ┏━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┓ │ │  │
│ │ │ ┃ 📋 Plan Preview                             ┃ │ │  │
│ │ │ ┣━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┫ │ │  │
│ │ │ ┃                                              ┃ │ │  │
│ │ │ ┃ [+] Mission: Emergency Response              ┃ │ │  │
│ │ │ ┃     Location: Operational Analysis >         ┃ │ │  │
│ │ │ ┃               Missions                       ┃ │ │  │
│ │ │ ┃     Description: (empty)                     ┃ │ │  │
│ │ │ ┃                                              ┃ │ │  │
│ │ │ ┃ 💡 Would you like to add a description?     ┃ │ │  │
│ │ │ ┃                                              ┃ │ │  │
│ │ │ ┗━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┛ │ │  │
│ │ │                                                     │ │  │
│ │ │ [✓ Execute] [✎ Add Description] [✕ Cancel]        │ │  │
│ │ └────────────────────────────────────────────────────┘ │  │
│ │                                                          │  │
│ │ You                                              2:31pm │  │
│ │          ┌──────────────────────────────────────┐      │  │
│ │          │ Yes, add "Coordinate emergency       │      │  │
│ │          │ response across agencies"             │      │  │
│ │          └──────────────────────────────────────┘      │  │
│ │                                                          │  │
│ │ AI                                               2:31pm │  │
│ │ ┌────────────────────────────────────────────────────┐ │  │
│ │ │ ✅ Created Mission "Emergency Response" with       │ │  │
│ │ │ description.                                       │ │  │
│ │ │                                                     │ │  │
│ │ │ [View in Model 🔍] [Open Diagram 📊]              │ │  │
│ │ │                                                     │ │  │
│ │ │ ─────────────────────────────────────────────────  │ │  │
│ │ │                                                     │ │  │
│ │ │ 🎯 What's next?                                    │ │  │
│ │ │                                                     │ │  │
│ │ │ • Define activities for this mission               │ │  │
│ │ │ • Create operational actors                        │ │  │
│ │ │ • Link mission to capabilities                     │ │  │
│ │ │                                                     │ │  │
│ │ │ [Define Activities] [Create Actors] [Link]         │ │  │
│ │ └────────────────────────────────────────────────────┘ │  │
│ │                                                          │  │
│ └──────────────────────────────────────────────────────────┘  │
│                                                                │
│ ┌──────────────────────────────────────────────────────────┐ │
│ │                                                            │ │
│ │ Type your message...                          [Send ↑]   │ │
│ │                                                            │ │
│ │ 💬 Try: "Define activities" or press /                   │ │
│ └──────────────────────────────────────────────────────────┘ │
│                                                                │
│ ┌──────────────────────────────────────────────────────┐    │
│ │ 📊 Model Context                    [Collapse ▼]     │    │
│ │ ────────────────────────────────────────────────────  │    │
│ │ Selected: Mission M1 (Operational Analysis)           │    │
│ │ Model: 235 elements • OA: 95% • SA: 45%              │    │
│ │ Arcadia: [████████░░] 80% → Complete SA next         │    │
│ └──────────────────────────────────────────────────────┘    │
│                                                                │
└───────────────────────────────────────────────────────────────┘
```

---

## Conclusion

### This Redesign Achieves:

✅ **Conversational Feel**: Chat is primary, AI guides naturally
✅ **Utmost Detail**: Plans show full structure before execution
✅ **Reduced Cognitive Load**: No button overload, focused interface
✅ **Proactive Guidance**: AI suggests next steps, coaches methodology
✅ **Visual Clarity**: Clear message hierarchy, plan previews, status
✅ **Accessibility**: Keyboard nav, screen readers, high contrast
✅ **Professional Polish**: Consistent, theme-aware, refined

### From User's Perspective:

**Before**: "Overwhelming tool with too many buttons, not sure what to click"
**After**: "Natural conversation where the AI helps me build my model step by step"

### Implementation Effort:

- **Week 1**: Basic conversation UI (message bubbles, plan previews)
- **Week 2**: Smart suggestions & methodology coaching
- **Week 3**: Context integration & model awareness
- **Week 4**: Polish, accessibility, theme support

**Total**: 4 weeks to transform from cluttered tool to conversational partner

---

**Next Step**: Shall I start implementing the Week 1 changes (basic conversational UI)?
