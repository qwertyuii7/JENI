# 📅 Google Calendar Integration — Full Implementation Plan
### JENI · Schedule Assistant · v2.0 Roadmap

---

## 1. Is It Possible?

**Yes — and it fits almost perfectly into the existing architecture.**

JENI already does the hard part: it extracts structured schedule data (title, ISO start time, end time, type, source) from WhatsApp messages and stores it as `SyncEventEntity` in Room DB. Every confirmed card already has everything Google Calendar needs:

| JENI Field | Google Calendar Field |
|---|---|
| `extraction.title` | `summary` |
| `extraction.start` (ISO-8601) | `start.dateTime` |
| `extraction.end` | `end.dateTime` |
| `extraction.start_date` | `start.date` (all-day) |
| `msg.chatTitle` / `msg.sender` | `description` / `source` |
| `extraction.type` (meeting/class/exam) | `colorId` + `extendedProperties` |

The integration point is **exactly one place**: `IngestionPipeline.confirmSchedule()`. When the user taps **Confirm** on a card, instead of only setting a local alarm, we also push the event to Google Calendar.

---

## 2. Architecture Overview

```
WhatsApp Notification
        │
        ▼
ScheduleNotificationListenerService
        │
        ▼
WhatsAppNotificationHandler (filter/dedupe)
        │
        ▼
IngestionPipeline
   ├── Prefilter
   ├── ScheduleParser  ──── ExtractionOutput (title, start, end, type)
   ├── AiReasoningEngine
   └── LaneClassifier ──── SCHEDULE / NEEDS_ATTENTION / IGNORE
        │
        ▼  (user taps Confirm)
   confirmSchedule()
        │
        ├── Local Alarm (AlarmManager) ← already exists
        ├── Room DB SyncEventEntity   ← already exists
        └── ── NEW ──► CalendarSyncManager.pushEvent()
                            │
                            ▼
                    Google Calendar API
                    (event_id stored back in DB)
```

---

## 3. What We Need

### 3.1 Google API Credentials
- Register app on **Google Cloud Console**
- Enable **Google Calendar API**
- Add **OAuth 2.0 Client ID** (Android type — uses SHA-1 fingerprint)
- No server needed — uses on-device OAuth flow

### 3.2 New Gradle Dependencies
```kotlin
// google-services.json already needed for OAuth
implementation("com.google.android.gms:play-services-auth:21.2.0")
implementation("com.google.api-client:google-api-client-android:2.2.0")
implementation("com.google.apis:google-api-services-calendar:v3-rev20231123-2.0.0")
implementation("com.google.oauth-client:google-oauth-client-jetty:1.34.1")
```

### 3.3 New Files to Create
```
app/src/main/java/com/scheduleassistant/app/
├── calendar/
│   ├── CalendarAuthManager.kt       ← OAuth sign-in + token management
│   ├── CalendarSyncManager.kt       ← Insert / Update / Delete events
│   ├── CalendarEventMapper.kt       ← ExtractionOutput → Google Event
│   └── CalendarSyncWorker.kt        ← WorkManager background retry
├── data/db/
│   └── CalendarSyncEntity.kt        ← Stores google_event_id per card
```

---

## 4. Phase-by-Phase Implementation

---

### Phase 1 — OAuth & Account Setup (Week 1)

**Goal:** User can sign in with their Google account from Settings.

