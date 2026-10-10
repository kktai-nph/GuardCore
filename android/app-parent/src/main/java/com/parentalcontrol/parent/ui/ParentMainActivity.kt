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
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF0F172A) // Sleek Dark Slate
                ) {
                    ParentDashboardScreen(
                        context = this,
                        onScanQr = { callback ->
                            onQrScannedCallback = callback
                            val options = ScanOptions().apply {
                                setPrompt("Scan Child SafeShield Pairing QR Code")
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
    var showSendChildUpdateDialog by remember { mutableStateOf(false) }
    var showLockDurationDialog by remember { mutableStateOf(false) }
    var showUpdatePopupDialog by remember { mutableStateOf(false) }
    var isSending by remember { mutableStateOf(false) }

    var isCheckingParentUpdate by remember { mutableStateOf(false) }
    var parentUpdateInfo by remember { mutableStateOf<com.parentalcontrol.core.updater.UpdateInfo?>(null) }
    var parentDownloadProgress by remember { mutableStateOf<Int?>(null) }
    var parentUpdateStatusMsg by remember { mutableStateOf<String?>(null) }
    val parentVersionName = remember { com.parentalcontrol.core.updater.AutoUpdateManager.getCurrentVersionName(context) }
    val parentVersionCode = remember { com.parentalcontrol.core.updater.AutoUpdateManager.getCurrentVersionCode(context) }

    val engine = remember { ParentEngineHolder.getEngine(context) }

    fun sendCommand(actionJson: String, actionName: String) {
        val child = selectedChild
        if (child == null) {
            Toast.makeText(context, "Please pair with a child device first", Toast.LENGTH_SHORT).show()
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
                // Encrypt command using Child's X25519 PubKey
                val wireMsg = engine?.createEncryptedCommandMessage(
                    child.deviceId,
                    actionJson,
                    nowEpoch.toULong(),
                    child.x25519Pubkey
                ) ?: ""

                val result = TelegramDispatcher.sendWireMessage(botToken, chatId, wireMsg)
                if (result.isSuccess) {
                    Toast.makeText(context, "✓ $actionName sent securely!", Toast.LENGTH_SHORT).show()
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
        containerColor = Color(0xFF0F172A),
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF1E293B)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Shield,
                                contentDescription = null,
                                tint = Color(0xFF38BDF8),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(strings.appTitleParent, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = Color.White)
                            Text(strings.appSubtitleParent, fontSize = 11.sp, color = Color(0xFF94A3B8))
                        }
                    }
                },
                actions = {
                    // Language Switcher Slider / Toggle
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
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0F172A))
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Target Device Hub
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = strings.managedDevices,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFFE2E8F0)
                        )
                        Button(
                            onClick = {
                                onScanQr { qrString ->
                                    val nowEpoch = System.currentTimeMillis() / 1000
                                    try {
                                        val pairingResult = engine?.pairWithChildQr(qrString, "Child Device", nowEpoch.toULong())
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

                                            // Send PairConfirm wire message to Telegram
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
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7))
                        ) {
                            Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(strings.pairNewChild, fontSize = 13.sp)
                        }
                    }

                    if (pairedChildren.isEmpty()) {
                        Text(
                            text = strings.noChildPaired,
                            color = Color(0xFFEF4444),
                            fontSize = 13.sp
                        )
                    } else {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            pairedChildren.forEach { child ->
                                val isSelected = selectedChild?.deviceId == child.deviceId
                                FilterChip(
                                    selected = isSelected,
                                    onClick = { selectedChild = child },
                                    label = { Text(child.childName, fontSize = 13.sp) },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.PhoneAndroid,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                            tint = if (isSelected) Color(0xFF38BDF8) else Color(0xFF94A3B8)
                                        )
                                    },
                                    trailingIcon = if (isSelected) {
                                        {
                                            IconButton(
                                                onClick = {
                                                    ParentEngineHolder.removePairedChild(context, child.deviceId)
                                                    pairedChildren = ParentEngineHolder.getPairedChildren(context)
                                                    selectedChild = pairedChildren.firstOrNull()
                                                    Toast.makeText(context, strings.deviceUnpaired, Toast.LENGTH_SHORT).show()
                                                },
                                                modifier = Modifier.size(16.dp)
                                            ) {
                                                Icon(Icons.Default.Close, contentDescription = "Unpair", tint = Color(0xFFEF4444))
                                            }
                                        }
                                    } else null,
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = Color(0xFF0369A1),
                                        selectedLabelColor = Color.White,
                                        containerColor = Color(0xFF334155),
                                        labelColor = Color(0xFFCBD5E1)
                                    )
                                )
                            }
                        }
                    }
                }
            }

            // Instant Device Controls (Hero Card)
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = strings.instantControls,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick = { showLockDurationDialog = true },
                            enabled = !isSending && selectedChild != null,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                        ) {
                            Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(strings.lockDevice, fontWeight = FontWeight.SemiBold)
                        }

                        Button(
                            onClick = {
                                val action = JsonObject().apply {
                                    addProperty("type", "UnlockNow")
                                }
                                sendCommand(action.toString(), "Unlock Device")
                            },
                            enabled = !isSending && selectedChild != null,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF16A34A)),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                        ) {
                            Icon(Icons.Default.LockOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(strings.unlockDevice, fontWeight = FontWeight.SemiBold)
                        }
                    }

                    // Bonus Screen Time
                    Text(
                        text = strings.bonusTimeTitle,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF94A3B8)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(15 to "+15m", 30 to "+30m", 60 to "+1h", 120 to "+2h").forEach { (mins, label) ->
                            OutlinedButton(
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
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(label, fontSize = 12.sp, maxLines = 1)
                            }
                        }
                    }
                }
            }

            // Bedtime & Offline Schedules
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = strings.bedtimeSchedulesTitle,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        IconButton(onClick = { showAddScheduleDialog = true }) {
                            Icon(Icons.Default.AddCircle, contentDescription = "Add Schedule", tint = Color(0xFF38BDF8))
                        }
                    }

                    Text(
                        text = strings.bedtimeSchedulesDesc,
                        fontSize = 12.sp,
                        color = Color(0xFF94A3B8)
                    )
                }
            }

            // App Limits & Quotas
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = strings.appLimitsTitle,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        IconButton(onClick = { showAddAppLimitDialog = true }) {
                            Icon(Icons.Default.AddCircle, contentDescription = "Add App Limit", tint = Color(0xFF38BDF8))
                        }
                    }

                    Text(
                        text = strings.appLimitsDesc,
                        fontSize = 12.sp,
                        color = Color(0xFF94A3B8)
                    )
                }
            }

            // In-App Auto-Update & Maintenance
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = strings.autoUpdateTitle,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = "${strings.version} $parentVersionName (${strings.build} $parentVersionCode)",
                        fontSize = 12.sp,
                        color = Color(0xFF94A3B8)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                isCheckingParentUpdate = true
                                parentUpdateStatusMsg = strings.checkingUpdateMsg
                                coroutineScope.launch {
                                    // 1. Check GitHub Releases
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

                                    // 2. Check Telegram
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
                            modifier = Modifier.weight(1f)
                        ) {
                            if (isCheckingParentUpdate) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(6.dp))
                            }
                            Text(strings.checkUpdate, fontSize = 13.sp)
                        }

                        OutlinedButton(
                            onClick = { showSendChildUpdateDialog = true },
                            enabled = selectedChild != null && botToken.isNotBlank(),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(strings.updateChild, fontSize = 13.sp)
                        }
                    }

                    // Preview / Test Update Popup Button
                    TextButton(
                        onClick = {
                            parentUpdateInfo = com.parentalcontrol.core.updater.UpdateInfo(
                                versionCode = 2,
                                versionName = "1.1",
                                apkUrl = "https://github.com/kktai-nph/GuardCore/releases/download/v1.1/Parent_SafeShield.apk",
                                changelog = if (currentLang == AppLanguage.KM) "🚀 ការអាប់ដេត SafeShield v1.1:\n• មុខងារចាក់សោរហ័សទាន់ចិត្ត\n• រក្សាដំណើរការ background ជាប់ជានិច្ច\n• ផ្ទាំងរូបរាង Dark Slate ថ្មីស្រឡាង\n• រយៈពេលចាក់សោថ្មី (១៥នាទី ដល់ ៨ម៉ោង)"
                                else "🚀 SafeShield v1.1 Update:\n• Ultra-fast instant lock sync\n• Battery-saver resilience\n• Enhanced Slate Dark Theme UI\n• New lock duration timers (15m - 8h)"
                            )
                            showUpdatePopupDialog = true
                        },
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    ) {
                        Icon(Icons.Default.Visibility, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color(0xFF38BDF8))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(strings.previewUpdatePopup, fontSize = 12.sp, color = Color(0xFF38BDF8))
                    }

                    parentUpdateStatusMsg?.let { msg ->
                        Text(text = msg, fontSize = 12.sp, color = Color(0xFF38BDF8))
                    }

                    parentUpdateInfo?.let { info ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = strings.newUpdateFoundMsg.format(info.versionName),
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF38BDF8)
                                )
                                if (info.changelog.isNotBlank()) {
                                    Text(text = info.changelog, fontSize = 12.sp, color = Color(0xFF94A3B8))
                                }

                                parentDownloadProgress?.let { prog ->
                                    LinearProgressIndicator(
                                        progress = { prog / 100f },
                                        modifier = Modifier.fillMaxWidth().height(6.dp)
                                    )
                                    Text(strings.downloadingProgress.format(prog), fontSize = 12.sp, color = Color.White)
                                }

                                Button(
                                    onClick = {
                                        coroutineScope.launch {
                                            parentDownloadProgress = 0
                                            val dl = com.parentalcontrol.core.updater.AutoUpdateManager.downloadApk(context, info.apkUrl) { p ->
                                                parentDownloadProgress = p
                                            }
                                            if (dl.isSuccess) {
                                                val file = dl.getOrThrow()
                                                com.parentalcontrol.core.updater.AutoUpdateManager.installApk(context, file)
                                            } else {
                                                parentUpdateStatusMsg = strings.updateFailed.format(dl.exceptionOrNull()?.message)
                                            }
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = parentDownloadProgress == null
                                ) {
                                    Text(strings.downloadInstallNow)
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
            title = { Text(strings.chooseLockDuration) },
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
                        Button(
                            onClick = {
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
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155))
                        ) {
                            Text(label)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showLockDurationDialog = false }) { Text(strings.cancel) }
            }
        )
    }

    // Settings Dialog
    if (showSettingsDialog) {
        AlertDialog(
            onDismissRequest = { showSettingsDialog = false },
            title = { Text(strings.telegramConfigTitle) },
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
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = chatId,
                        onValueChange = {
                            chatId = it
                            ParentEngineHolder.saveTelegramChatId(context, it)
                        },
                        label = { Text(strings.chatIdLabel) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = { showSettingsDialog = false }) {
                    Text(strings.saveAndClose)
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
            title = { Text(strings.addScheduleTitle) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = schedName,
                        onValueChange = { schedName = it },
                        label = { Text(strings.scheduleNameLabel) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = startTime,
                        onValueChange = { startTime = it },
                        label = { Text(strings.startTimeLabel) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = endTime,
                        onValueChange = { endTime = it },
                        label = { Text(strings.endTimeLabel) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    val action = JsonObject().apply {
                        addProperty("type", "SetBedtimeSchedule")
                        val p = JsonObject().apply {
                            addProperty("schedule_id", "sched_" + System.currentTimeMillis())
                            addProperty("name", schedName)
                            addProperty("days_of_week", 127) // All 7 days
                            addProperty("start_time", startTime)
                            addProperty("end_time", endTime)
                            addProperty("mode", "LOCK_ALL")
                        }
                        add("payload", p)
                    }
                    sendCommand(action.toString(), "Set $schedName Schedule")
                    showAddScheduleDialog = false
                }) {
                    Text(strings.sendPolicy)
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
            title = { Text(strings.addAppLimitTitle) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = pkgName,
                        onValueChange = { pkgName = it },
                        label = { Text(strings.packageNameLabel) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = appName,
                        onValueChange = { appName = it },
                        label = { Text(strings.appNameLabel) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = limitMins,
                        onValueChange = { limitMins = it },
                        label = { Text(strings.dailyLimitLabel) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(strings.blockCompletely)
                        Switch(checked = isBlocked, onCheckedChange = { isBlocked = it })
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
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
                }) {
                    Text(strings.sendLimit)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddAppLimitDialog = false }) { Text(strings.cancel) }
            }
        )
    }

    // Push Child Remote Update Dialog
    if (showSendChildUpdateDialog) {
        var childApkUrl by remember { mutableStateOf("https://github.com/kktai-nph/GuardCore/releases/download/v1.1/Child_SafeShield.apk") }
        var newVersionCode by remember { mutableStateOf("2") }

        AlertDialog(
            onDismissRequest = { showSendChildUpdateDialog = false },
            title = { Text(strings.pushChildUpdateTitle) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = strings.pushChildUpdateDesc,
                        fontSize = 13.sp,
                        color = Color(0xFF94A3B8)
                    )
                    OutlinedTextField(
                        value = childApkUrl,
                        onValueChange = { childApkUrl = it },
                        label = { Text(strings.childApkUrlLabel) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newVersionCode,
                        onValueChange = { newVersionCode = it },
                        label = { Text(strings.targetVersionCodeLabel) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    val action = JsonObject().apply {
                        addProperty("type", "UPDATE_APP")
                        val p = JsonObject().apply {
                            addProperty("apk_url", childApkUrl)
                            addProperty("version_code", newVersionCode.toIntOrNull() ?: 2)
                        }
                        add("payload", p)
                    }
                    sendCommand(action.toString(), "Remote Update Command")
                    showSendChildUpdateDialog = false
                }) {
                    Text(strings.pushCommand)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSendChildUpdateDialog = false }) { Text(strings.cancel) }
            }
        )
    }

    // New Update Available Alert Dialog
    if (showUpdatePopupDialog && parentUpdateInfo != null) {
        val info = parentUpdateInfo!!
        AlertDialog(
            onDismissRequest = { showUpdatePopupDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CloudDownload, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(strings.newUpdateFoundMsg.format(info.versionName), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Surface(
                        color = Color(0xFF0F172A),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("SafeShield v${info.versionName}", fontWeight = FontWeight.Bold, color = Color(0xFF38BDF8))
                            Text("${strings.build} ${info.versionCode}", color = Color(0xFF94A3B8), fontSize = 12.sp)
                        }
                    }

                    Text(strings.whatsNew, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Color.White)
                    Text(
                        text = info.changelog.ifBlank { "• Performance optimizations and security enhancements\n• Minor UI adjustments" },
                        fontSize = 13.sp,
                        color = Color(0xFFCBD5E1)
                    )

                    parentDownloadProgress?.let { prog ->
                        Spacer(modifier = Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { prog / 100f },
                            modifier = Modifier.fillMaxWidth().height(8.dp),
                            color = Color(0xFF38BDF8)
                        )
                        Text(strings.downloadingProgress.format(prog), fontSize = 12.sp, color = Color(0xFF38BDF8))
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        coroutineScope.launch {
                            parentDownloadProgress = 0
                            val dl = com.parentalcontrol.core.updater.AutoUpdateManager.downloadApk(context, info.apkUrl) { p ->
                                parentDownloadProgress = p
                            }
                            if (dl.isSuccess) {
                                val file = dl.getOrThrow()
                                com.parentalcontrol.core.updater.AutoUpdateManager.installApk(context, file)
                            } else {
                                parentUpdateStatusMsg = strings.updateFailed.format(dl.exceptionOrNull()?.message)
                            }
                        }
                    },
                    enabled = parentDownloadProgress == null,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(strings.downloadInstallNow)
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
 * Modern Segmented Language Slider / Pill Toggle (🇬🇧 EN / 🇰🇭 ខ្មែរ)
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
        color = Color(0xFF1E293B),
        border = BorderStroke(1.dp, Color(0xFF334155)),
        modifier = modifier.height(34.dp)
    ) {
        Row(
            modifier = Modifier.padding(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // English Pill
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (!isKhmer) Color(0xFF0284C7) else Color.Transparent)
                    .clickable { onLanguageChange(AppLanguage.EN) }
                    .padding(horizontal = 7.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = "🇬🇧", fontSize = 11.sp)
                    Spacer(modifier = Modifier.width(3.dp))
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
                    .background(if (isKhmer) Color(0xFF0284C7) else Color.Transparent)
                    .clickable { onLanguageChange(AppLanguage.KM) }
                    .padding(horizontal = 7.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = "🇰🇭", fontSize = 11.sp)
                    Spacer(modifier = Modifier.width(3.dp))
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
