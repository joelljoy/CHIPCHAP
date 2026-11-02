# CHIPCHAP — Claude Code Project Instructions

## 1. PROJECT IDENTITY

CHIPCHAP is a **purely mobile Android application**.

It is NOT:
- a website
- a web application
- a PWA
- a desktop application
- a cross-platform application

The existing Android application uses a WebView internally to render the existing Stitch-designed UI. The WebView is an **implementation detail of the Android app**, not a separate web product.

The current architecture is:

Android Application
→ MainActivity.java
→ Android WebView
→ bundled Stitch HTML/CSS/JavaScript UI
→ native JavaScript bridge
→ Firebase / Android native services

Preserve this architecture.

Do not introduce web deployment architecture, a separate website, PWA architecture, React web architecture, Flutter, React Native, Jetpack Compose, or another frontend framework.

---

## 2. PROJECT GOAL

CHIPCHAP is a civic issue reporting and community engagement Android application.

The long-term goal is to allow citizens to:
- create an account
- verify their email
- log in securely
- maintain a profile
- report civic issues
- capture/select evidence
- provide category, severity, description, and location
- submit reports
- receive a real report/tracking ID
- view submitted reports
- track report progress
- view report history/timeline
- participate in community discussions
- comment on appropriate reports
- vote/support reports without duplicate voting
- receive notifications

Authorized civic/government users will eventually be able to:
- review reports
- verify reports
- assign reports
- update statuses
- track resolution
- add official updates
- view operational information
- manage appropriate administrative workflows

The project is being developed incrementally. Do not attempt to complete the entire product in one task.

---

## 3. EXISTING ANDROID ARCHITECTURE — DO NOT REPLACE

The existing architecture is intentional:

Android Java application
→ MainActivity.java
→ WebView
→ bundled Stitch UI assets
→ native JavaScript bridge
→ Firebase / Android native functionality

The Stitch screens are bundled inside the Android application.

Treat files under the Android assets/screens area as **mobile application UI assets**, not as a separate web application.

Do NOT migrate the application to:
- Jetpack Compose
- Flutter
- React Native
- a separate web frontend
- another WebView framework
- a completely native UI
- another frontend framework

Preserve the existing Stitch UI.

---

## 4. CURRENT TECHNOLOGY

Current project configuration:
- Android Java application
- Gradle 8.9
- Android Gradle Plugin 8.7.3
- Java 17
- compileSdk 35
- targetSdk 35
- minSdk 23
- Firebase BOM 34.18.0
- WebView-based Stitch UI
- approximately 38 Stitch-based screens
- application ID: `com.chipchap.app`

Do not change these versions unless explicitly requested or a genuine compatibility issue requires it.

The project has a build-verified checkpoint using:

```bash
./gradlew clean :app:assembleDebug
```

Maintain buildability after changes.

---

## 5. PRESERVE THE EXISTING UI

The Stitch UI has already been designed.

Unless a task explicitly requests a UI change:
- do not redesign screens
- do not replace layouts
- do not change typography
- do not change colors
- do not change spacing
- do not replace illustrations
- do not change the visual hierarchy
- do not rebuild screens from scratch
- do not replace Stitch assets with a different UI framework

If backend integration requires a small UI state change, make the smallest possible change while preserving the existing design.

---

## 6. FIREBASE AUTHENTICATION IS ALREADY IMPLEMENTED

Firebase Authentication is already implemented and is a completed foundation.

Existing functionality includes:
- email/password registration
- email/password login
- Firebase email verification
- resend verification email
- verification-state checking
- Firebase session restoration
- protected-screen authentication gate
- Firebase logout
- password reset
- password update

Do NOT rewrite authentication unless a genuine blocking defect is discovered.

Firebase Authentication is authoritative for:
- `uid`
- `email`
- `emailVerified`
- authentication/session state

Do not replace Firebase Auth with another authentication system.

Do not create a second authentication/session system.

Do not weaken or bypass the existing email-verification gate.

---

## 7. IMPORTANT AUTHENTICATION COMPATIBILITY RULE

Before changing authentication-related files:
1. Inspect the actual current source code.
2. Do not rely blindly on historical documentation.
3. Preserve existing working authentication behavior.
4. Make only the smallest compatibility fix if one is genuinely necessary.
5. Do not refactor authentication merely for style.

`IMPLEMENTATION_STATUS.md` may contain historical information that is no longer perfectly synchronized with the source code. The actual source code is authoritative.

---

## 8. FIREBASE BACKEND STRATEGY

Backend implementation will happen incrementally.

Planned order:
1. Firestore foundation
2. User profiles
3. Reports
4. Report events/timeline
5. Firebase Storage/evidence
6. Location
7. Community
8. Comments/votes
9. Admin authorization/workflows
10. Notifications/FCM
11. AI/backend integration

Do not implement multiple major backend systems in one uncontrolled change.

