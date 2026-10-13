# QA_PHONE.md: Physical Device Soak Protocol & Verification Guide
## Target: Samsung Galaxy A15 5G • Android 16 • One UI 8.5

> **CRITICAL RULE**: **No device claims without owner verification.**  
> Automated unit test suites verify parser parity and rule tables, but OS-level features on Samsung hardware (NotificationListener uptime under One UI 8.5, Doze wakeups via `setAlarmClock`, memory-killer survival, and `BOOT_COMPLETED` recovery) require physical on-device execution.
>
> **M4 Gate**: Do not begin Milestone 4 until the owner completes this 48-hour soak procedure and confirms the checklist below.

---

## 1. Physical Device Manual Setup (No ADB — Settings Only)

On Samsung One UI 8.5 (Android 16), aggressive background process termination and sideload security policies must be manually configured via System Settings. Complete each step in order:

### Step 1: Allow Restricted Settings (Android 16 Sideload Bypass)
1. Open phone **Settings** -> **Apps**.
2. Tap **Schedule Assistant**.
3. Tap the **three dots (⋮)** in the top-right corner.
4. Tap **Allow restricted settings**.
5. Authenticate with your fingerprint or device PIN when prompted.
   > *Why: Android 16 blocks Notification Access for apps installed outside Galaxy Store/Google Play until explicitly unrestricted.*
- [ ] Allow restricted settings enabled (needs owner verification)

### Step 2: Enable Notification Access (Listener Service)
1. In phone **Settings** -> **Apps**, tap **Special app access** (or tap ⋮ -> Special access).
2. Tap **Notification access** (or **Notification read, reply & control**).
3. Find **Schedule Assistant Notification Reader** and toggle it **ON**.
4. Review the system prompt and tap **Allow**.
- [ ] Notification access granted (needs owner verification)

### Step 3: Grant System Notifications Permission
1. Open **Settings** -> **Apps** -> **Schedule Assistant**.
2. Tap **Permissions** -> **Notifications**.
3. Select **Allow notifications**. Ensure "Allow sound and vibration" and "Allow pop-up" are enabled.
- [ ] Notification posting permission granted (needs owner verification)

### Step 4: Set Battery Usage to Unrestricted
1. Open **Settings** -> **Apps** -> **Schedule Assistant**.
2. Tap **Battery**.
3. Select **Unrestricted** (change from default *Optimized*).
   > *Why: Prevents One UI from pausing background execution when the device enters deep sleep.*
- [ ] App battery set to Unrestricted (needs owner verification)

### Step 5: Add to "Never Auto Sleeping Apps"
1. Open **Settings** -> **Battery** (or **Device Care** -> **Battery**).
2. Tap **Background usage limits**.
3. Tap **Never auto sleeping apps** (or *Never sleeping apps*).
4. Tap the **+** (Add) icon in the top right.
5. Select **Schedule Assistant** and tap **Add**.
   > *Why: One UI 8.5 automatically moves apps to Deep Sleep after 24-72 hours of low screen interaction unless added here.*
- [ ] Added to Never auto sleeping apps (needs owner verification)

### Step 6: Lock App in Recent Tasks ("Keep Open")
1. Open the **Schedule Assistant** app.
2. Swipe up to open the **Recent Apps** (task switcher) overview.
3. Tap the circular **Schedule Assistant app icon** above its preview card.
4. Tap **Keep open** (a small padlock icon will appear on the bottom of the card).
   > *Why: Prevents One UI from killing the foreground service process when you tap "Close all".*
- [ ] Locked with padlock in Recent Apps (needs owner verification)

### Step 7: Allow Alarms & Reminders
1. Open **Settings** -> **Apps** -> **Special app access**.
2. Tap **Alarms & reminders**.
3. Ensure **Schedule Assistant** is toggled **ON** (Allowed).
   > *Why: Required for exact AlarmManager.setAlarmClock() scheduling.*
