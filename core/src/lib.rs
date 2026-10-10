pub mod crypto;
pub mod error;
pub mod engine;
pub mod ffi;
pub mod policy;
pub mod protocol;

pub use crypto::{ChaChaCipher, Ed25519KeyPair, NonceUtil, X25519KeyPair};
pub use error::CoreError;
pub use engine::{ChildEngine, ParentEngine, ProcessedCommandResult};
pub use policy::{AppAccessDecision, AppLimit, DeviceLockStatus, LockReason, PolicyEngine, PolicySchedule, PolicyStorage, TrustedParent};
pub use protocol::{Action, AckPayload, AckStatus, CommandPayload, PairingQrPayload, ParsedWireMessage, SignedAck, SignedCommand, TelegramWire};

uniffi::setup_scaffolding!();
