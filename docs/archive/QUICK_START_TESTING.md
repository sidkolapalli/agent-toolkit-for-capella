# Quick Start - Testing the Conversational UI

## Prerequisites

1. **Capella 7.0.1** installed
2. **Built plugin** (see instructions below if not built yet)
3. **LLM API Key** (OpenAI, Anthropic, or Azure)

---

## Step 1: Build the Plugin (If Not Built Yet)

```bash
cd <local-path>

# Export XML entity limits (required)
export JAVA_TOOL_OPTIONS="-Djdk.xml.maxGeneralEntitySizeLimit=0 -Djdk.xml.totalEntitySizeLimit=0 -Djdk.xml.entityExpansionLimit=0"

# Build
mvn -f plugins clean verify
```

**Expected output**: Update site created at `plugins/ai-capella-repository/target/repository`

---

## Step 2: Install Plugin in Capella

1. **Open Capella 7.0.1**

2. **Install from local update site**:
   - `Help → Install New Software...`
   - Click `Add...`
   - Click `Local...`
   - Navigate to: `<local-path>
   - Click `Add`
   - Check "Capella AI Feature"
   - Click `Next`, accept license, `Finish`
   - Restart Capella when prompted

3. **Verify installation**:
   - After restart: `Window → Show View → Other...`
   - Expand "Capella AI Assistant"
   - Double-click "Capella AI Assistant"
   - View should appear (likely docked at bottom)

---

## Step 3: Create a Test Capella Model

### Option A: Quick Empty Model (Fastest for UI Testing)

1. **Create new Capella project**:
   - `File → New → Capella Project`
   - Project name: `TestConversationalUI`
   - Click `Finish`

2. **Open the model**:
   - Expand `TestConversationalUI` in Project Explorer
   - Double-click `TestConversationalUI.aird`
   - Model opens with default Operational Analysis

3. **You're ready!** The empty model is perfect for testing the UI

### Option B: Create a Simple System (Better for Feature Testing)

1. **Create project** (same as Option A)

2. **Add some elements manually**:
   - In Project Explorer, expand: `TestConversationalUI → TestConversationalUI.capella → Operational Analysis`
   - Right-click `Operational Activities` → `New → Operational Activity`
   - Name it: `CustomerCheckout`
   - Right-click `Operational Capabilities` → `New → Operational Capability`
   - Name it: `OnlinePayment`

3. **Now you have elements to query with the AI!**

---

## Step 4: Configure LLM Connection

1. **In the Capella AI Assistant view**, find the **LLM Connection** section

2. **Set provider and model**:
   - Provider: Choose `OpenAI`, `Anthropic`, or `Azure`
   - Model:
     - OpenAI: `gpt-4o` or `gpt-4o-mini`
     - Anthropic: `claude-3-sonnet` or `claude-3-haiku`

3. **Set API Key**:
   - Click `API Key (secure)` field
   - Enter your API key
   - Key is stored securely in Eclipse preferences

4. **Test connection**:
   - Click `Test` button
   - Should see: "Connection OK" or similar

5. **Enable actions**:
   - Check `Enable tool actions` checkbox
   - This allows the AI to execute plans

---

## Step 5: Test the Conversational UI

### Test 1: Empty State Welcome

✅ **Expected**: You should see the welcoming empty state:

```
👋 Welcome to Capella AI Assistant

I'm here to help you build Arcadia models through conversation.

You can:
• Ask me to create system components, functions, or capabilities
• Request architectural transformations between layers
• Get guidance on next steps in your Arcadia methodology
• Explain selected model elements

