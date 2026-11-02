---
name: Civic Clarity
colors:
  surface: '#faf9ff'
  surface-dim: '#ccdaff'
  surface-bright: '#faf9ff'
  surface-container-lowest: '#ffffff'
  surface-container-low: '#f1f3ff'
  surface-container: '#e9edff'
  surface-container-high: '#e1e8ff'
  surface-container-highest: '#d8e2ff'
  on-surface: '#051a3e'
  on-surface-variant: '#434654'
  inverse-surface: '#1d3054'
  inverse-on-surface: '#edf0ff'
  outline: '#737685'
  outline-variant: '#c3c6d6'
  surface-tint: '#0c56d0'
  primary: '#003d9b'
  on-primary: '#ffffff'
  primary-container: '#0052cc'
  on-primary-container: '#c4d2ff'
  inverse-primary: '#b2c5ff'
  secondary: '#914d00'
  on-secondary: '#ffffff'
  secondary-container: '#fe8a00'
  on-secondary-container: '#613100'
  tertiary: '#004e32'
  on-tertiary: '#ffffff'
  tertiary-container: '#006844'
  on-tertiary-container: '#72e9af'
  error: '#ba1a1a'
  on-error: '#ffffff'
  error-container: '#ffdad6'
  on-error-container: '#93000a'
  primary-fixed: '#dae2ff'
  primary-fixed-dim: '#b2c5ff'
  on-primary-fixed: '#001848'
  on-primary-fixed-variant: '#0040a2'
  secondary-fixed: '#ffdcc3'
  secondary-fixed-dim: '#ffb77e'
  on-secondary-fixed: '#2f1500'
  on-secondary-fixed-variant: '#6e3900'
  tertiary-fixed: '#82f9be'
  tertiary-fixed-dim: '#65dca4'
  on-tertiary-fixed: '#002113'
  on-tertiary-fixed-variant: '#005235'
  background: '#faf9ff'
  on-background: '#051a3e'
  surface-variant: '#d8e2ff'
  civic-blue-dark: '#0747A6'
  safety-orange-light: '#FFFAE6'
  alert-red: '#DE350B'
  surface-gray: '#F4F5F7'
  border-subtle: '#DFE1E6'
typography:
  headline-lg:
    fontFamily: Hanken Grotesk
    fontSize: 32px
    fontWeight: '700'
    lineHeight: 40px
    letterSpacing: -0.02em
  headline-md:
    fontFamily: Hanken Grotesk
    fontSize: 24px
    fontWeight: '600'
    lineHeight: 32px
    letterSpacing: -0.01em
  headline-sm:
    fontFamily: Hanken Grotesk
    fontSize: 20px
    fontWeight: '600'
    lineHeight: 28px
  body-lg:
    fontFamily: Hanken Grotesk
    fontSize: 18px
    fontWeight: '400'
    lineHeight: 28px
  body-md:
    fontFamily: Hanken Grotesk
    fontSize: 16px
    fontWeight: '400'
    lineHeight: 24px
  body-sm:
    fontFamily: Hanken Grotesk
    fontSize: 14px
    fontWeight: '400'
    lineHeight: 20px
  label-md:
    fontFamily: JetBrains Mono
    fontSize: 12px
    fontWeight: '500'
    lineHeight: 16px
    letterSpacing: 0.05em
  label-sm:
    fontFamily: JetBrains Mono
    fontSize: 10px
    fontWeight: '500'
    lineHeight: 14px
    letterSpacing: 0.05em
rounded:
  sm: 0.25rem
  DEFAULT: 0.5rem
  md: 0.75rem
  lg: 1rem
  xl: 1.5rem
  full: 9999px
spacing:
  base: 8px
  xs: 4px
  sm: 8px
  md: 16px
  lg: 24px
  xl: 32px
  container-margin: 20px
  gutter: 12px
---

## Brand & Style

The design system is anchored in the concept of "Civic Clarity"—a design philosophy that prioritizes transparency, efficiency, and trust between citizens and their local government. The target audience encompasses a broad demographic of urban residents who require a friction-less way to contribute to their community's upkeep.

The visual style is **Corporate / Modern** with a strong emphasis on **Minimalism**. It utilizes generous whitespace to reduce cognitive load during the reporting process, ensuring that the interface feels helpful rather than bureaucratic. The aesthetic is clean and functional, employing subtle depth and a refined color palette to evoke a sense of professional reliability and civic pride.

