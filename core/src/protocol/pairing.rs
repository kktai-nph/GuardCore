use serde::{Deserialize, Serialize};
use crate::error::CoreError;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct PairingQrPayload {
    pub version: u8,
    pub device_id: String,
    pub child_name: String,
    pub ed25519_pubkey: String,
    pub x25519_pubkey: String,
    pub setup_token: String,
    pub created_at: u64,
}

impl PairingQrPayload {
    pub fn new(
        device_id: String,
        child_name: String,
        ed25519_pubkey: String,
        x25519_pubkey: String,
        setup_token: String,
        created_at: u64,
    ) -> Self {
        Self {
            version: 1,
            device_id,
            child_name,
            ed25519_pubkey,
            x25519_pubkey,
            setup_token,
            created_at,
        }
    }

    pub fn to_qr_string(&self) -> Result<String, CoreError> {
        let json = serde_json::to_string(self)
            .map_err(|e| CoreError::SerializationError(format!("QR JSON error: {}", e)))?;
        Ok(format!("CRT_PAIR:{}", json))
    }

    pub fn from_qr_string(qr_str: &str) -> Result<Self, CoreError> {
        let trimmed = qr_str.trim();
        let json_part = if let Some(stripped) = trimmed.strip_prefix("CRT_PAIR:") {
            stripped
        } else {
            trimmed
        };

        serde_json::from_str(json_part)
            .map_err(|e| CoreError::SerializationError(format!("Invalid QR data: {}", e)))
    }
}
