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
- dynamically sorted by order in store (i.e. custom sorting)
- dynamically filtered by availability in store
- stores:
    - stores are named (ordering + filter) settings
    - with a given shopping list, and one or more stores, the list becomes
      store-specific (i.e. ordered and filtered per store)
    - by default all items are available in every store, i.e. they are only marked as unavailable
    - initially there are no shops, but there's a "null" default shop, that's not visible but has an ordering
      and no filtering (i.e. all items are available)
    - stores can be marked as "specialty stores" where existing and new items are default-unavailable.
- two modes: main list, edit store
    - main list (formerly plan+shop combined):
        - add/activate/deactivate/postpone/edit/delete items
        - toggle done items visibility (shopping use case)
        - switching stores seamlessly re-orders & re-filters the list
        - search bar with integrated add functionality
    - store: order & filter items in the global shopping list according
      to placement and availability in the currently active store
- state transitions by mode (L/R - swipe left/swipe right on the item):
    - main list (new items are default activated):
        - postpone <-L- activated -R-> deactivate
        - postpone <-L- deactivated -R-> activate
        - deactivate <-L- postponed -R-> activate
        - deactivated items ("Done") visibility is controlled by tristate toggle in BottomAppBar:
            - none: hide all done items
            - current: show done items from last X minutes (X = configurable, default 60)
            - all: show all done items
        - tristate button cycles through modes on tap (none → current → all → none)
        - "flush recently done" menu action: sets a cutoff timestamp to now, hiding all
          previously done items in "recent" mode. The flush timestamp supersedes the
          X minutes threshold only while it's more recent; after X minutes pass, it
          becomes irrelevant and the normal X-minute window applies again.
    - store (new items are default available):
        - unavailable <-L- available -R-> unavailable
        - available <-L- unavailable -R-> available
- FAB per mode:
    - main list: search (magnifier icon)
        - FAB expands into search bar (above BottomAppBar, as before)
        - search-as-you-type filters the list
        - small "+" button appears on right end of search bar when:
            - search term has no exact match in list
        - when search yields no results:
            - keyboard action button becomes checkmark/enter to add item
            - the "+" button is more prominent
        - tapping "+" or keyboard action adds search term as new item
    - store: add new store
- UI language is a bit different from code language in terms of item states:
    - activated items have no state name, they're just there in normal font
    - deactivated items are labeled "Done"
    - postponed items are labeled "N/A"
- backend in database
    - items: strings
        - not unique (i don't want to deal with the error message)
        - case-insensitive (store as-entered)
    - states: strings
    - item states is a relational table (item<->state)
        - includes state_changed_at timestamp (nullable, updated on every state change)
        - used for "recent done items" filtering
    - stores are just strings
    - item unavailability is just a relation table (store<->item)
    - store order is a relational table (store<->item) with order integer
        - new items' order for any shop is assumed NULL (no db entry) and sorted before sorted items
        - unsorted items are sorted by item id (i.e. sorted by age because newer items = higher id)
        - duplicate sort indices are not critical (items are sorted next to each other by item id)
          but app logic should prevent this. but don't fail on db layer if app logic fails.
    - item add history is a relational table (item<->timestamp)
        - activation is recorded
        - done within mistake_done_seconds removes the latest history entry
        - reactivation only counts if last done is older than reactivate_min_seconds
        - entries older than history_days are pruned
    - history settings in preferences: history_days, mistake_done_seconds, reactivate_min_seconds
- main list page visible from lockscreen
- offline-first
- open source (CC0)
- minimal permissions
- sync across devices & share with other people (ignore sync/network/collisions for MVP)

#### Stack & Android requirements
- kotlin
- target android 8+
- use jetpack libs as appropriate and useful
    - room for DB
- small apk size is a high-value target

### Server
- in /server/
- sync shopping lists across devices
- in rust
    - minimal dependencies
- simple json api
- sync lists & stores (versioned)
- share lists (not copy, access)

## Design and a11y
- handedness setting
    - allow user to reverse swipe directions and FAB placement (left-handed mode)
- three zoom/fontsize modes: compact, normal, large
- dark/light theme (but keep it DRY)
- minimalistic design first, no frills unless asked for
