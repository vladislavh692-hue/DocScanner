package com.example.docscanner

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var preview: PreviewView
    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService
    private val pages = mutableListOf<File>()
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else toast("Нужен доступ к камере")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraExecutor = Executors.newSingleThreadExecutor()
        buildUi()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
            startCamera()
        else permission.launch(Manifest.permission.CAMERA)
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK) }
        preview = PreviewView(this).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE }
        root.addView(preview, LinearLayout.LayoutParams(-1, 0, 1f))
        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(12, 12, 12, 20) }
        val shot = Button(this).apply { text = "СКАНИРОВАТЬ"; setOnClickListener { takePhoto() } }
        val pdf = Button(this).apply { text = "PDF (0)"; setOnClickListener { if (pages.isNotEmpty()) makePdf() else toast("Сначала отсканируйте страницу") } }
        bar.addView(shot, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(pdf, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(bar)
        setContentView(root)
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val p = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
            imageCapture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, p, imageCapture)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        val capture = imageCapture ?: return
        val dir = File(cacheDir, "scans").apply { mkdirs() }
        val file = File(dir, "scan_${System.currentTimeMillis()}.jpg")
        val options = ImageCapture.OutputFileOptions.Builder(file).build()
        capture.takePicture(options, cameraExecutor, object : ImageCapture.OnImageSavedCallback {
            override fun onError(exc: ImageCaptureException) = runOnUiThread { toast("Ошибка камеры: ${exc.message}") }
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                pages.add(file)
                runOnUiThread {
                    val root = findViewById<LinearLayout>(android.R.id.content).getChildAt(0) as LinearLayout
                    val bar = root.getChildAt(1) as LinearLayout
                    (bar.getChildAt(1) as Button).text = "PDF (${pages.size})"
                    toast("Страница ${pages.size} добавлена")
                }
            }
        })
    }

    private fun makePdf() {
        val doc = PdfDocument()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        pages.forEachIndexed { i, file ->
            val bmp = BitmapFactory.decodeFile(file.absolutePath) ?: return@forEachIndexed
            val maxW = 595f
            val maxH = 842f
            val scale = minOf(maxW / bmp.width, maxH / bmp.height)
            val w = bmp.width * scale
            val h = bmp.height * scale
            val page = doc.startPage(PdfDocument.PageInfo.Builder(maxW.toInt(), maxH.toInt(), i + 1).create())
            page.canvas.drawBitmap(bmp, null, android.graphics.RectF((maxW-w)/2, (maxH-h)/2, (maxW+w)/2, (maxH+h)/2), paint)
            doc.finishPage(page)
            bmp.recycle()
        }
        val name = "Scan_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.pdf"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/DocScanner")
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        if (uri == null) { doc.close(); toast("Не удалось сохранить PDF"); return }
        contentResolver.openOutputStream(uri)?.use { doc.writeTo(it) }
        doc.close()
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(share, "Отправить PDF"))
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    override fun onDestroy() { super.onDestroy(); cameraExecutor.shutdown() }
}