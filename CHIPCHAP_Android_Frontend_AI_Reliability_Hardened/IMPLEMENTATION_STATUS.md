# CHIPCHAP implementation status — audit baseline

Audit date: 2026-09-13. Scope: inspection and baseline build only; no product features or UI were changed.

## A. Current architecture

- One Android Java app module with a single `MainActivity` hosting a full-screen WebView.
- 38 local Stitch HTML screens in `app/src/main/assets/screens`; a large JavaScript string injected by `MainActivity` provides routing, UI behaviors, and native callbacks through `window.Android`.
- Native capabilities: Firebase email/password registration/login, camera/gallery intents, runtime-permission request, Maps intent, screen history, and SharedPreferences role preference.
- Firebase config exists, but Gradle only includes `firebase-auth`. There is no Firestore, Storage, Functions, FCM, persistence layer, rules, test source, or repository layer.
- The Stitch source contains matching screens (the source name `uploading...` is packaged as `uploading`). Five packaged screens are exact copies; 33 contain navigation/behavior patches.

## B. What is already working (inspection only)

- SDK 35, min SDK 23, target SDK 35 Android configuration; package name matches `google-services.json`.
- Asset loading, `screen://` routing, back history, portrait orientation, citizen form validation, and Firebase `createUserWithEmailAndPassword` / `signInWithEmailAndPassword` calls with mapped errors.
- Camera/gallery result callback: JPEG data URI is passed to `window.nativePhotoSelected(dataUri)`.
- A historical debug APK exists (modified 2026-08-27) and can be parsed: `com.chipchap.app` v1.0, min SDK 23, target SDK 35. It is not proof of this audit's build.

## C. What is broken or unreliable

- The baseline Gradle build never reached project configuration. The Gradle 8.9 wrapper stopped repeatedly at `Downloading Gradle distribution: https://services.gradle.org/distributions/gradle-8.9-bin.zip`.
- Local Java is 23; Android Studio bundles Java 25. The project declares Java 17 and AGP 8.7.3, so JDK 17 should be installed/configured for reproducibility.
- Signup/login go directly to permissions. No verification email, reload, `emailVerified` check, or authenticated-entry gate exists.
- OTP is still phone/number UI with disabled digit inputs; Verify routes directly to permissions. Injected `otpVerify()` treats a locally entered six-digit value as success.
- Logout clears WebView state but never executes `FirebaseAuth.signOut()`.
- Government login is UI-only: arbitrary non-empty ID plus a six-character password routes to administration.
- Password reset/update, profile edit, comments, upvotes, assignment/dispatch, status updates, map, analytics, notifications, and upload progress are static/simulated.
- Camera normally returns only a thumbnail; gallery decodes the full bitmap before resizing. Neither image nor location is persisted.

## D. What is missing

- Required Firebase email-verification flow: send/resend email, reload user, check `emailVerified`, still-unverified state, verified-user bypass, and auth gate.
- Firebase-backed profile/report/evidence/comment/vote/notification/assignment/status/analytics/map data.
- Firestore/Storage dependencies, server-enforced security rules, schema/indexes, deployment configuration, and tests.
- Full-resolution media workflow, actual location acquisition, automated tests/lint setup, emulator/device validation, and release configuration.

## E. Brief vs. repository

The provided brief is specifically about repurposing the Stitch OTP screen to Firebase email verification. The repository has migrated citizen credential forms to email/password and added Firebase Auth, but it has not implemented the required verification behavior or enforcement.

- Preserved: the Stitch OTP screen/layout remains packaged.
- Not compliant: it still asks for a number/OTP, includes no email-verification message/resend/check actions, and can follow a local fake-success route.
- Not compliant: Firebase's `emailVerified` value is never read, so unverified users enter immediately.
- Design comparison: the package broadly retains the Civic Clarity design system (portrait layout, palette, typography, spacing, rounded controls). It is not a raw Stitch export because the assets have extensive navigation/script modifications and the native activity injects a global behavior layer.

## F. Build/test baseline

Attempted:

`./gradlew clean :app:assembleDebug --warning-mode all --stacktrace`

Result: **not established**. The pinned Gradle 8.9 distribution could not be downloaded, so there are no current compile, lint, test, warning, or APK results.

Locally verified: Android SDK platforms/build-tools exist; wrapper pins Gradle 8.9; AGP is 8.7.3; `google-services.json` matches the application ID; the historical APK's manifest is structurally valid.

Still requires working Gradle + JDK 17: dependency resolution, compilation, resources, DEXing, signing, lint, and tests.

Still requires emulator/device + Firebase configuration: WebView runtime behavior, permissions, camera/gallery, Firebase Email/Password enablement, verification email delivery/action links, `emailVerified` reload, key restrictions, and backend authorization.

## G. Security concerns

1. **Critical:** administration is accessible through a client-only fake login. Use trusted provisioning/custom claims and server-enforced rules before exposing data/actions.
2. **High:** JavaScript is enabled and the bridge is available to every page while all screens load remote Tailwind CDN code and many remote images. Compromised remote content can call privileged bridge methods. Add strict navigation/origin controls, package executable dependencies, and minimize/validate bridge APIs.
3. **High:** `setAllowFileAccess(true)`, `setAllowContentAccess(true)`, and `usesCleartextTraffic="true"` unnecessarily widen WebView attack surface.
4. **High:** no verification/auth gate and no Firebase sign-out means UI state is not a reliable security boundary.
5. **High:** no Firestore/Storage rules are supplied; future access must never rely on hidden UI or SharedPreferences roles.
6. **Medium:** the Firebase API key in `google-services.json` is expected in Android clients but must be restricted to this package and signing certificate in Google Cloud.
7. **Medium:** fully decoding gallery images then injecting base64 into JavaScript risks memory pressure; use bounded URI/file-based processing.
8. **Medium:** simulated password/reset success messages create false security expectations.

## H. Recommended implementation order

1. Resolve Gradle 8.9 availability and configure JDK 17; build, lint, and run an emulator baseline.
2. Harden the WebView boundary: package dependencies, strict navigation allowlist, remove unneeded file/content/cleartext access, and reduce/validate bridge methods.
3. Implement the brief: preserve/reword the OTP Stitch screen for email verification; send/resend, reload user, check `emailVerified`, and guard application entry.
4. Correct session lifecycle: startup state, verified-user bypass, Firebase sign-out, real password reset/update, and auth-state tests.
5. Define backend data model, trusted admin role mechanism, and Firestore/Storage rules before any persisted/admin mutation.
6. Replace mocks incrementally: reports/evidence, then status/timeline, map/feed, notifications, then government workflows.
7. Finish media/location handling, device coverage, offline/error states, accessibility, and release hardening.

## I. Blockers / information needed

- A reachable Gradle 8.9 distribution (or approved wrapper change) and JDK 17.
- Firebase console access/confirmation: Email/Password enabled, verification-link domain/action policy, supported auth providers, and Android API-key package/SHA restrictions.
- Government role/provisioning model; Firestore/Storage schema, rules, privacy/retention requirements, and environment separation.
- Emulator/device plus a safe Firebase test project for end-to-end validation.

## Scope guard

No feature implementation, UI redesign, WebView migration, SMS/OTP implementation, architecture migration, or Stitch source modification was performed during this audit.

## Task 1 — reproducible build environment (2026-09-13)

### Toolchain verification and repair

- **Gradle wrapper:** remains pinned to **Gradle 8.9** (`gradle/wrapper/gradle-wrapper.properties`).
- **Android Gradle Plugin:** remains **8.7.3** (`build.gradle`). No Gradle or AGP version was changed.
- **Java compatibility:** app compilation explicitly targets Java 17. AGP 8.7.3 requires Java 17 or newer to run; this baseline uses **Homebrew OpenJDK 17.0.20.1** at `/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`. `gradle.properties` now sets `org.gradle.java.home` to that path, so the standard wrapper command uses JDK 17 without shell-specific exports.
- **Wrapper repair:** the original `gradle-wrapper.jar` was a 2.5 KB Java-21 classfile (major version 65), which could not launch with Java 17. It was regenerated using a checksum-verified official Gradle 8.9 distribution; the resulting wrapper main class is classfile major version 50 and launches correctly on JDK 17. Wrapper scripts and the requested Gradle version remain unchanged.
- **Distribution/network result:** the official 136 MB Gradle 8.9 archive downloaded successfully and its SHA-256 matched the official published checksum. The wrapper now starts normally. No external network blocker remains.

### Verified command and result

`./gradlew clean :app:assembleDebug --warning-mode all --stacktrace`

Result: **BUILD SUCCESSFUL**. The first verified JDK-17 run completed in 17 seconds; the exact bare command was then rerun after the project-level JDK setting and completed in 2 seconds. Both runs executed 36 tasks.

Verified artifact: `app/build/outputs/apk/debug/app-debug.apk`, built 2026-09-13 23:11:35 +0530, 4,515,777 bytes. `aapt` confirms package `com.chipchap.app`, version 1.0, min SDK 23, target SDK 35.

