# iOS personalization implementation status — 2026-10-07

This is implementation evidence, not release approval. The 46-point specification is NOT complete.

## Integrated code

- **1, 9, 30, 31 (partial):** Canonical versioned visual preference repository, separate guest/account keys; live ObservableObject application, immediate session switch; no payroll data in the profile. Legacy `hp_theme` migrates once into the guest profile. Settings explicitly state local storage only. Auth activation regenerates a transient session token: the preferences subtree is recreated, pending import/export/reset presentations are dismissed, and delayed callbacks or bindings from an old session are ignored. No identity is logged.
- **2, 15, 16 (partial):** System/light/dark appearance, two existing accents, temporary night/economy contexts without overwriting base settings; last-change undo/redo; visual reset preserves writing setting. JSON export and size-bounded import, exact schema/type/value validation, complete preview and explicit restore. Invalid import preserves existing data; export contains neither identity nor permissions.
- **3, 34–38, 42–44, 46 (partial):** Root Dynamic Type additive size steps (0–5), preserving the system accessibility minimum and clamping at platform maximum; all existing material cards consume one opacity/contrast-aware component; celestial date uses an opaque semantic-color support. Visual settings persist per account. System accessibility preferences remain authoritative.
- **17, 25, 29, 30 (partial):** All existing SwiftUI text fields now consume `SharedTextFieldV2`, using native IME/selection/dictation/undo behavior. Structured inputs disable autocorrection/capitalization; descriptive source fields can opt into system spelling, controlled by a setting. This is not an AI spelling/grammar engine.
- **3, 46 (partial):** Reduced motion disables cloud/star decoration and transaction animations. Céleste keeps tabs visible with VoiceOver, accessibility text, or reduced motion. No timer-based decoration remains active in the removed cloud/star subtree.

## Verification

- `git diff --check`: passed after implementation.
- Source inventory: a single raw `TextField` remains, inside the shared component.
- Five Foundation-only tests added to the existing `RuntimeV2ContractTests` package target: schema/type/value rejection; round-trip; system-size clamping; contextual override restoration; account isolation/migration/failed-write preservation; corrupt stored data preservation (five test methods).
- `bash scripts/agent-toolbox.sh ios-tests`: BLOCKED, command reports macOS/Xcode required.
- `bash scripts/agent-toolbox.sh ios-build`: BLOCKED, command reports macOS/Xcode required.
- No Swift compiler or Apple SDK is installed in this execution environment. Tests are authored, NOT passed. No iOS binary has been built or distributed.

## Remaining gaps

- **4–8, 10–14:** No new translations, AI personality/relationship/learning/autonomy system, notification engine, permission dashboard, or cross-device synchronization. Existing business permissions are unchanged.
- **18–24:** No app-owned grammar, reformulation, personal dictionary, semantic protection engine or speech-recording pipeline. Native keyboard services only.
- **26–28:** No new persistent iOS drafts, app-owned undo stack or measured long-input performance guarantee.
- **32, 33, 45:** Physical-device, VoiceOver, landscape/small-screen/large-text, keyboard and regression matrix not executed. iOS/iPad universal support cannot be certified; existing iOS target remains iPhone.
- **34–37:** Full per-theme contrast measurement, graph/image label protection and complete component audit remain required. Opaque cards/actions are covered; this is not a verified global WCAG claim.
- **39–41:** No new document/image/graph pinch/pan viewer. Dynamic Type is UI reading enlargement, not universal content zoom.
- **44:** Values are account-isolated on this device, not synchronized. UserDefaults persistence is not a transactionally acknowledged durability guarantee.
- Android and iOS export schemas are currently different; interchange is not supported or advertised.
- Separate global source modules outside this repository (e.g. Genesis) are not modified.

## Merge/release

Requires successful macOS package tests + Xcode build, focused device accessibility/input validation, and the repository's exact-SHA specialist → team_lead → qa_reviewer → control_gate chain. No gate approval or release is asserted here.
