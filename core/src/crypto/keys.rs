use ed25519_dalek::{SigningKey, VerifyingKey, Signature, Signer, Verifier};
use x25519_dalek::{PublicKey as X25519PublicKey, StaticSecret};
use rand::rngs::OsRng;
use crate::error::CoreError;

#[derive(Clone)]
pub struct Ed25519KeyPair {
    signing_key: SigningKey,
}

impl Ed25519KeyPair {
    pub fn generate() -> Self {
        let mut csprng = OsRng;
        let signing_key = SigningKey::generate(&mut csprng);
        Self { signing_key }
    }

    pub fn from_secret_bytes(bytes: &[u8; 32]) -> Self {
        let signing_key = SigningKey::from_bytes(bytes);
        Self { signing_key }
    }

    pub fn from_secret_hex(hex_str: &str) -> Result<Self, CoreError> {
        let bytes = hex::decode(hex_str)
            .map_err(|e| CoreError::CryptoError(format!("Invalid secret key hex: {}", e)))?;
        if bytes.len() != 32 {
            return Err(CoreError::CryptoError("Secret key must be 32 bytes".into()));
        }
        let mut key_bytes = [0u8; 32];
        key_bytes.copy_from_slice(&bytes);
        Ok(Self::from_secret_bytes(&key_bytes))
    }

    pub fn secret_bytes(&self) -> [u8; 32] {
        self.signing_key.to_bytes()
    }

    pub fn secret_hex(&self) -> String {
        hex::encode(self.secret_bytes())
    }

    pub fn verifying_key(&self) -> VerifyingKey {
        self.signing_key.verifying_key()
    }

    pub fn public_key_bytes(&self) -> [u8; 32] {
        self.verifying_key().to_bytes()
    }

    pub fn public_key_hex(&self) -> String {
        hex::encode(self.public_key_bytes())
    }

    pub fn sign(&self, message: &[u8]) -> String {
        let sig: Signature = self.signing_key.sign(message);
        hex::encode(sig.to_bytes())
    }

    pub fn verify_signature(
        public_key_hex: &str,
        message: &[u8],
        signature_hex: &str,
    ) -> Result<(), CoreError> {
        let pubkey_bytes = hex::decode(public_key_hex)
            .map_err(|e| CoreError::CryptoError(format!("Invalid public key hex: {}", e)))?;
        if pubkey_bytes.len() != 32 {
            return Err(CoreError::CryptoError("Public key must be 32 bytes".into()));
        }

        let mut key_array = [0u8; 32];
        key_array.copy_from_slice(&pubkey_bytes);

        let verifying_key = VerifyingKey::from_bytes(&key_array)
            .map_err(|e| CoreError::CryptoError(format!("Invalid Ed25519 public key: {}", e)))?;

        let sig_bytes = hex::decode(signature_hex)
            .map_err(|e| CoreError::CryptoError(format!("Invalid signature hex: {}", e)))?;
        if sig_bytes.len() != 64 {
            return Err(CoreError::CryptoError("Signature must be 64 bytes".into()));
        }

        let mut sig_array = [0u8; 64];
        sig_array.copy_from_slice(&sig_bytes);
        let signature = Signature::from_bytes(&sig_array);

        verifying_key
            .verify(message, &signature)
            .map_err(|_| CoreError::SignatureInvalid)
    }
}

#[derive(Clone)]
pub struct X25519KeyPair {
    secret: StaticSecret,
}

impl X25519KeyPair {
    pub fn generate() -> Self {
        let mut csprng = OsRng;
        let secret = StaticSecret::random_from_rng(&mut csprng);
        Self { secret }
    }

    pub fn from_secret_bytes(bytes: [u8; 32]) -> Self {
        let secret = StaticSecret::from(bytes);
        Self { secret }
    }

    pub fn from_secret_hex(hex_str: &str) -> Result<Self, CoreError> {
        let bytes = hex::decode(hex_str)
            .map_err(|e| CoreError::CryptoError(format!("Invalid secret hex: {}", e)))?;
        if bytes.len() != 32 {
            return Err(CoreError::CryptoError("X25519 secret key must be 32 bytes".into()));
        }
        let mut key_bytes = [0u8; 32];
        key_bytes.copy_from_slice(&bytes);
        Ok(Self::from_secret_bytes(key_bytes))
    }

    pub fn secret_bytes(&self) -> [u8; 32] {
        self.secret.to_bytes()
    }

    pub fn secret_hex(&self) -> String {
        hex::encode(self.secret_bytes())
    }

    pub fn public_key(&self) -> X25519PublicKey {
        X25519PublicKey::from(&self.secret)
    }

    pub fn public_key_hex(&self) -> String {
        hex::encode(self.public_key().as_bytes())
    }

    pub fn diffie_hellman(&self, peer_public_key_hex: &str) -> Result<[u8; 32], CoreError> {
        let peer_bytes = hex::decode(peer_public_key_hex)
            .map_err(|e| CoreError::CryptoError(format!("Invalid peer public key hex: {}", e)))?;
        if peer_bytes.len() != 32 {
            return Err(CoreError::CryptoError("Peer public key must be 32 bytes".into()));
        }
        let mut peer_array = [0u8; 32];
        peer_array.copy_from_slice(&peer_bytes);
        let peer_pub = X25519PublicKey::from(peer_array);
        let shared_secret = self.secret.diffie_hellman(&peer_pub);
        Ok(shared_secret.to_bytes())
    }
}
