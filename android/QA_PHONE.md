# QA_PHONE.md: Physical Device Testing & Verification Protocol

> **CRITICAL RULE**: **No device claims without owner verification.**  
> Automated test suites verify parser parity and unit contracts, but physical OS-level features (Notification Listener, Doze bypass via `setAlarmClock`, and `BOOT_COMPLETED` alarm recovery) require real-device execution and manual sign-off.

---

## 1. Prerequisites
- **Target Device**: Android 8.0+ (API level 26 minimum, API 34 target).
- **Tooling**: Android SDK platform-tools (`adb`).
- **Permissions Required**:
  - `NotificationListenerService` (to read incoming WhatsApp notifications)
  - `SCHEDULE_EXACT_ALARM` / `USE_EXACT_ALARM` (for unthrottled alarms)
  - Battery Optimization Exemption (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`)

---

## 2. Build & Install APK

From the project root:
```bash
cd android
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## 3. One-Time Permission Setup via ADB

Grant permissions quickly without navigating complex OEM settings menus:

### A. Grant Notification Listener Access
```bash
adb shell cmd notification allow_listener com.scheduleassistant.app/com.scheduleassistant.app.service.ScheduleNotificationListenerService
```

### B. Exempt from Battery Optimization (Prevents OS from killing background listener)
```bash
adb shell dumpsys deviceidle whitelist +com.scheduleassistant.app
```

### C. Grant Exact Alarm Permission (Android 12+)
```bash
adb shell appops set com.scheduleassistant.app SCHEDULE_EXACT_ALARM allow
```

---

## 4. Test Scenarios & Verification Steps

### Test 1: In-App Debug Screen Notification Injection (Zero Risk / Offline)
1. Launch **Schedule Assistant**.
2. Tap the **Debug** tab on the bottom navigation bar.
3. In the input box, type or tap the quick chip:
   `kal 4 baje DBMS class hai`
4. Tap **Inject as Fake Notification**.
5. **Expected Output**:
   - Extraction card displays:
     - Type: `new`
     - Title: `DBMS class`
     - Start: Tomorrow at 16:00:00+05:30
     - Ambiguous: `false`, Confidence: `90%`
   - Check **Pending** tab badge: increments by 1.
   - Tap **Confirm**: event moves to **Home** tab and exact alarm is queued in **Alerts** tab.

---

### Test 2: Real WhatsApp Notification Ingestion
1. Ensure WhatsApp is installed and logged in on the device.
2. From another phone or web client, send the test message:
   `"meeting Friday 11 pe shift ho gayi"`
3. Keep the target phone screen off or on the home screen.
4. **Expected Output**:
   - A system heads-up notification appears:  
     `"New Schedule Found: meeting"`  
     `"Tap to review & confirm: 'meeting Friday 11 pe shift ho gayi'"`
5. Tap the notification:
   - Schedule Assistant opens immediately to the **Pending** tab with the item ready for one-tap confirmation.

---

### Test 3: Doze Mode & Exact Alarm Clock Firing
1. In the app, confirm a schedule set to trigger in 5 minutes (or edit time in Debug screen).
2. Lock the device and force it into deep Doze mode via ADB:
   ```bash
   # Unplug USB battery sensing
   adb shell dumpsys battery unplug
   # Step device into Doze IDLE
   adb shell dumpsys deviceidle force-idle
   # Verify device is in deep idle
   adb shell dumpsys deviceidle get deep
   ```
   *(Expected response: `IDLE`)*
3. Wait for the trigger time.
4. **Expected Output**:
   - The device screen turns on / vibrates with high-priority alarm notification:
     `⏰ ALARM: <Title>`
   - `AlarmManager.setAlarmClock()` fired exactly on time despite deep Doze mode.
5. Reconnect battery sensing:
   ```bash
   adb shell dumpsys battery reset
   ```

---

### Test 4: Device Reboot & BOOT_COMPLETED Alarm Recovery
1. Confirm at least one future schedule in the app.
2. Verify the alarm is scheduled under the **Alerts** tab.
3. Reboot the phone via ADB or power menu:
   ```bash
   adb reboot
   ```
4. Once the device boots up and unlocks, monitor logcat:
   ```bash
   adb logcat -s BootReceiver ScheduleAssistant:AlarmWakeLock
   ```
5. **Expected Output**:
   - `BootReceiver` receives `ACTION_BOOT_COMPLETED`.
   - Queries Room `events` table, projects active confirmed schedules via `ProjectionEngine`, and re-registers all alarms with `AlarmManager`.
   - Open app -> **Alerts** tab still shows scheduled alarms without duplicates.

---

### Test 5: Ambiguous AM/PM Dual Reading
1. In **Debug** tab, inject:
   `"kal 8 baje test hai"`
2. Open **Pending** tab.
3. **Expected Output**:
   - Item shows warning badge with `65% Conf`.
   - Two buttons are provided:
     - **Confirm Reading A (8:00 AM)**
     - **Confirm Reading B (8:00 PM)**
   - Tapping either updates the schedule to the exact 24-hour ISO timestamp and removes ambiguity.

---

### Test 6: SQLite Append-Only Security Check
1. Inspect the Room database using ADB shell:
   ```bash
   adb shell "run-as com.scheduleassistant.app sqlite3 /data/data/com.scheduleassistant.app/databases/schedule_assistant.db 'UPDATE events SET title=\"hacked\" WHERE seq=1;'"
   ```
2. **Expected Output**:
   - SQLite raises error: `Error: Events table is append-only: updates are strictly prohibited`.
   - Attempting `DELETE FROM events;` similarly fails with `Events table is append-only: deletes are strictly prohibited`.

---

## 5. Verification Sign-Off Checklist
- [ ] Debug injection parses correctly without crashes.
- [ ] Real WhatsApp notification captured by `NotificationListenerService`.
- [ ] One-tap confirm/dismiss writes atomic events into Room.
- [ ] Alarm fires while device is in deep Doze mode.
- [ ] Alarms survive phone reboot via `BOOT_COMPLETED`.
- [ ] Ambiguous AM/PM dual options selectable.
- [ ] No duplicate alerts on multiple launches or catch-ups.

**Status**: Ready for physical phone verification by owner.
