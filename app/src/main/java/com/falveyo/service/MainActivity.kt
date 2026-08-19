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
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
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
    val touching by GlobalCursorState.touching.collectAsState()
    val isScanning by GlobalCursorState.isScanning.collectAsState()
    val scannedDevices by GlobalCursorState.scannedDevices.collectAsState()
    val isServiceRunning by GlobalCursorState.serviceRunning.collectAsState()
    val statusLog by GlobalCursorState.statusLog.collectAsState()
    val edgeScrollingDirection by GlobalCursorState.edgeScrollingDirection.collectAsState()
    val lastButtonEvent by GlobalCursorState.lastButtonEvent.collectAsState()
    val buttonEventTimestamp by GlobalCursorState.buttonEventTimestamp.collectAsState()

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
            primary = Color.White,
            onPrimary = Color.Black,
            secondary = Color(0xFF00E5FF),
            background = Color(0xFF000000),
            surface = Color(0xFF22242E),
            surfaceVariant = Color(0xFF2A2D3A),
            onBackground = Color.White,
            onSurface = Color.White
        )
    ) {
        Scaffold(
            topBar = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF000000))
                ) {
                    // ÜST BAR: LOGO, BAŞLIK, DURUM IŞIĞI & SERVİS SWITCH
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        // SOL: LOGO + BAŞLIK + DURUM NOKTASI
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Image(
                                painter = painterResource(id = R.drawable.falveyo_logo_),
                                contentDescription = "FALVEYO Logo",
                                modifier = Modifier
                                    .size(30.dp)
                                    .clip(RoundedCornerShape(8.dp))
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                "FALVEYO",
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.5.sp,
                                fontSize = 18.sp,
                                color = Color.White
                            )
                            Spacer(Modifier.width(8.dp))
                            // Durum Gösterge Noktası (Kırmızı / Turuncu / Yeşil)
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (isConnected) Color(0xFF34C759)
                                        else if (isServiceRunning) Color(0xFFFF9F0A)
                                        else Color(0xFFFF3B30)
                                    )
                            )
                        }

                        // SAĞ: SERVİS AÇMA / KAPATMA SWITCH
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = if (isServiceRunning) "Servis Aktif" else "Servis Kapalı",
                                fontSize = 12.sp,
                                color = if (isServiceRunning) Color(0xFF00E5FF) else Color(0xFF8E8E98)
                            )
                            Switch(
                                checked = isServiceRunning,
                                onCheckedChange = { start ->
                                    if (start) onStartService() else onStopService()
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = Color(0xFF00E5FF),
                                    uncheckedThumbColor = Color(0xFF8E8E98),
                                    uncheckedTrackColor = Color(0xFF2C303E)
                                ),
                                modifier = Modifier.height(28.dp)
                            )
                        }
                    }

                    // SEKME ÇUBUĞU (Bağlantı & Durum | Ayarlar)
                    TabRow(
                        selectedTabIndex = selectedTab,
                        containerColor = Color(0xFF000000),
                        contentColor = Color.White,
                        divider = { Divider(color = Color(0xFF2C303E), thickness = 1.dp) },
                        indicator = { tabPositions ->
                            if (selectedTab < tabPositions.size) {
                                TabRowDefaults.SecondaryIndicator(
                                    modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                                    color = Color.White,
                                    height = 2.dp
                                )
                            }
                        }
                    ) {
                        Tab(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.Wifi,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = if (selectedTab == 0) Color.White else Color(0xFF7E7E8A)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        "Bağlantı & Durum",
                                        fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Normal,
                                        fontSize = 13.sp,
                                        color = if (selectedTab == 0) Color.White else Color(0xFF7E7E8A)
                                    )
                                }
                            }
                        )
                        Tab(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.Settings,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = if (selectedTab == 1) Color.White else Color(0xFF7E7E8A)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        "Ayarlar",
                                        fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Normal,
                                        fontSize = 13.sp,
                                        color = if (selectedTab == 1) Color.White else Color(0xFF7E7E8A)
                                    )
                                }
                            }
                        )
                    }
                }
            },
            containerColor = Color(0xFF000000)
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
                    // 1. KART: GEREKLİ İZİNLER EKSİK KARTI (Hafif gri iç, belirgin kalın kenarlık)
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

                    // 2. KART: BAĞLANTI BEKLENİYOR / BAĞLANDI KARTI (Hafif gri iç, belirgin kenarlık)
                    item {
                        StatusDashboardCard(
                            isConnected = isConnected,
                            connectedDeviceName = connectedDeviceName,
                            connectedDeviceAddress = connectedDeviceAddress,
                            touching = touching,
                            statusLog = statusLog,
                            edgeScrollingDirection = edgeScrollingDirection,
                            onDisconnect = onDisconnect
                        )
                    }

                    // 3. KART: HIZLI TIKLAMA & TUŞ TEST ALANI (MİNİ TEST PEDİ)
                    item {
                        QuickButtonTestPadCard(
                            touching = touching,
                            lastButtonEvent = lastButtonEvent,
                            buttonEventTimestamp = buttonEventTimestamp
                        )
                    }

                    // 4. ÇEVREDEKİ BLE CİHAZLARI BAŞLIK VE TARA BUTONU
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    "Çevredeki BLE Cihazları",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    if (isScanning) "Taranıyor... (${scannedDevices.size} cihaz)"
                                    else "${scannedDevices.size} cihaz listelendi",
                                    fontSize = 12.sp,
                                    color = Color(0xFF8E8E98)
                                )
                            }

                            // Cihazları Tara Butonu (Belirgin kenarlı şık koyu buton)
                            OutlinedButton(
                                onClick = { onScanToggle(!isScanning) },
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = if (isScanning) Color(0xFF2A1518) else Color(0xFF22242E),
                                    contentColor = if (isScanning) Color(0xFFFF453A) else Color.White
                                ),
                                border = androidx.compose.foundation.BorderStroke(
                                    1.5.dp,
                                    if (isScanning) Color(0xFFFF453A) else Color(0xFF4C5266)
                                ),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                            ) {
                                if (isScanning) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(14.dp),
                                        strokeWidth = 2.dp,
                                        color = Color(0xFFFF453A)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text("Durdur", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                } else {
                                    Icon(
                                        Icons.Default.Sync,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = Color.White
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        "Cihazları Tara",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color.White
                                    )
                                }
                            }
                        }
                    }

                    // BOŞ CİHAZ KARTI VEYA LİSTELENEN CİHAZLAR (GÖRSEL SİNYAL GÜCÜ ÇUBUĞU İLE)
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

                    // 5. KART: METİN SEÇİMİ & TEST ALANI KARTI
                    item {
                        TextSelectionTestCard()
                    }

                    // 6. KART: YAŞAM DÖNGÜSÜ & ENERJİ KORUMASI KARTI
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
// 1. KART: İZİNLER UYARI KARTI (Hafif Gri İç, Belirgin Kenarlık)
// ----------------------------------------------------------------

