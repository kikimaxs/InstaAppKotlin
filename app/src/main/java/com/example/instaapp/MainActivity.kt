@file:OptIn(ExperimentalMaterial3Api::class)
package com.example.instaapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ExperimentalMaterial3Api
import android.content.Context
import androidx.compose.foundation.lazy.rememberLazyListState
import kotlinx.coroutines.launch
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.example.instaapp.ui.theme.InstaAppTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.viewinterop.AndroidView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.ui.draw.scale
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.BorderStroke
import coil.compose.rememberAsyncImagePainter
import coil.decode.SvgDecoder
import coil.request.ImageRequest
import coil.ImageLoader
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import kotlinx.coroutines.flow.collect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import kotlinx.coroutines.delay
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import androidx.navigation.compose.composable
import androidx.navigation.navDeepLink
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import com.google.firebase.ktx.Firebase
import com.google.firebase.storage.ktx.storage
import com.google.firebase.firestore.ktx.firestore
import com.example.instaapp.data.FirebaseRepo
import com.example.instaapp.data.PostItem
import com.google.firebase.auth.ktx.auth
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FieldValue
import java.util.UUID

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            InstaAppTheme {
                // A surface container using the 'background' color from the theme
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    InstaAppRoot()
                }
            }
        }
    }
}

enum class AppScreen { Login, SignUp, OTP, Home }

@Composable
fun InstaAppRoot() {
    val initial = if (Firebase.auth.currentUser != null) AppScreen.Home else AppScreen.Login
    var screen by remember { mutableStateOf(initial) }
    when (screen) {
        AppScreen.Login -> LoginScreen(onSignUp = { screen = AppScreen.SignUp }, onLoggedIn = { screen = AppScreen.Home })
        AppScreen.SignUp -> SignUpScreen(onNext = { screen = AppScreen.Home })
        AppScreen.OTP -> OTPScreen(onBack = { screen = AppScreen.SignUp }, onVerify = { screen = AppScreen.Home })
        AppScreen.Home -> HomeScreen(onLogout = {
            try { Firebase.auth.signOut() } catch (_: Exception) {}
            screen = AppScreen.Login
        })
    }
}

private fun assetUri(projectPath: String): String {
    return projectPath
        .replace(".libs/assets/", "file:///android_asset/")
        .replace("src/main/assets/", "file:///android_asset/")
}

@Composable
private fun rememberSvgPainter(projectPath: String): Painter {
    val context = LocalContext.current
    val imageLoader = remember {
        ImageLoader.Builder(context)
            .components { add(SvgDecoder.Factory()) }
            .build()
    }
    val uri = assetUri(projectPath)
    return rememberAsyncImagePainter(
        model = ImageRequest.Builder(context)
            .data(uri)
            .build(),
        imageLoader = imageLoader
    )
}

 

private fun listPostImages(ctx: Context): List<String> {
    return try {
        val files = ctx.assets.list("png")?.toList() ?: emptyList()
        files.filter { it.lowercase().startsWith("rectangle") }
            .sorted()
            .map { "src/main/assets/png/$it" }
    } catch (_: Exception) { emptyList() }
}

data class UserComment(
    val id: Long,
    val user: String,
    val time: Long,
    val text: String,
    var likes: Int,
    var liked: Boolean
)

 

// posts are now loaded from Firebase; local persistence removed

private fun loadComments(ctx: Context): List<UserComment> {
    val raw = ctx.getSharedPreferences("comments_store", Context.MODE_PRIVATE).getString("comments", "") ?: ""
    if (raw.isEmpty()) return emptyList()
    return raw.split('\n').filter { it.isNotEmpty() }.mapNotNull { line ->
        val parts = line.split('|')
        if (parts.size < 6) null else UserComment(parts[0].toLong(), parts[1], parts[2].toLong(), parts[3], parts[4].toInt(), parts[5] == "1")
    }
}

private fun persistComments(ctx: Context, list: List<UserComment>) {
    val raw = list.joinToString("\n") { "${it.id}|${it.user}|${it.time}|${it.text}|${it.likes}|${if (it.liked) "1" else "0"}" }
    ctx.getSharedPreferences("comments_store", Context.MODE_PRIVATE).edit().putString("comments", raw).apply()
}

@Composable
private fun assetExists(projectPath: String): Boolean {
    val ctx = LocalContext.current
    val rel = projectPath.removePrefix(".libs/assets/")
    return try {
        ctx.assets.open(rel).close(); true
    } catch (_: Exception) { false }
}

@Composable
private fun rememberSvgPainterSafe(activePath: String, fallbackPath: String): Painter {
    val path = if (assetExists(activePath)) activePath else fallbackPath
    return rememberSvgPainter(path)
}

enum class LoginMode { Account, Credential }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(onSignUp: () -> Unit, onLoggedIn: () -> Unit) {
    var mode by remember { mutableStateOf(LoginMode.Account) }
    val context = LocalContext.current
    var loginError by remember { mutableStateOf<String?>(null) }
    var isLoggingIn by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        when (mode) {
            LoginMode.Account -> AccountLoginView(
                onSwitchAccounts = { mode = LoginMode.Credential },
                onLogin = {
                    mode = LoginMode.Credential
                },
                onSignUp = onSignUp
            )
            LoginMode.Credential -> CredentialLoginView(
                onBack = { mode = LoginMode.Account },
                onSubmit = { email, password ->
                    loginError = null
                    isLoggingIn = true
                    try {
                        val em = email.trim()
                        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(em).matches()) {
                            loginError = "Email tidak valid"; isLoggingIn = false; return@CredentialLoginView
                        }
                        if (password.length < 6) { loginError = "Password minimal 6 karakter"; isLoggingIn = false; return@CredentialLoginView }
                        com.example.instaapp.data.FirebaseRepo.loginUser(em, password) { success, err ->
                            isLoggingIn = false
                            if (success) {
                                onLoggedIn()
                            } else {
                                loginError = err ?: "Login gagal"
                            }
                        }
                    } catch (e: Exception) {
                        isLoggingIn = false
                        loginError = e.message
                    }
                },
                onSignUp = onSignUp
            )
        }
        if (isLoggingIn) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                androidx.compose.material3.CircularProgressIndicator()
            }
        }
        if (loginError != null) {
            ErrorModal(title = "Login gagal", message = loginError!!, onDismiss = { loginError = null })
        }
    }
}

