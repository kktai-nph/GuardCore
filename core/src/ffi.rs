use std::sync::{Arc, Mutex};
use crate::crypto::NonceUtil;
use crate::engine::child_engine::ChildEngine;
use crate::engine::parent_engine::ParentEngine;
use crate::error::CoreError;
use crate::policy::engine::{AppAccessDecision, LockReason};
use crate::protocol::command::Action;
use thiserror::Error;

#[derive(Debug, Error, uniffi::Error)]
pub enum CrtError {
    #[error("Crypto error: {detail}")]
    Crypto { detail: String },
    #[error("Signature invalid")]
    SignatureInvalid,
    #[error("Command expired: now={now}, expires_at={expires_at}")]
    ExpiredCommand { now: u64, expires_at: u64 },
    #[error("Replayed nonce: {nonce}")]
    ReplayedNonce { nonce: String },
    #[error("Unauthorized parent: {pubkey}")]
    UnauthorizedParent { pubkey: String },
    #[error("Database error: {detail}")]
    Database { detail: String },
    #[error("Serialization error: {detail}")]
    Serialization { detail: String },
    #[error("General error: {detail}")]
    General { detail: String },
}

impl From<CoreError> for CrtError {
    fn from(err: CoreError) -> Self {
        match err {
            CoreError::CryptoError(m) => CrtError::Crypto { detail: m },
            CoreError::SignatureInvalid => CrtError::SignatureInvalid,
            CoreError::ExpiredCommand { now, expires_at } => CrtError::ExpiredCommand { now, expires_at },
            CoreError::ReplayedNonce(n) => CrtError::ReplayedNonce { nonce: n },
            CoreError::UnauthorizedParent(p) => CrtError::UnauthorizedParent { pubkey: p },
            CoreError::DatabaseError(m) => CrtError::Database { detail: m },
            CoreError::SerializationError(m) => CrtError::Serialization { detail: m },
            _ => CrtError::General { detail: err.to_string() },
        }
    }
}

#[derive(uniffi::Record)]
pub struct CrtProcessedResult {
    pub command_id: String,
    pub sender_pubkey: String,
    pub action_type: String,
    pub action_json: String,
    pub ack_json: String,
}

#[derive(uniffi::Record)]
pub struct CrtDeviceLockStatus {
    pub is_locked: bool,
    pub reason_type: String,
    pub reason_detail: String,
}

#[derive(uniffi::Record)]
pub struct CrtAppDecision {
    pub decision_type: String, // "ALLOWED", "BLOCKED", "QUOTA_EXCEEDED", "DEVICE_LOCKED"
    pub remaining_seconds: i64, // -1 if not applicable
    pub detail_message: String,
}

#[derive(uniffi::Record)]
pub struct CrtPairingResult {
    pub child_device_id: String,
    pub child_name: String,
    pub child_ed25519_pubkey: String,
    pub child_x25519_pubkey: String,
    pub outbound_wire_message: String,
}

#[derive(uniffi::Object)]
pub struct CrtChildEngine {
    inner: Mutex<ChildEngine>,
}

#[uniffi::export]
impl CrtChildEngine {
    #[uniffi::constructor]
    pub fn new(
        db_path: String,
        device_id: String,
        secret_hex: Option<String>,
        x_secret_hex: Option<String>,
    ) -> Result<Arc<Self>, CrtError> {
        let engine = ChildEngine::new(
            &db_path,
            device_id,
            secret_hex.as_deref(),
            x_secret_hex.as_deref(),
        )?;
        Ok(Arc::new(Self {
            inner: Mutex::new(engine),
        }))
    }

    pub fn get_signing_public_key(&self) -> String {
        let engine = self.inner.lock().unwrap();
        engine.ed25519_keypair.public_key_hex()
    }

    pub fn get_secret_key(&self) -> String {
        let engine = self.inner.lock().unwrap();
        engine.ed25519_keypair.secret_hex()
    }

    pub fn get_x25519_public_key(&self) -> String {
        let engine = self.inner.lock().unwrap();
        engine.x25519_keypair.public_key_hex()
    }

