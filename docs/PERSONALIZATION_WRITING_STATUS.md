# Writing implementation — points 17–29

Status on 2026-10-07: Android source integrated; pure engine behavior checked. No device or Android build validation is claimed.

## Integration

`UniversalWritingInstaller.install(application)` installs a single lifecycle/focus entry point for standard Android text controls in activity windows. Existing selection/insertion action-mode callbacks are delegated. The selection menu exposes manual writing tools. `showTools(field)` provides an accessible explicit button entry point. `bind(field, documentKey)` opts an identified document into encrypted drafts. `SuggestionBoxView` uses this path and shows a dedicated tools/recovery button.

Numeric, phone, password, email, URI, person-name and postal-address input types are excluded. A module must correctly declare its field input type: ordinary text used for a technical identifier cannot be inferred safely. Dialog windows and custom/non-EditText inputs require explicit binding/entry points; the global activity focus listener does not prove universal field coverage.

| Point | Implemented | Remaining |
| --- | --- | --- |
| 17 | Shared Android engine, input-type exclusions, real feedback-screen integration | Inventory/migrate every module, custom field and dialog; iOS parity |
| 18 | Small conservative French spelling vocabulary, manual acceptance, dictionary exclusions | Full grammar/conjugation/agreement and multilingual checking |
| 19 | Selected known spelling/typing errors; same explicit check on pasted text | General typo/spacing detection without damaging intentional formatting |
| 20 | Original/replacement preview, accept/ignore, revision/content guard | Inline underlining and richer explanations |
| 21 | Account/language-scoped encrypted dictionary; add/edit/remove/paste terms; bounded import | Rich variants and shared module vocabulary |
| 22 | Explicit local dictionary prefix completions | Contextual word/phrase prediction |
| 23 | Not implemented; no remote content processing | Actual optional reformulation with fact/negation preservation |
| 24 | Existing system keyboard dictation remains untouched | Integrated recorder/transcription preview/pause/stop flow |
| 25 | Conservative surrogate/combining/ZWJ/emoji-modifier/flag boundary rejection; preserves undo selection; does not modify active composition | Device/IME/RTL/accessibility validation; full grapheme segmentation certification |
| 26 | Feedback draft opt-in; atomic AES-GCM files, account/document isolation, noBackup directory, 7-day expiration/launch purge, storage-failure message, explicit recovery and deletion | Other document bindings; measured crash window; cross-device recovery intentionally absent |
| 27 | Local undo/redo, grouped rapid typing, snapshot selection, bounded history, redo invalidation | Keyboard shortcuts and all custom controls |
| 28 | 200 ms history capture debounce, no automatic correction, IO on one worker, analysis disabled above 50,000 characters, bounded history | Real 1,000/50,000-character latency/memory/frame measurements on supported devices |
| 29 | Single local manual writing entry point; OS keyboard owns its corrections/composition | Complete cross-platform/multimodule adoption audit |

## Privacy and migration

No text, dictionary or audio is sent to a remote assistance service. Writing assistance reads the canonical `PersonalizationStoreV2` switch; draft preservation and undo remain available independently. Files are encrypted with an Android Keystore AES-GCM key and authenticated account/document identity; dictionary and draft text are outside device backups. Active bound draft fields and all editing histories are cleared on account changes. Open tools, suggestion, recovery and dictionary dialogs are dismissed on account changes. Pending IO keeps its captured scope. Writes are serialized and atomic. A successful idea send clears only the unchanged text for the same account; later edits are preserved.

The prior `user_feedback/draft_idea` value was unscoped plaintext. It is no longer automatically displayed or updated and its key is excluded from generic backup. A separate legacy recovery action requires explicit confirmation of ownership before encrypting it for the current account. Only a successful atomic encrypted write permits deleting the old value; the recovered legacy copy uses a separate document key so a concurrent newer draft is not overwritten. The new engine never silently attributes old content to the signed-in account.

Draft saves are requested after 500 ms of inactivity and on activity pause/detach. This is a target scheduling delay, not a crash durability guarantee: worker backlog, OS scheduling and storage affect completion. Empty documents remove their saved draft. No document identity is inferred from a view index, label or screen position. History/draft clearing does not undo a remote submission.

## Validation evidence

The repository JUnit file `WritingEngineTest.kt` covers stale suggestions, original selection undo/redo, redo branch invalidation, dictionary exclusion/completion, Unicode cluster boundaries and long-text analysis cutoff.

Executed independently using the already installed Gradle Kotlin compiler 2.3.21 and Java 17: compiled the actual `WritingEngine.kt` plus a standalone behavior harness, then ran it. Result: PASS for correction, original selection undo/redo, stale rejection, branching, dictionary, completion, Unicode, and long text. This does not validate Android classes or replace the project test/build gates. The normal project build remains blocked by dependency resolution in this environment, as reported by the main integration task.
