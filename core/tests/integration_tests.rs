use parental_control_core::crypto::{ChaChaCipher, Ed25519KeyPair, NonceUtil, X25519KeyPair};
use parental_control_core::engine::{ChildEngine, ParentEngine};
use parental_control_core::error::CoreError;
use parental_control_core::policy::engine::AppAccessDecision;
use parental_control_core::policy::storage::PolicySchedule;
use parental_control_core::protocol::command::Action;
use parental_control_core::protocol::response::AckStatus;
use parental_control_core::protocol::telegram::{ParsedWireMessage, TelegramWire};

#[test]
fn test_ed25519_signing_and_verification() {
    let keypair = Ed25519KeyPair::generate();
    let pubkey_hex = keypair.public_key_hex();
    let message = b"Hello Parental Control Secure Command";

    let signature = keypair.sign(message);
    assert!(!signature.is_empty());

    // Verify valid signature
    let res = Ed25519KeyPair::verify_signature(&pubkey_hex, message, &signature);
    assert!(res.is_ok());

    // Verify invalid signature or modified message
    let tampered_message = b"Tampered Message";
    let res_tampered = Ed25519KeyPair::verify_signature(&pubkey_hex, tampered_message, &signature);
    assert_eq!(res_tampered, Err(CoreError::SignatureInvalid));
}

#[test]
fn test_x25519_and_chacha20_encryption() {
    let parent_x = X25519KeyPair::generate();
    let child_x = X25519KeyPair::generate();

    // Compute shared secret independently
    let secret_parent_side = parent_x.diffie_hellman(&child_x.public_key_hex()).unwrap();
    let secret_child_side = child_x.diffie_hellman(&parent_x.public_key_hex()).unwrap();

    assert_eq!(secret_parent_side, secret_child_side);

    let plaintext = b"{\"action\":\"LOCK_NOW\",\"reason\":\"Bedtime\"}";
    let ciphertext_b64 = ChaChaCipher::encrypt(&secret_parent_side, plaintext).unwrap();

    let decrypted = ChaChaCipher::decrypt(&secret_child_side, &ciphertext_b64).unwrap();
    assert_eq!(decrypted, plaintext);
}

#[test]
fn test_end_to_end_device_pairing_and_commands() {
    let current_time = 1760000000u64;
    let child_device_id = "child_dev_001".to_string();

    // 1. Initialize Child Engine in memory
    let child_engine = ChildEngine::new_in_memory(child_device_id.clone()).unwrap();

    // 2. Child generates Pairing QR Code
    let setup_token = NonceUtil::generate_setup_token();
    let qr_string = child_engine
        .generate_pairing_qr("Alice", current_time, &setup_token)
        .unwrap();
    assert!(qr_string.starts_with("CRT_PAIR:"));

    // 3. Parent Engine scans QR code & generates PairConfirm wire message
    let parent_engine = ParentEngine::generate_new();
    let (qr_data, pair_confirm_wire) = parent_engine
        .pair_with_child_qr(&qr_string, "Mom's Phone", current_time)
        .unwrap();

    assert_eq!(qr_data.device_id, child_device_id);
    assert_eq!(qr_data.child_name, "Alice");

    // 4. Child processes PairConfirm message from Telegram wire
    let process_result = child_engine
        .process_wire_message(&pair_confirm_wire, current_time + 1, 85, false)
        .unwrap();

    assert!(process_result.is_some());
    let res = process_result.unwrap();
    assert_eq!(res.sender_pubkey, parent_engine.public_key_hex());

    // 5. Verify that Parent is now trusted in Child's database
    assert!(child_engine
        .storage
        .is_parent_trusted(&parent_engine.public_key_hex())
        .unwrap());

    // 6. Parent sends a LOCK_NOW command (Plain)
    let lock_cmd_wire = parent_engine
        .create_plain_command_message(
            &child_device_id,
            Action::LockNow {
                duration_seconds: Some(3600),
                reason: Some("Time to do homework".into()),
            },
            current_time + 10,
        )
        .unwrap();

    let lock_res = child_engine
        .process_wire_message(&lock_cmd_wire, current_time + 11, 85, false)
        .unwrap()
        .unwrap();

    assert_eq!(lock_res.command_id.len(), 32);

    // 7. Check if child device is now locked
    let lock_status = child_engine
        .evaluate_device_lock(current_time + 12, "16:00", 1)
        .unwrap();
    assert!(lock_status.is_locked);

    // 8. Parent sends UNLOCK_NOW command (Encrypted)
    let child_x_pub = child_engine.x25519_keypair.public_key_hex();
    let unlock_cmd_wire = parent_engine
        .create_encrypted_command_message(
            &child_device_id,
            Action::UnlockNow,
            current_time + 20,
            &child_x_pub,
        )
        .unwrap();

    let unlock_res = child_engine
        .process_wire_message(&unlock_cmd_wire, current_time + 21, 85, true)
        .unwrap()
        .unwrap();

    // Verify ACK in Parent
    let ack_verified = ParentEngine::verify_ack(&unlock_res.ack_json, &child_engine.ed25519_keypair.public_key_hex());
    assert!(ack_verified.is_ok());
    assert_eq!(ack_verified.unwrap().payload.status, AckStatus::Success);

    // Device should now be unlocked
    let lock_status_after = child_engine
        .evaluate_device_lock(current_time + 22, "16:00", 1)
        .unwrap();
    assert!(!lock_status_after.is_locked);
}

