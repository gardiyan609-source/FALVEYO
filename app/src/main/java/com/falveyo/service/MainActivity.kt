package com.falveyo.service

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        AppSettings.init(this)

        // Kalıcı root oturumunu uygulama ilk açıldığında tek seferde başlat
        InputExecutor.init()

        setContent {
            FalveyoApp(
                onStartService = { startFalveyoService() },
                onStopService = { stopFalveyoService() },
                onScanToggle = { shouldScan ->
                    if (shouldScan) {
                        ensureServiceStarted()
                        FalveyoService.instance?.bleManager?.startScan()
                            ?: GlobalCursorState.setStatusLog("Servis başlatılıyor, lütfen tekrar deneyin...")
                    } else {
                        FalveyoService.instance?.bleManager?.stopScan()
                    }
                },
                onConnectDevice = { deviceItem ->
                    ensureServiceStarted()
                    FalveyoService.instance?.bleManager?.connect(deviceItem.device)
                },
                onDisconnect = {
                    FalveyoService.instance?.bleManager?.disconnect()
                },
                onRequestOverlay = {
                    if (!Settings.canDrawOverlays(this)) {
                        startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:$packageName")
                            )
                        )
                    }
                }
            )
        }
    }

    private fun ensureServiceStarted() {
        if (FalveyoService.instance == null) {
            startFalveyoService()
        }
    }

    private fun startFalveyoService() {
        val serviceIntent = Intent(this, FalveyoService::class.java).apply {
            action = FalveyoService.ACTION_START
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Servis başlatılamadı: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopFalveyoService() {
        FalveyoService.instance?.stopServiceInternal() ?: run {
            val stopIntent = Intent(this, FalveyoService::class.java).apply {
                action = FalveyoService.ACTION_STOP
            }
            stopService(stopIntent)
            GlobalCursorState.setServiceRunning(false)
            GlobalCursorState.setConnected(false)
            GlobalCursorState.setIsScanning(false)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FalveyoApp(
    onStartService: () -> Unit,
    onStopService: () -> Unit,
    onScanToggle: (Boolean) -> Unit,
    onConnectDevice: (ScannedBleDevice) -> Unit,
    onDisconnect: () -> Unit,
    onRequestOverlay: () -> Unit
) {
    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(0) }

    val isConnected by GlobalCursorState.connected.collectAsState()
    val connectedDeviceName by GlobalCursorState.connectedDeviceName.collectAsState()
    val connectedDeviceAddress by GlobalCursorState.connectedDeviceAddress.collectAsState()
    val position by GlobalCursorState.position.collectAsState()
    val touching by GlobalCursorState.touching.collectAsState()
    val isScanning by GlobalCursorState.isScanning.collectAsState()
    val scannedDevices by GlobalCursorState.scannedDevices.collectAsState()
    val isServiceRunning by GlobalCursorState.serviceRunning.collectAsState()
    val lastCommand by GlobalCursorState.lastCommand.collectAsState()
    val statusLog by GlobalCursorState.statusLog.collectAsState()
    val edgeScrollingDirection by GlobalCursorState.edgeScrollingDirection.collectAsState()

    var hasOverlayPermission by remember { mutableStateOf(Settings.canDrawOverlays(context)) }

    // İzin kontrolü ve talebi
    val requiredPermissions = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.POST_NOTIFICATIONS else null
            ).filterNotNull()
        } else {
            listOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        }
    }

    var allPermissionsGranted by remember {
        mutableStateOf(
            requiredPermissions.all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissionsMap ->
        allPermissionsGranted = permissionsMap.values.all { it }
    }

    LaunchedEffect(Unit) {
        if (!allPermissionsGranted) {
            permissionLauncher.launch(requiredPermissions.toTypedArray())
        }
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF00E5FF),
            onPrimary = Color.Black,
            secondary = Color(0xFF7C4DFF),
            background = Color(0xFF0D0E15),
            surface = Color(0xFF161822),
            surfaceVariant = Color(0xFF212534)
        )
    ) {
        Scaffold(
            topBar = {
                Column {
                    TopAppBar(
                        title = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Image(
                                    painter = painterResource(id = R.drawable.falveyo_logo),
                                    contentDescription = "FALVEYO Logo",
                                    modifier = Modifier
                                        .size(28.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    "FALVEYO",
                                    fontWeight = FontWeight.ExtraBold,
                                    letterSpacing = 2.sp,
                                    fontSize = 19.sp,
                                    color = Color.White
                                )
                                Spacer(Modifier.width(8.dp))
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(if (isConnected) Color(0xFF00E676) else if (isServiceRunning) Color(0xFFFFB300) else Color(0xFFFF5252))
                                )
                            }
                        },
                        actions = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(end = 8.dp)
                            ) {
                                Text(
                                    text = if (isServiceRunning) "Servis Aktif" else "Servis Kapalı",
                                    fontSize = 12.sp,
                                    color = if (isServiceRunning) Color(0xFF00E5FF) else Color(0xFF888888),
                                    modifier = Modifier.padding(end = 6.dp)
                                )
                                Switch(
                                    checked = isServiceRunning,
                                    onCheckedChange = { start ->
                                        if (start) onStartService() else onStopService()
                                    },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Color.White,
                                        checkedTrackColor = Color(0xFF00E5FF),
                                        uncheckedThumbColor = Color(0xFF888888),
                                        uncheckedTrackColor = Color(0xFF2A2D3D)
                                    )
                                )
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = Color(0xFF12141F)
                        )
                    )

                    // TAB BAR (Bağlantı & Ayarlar)
                    TabRow(
                        selectedTabIndex = selectedTab,
                        containerColor = Color(0xFF12141F),
                        contentColor = Color(0xFF00E5FF),
                        divider = { Divider(color = Color(0xFF232738)) }
                    ) {
                        Tab(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Bağlantı & Durum", fontWeight = FontWeight.SemiBold)
                                }
                            }
                        )
                        Tab(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Ayarlar", fontWeight = FontWeight.SemiBold)
                                }
                            }
                        )
                    }
                }
            },
            containerColor = Color(0xFF0D0E15)
        ) { paddingValues ->
            if (selectedTab == 0) {
                // BAĞLANTI & KONTROL SEKMESİ
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    if (!allPermissionsGranted || !hasOverlayPermission) {
                        item {
                            PermissionWarningCard(
                                allPermissionsGranted = allPermissionsGranted,
                                hasOverlayPermission = hasOverlayPermission,
                                onGrantBluetooth = { permissionLauncher.launch(requiredPermissions.toTypedArray()) },
                                onGrantOverlay = {
                                    onRequestOverlay()
                                    hasOverlayPermission = Settings.canDrawOverlays(context)
                                }
                            )
                        }
                    }



                    item {
                        StatusDashboardCard(
                            isConnected = isConnected,
                            connectedDeviceName = connectedDeviceName,
                            connectedDeviceAddress = connectedDeviceAddress,
                            position = position,
                            touching = touching,
                            lastCommand = lastCommand,
                            statusLog = statusLog,
                            edgeScrollingDirection = edgeScrollingDirection,
                            onDisconnect = onDisconnect
                        )
                    }

                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    "Çevredeki BLE Cihazları",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Text(
                                    if (isScanning) "Taranıyor... (${scannedDevices.size} cihaz bulundu)"
                                    else "${scannedDevices.size} cihaz listelendi",
                                    fontSize = 12.sp,
                                    color = if (isScanning) Color(0xFF00E5FF) else Color(0xFF888888)
                                )
                            }

                            Button(
                                onClick = { onScanToggle(!isScanning) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isScanning) Color(0xFFE53935) else Color(0xFF00E5FF),
                                    contentColor = if (isScanning) Color.White else Color.Black
                                ),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                if (isScanning) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = Color.White
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text("Durdur", fontWeight = FontWeight.SemiBold)
                                } else {
                                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Cihazları Tara", fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }

                    if (scannedDevices.isEmpty()) {
                        item {
                            EmptyDevicesCard()
                        }
                    } else {
                        items(scannedDevices, key = { it.address }) { deviceItem ->
                            val isThisDeviceConnected = isConnected && connectedDeviceAddress == deviceItem.address

                            DeviceListItemCard(
                                item = deviceItem,
                                isConnected = isThisDeviceConnected,
                                onConnect = { onConnectDevice(deviceItem) },
                                onDisconnect = { onDisconnect() }
                            )
                        }
                    }

                    // METİN SEÇİMİ TEST ALANI
                    item {
                        TextSelectionTestCard()
                    }

                    item {
                        ServiceLifecycleCard(
                            onStopService = {
                                onStopService()
                                (context as? ComponentActivity)?.finish()
                            }
                        )
                    }
                }
            } else {
                // AYARLAR SEKMESİ
                SettingsScreen(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                )
            }
        }
    }
}

