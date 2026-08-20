package com.cpl.cplmobileapp

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.firebase.messaging.FirebaseMessaging
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import org.json.JSONObject
import java.net.URL
import java.util.EnumMap
import kotlin.concurrent.thread

// --- Data Models ---
data class LibraryCard(val name: String, val number: String, val email: String = "", val phone: String = "")
data class MenuItemData(val title: String, val url: String)

class MainActivity : AppCompatActivity() {

    // --- Core UI Components ---
    private lateinit var webView: WebView
    private lateinit var navBar: BottomNavigationView
    private lateinit var progressBar: ProgressBar
    private lateinit var overlay: View

    // --- URLs and Routing ---
    private val rootUrl = "https://www.chetwyndeventscalendar.com/cplapppage"
    private val guidePath = "/program-guide"
    private val opacUrl = "https://bche.bc.catalogue.libraries.coop/eg/opac/home"
    private val cplWebUrl = "https://chetwynd.bc.libraries.coop/"
    private val themeJsonUrl = "https://raw.githubusercontent.com/JohnPuflett/Chetwynd-Public-Library-App/main/theme.json"

    // --- Dynamic Header Configuration from theme.json ---
    private var headerText: String = "Chetwynd Public Library"
    private var logoCplWebUrl: String = ""
    private var logoEventsUrl: String = ""
    private var logoCplWebLink: String = "https://chetwynd.bc.libraries.coop/"
    private var logoEventsLink: String = "https://www.chetwyndeventscalendar.com/"

    // --- State Management ---
    private var doubleBackToExitPressedOnce = false
    private val backPressHandler = Handler(Looper.getMainLooper())
    private val backPressResetRunnable = Runnable { doubleBackToExitPressedOnce = false }
    private var lastSelectedTabId = R.id.nav_home
    private var isRestoringState = false
    private var isFormVisible = false
    private var isOffline = false
    private var isInitialAppLaunch = true // Track initial launch state to prevent flash re-triggering

    // Graphic overlay URLs from theme.json
    private var currentHeaderImgUrl: String = ""
    private var currentNavImgUrl: String = ""

    // Dynamic form detection domain list with fallback defaults
    private var formDetectionDomains: List<String> = listOf(
        "forms.office.com",
        "forms.microsoft.com",
        "forms.cloud.microsoft"
    )

    // Dynamic Menu Items parsed from remote theme.json
    private var dynamicMenuItems: List<MenuItemData> = emptyList()

    // Unique IDs for custom dynamically injected menu items
    private val ID_NAV_GUIDE = 1001

