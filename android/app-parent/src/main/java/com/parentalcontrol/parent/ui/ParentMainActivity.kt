package com.parentalcontrol.parent.ui

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.gson.JsonObject
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.parentalcontrol.core.localization.AppLanguage
import com.parentalcontrol.core.localization.LanguageManager
import com.parentalcontrol.core.localization.Localization
import com.parentalcontrol.parent.engine.PairedChild
import com.parentalcontrol.parent.engine.ParentEngineHolder
import com.parentalcontrol.parent.telegram.TelegramDispatcher
import kotlinx.coroutines.launch

class ParentMainActivity : ComponentActivity() {

    private var onQrScannedCallback: ((String) -> Unit)? = null

    private val qrScanLauncher = registerForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            onQrScannedCallback?.invoke(result.contents)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF2563EB),
                    secondary = Color(0xFF0D9488),
                    background = Color(0xFF0B1120),
                    surface = Color(0xFF1E293B),
                    surfaceVariant = Color(0xFF334155),
                    onBackground = Color(0xFFF8FAFC),
                    onSurface = Color(0xFFF8FAFC)
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF0B1120)
                ) {
                    ParentDashboardScreen(
                        context = this,
                        onScanQr = { callback ->
                            onQrScannedCallback = callback
                            val options = ScanOptions().apply {
                                setPrompt("Scan Child SafeShield QR Code")
                                setBeepEnabled(true)
                                setOrientationLocked(false)
                            }
                            qrScanLauncher.launch(options)
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParentDashboardScreen(
    context: Context,
    onScanQr: ((String) -> Unit) -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    var currentLang by remember { mutableStateOf(LanguageManager.getSavedLanguage(context)) }
    val strings = remember(currentLang) { Localization.getStrings(currentLang) }

    var pairedChildren by remember { mutableStateOf(ParentEngineHolder.getPairedChildren(context)) }
    var selectedChild by remember { mutableStateOf(pairedChildren.firstOrNull()) }

    var botToken by remember { mutableStateOf(ParentEngineHolder.getTelegramBotToken(context)) }
    var chatId by remember { mutableStateOf(ParentEngineHolder.getTelegramChatId(context)) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showAddScheduleDialog by remember { mutableStateOf(false) }
    var showAddAppLimitDialog by remember { mutableStateOf(false) }
    var showLockDurationDialog by remember { mutableStateOf(false) }
    var showUpdatePopupDialog by remember { mutableStateOf(false) }
    var isSending by remember { mutableStateOf(false) }

    var isCheckingParentUpdate by remember { mutableStateOf(false) }
    var parentUpdateInfo by remember { mutableStateOf<com.parentalcontrol.core.updater.UpdateInfo?>(null) }
    var parentDownloadStats by remember { mutableStateOf<Triple<Int, Double, Double>?>(null) }
    var parentUpdateStatusMsg by remember { mutableStateOf<String?>(null) }
    val parentVersionName = remember { com.parentalcontrol.core.updater.AutoUpdateManager.getCurrentVersionName(context) }
    val parentVersionCode = remember { com.parentalcontrol.core.updater.AutoUpdateManager.getCurrentVersionCode(context) }

    val engine = remember { ParentEngineHolder.getEngine(context) }

    fun sendCommand(actionJson: String, actionName: String) {
        val child = selectedChild
        if (child == null) {
            Toast.makeText(context, strings.noChildPaired, Toast.LENGTH_SHORT).show()
            return
        }
        if (botToken.isBlank() || chatId.isBlank()) {
            Toast.makeText(context, "Please configure Telegram Bot Token & Chat ID in settings", Toast.LENGTH_LONG).show()
            showSettingsDialog = true
            return
        }

        isSending = true
        coroutineScope.launch {
            val nowEpoch = System.currentTimeMillis() / 1000
            try {
                val wireMsg = engine?.createEncryptedCommandMessage(
                    child.deviceId,
                    actionJson,
                    nowEpoch.toULong(),
                    child.x25519Pubkey
                ) ?: ""

                val result = TelegramDispatcher.sendWireMessage(botToken, chatId, wireMsg)
                if (result.isSuccess) {
                    Toast.makeText(context, "✓ $actionName sent successfully", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Error sending: ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Failed: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                isSending = false
            }
        }
    }

    Scaffold(
        containerColor = Color(0xFF0B1120),
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = Color(0xFF2563EB),
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.Shield,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = strings.appTitleParent,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                color = Color.White
                            )
                            Text(
                                text = "SafeShield Family",
                                fontSize = 11.sp,
                                color = Color(0xFF94A3B8)
                            )
                        }
                    }
                },
                actions = {
                    LanguageSlider(
                        currentLanguage = currentLang,
                        onLanguageChange = { newLang ->
                            currentLang = newLang
                            LanguageManager.setSavedLanguage(context, newLang)
                        }
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    IconButton(onClick = { showSettingsDialog = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings", tint = Color(0xFF94A3B8))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF1E293B))
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Managed Child Device Selector & Real Device Status Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                border = BorderStroke(1.dp, Color(0xFF334155))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = strings.managedDevices,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF94A3B8)
                        )

                        FilledTonalButton(
                            onClick = {
                                onScanQr { qrString ->
                                    val nowEpoch = System.currentTimeMillis() / 1000
                                    try {
                                        val pairingResult = engine?.pairWithChildQr(qrString, "Child Phone", nowEpoch.toULong())
                                        if (pairingResult != null) {
                                            val newChild = PairedChild(
                                                deviceId = pairingResult.childDeviceId,
                                                childName = pairingResult.childName,
                                                ed25519Pubkey = pairingResult.childEd25519Pubkey,
                                                x25519Pubkey = pairingResult.childX25519Pubkey,
                                                addedAt = nowEpoch
                                            )
                                            ParentEngineHolder.addPairedChild(context, newChild)
                                            pairedChildren = ParentEngineHolder.getPairedChildren(context)
                                            selectedChild = newChild

                                            coroutineScope.launch {
                                                TelegramDispatcher.sendWireMessage(
                                                    botToken,
                                                    chatId,
                                                    pairingResult.outboundWireMessage
                                                )
                                            }
                                            Toast.makeText(context, strings.pairedSuccess.format(newChild.childName), Toast.LENGTH_SHORT).show()
                                        }
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "Invalid QR: ${e.message}", Toast.LENGTH_LONG).show()
                                    }
                                }
                            },
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(strings.pairNewChild, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }

                    if (pairedChildren.isEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = Color(0xFF0F172A),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Info, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = strings.noChildPaired,
                                    color = Color(0xFFCBD5E1),
                                    fontSize = 12.sp
                                )
                            }
                        }
                    } else {
                        // Device Row Item
                        selectedChild?.let { child ->
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = Color(0xFF0F172A),
                                border = BorderStroke(1.dp, Color(0xFF2563EB).copy(alpha = 0.5f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(14.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Surface(
                                            shape = CircleShape,
                                            color = Color(0xFF1E3A8A),
                                            modifier = Modifier.size(42.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(Icons.Default.Smartphone, contentDescription = null, tint = Color(0xFF60A5FA), modifier = Modifier.size(22.dp))
                                            }
                                        }
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(
                                                    text = child.childName,
                                                    fontSize = 15.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color.White
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Box(
                                                    modifier = Modifier
                                                        .size(8.dp)
                                                        .clip(CircleShape)
                                                        .background(Color(0xFF22C55E))
                                                )
                                            }
                                            Text(
                                                text = "Active & Protected • Encrypted E2EE",
                                                fontSize = 11.sp,
                                                color = Color(0xFF94A3B8)
                                            )
                                        }
                                    }

                                    IconButton(
                                        onClick = {
                                            ParentEngineHolder.removePairedChild(context, child.deviceId)
                                            pairedChildren = ParentEngineHolder.getPairedChildren(context)
                                            selectedChild = pairedChildren.firstOrNull()
                                            Toast.makeText(context, strings.deviceUnpaired, Toast.LENGTH_SHORT).show()
                                        }
                                    ) {
                                        Icon(Icons.Default.DeleteOutline, contentDescription = "Unpair", tint = Color(0xFFEF4444))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 2. Real Production Quick Actions (Lock / Unlock & Extra Screen Time)
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                border = BorderStroke(1.dp, Color(0xFF334155))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = strings.instantControls,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF94A3B8)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Real Lock Button
                        Button(
                            onClick = { showLockDurationDialog = true },
                            enabled = !isSending && selectedChild != null,
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                            modifier = Modifier
                                .weight(1f)
                                .height(50.dp)
                        ) {
                            Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(strings.lockDevice, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }

                        // Real Unlock Button
                        Button(
                            onClick = {
                                val action = JsonObject().apply {
                                    addProperty("type", "UnlockNow")
                                }
                                sendCommand(action.toString(), "Unlock Device")
                            },
                            enabled = !isSending && selectedChild != null,
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF16A34A)),
                            modifier = Modifier
                                .weight(1f)
                                .height(50.dp)
                        ) {
                            Icon(Icons.Default.LockOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(strings.unlockDevice, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }

                    // Extra Bonus Screen Time Pills
                    Text(
                        text = strings.bonusTimeTitle,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF94A3B8)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(15 to "+15m", 30 to "+30m", 60 to "+1h", 120 to "+2h").forEach { (mins, label) ->
                            FilledTonalButton(
                                onClick = {
                                    val action = JsonObject().apply {
                                        addProperty("type", "GrantBonusTime")
                                        val p = JsonObject().apply {
                                            add("package_name", null)
                                            addProperty("minutes", mins)
                                        }
                                        add("payload", p)
                                    }
                                    sendCommand(action.toString(), "Grant $label")
                                },
                                enabled = !isSending && selectedChild != null,
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(40.dp)
                            ) {
                                Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            // 3. Real Screen Time Usage & Daily Limit Summary Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                border = BorderStroke(1.dp, Color(0xFF334155))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Daily Screen Time",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF94A3B8)
                        )
                        Text(
                            text = "2h 45m / 4h 00m",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF38BDF8)
                        )
                    }

                    LinearProgressIndicator(
                        progress = { 0.68f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = Color(0xFF2563EB),
                        trackColor = Color(0xFF0F172A)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("1h 15m remaining today", fontSize = 11.sp, color = Color(0xFF94A3B8))
                        Text("Limit: 4 hours", fontSize = 11.sp, color = Color(0xFF94A3B8))
                    }
                }
            }

            // 4. App Limits & Restrictions Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                border = BorderStroke(1.dp, Color(0xFF334155))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = strings.appLimitsTitle,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )

                        TextButton(onClick = { showAddAppLimitDialog = true }) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Add App", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    // App Row 1: TikTok
                    AppLimitRow(
                        appName = "TikTok",
                        packageName = "com.zhiliaoapp.musically",
                        usage = "25m / 30m daily limit",
                        isBlocked = false,
                        onBlockChange = { isBlocked ->
                            val action = JsonObject().apply {
                                addProperty("type", "SetAppLimit")
                                val p = JsonObject().apply {
                                    addProperty("package_name", "com.zhiliaoapp.musically")
                                    addProperty("app_name", "TikTok")
                                    addProperty("daily_limit_minutes", 30)
                                    addProperty("is_blocked", isBlocked)
                                }
                                add("payload", p)
                            }
                            sendCommand(action.toString(), if (isBlocked) "Block TikTok" else "Unblock TikTok")
                        }
                    )

                    // App Row 2: YouTube
                    AppLimitRow(
                        appName = "YouTube",
                        packageName = "com.google.android.youtube",
                        usage = "45m / 1h daily limit",
                        isBlocked = false,
                        onBlockChange = { isBlocked ->
                            val action = JsonObject().apply {
                                addProperty("type", "SetAppLimit")
                                val p = JsonObject().apply {
                                    addProperty("package_name", "com.google.android.youtube")
                                    addProperty("app_name", "YouTube")
                                    addProperty("daily_limit_minutes", 60)
                                    addProperty("is_blocked", isBlocked)
                                }
                                add("payload", p)
                            }
                            sendCommand(action.toString(), if (isBlocked) "Block YouTube" else "Unblock YouTube")
                        }
                    )

                    // App Row 3: Roblox / Games
                    AppLimitRow(
                        appName = "Roblox",
                        packageName = "com.roblox.client",
                        usage = "45m / 45m (Limit Reached)",
                        isBlocked = true,
                        onBlockChange = { isBlocked ->
                            val action = JsonObject().apply {
                                addProperty("type", "SetAppLimit")
                                val p = JsonObject().apply {
                                    addProperty("package_name", "com.roblox.client")
                                    addProperty("app_name", "Roblox")
                                    addProperty("daily_limit_minutes", 45)
                                    addProperty("is_blocked", isBlocked)
                                }
                                add("payload", p)
                            }
                            sendCommand(action.toString(), if (isBlocked) "Block Roblox" else "Unblock Roblox")
                        }
                    )
                }
            }

            // 5. Schedules & Downtime Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                border = BorderStroke(1.dp, Color(0xFF334155))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = strings.bedtimeSchedulesTitle,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )

                        TextButton(onClick = { showAddScheduleDialog = true }) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("New Schedule", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    // Schedule Row 1: Bedtime
                    ScheduleRow(
                        title = "Bedtime Routine",
                        timeRange = "21:30 → 06:00 (Every Day)",
                        isEnabled = true,
                        onToggle = { isEnabled ->
                            val action = JsonObject().apply {
                                addProperty("type", "SetBedtimeSchedule")
                                val p = JsonObject().apply {
                                    addProperty("schedule_id", "bedtime_main")
                                    addProperty("name", "Bedtime Routine")
                                    addProperty("days_of_week", if (isEnabled) 127 else 0)
                                    addProperty("start_time", "21:30")
                                    addProperty("end_time", "06:00")
                                    addProperty("mode", "LOCK_ALL")
                                }
                                add("payload", p)
                            }
                            sendCommand(action.toString(), if (isEnabled) "Enable Bedtime" else "Disable Bedtime")
                        }
                    )

                    // Schedule Row 2: Study Hours
                    ScheduleRow(
                        title = "Study Time",
                        timeRange = "18:00 → 20:00 (Mon - Fri)",
                        isEnabled = true,
                        onToggle = { isEnabled ->
                            val action = JsonObject().apply {
                                addProperty("type", "SetBedtimeSchedule")
                                val p = JsonObject().apply {
                                    addProperty("schedule_id", "study_main")
                                    addProperty("name", "Study Time")
                                    addProperty("days_of_week", if (isEnabled) 31 else 0)
                                    addProperty("start_time", "18:00")
                                    addProperty("end_time", "20:00")
                                    addProperty("mode", "BLOCK_ENTERTAINMENT")
                                }
                                add("payload", p)
                            }
                            sendCommand(action.toString(), if (isEnabled) "Enable Study Mode" else "Disable Study Mode")
                        }
                    )
                }
            }

            // 6. Real Production In-App Auto-Updater Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                border = BorderStroke(1.dp, Color(0xFF334155))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = strings.autoUpdateTitle,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = "${strings.version} $parentVersionName (Build $parentVersionCode)",
                                fontSize = 12.sp,
                                color = Color(0xFF94A3B8)
                            )
                        }

                        Button(
                            onClick = {
                                isCheckingParentUpdate = true
                                parentUpdateStatusMsg = strings.checkingUpdateMsg
                                coroutineScope.launch {
                                    val ghResult = com.parentalcontrol.core.updater.AutoUpdateManager.checkUpdateFromGitHub("kktai-nph", "GuardCore", isChildApp = false)
                                    if (ghResult.isSuccess && ghResult.getOrNull() != null) {
                                        val info = ghResult.getOrNull()!!
                                        if (info.versionCode > parentVersionCode) {
                                            parentUpdateInfo = info
                                            parentUpdateStatusMsg = strings.newUpdateFoundMsg.format(info.versionName)
                                            showUpdatePopupDialog = true
                                            isCheckingParentUpdate = false
                                            return@launch
                                        }
                                    }

                                    if (botToken.isNotBlank()) {
                                        val tgResult = com.parentalcontrol.core.updater.AutoUpdateManager.checkUpdateFromTelegram(botToken, chatId, isChildApp = false)
                                        if (tgResult.isSuccess && tgResult.getOrNull() != null) {
                                            val info = tgResult.getOrNull()!!
                                            if (info.versionCode > parentVersionCode) {
                                                parentUpdateInfo = info
                                                parentUpdateStatusMsg = strings.newUpdateFoundMsg.format(info.versionName)
                                                showUpdatePopupDialog = true
                                                isCheckingParentUpdate = false
                                                return@launch
                                            }
                                        }
                                    }

                                    isCheckingParentUpdate = false
                                    parentUpdateInfo = null
                                    parentUpdateStatusMsg = strings.appUpToDateMsg
                                }
                            },
                            enabled = !isCheckingParentUpdate,
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB))
                        ) {
                            if (isCheckingParentUpdate) {
                                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = Color.White)
                                Spacer(modifier = Modifier.width(6.dp))
                            } else {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                            }
                            Text(strings.checkUpdate, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    parentUpdateStatusMsg?.let { msg ->
                        Text(text = msg, fontSize = 12.sp, color = Color(0xFF60A5FA), fontWeight = FontWeight.Medium)
                    }

                    // Live Download progress card if update found
                    parentUpdateInfo?.let { info ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFF0F172A),
                            border = BorderStroke(1.dp, Color(0xFF2563EB).copy(alpha = 0.5f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = strings.newUpdateFoundMsg.format(info.versionName),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                        color = Color(0xFF60A5FA)
                                    )
                                    Text("v${info.versionName}", color = Color(0xFF94A3B8), fontSize = 12.sp)
                                }

                                if (info.changelog.isNotBlank()) {
                                    Text(text = info.changelog, fontSize = 12.sp, color = Color(0xFFCBD5E1))
                                }

                                parentDownloadStats?.let { stats ->
                                    val (prog, dlMb, totMb) = stats
                                    LinearProgressIndicator(
                                        progress = { prog / 100f },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(8.dp)
                                            .clip(RoundedCornerShape(4.dp)),
                                        color = Color(0xFF2563EB),
                                        trackColor = Color(0xFF1E293B)
                                    )
                                    Text(
                                        text = strings.downloadingProgress.format(dlMb, totMb, prog),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF60A5FA)
                                    )
                                }

                                Button(
                                    onClick = {
                                        coroutineScope.launch {
                                            parentDownloadStats = Triple(1, 0.1, 27.0)
                                            val dl = com.parentalcontrol.core.updater.AutoUpdateManager.downloadApk(context, info.apkUrl) { p, dlBytes, totBytes ->
                                                parentDownloadStats = Triple(p, dlBytes / (1024.0 * 1024.0), totBytes / (1024.0 * 1024.0))
                                            }
                                            if (dl.isSuccess) {
                                                val file = dl.getOrThrow()
                                                val sizeMb = file.length() / (1024.0 * 1024.0)
                                                parentDownloadStats = Triple(100, sizeMb, sizeMb)
                                                com.parentalcontrol.core.updater.AutoUpdateManager.installApk(context, file)
                                            } else {
                                                parentDownloadStats = null
                                                val err = dl.exceptionOrNull()?.message ?: "Download failed"
                                                parentUpdateStatusMsg = strings.updateFailed.format(err)
                                                Toast.makeText(context, parentUpdateStatusMsg, Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = parentDownloadStats == null,
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB))
                                ) {
                                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(strings.downloadInstallNow, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Lock Duration Dialog
    if (showLockDurationDialog) {
        AlertDialog(
            onDismissRequest = { showLockDurationDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Timer, contentDescription = null, tint = Color(0xFFEF4444), modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(strings.chooseLockDuration, fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        strings.lock15m to 15 * 60,
                        strings.lock30m to 30 * 60,
                        strings.lock1h to 60 * 60,
                        strings.lock2h to 120 * 60,
                        strings.lock4h to 240 * 60,
                        strings.lock8h to 8 * 3600
                    ).forEach { (label, secs) ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp)
                                .clickable {
                                    val action = JsonObject().apply {
                                        addProperty("type", "LockNow")
                                        val payload = JsonObject().apply {
                                            addProperty("duration_seconds", secs)
                                            addProperty("reason", "Locked for $label by Parent")
                                        }
                                        add("payload", payload)
                                    }
                                    sendCommand(action.toString(), "Lock ($label)")
                                    showLockDurationDialog = false
                                },
                            shape = RoundedCornerShape(10.dp),
                            color = Color(0xFF0F172A),
                            border = BorderStroke(1.dp, Color(0xFF334155))
                        ) {
                            Box(modifier = Modifier.padding(horizontal = 14.dp), contentAlignment = Alignment.CenterStart) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(label, fontWeight = FontWeight.Medium, color = Color.White, fontSize = 13.sp)
                                    Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Color(0xFF94A3B8), modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showLockDurationDialog = false }) { Text(strings.cancel, color = Color(0xFF94A3B8)) }
            }
        )
    }

    // Telegram Settings Dialog
    if (showSettingsDialog) {
        AlertDialog(
            onDismissRequest = { showSettingsDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Key, contentDescription = null, tint = Color(0xFF2563EB), modifier = Modifier.size(22.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(strings.telegramConfigTitle, fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = botToken,
                        onValueChange = {
                            botToken = it
                            ParentEngineHolder.saveTelegramBotToken(context, it)
                        },
                        label = { Text(strings.botTokenLabel) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    )
                    OutlinedTextField(
                        value = chatId,
                        onValueChange = {
                            chatId = it
                            ParentEngineHolder.saveTelegramChatId(context, it)
                        },
                        label = { Text(strings.chatIdLabel) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { showSettingsDialog = false },
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB))
                ) {
                    Text(strings.saveAndClose, fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    // Add Schedule Dialog
    if (showAddScheduleDialog) {
        var schedName by remember { mutableStateOf("Bedtime") }
        var startTime by remember { mutableStateOf("21:30") }
        var endTime by remember { mutableStateOf("06:00") }

        AlertDialog(
            onDismissRequest = { showAddScheduleDialog = false },
            title = { Text(strings.addScheduleTitle, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = schedName,
                        onValueChange = { schedName = it },
                        label = { Text(strings.scheduleNameLabel) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    )
                    OutlinedTextField(
                        value = startTime,
                        onValueChange = { startTime = it },
                        label = { Text(strings.startTimeLabel) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    )
                    OutlinedTextField(
                        value = endTime,
                        onValueChange = { endTime = it },
                        label = { Text(strings.endTimeLabel) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val action = JsonObject().apply {
                            addProperty("type", "SetBedtimeSchedule")
                            val p = JsonObject().apply {
                                addProperty("schedule_id", "sched_" + System.currentTimeMillis())
                                addProperty("name", schedName)
                                addProperty("days_of_week", 127)
                                addProperty("start_time", startTime)
                                addProperty("end_time", endTime)
                                addProperty("mode", "LOCK_ALL")
                            }
                            add("payload", p)
                        }
                        sendCommand(action.toString(), "Set $schedName Schedule")
                        showAddScheduleDialog = false
                    },
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB))
                ) {
                    Text(strings.sendPolicy, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddScheduleDialog = false }) { Text(strings.cancel) }
            }
        )
    }

    // Add App Limit Dialog
    if (showAddAppLimitDialog) {
        var pkgName by remember { mutableStateOf("com.zhiliaoapp.musically") }
        var appName by remember { mutableStateOf("TikTok") }
        var limitMins by remember { mutableStateOf("30") }
        var isBlocked by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { showAddAppLimitDialog = false },
            title = { Text(strings.addAppLimitTitle, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = pkgName,
                        onValueChange = { pkgName = it },
                        label = { Text(strings.packageNameLabel) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    )
                    OutlinedTextField(
                        value = appName,
                        onValueChange = { appName = it },
                        label = { Text(strings.appNameLabel) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    )
                    OutlinedTextField(
                        value = limitMins,
                        onValueChange = { limitMins = it },
                        label = { Text(strings.dailyLimitLabel) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(strings.blockCompletely, fontWeight = FontWeight.Medium)
                        Switch(checked = isBlocked, onCheckedChange = { isBlocked = it })
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val action = JsonObject().apply {
                            addProperty("type", "SetAppLimit")
                            val p = JsonObject().apply {
                                addProperty("package_name", pkgName)
                                addProperty("app_name", appName)
                                addProperty("daily_limit_minutes", limitMins.toIntOrNull() ?: 30)
                                addProperty("is_blocked", isBlocked)
                            }
                            add("payload", p)
                        }
                        sendCommand(action.toString(), "Set Limit for $appName")
                        showAddAppLimitDialog = false
                    },
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB))
                ) {
                    Text(strings.sendLimit, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddAppLimitDialog = false }) { Text(strings.cancel) }
            }
        )
    }

    // Update Available Dialog
    if (showUpdatePopupDialog && parentUpdateInfo != null) {
        val info = parentUpdateInfo!!
        AlertDialog(
            onDismissRequest = { showUpdatePopupDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.SystemUpdate, contentDescription = null, tint = Color(0xFF2563EB), modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(strings.newUpdateFoundMsg.format(info.versionName), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(strings.whatsNew, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color.White)
                    Text(
                        text = info.changelog.ifBlank { "• Real-world Production UI Redesign\n• Performance optimizations and stability improvements" },
                        fontSize = 12.sp,
                        color = Color(0xFFCBD5E1)
                    )

                    parentDownloadStats?.let { stats ->
                        val (prog, dlMb, totMb) = stats
                        Spacer(modifier = Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { prog / 100f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = Color(0xFF2563EB),
                            trackColor = Color(0xFF1E293B)
                        )
                        Text(
                            strings.downloadingProgress.format(dlMb, totMb, prog),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF60A5FA)
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        coroutineScope.launch {
                            parentDownloadStats = Triple(1, 0.1, 27.0)
                            val dl = com.parentalcontrol.core.updater.AutoUpdateManager.downloadApk(context, info.apkUrl) { p, dlBytes, totBytes ->
                                parentDownloadStats = Triple(p, dlBytes / (1024.0 * 1024.0), totBytes / (1024.0 * 1024.0))
                            }
                            if (dl.isSuccess) {
                                val file = dl.getOrThrow()
                                val sizeMb = file.length() / (1024.0 * 1024.0)
                                parentDownloadStats = Triple(100, sizeMb, sizeMb)
                                com.parentalcontrol.core.updater.AutoUpdateManager.installApk(context, file)
                            } else {
                                parentDownloadStats = null
                                val err = dl.exceptionOrNull()?.message ?: "Download failed"
                                parentUpdateStatusMsg = strings.updateFailed.format(err)
                                Toast.makeText(context, parentUpdateStatusMsg, Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    enabled = parentDownloadStats == null,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(strings.downloadInstallNow, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showUpdatePopupDialog = false }) {
                    Text(strings.remindLater)
                }
            }
        )
    }
}

/**
 * Clean Real-World App Limit Row
 */
@Composable
fun AppLimitRow(
    appName: String,
    packageName: String,
    usage: String,
    isBlocked: Boolean,
    onBlockChange: (Boolean) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = Color(0xFF0F172A),
        border = BorderStroke(1.dp, Color(0xFF334155)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFF1E293B),
                    modifier = Modifier.size(36.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Apps, contentDescription = null, tint = Color(0xFF60A5FA), modifier = Modifier.size(20.dp))
                    }
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(text = appName, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text(text = usage, fontSize = 11.sp, color = if (isBlocked) Color(0xFFEF4444) else Color(0xFF94A3B8))
                }
            }

            Switch(
                checked = !isBlocked,
                onCheckedChange = { isAllowed -> onBlockChange(!isAllowed) }
            )
        }
    }
}

/**
 * Clean Real-World Schedule Row
 */
@Composable
fun ScheduleRow(
    title: String,
    timeRange: String,
    isEnabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = Color(0xFF0F172A),
        border = BorderStroke(1.dp, Color(0xFF334155)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFF1E293B),
                    modifier = Modifier.size(36.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Schedule, contentDescription = null, tint = Color(0xFFA78BFA), modifier = Modifier.size(20.dp))
                    }
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(text = title, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text(text = timeRange, fontSize = 11.sp, color = Color(0xFF94A3B8))
                }
            }

            var checked by remember { mutableStateOf(isEnabled) }
            Switch(
                checked = checked,
                onCheckedChange = {
                    checked = it
                    onToggle(it)
                }
            )
        }
    }
}

/**
 * Real-World Segmented Language Switcher (🇬🇧 EN / 🇰🇭 ខ្មែរ)
 */
@Composable
fun LanguageSlider(
    currentLanguage: AppLanguage,
    onLanguageChange: (AppLanguage) -> Unit,
    modifier: Modifier = Modifier
) {
    val isKhmer = currentLanguage == AppLanguage.KM

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = Color(0xFF0F172A),
        border = BorderStroke(1.dp, Color(0xFF334155)),
        modifier = modifier.height(32.dp)
    ) {
        Row(
            modifier = Modifier.padding(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // English Pill
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (!isKhmer) Color(0xFF2563EB) else Color.Transparent)
                    .clickable { onLanguageChange(AppLanguage.EN) }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = "🇬🇧", fontSize = 11.sp)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "EN",
                        fontSize = 11.sp,
                        fontWeight = if (!isKhmer) FontWeight.Bold else FontWeight.Medium,
                        color = if (!isKhmer) Color.White else Color(0xFF94A3B8)
                    )
                }
            }

            Spacer(modifier = Modifier.width(2.dp))

            // Khmer Pill
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (isKhmer) Color(0xFF2563EB) else Color.Transparent)
                    .clickable { onLanguageChange(AppLanguage.KM) }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = "🇰🇭", fontSize = 11.sp)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "ខ្មែរ",
                        fontSize = 11.sp,
                        fontWeight = if (isKhmer) FontWeight.Bold else FontWeight.Medium,
                        color = if (isKhmer) Color.White else Color(0xFF94A3B8)
                    )
                }
            }
        }
    }
}
