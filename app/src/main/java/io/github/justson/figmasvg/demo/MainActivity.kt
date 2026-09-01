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
        val shapesView = findViewById<FigmaSvgView>(R.id.shapesView)
        val switchButton = findViewById<Button>(R.id.switchSourceButton)
        var showingSvg = true
        switchButton.setOnClickListener {
            showingSvg = !showingSvg
            // 两个 View 一起切换，运行时 SVG 解析与构建期 JSON 两条路都会被走到。
            sourceView.setSourceResource(
                if (showingSvg) R.raw.background else R.raw.figma_svg_background
            )
            shapesView.setSourceResource(
                if (showingSvg) R.raw.shapes else R.raw.figma_svg_shapes
            )
            switchButton.setText(if (showingSvg) R.string.show_json else R.string.show_svg)
        }
    }
}
