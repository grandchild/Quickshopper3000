# QuickShopper3000 a.k.a. QS3k

## Goals

### Android App
- quick-to-use shopping list
- global shopping list
    - grows as one adds items
    - in the long run all items that one needs are on the global list
    - new items are added
    - existing items are very rarely deleted
    - items are activated if they are needed
    - items are deactivated when they have been bought or aren't needed anymore
    - items are postponed (mostly during a grocery trip) if they are still needed,
      but not available currently
    - items can carry an amount (default 1); the amount resets to 1 once an item
      has been done for long enough
- dynamically sorted by order in store (i.e. custom sorting)
- dynamically filtered by availability in store
- stores:
    - stores are named (ordering + filter) settings
    - the list is shown for one active store at a time (ordered & filtered for it)
    - by default all items are available in every store, i.e. they are only marked as unavailable
    - a default "All items" store (id 0) always exists: it has an ordering, no filtering,
      and is selectable from the store picker
    - stores can be marked as "specialty stores" where the items that exist at creation
      time are marked unavailable. (Items added later still default to available.)
- two modes: main list, store
    - main list:
        - add/activate/deactivate/postpone/edit/delete items
        - set item amount
        - toggle done items visibility (shopping use case)
        - switching stores seamlessly re-orders & re-filters the list
        - search bar with integrated add functionality
        - bulk actions: activate all, deactivate all, activate postponed
        - import items (bulk add from text, with an "add known only" option)
        - share active items as text
    - store: order & filter items (per-store drag reorder, availability toggle,
      add/rename/delete store)
- state transitions by mode (L/R - swipe left/swipe right on the item):
    - main list (new items are default activated):
        - postpone <-L- activated -R-> deactivate
        - postpone <-L- deactivated -R-> activate
        - deactivate <-L- postponed -R-> activate
        - done items ("Done") visibility is a two-state toggle in the BottomAppBar:
            - current: show items done within the last X minutes (X = configurable, default 60)
            - all: show all done items
            - the button cycles current <-> all on tap (text label, no icon)
        - "flush recently done" menu action: sets a cutoff to now, hiding all previously
          done items in "current" mode. The cutoff supersedes the X-minute window only
          while it is more recent; after X minutes it becomes irrelevant again.
    - store (new items are default available):
        - a swipe in either direction toggles available <-> unavailable
- in the store list, items are multi-selectable (tap / long-press) and can be
  dragged as a block, so several items can be reordered at once
- FAB per mode:
    - main list: search (magnifier-with-plus icon)
        - the FAB toggles a search bar above the BottomAppBar
        - search-as-you-type filters the list
        - a "+" button appears on the right end of the search bar when the
          search term has no exact match in the list
        - when the search yields no results, the keyboard action button becomes
          a checkmark/enter to add the item
        - tapping "+" or the keyboard action adds the search term as a new item
    - store: add new store
- UI language is a bit different from code language in terms of item states:
    - activated items have no state, they're just there in normal font
    - deactivated items show a "done" icon and dimmed text
    - postponed items show an "N/A" icon and slightly dimmed text
    - items unavailable in the active store are italicized
- backend in database
    - items: strings
        - not unique (i don't want to deal with the error message)
        - case- & accent-insensitive matching (stored as entered)
    - states: strings
    - item<->state is a relational table (no row = activated)
        - carries a nullable state_changed_at, updated on every state change
        - used for "recent done items" filtering
    - stores are just strings
    - item unavailability is just a relation table (store<->item)
    - store order is a relational table (store<->item) with an integer order
        - new items are appended to the end of the store's order
        - reordering writes concrete integers (gapped in the main list, dense in the store list)
        - don't fail on the db layer if ordering gets inconsistent; the app keeps it consistent
    - item add history is a relational table (item<->timestamp)
        - activation is recorded
        - done within mistake_done_seconds removes the latest history entry
        - reactivation only counts if last done is older than reactivate_min_seconds
        - entries older than history_days are pruned
    - history settings in preferences: history_days, mistake_done_seconds, reactivate_min_seconds
- main list page visible from lockscreen
- offline-first
- sync across devices & share with other people (intent-based, see NETWORKING.md)
- export/import backup (zip of database + settings)
- open source (CC0)
- minimal permissions (INTERNET only)

#### Stack & Android requirements
- kotlin
- min android 8 (SDK 26)
- use jetpack libs as appropriate and useful
    - room for DB
- small apk size is a high-value target

### Server
- in rust, at the repo root (qs3k-api.rs)
- minimal dependencies
- simple json api (POST/GET intents, snapshot, debug)
- intent-based sync with server-side compaction (see NETWORKING.md)
- share lists by access (list UUID / qs3k:// link), not by copy
- CLI client (qs3k-cli.rs) for add/done/search/show plus shell completions

## Design and a11y
- handedness: left-handed mode flips the FAB side (implemented); reversing swipe
  directions and a settings toggle are not wired yet
- dark/light theme via system setting (single DayNight theme, night color overrides)
- minimalistic design first, no frills unless asked for
- not yet: text/zoom scale modes (compact/normal/large), item-history visualization,
  recurring-items frequency view