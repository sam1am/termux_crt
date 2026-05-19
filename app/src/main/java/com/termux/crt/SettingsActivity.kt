package com.termux.crt

import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import com.termux.R
import org.json.JSONObject
import org.json.JSONTokener
import org.json.JSONArray
import kotlin.math.roundToInt

/**
 * Hand-rolled CRT settings screen.
 *
 * One row per effect (10 of them, cool-retro-term style), each with a Switch
 * for enable + a SeekBar for intensity. Writes directly to SharedPreferences
 * on every change; the renderer picks up the new values on the next frame via
 * `applySettings()` (called from TermuxActivity.onResume).
 */
class SettingsActivity : AppCompatActivity() {

    // SAF launchers — registered in onCreate before the picker can fire.
    private lateinit var exportLauncher: ActivityResultLauncher<Intent>
    private lateinit var importLauncher: ActivityResultLauncher<Intent>

    // Live preview pinned above the scrolling settings; the renderer pulls
    // its frames from [previewSurface] (a static, hand-painted sample
    // bitmap) so the shader effects animate over consistent content.
    private lateinit var preview: CrtSurfaceView
    private lateinit var previewSurface: PreviewTerminalSurface
    private lateinit var root: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var previewFrame: FrameLayout
    private var previewNormalHeightPx: Int = 0
    private var previewEnabled: Boolean = true

    // Theater-mode state: when a slider is being dragged, the row containing
    // it stays visible and every other top-level row in [root] is hidden, and
    // the preview expands to fill the freed vertical space.
    private val theaterPrevVisibilities = mutableMapOf<View, Int>()
    private var activeRowTop: View? = null

