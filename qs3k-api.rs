use flate2::read::GzDecoder;
use jiff::Timestamp;
use rusqlite::{params, Connection, Result as SqliteResult};
use serde::{Deserialize, Serialize};
use std::{
    collections::{HashMap, HashSet},
    error::Error,
    io::Read,
    sync::{Arc, Mutex, MutexGuard},
};
use tiny_http::{Header, Method, Request, Response, Server};

#[derive(Clone)]
struct AppState {
    db: Arc<Mutex<Connection>>,
    public_url: Option<String>,
}

#[derive(Debug)]
struct ErrorInfo {
    status: u16,
    message: String,
}

type ApiResult<T> = Result<T, ErrorInfo>;

#[derive(Serialize, Deserialize, Clone)]
struct Intent {
    device_ulid: String,
    #[serde(default)]
    server_ulid: Option<String>,
    device_id: String,
    list_id: String,
    #[serde(rename = "type")]
    intent_type: String,
    payload: serde_json::Value,
}

#[derive(Serialize)]
struct SnapshotResponse {
    snapshot_ulid: Option<String>,
    snapshot: Option<serde_json::Value>,
    intents: Vec<Intent>,
}

#[derive(Serialize)]
struct DebugResponse {
    intent_count: usize,
    snapshot_ulid: Option<String>,
    devices: Vec<DeviceInfo>,
    intents: Vec<Intent>,
}

#[derive(Serialize)]
struct DeviceInfo {
    device_id: String,
    last_synced_ulid: Option<String>,
}

#[derive(Serialize, Deserialize, Default)]
struct MaterializedSnapshot {
    #[serde(default)]
    items: Vec<SnapshotItem>,
    #[serde(default)]
    stores: Vec<SnapshotStore>,
    #[serde(default)]
    availability: Vec<SnapshotAvailability>,
    #[serde(default)]
    item_order: Vec<SnapshotItemOrder>,
    #[serde(default)]
    deleted_items: Vec<String>,
    #[serde(default)]
    deleted_stores: Vec<String>,
}

#[derive(Serialize, Deserialize)]
struct SnapshotItem {
    item_uuid: String,
    name: String,
    state: String,
    amount: i64,
    created_at: u64,
}

#[derive(Serialize, Deserialize)]
struct SnapshotStore {
    store_uuid: String,
    name: String,
    specialty: bool,
}

#[derive(Serialize, Deserialize)]
struct SnapshotAvailability {
    store_uuid: String,
    item_uuid: String,
    available: bool,
}

#[derive(Serialize, Deserialize)]
struct SnapshotItemOrder {
    store_uuid: String,
    item_uuid: String,
    sort_order: i64,
}

struct MaterializedItem {
    name: String,
    state: String,
    amount: i64,
    created_at: u64,
}

struct MaterializedStore {
    name: String,
    specialty: bool,
}

#[derive(Default)]
struct MaterializedState {
    items: HashMap<String, MaterializedItem>,
    stores: HashMap<String, MaterializedStore>,
    availability: HashMap<(String, String), bool>,
    item_order: HashMap<(String, String), i64>,
    deleted_items: HashSet<String>,
    deleted_stores: HashSet<String>,
}

const DB_VERSION: i32 = 1;

const MAX_TOTAL_INTENTS: usize = 10_000;
const MAX_ITEM_NAME_LEN: usize = 1000;
const MAX_STORE_NAME_LEN: usize = 1000;
const MAX_AMOUNT: i64 = 1_000_000;

const COMPACTION_BACKLOG_THRESHOLD: usize = 10;
const RETENTION_TAIL_HOURS: i64 = 7 * 24;
const STALE_DEVICE_HOURS: i64 = 60 * 24;
const COMPACTION_CHECK_INTERVAL_HOURS: i64 = 12;

fn db_error(e: rusqlite::Error) -> ErrorInfo {
    ErrorInfo {
        status: 500,
        message: format!("Database error: {e}"),
    }
}

fn json_error(e: serde_json::Error) -> ErrorInfo {
    ErrorInfo {
        status: 500,
        message: format!("JSON serialization error: {e}"),
    }
}

fn read_body_error(e: String) -> ErrorInfo {
    ErrorInfo {
        status: 400,
        message: e,
    }
}

fn invalid_json() -> ErrorInfo {
    ErrorInfo {
        status: 400,
        message: "Invalid JSON".into(),
    }
}

fn generate_ulid() -> String {
    let now_ms = {
        let ts = Timestamp::now();
        let secs = ts.as_second();
        let nanos = ts.subsec_nanosecond();
        (secs as u64) * 1000 + (nanos as u64) / 1_000_000
    };

    let mut bytes = [0u8; 10];
    let mut rng_state: u64 = now_ms;
    for b in &mut bytes {
        rng_state ^= rng_state << 13;
        rng_state ^= rng_state >> 7;
        rng_state ^= rng_state << 17;
        rng_state = rng_state.wrapping_add(0x9E3779B97F4A7C15);
        *b = rng_state as u8;
    }

    static COUNTER: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);
    let ctr = COUNTER.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
    bytes[8] ^= (ctr >> 8) as u8;
    bytes[9] ^= ctr as u8;

    let pid = std::process::id();
    bytes[0] ^= pid as u8;
    bytes[1] ^= (pid >> 8) as u8;

    const CROCKFORD: &[u8; 32] = b"0123456789ABCDEFGHJKMNPQRSTVWXYZ";

    let mut out = [0u8; 26];

    let ts_val = now_ms & 0xFFFF_FFFF_FFFF;
    out[0] = CROCKFORD[((ts_val >> 45) & 0x1F) as usize];
    out[1] = CROCKFORD[((ts_val >> 40) & 0x1F) as usize];
    out[2] = CROCKFORD[((ts_val >> 35) & 0x1F) as usize];
    out[3] = CROCKFORD[((ts_val >> 30) & 0x1F) as usize];
    out[4] = CROCKFORD[((ts_val >> 25) & 0x1F) as usize];
    out[5] = CROCKFORD[((ts_val >> 20) & 0x1F) as usize];
    out[6] = CROCKFORD[((ts_val >> 15) & 0x1F) as usize];
    out[7] = CROCKFORD[((ts_val >> 10) & 0x1F) as usize];
    out[8] = CROCKFORD[((ts_val >> 5) & 0x1F) as usize];
    out[9] = CROCKFORD[(ts_val & 0x1F) as usize];

    let rand_val: u128 = bytes.iter().fold(0u128, |acc, &b| (acc << 8) | b as u128);
    out[10] = CROCKFORD[((rand_val >> 75) & 0x1F) as usize];
    out[11] = CROCKFORD[((rand_val >> 70) & 0x1F) as usize];
    out[12] = CROCKFORD[((rand_val >> 65) & 0x1F) as usize];
    out[13] = CROCKFORD[((rand_val >> 60) & 0x1F) as usize];
    out[14] = CROCKFORD[((rand_val >> 55) & 0x1F) as usize];
    out[15] = CROCKFORD[((rand_val >> 50) & 0x1F) as usize];
    out[16] = CROCKFORD[((rand_val >> 45) & 0x1F) as usize];
    out[17] = CROCKFORD[((rand_val >> 40) & 0x1F) as usize];
    out[18] = CROCKFORD[((rand_val >> 35) & 0x1F) as usize];
    out[19] = CROCKFORD[((rand_val >> 30) & 0x1F) as usize];
    out[20] = CROCKFORD[((rand_val >> 25) & 0x1F) as usize];
    out[21] = CROCKFORD[((rand_val >> 20) & 0x1F) as usize];
    out[22] = CROCKFORD[((rand_val >> 15) & 0x1F) as usize];
    out[23] = CROCKFORD[((rand_val >> 10) & 0x1F) as usize];
    out[24] = CROCKFORD[((rand_val >> 5) & 0x1F) as usize];
    out[25] = CROCKFORD[(rand_val & 0x1F) as usize];

    String::from_utf8_lossy(&out).into_owned()
}