@Composable
private fun ErrorModal(title: String, message: String, onDismiss: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0x80000000))
                .clickable { onDismiss() }
        ) {}
        Box(
            modifier = Modifier
                .fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                color = Color.White,
                shadowElevation = 8.dp,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(title, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(message, color = Color(0xFF262626))
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onDismiss) { Text("OK") }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignUpScreen(onNext: () -> Unit) {
    var email by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var emailError by remember { mutableStateOf<String?>(null) }
    var usernameError by remember { mutableStateOf<String?>(null) }
    var passwordError by remember { mutableStateOf<String?>(null) }
    var confirmError by remember { mutableStateOf<String?>(null) }
    var showPassword by remember { mutableStateOf(false) }
    var showConfirm by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var registerError by remember { mutableStateOf<String?>(null) }
    val isFormValid = email.isNotBlank() && username.isNotBlank() && password.isNotBlank() && confirm.isNotBlank() && password == confirm
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            InstagramWordmark()
            Spacer(modifier = Modifier.height(24.dp))
            FormField(
                value = email,
                onValueChange = {
                    email = it
                    if (email.isNotBlank()) emailError = null
                },
                placeholder = "Email",
                isError = emailError != null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email)
            )
            Spacer(modifier = Modifier.height(12.dp))
            if (emailError != null) {
                val fadeAlpha by animateFloatAsState(1f, tween(200))
                Text(
                    text = emailError!!,
                    color = Color(0xFFFF0000),
                    fontSize = 12.sp,
                    fontStyle = FontStyle.Italic,
                    modifier = Modifier.align(Alignment.Start).graphicsLayer { this.alpha = fadeAlpha }
                )
            }
            FormField(
                value = username,
                onValueChange = {
                    username = it
                    if (username.isNotBlank()) usernameError = null
                },
                placeholder = "Username",
                isError = usernameError != null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
            )
            Spacer(modifier = Modifier.height(12.dp))
            if (usernameError != null) {
                val fadeAlpha by animateFloatAsState(1f, tween(200))
                Text(
                    text = usernameError!!,
                    color = Color(0xFFFF0000),
                    fontSize = 12.sp,
                    fontStyle = FontStyle.Italic,
                    modifier = Modifier.align(Alignment.Start).graphicsLayer { this.alpha = fadeAlpha }
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            FormField(
                value = password,
                onValueChange = {
                    password = it
                    if (password.length >= 6) passwordError = null
                },
                placeholder = "Password",
                isError = passwordError != null,
                trailingIcon = {
                    val interaction = remember { MutableInteractionSource() }
                    val pressed by interaction.collectIsPressedAsState()
                    val scale by animateFloatAsState(if (pressed) 0.9f else 1f, tween(80))
                    IconButton(onClick = { showPassword = !showPassword }, interactionSource = interaction, modifier = Modifier.scale(scale)) {
                        Icon(if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, contentDescription = "Toggle password")
                    }
                },
                visualTransformation = if (showPassword) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
            )
            Spacer(modifier = Modifier.height(12.dp))
            if (passwordError != null) {
                val fadeAlpha by animateFloatAsState(1f, tween(200))
                Text(
                    text = passwordError!!,
                    color = Color(0xFFFF0000),
                    fontSize = 12.sp,
                    fontStyle = FontStyle.Italic,
                    modifier = Modifier.align(Alignment.Start).graphicsLayer { this.alpha = fadeAlpha }
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            FormField(
                value = confirm,
                onValueChange = {
                    confirm = it
                    if (confirm == password) confirmError = null
                },
                placeholder = "Confirm password",
                isError = confirmError != null,
                trailingIcon = {
                    val interaction = remember { MutableInteractionSource() }
                    val pressed by interaction.collectIsPressedAsState()
                    val scale by animateFloatAsState(if (pressed) 0.9f else 1f, tween(80))
                    IconButton(onClick = { showConfirm = !showConfirm }, interactionSource = interaction, modifier = Modifier.scale(scale)) {
                        Icon(if (showConfirm) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, contentDescription = "Toggle confirm")
                    }
                },
                visualTransformation = if (showConfirm) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
            )
            Spacer(modifier = Modifier.height(16.dp))
            if (confirmError != null) {
                val fadeAlpha by animateFloatAsState(1f, tween(200))
                Text(
                    text = confirmError!!,
                    color = Color(0xFFFF0000),
                    fontSize = 12.sp,
                    fontStyle = FontStyle.Italic,
                    modifier = Modifier.align(Alignment.Start).graphicsLayer { this.alpha = fadeAlpha }
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            PrimaryButton(
                text = "Sign Up",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp),
                enabled = isFormValid && !isLoading,
                onClick = {
                    try {
                        registerError = null
                        usernameError = null
                        passwordError = null
                        confirmError = null
                        if (email.isBlank()) { emailError = "Email harus diisi"; throw IllegalArgumentException(emailError) }
                        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches()) { emailError = "Email tidak valid"; throw IllegalArgumentException(emailError) }
                        if (username.isBlank()) { usernameError = "Username harus diisi"; throw IllegalArgumentException(usernameError) }
                        if (password.length < 6) { passwordError = "Password minimal 6 karakter"; throw IllegalArgumentException(passwordError) }
                        if (password != confirm) { confirmError = "Password tidak sesuai"; throw IllegalArgumentException(confirmError) }
                        isLoading = true
                        FirebaseRepo.registerUser(email.trim(), password, username.trim()) { success, error ->
                            isLoading = false
                            if (success) {
                                onNext()
                            } else {
                                registerError = error ?: "Registrasi gagal"
                            }
                        }
                    } catch (e: Exception) { registerError = e.message ?: "Registrasi gagal" }
                }
            )
        }
        if (registerError != null) {
            ErrorModal(title = "Registrasi gagal", message = registerError!!, onDismiss = { registerError = null })
        }
        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OTPScreen(onBack: () -> Unit, onVerify: () -> Unit) {
    val focusManager = LocalFocusManager.current
    val requesters = remember { List(4) { FocusRequester() } }
    val digits = remember { mutableStateListOf("", "", "", "") }
    var otpError by remember { mutableStateOf<String?>(null) }
    var isVerifying by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        BackIcon(
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(24.dp)
        )
        Column(
            modifier = Modifier.padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            InstagramWordmark()
            Spacer(modifier = Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                repeat(4) { index ->
                    TextField(
                        value = digits[index],
                        onValueChange = { newVal ->
                            val v = newVal.filter { it.isDigit() }.take(1)
                            digits[index] = v
                            if (v.length == 1) {
                                if (index < 3) requesters[index + 1].requestFocus() else focusManager.clearFocus()
                            }
                            if (digits.all { it.isNotEmpty() }) otpError = null
                        },
                        modifier = Modifier
                            .width(56.dp)
                            .height(56.dp)
                            .focusRequester(requesters[index]),
                        textStyle = MaterialTheme.typography.titleMedium.copy(textAlign = TextAlign.Center),
                        colors = TextFieldDefaults.textFieldColors(
                            containerColor = Color(0xFFF3F4F6),
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        ),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                    )
                }
            }
            if (otpError != null) {
                Text(
                    text = otpError!!,
                    color = Color(0xFFFF0000),
                    fontSize = 12.sp,
                    fontStyle = FontStyle.Italic,
                    modifier = Modifier.align(Alignment.Start)
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            PrimaryButton(
                text = "Verify",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp),
                onClick = {
                    try {
                        otpError = null
                        if (!digits.all { it.isNotEmpty() }) {
                            otpError = "Kode OTP harus 4 digit"
                            throw IllegalArgumentException(otpError)
                        }
                        isVerifying = true
                    } catch (_: Exception) { }
                }
            )
            if (isVerifying) {
                Spacer(modifier = Modifier.height(12.dp))
                CircularProgressIndicator()
            }
        }
        LaunchedEffect(isVerifying) {
            if (isVerifying) {
                delay(1200)
                isVerifying = false
                onVerify()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onLogout: () -> Unit) {
    val navController = rememberNavController()
    var selected by remember { mutableStateOf("home") }
    var showMenu by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopHeaderBar(route = selected, onLogout = onLogout, onOpenMenu = { if (selected == "profile") showMenu = true })
        },
        bottomBar = if (!showMenu) {
            {
                Surface(color = Color.White.copy(alpha = 0.95f), shadowElevation = 8.dp) {
                    BottomNavBar(selectedRoute = selected, onSelect = { route ->
                        selected = route
                        navController.navigate(route) {
                            launchSingleTop = true
                            restoreState = true
                            popUpTo(navController.graph.startDestinationId) { saveState = true }
                        }
                    })
                }
            }
        } else {
            {}
        }
    ) { inner ->
        NavHost(
            navController = navController,
            startDestination = "home",
            modifier = Modifier.padding(inner)
        ) {
            composable(
                route = "home",
                deepLinks = listOf(navDeepLink { uriPattern = "instaapp://home" })
            ) { HomeTabContent() }
            composable(
                route = "search",
                deepLinks = listOf(navDeepLink { uriPattern = "instaapp://search" })
            ) { SearchTabContent() }
            composable(
                route = "create",
                deepLinks = listOf(navDeepLink { uriPattern = "instaapp://create" })
            ) { CreateTabContent(onPosted = {
                selected = "home"
                navController.navigate("home") {
                    launchSingleTop = true
                    restoreState = true
                    popUpTo(navController.graph.startDestinationId) { saveState = true }
                }
            }) }
            composable(
                route = "activity",
                deepLinks = listOf(navDeepLink { uriPattern = "instaapp://activity" })
            ) { ActivityTabContent() }
            composable(
                route = "profile",
                deepLinks = listOf(navDeepLink { uriPattern = "instaapp://profile" })
            ) { ProfileTabContent() }
        }
    }
    if (showMenu) {
        ProfileSideMenu(onClose = { showMenu = false }, onLogout = {
            showMenu = false
            onLogout()
        })
    }
    BackHandler(enabled = selected != "home") {
        navController.popBackStack()
        selected = navController.currentDestination?.route ?: "home"
    }
}

@Composable
private fun TopHeaderBar(route: String, onLogout: () -> Unit, onOpenMenu: () -> Unit) {
    val ctx = LocalContext.current
    Surface(color = Color.White.copy(alpha = 0.95f), shadowElevation = 4.dp) {
        when (route) {
            "profile" -> ProfileTopHeaderBar(onLogout, onOpenMenu)
            else -> DefaultTopHeaderBar()
        }
    }
}

@Composable
private fun DefaultTopHeaderBar() {
    val ctx = LocalContext.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        val logoPainter = rememberSvgPainter(".libs/assets/svg/logoIG.svg")
        Image(painter = logoPainter, contentDescription = "Logo", modifier = Modifier.height(28.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val camPainter = rememberSvgPainter(".libs/assets/svg/camera.svg")
            Image(
                painter = camPainter,
                contentDescription = "Camera",
                modifier = Modifier.size(24.dp).clickable { android.widget.Toast.makeText(ctx, "Camera", android.widget.Toast.LENGTH_SHORT).show() },
                colorFilter = ColorFilter.tint(Color(0xFF262626))
            )
            val msgPainter = rememberSvgPainterSafe(".libs/assets/svg/Messanger.svg", ".libs/assets/svg/Icon.svg")
            Image(
                painter = msgPainter,
                contentDescription = "Messenger",
                modifier = Modifier.size(24.dp).clickable { android.widget.Toast.makeText(ctx, "Messenger", android.widget.Toast.LENGTH_SHORT).show() },
                colorFilter = ColorFilter.tint(Color(0xFF262626))
            )
        }
    }
}

@Composable
private fun ProfileTopHeaderBar(onLogout: () -> Unit, onOpenMenu: () -> Unit) {
    val ctx = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Spacer(modifier = Modifier.width(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            val lockPainter = rememberSvgPainterSafe(".libs/assets/svg/PrivateIcon.svg", ".libs/assets/svg/Icon.svg")
            Image(painter = lockPainter, contentDescription = "Private", modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("jacob_w", color = Color(0xFF262626), style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.width(6.dp))
            Icon(imageVector = Icons.Filled.KeyboardArrowDown, contentDescription = "More", tint = Color(0xFF262626))
        }
        val menuPainter = rememberSvgPainterSafe(".libs/assets/svg/Menu.svg", ".libs/assets/svg/Icon.svg")
        Image(
            painter = menuPainter,
            contentDescription = "Menu",
            modifier = Modifier.size(22.dp).clickable { onOpenMenu() },
            colorFilter = ColorFilter.tint(Color(0xFF262626))
        )
    }
}

@Composable
private fun ProfileSideMenu(onClose: () -> Unit, onLogout: () -> Unit) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val panelWidth = maxWidth * 0.65f
        val targetOffset = maxWidth - panelWidth
        val offsetX by animateDpAsState(targetValue = targetOffset, animationSpec = tween(220))
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0x1A000000))
                .clickable { onClose() }
        ) {}
        Surface(
            color = Color.White,
            shadowElevation = 8.dp,
            shape = androidx.compose.foundation.shape.RoundedCornerShape(0.dp),
            modifier = Modifier
                .width(panelWidth)
                .fillMaxHeight()
                .offset(x = offsetX)
        ) {
            val userLabel = Firebase.auth.currentUser?.email ?: "Profile"
            Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(userLabel, color = Color(0xFF262626), style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(16.dp))
                MenuItemRow(".libs/assets/svg/Icon.svg", "Archive") { }
                MenuItemRow(".libs/assets/svg/Icon.svg", "Your Activity") { }
                MenuItemRow(".libs/assets/svg/Icon.svg", "Nametag") { }
                MenuItemRow(".libs/assets/svg/Icon.svg", "Saved") { }
                MenuItemRow(".libs/assets/svg/Icon.svg", "Close Friends") { }
                MenuItemRow(".libs/assets/svg/Icon.svg", "Discover People") { }
                MenuItemRow(".libs/assets/svg/Icon.svg", "Open Facebook") { }
                Spacer(modifier = Modifier.weight(1f))
                Divider(color = Color(0xFFE0E0E0))
                Spacer(modifier = Modifier.height(12.dp))
                MenuItemRow(null, "Log out", danger = true) { onLogout() }
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun MenuItemRow(iconPath: String?, label: String, danger: Boolean = false, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp)
            .clickable { onClick() },
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (iconPath != null) {
            val painter = rememberSvgPainterSafe(iconPath, ".libs/assets/svg/Icon.svg")
            Image(painter = painter, contentDescription = label, modifier = Modifier.size(22.dp), colorFilter = ColorFilter.tint(Color(0xFF262626)))
            Spacer(modifier = Modifier.width(12.dp))
        } else {
            Spacer(modifier = Modifier.width(4.dp))
        }
        Text(label, color = if (danger) Color(0xFFC62828) else Color(0xFF262626))
    }
}

@Composable
private fun BottomNavBar(selectedRoute: String, onSelect: (String) -> Unit) {
    val items = remember {
        listOf(
            Triple("home", ".libs/assets/svg/home.svg", "Home"),
            Triple("search", ".libs/assets/svg/search.svg", "Search"),
            Triple("create", ".libs/assets/svg/addpost.svg", "Create Post"),
            Triple("activity", ".libs/assets/svg/Icon.svg", "Activity"),
            Triple("profile", ".libs/assets/svg/dummyfoto.svg", "Profile")
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 14.dp, horizontal = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        items.forEach { (route, iconPath, label) ->
            val selected = selectedRoute == route
            Column(
                modifier = Modifier
                    .width(64.dp)
                    .clickable { onSelect(route) },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val painter = rememberSvgPainter(iconPath)
                val cf = if (route != "profile") ColorFilter.tint(if (selected) Color.Black else Color(0xFF606060)) else null
                Image(
                    painter = painter,
                    contentDescription = label,
                    modifier = Modifier.size(24.dp),
                    colorFilter = cf
                )
            }
        }
    }
}

@Composable
private fun HomeTabContent() {
    var showComments by remember { mutableStateOf(false) }
    var postLiked by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    val images = remember {
        val listed = listPostImages(ctx)
        if (listed.isEmpty()) listOf("src/main/assets/png/Rectangle.png") else listed
    }
    val latestPost = remember { mutableStateOf<PostItem?>(null) }
    var latestPostLiked by remember { mutableStateOf(false) }
    var latestShowComments by remember { mutableStateOf(false) }
    var latestLikesCount by remember { mutableStateOf(0) }
    val otherPosts = remember { mutableStateListOf<PostItem>() }
    val likesCount = remember { mutableStateMapOf<Long, Int>() }
    val likedMap = remember { mutableStateMapOf<Long, Boolean>() }
    var activePostId by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(Unit) {
        com.example.instaapp.data.FirebaseRepo.fetchRecentPosts(20) { list, _ ->
            val first = list?.firstOrNull()
            latestPost.value = first
            latestLikesCount = first?.likes ?: 0
            otherPosts.clear()
            list?.drop(1)?.let { ops ->
                otherPosts.addAll(ops)
                ops.forEach { p -> likesCount[p.id] = p.likes }
            }
        }
    }
    var currentImage by remember { mutableStateOf(0) }
    var firebaseConnected by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) {
        try {
            if (FirebaseApp.getApps(ctx).isNotEmpty()) {
                val id = java.util.UUID.randomUUID().toString()
                Firebase.firestore.collection("__health").document(id)
                    .set(mapOf("ts" to com.google.firebase.firestore.FieldValue.serverTimestamp()))
                    .addOnSuccessListener {
                        Firebase.firestore.collection("__health").document(id).get()
                            .addOnSuccessListener { firebaseConnected = true }
                            .addOnFailureListener { firebaseConnected = false }
                    }
                    .addOnFailureListener { firebaseConnected = false }
            } else {
                firebaseConnected = false
            }
        } catch (_: Exception) { firebaseConnected = false }
    }
    Box(modifier = Modifier.fillMaxSize()) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        if (latestPost.value != null) {
            item {
                val p = latestPost.value!!
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val avatar = rememberSvgPainter(".libs/assets/svg/dummyfoto.svg")
                            Image(painter = avatar, contentDescription = "Avatar", modifier = Modifier.size(32.dp).clip(CircleShape))
                            Spacer(modifier = Modifier.width(8.dp))
                            Column { Text("you", fontWeight = FontWeight.SemiBold, color = Color(0xFF262626)); Text("Just now", color = Color(0xFF8E8E8E), style = MaterialTheme.typography.labelSmall) }
                        }
                        Text("⋯", color = Color(0xFF9E9E9E))
                    }
                    if (p.kind == "image") {
                        val painter = rememberAsyncImagePainter(p.uri)
                        Image(painter = painter, contentDescription = "New post", modifier = Modifier.fillMaxWidth().aspectRatio(1f), contentScale = ContentScale.Crop)
                    } else {
                        AndroidView(factory = { viewContext ->
                            android.widget.VideoView(viewContext).apply {
                                setVideoURI(android.net.Uri.parse(p.uri))
                                setOnPreparedListener { it.isLooping = false }
                            }
                        }, update = { vv ->
                            vv.stopPlayback()
                            vv.setVideoURI(android.net.Uri.parse(p.uri))
                            vv.seekTo(1)
                        }, modifier = Modifier.fillMaxWidth().aspectRatio(1f))
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            val love = rememberSvgPainter(".libs/assets/svg/Icon.svg")
                            Image(
                                painter = love,
                                contentDescription = "Like",
                                modifier = Modifier.size(24.dp).clickable {
                                    latestPostLiked = !latestPostLiked
                                    latestLikesCount = if (latestPostLiked) latestLikesCount + 1 else maxOf(0, latestLikesCount - 1)
                                    com.example.instaapp.data.FirebaseRepo.updatePostLike(p.id, latestPostLiked) { }
                                },
                                colorFilter = if (latestPostLiked) ColorFilter.tint(Color(0xFFFF0000)) else ColorFilter.tint(Color(0xFF262626)),
                                contentScale = ContentScale.FillBounds,
                                alignment = Alignment.Center
                            )
                        val comment = rememberSvgPainterSafe(".libs/assets/svg/Comment.svg", ".libs/assets/svg/search.svg")
                            Image(painter = comment, contentDescription = "Comment", modifier = Modifier.size(24.dp).clickable { latestShowComments = true }, colorFilter = ColorFilter.tint(Color(0xFF262626)))
                        val send = rememberSvgPainterSafe(".libs/assets/svg/Messanger.svg", ".libs/assets/svg/Icon.svg")
                        Image(painter = send, contentDescription = "Send", modifier = Modifier.size(24.dp), colorFilter = ColorFilter.tint(Color(0xFF262626)))
                    }
                        Text("${latestLikesCount} likes", color = Color(0xFF9E9E9E), style = MaterialTheme.typography.labelSmall)
                    }
                    if (p.caption.isNotBlank()) {
                        Text(p.caption, color = Color(0xFF262626), modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                    }
                }
            }
        }
        items(otherPosts, key = { it.id }) { p ->
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val avatar = rememberSvgPainter(".libs/assets/svg/dummyfoto.svg")
                        Image(painter = avatar, contentDescription = "Avatar", modifier = Modifier.size(32.dp).clip(CircleShape))
                        Spacer(modifier = Modifier.width(8.dp))
                        Column { Text("user", fontWeight = FontWeight.SemiBold, color = Color(0xFF262626)); Text("Recent", color = Color(0xFF8E8E8E), style = MaterialTheme.typography.labelSmall) }
                    }
                    Text("⋯", color = Color(0xFF9E9E9E))
                }
                if (p.kind == "image") {
                    val painter = rememberAsyncImagePainter(p.uri)
                    Image(painter = painter, contentDescription = "Post", modifier = Modifier.fillMaxWidth().aspectRatio(1f), contentScale = ContentScale.Crop)
                } else {
                    AndroidView(factory = { viewContext ->
                        android.widget.VideoView(viewContext).apply {
                            setVideoURI(android.net.Uri.parse(p.uri))
                            setOnPreparedListener { it.isLooping = false }
                        }
                    }, update = { vv ->
                        vv.stopPlayback(); vv.setVideoURI(android.net.Uri.parse(p.uri)); vv.seekTo(1)
                    }, modifier = Modifier.fillMaxWidth().aspectRatio(1f))
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        val love = rememberSvgPainter(".libs/assets/svg/Icon.svg")
                        Image(
                            painter = love,
                            contentDescription = "Like",
                            modifier = Modifier.size(24.dp).clickable {
                                val newLiked = !(likedMap[p.id] ?: false)
                                likedMap[p.id] = newLiked
                                val cur = likesCount[p.id] ?: p.likes
                                likesCount[p.id] = if (newLiked) cur + 1 else maxOf(0, cur - 1)
                                com.example.instaapp.data.FirebaseRepo.updatePostLike(p.id, newLiked) { }
                            },
                            colorFilter = if (likedMap[p.id] == true) ColorFilter.tint(Color(0xFFFF0000)) else ColorFilter.tint(Color(0xFF262626)),
                            contentScale = ContentScale.FillBounds,
                            alignment = Alignment.Center
                        )
                        val comment = rememberSvgPainterSafe(".libs/assets/svg/Comment.svg", ".libs/assets/svg/search.svg")
                        Image(painter = comment, contentDescription = "Comment", modifier = Modifier.size(24.dp).clickable { activePostId = p.id }, colorFilter = ColorFilter.tint(Color(0xFF262626)))
                        val send = rememberSvgPainterSafe(".libs/assets/svg/Messanger.svg", ".libs/assets/svg/Icon.svg")
                        Image(painter = send, contentDescription = "Send", modifier = Modifier.size(24.dp), colorFilter = ColorFilter.tint(Color(0xFF262626)))
                    }
                    Text("${likesCount[p.id] ?: p.likes} likes", color = Color(0xFF9E9E9E), style = MaterialTheme.typography.labelSmall)
                }
                if (p.caption.isNotBlank()) {
                    Text(p.caption, color = Color(0xFF262626), modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                }
            }
        }
        item {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    when (firebaseConnected) {
                        true -> Text("Firebase: Connected", color = Color(0xFF2E7D32), style = MaterialTheme.typography.labelSmall)
                        false -> Text("Firebase: Disconnected", color = Color(0xFFC62828), style = MaterialTheme.typography.labelSmall)
                        null -> Text("Firebase: Checking...", color = Color(0xFF757575), style = MaterialTheme.typography.labelSmall)
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val avatar = rememberSvgPainter(".libs/assets/svg/dummyfoto.svg")
                        Image(painter = avatar, contentDescription = "Avatar", modifier = Modifier.size(32.dp).clip(CircleShape))
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text("joshua_l", fontWeight = FontWeight.SemiBold, color = Color(0xFF262626))
                            Text("Tokyo, Japan", color = Color(0xFF8E8E8E), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    Text("⋯", color = Color(0xFF9E9E9E))
                }

                Box(modifier = Modifier.fillMaxWidth()) {
                    val photoPainter = rememberAsyncImagePainter(assetUri(images[currentImage]))
                    Image(
                        painter = photoPainter,
                        contentDescription = "Post image",
                        modifier = Modifier.fillMaxWidth().aspectRatio(1f)
                    )
                    // slide controls
                    Row(modifier = Modifier.matchParentSize()) {
                        Box(modifier = Modifier.weight(1f).fillMaxHeight().clickable { if (currentImage > 0) currentImage -= 1 }) {}
                        Box(modifier = Modifier.weight(1f).fillMaxHeight().clickable { if (currentImage < images.size - 1) currentImage += 1 }) {}
                    }
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .background(Color(0x88000000), shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                    ) {
                        Text("${currentImage + 1}/${images.size}", color = Color.White, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        val love = rememberSvgPainter(".libs/assets/svg/Icon.svg")
                        Image(
                            painter = love,
                            contentDescription = "Like",
                            modifier = Modifier.size(24.dp).clickable { postLiked = !postLiked },
                            colorFilter = if (postLiked) ColorFilter.tint(Color(0xFFFF0000)) else ColorFilter.tint(Color(0xFF262626)),
                            contentScale = ContentScale.FillBounds,
                            alignment = Alignment.Center
                        )
                        val comment = rememberSvgPainterSafe(".libs/assets/svg/Comment.svg", ".libs/assets/svg/search.svg")
                        Image(painter = comment, contentDescription = "Comment", modifier = Modifier.size(24.dp).clickable { showComments = true }, colorFilter = ColorFilter.tint(Color(0xFF262626)))
                        val send = rememberSvgPainterSafe(".libs/assets/svg/Messanger.svg", ".libs/assets/svg/Icon.svg")
                        Image(painter = send, contentDescription = "Send", modifier = Modifier.size(24.dp), colorFilter = ColorFilter.tint(Color(0xFF262626)))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        repeat(images.size) { idx ->
                            Box(
                                modifier = Modifier.size(6.dp).clip(CircleShape).background(if (idx == currentImage) Color(0xFF262626) else Color(0xFFBDBDBD))
                            )
                        }
                    }
                    val bookmark = rememberSvgPainterSafe(".libs/assets/svg/Shape.svg", ".libs/assets/svg/Icon.svg")
                    Image(painter = bookmark, contentDescription = "Save", modifier = Modifier.size(24.dp), colorFilter = ColorFilter.tint(Color(0xFF262626)))
                }

                Text("Liked by craig_love and 44,686 others", color = Color(0xFF8E8E8E), modifier = Modifier.padding(horizontal = 12.dp))
                Spacer(modifier = Modifier.height(4.dp))
                Text("joshua_l The game in Japan was amazing and I want to share some photos", color = Color(0xFF262626), modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
            }
        }
    }
    CommentsOverlay(show = showComments, onClose = { showComments = false }) {
        CommentsSheet(onClose = { showComments = false })
    }
    CommentsOverlay(show = latestShowComments, onClose = { latestShowComments = false }) {
        val pid = latestPost.value?.id
        CommentsSheet(onClose = { latestShowComments = false }, postId = pid)
    }
    CommentsOverlay(show = activePostId != null, onClose = { activePostId = null }) {
        CommentsSheet(onClose = { activePostId = null }, postId = activePostId)
    }
    }
}

