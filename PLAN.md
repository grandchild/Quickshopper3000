# QuickShopper3k Implementation Plan

## Technology Stack

### Core
- **Language**: Kotlin
- **Min SDK**: 26 (Android 8.0)
- **Compile / Target SDK**: 36
- **Build System**: Gradle (Groovy DSL), KSP for Room
- **Java**: 17

### Architecture
- **Pattern**: MVVM (Model-View-ViewModel), Views (no Compose), RecyclerView
- **DI**: Manual — `ViewModelProvider` factories, no Hilt/Dagger
- **Navigation**: Single Activity + ViewPager2, two pages (Stores = 0, Main = 1).
  ViewPager swipe input is disabled; pages are switched by a fling gesture on the
  BottomAppBar. Main is the default page.

### Key Dependencies (exact versions in `app/build.gradle`)
- **UI**: Material Components, RecyclerView, ViewBinding, ViewPager2
- **Database**: Room (runtime/ktx + compiler via KSP)
- **Coroutines**: kotlinx-coroutines-android
- **Lifecycle**: lifecycle-viewmodel-ktx + lifecycle-livedata-ktx
- **Navigation component** is declared but unused
- **Testing**: JUnit, kotlinx-coroutines-test, org.json (unit); AndroidJUnit4,
  Room testing (instrumented). Mockito and Espresso are declared but unused.

## Database Schema

### Room Entities

```kotlin
@Entity(tableName = "items", indices = [Index(value = ["uuid"], unique = true)])
data class ItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "uuid") val uuid: String,
    @ColumnInfo(name = "name", collate = ColumnInfo.NOCASE) val name: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "amount", defaultValue = "1") val amount: Int = 1
)

@Entity(tableName = "item_states", primaryKeys = ["item_id"])
data class ItemStateEntity(
    @ColumnInfo(name = "item_id") val itemId: Long,
    val state: String,                 // "DEACTIVATED" | "POSTPONED"; no row = ACTIVATED
    @ColumnInfo(name = "state_changed_at") val stateChangedAt: Long? = null
)

@Entity(tableName = "stores", indices = [Index(value = ["uuid"], unique = true)])
data class StoreEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "uuid") val uuid: String,
    val name: String,
    val specialtyStore: Boolean = false
)

@Entity(tableName = "store_item_unavailability", primaryKeys = ["store_id", "item_id"])
data class StoreItemUnavailabilityEntity(
    @ColumnInfo(name = "store_id") val storeId: Long,
    @ColumnInfo(name = "item_id") val itemId: Long
)

@Entity(tableName = "store_item_order", primaryKeys = ["store_id", "item_id"])
data class StoreItemOrderEntity(
    @ColumnInfo(name = "store_id") val storeId: Long,
    @ColumnInfo(name = "item_id") val itemId: Long,
    @ColumnInfo(name = "sort_order") val sortOrder: Int
)

@Entity(tableName = "item_add_history")
data class ItemAddHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "item_id") val itemId: Long,
    @ColumnInfo(name = "added_at") val addedAt: Long
)

// Sync (see NETWORKING.md)
@Entity(tableName = "sync_intent_queue")
data class SyncIntentQueueEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "ulid") val ulid: String,
    @ColumnInfo(name = "intent_json") val intentJson: String,
    @ColumnInfo(name = "synced", defaultValue = "0") val synced: Int = 0
)

@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey @ColumnInfo(name = "key") val key: String,
    @ColumnInfo(name = "value") val value: String
)

@Entity(tableName = "sync_tombstones")
data class SyncTombstoneEntity(
    @PrimaryKey @ColumnInfo(name = "entity_uuid") val entityUuid: String,
    @ColumnInfo(name = "deleted_at_ulid") val deletedAtUlid: String
)
```

## Application Architecture

### Package Structure
```
org.effekthasch.quickshopper3k/
├── data/
│   ├── local/ (AppDatabase, DAOs, entities incl. sync tables)
│   ├── repository/ (ShoppingRepository)
│   └── sync/ (SyncManager, SyncIntent, IntentApplier, SnapshotApplier,
│               IntentEmitter, SyncHttpClient, SyncStats)
├── domain/model/ (Item, ItemState, Store, StoreItem)
├── ui/
│   ├── MainActivity.kt
│   ├── MainPagerAdapter.kt
│   ├── FabController.kt
│   ├── SettingsActivity.kt
│   ├── DebugActivity.kt
│   ├── common/ (BaseListFragment, BaseDragAdapter, ItemAmountBadge)
│   ├── main/ (MainFragment, MainViewModel, MainItemsAdapter)
│   └── stores/ (StoresFragment, StoresViewModel, StoresItemsAdapter)
└── util/ (PreferencesManager, Ulid, BackupManager)
```