// ----------------------------------------------------------------
// AYARLAR EKRANI
// ----------------------------------------------------------------

@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val cursorSpeed by AppSettings.cursorSpeed.collectAsState()
    val fineSensitivity by AppSettings.fineSensitivity.collectAsState()
    val deadzone by AppSettings.deadzone.collectAsState()
    val smoothingEnabled by AppSettings.smoothingEnabled.collectAsState()
    val smoothingFactor by AppSettings.smoothingFactor.collectAsState()
    val longPressMs by AppSettings.longPressMs.collectAsState()
    val touchMethod by AppSettings.touchMethod.collectAsState()
    val cursorRadius by AppSettings.cursorRadius.collectAsState()
    val cursorColor by AppSettings.cursorColor.collectAsState()
    val edgeScrollEnabled by AppSettings.edgeScrollEnabled.collectAsState()
    val edgeScrollDelayMs by AppSettings.edgeScrollDelayMs.collectAsState()
    val edgeScrollSpeed by AppSettings.edgeScrollSpeed.collectAsState()
    val edgeScrollMargin by AppSettings.edgeScrollMargin.collectAsState()

    val colorsList = listOf(
        0xFF00E5FF.toInt(), // Neon Camgöbeği
        0xFF00E676.toInt(), // Neon Yeşil
        0xFFFFD600.toInt(), // Sarı
        0xFFFF9100.toInt(), // Turuncu
        0xFFFF1744.toInt(), // Kırmızı
        0xFFE040FB.toInt(), // Magenta
        0xFF7C4DFF.toInt(), // Mor
        0xFFFFFFFF.toInt()  // Beyaz
    )

    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. İMLEÇ HIZI & HASSASİYETİ
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF161824)),
                shape = RoundedCornerShape(14.dp),
                border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(Color(0xFF262B3F)))
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color(0xFF00E5FF))
                        Spacer(Modifier.width(8.dp))
                        Text("İmleç Hızı (Speed)", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 16.sp)
                        Spacer(Modifier.weight(1f))
                        Surface(
                            color = Color(0x3300E5FF),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                "${cursorSpeed.roundToInt()}x",
                                color = Color(0xFF00E5FF),
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    Text(
                        "İmlecin ekranda ilerleme hız çarpanı. Düşük değerler daha yavaş ve kontrollü hareket sağlar.",
                        fontSize = 12.sp,
                        color = Color(0xFF8E95B3)
                    )
                    Spacer(Modifier.height(8.dp))

                    Slider(
                        value = cursorSpeed,
                        onValueChange = { AppSettings.setCursorSpeed(it) },
                        valueRange = 3f..40f,
                        steps = 37,
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFF00E5FF),
                            activeTrackColor = Color(0xFF00E5FF),
                            inactiveTrackColor = Color(0xFF2C3247)
                        )
                    )

                    // Hızlı Hazır Ayar Butonları
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("Yavaş (8)" to 8f, "Normal (15)" to 15f, "Hızlı (22)" to 22f, "Ultra (32)" to 32f).forEach { (label, value) ->
                            val isSelected = (cursorSpeed.roundToInt() == value.roundToInt())
                            OutlinedButton(
                                onClick = { AppSettings.setCursorSpeed(value) },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 2.dp, vertical = 4.dp),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = if (isSelected) Color(0x3300E5FF) else Color.Transparent,
                                    contentColor = if (isSelected) Color(0xFF00E5FF) else Color(0xFF8E95B3)
                                ),
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    if (isSelected) Color(0xFF00E5FF) else Color(0xFF2A2E42)
                                )
                            ) {
                                Text(label, fontSize = 10.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                            }
                        }
                    }
                }
            }
        }

        // 2. MİKRO HASSASİYET & KONTROL EĞRİSİ
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF161824)),
                shape = RoundedCornerShape(14.dp),
                border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(Color(0xFF262B3F)))
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Star, contentDescription = null, tint = Color(0xFF7C4DFF))
                        Spacer(Modifier.width(8.dp))
                        Text("Mikro Hareket Hassasiyeti", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 16.sp)
                        Spacer(Modifier.weight(1f))
                        Surface(
                            color = Color(0x337C4DFF),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                String.format("%.2f", fineSensitivity),
                                color = Color(0xFFB388FF),
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Joystick hafifçe eğildiğinde dahi mikro pikselleri algılamasını sağlar. 1.0 (Tam Doğrusal), 1.4 (Dengeli Hassas), 2.0 (Karesel Hızlı).",
                        fontSize = 12.sp,
                        color = Color(0xFF8E95B3)
                    )
                    Spacer(Modifier.height(6.dp))

                    Slider(
                        value = fineSensitivity,
                        onValueChange = { AppSettings.setFineSensitivity(it) },
                        valueRange = 1.0f..2.2f,
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFF7C4DFF),
                            activeTrackColor = Color(0xFF7C4DFF),
                            inactiveTrackColor = Color(0xFF2C3247)
                        )
                    )

                    Spacer(Modifier.height(6.dp))
                    // ÖLÜ BÖLGE (DEADZONE)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Ölü Bölge (Deadzone):", fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.width(8.dp))
                        Text("%${(deadzone * 100).roundToInt()}", fontSize = 13.sp, color = Color(0xFF7C4DFF), fontWeight = FontWeight.Bold)
                    }
                    Text(
                        "Joystick parmağınız üzerindeyken titremeyi keserken en ufak hareketi anında başlatır.",
                        fontSize = 11.sp,
                        color = Color(0xFF737996)
                    )
                    Slider(
                        value = deadzone,
                        onValueChange = { AppSettings.setDeadzone(it) },
                        valueRange = 0.00f..0.15f,
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFF9E77FF),
                            activeTrackColor = Color(0xFF9E77FF),
                            inactiveTrackColor = Color(0xFF2C3247)
                        )
                    )
                }
            }
        }

        // 3. 60 FPS PÜRÜZSÜZLEŞTİRME (KASILMAYI ÖNLEME MOTORU)
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF161824)),
                shape = RoundedCornerShape(14.dp),
                border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(Color(0xFF262B3F)))
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Refresh, contentDescription = null, tint = Color(0xFF00E676))
                            Spacer(Modifier.width(8.dp))
                            Text("Pürüzsüz İnterpolasyon (60 FPS)", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 15.sp)
                        }

                        Switch(
                            checked = smoothingEnabled,
                            onCheckedChange = { AppSettings.setSmoothingEnabled(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = Color(0xFF00E676),
                                uncheckedThumbColor = Color(0xFF888888),
                                uncheckedTrackColor = Color(0xFF2A2D3D)
                            )
                        )
                    }

                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Bluetooth veri paketleri arasındaki gecikmeleri yok ederek imlecin ekranda donmadan, pürüzsüz ve yağ gibi akmasını sağlar.",
                        fontSize = 12.sp,
                        color = Color(0xFF8E95B3)
                    )

                    if (smoothingEnabled) {
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Yumuşatma Tepki Oranı:", fontSize = 12.sp, color = Color.White)
                            Spacer(Modifier.width(8.dp))
                            Text("%${(smoothingFactor * 100).roundToInt()}", fontSize = 12.sp, color = Color(0xFF00E676), fontWeight = FontWeight.Bold)
                        }
                        Slider(
                            value = smoothingFactor,
                            onValueChange = { AppSettings.setSmoothingFactor(it) },
                            valueRange = 0.15f..0.85f,
                            colors = SliderDefaults.colors(
                                thumbColor = Color(0xFF00E676),
                                activeTrackColor = Color(0xFF00E676),
                                inactiveTrackColor = Color(0xFF2C3247)
                            )
                        )
                    }
                }
            }
        }

        // 4. METİN SEÇME & DOKUNMA YÖNTEMİ AYARLARI
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF161824)),
                shape = RoundedCornerShape(14.dp),
                border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(Color(0xFF262B3F)))
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Edit, contentDescription = null, tint = Color(0xFFFF9800))
                        Spacer(Modifier.width(8.dp))
                        Text("Metin Seçme & Basılı Tutma", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 16.sp)
                    }

                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Bir metni seçmek veya öğeyi sürüklemek için joystick butonuna basılı tuttuğunuzda kullanılan protokol.",
                        fontSize = 12.sp,
                        color = Color(0xFF8E95B3)
                    )

                    Spacer(Modifier.height(12.dp))
                    Text("Dokunma İletim Yöntemi:", fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))

                    TouchInputMethod.values().forEach { method ->
                        val isSelected = touchMethod == method
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clickable { AppSettings.setTouchMethod(method) },
                            color = if (isSelected) Color(0xFF222B2F) else Color(0xFF12141F),
                            shape = RoundedCornerShape(8.dp),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (isSelected) Color(0xFFFF9800) else Color(0xFF24283A)
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = { AppSettings.setTouchMethod(method) },
                                    colors = RadioButtonDefaults.colors(selectedColor = Color(0xFFFF9800))
                                )
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text(method.title, fontWeight = FontWeight.Bold, color = Color.White, fontSize = 13.sp)
                                    Text(method.description, color = Color(0xFF8E95B3), fontSize = 11.sp)
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Uzun Basış Eşiği (Metin Seçim Tetikleme):", fontSize = 12.sp, color = Color.White)
                        Spacer(Modifier.width(8.dp))
                        Text("${longPressMs}ms", fontSize = 12.sp, color = Color(0xFFFF9800), fontWeight = FontWeight.Bold)
                    }
                    Slider(
                        value = longPressMs.toFloat(),
                        onValueChange = { AppSettings.setLongPressMs(it.toLong()) },
                        valueRange = 250f..800f,
                        steps = 11,
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFFFF9800),
                            activeTrackColor = Color(0xFFFF9800),
                            inactiveTrackColor = Color(0xFF2C3247)
                        )
                    )
                }
            }
        }

        // 5. İMLEÇ GÖRÜNÜMÜ & RENK PALETİ
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF161824)),
                shape = RoundedCornerShape(14.dp),
                border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(Color(0xFF262B3F)))
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Face, contentDescription = null, tint = Color(0xFFE040FB))
                        Spacer(Modifier.width(8.dp))
                        Text("İmleç Görünümü", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 16.sp)
                    }

                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("İmleç Boyutu (Radius):", fontSize = 13.sp, color = Color.White)
                        Spacer(Modifier.width(8.dp))
                        Text("${cursorRadius.roundToInt()} px", fontSize = 13.sp, color = Color(0xFFE040FB), fontWeight = FontWeight.Bold)
                    }

                    Slider(
                        value = cursorRadius,
                        onValueChange = { AppSettings.setCursorRadius(it) },
                        valueRange = 10f..35f,
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFFE040FB),
                            activeTrackColor = Color(0xFFE040FB),
                            inactiveTrackColor = Color(0xFF2C3247)
                        )
                    )

                    Spacer(Modifier.height(8.dp))
                    Text("İmleç Rengi:", fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        colorsList.forEach { colInt ->
                            val isSelected = (cursorColor == colInt)
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(Color(colInt))
                                    .border(
                                        width = if (isSelected) 3.dp else 1.dp,
                                        color = if (isSelected) Color.White else Color.Transparent,
                                        shape = CircleShape
                                    )
                                    .clickable { AppSettings.setCursorColor(colInt) },
                                contentAlignment = Alignment.Center
                            ) {
                                if (isSelected) {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = null,
                                        tint = if (colInt == 0xFFFFFFFF.toInt()) Color.Black else Color.White,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 6. KENAR KAYDIRMA (Joystick İtme / Basınç Bazlı - Proportional Edge Scroll)
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF161824)),
                shape = RoundedCornerShape(14.dp),
                border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(Color(0xFF262B3F)))
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Menu, contentDescription = null, tint = Color(0xFF00E5FF))
                        Spacer(Modifier.width(8.dp))
                        Text("Kenar Kaydırma (Joystick İtme)", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 16.sp)
                        Spacer(Modifier.weight(1f))
                        Switch(
                            checked = edgeScrollEnabled,
                            onCheckedChange = { AppSettings.setEdgeScrollEnabled(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color(0xFF00E5FF),
                                checkedTrackColor = Color(0x5500E5FF),
                                uncheckedThumbColor = Color(0xFF8E95B3),
                                uncheckedTrackColor = Color(0xFF2C3247)
                            )
                        )
                    }

                    Spacer(Modifier.height(10.dp))
                    Text(
                        "İmleç ekran kenarındayken joystick ile o yöne ittiğiniz oranda hızlı veya yavaş kaydırılır. Joystick'i bıraktığınızda durur, kendi kendine otomatik kaydırmaz.",
                        fontSize = 12.sp,
                        color = Color(0xFF8E95B3)
                    )

                    if (edgeScrollEnabled) {
                        Spacer(Modifier.height(14.dp))
                        Divider(color = Color(0xFF262B3F))
                        Spacer(Modifier.height(14.dp))

                        // Kaydırma Hızı Çarpanı
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Kaydırma Hız Çarpanı", fontWeight = FontWeight.Medium, color = Color.White, fontSize = 14.sp)
                            Spacer(Modifier.weight(1f))
                            Text(String.format("%.1fx", edgeScrollSpeed), color = Color(0xFF00E5FF), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                        Slider(
                            value = edgeScrollSpeed,
                            onValueChange = { AppSettings.setEdgeScrollSpeed(it) },
                            valueRange = 0.5f..2.5f,
                            steps = 19,
                            colors = SliderDefaults.colors(
                                thumbColor = Color(0xFF00E5FF),
                                activeTrackColor = Color(0xFF00E5FF),
                                inactiveTrackColor = Color(0xFF2C3247)
                            )
                        )

                        Spacer(Modifier.height(10.dp))

                        // Kenar Algılama Mesafesi
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Kenar Algılama Alanı", fontWeight = FontWeight.Medium, color = Color.White, fontSize = 14.sp)
                            Spacer(Modifier.weight(1f))
                            Text("${edgeScrollMargin.toInt()} px", color = Color(0xFF00E5FF), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                        Slider(
                            value = edgeScrollMargin,
                            onValueChange = { AppSettings.setEdgeScrollMargin(it) },
                            valueRange = 20f..90f,
                            steps = 13,
                            colors = SliderDefaults.colors(
                                thumbColor = Color(0xFF00E5FF),
                                activeTrackColor = Color(0xFF00E5FF),
                                inactiveTrackColor = Color(0xFF2C3247)
                            )
                        )
                    }
                }
            }
        }

        // 7. SIFIRLA BUTONU
        item {
            OutlinedButton(
                onClick = { AppSettings.resetToDefaults() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF5252)),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x55FF5252)),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Tüm Ayarları Varsayılana Döndür")
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