## Colors

This design system utilizes a "Trust & Action" palette. 

- **Primary (Civic Blue):** Used for core branding, primary actions, and official states. It conveys stability and authority.
- **Secondary (Safety Orange):** Reserved for alerts, high-severity indicators, and attention-grabbing elements that require immediate citizen awareness.
- **Tertiary (Success Green):** Applied to resolution states, "Before/After" success stories, and completed tasks.
- **Neutral:** A deep navy-ink is used for typography to maintain high legibility, while a series of cool grays define the surface architecture.

The default mode is **Light**, ensuring maximum readability under varied outdoor lighting conditions when citizens are likely to report issues.

## Typography

The typography system uses **Hanken Grotesk** for all primary communication. Its sharp, contemporary geometry provides a modern professional feel that is highly legible at small sizes. 

For technical data, confidence scores (AI analysis), and status labels, **JetBrains Mono** is introduced. This monospaced font adds a "systematic" layer to the UI, signaling to the user that information is being processed accurately and technically.

Scale adjustments for mobile:
- `headline-lg` is reserved for onboarding and major success states.
- `headline-md` serves as the standard page title.
- Body text maintains a minimum of `16px` for accessibility in mobile environments.

## Layout & Spacing

This design system adheres to a strict **8pt Grid System** to ensure visual rhythm and vertical consistency. 

**Layout Model:**
- **Fluid Grid:** Content expands to fill the mobile width with a fixed outer margin of `20px`.
- **Vertical Rhythm:** Elements are separated by multiples of 8 (`16px`, `24px`, `32px`) depending on their relationship.
- **Form Factors:** This system is optimized exclusively for portrait mobile devices. Interactive elements (buttons, inputs) must maintain a minimum touch target height of `48px` (6 units).

## Elevation & Depth

The system uses **Tonal Layers** combined with **Ambient Shadows** to define hierarchy without clutter.

- **Level 0 (Background):** The base layer uses `#FFFFFF` or a very subtle `#F4F5F7`.
- **Level 1 (Cards):** White surfaces with a soft, diffused shadow (`0px 4px 12px rgba(9, 30, 66, 0.08)`). Used for complaint cards and feed items.
- **Level 2 (Overlays/Modals):** Standard white surface with a stronger shadow (`0px 8px 24px rgba(9, 30, 66, 0.12)`) and a `40%` black backdrop blur for focus.
- **AI Insight Layer:** AI-generated cards use a subtle blue-tinted background (`#E6EFFC`) with a thin `1px` border in the primary color to distinguish them from human-generated content.

## Shapes

The shape language is **Rounded**, reflecting an approachable and community-focused brand.

- **Standard Elements:** Buttons, input fields, and small cards use a `0.5rem (8px)` radius.
- **Large Components:** Onboarding cards and primary containers use `rounded-lg (16px)`.
- **Status Chips:** Use a full pill shape (`rounded-full`) to differentiate them from interactive buttons.
- **Bottom Sheets:** Use a `24px` top-only corner radius to create a soft, "drawer" feel.

## Components

### Buttons
- **Primary:** Solid `Civic Blue` with white text. High emphasis.
- **Secondary:** White background with `Civic Blue` border and text. Medium emphasis.
- **Ghost:** No background or border. Used for "Cancel" or "Skip" actions.
- **Safety Action:** Solid `Safety Orange` for "High Severity" reporting.

### Inputs
- **Text Fields:** Subtle gray border (`#DFE1E6`) that thickens and turns `Civic Blue` on focus.
- **Labels:** Always visible above the input using `label-md` typography.

### Chips (Status)
- **Pending:** Soft Blue background.
- **In Progress:** Soft Orange background.
- **Resolved:** Soft Green background.

### AI Insight Cards
Feature a "Sparkle" icon and a `label-sm` tag saying "AI ANALYSIS". These cards use a light blue fill to indicate they are generated by the app's processing engine.

### Navigation
- **Bottom Bar:** 5-tab layout (Home, Report, Map, Notifications, Profile). The "Report" button is centrally located and visually prioritized with a larger circular background or distinct color.

### Feedback States
- **Success:** Full-screen illustration with `headline-lg` and a primary action button.
- **Skeleton Loading:** Shimmering gray blocks mirroring the final card structure to reduce perceived wait time.
- **Empty States:** Centered, de-saturated illustrations with a clear "Call to Action" button.