Treat each phase as a hard scope boundary.

If something belongs to a later phase, document it rather than implementing it prematurely.

---

## 9. FIRESTORE ARCHITECTURE

Firestore should be accessed through native Android code.

Preferred structure:

MainActivity
→ NativeBridge
→ Repository/Service
→ Firebase

Do NOT put the entire Firestore implementation into `MainActivity.java`.

Prefer small maintainable classes such as:

```text
data/
    UserRepository.java
    ReportRepository.java
    CommentRepository.java
    NotificationRepository.java

models/
    UserProfile.java
    Report.java
    ReportEvent.java
    Comment.java
    Vote.java
    Notification.java
```

Only create classes required by the current phase.

Do not introduce unnecessary frameworks, dependency-injection systems, or architectural complexity.

---

## 10. FIRESTORE USER PROFILE

The initial Firestore profile foundation should use:

```text
users/{uid}
```

The UID must come from the authenticated Firebase user.

Initial profile fields may include:

```text
uid
email
fullName
neighborhood
createdAt
updatedAt
```

Do not invent additional mandatory profile requirements unless the existing application or requirements require them.

Use Firestore server timestamps where appropriate.

Preserve `createdAt` on updates.

Update `updatedAt` on modifications.

---

## 11. REPORT OWNERSHIP

Reports will eventually use:

```text
reports/{reportId}
```

The reporter UID must always come from the authenticated Firebase user.

Never trust an arbitrary UID supplied by:
- JavaScript
- the WebView
- HTML form fields
- SharedPreferences
- local storage
- request parameters

The authenticated Firebase user determines report ownership.

---

## 12. SECURITY PRINCIPLES

Security is a core requirement.

Never use any of the following as the actual authorization boundary:
- SharedPreferences
- JavaScript variables
- hidden UI elements
- local storage
- client-controlled role fields
- hard-coded admin credentials
- hard-coded admin passwords
- local `isAdmin=true` flags

The eventual authorization model should use:

Firebase Authentication
→ trusted authorization
→ Firebase Custom Claims / trusted backend
→ Firestore Security Rules

Citizens must not be able to arbitrarily modify privileged fields such as:
- `assignedTo`
- `assignedDepartment`
- administrative notes
- protected status transitions
- `resolvedAt`
- internal system fields
- protected AI/system metadata

Do not expose unrestricted Firestore operations through the WebView bridge.

---

## 13. WEBVIEW / JAVASCRIPT BRIDGE

The WebView communicates with native Android code through the existing JavaScript bridge.

Preserve this pattern.

Conceptually:

JavaScript
→ Android application method
→ native validation
→ repository/service
→ Firebase
→ result
→ WebView

Native code must validate important data rather than blindly trusting JavaScript.

Do NOT create a generic unrestricted bridge such as:

```text
Android.firestoreSet(...)
Android.firestoreUpdate(...)
Android.firestoreDelete(...)
```

that allows arbitrary HTML/JavaScript to manipulate the database.

Expose narrowly scoped application operations instead.

---

## 14. DATA OWNERSHIP

Maintain clear sources of truth.

### Firebase Authentication owns
- UID
- email
- email verification
- authentication/session state

### Firestore owns
- application profile
- reports
- report status/history
- comments
- votes
- notifications

### Firebase Storage owns
- images
- evidence
- media files

### WebView owns
- temporary presentation state
- transient UI state

### SharedPreferences owns
- local preferences or temporary compatibility data only

SharedPreferences must NOT become the backend source of truth for important application data.

SharedPreferences must NEVER be used as authorization.

---

## 15. MEDIA / EVIDENCE

Do NOT store large images directly in Firestore.

When Storage is implemented:

Camera/Gallery
→ Firebase Storage
→ storage path/reference
→ Firestore report metadata

Do not store large base64 image strings inside Firestore documents.

Preserve the existing Android camera/gallery functionality unless the current task specifically concerns it.

---

## 16. STATIC / MOCK DATA

The existing Stitch frontend contains prototype/demo data.

Examples may include:
- static reports
- static statistics
- hard-coded profile information
- fake notification entries
- demo AI results
- static comments
- simulated votes
- fake tracking IDs
- simulated upload states
- simulated report timelines

Do not assume a polished UI means the backend is complete.

When implementing a backend feature:
1. identify the existing mock behavior
2. replace only the relevant mock behavior
3. preserve the existing UI
4. do not fabricate backend success

If a Firebase operation fails, report the real failure state.

Do not silently display success when the backend operation failed.

---

## 17. EXISTING FUNCTIONALITY MUST NOT REGRESS

Before modifying code:
1. inspect the relevant implementation
2. understand the current flow
3. identify what is real versus mock
4. determine the smallest required change
5. implement only that change
6. build/test
7. re-check affected existing functionality

Do not rewrite working functionality merely because another implementation appears cleaner.

---

## 18. PHASE BOUNDARIES

