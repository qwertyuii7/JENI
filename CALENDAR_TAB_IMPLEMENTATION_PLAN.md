# JENI In-App Calendar Tab: Architectural Implementation Plan

## Executive Summary & Vision

This document details the complete end-to-end implementation plan for introducing a **dedicated, fully functional in-app Calendar Tab** into **JENI (Schedule Assistant)**.

Instead of needing an external calendar app, the **Calendar Tab** provides an interactive, visual date explorer directly inside JENI:
1. Users see an interactive monthly grid with colored dot indicators for dates that have events, pending notices, or active alarms.
2. Tapping any date smoothly opens all the schedules and reminders for that specific date below the calendar.
3. Users can review, edit times, toggle alarms, confirm, or delete events directly from the day's cards.

```
┌─────────────────────────────────────────────────────────────────────────┐
│                          JENI CALENDAR VIEW                             │
│                                                                         │
│   ◄   October 2026   ►                           [ Today ]  [ Week/Mo ] │
│   Mon   Tue   Wed   Thu   Fri   Sat   Sun                               │
│    28    29    30     1     2     3     4                               │
│     5     6     7     8     9    10    11   (● blue = schedule)         │
│    12    13    14    15    16    17    18   (● amber = attention)       │
│    19    20    21    22    23    24    25   (● green = alarm set)       │
│    26    27    28    29   [30]   31     1                               │
│                           ▲ Selected                                    │
├─────────────────────────────────────────────────────────────────────────┤
│  📅 SCHEDULES FOR FRIDAY, OCT 30 (2 Plans)                              │
│                                                                         │
│  ┌───────────────────────────────────────────────────────────────────┐  │
│  │ 🎓 DBMS Lab Exam                                                  │  │
│  │ 🕒 10:30 AM – 12:30 PM • Room 402                                 │  │
│  │ 💬 From WhatsApp: College CS Batch (Prof. Verma)                  │  │
│  │ ⏰ Alarm set for 10:15 AM (15m lead)              [Edit] [Alarm]  │  │
│  └───────────────────────────────────────────────────────────────────┘  │
│  ┌───────────────────────────────────────────────────────────────────┐  │
│  │ ☕ Coffee Meet with Rohan                                          │  │
│  │ 🕒 4:30 PM • Starbucks CP                                         │  │
│  │ 💬 From WhatsApp: Rohan                                           │  │
│  │ 🔔 Reminder: 4:15 PM                              [Edit] [Alarm]  │  │
│  └───────────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## 1. Core User Experience & Requirements

### 1.1 Key Capabilities
1. **Dedicated Navigation Tab**: A **"Calendar"** tab in the bottom bar between *Home* and *Pending*.
2. **Interactive Month/Week Grid**:
   - Clean, uncluttered monthly calendar grid.
   - Indicator dots on dates that have confirmed events, pending items, or active alarms.
   - Month navigation (`<` and `>` arrows) and a **"Today"** quick jump button.
3. **Day Card Opening & Detail View**:
   - Tapping any date instantly highlights it and opens all schedule cards for that specific date below the calendar.
   - Schedule cards display title, time badge, sender/WhatsApp chat source, and alarm status.
   - Cards support in-place actions: **Edit time/title**, **Toggle Alarm/Reminder**, **Confirm**, or **Delete**.
4. **Empty State & Quick Add**:
   - If a selected date has no plans: displays *"No plans scheduled for this day"* with a **"+ Add Event"** button to manually create an entry on that date.

---

## 2. Architecture & Data Flow

JENI uses an event-sourced architecture where all schedules and reminders are deterministically projected by `ProjectionEngine.projectState()` from `eventDao().getAllEvents()`. The Calendar tab seamlessly consumes this existing architecture without requiring database migrations.

```mermaid
flowchart TD
    DB[(Room Database: EventDao)] -->|getAllEventsFlow| StateProj[ProjectionEngine.projectState]
    StateProj --> ProjectedSchedules[projectedState.schedules]
    StateProj --> ProjectedReminders[projectedState.reminders]

    ProjectedSchedules --> CalMapper[CalendarDateMapper]
    ProjectedReminders --> CalMapper

    CalMapper -->|Map<LocalDate, List<ScheduleEntity>>| CalViewModel[Calendar Tab State]

    CalViewModel --> MonthGrid[Month Calendar Grid Component]
    CalViewModel --> DayAgenda[Selected Day Agenda List Component]

    MonthGrid -->|OnDateSelected(date)| CalViewModel
    DayAgenda -->|OnToggleAlarm / OnEdit| MainActivityActions[MainActivity Handlers]
