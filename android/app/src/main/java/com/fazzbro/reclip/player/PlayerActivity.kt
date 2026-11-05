package com.fazzbro.reclip.player

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.fazzbro.reclip.data.model.DownloadStatus
import com.fazzbro.reclip.data.repository.DownloadRepository
import com.fazzbro.reclip.util.FileUtils
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File

class PlayerActivity : ComponentActivity() {

    companion object {
        const val EXTRA_JOB_ID = "extra_job_id"
        const val EXTRA_FILE_PATH = "extra_file_path"
        const val EXTRA_TITLE = "extra_title"
        private const val SEEK_INCREMENT_MS = 10000L
    }

    private var player: ExoPlayer? = null
    private var playerView: PlayerView? = null
    private var repository: DownloadRepository? = null

    private var jobId: String = ""
    private var filePath: String = ""
    private var mediaTitle: String = ""

    private var topBar: LinearLayout? = null
    private var closeButton: View? = null
    private var resizeButton: TextView? = null
    private var externalButton: View? = null
    private var statusTextView: TextView? = null

    private var centerControlsOverlay: LinearLayout? = null
    private var seekBackButton: View? = null
    private var seekForwardButton: View? = null

    private var seekFeedbackBadge: TextView? = null
    private val hideFeedbackHandler = Handler(Looper.getMainLooper())
    private val hideFeedbackRunnable = Runnable { seekFeedbackBadge?.visibility = View.GONE }