@Composable
fun PermissionWarningCard(
    allPermissionsGranted: Boolean,
    hasOverlayPermission: Boolean,
    onGrantBluetooth: () -> Unit,
    onGrantOverlay: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF22242E)),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = androidx.compose.ui.graphics.SolidColor(Color(0xFF4C5266)),
            width = 1.5.dp
        ),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = Color(0xFFFF9F0A),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Gerekli İzinler Eksik",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = Color.White
                    )
                }
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = Color(0xFF8E8E98),
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(Modifier.height(8.dp))

            if (!hasOverlayPermission) {
                Text(
                    "İmleci ekranda gösterebilmek için 'Diğer uygulamaların üzerinde görüntüleme' izni gereklidir.",
                    fontSize = 12.sp,
                    color = Color(0xFFA0A0AC),
                    lineHeight = 16.sp
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = onGrantOverlay,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = Color(0xFF2C2F3C),
                        contentColor = Color.White
                    ),
                    border = androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFF565D74)),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text("Overlay İznini Aç", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            if (!allPermissionsGranted) {
                if (!hasOverlayPermission) Spacer(Modifier.height(10.dp))
                Text(
                    "BLE joystick taraması ve bağlantısı için Bluetooth ve Konum izinleri gereklidir.",
                    fontSize = 12.sp,
                    color = Color(0xFFA0A0AC),
                    lineHeight = 16.sp
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = onGrantBluetooth,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = Color(0xFF2C2F3C),
                        contentColor = Color.White
                    ),
                    border = androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFF565D74)),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text("Bluetooth İznini Ver", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

// ----------------------------------------------------------------
// 2. KART: BAĞLANTI BEKLENİYOR / BAĞLANDI KARTI (Hafif Gri İç, Belirgin Kenarlık)
// ----------------------------------------------------------------

@Composable
fun StatusDashboardCard(
    isConnected: Boolean,
    connectedDeviceName: String?,
    connectedDeviceAddress: String?,
    touching: Boolean,
    statusLog: String,
    edgeScrollingDirection: EdgeScrollDirection = EdgeScrollDirection.NONE,
    onDisconnect: () -> Unit
) {
    val cardBorder = if (isConnected) Color(0xFF34C759) else Color(0xFF4C5266)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF22242E)),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = androidx.compose.ui.graphics.SolidColor(cardBorder),
            width = 1.5.dp
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
                        Icons.Default.Sensors,
                        contentDescription = null,
                        tint = if (isConnected) Color(0xFF34C759) else Color(0xFF9E9EA8),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = if (isConnected) "BAĞLANDI" else "BAĞLANTI BEKLENİYOR",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = if (isConnected) Color(0xFF34C759) else Color.White,
                        letterSpacing = 0.5.sp
                    )
                }

                if (isConnected) {
                    TextButton(
                        onClick = onDisconnect,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text("Bağlantıyı Kes", color = Color(0xFFFF453A), fontSize = 12.sp)
                    }
                }
            }

            if (isConnected && connectedDeviceName != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Cihaz: $connectedDeviceName ${connectedDeviceAddress?.let { "($it)" } ?: ""}",
                    fontSize = 12.sp,
                    color = Color.White,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(Modifier.height(14.dp))

            // DOKUNMA / TUŞ DURUMU Satırı (Sıfır Recomposition - Akıcı)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "DOKUNMA / TUŞ DURUMU",
                    fontSize = 11.sp,
                    color = Color(0xFF8E8E98),
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = if (edgeScrollingDirection != EdgeScrollDirection.NONE) "Kenar Kaydırılıyor"
                    else if (touching) "Basılı Tutuluyor"
                    else "Boşta (Hazır)",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (edgeScrollingDirection != EdgeScrollDirection.NONE || touching) Color(0xFF00E5FF) else Color(0xFFD4D4DC)
                )
            }

            Spacer(Modifier.height(12.dp))

            // Durum Log Çubuğu
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF16171E))
                    .border(1.dp, Color(0xFF333748), RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(
                    text = "Durum: ${statusLog.ifBlank { "Tarama tamamlandı." }}",
                    fontSize = 11.sp,
                    color = Color(0xFFA0A0AC),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

// ----------------------------------------------------------------
// 3. KART: HIZLI TIKLAMA & TUŞ TEST ALANI (MİNİ TEST PEDİ)
// ----------------------------------------------------------------

@Composable
fun QuickButtonTestPadCard(
    touching: Boolean,
    lastButtonEvent: ButtonActionType,
    buttonEventTimestamp: Long
) {
    // Tıklama ve geri aksiyonları için hafif görsel aydınlanma
    var isClickActive by remember { mutableStateOf(false) }
    var isBackActive by remember { mutableStateOf(false) }
    var isLongPressActive by remember { mutableStateOf(false) }

    LaunchedEffect(buttonEventTimestamp, lastButtonEvent) {
        if (lastButtonEvent == ButtonActionType.SHORT_CLICK) {
            isClickActive = true
            delay(500L)
            isClickActive = false
        } else if (lastButtonEvent == ButtonActionType.BACK) {
            isBackActive = true
            delay(500L)
            isBackActive = false
        } else if (lastButtonEvent == ButtonActionType.LONG_PRESS) {
            isLongPressActive = true
            delay(600L)
            isLongPressActive = false
        }
    }

    val longPressLit = touching || isLongPressActive
    val clickLit = isClickActive
    val backLit = isBackActive

    // Mini Test Pedi için manuel basma simülasyonu
    var manualTouching by remember { mutableStateOf(false) }
    val effectiveTouching = touching || manualTouching

    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF22242E)),
        shape = RoundedCornerShape(12.dp),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = androidx.compose.ui.graphics.SolidColor(Color(0xFF4C5266)),
            width = 1.5.dp
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            // Başlık Satırı
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.TouchApp,
                        contentDescription = null,
                        tint = Color(0xFF00E5FF),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Hızlı Tıklama & Tuş Test Pedi",
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontSize = 14.sp
                    )
                }

                // Canlı Gösterge Rozeti
                Surface(
                    color = if (effectiveTouching) Color(0x3300E5FF) else Color(0xFF1B1C24),
                    shape = RoundedCornerShape(6.dp),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (effectiveTouching) Color(0xFF00E5FF) else Color(0xFF3E4354)
                    )
                ) {
                    Text(
                        text = if (effectiveTouching) "CANLI BASIŞ" else "HAZIR",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (effectiveTouching) Color(0xFF00E5FF) else Color(0xFF8E8E98),
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                "Joystick butonuna bastığınızda veya ekrana dokunduğunuzda anlık tuş algılamasını buradan izleyin.",
                fontSize = 12.sp,
                color = Color(0xFFA0A0AC),
                lineHeight = 16.sp
            )

            Spacer(Modifier.height(14.dp))

            // 3'LÜ IŞIKLI TUŞ GÖSTERGE ÇUBUĞU
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 1. TEK TIK GÖSTERGESİ (Cyan)
                TestKeyIndicatorPill(
                    title = "Tek Tık",
                    isActive = clickLit,
                    activeColor = Color(0xFF00E5FF),
                    activeBg = Color(0xFF123438),
                    icon = Icons.Default.CheckCircle,
                    modifier = Modifier.weight(1f)
                )

                // 2. UZUN BASIŞ GÖSTERGESİ (Amber)
                TestKeyIndicatorPill(
                    title = "Uzun Basış",
                    isActive = longPressLit,
                    activeColor = Color(0xFFFF9500),
                    activeBg = Color(0xFF382612),
                    icon = Icons.Default.Timer,
                    modifier = Modifier.weight(1f)
                )

                // 3. GERİ TUŞU GÖSTERGESİ (Mor)
                TestKeyIndicatorPill(
                    title = "Geri Tuşu",
                    isActive = backLit,
                    activeColor = Color(0xFFAF52DE),
                    activeBg = Color(0xFF2E1538),
                    icon = Icons.Default.ArrowBack,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(12.dp))

            // DOKUNMATİK / JOYSTICK MİNİ TEST PEDİ ALANI
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF16171E))
                    .border(
                        width = 1.dp,
                        color = Color(0xFF333748),
                        shape = RoundedCornerShape(10.dp)
                    )
                    .padding(vertical = 12.dp, horizontal = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    // SADECE ORTADAKİ DOKUNMA BUTONU
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clip(CircleShape)
                            .background(if (effectiveTouching) Color(0xFF00E5FF) else Color(0xFF22242E))
                            .border(
                                width = if (effectiveTouching) 2.dp else 1.5.dp,
                                color = if (effectiveTouching) Color.White else Color(0xFF4C5266),
                                shape = CircleShape
                            )
                            .pointerInput(Unit) {
                                awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false)
                                    manualTouching = true
                                    GlobalCursorState.setTouching(true)
                                    val startTime = System.currentTimeMillis()

                                    // Kullanıcı parmağını kaldırana kadar kesintisiz basılı tut
                                    do {
                                        val event = awaitPointerEvent()
                                        val anyPressed = event.changes.any { it.pressed }
                                    } while (anyPressed)

                                    val elapsed = System.currentTimeMillis() - startTime
                                    manualTouching = false
                                    GlobalCursorState.setTouching(false)
                                    if (elapsed >= 350L) {
                                        GlobalCursorState.recordButtonEvent(ButtonActionType.LONG_PRESS)
                                    } else {
                                        GlobalCursorState.recordButtonEvent(ButtonActionType.SHORT_CLICK)
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (effectiveTouching) Icons.Default.TouchApp else Icons.Default.RadioButtonChecked,
                            contentDescription = "Dokunma Test Butonu",
                            tint = if (effectiveTouching) Color(0xFF0A0B10) else Color(0xFF00E5FF),
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    Text(
                        text = if (effectiveTouching) "● BASILI TUTULUYOR (CANLI)" else "DOKUNMA TEST BUTONU",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (effectiveTouching) Color(0xFF00E5FF) else Color(0xFFD4D4DC)
                    )
                    Text(
                        text = if (effectiveTouching) "Basış algılandı, parmağınızı kaldırana kadar aktif" else "Yalnızca ortadaki butona basıldığında veya joystick butonuyla aktif olur",
                        fontSize = 10.sp,
                        color = Color(0xFF8E8E98)
                    )
                }
            }
        }
    }
}

