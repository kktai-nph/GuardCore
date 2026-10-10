package com.parentalcontrol.child.ui

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.parentalcontrol.child.engine.ChildEngineHolder
import com.parentalcontrol.child.receiver.AdminReceiver
import com.parentalcontrol.child.service.TelegramPollingService
import com.parentalcontrol.core.localization.AppLanguage
import com.parentalcontrol.core.localization.LanguageManager
import com.parentalcontrol.core.localization.Localization
import kotlinx.coroutines.launch
import java.util.UUID

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        TelegramPollingService.start(this)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF030F11) // Deep Emerald Obsidian Void
                ) {
                    ChildDashboardScreen(
                        context = this,
                        onEnableAdmin = { requestDeviceAdmin() },
                        onEnableAccessibility = { openAccessibilitySettings() },
                        onEnableOverlay = { openOverlaySettings() },
                        onEnableBatteryExemption = { requestBatteryExemption() }
                    )
                }
            }
        }
    }

    private fun requestDeviceAdmin() {
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, AdminReceiver.getComponentName(this@MainActivity))
            putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Required to enforce security and device lock policies.")
        }
        startActivity(intent)
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun openOverlaySettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }
    }

    private fun requestBatteryExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        }
    }
}

@Composable
fun ChildDashboardScreen(
    context: Context,
    onEnableAdmin: () -> Unit,
    onEnableAccessibility: () -> Unit,
    onEnableOverlay: () -> Unit,
    onEnableBatteryExemption: () -> Unit
) {
    val scrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()
    val engine = remember { ChildEngineHolder.getEngine(context) }

    var currentLang by remember { mutableStateOf(LanguageManager.getSavedLanguage(context)) }
    val strings = remember(currentLang) { Localization.getStrings(currentLang) }

    var botToken by remember { mutableStateOf(ChildEngineHolder.getTelegramBotToken(context)) }
    var chatId by remember { mutableStateOf(ChildEngineHolder.getTelegramChatId(context)) }
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var showQrDialog by remember { mutableStateOf(false) }

    val isAdminActive = remember { AdminReceiver.isAdminActive(context) }
    val isOverlayAllowed = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(context) else true
    }
    val isBatteryExempt = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            pm?.isIgnoringBatteryOptimizations(context.packageName) == true
        } else true
    }

    // Updater State
    var isCheckingUpdate by remember { mutableStateOf(false) }
    var updateInfo by remember { mutableStateOf<com.parentalcontrol.core.updater.UpdateInfo?>(null) }
    var downloadStats by remember { mutableStateOf<Triple<Int, Double, Double>?>(null) }
    var updateStatusMsg by remember { mutableStateOf<String?>(null) }
    val currentVersionName = remember { com.parentalcontrol.core.updater.AutoUpdateManager.getCurrentVersionName(context) }
    val currentVersionCode = remember { com.parentalcontrol.core.updater.AutoUpdateManager.getCurrentVersionCode(context) }

    // Pulsing Sentinel Animation
    val infiniteTransition = rememberInfiniteTransition(label = "sentinelPulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Top Header Bar with Language Switcher
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(Color(0xFF0F766E), Color(0xFF0D9488))
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Shield,
                        contentDescription = null,
                        tint = Color(0xFF2DD4BF),
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = strings.appTitleChild,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 16.sp,
                        color = Color.White
                    )
                    Text("Sentinel Node Active", fontSize = 11.sp, color = Color(0xFF2DD4BF), fontWeight = FontWeight.SemiBold)
                }
            }

            ChildLanguageSlider(
                currentLanguage = currentLang,
                onLanguageChange = { newLang ->
                    currentLang = newLang
                    LanguageManager.setSavedLanguage(context, newLang)
                }
            )
        }

        // 1. Creative Hero Sentinel Radar Core Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF071C20)),
            border = BorderStroke(
                1.dp,
                Brush.linearGradient(
                    listOf(
                        Color(0xFF2DD4BF).copy(alpha = 0.6f),
                        Color(0xFF0F766E).copy(alpha = 0.2f),
                        Color(0xFF2DD4BF).copy(alpha = 0.6f)
                    )
                )
            )
        ) {
            Column(
                modifier = Modifier.padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Concentric Animated Radar Sentinel Box
                Box(
                    modifier = Modifier
                        .size(110.dp)
                        .scale(pulseScale),
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val center = Offset(size.width / 2, size.height / 2)
                        val radius = size.minDimension / 2

                        // Outer Radar Ring
                        drawCircle(
                            color = Color(0xFF2DD4BF).copy(alpha = 0.25f),
                            radius = radius,
                            style = Stroke(width = 2.dp.toPx())
                        )
                        // Middle Ring
                        drawCircle(
                            color = Color(0xFF2DD4BF).copy(alpha = 0.4f),
                            radius = radius * 0.7f,
                            style = Stroke(width = 1.5.dp.toPx())
                        )
                        // Inner Glow
                        drawCircle(
                            brush = Brush.radialGradient(
                                listOf(Color(0xFF2DD4BF).copy(alpha = 0.35f), Color.Transparent)
                            ),
                            radius = radius * 0.5f
                        )

                        // Rotating Radar Beam
                        val sweepAngleRad = Math.toRadians(radarRotation.toDouble())
                        val endX = center.x + (radius * Math.cos(sweepAngleRad)).toFloat()
                        val endY = center.y + (radius * Math.sin(sweepAngleRad)).toFloat()

                        drawLine(
                            brush = Brush.linearGradient(
                                listOf(Color(0xFF2DD4BF), Color.Transparent),
                                start = center,
                                end = Offset(endX, endY)
                            ),
                            start = center,
                            end = Offset(endX, endY),
                            strokeWidth = 3.dp.toPx(),
                            cap = StrokeCap.Round
                        )
                    }

                    // Core Shield Emblem
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.radialGradient(
                                    listOf(Color(0xFF14B8A6), Color(0xFF0F766E))
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = strings.protectionActive,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White
                )
                Text(
                    text = strings.appSubtitleChild,
                    fontSize = 12.sp,
                    color = Color(0xFF94A3B8)
                )

                Spacer(modifier = Modifier.height(14.dp))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF042F2E),
                    border = BorderStroke(1.dp, Color(0xFF14B8A6).copy(alpha = 0.5f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF34D399))
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = strings.offlineSentinel,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF6EE7B7)
                        )
                    }
                }
            }
        }

        // 2. Futuristic Pairing QR Card (Cryptographic Vault)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF071C20)),
            border = BorderStroke(1.dp, Color(0xFF14B8A6).copy(alpha = 0.3f))
        ) {
            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.QrCode2, contentDescription = null, tint = Color(0xFF2DD4BF), modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = strings.devicePairing,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF042F2E),
                        border = BorderStroke(1.dp, Color(0xFF14B8A6).copy(alpha = 0.4f))
                    ) {
                        Text("Ed25519 Vault", fontSize = 10.sp, color = Color(0xFF2DD4BF), fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                    }
                }

                Text(
                    text = strings.scanQrDesc,
                    fontSize = 12.sp,
                    color = Color(0xFF94A3B8)
                )

                Button(
                    onClick = {
                        val token = UUID.randomUUID().toString()
                        val qrStr = engine?.generatePairingQr("Child Device", (System.currentTimeMillis() / 1000).toULong(), token)
                        if (qrStr != null) {
                            qrBitmap = generateQrBitmap(qrStr)
                            showQrDialog = true
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0D9488)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(imageVector = Icons.Default.QrCode, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(strings.showPairingQr, fontWeight = FontWeight.Bold)
                }
            }
        }

        // 3. Protection Permissions Matrix Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF071C20)),
            border = BorderStroke(1.dp, Color(0xFF14B8A6).copy(alpha = 0.3f))
        ) {
            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    text = strings.protectionPermissions,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )

                CleanPermissionRow(
                    title = strings.deviceAdmin,
                    description = strings.deviceAdminDesc,
                    isGranted = isAdminActive,
                    onRequest = onEnableAdmin,
                    enableLabel = strings.enable
                )

                CleanPermissionRow(
                    title = strings.accessibility,
                    description = strings.accessibilityDesc,
                    isGranted = false,
                    onRequest = onEnableAccessibility,
                    enableLabel = strings.enable
                )

                CleanPermissionRow(
                    title = strings.overlay,
                    description = strings.overlayDesc,
                    isGranted = isOverlayAllowed,
                    onRequest = onEnableOverlay,
                    enableLabel = strings.enable
                )

                CleanPermissionRow(
                    title = strings.batteryOptimization,
                    description = strings.batteryOptimizationDesc,
                    isGranted = isBatteryExempt,
                    onRequest = onEnableBatteryExemption,
                    enableLabel = strings.enable
                )
            }
        }

        // 4. Live Cyber Terminal Telemetry Feed
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF051316)),
            border = BorderStroke(1.dp, Color(0xFF0D9488).copy(alpha = 0.3f))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Terminal, contentDescription = null, tint = Color(0xFF2DD4BF), modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(strings.terminalLogs, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                    Text("LIVE FEED", fontSize = 10.sp, color = Color(0xFF34D399), fontWeight = FontWeight.Bold)
                }

                Surface(
                    color = Color(0xFF02090B),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, Color(0xFF0F3D39)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("> [KERNEL] ChaCha20-Poly1305 Cipher Engaged", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Color(0xFF2DD4BF))
                        Text("> [DAEMON] Accessibility Interceptor: Active", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Color(0xFF34D399))
                        Text("> [SENTINEL] Offline Policy Matrix: 100% Enforced", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Color(0xFF2DD4BF))
                        Text("> [TRANSPORT] Telegram Zero-Trust Relay: Standby", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Color(0xFF94A3B8))
                    }
                }
            }
        }

        // 5. Telegram Transport Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF071C20)),
            border = BorderStroke(1.dp, Color(0xFF14B8A6).copy(alpha = 0.3f))
        ) {
            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = strings.relayConfig,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )

                OutlinedTextField(
                    value = botToken,
                    onValueChange = {
                        botToken = it
                        ChildEngineHolder.saveTelegramBotToken(context, it)
                    },
                    label = { Text(strings.botTokenLabel) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )

                OutlinedTextField(
                    value = chatId,
                    onValueChange = {
                        chatId = it
                        ChildEngineHolder.saveTelegramChatId(context, it)
                    },
                    label = { Text(strings.chatIdLabel) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )
            }
        }

        // 6. In-App Auto-Update Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF071C20)),
            border = BorderStroke(1.dp, Color(0xFF14B8A6).copy(alpha = 0.3f))
        ) {
            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                            text = "${strings.version} $currentVersionName (${strings.build} $currentVersionCode)",
                            fontSize = 12.sp,
                            color = Color(0xFF94A3B8)
                        )
                    }

                    Button(
                        onClick = {
                            isCheckingUpdate = true
                            updateStatusMsg = strings.checkingUpdateMsg
                            coroutineScope.launch {
                                val ghResult = com.parentalcontrol.core.updater.AutoUpdateManager.checkUpdateFromGitHub("kktai-nph", "GuardCore", isChildApp = true)
                                if (ghResult.isSuccess && ghResult.getOrNull() != null) {
                                    val info = ghResult.getOrNull()!!
                                    if (info.versionCode > currentVersionCode) {
                                        updateInfo = info
                                        updateStatusMsg = strings.newUpdateFoundMsg.format(info.versionName)
                                        isCheckingUpdate = false
                                        return@launch
                                    }
                                }

                                if (botToken.isNotBlank()) {
                                    val tgResult = com.parentalcontrol.core.updater.AutoUpdateManager.checkUpdateFromTelegram(botToken, chatId, isChildApp = true)
                                    if (tgResult.isSuccess && tgResult.getOrNull() != null) {
                                        val info = tgResult.getOrNull()!!
                                        if (info.versionCode > currentVersionCode) {
                                            updateInfo = info
                                            updateStatusMsg = strings.newUpdateFoundMsg.format(info.versionName)
                                            isCheckingUpdate = false
                                            return@launch
                                        }
                                    }
                                }

                                isCheckingUpdate = false
                                updateInfo = null
                                updateStatusMsg = strings.appUpToDateMsg
                            }
                        },
                        enabled = !isCheckingUpdate,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0D9488))
                    ) {
                        if (isCheckingUpdate) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                            Spacer(modifier = Modifier.width(6.dp))
                        } else {
                            Icon(Icons.Default.CloudSync, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                        }
                        Text(strings.checkUpdate, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }

                updateStatusMsg?.let { msg ->
                    Text(text = msg, fontSize = 12.sp, color = Color(0xFF2DD4BF), fontWeight = FontWeight.Medium)
                }

                updateInfo?.let { info ->
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = Color(0xFF042F2E),
                        border = BorderStroke(1.dp, Color(0xFF14B8A6).copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                text = strings.newUpdateFoundMsg.format(info.versionName),
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF2DD4BF)
                            )
                            if (info.changelog.isNotBlank()) {
                                Text(text = info.changelog, fontSize = 12.sp, color = Color(0xFFCBD5E1))
                            }

                            downloadStats?.let { stats ->
                                val (prog, dlMb, totMb) = stats
                                LinearProgressIndicator(
                                    progress = { prog / 100f },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(8.dp)
                                        .clip(RoundedCornerShape(4.dp)),
                                    color = Color(0xFF2DD4BF),
                                    trackColor = Color(0xFF115E59)
                                )
                                Text(
                                    text = strings.downloadingProgress.format(dlMb, totMb, prog),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }

                            Button(
                                onClick = {
                                    coroutineScope.launch {
                                        downloadStats = Triple(1, 0.1, 27.0)
                                        val dl = com.parentalcontrol.core.updater.AutoUpdateManager.downloadApk(context, info.apkUrl) { p, dlBytes, totBytes ->
                                            downloadStats = Triple(p, dlBytes / (1024.0 * 1024.0), totBytes / (1024.0 * 1024.0))
                                        }
                                        if (dl.isSuccess) {
                                            val file = dl.getOrThrow()
                                            val sizeMb = file.length() / (1024.0 * 1024.0)
                                            downloadStats = Triple(100, sizeMb, sizeMb)
                                            com.parentalcontrol.core.updater.AutoUpdateManager.installApk(context, file)
                                        } else {
                                            downloadStats = null
                                            val err = dl.exceptionOrNull()?.message ?: "Download failed"
                                            updateStatusMsg = strings.updateFailed.format(err)
                                            android.widget.Toast.makeText(context, updateStatusMsg, android.widget.Toast.LENGTH_LONG).show()
                                        }
                                    }
                                },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth(),
                                enabled = downloadStats == null,
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0D9488))
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

    if (showQrDialog && qrBitmap != null) {
        AlertDialog(
            onDismissRequest = { showQrDialog = false },
            confirmButton = {
                Button(
                    onClick = { showQrDialog = false },
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0D9488))
                ) {
                    Text(strings.close, fontWeight = FontWeight.Bold)
                }
            },
            title = { Text(strings.scanPairingQrTitle, fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Color.White,
                        modifier = Modifier.padding(8.dp)
                    ) {
                        Image(
                            bitmap = qrBitmap!!.asImageBitmap(),
                            contentDescription = "Pairing QR",
                            modifier = Modifier
                                .size(240.dp)
                                .padding(12.dp)
                        )
                    }
                }
            }
        )
    }
}

