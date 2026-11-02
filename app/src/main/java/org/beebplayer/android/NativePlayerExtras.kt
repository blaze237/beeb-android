// SPDX-License-Identifier: GPL-3.0-or-later
package org.beebplayer.android

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Supplementary metadata and consistent pink selection styling over the standard Media3 transport UI. */
@OptIn(UnstableApi::class)
class NativePlayerExtras(context: Context, private val player: ExoPlayer, private val playerView: PlayerView,
                         private val episode: Programme?, title: String, private val nextAction: () -> Unit) : FrameLayout(context) {
    private val metadata = LinearLayout(context)
    private var dialog: Dialog? = null
    private var activeSkip: Skip? = null
    private var nextEpisode: Programme? = null
    private var firstFrame = false
    private val pink = Color.rgb(250, 35, 137)
    private val plum = 0xFF382039.toInt()
    val quality = button("", "Received stream quality") { qualityDialog() }
    val skip = button("Skip intro", "Skip intro") { activeSkip?.let { player.seekTo(it.endMs); show() } }
    private val nextButton = button("Play next", "Play next episode") { nextAction() }
    private val detailsButton = button("Details", "Programme details") { detailsDialog() }
    private val captionBadge = TextView(context).apply {
        text = "Captions temporarily on"
        textSize = 14f
        setTextColor(Color.WHITE)
        contentDescription = "Captions temporarily on"
        setPadding(dp(14), dp(6), dp(14), dp(6))
        background = GradientDrawable().apply { setColor(0xCC101016.toInt()); cornerRadius = dp(8).toFloat(); setStroke(dp(1), pink) }
        isFocusable = false; isClickable = false; isFocusableInTouchMode = false
        visibility = GONE
    }
    private var ended = false
    private var endOfferShown = false
    val play: View get() = playerView.findViewById(androidx.media3.ui.R.id.exo_play_pause)
    var visible = false
        private set

    init {
        metadata.apply { orientation = LinearLayout.VERTICAL; setPadding(dp(28), dp(18), dp(180), dp(16)); background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xD9000000.toInt(), Color.TRANSPARENT)) }
        metadata.addView(label(episode?.title ?: title, 24f).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        episode?.subtitle?.takeIf { it.isNotBlank() }?.let { metadata.addView(label(it, 17f)) }
        val details = listOfNotNull(episode?.duration?.takeIf { it.isNotBlank() }?.let { "Length: $it" }, broadcastDate(episode?.date.orEmpty()).takeIf { it.isNotBlank() }?.let { "Broadcast: $it" }).joinToString("  ·  ")
        if (details.isNotEmpty()) metadata.addView(label(details, 14f))
        episode?.summary?.takeIf { it.isNotBlank() }?.let { metadata.addView(label(it, 15f).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }) }
        metadata.contentDescription = "Programme details. " + listOfNotNull(episode?.title, episode?.subtitle, details, episode?.summary).joinToString(". ")
        // Metadata is display-only; Details opens only via the explicit button below.
        metadata.isFocusable = false; metadata.isClickable = false; metadata.isFocusableInTouchMode = false
        addView(metadata, LayoutParams(-1, -2, Gravity.TOP))
        addView(quality, LayoutParams(-2, dp(42), Gravity.TOP or Gravity.RIGHT).apply { topMargin = dp(22); rightMargin = dp(24) })
        addView(detailsButton, LayoutParams(-2, dp(42), Gravity.TOP or Gravity.RIGHT).apply { topMargin = dp(70); rightMargin = dp(24) })
        // Persistent temp-caption indicator: independent of controller visibility so it stays
        // on screen until captions restore or the user changes subtitles manually.
        addView(captionBadge, LayoutParams(-2, -2, Gravity.TOP or Gravity.RIGHT).apply { topMargin = dp(118); rightMargin = dp(24) })
        skip.visibility = GONE; nextButton.visibility = GONE; detailsButton.visibility = GONE
        addView(skip, LayoutParams(-2, dp(44), Gravity.RIGHT or Gravity.BOTTOM).apply { bottomMargin = dp(90); rightMargin = dp(24) })
        addView(nextButton, LayoutParams(-2, dp(44), Gravity.LEFT or Gravity.BOTTOM).apply { bottomMargin = dp(90); leftMargin = dp(24) })
        playerView.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
            visible = visibility == VISIBLE
            metadata.visibility = visibility; detailsButton.visibility = visibility; quality.visibility = if (quality.text.isNotEmpty()) visibility else GONE
            playerView.subtitleView?.setBottomPaddingFraction(if (visibility == VISIBLE) .22f else .08f)
            if (visibility != VISIBLE) (context as? android.app.Activity)?.currentFocus?.takeIf { !it.isShown }?.clearFocus()
        })
        update(); styleController()
    }
    fun show(focus: Boolean = true) { playerView.showController(); styleController(); if (focus) focusTarget(play) }
    /** Show the controller with the seek bar selected, e.g. after a sideways skip. */
    fun showOnSeekBar() { playerView.showController(); styleController(); focusTarget(seekTarget()) }
    private fun seekTarget(): View = playerView.findViewById<View>(androidx.media3.ui.R.id.exo_progress)?.also { it.isFocusable = true } ?: play
    private fun transportRow(): List<View> = listOfNotNull(
        play,
        playerView.findViewById(androidx.media3.ui.R.id.exo_prev),
        playerView.findViewById(androidx.media3.ui.R.id.exo_rew_with_amount)
            ?: playerView.findViewById(androidx.media3.ui.R.id.exo_rew),
        playerView.findViewById(androidx.media3.ui.R.id.exo_ffwd_with_amount)
            ?: playerView.findViewById(androidx.media3.ui.R.id.exo_ffwd),
        playerView.findViewById(androidx.media3.ui.R.id.exo_next)
    )
    /** Explicit vertical chain between the bottom seek bar and the transport row.
     *  Geometry search alone strands focus on the full-width seek bar. */
    private fun wireTransportFocus() {
        val seek = seekTarget()
        if (seek.id == View.NO_ID) seek.id = View.generateViewId()
        if (play.id == View.NO_ID) play.id = View.generateViewId()
        seek.nextFocusUpId = play.id
        transportRow().forEach { it.nextFocusDownId = seek.id }
    }
    /** Up from the seek bar must always reach the transport row; returns false when focus is elsewhere. */
    fun focusTransportFromSeek(): Boolean {
        val seek = seekTarget()
        if ((context as? android.app.Activity)?.currentFocus !== seek) return false
        show(false)
        play.requestFocus()
        return true
    }
    /** Focus [target] so DPAD has somewhere to go once the controller is up. */
    private fun focusTarget(target: View) {
        val seek = seekTarget()
        val current = listOf(seek, play, detailsButton, quality, skip, nextButton).firstOrNull { it.hasFocus() && it.isShown }
        // showController() always hands focus to play first; only stay put when focus is
        // already where we want it or the user placed it on another control.
        if (current != null && (current != play || target == play)) return
        if (target.isShown) target.requestFocus() else play.requestFocus()
        // If the controller views haven't finished becoming visible, the first
        // request can fail silently and leave nothing focused. Retry once after layout.
        playerView.post {
            if (listOf(seek, play, detailsButton, quality, skip, nextButton).none { it.hasFocus() && it.isShown }) {
                if (target.isShown) target.requestFocus() else play.requestFocus()
            }
        }
    }
    /** True when Select should toggle play/pause instead of clicking the focused view. */
    fun centerShouldToggle(): Boolean {
        val focused = (context as? android.app.Activity)?.currentFocus ?: return true
        if (!focused.isShown) return true
        if (focused == skip || focused == nextButton || focused == quality || focused == detailsButton) return false
        var parent = focused.parent
        while (parent != null) { if (parent === playerView) return focused === play || focused === seekTarget(); parent = parent.parent }
        return true
    }
    fun hide() = playerView.hideController()
    fun scheduleHide() { if (player.isPlaying && dialog == null) playerView.controllerShowTimeoutMs = 1500 }
    fun ready() { if (!firstFrame) { firstFrame = true; show() } }
    fun update(marker: Skip? = activeSkip, upcoming: Programme? = nextEpisode) {
        val previousSkip = activeSkip
        activeSkip = marker; nextEpisode = upcoming
        skip.text = activeSkip?.title.orEmpty(); skip.contentDescription = activeSkip?.title
        val skipAppeared = marker != null && (previousSkip?.title != marker.title || skip.visibility != VISIBLE)
        skip.visibility = if (activeSkip == null) GONE else VISIBLE
        if (skipAppeared) announceSkip()
        val nearEnd = upcoming != null && (ended || player.duration > 0 && player.duration - player.currentPosition <= 30_000)
        nextButton.contentDescription = upcoming?.let { "Play next episode: ${it.title} ${it.subtitle}".trim() } ?: "Play next episode"
        nextButton.visibility = if (nearEnd) VISIBLE else GONE
        if (!nearEnd && nextButton.hasFocus() || activeSkip == null && skip.hasFocus()) show()
        if (ended && nearEnd && !endOfferShown && upcoming != null) { endOfferShown = true; offerUpNext(upcoming) }
        val f = player.videoFormat
        quality.text = when { f == null || f.height <= 0 -> ""; f.height >= 2160 && f.colorInfo?.colorTransfer == C.COLOR_TRANSFER_HLG -> "4K HDR · HLG"; f.height >= 2160 && f.colorInfo?.colorTransfer == C.COLOR_TRANSFER_ST2084 -> "4K HDR"; f.height >= 2160 -> "4K"; f.height >= 1080 -> "Full HD"; f.height >= 720 -> "HD"; else -> "SD" }
        quality.visibility = if (visible && quality.text.isNotEmpty()) VISIBLE else GONE
    }
    /** End-state Up Next offer: a clear next-episode action after credits even when the 30s window was missed. Back still finishes (see PlaybackActivity). */
    fun onEnded(upcoming: Programme? = nextEpisode) {
        ended = true; endOfferShown = false
        playerView.controllerShowTimeoutMs = 0
        update(activeSkip, upcoming)
        if (upcoming == null) show()
    }
    /** Clear end-state persistence when playback restarts. */
    fun onPlaying() {
        if (!ended) return
        ended = false; endOfferShown = false
        playerView.controllerShowTimeoutMs = 1500
    }
    private fun offerUpNext(upcoming: Programme) {
        playerView.showController(); styleController()
        focusTarget(nextButton)
        nextButton.post {
            nextButton.announceForAccessibility("Up next: ${upcoming.title}. Play next episode available.")
            pulse(nextButton)
        }
    }
    /** TalkBack announcement plus a brief visual pulse; focus stays where the user left it. */
    private fun announceSkip() {
        skip.post {
            skip.announceForAccessibility("${skip.contentDescription ?: "Skip"} available")
            pulse(skip)
        }
    }
    private fun pulse(target: View) {
        target.animate().scaleX(1.12f).scaleY(1.12f).setDuration(150).withEndAction {
            target.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
        }.start()
    }
    fun replayNotice() { Toast.makeText(context, "Rewound 10 seconds · captions temporarily on", Toast.LENGTH_SHORT).show(); captionBadge.visibility = VISIBLE }
    fun dismissTransientNotice() { captionBadge.visibility = GONE }
    private fun present(d: Dialog) { dialog?.dismiss(); dialog = d; playerView.controllerShowTimeoutMs = 0; show(false); d.setOnDismissListener { dialog = null; playerView.controllerShowTimeoutMs = 1500; show() }; d.show() }
    private fun detailsDialog() {
        val info = listOfNotNull(episode?.subtitle?.takeIf { it.isNotBlank() }, episode?.duration?.takeIf { it.isNotBlank() }?.let { "Length: $it" }, broadcastDate(episode?.date.orEmpty()).takeIf { it.isNotBlank() }?.let { "Broadcast: $it" }, episode?.summary?.takeIf { it.isNotBlank() }).joinToString("\n\n")
        present(AlertDialog.Builder(context).setTitle(episode?.title ?: "Programme details").setMessage(info.ifBlank { "Programme information is unavailable." }).setPositiveButton("Close", null).create())
    }
    private fun qualityDialog() {
        val f = player.videoFormat ?: return
        val range = when (f.colorInfo?.colorTransfer) { C.COLOR_TRANSFER_HLG -> "HLG"; C.COLOR_TRANSFER_ST2084 -> "HDR10"; else -> "SDR / unspecified" }
        present(AlertDialog.Builder(context).setTitle("Received stream").setMessage("${f.width} × ${f.height}\n${f.codecs ?: f.sampleMimeType.orEmpty()}\nHDR: $range\n\n${UhdSupport.inspect(context).description}\n\nCheck the TV's HDR status for its output mode.").setPositiveButton("Close", null).create())
    }
    internal fun debugTempCaptionIndicatorVisible(): Boolean { check(BuildConfig.DEBUG); return captionBadge.visibility == VISIBLE }
    internal fun debugDialogShowing(): Boolean { check(BuildConfig.DEBUG); return dialog?.isShowing == true }
    internal fun debugDismissDialog() { check(BuildConfig.DEBUG); dialog?.dismiss() }
    fun dispose() { dialog?.setOnDismissListener(null); dialog?.dismiss(); dialog = null }
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun label(value: String, size: Float) = TextView(context).apply { text = value; textSize = size; setTextColor(Color.WHITE); setPadding(0, dp(2), 0, dp(2)) }
    private fun selectedBackground(on: Boolean) = GradientDrawable().apply { setColor(if (on) plum else 0xFF1C1B24.toInt()); cornerRadius = dp(10).toFloat(); setStroke(dp(if (on) 5 else 1), if (on) pink else 0xFF47434E.toInt()) }
    private fun button(title: String, description: String, action: () -> Unit) = Button(context).apply { text = title; textSize = 14f; isAllCaps = false; contentDescription = description; isFocusable = true; isFocusableInTouchMode = true; background = selectedBackground(false); setTextColor(Color.WHITE); setOnFocusChangeListener { v, on -> v.background = selectedBackground(on) }; setOnClickListener { action() } }
    private fun styleController() {
        runCatching {
            val progress = playerView.findViewById<View>(androidx.media3.ui.R.id.exo_progress)
            (progress as? androidx.media3.ui.DefaultTimeBar)?.let { bar -> bar.setPlayedColor(pink); bar.setScrubberColor(pink) }
        }
        styleButtons(playerView)
        wireTransportFocus()
        playerView.post { styleButtons(playerView); wireTransportFocus() }
    }
    private fun styleButtons(root: View) {
        if (root is android.view.ViewGroup) { for (i in 0 until root.childCount) styleButtons(root.getChildAt(i)); return }
        if (root is ImageButton) {
            root.isFocusable = true
            root.setOnFocusChangeListener { v, on ->
                // Focused transport icons read pink and grow; no focus box, so a selected
                // button is never both filled and boxed at once.
                (v as ImageButton).imageTintList = android.content.res.ColorStateList.valueOf(if (on) pink else Color.WHITE)
                v.foreground = rippleForeground()
                v.animate().scaleX(if (on) 1.12f else 1f).scaleY(if (on) 1.12f else 1f).setDuration(120).start()
            }
        }
        // Rewind/forward-with-amount are Buttons whose arrow glyph lives in the background,
        // so tinting it is unsafe: pink digits and growth carry focus instead of a box.
        if (root is Button) {
            root.isFocusable = true
            root.setOnFocusChangeListener { v, on ->
                (v as Button).setTextColor(if (on) pink else Color.WHITE)
                v.foreground = rippleForeground()
                v.animate().scaleX(if (on) 1.12f else 1f).scaleY(if (on) 1.12f else 1f).setDuration(120).start()
            }
        }
    }
    private fun rippleForeground(): android.graphics.drawable.Drawable? {
        val t = android.util.TypedValue()
        return if (context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, t, true)) context.getDrawable(t.resourceId) else null
    }
    private fun broadcastDate(raw: String): String {
        if (raw.isBlank()) return ""
        val date = runCatching { Instant.parse(raw).atZone(java.time.ZoneId.systemDefault()).toLocalDate() }.getOrElse { runCatching { LocalDate.parse(raw.take(10)) }.getOrNull() } ?: return raw
        return date.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.UK))
    }
}