    pub fn get_x25519_secret_key(&self) -> String {
        let engine = self.inner.lock().unwrap();
        engine.x25519_keypair.secret_hex()
    }

    pub fn generate_pairing_qr(
        &self,
        child_name: String,
        current_time: u64,
        setup_token: String,
    ) -> Result<String, CrtError> {
        let engine = self.inner.lock().unwrap();
        let qr = engine.generate_pairing_qr(&child_name, current_time, &setup_token)?;
        Ok(qr)
    }

    pub fn process_wire_message(
        &self,
        wire_text: String,
        current_time: u64,
        battery_level: u8,
        is_locked: bool,
    ) -> Result<Option<CrtProcessedResult>, CrtError> {
        let engine = self.inner.lock().unwrap();
        let res = engine.process_wire_message(&wire_text, current_time, battery_level, is_locked)?;
        match res {
            Some(r) => {
                let action_json = serde_json::to_string(&r.action)
                    .map_err(|e| CrtError::Serialization { detail: e.to_string() })?;
                let action_type = match &r.action {
                    Action::LockNow { .. } => "LOCK_NOW",
                    Action::UnlockNow => "UNLOCK_NOW",
                    Action::PauseDevice { .. } => "PAUSE_DEVICE",
                    Action::GrantBonusTime { .. } => "GRANT_BONUS_TIME",
                    Action::SetBedtimeSchedule { .. } => "SET_SCHEDULE",
                    Action::DeleteSchedule { .. } => "DELETE_SCHEDULE",
                    Action::SetAppLimit { .. } => "SET_APP_LIMIT",
                    Action::RemoveAppLimit { .. } => "REMOVE_APP_LIMIT",
                    Action::PairConfirm { .. } => "PAIR_CONFIRM",
                    Action::Ping => "PING",
                    Action::RequestUsageReport { .. } => "REQUEST_USAGE_REPORT",
                }.to_string();

                Ok(Some(CrtProcessedResult {
                    command_id: r.command_id,
                    sender_pubkey: r.sender_pubkey,
                    action_type,
                    action_json,
                    ack_json: r.ack_json,
                }))
            }
            None => Ok(None),
        }
    }

    pub fn evaluate_device_lock(
        &self,
        current_time: u64,
        current_hhmm: String,
        day_of_week: u8,
    ) -> Result<CrtDeviceLockStatus, CrtError> {
        let engine = self.inner.lock().unwrap();
        let status = engine.evaluate_device_lock(current_time, &current_hhmm, day_of_week)?;
        let (reason_type, reason_detail) = match status.reason {
            None => ("NONE".to_string(), "".to_string()),
            Some(LockReason::ManualLock) => ("MANUAL".to_string(), "Locked by parent".to_string()),
            Some(LockReason::TemporaryPause { remaining_seconds }) => (
                "PAUSE".to_string(),
                format!("Paused for {} more seconds", remaining_seconds),
            ),
            Some(LockReason::ActiveSchedule { schedule_name, end_time }) => (
                "SCHEDULE".to_string(),
                format!("Schedule active: {} until {}", schedule_name, end_time),
            ),
        };

        Ok(CrtDeviceLockStatus {
            is_locked: status.is_locked,
            reason_type,
            reason_detail,
        })
    }

    pub fn evaluate_app_access(
        &self,
        package_name: String,
        current_time: u64,
        current_hhmm: String,
        day_of_week: u8,
        today_date_str: String,
    ) -> Result<CrtAppDecision, CrtError> {
        let engine = self.inner.lock().unwrap();
        let decision = engine.evaluate_app_access(
            &package_name,
            current_time,
            &current_hhmm,
            day_of_week,
            &today_date_str,
        )?;

        let res = match decision {
            AppAccessDecision::Allowed { remaining_seconds_today } => CrtAppDecision {
                decision_type: "ALLOWED".to_string(),
                remaining_seconds: remaining_seconds_today.map(|s| s as i64).unwrap_or(-1),
                detail_message: "App allowed".to_string(),
            },
            AppAccessDecision::BlockedByPolicy { reason } => CrtAppDecision {
                decision_type: "BLOCKED".to_string(),
                remaining_seconds: 0,
                detail_message: reason,
            },
            AppAccessDecision::DailyQuotaExceeded { limit_minutes, used_seconds } => CrtAppDecision {
                decision_type: "QUOTA_EXCEEDED".to_string(),
                remaining_seconds: 0,
                detail_message: format!("Daily quota of {} mins exceeded (used {} mins)", limit_minutes, used_seconds / 60),
            },
            AppAccessDecision::DeviceLocked { reason } => CrtAppDecision {
                decision_type: "DEVICE_LOCKED".to_string(),
                remaining_seconds: 0,
                detail_message: format!("{:?}", reason),
            },
        };

        Ok(res)
    }

