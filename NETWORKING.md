# QuickShopper3k Networking & Sync Design

## Overview

Peer-to-peer sync via server relay. Any linked device can emit intents. The server stores and distributes intents to all devices linked to the same list.

## Core Principles

- **Intent-based, not state-based**: devices transmit user actions (intents), not database snapshots. State is derived by replaying intents.
- **Offline-first**: all operations apply locally immediately. Sync happens opportunistically.
- **Last-writer-wins (LWW)**: conflicts resolved by intent ULID. Acceptable trade-off for a shopping list.
- **Server-canonical ordering**: the server re-stamps every intent with its own ULID on receipt. This server ULID is the canonical ordering key. The device's original timestamp is preserved in the payload for debugging. This avoids clock-drift problems between devices without any arbitrary thresholds — offline intents simply land "when the server learns about them," which is the intuitive causal order.
- **Polling, not WebSocket**: `GET` with `?since={ulid}` is simple, sufficient for two devices, and trivial on the server side.

## Identity

### List UUID
- Generated on first use (or on explicit "create shared list" action).
- Shared out-of-band (QR code, copy-paste, etc.) so other devices can link to it.
- All intents are scoped to a list UUID.

### Device UUID
- Generated on first launch, stored in SharedPreferences.
- Attached to every intent for attribution (not used for conflict resolution — ULID timestamp is).

### Entity UUIDs (Items, Stores)
- Every item and store gets a UUID (ULID) at creation time.
- Local Room autoincrement `id` stays for internal foreign keys and query performance.
- The intent log and server use UUIDs exclusively.
- Local DB has a `uuid TEXT` column on `items` and `stores` tables, with a unique index.

### ULID Implementation
Hand-rolled (~40 lines), no external dependency. Crockford Base32 encoding of 48-bit millisecond timestamp + 80-bit random component. Sortable by time, globally unique.

## Intent Model

Each intent is a JSON object:

```json
{
  "device_ulid": "<ulid>",
  "server_ulid": null,
  "device_id": "<device-uuid>",
  "list_id": "<list-uuid>",
  "type": "<intent-type>",
  "payload": { ... }
}
```

- `device_ulid`: generated on the device at intent creation time. Used for local apply order. Encodes the device-local timestamp (ULID first 48 bits), so no separate timestamp field is needed.
- `server_ulid`: assigned by the server on receipt. This is the **canonical ordering key** for conflict resolution and cross-device replay. `null` until the server has seen the intent.

### Intent Catalog

| Type | Payload | Notes |
|---|---|---|
| `add_item` | `{item_uuid, name}` | Never conflicts (unique ULID) |
| `delete_item` | `{item_uuid}` | Tombstone; late intents for this item become no-ops |
| `rename_item` | `{item_uuid, name}` | LWW |
| `set_item_state` | `{item_uuid, state, previous_state}` | Priority-based conflict resolution (see below) |
| `set_item_amount` | `{item_uuid, amount}` | LWW |
| `add_store` | `{store_uuid, name, specialty}` | Never conflicts |
| `delete_store` | `{store_uuid}` | Tombstone |
| `update_store` | `{store_uuid, name, specialty}` | LWW |
| `set_availability` | `{store_uuid, item_uuid, available}` | LWW |
| `set_item_order` | `{store_uuid, item_uuid, sort_order}` | LWW per item-store pair |
| `bulk_add_items` | `{items: [{item_uuid, name}, ...]}` | Convenience; expands to individual `add_item` semantics |
| `bulk_set_state` | `{item_uuids: [...], state}` | Convenience; expands to individual `set_item_state` semantics |

## Conflict Resolution

- **LWW by server ULID** for most mutable fields. When two intents modify the same entity+field, the one with the higher server ULID wins.
- **Deletes are tombstones**: a deleted entity stays as a tombstone record. Any intent arriving for a tombstoned entity is silently dropped. Tombstones can be pruned during compaction once all devices have acknowledged past the delete.
- **Ordering**: LWW per `(store_uuid, item_uuid)` pair on `sort_order`. If two devices reorder simultaneously, the latest wins. Low-stakes for a shopping list.
- **Convergence guarantee**: after all devices have received all intents and replayed in server ULID order, they arrive at identical state.

### Item State Conflicts (Priority-Based)