### Important non-fatal warnings

- SDK XML warning: installed command-line SDK tooling understands XML schema up to version 3 while it encountered version 4. Align Android Studio and command-line tools when convenient.
- D8 logged an internal Kotlin-metadata rewriting warning for `com.google.firebase.auth.AuthKt`; DEXing completed and the APK was produced. Re-check after future Firebase/AGP upgrades.
- `MainActivity.java` uses deprecated Android APIs (notably the legacy activity-result/back APIs); compilation succeeded.
- `libdatastore_shared_counter.so` could not be stripped and was packaged unchanged; this is normal for that native dependency but increases artifact size slightly.

No product features were implemented as part of Task 1.

## Phase 1 — Firestore foundation (2026-09-22)

Scope: native Firestore data layer only. No UI change, no WebView/architecture change, no rewrite
of Firebase Authentication, no camera/gallery/Storage/GPS/maps/notifications/AI/community work.

### Files changed

Added:

- `app/src/main/java/com/chipchap/app/models/UserProfile.java`
- `app/src/main/java/com/chipchap/app/models/Complaint.java`
- `app/src/main/java/com/chipchap/app/models/TimelineEvent.java`
- `app/src/main/java/com/chipchap/app/data/UserRepository.java`
- `app/src/main/java/com/chipchap/app/data/ComplaintRepository.java`
- `app/src/main/java/com/chipchap/app/data/TimelineRepository.java`
- `app/src/main/java/com/chipchap/app/data/RepositoryCallback.java`
- `app/src/main/java/com/chipchap/app/data/FirestoreErrors.java`
- `firestore.rules`, `firebase.json`, `FIRESTORE_SECURITY.md`

Modified:

- `app/build.gradle` — added `implementation 'com.google.firebase:firebase-firestore'` under the
  existing Firebase BOM 34.18.0. No version, plugin or SDK level was changed.
- `app/src/main/java/com/chipchap/app/MainActivity.java` — six small edits: imports, a
  `UserRepository` field, its construction in `onCreate`, a Firestore profile write after
  successful registration, and a profile backfill on both verified entry points
  (`routeAfterAuthentication` after sign-in, and `restoreSession` when an existing verified
  session is restored). Roughly 30 lines. Nothing else in `MainActivity` was refactored.

### Firestore structure

```
users/{uid}
    uid, email, fullName, neighborhood, role, createdAt, updatedAt

reports/{reportId}
    reportId, citizenUid, citizenName, title, description, category, status,
    latitude, longitude, address, localImagePath,
    assignedGovernmentUid, assignedGovernmentName, createdAt, updatedAt

reports/{reportId}/timeline/{timelineId}
    status, message, actorUid, actorName, createdAt
```

All timestamps are Firestore server timestamps (`FieldValue.serverTimestamp()` for the profile
transaction, `@ServerTimestamp` for report and timeline writes). `reportId` and `timelineId` are
Firestore-generated document ids. Initial report status is `SUBMITTED`.

`localImagePath` stores a local-device file reference only. Firebase Storage is not on the current
plan, so no evidence is uploaded and no Base64 image data is written to Firestore.

### Authentication and role handling

- Firebase Authentication is unchanged. Registration, login, verification email, resend, verified
  check, session restore, logout, password reset and password update all behave as before.
- Every write derives identity from `FirebaseAuth.getCurrentUser()`. No repository method accepts a
  uid, `citizenUid` or `actorUid` argument, so JavaScript cannot supply an owner.
- `createdAt` is written once at profile creation; the update path omits it so Firestore preserves
  it. `updatedAt` is refreshed on every write. Creation and update run inside a Firestore
  transaction so a concurrent write cannot lose `createdAt`.
- `role` is written once at creation as `CITIZEN`. `UserProfile` defines `CITIZEN` and `GOVERNMENT`,
  but no client code path can set `GOVERNMENT`.
- The `chipchap_profile` SharedPreferences write that stored `neighborhood` was removed; Firestore
  is now the authoritative application profile store. SharedPreferences is not used for
  authorization anywhere, and no `isAdmin` flag was introduced.
- `denyAdminAccess()` is unchanged: administration screens remain blocked in this build.

### Build result

`./gradlew clean :app:assembleDebug`

Result: **BUILD SUCCESSFUL in 23s**, 36 actionable tasks executed, zero errors. Run on the same Mac
that produced the Task 1 baseline (JDK 17, Gradle 8.9, AGP 8.7.3 — unchanged).

Artifact: `app/build/outputs/apk/debug/app-debug.apk`, 8,095,933 bytes, built 2026-09-22 15:20 IST.
The Task 1 baseline APK was 4,515,777 bytes; the increase is the Firestore SDK being packaged.

All fifteen new classes are present in the compiled output and dexed into the APK:
`data/UserRepository`, `data/ComplaintRepository`, `data/TimelineRepository`,
`data/RepositoryCallback`, `data/FirestoreErrors`, `models/UserProfile`, `models/Complaint`,
`models/TimelineEvent` (plus the two transaction/comparator inner classes), alongside the existing
`MainActivity` classes.

Warnings only, and the same four the Task 1 baseline already recorded — this change introduced no
new warning category:

1. SDK XML version 4 encountered, tooling understands up to version 3.
2. `MainActivity.java` uses or overrides a deprecated API (legacy activity-result/back APIs).
3. D8 Kotlin-metadata rewriting warning for `com.google.firebase.auth.AuthKt`.
4. `libdatastore_shared_counter.so` could not be stripped and was packaged unchanged.

These were deliberately left alone: fixing them is unrelated to the Firestore foundation.

### Also verified

- The eight new data-layer files compile cleanly under Java 17 against exact stubs of the Firebase
  and GMS Tasks signatures they use (`javac`, zero errors, zero warnings). This was run before the
  Gradle build as an early signature check.

### What was NOT verified

- No device or emulator run. No real write to the `chipchap-e967b` Firestore was performed, so the
  profile write, the `createdAt`-preserving transaction and the rules have **not** been exercised
  end to end. A successful compile proves the data layer builds, not that it behaves correctly
  against live Firestore.
- `firestore.rules` has not been deployed or emulator-tested. See `FIRESTORE_SECURITY.md`.
- Registration, login, verification, logout and password flows were not re-run on a device after
  this change. The auth code paths themselves are unmodified apart from the added profile write.

### Security-rule status

`firestore.rules` covers the three collections above and denies everything else. Government access
is gated on a Firebase custom claim (`request.auth.token.role == 'GOVERNMENT'`) that nothing
provisions yet, so those branches currently deny everyone — the rules fail closed. The Firestore
`role` field is descriptive profile data and is never used as an authorization decision. No
insecure workaround was added. Full detail and the remaining blocker are in `FIRESTORE_SECURITY.md`.

### Next phase

Phase 2 (reports): wire the existing capture/details/review Stitch screens to
`ComplaintRepository`, replace the demo tracking ID with the real `reportId`, and write the initial
`SUBMITTED` timeline entry. Before that, deploy and emulator-test the rules, and force an ID-token
refresh after email verification so `email_verified` in the token matches the verified user.

## Phase 2 — citizen complaint creation (2026-09-22)

Scope: make the existing capture → details → review → submit → success flow real. No UI redesign,
no architecture change, no Storage, no Gallery, no government provisioning, no AI, notifications,
community or analytics.

### Files changed

Added:

- `app/src/main/java/com/chipchap/app/data/ComplaintDraft.java`
- `app/src/main/java/com/chipchap/app/data/LocalPhotoRepository.java`
- `app/src/main/java/com/chipchap/app/data/LocationProvider.java`
- `app/src/main/res/xml/file_paths.xml`

Modified:

- `app/build.gradle` — added `androidx.core:core:1.13.1`. FileProvider lives there, and it is
  declared explicitly rather than relied on as a transitive Firebase dependency. Firebase BOM,
  AGP, Gradle and SDK levels are unchanged.
- `app/src/main/AndroidManifest.xml` — added `ACCESS_COARSE_LOCATION`, a `<queries>` entry so the
  camera intent resolves on Android 11+, and a non-exported `FileProvider` scoped to one folder.
- `app/src/main/java/com/chipchap/app/MainActivity.java` — camera rewrite, permission results,
  five new bridge methods, the submission pipeline, and `injectComplaintFlow()`.

No Stitch HTML/CSS asset was edited. All screen behaviour is injected at runtime, the pattern the
app already used.

### Camera

Camera only; `openGallery()` and its request code were removed from the bridge, and the existing
Gallery and "upload" controls now say plainly that gallery upload is not available in this build
rather than performing a fake action. The Stitch layout is untouched.

`ACTION_IMAGE_CAPTURE` is now launched with `MediaStore.EXTRA_OUTPUT` pointing at a FileProvider
URI, so the full-resolution image is written to an app-owned file. The previous implementation read
`data.getExtras().get("data")`, which returns only a thumbnail.

