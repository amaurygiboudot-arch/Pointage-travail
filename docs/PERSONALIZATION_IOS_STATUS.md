# iOS personalization implementation status — 2026-10-07

This is implementation evidence, not release approval. The 46-point specification is NOT complete.

## Integrated code

- **1, 9, 30, 31 (partial):** Canonical versioned visual preference repository, separate guest/account keys; live ObservableObject application, immediate session switch; no payroll data in the profile. Legacy `hp_theme` migrates once into the guest profile. Native preferences remain local; an explicit manual cloud screen transfers only the three shared comfort preferences. Auth activation regenerates a transient session token: the preferences subtree is recreated, pending import/export/reset presentations are dismissed, and delayed callbacks or bindings from an old session are ignored. No identity is logged.
- **2, 15, 16 (partial):** System/light/dark appearance, two existing accents, temporary night/economy contexts without overwriting base settings; last-change undo/redo; visual reset preserves writing setting. JSON export and size-bounded import, exact schema/type/value validation, complete preview and explicit restore. Invalid import preserves existing data; export contains neither identity nor permissions.
- **3, 34–38, 42–44, 46 (partial):** Root Dynamic Type additive size steps (0–5), preserving the system accessibility minimum and clamping at platform maximum; all existing material cards consume one opacity/contrast-aware component; celestial date uses an opaque semantic-color support. Visual settings persist per account. System accessibility preferences remain authoritative.
- **17, 25, 29, 30 (partial):** All existing SwiftUI text fields now consume `SharedTextFieldV2`, using native IME/selection/dictation/undo behavior. Structured inputs disable autocorrection/capitalization; descriptive source fields can opt into system spelling, controlled by a setting. This is not an AI spelling/grammar engine.
- **3, 46 (partial):** Reduced motion disables cloud/star decoration and transaction animations. Céleste keeps tabs visible with VoiceOver, accessibility text, or reduced motion. No timer-based decoration remains active in the removed cloud/star subtree.

## Verification

- `git diff --check`: passed after implementation.
- Source inventory: a single raw `TextField` remains, inside the shared component.
- Eight Foundation-only tests added to the existing `RuntimeV2ContractTests` package target: schema/type/value rejection; round-trip; system-size clamping; contextual override restoration; account isolation/migration/failed-write preservation; corrupt stored data preservation (eight test methods, including zoom boundaries, legacy JSON zoom migration and bounded real-file imports).
- `bash scripts/agent-toolbox.sh ios-tests`: BLOCKED, command reports macOS/Xcode required.
- `bash scripts/agent-toolbox.sh ios-build`: BLOCKED, command reports macOS/Xcode required.
- No Swift compiler or Apple SDK is installed locally. GitHub macOS build and package tests passed for 8a57e032; this does not validate subsequent changes. No store distribution has occurred.

## Remaining gaps

- **4–8, 10–14:** No new translations, AI personality/relationship/learning/autonomy system, notification engine, complete permission dashboard, or automatic cross-device synchronization. Manual versioned cloud comfort save/restore is now implemented for the three shared fields. A real iOS permissions section now reads location/notification grants and opens system settings; it does not request unused permissions or claim notifications are scheduled. Existing business permissions are unchanged.
- **18–24:** No app-owned grammar, reformulation, personal dictionary, semantic protection engine or speech-recording pipeline. Native keyboard services only.
- **26–28:** No new persistent iOS drafts, app-owned undo stack or measured long-input performance guarantee.
- **32, 33, 45:** Physical-device, VoiceOver, landscape/small-screen/large-text, keyboard and regression matrix not executed. iOS/iPad universal support cannot be certified; existing iOS target remains iPhone.
- **34–37:** Full per-theme contrast measurement, graph/image label protection and complete component audit remain required. Opaque cards/actions are covered; this is not a verified global WCAG claim.
- **39–41 (partial):** A native reading sheet is connected to salary warnings/feedback and pointage errors, with pinch and accessible ±/reset controls, reflow, scrolling, account-isolated optional saved zoom (100–400%). Existing generated salary PDFs open in native Quick Look (platform pinch/scroll controls), without replacing the exporter. Image/graph/3D zoom is not implemented. No device gesture verification has run.
- **44:** Values are account-isolated on this device, not synchronized. UserDefaults persistence is not a transactionally acknowledged durability guarantee.
- Native Android/iOS full export schemas differ. The explicit common three-field comfort contract supports manual text transfer and account snapshots.
- Separate global source modules outside this repository (e.g. Genesis) are not modified.

## Merge/release

Requires successful macOS package tests + Xcode build, focused device accessibility/input validation, and the repository's exact-SHA specialist → team_lead → qa_reviewer → control_gate chain. No gate approval or release is asserted here.

## Follow-up: native reading parity

- Added `ReadingViewV2` and opt-in `readingActionV2` modifier, used on actual feedback strings and consolidated salary warnings. Reader data stays in memory; opening a reader never changes business data. Auth session change dismisses readers and PDF preview, clears generated PDF UI state, and guards zoom persistence.
- Added backward-compatible `readerScale` in the existing v1 visual schema: old exports without this field use 1.5; malformed present fields fail atomically. Import reads at most 16,385 bytes and rejects anything above 16,384 before applying anything.
- Added three pure tests for zoom/nonfinite values, old/malformed JSON migration, and real-file bounds; account-isolation test now covers zoom.
- `ios-tests` and `ios-build` were attempted again: still blocked locally by absence of macOS/Xcode. New SwiftUI/Quick Look paths are not marked runtime-verified. `git diff --check` passed.

## Grouped follow-up

- Manual account snapshots with server reads, expected-revision transactions and tombstones; auth/session guards prevent stale UI callbacks.
- Native French installed voice reading with stop on dismissal/session/inactive scene; VoiceOver overlap avoided.
- Optional local night schedule, manual-context priority, foreground-only minute updates and migration of previous exports.
- Pure snapshot/concurrency and schedule tests added; current-SHA CI and actual device integration remain required.