// ----------------------------------------------------------------
// YARDIMCI BİLEŞENLER
// ----------------------------------------------------------------

@Composable
fun TextSelectionTestCard() {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF141A28)),
        shape = RoundedCornerShape(14.dp),
        border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(Color(0xFF2E3E5B))),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.ThumbUp, contentDescription = null, tint = Color(0xFF00E5FF), modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Metin Seçimi & Test Alanı", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 14.sp)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "İmleci aşağıdaki metnin üzerine getirip joystick butonuna basılı tutun. Metin seçme çubuğunun açıldığını ve metni kaydırarak seçebildiğinizi buradan test edebilirsiniz:",
                fontSize = 11.sp,
                color = Color(0xFF909BBF)
            )
            Spacer(Modifier.height(8.dp))

            Surface(
                color = Color(0xFF0A0C13),
                shape = RoundedCornerShape(8.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF262C44)),
                modifier = Modifier.fillMaxWidth()
            ) {
                SelectionContainer {
                    Text(
                        text = "FALVEYO ESP32 BLE joystick kontrolü ile bu metni basılı tutarak kolayca seçebilir, kopyalayabilir ve Android üzerinde tam fare/dokunma deneyimi yaşayabilirsiniz.",
                        fontSize = 13.sp,
                        color = Color(0xFFE2E8F0),
                        modifier = Modifier.padding(12.dp),
                        lineHeight = 18.sp
                    )
                }
            }
        }
    }
}

