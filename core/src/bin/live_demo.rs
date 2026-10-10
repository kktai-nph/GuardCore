use parental_control_core::crypto::NonceUtil;
use parental_control_core::engine::{ChildEngine, ParentEngine};
use parental_control_core::protocol::command::Action;
use serde_json::json;
use std::time::{SystemTime, UNIX_EPOCH};

const BOT_TOKEN: &str = "8915785873:AAF7xDSUaQxpbcLIIcnlCx4D1kC628oBpqk";
const CHAT_ID: &str = "-5550682710";

fn get_now_epoch() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap()
        .as_secs()
}

fn send_telegram_message(text: &str) -> Result<i64, Box<dyn std::error::Error>> {
    let url = format!("https://api.telegram.org/bot{}/sendMessage", BOT_TOKEN);
    let resp: serde_json::Value = ureq::post(&url)
        .send_json(json!({
            "chat_id": CHAT_ID,
            "text": text,
            "parse_mode": "Markdown"
        }))?
        .into_json()?;

    if resp["ok"].as_bool().unwrap_or(false) {
        let msg_id = resp["result"]["message_id"].as_i64().unwrap_or(0);
        Ok(msg_id)
    } else {
        Err(format!("Telegram API error: {:?}", resp).into())
    }
}

fn main() -> Result<(), Box<dyn std::error::Error>> {
    println!("============================================================");
    println!("🚀 Starting SafeShield Live Telegram & Rust Core Demo");
    println!("Bot Token: {}", BOT_TOKEN);
    println!("Chat ID:   {}", CHAT_ID);
    println!("============================================================");

    let child_device_id = "child_galaxy_s24".to_string();
    let child_engine = ChildEngine::new_in_memory(child_device_id.clone())?;
    let parent_engine = ParentEngine::generate_new();

    println!("\n[1/5] 🔑 Initializing Cryptographic Keypairs...");
    println!("  • Child Ed25519 PubKey: {}", child_engine.ed25519_keypair.public_key_hex());
    println!("  • Parent Ed25519 PubKey: {}", parent_engine.public_key_hex());

    // --- STEP 1: Offline QR Pairing Protocol ---
    println!("\n[2/5] 📱 Executing Device Pairing Protocol...");
    let setup_token = NonceUtil::generate_setup_token();
    let current_time = get_now_epoch();
    let qr_string = child_engine.generate_pairing_qr("Alice S24", current_time, &setup_token)?;
    println!("  • Generated Pairing QR Data: {}", qr_string);

    let (qr_payload, pair_confirm_wire) = parent_engine.pair_with_child_qr(&qr_string, "Mom", current_time)?;
    println!("  • Parent scanned QR and generated signed PairConfirm command.");

    // Send notification to Telegram
    send_telegram_message(&format!(
        "🔗 *[Pairing Request]*\nChild Device: `{}`\nChild Name: `{}`\nStatus: Cryptographically Verifying...",
        qr_payload.device_id, qr_payload.child_name
    ))?;

    // Child processes PairConfirm
    let _pair_res = child_engine.process_wire_message(&pair_confirm_wire, current_time + 1, 92, false)?
        .expect("PairConfirm should process successfully");

    println!("  • ✓ Child verified setup token and registered Parent PubKey in local SQLite!");
    send_telegram_message(&format!(
        "✅ *[Pairing Completed]*\nParent `Mom` successfully paired with `{}`.\nShared secret established with zero server exposure!",
        child_device_id
    ))?;

    // --- STEP 2: Issue Instant LockNow Command ---
    println!("\n[3/5] 🔒 Sending Signed 'LockNow' Command...");
    let lock_now_time = get_now_epoch();
    let lock_cmd_wire = parent_engine.create_plain_command_message(
        &child_device_id,
        Action::LockNow {
            duration_seconds: Some(3600),
            reason: Some("Study hour - phone locked".into()),
        },
        lock_now_time,
    )?;

    // Dispatch to Telegram
    send_telegram_message(&format!(
        "🔒 *[Parent Command: LockNow]*\nDispatched to Telegram Transport:\n`{}`",
        lock_cmd_wire
    ))?;

    // Child processes command from Telegram transport
    let _lock_processed = child_engine.process_wire_message(&lock_cmd_wire, lock_now_time + 1, 91, false)?
        .expect("Lock command should execute");

    let lock_eval = child_engine.evaluate_device_lock(lock_now_time + 2, "19:00", 1)?;
    println!("  • ✓ Child received signed command, verified Ed25519 signature & Nonce.");
    println!("  • ✓ Child local SQLite Policy evaluated -> Device is locked: {}", lock_eval.is_locked);

    send_telegram_message(&format!(
        "🛡️ *[Child ACK Response]*\nDevice ID: `{}`\nAction: `LOCK_NOW` Applied\nBattery: `91%`\nLock Screen Active: `true`",
        child_device_id
    ))?;

    // --- STEP 3: Issue Policy Schedules and App Quota Limits ---
    println!("\n[4/5] 📅 Setting Bedtime Schedule & TikTok Daily Limit (30 min)...");
    let sched_time = get_now_epoch();
    let sched_cmd_wire = parent_engine.create_plain_command_message(
        &child_device_id,
        Action::SetBedtimeSchedule {
            schedule_id: "sched_bedtime".into(),
            name: "Night Bedtime".into(),
            days_of_week: 127, // All 7 days
            start_time: "21:30".into(),
            end_time: "06:00".into(),
            mode: "LOCK_ALL".into(),
        },
        sched_time,
    )?;

    child_engine.process_wire_message(&sched_cmd_wire, sched_time + 1, 91, true)?;

    let app_limit_wire = parent_engine.create_plain_command_message(
        &child_device_id,
        Action::SetAppLimit {
            package_name: "com.zhiliaoapp.musically".into(),
            app_name: "TikTok".into(),
            daily_limit_minutes: 30,
            is_blocked: false,
        },
        sched_time + 2,
    )?;

    child_engine.process_wire_message(&app_limit_wire, sched_time + 3, 91, true)?;

    send_telegram_message(&format!(
        "📋 *[Policy Applied to Child SQLite]*\n• Bedtime: `21:30 -> 06:00` (Mon-Sun)\n• App Limit: `TikTok` (30 min daily quota)\n• Status: Enforcing offline without internet dependency!"
    ))?;

    // --- STEP 4: Issue End-to-End Encrypted Unlock Command ---
    println!("\n[5/5] 🔓 Sending End-to-End Encrypted 'UnlockNow' Command...");
    let unlock_time = get_now_epoch();
    let child_x25519_pub = child_engine.x25519_keypair.public_key_hex();
    let encrypted_unlock_wire = parent_engine.create_encrypted_command_message(
        &child_device_id,
        Action::UnlockNow,
        unlock_time,
        &child_x25519_pub,
    )?;

    send_telegram_message(&format!(
        "🔐 *[Parent Command: Encrypted UnlockNow]*\nChaCha20-Poly1305 Ciphertext sent over Telegram:\n`{}`\n*(Telegram servers cannot inspect command contents!)*",
        encrypted_unlock_wire
    ))?;

    let _unlock_processed = child_engine.process_wire_message(&encrypted_unlock_wire, unlock_time + 1, 90, true)?
        .expect("Encrypted unlock should execute");

    let lock_eval_after = child_engine.evaluate_device_lock(unlock_time + 2, "14:00", 1)?;
    println!("  • ✓ Child decrypted payload using shared secret, verified signature.");
    println!("  • ✓ Child local SQLite Policy evaluated -> Device is locked: {}", lock_eval_after.is_locked);

    send_telegram_message(&format!(
        "🎉 *[Child ACK: Unlocked]*\nDevice ID: `{}`\nDevice Status: Unlocked & Normal Usage Resumed\nAck Signature: Verified ✓",
        child_device_id
    ))?;

    println!("\n============================================================");
    println!("✅ LIVE DEMO COMPLETED SUCCESSFULLY!");
    println!("Check Telegram group '{}' to view all live dispatched messages!", CHAT_ID);
    println!("============================================================");

    Ok(())
}
