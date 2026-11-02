
# Stitch Build Guide – CHIPCHAP (Citizen App First)

## Important Rules
- Generate ONLY a portrait mobile application until explicitly told to start the Admin Dashboard.
- Never switch to desktop, tablet, responsive, sidebar, or web layouts.
- Use one consistent design system across every screen.
- Finish each prompt before moving to the next.

---

## Prompt 1 – Design System
Create the design system for a modern civic complaint reporting mobile app named CHIPCHAP.

Include:
- Color palette
- Typography
- 8pt spacing system
- Buttons
- Inputs
- Cards
- Bottom navigation
- Status chips
- AI insight cards
- Empty states
- Success states
- Skeleton loading
- Light theme

Do not create application screens yet.

---

## Prompt 2 – Splash & Onboarding

Create these mobile screens:
1. Splash
2. Language selection
3. 3-page onboarding
4. Login
5. Signup
6. OTP Verification
7. Permissions (Location, Camera, Notifications)

Navigation:
Splash → Language → Onboarding → Login → OTP → Permissions → Home

---

## Prompt 3 – Home

Create the Home screen.

Include:
- Greeting
- Search
- Quick actions
- Report Complaint CTA
- Recent complaints
- Nearby complaints preview
- Community statistics
- Bottom Navigation:
Home | Report | Map | Notifications | Profile

---

## Prompt 4 – Report Complaint Flow

Create ONLY this flow.

Report
↓
Capture Photo
↓
AI Image Analysis
↓
Location Selection
↓
Category
↓
Severity
↓
Description
↓
Review
↓
Upload Progress
↓
Success

AI should highlight cleanliness issue, confidence score and suggested category.

---

## Prompt 5 – Complaint Details

Create:
- Complaint Details
- Live Tracking Timeline
- Assigned Department
- Estimated Resolution
- Before/After Images
- Citizen Comments
- Add Comment

---

## Prompt 6 – Map Module

Create:
- Interactive Map
- Complaint Pins
- Filters
- Nearby Complaints
- Complaint Preview Bottom Sheet
- Open Complaint Details

---

## Prompt 7 – Community

Create:
- Community Feed
- Trending complaints
- Upvote
- Share
- Volunteer events
- Success stories

---

## Prompt 8 – Notifications

Create:
- Notification list
- Status updates
- Government replies
- AI reminders
- Empty state

---

## Prompt 9 – Profile

Create:
- User Profile
- My Complaints
- Achievements
- Settings
- Help
- Privacy
- Logout

---

## Prompt 10 – Complete Mobile Flow

Connect every screen.

Navigation Flow

Splash
→ Language
→ Onboarding
→ Login
→ Permissions
→ Home

Home
├── Report Complaint
│   ├── Camera
│   ├── AI Analysis
│   ├── Location
│   ├── Category
│   ├── Review
│   └── Success
├── Map
├── Community
├── Notifications
└── Profile

Maintain identical design language throughout.

STOP after completing the mobile application.

---

# PHASE 2 (Only after Mobile App is finished)

## Prompt 11 – Admin Dashboard

Now create a desktop web dashboard.

Sections:
- Login
- Dashboard
- Complaint Management
- AI Analytics
- Map
- Department Assignment
- Reports
- Citizen Details
- Settings

This is the ONLY prompt where desktop layouts are allowed.

