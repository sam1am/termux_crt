package com.termux.crt

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import com.termux.R
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.settings_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val s = CrtSettings.load(this)
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        // ----- Master CRT toggle. -----
        root.addView(sectionHeader("CRT Overlay"))
        root.addView(masterToggle(s.crtEnabled))

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

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(root, ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        setContentView(scroll)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
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
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        container.addView(sw)
        container.addView(valueRow)
        container.addView(seek)
        parent.addView(container)
    }

    private fun saveEffect(key: String, enabled: Boolean, strength: Float) {
        CrtSettings.saveEffect(this, key, CrtSettings.Effect(enabled, strength))
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
}
