use serde::{Deserialize, Serialize};
use crate::error::CoreError;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TelegramUpdateResponse {
    pub ok: bool,
    pub result: Vec<TelegramUpdate>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TelegramUpdate {
    pub update_id: i64,
    pub message: Option<TelegramMessage>,
    pub channel_post: Option<TelegramMessage>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TelegramMessage {
    pub message_id: i64,
    pub date: i64,
    pub text: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ParsedWireMessage {
    PlainCommand(String),       // JSON string of SignedCommand
    EncryptedCommand(String),   // Base64 ciphertext
    PlainAck(String),           // JSON string of SignedAck
    EncryptedAck(String),       // Base64 ciphertext
    Ignored,
}

pub struct TelegramWire;

impl TelegramWire {
    pub const PREFIX_CMD: &'static str = "CRT_CMD:";
    pub const PREFIX_ENC_CMD: &'static str = "CRT_ENC_CMD:";
    pub const PREFIX_ACK: &'static str = "CRT_ACK:";
    pub const PREFIX_ENC_ACK: &'static str = "CRT_ENC_ACK:";

    pub fn format_command_message(signed_cmd_json: &str) -> String {
        format!("{}{}", Self::PREFIX_CMD, signed_cmd_json)
    }

    pub fn format_encrypted_command_message(encrypted_base64: &str) -> String {
        format!("{}{}", Self::PREFIX_ENC_CMD, encrypted_base64)
    }

    pub fn format_ack_message(signed_ack_json: &str) -> String {
        format!("{}{}", Self::PREFIX_ACK, signed_ack_json)
    }

    pub fn format_encrypted_ack_message(encrypted_base64: &str) -> String {
        format!("{}{}", Self::PREFIX_ENC_ACK, encrypted_base64)
    }

    pub fn parse_message_text(text: &str) -> ParsedWireMessage {
        let trimmed = text.trim();
        if let Some(idx) = trimmed.find(Self::PREFIX_CMD) {
            let payload = &trimmed[idx + Self::PREFIX_CMD.len()..];
            let clean = payload.trim_matches(|c| c == '`' || c == ' ' || c == '\n' || c == '\r');
            ParsedWireMessage::PlainCommand(clean.to_string())
        } else if let Some(idx) = trimmed.find(Self::PREFIX_ENC_CMD) {
            let payload = &trimmed[idx + Self::PREFIX_ENC_CMD.len()..];
            let clean = payload.trim_matches(|c| c == '`' || c == ' ' || c == '\n' || c == '\r');
            ParsedWireMessage::EncryptedCommand(clean.to_string())
        } else if let Some(idx) = trimmed.find(Self::PREFIX_ACK) {
            let payload = &trimmed[idx + Self::PREFIX_ACK.len()..];
            let clean = payload.trim_matches(|c| c == '`' || c == ' ' || c == '\n' || c == '\r');
            ParsedWireMessage::PlainAck(clean.to_string())
        } else if let Some(idx) = trimmed.find(Self::PREFIX_ENC_ACK) {
            let payload = &trimmed[idx + Self::PREFIX_ENC_ACK.len()..];
            let clean = payload.trim_matches(|c| c == '`' || c == ' ' || c == '\n' || c == '\r');
            ParsedWireMessage::EncryptedAck(clean.to_string())
        } else {
            ParsedWireMessage::Ignored
        }
    }

    pub fn parse_updates_json(json_str: &str) -> Result<Vec<(i64, ParsedWireMessage)>, CoreError> {
        let resp: TelegramUpdateResponse = serde_json::from_str(json_str)
            .map_err(|e| CoreError::SerializationError(format!("Invalid Telegram getUpdates JSON: {}", e)))?;

        let mut results = Vec::new();
        for update in resp.result {
            let msg = update.message.or(update.channel_post);
            if let Some(m) = msg {
                if let Some(text) = m.text {
                    let parsed = Self::parse_message_text(&text);
                    if parsed != ParsedWireMessage::Ignored {
                        results.push((update.update_id, parsed));
                    }
                }
            }
        }

        Ok(results)
    }
}