@Composable
fun TestKeyIndicatorPill(
    title: String,
    isActive: Boolean,
    activeColor: Color,
    activeBg: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = if (isActive) activeBg else Color(0xFF16171E),
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(
            width = if (isActive) 1.5.dp else 1.dp,
            color = if (isActive) activeColor else Color(0xFF2C303E)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp, horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isActive) activeColor else Color(0xFF787884),
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(5.dp))
            Text(
                text = title,
                fontSize = 11.sp,
                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                color = if (isActive) activeColor else Color(0xFFA0A0AC),
                maxLines = 1
            )
        }
    }
}

// ----------------------------------------------------------------
// 4. BOŞ CİHAZ KARTI (Hafif Gri İç, Belirgin Kenarlık)
// ----------------------------------------------------------------

@Composable
fun EmptyDevicesCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF22242E)),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = androidx.compose.ui.graphics.SolidColor(Color(0xFF4C5266)),
            width = 1.5.dp
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 32.dp, horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Default.Search,
                contentDescription = null,
                tint = Color(0xFF8E8E98),
                modifier = Modifier.size(36.dp)
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "Henüz cihaz bulunamadı",
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFFD4D4DC),
                fontSize = 14.sp
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "FALVEYO veya ESP32 cihazınızı seçmek için\n' Cihazları Tara ' butonuna basın.",
                fontSize = 12.sp,
                color = Color(0xFF8E8E98),
                textAlign = TextAlign.Center,
                lineHeight = 17.sp
            )
        }
    }
}

