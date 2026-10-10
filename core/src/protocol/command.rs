use serde::{Deserialize, Serialize};
use crate::crypto::{Ed25519KeyPair, NonceUtil};
use crate::error::CoreError;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(tag = "type", content = "payload")]
pub enum Action {
    LockNow {
        duration_seconds: Option<u64>,
        reason: Option<String>,
    },
    UnlockNow,
    PauseDevice {
        duration_minutes: u64,
    },
    GrantBonusTime {
        package_name: Option<String>,
        minutes: u64,
    },
    SetBedtimeSchedule {
        schedule_id: String,
        name: String,
        days_of_week: u8, // Bitmask: Mon=1, Tue=2, Wed=4, Thu=8, Fri=16, Sat=32, Sun=64
        start_time: String, // e.g. "21:00"
        end_time: String,   // e.g. "06:00"
        mode: String,       // "LOCK_ALL" | "ALLOW_WHITELIST"
    },
    DeleteSchedule {
        schedule_id: String,
    },
    SetAppLimit {
        package_name: String,
        app_name: String,
        daily_limit_minutes: u32,
        is_blocked: bool,
    },
    RemoveAppLimit {
        package_name: String,
    },
    PairConfirm {
        child_device_id: String,
        parent_x25519_pubkey: String,
        setup_token: String,
        parent_alias: String,
    },
    Ping,
    RequestUsageReport {
        days: u32,
    },
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct CommandPayload {
    pub command_id: String,
    pub sender_pubkey: String,
    pub target_device_id: String,
    pub timestamp: u64,
    pub expires_at: u64,
    pub nonce: String,
    pub action: Action,
}

impl CommandPayload {
    pub fn new(
        sender_pubkey: String,
        target_device_id: String,
        action: Action,
        current_time: u64,
    ) -> Self {
        Self {
            command_id: NonceUtil::generate_id(),
            sender_pubkey,
            target_device_id,
            timestamp: current_time,
            expires_at: current_time + 60, // 60-second validity window
            nonce: NonceUtil::generate_nonce(),
            action,
        }
    }

    pub fn signable_bytes(&self) -> Result<Vec<u8>, CoreError> {
        serde_json::to_vec(self)
            .map_err(|e| CoreError::SerializationError(format!("Failed to serialize payload: {}", e)))
    }

    pub fn sign(self, keypair: &Ed25519KeyPair) -> Result<SignedCommand, CoreError> {
        let bytes = self.signable_bytes()?;
        let signature = keypair.sign(&bytes);
        Ok(SignedCommand {
            payload: self,
            signature,
        })
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct SignedCommand {
    pub payload: CommandPayload,
    pub signature: String,
}

impl SignedCommand {
    pub fn to_json(&self) -> Result<String, CoreError> {
        serde_json::to_string(self)
            .map_err(|e| CoreError::SerializationError(format!("Failed to serialize signed command: {}", e)))
    }

    pub fn from_json(json_str: &str) -> Result<Self, CoreError> {
        serde_json::from_str(json_str)
            .map_err(|e| CoreError::SerializationError(format!("Invalid command JSON: {}", e)))
    }

    pub fn verify(&self) -> Result<(), CoreError> {
        let bytes = self.payload.signable_bytes()?;
        Ed25519KeyPair::verify_signature(&self.payload.sender_pubkey, &bytes, &self.signature)
    }

    pub fn validate_time_window(&self, current_time: u64) -> Result<(), CoreError> {
        // Expiry check
        if current_time > self.payload.expires_at {
            return Err(CoreError::ExpiredCommand {
                now: current_time,
                expires_at: self.payload.expires_at,
            });
        }

        // Future drift check (command timestamp cannot be > 30s into the future)
        if self.payload.timestamp > current_time + 30 {
            return Err(CoreError::InvalidTimestampDrift {
                now: current_time,
                timestamp: self.payload.timestamp,
            });
        }

        Ok(())
    }
}
