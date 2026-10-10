use serde::{Deserialize, Serialize};
use crate::crypto::Ed25519KeyPair;
use crate::error::CoreError;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub enum AckStatus {
    Success,
    Failed,
    Rejected,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct AckPayload {
    pub command_id: String,
    pub child_device_id: String,
    pub status: AckStatus,
    pub message: Option<String>,
    pub battery_level: u8,
    pub is_device_locked: bool,
    pub timestamp: u64,
}

impl AckPayload {
    pub fn signable_bytes(&self) -> Result<Vec<u8>, CoreError> {
        serde_json::to_vec(self)
            .map_err(|e| CoreError::SerializationError(format!("Failed to serialize ack payload: {}", e)))
    }

    pub fn sign(self, child_keypair: &Ed25519KeyPair) -> Result<SignedAck, CoreError> {
        let bytes = self.signable_bytes()?;
        let signature = child_keypair.sign(&bytes);
        Ok(SignedAck {
            payload: self,
            child_pubkey: child_keypair.public_key_hex(),
            signature,
        })
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct SignedAck {
    pub payload: AckPayload,
    pub child_pubkey: String,
    pub signature: String,
}

impl SignedAck {
    pub fn to_json(&self) -> Result<String, CoreError> {
        serde_json::to_string(self)
            .map_err(|e| CoreError::SerializationError(format!("Failed to serialize signed ack: {}", e)))
    }

    pub fn from_json(json_str: &str) -> Result<Self, CoreError> {
        serde_json::from_str(json_str)
            .map_err(|e| CoreError::SerializationError(format!("Invalid ack JSON: {}", e)))
    }

    pub fn verify(&self) -> Result<(), CoreError> {
        let bytes = self.payload.signable_bytes()?;
        Ed25519KeyPair::verify_signature(&self.child_pubkey, &bytes, &self.signature)
    }
}