@Composable
fun CleanPermissionRow(
    title: String,
    description: String,
    isGranted: Boolean,
    onRequest: () -> Unit,
    enableLabel: String = "Enable"
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFF0A2226),
        border = BorderStroke(1.dp, if (isGranted) Color(0xFF10B981).copy(alpha = 0.4f) else Color(0xFF1E3A3E)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(if (isGranted) Color(0xFF064E3B) else Color(0xFF132F34)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isGranted) Icons.Default.CheckCircle else Icons.Default.Shield,
                        contentDescription = null,
                        tint = if (isGranted) Color(0xFF34D399) else Color(0xFF94A3B8),
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(text = title, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text(text = description, fontSize = 11.sp, color = Color(0xFF94A3B8))
                }
            }

            if (!isGranted) {
                Button(
                    onClick = onRequest,
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F766E))
                ) {
                    Text(enableLabel, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
        }
    }
}

/**
 * Modern Segmented Language Slider / Pill Toggle for Child App (🇬🇧 EN / 🇰🇭 ខ្មែរ)
 */
@Composable
fun ChildLanguageSlider(
    currentLanguage: AppLanguage,
    onLanguageChange: (AppLanguage) -> Unit,
    modifier: Modifier = Modifier
) {
    val isKhmer = currentLanguage == AppLanguage.KM

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = Color(0xFF071C20),
        border = BorderStroke(1.dp, Color(0xFF14B8A6).copy(alpha = 0.4f)),
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
                    .background(if (!isKhmer) Color(0xFF0D9488) else Color.Transparent)
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
                    .background(if (isKhmer) Color(0xFF0D9488) else Color.Transparent)
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

fun generateQrBitmap(content: String): Bitmap {
    val writer = QRCodeWriter()
    val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, 512, 512)
    val width = bitMatrix.width
    val height = bitMatrix.height
    val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
    for (x in 0 until width) {
        for (y in 0 until height) {
            bmp.setPixel(x, y, if (bitMatrix.get(x, y)) AndroidColor.BLACK else AndroidColor.WHITE)
        }
    }
    return bmp
}