fn init_db(conn: &Connection) -> SqliteResult<()> {
    conn.pragma_update(None, "journal_mode", "WAL")?;
    create_tables(conn)?;
    println!("Database initialized");
    Ok(())
}

fn create_tables(conn: &Connection) -> SqliteResult<()> {
    conn.execute(
        "CREATE TABLE IF NOT EXISTS db_version (
            version INTEGER PRIMARY KEY
        )",
        params![],
    )?;

    conn.execute(
        "CREATE TABLE IF NOT EXISTS intent (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            list_id TEXT NOT NULL,
            server_ulid TEXT NOT NULL,
            device_ulid TEXT NOT NULL,
            device_id TEXT NOT NULL,
            intent_type TEXT NOT NULL,
            payload_json TEXT NOT NULL,
            created_at TEXT NOT NULL
        )",
        params![],
    )?;

    conn.execute(
        "CREATE INDEX IF NOT EXISTS idx_intent_list_server_ulid ON intent(list_id, server_ulid)",
        params![],
    )?;

    conn.execute(
        "CREATE TABLE IF NOT EXISTS snapshot (
            list_id TEXT PRIMARY KEY,
            snapshot_ulid TEXT NOT NULL,
            snapshot_json TEXT NOT NULL,
            created_at TEXT NOT NULL
        )",
        params![],
    )?;

    conn.execute(
        "CREATE TABLE IF NOT EXISTS device_sync (
            list_id TEXT NOT NULL,
            device_id TEXT NOT NULL,
            last_synced_ulid TEXT,
            updated_at TEXT NOT NULL,
            PRIMARY KEY (list_id, device_id)
        )",
        params![],
    )?;

    conn.execute(
        "CREATE TABLE IF NOT EXISTS compaction_state (
            list_id TEXT PRIMARY KEY,
            last_compaction_check_at TEXT NOT NULL
        )",
        params![],
    )?;

    Ok(())
}

fn migrate_db(conn: &Connection) -> SqliteResult<()> {
    let current_version: i32 = conn
        .query_row("SELECT version FROM db_version LIMIT 1", params![], |row| {
            row.get(0)
        })
        .unwrap_or(0);

    if current_version < 1 {
        conn.execute(
            "INSERT OR REPLACE INTO db_version (version) VALUES (?1)",
            params![DB_VERSION],
        )?;
    }

    Ok(())
}

fn get_current_timestamp() -> String {
    Timestamp::now()
        .strftime("%Y-%m-%dT%H:%M:%S.%fZ")
        .to_string()
}

fn lock_db(state: &AppState) -> ApiResult<MutexGuard<'_, Connection>> {
    state.db.lock().map_err(|_| ErrorInfo {
        status: 500,
        message: "Internal error: DB lock poisoned".into(),
    })
}

fn safe_header(name: &[u8], value: &[u8]) -> Option<Header> {
    Header::from_bytes(name, value).ok()
}

fn cors_headers() -> Vec<Header> {
    [
        safe_header(b"Access-Control-Allow-Origin", b"*"),
        safe_header(b"Access-Control-Allow-Methods", b"GET, POST, OPTIONS"),
        safe_header(
            b"Access-Control-Allow-Headers",
            b"DNT,User-Agent,X-Requested-With,If-Modified-Since,Cache-Control,Content-Type,Range",
        ),
        safe_header(b"Access-Control-Max-Age", b"1728000"),
    ]
    .into_iter()
    .flatten()
    .collect()
}

fn json_response<T: Serialize>(data: &T) -> ApiResult<Response<std::io::Cursor<Vec<u8>>>> {
    let json = serde_json::to_string(data).map_err(json_error)?;
    let mut response = Response::from_string(json);
    if let Some(h) = safe_header(b"Content-Type", b"application/json") {
        response = response.with_header(h);
    }
    for h in cors_headers() {
        response = response.with_header(h);
    }
    Ok(response)
}

fn error_response(msg: &str, status: u16) -> Response<std::io::Cursor<Vec<u8>>> {
    eprintln!("error status={status} msg={msg}");

    let mut escaped = String::with_capacity(msg.len() + 16);
    for ch in msg.chars() {
        match ch {
            '\\' => escaped.push_str("\\\\"),
            '"' => escaped.push_str("\\\""),
            '\n' => escaped.push_str("\\n"),
            '\r' => escaped.push_str("\\r"),
            '\t' => escaped.push_str("\\t"),
            c if c.is_control() => escaped.push(' '),
            c => escaped.push(c),
        }
    }
    let json = format!("{{\"error\":\"{escaped}\"}}");

    let mut response = Response::from_string(json).with_status_code(status);
    if let Some(h) = safe_header(b"Content-Type", b"application/json") {
        response = response.with_header(h);
    }
    for h in cors_headers() {
        response = response.with_header(h);
    }
    response
}

fn cors_response() -> Response<std::io::Cursor<Vec<u8>>> {
    let mut response = Response::from_string("").with_status_code(204);
    for h in cors_headers() {
        response = response.with_header(h);
    }
    response
}

fn read_body(request: &mut Request) -> Result<String, String> {
    let is_gzipped = request.headers().iter().any(|h| {
        h.field.equiv("Content-Encoding") && h.value.as_str().eq_ignore_ascii_case("gzip")
    });

    let mut body = String::new();
    if is_gzipped {
        let mut raw = Vec::new();
        request
            .as_reader()
            .read_to_end(&mut raw)
            .map_err(|e| format!("Failed to read body: {e}"))?;
        GzDecoder::new(&raw[..])
            .read_to_string(&mut body)
            .map_err(|e| format!("Failed to decompress gzipped body: {e}"))?;
    } else {
        request
            .as_reader()
            .read_to_string(&mut body)
            .map_err(|e| format!("Failed to read body: {e}"))?;
    }
    Ok(body)
}

fn parse_query_params(query: &str) -> HashMap<String, String> {
    query
        .split('&')
        .filter_map(|param| {
            let mut parts = param.split('=');
            match (parts.next(), parts.next()) {
                (Some(k), Some(v)) => Some((k.to_string(), v.to_string())),
                _ => None,
            }
        })
        .collect()
}

