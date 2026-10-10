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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.parentalcontrol.child.engine.ChildEngineHolder
import com.parentalcontrol.child.receiver.AdminReceiver
import com.parentalcontrol.child.service.TelegramPollingService
import kotlinx.coroutines.launch
import java.util.UUID

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Start background polling service
        TelegramPollingService.start(this)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF0F172A) // Sleek Dark Slate
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
    var downloadProgress by remember { mutableStateOf<Int?>(null) }
    var updateStatusMsg by remember { mutableStateOf<String?>(null) }
    val currentVersionName = remember { com.parentalcontrol.core.updater.AutoUpdateManager.getCurrentVersionName(context) }
    val currentVersionCode = remember { com.parentalcontrol.core.updater.AutoUpdateManager.getCurrentVersionCode(context) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Hero Header Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF0F766E)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Shield,
                        contentDescription = null,
                        tint = Color(0xFF2DD4BF),
                        modifier = Modifier.size(32.dp)
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "SafeShield Child",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    text = "Guard Core • Sovereign Protection",
                    fontSize = 13.sp,
                    color = Color(0xFF94A3B8)
                )

                Spacer(modifier = Modifier.height(12.dp))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF064E3B)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
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
                            text = "Protection Service Active",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF6EE7B7)
                        )
                    }
                }
            }
        }

        // Pairing QR Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Device Pairing",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    text = "Scan this QR code from Parent App to establish trusted cryptographic keys.",
                    fontSize = 13.sp,
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
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F766E)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(imageVector = Icons.Default.QrCode, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Show Pairing QR Code", fontWeight = FontWeight.SemiBold)
                }
            }
        }

        // Permissions Checklist Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Protection Permissions",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )

                CleanPermissionRow(
                    title = "Device Administrator",
                    description = "Anti-uninstall & instant lock",
                    isGranted = isAdminActive,
                    onRequest = onEnableAdmin
                )

                CleanPermissionRow(
                    title = "Accessibility Monitoring",
                    description = "Enforce daily app quotas",
                    isGranted = false,
                    onRequest = onEnableAccessibility
                )

                CleanPermissionRow(
                    title = "Display Over Other Apps",
                    description = "Fullscreen lock screen overlay",
                    isGranted = isOverlayAllowed,
                    onRequest = onEnableOverlay
                )

                CleanPermissionRow(
                    title = "Ignore Battery Optimizations",
                    description = "Keep background listener alive",
                    isGranted = isBatteryExempt,
                    onRequest = onEnableBatteryExemption
                )
            }
        }

        // Telegram Settings Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Transport Relay Configuration",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )

                OutlinedTextField(
                    value = botToken,
                    onValueChange = {
                        botToken = it
                        ChildEngineHolder.saveTelegramBotToken(context, it)
                    },
                    label = { Text("Telegram Bot Token") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                OutlinedTextField(
                    value = chatId,
                    onValueChange = {
                        chatId = it
                        ChildEngineHolder.saveTelegramChatId(context, it)
                    },
                    label = { Text("Telegram Chat ID / Group ID") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
        }

        // In-App Auto-Update Card
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
                    Column {
                        Text(
                            text = "Guard Core • OTA Updater",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = "Version $currentVersionName (Build $currentVersionCode)",
                            fontSize = 12.sp,
                            color = Color(0xFF94A3B8)
                        )
                    }

                    Button(
                        onClick = {
                            isCheckingUpdate = true
                            updateStatusMsg = "Checking GitHub & Telegram for updates..."
                            coroutineScope.launch {
                                // 1. Check GitHub Releases
                                val ghResult = com.parentalcontrol.core.updater.AutoUpdateManager.checkUpdateFromGitHub("kktai-nph", "GuardCore", isChildApp = true)
                                if (ghResult.isSuccess && ghResult.getOrNull() != null) {
                                    val info = ghResult.getOrNull()!!
                                    if (info.versionCode > currentVersionCode) {
                                        updateInfo = info
                                        updateStatusMsg = "New version v${info.versionName} found on GitHub!"
                                        isCheckingUpdate = false
                                        return@launch
                                    }
                                }

                                // 2. Check Telegram
                                if (botToken.isNotBlank()) {
                                    val tgResult = com.parentalcontrol.core.updater.AutoUpdateManager.checkUpdateFromTelegram(botToken, chatId, isChildApp = true)
                                    if (tgResult.isSuccess && tgResult.getOrNull() != null) {
                                        val info = tgResult.getOrNull()!!
                                        if (info.versionCode > currentVersionCode) {
                                            updateInfo = info
                                            updateStatusMsg = "New version v${info.versionName} found on Telegram!"
                                            isCheckingUpdate = false
                                            return@launch
                                        }
                                    }
                                }

                                isCheckingUpdate = false
                                updateInfo = null
                                updateStatusMsg = "App is up to date (v$currentVersionName)!"
                            }
                        },
                        enabled = !isCheckingUpdate,
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        if (isCheckingUpdate) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(6.dp))
                        }
                        Text("Check Update", fontSize = 13.sp)
                    }
                }

                updateStatusMsg?.let { msg ->
                    Text(text = msg, fontSize = 12.sp, color = Color(0xFF2DD4BF))
                }

                updateInfo?.let { info ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "Update Available: v${info.versionName}",
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF2DD4BF)
                            )
                            if (info.changelog.isNotBlank()) {
                                Text(text = info.changelog, fontSize = 12.sp, color = Color(0xFF94A3B8))
                            }

                            downloadProgress?.let { prog ->
                                LinearProgressIndicator(
                                    progress = { prog / 100f },
                                    modifier = Modifier.fillMaxWidth().height(6.dp)
                                )
                                Text("Downloading: $prog%", fontSize = 12.sp, color = Color.White)
                            }

                            Button(
                                onClick = {
                                    coroutineScope.launch {
                                        downloadProgress = 0
                                        val dl = com.parentalcontrol.core.updater.AutoUpdateManager.downloadApk(context, info.apkUrl) { p ->
                                            downloadProgress = p
                                        }
                                        if (dl.isSuccess) {
                                            val file = dl.getOrThrow()
                                            com.parentalcontrol.core.updater.AutoUpdateManager.installApk(context, file)
                                        } else {
                                            updateStatusMsg = "Download failed: ${dl.exceptionOrNull()?.message}"
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = downloadProgress == null
                            ) {
                                Text("Download & Install Now")
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
                Button(onClick = { showQrDialog = false }) {
                    Text("Close")
                }
            },
            title = { Text("Scan Pairing QR") },
            text = {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Image(
                        bitmap = qrBitmap!!.asImageBitmap(),
                        contentDescription = "Pairing QR",
                        modifier = Modifier
                            .size(240.dp)
                            .background(Color.White, RoundedCornerShape(12.dp))
                            .padding(8.dp)
                    )
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
    onRequest: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isGranted) Icons.Default.CheckCircle else Icons.Default.Warning,
                contentDescription = null,
                tint = if (isGranted) Color(0xFF10B981) else Color(0xFFEF4444),
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(text = title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color.White)
                Text(text = description, fontSize = 11.sp, color = Color(0xFF94A3B8))
            }
        }

        if (!isGranted) {
            Button(
                onClick = onRequest,
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155))
            ) {
                Text("Enable", fontSize = 12.sp)
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