    private var gestureDetector: GestureDetector? = null
    private var downX = 0f
    private var downY = 0f
    private var touchSlop = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep screen on and configure pure black full window
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)

        window.decorView.setBackgroundColor(Color.BLACK)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK

        // Edge-to-edge layout & display cutout handling so video fills entire physical display
        WindowCompat.setDecorFitsSystemWindows(window, false)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        hideSystemBars()

        jobId = intent.getStringExtra(EXTRA_JOB_ID) ?: ""
        filePath = intent.getStringExtra(EXTRA_FILE_PATH) ?: ""
        mediaTitle = intent.getStringExtra(EXTRA_TITLE) ?: "Playing Media"

        touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        repository = DownloadRepository.getInstance(applicationContext)

        if (filePath.isBlank() || !File(filePath).exists()) {
            Toast.makeText(this, "Media file not found on disk", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setupViews()
        initializePlayer()
        observeDownloadProgress()
    }

    private fun hideSystemBars() {
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insetsController.hide(WindowInsetsCompat.Type.systemBars())

        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_FULLSCREEN
        )
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemBars()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupViews() {
        val rootLayout = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        playerView = PlayerView(this).apply {
            useController = true
            setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
            setShowFastForwardButton(false)
            setShowRewindButton(false)
            setShowNextButton(false)
            setShowPreviousButton(false)
            setControllerShowTimeoutMs(3000)
            setControllerHideOnTouch(false)
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setBackgroundColor(Color.BLACK)
        }
        rootLayout.addView(
            playerView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        // Top control overlay bar (Close, Title, Status, Aspect Ratio FIT/ZOOM, EXT)
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#B3000000"))
            setPadding(dp(12), dp(18), dp(12), dp(10))
        }
        topBar = bar

        // Close / Back button
        val closeBtn = TextView(this).apply {
            text = "X"
            setTextColor(Color.WHITE)
            textSize = 18f
            setTypeface(Typeface.DEFAULT_BOLD)
            setPadding(dp(12), dp(8), dp(14), dp(8))
            setOnClickListener { finish() }
        }
        closeButton = closeBtn
        bar.addView(closeBtn)

        // Title and Status column
        val titleColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
        }

        val titleView = TextView(this).apply {
            text = mediaTitle
            setTextColor(Color.WHITE)
            textSize = 15f
            setTypeface(Typeface.DEFAULT_BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        titleColumn.addView(titleView)

        statusTextView = TextView(this).apply {
            text = if (jobId.isNotBlank()) "Connecting stream..." else "Local File"
            setTextColor(Color.parseColor("#FF7043"))
            textSize = 12f
        }
        titleColumn.addView(statusTextView)

        bar.addView(titleColumn)

        // Aspect ratio toggle button (FIT vs ZOOM to fill whole wide screen without black borders)
        val resizeBtn = TextView(this).apply {
            text = "FIT"
            setTextColor(Color.WHITE)
            textSize = 12f
            setTypeface(Typeface.DEFAULT_BOLD)
            val btnShape = GradientDrawable().apply {
                setColor(Color.parseColor("#33FFFFFF"))
                cornerRadius = dp(6).toFloat()
            }
            background = btnShape
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener { toggleResizeMode() }
        }
        resizeButton = resizeBtn
        bar.addView(resizeBtn)

        val btnSpacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(8), dp(1))
        }
        bar.addView(btnSpacer)

        // External player launcher button
        val extBtn = TextView(this).apply {
            text = "EXT"
            setTextColor(Color.WHITE)
            textSize = 12f
            setTypeface(Typeface.DEFAULT_BOLD)
            val extShape = GradientDrawable().apply {
                setColor(Color.parseColor("#33FFFFFF"))
                cornerRadius = dp(6).toFloat()
            }
            background = extShape
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener { openInExternalPlayer() }
        }
        externalButton = extBtn
        bar.addView(extBtn)

        rootLayout.addView(
            bar,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            )
        )

        // On-screen Center Skip Controls (<< 10s and 10s >>) positioned within the screen
        val centerOverlay = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }
        centerControlsOverlay = centerOverlay

        val skipBack = TextView(this).apply {
            text = "<< 10s"
            setTextColor(Color.WHITE)
            textSize = 14f
            setTypeface(Typeface.DEFAULT_BOLD)
            gravity = Gravity.CENTER
            val shape = GradientDrawable().apply {
                setColor(Color.parseColor("#99000000"))
                cornerRadius = dp(24).toFloat()
                setStroke(dp(1), Color.parseColor("#55FFFFFF"))
            }
            background = shape
            setPadding(dp(16), dp(10), dp(16), dp(10))
            isClickable = true
            isFocusable = true
            setOnClickListener { seekBy(-SEEK_INCREMENT_MS) }
        }
        seekBackButton = skipBack
        centerOverlay.addView(skipBack)

        // Center space so ExoPlayer Play/Pause button remains fully visible and clickable
        val midSpace = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(130), dp(1))
        }
        centerOverlay.addView(midSpace)

        val skipFwd = TextView(this).apply {
            text = "10s >>"
            setTextColor(Color.WHITE)
            textSize = 14f
            setTypeface(Typeface.DEFAULT_BOLD)
            gravity = Gravity.CENTER
            val shape = GradientDrawable().apply {
                setColor(Color.parseColor("#99000000"))
                cornerRadius = dp(24).toFloat()
                setStroke(dp(1), Color.parseColor("#55FFFFFF"))
            }
            background = shape
            setPadding(dp(16), dp(10), dp(16), dp(10))
            isClickable = true
            isFocusable = true
            setOnClickListener { seekBy(SEEK_INCREMENT_MS) }
        }
        seekForwardButton = skipFwd
        centerOverlay.addView(skipFwd)

        rootLayout.addView(centerOverlay)

        // Seek feedback overlay badge in center of screen
        val feedbackBadge = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 22f
            setTypeface(Typeface.DEFAULT_BOLD)
            val badgeShape = GradientDrawable().apply {
                setColor(Color.parseColor("#CC000000"))
                cornerRadius = dp(16).toFloat()
            }
            background = badgeShape
            setPadding(dp(24), dp(14), dp(24), dp(14))
            visibility = View.GONE
        }
        seekFeedbackBadge = feedbackBadge
        rootLayout.addView(
            feedbackBadge,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        )

        // Synchronize TopBar and on-screen skip buttons with PlayerView controller visibility
        playerView?.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
            topBar?.visibility = visibility
            centerControlsOverlay?.visibility = visibility
        })

        // Double-tap and single-tap gesture detector
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val screenWidth = playerView?.width ?: resources.displayMetrics.widthPixels
                if (e.x < screenWidth / 2) {
                    seekBy(-SEEK_INCREMENT_MS)
                } else {
                    seekBy(SEEK_INCREMENT_MS)
                }
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                showAllControls()
                return true
            }
        })

        setContentView(rootLayout)
    }

    private fun toggleResizeMode() {
        val pv = playerView ?: return
        if (pv.resizeMode == AspectRatioFrameLayout.RESIZE_MODE_FIT) {
            pv.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            resizeButton?.text = "ZOOM"
            showFeedback("Zoomed to fill")
        } else {
            pv.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            resizeButton?.text = "FIT"
            showFeedback("Fit to screen")
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // If touching interactive buttons or seek bar, allow normal view touch dispatch
        if (isTouchOnInteractiveView(ev)) {
            return super.dispatchTouchEvent(ev)
        }

        val isControlsShowing = (playerView?.isControllerFullyVisible == true) || (topBar?.visibility == View.VISIBLE)

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
            }
            MotionEvent.ACTION_UP -> {
                val dx = Math.abs(ev.x - downX)
                val dy = Math.abs(ev.y - downY)
                val isTap = dx < touchSlop && dy < touchSlop

                // When controls are visible, tapping anywhere on screen instantly dismisses all controls
                if (isControlsShowing && isTap) {
                    hideAllControls()
                    return true
                }
            }
        }

        // When controls are hidden, gesture detector handles double-tap seek and single-tap reveal
        if (!isControlsShowing) {
            if (gestureDetector?.onTouchEvent(ev) == true) {
                return true
            }
        }

        return super.dispatchTouchEvent(ev)
    }

    private fun isTouchOnInteractiveView(ev: MotionEvent): Boolean {
        if (isTouchInsideView(closeButton, ev)) return true
        if (isTouchInsideView(resizeButton, ev)) return true
        if (isTouchInsideView(externalButton, ev)) return true
        if (isTouchInsideView(seekBackButton, ev)) return true
        if (isTouchInsideView(seekForwardButton, ev)) return true

        val pv = playerView ?: return false
        try {
            val playPause = pv.findViewById<View>(androidx.media3.ui.R.id.exo_play_pause)
            if (isTouchInsideView(playPause, ev)) return true
            val progress = pv.findViewById<View>(androidx.media3.ui.R.id.exo_progress)
            if (isTouchInsideView(progress, ev)) return true
            val prev = pv.findViewById<View>(androidx.media3.ui.R.id.exo_prev)
            if (isTouchInsideView(prev, ev)) return true
            val next = pv.findViewById<View>(androidx.media3.ui.R.id.exo_next)
            if (isTouchInsideView(next, ev)) return true
            val rew = pv.findViewById<View>(androidx.media3.ui.R.id.exo_rew)
            if (isTouchInsideView(rew, ev)) return true
            val ffwd = pv.findViewById<View>(androidx.media3.ui.R.id.exo_ffwd)
            if (isTouchInsideView(ffwd, ev)) return true
            val rewAmt = pv.findViewById<View>(androidx.media3.ui.R.id.exo_rew_with_amount)
            if (isTouchInsideView(rewAmt, ev)) return true
            val ffwdAmt = pv.findViewById<View>(androidx.media3.ui.R.id.exo_ffwd_with_amount)
            if (isTouchInsideView(ffwdAmt, ev)) return true
        } catch (_: Exception) {}

        return false
    }

    private fun isTouchInsideView(view: View?, ev: MotionEvent): Boolean {
        if (view == null || !view.isShown) return false
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        val x = ev.rawX.toInt()
        val y = ev.rawY.toInt()
        return x >= location[0] && x <= location[0] + view.width &&
               y >= location[1] && y <= location[1] + view.height
    }

    private fun showAllControls() {
        playerView?.showController()
        topBar?.visibility = View.VISIBLE
        centerControlsOverlay?.visibility = View.VISIBLE
    }

    private fun hideAllControls() {
        playerView?.hideController()
        topBar?.visibility = View.GONE
        centerControlsOverlay?.visibility = View.GONE
        hideSystemBars()
    }

    private fun initializePlayer() {
        val targetFile = File(filePath)
        val dataSourceFactory = GrowingFileDataSource.Factory(jobId, repository)
        val extractorsFactory = DefaultExtractorsFactory()
            .setConstantBitrateSeekingEnabled(true)
            .setConstantBitrateSeekingAlwaysEnabled(true)
        val mediaSource = ProgressiveMediaSource.Factory(dataSourceFactory, extractorsFactory)
            .createMediaSource(MediaItem.fromUri(Uri.fromFile(targetFile)))

        val exo = ExoPlayer.Builder(this)
            .setSeekBackIncrementMs(SEEK_INCREMENT_MS)
            .setSeekForwardIncrementMs(SEEK_INCREMENT_MS)
            .build()
            .apply {
                setMediaSource(mediaSource)
                prepare()
                playWhenReady = true
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        when (state) {
                            Player.STATE_BUFFERING -> {
                                updateStatusText("Buffering stream...")
                            }
                            Player.STATE_READY -> {
                                updateActiveStatus()
                            }
                            Player.STATE_ENDED -> {
                                updateStatusText("Playback finished")
                            }
                            Player.STATE_IDLE -> {}
                        }
                    }
                })
            }

        player = exo
        playerView?.player = exo
    }

    private fun seekBy(offsetMs: Long) {
        val exo = player ?: return
        val current = exo.currentPosition
        val duration = exo.duration
        val maxTarget = if (duration > 0 && duration != C.TIME_UNSET) {
            duration
        } else {
            Long.MAX_VALUE
        }
        val target = (current + offsetMs).coerceIn(0L, maxTarget)
        exo.seekTo(target)

        val text = if (offsetMs > 0) "+10s" else "-10s"
        showFeedback(text)
    }

    private fun showFeedback(message: String) {
        runOnUiThread {
            seekFeedbackBadge?.text = message
            seekFeedbackBadge?.visibility = View.VISIBLE
            hideFeedbackHandler.removeCallbacks(hideFeedbackRunnable)
            hideFeedbackHandler.postDelayed(hideFeedbackRunnable, 600)
        }
    }

    private fun observeDownloadProgress() {
        if (jobId.isBlank()) return
        lifecycleScope.launch {
            repository?.downloadEvents?.collectLatest { item ->
                if (item.id == jobId) {
                    if (item.status == DownloadStatus.COMPLETED) {
                        updateStatusText("Download Completed (${item.filesizeFormatted})")
                    } else if (item.status == DownloadStatus.DOWNLOADING) {
                        val speedText = if (item.speed.isNotBlank()) " • ${item.speed}" else ""
                        updateStatusText("Live Streaming • ${item.progressString}$speedText")
                    } else if (item.status == DownloadStatus.PAUSED) {
                        updateStatusText("Download Paused")
                    }
                }
            }
        }
    }

    private fun updateActiveStatus() {
        if (jobId.isBlank()) {
            updateStatusText("Playing")
            return
        }
        val active = repository?.activeDownloads?.value?.get(jobId)
        if (active != null && active.status == DownloadStatus.DOWNLOADING) {
            val speedText = if (active.speed.isNotBlank()) " • ${active.speed}" else ""
            updateStatusText("Live Streaming • ${active.progressString}$speedText")
        } else {
            updateStatusText("Playing")
        }
    }

    private fun updateStatusText(message: String) {
        runOnUiThread {
            statusTextView?.text = message
        }
    }

    private fun openInExternalPlayer() {
        try {
            val targetFile = File(filePath)
            if (!targetFile.exists()) {
                Toast.makeText(this, "File not found", Toast.LENGTH_SHORT).show()
                return
            }
            val uri = FileUtils.getContentUriForFile(this, targetFile)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "video/mp4")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(Intent.createChooser(intent, "Open in External Player"))
        } catch (e: Exception) {
            Toast.makeText(this, "Could not open player: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onPause() {
        super.onPause()
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        hideFeedbackHandler.removeCallbacks(hideFeedbackRunnable)
        player?.release()
        player = null
        playerView?.player = null
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()
    }
}