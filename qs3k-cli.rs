use serde::{Deserialize, Serialize};
use std::{
    collections::HashMap,
    fmt,
    fs,
    path::PathBuf,
    process,
    time::{SystemTime, UNIX_EPOCH},
};
use unicode_normalization::UnicodeNormalization;

// ── Config ────────────────────────────────────────────────────────────────────

#[derive(Debug, Default, Serialize, Deserialize)]
struct Config {
    server_url: Option<String>,
    default_list_id: Option<String>,
    device_id: Option<String>,
}

fn config_dir() -> PathBuf {
    let base = std::env::var("XDG_CONFIG_HOME")
        .map(PathBuf::from)
        .unwrap_or_else(|_| {
            let home = std::env::var("HOME").unwrap_or_else(|_| ".".into());
            PathBuf::from(home).join(".config")
        });
    base.join("qs3k")
}

fn config_path() -> PathBuf {
    config_dir().join("config.toml")
}

fn load_config() -> Config {
    let path = config_path();
    let Ok(text) = fs::read_to_string(&path) else {
        return Config::default();
    };
    toml_parse(&text).unwrap_or_default()
}

fn save_config(cfg: &Config) {
    let dir = config_dir();
    fs::create_dir_all(&dir).unwrap_or(());
    let text = toml_serialize(cfg);
    fs::write(config_path(), text).unwrap_or(());
}

// Minimal TOML serialiser/parser — only handles our flat string-value config.

fn toml_serialize(cfg: &Config) -> String {
    let mut out = String::new();
    if let Some(v) = &cfg.server_url {
        out.push_str(&format!("server_url = {}\n", toml_quote(v)));
    }
    if let Some(v) = &cfg.default_list_id {
        out.push_str(&format!("default_list_id = {}\n", toml_quote(v)));
    }
    if let Some(v) = &cfg.device_id {
        out.push_str(&format!("device_id = {}\n", toml_quote(v)));
    }
    out
}

fn toml_quote(s: &str) -> String {
    format!("\"{}\"", s.replace('\\', "\\\\").replace('"', "\\\""))
}

fn toml_parse(text: &str) -> Option<Config> {
    let mut map: HashMap<&str, &str> = HashMap::new();
    for line in text.lines() {
        let line = line.trim();
        if line.is_empty() || line.starts_with('#') {
            continue;
        }
        let (k, v) = line.split_once('=')?;
        let k = k.trim();
        let v = v.trim();
        // strip surrounding quotes
        let v = if v.starts_with('"') && v.ends_with('"') && v.len() >= 2 {
            &v[1..v.len() - 1]
        } else {
            v
        };
        map.insert(k, v);
    }
    Some(Config {
        server_url: map.get("server_url").map(|s| s.to_string()),
        default_list_id: map.get("default_list_id").map(|s| s.to_string()),
        device_id: map.get("device_id").map(|s| s.to_string()),
    })
}

// ── Device ID ─────────────────────────────────────────────────────────────────

fn get_or_create_device_id(cfg: &mut Config) -> String {
    if let Some(id) = &cfg.device_id {
        return id.clone();
    }
    let id = format!("cli-{}", generate_ulid());
    cfg.device_id = Some(id.clone());
    save_config(cfg);
    id
}

// ── ULID ─────────────────────────────────────────────────────────────────────

