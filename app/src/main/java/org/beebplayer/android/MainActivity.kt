// SPDX-License-Identifier: GPL-3.0-or-later
package org.beebplayer.android

import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.LruCache
import android.view.KeyEvent
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import java.io.File
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private var directSetup: UhdSetupSession? = null
    private var setupProgress: AlertDialog? = null
    private var backupProgress: AlertDialog? = null
    private var pendingBackup: ByteArray? = null
    private val exportBackup = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val bytes = pendingBackup; pendingBackup = null
        if (uri != null && bytes != null) io.execute {
            val result = runCatching { contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("Unable to open backup destination") }
            bytes.fill(0)
            main.post { if (!isDestroyed) Toast.makeText(this, if (result.isSuccess) "Encrypted UHD backup saved. Keep its password separately." else "Unable to save backup", Toast.LENGTH_LONG).show() }
        } else bytes?.fill(0)
    }
    private var searchQuery = ""
    private var searchInput: EditText? = null
    private var searchUpdate: Runnable? = null
    private val searchWorker = Executors.newSingleThreadExecutor()
    private var searchTask: java.util.concurrent.Future<*>? = null
    private var searchRevision = 0
    private var refreshingCatalogue = false
    private val catalogue by lazy { CatalogueIndex(this, api) }
    private val voiceSearch = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val query = result.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (result.resultCode == RESULT_OK && !query.isNullOrBlank()) {
            searchQuery = query
            store.recordSearch(query)
            Toast.makeText(this, "Heard: “$query”", Toast.LENGTH_SHORT).show()
            search()
            content.announceForAccessibility("Heard $query")
        }
    }
    private lateinit var api: BbcApi
    private lateinit var store: LocalStore
    private lateinit var body: LinearLayout
    private lateinit var pageHeader: LinearLayout
    private val tabViews = mutableMapOf<String, LinearLayout>()
    private var platformBack: Any? = null
    private var episodePanel: android.app.Dialog? = null
    private lateinit var content: LinearLayout
    private val io = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())
    private var generation = 0
    private var currentTab = "Home"
    private val backStack = mutableListOf<() -> Unit>()
    private val pink = Color.rgb(250, 35, 137)
    private val ink = Color.rgb(16, 16, 22)
    private var homeFeed: Home? = null
    private var featureIndex = 0
    private var banner: View? = null
    private var bannerArt: ImageView? = null
    private var bannerCounter: TextView? = null
    private var bannerHeadline: TextView? = null
    private var bannerSummary: TextView? = null
    private var returnScreen: () -> Unit = { home() }
    private val rotate = object : Runnable { override fun run() { if (currentTab == "Home" && backStack.isEmpty() && body.findFocus() == null) { featureIndex++; updateBanner() }; main.postDelayed(this, 8000) } }
    private val liveRefresh = object : Runnable { override fun run() { if (currentTab == "Live" && backStack.isEmpty()) live(); main.postDelayed(this, 60_000) } }
    private var liveLoadedAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        api = BbcApi(this); store = LocalStore(this)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) { override fun handleOnBackPressed() { navigateBack() } })
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(ink); setPadding(dp(38), dp(18), dp(38), dp(12)) }
        val nav = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        nav.addView(ImageView(this).apply { setImageResource(R.drawable.beeb_mark) }, LinearLayout.LayoutParams(dp(48), dp(48)))
        for (tab in listOf("Home", "Browse", "Search", "Live", "Saved", "Settings")) {
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                isFocusable = true; isFocusableInTouchMode = true; contentDescription = tab
                setPadding(dp(18), dp(4), dp(18), 0)
                addView(label(tab, 19f).apply { gravity = Gravity.CENTER; setPadding(0, dp(6), 0, dp(8)) }, LinearLayout.LayoutParams(-2, -2))
                addView(View(this@MainActivity), LinearLayout.LayoutParams(dp(32), dp(5)))
                setOnFocusChangeListener { _, _ -> updateTabs() }
                setOnClickListener {
                    backStack.clear(); currentTab = tab
                    when (tab) { "Home" -> home(); "Browse" -> browse(); "Search" -> search(); "Live" -> live(); "Saved" -> saved(); else -> settings() }
                }
            }
            tabViews[tab] = item
            nav.addView(item, LinearLayout.LayoutParams(-2, dp(50)).apply { marginStart = dp(6) })
        }
        updateTabs()
        root.addView(nav)
        val scroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = false }
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(8), dp(10), dp(30)); clipChildren = false }
        scroll.addView(body); root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(24) }); setContentView(root)
        if (!handleLaunch(intent)) home()
        io.execute { LiveHomeChannels.refreshIfNeeded(this) }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); handleLaunch(intent) }
    private fun handleLaunch(incoming: Intent): Boolean {
        if (incoming.action == Intent.ACTION_APPLICATION_PREFERENCES) { currentTab = "Settings"; settings(); return true }
        val channel = LiveHomeChannels.channelId(incoming) ?: return false
        backStack.clear(); currentTab = "Live"; live()
        if (channel != "all") { store.lastChannel(channel); startActivity(Intent(this, PlaybackActivity::class.java).putExtra("channel", channel).putExtra("title", "BBC Live")) }
        return true
    }
    override fun onResume() { super.onResume();
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            platformBack = android.window.OnBackInvokedCallback { navigateBack() }
            onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, platformBack as android.window.OnBackInvokedCallback)
        }
 main.postDelayed(rotate, 8000); main.postDelayed(liveRefresh, 60_000); if (::store.isInitialized && currentTab == "Home" && backStack.isEmpty() && homeFeed != null) renderHome(); if (::store.isInitialized && currentTab == "Live" && backStack.isEmpty() && liveLoadedAt > 0 && System.currentTimeMillis() - liveLoadedAt > 60_000) live(focusLast = true) }
    override fun onPause() {
        if (android.os.Build.VERSION.SDK_INT >= 33) (platformBack as? android.window.OnBackInvokedCallback)?.let { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
        platformBack = null
        main.removeCallbacks(rotate); main.removeCallbacks(liveRefresh); super.onPause() }
    override fun onDestroy() { directSetup?.cancel(); setupProgress?.dismiss(); episodePanel?.dismiss(); backupProgress?.dismiss(); generation++; searchRevision++; searchTask?.cancel(true); searchWorker.shutdownNow(); main.removeCallbacksAndMessages(null); io.shutdownNow(); super.onDestroy() }
    private fun navigateBack() { if (episodePanel?.isShowing == true) episodePanel?.dismiss() else if (backStack.isNotEmpty()) backStack.removeAt(backStack.lastIndex).invoke() else if (currentTab != "Home") { currentTab = "Home"; home() } else finish() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun label(text: String, size: Float = 22f, muted: Boolean = false) = TextView(this).apply { this.text = text; textSize = size; setTextColor(if (muted) 0xFFB6B2C0.toInt() else Color.WHITE); setPadding(0, dp(6), 0, dp(8)) }
    private fun border(focused: Boolean) = GradientDrawable().apply { setColor(if (focused) 0xFF382039.toInt() else 0xFF1C1B24.toInt()); cornerRadius = dp(10).toFloat(); setStroke(dp(if (focused) 5 else 1), if (focused) pink else 0xFF47434E.toInt()) }
    private fun focus(view: View) { view.isFocusable = true; view.isFocusableInTouchMode = true; view.background = border(false); view.setOnFocusChangeListener { v, on -> v.background = border(on) } }
    private fun button(text: String, action: () -> Unit) = Button(this).apply { this.text = text; isAllCaps = false; textSize = 18f; setTextColor(Color.WHITE); setPadding(dp(20), dp(8), dp(20), dp(8)); focus(this); setOnClickListener { action() } }
    private fun updateTabs() {
        tabViews.forEach { (name, item) ->
            val selected = name == currentTab
            val focused = item.hasFocus()
            (item.getChildAt(0) as TextView).apply {
                setTextColor(if (selected || focused) Color.WHITE else 0xFFAAA9B2.toInt())
                setTypeface(null, if (selected || focused) Typeface.BOLD else Typeface.NORMAL)
            }
            item.getChildAt(1).background = GradientDrawable().apply {
                cornerRadius = dp(3).toFloat()
                setColor(if (focused || selected) pink else Color.TRANSPARENT)
            }
            item.animate().scaleX(if (focused) 1.08f else 1f).scaleY(if (focused) 1.08f else 1f).setDuration(120).start()
        }
    }
    private fun reset(title: String): Int {
        generation++; searchRevision++; searchTask?.cancel(true); searchInput = null; searchUpdate?.let(main::removeCallbacks); body.removeAllViews(); banner = null; bannerArt = null; bannerCounter = null; bannerHeadline = null; bannerSummary = null
        updateTabs()
        pageHeader = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, dp(8)) }
        if (title != "Home") {
            pageHeader.addView(label(title, 30f).apply { setTypeface(typeface, Typeface.BOLD) }, LinearLayout.LayoutParams(0, -2, 1f))
            body.addView(pageHeader)
        }
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false }; body.addView(content)
        return generation
    }
    private fun <T> load(title: String, task: () -> T, done: (T) -> Unit) {
        val id = reset(title); val spinner = label("Loading…", 18f, true); content.addView(spinner)
        io.execute { val result = runCatching(task); main.post { if (generation == id && !isDestroyed) { content.removeView(spinner); result.fold(done) { error -> content.addView(label(friendlyError(error), 19f, true)); content.addView(button("Retry") { load(title, task, done) }) } } } }
    }
    private fun image(url: String, height: Int) = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(0xFF24212A.toInt()); layoutParams = LinearLayout.LayoutParams(-1, dp(height)); contentDescription = null; Images.load(api, url, this) }
    private fun card(p: Programme, record: Progress? = null, action: () -> Unit): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(4), dp(4), dp(4), dp(4))
        val watched = record?.takeIf { !it.completed && it.duration > 0 && it.position > 0 }
        val leftMs = watched?.let { (it.duration - it.position).coerceAtLeast(0) }
        isFocusable = true; isFocusableInTouchMode = true
        contentDescription = p.title + " " + p.subtitle + if (watched != null && leftMs != null) ". ${(watched.position * 100 / watched.duration).toInt().coerceIn(0, 100)}% watched, ${Parsing.clock(leftMs)} left" else ""
        val art = image(p.image, 140).apply {
            background = GradientDrawable().apply { setColor(0xFF24212A.toInt()); cornerRadius = dp(8).toFloat() }
            clipToOutline = true
        }
        addView(art)
        val caption = label(p.subtitle.ifEmpty { p.title }, 17f).apply { maxLines = 2; minLines = 2; setPadding(dp(2), dp(10), dp(2), 0) }
        addView(caption)
        if (watched != null && leftMs != null) {
            addView(ProgressBar(this@MainActivity, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000; progress = (watched.position * 1000 / watched.duration).toInt().coerceIn(0, 1000); progressTintList = android.content.res.ColorStateList.valueOf(pink) }, LinearLayout.LayoutParams(-1, dp(8)).apply { topMargin = dp(8) })
            addView(label("${Parsing.clock(leftMs)} left", 14f, true).apply { maxLines = 1 })
        }
        if (p.uhd) addView(label(if (p.hdr) "4K HDR" else "4K", 12f, true))
        setOnFocusChangeListener { _, on ->
            if (on) Images.retry(api, p.image, art)
            art.foreground = if (on) GradientDrawable().apply { setColor(Color.TRANSPARENT); cornerRadius = dp(8).toFloat(); setStroke(dp(5), pink) } else null
            caption.setTextColor(if (on) Color.WHITE else 0xFFCDCCD4.toInt()); caption.setTypeface(null, if (on) Typeface.BOLD else Typeface.NORMAL)
            animate().scaleX(if (on) 1.025f else 1f).scaleY(if (on) 1.025f else 1f).setDuration(120).start()
        }
        setOnClickListener { action() }
    }
    private fun row(title: String, programmes: List<Programme>, onSelect: (Programme) -> Unit) {
        if (programmes.isEmpty()) return
        content.addView(label(title, 24f))
        val line = LinearLayout(this).apply { clipChildren = false; setPadding(dp(5), dp(5), dp(5), dp(10)) }
        programmes.forEach { p -> line.addView(card(p) { onSelect(p) }, LinearLayout.LayoutParams(dp(250), -2).apply { marginEnd = dp(16) }) }
        content.addView(HorizontalScrollView(this).apply { clipToPadding = false; addView(line) })
    }
    private fun grid(items: List<Programme>, onSelect: (Programme) -> Unit, progressFor: (Programme) -> Progress? = { null }) {
        val available = resources.displayMetrics.widthPixels / resources.displayMetrics.density - 116
        val columns = maxOf(2, (available / 265).toInt())
        items.chunked(columns).forEach { group ->
            val line = LinearLayout(this)
            group.forEach { p -> line.addView(card(p, progressFor(p)) { onSelect(p) }, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(4), dp(4), dp(10), dp(12)) }) }
            repeat(columns - group.size) { line.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f)) }; content.addView(line)
        }
    }
    private fun home() { returnScreen = { home() }; if (homeFeed != null) renderHome() else load("Home", { api.home() }) { homeFeed = it; renderHome() } }
    private fun updateBanner(retryFailed: Boolean = false) {
        val feed = homeFeed ?: return
        if (feed.features.isEmpty()) return
        val panel = banner ?: return
        featureIndex = Math.floorMod(featureIndex, feed.features.size)
        val f = feed.features[featureIndex]
        panel.contentDescription = "${f.headline}. Featured ${featureIndex + 1} of ${feed.features.size}. Use left and right to browse."
        bannerCounter?.text = "FEATURED  ·  ${featureIndex + 1} / ${feed.features.size}"
        bannerHeadline?.text = f.headline
        bannerSummary?.text = f.summary
        bannerArt?.let { if (retryFailed) Images.reload(api, f.image, it) else Images.load(api, f.image, it) }
    }
    private fun renderHome() {
        val feed = homeFeed ?: return; reset("Home")
        if (api.homeServedFromCache) {
            val stamp = runCatching { java.time.Instant.ofEpochMilli(api.homeCacheTime()).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm")) }.getOrDefault("an earlier visit")
            content.addView(label("You're offline. Showing saved Home from $stamp.", 17f, true))
        }
        if (!store.setupHintSeen()) {
            val hint = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(24), dp(16), dp(24), dp(16))
                background = GradientDrawable().apply { setColor(0xFF24212B.toInt()); cornerRadius = dp(12).toFloat(); setStroke(dp(2), pink) }
            }
            hint.addView(label("New here? Set up 4K UHD in Settings, or search for programmes.", 19f))
            val hintRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            hintRow.addView(button("Open Settings") { currentTab = "Settings"; backStack.clear(); settings() }, LinearLayout.LayoutParams(-2, dp(52)))
            hintRow.addView(button("Search") { currentTab = "Search"; backStack.clear(); search() }, LinearLayout.LayoutParams(-2, dp(52)).apply { marginStart = dp(16) })
            hintRow.addView(button("Dismiss") { store.dismissSetupHint(); content.removeView(hint) }, LinearLayout.LayoutParams(-2, dp(52)).apply { marginStart = dp(16) })
            hint.addView(hintRow)
            content.addView(hint, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })
        }
        if (feed.features.isNotEmpty()) {
            val panel = FrameLayout(this).apply { isFocusable = true; isFocusableInTouchMode = true }
            // Prefer the artwork ratio, capped to keep the complete card visible on a TV.
            val height = minOf(((resources.displayMetrics.widthPixels / resources.displayMetrics.density - 96) * 9 / 16).toInt(), (resources.displayMetrics.heightPixels / resources.displayMetrics.density - 128).toInt())
            val art = image("", height)
            panel.addView(art, FrameLayout.LayoutParams(-1, dp(height)))
            val overlay = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(28), dp(18), dp(28), dp(18)); background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x00101016, 0xF0101016.toInt())) }
            val counter = label("", 14f).apply { setTextColor(pink) }
            val headline = label("", 32f)
            val summary = label("", 18f).apply { maxLines = 2 }
            overlay.addView(counter); overlay.addView(headline); overlay.addView(summary); overlay.addView(label("Explore programme  ›", 16f))
            panel.addView(overlay, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
            banner = panel; bannerArt = art; bannerCounter = counter; bannerHeadline = headline; bannerSummary = summary
            panel.setOnFocusChangeListener { _, focused ->
                panel.foreground = if (focused) GradientDrawable().apply { setColor(Color.TRANSPARENT); setStroke(dp(5), pink) } else null
                if (focused) (art.tag as? String)?.let { Images.retry(api, it, art) }
            }
            panel.setOnKeyListener { _, key, event ->
                if (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        featureIndex += if (key == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1
                        updateBanner(true)
                    }
                    true
                } else false
            }
            panel.setOnClickListener { openShow(feed.features[Math.floorMod(featureIndex, feed.features.size)].show) }
            updateBanner(); content.addView(panel)
        }
        val history = store.history()
        if (history.isNotEmpty()) {
            content.addView(label("Continue Watching", 24f))
            val line = LinearLayout(this)
            history.forEach { record ->
                val target = record.target ?: record.episode
                val view = card(target.copy(subtitle = (if (record.completed) "Up next · " else "Resume · ") + target.subtitle), record.takeUnless { it.completed }) {
                    if (record.completed && record.next == null) openShow(Programme(record.episode.showId, record.episode.title)) else episode(target, record.next, record)
                }
                view.setOnLongClickListener { AlertDialog.Builder(this).setMessage("Remove ${record.episode.title} from Continue Watching? You can also remove it from its episode details.").setPositiveButton("Remove") { _, _ -> store.remove(record.episode.id); renderHome() }.setNegativeButton("Cancel", null).show(); true }
                line.addView(view, LinearLayout.LayoutParams(dp(250), -2).apply { marginEnd = dp(16) })
            }
            content.addView(HorizontalScrollView(this).apply { addView(line) })
        }
        feed.rows.take(8).forEach { (title, shows) -> row(title, shows, ::openShow) }
        content.addView(button("Refresh Home") { homeFeed = null; home() })
    }
    private fun discoveryText(text: String, action: () -> Unit) = TextView(this).apply {
        this.text=text; textSize=19f; gravity=Gravity.CENTER_VERTICAL
        isFocusable=true; isFocusableInTouchMode=true; setPadding(dp(18),dp(12),dp(18),dp(12))
        setTextColor(0xFFCDCCD4.toInt())
        setOnFocusChangeListener { _, focused ->
            setTextColor(if(focused) Color.WHITE else 0xFFCDCCD4.toInt())
            setTypeface(null,if(focused) Typeface.BOLD else Typeface.NORMAL)
            background=if(focused) GradientDrawable().apply { setColor(0xFF382039.toInt()); cornerRadius=dp(24).toFloat(); setStroke(dp(4), pink) } else null
        }
        setOnClickListener { action() }
    }
    private fun browse() {
        returnScreen = { browse() }; reset("Browse")
        content.addView(label("Discover",22f))
        content.addView(discoveryText("4K UHD   ›") { uhdCatalogue() },LinearLayout.LayoutParams(-1,dp(58)))
        content.addView(label("Categories",22f))
        val categoriesHost=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        content.addView(categoriesHost)
        content.addView(label("A–Z",22f).apply { setPadding(0,dp(22),0,dp(4)) })
        content.addView(label("Tip: Up and Down jump between rows",15f,true))
        val letters=listOf("0-9")+('a'..'z').map(Char::toString)
        val letterViews=letters.map { letter -> discoveryText(letter.uppercase()) { page(letter,false,1) }.apply { id=View.generateViewId(); tag=letter } }
        val letterRows=letterViews.chunked(9)
        val gridHost=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        letterRows.forEach { chunk ->
            val rowLine=LinearLayout(this)
            chunk.forEach { v -> rowLine.addView(v,LinearLayout.LayoutParams(if((v.tag as String)=="0-9")dp(74) else dp(52),dp(50)).apply { marginEnd=dp(6) }) }
            gridHost.addView(rowLine)
        }
        letterRows.forEach { chunk -> if(chunk.size>1) { chunk.first().nextFocusLeftId=chunk.last().id; chunk.last().nextFocusRightId=chunk.first().id } }
        for(r in letterRows.indices) for(c in letterRows[r].indices) {
            val v=letterRows[r][c]
            if(r>0) v.nextFocusUpId=letterRows[r-1][minOf(c,letterRows[r-1].size-1)].id
            if(r<letterRows.size-1) v.nextFocusDownId=letterRows[r+1][minOf(c,letterRows[r+1].size-1)].id
        }
        content.addView(gridHost)
        fun loadCategories() {
            val id=generation
            categoriesHost.removeAllViews()
            categoriesHost.addView(label("Loading categories…",16f,true))
            io.execute { val result=runCatching { api.genres() }; main.post {
                if(generation!=id || isDestroyed) return@post
                categoriesHost.removeAllViews()
                result.fold({ categories ->
                    categories.chunked(3).forEach { group -> val row=LinearLayout(this); group.forEach { (key,title) ->
                        row.addView(discoveryText(title) { page(key,true,1) },LinearLayout.LayoutParams(0,dp(54),1f).apply { marginEnd=dp(14) })
                    }; repeat(3-group.size) { row.addView(View(this),LinearLayout.LayoutParams(0,1,1f)) }; categoriesHost.addView(row) }
                },{ error ->
                    categoriesHost.addView(label(friendlyError(error)+" A–Z and 4K UHD are still available.",16f,true))
                    categoriesHost.addView(button("Retry") { loadCategories() })
                })
            } }
        }
        loadCategories()
    }
    private fun uhdCatalogue() {
        if(backStack.isEmpty()) backStack.add { browse() }
        returnScreen={ uhdCatalogue() }; reset("4K UHD")
        val countLabel=label("",16f,true); content.addView(countLabel)
        val itemsHost=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; clipChildren=false }; content.addView(itemsHost)
        val status=label("",15f,true);content.addView(status)
        var items=catalogue.allUhd()
        var shown=0
        lateinit var more: TextView
        fun append() {
            itemsHost.removeView(more)
            val next=items.drop(shown).take(30); shown+=next.size
            val previous=content; content=itemsHost
            try { grid(next,::openShow) } finally { content=previous }
            countLabel.text="${items.size} programmes"
            more.text="Show more programmes  ›"; if(shown<items.size) itemsHost.addView(more)
        }
        more=discoveryText("Show more programmes  ›") { append() }; append()
        fun updateInPlace() {
            if(!itemsHost.isAttachedToWindow) return
            val scroller=body.parent as? ScrollView
            val savedY=scroller?.scrollY ?: 0
            val focusedDesc=content.findFocus()?.contentDescription?.toString()
            items=catalogue.allUhd()
            val target=shown.coerceAtLeast(minOf(30,items.size)).coerceAtMost(items.size)
            itemsHost.removeAllViews()
            shown=0
            while(shown<target) {
                val next=items.drop(shown).take(30)
                if(next.isEmpty()) break
                shown+=next.size
                val previous=content; content=itemsHost
                try { grid(next,::openShow) } finally { content=previous }
            }
            countLabel.text="${items.size} programmes"
            more.text="Show more programmes  ›"; if(shown<items.size) itemsHost.addView(more)
            scroller?.post { scroller.scrollTo(0,savedY) }
            if(focusedDesc!=null) {
                fun findByDesc(v: View): View? {
                    if(v.contentDescription?.toString()==focusedDesc) return v
                    if(v is ViewGroup) for(i in 0 until v.childCount) findByDesc(v.getChildAt(i))?.let { return it }
                    return null
                }
                main.post { findByDesc(itemsHost)?.requestFocus() }
            }
        }
        content.addView(discoveryText("Refresh catalogue") { refreshCatalogue(status,true,::updateInPlace) })
        refreshCatalogue(status,false,::updateInPlace)
    }
    private fun refreshCatalogue(status: TextView, force: Boolean, done: () -> Unit) {
        if(refreshingCatalogue || !force && !catalogue.needsRefresh()) return
        refreshingCatalogue=true;status.text="Updating programme catalogue…"
        io.execute { val result=runCatching { catalogue.refresh() }; main.post {
            refreshingCatalogue=false
            if(!isDestroyed && status.isAttachedToWindow) result.fold({
                val stamp=java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
                status.text="Catalogue updated $stamp"
                done()
            },{ status.text="Using the saved catalogue. Refresh is unavailable right now." })
        } }
    }
    private fun search() {
        currentTab="Search"; returnScreen={ search() }; reset("Search")
        val bar=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL; setPadding(dp(10),dp(3),dp(10),dp(3)); background=GradientDrawable().apply { setColor(0xFF24212B.toInt());cornerRadius=dp(30).toFloat() } }
        val field=EditText(this).apply {
            id=View.generateViewId()
            hint="Search programmes";textSize=20f;setTextColor(Color.WHITE);setHintTextColor(0xFFAAA9B2.toInt())
            setSingleLine(true);inputType=InputType.TYPE_CLASS_TEXT;imeOptions=android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            filters=arrayOf(android.text.InputFilter.LengthFilter(200));background=null;setPadding(dp(16),dp(12),dp(16),dp(12));setText(searchQuery)
        }; searchInput=field
        fun barBackground(on: Boolean) = GradientDrawable().apply { setColor(if (on) 0xFF382039.toInt() else 0xFF24212B.toInt()); cornerRadius = dp(30).toFloat(); if (on) setStroke(dp(4), pink) }
        field.setOnFocusChangeListener { _, on -> bar.background = barBackground(on) }
        bar.addView(field,LinearLayout.LayoutParams(0,dp(58),1f))
        val mic=ImageButton(this).apply {
            contentDescription="Search by voice";setImageResource(R.drawable.search_mic);imageTintList=android.content.res.ColorStateList.valueOf(Color.WHITE)
            setPadding(dp(14),dp(14),dp(14),dp(14));isFocusable=true
            background=GradientDrawable().apply { shape=GradientDrawable.OVAL;setColor(0xFF393340.toInt()) }
            setOnFocusChangeListener { _,on -> background=GradientDrawable().apply { shape=GradientDrawable.OVAL;setColor(if(on)0xFF382039.toInt() else 0xFF393340.toInt());setStroke(dp(if(on)4 else 0), pink) } }
            setOnClickListener {
                val intent=Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT,"Search Beeb programmes")
                    .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE,java.util.Locale.getDefault().toLanguageTag())
                runCatching { voiceSearch.launch(intent) }.onFailure {
                    Toast.makeText(this@MainActivity,"Voice input is unavailable on this device. Use the keyboard instead.",Toast.LENGTH_LONG).show()
                    field.requestFocus(); (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showSoftInput(field,android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                }
            }
        }
        bar.addView(mic,LinearLayout.LayoutParams(dp(50),dp(50)))
        content.addView(bar)
        val status=label("",15f,true);content.addView(status)
        val results=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL };content.addView(results)
        fun firstFocusable(): View? {
            fun scan(v: View): View? {
                if(v!==field && v!==results && v.isFocusable) return v
                if(v is ViewGroup) for(i in 0 until v.childCount) scan(v.getChildAt(i))?.let { return it }
                return null
            }
            return scan(results)
        }
        field.setOnKeyListener { _, keyCode, event ->
            if(keyCode==KeyEvent.KEYCODE_DPAD_DOWN && event.action==KeyEvent.ACTION_DOWN) firstFocusable()?.requestFocus()==true else false
        }
        var lastQuery: String? = null
        var lastShown = 9
        fun render(moveFocus: Boolean = false) {
            if(searchInput!==field) return
            val query=searchQuery; val revision=++searchRevision
            searchTask?.cancel(true)
            searchTask=searchWorker.submit {
                val blank=query.isBlank()
                val matches=if(blank) emptyList() else try { catalogue.search(query) } catch (_: java.util.concurrent.CancellationException) { return@submit }
                val recents=if(blank) store.recentSearches() else emptyList()
                val suggestions=if(blank) runCatching { catalogue.allUhd().take(9) }.getOrDefault(emptyList()) else emptyList()
                main.post {
                    if(isDestroyed || searchInput!==field || revision!=searchRevision) return@post
                    val scroller=body.parent as? ScrollView
                    val savedY=scroller?.scrollY ?: 0
                    val focusedDesc=results.findFocus()?.contentDescription?.toString()
                    val keepFocus=results.findFocus()!=null && !moveFocus
                    results.removeAllViews()
                    fun select(p: Programme) { store.recordSearch(query); openShow(p) }
                    if(blank) {
                        results.addView(label("Find programmes by title or description",18f,true))
                        if(recents.isNotEmpty()) {
                            results.addView(label("Recent searches",22f))
                            recents.forEach { recent ->
                                results.addView(discoveryText(recent) {
                                    store.recordSearch(recent)
                                    searchQuery=recent
                                    field.setText(recent)
                                    field.setSelection(recent.length)
                                    searchUpdate?.let(main::removeCallbacks)
                                    render()
                                },LinearLayout.LayoutParams(-1,dp(54)))
                            }
                        }
                        if(suggestions.isNotEmpty()) {
                            results.addView(label("Suggested programmes",22f))
                            val previous=content;content=results
                            try { grid(suggestions,::openShow) } finally { content=previous }
                        }
                        if(recents.isEmpty() && suggestions.isEmpty())
                            results.addView(label("Type above, or use voice search, to find programmes.",17f,true))
                    } else {
                        results.addView(label("${matches.size} ${if(matches.size==1) "result" else "results"} for “$query”",18f,true))
                        var shown=0;lateinit var more: TextView
                        fun append() {
                            val moreFocused=more.hasFocus()
                            results.removeView(more); val next=matches.drop(shown).take(if(shown==0)9 else 30);shown+=next.size
                            lastShown=shown; lastQuery=query
                            val base=results.childCount
                            val previous=content;content=results
                            try { grid(next, { p -> select(p) }) } finally { content=previous }
                            if(shown<matches.size)results.addView(more)
                            if(moreFocused) {
                                var target: View?=null
                                for(i in base until results.childCount) {
                                    val row=results.getChildAt(i)
                                    if(row is ViewGroup) for(j in 0 until row.childCount) {
                                        val card=row.getChildAt(j)
                                        if(card.isFocusable) { target=card; break }
                                    }
                                    if(target!=null) break
                                }
                                (target ?: more.takeIf { it.parent!=null })?.requestFocus()
                            }
                        }
                        more=discoveryText("More results  ›") { append() }
                        val restoreTarget=if(query==lastQuery) lastShown.coerceAtMost(matches.size) else minOf(9,matches.size)
                        do { append() } while(shown<restoreTarget && shown<matches.size)
                    }
                    firstFocusable()?.let { first ->
                        if(first.id==View.NO_ID) first.id=View.generateViewId()
                        field.nextFocusDownId=first.id
                        first.nextFocusUpId=field.id
                    }
                    scroller?.post { scroller.scrollTo(0,savedY) }
                    if(moveFocus) firstFocusable()?.requestFocus()
                    else if(keepFocus && focusedDesc!=null) {
                        fun findByDesc(v: View): View? {
                            if(v.contentDescription?.toString()==focusedDesc) return v
                            if(v is ViewGroup) for(i in 0 until v.childCount) findByDesc(v.getChildAt(i))?.let { return it }
                            return null
                        }
                        results.post { findByDesc(results)?.requestFocus() }
                    }
                }
            }
        }
        field.addTextChangedListener(object:android.text.TextWatcher {
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int)=Unit
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int) { searchQuery=s.toString().take(200); searchRevision++; searchTask?.cancel(true); searchUpdate?.let(main::removeCallbacks);searchUpdate=Runnable { render() };main.postDelayed(searchUpdate!!,350) }
            override fun afterTextChanged(s:android.text.Editable?)=Unit
        })
        field.setOnEditorActionListener { _,action,event ->
            if(action==android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH || event?.keyCode==KeyEvent.KEYCODE_ENTER) {
                (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(field.windowToken,0)
                store.recordSearch(searchQuery);searchUpdate?.let(main::removeCallbacks);render(true);true
            } else false
        }
        content.addView(discoveryText("Refresh catalogue") { refreshCatalogue(status,true) { render() } })
        render();refreshCatalogue(status,false) { render() }
    }
    private fun page(key: String, category: Boolean, number: Int) {
        if (backStack.isEmpty()) backStack.add { browse() }
        returnScreen = { page(key, category, number) }
        load(key.replaceFirstChar(Char::uppercase), { api.page(key, category, number) }) { result ->
            fun pageBar() = LinearLayout(this).apply { if (number > 1) addView(button("‹ Previous") { page(key, category, number - 1) }); addView(label("  Page $number / ${result.totalPages}  ", 18f)); if (number < result.totalPages) addView(button("Next ›") { page(key, category, number + 1) }) }
            content.addView(pageBar())
            grid(result.shows, ::openShow)
            content.addView(pageBar())
        }
    }
    private fun openShow(show: Programme) {
        val previousScreen = returnScreen
        backStack.add(previousScreen)
        load(show.title, { api.episodes(show) }) { episodes ->
            io.execute { runCatching { catalogue.discover(show,episodes) } }
            content.addView(label(show.summary, 19f, true))
            val bookmarkLabel = label("", 16f).apply { setPadding(0, 0, dp(10), 0) }
            val bookmark = ImageButton(this).apply {
                setPadding(dp(12), dp(12), dp(12), dp(12)); isFocusable = true
                fun update() {
                    val saved = store.isSaved(show)
                    setImageResource(if (saved) R.drawable.bookmark_filled else R.drawable.bookmark_outline)
                    imageTintList = android.content.res.ColorStateList.valueOf(if (saved) pink else Color.WHITE)
                    contentDescription = if (saved) "Remove saved programme" else "Save programme"
                    if (android.os.Build.VERSION.SDK_INT >= 26) tooltipText = contentDescription
                    isSelected = saved
                    bookmarkLabel.text = if (saved) "Saved ✓" else "Save"
                    bookmarkLabel.setTextColor(if (saved) pink else Color.WHITE)
                }
                fun backgroundFor(on: Boolean) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (on) 0xFF382039.toInt() else 0xFF24212B.toInt()); if (on) setStroke(dp(4), pink) }
                background = backgroundFor(false)
                setOnFocusChangeListener { _, on -> background = backgroundFor(on) }
                update()
                setOnClickListener { store.toggle(show); update(); announceForAccessibility(if (store.isSaved(show)) "Programme saved" else "Programme removed from Saved") }
            }
            pageHeader.addView(LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; addView(bookmarkLabel); addView(bookmark, LinearLayout.LayoutParams(dp(48), dp(48))) })
            if (episodes.isEmpty()) content.addView(label("No episodes available.", 20f))
            val episodeHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val groups = episodes.groupBy { it.series }
            fun showSeries(items: List<Programme>, page: Int = 1) {
                episodeHost.removeAllViews()
                val previous = content; content = episodeHost
                fun seriesBar() = LinearLayout(this).apply { if (page > 1) addView(button("‹ Previous episodes") { showSeries(items, page - 1) }); if (page * 30 < items.size) addView(button("More episodes ›") { showSeries(items, page + 1) }) }
                if (items.size > 30) content.addView(seriesBar())
                val watchedById = store.history().filter { !it.completed }.associateBy { it.episode.id }
                grid(items.drop((page - 1) * 30).take(30), { p ->
                    val index = episodes.indexOfFirst { it.id == p.id }; val candidate = episodes.getOrNull(index + 1)
                    val next = candidate?.takeIf { p.position > 0 && it.position > 0 || p.date.isNotEmpty() && it.date.isNotEmpty() }
                    episode(p, next)
                }, { watchedById[it.id] })
                if (items.size > 30) content.addView(seriesBar())
                content = previous
            }
            if (groups.size > 1) {
                val line = LinearLayout(this).apply { setPadding(0, dp(8), 0, dp(12)) }
                var selected = groups.keys.first()
                fun updateSeries() {
                    for (i in 0 until line.childCount) (line.getChildAt(i) as TextView).apply {
                        val active = tag == selected
                        setTextColor(if (active || hasFocus()) Color.WHITE else 0xFFB6B2C0.toInt()); setTypeface(null, if (active || hasFocus()) Typeface.BOLD else Typeface.NORMAL)
                        background = GradientDrawable().apply { cornerRadius = dp(24).toFloat(); setColor(if (active) pink else if (hasFocus()) 0xFF382039.toInt() else Color.TRANSPARENT); if (!active && hasFocus()) setStroke(dp(4), pink) }
                    }
                }
                groups.forEach { (title, items) ->
                    val tab = TextView(this).apply {
                        tag = title; text = if (title == "Episodes") "Specials" else title; textSize = 17f; gravity = Gravity.CENTER; isFocusable = true
                        setPadding(dp(22), dp(10), dp(22), dp(10))
                        setOnFocusChangeListener { _, _ -> updateSeries() }
                        setOnClickListener { selected = title; updateSeries(); showSeries(items) }
                    }
                    line.addView(tab, LinearLayout.LayoutParams(-2, dp(44)).apply { marginEnd = dp(10) })
                }
                updateSeries()
                content.addView(HorizontalScrollView(this).apply { addView(line) })
            }
            content.addView(episodeHost); groups.values.firstOrNull()?.let { showSeries(it) }
        }
    }
    private fun seriesPosition(p: Programme): Pair<String, String>? {
        val series = Regex("(?:Series|Season)\\s+(\\d+)", RegexOption.IGNORE_CASE).find(p.series)?.groupValues?.get(1)?.toIntOrNull()
        return when {
            series != null && p.position > 0 -> "S$series E${p.position}" to "Series $series, episode ${p.position}"
            p.position > 0 -> "Episode ${p.position}" to "Episode ${p.position}"
            series != null -> "Series $series" to "Series $series"
            else -> null
        }
    }
    private fun episode(p: Programme, next: Programme?, record: Progress? = null) {
        episodePanel?.dismiss()
        val dialog = android.app.Dialog(this)
        episodePanel = dialog
        val surface = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply { setColor(0xFF201F27.toInt()); cornerRadius = dp(24).toFloat() }
            clipToOutline = true
        }
        val dialogArt = image(p.image, 390)
        surface.addView(dialogArt, LinearLayout.LayoutParams(0, -1, .40f))
        Images.reload(api, p.image, dialogArt)
        val info = RelativeLayout(this).apply { setPadding(dp(30), dp(24), dp(30), dp(24)); clipChildren = true; clipToPadding = true }
        val header = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; id=View.generateViewId() }
        header.addView(label(p.title, 14f, true).apply { setTextColor(pink); letterSpacing = .08f; maxLines=1; ellipsize=android.text.TextUtils.TruncateAt.END })
        header.addView(label(p.subtitle.ifEmpty { p.title }, 24f).apply { maxLines = 2; ellipsize=android.text.TextUtils.TruncateAt.END; setTypeface(null, Typeface.BOLD) })
        val badges = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        if (p.uhd) badges.addView(TextView(this).apply {
            text = if (p.hdr) "UHD · 4K HDR" else "UHD · 4K"
            textSize = 13f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
            setPadding(dp(12), dp(4), dp(12), dp(4))
            background = GradientDrawable().apply { setColor(0xFF382039.toInt()); cornerRadius = dp(12).toFloat(); setStroke(dp(2), pink) }
            contentDescription = if (p.hdr) "Available in ultra high definition with high dynamic range" else "Available in ultra high definition"
        })
        seriesPosition(p)?.let { (short, spoken) ->
            badges.addView(label(short, 14f, true).apply {
                setPadding(dp(if (badges.childCount > 0) 12 else 0), 0, 0, 0)
                contentDescription = spoken
            })
        }
        if (badges.childCount > 0) header.addView(badges)
        val date = runCatching { java.time.LocalDate.parse(p.date.take(10)).format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.UK)) }.getOrDefault(p.date.take(10))
        val meta = listOf(p.duration, date, if (p.uhd) { if (p.hdr) "4K HDR available" else "4K available" } else "").filter { it.isNotBlank() }.joinToString("   ·   ")
        header.addView(label(meta, 13f, true).apply { maxLines=1; ellipsize=android.text.TextUtils.TruncateAt.END })
        val resumable = store.progress(p)
        if (resumable != null && resumable.duration > 0) {
            val left = Parsing.clock((resumable.duration - resumable.position).coerceAtLeast(0))
            header.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000; progress = (resumable.position * 1000 / resumable.duration).toInt().coerceIn(0, 1000); progressTintList = android.content.res.ColorStateList.valueOf(pink) }, LinearLayout.LayoutParams(-1, dp(8)).apply { topMargin = dp(8) })
            header.addView(label("${Parsing.clock(resumable.position)} of ${Parsing.clock(resumable.duration)} watched · $left left", 13f, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        }
        info.addView(header,RelativeLayout.LayoutParams(-1,-2).apply { addRule(RelativeLayout.ALIGN_PARENT_TOP) })
        val description = label(p.summary, 16f).apply { setTextColor(0xFFD5D3DD.toInt()); setLineSpacing(dp(3).toFloat(), 1f) }
        val descriptionScroll = ScrollView(this).apply { id=View.generateViewId(); contentDescription="Programme description"; isFocusable = true; isFillViewport=false; clipChildren=true; clipToPadding=true; isVerticalScrollBarEnabled=true; addView(description) }
        val scrollCue = label("▼ More below — press Down to scroll", 13f, true).apply { id = View.generateViewId(); gravity = Gravity.CENTER; visibility = View.GONE }
        val actions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; id=View.generateViewId(); gravity = Gravity.CENTER_VERTICAL; clipChildren = false; clipToPadding = false }
        val primaryRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val playButton = button(resumable?.let { "Resume · ${Parsing.clock((it.duration - it.position).coerceAtLeast(0))} left" } ?: "Play") { dialog.dismiss(); play(p, next, false) }
        primaryRow.addView(playButton, LinearLayout.LayoutParams(-2, dp(54)))
        if (resumable != null) primaryRow.addView(button("Start over") { dialog.dismiss(); play(p, next, true) }, LinearLayout.LayoutParams(-2, dp(54)).apply { marginStart = dp(16) })
        actions.addView(primaryRow)
        val secondaryRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, 0) }
        val showRef = Programme(p.showId, p.title)
        val saveButton = button(if (store.isSaved(showRef)) "Saved ✓" else "Save") {}
        saveButton.setOnClickListener { store.toggle(showRef); saveButton.text = if (store.isSaved(showRef)) "Saved ✓" else "Save"; saveButton.announceForAccessibility(if (store.isSaved(showRef)) "Programme saved" else "Programme removed from Saved") }
        secondaryRow.addView(saveButton, LinearLayout.LayoutParams(-2, dp(48)))
        // Only offered when the episode actually has a Continue Watching record;
        // first-time items show Save alone.
        val doomed = (record?.episode ?: p).id
        if (hasHistoryRecord(store.history(), doomed) || hasHistoryRecord(store.history(), p.id)) {
            secondaryRow.addView(button("Remove from history") {
                store.remove(doomed)
                if (doomed != p.id) store.remove(p.id)
                Toast.makeText(this, "Removed from Continue Watching", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
                if (currentTab == "Home" && backStack.isEmpty()) renderHome()
            }, LinearLayout.LayoutParams(-2, dp(48)).apply { marginStart = dp(16) })
        }
        actions.addView(secondaryRow)
        info.addView(actions,RelativeLayout.LayoutParams(-1,-2).apply { addRule(RelativeLayout.ALIGN_PARENT_BOTTOM) })
        info.addView(scrollCue,RelativeLayout.LayoutParams(-1,-2).apply { addRule(RelativeLayout.ABOVE,actions.id); bottomMargin=dp(4) })
        info.addView(descriptionScroll,RelativeLayout.LayoutParams(-1,-1).apply { addRule(RelativeLayout.BELOW,header.id); addRule(RelativeLayout.ABOVE,scrollCue.id); topMargin=dp(6); bottomMargin=dp(4) })
        fun updateScrollCue(scrollY: Int = descriptionScroll.scrollY) {
            val child = descriptionScroll.getChildAt(0)
            val overflow = (child?.height ?: 0) - descriptionScroll.height
            scrollCue.visibility = if (overflow > 0 && scrollY < overflow - dp(8)) View.VISIBLE else View.GONE
        }
        descriptionScroll.post { updateScrollCue(0) }
        descriptionScroll.setOnScrollChangeListener { _, _, scrollY, _, _ -> updateScrollCue(scrollY) }
        playButton.nextFocusUpId=descriptionScroll.id
        descriptionScroll.nextFocusDownId=playButton.id.takeIf {it!=View.NO_ID} ?: View.generateViewId().also {playButton.id=it}
        surface.addView(info, LinearLayout.LayoutParams(0, -1, .60f))
        dialog.setContentView(surface)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(.82f)
        }
        dialog.setOnDismissListener { episodePanel = null }
        dialog.show()
        dialog.window?.setLayout(resources.displayMetrics.widthPixels - dp(96), dp(390))
        playButton.requestFocus()
    }
    private fun play(p: Programme, next: Programme?, restart: Boolean) { startActivity(Intent(this, PlaybackActivity::class.java).putExtra("episode", p.json().toString()).putExtra("next", next?.json()?.toString()).putExtra("restart", restart)) }
    private fun saved() { returnScreen = { saved() }; reset("Saved programmes"); val items = store.saved(); if (items.isEmpty()) content.addView(label("Save a programme from its episode page.", 20f, true)); grid(items, ::openShow) }
    private fun live(focusLast: Boolean = false) { returnScreen = { live() }; load("Live", { api.channels() }) { channels ->
        liveLoadedAt = System.currentTimeMillis()
        val last = store.lastChannel()
        liveGrid(channels, last) { c -> store.lastChannel(c.id); startActivity(Intent(this, PlaybackActivity::class.java).putExtra("channel", c.id).putExtra("title", c.title + " · " + c.name)) }
        content.addView(label("Updated " + java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")), 15f, true))
        if (focusLast) content.post { content.findViewWithTag<View>("live:" + last.ifEmpty { channels.firstOrNull()?.id })?.requestFocus() }
    } }
    private fun liveCard(c: Channel, lastWatched: Boolean, action: () -> Unit): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(4), dp(4), dp(4), dp(4)); tag = "live:" + c.id
        isFocusable = true; isFocusableInTouchMode = true
        contentDescription = c.name + ". Now: " + c.title + (c.progress?.let { ", ${(it * 100).toInt()}% through" } ?: "") + (if (c.nextTitle.isNotEmpty()) ". Next: ${c.nextTitle}" else "") + (if (lastWatched) ". Last watched" else "")
        val art = image(c.image, 140).apply {
            background = GradientDrawable().apply { setColor(0xFF24212A.toInt()); cornerRadius = dp(8).toFloat() }
            clipToOutline = true
        }
        addView(art)
        addView(label(c.name, 18f).apply { maxLines = 1; setTypeface(null, Typeface.BOLD); setPadding(dp(2), dp(10), dp(2), 0) })
        val now = label(c.title, 16f).apply { maxLines = 2; minLines = 2; setPadding(dp(2), dp(2), dp(2), 0) }
        addView(now)
        c.progress?.let { addView(ProgressBar(this@MainActivity, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000; progress = (it * 1000).toInt(); progressTintList = android.content.res.ColorStateList.valueOf(pink) }, LinearLayout.LayoutParams(-1, dp(8)).apply { topMargin = dp(6) }) }
        if (c.nextTitle.isNotEmpty()) addView(label("Next: " + c.nextTitle, 14f, true).apply { maxLines = 1; setPadding(dp(2), dp(4), dp(2), 0) })
        if (lastWatched) addView(label("● Last watched", 13f).apply { setTextColor(pink); setPadding(dp(2), dp(4), dp(2), 0) })
        setOnFocusChangeListener { _, on ->
            if (on) Images.retry(api, c.image, art)
            art.foreground = if (on) GradientDrawable().apply { setColor(Color.TRANSPARENT); cornerRadius = dp(8).toFloat(); setStroke(dp(5), pink) } else null
            now.setTextColor(if (on) Color.WHITE else 0xFFCDCCD4.toInt())
            animate().scaleX(if (on) 1.025f else 1f).scaleY(if (on) 1.025f else 1f).setDuration(120).start()
        }
        setOnClickListener { action() }
    }
    private fun liveGrid(channels: List<Channel>, last: String, onSelect: (Channel) -> Unit) {
        val available = resources.displayMetrics.widthPixels / resources.displayMetrics.density - 116
        val columns = maxOf(2, (available / 265).toInt())
        channels.chunked(columns).forEach { group ->
            val line = LinearLayout(this)
            group.forEach { c -> line.addView(liveCard(c, last.isNotEmpty() && c.id == last) { onSelect(c) }, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(4), dp(4), dp(10), dp(12)) }) }
            repeat(columns - group.size) { line.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f)) }; content.addView(line)
        }
    }
    private fun saveBackupToDownloads(bytes: ByteArray) {
        if (android.os.Build.VERSION.SDK_INT < 29) {
            pendingBackup = bytes
            runCatching { exportBackup.launch("Beeb-UHD-backup.beeb") }.onFailure { pendingBackup?.fill(0); pendingBackup = null; Toast.makeText(this, "No file picker is available. Keep the original TwinVine configuration on your computer.", Toast.LENGTH_LONG).show() }
            return
        }
        showBackupProgress("Saving encrypted backup…")
        io.execute {
            val result = runCatching {
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Downloads.DISPLAY_NAME, "Beeb-UHD-${java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))}.beeb")
                    put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                    put(android.provider.MediaStore.Downloads.RELATIVE_PATH, "Download/Beeb/")
                    put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("Unable to create backup")
                try {
                    contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("Unable to write backup")
                    contentResolver.update(uri, android.content.ContentValues().apply { put(android.provider.MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                } catch (e: Exception) { contentResolver.delete(uri, null, null); throw e }
            }
            bytes.fill(0)
            main.post { if (!isDestroyed) { backupProgress?.dismiss(); backupProgress = null; Toast.makeText(this, if (result.isSuccess) "Encrypted backup saved in Downloads/Beeb. Copy it to your computer and keep the password separately." else "Unable to save backup to Downloads", Toast.LENGTH_LONG).show() } }
        }
    }
    private fun showBackupProgress(title: String) {
        backupProgress?.dismiss()
        backupProgress = AlertDialog.Builder(this).setTitle(title).setMessage("This can take a little while on a TV.").setView(ProgressBar(this)).create().also { it.show() }
    }
    private fun passwordPrompt(title: String, confirm: Boolean, action: (CharArray) -> Unit) {
        val fields = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), 0) }
        fun field(hint: String) = EditText(this).apply { this.hint = hint; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD; isSingleLine = true; fields.addView(this) }
        val first = field("Backup password (at least 12 characters)")
        val second = if (confirm) field("Repeat password") else null
        val dialog = AlertDialog.Builder(this).setTitle(title).setView(fields).setPositiveButton(if (confirm) "Save backup" else "Restore", null).setNegativeButton("Cancel", null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val password = first.text.toString().toCharArray()
            if (password.size < 12) { first.error = "Use at least 12 characters"; password.fill('\u0000') }
            else if (second != null && first.text.toString() != second.text.toString()) { second.error = "Passwords do not match"; password.fill('\u0000') }
            else { first.setText(""); second?.setText(""); dialog.dismiss(); action(password) }
        } }
        dialog.show()
    }
    private fun enableDirectUhd() {
        if(directSetup!=null)return
        val session=UhdSetupSession();directSetup=session
        val dialog=AlertDialog.Builder(this).setTitle("Enable BBC UHD").setMessage("Downloading and validating UHD setup…")
            .setView(ProgressBar(this)).setNegativeButton("Cancel") { _,_ -> if(session.cancel() && directSetup===session)directSetup=null }
            .setOnCancelListener { if(session.cancel() && directSetup===session)directSetup=null }.create()
        setupProgress=dialog;dialog.show()
        io.execute {
            val result=runCatching { DirectUhdSetup.enable(api,session) { message -> main.post { if(directSetup===session && !isDestroyed)dialog.setMessage(message) } } }
            main.post { if(directSetup===session && !isDestroyed) {
                directSetup=null;setupProgress=null;dialog.dismiss();settings()
                val message=result.fold({ title -> "Connected. BBC returned a 4K HLG stream for $title." },{ error ->
                    if(error is java.util.concurrent.CancellationException) "Setup cancelled. Your existing setup has been kept."
                    else if(error is DirectUhdSetup.SetupError) error.message.orEmpty()
                    else "Could not verify BBC UHD access. Your existing setup has been kept. Check your connection and try again."
                })
                AlertDialog.Builder(this).setTitle(if(result.isSuccess)"BBC UHD connected" else "BBC UHD setup").setMessage(message).setPositiveButton("OK",null).show()
            } }
        }
    }
    private fun settings() {
        returnScreen = { settings() }
        reset("Settings")
        content.addView(label("Beeb for Android TV · ${BuildConfig.VERSION_NAME}", 22f))
        content.addView(label("4K UHD playback", 24f))
        val uhdConfigured = api.vault.configured()
        if (uhdConfigured) {
            content.addView(label("✓ " + api.vault.status(), 19f).apply { setTypeface(typeface, Typeface.BOLD) })
            content.addView(label("UHD is ready on this TV. Reconnect only if playback stops working.",18f,true))
            content.addView(discoveryText("Reconnect BBC UHD") { enableDirectUhd() })
        } else {
            content.addView(label(api.vault.status(), 19f, true))
            content.addView(label("Automatically configure UHD playback on this TV. No computer required.",18f,true))
            content.addView(discoveryText("Enable BBC UHD") { enableDirectUhd() })
        }
        content.addView(label(UhdSupport.inspect(this).description, 17f, true))
        if (api.vault.configured()) content.addView(discoveryText("Export encrypted UHD backup") {
            passwordPrompt("Protect UHD backup", true) { password -> showBackupProgress("Encrypting UHD backup…"); io.execute {
                val result = runCatching { api.vault.exportBackup(password) }; password.fill('\u0000')
                main.post { if (!isDestroyed) { backupProgress?.dismiss(); backupProgress = null; result.fold({ bytes -> saveBackupToDownloads(bytes) }, { Toast.makeText(this, "Unable to export this identity", Toast.LENGTH_LONG).show() }) } }
            } }
        })
        if (api.vault.configured()) content.addView(discoveryText("Forget UHD identity") { AlertDialog.Builder(this).setMessage("Remove UHD setup from this app?").setPositiveButton("Forget") { _, _ -> api.vault.forget(); settings() }.setNegativeButton("Cancel", null).show() })
        if (android.os.Build.VERSION.SDK_INT >= 26) content.addView(label("TV home", 22f))
        if (android.os.Build.VERSION.SDK_INT >= 26) content.addView(discoveryText("Add live-channel cards to TV home") {
            val busy = Toast.makeText(this, "Updating live-channel cards…", Toast.LENGTH_SHORT); busy.show()
            io.execute { val result = runCatching { LiveHomeChannels.publish(this, api.channels()) }; main.post { if (!isDestroyed) result.fold({ id ->
                LiveHomeChannels.requestVisible(this, id)
                Toast.makeText(this, "Live-channel cards published. Visibility and placement depend on your TV launcher.", Toast.LENGTH_LONG).show()
            }, { Toast.makeText(this, "The launcher could not accept the live-channel row.", Toast.LENGTH_LONG).show() }) } }
        })
        content.addView(label("Subtitles", 22f))
        lateinit var captionRow: TextView
        captionRow = discoveryText("Captions on 10-second rewind: ${if (store.autoCaptions()) "On" else "Off"}") {
            store.autoCaptions(!store.autoCaptions())
            captionRow.text = "Captions on 10-second rewind: ${if (store.autoCaptions()) "On" else "Off"}"
            captionRow.announceForAccessibility(if (store.autoCaptions()) "Rewind captions on" else "Rewind captions off")
        }
        content.addView(captionRow)
        content.addView(discoveryText("Android caption appearance settings") { runCatching { startActivity(Intent(android.provider.Settings.ACTION_CAPTIONING_SETTINGS)) }.onFailure { Toast.makeText(this, "Caption settings are unavailable on this device", Toast.LENGTH_LONG).show() } })
        content.addView(label("Your data", 22f))
        content.addView(discoveryText("Clear Continue Watching") {
            AlertDialog.Builder(this).setMessage("Remove everything from Continue Watching?").setPositiveButton("Clear") { _, _ ->
                store.clearHistory()
                Toast.makeText(this, "Continue Watching cleared", Toast.LENGTH_SHORT).show()
            }.setNegativeButton("Cancel", null).show()
        })
        content.addView(discoveryText("Clear Saved programmes") {
            AlertDialog.Builder(this).setMessage("Remove all Saved programmes?").setPositiveButton("Clear") { _, _ ->
                store.clearSaved()
                Toast.makeText(this, "Saved programmes cleared", Toast.LENGTH_SHORT).show()
            }.setNegativeButton("Cancel", null).show()
        })
        content.addView(discoveryText("Clear image cache") {
            AlertDialog.Builder(this).setMessage("Clear cached programme art? Images will load again as you browse.").setPositiveButton("Clear") { _, _ ->
                Images.clear(this)
                Toast.makeText(this, "Image cache cleared", Toast.LENGTH_SHORT).show()
            }.setNegativeButton("Cancel", null).show()
        })
        content.addView(label("Independent BBC player · GPL-3.0-or-later", 17f, true))
    }
}