fn track_device_sync(
    conn: &Connection,
    list_id: &str,
    device_id: &str,
    server_ulid: &str,
) -> ApiResult<()> {
    let now = get_current_timestamp();
    conn.execute(
        "INSERT INTO device_sync (list_id, device_id, last_synced_ulid, updated_at)
         VALUES (?1, ?2, ?3, ?4)
         ON CONFLICT(list_id, device_id) DO UPDATE SET
            last_synced_ulid = CASE
                WHEN excluded.last_synced_ulid > device_sync.last_synced_ulid THEN excluded.last_synced_ulid
                ELSE device_sync.last_synced_ulid
            END,
            updated_at = excluded.updated_at",
        params![list_id, device_id, server_ulid, now],
    )
    .map_err(db_error)?;
    Ok(())
}

fn load_intents_since(conn: &Connection, list_id: &str, since: &str) -> ApiResult<Vec<Intent>> {
    let mut stmt = conn
        .prepare(
            "SELECT server_ulid, device_ulid, device_id, list_id, intent_type, payload_json
             FROM intent
             WHERE list_id = ?1 AND server_ulid > ?2
             ORDER BY server_ulid ASC",
        )
        .map_err(db_error)?;

    let rows = stmt
        .query_map(params![list_id, since], |row| {
            let payload_str: String = row.get(5)?;
            Ok((
                row.get::<_, String>(0)?,
                row.get::<_, String>(1)?,
                row.get::<_, String>(2)?,
                row.get::<_, String>(3)?,
                row.get::<_, String>(4)?,
                payload_str,
            ))
        })
        .map_err(db_error)?;

    let mut intents = Vec::new();
    for row in rows {
        let (server_ulid, device_ulid, device_id, list_id, intent_type, payload_str) =
            row.map_err(db_error)?;
        let payload: serde_json::Value = serde_json::from_str(&payload_str).map_err(json_error)?;
        intents.push(Intent {
            device_ulid,
            server_ulid: Some(server_ulid),
            device_id,
            list_id,
            intent_type,
            payload,
        });
    }
    Ok(intents)
}

fn load_all_intents(conn: &Connection, list_id: &str) -> ApiResult<Vec<Intent>> {
    load_intents_since(conn, list_id, "")
}

fn should_run_compaction_check(conn: &Connection, list_id: &str) -> ApiResult<bool> {
    let maybe_last: Result<String, rusqlite::Error> = conn.query_row(
        "SELECT last_compaction_check_at FROM compaction_state WHERE list_id = ?1",
        params![list_id],
        |row| row.get(0),
    );

    let now = Timestamp::now();
    let should_run = match maybe_last {
        Ok(last) => match last.parse::<Timestamp>() {
            Ok(last_ts) => now
                .since(last_ts)
                .and_then(|span| span.total(jiff::Unit::Hour))
                .map(|hours| hours as i64 >= COMPACTION_CHECK_INTERVAL_HOURS)
                .unwrap_or(true),
            Err(_) => true,
        },
        Err(rusqlite::Error::QueryReturnedNoRows) => true,
        Err(e) => return Err(db_error(e)),
    };

    Ok(should_run)
}

fn mark_compaction_check(conn: &Connection, list_id: &str) -> ApiResult<()> {
    let now = get_current_timestamp();
    conn.execute(
        "INSERT INTO compaction_state (list_id, last_compaction_check_at)
         VALUES (?1, ?2)
         ON CONFLICT(list_id) DO UPDATE SET
            last_compaction_check_at = excluded.last_compaction_check_at",
        params![list_id, now],
    )
    .map_err(db_error)?;
    Ok(())
}

fn payload_str<'a>(
    payload: &'a serde_json::Value,
    key: &str,
    intent_type: &str,
) -> ApiResult<&'a str> {
    payload
        .get(key)
        .and_then(|v| v.as_str())
        .ok_or_else(|| ErrorInfo {
            status: 500,
            message: format!("Cannot compact malformed {intent_type}: missing string '{key}'"),
        })
}

fn payload_bool(payload: &serde_json::Value, key: &str, intent_type: &str) -> ApiResult<bool> {
    payload
        .get(key)
        .and_then(|v| v.as_bool())
        .ok_or_else(|| ErrorInfo {
            status: 500,
            message: format!("Cannot compact malformed {intent_type}: missing bool '{key}'"),
        })
}

fn payload_i64(payload: &serde_json::Value, key: &str, intent_type: &str) -> ApiResult<i64> {
    payload
        .get(key)
        .and_then(|v| v.as_i64())
        .ok_or_else(|| ErrorInfo {
            status: 500,
            message: format!("Cannot compact malformed {intent_type}: missing integer '{key}'"),
        })
}

fn payload_array<'a>(
    payload: &'a serde_json::Value,
    key: &str,
    intent_type: &str,
) -> ApiResult<&'a Vec<serde_json::Value>> {
    payload
        .get(key)
        .and_then(|v| v.as_array())
        .ok_or_else(|| ErrorInfo {
            status: 500,
            message: format!("Cannot compact malformed {intent_type}: missing array '{key}'"),
        })
}

fn ulid_digit(c: char) -> Option<u8> {
    match c {
        '0'..='9' => Some((c as u8) - b'0'),
        'A'..='Z' => Some(10 + (c as u8) - b'A'),
        'a'..='z' => Some(10 + (c as u8) - b'a'),
        _ => None,
    }
}

fn is_valid_ulid(s: &str) -> bool {
    s.len() == 26 && s.bytes().all(|b| b.is_ascii_alphanumeric())
}

fn decode_ulid_timestamp_ms(ulid: &str) -> Option<u64> {
    let mut value = 0u64;
    let mut chars = ulid.chars();
    for _ in 0..10 {
        let c = chars.next()?;
        let digit = ulid_digit(c)?;
        if digit >= 32 {
            return None;
        }
        value = value.checked_mul(32)?;
        value = value.checked_add(u64::from(digit))?;
    }
    Some(value)
}

impl MaterializedState {
    fn delete_item(&mut self, item_uuid: &str) {
        self.deleted_items.insert(item_uuid.to_owned());
        self.items.remove(item_uuid);
        self.availability
            .retain(|(_, entry_item_uuid), _| entry_item_uuid != item_uuid);
        self.item_order
            .retain(|(_, entry_item_uuid), _| entry_item_uuid != item_uuid);
    }

    fn delete_store(&mut self, store_uuid: &str) {
        self.deleted_stores.insert(store_uuid.to_owned());
        self.stores.remove(store_uuid);
        self.availability
            .retain(|(entry_store_uuid, _), _| entry_store_uuid != store_uuid);
        self.item_order
            .retain(|(entry_store_uuid, _), _| entry_store_uuid != store_uuid);
    }

    fn apply_item_state(&mut self, item_uuid: &str, state: &str, previous_state: &str) {
        let Some(item) = self.items.get_mut(item_uuid) else {
            return;
        };

        let current_state = item.state.clone();
        let is_concurrent = !previous_state.is_empty() && previous_state != current_state;
        if is_concurrent {
            let incoming_priority = match state {
                "DEACTIVATED" => Some(0),
                "ACTIVATED" => Some(1),
                "POSTPONED" => Some(2),
                _ => None,
            };
            let current_priority = match current_state.as_str() {
                "DEACTIVATED" => Some(0),
                "ACTIVATED" => Some(1),
                "POSTPONED" => Some(2),
                _ => None,
            };
            let (Some(incoming_priority), Some(current_priority)) =
                (incoming_priority, current_priority)
            else {
                return;
            };
            if incoming_priority >= current_priority {
                return;
            }
        }

        item.state = state.to_owned();
    }

