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

## What phase 2 adds

- **Inbox tab:** every notification on the phone is logged. For a new kind the app asks Keep or Mute; after 3 identical choices for a sender (or 5 for an app) it makes the rule itself. "Always…" makes a rule at once.
- **To respond:** kept notifications wait in one list. Any notification can become a reminder or a task.
- **Muted review:** all muted notifications not yet looked at, one at a time, newest first. Swipe left to dismiss, right for a reminder, up for a task.
- **Week tab:** place tasks on days and confirm the plan. Prompted at 18:00 on any day the week has no plan (on Sunday, for the week ahead).
- **Nightly review:** offered at 21:30, or once the day's list is empty after 17:00. Confirm tomorrow, then the app offers the muted review.

Notification reading needs "notification access" for JayZee in system settings. Because the app is installed outside the Play Store, first open Settings, Apps, JayZee, the three-dot menu, "Allow restricted settings".

## Where to change things

| File | What it controls |
| --- | --- |
| `Parser.kt` | How a typed line is read (dates, times, types) |
| `Planner.kt` | Start-by dates, free hours per day, the Today sections |
| `Scheduler.kt` | Alarms, notifications, brief time, snooze length |
| `CalendarSync.kt` | Writing to the phone calendar |
| `Notifs.kt` | Reading notifications, keep/mute rules and how they are learned |
| `InboxScreen.kt` | Inbox tab and the muted-notification swipe review |
| `PlanScreen.kt` | Week tab and the nightly review |
| `MainActivity.kt` | Navigation, Today and All tabs, cards, the add/edit box |
| `Theme.kt` | Colours and type |
| `Db.kt` | Stored data |