// Custom overlay used instead of Material bottom sheet for compatibility

@Composable
private fun CommentsSheet(onClose: () -> Unit, postId: Long? = null) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val comments = remember { mutableStateListOf<UserComment>() }
    var cloudEnabled by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(postId) {
        val loader: (List<UserComment>?, Exception?) -> Unit = { list, err ->
            if (list != null) {
                cloudEnabled = true
                comments.clear()
                comments.addAll(list)
                persistComments(ctx, comments)
            } else {
                cloudEnabled = false
                val initial = loadComments(ctx)
                if (initial.isEmpty()) {
                    comments.clear()
                    comments.addAll(
                        listOf(
                            UserComment(System.currentTimeMillis(), "grassrootssp", System.currentTimeMillis() - 28L * 24 * 60 * 60 * 1000, "Amazing, thank you so much for being a part of @mentalhealthunitedfc’s tournament for a second year. We are so grateful for such amazing community support all working together to help prevent suicide.❤️", 1, true),
                            UserComment(System.currentTimeMillis() - 1000, "chloesim___", System.currentTimeMillis() - 14_000, "Great work!", 0, false),
                            UserComment(System.currentTimeMillis() - 2000, "alexmkeith", System.currentTimeMillis() - 37_000, "👏👏👏", 0, false)
                        )
                    )
                } else {
                    comments.clear()
                    comments.addAll(initial.sortedByDescending { it.time })
                }
            }
        }
        if (postId != null) {
            com.example.instaapp.data.FirebaseRepo.fetchCommentsForPost(postId, loader)
        } else {
            com.example.instaapp.data.FirebaseRepo.fetchComments(loader)
        }
    }
    var input by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var isPosting by remember { mutableStateOf(false) }
    var postedTimestamps by remember { mutableStateOf<List<Long>>(emptyList()) }
    val maxChars = 500
    val remaining = maxChars - input.length
    Column(modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 8.dp)) {
        Box(modifier = Modifier
            .fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(modifier = Modifier
                .width(40.dp)
                .height(4.dp)
                .background(Color(0xFFE0E0E0), shape = androidx.compose.foundation.shape.RoundedCornerShape(2.dp)))
        }
        Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(modifier = Modifier.weight(1f))
            Text("Comments", fontWeight = FontWeight.SemiBold, color = Color(0xFF262626))
            Spacer(modifier = Modifier.weight(1f))
            Text("⋯", color = Color(0xFF9E9E9E), modifier = Modifier.clickable { onClose() })
        }
        Divider(color = Color(0xFFE0E0E0))
        Spacer(modifier = Modifier.height(8.dp))
        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f), state = listState) {
            items(comments, key = { it.id }) { c ->
                CommentRow(
                    comment = c,
                    onToggleLike = {
                        c.liked = !c.liked
                        c.likes = if (c.liked) c.likes + 1 else maxOf(0, c.likes - 1)
                        persistComments(ctx, comments)
                        com.example.instaapp.data.FirebaseRepo.updateLike(c.id, c.liked, c.likes) { }
                    }
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf("❤️","🙌","🔥","👏","😢","😍","😮","😂").forEach { emoji ->
                Box(modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFF5F5F5)), contentAlignment = Alignment.Center) {
                    Text(emoji)
                }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(32.dp).clip(CircleShape).background(Color(0xFFEDE7F6)))
            Spacer(modifier = Modifier.width(8.dp))
            TextField(
                value = input,
                onValueChange = {
                    input = it.take(maxChars)
                    error = null
                },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Add a comment...") },
                colors = TextFieldDefaults.textFieldColors(
                    containerColor = Color(0xFFF3F4F6),
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                )
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("${remaining}", color = Color(0xFF9E9E9E), style = MaterialTheme.typography.labelSmall)
            Spacer(modifier = Modifier.width(8.dp))
            OutlinedButton(
                onClick = {
                    if (input.isBlank()) { error = "Komentar tidak boleh kosong"; return@OutlinedButton }
                    if (input.length > maxChars) { error = "Maksimal 500 karakter"; return@OutlinedButton }
                    val now = System.currentTimeMillis()
                    val recent = postedTimestamps.filter { now - it < 60_000 }
                    if (recent.size >= 5) { error = "Terlalu banyak komentar, coba lagi nanti"; return@OutlinedButton }
                    isPosting = true
                    scope.launch {
                        delay(600)
                        val new = UserComment(System.currentTimeMillis(), "you", System.currentTimeMillis(), input.trim(), 0, false)
                        comments.add(0, new)
                        persistComments(ctx, comments)
                        if (postId != null) com.example.instaapp.data.FirebaseRepo.postCommentForPost(postId, new) { }
                        else com.example.instaapp.data.FirebaseRepo.postComment(new) { }
                        input = ""
                        postedTimestamps = recent + now
                        isPosting = false
                        listState.animateScrollToItem(0)
                    }
                },
                border = BorderStroke(1.dp, Color(0xFFE0E0E0))
            ) { Text("Post", color = Color(0xFF262626)) }
        }
        if (error != null) {
            Text(error!!, color = Color(0xFFFF0000), style = MaterialTheme.typography.labelSmall)
        }
        if (isPosting) {
            Spacer(modifier = Modifier.height(8.dp))
            CircularProgressIndicator()
        }
        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
private fun CommentRow(comment: UserComment, onToggleLike: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(comment.user, fontWeight = FontWeight.SemiBold, color = Color(0xFF262626))
                Spacer(modifier = Modifier.width(6.dp))
                val rel = ((System.currentTimeMillis() - comment.time) / 1000).toInt()
                Text(if (rel < 60) "${rel}s" else if (rel < 3600) "${rel/60}m" else "${rel/3600}h", color = Color(0xFF9E9E9E), style = MaterialTheme.typography.labelSmall)
            }
            Text(comment.text, color = Color(0xFF262626))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (comment.likes == 1) "1 like" else "${comment.likes} likes", color = Color(0xFF9E9E9E), style = MaterialTheme.typography.labelSmall)
                Text("Reply", color = Color(0xFF9E9E9E), style = MaterialTheme.typography.labelSmall)
            }
        }
        val heart = rememberSvgPainter(".libs/assets/svg/Icon.svg")
        Image(
            painter = heart,
            contentDescription = "Like",
            modifier = Modifier.size(20.dp).clickable { onToggleLike() },
            colorFilter = if (comment.liked) ColorFilter.tint(Color(0xFFFF0000)) else ColorFilter.tint(Color(0xFF9E9E9E)),
            contentScale = ContentScale.FillBounds,
            alignment = Alignment.Center
        )
    }
}