    fn apply_intent(&mut self, intent: &Intent) -> ApiResult<()> {
        match intent.intent_type.as_str() {
            "add_item" => {
                let item_uuid = payload_str(&intent.payload, "item_uuid", "add_item")?;
                let name = payload_str(&intent.payload, "name", "add_item")?;
                if self.deleted_items.contains(item_uuid) || self.items.contains_key(item_uuid) {
                    return Ok(());
                }
                let created_at = decode_ulid_timestamp_ms(item_uuid).unwrap_or(0);
                self.items.insert(
                    item_uuid.to_owned(),
                    MaterializedItem {
                        name: name.to_owned(),
                        state: "ACTIVATED".to_owned(),
                        amount: 1,
                        created_at,
                    },
                );
            }
            "delete_item" => {
                let item_uuid = payload_str(&intent.payload, "item_uuid", "delete_item")?;
                self.delete_item(item_uuid);
            }
            "rename_item" => {
                let item_uuid = payload_str(&intent.payload, "item_uuid", "rename_item")?;
                let name = payload_str(&intent.payload, "name", "rename_item")?;
                if let Some(item) = self.items.get_mut(item_uuid) {
                    item.name = name.to_owned();
                }
            }
            "set_item_state" => {
                let item_uuid = payload_str(&intent.payload, "item_uuid", "set_item_state")?;
                let state = payload_str(&intent.payload, "state", "set_item_state")?;
                let previous_state =
                    payload_str(&intent.payload, "previous_state", "set_item_state")?;
                self.apply_item_state(item_uuid, state, previous_state);
            }
            "set_item_amount" => {
                let item_uuid = payload_str(&intent.payload, "item_uuid", "set_item_amount")?;
                let amount = payload_i64(&intent.payload, "amount", "set_item_amount")?;
                if let Some(item) = self.items.get_mut(item_uuid) {
                    item.amount = amount;
                }
            }
            "add_store" => {
                let store_uuid = payload_str(&intent.payload, "store_uuid", "add_store")?;
                let name = payload_str(&intent.payload, "name", "add_store")?;
                let specialty = payload_bool(&intent.payload, "specialty", "add_store")?;
                if self.deleted_stores.contains(store_uuid) || self.stores.contains_key(store_uuid)
                {
                    return Ok(());
                }
                self.stores.insert(
                    store_uuid.to_owned(),
                    MaterializedStore {
                        name: name.to_owned(),
                        specialty,
                    },
                );
            }
            "delete_store" => {
                let store_uuid = payload_str(&intent.payload, "store_uuid", "delete_store")?;
                self.delete_store(store_uuid);
            }
            "update_store" => {
                let store_uuid = payload_str(&intent.payload, "store_uuid", "update_store")?;
                let name = payload_str(&intent.payload, "name", "update_store")?;
                let specialty = payload_bool(&intent.payload, "specialty", "update_store")?;
                if let Some(store) = self.stores.get_mut(store_uuid) {
                    store.name = name.to_owned();
                    store.specialty = specialty;
                }
            }
            "set_availability" => {
                let store_uuid = payload_str(&intent.payload, "store_uuid", "set_availability")?;
                let item_uuid = payload_str(&intent.payload, "item_uuid", "set_availability")?;
                let available = payload_bool(&intent.payload, "available", "set_availability")?;
                if self.stores.contains_key(store_uuid) && self.items.contains_key(item_uuid) {
                    self.availability
                        .insert((store_uuid.to_owned(), item_uuid.to_owned()), available);
                }
            }
            "set_item_order" => {
                let store_uuid = payload_str(&intent.payload, "store_uuid", "set_item_order")?;
                let item_uuid = payload_str(&intent.payload, "item_uuid", "set_item_order")?;
                let sort_order = payload_i64(&intent.payload, "sort_order", "set_item_order")?;
                if self.stores.contains_key(store_uuid) && self.items.contains_key(item_uuid) {
                    self.item_order
                        .insert((store_uuid.to_owned(), item_uuid.to_owned()), sort_order);
                }
            }
            "bulk_add_items" => {
                for item in payload_array(&intent.payload, "items", "bulk_add_items")? {
                    let item_uuid = payload_str(item, "item_uuid", "bulk_add_items")?;
                    let name = payload_str(item, "name", "bulk_add_items")?;
                    if self.deleted_items.contains(item_uuid) || self.items.contains_key(item_uuid)
                    {
                        continue;
                    }
                    let created_at = decode_ulid_timestamp_ms(item_uuid).unwrap_or(0);
                    self.items.insert(
                        item_uuid.to_owned(),
                        MaterializedItem {
                            name: name.to_owned(),
                            state: "ACTIVATED".to_owned(),
                            amount: 1,
                            created_at,
                        },
                    );
                }
            }
            "bulk_set_state" => {
                let state = payload_str(&intent.payload, "state", "bulk_set_state")?;
                for item_uuid_value in
                    payload_array(&intent.payload, "item_uuids", "bulk_set_state")?
                {
                    let Some(item_uuid) = item_uuid_value.as_str() else {
                        return Err(ErrorInfo {
                            status: 500,
                            message: "Cannot compact malformed bulk_set_state: item_uuids must contain strings".into(),
                        });
                    };
                    self.apply_item_state(item_uuid, state, "");
                }
            }
            other => {
                return Err(ErrorInfo {
                    status: 500,
                    message: format!("Cannot compact unknown intent type '{other}'"),
                });
            }
        }

        Ok(())
    }

    fn apply_snapshot_value(&mut self, snapshot: &serde_json::Value) -> ApiResult<()> {
        match snapshot {
            serde_json::Value::Null => Ok(()),
            serde_json::Value::Array(intents) => {
                for value in intents {
                    let intent: Intent =
                        serde_json::from_value(value.clone()).map_err(json_error)?;
                    self.apply_intent(&intent)?;
                }
                Ok(())
            }
            serde_json::Value::Object(_) => {
                let snapshot: MaterializedSnapshot =
                    serde_json::from_value(snapshot.clone()).map_err(json_error)?;

                for item in snapshot.items {
                    self.items.insert(
                        item.item_uuid,
                        MaterializedItem {
                            name: item.name,
                            state: item.state,
                            amount: item.amount,
                            created_at: item.created_at,
                        },
                    );
                }

                for store in snapshot.stores {
                    self.stores.insert(
                        store.store_uuid,
                        MaterializedStore {
                            name: store.name,
                            specialty: store.specialty,
                        },
                    );
                }

                for entry in snapshot.availability {
                    if self.stores.contains_key(&entry.store_uuid)
                        && self.items.contains_key(&entry.item_uuid)
                    {
                        self.availability
                            .insert((entry.store_uuid, entry.item_uuid), entry.available);
                    }
                }

                for entry in snapshot.item_order {
                    if self.stores.contains_key(&entry.store_uuid)
                        && self.items.contains_key(&entry.item_uuid)
                    {
                        self.item_order
                            .insert((entry.store_uuid, entry.item_uuid), entry.sort_order);
                    }
                }

                for item_uuid in snapshot.deleted_items {
                    self.delete_item(&item_uuid);
                }

                for store_uuid in snapshot.deleted_stores {
                    self.delete_store(&store_uuid);
                }

                Ok(())
            }
            _ => Err(ErrorInfo {
                status: 500,
                message: "Cannot compact unsupported snapshot JSON shape".into(),
            }),
        }
    }