Handled explicitly, each with its own message and no fake success: permission denied, no camera app
installed, file creation failure, FileProvider failure, capture cancelled, a non-OK result, a
zero-byte file, and a file that cannot be decoded. A failed capture deletes the empty file.

### Local photo storage

`LocalPhotoRepository` writes to `getExternalFilesDir(Pictures)/complaint_photos` (internal files
as fallback) — app-owned, no storage permission, nothing in the user's gallery. Only the absolute
path is stored on the report. No bytes and no Base64 reach Firestore, and nothing is uploaded.

The WebView cannot reliably render a `file://` image from a `file:///android_asset` page, so a
bounded, downscaled JPEG data URI (max 1200px, quality 75, sample-size bounded decode) is generated
for preview only. It is held in memory, never persisted, and cleared after submission.

### Complaint draft

`ComplaintDraft` is an in-memory holder on the Activity carrying `localImagePath`, `title`,
`description`, `category`, `severity`, `latitude`, `longitude`, `address` and a truthful
`locationStatus`. It survives the three page loads that a JavaScript variable would not. Nothing is
written to disk. Fields are `volatile` because they are written on the main thread and read on the
WebView JavaScript-bridge thread.

### Details screen

Read from the existing UI: `#desc`, the severity radio group, and the category value. Two additions
were necessary because the Stitch screen does not contain them:

- A **Title** field, injected above Description. The report model requires a title and the screen
  had no input for one.
- The Category **Edit** button now opens a category picker (Infrastructure, Cleanliness, Safety,
  Other — the vocabulary already used elsewhere in the app). It was previously inert.

Also corrected for truthfulness, text only: the screen claimed the photo had been categorised
automatically by AI. No AI backend exists, so that caption, the "AI-suggested" subtitle and the
"AI ANALYSIS" badge wording were changed. The Description field said "Optional" but is required by
the model, so it now says "Required".

Validation is native, in `saveComplaintDetails`: title, description, category and a captured photo
are all required, and failure returns a truthful message and does not advance. "Skip" validates
too, since it otherwise bypassed required fields.

### Location

`LocationProvider` uses the platform `LocationManager` — no Maps SDK, no Play Services fused
provider, no map UI change. Permission is requested on the details screen, the screen that shows
location. It requests a fresh fix with a 20s timeout, falls back to a cached fix under 5 minutes
old, and reverse-geocodes on a background thread for the address.

Coordinates are never invented. Permission denied, location off, no provider, or no fix in time all
leave `latitude`/`longitude` null, write a truthful status line onto the screen, and still allow
submission. An address that cannot be resolved stays empty.

### Firestore submission

Review → Submit calls `submitComplaint()`. The flow refuses without an authenticated user, refuses
an incomplete draft, and guards against double submission. It then forces `getIdToken(true)` before
writing, because Firestore rules read `email_verified` from the ID token, which can lag a
just-verified user by up to an hour — the Phase 1 limitation, fixed by refreshing the token rather
than weakening the rules.

`ComplaintRepository` still derives `citizenUid` and `citizenName` from `FirebaseAuth`, generates
the document id, and forces `status = SUBMITTED` with null assignment fields. JavaScript supplies
only title, description, category and severity; it cannot supply ownership, status or role. No
generic Firestore write is exposed to the WebView.

### Timeline

`TimelineRepository.addEvent` is called only after the report document exists, with
`status = SUBMITTED`, message "Report submitted by the citizen.", `actorUid`/`actorName` from
Firebase Auth and a server timestamp.

If the report succeeds but the timeline write fails, the app does not claim total success or total
failure. The report genuinely exists, so it proceeds to the success screen with the real id and
shows an explicit warning that the history entry could not be written. Sending the user back to
review would risk a duplicate report.

### Success screen and report ID

Navigation to success happens only after the report document is confirmed created. The demo
`#CCP-8291` is replaced at runtime with the real Firestore document id. No tracking ID is generated
anywhere in the app.

On any Firestore failure the flow returns to `review_report` with the actual error and never
reaches success. After success the draft is cleared, the preview is released, the photo file is
deliberately kept on the device, and the back stack is reset so Back goes home rather than into a
flow whose draft no longer exists.

### What was verified

- All 11 data-layer and model files compile cleanly under Java 17 against exact stubs of the
  Firebase, AndroidX and Android APIs they use (`javac`, zero errors).
- `MainActivity.java` has no syntax errors and every new symbol resolves; the only remaining
  compiler messages are the absent `android.*` packages, unchanged in count from Phase 1.
- Both injected JavaScript payloads pass `node --check`, including the pre-existing universal
  script as a regression check.

### What was NOT verified

- `./gradlew clean :app:assembleDebug` has not been run for this change — it must be run locally.
- **No physical-device run.** The camera, FileProvider, permission prompts, location fix and the
  real Firestore write have not been exercised on hardware. Camera and location behaviour cannot
  be meaningfully tested without a real phone.
- No write has yet been made to the `chipchap-e967b` Firestore, and `firestore.rules` is still not
  deployed or emulator-tested.

### Known gap: severity is collected but not stored

The details screen collects severity, and it is carried in the draft and shown on the review
screen, but it is **not written to Firestore**. The Phase 1 `Complaint` model and the `hasOnly`
field allow-list in `firestore.rules` do not include it. Adding it needs a model field, a rules
change and a decision on the allowed values. Flagged rather than silently dropped or silently
added. Until then the review screen shows a severity the backend does not keep.

### Next phase

My Reports, then the tracking timeline screen — both read paths over data this phase now creates.
Rules still need deploying and emulator-testing first.

## Fix — injected JavaScript syntax error breaking every screen (2026-09-22)

Found by the first physical-device test. Logcat:

```
Uncaught SyntaxError: missing ) after argument list
source: file:///android_asset/screens/login_screen/code.html (22)
```

### Cause

The reported line is not line 22 of the Stitch asset — `evaluateJavascript` reports the page URL
as the source, so it is line 22 of the script injected by `injectUniversalUX()`.

That script is a Java text block. Six of its modal builders wrote `\'` to escape a quote inside a
JavaScript string, for example:

```
onclick="this.closest(\'.cc-modal-backdrop\').remove()"
```

`\'` is a valid **Java** escape, so the Java compiler strips the backslash and the WebView receives
a bare `'`. Inside the surrounding single-quoted JavaScript string that quote closes the string
early, and the argument list never terminates. 26 occurrences across lines 22-27.

V8 fails at parse time, so the **entire** universal script was discarded on every screen: no icon
substitution, no toasts, and — decisively — no click delegation. The Login button is wired only
through that delegated handler (`t==='login'` -> `citizenLogin()` -> `Android.citizenLogin(...)`),
which is why pressing Login produced no Firebase activity while Firebase itself initialised fine.

This is a pre-existing defect, present before the Phase 1 and Phase 2 work and unrelated to either.
It was missed because earlier JavaScript validation unescaped only `\\` and not `\'`, so the
extracted script parsed when the real one did not.

### Change

`MainActivity.injectUniversalUX()` only: `\'` -> `\\'` at all 26 occurrences, so Java emits `\'`
and JavaScript reads an escaped quote. Nothing else was touched — no Stitch asset, no
authentication, no Firebase or Firestore configuration, no UI.

### Verification

- The evaluated script was reconstructed with correct Java text-block escape semantics. Before the
  fix `node --check` reproduced the exact logcat error at line 22; after the fix both injected
  scripts parse.
- `passwordModal()` was executed with a stubbed `modal()`: it now emits
  `onclick="this.closest('.cc-modal-backdrop').remove()"`, correctly quoted HTML.
- `MainActivity.java` still parses with no syntax or illegal-escape errors.
- **Not device-tested.** Login has not been confirmed working on hardware.

### Separate finding — the email-verification screen is not wired (NOT fixed here)

Out of scope for this fix, recorded so it is not lost. The native bridge methods
`checkEmailVerification()`, `resendVerification()` and `getVerificationEmail()` exist and work, and
`setVerificationState()` calls `window.ccVerificationState(...)`, but **no bundled screen and no
injected script calls any of them**, and no screen defines `ccVerificationState`. The universal
script's only OTP rule is `resend code` -> `preventDefault()` with no action.

So an unverified user who signs in reaches `otp_verification` and cannot check verification, resend
the email, or see which address it went to. The native gate still blocks entry correctly, so this
is a dead end rather than a security hole. Wiring it is a small, separate task.

## Fix — citizen login routed to the phone-OTP screen (2026-09-22)

Found by physical-device test: after a successful email/password login the app showed the Stitch
"Verify Your Number" screen with the mock number +1 (555) 019-2834 and a 6-digit code field.

### Cause

`routeAfterAuthentication()` sent the citizen to `home_screen` only when
`refreshedUser.isEmailVerified()` was true, and to `otp_verification` otherwise. The test account's
email was not verified, so Firebase authentication succeeded and the app parked on that screen.

