use chacha20poly1305::{
    aead::{Aead, KeyInit, OsRng as AeadOsRng},
    ChaCha20Poly1305, Nonce, Key
};
use base64::prelude::*;
use rand::RngCore;
use crate::error::CoreError;

pub struct ChaChaCipher;

impl ChaChaCipher {
    /// Encrypts plaintext bytes using ChaCha20-Poly1305 with a random 12-byte nonce.
    /// Output format: base64( 12_bytes_nonce || ciphertext_with_auth_tag )
    pub fn encrypt(key: &[u8; 32], plaintext: &[u8]) -> Result<String, CoreError> {
        let cipher = ChaCha20Poly1305::new(Key::from_slice(key));
        
        let mut nonce_bytes = [0u8; 12];
        AeadOsRng.fill_bytes(&mut nonce_bytes);
        let nonce = Nonce::from_slice(&nonce_bytes);

        let ciphertext = cipher
            .encrypt(nonce, plaintext)
            .map_err(|e| CoreError::CryptoError(format!("ChaCha20 encryption failed: {}", e)))?;

        let mut combined = Vec::with_capacity(12 + ciphertext.len());
        combined.extend_from_slice(&nonce_bytes);
        combined.extend_from_slice(&ciphertext);

        Ok(BASE64_STANDARD.encode(combined))
    }

    /// Decrypts a base64 payload containing 12-byte nonce + ciphertext + tag.
    pub fn decrypt(key: &[u8; 32], payload_base64: &str) -> Result<Vec<u8>, CoreError> {
        let combined = BASE64_STANDARD
            .decode(payload_base64)
            .map_err(|e| CoreError::CryptoError(format!("Base64 decode error: {}", e)))?;

        if combined.len() < 12 + 16 {
            return Err(CoreError::CryptoError(
                "Ciphertext too short (must contain 12-byte nonce + 16-byte tag)".into(),
            ));
        }

        let nonce_bytes = &combined[0..12];
        let ciphertext = &combined[12..];

        let cipher = ChaCha20Poly1305::new(Key::from_slice(key));
        let nonce = Nonce::from_slice(nonce_bytes);

        let plaintext = cipher
            .decrypt(nonce, ciphertext)
            .map_err(|e| CoreError::CryptoError(format!("ChaCha20 decryption failed / auth tag mismatch: {}", e)))?;

        Ok(plaintext)
    }
}
