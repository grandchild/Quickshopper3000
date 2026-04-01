# QuickShopper3k Implementation Plan

## Technology Stack

### Core
- **Language**: Kotlin
- **Min SDK**: 26 (Android 8.0)
- **Target SDK**: 34 (Android 14)
- **Build System**: Gradle with Kotlin DSL

### Architecture
- **Pattern**: MVVM (Model-View-ViewModel)
- **DI**: Manual dependency injection (avoid Hilt/Dagger overhead)
- **Navigation**: Single Activity with ViewPager2 (Stores/Main) - two pages only

### Key Dependencies
- **UI**: Material Design Components, RecyclerView, ViewBinding, ViewPager2
- **Database**: Room (persistence library)
- **Coroutines**: kotlinx-coroutines-android
- **Lifecycle**: androidx.lifecycle.viewmodel-ktx
- **Navigation**: androidx.navigation.fragment-ktx
- **Testing**: JUnit, Mockito, Room testing, Espresso

## Database Schema

### Room Entities

```kotlin
@Entity(tableName = "items")
data class ItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "name", collate = ColumnInfo.NOCASE) val name: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "amount", defaultValue = "1") val amount: Int = 1
)

@Entity(tableName = "item_states", primaryKeys = ["item_id"])
data class ItemStateEntity(
    @ColumnInfo(name = "item_id") val itemId: Long,
    val state: String,
    @ColumnInfo(name = "state_changed_at") val stateChangedAt: Long? = null
)

@Entity(tableName = "stores")
data class StoreEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
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
```

## Application Architecture

### Package Structure
```
com.quickshopper3k/
├── data/
│   ├── local/ (Room database, DAOs, entities)
│   └── repository/
├── domain/
│   └── model/ (Item, ItemState, Store)
├── ui/
│   ├── MainActivity.kt
│   ├── stores/ (Fragment, ViewModel, Adapter - stores management page)
│   └── main/ (Fragment, ViewModel, Adapter - unified list page)
└── util/
```

### Domain Models

```kotlin
enum class ItemState {
    ACTIVATED, DEACTIVATED, POSTPONED
}

data class Item(
    val id: Long,
    val name: String,
    val state: ItemState,
    val createdAt: Long
)

data class StoreItem(
    val item: Item,
    val sortOrder: Int?,
    val isAvailable: Boolean
)

data class Store(
    val id: Long,
    val name: String,
    val specialtyStore: Boolean = false
)
```

## UI Implementation

### Navigation
- **MainActivity** hosts ViewPager2 with Stores and Main fragments (2 pages, swipeable)
- **BottomAppBar** fixed at bottom, content updates based on current page
- Swipe left/right on content area switches between Stores/Main modes
- BottomAppBar observes ViewPager position to update menus/title
- **Note**: There is NO separate "Store Edit" destination - all store management happens in Stores mode (page 0)

### Screen Layouts

#### Stores Mode (ViewPager Page 0)
This is the ONLY page for managing stores and per-store item configuration.
- **BottomAppBar**: Store selector (dropdown icon), FAB to add store, delete store option
- **RecyclerView**: All items for selected store with drag handles
- **FAB**: Add store (anchored to BottomAppBar)
- **Empty state**: "No stores" text when no store selected, no items shown
- **Item row**: Drag handle, item name (grayed-out if unavailable)
- **Drag to reorder**: Changes store-specific item order
- **Swipe right**: Mark item as available (normal appearance)
- **Swipe left**: Mark item as unavailable (grayed-out with strikethrough)
- **Add store dialog**: Text input for name + checkbox for "Specialty store"
- **Store selection**: Via menu icon, shows AlertDialog with single-choice list
- **Delete store**: Via overflow menu, clears current selection
- **Horizontal swipe**: Switch to Main mode

#### Main Mode (ViewPager Page 1)
- **BottomAppBar**: Store selector (dropdown), done-items visibility button (tristate), settings menu (right side)
- **Done visibility button**: Cycles through three states on tap:
  - NONE: Hide all done items (default)
  - RECENT: Show items marked done within last X minutes (X=20 default)
  - ALL: Show all done items
  - Icon changes per state for visual clarity
- **FAB**: Search icon (magnifier), anchored to BottomAppBar
  - Tapping FAB expands search bar above BottomAppBar (same as old Shop behavior)
  - Search bar filters list in real-time as user types
  - Small "+" icon appears on right end of search bar when search term doesn't exactly match an existing item name
  - When search yields no results, keyboard action button becomes checkmark/enter to add item directly
  - Tapping "+" or pressing keyboard action adds search term as new activated item, then clears search
- **RecyclerView**: Filtered & sorted by current store; sorted by state (activated, postponed, deactivated), then by store order, then by ID
- **Item swipe gestures**: per DESIGN.md state transitions
- **Long press**: Edit item dialog
- **Horizontal swipe**: Switch left to Stores mode
- **Lockscreen**: Main page remains visible on lockscreen (setShowWhenLocked/setTurnScreenOn flags)

### Theming
- Material Design Components
- Light/Dark theme via system setting
- Three text scale modes in preferences: compact (0.9x), normal (1.0x), large (1.2x)

### Handedness Setting
SharedPreferences boolean `is_left_handed`:
- Controls FAB alignment on BottomAppBar (`fabAlignmentMode`: "end" vs "start")
- Controls menu overflow position on BottomAppBar
- Reverses item swipe directions
- Controls search bar add-button position (left vs right end icon)