Two things made it a dead end rather than a delay:

1. `otp_verification` is still the original phone/SMS Stitch screen. It asks for a number and a
   6-digit code and was never re-authored for email verification.
2. As recorded in the previous entry, nothing wires that screen to the native bridge, so it has no
   working check, resend or "sent to" display.

CHIPCHAP uses Firebase email/password only. There is no phone provider, no SMS and no OTP anywhere
in the project, so that screen should never have been in the authenticated path.

### Changes — `MainActivity.java` only

| Routing condition | Before | After |
|---|---|---|
| `routeAfterAuthentication(...)` | verified -> `home_screen` / `app_permissions`; **unverified -> `otp_verification`** | signed in -> `app_permissions` after registration, otherwise `home_screen` |
| `restoreSession()` | verified -> `home_screen`; **unverified -> `otp_verification`** | signed in -> `home_screen` |
| protected-screen gate | `requireVerifiedUser()` -> reload, **unverified -> `otp_verification`** | renamed `requireSignedInUser()` -> signed in passes, signed out -> `login_screen` |
| `sendVerificationEmail(user, true)` | always navigated to `otp_verification` | still sends the Firebase email, then continues to `app_permissions` and reports the result |

The gate had to change too: without it, a signed-in user would reach the dashboard and then bounce
back to `otp_verification` on the first navigation, since every protected screen went through it.

Also added `refreshVerificationState()` — a fire-and-forget `user.reload()` after login and on app
open. It keeps `isEmailVerified()` current for the submission check now that no navigation calls
`reload()`. No routing waits on it or depends on its result.

### What did not change

Firebase email/password authentication, `signInWithEmailAndPassword`, registration, email
verification sending, password reset/update, logout, the Firebase project, Firestore architecture,
`firestore.rules`, government handling, the complaint flow, and every Stitch asset. No phone or SMS
authentication was added. Nothing is hard-coded as authenticated —
`FirebaseAuth.getCurrentUser()` remains the only source of authentication state. The
`otp_verification` asset and the `checkEmailVerification()` / `resendVerification()` /
`getVerificationEmail()` bridge methods remain in place, now unused.

### Verification

- `citizenLogin` still calls `signInWithEmailAndPassword`, then `routeAfterAuthentication(false)`
  -> `home_screen`.
- Logout still calls `auth.signOut()` and returns to `login_screen`.
- No Java code path, no bundled Stitch screen and no injected script navigates to
  `otp_verification` any more. The only remaining mentions are the inert `onPageFinished` hook and
  its entry in the unprotected-screen list.
- `MainActivity.java` parses with no syntax or escape errors; both injected scripts still pass
  `node --check`.
- **Not device-tested.** Whether login reaches the dashboard on hardware is unconfirmed.

### Consequence to decide: verification now gates writing, not entry

Unverified citizens can now use the app, but they cannot file a report:

- `startComplaintSubmission()` blocks with "Verify your email address before submitting a report."
- `firestore.rules` independently requires `request.auth.token.email_verified == true` to create a
  report, so the server would reject it regardless.

That is deliberate - the rules were not weakened - but it means an unverified citizen can browse
and hit a wall at submission, with no in-app way to resend the verification email. Three options,
all needing a decision:

1. Wire a resend/check action into a screen (the bridge methods already exist).
2. Relax the report-create rule to `isSignedIn()` and drop the native check.
3. Leave as is and rely on the verification email sent at registration.

## Citizen MVP integration fixes (2026-09-22)

Twelve issues from the first successful physical-device run. Several shared one root cause.

### The connected root cause

The universal click handler matched controls by their text:

```java
let t = text(el)   // includes the text of any icon <span> inside the control
```

Every Stitch list row is `<a><span class="material-symbols-outlined">lock</span><span>Update
Password</span><span>chevron_right</span></a>`, so `t` read `"lockupdate passwordchevron_right"`
and every exact-match rule failed. That single line is why **Update Password, Language
Preferences, Data Privacy Policy and the settings Sign Out button all did nothing**.

Fixed with a `label(el)` helper that clones the control, strips `.material-symbols-outlined`
descendants and returns the remaining text. Verified in jsdom: all four controls went from no
match to matching.

### Files changed

| File | Change |
|---|---|
| `MainActivity.java` | verification order, `label()` matching, icon rendering, profile/community/vote/comment bridges, `injectCitizenScreens()` |
| `data/LocationProvider.java` | per-provider probing |
| `data/ComplaintRepository.java` | persists severity; `loadCommunityReports()` |
| `data/VoteRepository.java` | **new** |
| `data/CommentRepository.java` | **new** |
| `models/Comment.java` | **new** |
| `models/Complaint.java` | `severity` field |
| `firestore.rules` | severity key, votes + comments rules, community read |

### 1. Email verification blocked a verified account

`startComplaintSubmission()` called `getIdToken(true)` and then `isEmailVerified()`.
`getIdToken(true)` refreshes the **token**; only `reload()` refreshes the **cached user record**
that `isEmailVerified()` reads. A genuinely verified account whose session predated verification
therefore looked unverified forever.

Now: `reload()` → re-read the user → check `isEmailVerified()` → `getIdToken(true)` → write. The
check and the `email_verified` rule are both untouched; only the order was wrong.

### 2. Icons rendered as dots

Two faults. The injected CSS forced `font-family:system-ui!important` onto
`.material-symbols-outlined`, so the Material Symbols webfont could never render even when it
loaded; and the fallback map covers ~70 of the 106 ligature names the screens use, with everything
else replaced by `•`.

Now the CSS override is gone, `icons()` checks `document.fonts.check(...)` and leaves the real
ligature in place when the font is available (re-running on `document.fonts.ready`). Only when the
font is genuinely unavailable does the curated map apply, and an unmapped name is hidden rather
than shown as a meaningless dot. `dataset.ccIcon` is now read before it is overwritten, so repeated
runs no longer corrupt the stored name.

### 3. Greeting

"Good morning, Alex." now comes from `users/{uid}.fullName`, falling back to the Auth display name
and then the email local part. No name available means "Good morning." with no name — never an
invented one. Time of day is computed, not assumed.

### 4. Location reported as off

`bestEnabledProvider()` wrapped all three provider probes in one `try`. On a device where the
`FUSED` probe throws, the whole method returned null and the UI said location was turned off even
with GPS on. Each provider is now probed separately, with a final pass over any other enabled
provider. The four states (permission denied / provider unavailable / not yet obtained / obtained)
are reported distinctly, and coordinates are still never invented.

### 5. Map visual

The dashboard photograph (`img[data-location]`) is replaced with a drawn CSS map captioned
"Static map preview - not live data". The two decorative pins were removed rather than left
implying live issues.

### 6. Severity

Three defects: it was only read at Continue; `writeComplaint()` never copied it onto the
`Complaint`; and neither the model nor the rules had the field. Now severity is pushed to the
native draft on every `change`, carried through draft → `Complaint` → repository → Firestore, and
allowed by `reportKeys()`.

### 7. Edit Profile

Loads `fullName` and `neighborhood` from `users/{uid}`, saves through `UserRepository` (which
refreshes `updatedAt` and derives the uid from Auth), and re-renders from the saved data. The fake
phone field was removed rather than kept as decoration. Nothing goes to SharedPreferences.

### 8. Update Password

Fixed by the `label()` change — the existing Firebase `updatePassword` bridge was already correct
and simply unreachable. Re-authentication failures surface the real Firebase error.

### 9. Language / Privacy — deferred, honestly

Language selection now carries a visible notice that the choice is not saved and the app is English
only. The privacy modal states plainly that no full policy is published in this build. Neither
claims success. **Both remain deferred features.**

### 10 & 11. Upvotes and comments

These needed real report ids, so the community feed and the dashboard "Recent Activity" now render
real reports from Firestore instead of static cards. Votes live at `reports/{id}/votes/{uid}` —
the uid **is** the document id, so repeat taps rewrite one document instead of inflating a count,
and the count is derived by reading the collection rather than from a client-writable counter.
Comments live at `reports/{id}/comments/{commentId}` with `authorUid` from Auth and empty text
rejected natively. A card with no stored report behind it says so instead of pretending to vote.

### 12. Mock data in the touched areas

Removed: "Alex", the stock map photograph, the fabricated "AI ANALYSIS" trend card on the
dashboard, the fake phone number, the static community cards. "MY REPORTS" is now a real count;
"CITY RESOLVED" shows `--` because no report can be resolved until government workflows exist —
deliberately blank rather than another invented number. Achievements, Success Stories, Volunteer
Events and notification screens are still static and remain **deferred**.

### Security note — one deliberate broadening

`reports` read changed from "own reports or government" to "any verified signed-in user". A
community feed is impossible otherwise. Writes are unchanged: create is still owner-only with
forced `SUBMITTED` status, update is still government-claim-only, delete is still denied. **This
widens who can read report contents and should be confirmed before deployment.**

