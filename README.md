# Figma SVG View

把 Figma 导出的 SVG 放进 Android 工程，Gradle 会在构建期自动转换成紧凑的绘制参数，运行时由一个通用 `FigmaSvgView` 绘制。原始 SVG 只是构建输入，不会被打进 APK。

仓库内包含：

- `buildSrc`：SVG → JSON 绘制参数的 Gradle 插件
- `figma-svg-view`：通用 Android View
- `app`：使用 `背景.svg` 的可运行 Demo

## 快速使用

当前仓库提供源码集成方式：把 `buildSrc` 和 `figma-svg-view` 目录复制到目标工程，并在目标工程的 `settings.gradle.kts` 中加入：

```kotlin
include(":figma-svg-view")
```

然后在需要转换 SVG 的 Android 模块中应用插件和 View 依赖：

```kotlin
plugins {
    id("io.github.justson.figma-svg")
}

dependencies {
    implementation(project(":figma-svg-view"))
}
```

把符合 Android 资源命名规则的文件放入：

```text
app/src/main/figmaSvg/background.svg
```

在布局中引用自动生成的资源：

```xml
<io.github.justson.figmasvg.FigmaSvgView
    android:layout_width="match_parent"
    android:layout_height="358dp"
    app:figmaSvgSpec="@raw/figma_svg_background"
    app:figmaSvgScaleType="fit_xy" />
```

也可以在代码中切换：

```kotlin
figmaSvgView.setSpecResource(R.raw.figma_svg_background)
```

构建产物位于 `build/generated/figmaSvg/<sourceSet>/res/raw/`。同名 flavor 文件会遵循 Android source set 的资源覆盖规则。

## 当前支持范围

- `viewBox`
- `<ellipse>` 与十六进制 `fill` / opacity
- 单个共享的矩形 alpha `<mask>`
- Figma 常见的透明 `feFlood`、normal `feBlend`、`feGaussianBlur`
- `filterUnits="userSpaceOnUse"` 的滤镜裁剪范围
- `fit_xy`、`fit_center`、`center_crop`
- Gradle 增量转换与构建缓存

遇到 `path`、`transform`、复杂 mask、非 normal blend 或其他尚未支持的节点时，转换器会让构建失败并指出具体节点，避免静默生成错误 UI。

## 构建

需要 JDK 17、Android SDK 36：

```bash
./gradlew :app:assembleDebug
```

## License

MIT