    pub fn record_app_usage(
        &self,
        package_name: String,
        duration_seconds: u64,
        current_time: u64,
        today_date_str: String,
    ) -> Result<(), CrtError> {
        let engine = self.inner.lock().unwrap();
        engine.record_app_usage(&package_name, duration_seconds, current_time, &today_date_str)?;
        Ok(())
    }
}

#[derive(uniffi::Object)]
pub struct CrtParentEngine {
    inner: ParentEngine,
}

#[uniffi::export]
impl CrtParentEngine {
    #[uniffi::constructor]
    pub fn new(
        secret_hex: Option<String>,
        x_secret_hex: Option<String>,
    ) -> Result<Arc<Self>, CrtError> {
        let engine = ParentEngine::new(secret_hex.as_deref(), x_secret_hex.as_deref())?;
        Ok(Arc::new(Self { inner: engine }))
    }

    pub fn get_signing_public_key(&self) -> String {
        self.inner.public_key_hex()
    }

    pub fn get_secret_key(&self) -> String {
        self.inner.ed25519_keypair.secret_hex()
    }

    pub fn get_x25519_public_key(&self) -> String {
        self.inner.x25519_public_key_hex()
    }

    pub fn get_x25519_secret_key(&self) -> String {
        self.inner.x25519_keypair.secret_hex()
    }

    pub fn create_plain_command_message(
        &self,
        target_device_id: String,
        action_json: String,
        current_time: u64,
    ) -> Result<String, CrtError> {
        let action: Action = serde_json::from_str(&action_json)
            .map_err(|e| CrtError::Serialization { detail: e.to_string() })?;
        let msg = self.inner.create_plain_command_message(&target_device_id, action, current_time)?;
        Ok(msg)
    }

    pub fn create_encrypted_command_message(
        &self,
        target_device_id: String,
        action_json: String,
        current_time: u64,
        child_x25519_pubkey: String,
    ) -> Result<String, CrtError> {
        let action: Action = serde_json::from_str(&action_json)
            .map_err(|e| CrtError::Serialization { detail: e.to_string() })?;
        let msg = self.inner.create_encrypted_command_message(
            &target_device_id,
            action,
            current_time,
            &child_x25519_pubkey,
        )?;
        Ok(msg)
    }

    pub fn pair_with_child_qr(
        &self,
        qr_string: String,
        parent_alias: String,
        current_time: u64,
    ) -> Result<CrtPairingResult, CrtError> {
        let (qr, wire_msg) = self.inner.pair_with_child_qr(&qr_string, &parent_alias, current_time)?;
        Ok(CrtPairingResult {
            child_device_id: qr.device_id,
            child_name: qr.child_name,
            child_ed25519_pubkey: qr.ed25519_pubkey,
            child_x25519_pubkey: qr.x25519_pubkey,
            outbound_wire_message: wire_msg,
        })
    }

    pub fn verify_ack(
        &self,
        raw_ack_json: String,
        expected_child_pubkey: String,
    ) -> Result<bool, CrtError> {
        ParentEngine::verify_ack(&raw_ack_json, &expected_child_pubkey)?;
        Ok(true)
    }
}

#[uniffi::export]
pub fn crt_generate_random_token() -> String {
    NonceUtil::generate_setup_token()
}

#[uniffi::export]
pub fn crt_generate_device_id() -> String {
    NonceUtil::generate_id()
}
