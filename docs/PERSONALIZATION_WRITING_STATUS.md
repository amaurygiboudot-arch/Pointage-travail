# Writing implementation — points 17–29

Status on 2026-10-07: Android source integrated; pure engine behavior checked. No device or Android build validation is claimed.

## Integration

`UniversalWritingInstaller.install(application)` installs a single lifecycle/focus entry point for standard Android text controls in activity windows. Existing selection/insertion action-mode callbacks are delegated. The selection menu exposes manual writing tools. `showTools(field)` provides an accessible explicit button entry point. `bind(field, documentKey)` opts an identified document into encrypted drafts. `SuggestionBoxView` uses this path and shows a dedicated tools/recovery button.

Numeric, phone, password, email, URI, person-name and postal-address input types are excluded. A module must correctly declare its field input type: ordinary text used for a technical identifier cannot be inferred safely. Dialog windows and custom/non-EditText inputs require explicit binding/entry points; the global activity focus listener does not prove universal field coverage.

| Point | Implemented | Remaining |
| --- | --- | --- |
| 17 | Shared Android engine, input-type exclusions, real feedback-screen integration | Inventory/migrate every module, custom field and dialog; iOS parity |
| 18 | French offline fallback plus explicit Android sentence spell-check provider, grammar suggestions when its provider supports them, dictionary exclusions and acceptance preview | Provider coverage varies by installed language/service; no guaranteed full grammar/conjugation/agreement checking |
| 19 | Local known errors plus native provider typo suggestions on typed/pasted selected text | Provider accuracy and device/language validation; conservative exclusions protect numeric content |
| 20 | Original/replacement preview, accept/ignore, revision/content guard | Inline underlining and richer explanations |
| 21 | Account/language-scoped encrypted dictionary; add/edit/remove/paste terms; bounded import | Rich variants and shared module vocabulary |
| 22 | Explicit local dictionary prefix completions | Contextual word/phrase prediction |
| 23 | Not implemented; no remote content processing | Actual optional reformulation with fact/negation preservation |
| 24 | Non-exported activity delegates to Android recognition UI, explains provider/network handling, editable transcript preview, explicit one-time insertion/cancellation and undo | Actual provider/device/no-service/interruption tests; pause/stop controls belong to the recognition provider and are not guaranteed |
| 25 | Conservative surrogate/combining/ZWJ/emoji-modifier/flag boundary rejection; preserves undo selection; does not modify active composition | Device/IME/RTL/accessibility validation; full grapheme segmentation certification |
| 26 | Feedback draft opt-in; atomic AES-GCM files, account/document isolation, noBackup directory, 7-day expiration/launch purge, storage-failure message, explicit recovery and deletion | Other document bindings; measured crash window; cross-device recovery intentionally absent |
| 27 | Local undo/redo, grouped rapid typing, snapshot selection, bounded history, redo invalidation | Keyboard shortcuts and all custom controls |
| 28 | 200 ms history capture debounce, no automatic correction, IO on one worker, analysis disabled above 50,000 characters, bounded history | Real 1,000/50,000-character latency/memory/frame measurements on supported devices |
| 29 | Single local manual writing entry point; OS keyboard owns its corrections/composition | Complete cross-platform/multimodule adoption audit |

## Privacy and migration

Local suggestions/dictionary never send text or audio. Two separately confirmed actions can invoke the configured Android spelling or speech provider; their consent screens explicitly explain that these providers may use Internet. There is no application backend, paid API, hidden fallback, audio recording or telemetry integration. Offline speech preference is requested but not claimed as an enforceable guarantee. Writing assistance reads the canonical `PersonalizationStoreV2` switch; draft preservation and undo remain available independently. Files are encrypted with an Android Keystore AES-GCM key and authenticated account/document identity; dictionary and draft text are outside device backups. Active bound draft fields and all editing histories are cleared on account changes. Open tools, suggestion, recovery and dictionary dialogs are dismissed on account changes. Pending IO keeps its captured scope. Writes are serialized and atomic. A successful idea send clears only the unchanged text for the same account; later edits are preserved.

The prior `user_feedback/draft_idea` value was unscoped plaintext. It is no longer automatically displayed or updated and its key is excluded from generic backup. A separate legacy recovery action requires explicit confirmation of ownership before encrypting it for the current account. Only a successful atomic encrypted write permits deleting the old value; the recovered legacy copy uses a separate document key so a concurrent newer draft is not overwritten. The new engine never silently attributes old content to the signed-in account.

Draft saves are requested after 500 ms of inactivity and on activity pause/detach. This is a target scheduling delay, not a crash durability guarantee: worker backlog, OS scheduling and storage affect completion. Empty documents remove their saved draft. No document identity is inferred from a view index, label or screen position. History/draft clearing does not undo a remote submission.

## Validation evidence

The repository JUnit file `WritingEngineTest.kt` covers stale suggestions, original selection undo/redo, redo branch invalidation, dictionary exclusion/completion, Unicode cluster boundaries and long-text analysis cutoff.

Executed independently using the already installed Gradle Kotlin compiler 2.3.21 and Java 17: compiled the actual `WritingEngine.kt` plus a standalone behavior harness, then ran it. Result: PASS for correction, original selection undo/redo, stale rejection, branching, dictionary, completion, Unicode, and long text. This does not validate Android classes or replace the project test/build gates. The normal project build remains blocked by dependency resolution in this environment, as reported by the main integration task.


## Native writing provider extension (2026-10-07)

`WritingSystemSpellChecker` uses Android `TextServicesManager` / `SpellCheckerSession.getSentenceSuggestions`. It analyzes only an explicitly selected passage (or a whole document up to 5,000 characters), after one-action consent. An 8-second timeout, per-request generation/cookie checks, document revision checks, pause/edit/account cancellation and explicit `close()` prevent old results from modifying later text. `WritingProviderEdits` validates offsets (including integer-overflow cases), rejects broken Unicode boundaries and changes involving original numeric passages, protects dictionary terms, bounds provider replacements and produces ordinary individually accepted engine suggestions. Empty results do not imply correct grammar.

`WritingDictationActivity` is non-exported and launched with an opaque one-use request token. Original field text is never placed in an Intent, clipboard or saved-instance state. `ACTION_RECOGNIZE_SPEECH` delegates microphone UI and permission behavior to the installed recognition activity; AGKGMG records no audio. The user sees provider/network disclosure before launching, then edits or rejects the returned transcript before insertion. Missing provider, cancellation, account switch, invalidated field/version and process death leave the original field untouched. One-use delivery prevents repeated insertion. The transcript remains only in a short-lived in-process request; orientation can restore that preview while the original target remains valid. Underlying activity recreation that invalidates the field cancels rather than guessing a new target. These lifecycle cases still require Android device tests.

Executed standalone pure Kotlin provider harness: PASS for invalid/overflowed ranges, numeric protection, Unicode boundaries, dictionary exclusion, stale native suggestions and undo after confirmed dictation replacement. Added the same cases in `WritingProviderEditsTest.kt`. Manifest parsed successfully and `git diff --check` passed. Full Android compilation and actual spelling/recognition UI tests are not claimed by these checks.

Official API references used:
- https://developer.android.com/reference/android/view/textservice/TextServicesManager
- https://developer.android.com/reference/android/view/textservice/SpellCheckerSession
- https://developer.android.com/reference/android/speech/RecognizerIntent