### Domain Models

```kotlin
enum class ItemState { ACTIVATED, DEACTIVATED, POSTPONED }

data class Item(
    val id: Long,
    val uuid: String,
    val name: String,
    val state: ItemState,
    val createdAt: Long,
    val stateChangedAt: Long? = null,
    val amount: Int = 1
)

data class StoreItem(
    val item: Item,
    val sortOrder: Int?,
    val isAvailable: Boolean
)

data class Store(
    val id: Long,
    val uuid: String,
    val name: String,
    val specialtyStore: Boolean = false
)
```

## UI Implementation

### Navigation
- **MainActivity** hosts ViewPager2 with Stores (page 0) and Main (page 1); page
  swipe input disabled, fling on the BottomAppBar switches pages.
- **BottomAppBar** holds: overflow menu, store selector, done-visibility toggle,
  selection counter (stores mode). Its content updates with the current page.
- **FAB** is anchored to the BottomAppBar and changes per page (add store / search).
- There is NO separate "Store Edit" destination — all store management happens in
  Stores mode (page 0).

### Screen Layouts

#### Stores Mode (ViewPager Page 0)
The ONLY page for managing stores and per-store item configuration.
- **Store selector**: text button opening a PopupMenu of stores (specialty stores
  prefixed with ★).
- **FAB**: add store → dialog with name input + "Specialty store" checkbox.
- **Rename / delete store**: via the overflow menu (delete asks for confirmation and
  clears the current selection).
- **RecyclerView**: all items for the selected store, each row has a drag handle.
- **Drag to reorder**: writes a store-specific order; multi-select (tap / long-press)
  lets you drag a contiguous block.
- **Swipe** (either direction): toggles available <-> unavailable; unavailable items
  are dimmed.
- **Empty state**: "No stores" text when no store is selected.

#### Main Mode (ViewPager Page 1)
- **BottomAppBar**: store selector (PopupMenu incl. "All items"), done-visibility
  toggle (two-state `current`/`all`, shown as a text label), overflow menu.
- **FAB**: search (magnifier-with-plus), toggles a search bar above the BottomAppBar.
  - Search filters in real time as the user types.
  - A "+" button appears at the right end when the term has no exact match.
  - With no results the keyboard action becomes a checkmark/enter; either path adds
    the term as a new activated item, then clears the search.
- **RecyclerView**: items in store order then id (no state grouping); filtered by
  done-visibility, store availability, and the search term.
- **Item swipes**: per DESIGN.md state machine, with a colored background + state icon.
- **Long press**: context menu with amount +/- (tap the value for a set-amount dialog),
  toggle availability (only when a real store is selected), and edit (rename/delete).
- **Overflow menu**: import items, activate all, deactivate all, activate postponed,
  flush recently done, share active items.
- **Lockscreen**: Main page stays visible (setShowWhenLocked / setTurnScreenOn).

### Theming
- Material Components, `DayNight` theme following the system dark/light setting.
- One base theme with brand colors; `values-night` overrides colors only.

### Handedness Setting
SharedPreferences `is_left_handed` (default false):
- Flips the FAB anchor side on the BottomAppBar (start vs end).
- Not exposed in a settings UI yet; swipe-direction reversal is not implemented.

### Swipeable Implementation
- **ItemTouchHelper**: item-level swipes (colored background + state icon) and
  handle-based drag, with a combined callback per list.
- No gesture conflicts: ViewPager page input is disabled, so only item swipes/drag
  are handled.

## Data Flow

### General Pattern
- Repository exposes Room queries as Flow.
- ViewModel converts to LiveData; the displayed list is a `MediatorLiveData` that
  recomputes filtering/sorting in memory from its sources.
- Fragment observes LiveData and updates the RecyclerView adapter.
- User actions → ViewModel → Repository → Room (+ emits a sync intent) → Flow emits.

### Main Mode
- Query joins items with item_states, store_item_order (active store + default store
  0), and store_item_unavailability; ordered by store order then id.
