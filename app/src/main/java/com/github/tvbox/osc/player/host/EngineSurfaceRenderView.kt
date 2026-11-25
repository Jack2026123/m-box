package com.github.tvbox.osc.player.host

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.PixelCopy
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import com.github.tvbox.osc.player.KernelPlayer
import com.github.tvbox.osc.util.LOG
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class EngineSurfaceRenderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : SurfaceView(context, attrs, defStyleAttr), PlayerRenderView, SurfaceHolder.Callback {

    private var mediaPlayer: KernelPlayer? = null

    private var released = false

    private var scaleType = RenderMeasure.SCALE_DEFAULT

    private var videoWidth = 0

    private var videoHeight = 0

    private var videoRotationDegree = 0

    init {
        val surfaceHolder = holder
        surfaceHolder.addCallback(this)
        surfaceHolder.setFormat(PixelFormat.RGBA_8888)
        LOG.i("echo-render-view: create SurfaceView")
    }

    override fun attachToPlayer(player: KernelPlayer) {
        if (released) return
        mediaPlayer = player
        // 先把 SurfaceView 本体交给内核（media3 PlayerView 契约）：MPV 适配层据此
        // 让 core 的开播 surface 门生效；其余内核是默认空实现，不受影响。
        player.bindSurfaceView(this)
        val surfaceHolder = holder
        val surface = surfaceHolder.surface
        val valid = surface != null && surface.isValid
        if (valid) {
            player.setDisplay(surfaceHolder)
        }
        LOG.i("echo-surface: attach player valid=$valid")
    }

    override fun setVideoSize(videoWidth: Int, videoHeight: Int) {
        if (videoWidth > 0 && videoHeight > 0) {
            this.videoWidth = videoWidth
            this.videoHeight = videoHeight
            requestLayout()
        }
    }

    override fun setVideoRotation(degree: Int) {
        videoRotationDegree = degree
        rotation = degree.toFloat()
    }

    override fun setScaleType(scaleType: Int) {
        this.scaleType = scaleType
        requestLayout()
    }

    override fun getView(): View = this

    /**
     * SurfaceView 截帧：PixelCopy 是异步的，主线程上等不了，只能回 null
     * （调用方在后台线程再调一次）；API 26 以下没有 PixelCopy，也回 null。
     * TextureView 那边直接拿 bitmap，不走这里。
     */
    override fun doScreenShot(): Bitmap? {
        if (released) return null
        if (Looper.myLooper() == Looper.getMainLooper()) return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        val surface = holder.surface
        if (surface == null || !surface.isValid) return null
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return null
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val latch = CountDownLatch(1)
        var copyResult = PixelCopy.ERROR_UNKNOWN
        PixelCopy.request(surface, bitmap, { result ->
            copyResult = result
            latch.countDown()
        }, Handler(Looper.getMainLooper()))
        val done = try {
            latch.await(2000, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            false
        }
        if (!done || copyResult != PixelCopy.SUCCESS) {
            bitmap.recycle()
            return null
        }
        return bitmap
    }

    override fun release() {
        released = true
        mediaPlayer?.bindSurfaceView(null)
        mediaPlayer?.detachVideoSurface()
        mediaPlayer = null
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val measured = RenderMeasure.measure(
            widthMeasureSpec,
            heightMeasureSpec,
            View.MeasureSpec.getSize(widthMeasureSpec),
            View.MeasureSpec.getSize(heightMeasureSpec),
            scaleType,
            videoWidth,
            videoHeight,
            videoRotationDegree,
        )
        setMeasuredDimension(measured[0], measured[1])
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (released) return
        LOG.i("echo-surface: created valid=" + holder.surface?.isValid + " player=" + (mediaPlayer != null))
        mediaPlayer?.bindSurfaceView(this)
        mediaPlayer?.setDisplay(holder)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (released) return
        LOG.i("echo-surface: changed ${width}x$height player=" + (mediaPlayer != null))
        mediaPlayer?.setDisplay(holder)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        if (released) return
        LOG.i("echo-surface: destroyed -> detach player=" + (mediaPlayer != null))
        mediaPlayer?.detachVideoSurface()
    }
}
