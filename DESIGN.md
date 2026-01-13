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
- three modes: plan, shop, edit store
    - plan: add/activate/deactivate/postpone/edit/delete items
    - shop: check/postpone items
        - switching stores should seamlessly re-order & re-filter the list
    - store: order & filter items in the global shopping list according
      to placement and availability in the currently active store
- state transitions by mode (L/R - swipe left/swipe right on the item):
    - plan (new items are default activated):
        - postpone <-L- activated -R-> deactivate
        - postpone <-L- deactivated -R-> activate
        - deactivate <-L- postponed -R-> activate
    - shop (new items are default activated):
        - postpone <-L- activated -R-> deactivate
        - activate <-L- deactivated -R-> postpone
            - deactivated items are not visible by default in shop mode
            - but show-done-items is be togglebale via overflow/options
        - deactivate <-L- postponed -R-> activate
- FAB in each mode:
    - plan: add item ("+")
    - shop: search-as-you-type (search icon, magnifier)
        - FAB expands into search bar
        - but can still add items via options/overflow menu
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
    - item states is a a relational table (item<->state)
    - stores are just strings
    - item unavailability is just a relation table (store<->item)
    - store order is a relational table (store<->item) with order integer
        - new items' order for any shop is assumed NULL (no db entry) and sorted before sorted items
        - unsorted items are sorted by item id (i.e. sorted by age because newer items = higher id)
        - duplicate sort indices are not critical (items are sorted next to each other by item id)
          but app logic should prevent this. but don't fail on db layer if app logic fails.
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