// ----------------------------------------------------------------
// 5. CİHAZ LİSTE ELEMANI KARTI (Hafif Gri İç, Belirgin Kenarlık)
// ----------------------------------------------------------------

@Composable
fun DeviceListItemCard(
    item: ScannedBleDevice,
    isConnected: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit
) {
    val cardBg = if (isConnected) Color(0xFF15261D) else Color(0xFF22242E)
    val cardBorder = if (isConnected) Color(0xFF34C759) else Color(0xFF4C5266)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = cardBg),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = androidx.compose.ui.graphics.SolidColor(cardBorder),
            width = 1.5.dp
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
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(if (item.isFalveyoOrEsp) Color(0xFF5856D6) else Color(0xFF2C303E)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (item.isFalveyoOrEsp) Icons.Default.Star else Icons.Default.Place,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(Modifier.width(10.dp))

                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = item.name,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (item.isFalveyoOrEsp) {
                            Spacer(Modifier.width(6.dp))
                            Surface(
                                color = Color(0x335856D6),
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

                    Spacer(Modifier.height(3.dp))

                    Text(
                        text = "${item.address}  •  ${item.rssi} dBm",
                        fontSize = 11.sp,
                        color = Color(0xFF8E8E98),
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
                    Text("Bağlı", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            } else {
                OutlinedButton(
                    onClick = onConnect,
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = Color(0xFF2C2F3C),
                        contentColor = Color.White
                    ),
                    border = androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFF565D74)),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text("Bağlan", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

// ----------------------------------------------------------------
// 6. KART: METİN SEÇİMİ & TEST ALANI KARTI
// ----------------------------------------------------------------

@Composable
fun TextSelectionTestCard() {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF22242E)),
        shape = RoundedCornerShape(12.dp),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = androidx.compose.ui.graphics.SolidColor(Color(0xFF4C5266)),
            width = 1.5.dp
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.SelectAll,
                    contentDescription = null,
                    tint = Color(0xFFD4D4DC),
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "Metin Seçimi & Test Alanı",
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    fontSize = 14.sp
                )
            }

            Spacer(Modifier.height(8.dp))
            Text(
                "İmleci aşağıdaki metnin üzerine getirip joystick butonuna basılı tutun. Metin seçme çubuğunun açıldığını ve metni kaydırarak seçebildiğinizi buradan test edebilirsiniz.",
                fontSize = 12.sp,
                color = Color(0xFFA0A0AC),
                lineHeight = 16.sp
            )
            Spacer(Modifier.height(12.dp))

            // Seçilebilir Metin Kutusu (İç alan)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF16171E))
                    .border(1.dp, Color(0xFF333748), RoundedCornerShape(8.dp))
                    .padding(12.dp)
            ) {
                SelectionContainer {
                    Text(
                        text = "FALVEYO BLE Joystick ile Android ekranında tam kontrol sağlayabilir, metinleri basılı tutarak kolayca seçebilirsiniz.",
                        fontSize = 12.sp,
                        color = Color(0xFFD4D4DC),
                        lineHeight = 16.sp
                    )
                }
            }
        }
    }
}