fn generate_ulid() -> String {
    let now_ms = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64;

    // Simple xorshift-based random (no dep needed, non-crypto)
    static COUNTER: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);
    let ctr = COUNTER.fetch_add(1, std::sync::atomic::Ordering::Relaxed);

    let pid = std::process::id() as u64;
    let mut r: u64 = now_ms ^ (pid << 32) ^ ctr.wrapping_mul(0x9E3779B97F4A7C15);
    let mut rand_bytes = [0u8; 10];
    for b in &mut rand_bytes {
        r ^= r << 13;
        r ^= r >> 7;
        r ^= r << 17;
        *b = r as u8;
    }

    const C: &[u8; 32] = b"0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    let mut out = [0u8; 26];
    let ts = now_ms & 0xFFFF_FFFF_FFFF;
    for i in 0..10 {
        out[9 - i] = C[((ts >> (i * 5)) & 0x1F) as usize];
    }
    let rv: u128 = rand_bytes.iter().fold(0u128, |a, &b| (a << 8) | b as u128);
    for i in 0..16 {
        out[25 - i] = C[((rv >> (i * 5)) & 0x1F) as usize];
    }
    String::from_utf8_lossy(&out).into_owned()
}

// ── Unicode folding ───────────────────────────────────────────────────────────

/// NFD-decompose, strip combining characters, then lowercase.
fn fold(s: &str) -> String {
    s.nfd()
        .filter(|c| !unicode_normalization::char::is_combining_mark(*c))
        .flat_map(|c| c.to_lowercase())
        .collect()
}

// ── API types ─────────────────────────────────────────────────────────────────

#[derive(Serialize)]
struct IntentEnvelope<'a> {
    device_ulid: String,
    server_ulid: Option<String>,
    device_id: &'a str,
    list_id: &'a str,
    #[serde(rename = "type")]
    intent_type: &'a str,
    payload: serde_json::Value,
}

#[derive(Deserialize)]
#[allow(dead_code)]
struct SnapshotResponse {
    snapshot_ulid: Option<String>,
    snapshot: Option<serde_json::Value>,
    intents: Vec<RemoteIntent>,
}

#[derive(Deserialize)]
struct RemoteIntent {
    #[serde(rename = "type")]
    intent_type: String,
    payload: serde_json::Value,
}

#[derive(Default)]
struct ListState {
    /// item_uuid → name
    items: HashMap<String, String>,
    /// item_uuid → state ("ACTIVATED" | "DEACTIVATED" | "POSTPONED")
    states: HashMap<String, String>,
}

impl ListState {
    fn apply_snapshot(&mut self, snap: &serde_json::Value) {
        if let Some(items) = snap.get("items").and_then(|v| v.as_array()) {
            for item in items {
                if let (Some(uuid), Some(name), Some(state)) = (
                    item.get("item_uuid").and_then(|v| v.as_str()),
                    item.get("name").and_then(|v| v.as_str()),
                    item.get("state").and_then(|v| v.as_str()),
                ) {
                    self.items.insert(uuid.to_string(), name.to_string());
                    if state != "ACTIVATED" {
                        self.states.insert(uuid.to_string(), state.to_string());
                    }
                }
            }
        }
        if let Some(deleted) = snap.get("deleted_items").and_then(|v| v.as_array()) {
            for uuid in deleted.iter().filter_map(|v| v.as_str()) {
                self.items.remove(uuid);
                self.states.remove(uuid);
            }
        }
    }

