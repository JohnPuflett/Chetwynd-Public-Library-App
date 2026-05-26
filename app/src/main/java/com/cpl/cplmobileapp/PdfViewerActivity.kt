package com.cpl.cplmobileapp

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class PdfViewerActivity : AppCompatActivity() {

    private lateinit var viewPager: ViewPager2
    private lateinit var pageIndicator: TextView
    private lateinit var progressBar: ProgressBar

    private var pdfRenderer: PdfRenderer? = null
    private var fileDescriptor: ParcelFileDescriptor? = null
    private var cachedPdfFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. Force status bar background control
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)

        // 2. Set status bar background color to match your dark layout surface (#121212)
        window.statusBarColor = android.graphics.Color.parseColor("#121212")

        // 3. Use Jetpack WindowCompat to force system icons (Clock, Battery) to stay light/white
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController?.let {
            it.isAppearanceLightStatusBars = false
        }

        setContentView(R.layout.activity_pdf_viewer)

        // Initialize view bindings
        viewPager = findViewById(R.id.pdf_view_pager)
        pageIndicator = findViewById(R.id.txt_page_indicator)
        progressBar = findViewById(R.id.pdf_progress)

        // FIX: Dynamically apply padding so layout contents never clip behind system bars
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            // Apply the system bar sizes as padding to the root content view box
            findViewById<View>(android.R.id.content).setPadding(
                systemBars.left,
                systemBars.top,     // Pushes close button cleanly below the clock/status bar
                systemBars.right,
                systemBars.bottom   // Pushes nav buttons cleanly above the Android back/home keys
            )
            WindowInsetsCompat.CONSUMED
        }

        // Header Close Button
        findViewById<TextView>(R.id.btn_back).setOnClickListener { finish() }

        // Footer Navigation Arrow Click Listeners
        findViewById<TextView>(R.id.btn_pdf_prev).setOnClickListener {
            val currentItem = viewPager.currentItem
            if (currentItem > 0) {
                viewPager.setCurrentItem(currentItem - 1, true)
            }
        }

        findViewById<TextView>(R.id.btn_pdf_next).setOnClickListener {
            val currentItem = viewPager.currentItem
            val totalItems = viewPager.adapter?.itemCount ?: 0
            if (currentItem < totalItems - 1) {
                viewPager.setCurrentItem(currentItem + 1, true)
            }
        }

        // Apply 3D page rotation transformation layers
        viewPager.setPageTransformer(BookFlipPageTransformer())

        // Launch the localized caching & engine manager
        loadAndCachePdf("https://chetwynd.bc.libraries.coop/files/2026/05/Program-Guide_merged.pdf")
    }

    private fun loadAndCachePdf(urlString: String) {
        cachedPdfFile = File(filesDir, "cached_program_guide.pdf")

        // 1. IMMEDIATE LOCAL LOAD: If the file exists, initialize it instantly
        if (cachedPdfFile?.exists() == true) {
            initializePdfRenderer(cachedPdfFile!!)
        }

        // 2. BACKGROUND SYNC: Quietly fetch network copy to verify/update file
        thread {
            try {
                val url = URL(urlString)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 5000
                connection.connect()

                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val tempFile = File(cacheDir, "download_stage.pdf")
                    connection.inputStream.use { inputStream ->
                        FileOutputStream(tempFile).use { outputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }

                    if (tempFile.exists() && tempFile.length() > 0) {
                        tempFile.copyTo(cachedPdfFile!!, overwrite = true)
                        tempFile.delete()

                        if (pdfRenderer == null) {
                            runOnUiThread {
                                initializePdfRenderer(cachedPdfFile!!)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    if (pdfRenderer == null) {
                        progressBar.visibility = View.GONE
                        Toast.makeText(this@PdfViewerActivity, "Offline: No cached guide found.", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun initializePdfRenderer(file: File) {
        try {
            pdfRenderer?.close()
            fileDescriptor?.close()

            fileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            fileDescriptor?.let {
                pdfRenderer = PdfRenderer(it)
            }

            pdfRenderer?.let { renderer ->
                progressBar.visibility = View.GONE
                val adapter = PdfPageAdapter(renderer, viewPager)
                viewPager.adapter = adapter

                pageIndicator.text = "Page ${viewPager.currentItem + 1} of ${renderer.pageCount}"

                viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                    override fun onPageSelected(position: Int) {
                        super.onPageSelected(position)
                        pageIndicator.text = "Page ${position + 1} of ${renderer.pageCount}"

                        val recyclerView = viewPager.getChildAt(0) as? RecyclerView
                        recyclerView?.let { rv ->
                            (rv.findViewHolderForAdapterPosition(position - 1) as? PdfPageAdapter.PageViewHolder)?.imgSurface?.resetZoom()
                            (rv.findViewHolderForAdapterPosition(position + 1) as? PdfPageAdapter.PageViewHolder)?.imgSurface?.resetZoom()
                        }
                    }
                })
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        try {
            pdfRenderer?.close()
            fileDescriptor?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        super.onDestroy()
    }

    // --- High Performance Background Core Page Renderer Adapter ---
    class PdfPageAdapter(
        private val renderer: PdfRenderer,
        private val viewPager: ViewPager2
    ) : RecyclerView.Adapter<PdfPageAdapter.PageViewHolder>() {

        class PageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val imgSurface: ZoomableImageView = view.findViewById(R.id.img_page_surface)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_pdf_page, parent, false)
            return PageViewHolder(view)
        }

        override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
            holder.imgSurface.resetZoom()
            holder.imgSurface.parentViewPager = viewPager

            val page = renderer.openPage(position)

            val targetWidth = page.width * 3
            val targetHeight = page.height * 3

            val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

            holder.imgSurface.setImageBitmap(bitmap)
            page.close()
        }

        override fun getItemCount(): Int = renderer.pageCount
    }

    // --- Custom Hardware-Accelerated 3D Book Flip Animation Transformer ---
    class BookFlipPageTransformer : ViewPager2.PageTransformer {
        override fun transformPage(page: View, position: Float) {
            when {
                position < -1 -> {
                    page.alpha = 0f
                }
                position <= 0 -> {
                    page.alpha = 1f
                    page.translationX = -position * page.width
                    page.rotationY = 180f * position
                    page.pivotX = 0f
                    page.pivotY = page.height / 2f
                }
                position <= 1 -> {
                    page.alpha = 1f
                    page.translationX = 0f
                    page.rotationY = 0f
                    page.pivotX = 0f
                    page.pivotY = page.height / 2f
                }
                else -> {
                    page.alpha = 0f
                }
            }
        }
    }
}