    fn to_snapshot_value(&self) -> ApiResult<serde_json::Value> {
        let mut items: Vec<SnapshotItem> = self
            .items
            .iter()
            .map(|(item_uuid, item)| SnapshotItem {
                item_uuid: item_uuid.clone(),
                name: item.name.clone(),
                state: item.state.clone(),
                amount: item.amount,
                created_at: item.created_at,
            })
            .collect();
        items.sort_by(|a, b| {
            a.created_at
                .cmp(&b.created_at)
                .then_with(|| a.item_uuid.cmp(&b.item_uuid))
        });

        let mut stores: Vec<SnapshotStore> = self
            .stores
            .iter()
            .map(|(store_uuid, store)| SnapshotStore {
                store_uuid: store_uuid.clone(),
                name: store.name.clone(),
                specialty: store.specialty,
            })
            .collect();
        stores.sort_by(|a, b| a.store_uuid.cmp(&b.store_uuid));

        let mut availability: Vec<SnapshotAvailability> = self
            .availability
            .iter()
            .filter(|(_, available)| !**available)
            .map(
                |((store_uuid, item_uuid), available)| SnapshotAvailability {
                    store_uuid: store_uuid.clone(),
                    item_uuid: item_uuid.clone(),
                    available: *available,
                },
            )
            .collect();
        availability.sort_by(|a, b| {
            a.store_uuid
                .cmp(&b.store_uuid)
                .then_with(|| a.item_uuid.cmp(&b.item_uuid))
        });

        let mut item_order: Vec<SnapshotItemOrder> = self
            .item_order
            .iter()
            .map(|((store_uuid, item_uuid), sort_order)| SnapshotItemOrder {
                store_uuid: store_uuid.clone(),
                item_uuid: item_uuid.clone(),
                sort_order: *sort_order,
            })
            .collect();
        item_order.sort_by(|a, b| {
            a.store_uuid
                .cmp(&b.store_uuid)
                .then_with(|| a.sort_order.cmp(&b.sort_order))
                .then_with(|| a.item_uuid.cmp(&b.item_uuid))
        });

        let mut deleted_items: Vec<String> = self.deleted_items.iter().cloned().collect();
        deleted_items.sort();

        let mut deleted_stores: Vec<String> = self.deleted_stores.iter().cloned().collect();
        deleted_stores.sort();

        serde_json::to_value(MaterializedSnapshot {
            items,
            stores,
            availability,
            item_order,
            deleted_items,
            deleted_stores,
        })
        .map_err(json_error)
    }
}

fn load_compactable_intents(
    conn: &Connection,
    list_id: &str,
    compact_to_ulid: &str,
) -> ApiResult<Vec<Intent>> {
    let mut stmt = conn
        .prepare(
            "SELECT server_ulid, device_ulid, device_id, list_id, intent_type, payload_json
             FROM intent
             WHERE list_id = ?1 AND server_ulid <= ?2
             ORDER BY server_ulid ASC",
        )
        .map_err(db_error)?;

    let rows = stmt
        .query_map(params![list_id, compact_to_ulid], |row| {
            let payload_str: String = row.get(5)?;
            Ok((
                row.get::<_, String>(0)?,
                row.get::<_, String>(1)?,
                row.get::<_, String>(2)?,
                row.get::<_, String>(3)?,
                row.get::<_, String>(4)?,
                payload_str,
            ))
        })
        .map_err(db_error)?;

    let mut intents = Vec::new();
    for row in rows {
        let (server_ulid, device_ulid, device_id, row_list_id, intent_type, payload_str) =
            row.map_err(db_error)?;
        intents.push(Intent {
            device_ulid,
            server_ulid: Some(server_ulid),
            device_id,
            list_id: row_list_id,
            intent_type,
            payload: serde_json::from_str(&payload_str).map_err(json_error)?,
        });
    }

    Ok(intents)
}

fn compact_list_internal(conn: &Connection, list_id: &str, force: bool) -> ApiResult<()> {
    let now = Timestamp::now();
    let stale_cutoff = now
        .checked_sub(jiff::Span::new().hours(STALE_DEVICE_HOURS))
        .unwrap_or(now);
    let stale_cutoff_s = stale_cutoff.strftime("%Y-%m-%dT%H:%M:%S.%fZ").to_string();

    let min_non_stale_hwm: Option<String> = conn
        .query_row(
            "SELECT MIN(last_synced_ulid)
             FROM device_sync
             WHERE list_id = ?1
               AND last_synced_ulid IS NOT NULL
               AND updated_at >= ?2",
            params![list_id, stale_cutoff_s],
            |row| row.get(0),
        )
        .map_err(db_error)?;

    let latest_ulid: Option<String> = conn
        .query_row(
            "SELECT MAX(server_ulid) FROM intent WHERE list_id = ?1",
            params![list_id],
            |row| row.get(0),
        )
        .map_err(db_error)?;

    let Some(latest_ulid) = latest_ulid else {
        mark_compaction_check(conn, list_id)?;
        return Ok(());
    };

    let compact_to_ulid = if force {
        latest_ulid
    } else {
        let backlog_count: usize = if let Some(ref hwm) = min_non_stale_hwm {
            conn.query_row(
                "SELECT COUNT(*) FROM intent WHERE list_id = ?1 AND server_ulid > ?2",
                params![list_id, hwm],
                |row| row.get(0),
            )
            .map_err(db_error)?
        } else {
            0
        };

        let retention_tail_cutoff = now
            .checked_sub(jiff::Span::new().hours(RETENTION_TAIL_HOURS))
            .unwrap_or(now)
            .strftime("%Y-%m-%dT%H:%M:%S.%fZ")
            .to_string();

        let newest_before_tail: Option<String> = conn
            .query_row(
                "SELECT MAX(server_ulid)
                 FROM intent
                 WHERE list_id = ?1 AND created_at < ?2",
                params![list_id, retention_tail_cutoff],
                |row| row.get(0),
            )
            .map_err(db_error)?;

        let should_compact =
            backlog_count >= COMPACTION_BACKLOG_THRESHOLD || newest_before_tail.is_some();
        if !should_compact {
            mark_compaction_check(conn, list_id)?;
            return Ok(());
        }

        match (min_non_stale_hwm, newest_before_tail) {
            (Some(a), Some(b)) => {
                if a < b {
                    a
                } else {
                    b
                }
            }
            (Some(a), None) => a,
            (None, Some(b)) => b,
            (None, None) => {
                mark_compaction_check(conn, list_id)?;
                return Ok(());
            }
        }
    };

    let intents = load_compactable_intents(conn, list_id, &compact_to_ulid)?;
    if intents.is_empty() {
        mark_compaction_check(conn, list_id)?;
        return Ok(());
    }

    let (_, existing_snapshot) = get_snapshot(conn, list_id)?;
    let mut state = MaterializedState::default();
    if let Some(snapshot) = existing_snapshot {
        state.apply_snapshot_value(&snapshot)?;
    }
    for intent in &intents {
        state.apply_intent(intent)?;
    }

    let snapshot_json = serde_json::to_string(&state.to_snapshot_value()?).map_err(json_error)?;
    let now_s = get_current_timestamp();

    conn.execute_batch("BEGIN").map_err(db_error)?;

    conn.execute(
        "INSERT INTO snapshot (list_id, snapshot_ulid, snapshot_json, created_at)
         VALUES (?1, ?2, ?3, ?4)
         ON CONFLICT(list_id) DO UPDATE SET
            snapshot_ulid = excluded.snapshot_ulid,
            snapshot_json = excluded.snapshot_json,
            created_at = excluded.created_at",
        params![list_id, compact_to_ulid, snapshot_json, now_s],
    )
    .map_err(db_error)?;

    conn.execute(
        "DELETE FROM intent
         WHERE list_id = ?1
           AND server_ulid <= ?2",
        params![list_id, compact_to_ulid],
    )
    .map_err(db_error)?;

    mark_compaction_check(conn, list_id)?;
    conn.execute_batch("COMMIT").map_err(db_error)?;

    Ok(())
}

