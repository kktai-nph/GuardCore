use rand::RngCore;

pub struct NonceUtil;

impl NonceUtil {
    /// Generates a random 16-byte nonce encoded as a 32-character hexadecimal string.
    pub fn generate_nonce() -> String {
        let mut bytes = [0u8; 16];
        rand::thread_rng().fill_bytes(&mut bytes);
        hex::encode(bytes)
    }

    /// Generates a random 32-byte setup token for pairing verification.
    pub fn generate_setup_token() -> String {
        let mut bytes = [0u8; 32];
        rand::thread_rng().fill_bytes(&mut bytes);
        hex::encode(bytes)
    }

    /// Generates a UUID-like random identifier.
    pub fn generate_id() -> String {
        let mut bytes = [0u8; 16];
        rand::thread_rng().fill_bytes(&mut bytes);
        hex::encode(bytes)
    }
}
