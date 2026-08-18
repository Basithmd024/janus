package com.janus.app

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.provider.Settings
import android.content.pm.PackageManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.animation.core.*
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.gson.JsonObject
import com.janus.app.core.JanusNotificationListenerService
import com.janus.app.core.JanusService
import com.janus.app.core.Packet
// Offline QR Scanner via CameraX and ZXing
import com.google.firebase.auth.FirebaseAuth
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.GoogleAuthProvider
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts


class MainActivity : ComponentActivity() {

    private val qrScannerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val qrData = result.data?.getStringExtra(QrScannerActivity.EXTRA_QR_RESULT)
            if (!qrData.isNullOrBlank()) {
                parseAndConnectQrCode(qrData)
            }
        }
    }

    private var janusService: JanusService? = null
    private var isBound = false

    private val discoveredList = mutableStateListOf<NsdServiceInfo>()
    private var isConnectedState = mutableStateOf(false)
    private var localFingerprint = mutableStateOf("")

    private var activeTransferName = mutableStateOf<String?>(null)
    private var activeTransferProgress = mutableStateOf(0f)
    private var isOutgoingTransfer = mutableStateOf(false)
    private var notificationAccessEnabled = mutableStateOf(false)
    private var isScreenMirroringActive = mutableStateOf(false)
    private var accessibilityEnabled = mutableStateOf(false)

    private val projectionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val serviceIntent = Intent(this, com.janus.app.core.JanusScreenCastService::class.java).apply {
                putExtra("resultCode", result.resultCode)
                putExtra("data", result.data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            isScreenMirroringActive.value = true
            Toast.makeText(this, "Screen Mirroring Started", Toast.LENGTH_SHORT).show()
        }
    }

    private val pickFileLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri: android.net.Uri? ->
        if (uri != null) {
            uploadSelectedFile(uri)
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as JanusService.LocalBinder
            val s = binder.getService()
            janusService = s
            isBound = true

            // Set initial state
            localFingerprint.value = s.identity.fingerprint
            isConnectedState.value = s.isConnected

            // Populate initially discovered nodes from service cache
            s.discoveredServices.values.forEach { info ->
                if (!discoveredList.any { it.serviceName == info.serviceName }) {
                    discoveredList.add(info)
                }
            }

            // Register service callbacks to update UI
            s.onDeviceDiscovered = { info ->
                if (!discoveredList.any { it.serviceName == info.serviceName }) {
                    discoveredList.add(info)
                }
            }
            s.onDeviceRemoved = { info ->
                discoveredList.removeAll { it.serviceName == info.serviceName }
            }
            s.onConnectionStateChanged = { connected ->
                isConnectedState.value = connected
            }
            s.onPacketReceived = { packet: Packet ->
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Received: ${packet.type}", Toast.LENGTH_SHORT).show()
                }
            }
            s.onUploadProgress = { sessionId, fileHash, bytesReceived, totalBytes, name ->
                runOnUiThread {
                    activeTransferName.value = name
                    activeTransferProgress.value = bytesReceived.toFloat() / totalBytes.toFloat()
                    isOutgoingTransfer.value = false
                }
            }
            s.onUploadComplete = { sessionId, fileHash, fileName, uri ->
                runOnUiThread {
                    activeTransferName.value = null
                    activeTransferProgress.value = 0f
                    Toast.makeText(this@MainActivity, "Received file: $fileName", Toast.LENGTH_LONG).show()
                }
            }
            s.onScreenMirrorRequest = {
                runOnUiThread {
                    val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
                    projectionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
                }
            }
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            isBound = false
            janusService = null
        }
    }

    /** Check if Notification Access has been granted for our NotificationListenerService */
    private fun isNotificationServiceEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        if (flat.isNullOrEmpty()) return false
        val componentName = ComponentName(this, JanusNotificationListenerService::class.java).flattenToString()
        return flat.split(":").any { it == componentName }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Request call, contacts, notifications, and phone permissions
        val requiredPermissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requiredPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            requiredPermissions.add(Manifest.permission.READ_PHONE_STATE)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            requiredPermissions.add(Manifest.permission.CALL_PHONE)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ANSWER_PHONE_CALLS) != PackageManager.PERMISSION_GRANTED) {
            requiredPermissions.add(Manifest.permission.ANSWER_PHONE_CALLS)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            requiredPermissions.add(Manifest.permission.READ_CONTACTS)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requiredPermissions.add(Manifest.permission.RECORD_AUDIO)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {
            requiredPermissions.add(Manifest.permission.READ_CALL_LOG)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            requiredPermissions.add(Manifest.permission.READ_SMS)
        }
        
        if (requiredPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, requiredPermissions.toTypedArray(), 102)
        }

        // Check notification listener permission
        notificationAccessEnabled.value = isNotificationServiceEnabled()

        // Start Foreground Service
        val intent = Intent(this, JanusService::class.java)
        startService(intent)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFFC084FC),
                    background = Color(0xFF0F0A1C),
                    surface = Color(0xFF1E1535)
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    var showIntroSplash by remember { mutableStateOf(true) }
                    var isAuthenticated by remember { mutableStateOf(FirebaseAuth.getInstance().currentUser != null) }
                    var forceLocalMode by remember { mutableStateOf(true) }

                    if (showIntroSplash) {
                        IntroMotionScreen(onFinish = { showIntroSplash = false })
                        return@Surface
                    }
                    
                    DisposableEffect(Unit) {
                        val listener = FirebaseAuth.AuthStateListener { auth ->
                            isAuthenticated = auth.currentUser != null
                        }
                        FirebaseAuth.getInstance().addAuthStateListener(listener)
                        onDispose {
                            FirebaseAuth.getInstance().removeAuthStateListener(listener)
                        }
                    }
                    
                    if (isAuthenticated || forceLocalMode) {
                        MainScreen(onOpenLogin = { forceLocalMode = false })
                    } else {
                        LoginScreen(onSkipToLocal = { forceLocalMode = true })
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        notificationAccessEnabled.value = isNotificationServiceEnabled()
        isScreenMirroringActive.value = com.janus.app.core.JanusScreenCastService.isRunning
        accessibilityEnabled.value = isAccessibilityServiceEnabled()
    }

    @Composable
    fun IntroMotionScreen(onFinish: () -> Unit) {
        LaunchedEffect(Unit) {
            kotlinx.coroutines.delay(2300L)
            onFinish()
        }

        val infiniteTransition = rememberInfiniteTransition(label = "intro")
        val pulseScale by infiniteTransition.animateFloat(
            initialValue = 0.85f,
            targetValue = 1.35f,
            animationSpec = infiniteRepeatable(
                animation = tween(1800, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "pulseScale"
        )
        val pulseAlpha by infiniteTransition.animateFloat(
            initialValue = 0.65f,
            targetValue = 0f,
            animationSpec = infiniteRepeatable(
                animation = tween(1800, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "pulseAlpha"
        )

        var isVisible by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            isVisible = true
        }

        val floatAnim by animateFloatAsState(
            targetValue = if (isVisible) 1f else 0f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessLow
            ),
            label = "springEntrance"
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF090514))
                .clickable { onFinish() },
            contentAlignment = Alignment.Center
        ) {
            // Radial Glow Background Pulse
            Box(
                modifier = Modifier
                    .size(240.dp)
                    .graphicsLayer {
                        scaleX = pulseScale
                        scaleY = pulseScale
                        alpha = pulseAlpha
                    }
                    .background(Color(0xFFC084FC), androidx.compose.foundation.shape.CircleShape)
            )

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier
                    .padding(24.dp)
                    .graphicsLayer {
                        scaleX = floatAnim
                        scaleY = floatAnim
                        alpha = floatAnim
                    }
            ) {
                // Glassmorphic Logo Card
                Card(
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1535)),
                    border = androidx.compose.foundation.BorderStroke(2.dp, Color(0xFFC084FC)),
                    modifier = Modifier.size(96.dp)
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Janus Bridge",
                            tint = Color(0xFFC084FC),
                            modifier = Modifier.size(48.dp)
                        )
                    }
                }

                // Title with Spacing
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "J A N U S",
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Black,
                        color = Color.White,
                        letterSpacing = 6.sp
                    )
                    Text(
                        text = "Quantum Continuity Bridge",
                        fontSize = 13.sp,
                        color = Color(0xFFC084FC),
                        fontWeight = FontWeight.SemiBold
                    )
                }

                // HUD Badges
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BadgePill(text = "⚡ TLS 1.3", color = Color(0xFF38BDF8))
                    BadgePill(text = "🔒 SHA-256", color = Color(0xFFC084FC))
                    BadgePill(text = "0ms P2P", color = Color(0xFF34D399))
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Tap anywhere to launch",
                    fontSize = 11.sp,
                    color = Color.Gray
                )
            }
        }
    }

    @Composable
    fun BadgePill(text: String, color: Color) {
        Surface(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(100.dp),
            color = color.copy(alpha = 0.12f),
            border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.35f))
        ) {
            Text(
                text = text,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = color,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
            )
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun MainScreen(onOpenLogin: () -> Unit = {}) {
        var pairingTarget by remember { mutableStateOf<NsdServiceInfo?>(null) }
        var showPairingDialog by remember { mutableStateOf(false) }
        var pairingPin by remember { mutableStateOf("") }
        var isPairingProgress by remember { mutableStateOf(false) }
        var showManualPairDialog by remember { mutableStateOf(false) }
        var manualIp by remember { mutableStateOf("") }
        var manualPort by remember { mutableStateOf("53317") }
        var manualFingerprint by remember { mutableStateOf("") }
        var manualPin by remember { mutableStateOf("") }

        var activeTabState by remember { mutableIntStateOf(0) }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("Janus Bridge", fontWeight = FontWeight.Bold)
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(
                                        color = if (isConnectedState.value) Color(0xFF10B981) else Color(0xFFEF4444),
                                        shape = androidx.compose.foundation.shape.CircleShape
                                    )
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                TabRow(
                    selectedTabIndex = activeTabState,
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Tab(
                        selected = activeTabState == 0,
                        onClick = { activeTabState = 0 },
                        text = { Text("Dashboard") },
                        icon = { Icon(Icons.Default.Home, contentDescription = "Dashboard") }
                    )
                    Tab(
                        selected = activeTabState == 1,
                        onClick = { activeTabState = 1 },
                        text = { Text("Devices") },
                        icon = { Icon(Icons.Default.Settings, contentDescription = "Devices") }
                    )
                }

                if (activeTabState == 0) {
                    // Dashboard View
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Local Node Status
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text("Local Node Status", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("Model: ${Build.MODEL}", color = Color.LightGray, fontSize = 14.sp)
                                Text("FP: ${localFingerprint.value.take(16)}...", fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = if (isConnectedState.value) "Connected to Mac" else "Not Connected",
                                    color = if (isConnectedState.value) Color(0xFF10B981) else Color.Gray,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 14.sp
                                )
                            }
                        }

                        // File Transfer progress card
                        if (activeTransferName.value != null) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text(
                                        text = if (isOutgoingTransfer.value) "Sending file..." else "Receiving file...",
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(activeTransferName.value ?: "", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                    Spacer(modifier = Modifier.height(8.dp))
                                    LinearProgressIndicator(
                                        progress = { activeTransferProgress.value },
                                        modifier = Modifier.fillMaxWidth(),
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text("${(activeTransferProgress.value * 100).toInt()}% completed", fontSize = 12.sp, color = Color.Gray)
                                }
                            }
                        }

                        // Services Card with toggles
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                Text("Services", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = MaterialTheme.colorScheme.primary)
                                
                                // Notification Mirroring
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Notifications,
                                            contentDescription = "Notification Mirroring",
                                            tint = if (notificationAccessEnabled.value) MaterialTheme.colorScheme.primary else Color.Gray
                                        )
                                        Column {
                                            Text("Notification Mirroring", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                            Text(
                                                text = if (notificationAccessEnabled.value) "Active" else "Requires permission",
                                                fontSize = 12.sp,
                                                color = if (notificationAccessEnabled.value) Color(0xFF10B981) else Color.Gray
                                            )
                                        }
                                    }
                                    Switch(
                                        checked = notificationAccessEnabled.value,
                                        onCheckedChange = { checked ->
                                            if (checked) {
                                                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                                            } else {
                                                Toast.makeText(this@MainActivity, "Disable in Settings to stop mirroring", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    )
                                }

                                HorizontalDivider(color = Color.DarkGray.copy(alpha = 0.5f))

                                // Screen Mirroring
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.PlayArrow,
                                            contentDescription = "Screen Mirroring",
                                            tint = if (isScreenMirroringActive.value) MaterialTheme.colorScheme.primary else Color.Gray
                                        )
                                        Column {
                                            Text("Screen Mirroring", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                            Text(
                                                text = if (isScreenMirroringActive.value) "Active" else "Inactive",
                                                fontSize = 12.sp,
                                                color = if (isScreenMirroringActive.value) Color(0xFF10B981) else Color.Gray
                                            )
                                        }
                                    }
                                    Switch(
                                        checked = isScreenMirroringActive.value,
                                        onCheckedChange = { checked ->
                                            if (checked) {
                                                val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
                                                projectionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
                                            } else {
                                                val stopIntent = Intent(this@MainActivity, com.janus.app.core.JanusScreenCastService::class.java)
                                                stopService(stopIntent)
                                                isScreenMirroringActive.value = false
                                            }
                                        }
                                    )
                                }

                                HorizontalDivider(color = Color.DarkGray.copy(alpha = 0.5f))

                                // Remote Control
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Build,
                                            contentDescription = "Remote Control",
                                            tint = if (accessibilityEnabled.value) MaterialTheme.colorScheme.primary else Color.Gray
                                        )
                                        Column {
                                            Text("Remote Control", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                            Text(
                                                text = if (accessibilityEnabled.value) "Active" else "Requires permission",
                                                fontSize = 12.sp,
                                                color = if (accessibilityEnabled.value) Color(0xFF10B981) else Color.Gray
                                            )
                                        }
                                    }
                                    Switch(
                                        checked = accessibilityEnabled.value,
                                        onCheckedChange = { checked ->
                                            if (checked) {
                                                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                                startActivity(intent)
                                            } else {
                                                Toast.makeText(this@MainActivity, "Disable in Settings to stop remote control", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    )
                                }
                            }
                        }

                        // Quick Actions
                        if (isConnectedState.value) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Text("Quick Actions", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = MaterialTheme.colorScheme.primary)
                                    
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Button(
                                            onClick = { sendClipboardDemo() },
                                            modifier = Modifier.weight(1f),
                                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                                        ) {
                                            Icon(Icons.Default.Share, contentDescription = "Send Clipboard", modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Clipboard", fontSize = 12.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                        }
                                        
                                        Button(
                                            onClick = { pickFileLauncher.launch("*/*") },
                                            modifier = Modifier.weight(1f),
                                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                                        ) {
                                            Icon(Icons.Default.Send, contentDescription = "Send File", modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Send File", fontSize = 12.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                        }
                                    }
                                    
                                    Button(
                                        onClick = { janusService?.connectionManager?.disconnect() },
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444))
                                    ) {
                                        Text("Disconnect", color = Color.White)
                                    }
                                }
                            }
                        }
                    }
                } else {
                    // Devices View
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        if (isConnectedState.value) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                                border = androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFF10B981))
                            ) {
                                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Box(modifier = Modifier.size(10.dp).background(Color(0xFF10B981), androidx.compose.foundation.shape.CircleShape))
                                            Text("Active Connection", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 16.sp)
                                        }
                                        Button(
                                            onClick = { janusService?.connectionManager?.disconnect() },
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
                                            shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                                        ) {
                                            Text("Disconnect", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                    Text(
                                        "Host: ${janusService?.connectionManager?.connectedIp ?: "Mac Host"}:${janusService?.connectionManager?.connectedPort ?: 53317}",
                                        color = Color.LightGray,
                                        fontSize = 13.sp
                                    )
                                    Text(
                                        "🟢 TLS WebSocket Mesh Active • Real-time Telemetry & Clipboard Synced",
                                        color = Color(0xFF34D399),
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }

                        Text("Pairing & Other Mac Nodes", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = MaterialTheme.colorScheme.primary)
                        
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { startQrCodeScanner() },
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Search,
                                    contentDescription = "Scan QR Code",
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Column {
                                    Text("Scan QR Code to Pair", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                    Text("Instantly pair with your Mac by scanning the screen", fontSize = 12.sp, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f))
                                }
                            }
                        }

                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showManualPairDialog = true },
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = "Pair Manually",
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                                Column {
                                    Text("Pair Manually by IP", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSecondaryContainer)
                                    Text("Enter your Mac's IP address and PIN to connect", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f))
                                }
                            }
                        }
                        if (discoveredList.isEmpty()) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(24.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                                        Text(
                                            if (isConnectedState.value) "Connected to host • Scanning for other Macs..." else "Scanning local Wi-Fi for Macs...",
                                            color = Color.Gray,
                                            fontSize = 14.sp
                                        )
                                    }
                                }
                            }
                        } else {
                            discoveredList.forEach { info ->
                                val txtIp = info.attributes?.get("ip")?.let { String(it) }
                                val hostIp = txtIp ?: info.host?.hostAddress ?: "Unknown IP"
                                val port = info.port
                                val fingerprint = info.attributes?.get("fn")?.let { String(it) } ?: "unknown"
                                
                                val isPaired = janusService?.connectionManager?.isFingerprintPaired(fingerprint) == true
                                val isCurrentConnection = isConnectedState.value && janusService?.connectionManager?.connectedFingerprint == fingerprint

                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            if (!isCurrentConnection) {
                                                janusService?.connectionManager?.connectToDevice(hostIp, port, fingerprint)
                                            }
                                        },
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (isCurrentConnection) Color(0xFF1E293B) else MaterialTheme.colorScheme.surface
                                    ),
                                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(16.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Text(info.serviceName.substringBefore("."), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                                if (isCurrentConnection) {
                                                    Box(
                                                        modifier = Modifier
                                                            .size(8.dp)
                                                            .background(Color(0xFF10B981), androidx.compose.foundation.shape.CircleShape)
                                                    )
                                                }
                                            }
                                            Text("$hostIp:$port", fontSize = 12.sp, color = Color.Gray)
                                            Text("FP: ${fingerprint.take(12)}...", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Color.LightGray)
                                        }
                                        
                                        if (isCurrentConnection) {
                                            Text("🟢 Connected", color = Color(0xFF10B981), fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 8.dp), fontSize = 14.sp)
                                        } else {
                                            Button(
                                                onClick = { janusService?.connectionManager?.connectToDevice(hostIp, port, fingerprint) },
                                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                                            ) {
                                                Text("⚡ Connect", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Pairing PIN dialog
        if (showPairingDialog && pairingTarget != null) {
            AlertDialog(
                onDismissRequest = { showPairingDialog = false },
                title = { Text("Pair with ${pairingTarget!!.serviceName.substringBefore(".")}") },
                text = {
                    Column {
                        Text("Enter the 6-digit PIN shown on the Mac screen:")
                        Spacer(modifier = Modifier.height(8.dp))
                        TextField(
                            value = pairingPin,
                            onValueChange = { if (it.length <= 6) pairingPin = it },
                            placeholder = { Text("123456") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val txtIp = pairingTarget!!.attributes?.get("ip")?.let { String(it) }
                            val ip = txtIp ?: pairingTarget!!.host?.hostAddress ?: return@Button
                            val port = pairingTarget!!.port
                            val fingerprint = pairingTarget!!.attributes?.get("fn")?.let { String(it) }
                            
                            isPairingProgress = true
                            janusService?.connectionManager?.connectToDevice(
                                ip = ip,
                                port = port,
                                expectedFingerprint = fingerprint,
                                pairingPin = pairingPin,
                                onPairingResult = { success, message ->
                                    runOnUiThread {
                                        isPairingProgress = false
                                        if (success) {
                                            Toast.makeText(this@MainActivity, "Paired successfully!", Toast.LENGTH_SHORT).show()
                                            showPairingDialog = false
                                            // No need to reconnect — device.register is now
                                            // sent on the same socket by ConnectionManager
                                        } else {
                                            Toast.makeText(this@MainActivity, "Pairing Failed: $message", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                }
                            )
                        },
                        enabled = pairingPin.length == 6 && !isPairingProgress
                    ) {
                        if (isPairingProgress) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color.White)
                        } else {
                            Text("Connect")
                        }
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showPairingDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }

        // Manual pairing by IP/Port/PIN dialog
        if (showManualPairDialog) {
            AlertDialog(
                onDismissRequest = { showManualPairDialog = false },
                title = { Text("Pair Manually by IP") },
                text = {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Enter the connection details for your Mac:")
                        
                        TextField(
                            value = manualIp,
                            onValueChange = { manualIp = it },
                            label = { Text("Mac IP Address (e.g. 192.168.1.34)") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        
                        TextField(
                            value = manualPort,
                            onValueChange = { manualPort = it },
                            label = { Text("Port (default 53317)") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        
                        TextField(
                            value = manualPin,
                            onValueChange = { if (it.length <= 6) manualPin = it },
                            label = { Text("6-Digit PIN") },
                            placeholder = { Text("123456") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val ipVal = manualIp.trim()
                            val portVal = manualPort.toIntOrNull() ?: 53317
                            val pinVal = manualPin.trim()
                            
                            if (ipVal.isEmpty()) {
                                Toast.makeText(this@MainActivity, "Please enter an IP address", Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            if (pinVal.length != 6) {
                                Toast.makeText(this@MainActivity, "Please enter a 6-digit PIN", Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            
                            isPairingProgress = true
                            janusService?.connectionManager?.connectToDevice(
                                ip = ipVal,
                                port = portVal,
                                expectedFingerprint = null, // don't pin fingerprint on manual first-connection
                                pairingPin = pinVal,
                                onPairingResult = { success, message ->
                                    runOnUiThread {
                                        isPairingProgress = false
                                        if (success) {
                                            Toast.makeText(this@MainActivity, "Paired successfully!", Toast.LENGTH_SHORT).show()
                                            showManualPairDialog = false
                                        } else {
                                            Toast.makeText(this@MainActivity, "Pairing Failed: $message", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                }
                            )
                        },
                        enabled = manualIp.isNotEmpty() && manualPin.length == 6 && !isPairingProgress
                    ) {
                        if (isPairingProgress) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color.White)
                        } else {
                            Text("Pair")
                        }
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showManualPairDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }

    private fun sendClipboardDemo() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val text = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
        if (text.isEmpty()) {
            Toast.makeText(this, "Android clipboard is empty!", Toast.LENGTH_SHORT).show()
            return
        }

        val payload = JsonObject().apply {
            addProperty("content", text)
            addProperty("contentType", "text/plain")
        }

        val packet = Packet(
            type = "clipboard.update",
            id = java.util.UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis() / 1000,
            payload = payload
        )

        janusService?.connectionManager?.sendPacket(packet)
        Toast.makeText(this, "Sent: $text", Toast.LENGTH_SHORT).show()
    }

    private fun uploadSelectedFile(uri: android.net.Uri) {
        val service = janusService ?: return
        val connManager = service.connectionManager ?: return

        try {
            val contentResolver = contentResolver
            // Get file name and size
            var name = "unknown_file"
            var size = 0L
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                if (cursor.moveToFirst()) {
                    if (nameIndex != -1) name = cursor.getString(nameIndex)
                    if (sizeIndex != -1) size = cursor.getLong(sizeIndex)
                }
            }

            val inputStream = contentResolver.openInputStream(uri) ?: return

            activeTransferName.value = name
            activeTransferProgress.value = 0f
            isOutgoingTransfer.value = true

            connManager.uploadFileToConnectedDevice(
                fileName = name,
                fileSize = size,
                fileInputStream = inputStream,
                onProgress = { uploaded ->
                    runOnUiThread {
                        activeTransferProgress.value = uploaded.toFloat() / size.toFloat()
                    }
                },
                onResult = { success, errorMsg ->
                    runOnUiThread {
                        activeTransferName.value = null
                        activeTransferProgress.value = 0f
                        if (success) {
                            Toast.makeText(this, "File sent successfully!", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this, "Failed to send: $errorMsg", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            )
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to prepare file upload", e)
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /** Check if Janus Remote Control Accessibility Service is active */
    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedComponentName = ComponentName(this, com.janus.app.core.JanusAccessibilityService::class.java)
        val enabledServicesSetting = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        
        val colonSplitter = android.text.TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServicesSetting)
        while (colonSplitter.hasNext()) {
            val componentNameString = colonSplitter.next()
            val enabledService = ComponentName.unflattenFromString(componentNameString)
            if (enabledService != null && enabledService == expectedComponentName) {
                return true
            }
        }
        return false
    }

    private fun startQrCodeScanner() {
        val intent = Intent(this, QrScannerActivity::class.java)
        qrScannerLauncher.launch(intent)
    }

    private fun parseAndConnectQrCode(qrData: String) {
        try {
            val json = com.google.gson.JsonParser.parseString(qrData).asJsonObject
            val ip = json.get("ip")?.asString ?: ""
            val port = json.get("port")?.asInt ?: 53317
            val fingerprint = json.get("fn")?.asString ?: ""
            val pin = json.get("pin")?.asString ?: ""
            
            if (ip.isBlank() || pin.isBlank()) {
                Toast.makeText(this, "Invalid QR Code: missing IP or PIN", Toast.LENGTH_LONG).show()
                return
            }
            
            Toast.makeText(this, "QR Code Decoded. Pairing with $ip...", Toast.LENGTH_SHORT).show()
            
            janusService?.connectionManager?.connectToDevice(
                ip = ip,
                port = port,
                expectedFingerprint = fingerprint.ifEmpty { null },
                pairingPin = pin,
                onPairingResult = { success, message ->
                    runOnUiThread {
                        if (success) {
                            Toast.makeText(this@MainActivity, "Paired successfully via QR Code!", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this@MainActivity, "Pairing Failed: $message", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            )
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to parse QR code JSON", e)
            Toast.makeText(this, "Invalid QR Code format: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    @Composable
    fun LoginScreen(onSkipToLocal: () -> Unit = {}) {
        val context = androidx.compose.ui.platform.LocalContext.current
        var errorMsg by remember { mutableStateOf<String?>(null) }
        var isLoading by remember { mutableStateOf(false) }

        val gso = remember {
            GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail()
                .build()
        }
        val googleSignInClient = remember { GoogleSignIn.getClient(context, gso) }

        val handleInstantSignIn: () -> Unit = {
            isLoading = true
            errorMsg = null
            FirebaseAuth.getInstance().signInAnonymously()
                .addOnCompleteListener { authTask ->
                    isLoading = false
                    if (authTask.isSuccessful) {
                        Toast.makeText(context, "⚡ Connected to Cloud Bridge!", Toast.LENGTH_SHORT).show()
                    } else {
                        errorMsg = authTask.exception?.message ?: "Cloud Connection failed"
                    }
                }
        }

        val googleLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartActivityForResult()
        ) { result ->
            try {
                val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
                val account = task.getResult(ApiException::class.java)
                val idToken = account?.idToken
                if (idToken != null) {
                    val credential = GoogleAuthProvider.getCredential(idToken, null)
                    isLoading = true
                    FirebaseAuth.getInstance().signInWithCredential(credential)
                        .addOnCompleteListener { authTask ->
                            isLoading = false
                            if (!authTask.isSuccessful) {
                                handleInstantSignIn()
                            }
                        }
                } else {
                    handleInstantSignIn()
                }
            } catch (e: Exception) {
                // If Google Play Services throws Error 10 (unregistered SHA-1 in Firebase Console),
                // smoothly fallback to Instant 1-Tap Cloud Sync so user is NEVER blocked!
                Log.w("MainActivity", "Google OAuth exception: ${e.message}, auto-falling back to 1-Tap Cloud", e)
                handleInstantSignIn()
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "🌌",
                fontSize = 48.sp,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Text(
                text = "Janus Ecosystem",
                fontWeight = FontWeight.Bold,
                fontSize = 24.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            Text(
                text = "Connect your macOS and Android devices seamlessly",
                fontSize = 12.sp,
                color = Color.Gray,
                modifier = Modifier.padding(bottom = 24.dp)
            )

            if (errorMsg != null) {
                Text(
                    text = "⚠️ $errorMsg",
                    color = Color(0xFFEF4444),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
            }

            // 1-Tap Google Sign-In Button
            Button(
                onClick = {
                    isLoading = true
                    errorMsg = null
                    googleLauncher.launch(googleSignInClient.signInIntent)
                },
                modifier = Modifier.fillMaxWidth().height(54.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                enabled = !isLoading,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)
            ) {
                Text("🔴 🟡 🔵 Continue with Google", color = Color(0xFF1F1F1F), fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 1-Tap Instant Cloud Guest Bridge
            OutlinedButton(
                onClick = handleInstantSignIn,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                enabled = !isLoading,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = MaterialTheme.colorScheme.primary)
                } else {
                    Text("⚡ Instant 1-Tap Cloud Link", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text("— OR USE DIRECT LOCAL WI-FI —", fontSize = 11.sp, color = Color.Gray, fontWeight = FontWeight.Bold)

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedButton(
                onClick = onSkipToLocal,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)
            ) {
                Text("📶 Continue in Local Wi-Fi Mode", color = Color(0xFFC4B5FD))
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
    }
}