    // --- File Upload State & System Picker Launcher ---
    private var pendingFilePathCallback: ValueCallback<Array<Uri>>? = null

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data
            val uri = data?.data
            pendingFilePathCallback?.onReceiveValue(if (uri != null) arrayOf(uri) else null)
        } else {
            pendingFilePathCallback?.onReceiveValue(null)
        }
        pendingFilePathCallback = null
    }

    // --- Permissions ---
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                Log.d("PERMISSION", "Notifications granted")
                getFirebaseToken()
            } else {
                Toast.makeText(this, "Notifications disabled.", Toast.LENGTH_SHORT).show()
            }
        }

    // ==========================================
    // LIFECYCLE METHODS
    // ==========================================

    override fun onCreate(savedInstanceState: Bundle?) {
        // 1. Hand off from system splash screen instantly
        installSplashScreen()

        super.onCreate(savedInstanceState)

        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        setContentView(R.layout.activity_main)

        bindViews()
        configureHeader()
        setupNavigationBar()
        setupWebView()

        // Apply drop shadows to bottom navigation text labels
        applyBottomNavTextShadows()

        // Fetch and apply dynamic remote skinning configuration & check for app updates
        applyRemoteTheme()

        askNotificationPermission()
        getFirebaseToken()
        handleBackNavigation()

        // Handle incoming target URL intent passed from external callers or PdfViewerActivity
        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        // Check for raw Uri data (from system PDF link tap) or explicit intent string
        val targetUrl = intent?.data?.toString() ?: intent?.getStringExtra("EXTRA_NAVIGATE_URL")
        if (!targetUrl.isNullOrEmpty()) {
            var formattedUrl = targetUrl.trim()
            if (!formattedUrl.startsWith("http://") && !formattedUrl.startsWith("https://")) {
                formattedUrl = "https://$formattedUrl"
            }

            Log.d("MAIN_NAV", "Loading URL from intent: $formattedUrl")
            showLoadingState()

            // Post to main handler to ensure webview is fully bound
            Handler(Looper.getMainLooper()).postDelayed({
                webView.loadUrl(formattedUrl)
            }, 100)
        }
    }

    // ==========================================
    // UI INITIALIZATION & CONFIGURATION
    // ==========================================

    private fun bindViews() {
        webView = findViewById(R.id.webview)
        navBar = findViewById(R.id.bottom_navigation)
        progressBar = findViewById(R.id.loading_progress)
        overlay = findViewById(R.id.loading_overlay)

        val params = webView.layoutParams as ConstraintLayout.LayoutParams
        params.topToBottom = R.id.custom_header
        params.topToTop = -1
        webView.layoutParams = params
    }

    private fun configureHeader() {
        val logoCplWeb = findViewById<ImageView>(R.id.logo_cpl_web)
        val logoEvents = findViewById<ImageView>(R.id.logo_events_calendar)

        logoCplWeb?.setOnClickListener {
            showLoadingState()
            webView.loadUrl(logoCplWebLink)
        }
        logoEvents?.setOnClickListener {
            showLoadingState()
            webView.loadUrl(logoEventsLink)
        }
    }

    private fun setupNavigationBar() {
        navBar.labelVisibilityMode = BottomNavigationView.LABEL_VISIBILITY_LABELED
        navBar.itemIconTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
        navBar.itemTextColor = android.content.res.ColorStateList.valueOf(Color.WHITE)
        navBar.background = null
        navBar.setBackgroundResource(R.drawable.header_gradient)

        navBar.setOnItemSelectedListener { item ->
            navBar.post { forceUpdateNavLabels() }

            if (isRestoringState) {
                true
            } else if (overlay.visibility == View.VISIBLE) {
                false
            } else if (isOffline && item.itemId != R.id.nav_cards) {
                Toast.makeText(this, "Internet connection required for web features.", Toast.LENGTH_SHORT).show()
                false
            } else {
                val result = when (item.itemId) {
                    R.id.nav_search -> { showLoadingState(); webView.loadUrl(opacUrl); true }
                    R.id.nav_cards -> { showLibraryCardsDialog(); false }
                    R.id.nav_home -> { showLoadingState(); webView.loadUrl(rootUrl); true }
                    R.id.nav_web -> { showLoadingState(); webView.loadUrl(cplWebUrl); true }
                    R.id.nav_more -> { showMoreMenu(navBar.findViewById(R.id.nav_more)); false }
                    else -> false
                }

                if (result) lastSelectedTabId = item.itemId
                result
            }
        }

        navBar.setOnItemReselectedListener { item ->
            if (overlay.visibility != View.VISIBLE) {
                if (isOffline && item.itemId != R.id.nav_cards) {
                    Toast.makeText(this, "Internet connection required for web features.", Toast.LENGTH_SHORT).show()
                } else {
                    when (item.itemId) {
                        R.id.nav_home -> { showLoadingState(); webView.loadUrl(rootUrl) }
                        R.id.nav_search -> { showLoadingState(); webView.loadUrl(opacUrl) }
                        R.id.nav_cards -> showLibraryCardsDialog()
                        R.id.nav_web -> { showLoadingState(); webView.loadUrl(cplWebUrl) }
                        R.id.nav_more -> showMoreMenu(navBar.findViewById(R.id.nav_more))
                    }
                }
            }
        }

        navBar.selectedItemId = R.id.nav_home
        navBar.post { forceUpdateNavLabels() }
    }

    private fun applyBottomNavTextShadows() {
        navBar.post {
            val menuView = navBar.getChildAt(0) as? ViewGroup ?: return@post
            for (i in 0 until menuView.childCount) {
                val itemView = menuView.getChildAt(i) as? ViewGroup ?: continue
                val labelGroup = itemView.findViewById<ViewGroup>(com.google.android.material.R.id.navigation_bar_item_labels_group)
                if (labelGroup != null) {
                    for (j in 0 until labelGroup.childCount) {
                        val textView = labelGroup.getChildAt(j) as? TextView
                        textView?.setShadowLayer(8f, 3f, 5f, Color.BLACK)
                    }
                }
            }
        }
    }

    private fun showLoadingState() {
        val loadingBgImage = findViewById<ImageView>(R.id.loading_bg_image)
        val loadingLogo = findViewById<ImageView>(R.id.loading_logo)

        if (!isInitialAppLaunch) {
            // Standard tab navigation: hide splash graphic and center logo completely
            loadingBgImage?.visibility = View.GONE
            loadingLogo?.visibility = View.GONE

            // Apply a lightweight semi-transparent dark scrim overlay over the webview
            overlay.setBackgroundColor(Color.parseColor("#40000000"))
        }

        overlay.alpha = 1f
        overlay.visibility = View.VISIBLE
        progressBar.visibility = View.VISIBLE
        navBar.isEnabled = false
    }

    private fun hideLoadingState() {
        if (isInitialAppLaunch) {
            // Keep layout overlay visible on cold boot, then fade out smoothly
            Handler(Looper.getMainLooper()).postDelayed({
                isInitialAppLaunch = false
                overlay.animate()
                    .alpha(0f)
                    .setDuration(500)
                    .withEndAction {
                        overlay.visibility = View.GONE
                        overlay.alpha = 1f
                        progressBar.visibility = View.GONE
                        navBar.isEnabled = true
                    }
            }, 5000)
        } else {
            // Immediate hide for standard tab navigation
            overlay.visibility = View.GONE
            progressBar.visibility = View.GONE
            navBar.isEnabled = true
        }
    }

    // ==========================================
    // REMOTE THEME & VERSION ENGINE (CACHED)
    // ==========================================

    private fun applyRemoteTheme() {
        val prefs = getSharedPreferences("CPL_THEME_CACHE", Context.MODE_PRIVATE)

        val cachedJsonStr = prefs.getString("theme_json", null)
        if (!cachedJsonStr.isNullOrEmpty()) {
            try {
                renderThemeJson(JSONObject(cachedJsonStr))
            } catch (e: Exception) {
                Log.e("THEME_ENGINE", "Error rendering cached theme", e)
            }
        }

        thread {
            try {
                val dynamicUrl = "$themeJsonUrl?t=${System.currentTimeMillis()}"

                val connection = (URL(dynamicUrl).openConnection() as java.net.HttpURLConnection).apply {
                    connectTimeout = 3000
                    readTimeout = 3000
                    requestMethod = "GET"
                    useCaches = false
                    setRequestProperty("Cache-Control", "no-cache, no-store")
                    setRequestProperty("Pragma", "no-cache")
                }

                if (connection.responseCode == 200) {
                    var freshJsonStr = connection.inputStream.bufferedReader().use { it.readText() }
                    connection.disconnect()

                    // Strip UTF-8 Byte Order Mark if present
                    if (freshJsonStr.startsWith("\uFEFF")) {
                        freshJsonStr = freshJsonStr.substring(1)
                    }
                    freshJsonStr = freshJsonStr.trim()

                    if (freshJsonStr.startsWith("{")) {
                        prefs.edit().putString("theme_json", freshJsonStr).apply()
                        runOnUiThread {
                            try {
                                renderThemeJson(JSONObject(freshJsonStr))
                            } catch (e: Exception) {
                                Log.e("THEME_ENGINE", "Error rendering fresh theme: ${e.message}", e)
                            }
                        }
                    }
                } else {
                    connection.disconnect()
                }
            } catch (e: Exception) {
                Log.d("THEME_ENGINE", "Offline or network timeout; keeping current theme: ${e.message}")
            }
        }
    }

    private fun safeParseColor(colorStr: String): Int? {
        if (colorStr.isEmpty()) return null
        return try {
            val formattedHex = if (colorStr.startsWith("#")) colorStr else "#$colorStr"
            Color.parseColor(formattedHex)
        } catch (e: Exception) {
            Log.e("THEME_ENGINE", "Invalid hex color format: $colorStr", e)
            null
        }
    }

    private fun renderThemeJson(json: JSONObject) {
        // 0. Load Dynamic Splash / Loading Background Graphic & Hide Center Logo on Success
        val splashBgUrl = json.optString("splashBgUrl", "")
        val loadingBgImage = findViewById<ImageView>(R.id.loading_bg_image)
        val loadingLogo = findViewById<ImageView>(R.id.loading_logo)

        if (splashBgUrl.isNotEmpty() && loadingBgImage != null) {
            Glide.with(this@MainActivity)
                .load(splashBgUrl)
                .into(object : CustomTarget<Drawable>() {
                    override fun onResourceReady(
                        resource: Drawable,
                        transition: Transition<in Drawable>?
                    ) {
                        loadingBgImage.setImageDrawable(resource)
                        loadingLogo?.visibility = View.GONE
                    }

                    override fun onLoadCleared(placeholder: Drawable?) {
                        loadingBgImage.setImageDrawable(placeholder)
                    }

                    override fun onLoadFailed(errorDrawable: Drawable?) {
                        super.onLoadFailed(errorDrawable)
                        // Keep logo visible if remote image fails
                        loadingLogo?.visibility = View.VISIBLE
                    }
                })
        }

        // 1. Header Text and Logos Parsing
        headerText = json.optString("headerText", "Chetwynd Public Library")
        logoCplWebUrl = json.optString("logoCplWebUrl", "")
        logoEventsUrl = json.optString("logoEventsUrl", "")
        logoCplWebLink = json.optString("logoCplWebLink", "https://chetwynd.bc.libraries.coop/")
        logoEventsLink = json.optString("logoEventsLink", "https://www.chetwyndeventscalendar.com/")

        val headerTextView = findViewById<TextView>(R.id.header_title)
        headerTextView?.text = headerText

        val logoCplWeb = findViewById<ImageView>(R.id.logo_cpl_web)
        if (logoCplWebUrl.isNotEmpty() && logoCplWeb != null) {
            Glide.with(this@MainActivity)
                .load(logoCplWebUrl)
                .into(logoCplWeb)
        }

        val logoEvents = findViewById<ImageView>(R.id.logo_events_calendar)
        if (logoEventsUrl.isNotEmpty() && logoEvents != null) {
            Glide.with(this@MainActivity)
                .load(logoEventsUrl)
                .into(logoEvents)
        }

        // 2. Header Background Banner / Color Styling
        val headerView = findViewById<View>(R.id.custom_header)
        currentHeaderImgUrl = json.optString("headerImageUrl", "")
        val headerHex = json.optString("headerBgColor", json.optString("headerColor", ""))

        if (currentHeaderImgUrl.isNotEmpty()) {
            Glide.with(this@MainActivity)
                .load(currentHeaderImgUrl)
                .into(object : CustomTarget<Drawable>() {
                    override fun onResourceReady(resource: Drawable, transition: Transition<in Drawable>?) {
                        headerView?.background = resource
                    }
                    override fun onLoadCleared(placeholder: Drawable?) {
                        headerView?.background = placeholder
                    }
                })
        } else {
            val color = safeParseColor(headerHex)
            if (color != null) {
                headerView?.setBackgroundColor(color)
            }
        }

        // 3. Bottom Navigation Banner / Color Styling
        currentNavImgUrl = json.optString("navImageUrl", "")
        val navHex = json.optString("navBgColor", json.optString("navColor", ""))

        if (currentNavImgUrl.isNotEmpty()) {
            Glide.with(this@MainActivity)
                .load(currentNavImgUrl)
                .into(object : CustomTarget<Drawable>() {
                    override fun onResourceReady(resource: Drawable, transition: Transition<in Drawable>?) {
                        navBar.background = resource
                    }
                    override fun onLoadCleared(placeholder: Drawable?) {
                        navBar.background = placeholder
                    }
                })
        } else {
            val color = safeParseColor(navHex)
            if (color != null) {
                val currentBg = navBar.background
                if (currentBg is GradientDrawable) {
                    currentBg.setColor(color)
                } else {
                    navBar.setBackgroundColor(color)
                }
            } else {
                navBar.setBackgroundResource(R.drawable.header_gradient)
            }
        }

        // 4. Parse Form Detection Domains
        val domainArray = json.optJSONArray("formDetectionDomains")
        if (domainArray != null && domainArray.length() > 0) {
            val domains = mutableListOf<String>()
            for (i in 0 until domainArray.length()) {
                val domain = domainArray.optString(i, "").trim()
                if (domain.isNotEmpty()) {
                    domains.add(domain)
                }
            }
            if (domains.isNotEmpty()) {
                formDetectionDomains = domains
            }
        }

        // 5. Parse Dynamic More Menu Items
        val menuArray = json.optJSONArray("moreMenuItems")
        if (menuArray != null && menuArray.length() > 0) {
            val items = mutableListOf<MenuItemData>()
            for (i in 0 until menuArray.length()) {
                val itemObj = menuArray.getJSONObject(i)
                val title = itemObj.optString("title", "")
                val url = itemObj.optString("url", "")
                if (title.isNotEmpty() && url.isNotEmpty()) {
                    items.add(MenuItemData(title, url))
                }
            }
            dynamicMenuItems = items
        }

        // 6. Check Remote Version vs Installed App Version
        checkForAppUpdates(json)

        // 7. Check for Dismissible Announcement Popup
        Handler(Looper.getMainLooper()).postDelayed({
            checkAndShowAnnouncementModal(json)
        }, 5600)
    }

    // ==========================================
    // DYNAMIC ANNOUNCEMENT POPUP MODAL
    // ==========================================

    private fun checkAndShowAnnouncementModal(json: JSONObject) {
        val modalObj = json.optJSONObject("announcementModal") ?: return
        val enabled = modalObj.optBoolean("enabled", false)
        if (!enabled) return

        val announcementId = modalObj.optString("id", "")
        if (announcementId.isEmpty()) return

        // Check if user previously dismissed this specific announcement ID
        val prefs = getSharedPreferences("CPL_ANNOUNCEMENTS", Context.MODE_PRIVATE)
        val isDismissed = prefs.getBoolean("dismissed_$announcementId", false)
        if (isDismissed) return

        val title = modalObj.optString("title", "Notice")
        val message = modalObj.optString("message", "")
        val dismissText = modalObj.optString("dismissButtonText", "Got It")

        runOnUiThread {
            val builder = android.app.AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setCancelable(true)
                .setPositiveButton(dismissText) { dialog, _ ->
                    // Save preference so user isn't shown this announcement again
                    prefs.edit().putBoolean("dismissed_$announcementId", true).apply()
                    dialog.dismiss()
                }

            val dialog = builder.create()
            dialog.show()
        }
    }

    // ==========================================
    // REMOTE VERSION CHECKING & UPDATES
    // ==========================================

    private fun getInstalledVersionCode(): Long {
        return try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                pInfo.versionCode.toLong()
            }
        } catch (e: Exception) {
            1L
        }
    }

    private fun checkForAppUpdates(json: JSONObject) {
        val latestVersionCode = json.optLong("latestVersionCode", 0)
        val currentVersionCode = getInstalledVersionCode()

        if (latestVersionCode > currentVersionCode) {
            val updateUrl = json.optString(
                "updateUrl",
                "https://play.google.com/store/apps/details?id=$packageName"
            )
            val forceUpdate = json.optBoolean("forceUpdate", false)

            showUpdateDialog(updateUrl, forceUpdate)
        }
    }

    private fun showUpdateDialog(updateUrl: String, forceUpdate: Boolean) {
        val builder = android.app.AlertDialog.Builder(this)
            .setTitle("Update Available")
            .setMessage("A new version of the Chetwynd Public Library app is available. Please update to access the latest features and fixes.")
            .setPositiveButton("Update Now") { _, _ ->
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(updateUrl)))
                } catch (e: Exception) {
                    Toast.makeText(this, "Unable to open update link.", Toast.LENGTH_SHORT).show()
                }
            }
            .setCancelable(!forceUpdate)

        if (!forceUpdate) {
            builder.setNegativeButton("Later", null)
        }

        val dialog = builder.create()
        dialog.show()
    }

    // ==========================================
    // DYNAMIC LAYOUT MANAGEMENT
    // ==========================================

    private fun updateHeaderVisibility(url: String?) {
        val header = findViewById<View>(R.id.custom_header) ?: return
        val params = webView.layoutParams as ConstraintLayout.LayoutParams

        val isProgramGuide = url != null && (url.contains(guidePath) || url.endsWith(".pdf"))

        if (isProgramGuide) {
            header.visibility = View.GONE

            var statusBarHeight = 0
            val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
            if (resourceId > 0) {
                statusBarHeight = resources.getDimensionPixelSize(resourceId)
            }
            if (statusBarHeight == 0) {
                statusBarHeight = (resources.displayMetrics.density * 24).toInt() // Fallback
            }
            params.topMargin = statusBarHeight
        } else {
            header.visibility = View.VISIBLE
            params.topMargin = 0
        }

        webView.layoutParams = params
    }

    private fun getDefaultMenuItems(): List<MenuItemData> {
        return listOf(
            MenuItemData("Online Store", "https://chetwynd-public-library.square.site/"),
            MenuItemData("Program Guide", "pdf_viewer"),
            MenuItemData("Children's Programs", "$rootUrl/cplchildrensprograms"),
            MenuItemData("Family Events", "$rootUrl/cplfamilyevents"),
            MenuItemData("Teen Events", "$rootUrl/cplteenevents"),
            MenuItemData("Pre-Teen Events", "$rootUrl/cplpreteenevents"),
            MenuItemData("Creative Journeys", "$rootUrl/cplcreativejourneys"),
            MenuItemData("STEM Programs", "$rootUrl/cplstemprograms"),
            MenuItemData("Print Job Upload", "https://www.chetwyndeventscalendar.com/printing")
        )
    }

    private fun showMoreMenu(anchorView: View) {
        val menuList = if (dynamicMenuItems.isNotEmpty()) dynamicMenuItems else getDefaultMenuItems()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 24)

            if (currentNavImgUrl.isNotEmpty()) {
                Glide.with(this@MainActivity)
                    .load(currentNavImgUrl)
                    .into(object : CustomTarget<Drawable>() {
                        override fun onResourceReady(resource: Drawable, transition: Transition<in Drawable>?) {
                            background = resource
                        }
                        override fun onLoadCleared(placeholder: Drawable?) {
                            background = placeholder
                        }
                    })
            } else {
                val navBg = navBar.background
                if (navBg != null) {
                    background = navBg.constantState?.newDrawable()?.mutate() ?: navBg
                } else {
                    setBackgroundResource(R.drawable.rounded_nav_bg)
                }
            }
        }

        val popupWindow = PopupWindow(
            container,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                elevation = 16f
            }
            isClippingEnabled = false
        }

        menuList.forEachIndexed { index, menuItem ->
            val textView = TextView(this).apply {
                text = menuItem.title
                setTextColor(Color.WHITE)
                textSize = 18f
                setPadding(28, 24, 28, 24)
                gravity = Gravity.CENTER_VERTICAL
                background = ContextCompat.getDrawable(context, android.R.drawable.list_selector_background)

                setOnClickListener {
                    popupWindow.dismiss()
                    when {
                        menuItem.url == "pdf_viewer" -> {
                            startActivity(Intent(this@MainActivity, PdfViewerActivity::class.java))
                        }
                        menuItem.url.contains("/printing") -> {
                            startActivity(Intent(Intent.ACTION_VIEW, menuItem.url.toUri()))
                        }
                        else -> {
                            showLoadingState()
                            webView.loadUrl(menuItem.url)
                        }
                    }
                }
            }
            container.addView(textView)

            if (index < menuList.size - 1) {
                val divider = View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        2
                    ).apply {
                        setMargins(12, 4, 12, 4)
                    }
                    setBackgroundColor(Color.parseColor("#40FFFFFF"))
                }
                container.addView(divider)
            }
        }

        container.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val navBarLocation = IntArray(2)
        navBar.getLocationOnScreen(navBarLocation)

        val absoluteY = navBarLocation[1] - container.measuredHeight - 24
        popupWindow.showAtLocation(anchorView.rootView, Gravity.CENTER_HORIZONTAL or Gravity.TOP, 0, absoluteY)
    }

    private fun forceUpdateNavLabels() {
        val menuView = navBar.getChildAt(0) as? ViewGroup ?: return
        menuView.clipChildren = false
        menuView.clipToPadding = false
        recursiveSetMaxLines(menuView)
    }

    private fun recursiveSetMaxLines(view: View) {
        if (view is TextView) {
            view.isSingleLine = false
            view.maxLines = 2
            view.setLines(2)
            view.ellipsize = null
            view.gravity = Gravity.CENTER
            view.textAlignment = View.TEXT_ALIGNMENT_CENTER
            view.setLineSpacing(1f, 1f)

            view.layoutParams?.let {
                it.height = ViewGroup.LayoutParams.WRAP_CONTENT
                view.layoutParams = it
            }
        } else if (view is ViewGroup) {
            view.clipChildren = false
            view.clipToPadding = false
            for (i in 0 until view.childCount) {
                recursiveSetMaxLines(view.getChildAt(i))
            }
        }
    }

    // ==========================================
    // WEBVIEW CONFIGURATION
    // ==========================================

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            domStorageEnabled = true
            allowFileAccess = true
        }

        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun onAutofillTriggered() {
                runOnUiThread { Toast.makeText(this@MainActivity, "Autofilling profile info...", Toast.LENGTH_SHORT).show() }
            }
            @JavascriptInterface
            fun onTargetDetected(label: String) {
                runOnUiThread { isFormVisible = true }
            }
        }, "FormDetector")

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams
            ): Boolean {
                val intent = fileChooserParams.createIntent()
                try {
                    filePickerLauncher.launch(intent)
                    pendingFilePathCallback = filePathCallback
                } catch (e: Exception) {
                    return false
                }
                return true
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                isFormVisible = false

                if (isOffline) {
                    isOffline = false
                    navBar.alpha = 1.0f
                }

                showLoadingState()
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)

                if (request?.isForMainFrame == true) {
                    hideLoadingState()

                    isOffline = true
                    navBar.alpha = 0.4f

                    val failedUrl = request.url.toString()
                    val offlineHtml = """
                        <!DOCTYPE html>
                        <html>
                        <head>
                            <meta name="viewport" content="width=device-width, initial-scale=1.0">
                            <style>
                                body {
                                    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
                                    background-color: #F4F6F9;
                                    color: #333333;
                                    display: flex;
                                    justify-content: center;
                                    align-items: center;
                                    height: 100vh;
                                    margin: 0;
                                    padding: 20px;
                                    box-sizing: border-box;
                                }
                                .card {
                                    background: #ffffff;
                                    border-radius: 16px;
                                    padding: 32px 24px;
                                    text-align: center;
                                    box-shadow: 0 4px 16px rgba(0, 0, 0, 0.08);
                                    max-width: 320px;
                                    width: 100%;
                                }
                                .icon {
                                    font-size: 52px;
                                    margin-bottom: 16px;
                                }
                                h2 {
                                    color: #1B365D;
                                    margin: 0 0 12px 0;
                                    font-size: 20px;
                                }
                                p {
                                    color: #666666;
                                    font-size: 14px;
                                    line-height: 1.5;
                                    margin: 0 0 24px 0;
                                }
                                .btn {
                                    background-color: #1B365D;
                                    color: #ffffff;
                                    border: none;
                                    padding: 12px 28px;
                                    font-size: 15px;
                                    font-weight: 600;
                                    border-radius: 24px;
                                    cursor: pointer;
                                    width: 100%;
                                    box-sizing: border-box;
                                }
                                .btn:active {
                                    opacity: 0.85;
                                }
                            </style>
                        </head>
                        <body>
                            <div class="card">
                                <div class="icon">📡</div>
                                <h2>No Internet Connection</h2>
                                <p>Please check your connection or turn off Airplane Mode to access online library features.</p>
                                <button class="btn" onclick="window.location.href='$failedUrl'">Try Again</button>
                            </div>
                        </body>
                        </html>
                    """.trimIndent()

                    view?.loadDataWithBaseURL(null, offlineHtml, "text/html", "UTF-8", null)
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                hideLoadingState()
                updateHeaderVisibility(url)

                view?.postDelayed({ view.scrollTo(0, 0) }, 100)

                val newTabId = when {
                    url == null -> R.id.nav_home
                    url.contains(opacUrl) -> R.id.nav_search
                    url.contains(guidePath) -> ID_NAV_GUIDE
                    url.contains("chetwynd.bc.libraries.coop") -> R.id.nav_web
                    url.contains("copy-of-") || url.contains("my-story") -> R.id.nav_more
                    url.startsWith(rootUrl) -> R.id.nav_home
                    else -> lastSelectedTabId
                }

                if (newTabId != navBar.selectedItemId) {
                    isRestoringState = true
                    navBar.selectedItemId = newTabId
                    lastSelectedTabId = newTabId
                    isRestoringState = false
                    navBar.post { forceUpdateNavLabels() }
                }

                val isZoomable = url?.contains(guidePath) == true || url?.contains(opacUrl) == true
                view?.settings?.apply {
                    setSupportZoom(isZoomable)
                    builtInZoomControls = isZoomable
                    displayZoomControls = false
                }

                val isFormUrl = url != null && formDetectionDomains.any { domain -> url.contains(domain) }

                if (isFormUrl) {
                    val detectJs = """
                        (function() {
                            var detectedEmail = false;
                            function checkAndNotify() {
                                if (detectedEmail) return;
                                var pageText = document.body.innerText;
                                
                                if (pageText.indexOf("Start now") !== -1) return;
                                
                                if (pageText.toLowerCase().indexOf("email address") !== -1) {
                                    if (window.FormDetector) {
                                        window.FormDetector.onTargetDetected("Email Address");
                                        detectedEmail = true;
                                    }
                                }
                            }
                            var observer = new MutationObserver(checkAndNotify);
                            observer.observe(document.body, { childList: true, subtree: true });
                            checkAndNotify();
                        })();
                    """.trimIndent()

                    view?.evaluateJavascript(detectJs, null)
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url.toString()

                if (url.contains("/printing")) {
                    startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
                    return true
                }

                // Allow web pages & forms to process directly inside WebView
                return false
            }
        }
        webView.loadUrl(rootUrl)
    }

    // ==========================================
    // LIBRARY CARD MANAGEMENT (BOTTOM SHEET)
    // ==========================================

    private fun showLibraryCardsDialog() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.dialog_library_card, null)

        // Set a clean, neutral light background
        view.setBackgroundColor(Color.parseColor("#F8F9FA"))

        val cardContainer = view.findViewById<LinearLayout>(R.id.card_list_container)
        val addCardBtn = view.findViewById<Button>(R.id.add_card_button)
        val closeBtn = view.findViewById<Button>(R.id.close_button)

        val cards = getSavedCards()

        if (cards.isEmpty()) {
            cardContainer.addView(TextView(this).apply {
                text = "No cards saved yet."
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#495057"))
                textSize = 16f
                setPadding(0, 50, 0, 50)
            })
        } else {
            cards.forEachIndexed { index, card ->
                val cardView = layoutInflater.inflate(R.layout.item_card_row, null)

                cardView.findViewById<TextView>(R.id.row_card_name).text = card.name
                cardView.findViewById<TextView>(R.id.row_card_number).text = card.number
                generateBarcode(card.number)?.let { cardView.findViewById<ImageView>(R.id.row_barcode_image).setImageBitmap(it) }

                cardView.findViewById<TextView>(R.id.btn_delete_card).setOnClickListener {
                    removeCardAt(index)
                    dialog.dismiss()
                    showLibraryCardsDialog()
                }

                cardView.findViewById<TextView>(R.id.btn_edit_card).setOnClickListener {
                    showEditCardInput(card, index, dialog)
                }

                val fillBtn = cardView.findViewById<TextView>(R.id.btn_fill_form)
                if (isFormVisible) {
                    fillBtn.visibility = View.VISIBLE
                    fillBtn.setOnClickListener {
                        executeAutofill(card)
                        dialog.dismiss()
                    }
                }

                cardView.setOnLongClickListener {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val digitsOnly = card.number.removePrefix("CHEP")
                    val clip = ClipData.newPlainText("Library Card Number", digitsOnly)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(this, "Digits copied: $digitsOnly", Toast.LENGTH_SHORT).show()
                    true
                }
                cardContainer.addView(cardView)
            }
        }

        addCardBtn.visibility = if (cards.size >= 10) View.GONE else View.VISIBLE
        addCardBtn.setOnClickListener { showAddCardInput(dialog) }
        closeBtn.setOnClickListener { dialog.dismiss() }

        dialog.setContentView(view)

        dialog.behavior.apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
            peekHeight = (resources.displayMetrics.heightPixels * 0.9).toInt()
        }

        dialog.setOnDismissListener {
            if (navBar.selectedItemId != lastSelectedTabId) {
                isRestoringState = true
                navBar.selectedItemId = lastSelectedTabId
                isRestoringState = false
                navBar.post { forceUpdateNavLabels() }
            }
        }

        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.90).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun executeAutofill(card: LibraryCard) {
        val digitsOnly = card.number.replace("CHEP", "")
        val js = """
            (function() {
                var data = {
                    name: "${card.name}",
                    phone: "${card.phone}",
                    email: "${card.email}",
                    card: "$digitsOnly"
                };
                var allInputs = Array.from(document.querySelectorAll('input, textarea'));
                var visibleInputs = allInputs.filter(function(el) {
                    var style = window.getComputedStyle(el);
                    var type = el.getAttribute('type') || 'text';
                    return style.display !== 'none' && style.visibility !== 'hidden' && type !== 'hidden' && type !== 'checkbox' && type !== 'radio';
                });
                if (visibleInputs.length > 0) {
                    visibleInputs.forEach(function(input, index) {
                        var valueToSet = "";
                        if (index === 0) valueToSet = data.name;
                        else if (index === 1) valueToSet = data.email;
                        else if (index === 2) valueToSet = data.phone;
                        else if (index === 3) valueToSet = data.card;
                        if (valueToSet) {
                            input.value = valueToSet;
                            input.setAttribute('value', valueToSet);
                            ['input', 'change', 'blur', 'focus'].forEach(function(name) {
                                input.dispatchEvent(new Event(name, { bubbles: true }));
                            });
                        }
                    });
                    if (window.FormDetector) { window.FormDetector.onAutofillTriggered(); }
                }
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    // ==========================================
    // LIBRARY CARD CREATION & EDITING
    // ==========================================

    private fun showAddCardInput(parentDialog: BottomSheetDialog) {
        val (scrollView, inputs) = createCardInputForm(null)

        android.app.AlertDialog.Builder(this)
            .setTitle("Add Library Card")
            .setView(scrollView)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .create()
            .apply {
                show()
                getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    processCardSubmission(inputs, null, this, parentDialog)
                }
            }
    }

    private fun showEditCardInput(card: LibraryCard, index: Int, parentDialog: BottomSheetDialog) {
        val (scrollView, inputs) = createCardInputForm(card)

        android.app.AlertDialog.Builder(this)
            .setTitle("Edit Library Card")
            .setView(scrollView)
            .setPositiveButton("Update", null)
            .setNegativeButton("Cancel", null)
            .create()
            .apply {
                show()
                getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    processCardSubmission(inputs, index, this, parentDialog)
                }
            }
    }

    private fun createCardInputForm(card: LibraryCard?): Pair<ScrollView, Map<String, EditText>> {
        val scrollView = ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 40, 60, 10)
        }

        val nameInput = EditText(this).apply {
            hint = "Full Name"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            card?.let { setText(it.name) }
        }
        val emailInput = EditText(this).apply {
            hint = "Email Address"
            inputType = InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            card?.let { setText(it.email) }
        }
        val phoneInput = EditText(this).apply {
            hint = "Phone Number"
            inputType = InputType.TYPE_CLASS_PHONE
            card?.let { setText(it.phone) }
        }

        val prefix = "CHEP"
        val numberInput = EditText(this).apply {
            setText(card?.number ?: prefix)
            setSelection(text.length)
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(9))

            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (s == null) return
                    if (!s.toString().startsWith(prefix)) {
                        setText(prefix)
                        setSelection(prefix.length)
                    }
                }
            })
        }

        layout.addView(nameInput)
        layout.addView(emailInput)
        layout.addView(phoneInput)
        layout.addView(TextView(this).apply { text = "\nBarcode Number (5 digits after CHEP)" })
        layout.addView(numberInput)
        scrollView.addView(layout)

        val inputs = mapOf(
            "name" to nameInput,
            "email" to emailInput,
            "phone" to phoneInput,
            "number" to numberInput
        )
        return Pair(scrollView, inputs)
    }

    private fun processCardSubmission(
        inputs: Map<String, EditText>,
        editIndex: Int?,
        alertDialog: android.app.AlertDialog,
        parentDialog: BottomSheetDialog
    ) {
        val name = inputs["name"]?.text.toString().trim()
        val email = inputs["email"]?.text.toString().trim()
        val phone = inputs["phone"]?.text.toString().trim()
        val number = inputs["number"]?.text.toString().trim()

        if (name.isEmpty()) {
            inputs["name"]?.error = "Name is required"
        } else if (number.length != 9) {
            inputs["number"]?.error = "Must be CHEP followed by exactly 5 digits"
        } else {
            val currentCards = getSavedCards()
            val newCard = LibraryCard(name, number, email, phone)

            if (editIndex != null && editIndex in currentCards.indices) {
                currentCards[editIndex] = newCard
            } else {
                currentCards.add(newCard)
            }

            saveAllCards(currentCards)
            alertDialog.dismiss()
            parentDialog.dismiss()
            showLibraryCardsDialog()
        }
    }

    private fun generateBarcode(content: String): Bitmap? {
        return try {
            val hints = EnumMap<com.google.zxing.EncodeHintType, Any>(com.google.zxing.EncodeHintType::class.java)
            hints[com.google.zxing.EncodeHintType.MARGIN] = 0
            hints[com.google.zxing.EncodeHintType.CHARACTER_SET] = "UTF-8"

            val matrix = MultiFormatWriter().encode(content, BarcodeFormat.CODE_39, 300, 100, hints)
            val baseBitmap = Bitmap.createBitmap(300, 100, Bitmap.Config.ARGB_8888)
            for (x in 0 until 300) {
                for (y in 0 until 100) {
                    baseBitmap.setPixel(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
                }
            }
            val sharpScaledBitmap = Bitmap.createScaledBitmap(baseBitmap, 900, 300, false)
            baseBitmap.recycle()
            sharpScaledBitmap
        } catch (e: Exception) {
            Log.e("BARCODE_ERR", "Failed to render matrix", e)
            null
        }
    }

    // ==========================================
    // SHARED PREFERENCES / DATA STORAGE
    // ==========================================

    private fun getSavedCards(): MutableList<LibraryCard> {
        val prefs = getSharedPreferences("CPL_CARDS", Context.MODE_PRIVATE)
        val json = prefs.getString("saved_cards", null) ?: return mutableListOf()
        val type = object : TypeToken<MutableList<LibraryCard>>() {}.type
        return Gson().fromJson(json, type)
    }

    private fun saveAllCards(cards: List<LibraryCard>) {
        val prefs = getSharedPreferences("CPL_CARDS", Context.MODE_PRIVATE)
        val json = Gson().toJson(cards)
        prefs.edit().putString("saved_cards", json).apply()
    }

    private fun removeCardAt(index: Int) {
        val cards = getSavedCards()
        if (index in cards.indices) {
            cards.removeAt(index)
            saveAllCards(cards)
            Toast.makeText(this, "Card removed", Toast.LENGTH_SHORT).show()
        }
    }

    // ==========================================
    // SYSTEM AND PERMISSIONS
    // ==========================================

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun getFirebaseToken() {
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (!task.isSuccessful) {
                Log.w("MainActivity", "Fetching FCM registration token failed", task.exception)
                return@addOnCompleteListener
            }
            Log.d("MainActivity", "Current FCM Token: ${task.result}")
        }
    }

    // ==========================================
    // CUSTOM NAVIGATION ROUTING & EXIT
    // ==========================================

    private fun handleBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val currentUrl = webView.url ?: ""

                val isHome = currentUrl == rootUrl ||
                        currentUrl == "$rootUrl/" ||
                        currentUrl == "https://www.chetwyndeventscalendar.com/"

                val isTopLevelSection = currentUrl.contains(opacUrl) ||
                        currentUrl.contains("chetwynd.bc.libraries.coop") ||
                        currentUrl.contains(guidePath) ||
                        currentUrl.endsWith("cplchildrensprograms") ||
                        currentUrl.endsWith("cplfamilyevents") ||
                        currentUrl.endsWith("cplteenevents") ||
                        currentUrl.endsWith("cplpreteenevents") ||
                        currentUrl.endsWith("cplcreativejourneys") ||
                        currentUrl.endsWith("cplstemprograms")

                if (isHome) {
                    if (doubleBackToExitPressedOnce) {
                        // Fully close activity and remove task from recent apps list
                        finishAndRemoveTask()
                        return
                    }
                    doubleBackToExitPressedOnce = true
                    Toast.makeText(this@MainActivity, "Press back again to exit", Toast.LENGTH_SHORT).show()
                    backPressHandler.postDelayed(backPressResetRunnable, 2000)
                }
                else if (isTopLevelSection) {
                    webView.clearHistory()
                    webView.loadUrl(rootUrl)
                }
                else {
                    if (webView.canGoBack()) {
                        webView.goBack()
                    } else {
                        webView.loadUrl(rootUrl)
                    }
                }
            }
        })
    }
}