```

### 2.1 Date Grouping & Mapping Logic
Every `ScheduleEntity` contains:
- `start`: ISO-8601 string (e.g., `"2026-10-30T10:30:00+05:30"`)
- `start_date`: Date string (e.g., `"2026-10-30"`)
- `time_unspecified`: Boolean flag

We parse both to an Indian Standard Time (`Asia/Kolkata`) `java.time.LocalDate` key:
```kotlin
data class CalendarDayItem(
    val date: LocalDate,
    val schedules: List<ScheduleEntity>,
    val reminders: List<ReminderEntity>,
    val hasConfirmed: Boolean,
    val hasPending: Boolean,
    val hasActiveAlarm: Boolean
)
```

---

## 3. UI/UX Component Specifications

### 3.1 App Navigation Bar Update (`MainActivity.kt`)
Add `AppTab.CALENDAR` to the primary navigation enum:
```kotlin
enum class AppTab(val title: String) {
    HOME("Home"),
    CALENDAR("Calendar"),
    PENDING("Pending"),
    ALERTS("Alerts"),
    SETTINGS("Settings"),
    DEV_CONSOLE("Developer"),
    CHAT_MANAGEMENT("Chat Management")
}
```
Bottom navigation bar icon: `Icons.Default.CalendarMonth` with badge support for today's active plans.

---

### 3.2 Calendar Screen (`CalendarScreen.kt`)
The screen consists of two smoothly coordinated sections:

#### Top Section: Interactive Month Grid
- **Header Row**:
  - Month & Year title (e.g. `"October 2026"`)
  - Previous (`<`) and Next (`>`) month icon buttons
  - `"Today"` button (smooth scrolls back to current date if scrolled away)
  - Weekday labels: `M`, `T`, `W`, `T`, `F`, `S`, `S`
- **Day Cells**:
  - Size: ~40x44 dp circular/rounded pill touch target
  - Active Selection: Highlighted with `Primary` indigo circle or `Accent` cyan outline
  - Today Indicator: Glowing border or bold accent text
  - Dot Indicators:
    - 🔵 Indigo Dot = Confirmed event
    - 🟡 Amber Dot = Needs attention / pending review
    - 🟢 Green Dot = Active alarm clock set

#### Bottom Section: Selected Date Schedule Cards
- **Section Title**: e.g., `"📅 Friday, 30 October 2026 · 2 Schedules"`
- **List of Cards**:
  - Renders `ScheduleCard` with full time duration, sender name, chat badge, and alarms.
  - Interactive actions on each card:
    - ⏰ **Alarm Clock Status**: Shows exact alarm trigger time with toggle switch.
    - ✏️ **Edit**: Opens `EditScheduleDialog` to adjust title or time.
    - 🗑️ **Delete / Dismiss**: With instant undo snackbar.
- **Empty State**:
  - Modern minimalistic card: *"Clear Schedule — No events on this date"*.
  - Fast action button: `"+ Add Schedule"` to create an event directly for the selected date.

---

## 4. Implementation Phase Breakdown

### Phase 1: Pure Kotlin Calendar Engine (`CalendarEngine.kt`)
- Zero Android UI imports (pure JVM testable logic).
- Generates 6x7 month grids (42 days including leading/trailing days of adjacent months).
- Calculates event counts and dot indicators per day.

### Phase 2: Calendar UI Components (`CalendarComponents.kt`)
- `MonthHeader(currentMonth, onPrevMonth, onNextMonth, onJumpToToday)`
- `WeekdayHeaderRow()`
- `DayCell(dayNumber, isCurrentMonth, isToday, isSelected, dots, onClick)`
- `DayAgendaSection(selectedDate, items, onEdit, onDelete, onToggleAlarm)`

### Phase 3: Screen Assembly & Wiring (`CalendarScreen.kt`)
- Connects Room `eventsFlow` to `CalendarEngine`.
- Manages selection state: `selectedDate = remember { mutableStateOf(LocalDate.now()) }`.
- Integrates pull-to-refresh or active notification sync button.

### Phase 4: Bottom Navigation Integration (`MainActivity.kt`)
- Insert `AppTab.CALENDAR` into `NavigationBar`.
- Handle deep-links (e.g. tapping a notification date opens Calendar tab focused on that date).

### Phase 5: Verification & Packaging
- Unit test calendar date math, month boundary rollovers, leap years.
- Verify on Samsung Galaxy A15 5G (One UI 8.5 / Android 16).
- Build signed release APK and save to user Downloads folder.

---

## 5. Technical Deliverables & Milestones

| Milestone | Deliverable | Est. Effort |
|---|---|---|
| **M1** | `CalendarEngine.kt` (Date math, grid generator, month boundaries) | 2 hours |
| **M2** | `CalendarComponents.kt` (Month grid, Day cells, dots, micro-animations) | 3 hours |
| **M3** | `CalendarScreen.kt` (Agenda cards list, detail sheet, quick add) | 3 hours |
| **M4** | Navigation wiring in `MainActivity.kt` & Theme alignment | 1 hour |
| **M5** | Regression testing, APK build & push to GitHub | 1 hour |

---

> [!TIP]
> **Zero External Dependency Guarantee**: All calendar math is implemented using standard `java.time.*` (ThreeTen / API 26+ desugared), requiring zero third-party calendar libraries. This keeps the APK lightweight and completely private on-device.