#[test]
fn test_anti_replay_and_expiry_protections() {
    let current_time = 1760000000u64;
    let child_device_id = "child_dev_002".to_string();

    let child_engine = ChildEngine::new_in_memory(child_device_id.clone()).unwrap();
    let parent_engine = ParentEngine::generate_new();

    // Add parent as trusted directly
    child_engine
        .storage
        .add_trusted_parent(&parental_control_core::policy::storage::TrustedParent {
            parent_pubkey: parent_engine.public_key_hex(),
            alias: "Dad".into(),
            created_at: current_time,
            is_active: true,
            shared_secret_hex: None,
        })
        .unwrap();

    let cmd_wire = parent_engine
        .create_plain_command_message(&child_device_id, Action::Ping, current_time)
        .unwrap();

    // First execution -> Succeeds
    let res1 = child_engine.process_wire_message(&cmd_wire, current_time + 1, 90, false);
    assert!(res1.is_ok());
    assert!(res1.unwrap().is_some());

    // Replay same message -> Rejected with ReplayedNonce
    let res_replayed = child_engine.process_wire_message(&cmd_wire, current_time + 2, 90, false);
    assert!(matches!(res_replayed, Err(CoreError::ReplayedNonce(_))));

    // Expired command (> 60s old) -> Rejected with ExpiredCommand
    let old_cmd_wire = parent_engine
        .create_plain_command_message(&child_device_id, Action::Ping, current_time - 100)
        .unwrap();
    let res_expired = child_engine.process_wire_message(&old_cmd_wire, current_time, 90, false);
    assert!(matches!(res_expired, Err(CoreError::ExpiredCommand { .. })));

    // Future timestamp drift (> 30s in future) -> Rejected with InvalidTimestampDrift
    let future_cmd_wire = parent_engine
        .create_plain_command_message(&child_device_id, Action::Ping, current_time + 100)
        .unwrap();
    let res_future = child_engine.process_wire_message(&future_cmd_wire, current_time, 90, false);
    assert!(matches!(res_future, Err(CoreError::InvalidTimestampDrift { .. })));

    // Command from unauthorized parent -> Rejected with UnauthorizedParent
    let attacker_engine = ParentEngine::generate_new();
    let attacker_wire = attacker_engine
        .create_plain_command_message(&child_device_id, Action::UnlockNow, current_time)
        .unwrap();
    let res_unauth = child_engine.process_wire_message(&attacker_wire, current_time + 1, 90, false);
    assert!(matches!(res_unauth, Err(CoreError::UnauthorizedParent(_))));
}