@Composable
private fun SearchTabContent() {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(20) { i ->
            Text("Search result #$i", modifier = Modifier.padding(16.dp))
        }
    }
}

@Composable
private fun CreateTabContent(onPosted: () -> Unit) {
    val ctx = LocalContext.current
    var mediaUrl by remember { mutableStateOf("") }
    var caption by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var posting by remember { mutableStateOf(false) }
    

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        TextField(
            value = mediaUrl,
            onValueChange = { mediaUrl = it.trim(); error = null },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Media URL (https://)") },
            colors = TextFieldDefaults.textFieldColors(
                containerColor = Color(0xFFF3F4F6),
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent
            )
        )
        Spacer(modifier = Modifier.height(16.dp))
        Box(modifier = Modifier.fillMaxWidth().aspectRatio(1f).background(Color(0xFFF3F4F6)), contentAlignment = Alignment.Center) {
            if (mediaUrl.isBlank()) {
                Text("Masukkan URL media")
            } else if (mediaUrl.endsWith(".mp4") || mediaUrl.contains("/video")) {
                AndroidView(factory = { viewContext ->
                    android.widget.VideoView(viewContext).apply {
                        setVideoURI(android.net.Uri.parse(mediaUrl))
                        setOnPreparedListener { it.isLooping = false }
                    }
                }, update = { vv ->
                    vv.stopPlayback(); vv.setVideoURI(android.net.Uri.parse(mediaUrl))
                }, modifier = Modifier.fillMaxSize())
            } else {
                val painter = rememberAsyncImagePainter(mediaUrl)
                Image(painter = painter, contentDescription = "Preview", modifier = Modifier.fillMaxSize())
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        FormField(value = caption, onValueChange = { caption = it }, placeholder = "Caption", keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text))
        Spacer(modifier = Modifier.height(16.dp))
        PrimaryButton(text = if (posting) "Posting..." else "Post", enabled = !posting && mediaUrl.isNotBlank(), onClick = {
            val low = mediaUrl.lowercase()
            if (!low.startsWith("http")) { error = "Masukkan URL HTTPS"; return@PrimaryButton }
            posting = true
            com.example.instaapp.data.FirebaseRepo.createPostFromUrl(mediaUrl.trim(), caption.trim()) { success, err ->
                posting = false
                if (success) {
                    caption = ""; mediaUrl = ""; error = null; onPosted()
                } else { error = err ?: "Gagal posting" }
            }
        })
        if (error != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(error!!, color = Color(0xFFFF0000), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun ActivityTabContent() {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(10) { i ->
            Text("Activity #$i", modifier = Modifier.padding(16.dp))
        }
    }
}

@Composable
private fun ProfileTabContent() {
    var tab by remember { mutableStateOf("grid") }
    androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
        columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(3),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(1.dp),
        horizontalArrangement = Arrangement.spacedBy(1.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp)
    ) {
        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(3) }) {
            Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                val avatar = rememberSvgPainter(".libs/assets/svg/dummyfoto.svg")
                Image(painter = avatar, contentDescription = "Avatar", modifier = Modifier.size(80.dp).clip(CircleShape))
                Spacer(modifier = Modifier.width(16.dp))
                Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceBetween) {
                    StatItem("54", "Posts")
                    StatItem("834", "Followers")
                    StatItem("162", "Following")
                }
            }
        }
        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(3) }) {
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                Text("Jacob West", fontWeight = FontWeight.SemiBold, color = Color(0xFF262626))
                Text("Digital goodies designer @pixsellz", color = Color(0xFF262626))
                Text("Everything is designed.", color = Color(0xFF262626))
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(onClick = {}, modifier = Modifier.fillMaxWidth().height(40.dp), border = BorderStroke(1.dp, Color(0xFFE0E0E0))) {
                    Text("Edit Profile", color = Color(0xFF262626))
                }
            }
        }
        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(3) }) {
            Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                HighlightItem("+", "New")
                HighlightItem("", "Friends")
                HighlightItem("", "Sport")
                HighlightItem("", "Design")
            }
        }
        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(3) }) {
            Column {
                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFE0E0E0)))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val gridPainter = rememberSvgPainterSafe(".libs/assets/svg/grid.svg", ".libs/assets/svg/home.svg")
                    Image(
                        painter = gridPainter,
                        contentDescription = "Grid",
                        modifier = Modifier.size(24.dp).clickable { tab = "grid" },
                        colorFilter = ColorFilter.tint(if (tab == "grid") Color(0xFF000000) else Color(0xFF9E9E9E))
                    )
                    val tagPainter = rememberSvgPainterSafe(".libs/assets/svg/TagsIcon.svg", ".libs/assets/svg/Icon.svg")
                    Image(
                        painter = tagPainter,
                        contentDescription = "Tags",
                        modifier = Modifier.size(24.dp).clickable { tab = "tags" },
                        colorFilter = ColorFilter.tint(if (tab == "tags") Color(0xFF000000) else Color(0xFF9E9E9E))
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                // Garis bawah penuh dibagi dua bagian, dengan indikator aktif yang berpindah halus
                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    val half = maxWidth / 2
                    Row(modifier = Modifier.fillMaxWidth().height(2.dp)) {
                        Box(modifier = Modifier.width(half).fillMaxHeight().background(Color(0xFFE0E0E0)))
                        Box(modifier = Modifier.width(half).fillMaxHeight().background(Color(0xFFE0E0E0)))
                    }
                    val target = if (tab == "grid") 0.dp else half
                    val anim by animateDpAsState(targetValue = target, animationSpec = tween(220))
                    Box(
                        modifier = Modifier
                            .offset(x = anim, y = 0.dp)
                            .width(half)
                            .height(2.dp)
                            .background(Color(0xFF000000))
                    )
                }
            }
        }
        if (tab == "grid") {
            items(21) { idx ->
                Box(modifier = Modifier.aspectRatio(1f).background(Color(listOf(0xFFEEEEEE, 0xFFE3F2FD, 0xFFFCE4EC)[idx % 3].toInt())))
            }
        } else {
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(3) }) {
                TagsPlaceholder()
            }
        }
    }
}

