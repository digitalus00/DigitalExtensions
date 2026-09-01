
package com.glttv

import android.app.Dialog
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import java.lang.ref.WeakReference
import java.util.LinkedHashMap

object StoryReader {
    private const val BUTTON_TAG = "desikahaniya_reader_button"
    private const val MAX_CACHE_SIZE = 30
    private val stories = object : LinkedHashMap<String, StoryDocument>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, StoryDocument>?) = size > MAX_CACHE_SIZE
    }
    private val buttons = mutableMapOf<String, WeakReference<View>>()
    private var registeredManager: FragmentManager? = null
    private var preferences: SharedPreferences? = null

    fun initialize(context: Context) {
        preferences = context.applicationContext.getSharedPreferences("desikahaniya_stories", Context.MODE_PRIVATE)
        (context as? AppCompatActivity)?.let(::register)
        (context.applicationContext as? Application)?.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(activity: Activity, state: Bundle?) { (activity as? AppCompatActivity)?.let(::register) }
                override fun onActivityStarted(activity: Activity) = Unit
                override fun onActivityResumed(activity: Activity) { (activity as? AppCompatActivity)?.let(::register) }
                override fun onActivityPaused(activity: Activity) = Unit
                override fun onActivityStopped(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) = Unit
            },
        )
    }

    @Synchronized
    fun cache(url: String, story: StoryDocument) {
        stories[url] = story
        preferences?.edit()?.putString(url, story.title + "\u0000" + story.text)?.apply()
        buttons[url]?.get()?.post { buttons[url]?.get()?.visibility = View.VISIBLE }
    }

    fun register(activity: AppCompatActivity) {
        val manager = activity.supportFragmentManager
        if (registeredManager === manager) return
        registeredManager = manager
        manager.registerFragmentLifecycleCallbacks(
            object : FragmentManager.FragmentLifecycleCallbacks() {
                override fun onFragmentViewCreated(
                    fm: FragmentManager,
                    fragment: Fragment,
                    view: View,
                    savedInstanceState: Bundle?,
                ) {
                    val args = fragment.arguments ?: return
                    if (args.getString("apiName") != DesiKahaniya.API_NAME) return
                    val url = args.getString("url") ?: return
                    injectButton(activity, view, url)
                }
            }, true,
        )
    }

    private fun injectButton(activity: AppCompatActivity, root: View, url: String) {
        val pkg = root.context.packageName
        fun id(name: String) = root.resources.getIdentifier(name, "id", pkg)
        bindPrimaryReadAction(activity, root, url, ::id)

        val tvParent = id("result_play_parent").takeIf { it != 0 }?.let { root.findViewById<LinearLayout>(it) }
        if (tvParent != null && tvParent.findViewWithTag<View>(BUTTON_TAG) == null) {
            val button = readerButton(root.context, true) { open(activity, url) }
            val anchor = id("result_bookmark").takeIf { it != 0 }?.let { tvParent.findViewById<View>(it) }
            tvParent.addView(button, if (anchor == null) tvParent.childCount else tvParent.indexOfChild(anchor) + 1)
            showWhenReady(button, url)
            return
        }
        val phoneParent = id("media_route_button_holder").takeIf { it != 0 }?.let { root.findViewById<LinearLayout>(it) }
        if (phoneParent != null && phoneParent.findViewWithTag<View>(BUTTON_TAG) == null) {
            val button = readerButton(root.context, false) { open(activity, url) }
            phoneParent.addView(button)
            showWhenReady(button, url)
        }
    }

    private fun bindPrimaryReadAction(
        activity: AppCompatActivity,
        root: View,
        url: String,
        id: (String) -> Int,
    ) {
        val candidates = listOf(
            "result_play_movie",
            "result_play_movie_button",
            "result_play_button",
            "result_play_series",
            "result_play_series_button",
            "result_resume_series_button",
        )

        candidates.forEach { name ->
            val viewId = id(name)
            if (viewId == 0) return@forEach
            root.findViewById<View>(viewId)?.apply {
                contentDescription = "Read story"
                if (this is TextView) text = "Read"
                setOnClickListener { open(activity, url) }
            }
        }

        listOf("result_play_movie_text", "result_play_series_text").forEach { name ->
            val viewId = id(name)
            if (viewId != 0) root.findViewById<TextView>(viewId)?.text = "Read"
        }
    }

    @Synchronized
    private fun showWhenReady(button: View, url: String) {
        button.visibility = if (story(url) != null) View.VISIBLE else View.GONE
        buttons[url] = WeakReference(button)
    }

    @Synchronized
    private fun open(activity: AppCompatActivity, url: String) {
        story(url)?.let { ReaderDialog(activity, it).show() }
    }

    @Synchronized
    private fun story(url: String): StoryDocument? {
        stories[url]?.let { return it }
        val stored = preferences?.getString(url, null) ?: return null
        val separator = stored.indexOf('\u0000')
        if (separator < 0) return null
        return StoryDocument(stored.substring(0, separator), stored.substring(separator + 1)).also { stories[url] = it }
    }

    private fun readerButton(context: Context, tv: Boolean, action: () -> Unit) = TextView(context).apply {
        tag = BUTTON_TAG
        text = if (tv) "Aa\nRead" else "Aa"
        contentDescription = "Read story"
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        textSize = if (tv) 13f else 16f
        typeface = Typeface.DEFAULT_BOLD
        isClickable = true
        isFocusable = true
        setPadding(context.dp(if (tv) 14 else 7), context.dp(7), context.dp(if (tv) 14 else 7), context.dp(7))
        background = rounded(0x8C282828.toInt(), context.dp(8).toFloat())
        setOnClickListener { action() }
        setOnFocusChangeListener { view, focused ->
            view.background = rounded(if (focused) 0xFF336699.toInt() else 0x8C282828.toInt(), context.dp(8).toFloat())
        }
    }
}