### Verification

- All 13 data-layer and model files compile under Java 17 against exact API stubs (zero errors).
- `MainActivity.java`: zero syntax errors; every new symbol resolves.
- All three injected scripts pass `node --check` after correct Java text-block unescaping.
- `label()` proven in jsdom against the four broken controls.

### NOT verified

- **No Gradle build has been run for this change** — it must be run locally.
- **No physical-device test.** Camera, location, the icon font, and every Firestore path here
  (profile save, community feed, votes, comments) are unexercised.
- **No Firestore operation has ever been run against `chipchap-e967b`.** `firestore.rules` is
  still not deployed or emulator-tested — and the new votes/comments rules mean an outdated
  deployed ruleset will reject these writes.

## Device test round 2 (2026-09-23)

### Issues 1, 2, 4 — all three PERMISSION_DENIED failures are ONE cause

Report create, `users/{uid}` update and the community read all fail with the same message. They
share no rule condition, no collection and no field, so a per-feature bug cannot explain all three.
What they do share is that **`firestore.rules` in this repository has never been deployed to
`chipchap-e967b`.** A Firestore database created in production mode denies everything; a test-mode
database denies everything once it expires. Either matches exactly what the device shows.

The evidence that it is not a client bug: the Edit Profile form shows the correct name. That name
does **not** come from Firestore — `citizenDisplayName()` falls back to the Firebase Auth
`displayName` set during registration. So the `users/{uid}` read is failing silently too, and the
one Firestore operation that appears to work actually never ran.

Each client payload was re-checked line by line against the rules in this repo and they agree:

| Operation | Rule requires | Client sends |
|---|---|---|
| `users` create | uid match, `email == token.email`, `role == 'CITIZEN'`, both timestamps `request.time`, `hasOnly(userKeys)` | exactly those 7 keys |
| `users` update | uid match, `role`/`createdAt` unchanged, `updatedAt == request.time` | merge writing uid, email, fullName, neighborhood, updatedAt |
| `reports` create | verified, `citizenUid == auth.uid`, `reportId == docId`, `status == 'SUBMITTED'`, assigned fields null, `hasOnly(reportKeys)` | the 16-field `Complaint` POJO |
| `reports` read | verified | `orderBy(createdAt desc).limit(20)`, and `whereEqualTo(citizenUid)` for My Reports |

Both queries are single-field, so **no composite index is required**.

**This cannot be fixed from code.** Deploy the rules — see "What you must deploy" below.

To make the next round diagnosable rather than guesswork, `FirestoreErrors` now reports the real
Firestore status code, so the toast reads `PERMISSION_DENIED: the deployed Firestore security rules
rejected this...` instead of a generic sentence.

### Issue 3 — severity: found, proven, fixed

`txt()` prefers `innerText`, and `innerText` returns text **after** CSS `text-transform`. The
Review screen's two labels carry Tailwind's `uppercase` class, so `innerText` returned `"CATEGORY"`
and `"SEVERITY"`, the exact match against `'Category'`/`'Severity'` never fired, and **both values
kept their Stitch defaults** — which is why Severity read "Moderate", a word the app never writes.
The same screen's location card updated correctly because those spans are not uppercased.

Fixed by comparing lower-cased, and the value holders are now tagged
`data-cc-category-value` / `data-cc-severity-value` for stable future targeting. Two related
defects fixed at the same time: `saveComplaintDetails` could overwrite a stored severity with an
empty string when the radio read failed (it now ignores a null), and the "1 Photo attached" caption
is a text node on a wrapper div rather than inside a span, so it was never updated.

### Issues 5, 13 — profile identity and avatar

The Profile header was never wired: "Citizen User", "Level 5 Contributor" and "Joined April 2023 -
Verified Resident" are all static Stitch text. The header now renders the same
`users/{uid}.fullName` → Auth `displayName` → email-local-part chain the dashboard greeting uses,
the "Level 5" line shows the account email, and the stock headshot is replaced by a neutral initial
avatar in the existing circle. Layout unchanged.

### Issues 6-10 — map

Replaced with a local demo dataset of five issues (Severe Pothole, Broken Streetlight, Illegal
Dumping, Fallen Tree, Damaged Sidewalk), each with its own category, status, severity, distance,
backer count, demo place and marker position. Filters now filter, markers select, the X closes the
sheet, and Open in Maps uses the selected issue's demo place instead of "Downtown civic issues".
The map is captioned "Demo map - sample issues, not live reports" and View Details says plainly
that a demo issue has no stored report. Every map control is matched on a `data-cc-*` attribute,
never on visible text (issue 10).

### Issues 11, 12 — My Reports and Notifications

My Reports now queries `reports where citizenUid == auth.uid` and shows an honest empty state when
there are none. The four fabricated reports and the "AI ANALYSIS SUGGESTED" badge are gone.
Notifications had no backend at all, so the invented report ids, department replies, AI analysis
and milestone entries are replaced by an empty state — **notifications remain deferred**.

### Issue 14 — branding

No defect. Neither login nor signup contains an `<img>`; the wordmark is `<h1>CHIPCHAP</h1>` text,
and the icon pass only touches `.material-symbols-outlined` spans. Nothing altered it.

### What you must deploy

```
firebase deploy --only firestore:rules
```

or paste `firestore.rules` into Firebase console → Firestore Database → Rules → Publish.
It could not be deployed from this environment: no Firebase CLI and no network route to Google
APIs from either available shell.

### Verification

- All 13 data-layer and model files compile under Java 17 against exact API stubs (0 errors).
- `MainActivity.java`: 0 syntax errors; all new symbols resolve.
- All three injected scripts pass `node --check`.
- The severity cause was reproduced in jsdom: the old comparison fails, the new one passes.

### NOT verified

- No Gradle build for this change; no physical-device run.
- **No Firestore operation has ever succeeded against `chipchap-e967b`.** Until the rules are
  deployed, submission, profile save, the community feed, My Reports, votes and comments are all
  expected to keep failing, and none of this round's Firestore work can be confirmed.

## Fix pass — auth, camera, help, sharing, tracking (2026-09-23)

### 1. Console-created account — root cause

Two separate causes, not one.

**The permission denial was email verification.** `firestore.rules` gated every report read/write on
`isVerified()` → `request.auth.token.email_verified == true`. An account created in the Firebase
console has `emailVerified: false`, so every Firestore call failed. The app-created account worked
because its email had been verified. The missing `users/{uid}` document was a *second*, independent
problem that would have surfaced next.

Per the instruction that verification must not gate normal use, `isVerified()` now returns
`isSignedIn()`. **No ownership check changed**: a citizen still cannot forge `citizenUid`,
`actorUid`, `authorUid` or a vote document id, cannot set `status` or the assignment fields, and
cannot touch another user's profile. Only the `email_verified` precondition was removed, and the
function name is kept so the change is one line and reversible.

`firestore.rules` must be redeployed for this to take effect.

The native gate in `startComplaintSubmission()` is gone too. In its place, submission now calls
`ensureProfile()` first, which creates `users/{uid}` from `FirebaseAuth.getCurrentUser()` when it is
missing — so a console-created account is bootstrapped on its first submission. The uid still comes
only from Auth, and the role still defaults to `CITIZEN`.

### 2. Photo orientation

Camera apps normally write the sensor image plus an EXIF orientation tag rather than rotating
pixels, so a portrait capture was stored sideways. `LocalPhotoRepository.normalizeOrientation()`
now reads `TAG_ORIENTATION`, rotates (and mirrors, for the flipped/transposed cases) the pixels,
rewrites the JPEG at quality 92, and resets the tag to `NORMAL` so nothing rotates it twice. It runs
on the stored file before the preview is built, so the evidence itself is corrected, not just the
preview. Decoding is bounded to a 3000px long edge to avoid OOM, and any failure leaves the original
file untouched. Added `androidx.exifinterface:exifinterface:1.3.7`.

### 3. Flash — NOT implemented, and deliberately so

`ACTION_IMAGE_CAPTURE` hands the whole capture session to the external camera app. There is no
standard extra for flash, the camera app owns that control, and nothing the WebView or the intent
can do will toggle the torch. **The flash button could never have worked with this architecture.**

Rather than leave a fake "Flash enabled" state, the button now says plainly that flash is controlled
by the phone's camera app. Real flash control needs CameraX: a new dependency, a native preview
surface composited over the WebView, its own lifecycle, capture and permission handling — a
substantial change to the one part of the app that currently works. **That is a separate pass, not
a small edit, and I have not attempted it here.**

### 4-9, 12 — implemented

- **Help**: the generic toast is gone from the codebase (verified: zero occurrences). The `?` icon
  and Help & Support now open a real scrollable help modal — workflow, reporting guidance and ten
  FAQ answers.
