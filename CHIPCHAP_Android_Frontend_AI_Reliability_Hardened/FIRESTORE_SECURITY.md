# CHIPCHAP — Firestore security status (Phase 1)

Date: 2026-09-22. Firebase project: `chipchap-e967b`.

## 1. What exists now

- `firestore.rules` — rules covering `users/{uid}`, `reports/{reportId}` and
  `reports/{reportId}/timeline/{timelineId}`, plus an explicit deny for everything else.
- `firebase.json` — minimal config so the rules can be deployed with
  `firebase deploy --only firestore:rules`, or pasted into
  Firebase console → Firestore Database → Rules.

**These rules have not been deployed and have not been run against the Firestore emulator.**
No emulator, Firebase CLI login or device test run was possible from this environment.
Until they are deployed, whatever rules the console currently holds are what is enforced.

## 2. The current rules in one line each

| Path | Read | Create | Update | Delete |
|---|---|---|---|---|
| `users/{uid}` | own doc, or government claim | own doc only, `role` forced to `CITIZEN`, timestamps must be server time | own doc only, `role` and `createdAt` cannot change | never |
| `reports/{reportId}` | own report, or government claim | verified user, `citizenUid == auth.uid`, `status == SUBMITTED`, unassigned | government claim only | never |
| `reports/{reportId}/timeline/{id}` | parent report readable | `actorUid == auth.uid` and (owns parent report or government claim) | never | never |

Field allow-lists (`keys().hasOnly(...)`) are applied on every write, so the client cannot
introduce unexpected fields.

## 3. The genuine blocker: there is no trusted GOVERNMENT role yet

The rules authorize government access with `request.auth.token.role == 'GOVERNMENT'` — a
**Firebase custom claim**, not the Firestore `role` field.

Nothing sets that claim today. Setting custom claims requires the Firebase Admin SDK, which
means a Cloud Function or another trusted backend. That is out of scope for this phase, and
Cloud Functions/Storage are not on the current Firebase plan.

The consequence, stated plainly:

- The Firestore `role` field on `users/{uid}` is **descriptive profile data only**. It is never
  read as an authorization decision anywhere in the app or in the rules.
- `UserRepository` writes `role` exactly once, at profile creation, always as `CITIZEN`. There
  is deliberately no client method that can set `GOVERNMENT`, and the update rule rejects any
  attempt to change `role`.
- Because no account carries the claim, every government branch in the rules currently denies.
  This is intentional: the system fails closed rather than granting access through a
  client-writable field.
- The existing `denyAdminAccess()` gate in `MainActivity` is unchanged. Administration screens
  remain blocked in this build.

**No insecure workaround was implemented.** No `isAdmin` flag, no SharedPreferences role
authorization, no JavaScript-supplied role, no hard-coded government credentials.

## 4. Before Phase 2 (reports) can be trusted

1. Deploy `firestore.rules` and verify them against the Firestore emulator.
2. Decide the government provisioning mechanism (Cloud Function setting custom claims from an
   allow-list, or an admin tool using the Admin SDK). This needs a Firebase plan that allows
   Cloud Functions, or an external trusted backend.
3. Known token-refresh caveat: `request.auth.token.email_verified` comes from the ID token, not
   from a `reload()`. A user who has just verified their email may still hold a token that says
   `email_verified: false` until the token refreshes (up to ~1 hour, or immediately via
   `getIdToken(true)`). Report writes are gated on verification, so Phase 2 should force a token
   refresh after verification succeeds.

## 5. Unrelated pre-existing security findings (not addressed in this phase)

These come from the earlier audit and are recorded here so they are not forgotten. They were
deliberately **not** changed, because this task was scoped to the Firestore foundation:

- WebView has `setAllowFileAccess(true)`, `setAllowContentAccess(true)` and the manifest sets
  `usesCleartextTraffic="true"`.
- Screens load Tailwind from a remote CDN while the `Android` JavaScript bridge is exposed to
  every page, so compromised remote content could reach bridge methods.
- The `google-services.json` API key should be restricted to this package and signing
  certificate in Google Cloud.
