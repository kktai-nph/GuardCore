use crate::crypto::{ChaChaCipher, Ed25519KeyPair, X25519KeyPair};
use crate::error::CoreError;
use crate::policy::engine::{AppAccessDecision, DeviceLockStatus, PolicyEngine};
use crate::policy::storage::{AppLimit, PolicySchedule, PolicyStorage, TrustedParent};
use crate::protocol::command::{Action, SignedCommand};
use crate::protocol::pairing::PairingQrPayload;
use crate::protocol::response::{AckPayload, AckStatus};
use crate::protocol::telegram::{ParsedWireMessage, TelegramWire};
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ProcessedCommandResult {
    pub command_id: String,
    pub sender_pubkey: String,
    pub action: Action,
    pub ack_json: String,
}

pub struct ChildEngine {
    pub device_id: String,
    pub ed25519_keypair: Ed25519KeyPair,
    pub x25519_keypair: X25519KeyPair,
    pub storage: PolicyStorage,
}

impl ChildEngine {
    pub fn new(
        db_path: &str,
        device_id: String,
        secret_hex: Option<&str>,
        x_secret_hex: Option<&str>,
    ) -> Result<Self, CoreError> {
        let storage = PolicyStorage::open(db_path)?;
        let ed25519_keypair = if let Some(hex_str) = secret_hex {
            Ed25519KeyPair::from_secret_hex(hex_str)?
        } else {
            Ed25519KeyPair::generate()
        };

        let x25519_keypair = if let Some(hex_str) = x_secret_hex {
            X25519KeyPair::from_secret_hex(hex_str)?
        } else {
            X25519KeyPair::generate()
        };

        Ok(Self {
            device_id,
            ed25519_keypair,
            x25519_keypair,
            storage,
        })
    }

    pub fn new_in_memory(device_id: String) -> Result<Self, CoreError> {
        let storage = PolicyStorage::new_in_memory()?;
        let ed25519_keypair = Ed25519KeyPair::generate();
        let x25519_keypair = X25519KeyPair::generate();
        Ok(Self {
            device_id,
            ed25519_keypair,
            x25519_keypair,
            storage,
        })
    }

    /// Generates QR Code string to display on child phone for parent pairing
    pub fn generate_pairing_qr(
        &self,
        child_name: &str,
        current_time: u64,
        setup_token: &str,
    ) -> Result<String, CoreError> {
        // Save current setup token to device state
        self.storage.set_device_state("pending_setup_token", setup_token)?;

        let payload = PairingQrPayload::new(
            self.device_id.clone(),
            child_name.to_string(),
            self.ed25519_keypair.public_key_hex(),
            self.x25519_keypair.public_key_hex(),
            setup_token.to_string(),
            current_time,
        );
        payload.to_qr_string()
    }