@Composable
private fun StatItem(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontWeight = FontWeight.Bold, color = Color(0xFF262626))
        Text(label, color = Color(0xFF8E8E8E), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun HighlightItem(symbol: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(modifier = Modifier.size(64.dp).clip(CircleShape).border(1.dp, Color(0xFFE0E0E0), CircleShape), contentAlignment = Alignment.Center) {
            Text(symbol, color = Color(0xFF606060))
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(label, color = Color(0xFF8E8E8E), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
// obsolete helper removed: grid now integrated in ProfileTabContent


private fun TagsPlaceholder() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No tagged posts") }
}

// Preview removed to avoid plugin-related annotation issues during build

@Composable
private fun InstagramWordmark(modifier: Modifier = Modifier) {
    val painter = rememberSvgPainter(".libs/assets/svg/logoIG.svg")
    Image(
        painter = painter,
        contentDescription = "Instagram logo",
        modifier = modifier
            .width(182.dp)
            .height(49.dp)
    )
}

@Composable
private fun BackIcon(modifier: Modifier = Modifier, onClick: () -> Unit) {
    val painter = rememberSvgPainter(".libs/assets/svg/Back.svg")
    Image(
        painter = painter,
        contentDescription = "Kembali",
        modifier = modifier
            .size(18.dp)
            .clickable { onClick() }
    )
}

@Composable
private fun PrimaryButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    var pressed by remember { mutableStateOf(false) }
    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> pressed = true
                is PressInteraction.Release, is PressInteraction.Cancel -> pressed = false
            }
        }
    }
    val scale = if (pressed) 0.98f else 1f
    Button(
        onClick = onClick,
        interactionSource = interactionSource,
        modifier = modifier
            .scale(scale),
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3897F0))
    ) {
        Text(text, color = Color.White)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FormField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    isError: Boolean = false,
    trailingIcon: @Composable (() -> Unit)? = null,
    visualTransformation: VisualTransformation = androidx.compose.ui.text.input.VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp),
        singleLine = true,
        maxLines = 1,
        placeholder = { Text(placeholder) },
        isError = isError,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
        trailingIcon = trailingIcon,
        colors = TextFieldDefaults.outlinedTextFieldColors(
            focusedBorderColor = Color(0xFFE5E7EB),
            unfocusedBorderColor = Color(0xFFE5E7EB),
            errorBorderColor = Color(0xFFE5E7EB),
            cursorColor = Color.Black,
            placeholderColor = Color(0xFF9E9E9E),
            containerColor = Color.White
        )
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccountLoginView(
    onSwitchAccounts: () -> Unit,
    onLogin: () -> Unit,
    onSignUp: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        InstagramWordmark()
        Spacer(modifier = Modifier.height(24.dp))

        val avatarPainter = rememberSvgPainter(".libs/assets/svg/dummyfoto.svg")
        Image(
            painter = avatarPainter,
            contentDescription = "Foto profil",
            modifier = Modifier
                .size(96.dp)
                .clip(CircleShape)
                .border(1.dp, Color(0xFFE0E0E0), CircleShape)
        )

        Spacer(modifier = Modifier.height(12.dp))
        Text("jacob_w", color = Color(0xFF262626))

        Spacer(modifier = Modifier.height(16.dp))
        PrimaryButton(
            text = "Log in",
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp),
            onClick = onLogin
        )

        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "Switch accounts",
            color = Color(0xFF3897F0),
            modifier = Modifier.clickable { onSwitchAccounts() }
        )

        Spacer(modifier = Modifier.height(48.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Don't have an account?",
                color = Color(0xFF8E8E8E)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "Sign up.",
                color = Color(0xFF3897F0),
                modifier = Modifier.clickable { onSignUp() }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CredentialLoginView(
    onBack: () -> Unit,
    onSubmit: (String, String) -> Unit,
    onSignUp: () -> Unit
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val isFormValid = username.isNotBlank() && password.isNotBlank()

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        BackIcon(
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(24.dp)
        )

        Column(
            modifier = Modifier.padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            InstagramWordmark()

            Spacer(modifier = Modifier.height(24.dp))

            FormField(
                value = username,
                onValueChange = { username = it },
                placeholder = "Email",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email)
            )

            Spacer(modifier = Modifier.height(12.dp))
            FormField(
                value = password,
                onValueChange = { password = it },
                placeholder = "Password",
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
            )

            Box(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Forgot password?",
                    color = Color(0xFF3897F0),
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .clickable { }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            PrimaryButton(
                text = "Log in",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp),
                enabled = isFormValid,
                onClick = { onSubmit(username, password) }
            )

            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(
                onClick = {},
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp),
                border = BorderStroke(1.dp, Color(0xFFE0E0E0))
            ) {
                Text("Log in with Facebook", color = Color(0xFF3897F0))
            }

            Spacer(modifier = Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Divider(color = Color(0xFFE0E0E0), modifier = Modifier.weight(1f))
                Text(
                    text = "OR",
                    color = Color(0xFF8E8E8E),
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
                Divider(color = Color(0xFFE0E0E0), modifier = Modifier.weight(1f))
            }

            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Don't have an account?",
                    color = Color(0xFF8E8E8E)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Sign up.",
                    color = Color(0xFF3897F0),
                    modifier = Modifier.clickable { onSignUp() }
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "Instagram or Facebook",
                color = Color(0xFFBDBDBD),
                fontSize = 12.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun LoginScreenPreview() {
    InstaAppTheme {
        LoginScreen(onSignUp = {}, onLoggedIn = {})
    }
}
@Composable
private fun CommentsOverlay(show: Boolean, onClose: () -> Unit, content: @Composable () -> Unit) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val sheetHeight = maxHeight * 0.75f
        val targetOffset = if (show) maxHeight - sheetHeight else maxHeight
        val offsetY by animateDpAsState(targetValue = targetOffset, animationSpec = tween(240))

        if (show) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x80000000))
                    .clickable { onClose() }
            ) {}
        }

        Surface(
            color = Color.White,
            shadowElevation = 8.dp,
            shape = androidx.compose.foundation.shape.RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(sheetHeight)
                .offset(y = offsetY)
        ) {
            content()
        }
    }
}
