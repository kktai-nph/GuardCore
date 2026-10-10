package com.parentalcontrol.parent.ui

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
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
                    color = Color(0xFF070B12) // Deep Cyber Obsidian Void
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

    // Pulsing Animation for Hero Status
    val infiniteTransition = rememberInfiniteTransition(label = "sentinelPulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    val radarRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(3500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "radarRotation"
    )

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
        containerColor = Color(0xFF070B12),
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    Brush.linearGradient(
                                        listOf(Color(0xFF0284C7), Color(0xFF0369A1))
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Shield,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = strings.appTitleParent,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 16.sp,
                                color = Color.White
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF10B981))
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = strings.appSubtitleParent,
                                    fontSize = 11.sp,
                                    color = Color(0xFF94A3B8)
                                )
                            }
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
                        Icon(Icons.Default.Tune, contentDescription = "Settings", tint = Color(0xFF38BDF8))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0B1322))
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
            // 1. Creative Animated Cyber Sentinel Radar & Integrity HUD
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0D172A)),
                border = BorderStroke(
                    1.dp,
                    Brush.linearGradient(
                        listOf(
                            Color(0xFF0284C7).copy(alpha = 0.6f),
                            Color(0xFF38BDF8).copy(alpha = 0.2f),
                            Color(0xFF0284C7).copy(alpha = 0.6f)
                        )
                    )
                )
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Radar Animation Box
                    Box(
                        modifier = Modifier
                            .size(100.dp)
                            .scale(pulseScale),
                        contentAlignment = Alignment.Center
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val center = Offset(size.width / 2, size.height / 2)
                            val radius = size.minDimension / 2

                            // Outer Circle
                            drawCircle(
                                color = Color(0xFF0284C7).copy(alpha = 0.2f),
                                radius = radius,
                                style = Stroke(width = 2.dp.toPx())
                            )
                            // Mid Circle
                            drawCircle(
                                color = Color(0xFF0284C7).copy(alpha = 0.35f),
                                radius = radius * 0.7f,
                                style = Stroke(width = 1.5.dp.toPx())
                            )
                            // Inner Glow Circle
                            drawCircle(
                                brush = Brush.radialGradient(
                                    listOf(Color(0xFF0284C7).copy(alpha = 0.4f), Color.Transparent)
                                ),
                                radius = radius * 0.5f
                            )

                            // Rotating Radar Sweep Line
                            val sweepAngleRad = Math.toRadians(radarRotation.toDouble())
                            val endX = center.x + (radius * Math.cos(sweepAngleRad)).toFloat()
                            val endY = center.y + (radius * Math.sin(sweepAngleRad)).toFloat()

                            drawLine(
                                brush = Brush.linearGradient(
                                    listOf(Color(0xFF38BDF8), Color.Transparent),
                                    start = center,
                                    end = Offset(endX, endY)
                                ),
                                start = center,
                                end = Offset(endX, endY),
                                strokeWidth = 3.dp.toPx(),
                                cap = StrokeCap.Round
                            )
                        }

                        // Central Shield Icon
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.radialGradient(
                                        listOf(Color(0xFF0284C7), Color(0xFF0369A1))
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Security,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = strings.systemIntegrity,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color(0xFF38BDF8),
                        letterSpacing = 0.5.sp
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // Futuristic Telemetry Badges Strip
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        TelemetryBadge(label = "ChaCha20-Poly1305", icon = Icons.Default.Lock, color = Color(0xFF38BDF8))
                        TelemetryBadge(label = "Zero-Trust Mesh", icon = Icons.Default.Hub, color = Color(0xFF10B981))
                        TelemetryBadge(label = "Telegram Relay", icon = Icons.Default.CloudQueue, color = Color(0xFFA78BFA))
                    }
                }
            }

            // 2. Creative Managed Devices Carousel Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                border = BorderStroke(1.dp, Brush.horizontalGradient(listOf(Color(0xFF1E293B), Color(0xFF0284C7).copy(alpha = 0.4f), Color(0xFF1E293B))))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Devices, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = strings.managedDevices,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFF1F5F9)
                            )
                        }

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
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7))
                        ) {
                            Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(strings.pairNewChild, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }

                    if (pairedChildren.isEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFF1E293B).copy(alpha = 0.6f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Info, contentDescription = null, tint = Color(0xFFEF4444), modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = strings.noChildPaired,
                                    color = Color(0xFFCBD5E1),
                                    fontSize = 12.sp
                                )
                            }
                        }
                    } else {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            pairedChildren.forEach { child ->
                                val isSelected = selectedChild?.deviceId == child.deviceId
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = if (isSelected) Color(0xFF0369A1) else Color(0xFF1E293B),
                                    border = BorderStroke(1.dp, if (isSelected) Color(0xFF38BDF8) else Color(0xFF334155)),
                                    modifier = Modifier.clickable { selectedChild = child }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            Icons.Default.Smartphone,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp),
                                            tint = if (isSelected) Color.White else Color(0xFF94A3B8)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Column {
                                            Text(
                                                text = child.childName,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color.White
                                            )
                                            Text(
                                                text = if (isSelected) "● Active" else "Ready",
                                                fontSize = 10.sp,
                                                color = if (isSelected) Color(0xFF67E8F9) else Color(0xFF94A3B8)
                                            )
                                        }
                                        if (isSelected) {
                                            Spacer(modifier = Modifier.width(8.dp))
                                            IconButton(
                                                onClick = {
                                                    ParentEngineHolder.removePairedChild(context, child.deviceId)
                                                    pairedChildren = ParentEngineHolder.getPairedChildren(context)
                                                    selectedChild = pairedChildren.firstOrNull()
                                                    Toast.makeText(context, strings.deviceUnpaired, Toast.LENGTH_SHORT).show()
                                                },
                                                modifier = Modifier.size(18.dp)
                                            ) {
                                                Icon(Icons.Default.Close, contentDescription = "Unpair", tint = Color(0xFFF87171))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 3. Creative Hero Instant Tactical Controls (Lockdown & Unlock)
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                border = BorderStroke(1.dp, Brush.linearGradient(listOf(Color(0xFF38BDF8).copy(alpha = 0.4f), Color(0xFF1E293B))))
            ) {
                Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .scale(pulseScale)
                                    .clip(CircleShape)
                                    .background(Color(0xFF10B981))
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = strings.instantControls,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color.White
                            )
                        }
                        Text("Zero-Trust • E2EE", fontSize = 11.sp, color = Color(0xFF38BDF8), fontWeight = FontWeight.SemiBold)
                    }

                    // Action Buttons (Radiant Crimson Lockdown & Aurora Emerald Unlock)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Instant Lock Card Button
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .height(64.dp)
                                .clickable(enabled = !isSending && selectedChild != null) {
                                    showLockDurationDialog = true
                                },
                            shape = RoundedCornerShape(16.dp),
                            color = Color.Transparent,
                            border = BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.7f))
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(
                                        Brush.horizontalGradient(
                                            listOf(Color(0xFF7F1D1D), Color(0xFFDC2626))
                                        )
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Lock, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = strings.lockDevice,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                        color = Color.White
                                    )
                                }
                            }
                        }

                        // Unlock Card Button
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .height(64.dp)
                                .clickable(enabled = !isSending && selectedChild != null) {
                                    val action = JsonObject().apply {
                                        addProperty("type", "UnlockNow")
                                    }
                                    sendCommand(action.toString(), "Unlock Device")
                                },
                            shape = RoundedCornerShape(16.dp),
                            color = Color.Transparent,
                            border = BorderStroke(1.dp, Color(0xFF10B981).copy(alpha = 0.7f))
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(
                                        Brush.horizontalGradient(
                                            listOf(Color(0xFF064E3B), Color(0xFF10B981))
                                        )
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.LockOpen, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = strings.unlockDevice,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                        color = Color.White
                                    )
                                }
                            }
                        }
                    }

                    // Bonus Screen Time Capsules
                    Text(
                        text = strings.bonusTimeTitle,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF94A3B8)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(15 to "+15m", 30 to "+30m", 60 to "+1h", 120 to "+2h").forEach { (mins, label) ->
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(42.dp)
                                    .clickable(enabled = !isSending && selectedChild != null) {
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
                                shape = RoundedCornerShape(12.dp),
                                color = Color(0xFF1E293B),
                                border = BorderStroke(1.dp, Color(0xFF0284C7).copy(alpha = 0.4f))
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = label,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF38BDF8)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 4. Creative Screen Time Holographic Visualizer Matrix
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                border = BorderStroke(1.dp, Color(0xFF0284C7).copy(alpha = 0.3f))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Insights, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = strings.screenTimeVisualizer,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                        Text("Daily Live HUD", fontSize = 11.sp, color = Color(0xFF94A3B8))
                    }

                    // Bar 1: Gaming / Media
                    ScreenTimeCategoryBar(
                        icon = Icons.Default.SportsEsports,
                        categoryName = strings.gamingCategory,
                        timeUsed = "45m",
                        timeLimit = "60m",
                        progress = 0.75f,
                        color = Color(0xFFF59E0B)
                    )

                    // Bar 2: Study / Educational
                    ScreenTimeCategoryBar(
                        icon = Icons.Default.Book,
                        categoryName = strings.studyCategory,
                        timeUsed = "1h 20m",
                        timeLimit = "Unlimited",
                        progress = 0.40f,
                        color = Color(0xFF10B981)
                    )

                    // Bar 3: Social & Messaging
                    ScreenTimeCategoryBar(
                        icon = Icons.Default.Forum,
                        categoryName = strings.socialCategory,
                        timeUsed = "25m",
                        timeLimit = "45m",
                        progress = 0.55f,
                        color = Color(0xFFA78BFA)
                    )
                }
            }

            // 5. Bedtime Schedules & App Quotas Grid
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Bedtime Card (Amethyst Theme)
                Card(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { showAddScheduleDialog = true },
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                    border = BorderStroke(1.dp, Color(0xFF8B5CF6).copy(alpha = 0.4f))
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color(0xFF4C1D95)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Bedtime, contentDescription = null, tint = Color(0xFFC4B5FD), modifier = Modifier.size(18.dp))
                            }
                            Icon(Icons.Default.AddCircleOutline, contentDescription = null, tint = Color(0xFF8B5CF6), modifier = Modifier.size(20.dp))
                        }
                        Text(strings.bedtimeSchedulesTitle, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color.White)
                        Text("21:30 - 06:00", fontSize = 11.sp, color = Color(0xFFA78BFA), fontWeight = FontWeight.SemiBold)
                    }
                }

                // App Limits Card (Amber Flare Theme)
                Card(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { showAddAppLimitDialog = true },
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                    border = BorderStroke(1.dp, Color(0xFFF59E0B).copy(alpha = 0.4f))
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color(0xFF78350F)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.HourglassTop, contentDescription = null, tint = Color(0xFFFDE68A), modifier = Modifier.size(18.dp))
                            }
                            Icon(Icons.Default.AddCircleOutline, contentDescription = null, tint = Color(0xFFF59E0B), modifier = Modifier.size(20.dp))
                        }
                        Text(strings.appLimitsTitle, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color.White)
                        Text("TikTok, Games: 30m", fontSize = 11.sp, color = Color(0xFFFBBF24), fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            // 6. Next-Gen In-App Auto-Update & Maintenance Pod
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                border = BorderStroke(1.dp, Color(0xFF0284C7).copy(alpha = 0.4f))
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
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = "${strings.version} $parentVersionName (${strings.build} $parentVersionCode)",
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
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7))
                        ) {
                            if (isCheckingParentUpdate) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                                Spacer(modifier = Modifier.width(6.dp))
                            } else {
                                Icon(Icons.Default.CloudSync, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                            }
                            Text(strings.checkUpdate, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    parentUpdateStatusMsg?.let { msg ->
                        Text(text = msg, fontSize = 12.sp, color = Color(0xFF38BDF8), fontWeight = FontWeight.Medium)
                    }

                    // Live Download progress card if update found
                    parentUpdateInfo?.let { info ->
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = Color(0xFF070B12),
                            border = BorderStroke(1.dp, Color(0xFF0284C7).copy(alpha = 0.5f)),
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
                                        color = Color(0xFF38BDF8)
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
                                        color = Color(0xFF38BDF8),
                                        trackColor = Color(0xFF1E293B)
                                    )
                                    Text(
                                        text = strings.downloadingProgress.format(dlMb, totMb, prog),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF38BDF8)
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
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = parentDownloadStats == null,
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7))
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

    // Creative Lock Duration Dialog
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
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFF1E293B),
                            border = BorderStroke(1.dp, Color(0xFF334155))
                        ) {
                            Box(modifier = Modifier.padding(horizontal = 14.dp), contentAlignment = Alignment.CenterStart) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(label, fontWeight = FontWeight.SemiBold, color = Color.White, fontSize = 13.sp)
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

    // Creative Settings Dialog
    if (showSettingsDialog) {
        AlertDialog(
            onDismissRequest = { showSettingsDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Key, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(22.dp))
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
                        shape = RoundedCornerShape(12.dp)
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
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { showSettingsDialog = false },
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7))
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
                        shape = RoundedCornerShape(12.dp)
                    )
                    OutlinedTextField(
                        value = startTime,
                        onValueChange = { startTime = it },
                        label = { Text(strings.startTimeLabel) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )
                    OutlinedTextField(
                        value = endTime,
                        onValueChange = { endTime = it },
                        label = { Text(strings.endTimeLabel) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
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
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7))
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
                        shape = RoundedCornerShape(12.dp)
                    )
                    OutlinedTextField(
                        value = appName,
                        onValueChange = { appName = it },
                        label = { Text(strings.appNameLabel) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )
                    OutlinedTextField(
                        value = limitMins,
                        onValueChange = { limitMins = it },
                        label = { Text(strings.dailyLimitLabel) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(strings.blockCompletely, fontWeight = FontWeight.SemiBold)
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
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7))
                ) {
                    Text(strings.sendLimit, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddAppLimitDialog = false }) { Text(strings.cancel) }
            }
        )
    }

    // Creative Update Available Alert Dialog
    if (showUpdatePopupDialog && parentUpdateInfo != null) {
        val info = parentUpdateInfo!!
        AlertDialog(
            onDismissRequest = { showUpdatePopupDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF0284C7)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.CloudDownload, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(strings.newUpdateFoundMsg.format(info.versionName), fontWeight = FontWeight.Bold, fontSize = 17.sp)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Surface(
                        color = Color(0xFF0F172A),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, Color(0xFF334155))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("SafeShield v${info.versionName}", fontWeight = FontWeight.ExtraBold, color = Color(0xFF38BDF8))
                            Text("${strings.build} ${info.versionCode}", color = Color(0xFF94A3B8), fontSize = 12.sp)
                        }
                    }

                    Text(strings.whatsNew, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color.White)
                    Text(
                        text = info.changelog.ifBlank { "• Performance optimizations and security enhancements\n• Ultra-fast responsive UI" },
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
                            color = Color(0xFF38BDF8),
                            trackColor = Color(0xFF1E293B)
                        )
                        Text(
                            strings.downloadingProgress.format(dlMb, totMb, prog),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF38BDF8)
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
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                    shape = RoundedCornerShape(10.dp)
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
 * Creative Visualizer Category Bar
 */
@Composable
fun ScreenTimeCategoryBar(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    categoryName: String,
    timeUsed: String,
    timeLimit: String,
    progress: Float,
    color: Color
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(text = categoryName, fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
            }
            Text(text = "$timeUsed / $timeLimit", fontSize = 11.sp, color = color, fontWeight = FontWeight.Bold)
        }

        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(7.dp)
                .clip(RoundedCornerShape(4.dp)),
            color = color,
            trackColor = Color(0xFF1E293B)
        )
    }
}

/**
 * Micro Telemetry Badge
 */
@Composable
fun TelemetryBadge(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = Color(0xFF1E293B).copy(alpha = 0.6f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.3f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(13.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text(text = label, fontSize = 10.sp, color = Color(0xFFCBD5E1), fontWeight = FontWeight.Medium)
        }
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
        color = Color(0xFF111827),
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
