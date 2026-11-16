# Project JayZee 2.0

Personal organizer app for Android. All data stays on the phone.

## Installing or updating

1. On the phone, open this repository's **Releases** page and tap **Latest build**.
2. Download `JayZee.apk` and open it. Allow your browser to install apps if asked.
3. Updates install over the old version and keep your data.

Every push to `main` rebuilds the app automatically (see `.github/workflows/build.yml`).

## What phase 1 does

- One capture box: type or speak a line such as `dentist thursday 3pm`; the app decides whether it is a reminder, task or note, and you can correct the guess with a tap.
- Reminders that cannot be swiped away: mark them done or give them a new time. A swiped reminder returns after ten minutes.
- Tasks get a start-by date worked out from the due date and the minutes of work.
- A task moved three times is flagged, and the app asks for a first step of ten minutes or less.
- Morning brief notification at 7:00, and a Today screen.
- Timed reminders are written to the phone calendar.
- Home-screen widget for quick capture.

## Where to change things

| File | What it controls |
| --- | --- |
| `Parser.kt` | How a typed line is read (dates, times, types) |
| `Planner.kt` | Start-by dates, free hours per day, the Today sections |
| `Scheduler.kt` | Alarms, notifications, brief time, snooze length |
| `CalendarSync.kt` | Writing to the phone calendar |
| `MainActivity.kt` | Screens |
| `Db.kt` | Stored data |