- **Copy Link / Share**: the three-dot menu copies the real report reference to the Android
  clipboard and opens the system share sheet with title/reference. No invented public URL.
- **Comment send icon**: the redundant small arrow beside the comment field is hidden; the large
  Send button is unchanged.
- **"1 Photo attached" overlap**: the caption is now written to the badge itself, found via its
  `photo_camera` icon. The previous code matched the first *ancestor* div containing that phrase and
  wrote into a wrapper, which is what produced the duplicated text.
- **Contact Support**: opens a dialog labelled as demo contact detail, with a tappable
  `support@chipchap.gov.in` and `+91 98765 43210` (dialer only, never places a call).
- **Success screen**: the copy button copies the real Firestore document id shown on screen. Track
  Status sets that id as the active report and opens the tracking screen for it.

### 10, 13 — tracking screen

"Crew is on the way", "Est. arrival: 15 mins", "Downtown Maintenance Crew", the fake timestamps and
the animated crew marker are all removed. The screen now loads the real report and its real timeline
via `loadReportDetail()` and renders only events that actually exist, with a line saying later
stages appear once staff act. A freshly submitted report therefore shows only its SUBMITTED event.
`RESOLVED` will render automatically when a government workflow writes it.

### 11 — map

Search now filters the demo dataset, the blue + opens the capture flow, close hides the sheet,
Open in Maps uses the selected issue's place. Still explicitly labelled demo data.

### Files changed

- `firestore.rules` — `isVerified()` now signed-in only
- `app/build.gradle` — added `androidx.exifinterface:exifinterface:1.3.7`
- `app/src/main/java/com/chipchap/app/data/LocalPhotoRepository.java` — EXIF normalisation
- `app/src/main/java/com/chipchap/app/MainActivity.java` — gate removal, profile bootstrap,
  clipboard/share/contact/report-detail bridges, help, flash, success, tracking, map, overlap

### Verified

All 15 Java files compile under Java 17 against exact API stubs (0 errors). MainActivity: 0 syntax
errors. All three injected scripts pass `node --check` — this caught a real escaping bug where `\n`
inside the share text was consumed by the Java text block and broke the script.

### NOT done / not verified

- **No Gradle build and no device test.** `./gradlew clean :app:assembleDebug` must be run locally.
- **CameraX flash not implemented** (see above).
- `complaint_details_1` still shows Stitch placeholder title/description for a real report; only its
  votes and comments are live. Opening a report from the community feed or My Reports carries the
  real id correctly, so upvote and comments work against the right document — but the header text is
  still template copy. Deferred.
- Notifications remain an honest empty state; there is no notification backend.

## Camera UI cleanup — Flash control removed (2026-09-23)

### Why

`ACTION_IMAGE_CAPTURE` hands the capture session to the external camera app. There is no standard
intent extra for flash, so the WebView could never control the torch. The button was decorative
from the day it was added.

### Files changed (two)

**`app/src/main/assets/screens/capture_photo/code.html`** — the Flash `<button>` (its
`aria-label="Toggle flash"`, the `flash_off` icon span and the "Flash" label) is replaced by
`<div class="w-16 h-16" aria-hidden="true"></div>`. The controls row is `justify-between` with
three children, so an inert spacer of the same size keeps the shutter centred; removing the node
outright would have pushed Gallery and the shutter to opposite edges. No other markup, class or
style was touched. This is the first edit to a Stitch asset in the project — done because the task
explicitly asked for the control to be removed rather than hidden.

**`app/src/main/java/com/chipchap/app/MainActivity.java`** — two deletions:
- the click rule `if(aria.includes('flash')||t==='flash'){...}` and its toast
- the now-dead `flash_off` and `flash_on` entries in the icon fallback map

### Dead-code sweep

`grep -rni flash` across `app/src/main/java`, `app/src/main/assets/screens`, `app/build.gradle` and
`AndroidManifest.xml` returns only the explanatory comment in `capture_photo/code.html`. There was
no native bridge method, no state variable and no stored flash flag — the toggle only ever set a
`dataset.flash` attribute, which was already removed in an earlier pass.

### Unchanged (verified by grep)

- `ACTION_IMAGE_CAPTURE` + `EXTRA_OUTPUT` + FileProvider capture path
- `normalizeOrientation()` EXIF handling
- "Use this photo" -> `ComplaintDraft.setLocalImagePath()` -> submission
- `app/build.gradle` dependencies: firebase-bom, firebase-auth, firebase-firestore,
  androidx.core, androidx.exifinterface. **No CameraX, no Firebase Storage.**
- Firestore rules, repositories, community, map, help, tracking, timeline, profile

### Gallery — discrepancy the task assumed otherwise

**Gallery is not currently wired to a photo picker.** The `openGallery()` bridge method and its
`ACTION_PICK` intent were deliberately removed during Phase 2, on the instruction "REMOVE GALLERY
FROM THE MVP FLOW / Do NOT implement Gallery". The Gallery button is still visible and still says,
truthfully, that gallery upload is not available in this build.

So nothing was broken by this pass, but the requirement "Gallery must continue to open the Android
photo picker" describes behaviour that does not exist yet. Re-enabling it is a small, self-contained
change (a picker intent plus the existing `LocalPhotoRepository` path) and is **not** done here.

### Verification

MainActivity: 0 syntax errors, all three injected scripts pass `node --check`, zero flash references
remain in any script. **No Gradle build and no device test** — `./gradlew clean :app:assembleDebug`
must be run locally, and camera behaviour on hardware is unverified.

## Camera UI — Gallery option removed (2026-09-23)

A CHIPCHAP report must be a new photo taken at the scene, so the only photo source is now a live
`ACTION_IMAGE_CAPTURE` capture.

### Files changed (two)

**`app/src/main/assets/screens/capture_photo/code.html`**
- Removed the Gallery `<button>`: `aria-label="Upload from gallery"`, its `photo_library` icon span
  and its "Gallery" label. No disabled button and no placeholder left behind.
- Removed the inert spacer added in the flash pass; it existed only to balance the row.
- Controls row `justify-between` -> `justify-center`. The row now holds one child, the shutter,
  so it stays centred with the same margins and padding. Nothing else in the file changed.

**`app/src/main/java/com/chipchap/app/MainActivity.java`**
- Removed the click rule `if(aria.includes('gallery')||t==='gallery'){...}` and its toast.
- Removed the fallback rule `if(t.includes('upload')||t.includes('add a photo')){...}`, whose whole
  body was the same gallery toast. The only matching element left in the bundled screens is an
  `upload` icon on `update_status`, a government screen that `isAdminScreen()` already blocks.
- Removed the now-dead `photo_library` entry from the icon fallback map (that icon appeared only on
  the button just deleted).

### Verified absent

`grep -rniE "ACTION_PICK|ACTION_GET_CONTENT|EXTERNAL_CONTENT_URI|PickVisualMedia|OPEN_DOCUMENT|openGallery|photo_library"` across `app/src/main/java`, `app/src/main/assets/screens`, `app/build.gradle`
and `AndroidManifest.xml` returns nothing. There is no picker intent, no gallery bridge method and
no gallery UI anywhere in the project.

The word "gallery" survives in four places, all inert: this file's explanatory comment in
`capture_photo`, a Javadoc line on `takePhoto()` recording that gallery is deliberately not
implemented, a sentence in `LocalPhotoRepository` explaining that photos do not land in the user's
shared gallery, and an unrelated `<!-- Media Gallery (Hero) -->` comment in the government-only
`complaint_details_2` screen.

### Verified intact

- `ACTION_IMAGE_CAPTURE` + `EXTRA_OUTPUT` + `FileProvider` (`contentUriFor`) capture path
- `LocalPhotoRepository` create/usable/delete/preview, and `normalizeOrientation()` EXIF handling
- `ComplaintDraft.setLocalImagePath()` -> `Complaint.localImagePath` -> submission
- "Use this photo" button and its `ccUsePhoto` handler
- Flash not reintroduced (only the explanatory comments remain)
- `app/build.gradle` dependencies unchanged; no CameraX, no Firebase Storage, no Base64 to Firestore
- Firebase Auth, Firestore rules and repositories, submission, location, maps, profile, community,
  timeline and government code untouched

### Verification

All three injected scripts still pass `node --check` after the deletions, and the camera click rule
`aria-label="Take photo" -> native.takePhoto()` is intact. **No Gradle build was run** — as
requested, the build is yours.

## Real report data flow (2026-09-23)

### Root causes — three separate bugs, all on the details screen

**1. Complaint Details was never bound to report data.** `setupDetailsView()` wired only comments.
Title, id, status, byline, category, severity, location, description, the assigned-department card
and the inline timeline were all still hard-coded Stitch content — "Severe Pothole on Main St.",
"#CCP-8291", "In Progress", "Reported by Citizen User on Oct 24, 2023", "Sanitation & Public
Works", "2-3 Bus. Days", "AI Categorized", "Crew Dispatched". The real reportId was flowing
correctly; nothing consumed it. That is why every report looked like the same demo complaint.