private object Images {
    private const val DISK_NAME = "images"
    private const val MAX_BYTES = 4_000_000
    private const val MAX_FILES = 200
    private val executor = Executors.newFixedThreadPool(4)
    private val memory = object : LruCache<String, Bitmap>(32 * 1024) { override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024 }
    private val failed = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private var placeholder: Bitmap? = null
    private var failureTile: Bitmap? = null
    @Synchronized private fun placeholderTile(): Bitmap = placeholder ?: tile(null).also { placeholder = it }
    @Synchronized private fun failureBitmap(): Bitmap = failureTile ?: tile("Artwork unavailable").also { failureTile = it }
    private fun tile(text: String?): Bitmap {
        val bitmap = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(if (text == null) 0xFF24212A.toInt() else 0xFF2B2531.toInt())
        if (text != null) canvas.drawText(text, 320f, 192f, android.graphics.Paint().apply {
            color = 0xFFB6B2C0.toInt(); textSize = 34f; textAlign = android.graphics.Paint.Align.CENTER; isAntiAlias = true
        })
        return bitmap
    }
    private fun diskFile(context: Context, url: String): File {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8))
        return File(File(context.cacheDir, DISK_NAME), digest.joinToString("") { "%02x".format(it) } + ".img")
    }
    private fun trim(context: Context) {
        val files = File(context.cacheDir, DISK_NAME).listFiles()?.sortedBy { it.lastModified() } ?: return
        if (files.size > MAX_FILES) files.take(files.size - MAX_FILES).forEach { runCatching { it.delete() } }
    }
    private fun fetch(api: BbcApi, url: String): ByteArray? = runCatching {
        api.http.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) return@use null
            r.body?.byteStream()?.use { input ->
                val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (true) { val n = input.read(buffer); if (n < 0) break; if (out.size() + n > MAX_BYTES) return@use null; out.write(buffer, 0, n) }
                out.toByteArray()
            }
        }
    }.getOrNull()
    fun clear(context: Context? = null) {
        memory.evictAll(); failed.clear()
        context?.let { ctx -> executor.execute { runCatching { File(ctx.cacheDir, DISK_NAME).deleteRecursively() } } }
    }
    fun retry(api: BbcApi, url: String, target: ImageView) { if (url.isNotEmpty() && failed.remove(url)) load(api, url, target) }
    fun reload(api: BbcApi, url: String, target: ImageView) { failed.remove(url); load(api, url, target) }
    fun load(api: BbcApi, url: String, target: ImageView) {
        target.tag = url
        if (url.isEmpty()) { target.setImageBitmap(placeholderTile()); return }
        memory.get(url)?.let { target.setImageBitmap(it); return }
        if (failed.contains(url)) { target.setImageBitmap(failureBitmap()); return }
        target.setImageBitmap(placeholderTile())
        val appContext = target.context.applicationContext
        executor.execute {
            val disk = diskFile(appContext, url)
            var bitmap = if (disk.exists()) runCatching { BitmapFactory.decodeFile(disk.absolutePath) }.getOrNull() else null
            if (bitmap == null) {
                val bytes = fetch(api, url)
                bitmap = bytes?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull() }
                if (bitmap != null && bytes != null) runCatching { disk.parentFile?.mkdirs(); disk.writeBytes(bytes); trim(appContext) }
            }
            if (bitmap != null) {
                failed.remove(url); memory.put(url, bitmap)
                target.post { if (target.tag == url) target.setImageBitmap(bitmap) }
            } else {
                failed.add(url)
                val tile = failureBitmap()
                target.post { if (target.tag == url) target.setImageBitmap(tile) }
            }
        }
    }
}
