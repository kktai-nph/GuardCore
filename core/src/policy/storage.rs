use rusqlite::{params, Connection};
use crate::error::CoreError;
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TrustedParent {
    pub parent_pubkey: String,
    pub alias: String,
    pub created_at: u64,
    pub is_active: bool,
    pub shared_secret_hex: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct PolicySchedule {
    pub id: String,
    pub name: String,
    pub days_of_week: u8,
    pub start_time: String, // "HH:MM" 24h
    pub end_time: String,   // "HH:MM" 24h
    pub mode: String,       // "LOCK_ALL" | "ALLOW_WHITELIST"
    pub is_enabled: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct AppLimit {
    pub package_name: String,
    pub app_name: String,
    pub daily_limit_minutes: u32,
    pub used_seconds_today: u64,
    pub is_blocked: bool,
    pub last_reset_date: String, // "YYYY-MM-DD"
}

pub struct PolicyStorage {
    conn: Connection,
}

impl PolicyStorage {
    pub fn new_in_memory() -> Result<Self, CoreError> {
        let conn = Connection::open_in_memory()?;
        let storage = Self { conn };
        storage.init_tables()?;
        Ok(storage)
    }

    pub fn open(db_path: &str) -> Result<Self, CoreError> {
        let conn = Connection::open(db_path)?;
        let storage = Self { conn };
        storage.init_tables()?;
        Ok(storage)
    }

    pub fn init_tables(&self) -> Result<(), CoreError> {
        self.conn.execute_batch(
            r#"
            PRAGMA journal_mode = WAL;
            PRAGMA foreign_keys = ON;

            CREATE TABLE IF NOT EXISTS trusted_parents (
                parent_pubkey TEXT PRIMARY KEY,
                alias TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                is_active INTEGER DEFAULT 1,
                shared_secret_hex TEXT
            );

            CREATE TABLE IF NOT EXISTS processed_nonces (
                nonce TEXT PRIMARY KEY,
                received_at INTEGER NOT NULL
            );

            CREATE INDEX IF NOT EXISTS idx_nonces_received_at ON processed_nonces(received_at);

            CREATE TABLE IF NOT EXISTS policy_schedules (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                days_of_week INTEGER NOT NULL,
                start_time TEXT NOT NULL,
                end_time TEXT NOT NULL,
                mode TEXT NOT NULL,
                is_enabled INTEGER DEFAULT 1
            );

            CREATE TABLE IF NOT EXISTS app_usage_limits (
                package_name TEXT PRIMARY KEY,
                app_name TEXT NOT NULL,
                daily_limit_minutes INTEGER NOT NULL,
                used_seconds_today INTEGER DEFAULT 0,
                is_blocked INTEGER DEFAULT 0,
                last_reset_date TEXT NOT NULL
            );

            CREATE TABLE IF NOT EXISTS usage_logs (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                package_name TEXT NOT NULL,
                duration_seconds INTEGER NOT NULL,
                timestamp INTEGER NOT NULL
            );

            CREATE INDEX IF NOT EXISTS idx_usage_timestamp ON usage_logs(timestamp);

            CREATE TABLE IF NOT EXISTS device_state (
                key TEXT PRIMARY KEY,
                value TEXT NOT NULL
            );
            "#,
        )?;
        Ok(())
    }

    // --- Trusted Parents ---
    pub fn add_trusted_parent(&self, parent: &TrustedParent) -> Result<(), CoreError> {
        self.conn.execute(
            r#"
            INSERT INTO trusted_parents (parent_pubkey, alias, created_at, is_active, shared_secret_hex)
            VALUES (?1, ?2, ?3, ?4, ?5)
            ON CONFLICT(parent_pubkey) DO UPDATE SET
                alias = excluded.alias,
                is_active = excluded.is_active,
                shared_secret_hex = excluded.shared_secret_hex;
            "#,
            params![
                parent.parent_pubkey,
                parent.alias,
                parent.created_at,
                if parent.is_active { 1 } else { 0 },
                parent.shared_secret_hex
            ],
        )?;
        Ok(())
    }

    pub fn is_parent_trusted(&self, parent_pubkey: &str) -> Result<bool, CoreError> {
        let mut stmt = self.conn.prepare(
            "SELECT is_active FROM trusted_parents WHERE parent_pubkey = ?1 AND is_active = 1",
        )?;
        let exists = stmt.exists(params![parent_pubkey])?;
        Ok(exists)
    }

    pub fn get_parent(&self, parent_pubkey: &str) -> Result<Option<TrustedParent>, CoreError> {
        let mut stmt = self.conn.prepare(
            "SELECT parent_pubkey, alias, created_at, is_active, shared_secret_hex FROM trusted_parents WHERE parent_pubkey = ?1",
        )?;
        let mut rows = stmt.query(params![parent_pubkey])?;
        if let Some(row) = rows.next()? {
            let is_active_int: i32 = row.get(3)?;
            Ok(Some(TrustedParent {
                parent_pubkey: row.get(0)?,
                alias: row.get(1)?,
                created_at: row.get(2)?,
                is_active: is_active_int == 1,
                shared_secret_hex: row.get(4)?,
            }))
        } else {
            Ok(None)
        }
    }

    pub fn list_parents(&self) -> Result<Vec<TrustedParent>, CoreError> {
        let mut stmt = self.conn.prepare(
            "SELECT parent_pubkey, alias, created_at, is_active, shared_secret_hex FROM trusted_parents",
        )?;
        let parent_iter = stmt.query_map([], |row| {
            let is_active_int: i32 = row.get(3)?;
            Ok(TrustedParent {
                parent_pubkey: row.get(0)?,
                alias: row.get(1)?,
                created_at: row.get(2)?,
                is_active: is_active_int == 1,
                shared_secret_hex: row.get(4)?,
            })
        })?;

        let mut list = Vec::new();
        for p in parent_iter {
            list.push(p?);
        }
        Ok(list)
    }

    // --- Nonce / Anti-Replay ---
    pub fn exists_nonce(&self, nonce: &str) -> Result<bool, CoreError> {
        let mut stmt = self
            .conn
            .prepare("SELECT 1 FROM processed_nonces WHERE nonce = ?1")?;
        let exists = stmt.exists(params![nonce])?;
        Ok(exists)
    }

    pub fn save_nonce(&self, nonce: &str, received_at: u64) -> Result<(), CoreError> {
        self.conn.execute(
            "INSERT INTO processed_nonces (nonce, received_at) VALUES (?1, ?2)",
            params![nonce, received_at],
        )?;
        Ok(())
    }

    pub fn prune_old_nonces(&self, current_time: u64, max_age_seconds: u64) -> Result<usize, CoreError> {
        if current_time < max_age_seconds {
            return Ok(0);
        }
        let threshold = current_time - max_age_seconds;
        let deleted = self.conn.execute(
            "DELETE FROM processed_nonces WHERE received_at < ?1",
            params![threshold],
        )?;
        Ok(deleted)
    }

    // --- Schedules ---
    pub fn save_schedule(&self, schedule: &PolicySchedule) -> Result<(), CoreError> {
        self.conn.execute(
            r#"
            INSERT INTO policy_schedules (id, name, days_of_week, start_time, end_time, mode, is_enabled)
            VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)
            ON CONFLICT(id) DO UPDATE SET
                name = excluded.name,
                days_of_week = excluded.days_of_week,
                start_time = excluded.start_time,
                end_time = excluded.end_time,
                mode = excluded.mode,
                is_enabled = excluded.is_enabled;
            "#,
            params![
                schedule.id,
                schedule.name,
                schedule.days_of_week,
                schedule.start_time,
                schedule.end_time,
                schedule.mode,
                if schedule.is_enabled { 1 } else { 0 }
            ],
        )?;
        Ok(())
    }

    pub fn delete_schedule(&self, id: &str) -> Result<(), CoreError> {
        self.conn
            .execute("DELETE FROM policy_schedules WHERE id = ?1", params![id])?;
        Ok(())
    }

    pub fn get_schedules(&self) -> Result<Vec<PolicySchedule>, CoreError> {
        let mut stmt = self.conn.prepare(
            "SELECT id, name, days_of_week, start_time, end_time, mode, is_enabled FROM policy_schedules WHERE is_enabled = 1",
        )?;
        let rows = stmt.query_map([], |row| {
            let enabled_int: i32 = row.get(6)?;
            Ok(PolicySchedule {
                id: row.get(0)?,
                name: row.get(1)?,
                days_of_week: row.get(2)?,
                start_time: row.get(3)?,
                end_time: row.get(4)?,
                mode: row.get(5)?,
                is_enabled: enabled_int == 1,
            })
        })?;

        let mut list = Vec::new();
        for r in rows {
            list.push(r?);
        }
        Ok(list)
    }

    // --- App Limits ---
    pub fn save_app_limit(&self, limit: &AppLimit) -> Result<(), CoreError> {
        self.conn.execute(
            r#"
            INSERT INTO app_usage_limits (package_name, app_name, daily_limit_minutes, used_seconds_today, is_blocked, last_reset_date)
            VALUES (?1, ?2, ?3, ?4, ?5, ?6)
            ON CONFLICT(package_name) DO UPDATE SET
                app_name = excluded.app_name,
                daily_limit_minutes = excluded.daily_limit_minutes,
                is_blocked = excluded.is_blocked;
            "#,
            params![
                limit.package_name,
                limit.app_name,
                limit.daily_limit_minutes,
                limit.used_seconds_today,
                if limit.is_blocked { 1 } else { 0 },
                limit.last_reset_date
            ],
        )?;
        Ok(())
    }

    pub fn remove_app_limit(&self, package_name: &str) -> Result<(), CoreError> {
        self.conn.execute(
            "DELETE FROM app_usage_limits WHERE package_name = ?1",
            params![package_name],
        )?;
        Ok(())
    }

    pub fn get_app_limit(&self, package_name: &str) -> Result<Option<AppLimit>, CoreError> {
        let mut stmt = self.conn.prepare(
            "SELECT package_name, app_name, daily_limit_minutes, used_seconds_today, is_blocked, last_reset_date FROM app_usage_limits WHERE package_name = ?1",
        )?;
        let mut rows = stmt.query(params![package_name])?;
        if let Some(row) = rows.next()? {
            let blocked_int: i32 = row.get(4)?;
            Ok(Some(AppLimit {
                package_name: row.get(0)?,
                app_name: row.get(1)?,
                daily_limit_minutes: row.get(2)?,
                used_seconds_today: row.get(3)?,
                is_blocked: blocked_int == 1,
                last_reset_date: row.get(5)?,
            }))
        } else {
            Ok(None)
        }
    }

    pub fn list_app_limits(&self) -> Result<Vec<AppLimit>, CoreError> {
        let mut stmt = self.conn.prepare(
            "SELECT package_name, app_name, daily_limit_minutes, used_seconds_today, is_blocked, last_reset_date FROM app_usage_limits",
        )?;
        let rows = stmt.query_map([], |row| {
            let blocked_int: i32 = row.get(4)?;
            Ok(AppLimit {
                package_name: row.get(0)?,
                app_name: row.get(1)?,
                daily_limit_minutes: row.get(2)?,
                used_seconds_today: row.get(3)?,
                is_blocked: blocked_int == 1,
                last_reset_date: row.get(5)?,
            })
        })?;

        let mut list = Vec::new();
        for r in rows {
            list.push(r?);
        }
        Ok(list)
    }

    pub fn update_app_usage(&self, package_name: &str, add_seconds: u64, today_date_str: &str) -> Result<(), CoreError> {
        // If app limit exists, check if date needs reset
        if let Some(mut limit) = self.get_app_limit(package_name)? {
            if limit.last_reset_date != today_date_str {
                limit.used_seconds_today = add_seconds;
                limit.last_reset_date = today_date_str.to_string();
            } else {
                limit.used_seconds_today += add_seconds;
            }
            self.conn.execute(
                "UPDATE app_usage_limits SET used_seconds_today = ?1, last_reset_date = ?2 WHERE package_name = ?3",
                params![limit.used_seconds_today, limit.last_reset_date, package_name],
            )?;
        }
        Ok(())
    }

    // --- Usage Logs ---
    pub fn log_usage(&self, package_name: &str, duration_seconds: u64, timestamp: u64) -> Result<(), CoreError> {
        self.conn.execute(
            "INSERT INTO usage_logs (package_name, duration_seconds, timestamp) VALUES (?1, ?2, ?3)",
            params![package_name, duration_seconds, timestamp],
        )?;
        Ok(())
    }

    pub fn get_total_usage_since(&self, package_name: &str, since_timestamp: u64) -> Result<u64, CoreError> {
        let mut stmt = self.conn.prepare(
            "SELECT COALESCE(SUM(duration_seconds), 0) FROM usage_logs WHERE package_name = ?1 AND timestamp >= ?2",
        )?;
        let total: u64 = stmt.query_row(params![package_name, since_timestamp], |row| row.get(0))?;
        Ok(total)
    }

    // --- Device State Key/Value ---
    pub fn set_device_state(&self, key: &str, value: &str) -> Result<(), CoreError> {
        self.conn.execute(
            "INSERT INTO device_state (key, value) VALUES (?1, ?2) ON CONFLICT(key) DO UPDATE SET value = excluded.value",
            params![key, value],
        )?;
        Ok(())
    }

    pub fn get_device_state(&self, key: &str) -> Result<Option<String>, CoreError> {
        let mut stmt = self
            .conn
            .prepare("SELECT value FROM device_state WHERE key = ?1")?;
        let mut rows = stmt.query(params![key])?;
        if let Some(row) = rows.next()? {
            let val: String = row.get(0)?;
            Ok(Some(val))
        } else {
            Ok(None)
        }
    }

    pub fn delete_device_state(&self, key: &str) -> Result<(), CoreError> {
        self.conn
            .execute("DELETE FROM device_state WHERE key = ?1", params![key])?;
        Ok(())
    }
}