    fn apply_intent(&mut self, intent: &RemoteIntent) {
        let p = &intent.payload;
        match intent.intent_type.as_str() {
            "add_item" | "bulk_add_items" => {
                let items_to_add: Vec<(String, String)> = if intent.intent_type == "add_item" {
                    match (
                        p.get("item_uuid").and_then(|v| v.as_str()),
                        p.get("name").and_then(|v| v.as_str()),
                    ) {
                        (Some(u), Some(n)) => vec![(u.to_string(), n.to_string())],
                        _ => vec![],
                    }
                } else {
                    p.get("items")
                        .and_then(|v| v.as_array())
                        .map(|arr| {
                            arr.iter()
                                .filter_map(|item| {
                                    Some((
                                        item.get("item_uuid")?.as_str()?.to_string(),
                                        item.get("name")?.as_str()?.to_string(),
                                    ))
                                })
                                .collect()
                        })
                        .unwrap_or_default()
                };
                for (uuid, name) in items_to_add {
                    self.items.entry(uuid).or_insert(name);
                }
            }
            "delete_item" => {
                if let Some(uuid) = p.get("item_uuid").and_then(|v| v.as_str()) {
                    self.items.remove(uuid);
                    self.states.remove(uuid);
                }
            }
            "rename_item" => {
                if let (Some(uuid), Some(name)) = (
                    p.get("item_uuid").and_then(|v| v.as_str()),
                    p.get("name").and_then(|v| v.as_str()),
                ) {
                    if self.items.contains_key(uuid) {
                        self.items.insert(uuid.to_string(), name.to_string());
                    }
                }
            }
            "set_item_state" => {
                if let (Some(uuid), Some(state)) = (
                    p.get("item_uuid").and_then(|v| v.as_str()),
                    p.get("state").and_then(|v| v.as_str()),
                ) {
                    if self.items.contains_key(uuid) {
                        if state == "ACTIVATED" {
                            self.states.remove(uuid);
                        } else {
                            self.states.insert(uuid.to_string(), state.to_string());
                        }
                    }
                }
            }
            "bulk_set_state" => {
                if let (Some(state), Some(uuids)) = (
                    p.get("state").and_then(|v| v.as_str()),
                    p.get("item_uuids").and_then(|v| v.as_array()),
                ) {
                    for uuid in uuids.iter().filter_map(|v| v.as_str()) {
                        if self.items.contains_key(uuid) {
                            if state == "ACTIVATED" {
                                self.states.remove(uuid);
                            } else {
                                self.states.insert(uuid.to_string(), state.to_string());
                            }
                        }
                    }
                }
            }
            _ => {}
        }
    }

    fn state_of(&self, uuid: &str) -> &str {
        self.states.get(uuid).map(|s| s.as_str()).unwrap_or("ACTIVATED")
    }

    /// Find an existing item by folded name. Returns (uuid, current_state).
    fn find_by_name(&self, needle: &str) -> Option<(String, &str)> {
        let needle_folded = fold(needle);
        for (uuid, name) in &self.items {
            if fold(name) == needle_folded {
                return Some((uuid.clone(), self.state_of(uuid)));
            }
        }
        None
    }
}

// ── HTTP helpers ──────────────────────────────────────────────────────────────

fn fetch_list_state(server_url: &str, list_id: &str) -> Result<ListState, String> {
    let url = format!("{}/list/{}/snapshot", server_url.trim_end_matches('/'), list_id);
    let resp = ureq::get(&url)
        .call()
        .map_err(|e| format!("GET {url}: {e}"))?;

    let body = resp
        .into_body()
        .read_to_string()
        .map_err(|e| format!("reading response: {e}"))?;

    let sr: SnapshotResponse =
        serde_json::from_str(&body).map_err(|e| format!("parsing response: {e}"))?;

    let mut state = ListState::default();
    if let Some(snap) = &sr.snapshot {
        state.apply_snapshot(snap);
    }
    for intent in &sr.intents {
        state.apply_intent(intent);
    }
    if std::env::var("QS3K_DEBUG").is_ok() {
        eprintln!("[debug] {} items in state after apply:", state.items.len());
        let mut names: Vec<&str> = state.items.values().map(|s| s.as_str()).collect();
        names.sort();
        for n in names { eprintln!("  {:?}", n); }
    }
    Ok(state)
}

fn post_intents(
    server_url: &str,
    list_id: &str,
    device_id: &str,
    intents: &[(&str, serde_json::Value)],
) -> Result<(), String> {
    let envelopes: Vec<IntentEnvelope> = intents
        .iter()
        .map(|(intent_type, payload)| IntentEnvelope {
            device_ulid: generate_ulid(),
            server_ulid: None,
            device_id,
            list_id,
            intent_type,
            payload: payload.clone(),
        })
        .collect();

    let url = format!("{}/list/{}/intents", server_url.trim_end_matches('/'), list_id);
    let body_str =
        serde_json::to_string(&envelopes).map_err(|e| format!("serialising intents: {e}"))?;

    let resp = ureq::post(&url)
        .header("Content-Type", "application/json")
        .send(body_str.as_bytes())
        .map_err(|e| format!("POST {url}: {e}"))?;

    let status = resp.status();
    if !status.is_success() {
        let body = resp.into_body().read_to_string().unwrap_or_default();
        return Err(format!("server returned {status}: {body}"));
    }
    Ok(())
}

