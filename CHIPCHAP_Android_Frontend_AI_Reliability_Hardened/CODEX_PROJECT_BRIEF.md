## Authentication UI — Preserve and Repurpose Existing Stitch Screens

The Stitch project already contains UI screens designed for mobile-number/OTP verification.

**Do not delete these screens or rebuild the verification experience from scratch.**

These existing Stitch screens should be **repurposed for email verification** while preserving their existing visual language and layout as much as reasonably possible.

The goal is to reuse the existing Stitch verification UI, not to introduce a completely new verification design.

### Required behavior

The existing mobile verification/OTP screens should be adapted so that:

* They no longer request a mobile number as an authentication requirement.
* They no longer request or validate an SMS OTP.
* They no longer call any SMS/phone verification API.
* They use the user's Firebase email address as the verification destination.
* The existing verification screen should communicate that a verification email has been sent.
* Existing OTP input fields should be removed or replaced with an appropriate email-verification interaction while retaining the surrounding Stitch visual structure where practical.
* Provide an action to resend the Firebase verification email.
* Provide an action such as "I've Verified" / "Check Verification" that refreshes the Firebase user's authentication state and checks `emailVerified`.
* Display an appropriate message when the email is still unverified.
* Allow the user to continue to the application only after Firebase confirms that the email is verified.
* Handle already-verified users without unnecessarily showing the verification screen.

### Visual preservation

Preserve from the existing Stitch verification screens wherever practical:

* overall layout
* typography
* spacing
* cards/containers
* buttons
* illustrations/images
* colors
* navigation structure
* animations/transitions
* visual hierarchy

Only change elements that are inherently tied to mobile OTP verification.

For example, if the Stitch screen currently says:

"Enter the 6-digit code sent to your phone"

adapt it to an equivalent email-verification message such as:

"Check your email for a verification link"

Do not redesign the screen simply because the underlying authentication mechanism has changed.

### Authentication source of truth

The UI must reflect the real Firebase Authentication state.

Do not simulate email verification with:

* local flags
* SharedPreferences
* JavaScript-only state
* hard-coded success states
* timers
* fake verification codes

Firebase Authentication's `emailVerified` state is the authoritative source.

### Intended flow

Existing Stitch Signup UI
→ Firebase email/password account creation
→ Existing Stitch verification UI, repurposed for email
→ Firebase verification email sent
→ User opens verification link in email
→ User returns to CHIPCHAP
→ "I've Verified" / "Check Verification"
→ Firebase user state refreshed
→ `emailVerified == true`
→ Existing authenticated application flow

If `emailVerified == false`, remain on the verification screen and provide the option to resend the verification email.

The objective is to **preserve the Stitch verification UI while changing its underlying authentication mechanism from mobile OTP to Firebase email verification**.