fn compact_list(conn: &Connection, list_id: &str) -> ApiResult<()> {
    compact_list_internal(conn, list_id, false)
}

fn compact_list_unconditional(conn: &Connection, list_id: &str) -> ApiResult<()> {
    compact_list_internal(conn, list_id, true)
}

fn maybe_compact_list(conn: &Connection, list_id: &str) -> ApiResult<()> {
    if !should_run_compaction_check(conn, list_id)? {
        return Ok(());
    }
    compact_list(conn, list_id)
}

fn get_snapshot(
    conn: &Connection,
    list_id: &str,
) -> ApiResult<(Option<String>, Option<serde_json::Value>)> {
    let mut stmt = conn
        .prepare("SELECT snapshot_ulid, snapshot_json FROM snapshot WHERE list_id = ?1")
        .map_err(db_error)?;

    match stmt.query_row(params![list_id], |row| {
        Ok((row.get::<_, String>(0)?, row.get::<_, String>(1)?))
    }) {
        Ok((ulid, json_str)) => {
            let val: serde_json::Value = serde_json::from_str(&json_str).map_err(json_error)?;
            Ok((Some(ulid), Some(val)))
        }
        Err(rusqlite::Error::QueryReturnedNoRows) => Ok((None, None)),
        Err(e) => Err(db_error(e)),
    }
}

#[allow(clippy::too_many_lines)]
fn route_request(
    method: Method,
    path_parts: &[&str],
    request: &mut Request,
    state: &AppState,
    query_params: &HashMap<String, String>,
) -> ApiResult<Response<std::io::Cursor<Vec<u8>>>> {
    match (method, path_parts) {
        (Method::Post, ["list", list_uuid, "intents"]) => {
            let body = read_body(request).map_err(read_body_error)?;
            let intents: Vec<Intent> = serde_json::from_str(&body).map_err(|_| invalid_json())?;

            if intents.is_empty() {
                return json_response(&serde_json::json!({
                    "received": 0,
                    "server_ulids": []
                }));
            }

            let conn = lock_db(state)?;

            let mut stmt = conn
                .prepare("SELECT COUNT(*) FROM intent WHERE list_id = ?1")
                .map_err(db_error)?;
            let current_count: usize = stmt
                .query_row(params![list_uuid], |row| row.get(0))
                .map_err(db_error)?;

            let incoming_count = intents.len();
            let total_intents = current_count + incoming_count;

            if total_intents > MAX_TOTAL_INTENTS {
                return Err(ErrorInfo {
                    status: 413,
                    message: format!("Intent limit exceeded (max {} intents)", MAX_TOTAL_INTENTS),
                });
            }

            for intent in &intents {
                if intent.list_id != *list_uuid {
                    return Err(ErrorInfo {
                        status: 400,
                        message: format!(
                            "Intent list_id '{}' does not match URL list '{}'",
                            intent.list_id, list_uuid
                        ),
                    });
                }

                match intent.intent_type.as_str() {
                    "add_item" | "rename_item" => {
                        if let Some(name) = intent.payload.get("name").and_then(|v| v.as_str()) {
                            if name.chars().count() > MAX_ITEM_NAME_LEN {
                                return Err(ErrorInfo {
                                    status: 400,
                                    message: format!(
                                        "Item name too long (max {})",
                                        MAX_ITEM_NAME_LEN
                                    ),
                                });
                            }
                        }
                    }
                    "set_item_amount" => {
                        if let Some(amount) = intent.payload.get("amount").and_then(|v| v.as_i64())
                        {
                            if amount >= MAX_AMOUNT {
                                return Err(ErrorInfo {
                                    status: 400,
                                    message: format!("Amount too large (max {})", MAX_AMOUNT - 1),
                                });
                            }
                        }
                    }
                    "add_store" | "update_store" => {
                        if let Some(name) = intent.payload.get("name").and_then(|v| v.as_str()) {
                            if name.chars().count() > MAX_STORE_NAME_LEN {
                                return Err(ErrorInfo {
                                    status: 400,
                                    message: format!(
                                        "Store name too long (max {})",
                                        MAX_STORE_NAME_LEN
                                    ),
                                });
                            }
                        }
                    }
                    "bulk_add_items" => {
                        if let Some(items) = intent.payload.get("items").and_then(|v| v.as_array())
                        {
                            for item in items {
                                if let Some(name) = item.get("name").and_then(|v| v.as_str()) {
                                    if name.chars().count() > MAX_ITEM_NAME_LEN {
                                        return Err(ErrorInfo {
                                            status: 400,
                                            message: format!(
                                                "Item name too long in bulk_add_items (max {})",
                                                MAX_ITEM_NAME_LEN
                                            ),
                                        });
                                    }
                                }
                            }
                        }
                    }
                    _ => {}
                }
            }

            conn.execute_batch("BEGIN").map_err(db_error)?;
            let now = get_current_timestamp();
            let mut server_ulids = Vec::with_capacity(intents.len());

            for intent in &intents {
                let server_ulid = generate_ulid();
                let payload_json = serde_json::to_string(&intent.payload).map_err(json_error)?;

                conn.execute(
                    "INSERT INTO intent (list_id, server_ulid, device_ulid, device_id, intent_type, payload_json, created_at)
                     VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)",
                    params![
                        list_uuid,
                        server_ulid,
                        intent.device_ulid,
                        intent.device_id,
                        intent.intent_type,
                        payload_json,
                        now,
                    ],
                )
                .map_err(db_error)?;

                server_ulids.push(server_ulid);
            }

            if let Some(last_ulid) = server_ulids.last() {
                track_device_sync(&conn, list_uuid, &intents[0].device_id, last_ulid)?;
            }

            conn.execute_batch("COMMIT").map_err(db_error)?;

            json_response(&serde_json::json!({
                "received": server_ulids.len(),
                "server_ulids": server_ulids
            }))
        }

        (Method::Get, ["list", list_uuid, "intents"]) => {
            let conn = lock_db(state)?;
            maybe_compact_list(&conn, list_uuid)?;

            let (snapshot_ulid, snapshot) = get_snapshot(&conn, list_uuid)?;
            let intents = if let Some(since) = query_params.get("since") {
                if let Some(ref snap_ulid) = snapshot_ulid {
                    if since.as_str() < snap_ulid.as_str() {
                        let (_, snapshot) = get_snapshot(&conn, list_uuid)?;
                        let subsequent = load_intents_since(&conn, list_uuid, snap_ulid)?;
                        return json_response(&SnapshotResponse {
                            snapshot_ulid,
                            snapshot,
                            intents: subsequent,
                        });
                    }
                }
                load_intents_since(&conn, list_uuid, since)?
            } else {
                if snapshot_ulid.is_some() {
                    let subsequent = load_intents_since(
                        &conn,
                        list_uuid,
                        snapshot_ulid.as_deref().unwrap_or(""),
                    )?;
                    return json_response(&SnapshotResponse {
                        snapshot_ulid,
                        snapshot,
                        intents: subsequent,
                    });
                }
                load_all_intents(&conn, list_uuid)?
            };

            if snapshot_ulid.is_none() && intents.is_empty() {
                return Err(ErrorInfo {
                    status: 404,
                    message: format!("No list '{}'", list_uuid),
                });
            }

            json_response(&intents)
        }

        (Method::Get, ["list", list_uuid, "snapshot"]) => {
            let conn = lock_db(state)?;
            let (snapshot_ulid, snapshot) = get_snapshot(&conn, list_uuid)?;
            let since = snapshot_ulid.as_deref().unwrap_or("");
            let intents = load_intents_since(&conn, list_uuid, since)?;

            json_response(&SnapshotResponse {
                snapshot_ulid,
                snapshot,
                intents,
            })
        }

        (Method::Get, ["list", list_uuid, "debug"]) => {
            let conn = lock_db(state)?;
            let intents = load_all_intents(&conn, list_uuid)?;
            let (snapshot_ulid, _) = get_snapshot(&conn, list_uuid)?;

            let mut dev_stmt = conn
                .prepare("SELECT device_id, last_synced_ulid FROM device_sync WHERE list_id = ?1")
                .map_err(db_error)?;

            let dev_rows = dev_stmt
                .query_map(params![list_uuid], |row| {
                    Ok(DeviceInfo {
                        device_id: row.get(0)?,
                        last_synced_ulid: row.get(1)?,
                    })
                })
                .map_err(db_error)?;

            let devices: Vec<DeviceInfo> = dev_rows.collect::<Result<_, _>>().map_err(db_error)?;

            json_response(&DebugResponse {
                intent_count: intents.len(),
                snapshot_ulid,
                devices,
                intents,
            })
        }

        (Method::Get, [list_uuid]) if is_valid_ulid(list_uuid) => {
            match &state.public_url {
                None => Err(ErrorInfo { status: 404, message: "Not found".into() }),
                Some(base) => {
                    let base = base.trim_end_matches('/');
                    let deep_link = if base.starts_with("https://") {
                        format!("qs3ks://{}/{}", &base[8..], list_uuid)
                    } else if base.starts_with("http://") {
                        format!("qs3k://{}/{}", &base[7..], list_uuid)
                    } else {
                        return Err(ErrorInfo { status: 500, message: "Invalid public URL".into() });
                    };
                    let mut response = Response::from_string("").with_status_code(302);
                    if let Some(h) = safe_header(b"Location", deep_link.as_bytes()) {
                        response = response.with_header(h);
                    }
                    Ok(response)
                }
            }
        }

        _ => Err(ErrorInfo {
            status: 404,
            message: "Not found".into(),
        }),
    }
}

