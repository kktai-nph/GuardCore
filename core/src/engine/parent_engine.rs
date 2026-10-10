use crate::crypto::{ChaChaCipher, Ed25519KeyPair, X25519KeyPair};
use crate::error::CoreError;
use crate::protocol::command::{Action, CommandPayload, SignedCommand};
use crate::protocol::pairing::PairingQrPayload;
use crate::protocol::response::SignedAck;
use crate::protocol::telegram::TelegramWire;

pub struct ParentEngine {
    pub ed25519_keypair: Ed25519KeyPair,
    pub x25519_keypair: X25519KeyPair,
}

impl ParentEngine {
    pub fn new(secret_hex: Option<&str>, x_secret_hex: Option<&str>) -> Result<Self, CoreError> {
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
            ed25519_keypair,
            x25519_keypair,
        })
    }

    pub fn generate_new() -> Self {
        Self {
            ed25519_keypair: Ed25519KeyPair::generate(),
            x25519_keypair: X25519KeyPair::generate(),
        }
    }

    pub fn public_key_hex(&self) -> String {
        self.ed25519_keypair.public_key_hex()
    }

    pub fn x25519_public_key_hex(&self) -> String {
        self.x25519_keypair.public_key_hex()
    }

    /// Creates a signed command ready to be sent over Telegram
    pub fn create_command(
        &self,
        target_device_id: &str,
        action: Action,
        current_time: u64,
    ) -> Result<SignedCommand, CoreError> {
        let payload = CommandPayload::new(
            self.public_key_hex(),
            target_device_id.to_string(),
            action,
            current_time,
        );
        payload.sign(&self.ed25519_keypair)
    }

    /// Creates an encrypted and signed command using ChaCha20-Poly1305 with Child's X25519 pubkey
    pub fn create_encrypted_command_message(
        &self,
        target_device_id: &str,
        action: Action,
        current_time: u64,
        child_x25519_pubkey: &str,
    ) -> Result<String, CoreError> {
        let signed_cmd = self.create_command(target_device_id, action, current_time)?;
        let json_str = signed_cmd.to_json()?;

        let shared_secret = self.x25519_keypair.diffie_hellman(child_x25519_pubkey)?;
        let ciphertext_b64 = ChaChaCipher::encrypt(&shared_secret, json_str.as_bytes())?;

        Ok(TelegramWire::format_encrypted_command_message(&ciphertext_b64))
    }

    /// Creates a plain wire command message for Telegram
    pub fn create_plain_command_message(
        &self,
        target_device_id: &str,
        action: Action,
        current_time: u64,
    ) -> Result<String, CoreError> {
        let signed_cmd = self.create_command(target_device_id, action, current_time)?;
        let json_str = signed_cmd.to_json()?;
        Ok(TelegramWire::format_command_message(&json_str))
    }

    /// Scans Child's QR Code and produces the PairConfirm command message
    pub fn pair_with_child_qr(
        &self,
        qr_string: &str,
        parent_alias: &str,
        current_time: u64,
    ) -> Result<(PairingQrPayload, String), CoreError> {
        let qr_payload = PairingQrPayload::from_qr_string(qr_string)?;

        let action = Action::PairConfirm {
            child_device_id: qr_payload.device_id.clone(),
            parent_x25519_pubkey: self.x25519_public_key_hex(),
            setup_token: qr_payload.setup_token.clone(),
            parent_alias: parent_alias.to_string(),
        };

        let plain_msg = self.create_plain_command_message(&qr_payload.device_id, action, current_time)?;
        Ok((qr_payload, plain_msg))
    }

    /// Verifies ACK received from child device
    pub fn verify_ack(
        raw_ack_json: &str,
        expected_child_pubkey: &str,
    ) -> Result<SignedAck, CoreError> {
        let signed_ack = SignedAck::from_json(raw_ack_json)?;
        if signed_ack.child_pubkey != expected_child_pubkey {
            return Err(CoreError::TargetMismatch {
                expected: expected_child_pubkey.to_string(),
                actual: signed_ack.child_pubkey,
            });
        }
        signed_ack.verify()?;
        Ok(signed_ack)
    }
}