// ----------------------------------------------------------------
// 7. KART: YAŞAM DÖNGÜSÜ & ENERJİ KORUMASI KARTI
// ----------------------------------------------------------------

@Composable
fun ServiceLifecycleCard(onStopService: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF22242E)),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = androidx.compose.ui.graphics.SolidColor(Color(0xFF4C5266)),
            width = 1.5.dp
        ),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "Yaşam Döngüsü & Enerji Koruması",
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFFD4D4DC),
                fontSize = 13.sp
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "• BLE Taraması yalnızca siz istediğinizde çalışır ve 15 saniye sonra otomatik durur.\n" +
                        "• Uygulamayı veya servisi kapattığınızda BLE bağlantısı, tarama ve bildirim tamamen temizlenir.",
                fontSize = 11.sp,
                color = Color(0xFF8E8E98),
                lineHeight = 16.sp
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = onStopService,
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = Color(0xFF2A1518),
                    contentColor = Color(0xFFFF453A)
                ),
                border = androidx.compose.foundation.BorderStroke(1.5.dp, Color(0x66FF453A)),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Servisi ve Uygulamayı Tamamen Kapat", fontSize = 12.sp)
            }
        }
    }
}

// ----------------------------------------------------------------
// AYARLAR EKRANI (Hafif Gri İç, Belirgin Kenarlıklar)
// ----------------------------------------------------------------

