# CHIPCHAP — Community Civic Issue Reporting Platform

> **Report local problems. Follow their progress. Help improve your community.**

CHIPCHAP is an Android-based civic engagement application designed to make it easier for residents to report public-infrastructure and neighbourhood issues and follow what happens next. It provides a citizen-facing reporting experience and a separate government-facing workflow for reviewing and managing reports.

The project combines a native Android host with locally bundled HTML/CSS/JavaScript screens. Android and Java handle device capabilities and trusted application operations, while Firebase provides authentication and cloud data access. The application is an actively developed MVP/prototype; the limitations and verification status below are intentionally documented so that users and contributors can distinguish implemented functionality from planned or environment-dependent functionality.

---

## Contents

- [Why CHIPCHAP?](#why-chipchap)
- [Who is it for?](#who-is-it-for)
- [Key capabilities](#key-capabilities)
- [How it works](#how-it-works)
- [Technology stack](#technology-stack)
- [Architecture](#architecture)
- [Repository structure](#repository-structure)
- [Getting started](#getting-started)
- [Firebase and API configuration](#firebase-and-api-configuration)
- [Build and run](#build-and-run)
- [Data model overview](#data-model-overview)
- [Security notes](#security-notes)
- [Current implementation status and limitations](#current-implementation-status-and-limitations)
- [Testing and verification](#testing-and-verification)
- [Contributing](#contributing)
- [License](#license)

## Why CHIPCHAP?

Residents often notice issues such as potholes, damaged streetlights, overflowing waste, drainage problems, and other public-service concerns, but reporting them and tracking their progress can be fragmented. CHIPCHAP aims to bring these steps into one place:

1. A resident creates a report with a description, category, severity, and location.
2. The application can use a photo and AI-assisted analysis as part of the reporting flow.
3. The report is submitted to the cloud-backed report system.
4. Residents can revisit their reports and view available status/history information.
5. Government-side users have dedicated screens for reviewing reports and viewing operational information, subject to trusted authorization being configured.

CHIPCHAP is intended to improve visibility and communication. It does **not** itself guarantee that a civic authority will accept, assign, or resolve a report.

## Who is it for?

| User | Intended use |
|---|---|
| **Residents / citizens** | Create civic issue reports, attach evidence, provide a location, review their reports, and participate in available community interactions. |
| **Government / operations staff** | Review incoming reports, inspect report details and locations, manage permitted workflow actions, and view aggregate civic analytics. Access requires a trusted government identity; a client-side role label is not sufficient. |

## Key capabilities

### Citizen experience

- Firebase email/password account registration and sign-in.
- Citizen profile information, including name and neighbourhood.
- Guided report creation flow with photo capture, issue details, location selection, and review.
- AI-assisted photo/report analysis using Firebase AI Logic (Gemini), including bounded retries for selected temporary service failures.
- Similar-report suggestions during review. The current matcher looks for unresolved reports in the same category within a 100-metre radius; it is advisory and does not block submission.
- Cloud-backed report retrieval, report details, and available timeline information.
- My Reports and community feed screens.
- Available report interactions such as voting and comments, subject to the deployed Firestore rules.
- Notifications and unread-notification count where the corresponding backend data exists.
- Map-based report exploration, place search, and route previews. Route previews use Geoapify; route sharing/copy actions use the calculated route endpoints.

### Government / operations experience

- Dedicated government portal screens and navigation.
- Complaint queue, report details, assignment/status-related screens, and notifications UI.
- Map exploration and route preview for report locations and searched places.
- Civic analytics including report counts, resolution rate, unresolved reports, reports older than seven days, category/status breakdowns, and candidate areas with multiple open reports.

**Important access note:** the repository's Firestore rules expect a trusted Firebase custom claim (`role == GOVERNMENT`) for government privileges. The trusted process that provisions this claim is not included in this Android repository. Until that backend process and the rules are deployed and tested, government operations should be treated as unavailable / not production-ready.

## How it works

### Citizen report flow

```text
Open CHIPCHAP
      |
      v
Create account / Sign in
      |
      v
Open report creation
      |
      v
Capture issue photo (where applicable)
      |
      v
Enter title, description, category and severity
      |
      v
Choose or confirm the incident location
      |
      v
Review report and check for nearby similar reports
      |
      v
Submit report
      |
      v
View report details and available status history
```

The similar-report check is a suggestion only. A missing location/category or a failed lookup is not treated as proof that no similar report exists.

### High-level application flow

```text
Android device
  |
  v
MainActivity (Java)
  |
  +--> Local WebView screens (HTML / CSS / JavaScript)
  |       |
  |       +--> Screen navigation and user interactions
  |       +--> Calls validated native bridge methods
  |
  +--> Native data and service layer
          |
          +--> Firebase Authentication
          +--> Cloud Firestore repositories
          +--> Firebase AI Logic (Gemini)
          +--> Android camera and location capabilities
          +--> Geoapify geocoding and route preview
          +--> Cloudinary evidence-image upload
```

The WebView is the presentation layer, not the authorization boundary. Data permissions must be enforced by Firebase Security Rules and trusted backend configuration.

## Technology stack

| Layer | Technologies | Responsibility |
|---|---|---|
| Android application | Java, Android SDK, Android Studio | Native application shell, lifecycle, permissions, camera integration, intents, and platform APIs. |
| UI | Android WebView, HTML, CSS, JavaScript | Bundled Stitch-derived screens, screen navigation, presentation, and user interactions. |
| Build system | Gradle 8.9, Android Gradle Plugin 8.7.3 | Android project configuration and build tasks. |
| Authentication | Firebase Authentication | Email/password sign-up and sign-in. |
| Cloud database | Cloud Firestore | User profiles, reports, report timelines, comments, votes, and notification-related data used by the app. |
| AI | Firebase AI Logic / Gemini | AI-assisted civic photo/report analysis. |
| Maps | MapLibre GL JS, OpenFreeMap map style/data services | Map rendering and map interactions. |
| Geocoding and routing | Geoapify APIs | Place autocomplete and route preview calculations. |
| Evidence media | Cloudinary unsigned upload preset | Upload and delivery of report evidence images. |
| Image metadata | AndroidX ExifInterface | Reading image orientation metadata. |

## Architecture

CHIPCHAP uses a single Android application module (`:app`). `MainActivity` hosts the WebView and exposes a JavaScript bridge for specific native operations. The Java code is separated into data repositories, models, and service classes.

- **Presentation:** screen HTML files are bundled under `app/src/main/assets/screens/`. The Stitch source/export material is retained under `stitch_markdown_project_builder/`.
- **Native host:** `MainActivity.java` loads local screens, manages navigation, coordinates native operations, and handles WebView-to-Java callbacks.
- **Repositories:** classes under `data/` encapsulate operations such as reports, users, comments, votes, notifications, timelines, and local/cloud evidence.
- **Models:** classes under `models/` represent report, user-profile, timeline, and comment data.
- **AI service:** `CivicPhotoAiService.java` contains the Firebase AI Logic integration and retry/error handling.
- **Rules:** `firestore.rules` describes the intended Firestore authorization policy. Read [FIRESTORE_SECURITY.md](FIRESTORE_SECURITY.md) before deploying or relying on it.

### Important design boundaries

- The Android client must not be treated as a trusted authority for user roles.
- Firestore rules are the server-side access-control layer; hiding a screen or button is not security.
- AI analysis is an assistive feature and should not be interpreted as an authoritative civic decision.
- Similar-report matching is location/category based and advisory; it is not semantic duplicate detection.
- Route previews are estimates and are not turn-by-turn navigation or live traffic guidance.

## Repository structure

```text
CHIPCHAP/
├── app/
│   ├── build.gradle
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── assets/
│       │   ├── screens/                 # Bundled application UI screens
│       │   └── vendor/                  # Locally bundled map library assets and license
│       ├── java/com/chipchap/app/
│       │   ├── MainActivity.java        # Android host and native bridge
│       │   ├── ai/                      # AI integration
│       │   ├── data/                    # Repositories and service classes
│       │   └── models/                  # Application data models
│       └── res/                         # Android resources
├── gradle/wrapper/                      # Gradle wrapper
├── stitch_markdown_project_builder/    # Original/exported UI design material
├── firestore.rules                     # Firestore security rules (review before deploy)
├── firebase.json                       # Firebase CLI configuration
├── FIRESTORE_SECURITY.md               # Security model and deployment caveats
├── IMPLEMENTATION_STATUS.md             # Detailed engineering history and verification notes
├── CODEX_PROJECT_BRIEF.md               # Authentication/UI implementation brief
├── build.gradle
├── settings.gradle
└── README.md
```

## Getting started

### Prerequisites

- Android Studio with Android SDK Platform 35 and Android Build Tools installed.
- JDK 17 configured as the Gradle JDK in Android Studio.
- Internet access for Gradle dependency resolution and Firebase/API services.
- A Firebase project configured for the Android package `com.chipchap.app`.
- A Geoapify API key if you want to use place search and route previews.

### Clone the repository

```bash
git clone https://github.com/<your-username>/CHIPCHAP.git
cd CHIPCHAP
```

Open the repository root in Android Studio. Do not open only the `app/` directory; the root contains the Gradle settings and wrapper.

## Firebase and API configuration

The actual Firebase and API credentials are intentionally not part of the public repository. Configure your own development project before building/running the app.

### 1. Firebase Android configuration

1. Create or select a Firebase project in the Firebase Console.
2. Register an Android app with package name **`com.chipchap.app`**.
3. Enable the Email/Password provider in Firebase Authentication.
4. Create/enable Cloud Firestore.
5. Download that Firebase app's `google-services.json`.
6. Place it at:

   ```text
   CHIPCHAP/app/google-services.json
   ```

7. Review and deploy `firestore.rules` only after understanding [FIRESTORE_SECURITY.md](FIRESTORE_SECURITY.md). The rules in this repository are not a substitute for testing them in the Firebase Emulator Suite and validating your own project configuration.

The `google-services.json` file and `.firebaserc` are ignored by Git in this repository. After installing the Firebase CLI, select your own project with `firebase use --add` (or run `firebase init` and choose Firestore) before deploying anything. Do not commit private environment configuration. Firebase client API keys are not equivalent to server secrets, but they should still be restricted to the correct Android package and signing certificate in Google Cloud.

### 2. Geoapify key

Create or obtain a Geoapify API key and add it to the root-level `local.properties` file (which is ignored by Git):

```properties
sdk.dir=/path/to/your/Android/Sdk
GEOAPIFY_API_KEY=YOUR_GEOAPIFY_API_KEY
```

Android Studio normally manages `sdk.dir`; add only the `GEOAPIFY_API_KEY` entry if the SDK path is already configured. A key embedded in an APK can be extracted, so apply provider-side restrictions and usage limits. Do not use a privileged server key in a mobile app.

### 3. Cloudinary

The current Android resources contain a Cloudinary cloud name and unsigned upload preset. These values are designed for client-side use, but an unsigned preset must be tightly restricted in the Cloudinary console (allowed formats, size limits, upload scope, and any available abuse controls). Never place a Cloudinary API secret in the Android project.

### 4. Firebase AI Logic

The project uses Firebase AI Logic (`firebase-ai`) and expects the Firebase-side model/provider configuration and App Check policy to be set up in the Firebase Console. Follow current Firebase documentation for enabling the provider, configuring the model, and enforcing App Check. Do not add a Gemini server secret to source code.

## Build and run

From the repository root:

```bash
# Windows
 gradlew.bat :app:assembleDebug

# macOS / Linux
 ./gradlew :app:assembleDebug
```

The debug APK, if the build succeeds, is generated at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

To install on a connected Android device with ADB:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

You can also select the `app` run configuration in Android Studio and run it on an emulator or physical Android device. Grant camera and location permissions when testing the related features.

## Data model overview

The current Firestore rules and repository code use the following principal collections (exact fields and allowed operations are defined in the source and security rules):

| Collection / path | Purpose |
|---|---|
| `users/{uid}` | Citizen profile associated with a Firebase Authentication UID. |
| `reports/{reportId}` | Civic issue report, ownership, description/category/status, location, and evidence references. |
| `reports/{reportId}/timeline/{timelineId}` | Append-only report status/history events. |
| `reports/{reportId}/comments/{commentId}` | Report discussion/comments. |
| `reports/{reportId}/votes/{...}` | Per-user report voting/interaction records. |
| `users/{uid}/notifications/{notificationId}` | Notifications associated with a user. |

The exact schema and rule conditions may evolve. Consult `firestore.rules`, the repository classes, and `FIRESTORE_SECURITY.md` rather than assuming this overview is a complete schema specification.

## Security notes

This repository is a development project, not a security-certified production release. Before deploying it for real users:

1. Configure and test Firestore rules in the Firebase Emulator Suite, then deploy the reviewed rules to the intended Firebase project.
2. Implement a trusted government-user provisioning process that sets Firebase custom claims using a trusted server/Admin SDK. Never grant government access through a client-writable Firestore field, local preference, or JavaScript flag.
3. Review the WebView JavaScript bridge and navigation/origin restrictions. The bridge exposes native capabilities and therefore needs a strict security boundary.
4. Reassess WebView file/content access and cleartext-traffic settings; use HTTPS and the minimum required permissions.
5. Restrict Firebase and Geoapify keys to the intended app/project and monitor usage.
6. Apply Cloudinary upload restrictions and validate uploaded media server-side where possible.
7. Add production-grade privacy, retention, consent, abuse-prevention, monitoring, and incident-response policies before collecting real civic/user data.

Do not upload real user data, credentials, signing keys, service-account JSON files, or private API secrets to a public GitHub repository.

## Current implementation status and limitations

The repository contains substantial working implementation code, but not every screen or workflow is fully connected to a production backend. Some UI areas may still be placeholders or depend on Firebase Console configuration and deployed rules.

Known limitations recorded by the project implementation notes include:

- A complete Gradle build and physical-device end-to-end test have **not** been established in the latest recorded verification notes. The Gradle wrapper is pinned to Gradle 8.9 and the project uses JDK 17 / Android Gradle Plugin 8.7.3; verify the current environment before claiming a successful build.
- Browser-level checks, Java unit checks against test doubles, and JavaScript syntax checks have passed for several recent checkpoints. These are not equivalent to a full Android build or live Firebase test.
- Firestore rules are documented as **not yet deployed or Firebase Emulator-tested** in `FIRESTORE_SECURITY.md`.
- Government privileges depend on a trusted custom-claim provisioning backend that is not included here. Government-facing screens should not be assumed to be operational for real government accounts.
- Some app functions/screens are incomplete, simulated, or dependent on external Firebase/API configuration. Check `IMPLEMENTATION_STATUS.md` for the detailed feature-by-feature history and known gaps.
- Similar-report detection uses category and geographic distance, not natural-language semantic similarity. It is advisory and operates on a bounded query result.
- Civic hotspot metrics are descriptive calculations over a bounded set of recent reports; they are not predictive analytics and their thresholds are initial values.
- Route previews are not turn-by-turn navigation. Geoapify keys included in a compiled client can be extracted.
- Photo storage and evidence display behavior has documented constraints; do not assume every locally captured image is automatically available to every other user.

For an accurate view of what was changed and what was tested, read [IMPLEMENTATION_STATUS.md](IMPLEMENTATION_STATUS.md), especially the latest checkpoint sections.

## Testing and verification

The project has used a combination of:

- Java compilation/unit checks for isolated logic such as duplicate-report matching and civic analytics.
- JavaScript syntax checks (`node --check`) for injected screen scripts.
- Headless Chromium behavioral checks against bundled screen HTML with mocked native bridges/maps.
- Repository tests using Firestore stand-ins for selected data-access behavior.

These checks cover selected components and checkpoints. They do **not** establish that the complete Android app builds, that live Firebase permissions are correct, or that every feature works on a physical device. Run the Gradle build and perform end-to-end testing in your own configured environment before releasing.

## Contributing

Contributions and issue reports are welcome.

1. Fork the repository.
2. Create a focused branch: `git checkout -b feature/your-change`.
3. Keep credentials and local configuration out of commits.
4. Make the smallest practical change and update relevant documentation.
5. Run the checks relevant to the changed code and describe any tests that could not be run.
6. Open a pull request with a clear summary, screenshots for UI changes, and test evidence.

## License

No license file is currently included. Unless a license is added by the project owner, all rights are reserved and reuse, redistribution, and modification are not automatically granted. Add a `LICENSE` file before inviting external reuse or contributions under an open-source license.

---

**Project:** CHIPCHAP — Community Civic Issue Reporting Platform  
**Android application ID:** `com.chipchap.app`