// ── CLI ───────────────────────────────────────────────────────────────────────

#[derive(Debug)]
enum CliError {
    Usage(String),
    Fatal(String),
}

impl fmt::Display for CliError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            CliError::Usage(s) => write!(f, "{s}"),
            CliError::Fatal(s) => write!(f, "{s}"),
        }
    }
}

fn usage() -> &'static str {
    "Usage:
  qs3k [--server <URL>] [--list <LIST_ID>] add <item> [<item> ...]
  qs3k [--server <URL>] [--list <LIST_ID>] done <item> [<item> ...]
  qs3k [--server <URL>] [--list <LIST_ID>] search <query>
  qs3k [--server <URL>] [--list <LIST_ID>] show
  qs3k completions bash
  qs3k completions zsh
  qs3k config set-server <URL>
  qs3k config set-list <LIST_ID>
  qs3k config show

add: activates the item if it already exists (by name), creates it otherwise.
search: shows all items matching query (any state). ✓ active  + inactive  ~ postponed
--list and --server override the configured defaults for this invocation only."
}

fn run() -> Result<(), CliError> {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let mut args = args.as_slice();

    if args.is_empty() {
        return Err(CliError::Usage(usage().to_string()));
    }

    // ── completions subcommand (no server/list needed) ─────────────────────
    if args[0] == "completions" {
        return cmd_completions(&args[1..]);
    }

    // ── config subcommand (no server/list needed) ──────────────────────────
    if args[0] == "config" {
        return cmd_config(&args[1..]);
    }

    // ── parse optional --server / --list flags ────────────────────────────
    let mut list_override: Option<String> = None;
    let mut server_override: Option<String> = None;
    loop {
        if args.len() >= 2 && args[0] == "--list" {
            list_override = Some(args[1].clone());
            args = &args[2..];
        } else if args.len() >= 2 && args[0] == "--server" {
            server_override = Some(args[1].clone());
            args = &args[2..];
        } else {
            break;
        }
    }

    if args.is_empty() {
        return Err(CliError::Usage(usage().to_string()));
    }

    let subcommand = &args[0];
    let rest = &args[1..];

    match subcommand.as_str() {
        "add" => cmd_add(rest, server_override, list_override),
        "done" => cmd_done(rest, server_override, list_override),
        "search" => cmd_search(rest, server_override, list_override),
        "--complete" => cmd_complete(rest, server_override, list_override),
        "show" => cmd_show(server_override, list_override),
        other => Err(CliError::Usage(format!("unknown command '{other}'\n\n{}", usage()))),
    }
}

fn cmd_config(args: &[String]) -> Result<(), CliError> {
    let mut cfg = load_config();
    match args {
        [cmd, url] if cmd == "set-server" => {
            cfg.server_url = Some(url.clone());
            save_config(&cfg);
            println!("server_url set to {url}");
        }
        [cmd, list_id] if cmd == "set-list" => {
            cfg.default_list_id = Some(list_id.clone());
            save_config(&cfg);
            println!("default_list_id set to {list_id}");
        }
        [cmd] if cmd == "show" => {
            println!("config file : {}", config_path().display());
            println!("server_url  : {}", cfg.server_url.as_deref().unwrap_or("(not set)"));
            println!("default_list: {}", cfg.default_list_id.as_deref().unwrap_or("(not set)"));
            println!("device_id   : {}", cfg.device_id.as_deref().unwrap_or("(not generated yet)"));
        }
        _ => {
            return Err(CliError::Usage(
                "Usage:\n  qs3k config set-server <URL>\n  qs3k config set-list <LIST_ID>\n  qs3k config show".into(),
            ));
        }
    }
    Ok(())
}