@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val cursorSpeed by AppSettings.cursorSpeed.collectAsState()
    val fineSensitivity by AppSettings.fineSensitivity.collectAsState()
    val deadzone by AppSettings.deadzone.collectAsState()
    val smoothingEnabled by AppSettings.smoothingEnabled.collectAsState()
    val smoothingFactor by AppSettings.smoothingFactor.collectAsState()
    val longPressMs by AppSettings.longPressMs.collectAsState()
    val cursorRadius by AppSettings.cursorRadius.collectAsState()
    val cursorColor by AppSettings.cursorColor.collectAsState()
    val edgeScrollEnabled by AppSettings.edgeScrollEnabled.collectAsState()
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

    val cardBg = Color(0xFF22242E)
    val cardBorder = Color(0xFF4C5266)
    val sliderInactive = Color(0xFF2E3242)

    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. İMLEÇ HIZI & HASSASİYETİ
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = cardBg),
                shape = RoundedCornerShape(12.dp),
                border = CardDefaults.outlinedCardBorder().copy(
                    brush = androidx.compose.ui.graphics.SolidColor(cardBorder),
                    width = 1.5.dp
                )
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color(0xFF00E5FF))
                        Spacer(Modifier.width(8.dp))
                        Text("İmleç Hızı (Speed)", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 15.sp)
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

                    Spacer(Modifier.height(8.dp))
                    Text(
                        "İmlecin ekranda ilerleme hız çarpanı. Düşük değerler daha yavaş ve kontrollü hareket sağlar.",
                        fontSize = 12.sp,
                        color = Color(0xFFA0A0AC)
                    )
                    Spacer(Modifier.height(6.dp))

                    Slider(
                        value = cursorSpeed,
                        onValueChange = { AppSettings.setCursorSpeed(it) },
                        valueRange = 1f..20f,
                        steps = 19,
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFF00E5FF),
                            activeTrackColor = Color(0xFF00E5FF),
                            inactiveTrackColor = sliderInactive
                        )
                    )

                    // Hızlı Hazır Ayar Butonları
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("Çok Yavaş (3)" to 3f, "Yavaş (5)" to 5f, "Normal (7)" to 7f, "Hızlı (10)" to 10f).forEach { (label, value) ->
                            val isSelected = (cursorSpeed.roundToInt() == value.roundToInt())
                            OutlinedButton(
                                onClick = { AppSettings.setCursorSpeed(value) },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 2.dp, vertical = 4.dp),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = if (isSelected) Color(0x3300E5FF) else Color(0xFF2C2F3C),
                                    contentColor = if (isSelected) Color(0xFF00E5FF) else Color(0xFFA0A0AC)
                                ),
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    if (isSelected) Color(0xFF00E5FF) else Color(0xFF565D74)
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
                colors = CardDefaults.cardColors(containerColor = cardBg),
                shape = RoundedCornerShape(12.dp),
                border = CardDefaults.outlinedCardBorder().copy(
                    brush = androidx.compose.ui.graphics.SolidColor(cardBorder),
                    width = 1.5.dp
                )
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Star, contentDescription = null, tint = Color(0xFF7C4DFF))
                        Spacer(Modifier.width(8.dp))
                        Text("Mikro Hareket Hassasiyeti", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 15.sp)
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
                        "Joystick hafifçe eğildiğinde mikro pikselleri algılamasını sağlar. 1.0 (Doğrusal), 1.4 (Dengeli), 2.0 (Hızlı).",
                        fontSize = 12.sp,
                        color = Color(0xFFA0A0AC)
                    )
                    Spacer(Modifier.height(6.dp))

                    Slider(
                        value = fineSensitivity,
                        onValueChange = { AppSettings.setFineSensitivity(it) },
                        valueRange = 1.0f..2.2f,
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFF7C4DFF),
                            activeTrackColor = Color(0xFF7C4DFF),
                            inactiveTrackColor = sliderInactive
                        )
                    )

                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Ölü Bölge (Deadzone):", fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.width(8.dp))
                        Text("%${(deadzone * 100).roundToInt()}", fontSize = 13.sp, color = Color(0xFF7C4DFF), fontWeight = FontWeight.Bold)
                    }
                    Slider(
                        value = deadzone,
                        onValueChange = { AppSettings.setDeadzone(it) },
                        valueRange = 0.00f..0.15f,
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFF9E77FF),
                            activeTrackColor = Color(0xFF9E77FF),
                            inactiveTrackColor = sliderInactive
                        )
                    )
                }
            }
        }

        // 3. YUMUŞATMA (SMOOTHING) FİLTRESİ
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = cardBg),
                shape = RoundedCornerShape(12.dp),
                border = CardDefaults.outlinedCardBorder().copy(
                    brush = androidx.compose.ui.graphics.SolidColor(cardBorder),
                    width = 1.5.dp
                )
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Build, contentDescription = null, tint = Color(0xFF00E676))
                            Spacer(Modifier.width(8.dp))
                            Text("İmleç Yumuşatma (Smoothing)", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 15.sp)
                        }
                        Switch(
                            checked = smoothingEnabled,
                            onCheckedChange = { AppSettings.setSmoothingEnabled(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = Color(0xFF00E676),
                                uncheckedThumbColor = Color(0xFF888888),
                                uncheckedTrackColor = Color(0xFF2C303E)
                            )
                        )
                    }

                    if (smoothingEnabled) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Düşük geçiren filtre ile BLE paketleri arasındaki hareketi yumuşatarak kaygan hareket oluşturur.",
                            fontSize = 12.sp,
                            color = Color(0xFFA0A0AC)
                        )
                        Spacer(Modifier.height(6.dp))
                        Slider(
                            value = smoothingFactor,
                            onValueChange = { AppSettings.setSmoothingFactor(it) },
                            valueRange = 0.15f..0.85f,
                            colors = SliderDefaults.colors(
                                thumbColor = Color(0xFF00E676),
                                activeTrackColor = Color(0xFF00E676),
                                inactiveTrackColor = sliderInactive
                            )
                        )
                    }
                }
            }
        }

        // 4. METİN SEÇİMİ & UZUN BASIŞ SÜRESİ
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = cardBg),
                shape = RoundedCornerShape(12.dp),
                border = CardDefaults.outlinedCardBorder().copy(
                    brush = androidx.compose.ui.graphics.SolidColor(cardBorder),
                    width = 1.5.dp
                )
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.ThumbUp, contentDescription = null, tint = Color(0xFFFF9100))
                        Spacer(Modifier.width(8.dp))
                        Text("Metin Seçimi & Uzun Basış", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 15.sp)
                        Spacer(Modifier.weight(1f))
                        Surface(
                            color = Color(0x33FF9100),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                "${longPressMs} ms",
                                color = Color(0xFFFFB74D),
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Joystick butonuna bu süre kadar basılı tutulduğunda dokunma basılı tutulur ve metin seçme kulpları açılır.",
                        fontSize = 12.sp,
                        color = Color(0xFFA0A0AC)
                    )
                    Spacer(Modifier.height(6.dp))

                    Slider(
                        value = longPressMs.toFloat(),
                        onValueChange = { AppSettings.setLongPressMs(it.toLong()) },
                        valueRange = 250f..900f,
                        steps = 12,
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFFFF9100),
                            activeTrackColor = Color(0xFFFF9100),
                            inactiveTrackColor = sliderInactive
                        )
                    )
                }
            }
        }

        // 5. İMLEÇ GÖRÜNÜMÜ & BOYUT & RENK
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = cardBg),
                shape = RoundedCornerShape(12.dp),
                border = CardDefaults.outlinedCardBorder().copy(
                    brush = androidx.compose.ui.graphics.SolidColor(cardBorder),
                    width = 1.5.dp
                )
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Create, contentDescription = null, tint = Color(0xFF00E5FF))
                        Spacer(Modifier.width(8.dp))
                        Text("İmleç Boyutu ve Rengi", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 15.sp)
                    }

                    Spacer(Modifier.height(10.dp))
                    Text("İmleç Yarıçapı: ${cursorRadius.roundToInt()} px", fontSize = 12.sp, color = Color(0xFFA0A0AC))
                    Slider(
                        value = cursorRadius,
                        onValueChange = { AppSettings.setCursorRadius(it) },
                        valueRange = 10f..36f,
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFF00E5FF),
                            activeTrackColor = Color(0xFF00E5FF),
                            inactiveTrackColor = sliderInactive
                        )
                    )

                    Spacer(Modifier.height(8.dp))
                    Text("İmleç Rengi:", fontSize = 12.sp, color = Color(0xFFA0A0AC))
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        colorsList.forEach { colInt ->
                            val isSelected = (cursorColor == colInt)
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(Color(colInt))
                                    .clickable { AppSettings.setCursorColor(colInt) }
                                    .border(
                                        width = if (isSelected) 3.dp else 1.dp,
                                        color = if (isSelected) Color.White else Color(0x44888888),
                                        shape = CircleShape
                                    ),
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

        // 6. KENAR OTOMATİK SAYFA KAYDIRMA
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = cardBg),
                shape = RoundedCornerShape(12.dp),
                border = CardDefaults.outlinedCardBorder().copy(
                    brush = androidx.compose.ui.graphics.SolidColor(cardBorder),
                    width = 1.5.dp
                )
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = Color(0xFF00E5FF))
                            Spacer(Modifier.width(8.dp))
                            Text("Kenar Otomatik Kaydırma", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 15.sp)
                        }
                        Switch(
                            checked = edgeScrollEnabled,
                            onCheckedChange = { AppSettings.setEdgeScrollEnabled(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = Color(0xFF00E5FF),
                                uncheckedThumbColor = Color(0xFF888888),
                                uncheckedTrackColor = Color(0xFF2C303E)
                            )
                        )
                    }

                    Spacer(Modifier.height(8.dp))
                    Text(
                        "İmleç ekranın kenarına dayandığında sayfayı otomatik ve yumuşak şekilde aşağı/yukarı kaydırır.",
                        fontSize = 12.sp,
                        color = Color(0xFFA0A0AC)
                    )

                    if (edgeScrollEnabled) {
                        Spacer(Modifier.height(12.dp))
                        Divider(color = cardBorder)
                        Spacer(Modifier.height(12.dp))

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Kaydırma Hız Çarpanı", fontWeight = FontWeight.Medium, color = Color.White, fontSize = 13.sp)
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
                                inactiveTrackColor = sliderInactive
                            )
                        )

                        Spacer(Modifier.height(8.dp))

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Kenar Algılama Alanı", fontWeight = FontWeight.Medium, color = Color.White, fontSize = 13.sp)
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
                                inactiveTrackColor = sliderInactive
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
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = Color(0xFF2A1518),
                    contentColor = Color(0xFFFF453A)
                ),
                border = androidx.compose.foundation.BorderStroke(1.5.dp, Color(0x66FF453A)),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("Tüm Ayarları Varsayılana Döndür")
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