`set_item_state` carries `previous_state` — the state the device saw at the time of the action. Two intents are **concurrent** if they share the same `previous_state` (neither device had seen the other's change). Sequential intents (different `previous_state`) are applied in server ULID order as normal.

For concurrent intents, instead of LWW, a **priority order** applies:

> `DEACTIVATED` > `ACTIVATED` > `POSTPONED`

Rationale by use-case scenario:

| Conflict | Winner | Why |
|---|---|---|
| A: done, B: postponed | `DEACTIVATED` | Someone bought it — that's definitive |
| A: activate, B: deactivate | `DEACTIVATED` | It was bought, even if someone else still had it on their list |
| A: activate, B: postpone | `ACTIVATED` | Still needed, just not at one particular store |
| A: postpone, B: postpone | `POSTPONED` | No conflict |

This reflects that "bought" is the strongest signal, "still need it" is next, and "can't find it right now" is weakest — regardless of whether the devices are at home or in a shop.

## API Contract

Server is a black box. Assumed endpoints:

### `POST /list/{list_uuid}/intents`
Push one or more intents. Body is a JSON array of intent objects. Server stores them and makes them available to other devices.

### `GET /list/{list_uuid}/intents?since={ulid}&device_id={device_id}`
Pull intents newer than the given ULID. `device_id` identifies the client and `since` acknowledges the latest server ULID it has already applied. Returns JSON array of intent objects in **strict `server_ulid` order**. The client relies on this guarantee to apply intents incrementally without per-field LWW tracking — every incoming intent is newer than anything previously seen. If `since` is omitted or the server has compacted past that point, returns a snapshot + subsequent intents.

### `GET /list/{list_uuid}/snapshot`
Returns the latest compacted snapshot (full state) plus the ULID of the snapshot. Client can then poll intents from that ULID forward.

### `GET /list/{list_uuid}/debug`
Returns the full intent queue for the list: all retained intents (post-compaction), the current snapshot ULID if any, and connected device IDs with their `last_synced_ulid`. For in-app debugging only.

## Sync Flow

```
  Device A                    Server                    Device B
     │                          │                          │
     ├──POST intents──────────▶ │                          │
     │                          │ (stores)                 │
     │                          │                          │
     │                          │ ◀──GET intents?since=X── ┤
     │                          │ ──intents──────────────▶ │
     │                          │                          │
     │ ◀──GET intents?since=Y── │                          │
     │ ──intents──────────────▶ │                          │
     │                          │ ◀──POST intents───────── ┤
     │                          │ (stores)                 │
```

1. User action → create intent → apply locally → enqueue for sync.
2. On sync tick (polling interval, e.g. 5s when app is foreground):
   - Pull intents since the last applied ULID, acknowledging that cursor to the server.
   - Apply remote intents locally in ULID order, skipping own intents (already applied).
   - Push queued local intents to the server.
3. On app open after being backgrounded: immediate sync.
4. On first link to a list UUID: pull snapshot + all intents, rebuild local state from scratch.

## Compaction

The intent log grows without bound. Compaction collapses old intents into a state snapshot.

- **Server-side**: compaction is done on the server.
- **Trigger point**: evaluated on `GET /list/{list_uuid}/intents`, but rate-limited to at most once per list every 12 hours.
- **Active-device floor**: compute `effective_min_hwm` as the minimum `last_synced_ulid` among non-stale devices for the list.
- **Stale device cutoff**: devices with no sync activity for 60 days are excluded from `effective_min_hwm`.
- **Backlog trigger**: compaction is eligible when there are at least 10 intents newer than `effective_min_hwm`.
- **Retained tail**: always retain recent intents by time, keeping at least a 7-day tail.
- **Compaction boundary**: compact only up to the older of:
  - `effective_min_hwm`, and
  - the ULID corresponding to `now - 7 days`.
- **Snapshot format**: full state dump — all items (with uuid, name, state, amount, created_at), all stores (with uuid, name, specialty), all availability entries, all ordering entries. JSON.
- **Client catch-up**: if a client's `since` ULID is older than the oldest retained intent, the server returns the snapshot first, then intents after the snapshot.
- **Tombstone pruning**: tombstones can be removed during compaction if all non-stale devices have synced past the delete intent.

## Local Schema Changes Required

### New columns
- `items.uuid TEXT NOT NULL` with `UNIQUE` index
- `stores.uuid TEXT NOT NULL` with `UNIQUE` index

### New tables
```sql
CREATE TABLE sync_intent_queue (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    ulid TEXT NOT NULL,
    intent_json TEXT NOT NULL,
    synced INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE sync_state (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL
);
-- keys: 'list_uuid', 'device_uuid', 'last_synced_ulid'
```

### Migration
- Room DB version bump
- Generate UUIDs for all existing items and stores
- Existing local-only users continue to work without a list UUID until they choose to share

## Sharing UX (Minimal)

1. **Share**: user taps "Share list" → app generates (or shows existing) list UUID → presents as copyable text / QR code.
2. **Join**: user taps "Join shared list" → enters/scans list UUID → app pulls snapshot → replaces local data with shared list state.
3. After joining, both devices sync bidirectionally via the server.

## Not In Scope (Yet)

- Authentication / authorization (anyone with the list UUID can read/write)
- Multiple lists per device
- Permissions (read-only subscribers)
- End-to-end encryption
- Push notifications for real-time updates
- Server implementation details (deferred to `/server/`)