fn state_symbol(state: &str) -> &'static str {
    match state {
        "ACTIVATED"   => "\u{2713}", // ✓
        "POSTPONED"   => "~",
        _             => "+",        // DEACTIVATED or unknown
    }
}

fn cmd_search(args: &[String], server_override: Option<String>, list_override: Option<String>) -> Result<(), CliError> {
    let query = args.join(" ");
    let cfg = load_config();

    let server_url = server_override
        .or_else(|| cfg.server_url.clone())
        .ok_or_else(|| CliError::Fatal("no server URL configured — run: qs3k config set-server <URL>".into()))?;

    let list_id = list_override
        .or_else(|| cfg.default_list_id.clone())
        .ok_or_else(|| CliError::Fatal(
            "no list specified — use --list <LIST_ID> or run: qs3k config set-list <LIST_ID>".into(),
        ))?;

    let state = fetch_list_state(&server_url, &list_id)
        .map_err(|e| CliError::Fatal(format!("failed to fetch list: {e}")))?;

    let query_folded = fold(&query);
    let mut results: Vec<(String, String)> = state
        .items
        .iter()
        .filter(|(_, name)| query_folded.is_empty() || fold(name).contains(&query_folded))
        .map(|(uuid, name)| (name.clone(), state.state_of(uuid).to_string()))
        .collect();

    results.sort_unstable_by(|a, b| fold(&a.0).cmp(&fold(&b.0)));

    if results.is_empty() {
        println!("(no matches)");
    } else {
        for (name, item_state) in &results {
            println!("{} {name}", state_symbol(item_state));
        }
    }
    Ok(())
}

/// Internal verb used by shell completion scripts.
/// Usage: qs3k [--server X] [--list Y] --complete <verb> <prefix>
/// Prints one candidate per line, prefixed with state symbol.
fn cmd_complete(args: &[String], server_override: Option<String>, list_override: Option<String>) -> Result<(), CliError> {
    // args: [verb, prefix]  (prefix may be absent)
    let prefix = args.get(1).map(|s| s.as_str()).unwrap_or("");
    // For now all verbs complete against all items; callers can filter by symbol if desired.
    cmd_search(&[prefix.to_string()], server_override, list_override)
}

fn cmd_completions(args: &[String]) -> Result<(), CliError> {
    let shell = args.first().map(|s| s.as_str()).unwrap_or("");
    match shell {
        "bash" => { print!("{}", completion_script_bash()); Ok(()) }
        "zsh"  => { print!("{}", completion_script_zsh());  Ok(()) }
        _ => Err(CliError::Usage(
            "Usage: qs3k completions <shell>\nSupported shells: bash, zsh".into(),
        )),
    }
}

// ── Shell completion scripts ────────────────────────────────────────────────────
//
// To add a new shell:
//   1. Add a fn completion_script_<shell>() -> &'static str below.
//   2. Add a match arm in cmd_completions().
//   3. Document in usage().
//
// The scripts call:  qs3k [flags already on cmdline] --complete <verb> <prefix>
// and split on the first space to separate the state symbol from the item name.

fn completion_script_bash() -> &'static str {
    // To activate: eval "$(qs3k completions bash)"
    r#"_qs3k_complete() {
    local cur prev words cword flags verb
    _init_completion || return

    # Collect any --server/--list flags already typed
    flags=""
    verb=""
    local i
    for ((i=1; i<cword; i++)); do
        case "${words[i]}" in
            --server|--list)
                flags="$flags ${words[i]} ${words[i+1]}"
                ((i++))
                ;;
            add|done|search|show)
                verb="${words[i]}"
                ;;
        esac
    done

    # Complete the verb itself
    if [[ -z "$verb" ]]; then
        COMPREPLY=($(compgen -W "add done search show config completions" -- "$cur"))
        return
    fi

    # Complete item names for verbs that take them
    case "$verb" in
        add|done|search)
            local items
            items=$(qs3k $flags --complete "$verb" "$cur" 2>/dev/null | sed 's/^[^ ]* //')
            COMPREPLY=($(compgen -W "$items" -- "$cur"))
            ;;
    esac
}
complete -F _qs3k_complete qs3k
"#
}

