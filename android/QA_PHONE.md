# QA_PHONE.md: Physical Device Verification Protocol & 48-Hour Soak Checklist

> **CRITICAL RULE**: **No device claims without owner verification.**  
> Automated test suites verify parser parity and unit contracts, but physical OS-level features (Notification Listener, Doze bypass via `setAlarmClock`, and `BOOT_COMPLETED` alarm recovery) require real-device execution and manual sign-off.
>
> **M4 Gate**: Do not proceed to M4 until the owner completes the 48-hour soak test and confirms the checklist below.

---

## 1. Per-OEM Configuration: Samsung (One UI 5 / One UI 6 / Android 13-14)

Samsung's aggressive background battery management ("Device Care") aggressively puts inactive background services and notification listeners into sleep mode. Apply the following settings before initiating the soak test:

### Step 1: Add to "Never Sleeping Apps"
1. Open **Settings** -> **Battery** (or **Device Care** -> **Battery**).
2. Tap **Background usage limits**.
3. Tap **Never sleeping apps**.
4. Tap the **+** (Add) icon in the top right corner.
5. Search for and select **Schedule Assistant** -> tap **Add**.
   > *Why: Prevents Samsung One UI from moving the app into "Sleeping" or "Deep sleeping" status after 24-72 hours of low foreground use.*

### Step 2: Set Battery Usage to "Unrestricted"
1. Long-press the **Schedule Assistant** app icon on the home screen/app drawer -> tap the **(i)** (App info) icon.
2. Scroll to **Battery**.
3. Select **Unrestricted** (default is *Optimized*).
   > *Why: Grants exemption from Android Doze background network and CPU throttling.*

### Step 3: Lock App in Recent Tasks ("Keep Open")
1. Open **Schedule Assistant**, then swipe up to open the **Recent Apps** (task switcher) screen.
2. Tap the circular **Schedule Assistant icon** above its preview card.
3. Select **Keep open** (a small padlock icon will appear on the card).
   > *Why: Prevents One UI from terminating the foreground service when the user taps "Close all".*

### Step 4: Verify Notification Listener Access
1. Open **Settings** -> **Apps** -> tap the three dots **(⋮)** in top-right -> **Special access**.
2. Tap **Notification access** (or **Notification read, reply & control**).
3. Ensure **Schedule Assistant Notification Reader** toggle is **ON** (Allowed).

### Quick ADB Shortcuts (One-Command Setup):
```bash
# 1. Grant Notification Listener Access
adb shell cmd notification allow_listener com.scheduleassistant.app/com.scheduleassistant.app.service.ScheduleNotificationListenerService

# 2. Add to Battery Optimization Whitelist
adb shell dumpsys deviceidle whitelist +com.scheduleassistant.app

# 3. Grant Exact Alarm Permission (Android 12+)
adb shell appops set com.scheduleassistant.app SCHEDULE_EXACT_ALARM allow

# 4. Grant POST_NOTIFICATIONS Permission (Android 13+)
adb shell pm grant com.scheduleassistant.app android.permission.POST_NOTIFICATIONS
```

---

## 2. 48-Hour Soak Testing Protocol

The 48-hour soak test ensures stability, zero-leak memory usage, unthrottled notification listener uptime, and Doze-safe alarm delivery under real-world usage.

### Preparation (Hour 0)
1. Install fresh build:
   ```bash
   cd android
   ./gradlew assembleDebug
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```
2. Open **Diagnostics** tab (bottom bar):
   - Confirm **Listener State**: `CONNECTED` (Green)
   - Confirm **Foreground Service**: `ACTIVE` (Green)
   - Confirm **Battery Whitelist Status**: `WHITELISTED` (Green)
   - Confirm **Notification Permission**: `GRANTED` (Green)
3. Choose mode:
   - For passive validation without notifications/alarms: Enable **Log-Only Mode** toggle.
   - For full live assistant mode: Keep **Log-Only Mode** toggle OFF.
4. Record starting battery percentage and time.

---

### Soak Checkpoints

| Time Elapsed | Target Inspection | Verification Steps | Pass Criteria |
| :--- | :--- | :--- | :--- |
| **Hour 6** | Background Uptime | Lock device for 2 hours, then check Diagnostics screen. | `Listener State` remains `CONNECTED`; zero disconnect events; no OS kill. |
| **Hour 12** | WhatsApp Group Messages | Receive normal WhatsApp group chatter and 2 schedule messages ("Sender: text" format). | Group sender prefix stripped; clean title & time in Pending queue; summary notifications ignored. |
| **Hour 24** | Deep Doze Wake Alarm | Schedule an exact alarm for 7:00 AM; leave device unplugged overnight. | Alarm fires on the dot via `AlarmManager.setAlarmClock()`; wakes screen/vibrates in Doze. |
| **Hour 30** | Device Reboot Recovery | Confirm at least 1 future schedule, then reboot phone (`adb reboot`). | `last boot-recovery` timestamp updates; all alarms restored by `BootReceiver` without duplicates. |
| **Hour 36** | Memory & Process Stability | Check process memory via ADB: `adb shell dumpsys meminfo com.scheduleassistant.app` | PSS memory steady (< 65MB); no native or SQLite leak; zero GC spikes. |
| **Hour 48** | Anonymized Log Export | Navigate to Diagnostics tab -> tap **Export Anonymized Log (Share Sheet)**. | Exported JSON generated; phone numbers masked as `[PHONE_MASKED]`; names masked as `[PERSON_N]`; schedule text structure preserved. |

---

## 3. 48-Hour Soak Sign-Off Checklist

Before proceeding to Milestone 4 (Relay & Synchronization), verify and sign off on all items:

- [ ] **Listener Continuity**: Notification listener did not drop or disconnect over 48 hours.
- [ ] **requestRebind Recovery**: If listener was temporarily disrupted, `requestRebind` re-established connection automatically without manual reboot.
- [ ] **Summary Notification Filtering**: Zero group summary / bundled notifications ingested as false schedules.
- [ ] **Group Sender Prefix Handling**: Messages in `"Sender: kal 4 baje meeting"` correctly parsed `"kal 4 baje meeting"` without contaminating title or failing rules.
- [ ] **Deduplication**: Repeated WhatsApp message notifications with identical `(chat, text, post time)` ingested exactly once.
- [ ] **Doze Mode Alarm Clock**: Alarms fired on time when the device was in deep idle Doze mode.
- [ ] **Reboot Recovery**: All active alarms recovered from Room database after phone restart.
- [ ] **Battery Impact**: Battery consumption attributable to Schedule Assistant is < 1.5% over 48 hours.
- [ ] **Anonymized Log Export**: Export via share sheet generated valid JSON with phone numbers and personal names masked.
- [ ] **Append-Only Room Database**: Events table immutability verified; zero updates or deletes permitted.

**Status**: Ready for physical device verification and 48-hour soak run by owner.  
**M4 Gate**: Do not start M4 until soak results are reported by the owner.