Treat each requested phase as a hard boundary.

For example, if asked to implement:

"Firestore user profile"

DO NOT also implement:
- reports
- Storage
- AI
- notifications
- admin
- location
- comments
- votes
- FCM

If a later feature becomes necessary for the current phase, explain why before expanding scope.

Otherwise document it as a future dependency and stop.

---

## 19. ADMIN FEATURES

Admin functionality must eventually be securely server-authorized.

Never implement admin authorization using:
- SharedPreferences
- local storage
- JavaScript variables
- a Firestore `role` field alone
- hard-coded credentials
- hidden UI controls

Use Firebase Authentication plus trusted authorization such as Custom Claims and secure backend/rules.

Sensitive administrative mutations may eventually use Cloud Functions or another trusted backend mechanism.

Do not implement the full admin system unless explicitly requested.

---

## 20. AI FEATURES

The existing AI analysis screen does not mean a real AI backend exists.

Do not fabricate AI processing.

Do not claim AI analysis is real unless an actual AI service/backend has been integrated and tested.

AI is a later phase.

---

## 21. LOCATION

Do not implement location backend functionality unless explicitly requested.

When implemented, location should integrate with the existing mobile application's report/map workflow.

Do not redesign the map UI.

Do not add unnecessary location architecture before the relevant phase.

---

## 22. NOTIFICATIONS

Do not implement FCM or notification backend functionality unless explicitly requested.

The existing notification screens may contain static/demo data.

Do not claim push notifications are implemented unless they have actually been integrated and tested.

---

## 23. BUILD REQUIREMENT

After meaningful code changes, run:

```bash
./gradlew clean :app:assembleDebug
```

The build must succeed before declaring the phase complete.

If the build fails:
1. identify the actual error
2. fix only errors relevant to the current task
3. rebuild
4. verify again

Do not make unrelated cleanup changes while fixing build errors.

---

## 24. TESTING REQUIREMENTS

For every implementation phase, verify as much as the environment permits.

### Compilation
- Gradle build succeeds.

### Authentication
When authentication-related code is touched:
- registration remains functional
- login remains functional
- email verification remains enforced
- logout remains functional
- session restoration remains functional

### Backend
- Firebase operations use the authenticated UID
- failures are surfaced honestly
- no fake success is generated
- ownership is derived from Firebase Auth
- privileged fields are not client-controlled

### UI
- existing Stitch design remains intact
- existing navigation remains intact
- no unnecessary redesign occurs

Do not claim end-to-end Firebase testing unless it was actually performed against the real Firebase project.

---

## 25. CHANGE DISCIPLINE

For every task:

### Before editing
Inspect the relevant files and current implementation.

### Before making broad changes
Identify exactly which files need modification.

### During implementation
Modify only the requested scope.

### After implementation
Build/test the project.

### Final report
State:
- files changed
- what was implemented
- what was tested
- build result
- known limitations
- what remains

Do not hide failures.

Do not claim work that was not actually completed.

---

## 26. NO UNREQUESTED REFACTORING

Do not:
- rewrite MainActivity.java simply for cleanliness
- reorganize the entire project
- rename large numbers of files
- replace the existing frontend
- migrate frameworks
- introduce unnecessary libraries
- rewrite Firebase Auth
- change the UI design
- remove working functionality
- implement future features "while you are here"

Keep changes small, focused, and reversible.

---

## 27. DOCUMENTATION

Maintain `IMPLEMENTATION_STATUS.md` after meaningful implementation phases.

Record:
- what was implemented
- files changed
- what was tested
- build result
- known limitations
- next phase

Do not claim a feature is implemented if it is only mocked.

If project documentation conflicts with the source code:
1. inspect the source
2. identify the discrepancy
3. preserve working source behavior
4. mention the discrepancy in the final report

---

## 28. CHECKPOINTS

After completing a meaningful phase:
1. build successfully
2. verify changed functionality
3. update implementation status
4. create a clean checkpoint/commit if the environment supports it

Do not combine unrelated phases into one checkpoint.

---

## 29. WORKING STYLE

Act as an implementation engineer working on an existing production-oriented Android application.

Do not behave as though the project is a blank-slate application.

Prefer:
- minimal changes
- existing architecture
- existing UI
- existing Firebase Auth
- clear data ownership
- maintainable native repositories
- explicit error handling
- incremental implementation
- build verification
- reversible changes

When uncertain, inspect the actual project before making assumptions.

---

## 30. FINAL RULE

CHIPCHAP is an existing **Android mobile application**.

Your job is to complete it incrementally, not redesign it.

Preserve:
- Android architecture
- WebView-based Stitch UI
- MainActivity/native layer
- JavaScript bridge
- Firebase Authentication
- existing navigation
- existing visual design
- existing working functionality

Implement the backend carefully underneath the existing mobile application.

Small, testable, reversible changes are preferred over large rewrites.