private class ReaderDialog(context: Context, private val story: StoryDocument) : Dialog(context) {
    private lateinit var scroll: ScrollView
    private lateinit var body: TextView
    private lateinit var root: LinearLayout
    private var fontSize = 19f
    private var darkTheme = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(buildView())
        window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        applyTheme()
        body.requestFocus()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val amount = (scroll.height * 0.8f).toInt().coerceAtLeast(200)
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> { scroll.smoothScrollBy(0, amount); true }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_PAGE_UP -> { scroll.smoothScrollBy(0, -amount); true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { changeFont(1f); true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { changeFont(-1f); true }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun buildView(): View {
        root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(12), context.dp(8), context.dp(12), context.dp(12))
        }
        val toolbar = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        toolbar.addView(tool("Close", "X") { dismiss() })
        toolbar.addView(TextView(context).apply {
            text = story.title
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 2
            setPadding(context.dp(12), 0, context.dp(12), 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        toolbar.addView(tool("Decrease font", "A-") { changeFont(-1f) })
        toolbar.addView(tool("Increase font", "A+") { changeFont(1f) })
        toolbar.addView(tool("Change theme", "Theme") { darkTheme = !darkTheme; applyTheme() })
        root.addView(toolbar)
        body = TextView(context).apply {
            text = story.text
            setTextIsSelectable(true)
            setLineSpacing(0f, 1.35f)
            setPadding(context.dp(16), context.dp(18), context.dp(16), context.dp(40))
            isFocusable = true
            isFocusableInTouchMode = true
        }
        scroll = ScrollView(context).apply { isFillViewport = true; addView(body) }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    private fun tool(description: String, label: String, action: () -> Unit) = TextView(context).apply {
        text = label
        contentDescription = description
        gravity = Gravity.CENTER
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        isClickable = true
        isFocusable = true
        setPadding(context.dp(12), context.dp(10), context.dp(12), context.dp(10))
        setOnClickListener { action() }
    }

    private fun changeFont(delta: Float) {
        fontSize = (fontSize + delta).coerceIn(14f, 34f)
        body.textSize = fontSize
    }

    private fun applyTheme() {
        val background = if (darkTheme) 0xFF101214.toInt() else 0xFFF8F4E9.toInt()
        val foreground = if (darkTheme) 0xFFF0F0F0.toInt() else 0xFF202020.toInt()
        root.setBackgroundColor(background)
        updateColors(root, foreground)
        body.textSize = fontSize
    }

    private fun updateColors(view: View, color: Int) {
        if (view is TextView) view.setTextColor(color)
        if (view is ViewGroup) for (index in 0 until view.childCount) updateColors(view.getChildAt(index), color)
    }
}

private fun rounded(color: Int, radius: Float) = GradientDrawable().apply { setColor(color); cornerRadius = radius }
private fun Context.dp(value: Int) = (value * resources.displayMetrics.density).toInt()