#[test]
fn test_policy_schedules_and_app_quotas() {
    let child_device_id = "child_dev_003".to_string();
    let child_engine = ChildEngine::new_in_memory(child_device_id).unwrap();

    // 1. Bedtime Schedule: Monday to Friday (bitmask = 1|2|4|8|16 = 31), from 21:00 to 06:00
    let bedtime = PolicySchedule {
        id: "bedtime_01".into(),
        name: "Bedtime".into(),
        days_of_week: 31,
        start_time: "21:00".into(),
        end_time: "06:00".into(),
        mode: "LOCK_ALL".into(),
        is_enabled: true,
    };
    child_engine.storage.save_schedule(&bedtime).unwrap();

    // Monday (day 1) at 22:30 -> Should be locked
    let status_night = child_engine.evaluate_device_lock(1000, "22:30", 1).unwrap();
    assert!(status_night.is_locked);

    // Monday (day 1) at 05:30 -> Should be locked (overnight window)
    let status_early_morning = child_engine.evaluate_device_lock(1000, "05:30", 1).unwrap();
    assert!(status_early_morning.is_locked);

    // Monday (day 1) at 14:00 -> Not locked
    let status_daytime = child_engine.evaluate_device_lock(1000, "14:00", 1).unwrap();
    assert!(!status_daytime.is_locked);

    // Saturday (day 6, not in bitmask 31) at 22:30 -> Not locked
    let status_saturday_night = child_engine.evaluate_device_lock(1000, "22:30", 6).unwrap();
    assert!(!status_saturday_night.is_locked);

    // 2. App Quota Limit: TikTok (30 mins daily)
    child_engine
        .storage
        .save_app_limit(&parental_control_core::policy::storage::AppLimit {
            package_name: "com.zhiliaoapp.musically".into(),
            app_name: "TikTok".into(),
            daily_limit_minutes: 30,
            used_seconds_today: 0,
            is_blocked: false,
            last_reset_date: "2026-10-10".into(),
        })
        .unwrap();

    // Check initially -> Allowed (1800s remaining)
    let decision = child_engine
        .evaluate_app_access("com.zhiliaoapp.musically", 1000, "14:00", 1, "2026-10-10")
        .unwrap();
    assert_eq!(
        decision,
        AppAccessDecision::Allowed {
            remaining_seconds_today: Some(1800)
        }
    );

    // Record 20 mins (1200s) usage
    child_engine
        .record_app_usage("com.zhiliaoapp.musically", 1200, 1000, "2026-10-10")
        .unwrap();

    let decision_after_20 = child_engine
        .evaluate_app_access("com.zhiliaoapp.musically", 1000, "14:00", 1, "2026-10-10")
        .unwrap();
    assert_eq!(
        decision_after_20,
        AppAccessDecision::Allowed {
            remaining_seconds_today: Some(600)
        }
    );

    // Record another 15 mins (900s) -> Exceeds quota (total 2100s > 1800s)
    child_engine
        .record_app_usage("com.zhiliaoapp.musically", 900, 1000, "2026-10-10")
        .unwrap();

    let decision_exceeded = child_engine
        .evaluate_app_access("com.zhiliaoapp.musically", 1000, "14:00", 1, "2026-10-10")
        .unwrap();
    assert!(matches!(
        decision_exceeded,
        AppAccessDecision::DailyQuotaExceeded { limit_minutes: 30, used_seconds: 2100 }
    ));

    // Dial / Emergency app is always allowed regardless of lock or quota
    let dialer_decision = child_engine
        .evaluate_app_access("com.google.android.dialer", 1000, "22:30", 1, "2026-10-10")
        .unwrap();
    assert_eq!(
        dialer_decision,
        AppAccessDecision::Allowed {
            remaining_seconds_today: None
        }
    );
}

#[test]
fn test_telegram_wire_and_updates_parser() {
    let mock_updates_json = r#"{
        "ok": true,
        "result": [
            {
                "update_id": 1001,
                "message": {
                    "message_id": 50,
                    "date": 1760000000,
                    "text": "CRT_CMD:{\"payload\":{\"command_id\":\"123\",\"sender_pubkey\":\"abc\",\"target_device_id\":\"c1\",\"timestamp\":1760000000,\"expires_at\":1760000060,\"nonce\":\"n1\",\"action\":{\"type\":\"Ping\"}},\"signature\":\"sig\"}"
                }
            },
            {
                "update_id": 1002,
                "message": {
                    "message_id": 51,
                    "date": 1760000001,
                    "text": "/start"
                }
            }
        ]
    }"#;

    let parsed = TelegramWire::parse_updates_json(mock_updates_json).unwrap();
    assert_eq!(parsed.len(), 1);
    assert_eq!(parsed[0].0, 1001);
    assert!(matches!(parsed[0].1, ParsedWireMessage::PlainCommand(_)));
}