### Swipeable Implementation
- **ViewPager2**: Horizontal page swipes between Stores/Main modes
- **ItemTouchHelper**: Item-level swipes with visual feedback (background color/icon)
- No gesture conflicts: ViewPager handles horizontal content swipes, ItemTouchHelper handles individual item swipes

## Data Flow

### General Pattern
- Repository exposes Room queries as Flow
- ViewModel converts to LiveData via `asLiveData()`
- Fragment observes LiveData, updates RecyclerView adapter
- User actions → ViewModel → Repository → Room → Flow emits

### Main Mode
- Query items with JOIN on item_states, LEFT JOIN on store_item_order and store_item_unavailability
- Filter by current store availability (from preferences)
- Sort by state first, then: NULL order items (by ID), then by sort_order, then by ID
- Search filters in-memory on query result

### Store Edit Mode
- Load all items for current store
- Drag updates local state, batch write to DB on exit
- Availability toggle writes immediately

## Persistence

### SharedPreferences
Store in default shared preferences:
- `current_store_id`: Long? (null = no store selected)
- `is_left_handed`: Boolean (default false)
- `text_scale`: String ("compact"|"normal"|"large", default "normal")
- `show_done_items_mode`: String ("none"|"recent"|"all", default "none")
- `recently_done_minutes`: Int (default 60, threshold for "recent" mode)
- `flush_recently_done_at`: Long? (timestamp for "flush recently done" action; supersedes minutes threshold while fresher)
- `history_days`: Int (default 183)
- `mistake_done_seconds`: Int (default 20)
- `reactivate_min_seconds`: Int (default 3600)

### Room Configuration
```kotlin
@Database(
    entities = [
        ItemEntity::class,
        ItemStateEntity::class,
        StoreEntity::class,
        StoreItemUnavailabilityEntity::class,
        StoreItemOrderEntity::class,
        ItemAddHistoryEntity::class
    ],
    version = 5
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun itemDao(): ItemDao
    abstract fun storeDao(): StoreDao
}
```
No migrations registered. Fresh installs start at version 5 with the current schema.

## State Management

### ViewModels
- Expose LiveData for data lists (from Flow.asLiveData())
- Expose LiveData for UI state
- User action functions use viewModelScope internally

### Error Handling
- DB operations in try/catch
- Errors logged and shown to user via Snackbar
- No crashes on constraint violations

## Testing Strategy

### Unit Tests
- Repository queries
- ViewModel state transformations
- Sorting logic (NULL order handling, duplicate sort_order)

### Integration Tests
- Room migrations (future)
- State transitions per DESIGN.md
- Store filtering and ordering

### UI Tests
- Swipe gestures
- FAB actions
- Navigation
- Search

## Build Configuration

### build.gradle.kts (app)
```kotlin
android {
    compileSdk = 34
    defaultConfig {
        minSdk = 26
        targetSdk = 34
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.10.0")
    implementation("androidx.navigation:navigation-fragment-ktx:2.9.6")
    implementation("androidx.navigation:navigation-ui-ktx:2.9.6")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")
    
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:5.21.0")
    testImplementation("androidx.arch.core:core-testing:2.2.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.room:room-testing:2.8.4")
}
```

### Proguard
Keep Room entities and DAOs, shrink everything else aggressively.

## Performance Targets
- APK size: < 3MB (Views + Room significantly smaller than Compose)
- Cold start: < 800ms on mid-range device
- List scroll: 60fps for 1000+ items (RecyclerView with DiffUtil)
- DB operations: < 50ms for typical queries

## Accessibility
- All interactive elements: minimum 48dp touch target
- Content descriptions on all icons
- Support TalkBack navigation
- Text scale respects system font size + app multiplier
- High contrast mode compatible

## MVP Scope
Version 1.0 includes:
- Both modes functional (Stores, Main - in ViewPager)
- FAB-triggered search bar with integrated add functionality
- Local database persistence
- Handedness setting
- Theme support
- Text scale options
- Lockscreen visibility on Main page
- NO sync/network (deferred to v2.0)
- NO separate Store Edit navigation destination (all store management in Stores mode page 0)

## Implementation Order
1. ✅ Database schema + DAOs
2. ✅ Repository layer + domain models
3. ✅ MainActivity with BottomAppBar + ViewPager2 adapter
4. ✅ Plan fragment (core functionality)
5. ✅ Shop fragment + store selection
6. ✅ ViewPager/BottomAppBar integration
7. ✅ Stores page (reorder items, set availability per store via swipes)
8. ✅ Store selector via menu dialog (Stores mode)
9. ✅ Swipe gestures for availability toggle (Stores mode)
11. ✅ Specialty store flag in database and UI
12. ✅ Import items feature (bulk add from text)
13. ✅ Bulk state changes (activate all / deactivate all in Plan mode)
14. ✅ Lockscreen visibility for Shop mode
15. ⏳ Merge Plan+Shop into unified Main page
16. ⏳ Add "+" functionality to search bar (add item when no exact match)
17. ⏳ Settings + preferences
18. ⏳ Polish + accessibility
19. ⏳ Testing

## Implementation Details to Decide
- Drag animation style
- Search debounce timing (for filtering, ~150-200ms suggested)
- Done visibility button icon set (distinct icons for none/recent/all states) - **POSTPONED**, using text labels ("todo"/"recent"/"all") for now
- Store selector UI pattern (Spinner vs custom dropdown)
- DiffUtil comparison strategy
- Error event delivery mechanism (SingleLiveEvent vs Channel)
- ViewPager page change animation duration
- BottomAppBar content transition animation
- Search bar "+" icon appearance threshold (exact match vs partial match)
- Keyboard action button switch timing (when to show checkmark vs default action)