    // Listens for any pref edit (every slider change writes immediately) and
    // forwards the fresh settings to the preview renderer. Re-rasterizes the
    // sample bitmap when the chosen font changes.
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (!::preview.isInitialized) return@OnSharedPreferenceChangeListener
        val s = CrtSettings.load(this)
        preview.applySettings(s.copy(crtEnabled = true))
        if (key == CrtSettings.KEY_FONT) {
            previewSurface.setStyle(TerminalFont.typeface(this, s.font))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.settings_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        exportLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode != RESULT_OK) return@registerForActivityResult
            result.data?.data?.let { writeAllProfilesToUri(it) }
        }
        importLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode != RESULT_OK) return@registerForActivityResult
            result.data?.data?.let { readProfilesFromUri(it) }
        }

        val s = CrtSettings.load(this)
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        // ----- Master CRT toggle. -----
        root.addView(sectionHeader("CRT Overlay"))
        root.addView(masterToggle(s.crtEnabled))
        previewEnabled = CrtSettings.prefs(this).getBoolean(CrtSettings.KEY_PREVIEW_ENABLED, true)
        root.addView(Switch(this).apply {
            text = "Show live preview"
            setTextColor(Color.WHITE)
            textSize = 16f
            isChecked = previewEnabled
            setOnCheckedChangeListener { _, checked ->
                previewEnabled = checked
                CrtSettings.prefs(this@SettingsActivity).edit {
                    putBoolean(CrtSettings.KEY_PREVIEW_ENABLED, checked)
                }
                previewFrame.visibility = if (checked) View.VISIBLE else View.GONE
            }
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        })

        root.addView(divider(dp(1)))

        // ----- Font. -----
        root.addView(sectionHeader(getString(R.string.settings_font)))
        root.addView(intSlider(
            label = getString(R.string.settings_font_size),
            valueText = { "$it sp" },
            min = 8, max = 32, initial = s.fontSizeSp,
            onChange = { CrtSettings.prefs(this).edit { putInt(CrtSettings.KEY_FONT_SIZE_SP, it) } },
        ))
        root.addView(fontPicker(s.font) { picked ->
            CrtSettings.prefs(this).edit { putString(CrtSettings.KEY_FONT, picked.name) }
        })

        root.addView(divider(dp(1)))

        // ----- Colors. -----
        root.addView(sectionHeader("Colors"))
        root.addView(colorRow(
            label = "Background color",
            initialOn = s.bgColorOverride,
            initialColor = s.bgColor,
            onKey = CrtSettings.KEY_BG_COLOR_ON,
            colorKey = CrtSettings.KEY_BG_COLOR,
        ))
        root.addView(colorRow(
            label = "Text color override",
            initialOn = s.textColorOverride,
            initialColor = s.textColor,
            onKey = CrtSettings.KEY_TEXT_COLOR_ON,
            colorKey = CrtSettings.KEY_TEXT_COLOR,
            mixKey = CrtSettings.KEY_TEXT_COLOR_MIX,
            initialMix = s.textColorMix,
        ))

        root.addView(divider(dp(1)))

        // ----- Effects (cool-retro-term style). -----
        effectRow(root, "Bloom",          "bloom",     s.bloom)
        effectRow(root, "Burn-in",        "burnin",    s.burnin)
        effectRow(root, "Static Noise",   "static",    s.staticNoise)
        effectRow(root, "Jitter",         "jitter",    s.jitter)
        effectRow(root, "Glow Line",      "glowline",  s.glowLine)
        effectRow(root, "Screen Curvature", "curvature", s.curvature)
        effectRow(root, "Ambient Light",  "ambient",   s.ambient)
        effectRow(root, "Flicker",        "flicker",   s.flicker)
        effectRow(root, "Horizontal Sync", "hsync",    s.hsync)
        effectRow(root, "RGB Shift",      "rgbshift",  s.rgbShift)

        root.addView(divider(dp(1)))

        // ----- Profiles. -----
        root.addView(sectionHeader("Profiles"))
        root.addView(profilesSection())

        root.addView(divider(dp(1)))

        root.addView(Button(this).apply {
            text = getString(R.string.settings_reset)
            setOnClickListener {
                CrtSettings.reset(this@SettingsActivity)
                recreate()
            }
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                topMargin = dp(16)
            }
        })

        scroll = ScrollView(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(root, ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }

        // Compact full-width preview at the top in normal mode. When the
        // user begins dragging a slider we expand it to fill the screen
        // (see [enterTheaterMode]) — the small/wide aspect in normal mode
        // is just a glanceable indicator; theater mode is where the user
        // actually tunes.
        previewSurface = PreviewTerminalSurface().apply {
            setStyle(TerminalFont.typeface(this@SettingsActivity, s.font))
        }
        preview = CrtSurfaceView(this).apply {
            attach(previewSurface)
            applySettings(s.copy(crtEnabled = true))
        }
        previewNormalHeightPx = dp(140)
        previewFrame = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, previewNormalHeightPx)
            addView(preview, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            visibility = if (previewEnabled) View.VISIBLE else View.GONE
        }

        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            addView(previewFrame)
            addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        }
        setContentView(outer)

        CrtSettings.prefs(this).registerOnSharedPreferenceChangeListener(prefsListener)
    }

    override fun onResume() {
        super.onResume()
        if (::preview.isInitialized) preview.onResume()
    }

    override fun onPause() {
        if (::preview.isInitialized) preview.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        CrtSettings.prefs(this).unregisterOnSharedPreferenceChangeListener(prefsListener)
        super.onDestroy()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    // ----- Theater mode -----

    /**
     * Expand the preview to fill the screen above the active slider while
     * the user is dragging — every other top-level row in [root] is hidden,
     * the preview gets weight=1, and the ScrollView shrinks to fit just the
     * row containing the slider. Restored on [exitTheaterMode].
     */
    private fun enterTheaterMode(sb: SeekBar) {
        if (!::root.isInitialized || activeRowTop != null || !previewEnabled) return
        val topLevel = findTopLevelChild(sb, root) ?: return
        activeRowTop = topLevel

        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            if (child !== topLevel) {
                theaterPrevVisibilities[child] = child.visibility
                child.visibility = View.GONE
            }
        }
        (previewFrame.layoutParams as LinearLayout.LayoutParams).apply {
            height = 0
            weight = 1f
        }
        (scroll.layoutParams as LinearLayout.LayoutParams).apply {
            height = ViewGroup.LayoutParams.WRAP_CONTENT
            weight = 0f
        }
        previewFrame.requestLayout()
        scroll.requestLayout()
    }

    private fun exitTheaterMode() {
        if (activeRowTop == null) return
        for ((v, vis) in theaterPrevVisibilities) v.visibility = vis
        theaterPrevVisibilities.clear()
        (previewFrame.layoutParams as LinearLayout.LayoutParams).apply {
            height = previewNormalHeightPx
            weight = 0f
        }
        (scroll.layoutParams as LinearLayout.LayoutParams).apply {
            height = 0
            weight = 1f
        }
        previewFrame.requestLayout()
        scroll.requestLayout()
        activeRowTop = null
    }

    private fun findTopLevelChild(view: View, parent: ViewGroup): View? {
        var current: View = view
        var p: ViewGroup? = view.parent as? ViewGroup
        while (p != null && p !== parent) {
            current = p
            p = current.parent as? ViewGroup
        }
        return if (p === parent) current else null
    }

    // ----- Row builders -----

    private fun effectRow(
        parent: LinearLayout,
        label: String,
        key: String,
        initial: CrtSettings.Effect,
    ) {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        parent.addView(divider(dp(1)))

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }
        val sw = Switch(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 16f
            isChecked = initial.enabled
        }
        val seek = SeekBar(this).apply {
            max = 100
            progress = (initial.strength * 100).roundToInt().coerceIn(0, 100)
            isEnabled = initial.enabled
        }
        val valueLabel = TextView(this).apply {
            text = String.format("%.2f", initial.strength)
            setTextColor(Color.WHITE)
            gravity = Gravity.END
        }
        val valueRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(TextView(this@SettingsActivity).apply {
                text = "Strength"
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            })
            addView(valueLabel)
        }

        sw.setOnCheckedChangeListener { _, checked ->
            seek.isEnabled = checked
            saveEffect(key, checked, seek.progress / 100f)
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                val v = p / 100f
                valueLabel.text = String.format("%.2f", v)
                saveEffect(key, sw.isChecked, v)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) { sb?.let { enterTheaterMode(it) } }
            override fun onStopTrackingTouch(sb: SeekBar?) { exitTheaterMode() }
        })

        container.addView(sw)
        container.addView(valueRow)
        container.addView(seek)
        parent.addView(container)
    }

    private fun saveEffect(key: String, enabled: Boolean, strength: Float) {
        CrtSettings.saveEffect(this, key, CrtSettings.Effect(enabled, strength))
    }

    /**
     * On/off switch + a row of preset swatches + R/G/B sliders for fine
     * tuning. Persists every change immediately to SharedPreferences under
     * [onKey] (boolean) and [colorKey] (packed ARGB int).
     */
    private fun colorRow(
        label: String,
        initialOn: Boolean,
        initialColor: Int,
        onKey: String,
        colorKey: String,
        mixKey: String? = null,
        initialMix: Float = 1f,
    ): View {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }

        var currentColor = initialColor

        val sw = Switch(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 16f
            isChecked = initialOn
        }

        val swatch = View(this).apply {
            setBackgroundColor(currentColor or Color.BLACK)  // force opaque preview
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(20)).apply {
                topMargin = dp(4)
                bottomMargin = dp(4)
            }
        }

        // Three R/G/B sliders. They share `currentColor` and the swatch view.
        fun makeChannel(
            name: String,
            initial: Int,
            extract: (Int) -> Int,
            apply: (Int, Int) -> Int,
        ): LinearLayout {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            }
            val nameLabel = TextView(this).apply {
                text = name
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(dp(20), WRAP_CONTENT)
            }
            val valueLabel = TextView(this).apply {
                text = initial.toString()
                setTextColor(Color.WHITE)
                gravity = Gravity.END
                layoutParams = LinearLayout.LayoutParams(dp(36), WRAP_CONTENT)
            }
            val seek = SeekBar(this).apply {
                max = 255
                progress = initial
                isEnabled = sw.isChecked
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                        valueLabel.text = p.toString()
                        currentColor = apply(currentColor, p)
                        swatch.setBackgroundColor(currentColor or Color.BLACK)
                        CrtSettings.prefs(this@SettingsActivity).edit {
                            putInt(colorKey, currentColor)
                        }
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) {}
                })
            }
            row.addView(nameLabel)
            row.addView(seek)
            row.addView(valueLabel)
            return row
        }

        val rSeek = makeChannel("R", Color.red(initialColor),
            { Color.red(it) }, { c, v -> Color.rgb(v, Color.green(c), Color.blue(c)) })
        val gSeek = makeChannel("G", Color.green(initialColor),
            { Color.green(it) }, { c, v -> Color.rgb(Color.red(c), v, Color.blue(c)) })
        val bSeek = makeChannel("B", Color.blue(initialColor),
            { Color.blue(it) }, { c, v -> Color.rgb(Color.red(c), Color.green(c), v) })

        // Preset swatches — quick picks for the common CRT phosphor colors.
        val presetRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                topMargin = dp(4)
                bottomMargin = dp(4)
            }
        }
        val presets = intArrayOf(
            Color.BLACK,
            Color.rgb(0, 0x22, 0),       // dark green CRT background
            Color.rgb(0x1A, 0x0F, 0),    // dark amber background
            Color.WHITE,
            Color.rgb(0x33, 0xFF, 0x33), // phosphor green
            Color.rgb(0xFF, 0xB0, 0x00), // amber
            Color.rgb(0x7D, 0xF9, 0xFF), // ice blue
        )
        for (preset in presets) {
            presetRow.addView(View(this).apply {
                setBackgroundColor(preset or Color.BLACK)
                layoutParams = LinearLayout.LayoutParams(0, dp(24), 1f).apply {
                    marginStart = dp(2)
                    marginEnd = dp(2)
                }
                setOnClickListener {
                    if (!sw.isChecked) return@setOnClickListener
                    // Update each slider's progress; their listeners write
                    // the new packed color to prefs and refresh the swatch.
                    // Slider is the second child of each row (after R/G/B label).
                    (rSeek.getChildAt(1) as SeekBar).progress = Color.red(preset)
                    (gSeek.getChildAt(1) as SeekBar).progress = Color.green(preset)
                    (bSeek.getChildAt(1) as SeekBar).progress = Color.blue(preset)
                }
            })
        }

        // Optional Mix slider — only present when the caller passed a [mixKey].
        // At 1.0 the color override fully replaces the source; at lower values
        // the source bleeds through and the override layers on top.
        var mixSeek: SeekBar? = null
        var mixRow: LinearLayout? = null
        if (mixKey != null) {
            val nameLabel = TextView(this).apply {
                text = "Mix"
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(dp(36), WRAP_CONTENT)
            }
            val valueLabel = TextView(this).apply {
                text = String.format("%.2f", initialMix)
                setTextColor(Color.WHITE)
                gravity = Gravity.END
                layoutParams = LinearLayout.LayoutParams(dp(48), WRAP_CONTENT)
            }
            mixSeek = SeekBar(this).apply {
                max = 100
                progress = (initialMix * 100).roundToInt().coerceIn(0, 100)
                isEnabled = sw.isChecked
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                        val v = p / 100f
                        valueLabel.text = String.format("%.2f", v)
                        CrtSettings.prefs(this@SettingsActivity).edit { putFloat(mixKey, v) }
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) {}
                })
            }
            mixRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                addView(nameLabel)
                addView(mixSeek)
                addView(valueLabel)
            }
        }

        sw.setOnCheckedChangeListener { _, checked ->
            CrtSettings.prefs(this@SettingsActivity).edit { putBoolean(onKey, checked) }
            (rSeek.getChildAt(1) as SeekBar).isEnabled = checked
            (gSeek.getChildAt(1) as SeekBar).isEnabled = checked
            (bSeek.getChildAt(1) as SeekBar).isEnabled = checked
            mixSeek?.isEnabled = checked
        }

        container.addView(sw)
        container.addView(swatch)
        container.addView(presetRow)
        container.addView(rSeek)
        container.addView(gSeek)
        container.addView(bSeek)
        mixRow?.let { container.addView(it) }
        return container
    }

    private fun masterToggle(initial: Boolean): View {
        return Switch(this).apply {
            text = "Enable CRT shader"
            setTextColor(Color.WHITE)
            textSize = 16f
            isChecked = initial
            setOnCheckedChangeListener { _, checked ->
                CrtSettings.prefs(this@SettingsActivity).edit { putBoolean(CrtSettings.KEY_CRT_ENABLED, checked) }
            }
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }
    }

    // ----- Helpers carried over from before -----

    private fun sectionHeader(text: String): View {
        val density = resources.displayMetrics.density
        return TextView(this).apply {
            this.text = text
            textSize = 16f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, (density * 12).toInt(), 0, (density * 4).toInt())
        }
    }

    private fun intSlider(
        label: String,
        valueText: (Int) -> String,
        min: Int,
        max: Int,
        initial: Int,
        onChange: (Int) -> Unit,
    ): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }
        val labelRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val name = TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        }
        val value = TextView(this).apply {
            text = valueText(initial)
            setTextColor(Color.WHITE)
            gravity = Gravity.END
        }
        labelRow.addView(name)
        labelRow.addView(value)
        val seek = SeekBar(this).apply {
            this.max = max - min
            progress = (initial - min).coerceIn(0, max - min)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    val v = p + min
                    value.text = valueText(v)
                    onChange(v)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        container.addView(labelRow)
        container.addView(seek)
        return container
    }

    private fun fontPicker(current: CrtFont, onPick: (CrtFont) -> Unit): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }
        val group = android.widget.RadioGroup(this).apply {
            orientation = android.widget.RadioGroup.VERTICAL
        }
        val idMap = mutableMapOf<Int, CrtFont>()
        var initialId = -1
        CrtFont.entries.forEach { font ->
            val rb = android.widget.RadioButton(this).apply {
                id = View.generateViewId()
                text = font.displayName
                setTextColor(Color.WHITE)
                try {
                    typeface = TerminalFont.typeface(this@SettingsActivity, font)
                } catch (_: Throwable) { /* fall back to default */ }
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            }
            idMap[rb.id] = font
            if (font == current) initialId = rb.id
            group.addView(rb)
        }
        if (initialId != -1) group.check(initialId)
        group.setOnCheckedChangeListener { _, checkedId -> idMap[checkedId]?.let(onPick) }
        container.addView(group)
        return container
    }

    private fun divider(heightPx: Int): View {
        val density = resources.displayMetrics.density
        return View(this).apply {
            setBackgroundColor(Color.argb(64, 255, 255, 255))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, heightPx).apply {
                topMargin = (density * 8).toInt()
                bottomMargin = (density * 8).toInt()
            }
        }
    }

    // ----- Profiles -----

    private fun profilesSection(): View {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }

        // Save-as row: name field + Save button. Captures the *current*
        // SharedPreferences-resident settings under the typed name.
        val nameField = EditText(this).apply {
            hint = "Profile name"
            setHintTextColor(Color.argb(160, 255, 255, 255))
            setTextColor(Color.WHITE)
            setSingleLine(true)
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        }
        val saveBtn = Button(this).apply {
            text = "Save"
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                marginStart = dp(8)
            }
        }
        val saveRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(nameField)
            addView(saveBtn)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }
        saveBtn.setOnClickListener {
            val name = nameField.text.toString().trim()
            if (name.isEmpty()) {
                toast("Enter a profile name")
                return@setOnClickListener
            }
            confirmIfExists(name) {
                CrtProfileStore.save(
                    this,
                    CrtProfile(name, CrtSettings.load(this)),
                )
                toast("Saved \"$name\"")
                recreate()
            }
        }
        container.addView(saveRow)

        // Export/Import row.
        val exportBtn = Button(this).apply {
            text = "Export…"
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
                marginEnd = dp(4)
            }
            setOnClickListener { launchExport() }
        }
        val importBtn = Button(this).apply {
            text = "Import…"
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
                marginStart = dp(4)
            }
            setOnClickListener { launchImport() }
        }
        val ioRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(exportBtn)
            addView(importBtn)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                topMargin = dp(8)
            }
        }
        container.addView(ioRow)

        // Saved profiles list.
        val profiles = CrtProfileStore.list(this)
        if (profiles.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "No saved profiles yet."
                setTextColor(Color.argb(160, 255, 255, 255))
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                    topMargin = dp(8)
                }
            })
        } else {
            profiles.forEach { container.addView(profileRow(it)) }
        }
        return container
    }

    private fun profileRow(profile: CrtProfile): View {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                topMargin = dp(6)
            }
        }
        row.addView(TextView(this).apply {
            text = profile.name
            setTextColor(Color.WHITE)
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        })
        row.addView(Button(this).apply {
            text = "Load"
            setOnClickListener {
                CrtSettings.saveAll(this@SettingsActivity, profile.settings)
                toast("Loaded \"${profile.name}\"")
                recreate()
            }
        })
        row.addView(Button(this).apply {
            text = "Delete"
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                marginStart = dp(4)
            }
            setOnClickListener {
                AlertDialog.Builder(this@SettingsActivity)
                    .setTitle("Delete profile")
                    .setMessage("Delete \"${profile.name}\"?")
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        CrtProfileStore.delete(this@SettingsActivity, profile.name)
                        recreate()
                    }
                    .show()
            }
        })
        return row
    }

    private fun confirmIfExists(name: String, onConfirmed: () -> Unit) {
        if (!CrtProfileStore.exists(this, name)) {
            onConfirmed()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Overwrite profile?")
            .setMessage("A profile named \"$name\" already exists.")
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("Overwrite") { _, _ -> onConfirmed() }
            .show()
    }

    private fun launchExport() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "crt-profiles.json")
        }
        try {
            exportLauncher.launch(intent)
        } catch (t: Throwable) {
            toast("No file picker available")
        }
    }

    private fun launchImport() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        try {
            importLauncher.launch(intent)
        } catch (t: Throwable) {
            toast("No file picker available")
        }
    }

    private fun writeAllProfilesToUri(uri: Uri) {
        val profiles = CrtProfileStore.list(this)
        if (profiles.isEmpty()) {
            toast("No profiles to export")
            return
        }
        try {
            val bytes = CrtProfile.bundleToJson(profiles).toString(2).toByteArray()
            contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
                ?: throw IllegalStateException("Couldn't open file for writing")
            toast("Exported ${profiles.size} profile(s)")
        } catch (t: Throwable) {
            toast("Export failed: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    private fun readProfilesFromUri(uri: Uri) {
        try {
            val text = contentResolver.openInputStream(uri)?.use {
                it.reader(Charsets.UTF_8).readText()
            } ?: throw IllegalStateException("Couldn't open file for reading")
            val parsed = when (val token = JSONTokener(text).nextValue()) {
                is JSONObject -> CrtProfile.bundleFromJson(token)
                is JSONArray  -> CrtProfile.bundleFromJson(token)
                else -> throw IllegalArgumentException("Not a JSON document")
            }
            if (parsed.isEmpty()) {
                toast("No profiles found in file")
                return
            }
            parsed.forEach { CrtProfileStore.save(this, it) }
            toast("Imported ${parsed.size} profile(s)")
            recreate()
        } catch (t: Throwable) {
            toast("Import failed: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
