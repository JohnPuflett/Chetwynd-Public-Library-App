package com.cpl.cplmobileapp

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
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

        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.statusBarColor = android.graphics.Color.parseColor("#121212")

        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController?.isAppearanceLightStatusBars = false

        setContentView(R.layout.activity_pdf_viewer)

        viewPager = findViewById(R.id.pdf_view_pager)
        pageIndicator = findViewById(R.id.txt_page_indicator)
        progressBar = findViewById(R.id.pdf_progress)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            findViewById<View>(android.R.id.content).setPadding(
                systemBars.left,
                systemBars.top,
                systemBars.right,
                systemBars.bottom
            )
            WindowInsetsCompat.CONSUMED
        }

        findViewById<TextView>(R.id.btn_back).setOnClickListener { finish() }

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

        // Clean Material Depth transition
        viewPager.setPageTransformer(DepthPageTransformer())

        loadAndCachePdf("https://chetwynd.bc.libraries.coop/files/2026/05/Program-Guide_merged.pdf")
    }

    private fun loadAndCachePdf(urlString: String) {
        cachedPdfFile = File(filesDir, "cached_program_guide.pdf")

        if (cachedPdfFile?.exists() == true) {
            initializePdfRenderer(cachedPdfFile!!)
        }

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
                Log.e("PDF_VIEWER", "Download failed", e)
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
                    }
                })
            }
        } catch (e: Exception) {
            Log.e("PDF_VIEWER", "PdfRenderer init error", e)
        }
    }

    override fun onDestroy() {
        try {
            pdfRenderer?.close()
            fileDescriptor?.close()
        } catch (e: Exception) {
            Log.e("PDF_VIEWER", "Cleanup error", e)
        }
        super.onDestroy()
    }

    class PdfPageAdapter(
        private val renderer: PdfRenderer,
        private val viewPager: ViewPager2
    ) : RecyclerView.Adapter<PdfPageAdapter.PageViewHolder>() {

        class PageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val imgSurface: ImageView = view.findViewById(R.id.img_page_surface)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_pdf_page, parent, false)
            return PageViewHolder(view)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
            val imageView = holder.imgSurface
            imageView.scaleType = ImageView.ScaleType.FIT_CENTER

            val matrix = Matrix()
            var currentScale = 1f
            val maxScale = 4.0f
            val minScale = 1.0f
            val lastTouch = PointF()

            val scaleDetector = ScaleGestureDetector(imageView.context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                    if (imageView.scaleType != ImageView.ScaleType.MATRIX) {
                        imageView.scaleType = ImageView.ScaleType.MATRIX
                        matrix.set(imageView.imageMatrix)
                    }
                    // Immediately lock ViewPager swipe so pinching is never interrupted
                    viewPager.isUserInputEnabled = false
                    return true
                }

                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val scaleFactor = detector.scaleFactor
                    val targetScale = currentScale * scaleFactor

                    if (targetScale in minScale..maxScale) {
                        currentScale = targetScale
                        matrix.postScale(scaleFactor, scaleFactor, detector.focusX, detector.focusY)
                        imageView.imageMatrix = matrix
                    }
                    return true
                }
            })

            val gestureDetector = GestureDetector(imageView.context, object : GestureDetector.SimpleOnGestureListener() {
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    if (currentScale > 1.05f) {
                        currentScale = 1f
                        matrix.reset()
                        imageView.scaleType = ImageView.ScaleType.FIT_CENTER
                        viewPager.isUserInputEnabled = true
                    } else {
                        currentScale = 2.5f
                        imageView.scaleType = ImageView.ScaleType.MATRIX
                        matrix.set(imageView.imageMatrix)
                        matrix.postScale(2.5f, 2.5f, e.x, e.y)
                        imageView.imageMatrix = matrix
                        viewPager.isUserInputEnabled = false
                    }
                    return true
                }
            })

            imageView.setOnTouchListener { _, event ->
                // Disallow ViewPager touch interception immediately when two fingers touch down
                if (event.pointerCount >= 2) {
                    viewPager.isUserInputEnabled = false
                    imageView.parent?.requestDisallowInterceptTouchEvent(true)
                }

                scaleDetector.onTouchEvent(event)
                gestureDetector.onTouchEvent(event)

                val curr = PointF(event.x, event.y)

                when (event.action and MotionEvent.ACTION_MASK) {
                    MotionEvent.ACTION_DOWN -> {
                        lastTouch.set(curr)
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (currentScale > 1.05f && !scaleDetector.isInProgress) {
                            val deltaX = curr.x - lastTouch.x
                            val deltaY = curr.y - lastTouch.y
                            matrix.postTranslate(deltaX, deltaY)
                            imageView.imageMatrix = matrix
                            lastTouch.set(curr.x, curr.y)
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                        if (event.pointerCount <= 1 && currentScale <= 1.05f) {
                            currentScale = 1f
                            matrix.reset()
                            imageView.scaleType = ImageView.ScaleType.FIT_CENTER
                            viewPager.isUserInputEnabled = true
                        }
                    }
                }
                true
            }

            synchronized(renderer) {
                try {
                    val page = renderer.openPage(position)

                    val displayMetrics = holder.itemView.resources.displayMetrics
                    val targetWidth = displayMetrics.widthPixels * 2
                    val aspectRatio = page.height.toFloat() / page.width.toFloat()
                    val targetHeight = (targetWidth * aspectRatio).toInt()

                    val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                    imageView.setImageBitmap(bitmap)
                    page.close()
                } catch (e: Exception) {
                    Log.e("PDF_ADAPTER", "Error rendering page $position", e)
                }
            }
        }

        override fun getItemCount(): Int = renderer.pageCount
    }

    class DepthPageTransformer : ViewPager2.PageTransformer {
        private val minScale = 0.85f

        override fun transformPage(page: View, position: Float) {
            val pageWidth = page.width

            when {
                position < -1 -> {
                    page.alpha = 0f
                }
                position <= 0 -> {
                    page.alpha = 1f
                    page.translationX = 0f
                    page.scaleX = 1f
                    page.scaleY = 1f
                }
                position <= 1 -> {
                    page.alpha = 1f - position
                    page.translationX = pageWidth * -position
                    val scaleFactor = minScale + (1 - minScale) * (1 - Math.abs(position))
                    page.scaleX = scaleFactor
                    page.scaleY = scaleFactor
                }
                else -> {
                    page.alpha = 0f
                }
            }
        }
    }
}