- In-memory: done-visibility (current/all, with the flush cutoff), availability
  (skipped for "All items"), and case/accent-insensitive search.
- Stale done items with a non-default amount have their amount reset to 1.

### Store Mode
- Loads all items for the current store.
- Drag updates local state, then writes the order (dense indices) on release.
- Availability toggle writes immediately.

## Persistence

### SharedPreferences (`quickshopper3k_prefs`)
- `current_store_id`: Long? (null = none)
- `selected_shop_store_ids`: Set<Long> (last selected store for the main list)
- `is_left_handed`: Boolean (default false)
- `done_visibility_mode`: "current" | "all" (default "current")
- `recently_done_minutes`: Int (default 60, "current" window)
- `flush_recently_done_at`: Long? (cutoff for the flush action; supersedes the
  minutes window while fresher)
- `history_days`: Int (default 183)
- `mistake_done_seconds`: Int (default 20)
- `reactivate_min_seconds`: Int (default 3600)
- `sync_server_uri`: String?, `device_id`: String, `list_id`: String

### Room Configuration
```kotlin
@Database(
    entities = [
        ItemEntity::class,
        ItemStateEntity::class,
        StoreEntity::class,
        StoreItemUnavailabilityEntity::class,
        StoreItemOrderEntity::class,
        ItemAddHistoryEntity::class,
        SyncIntentQueueEntity::class,
        SyncStateEntity::class,
        SyncTombstoneEntity::class
    ],
    version = 7,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase()
```
No migrations registered. Fresh installs start at version 7.

## Sync (see NETWORKING.md)
- Intent queue + sync_state + tombstones tables; every mutating operation emits an
  intent in addition to writing locally.
- `SyncManager` polls every 5s while the app is in the foreground: push queued
  intents, then pull by `since` ULID. On first link it pulls the snapshot/intents or
  pushes the full local state.
- HTTP bodies are gzip-compressed; a hand-rolled ULID provides ordering.
- Item-state conflicts resolve by priority (DEACTIVATED > ACTIVATED > POSTPONED);
  other fields use last-writer-wins by server ULID.

## State Management

### ViewModels
- Expose LiveData for lists and UI state; action functions run in `viewModelScope`.

### Error Handling
- DB operations wrapped in try/catch returning `Result`.
- Errors are logged and surfaced via Snackbar; no crashes on constraint violations.

## Testing Strategy

### Instrumented tests (`androidTest`, AndroidJUnit4 + in-memory Room)
- Repository round-trips (items, stores, availability, ordering)
- Filtering & availability, sorting & ordering, state transitions, swipe logic
- Stores management, search & case-insensitivity, preferences

### Unit tests (`test`)
- ULID generation/parsing, sync intent serialization, stable filtered reorder

### Notes
- No Espresso UI tests; Mockito is declared but unused.

## Build Configuration
- `app/build.gradle` (Groovy): compileSdk 36, minSdk 26, targetSdk 36, viewBinding,
  release R8 minification with the default + Room consumer rules.
- See `app/build.gradle` for the exact dependency list and versions.
- `Makefile` builds the app (`assembleRelease`), the API and CLI (`cargo build`).

## Performance Targets
- Small APK (Views + Room)
- Fast cold start
- Smooth scrolling for 1000+ items (RecyclerView)
- Fast DB queries for typical list operations

## Accessibility
- Minimum 48dp touch targets
- Content descriptions on all icons
- TalkBack navigation
- Respects system font size
- High-contrast compatible

## MVP Scope
Version 1.0 includes:
- Both modes (Stores, Main) in a ViewPager
- FAB-triggered search with integrated add
- Local database persistence + offline-first
- Intent-based sync + sharing (see NETWORKING.md), plus a Rust CLI
- Rust server (qs3k-api.rs) with compaction
- Import items, bulk state actions, item amounts, backup export/import
- Lockscreen visibility on the Main page
- DayNight theme

## Remaining (not yet)
- App icon
- Item-history visualization
- Recurring-items frequency view
- Settings UI for the numeric prefs (history_days, mistake_done_seconds,
  reactivate_min_seconds, recently_done_minutes) and for handedness
- Text/zoom scale modes (compact/normal/large) if still wanted
- Mark newly added items unavailable in existing specialty stores
- Room migrations (currently fresh-install only)