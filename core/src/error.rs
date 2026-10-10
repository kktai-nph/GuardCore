use thiserror::Error;

#[derive(Error, Debug, PartialEq, Eq)]
pub enum CoreError {
    #[error("Cryptographic error: {0}")]
    CryptoError(String),

    #[error("Invalid signature: verification failed")]
    SignatureInvalid,

    #[error("Command expired: current_time={now}, expires_at={expires_at}")]
    ExpiredCommand { now: u64, expires_at: u64 },

    #[error("Command has invalid timestamp drift: now={now}, timestamp={timestamp}")]
    InvalidTimestampDrift { now: u64, timestamp: u64 },

    #[error("Replayed nonce detected: {0}")]
    ReplayedNonce(String),

    #[error("Unauthorized parent sender: {0}")]
    UnauthorizedParent(String),

    #[error("Target device mismatch: expected {expected}, got {actual}")]
    TargetMismatch { expected: String, actual: String },

    #[error("Database error: {0}")]
    DatabaseError(String),

    #[error("Serialization error: {0}")]
    SerializationError(String),

    #[error("Protocol error: {0}")]
    ProtocolError(String),

    #[error("Invalid setup token for pairing: {0}")]
    InvalidSetupToken(String),

    #[error("State error: {0}")]
    InvalidState(String),
}

impl From<rusqlite::Error> for CoreError {
    fn from(err: rusqlite::Error) -> Self {
        CoreError::DatabaseError(err.to_string())
    }
}

impl From<serde_json::Error> for CoreError {
    fn from(err: serde_json::Error) -> Self {
        CoreError::SerializationError(err.to_string())
    }
}

impl From<hex::FromHexError> for CoreError {
    fn from(err: hex::FromHexError) -> Self {
        CoreError::CryptoError(format!("Hex decode error: {}", err))
    }
}
