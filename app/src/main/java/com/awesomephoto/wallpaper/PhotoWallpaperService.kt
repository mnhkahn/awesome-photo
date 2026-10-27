package com.awesomephoto.wallpaper

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.media.ExifInterface
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import java.io.File

class PhotoWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine = SlideshowEngine()

    inner class SlideshowEngine : Engine(), SharedPreferences.OnSharedPreferenceChangeListener {
        private val thread = HandlerThread("photo-wallpaper").apply { start() }
        private val handler = Handler(thread.looper)
        private val store = WallpaperStore(this@PhotoWallpaperService)
        @Volatile private var visible = false
        @Volatile private var surfaceReady = false
        private var album: WallpaperStore.Album? = null
        private var index = -1
        private var changedAt = 0L
        private var bitmap: Bitmap? = null
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val render = Runnable { drawFrame() }

        override fun onCreate(holder: SurfaceHolder) {
            super.onCreate(holder)
            setTouchEventsEnabled(false)
            setOffsetNotificationsEnabled(false)
            store.preferences.registerOnSharedPreferenceChangeListener(this)
        }

        override fun onSharedPreferenceChanged(preferences: SharedPreferences, key: String?) {
            if (key != null && key != if (isPreview) "pending" else "active") return
            handler.post {
                album = null
                index = -1
                bitmap?.recycle()
                bitmap = null
                requestFrame()
            }
        }

        override fun onVisibilityChanged(visible: Boolean) {
            this.visible = visible
            requestFrame()
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            surfaceReady = true
            handler.post { bitmap?.recycle(); bitmap = null; requestFrame() }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            surfaceReady = false
            handler.removeCallbacks(render)
            super.onSurfaceDestroyed(holder)
        }

        private fun requestFrame() {
            handler.removeCallbacks(render)
            if (visible && surfaceReady) handler.post(render)
        }

        private fun drawFrame() {
            if (!visible || !surfaceReady) return
            val loaded = store.read(isPreview) ?: store.read(false)
            if (loaded != album) {
                album = loaded
                index = -1
                bitmap?.recycle()
                bitmap = null
            }
            val current = album
            val now = SystemClock.elapsedRealtime()
            if (current != null && current.files.isNotEmpty()) {
                val interval = current.config.intervalMinutes * 60_000L
                if (index < 0 || now - changedAt >= interval) {
                    index = nextPhotoIndex(index, current.files.size, current.config.shuffle)
                    bitmap?.recycle()
                    bitmap = null
                    changedAt = now
                }
                if (bitmap == null) {
                    // Skip a damaged copy instead of killing the wallpaper service.
                    for (attempt in current.files.indices) {
                        bitmap = runCatching { decode(current.files[index]) }.getOrNull()
                        if (bitmap != null) break
                        index = (index + 1) % current.files.size
                    }
                }
            }
            if (!surfaceReady || !visible) return
            runCatching {
                val canvas = surfaceHolder.lockCanvas() ?: return@runCatching
                try {
                    canvas.drawColor(Color.BLACK)
                    bitmap?.let { image ->
                        val sx = canvas.width.toFloat() / image.width
                        val sy = canvas.height.toFloat() / image.height
                        val scale = if (current?.config?.fillScreen != false) maxOf(sx, sy) else minOf(sx, sy)
                        val w = image.width * scale
                        val h = image.height * scale
                        canvas.drawBitmap(image, null, RectF((canvas.width - w) / 2, (canvas.height - h) / 2,
                            (canvas.width + w) / 2, (canvas.height + h) / 2), paint)
                    }
                } finally { surfaceHolder.unlockCanvasAndPost(canvas) }
            }
            if (visible && surfaceReady && current != null && current.files.size > 1) {
                handler.postDelayed(render, (current.config.intervalMinutes * 60_000L - (SystemClock.elapsedRealtime() - changedAt)).coerceAtLeast(1000))
            }
        }

        private fun decode(file: File): Bitmap? {
            val frame = surfaceHolder.surfaceFrame
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.RGB_565
                // Bound both source dimensions, including extreme panoramas.
                val maxEdge = maxOf(frame.width(), frame.height(), 1024).coerceAtMost(4096)
                inSampleSize = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / inSampleSize > maxEdge) inSampleSize *= 2
            }
            val source = BitmapFactory.decodeFile(file.path, options) ?: return null
            val orientation = runCatching { ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }.getOrDefault(1)
            val matrix = Matrix().apply {
                when (orientation) {
                    2 -> setScale(-1f, 1f)
                    3 -> setRotate(180f)
                    4 -> setScale(1f, -1f)
                    5 -> { setRotate(90f); postScale(-1f, 1f) }
                    6 -> setRotate(90f)
                    7 -> { setRotate(-90f); postScale(-1f, 1f) }
                    8 -> setRotate(-90f)
                }
            }
            if (matrix.isIdentity) return source
            return try {
                Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
            } finally { source.recycle() }
        }

        override fun onDestroy() {
            visible = false
            surfaceReady = false
            store.preferences.unregisterOnSharedPreferenceChangeListener(this)
            handler.removeCallbacksAndMessages(null)
            handler.post { bitmap?.recycle(); bitmap = null; thread.quitSafely() }
            super.onDestroy()
        }
    }
}