fn completion_script_zsh() -> &'static str {
    // To activate: eval "$(qs3k completions zsh)"
    // Or: qs3k completions zsh > ~/.zsh/completions/_qs3k  (with fpath entry)
    r#"#compdef qs3k
_qs3k() {
    local flags="" verb="" i

    # Collect --server/--list flags and the verb from words already typed
    for ((i=2; i<CURRENT; i++)); do
        case "${words[i]}" in
            --server|--list)
                flags="$flags ${words[i]} ${words[i+1]}"
                ((i++))
                ;;
            add|done|search|show)
                verb="${words[i]}"
                ;;
        esac
    done

    if [[ -z "$verb" ]]; then
        local subcmds
        subcmds=(add:'add or activate item' done:'deactivate item' search:'search items' show:'show active items' config:'manage config' completions:'print shell completion script')
        _describe 'command' subcmds
        return
    fi

    case "$verb" in
        add|done|search)
            local items
            items=(${(f)"$(qs3k $flags --complete "$verb" "${words[CURRENT]}" 2>/dev/null | sed 's/^[^ ]* //')"} )
            compadd -a items
            ;;
    esac
}
_qs3k
"#
}

fn cmd_show(server_override: Option<String>, list_override: Option<String>) -> Result<(), CliError> {
    let cfg = load_config();

    let server_url = server_override
        .or_else(|| cfg.server_url.clone())
        .ok_or_else(|| CliError::Fatal("no server URL configured — run: qs3k config set-server <URL>".into()))?;

    let list_id = list_override
        .or_else(|| cfg.default_list_id.clone())
        .ok_or_else(|| {
            CliError::Fatal(
                "no list specified — use --list <LIST_ID> or run: qs3k config set-list <LIST_ID>".into(),
            )
        })?;

    let state =
        fetch_list_state(&server_url, &list_id).map_err(|e| CliError::Fatal(format!("failed to fetch list: {e}")))?;

    let mut active: Vec<&str> = state
        .items
        .iter()
        .filter(|(uuid, _)| state.state_of(uuid) == "ACTIVATED")
        .map(|(_, name)| name.as_str())
        .collect();

    if active.is_empty() {
        println!("(no active items)");
        return Ok(());
    }

    active.sort_unstable_by(|a, b| fold(a).cmp(&fold(b)));
    for name in active {
        println!("{name}");
    }
    Ok(())
}

