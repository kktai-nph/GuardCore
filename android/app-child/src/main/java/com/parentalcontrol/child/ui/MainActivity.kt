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
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.parentalcontrol.child.engine.ChildEngineHolder
import com.parentalcontrol.child.receiver.AdminReceiver
import com.parentalcontrol.child.service.TelegramPollingService
import com.parentalcontrol.core.localization.AppLanguage
import com.parentalcontrol.core.localization.LanguageManager
import com.parentalcontrol.core.localization.Localization
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        TelegramPollingService.start(this)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF0D9488),
                    secondary = Color(0xFF2563EB),
                    background = Color(0xFF0F172A),
                    surface = Color(0xFF1E293B),
                    surfaceVariant = Color(0xFF334155),
                    onBackground = Color(0xFFF8FAFC),
                    onSurface = Color(0xFFF8FAFC)
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF0F172A)
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

@OptIn(ExperimentalMaterial3Api::class)
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
    var downloadedChildApkFile by remember { mutableStateOf<File?>(null) }
    var hasInstallPermission by remember { mutableStateOf(com.parentalcontrol.core.updater.AutoUpdateManager.canRequestPackageInstalls(context)) }
    val currentVersionName = remember { com.parentalcontrol.core.updater.AutoUpdateManager.getCurrentVersionName(context) }
    val currentVersionCode = remember { com.parentalcontrol.core.updater.AutoUpdateManager.getCurrentVersionCode(context) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasInstallPermission = com.parentalcontrol.core.updater.AutoUpdateManager.canRequestPackageInstalls(context)
                downloadedChildApkFile?.let { file ->
                    if (file.exists() && hasInstallPermission) {
                        com.parentalcontrol.core.updater.AutoUpdateManager.installApk(context, file)
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Scaffold(
        containerColor = Color(0xFF0F172A),
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = Color(0xFF0D9488),
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Shield,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = strings.appTitleChild,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                color = Color.White
                            )
                            Text("SafeShield Client", fontSize = 11.sp, color = Color(0xFF94A3B8))
                        }
                    }
                },
                actions = {
                    ChildLanguageSlider(
                        currentLanguage = currentLang,
                        onLanguageChange = { newLang ->
                            currentLang = newLang
                            LanguageManager.setSavedLanguage(context, newLang)
                        }
                    )
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
            // 1. Real-World Protection Status Banner
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                border = BorderStroke(1.dp, Color(0xFF334155))
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Surface(
                        shape = CircleShape,
                        color = Color(0xFF064E3B),
                        modifier = Modifier.size(60.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Security,
                                contentDescription = null,
                                tint = Color(0xFF34D399),
                                modifier = Modifier.size(32.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = strings.protectionActive,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = "This device is protected by parent rules (Offline Zero-Trust Enforced)",
                        fontSize = 12.sp,
                        color = Color(0xFF94A3B8),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }

            // 2. Real-World Required Permissions Checklist Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                border = BorderStroke(1.dp, Color(0xFF334155))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = strings.protectionPermissions,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF94A3B8)
                    )

                    CleanPermissionItem(
                        title = strings.deviceAdmin,
                        description = strings.deviceAdminDesc,
                        isGranted = isAdminActive,
                        onRequest = onEnableAdmin,
                        enableLabel = strings.enable
                    )

                    CleanPermissionItem(
                        title = strings.accessibility,
                        description = strings.accessibilityDesc,
                        isGranted = false,
                        onRequest = onEnableAccessibility,
                        enableLabel = strings.enable
                    )

                    CleanPermissionItem(
                        title = strings.overlay,
                        description = strings.overlayDesc,
                        isGranted = isOverlayAllowed,
                        onRequest = onEnableOverlay,
                        enableLabel = strings.enable
                    )

                    CleanPermissionItem(
                        title = strings.batteryOptimization,
                        description = strings.batteryOptimizationDesc,
                        isGranted = isBatteryExempt,
                        onRequest = onEnableBatteryExemption,
                        enableLabel = strings.enable
                    )
                }
            }

            // 3. Pairing QR Code Card
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
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.QrCode2, contentDescription = null, tint = Color(0xFF0D9488), modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = strings.devicePairing,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
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
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0D9488)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(imageVector = Icons.Default.QrCode, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(strings.showPairingQr, fontWeight = FontWeight.Bold)
                    }
                }
            }

            // 4. Telegram Relay Transport Settings Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                border = BorderStroke(1.dp, Color(0xFF334155))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = strings.relayConfig,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF94A3B8)
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
                        shape = RoundedCornerShape(10.dp)
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
                        shape = RoundedCornerShape(10.dp)
                    )
                }
            }

            // 5. Real In-App Auto-Update Card
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
                                text = "${strings.version} $currentVersionName (Build $currentVersionCode)",
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
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0D9488))
                        ) {
                            if (isCheckingUpdate) {
                                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = Color.White)
                                Spacer(modifier = Modifier.width(6.dp))
                            } else {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
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
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFF042F2E),
                            border = BorderStroke(1.dp, Color(0xFF0D9488).copy(alpha = 0.5f)),
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

                                if (downloadedChildApkFile != null) {
                                    if (!hasInstallPermission) {
                                        Surface(
                                            shape = RoundedCornerShape(10.dp),
                                            color = Color(0xFF78350F).copy(alpha = 0.5f),
                                            border = BorderStroke(1.dp, Color(0xFFF59E0B)),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(10.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Text(
                                                    text = strings.allowInstallPrompt,
                                                    fontSize = 11.sp,
                                                    color = Color(0xFFFDE68A),
                                                    modifier = Modifier.weight(1f)
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                FilledTonalButton(
                                                    onClick = { com.parentalcontrol.core.updater.AutoUpdateManager.openInstallPermissionSettings(context) },
                                                    shape = RoundedCornerShape(8.dp)
                                                ) {
                                                    Text("Settings", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                                }
                                            }
                                        }
                                    }

                                    Button(
                                        onClick = {
                                            downloadedChildApkFile?.let { file ->
                                                val res = com.parentalcontrol.core.updater.AutoUpdateManager.installApk(context, file)
                                                if (res.isFailure) {
                                                    android.widget.Toast.makeText(context, "Error: ${res.exceptionOrNull()?.message}", android.widget.Toast.LENGTH_LONG).show()
                                                }
                                            }
                                        },
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF16A34A))
                                    ) {
                                        Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(strings.installNow, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    }
                                } else {
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
                                                    downloadedChildApkFile = file
                                                    updateStatusMsg = strings.downloadCompleteMsg
                                                    val res = com.parentalcontrol.core.updater.AutoUpdateManager.installApk(context, file)
                                                    if (res.isFailure) {
                                                        android.widget.Toast.makeText(context, "Error: ${res.exceptionOrNull()?.message}", android.widget.Toast.LENGTH_LONG).show()
                                                    }
                                                } else {
                                                    downloadStats = null
                                                    downloadedChildApkFile = null
                                                    val err = dl.exceptionOrNull()?.message ?: "Download failed"
                                                    updateStatusMsg = strings.updateFailed.format(err)
                                                    android.widget.Toast.makeText(context, updateStatusMsg, android.widget.Toast.LENGTH_LONG).show()
                                                }
                                            }
                                        },
                                        shape = RoundedCornerShape(10.dp),
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
        }
    }

    if (showQrDialog && qrBitmap != null) {
        AlertDialog(
            onDismissRequest = { showQrDialog = false },
            confirmButton = {
                Button(
                    onClick = { showQrDialog = false },
                    shape = RoundedCornerShape(8.dp),
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
                        shape = RoundedCornerShape(12.dp),
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
fun CleanPermissionItem(
    title: String,
    description: String,
    isGranted: Boolean,
    onRequest: () -> Unit,
    enableLabel: String = "Enable"
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
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = CircleShape,
                    color = if (isGranted) Color(0xFF064E3B) else Color(0xFF1E293B),
                    modifier = Modifier.size(32.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (isGranted) Icons.Default.Check else Icons.Default.Security,
                            contentDescription = null,
                            tint = if (isGranted) Color(0xFF34D399) else Color(0xFF94A3B8),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(text = title, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text(text = description, fontSize = 11.sp, color = Color(0xFF94A3B8))
                }
            }

            if (!isGranted) {
                FilledTonalButton(
                    onClick = onRequest,
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text(enableLabel, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/**
 * Segmented Language Slider for Child App (🇬🇧 EN / 🇰🇭 ខ្មែរ)
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
                    .background(if (!isKhmer) Color(0xFF0D9488) else Color.Transparent)
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
                    .background(if (isKhmer) Color(0xFF0D9488) else Color.Transparent)
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