@Composable
fun PermissionWarningCard(
    allPermissionsGranted: Boolean,
    hasOverlayPermission: Boolean,
    onGrantBluetooth: () -> Unit,
    onGrantOverlay: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF332014)),
        border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(Color(0xFFFF9800))),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFFF9800))
                Spacer(Modifier.width(8.dp))
                Text("Gerekli İzinler Eksik", fontWeight = FontWeight.Bold, color = Color.White)
            }
            Spacer(Modifier.height(6.dp))
            if (!allPermissionsGranted) {
                Text(
                    "• Bluetooth ve Konum izinleri BLE taraması için gereklidir.",
                    fontSize = 12.sp,
                    color = Color(0xFFE0E0E0)
                )
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = onGrantBluetooth,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800))
                ) {
                    Text("Bluetooth İznini Ver", color = Color.Black, fontSize = 12.sp)
                }
            }
            if (!hasOverlayPermission) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "• İmleci ekranda gösterebilmek için 'Diğer uygulamaların üzerinde görüntüleme' izni gereklidir.",
                    fontSize = 12.sp,
                    color = Color(0xFFE0E0E0)
                )
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = onGrantOverlay,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF))
                ) {
                    Text("Overlay İznini Aç", color = Color.Black, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
fun StatusDashboardCard(
    isConnected: Boolean,
    connectedDeviceName: String?,
    connectedDeviceAddress: String?,
    position: android.graphics.PointF,
    touching: Boolean,
    lastCommand: String?,
    statusLog: String,
    edgeScrollingDirection: EdgeScrollDirection = EdgeScrollDirection.NONE,
    onDisconnect: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isConnected) Color(0xFF142921) else Color(0xFF161826)
        ),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = androidx.compose.ui.graphics.SolidColor(
                if (isConnected) Color(0xFF00E676) else Color(0xFF2C3147)
            )
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (isConnected) Icons.Default.CheckCircle else Icons.Default.Info,
                        contentDescription = null,
                        tint = if (isConnected) Color(0xFF00E676) else Color(0xFFFF9800),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (isConnected) "BAĞLANDI" else "BAĞLANTI BEKLENİYOR",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = if (isConnected) Color(0xFF00E676) else Color(0xFFFF9800),
                        letterSpacing = 1.sp
                    )
                }

                if (isConnected) {
                    TextButton(
                        onClick = onDisconnect,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text("Bağlantıyı Kes", color = Color(0xFFFF5252), fontSize = 12.sp)
                    }
                }
            }

            if (isConnected && connectedDeviceName != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Cihaz: $connectedDeviceName ${connectedDeviceAddress?.let { "($it)" } ?: ""}",
                    fontSize = 13.sp,
                    color = Color.White,
                    fontWeight = FontWeight.Medium
                )
            }

            if (edgeScrollingDirection != EdgeScrollDirection.NONE) {
                Spacer(Modifier.height(8.dp))
                Surface(
                    color = Color(0x3300E5FF),
                    shape = RoundedCornerShape(6.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x6600E5FF))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = Color(0xFF00E5FF),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "Kenar Otomatik Kaydırma Aktif: ${edgeScrollingDirection.title}",
                            color = Color(0xFF00E5FF),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Divider(color = Color(0x33FFFFFF))
            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("İMLEÇ KONUMU", fontSize = 10.sp, color = Color(0xFF8C93B0), fontWeight = FontWeight.SemiBold)
                    Text(
                        "X: ${position.x.toInt()}  Y: ${position.y.toInt()}",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text("DOKUNMA / KOMUT", fontSize = 10.sp, color = Color(0xFF8C93B0), fontWeight = FontWeight.SemiBold)
                    Text(
                        text = if (edgeScrollingDirection != EdgeScrollDirection.NONE) "KENAR KAYDIRILIYOR" else if (touching) "BASILI (Metin Seçimi/Sürükleme)" else (lastCommand ?: "Boşta"),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (edgeScrollingDirection != EdgeScrollDirection.NONE || touching) Color(0xFF00E5FF) else Color(0xFFE0E0E0)
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            Surface(
                color = Color(0xFF0B0D13),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Durum: $statusLog",
                    fontSize = 11.sp,
                    color = Color(0xFF9FA7C7),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun EmptyDevicesCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF161822)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Default.Search,
                contentDescription = null,
                tint = Color(0xFF555B77),
                modifier = Modifier.size(40.dp)
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "Henüz cihaz bulunamadı",
                fontWeight = FontWeight.Medium,
                color = Color(0xFF8C93B0),
                fontSize = 14.sp
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "FALVEYO veya ESP32 cihazınızı açın ve 'Cihazları Tara' butonuna basın.",
                fontSize = 12.sp,
                color = Color(0xFF555B77),
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun DeviceListItemCard(
    item: ScannedBleDevice,
    isConnected: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isConnected) Color(0xFF152A22) else if (item.isFalveyoOrEsp) Color(0xFF1E2336) else Color(0xFF161824)
        ),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = androidx.compose.ui.graphics.SolidColor(
                if (isConnected) Color(0xFF00E676)
                else if (item.isFalveyoOrEsp) Color(0xFF7C4DFF)
                else Color(0xFF262B3F)
            )
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(if (item.isFalveyoOrEsp) Color(0xFF7C4DFF) else Color(0xFF262A3C)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (item.isFalveyoOrEsp) Icons.Default.Star else Icons.Default.Place,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(Modifier.width(12.dp))

                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = item.name,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (item.isFalveyoOrEsp) {
                            Spacer(Modifier.width(6.dp))
                            Surface(
                                color = Color(0x337C4DFF),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    "ESP32",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFB388FF),
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "${item.address}  •  ${item.rssi} dBm",
                        fontSize = 11.sp,
                        color = Color(0xFF8A92B2),
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            Spacer(Modifier.width(8.dp))

            if (isConnected) {
                Button(
                    onClick = onDisconnect,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF2E7D32),
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Bağlı", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            } else {
                Button(
                    onClick = onConnect,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (item.isFalveyoOrEsp) Color(0xFF00E5FF) else Color(0xFF2C3247),
                        contentColor = if (item.isFalveyoOrEsp) Color.Black else Color.White
                    ),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text("Bağlan", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
fun ServiceLifecycleCard(onStopService: () -> Unit) {
    Spacer(Modifier.height(8.dp))
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF12141F)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                "Yaşam Döngüsü & Enerji Koruması",
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFFB0B7D0),
                fontSize = 13.sp
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "• BLE Taraması yalnızca siz istediğinizde çalışır ve 15 saniye sonra otomatik durur.\n" +
                        "• Uygulamayı veya servisi kapattığınızda BLE bağlantısı, tarama ve bildirim tamamen temizlenir.",
                fontSize = 11.sp,
                color = Color(0xFF72799A),
                lineHeight = 16.sp
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = onStopService,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF5252)),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x66FF5252)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Servisi ve Uygulamayı Tamamen Kapat", fontSize = 12.sp)
            }
        }
    }
}