fn cmd_done(item_names: &[String], server_override: Option<String>, list_override: Option<String>) -> Result<(), CliError> {
    if item_names.is_empty() {
        return Err(CliError::Usage(format!("'done' requires at least one item name\n\n{}", usage())));
    }

    let mut cfg = load_config();

    let server_url = server_override
        .or_else(|| cfg.server_url.clone())
        .ok_or_else(|| CliError::Fatal("no server URL configured — run: qs3k config set-server <URL>".into()))?;

    let list_id = list_override
        .or_else(|| cfg.default_list_id.clone())
        .ok_or_else(|| {
            CliError::Fatal(
                "no list specified — use --list <LIST_ID> or run: qs3k config set-list <LIST_ID>".into(),
            )
        })?;

    let device_id = get_or_create_device_id(&mut cfg);

    let state =
        fetch_list_state(&server_url, &list_id).map_err(|e| CliError::Fatal(format!("failed to fetch list: {e}")))?;

    let mut intents: Vec<(&str, serde_json::Value)> = Vec::new();
    let mut actions: Vec<(String, &str)> = Vec::new();

    for name in item_names {
        match state.find_by_name(name) {
            None => {
                actions.push((name.clone(), "not found"));
            }
            Some((_uuid, "DEACTIVATED")) => {
                actions.push((name.clone(), "already done"));
            }
            Some((uuid, current_state)) => {
                intents.push((
                    "set_item_state",
                    serde_json::json!({
                        "item_uuid": uuid,
                        "state": "DEACTIVATED",
                        "previous_state": current_state,
                    }),
                ));
                actions.push((name.clone(), "done"));
            }
        }
    }

    if !intents.is_empty() {
        post_intents(&server_url, &list_id, &device_id, &intents)
            .map_err(|e| CliError::Fatal(format!("failed to post intents: {e}")))?;
    }

    for (name, action) in &actions {
        match *action {
            "done" => println!("done      {name}"),
            "already done" => println!("(done)    {name}"),
            "not found" => println!("(unknown) {name}"),
            _ => {}
        }
    }

    Ok(())
}

fn cmd_add(item_names: &[String], server_override: Option<String>, list_override: Option<String>) -> Result<(), CliError> {
    if item_names.is_empty() {
        return Err(CliError::Usage(format!("'add' requires at least one item name\n\n{}", usage())));
    }

    let mut cfg = load_config();

    let server_url = server_override
        .or_else(|| cfg.server_url.clone())
        .ok_or_else(|| CliError::Fatal("no server URL configured — run: qs3k config set-server <URL>".into()))?;

    let list_id = list_override
        .or_else(|| cfg.default_list_id.clone())
        .ok_or_else(|| {
            CliError::Fatal(
                "no list specified — use --list <LIST_ID> or run: qs3k config set-list <LIST_ID>".into(),
            )
        })?;

    let device_id = get_or_create_device_id(&mut cfg);

    // Fetch current state to decide add vs. activate per item
    let state =
        fetch_list_state(&server_url, &list_id).map_err(|e| CliError::Fatal(format!("failed to fetch list: {e}")))?;

    let mut intents: Vec<(&str, serde_json::Value)> = Vec::new();
    // Track activations separately so we can report them
    let mut actions: Vec<(String, &str)> = Vec::new(); // (name, "add"|"activate"|"already active")

    for name in item_names {
        match state.find_by_name(name) {
            Some((_uuid, current_state)) if current_state == "ACTIVATED" => {
                actions.push((name.clone(), "already active"));
            }
            Some((uuid, _current_state)) => {
                // Exists but not ACTIVATED — re-activate it
                intents.push((
                    "set_item_state",
                    serde_json::json!({
                        "item_uuid": uuid,
                        "state": "ACTIVATED",
                        "previous_state": _current_state,
                    }),
                ));
                actions.push((name.clone(), "activate"));
            }
            None => {
                let item_uuid = generate_ulid();
                intents.push((
                    "add_item",
                    serde_json::json!({
                        "item_uuid": item_uuid,
                        "name": name,
                    }),
                ));
                actions.push((name.clone(), "add"));
            }
        }
    }

    if !intents.is_empty() {
        post_intents(&server_url, &list_id, &device_id, &intents)
            .map_err(|e| CliError::Fatal(format!("failed to post intents: {e}")))?;
    }

    for (name, action) in &actions {
        match *action {
            "add" => println!("added     {name}"),
            "activate" => println!("activated {name}"),
            "already active" => println!("(active)  {name}"),
            _ => {}
        }
    }

    Ok(())
}

fn main() {
    match run() {
        Ok(()) => {}
        Err(CliError::Usage(msg)) => {
            eprintln!("{msg}");
            process::exit(2);
        }
        Err(CliError::Fatal(msg)) => {
            eprintln!("error: {msg}");
            process::exit(1);
        }
    }
}