**2. Upvote could not see the report id on a details screen.** `ccVote()` resolved the id only from
a `[data-cc-report]` feed-card ancestor. The details screen has no feed card, so the lookup returned
empty and the "not backed by a stored report" message fired even for a genuinely stored report.

**3. The vote count had nowhere to render.** `chipchapInteractions` writes into
`[data-cc-report="<id>"]`; no such element existed on the details screen.

A fourth, smaller one: the universal click handler routed any `<article>` whose text contained
"pothole"/"streetlight"/"graffiti"/"fallen tree" straight to `complaint_details_1` without setting
the active report — so a real card whose title happened to contain one of those words opened the
demo screen.

### Files changed — one

`app/src/main/java/com/chipchap/app/MainActivity.java`

- `bindReportDetails(d)` + `setupReportDetails()` — new. Binds the Complaint Details Stitch screen
  to the real report: status, `#<reportId>`, title, reporter byline, category, severity, location,
  description, and the inline timeline rebuilt from real stored events. The assigned-department and
  estimated-resolution fields show "Not assigned yet" / "Not set" because no government workflow
  exists yet — no invented department, crew or ETA.
- `chipchapReportDetail` now routes its payload to the details screen as well as the tracking screen.
- `ccVote()` falls back to `native.getActiveReportId()` when there is no feed-card ancestor.
- `setupReportDetails()` tags `main` with `data-cc-report` and tags the vote counter with
  `data-cc-votecount`, so the stored count renders and voting targets the right document.
- The `<article>` text-routing rule is now skipped when the article carries `data-cc-report`.
- Signup no longer calls `sendVerificationEmail()`; it goes straight to `routeAfterAuthentication(true)`.

No Stitch asset was edited in this pass.

### reportId flow, end to end

```
Firestore reports/{id}
  -> ComplaintRepository.loadCommunityReports()      (existing)
  -> publishReports -> chipchapCommunityFeed
  -> card() sets data-cc-report = the real document id
  -> tap -> native.setActiveReport(id) -> openScreen('complaint_details_1')
  -> setupDetailsView -> loadReportInteractions(id) + setupReportDetails
  -> loadReportDetail(id) -> chipchapReportDetail -> bindReportDetails
  -> main[data-cc-report] = id
  -> ccVote -> toggleReportVote(id) -> reports/{id}/votes/{uid}
  -> comments (unchanged) -> reports/{id}/comments/{commentId}
  -> timeline -> reports/{id}/timeline/{timelineId}
```

The id is the Firestore document id throughout. Nothing generates, relabels or hard-codes it.

### Confirmations

- **Comments not modified.** `CommentRepository`, the Firestore paths, `addReportComment`,
  `loadComments`, the comment UI, the Send button and author handling are all untouched. They
  simply receive the same real id they already did.
- **No automatic verification email.** `sendVerificationEmail()` is now called from exactly one
  place, the manual `resendVerification()` bridge method, which nothing in the UI invokes. Signup
  and login send nothing and show no verification notice.
- **Forgot Password intact.** `sendPasswordReset` bridge and `auth.sendPasswordResetEmail()`
  unchanged, still reachable from the login screen's "Forgot password?" modal.
- **Firestore rules untouched this pass.** Report ownership is still
  `request.resource.data.citizenUid == request.auth.uid`; the client never supplies a uid.
- **Government dashboard not modified**; `isAdminScreen()` / `denyAdminAccess()` unchanged.
- **Nothing unrelated changed.** Only `MainActivity.java` was modified. Camera, gallery removal,
  flash removal, location, profile editing, maps and the repository layer are untouched.

### Verification

Java: 0 syntax errors, all new symbols resolve. All three injected scripts pass `node --check`.
**No Gradle build and no device test** — as requested, the build is yours.

### Known limitation

If Home's "Recent Activity" shows an empty state, that is the honest result of
`loadCommunityReports()` returning no documents, not a rendering bug. Photos remain device-local,
so another citizen's report shows no image — the evidence tile now says so rather than implying one.

## Checkpoint F1 — Geoapify route preview on the existing map (2026-10-20)

Government "Navigate to Report" no longer hands off to Google Maps. It calculates a driving route
with the Geoapify Routing API and draws it on CHIPCHAP's existing MapLibre/OpenFreeMap map.

### Files changed
- `app/build.gradle` — reads `GEOAPIFY_API_KEY` from `local.properties` into
  `BuildConfig.GEOAPIFY_API_KEY`; enables `buildFeatures.buildConfig`.
- `app/src/main/java/com/chipchap/app/data/GeoapifyRouteService.java` (new) — one GET to
  `https://api.geoapify.com/v1/routing?waypoints=lat,lon|lat,lon&mode=drive&units=metric&format=geojson&apiKey=…`
  on a background thread (HttpURLConnection, no new dependency); defensive GeoJSON parsing
  (MultiLineString geometry, `distance` in meters, `time` in seconds); honest error mapping.
- `MainActivity.java` — route state + generation counter, bridge methods
  `requestReportRoute(id)` / `clearReportRoute()` / `loadRouteState()` (replacing
  `navigateToReport`), location-permission branch (request 7007), route source/layers
  (`ccroute-source`, `ccroute-casing`, `ccroute-line`, `ccroute-ends`), route panel and Clear Route
  on `map_explorer`; `govNavigate` now sends only the report id.
- `local.properties` — commented `GEOAPIFY_API_KEY=` placeholder (no key).

### Not changed
Report sources/layers, clustering, heatmap, My Location, search/filter, Firestore schema/rules,
Cloudinary, notifications, authentication, AI (`CivicPhotoAiService`, prompts, App Check).
No route data is stored anywhere.

### Verification
Both edited injected scripts pass `node --check`; route JS exercised against a mock map
(layer order, stale/late replies, Clear Route). **No Gradle build, no device test.**

### Known limitations
Route preview only (no turn-by-turn, rerouting or live tracking). The Geoapify key ships inside the
APK and can be extracted (MVP limitation). Route state lives in memory and is dropped on Activity
recreation or sign-out.

## Checkpoint F2 — Admin workflow cleanup, editable route origin, place search (2026-10-20)

### Files changed
- `app/src/main/assets/screens/complaint_details_2/code.html` — Add Note button removed.
- `app/src/main/java/com/chipchap/app/MainActivity.java`
  - Government details: Add Note wiring + its "not available" toast removed; Assign/Change label.
  - Assign screen: department list = Roads & Public Works, Sanitation, Electrical, Water Supply,
    Drainage; the saved department is pre-selected when a report is reopened. Saved through the
    EXISTING `governmentAssignReport` → `assignedGovernmentName` path (no schema/rules change).
  - Map: Create Report button removed for verified government sessions only (`getMapMode()`,
    decided natively by `hasGovernmentAccess()`); citizens unchanged.
  - Route preview: editable starting point (device location by default, or a searched place),
    place destinations, Show/Update Route only on explicit taps. New bridge methods
    `requestReportRouteFrom`, `requestPlaceRoute`, `searchPlaces`, `cancelPlaceSearch`,
    `getMapMode`. Places are kept natively by id, so routes always use Geoapify's coordinates;
    report destinations still use the report's stored coordinates.
  - Map search (government only): debounced Geoapify place search beside the existing report
    search; results listed in separate "CHIPCHAP reports" / "Places and addresses" sections;
    temporary place marker + place card (Route here / Start from here / clear).
- `app/src/main/java/com/chipchap/app/data/GeoapifyRouteService.java` — Geoapify Address
  Autocomplete (`/v1/geocode/autocomplete`, format=json, limit 6, proximity bias) on its own
  thread with superseded-search skipping; shared HTTP helper; honest error mapping.

### Not changed
AI/photo validation, Firebase Auth, Firestore rules/schema, Cloudinary, notifications, status
workflow, timeline, report markers/clusters/heatmap, citizen map search and Create Report.

### Verification
`node --check` on both injected scripts; 59 behavioural checks in headless Chromium against the
real screen HTML with a mocked map and bridge (`Claude outputs/CheckpointF2_AdminWorkflow_PlaceSearch/static_checks`).
**No Gradle build, no device test.**

## Checkpoint F3 — Role-specific map navigation, citizen place search/routing, route Copy/Share (2026-10-20)

### File changed
- `app/src/main/java/com/chipchap/app/MainActivity.java` (only file).

### What changed
- Government Live Map: the citizen bottom bar is replaced by the government portal's own bar
  (Dashboard, Complaints, Analytics, Alerts → notifications_2, Profile; same Stitch classes/icons),
  only when the native session check says GOVERNMENT. Destinations go through native loadScreen
  gates. The government bar has no Map item, so nothing is highlighted. Citizen bar unchanged.