Try asking: "Create an operational mission for customer checkout"
```

---

### Test 2: Send First Message

1. **Type in the conversation input**:
   ```
   Hello! Can you explain what you can do?
   ```

2. **Press Enter or click Send**

✅ **Expected**:
- Your message appears as a **blue bubble on the right** with "You" label
- AI response appears as a **gray bubble on the left** with "Assistant" label
- Response should stream in (text appears gradually)
- During streaming, bubble background is slightly darker

---

### Test 3: Generate a Plan

1. **Type**:
   ```
   Create an operational mission called "Customer Checkout Process"
   ```

2. **Press Enter**

✅ **Expected**:
- Your message: blue bubble on right
- AI response: gray bubble on left (streaming)
- **Lavender plan preview box** appears below AI response:
  ```
  📋 Proposed Plan
  Create operational mission: Customer Checkout Process

  💡 Review this plan in the Plan Editor before execution
  ```
- **Plan Editor dialog** opens (if "Enable tool actions" is checked)
- Plan Editor shows the proposed action with checkbox
- Can approve/cancel the plan

---

### Test 4: Execute a Plan

1. **In Plan Editor**, check the action checkbox
2. **Click OK**

✅ **Expected**:
- Plan Editor closes
- **Yellow system bubble** appears:
  ```
  System
  ✓ Plan actions applied: 1
  ```
- In Project Explorer, new mission appears under Operational Analysis

---

### Test 5: Error Handling

1. **Stop Capella AI connection** (or use invalid API key)
2. **Send a message**

✅ **Expected**:
- **Yellow system bubble** appears with error:
  ```
  System
  ⚠️ Error: Connection failed
  ```

---

### Test 6: Streaming Cancellation

1. **Send a complex request**:
   ```
   Explain the entire Arcadia methodology in detail
   ```

2. **Immediately click the Stop button**

✅ **Expected**:
- Streaming stops mid-response
- **Yellow system bubble** appears:
  ```
  System
  [generation cancelled]
  ```

---

### Test 7: Long Conversation

1. **Send 10+ messages** (any content)

✅ **Expected**:
- Scrollbar appears
- New messages auto-scroll to bottom
- Can manually scroll up to read old messages
- All bubbles properly aligned (no overlapping)

---

### Test 8: Using Templates

1. **In the view, find "Operational templates" section**
2. **Click "Create Mission" button**
3. **Fill in dialog** with mission name
4. **Click OK**

✅ **Expected**:
- **Yellow system bubble** appears:
  ```
  System
  Created mission 'MissionName'.
  ```

---

### Test 9: Explain Model

1. **Click "Explain model" button** in the conversation section

✅ **Expected**:
- **Yellow system bubble** appears with model summary
- Shows layer dashboards, element counts, validation warnings

---

### Test 10: Search Model

1. **Click "Search Model" button**
2. **Enter search term**: `checkout`

✅ **Expected**:
- **Yellow system bubbles** appear with search results
- Each result shows element name, type, layer, path

---

## Visual Verification Checklist

During testing, verify these visual elements:

### Message Bubbles
- [ ] **User messages**: Right-aligned, light blue background (#DCEEFF)
- [ ] **AI messages**: Left-aligned, light gray background (#F5F5F5)
- [ ] **System messages**: Centered, light yellow background (#FFFADC)
- [ ] **Streaming indicator**: Darker gray during streaming
- [ ] **Role labels**: "You" in blue, "Assistant" in gray, "System" in dark yellow

### Plan Previews
- [ ] **Background**: Light lavender (#F0F0FF)
- [ ] **Header**: "📋 Proposed Plan" in dark blue
- [ ] **Hint**: "💡 Review this plan..." in gray
- [ ] **Centered**: Max 600px width

### Layout
- [ ] **Scrolling**: Smooth, auto-scrolls to bottom
- [ ] **Alignment**: User bubbles right, AI bubbles left, system centered
- [ ] **Spacing**: No overlapping bubbles
- [ ] **Text wrapping**: Proper word wrap within bubbles

---

## Troubleshooting

### Plugin Not Showing in "Install New Software"

**Cause**: Update site not built or path incorrect

**Fix**:
```bash
# Rebuild
export JAVA_TOOL_OPTIONS="-Djdk.xml.maxGeneralEntitySizeLimit=0 -Djdk.xml.totalEntitySizeLimit=0 -Djdk.xml.entityExpansionLimit=0"
mvn -f plugins clean verify

# Verify update site exists
ls -la plugins/ai-capella-repository/target/repository/
```

---

### View Not Appearing After Install

**Cause**: Perspective not reset

**Fix**:
1. `Window → Perspective → Reset Perspective...`
2. Restart Capella
3. Try `Window → Show View → Other...` again

---

### Empty State Not Showing

**Cause**: Old messages in history

**Fix**: Close and reopen the view:
1. Click X on the view tab
2. `Window → Show View → Other... → Capella AI Assistant`

---

### AI Not Responding

**Cause 1**: No LLM configured

**Fix**: Configure API key in LLM Connection section

**Cause 2**: No active Capella session

**Fix**: Open a `.aird` file (Capella model)

---

### Bubbles Not Colored Correctly

**Cause**: SWT color allocation issue

**Expected**: This is rare. Colors should render correctly in Eclipse.

**Temporary workaround**: Restart Capella

---

## Quick Test Commands

Copy-paste these to quickly test different scenarios:

```
Hello!
```
→ Test basic message

```
Create an operational mission called "Customer Checkout"
```
→ Test plan generation

```
Explain the current model
```
→ Test model summary (use "Explain model" button instead)

```
What elements are in the Operational Analysis layer?
```
→ Test model query

```
Create a system component called "Payment Gateway"
```
→ Test System Analysis creation

---

## Next Steps After Testing

**If everything works**:
- ✅ Document any UX feedback
- ✅ Proceed with real modeling work
- ✅ Explore Week 2 features (smart suggestions, methodology coaching)

**If you find issues**:
- 📝 Note exact steps to reproduce
- 📝 Take screenshots
- 📝 Check `docs/TESTING_GUIDE.md` for reporting format

---

## Getting Help

- **Documentation**: Check `docs/` folder for guides
- **Code**: See `CLAUDE.md` for architecture overview
- **Issues**: Review `docs/INTEGRATION_ISSUES.md` for known problems

---

**Ready to test!** 🚀

Start with Test 1 (Empty State) and work through Test 10 (Search Model). The conversational UI should make the experience feel like chatting with Cursor instead of using a traditional Eclipse tool.