    /// Processes incoming raw Telegram wire text (from getUpdates long-poll)
    pub fn process_wire_message(
        &self,
        wire_text: &str,
        current_time: u64,
        battery_level: u8,
        is_locked: bool,
    ) -> Result<Option<ProcessedCommandResult>, CoreError> {
        let parsed = TelegramWire::parse_message_text(wire_text);

        let signed_cmd = match parsed {
            ParsedWireMessage::PlainCommand(json) => SignedCommand::from_json(&json)?,
            ParsedWireMessage::EncryptedCommand(b64) => {
                // If encrypted, decrypt using a known shared secret
                // First try to find parents with shared secret
                let parents = self.storage.list_parents()?;
                let mut decrypted_cmd: Option<SignedCommand> = None;
                for p in parents {
                    if let Some(secret_hex) = p.shared_secret_hex {
                        if let Ok(key_bytes) = hex::decode(&secret_hex) {
                            if key_bytes.len() == 32 {
                                let mut k = [0u8; 32];
                                k.copy_from_slice(&key_bytes);
                                if let Ok(plaintext) = ChaChaCipher::decrypt(&k, &b64) {
                                    if let Ok(json_str) = String::from_utf8(plaintext) {
                                        if let Ok(cmd) = SignedCommand::from_json(&json_str) {
                                            decrypted_cmd = Some(cmd);
                                            break;
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                match decrypted_cmd {
                    Some(cmd) => cmd,
                    None => return Ok(None), // Not meant for us or unknown key
                }
            }
            _ => return Ok(None), // Not a command message
        };

        // 1. Check if command is targeting this device
        if signed_cmd.payload.target_device_id != self.device_id {
            return Ok(None); // Silently ignore commands for other child devices
        }

        // 2. Validate time window (Anti-Expiry and Anti-Future-Drift)
        signed_cmd.validate_time_window(current_time)?;

        // 3. Verify Nonce (Anti-Replay)
        if self.storage.exists_nonce(&signed_cmd.payload.nonce)? {
            return Err(CoreError::ReplayedNonce(signed_cmd.payload.nonce.clone()));
        }

        // 4. Check Authorization & Verify Signature
        match &signed_cmd.payload.action {
            Action::PairConfirm {
                child_device_id,
                parent_x25519_pubkey,
                setup_token,
                parent_alias,
            } => {
                if child_device_id != &self.device_id {
                    return Err(CoreError::TargetMismatch {
                        expected: self.device_id.clone(),
                        actual: child_device_id.clone(),
                    });
                }
                let expected_token = self
                    .storage
                    .get_device_state("pending_setup_token")?
                    .unwrap_or_default();
                if expected_token.is_empty() || &expected_token != setup_token {
                    return Err(CoreError::InvalidSetupToken(
                        "Setup token does not match pending pairing token".into(),
                    ));
                }

                // Verify signature with sender's key
                signed_cmd.verify()?;

                // Compute Diffie-Hellman Shared Secret with Parent
                let shared_secret = self.x25519_keypair.diffie_hellman(parent_x25519_pubkey)?;
                let shared_secret_hex = hex::encode(shared_secret);

                // Add to trusted parents
                self.storage.add_trusted_parent(&TrustedParent {
                    parent_pubkey: signed_cmd.payload.sender_pubkey.clone(),
                    alias: parent_alias.clone(),
                    created_at: current_time,
                    is_active: true,
                    shared_secret_hex: Some(shared_secret_hex),
                })?;

                // Clear pending token
                self.storage.delete_device_state("pending_setup_token")?;
            }
            _ => {
                // Must be an already trusted parent
                let is_trusted = self
                    .storage
                    .is_parent_trusted(&signed_cmd.payload.sender_pubkey)?;
                if !is_trusted {
                    return Err(CoreError::UnauthorizedParent(
                        signed_cmd.payload.sender_pubkey.clone(),
                    ));
                }

                // Verify signature
                signed_cmd.verify()?;
            }
        }

        // 5. Save Nonce to prevent replay
        self.storage.save_nonce(&signed_cmd.payload.nonce, current_time)?;

        // Prune old nonces (> 10 minutes)
        let _ = self.storage.prune_old_nonces(current_time, 600);

        // 6. Execute Policy Updates in SQLite
        self.apply_action_to_storage(&signed_cmd.payload.action, current_time)?;

        // 7. Create Signed ACK
        let ack_payload = AckPayload {
            command_id: signed_cmd.payload.command_id.clone(),
            child_device_id: self.device_id.clone(),
            status: AckStatus::Success,
            message: Some("Applied".to_string()),
            battery_level,
            is_device_locked: is_locked,
            timestamp: current_time,
        };
        let signed_ack = ack_payload.sign(&self.ed25519_keypair)?;
        let ack_json = signed_ack.to_json()?;

        Ok(Some(ProcessedCommandResult {
            command_id: signed_cmd.payload.command_id,
            sender_pubkey: signed_cmd.payload.sender_pubkey,
            action: signed_cmd.payload.action,
            ack_json,
        }))
    }

    fn apply_action_to_storage(&self, action: &Action, current_time: u64) -> Result<(), CoreError> {
        match action {
            Action::LockNow { duration_seconds, .. } => {
                if let Some(secs) = duration_seconds {
                    let lock_until = current_time + secs;
                    self.storage
                        .set_device_state("lock_until_epoch", &lock_until.to_string())?;
                } else {
                    self.storage.set_device_state("manual_lock", "1")?;
                }
            }
            Action::UnlockNow => {
                self.storage.delete_device_state("manual_lock")?;
                self.storage.delete_device_state("lock_until_epoch")?;
            }
            Action::PauseDevice { duration_minutes } => {
                let lock_until = current_time + (duration_minutes * 60);
                self.storage
                    .set_device_state("lock_until_epoch", &lock_until.to_string())?;
            }
            Action::GrantBonusTime { package_name, minutes } => {
                if let Some(pkg) = package_name {
                    if let Some(mut limit) = self.storage.get_app_limit(pkg)? {
                        limit.daily_limit_minutes += *minutes as u32;
                        self.storage.save_app_limit(&limit)?;
                    }
                } else {
                    // Reduce lock duration if active
                    if let Some(val) = self.storage.get_device_state("lock_until_epoch")? {
                        if let Ok(until) = val.parse::<u64>() {
                            let bonus_seconds = minutes * 60;
                            if until > current_time + bonus_seconds {
                                let new_until = until - bonus_seconds;
                                self.storage
                                    .set_device_state("lock_until_epoch", &new_until.to_string())?;
                            } else {
                                self.storage.delete_device_state("lock_until_epoch")?;
                            }
                        }
                    }
                }
            }
            Action::SetBedtimeSchedule {
                schedule_id,
                name,
                days_of_week,
                start_time,
                end_time,
                mode,
            } => {
                let schedule = PolicySchedule {
                    id: schedule_id.clone(),
                    name: name.clone(),
                    days_of_week: *days_of_week,
                    start_time: start_time.clone(),
                    end_time: end_time.clone(),
                    mode: mode.clone(),
                    is_enabled: true,
                };
                self.storage.save_schedule(&schedule)?;
            }
            Action::DeleteSchedule { schedule_id } => {
                self.storage.delete_schedule(schedule_id)?;
            }
            Action::SetAppLimit {
                package_name,
                app_name,
                daily_limit_minutes,
                is_blocked,
            } => {
                let limit = AppLimit {
                    package_name: package_name.clone(),
                    app_name: app_name.clone(),
                    daily_limit_minutes: *daily_limit_minutes,
                    used_seconds_today: 0,
                    is_blocked: *is_blocked,
                    last_reset_date: "".into(),
                };
                self.storage.save_app_limit(&limit)?;
            }
            Action::RemoveAppLimit { package_name } => {
                self.storage.remove_app_limit(package_name)?;
            }
            Action::PairConfirm { .. } | Action::Ping | Action::RequestUsageReport { .. } => {
                // Handled in caller or no storage mutation
            }
        }
        Ok(())
    }

    pub fn evaluate_device_lock(
        &self,
        current_time: u64,
        current_hhmm: &str,
        day_of_week: u8,
    ) -> Result<DeviceLockStatus, CoreError> {
        PolicyEngine::evaluate_device_lock(&self.storage, current_time, current_hhmm, day_of_week)
    }

    pub fn evaluate_app_access(
        &self,
        package_name: &str,
        current_time: u64,
        current_hhmm: &str,
        day_of_week: u8,
        today_date_str: &str,
    ) -> Result<AppAccessDecision, CoreError> {
        PolicyEngine::evaluate_app_access(
            &self.storage,
            package_name,
            current_time,
            current_hhmm,
            day_of_week,
            today_date_str,
        )
    }

    pub fn record_app_usage(
        &self,
        package_name: &str,
        duration_seconds: u64,
        current_time: u64,
        today_date_str: &str,
    ) -> Result<(), CoreError> {
        self.storage
            .update_app_usage(package_name, duration_seconds, today_date_str)?;
        self.storage
            .log_usage(package_name, duration_seconds, current_time)?;
        Ok(())
    }
}