#### Step 1.1 — Google Cloud Console Setup
1. Go to [console.cloud.google.com](https://console.cloud.google.com)
2. Create project → Enable **Google Calendar API**
3. Credentials → Create OAuth 2.0 Client ID (Android)
4. Enter package name: `com.scheduleassistant.app`
5. Enter SHA-1 from keystore: `keytool -list -v -keystore debug.keystore`
6. Download `google-services.json` → place in `android/app/`

#### Step 1.2 — `CalendarAuthManager.kt`
```kotlin
class CalendarAuthManager(private val context: Context) {

    private val signInOptions = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
        .requestEmail()
        .requestScopes(Scope(CalendarScopes.CALENDAR))
        .build()

    val googleSignInClient: GoogleSignInClient =
        GoogleSignIn.getClient(context, signInOptions)

    fun getSignedInAccount(): GoogleSignInAccount? =
        GoogleSignIn.getLastSignedInAccount(context)

    fun isSignedIn(): Boolean = getSignedInAccount() != null

    fun hasCalendarScope(): Boolean {
        val account = getSignedInAccount() ?: return false
        return GoogleSignIn.hasPermissions(account, Scope(CalendarScopes.CALENDAR))
    }

    fun buildCalendarService(account: GoogleSignInAccount): Calendar {
        val credential = GoogleAccountCredential
            .usingOAuth2(context, listOf(CalendarScopes.CALENDAR))
            .apply { selectedAccount = account.account }

        return Calendar.Builder(
            AndroidHttp.newCompatibleTransport(),
            GsonFactory.getDefaultInstance(),
            credential
        ).setApplicationName("JENI Schedule Assistant").build()
    }

    fun signOut() = googleSignInClient.signOut()
}
```

#### Step 1.3 — Add Sign-In UI to Settings Screen
- Add a `"Connect Google Calendar"` toggle card in `Screens.kt` Settings section
- On toggle ON → launch `googleSignInClient.signInIntent` via `ActivityResultLauncher`
- On success → store account in prefs, show connected email
- On toggle OFF → `signOut()` + clear stored google_event_ids

---

### Phase 2 — Event Mapping Layer (Week 1-2)

**Goal:** Convert JENI's `ExtractionOutput` into a valid Google Calendar `Event` object.

#### `CalendarEventMapper.kt`
```kotlin
object CalendarEventMapper {

    // Maps JENI event types to Google Calendar color IDs
    private val typeColorMap = mapOf(
        "class"    to "9",  // Blueberry
        "exam"     to "11", // Tomato
        "meeting"  to "7",  // Peacock
        "deadline" to "6",  // Tangerine
        "lab"      to "2",  // Sage
        "seminar"  to "3",  // Grape
    )

    fun map(
        extraction: ExtractionOutput,
        rawText: String,
        sender: String?,
        chatTitle: String?
    ): Event {
        val event = Event().apply {
            summary = extraction.title
            description = buildDescription(rawText, sender, chatTitle)
            colorId = typeColorMap[extraction.type?.lowercase()] ?: "1"
            source = Event.Source().apply {
                title = "JENI · via WhatsApp"
                url = "https://github.com/qwertyuii7/JENI"
            }
            extendedProperties = Event.ExtendedProperties().apply {
                private_ = mapOf(
                    "jeni_type"       to (extraction.type ?: "event"),
                    "jeni_source"     to "whatsapp",
                    "jeni_chat"       to (chatTitle ?: ""),
                    "jeni_sender"     to (sender ?: ""),
                    "jeni_confidence" to (extraction.confidence?.toString() ?: "")
                )
            }
            // Reminder: 15 min popup (mirrors local alarm)
            reminders = Event.Reminders().apply {
                useDefault = false
                overrides = listOf(
                    EventReminder().apply { method = "popup"; minutes = 15 },
                    EventReminder().apply { method = "email";  minutes = 60 }
                )
            }
        }

        // Time: concrete start vs all-day
        if (!extraction.time_unspecified && extraction.start != null) {
            event.start = EventDateTime()
                .setDateTime(DateTime(extraction.start))
                .setTimeZone("Asia/Kolkata")
            event.end = if (extraction.end != null) {
                EventDateTime().setDateTime(DateTime(extraction.end))
                    .setTimeZone("Asia/Kolkata")
            } else {
                // Default: 1 hour duration
                EventDateTime().setDateTime(
                    DateTime(parseIso(extraction.start) + 3_600_000)
                ).setTimeZone("Asia/Kolkata")
            }
        } else {
            // All-day event from start_date
            val dateStr = extraction.start_date ?: todayIsoDate()
            event.start = EventDateTime().setDate(DateTime(dateStr))
            event.end   = EventDateTime().setDate(DateTime(dateStr))
        }

        return event
    }

    private fun buildDescription(raw: String, sender: String?, chat: String?): String {
        return buildString {
            appendLine("📨 Detected from WhatsApp")
            if (!chat.isNullOrBlank())   appendLine("👥 Chat: $chat")
            if (!sender.isNullOrBlank()) appendLine("👤 From: $sender")
            appendLine()
            appendLine("Original message:")
            appendLine("\"${raw.take(300)}\"")
            appendLine()
            appendLine("— Added by JENI Schedule Assistant")
        }
    }
}
```

---

### Phase 3 — `CalendarSyncManager` (Week 2)

**Goal:** Insert, update, and delete events in the user's Google Calendar.

```kotlin
class CalendarSyncManager(
    private val context: Context,
    private val authManager: CalendarAuthManager
) {
    private val calendarId = "primary" // User's default calendar

    /** Insert a new event. Returns the google event ID. */
    suspend fun insertEvent(
        extraction: ExtractionOutput,
        rawText: String,
        sender: String?,
        chatTitle: String?,
        jeniEntityId: String
    ): String? = withContext(Dispatchers.IO) {
        val account = authManager.getSignedInAccount() ?: return@withContext null
        val service = authManager.buildCalendarService(account)
        val googleEvent = CalendarEventMapper.map(extraction, rawText, sender, chatTitle)
        try {
            val created = service.events().insert(calendarId, googleEvent).execute()
            // Store the google_event_id in Room DB
            AppDatabase.getInstance(context).calendarSyncDao().upsert(
                CalendarSyncEntity(
                    jeniEntityId = jeniEntityId,
                    googleEventId = created.id,
                    calendarId = calendarId,
                    syncedAt = isoNow(),
                    status = "synced"
                )
            )
            created.id
        } catch (e: Exception) {
            Log.e("CalendarSync", "Insert failed: ${e.message}", e)
            // Queue for retry via WorkManager
            CalendarSyncWorker.enqueue(context, jeniEntityId, "insert")
            null
        }
    }

    /** Update an existing event (e.g. user edits time on JENI card) */
    suspend fun updateEvent(
        jeniEntityId: String,
        extraction: ExtractionOutput,
        rawText: String,
        sender: String?,
        chatTitle: String?
    ): Boolean = withContext(Dispatchers.IO) {
        val syncRecord = AppDatabase.getInstance(context)
            .calendarSyncDao().getByJeniId(jeniEntityId) ?: return@withContext false
        val account = authManager.getSignedInAccount() ?: return@withContext false
        val service = authManager.buildCalendarService(account)
        val updatedEvent = CalendarEventMapper.map(extraction, rawText, sender, chatTitle)
        return@withContext try {
            service.events().update(calendarId, syncRecord.googleEventId, updatedEvent).execute()
            AppDatabase.getInstance(context).calendarSyncDao().upsert(
                syncRecord.copy(syncedAt = isoNow(), status = "synced")
            )
            true
        } catch (e: Exception) {
            Log.e("CalendarSync", "Update failed: ${e.message}", e)
            CalendarSyncWorker.enqueue(context, jeniEntityId, "update")
            false
        }
    }

    /** Delete when user dismisses or cancels a JENI card */
    suspend fun deleteEvent(jeniEntityId: String): Boolean = withContext(Dispatchers.IO) {
        val syncRecord = AppDatabase.getInstance(context)
            .calendarSyncDao().getByJeniId(jeniEntityId) ?: return@withContext false
        val account = authManager.getSignedInAccount() ?: return@withContext false
        val service = authManager.buildCalendarService(account)
        return@withContext try {
            service.events().delete(calendarId, syncRecord.googleEventId).execute()
            AppDatabase.getInstance(context).calendarSyncDao().deleteByJeniId(jeniEntityId)
            true
        } catch (e: Exception) {
            Log.e("CalendarSync", "Delete failed: ${e.message}", e)
            false
        }
    }
}
```

---

### Phase 4 — Hook into `IngestionPipeline.confirmSchedule()` (Week 2)

**Goal:** Every user confirmation automatically pushes to Google Calendar.

This is the critical integration point. The change is minimal and surgical:

```kotlin
// In IngestionPipeline.confirmSchedule() — after the local alarm is set:

// ── NEW: Push to Google Calendar ─────────────────────────────────────
val calendarAuthManager = CalendarAuthManager(context)
if (calendarAuthManager.isSignedIn() && calendarAuthManager.hasCalendarScope()) {
    val calendarSync = CalendarSyncManager(context, calendarAuthManager)
    scope.launch(Dispatchers.IO) {
        calendarSync.insertEvent(
            extraction   = concreteExtraction,
            rawText      = sched.source_quote ?: "",
            sender       = sched.sender,
            chatTitle    = sched.chat_title,
            jeniEntityId = entityId
        )
    }
}
```

And in `dismissSchedule()`:
```kotlin
// ── NEW: Remove from Google Calendar on dismiss ───────────────────────
if (calendarAuthManager.isSignedIn()) {
    scope.launch(Dispatchers.IO) {
        CalendarSyncManager(context, calendarAuthManager).deleteEvent(entityId)
    }
}
```

---

### Phase 5 — Room DB: `CalendarSyncEntity` (Week 2)

```kotlin
@Entity(tableName = "calendar_sync")
data class CalendarSyncEntity(
    @PrimaryKey val jeniEntityId: String,  // FK to SyncEventEntity
    val googleEventId: String,
    val calendarId: String,
    val syncedAt: String,                  // ISO-8601
    val status: String                     // "synced" | "pending_retry" | "failed"
)

@Dao
interface CalendarSyncDao {
    @Upsert
    suspend fun upsert(entity: CalendarSyncEntity)

    @Query("SELECT * FROM calendar_sync WHERE jeniEntityId = :id")
    suspend fun getByJeniId(id: String): CalendarSyncEntity?

    @Query("DELETE FROM calendar_sync WHERE jeniEntityId = :id")
    suspend fun deleteByJeniId(id: String)

    @Query("SELECT * FROM calendar_sync WHERE status = 'pending_retry'")
    suspend fun getPendingRetries(): List<CalendarSyncEntity>
}
```

---

### Phase 6 — Offline Retry with WorkManager (Week 3)

**Goal:** If the device is offline when a card is confirmed, sync when connection returns.

```kotlin
class CalendarSyncWorker(ctx: Context, params: WorkerParameters) 
    : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val jeniId  = inputData.getString("jeni_entity_id") ?: return Result.failure()
        val action  = inputData.getString("action") ?: "insert"

        val db = AppDatabase.getInstance(applicationContext)
        val authManager = CalendarAuthManager(applicationContext)

        if (!authManager.isSignedIn()) return Result.failure()

        val syncManager = CalendarSyncManager(applicationContext, authManager)
        val event = db.eventDao().getEventById(jeniId) ?: return Result.failure()

        return try {
            when (action) {
                "insert" -> { /* rebuild extraction from payload and insert */ }
                "update" -> { /* rebuild and update */ }
                "delete" -> syncManager.deleteEvent(jeniId)
            }
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    companion object {
        fun enqueue(context: Context, jeniId: String, action: String) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = OneTimeWorkRequestBuilder<CalendarSyncWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .setInputData(workDataOf(
                    "jeni_entity_id" to jeniId,
                    "action"         to action
                ))
                .build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }
}
```

---

### Phase 7 — UI Enhancements (Week 3)

#### 7.1 Settings Screen — "Google Calendar" Section
```
┌─────────────────────────────────────────────────────┐
│  📅  Google Calendar                                 │
│                                                     │
│  ◉ Connected as: user@gmail.com          [Sign Out] │
│                                                     │
│  Auto-add confirmed events    [ ● ON  ]             │
│  Auto-delete on dismiss       [   OFF ]             │
│  Default calendar             [Primary ▾]           │
│  Add email reminder (60 min)  [ ● ON  ]             │
└─────────────────────────────────────────────────────┘
```

#### 7.2 Schedule Card — Calendar Sync Badge
Each confirmed card shows a small badge:
```
┌─────────────────────────────────────────┐
│  📅  Team Standup                        │
│  Thu, 31 Oct · 10:00 AM                 │
│  From: Work Group                       │
│                                         │
│  [✓ Confirmed]  [🗑 Dismiss]            │
│  📅 Added to Google Calendar  ✓         │  ← NEW
└─────────────────────────────────────────┘
```

#### 7.3 Needs Attention Card — "Add to Calendar" Button
For ambiguous cards with no fixed time, add a manual push button:
```
┌─────────────────────────────────────────┐
│  ⚠️  Submit Assignment                   │
│  Date: Nov 3 (time not confirmed)       │
│                                         │
│  [Set Time & Add to Calendar]           │  ← NEW
│  [Dismiss]                              │
└─────────────────────────────────────────┘
```

---

### Phase 8 — (Optional Advanced) Read-Back & Smart Conflict Detection

Once we can write to Calendar, we can also **read** it to detect conflicts:

```kotlin
suspend fun checkConflicts(
    proposedStart: Long,
    proposedEnd: Long
): List<Event> {
    val service = authManager.buildCalendarService(account)
    return service.events().list("primary")
        .setTimeMin(DateTime(proposedStart - 900_000)) // 15 min buffer
        .setTimeMax(DateTime(proposedEnd + 900_000))
        .setSingleEvents(true)
        .execute()
        .items ?: emptyList()
}
```

Show conflict warning on the card:
```
⚠️ You have "Lunch with Team" at the same time. Confirm anyway?
```

---

## 5. Full Implementation Timeline

```
Week 1 ──────────────────────────────────────────────────────────────────
  Mon  │ Google Cloud Console setup, SHA-1, google-services.json
  Tue  │ CalendarAuthManager.kt + Gradle deps
  Wed  │ Settings UI: Sign-In flow, account display
  Thu  │ CalendarEventMapper.kt + unit tests
  Fri  │ CalendarSyncEntity + DAO + Room migration

Week 2 ──────────────────────────────────────────────────────────────────
  Mon  │ CalendarSyncManager: insertEvent()
  Tue  │ CalendarSyncManager: updateEvent() + deleteEvent()
  Wed  │ Hook into IngestionPipeline.confirmSchedule() + dismissSchedule()
  Thu  │ Card UI: sync badge + error state
  Fri  │ Integration test: confirm card → verify in Google Calendar

Week 3 ──────────────────────────────────────────────────────────────────
  Mon  │ CalendarSyncWorker (WorkManager offline retry)
  Tue  │ Needs Attention: "Set Time & Add to Calendar" flow
  Wed  │ Settings: per-option toggles (auto-add, auto-delete, email reminder)
  Thu  │ Conflict detection (read-back)
  Fri  │ End-to-end device test + APK release
```

---

## 6. Permissions Required

Add to `AndroidManifest.xml`:
```xml
<!-- Already have these: -->
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />

<!-- New for Google Sign-In (handled by Play Services, no extra manifest entry needed) -->
```

Add to `build.gradle (app)`:
```groovy
apply plugin: 'com.google.gms.google-services' // at the bottom
```

Add to `build.gradle (project)`:
```groovy
classpath 'com.google.gms:google-services:4.4.1'
```

---

## 7. Risk & Mitigations

| Risk | Likelihood | Mitigation |
|---|---|---|
| User revokes Google permission mid-session | Medium | Check `hasCalendarScope()` before every call; graceful re-auth prompt |
| Offline at confirm time | High | WorkManager retry queue with exponential backoff |
| Duplicate events on retry | Medium | Check `calendarSyncDao.getByJeniId()` before inserting; idempotency key |
| Token expiry (1-hour OAuth tokens) | High | `GoogleAccountCredential` auto-refreshes; catch `401` and re-auth |
| User on Samsung without Play Services | Low | Check `GoogleApiAvailability.isGooglePlayServicesAvailable()` before showing option |
| Rate limits (Calendar API: 1M req/day, 10 req/sec/user) | Very Low | Single user — nowhere near limits |

---

## 8. Summary

> **Yes — Google Calendar integration is 100% possible and is a natural extension of JENI's existing architecture.** JENI already produces structured, ISO-timestamped, typed schedule events. Pushing them to Google Calendar is a matter of:
> 1. One OAuth sign-in flow
> 2. One mapper (`ExtractionOutput` → `Calendar.Event`)
> 3. One hook in `confirmSchedule()`
> 4. One Room table for the `google_event_id`
>
> The rest (retry, conflict detection, UI badges) are polish on top of a solid foundation.

**Estimated effort:** ~3 weeks of focused development  
**Risk level:** Low — no backend changes needed, pure on-device OAuth  
**Value delivered:** JENI cards become first-class Google Calendar events — visible across all devices, shareable, and integrated with Google Meet links

---
*Generated: 2026-10-30 · JENI v1.1 · Plan version 1.0*
