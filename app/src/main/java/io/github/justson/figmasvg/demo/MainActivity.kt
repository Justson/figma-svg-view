package io.github.justson.figmasvg.demo

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import io.github.justson.figmasvg.FigmaSvgView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val sourceView = findViewById<FigmaSvgView>(R.id.figmaSvgView)
        val switchButton = findViewById<Button>(R.id.switchSourceButton)
        var showingSvg = true
        switchButton.setOnClickListener {
            showingSvg = !showingSvg
            sourceView.setSourceResource(
                if (showingSvg) R.raw.background else R.raw.figma_svg_background
            )
            switchButton.setText(if (showingSvg) R.string.show_json else R.string.show_svg)
        }
    }
}
