# AGENTS.md — CommuteBuddy Design & Architecture Rules

Follow these rules strictly in all tasks:

## 1. Visual Design (Google Maps Android Look)
- **Primary Text**: `#202124`
- **Secondary Text**: `#5f6368`
- **Borders & Dividers**: `#dadce0`
- **Surface**: `#ffffff` (White)
- **Background**: `#f1f3f4`
- **Accent (Only Accent)**: `#1a73e8`
- **Selected Tint**: `#e8f0fe`
- **Typography**: Roboto font family across all text styles.

## 2. Layout, Spacing & Geometry
- **Spacing Grid**: Strict `8px` (`8dp`) spacing grid (`4`, `8`, `12`, `16`, `24`, `32`).
- **Touch Targets**: Minimum `48px` (`48dp`) height/width on all interactive controls.
- **Corner Radius**:
  - Cards: `12px` (`12dp`)
  - Bottom Sheet top corners: `16px` (`16dp`)
  - Search pill & filter chips: Fully rounded (`9999px` / `50%`).
- **Viewport**: Mobile layout (`360–412px` width target), full viewport height (`100dvh` / edge-to-edge full screen), no full-page vertical scroll (map canvas + anchored bottom sheet / internal list scroll).

## 3. Elevation & Effects
- **Allowed Elevation**: Only one soft shadow (`0 1px 3px rgba(60,64,67,.3)` / `2dp` subtle shadow).
- **Banned Visuals**:
  - No gradients, glow effects, or blur/glassmorphism (`backdrop-filter`).
  - No emojis anywhere in UI text or data.
  - No 3D effects, bounce, or spring animations.
  - No decorative illustrations.
  - No purple or neon colors.
- **Icons**: Clean geometric vector icons (Lucide / Material Outlined/Filled equivalent), `24px` (`24dp`), `2px` (`2dp`) stroke weight.
- **Transitions**: Maximum `200ms ease` (`TweenSpec(durationMillis = 180, easing = FastOutSlowInEasing)`).

## 4. Content & States
- **Copywriting**: Real, concise UI text modeled after Google Maps Android (e.g., `"Search here"`, `"4 min"`, `"Every 8 min"`, `"On time"`, `"Platform 2"`).
- **Mandatory List States**: Every list view must implement and support **Loading**, **Empty**, and **Error** states with a retry action.

## 5. Architecture & Code Organization
- **Frontend Only**: Local dummy data stored in `data/`.
- **Modularity**: One component per file. Short, purposeful comments.
- **Directory Structure**:
  - `components/` — Reusable UI building blocks (one component per file)
  - `screens/` — Top-level views (one screen per file)
  - `data/` — Static transit and commute dataset
  - `lib/` — Formatting, map geometry, and state helpers
- **Dependencies**: Do not add new third-party libraries without asking first.