- Place search + route preview now available to any signed-in session (`hasMapRouteAccess()`);
  heatmap and every government action keep `hasGovernmentAccess()`. Report card gains a Route
  button (report's stored coordinates, read natively).
- Route panel: Copy start / Copy destination / Share route, composed natively
  (`copyRouteStart`, `copyRouteDestination`, `shareRoute`) from real values only — place name +
  formatted address + coordinates, report stored title/address/coordinates, device position as
  coordinates only. Share (ACTION_SEND chooser) is offered and accepted only for a route calculated
  for exactly the current endpoints; it includes Geoapify distance/time labelled as an
  approximate estimate and a Google Maps directions URL built from the route's coordinates.
- Layout: route panel above report/place cards with internal scroll; place card hides while the
  place is the route's start/destination; routing from a report card closes that card.

### Verification
`node --check` on both injected scripts; headless Chromium checks against the real screen HTML with
mocked map/bridge: F3 50/50 at 400×820 and 360×640, F2 regression 59/59
(`Claude outputs/CheckpointF3_RoleNav_CitizenRouting/static_checks`). **No Gradle build, no device test.**

## AI reliability — bounded retry of temporary Gemini failures (2026-10-20)

File changed: `app/src/main/java/com/chipchap/app/ai/CivicPhotoAiService.java` only.
Temporary failures (busy/overloaded/5xx up to 2 retries; network drop 1; 429 without explicit
quota wording 1) are retried automatically inside the unchanged 25 s deadline, on the same request
(one callback, at most one upload). Explicit quota, App Check/auth, configuration/model, invalid
request, malformed answer, low confidence and timeout are never retried. New SERVICE_UNAVAILABLE
kind; explicit quota separated from unconfirmed rate limit/capacity; APINotConfiguredException =
configuration. Model, prompt, schema, 0.60 threshold, timeout, App Check and UI unchanged.
Verified with `javac` + a scripted Gemini mock against stand-ins: 32/32
(`Claude outputs/AI_Reliability_Retry`). Not verified against live Firebase/Gemini. No Gradle build.

## Post-checkpoint-1 fixes (2026-10-21)

### Files changed
- `assets/screens/capture_photo|details|review_report/code.html` — steps renumbered to the real
  flow (Photo 1/3 → Details 2/3 → Review 3/3; the old "AI analysis" Step 2 screen is no longer in
  the flow); Step 3 (details) Skip button removed.
- `assets/screens/{dashboard,complaint_queue,analytics,admin_profile,notifications_2,assign_complaint,update_status}/code.html`
  — Alerts link removed from the government side drawer (bottom bars were already done).
- `MainActivity.java`
  - Capture/review: full-size photo viewer on tap (display only; Back closes it first).
  - Details: the static stock "map" picture replaced by the bundled MapLibre/OpenFreeMap map;
    pan/tap moves the centre pin; `setComplaintPinLocation` writes the existing draft
    latitude/longitude/address; a late device fix can no longer overwrite a placed pin.
  - Citizen report: demo "Est. resolution" box hidden (no estimate exists); department card kept.
  - Resolution photo: government details and Update Status now display the saved
    `resolutionEvidenceUrl`; a photo taken with a non-Resolved status is refused with a message
    instead of being dropped; stale Cloudinary upload callbacks are ignored.
- `data/LocationProvider.java` — `lookupAddress()` (existing Geocoder, background thread).

### Not changed
Firestore schema/rules, Cloudinary, notifications, timelines, AI, auth, map screen/routing.
Add Note was already removed in F2 (verified).

### Verification
`node --check` on all four injected scripts; headless Chromium, real screen HTML, mocked bridge/map:
65/65 new checks; regression F2 59/59, F3 50/50 (×2 viewports), gov nav 25/25
(`Claude outputs/PostCheckpoint1_Fixes/static_checks`). **No Gradle build, no device test.**

## Checkpoint 2 — Duplicate complaint detection (2026-10-21)

### What it does
On the citizen Review step (Step 3 of 3) a "Possible similar reports" card lists up to 3 stored
reports that are **not Resolved**, have the **same category** as the draft, and lie within
**100 m** (Haversine) of the draft's incident location — nearest first, then newest. Each shows
title, category, status, approximate distance and date, with "View report", which opens the
existing citizen report screen (support there uses the existing one-vote-per-user mechanism).
Back returns to the unchanged draft. The check is advisory only: it never blocks, merges,
votes, or changes any report, and Submit works at all times.

### Files changed
- `data/DuplicateReportFinder.java` (new, pure Java) — matching rules and constants:
  `MATCH_RADIUS_METERS = 100` (a starting threshold to tune with real data), `MAX_CANDIDATES = 3`,
  `QUERY_LIMIT = 150`.
- `data/ComplaintRepository.java` — `loadReportsInLatitudeBand()`: one read-only Firestore range
  query on the existing `latitude` field (automatic single-field index), bounded by the limit.
- `MainActivity.java` — bridge `checkSimilarReports(seq)`; native check reads location and
  category from the stored draft only; result cached per draft (photo + location + category);
  generation/sequence guards drop stale answers; answers only the Review screen; review card
  rendering via `textContent`; sign-out clears the check.

### Honest limits
- Coverage: all reports within about ±150 m of latitude are read, up to 150. If 150 are returned,
  the card says only those were checked and other similar reports may exist.
- Missing location or category → "not checked" message (nothing is guessed). A Firestore
  failure → "Couldn't check for similar reports. You can still submit your report." (never shown
  as "none found"). Text similarity is not used.
- Payload to the page: report id, title, category, status, distance, date only — no reporter,
  uid, email, evidence URL or storage path.

### Not changed
Firestore schema, rules and indexes; AI (CivicPhotoAiService); Cloudinary; auth; maps/routing;
voting; screen HTML; navigation.

### Verification
`javac` unit tests of the real `DuplicateReportFinder`: 20/20. Headless Chromium on the real
review/report screens with a mocked bridge: 26/26. Regression: post-checkpoint-1 65/65, F2 59/59,
F3 50/50 (×2 viewports), gov nav 25/25. `node --check` on all injected scripts.
(`Claude outputs/Checkpoint2_DuplicateDetection`.) The native async code in MainActivity, the
live Firestore query and the device flow were reviewed but **not run**: no Gradle build, no
device or live-Firebase test.

## Checkpoint 3 — Civic Hotspot Intelligence (2026-10-21)

### What it does
The government Analytics screen now shows the following, all counted from the reports the portal already loads (descriptive counts, not predictions):
- reports analysed;
- resolution rate;
- unresolved;
- open over 7 days;
- reports by category;
- reports by status;
- "Areas with several open reports".

It uses the existing KPI card and list-row components; no screen HTML changed.

### Files changed
- `data/CivicAnalytics.java` (new, pure Java): definitions, metrics and the candidate-area method. Constants: `HOTSPOT_RADIUS_METERS = 250`, `HOTSPOT_MIN_REPORTS = 3`, `MAX_HOTSPOTS = 5`, `AGING_THRESHOLD_MS = 7 days`. These are starting values, not validated.
- `data/ComplaintRepository.java`: `loadGovernmentReports()`. It runs the same read as before (newest 500 by `createdAt`), but a document with wrong-typed fields no longer aborts the load: it is left out of the list and still counted in the analytics.
- `MainActivity.java`:
  - the government load computes the analytics natively, behind `hasGovernmentAccess()` (checked before reading and before publishing), and adds them to the existing payload;
  - analytics-screen rendering;
  - the government script is split into two joined text blocks (Java's 64 KB constant limit).

### Definitions (short)
- **Unresolved:** Submitted, Under review, Assigned or In progress. A missing or unrecognised status is "unknown", not unresolved.
- **Over 7 days:** strictly more than 7 days from one device "now". A missing or unsupported date is never aged.
- **Location:** the existing validity rule; (0,0) is invalid.
- **Areas:** greedy and non-overlapping. Each report is in at most one area. Ranked by count, then by centre report id. Nearby reports are not assumed to share a cause.

### Honest limits
- Based only on the newest 500 reports. When the cap is reached, the screen says so.
- Reports with no `createdAt` are not returned by the query.
- A failed load shows "--" and the error, never 0.

### Not changed
- Firestore rules, indexes and schema.
- AI.
- Checkpoint 2 duplicate detection (`DuplicateReportFinder` is byte-identical).
- Cloudinary, auth, notifications and voting.
- Citizen queries, map, heatmap, search and routing.
- Screen HTML.

### Verification
- `CivicAnalytics`, javac: 37/37.
- Real `ComplaintRepository` against Firestore stand-ins: 10/10.
- Analytics browser tests: 35/35.
- Regression: 65/65, 26/26 (CP2), 20/20 (CP2 matcher), 59/59, 50/50 ×2, 25/25.
- `node --check` on all scripts.
- Artifacts are in `Claude outputs/Checkpoint3_CivicHotspotIntelligence`.

`MainActivity.java` was reviewed but not compiled. **No Gradle build, no device or live-Firebase test.**