fn handle_request(mut request: Request, state: &AppState) {
    let method = request.method().clone();
    let url = request.url().to_string();

    println!(
        "{} - {} {}",
        Timestamp::now().strftime("%Y-%m-%d %H:%M:%S%.3f"),
        method,
        url
    );

    let response = if method == Method::Options {
        cors_response()
    } else {
        let url_path = if let Some(proto_pos) = url.find("://") {
            if let Some(path_pos) = url[proto_pos + 3..].find('/') {
                &url[proto_pos + 3 + path_pos..]
            } else {
                "/"
            }
        } else {
            &url
        };

        let (path, query_map) = if let Some(q_pos) = url_path.find('?') {
            let path = &url_path[..q_pos];
            let q = &url_path[q_pos + 1..];
            (path, parse_query_params(q))
        } else {
            (url_path, HashMap::new())
        };

        let path_parts: Vec<&str> = path
            .trim_start_matches('/')
            .trim_end_matches('/')
            .split('/')
            .collect();

        if path_parts.is_empty() || path_parts == [""] {
            error_response("Invalid path", 404)
        } else {
            match route_request(method, &path_parts, &mut request, state, &query_map) {
                Ok(resp) => resp,
                Err(err) => error_response(&err.message, err.status),
            }
        }
    };

    if let Err(e) = request.respond(response) {
        eprintln!("Error sending response: {e}");
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    type TestResult = Result<(), Box<dyn Error + Send + Sync>>;

    fn test_ulid(n: u64) -> String {
        format!("{n:026}")
    }

    fn test_error(message: String) -> Box<dyn Error + Send + Sync> {
        Box::new(std::io::Error::new(std::io::ErrorKind::Other, message))
    }

    fn insert_test_intent(
        conn: &Connection,
        list_id: &str,
        server_ulid: &str,
        intent_type: &str,
        payload: serde_json::Value,
    ) -> TestResult {
        conn.execute(
            "INSERT INTO intent (list_id, server_ulid, device_ulid, device_id, intent_type, payload_json, created_at)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)",
            params![
                list_id,
                server_ulid,
                test_ulid(9_000_000),
                "test-device",
                intent_type,
                serde_json::to_string(&payload)?,
                get_current_timestamp(),
            ],
        )?;
        Ok(())
    }

    fn read_snapshot_json(conn: &Connection, list_id: &str) -> TestResultValue {
        let snapshot_json: String = conn.query_row(
            "SELECT snapshot_json FROM snapshot WHERE list_id = ?1",
            params![list_id],
            |row| row.get(0),
        )?;
        Ok(serde_json::from_str(&snapshot_json)?)
    }

    type TestResultValue = Result<serde_json::Value, Box<dyn Error + Send + Sync>>;

    #[test]
    fn forced_compaction_keeps_only_current_item_order() -> TestResult {
        let conn = Connection::open_in_memory()?;
        create_tables(&conn)?;
        migrate_db(&conn)?;

        let list_id = "list-order";
        let store_uuid = test_ulid(100);
        let a = test_ulid(101);
        let b = test_ulid(102);
        let c = test_ulid(103);
        let d = test_ulid(104);

        insert_test_intent(
            &conn,
            list_id,
            &test_ulid(1),
            "add_store",
            serde_json::json!({ "store_uuid": store_uuid, "name": "Main", "specialty": false }),
        )?;
        insert_test_intent(
            &conn,
            list_id,
            &test_ulid(2),
            "bulk_add_items",
            serde_json::json!({
                "items": [
                    { "item_uuid": a, "name": "a" },
                    { "item_uuid": b, "name": "b" },
                    { "item_uuid": c, "name": "c" },
                    { "item_uuid": d, "name": "d" }
                ]
            }),
        )?;

        for (idx, item_uuid) in [a.as_str(), b.as_str(), c.as_str(), d.as_str()]
            .into_iter()
            .enumerate()
        {
            insert_test_intent(
                &conn,
                list_id,
                &test_ulid(10 + idx as u64),
                "set_item_order",
                serde_json::json!({
                    "store_uuid": store_uuid,
                    "item_uuid": item_uuid,
                    "sort_order": idx as i64 + 1
                }),
            )?;
        }

        for (idx, item_uuid) in [b.as_str(), a.as_str(), d.as_str(), c.as_str()]
            .into_iter()
            .enumerate()
        {
            insert_test_intent(
                &conn,
                list_id,
                &test_ulid(20 + idx as u64),
                "set_item_order",
                serde_json::json!({
                    "store_uuid": store_uuid,
                    "item_uuid": item_uuid,
                    "sort_order": idx as i64 + 1
                }),
            )?;
        }

        compact_list_unconditional(&conn, list_id).map_err(|e| test_error(e.message))?;

        let snapshot = read_snapshot_json(&conn, list_id)?;
        let order = snapshot
            .get("item_order")
            .and_then(|v| v.as_array())
            .ok_or_else(|| test_error("snapshot.item_order missing".into()))?;

        let compacted_order: Vec<String> = order
            .iter()
            .filter_map(|entry| {
                entry
                    .get("item_uuid")
                    .and_then(|v| v.as_str())
                    .map(ToOwned::to_owned)
            })
            .collect();

        assert_eq!(
            compacted_order,
            vec![b.clone(), a.clone(), d.clone(), c.clone()]
        );
        assert_eq!(order.len(), 4);

        let remaining_intents: usize = conn.query_row(
            "SELECT COUNT(*) FROM intent WHERE list_id = ?1",
            params![list_id],
            |row| row.get(0),
        )?;
        assert_eq!(remaining_intents, 0);
        Ok(())
    }

    #[test]
    fn forced_compaction_merges_existing_snapshot() -> TestResult {
        let conn = Connection::open_in_memory()?;
        create_tables(&conn)?;
        migrate_db(&conn)?;

        let list_id = "list-merge";
        let a = test_ulid(201);
        let b = test_ulid(202);

        insert_test_intent(
            &conn,
            list_id,
            &test_ulid(1),
            "add_item",
            serde_json::json!({ "item_uuid": a, "name": "a" }),
        )?;

        compact_list_unconditional(&conn, list_id).map_err(|e| test_error(e.message))?;

        insert_test_intent(
            &conn,
            list_id,
            &test_ulid(2),
            "rename_item",
            serde_json::json!({ "item_uuid": a, "name": "a2" }),
        )?;
        insert_test_intent(
            &conn,
            list_id,
            &test_ulid(3),
            "add_item",
            serde_json::json!({ "item_uuid": b, "name": "b" }),
        )?;

        compact_list_unconditional(&conn, list_id).map_err(|e| test_error(e.message))?;

        let snapshot = read_snapshot_json(&conn, list_id)?;
        let items = snapshot
            .get("items")
            .and_then(|v| v.as_array())
            .ok_or_else(|| test_error("snapshot.items missing".into()))?;

        assert!(snapshot.is_object());
        assert_eq!(items.len(), 2);
        assert!(items.iter().any(|item| {
            item.get("item_uuid").and_then(|v| v.as_str()) == Some(a.as_str())
                && item.get("name").and_then(|v| v.as_str()) == Some("a2")
        }));
        assert!(items.iter().any(|item| {
            item.get("item_uuid").and_then(|v| v.as_str()) == Some(b.as_str())
                && item.get("name").and_then(|v| v.as_str()) == Some("b")
        }));
        Ok(())
    }

    #[test]
    fn forced_compaction_migrates_legacy_snapshot_log() -> TestResult {
        let conn = Connection::open_in_memory()?;
        create_tables(&conn)?;
        migrate_db(&conn)?;

        let list_id = "list-legacy";
        let a = test_ulid(301);
        let b = test_ulid(302);
        let legacy_snapshot = serde_json::json!([
            {
                "device_ulid": test_ulid(7001),
                "server_ulid": test_ulid(1),
                "device_id": "legacy-device",
                "list_id": list_id,
                "type": "add_item",
                "payload": { "item_uuid": a, "name": "a" }
            }
        ]);

        conn.execute(
            "INSERT INTO snapshot (list_id, snapshot_ulid, snapshot_json, created_at)
             VALUES (?1, ?2, ?3, ?4)",
            params![
                list_id,
                test_ulid(1),
                serde_json::to_string(&legacy_snapshot)?,
                get_current_timestamp(),
            ],
        )?;

        insert_test_intent(
            &conn,
            list_id,
            &test_ulid(2),
            "rename_item",
            serde_json::json!({ "item_uuid": a, "name": "a3" }),
        )?;
        insert_test_intent(
            &conn,
            list_id,
            &test_ulid(3),
            "add_item",
            serde_json::json!({ "item_uuid": b, "name": "b" }),
        )?;

        compact_list_unconditional(&conn, list_id).map_err(|e| test_error(e.message))?;

        let snapshot = read_snapshot_json(&conn, list_id)?;
        let items = snapshot
            .get("items")
            .and_then(|v| v.as_array())
            .ok_or_else(|| test_error("snapshot.items missing".into()))?;

        assert!(snapshot.is_object());
        assert_eq!(items.len(), 2);
        assert!(items.iter().any(|item| {
            item.get("item_uuid").and_then(|v| v.as_str()) == Some(a.as_str())
                && item.get("name").and_then(|v| v.as_str()) == Some("a3")
        }));
        assert!(items.iter().any(|item| {
            item.get("item_uuid").and_then(|v| v.as_str()) == Some(b.as_str())
                && item.get("name").and_then(|v| v.as_str()) == Some("b")
        }));
        Ok(())
    }
}

fn main() -> Result<(), Box<dyn Error + Send + Sync>> {
    std::fs::create_dir_all("db")?;

    let conn = Connection::open("db/qs3k-api.db")?;
    init_db(&conn)?;
    migrate_db(&conn)?;

    if std::env::args().any(|a| a == "--compact") {
        let mut stmt = conn.prepare(
            "SELECT DISTINCT list_id FROM intent UNION SELECT DISTINCT list_id FROM snapshot",
        )?;
        let list_ids: Vec<String> = stmt
            .query_map([], |row| row.get(0))?
            .collect::<Result<Vec<_>, _>>()?;
        for list_id in &list_ids {
            match compact_list_unconditional(&conn, list_id) {
                Ok(()) => println!("compacted list {list_id}"),
                Err(e) => eprintln!("error compacting list {list_id}: {}", e.message),
            }
        }
        println!("compaction done, {} lists processed", list_ids.len());
        return Ok(());
    }

    let public_url = std::env::var("QS3K_PUBLIC_URL").ok();

    let state = AppState {
        db: Arc::new(Mutex::new(conn)),
        public_url,
    };

    let server = Server::http("127.0.0.1:50484").map_err(|e| format!("{e}"))?;
    println!("qs3k-api server running on http://127.0.0.1:50484");

    for request in server.incoming_requests() {
        handle_request(request, &state);
    }

    Ok(())
}
