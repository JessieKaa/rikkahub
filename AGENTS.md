# Repository Guidelines

## Project Overview

RikkaHub is a native Android LLM chat client that supports switching between different AI providers
for conversations.
Built with Jetpack Compose, Kotlin, and follows Material Design 3 principles.

## Development Coordination

- The main agent owns approvals, task assignment, and coordination/management actions.
- Implementation (code and document edits, validation) and review are delegated to subagents.
- Run at most 10 subagents concurrently, counting implementation and review agents.
- All subagents use model `mmyds/deepseek-v4-flash` with thinking intensity `xhigh`.
- When the specified model or thinking level is unavailable, stop, report the blocker, and request approval before any
  substitution. This guidance supersedes prior coordination preferences that named other models or thinking levels.

## Persistent Design Context

### Family Chat-Only Customization

- Read [the design](docs/plans/family-mode-design.md) and [the implementation roadmap](docs/plans/family-mode-roadmap.md)
  before working on family-mode, administrator access, chat-only UI, or related navigation restrictions.
- Recorded status: main implementation has landed in the working tree. Compilation and JVM unit tests
  are verified with `-x processDebugGoogleServices -x buildWebUi`: `:app:testDebugUnitTest` passed 354/354 (46 suites).
  The owner-approved optional no-op `AnalyticsTracker` fallback is implemented and leaves the default Firebase behavior
  unchanged when configured, so private debug chat runs without Firebase config. The excluded debug APK
  (`app-arm64-v8a-debug.apk`, SHA-256 `94a4fc84d2b9439b34cf3e4ebebdd364fd42efc76515103cd83b3b30c694195f`, package
  `me.rerere.rikkahub.debug`, v2.5.5/code 190) was installed with `install -r` and passed focused ADB real-device checks
  (PIN lock/unlock/complete-management/force-stop/background relock, share/PROCESS_TEXT decode, dynamic shortcut gating
  with camera retained). Signed release/build configuration and remaining capability tests (real providers, ASR/TTS,
  MCP, workspace, full Web, pinned legacy shortcuts, OEM, formal instrumented suite) are pending; P1–P5 are implemented
  with core device validation, and P6 is partially completed. See roadmap §8 and
  `/tmp/rikkahub-family-device/EXECUTOR_REPORT_R3.md`.
- Shared implementation now exists; proposed components/fields may differ from the source. Exact API (naming, signatures,
  state semantics) must be confirmed against the source. Verified naming includes `FamilyModeStore`,
  `FamilyModeController`, `FamilyModePolicy`, `FamilyPinCrypto`, `EffectiveAssistantResolver`. Unverified gaps include
  real SettingsStore/backup integration, permissions, service notifications, camera/voice, UI config fingerprint, and
  SDK/OEM behavior.
- The owner initializes all configuration. Family mode exposes chat and approved chat-related actions; a hidden entry
  plus a local administrator PIN restores full management access.
- First-release scope: one family assistant/model, existing chat UI, preserved configured runtime capabilities, and a
  disabled web server while family mode is locked. Keep device access-control/PIN records separate from configuration backups.
- Apply a centralized policy to navigation, external intents, restored stacks, configuration callbacks, and conversation
  ownership. Preserve message persistence, automatic memory, MCP token refresh/tool discovery, and per-call tool approval.
- Treat settings-load recovery, context-bound MCP tool selection, and completion-aware web shutdown as required
  implementation edges; the documents contain their acceptance criteria.
- Update the roadmap with changed files, verification evidence, blockers, and next steps after each implementation stage.
  Keep both documents and this status index consistent when scope or implementation status changes.

## Build, Test, and Development Commands

```bash
./gradlew assembleDebug          # 构建 Debug APK
./gradlew test                   # 运行所有模块的 JVM 单元测试
./gradlew lint                   # 运行 Android Lint
```

## Module Structure

- **app**: Main application module with UI, ViewModels, and core logic
- **ai**: AI SDK abstraction layer for different providers (OpenAI, Google, Anthropic)
- **common**: Common utilities and extensions
- **document**: Document parsing module for handling PDF, DOCX, PPTX, and EPUB files
- **highlight**: Code syntax highlighting implementation
- **material3**: Material color utility extensions used by the app UI
- **search**: Search functionality SDK for multiple providers (Exa, Tavily, Zhipu, Bing, Brave, SearXNG, and others)
- **speech**: Speech module for TTS and ASR implementations
- **web**: Embedded web server module that provides Ktor server startup function and hosts static frontend build files (
  built from web-ui/ React project)
- **workspace**: Sandboxed per-workspace file system and shell execution environment exposed to the AI as tools.

## Concepts

- **Assistant**: An assistant configuration with system prompts, model parameters, and conversation isolation. Each
  assistant maintains its own settings including temperature, context size, custom headers, tools, memory options, regex
  transformations, and prompt injections (mode/lorebook). Assistants provide isolated chat environments with specific
  behaviors and capabilities. (app/src/main/java/me/rerere/rikkahub/data/model/Assistant.kt)

- **Conversation**: A persistent conversation thread between the user and an assistant. Each conversation maintains a
  list of MessageNodes in a tree structure to support message branching, along with metadata like title, creation time,
  update time, pin status, chat suggestions, optional conversation-level system prompt, and prompt injection bindings. (
  app/src/main/java/me/rerere/rikkahub/data/model/Conversation.kt)

- **UIMessage**: A platform-agnostic message abstraction that encapsulates chat messages with different types of content
  parts (text, images, documents, reasoning, tool calls/results, etc.). Each message has a role (USER, ASSISTANT,
  SYSTEM, TOOL), creation timestamp, model ID, token usage information, and optional annotations. UIMessages support
  streaming updates through chunk merging. (ai/src/main/java/me/rerere/ai/ui/Message.kt)

- **MessageNode**: A container holding one or more UIMessages to implement message branching functionality. Each node
  maintains a list of alternative messages and tracks which message is currently selected (selectIndex). This enables
  users to regenerate responses and switch between different conversation branches, creating a tree-like conversation
  structure. (app/src/main/java/me/rerere/rikkahub/data/model/Conversation.kt)

- **Message Transformer**: A pipeline mechanism for transforming messages before sending to AI providers (
  InputMessageTransformer) or after receiving responses (OutputMessageTransformer). Transformers can modify message
  content, add metadata, apply templates, handle special tags, convert formats, and perform OCR. Common transformers
  include:
  - TemplateTransformer: Apply Pebble templates to user messages with variables like time/date
  - ThinkTagTransformer: Extract `<think>` tags and convert to reasoning parts
  - RegexOutputTransformer: Apply regex replacements to assistant responses
  - DocumentAsPromptTransformer: Convert document attachments to text prompts
  - Base64ImageToLocalFileTransformer: Convert base64 images to local file references
  - OcrTransformer: Perform OCR on images to extract text

  Output transformers support `visualTransform()` for UI display during streaming and `onGenerationFinish()` for final
  processing after generation completes.
  (app/src/main/java/me/rerere/rikkahub/data/ai/transformers/Transformer.kt)

## Internationalization

- String resources are usually located in `app/src/main/res/values*/strings.xml`; feature modules such as `search`
  may also maintain their own `values*/strings.xml`
- Use `stringResource(R.string.key_name)` in Compose
- Page-specific strings should use page prefix (e.g., `setting_page_`)
- If the user does not explicitly request localization, prioritize implementing functionality without considering
  localization. (e.g `Text("Hello world")`)
- For `locale-tui` operations, use the `locale-tui-localization` skill.