- [ ] Alarms & reminders permission enabled (needs owner verification)

### Step 8: Disable Auto Restart
1. Open **Settings** -> **Device Care**.
2. Tap **Auto optimization**.
3. Tap **Auto restart** (or *Restart when needed*) and toggle it **OFF**.
   > *Why: Prevents unexpected overnight restarts while the phone is locked before first PIN entry.*
- [ ] Auto restart disabled (needs owner verification)

### Step 9: Disable Adaptive Battery
1. Open **Settings** -> **Battery** -> **More battery settings**.
2. Toggle **Adaptive battery** to **OFF**.
- [ ] Adaptive battery disabled (needs owner verification)

---

## 2. Diagnostics Screen Verification (Samsung Galaxy A15 5G)

Launch **Schedule Assistant** -> tap **Diagnostics** tab (bottom right) and verify live system states:

- [ ] **Restricted Settings**: Displays `ALLOWED` (needs owner verification)
- [ ] **Samsung Deep Sleep Status**: Displays `VERIFY MANUALLY` with confirmation that Step 5 was performed (needs owner verification)
- [ ] **Battery Whitelist Status**: Displays `WHITELISTED` — re-queried dynamically from OS on every resume (never cached) (needs owner verification)
- [ ] **Notification Listener State**: Displays `CONNECTED` (Green badge) (needs owner verification)
- [ ] **Foreground Service**: Displays `ACTIVE` (Green badge) (needs owner verification)
- [ ] **POST_NOTIFICATIONS**: Displays `GRANTED` (Green badge) (needs owner verification)
- [ ] **canScheduleExactAlarms**: Displays `GRANTED` (Green badge) (needs owner verification)
- [ ] **Full-Screen Intent Allowed**: Displays `ALLOWED` (Green badge) in Diagnostics screen (needs owner verification)
- [ ] **getNextAlarmClock**: Displays the exact next alarm timestamp and status bar alarm icon is visible immediately after scheduling (needs owner verification)
- [ ] **Scheduled Alarm Count**: Reflects active scheduled alarms/reminders (needs owner verification)
- [ ] **Last Scheduling Error**: Displays `NONE` (or explicit error details in red if scheduling failed) (needs owner verification)
- [ ] **Alerts Tab & Scheduling Audit Log**: Confirmed events appear with status and audit log entries detailing API calls (`AlarmManager.setAlarmClock`, `AlarmManager.cancel`) (needs owner verification)
- [ ] **Single Delivery Mechanism (No ACTION_SET_ALARM)**: One mechanism only via `AlarmManager.setAlarmClock` + `AlarmRingingService` (no external Clock app dependency) (needs owner verification)
- [ ] **Guard Against Double Ringing**: One ringer per alarm ID enforced; duplicate receiver triggers never double-ring or stack audio streams (needs owner verification)
- [ ] **Cancel & Reschedule Alerts**: Cancelling an event cancels its AlarmManager intents; rescheduling cancels previous alerts and schedules new trigger times, with `getNextAlarmClock` immediately reflecting the change (needs owner verification)
- [ ] **Concurrent Same-Time Alarms (Fix #1)**: When multiple alarms trigger at the exact same trigger time (e.g. sched-82b5af48 and sched-83433422 at 11:00:00Z), per-alarm state in `AlarmRingingService` tracks all alarms. Ringing UI lists each active alarm with individual Dismiss buttons + Dismiss All + Snooze All. Unique notification IDs and request codes ensure no collision, and service stops only when none remain (needs owner verification)
- [ ] **Alarm Auto-Stop & Missed Alarm (Fix #2)**: Configurable auto-stop timeout (default 5 min). When timeout expires, service stops ringing and posts a "Missed Alarm: <title>" high-importance notification. Swiping/dismissing the foreground notification stops ringing. "Stop all alarms" button available in Alerts and Diagnostics screens (needs owner verification)
- [ ] **Full-Screen UI & Screen Wake Audit (Fix #3)**: When alarm fires, wake lock with `ACQUIRE_CAUSES_WAKEUP` + `setTurnScreenOn(true)` + `setShowWhenLocked(true)` wakes screen and dismisses keyguard. At fire time, `canUseFullScreenIntent`, `fullScreenSet`, `interactive`, `keyguardLocked`, and `activityLaunchResult` are audited in `alarm_audit_logs` and included in export. If permission restricted, Diagnostics button opens `ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT` (needs owner verification)
- [ ] **Immediate Reminder for ~10m Events (Fix #4)**: Injecting "class today <now+10m>" (with alarm off) -> Confirm immediately schedules and audits reminder (`AlarmManager.setAlarmClock (Immediate notification)`). Export includes `event_log` (`type`, `entity_id`, `timestamp`, `seq`, `hlc` — strictly no raw text) and `debug_injections` (`prefilter_result`, `parser_output`, `ambiguous`) (needs owner verification)
- [ ] **WhatsApp Filtering & Message Counts (Fix #5)**: Filters out WhatsApp system notifications ("Checking for new messages", "WhatsApp Web", chat backup). `is_group` is strictly determined by `MessagingStyle.isGroupConversation == true` (never defaults to true for 1-on-1 contact chats). Counts real chat messages separately from system notifications in Diagnostics and export (needs owner verification)
- [ ] **Deduplicated EXIT_INFO Heartbeats (Fix #6)**: Deduplicates `ApplicationExitInfo` records so duplicate records sharing the same exit timestamp are never re-logged (needs owner verification)
- [ ] **Next Alarm Clock Visibility (Fix #7)**: Shows `getNextAlarmClock` immediately after scheduling in Diagnostics and in the JSON export (needs owner verification)
- [ ] **Immediate Reminder on Past Lead Time**: If reminder time is in the past but the event is in the future (e.g. 10m away with 15m lead time), reminder fires immediately (`now + 1000ms`) and is audited with `isImmediate=true` (never silently dropped) (needs owner verification)
- [ ] **Reminder by Default & Opt-in Alarm**: Reminders scheduled by default; audible alarms opt-in per event via Pending card toggle. Lead times configurable in Diagnostics (Reminder default 15m, Alarm default at start) (needs owner verification)
- [ ] **Survives Silent / Vibrate / DND & Lockscreen**: Alarm audio uses `USAGE_ALARM` stream and notification channel has `bypassDnd=true`; full-screen ringing UI launches over lockscreen (needs owner verification)
- [ ] **Snooze (+5m) and Dismiss**: Dismiss immediately terminates `AlarmRingingService` and audio stream; snooze reschedules alarm by 5 minutes (needs owner verification)
- [ ] **Boot Recovery Audit**: Boot recovery restores all future alarms from Room event log with per-attempt audit logging in `alarm_audit_logs` (needs owner verification)
- [ ] **Automatic Heartbeat Log**: Append-only log automatically records ticks on process start (`Application.onCreate`) + `ApplicationExitInfo`, listener connect/disconnect, WhatsApp notifications only (count only), service lifecycle, boot, and 15 min WorkManager aggregate ticks (needs owner verification)
- [ ] **ApplicationExitInfo Historical Reasons**: Previous process exit reasons with timestamps automatically logged on process start (needs owner verification)

### Device Testing Fixes (Galaxy A15 5G):
- [ ] **Title Extraction Subject Retention**:
  - "kal 6 baje physics lab hai" -> extracts title `"physics lab"` (needs owner verification)
  - "dbms ki class hai" -> extracts title `"DBMS
   class"` (needs owner verification)
  - "chemistry practical" -> extracts title `"chemistry practical"` (needs owner verification)
  - "OS ki lecture" -> extracts title `"OS ki lecture"` (needs owner verification)
  - Cancel/reschedule matching does not merge "physics lab" with "chemistry lab" (needs owner verification)
- [ ] **Filler Words & Hindi Cancel/Reschedule Ingestion**:
  - Title fillers (`ki/ka/ke`, Devanagari particles `की/का/के`, and Hinglish fillers `wali/wala/wale`, `वाली/वाला/वाले`) loaded from `/spec/fillers.json` into core and Kotlin app (needs owner verification)
  - "OS lecture cancelled", "OS की lecture रद्द हो गई", and "OS वाली क्लास रद्द हो गई" match existing "OS ki lecture" (needs owner verification)
  - Hindi reschedule "DBMS class सोमवार को स्थगित हो गई" matches existing "DBMS class" and inherits original clock time (needs owner verification)
  - Physics lab and chemistry lab strictly maintain non-merging separation (needs owner verification)
- [ ] **Log-Only Home Visibility & First-Run Prompt**:
  - Fresh install defaults to Log-only mode ON (needs owner verification)
  - Persistent Home banner displayed: "Log-only ON: no alerts will be created" (needs owner verification)
  - First-run explanation dialog shown on first app launch explaining suppression of real notification alerts (needs owner verification)
- [ ] **Allowlist Enforcement & Ingestion Decision Log**:
  - Diagnostics lists all WhatsApp chats seen in the last 24h with allowed/blocked state (needs owner verification)
  - Per-chat Allow/Block toggles update allowlist dynamically (needs owner verification)
  - "Capture all chats" test toggle (default OFF) allows testing all chats without allowlisting (needs owner verification)
  - Ingestion decision log records every notification: group vs DM, allowlisted or not, disposition (`skipped: own status/summary/duplicate/not allowlisted` or `processed` with prefilter and parser outputs) (needs owner verification)
  - Included in JSON export with no raw text beyond existing anonymized field (needs owner verification)
- [ ] **Live Diagnostics Refresh & Full-Screen Intent Warning**:
  - Diagnostics rows refresh live on resume, after scheduling calls, and periodically every 1.5s while visible (needs owner verification)
  - `getNextAlarmClock` displays label (Reminder vs Alarm) and target time (needs owner verification)
  - Scheduled count reflects active alarms/reminders (needs owner verification)
  - `canUseFullScreenIntent()` queried live via `NotificationManager`. When blocked, shows warning on Home with button opening `ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT` (needs owner verification)

---

## 3. Dedicated 48-Hour Soak Test Cases

During the 48-hour soak window, execute these five stress scenarios:

### Case 1: 8-Hour Overnight Lock (Deep Doze Survival)
1. At 11:00 PM, confirm a test alarm scheduled for 07:00 AM under the **Alerts** tab.
2. Turn off the screen and leave the Samsung Galaxy A15 5G unplugged for 8 continuous hours.
3. **Pass Criteria**:
   - At 07:00 AM, `AlarmManager.setAlarmClock()` fires on the exact minute.
   - Screen turns on, phone vibrates and plays alert sound despite 8 hours of deep Doze.
   - No "missed" flag unless trigger time passed while phone was off.
- [ ] 8-hour overnight Doze wake alarm fired on time (needs owner verification)

### Case 2: Reboot Without Opening the App
1. With at least 1 future confirmed schedule in the database, reboot the phone via power menu.
2. Once the device boots up and displays the lockscreen, unlock with PIN/fingerprint.
3. **DO NOT** open the Schedule Assistant app.
4. From another phone or web client, send a test WhatsApp message:
   `"kal 4 baje DBMS class hai"`
5. **Pass Criteria**:
   - `ScheduleNotificationListenerService` and `ScheduleForegroundService` start automatically on boot.
   - Heads-up alert appears: *"New Schedule Found: DBMS class"* without opening the app UI.
   - Now open the app -> Diagnostics screen shows updated **Last Boot Recovery Time** and freshly re-read battery whitelist status.
- [ ] Ingested notifications and recovered alarms after boot without launching UI (needs owner verification)

### Case 3: Extreme Memory Pressure Stress
1. With Schedule Assistant running in background, launch Samsung Camera app.
2. Record 3 minutes of high-resolution video.
3. Open multiple resource-heavy apps (Chrome with 15 tabs, YouTube, high-memory games).
4. Send WhatsApp schedule message: `"meeting Friday 11 pe shift ho gayi"`.
5. **Pass Criteria**:
   - Low Memory Killer (LMK) does not terminate `ScheduleNotificationListenerService`.
   - Message is ingested within 5 seconds.
- [ ] Memory pressure survived without listener disconnect (needs owner verification)

### Case 4: One UI Battery Saver Mode Test
1. Pull down Quick Settings shade -> Enable **Power saving** mode.
2. Keep phone locked for 30 minutes.
3. Send test WhatsApp message.
4. **Pass Criteria**:
   - Ingestion pipeline continues processing notifications.
   - Notification listener remains connected.
- [ ] Ingestion verified under Power Saving mode (needs owner verification)

### Case 5: Muted WhatsApp Chat & Group Ingestion
1. In WhatsApp, mute a test group or conversation ("Mute notifications -> Always").
2. Send test schedule message into the muted conversation:
   `"Rahul: kal 2 baje lab exam hai"`
3. WhatsApp posts a silent notification (no sound/vibration).
4. **Pass Criteria**:
   - Schedule Assistant still captures the status bar notification.
   - Group sender prefix `"Rahul: "` is cleanly stripped.
   - Title `"lab exam"` and time `14:00` extracted into diagnostic log.
- [ ] Muted chat notification parsed correctly (needs owner verification)

---

## 4. Hourly Soak Logging & Export Protocol

**Rule**: **Log-only mode must remain ON for real WhatsApp notifications** throughout the 48-hour soak test so real personal chatter is never converted into unwanted alerts or alarms. Debug injection, Confirm, and Alarm Self-Test remain fully functional.

### Automatic Heartbeat & Checkpoints:
- **Automatic Heartbeat Log (Append-Only)**: The app runs continuous background logging with zero owner action required:
  - Process start (`Application.onCreate`) + `ApplicationExitInfo` historical exit reasons
  - Listener connection / disconnection events
  - Notification posted events (count only, zero message payload stored in heartbeat)
  - Foreground service lifecycle (`onCreate`, `onDestroy`, `onTaskRemoved`)
  - Device boot recovery (`BOOT_COMPLETED`)
  - Periodic 15-minute background ticks scheduled via WorkManager
- **Manual Milestone Checkpoints (Optional)**: At each milestone, you may optionally open the **Diagnostics** tab and tap **"+ Record Checkpoint (Optional)"**:

| Milestone | Target Elapsed | Checkpoint Item | Owner Verification |
| :--- | :--- | :--- | :--- |
| **Hour 1** | T + 1h | Initial setup verification | - [ ] Hour 1 checkpoint recorded (needs owner verification) |
| **Hour 6** | T + 6h | Post-commute background check | - [ ] Hour 6 checkpoint recorded (needs owner verification) |
| **Hour 12** | T + 12h | Day 1 WhatsApp traffic | - [ ] Hour 12 checkpoint recorded (needs owner verification) |
| **Hour 18** | T + 18h | Evening group chat check | - [ ] Hour 18 checkpoint recorded (needs owner verification) |
| **Hour 24** | T + 24h | Post-overnight Doze check (Case 1) | - [ ] Hour 24 checkpoint recorded (needs owner verification) |
| **Hour 30** | T + 30h | Post-reboot test check (Case 2) | - [ ] Hour 30 checkpoint recorded (needs owner verification) |
| **Hour 36** | T + 36h | Post-memory stress check (Case 3) | - [ ] Hour 36 checkpoint recorded (needs owner verification) |
| **Hour 42** | T + 42h | Battery saver check (Case 4) | - [ ] Hour 42 checkpoint recorded (needs owner verification) |
| **Hour 48** | T + 48h | Final soak completion & export | - [ ] Hour 48 checkpoint recorded (needs owner verification) |

### Export Anonymized Soak Log via Share Sheet:
1. At Hour 48, open **Diagnostics** tab.
2. Tap **Export Anonymized Soak Log (Share Sheet)**.
3. Save or share the generated `anonymized_schedule_diagnostics_<date>.json`.
4. Inspect the JSON file:
   - Contains `target_device: "Samsung Galaxy A15 5G"`, `os_version: "Android 16 / One UI 8.5"`.
   - Contains all hourly `soak_checkpoints`.
   - Contains full `heartbeat_logs` (automatic append-only records from WorkManager, process start, listener, services, boot).
   - Contains `historical_exit_reasons` extracted from Android `ApplicationExitInfo`.
   - All phone numbers masked as `[PHONE_MASKED]`.
   - All sender names pseudonymized as `[PERSON_1]`, `[PERSON_2]`.
   - Schedule keywords, verbs, and times preserved.
- [ ] Anonymized soak log exported and validated (needs owner verification)

---

## 5. 48-Hour Master Sign-Off Checklist

Before approving transition to Milestone 4 (Relay & Synchronization), verify every item:

- [ ] **No Disconnects**: `NotificationListenerState` remained `CONNECTED` with zero unrecovered drops (needs owner verification)
- [ ] **Summary Skipping**: Group summary bundles never produced phantom events (needs owner verification)
- [ ] **Sender Prefix Splitting**: Clean extraction of `"Sender: message"` without sender text polluting titles (needs owner verification)
- [ ] **Exact Alarms in Doze**: Fired on time during 8h overnight screen-off (needs owner verification)
- [ ] **Alarm Self-Test**: Test alarms fired on the exact minute and survived reboot with `firedAfterReboot = true` without touching events (needs owner verification)
- [ ] **Automatic Heartbeat**: Append-only heartbeat ticks recorded continuously across process starts, reboots, and 15m intervals (needs owner verification)
- [ ] **Boot Recovery**: Service and alarms auto-recovered after phone reboot without UI launch (needs owner verification)
- [ ] **Battery Consumption**: Total 48h battery drain attributable to Schedule Assistant < 1.5% (needs owner verification)
- [ ] **Memory Stability**: Zero native memory leaks or crash loops under camera/gaming pressure (needs owner verification)
- [ ] **Log-Only Invariant**: Real WhatsApp chat produced zero unintended alarms, while Debug screen injection remained functional (needs owner verification)
- [ ] **SQLite Immutability**: Room `events` and `heartbeat_logs` triggers strictly prevented updates and deletes (needs owner verification)

**Verification Sign-Off Date**: `________________________`  
**Verified by Owner**: `________________________`  
**Gate Status**: **M4 LOCKED until signed off by owner.**

---

## 6. Optional: Debugging & Troubleshooting via ADB (Reference Only)

*Use only if a setting fails to apply through the Samsung One UI Settings menus:*

```bash
# Verify device connection
adb devices

# Check if NotificationListener is connected
adb shell cmd notification list_listeners

# Manually rebind listener if disconnected
adb shell cmd notification allow_listener com.scheduleassistant.app/com.scheduleassistant.app.service.ScheduleNotificationListenerService

# Check battery optimization whitelist status
adb shell dumpsys deviceidle whitelist | grep com.scheduleassistant.app

# Force deep Doze mode for immediate testing
adb shell dumpsys battery unplug
adb shell dumpsys deviceidle force-idle
adb shell dumpsys deviceidle get deep   # Expected: IDLE
adb shell dumpsys battery reset

# Inspect process memory (PSS)
adb shell dumpsys meminfo com.scheduleassistant.app

# Stream real-time Schedule Assistant logs
adb logcat -s ScheduleListener BootReceiver AlarmScheduler ScheduleAssistant:AlarmWakeLock
```
