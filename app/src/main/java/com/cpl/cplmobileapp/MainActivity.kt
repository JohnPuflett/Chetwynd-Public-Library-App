package com.cpl.cplmobileapp

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
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
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.firebase.messaging.FirebaseMessaging
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import java.util.EnumMap

// --- Data Models ---
data class LibraryCard(val name: String, val number: String, val email: String = "", val phone: String = "")

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
    private val cplWebUrl = "https://www.chetwyndpubliclibrary.ca"

    // --- State Management ---
    private var doubleBackToExitPressedOnce = false
    private val backPressHandler = Handler(Looper.getMainLooper())
    private val backPressResetRunnable = Runnable { doubleBackToExitPressedOnce = false }
    private var lastSelectedTabId = R.id.nav_home
    private var isRestoringState = false
    private var isFormVisible = false

    // Unique IDs for custom dynamically injected menu items
    private val ID_NAV_GUIDE = 1001

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
        super.onCreate(savedInstanceState)

        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        setContentView(R.layout.activity_main)

        bindViews()
        configureHeader()
        setupNavigationBar()
        setupWebView()

        askNotificationPermission()
        getFirebaseToken()
        handleBackNavigation()
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

        logoCplWeb.setOnClickListener {
            showLoadingState()
            webView.loadUrl(cplWebUrl)
        }
        logoEvents.setOnClickListener {
            showLoadingState()
            webView.loadUrl("https://www.chetwyndeventscalendar.com/")
        }
    }

    private fun setupNavigationBar() {
        navBar.labelVisibilityMode = BottomNavigationView.LABEL_VISIBILITY_LABELED
        navBar.itemIconTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
        navBar.itemTextColor = android.content.res.ColorStateList.valueOf(Color.WHITE)
        navBar.background = null
        navBar.setBackgroundResource(R.drawable.rounded_nav_bg)

        navBar.setOnItemSelectedListener { item ->
            navBar.post { forceUpdateNavLabels() }

            if (isRestoringState) return@setOnItemSelectedListener true
            if (progressBar.visibility == View.VISIBLE) return@setOnItemSelectedListener false

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

        navBar.setOnItemReselectedListener { item ->
            if (progressBar.visibility == View.VISIBLE) return@setOnItemReselectedListener
            when (item.itemId) {
                R.id.nav_home -> { showLoadingState(); webView.loadUrl(rootUrl) }
                R.id.nav_search -> { showLoadingState(); webView.loadUrl(opacUrl) }
                R.id.nav_cards -> showLibraryCardsDialog()
                R.id.nav_web -> { showLoadingState(); webView.loadUrl(cplWebUrl) }
                R.id.nav_more -> showMoreMenu(navBar.findViewById(R.id.nav_more))
            }
        }

        navBar.selectedItemId = R.id.nav_home
        navBar.post { forceUpdateNavLabels() }
    }

    private fun showLoadingState() {
        progressBar.visibility = View.VISIBLE
        overlay.visibility = View.VISIBLE
        navBar.isEnabled = false
    }

    private fun hideLoadingState() {
        progressBar.visibility = View.GONE
        overlay.visibility = View.GONE
        navBar.isEnabled = true
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

    private fun showMoreMenu(anchorView: View) {
        val popupView = layoutInflater.inflate(R.layout.popup_more_menu, null)

        val popupWindow = PopupWindow(
            popupView,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        )

        popupWindow.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            popupWindow.elevation = 16f
        }
        popupWindow.isClippingEnabled = false

        popupView.findViewById<TextView>(R.id.menu_guide).setOnClickListener {
            popupWindow.dismiss()
            startActivity(Intent(this, PdfViewerActivity::class.java))
        }

        val links = mapOf(
            R.id.menu_children to "cplchildrensprograms",
            R.id.menu_family to "cplfamilyevents",
            R.id.menu_teen to "cplteenevents",
            R.id.menu_pre_teen to "cplpreteenevents",
            R.id.menu_creative to "cplcreativejourneys",
            R.id.menu_stem to "cplstemprograms"
        )

        links.forEach { (id, path) ->
            popupView.findViewById<TextView>(id).setOnClickListener {
                popupWindow.dismiss()
                showLoadingState()
                webView.loadUrl("$rootUrl/$path")
            }
        }

        popupView.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val navBarLocation = IntArray(2)
        navBar.getLocationOnScreen(navBarLocation)

        val absoluteY = navBarLocation[1] - popupView.measuredHeight - 24
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

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                isFormVisible = false
                showLoadingState()
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
                    url.contains("chetwyndpubliclibrary.ca") -> R.id.nav_web
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

                if (url?.contains("forms.office.com") == true || url?.contains("forms.microsoft.com") == true) {
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

            override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                super.onReceivedError(view, errorCode, description, failingUrl)
                hideLoadingState()
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url.toString()
                if (url.contains("/printing")) {
                    startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
                    return true
                }
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

        val cardContainer = view.findViewById<LinearLayout>(R.id.card_list_container)
        val addCardBtn = view.findViewById<Button>(R.id.add_card_button)
        val closeBtn = view.findViewById<Button>(R.id.close_button)

        val cards = getSavedCards()

        if (cards.isEmpty()) {
            cardContainer.addView(TextView(this).apply {
                text = "No cards saved yet."
                gravity = Gravity.CENTER
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
    // CUSTOM NAVIGATION ROUTING
    // ==========================================

    private fun handleBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val currentUrl = webView.url ?: ""

                // 1. Identify if we are on the main landing page
                val isHome = currentUrl == rootUrl ||
                        currentUrl == "$rootUrl/" ||
                        currentUrl == "https://www.chetwyndeventscalendar.com/"

                // 2. Identify if we are on a Top-Level section (OPAC, main CPL site, or a primary category)
                val isTopLevelSection = currentUrl.contains(opacUrl) ||
                        currentUrl.contains("chetwyndpubliclibrary.ca") ||
                        currentUrl.contains(guidePath) ||
                        currentUrl.endsWith("cplchildrensprograms") ||
                        currentUrl.endsWith("cplfamilyevents") ||
                        currentUrl.endsWith("cplteenevents") ||
                        currentUrl.endsWith("cplpreteenevents") ||
                        currentUrl.endsWith("cplcreativejourneys") ||
                        currentUrl.endsWith("cplstemprograms")

                if (isHome) {
                    // Trigger exit flow if already at the base of the app
                    if (doubleBackToExitPressedOnce) {
                        finish()
                        return
                    }
                    doubleBackToExitPressedOnce = true
                    Toast.makeText(this@MainActivity, "Press back again to exit", Toast.LENGTH_SHORT).show()
                    backPressHandler.postDelayed(backPressResetRunnable, 2000)
                }
                else if (isTopLevelSection) {
                    // Hierarchical Up-Navigation: Force return to the main landing page
                    webView.clearHistory() // Clear browser history to prevent tangled forward/back states
                    webView.loadUrl(rootUrl)
                }
                else {
                    // We are deep in a form, document, or external link. Step back exactly one level.
                    if (webView.canGoBack()) {
                        webView.goBack()
                    } else {
                        // Safety fallback to Home
                        webView.loadUrl(rootUrl)
                    }
                }
            }
        })
    }
}