# Figma SVG View

把 Figma 导出的 SVG 放进 Android 工程，由通用 `FigmaSvgView` 直接读取 SVG，或者使用可选的 Gradle plugin 在构建期转成更紧凑的 JSON 绘制参数。View 会自动识别两种格式。

仓库内包含：

- `figma-svg-plugin`：可选的 SVG → JSON 绘制参数 Gradle plugin
- `figma-svg-view`：通用 Android View
- `app`：使用 `背景.svg` 的可运行 Demo

## 快速使用

在 `settings.gradle.kts` 中加入 JitPack 仓库。只用 View 的话，加 `dependencyResolutionManagement` 一段即可；要用 plugin 再加 `pluginManagement` 一段：

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven("https://jitpack.io")
    }
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "io.github.justson.figma-svg") {
                useModule("com.github.Justson.figma-svg-view:figma-svg-plugin:${requested.version}")
            }
        }
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}
```

然后依赖 View：

```kotlin
dependencies {
    implementation("com.github.Justson.figma-svg-view:figma-svg-view:1.0.0")
}
```

两种使用方式可以按模块自由选择，也可以混用。

### 方式一：直接 SVG，不使用 plugin

把 SVG 放入普通 raw 资源目录：

```text
app/src/main/res/raw/background.svg
```

布局直接引用：

```xml
<io.github.justson.figmasvg.FigmaSvgView
    android:layout_width="match_parent"
    android:layout_height="358dp"
    app:figmaSvgSource="@raw/background"
    app:figmaSvgScaleType="fit_xy" />
```

这种方式接入最简单，但首次显示时需要解析 SVG，原始 SVG 也会进入 APK。

### 方式二：plugin 生成 JSON

在需要转换 SVG 的 Android 模块应用插件：

```kotlin
plugins {
    id("io.github.justson.figma-svg") version "1.0.0"
}
```

plugin 只在构建期运行，不会给 APK 增加运行时依赖。

把符合 Android 资源命名规则的文件放入：

```text
app/src/main/figmaSvg/background.svg
```

在布局中引用自动生成的 JSON 资源：

```xml
<io.github.justson.figmasvg.FigmaSvgView
    android:layout_width="match_parent"
    android:layout_height="358dp"
    app:figmaSvgSource="@raw/figma_svg_background"
    app:figmaSvgScaleType="fit_xy" />
```

也可以在代码中切换：

```kotlin
figmaSvgView.setSourceResource(R.raw.background) // SVG
figmaSvgView.setSourceResource(R.raw.figma_svg_background) // generated JSON
```

JSON 构建产物位于 `build/generated/figmaSvg/<sourceSet>/res/raw/`。原始 SVG 只作为构建输入，不进入 APK；同名 flavor 文件遵循 Android source set 的资源覆盖规则。

## 当前支持范围

- `viewBox`
- `<ellipse>` 与十六进制 `fill` / opacity
- 单个共享的矩形 alpha `<mask>`
- Figma 常见的透明 `feFlood`、normal `feBlend`、`feGaussianBlur`
- `filterUnits="userSpaceOnUse"` 的滤镜裁剪范围
- `fit_xy`、`fit_center`、`center_crop`
- Gradle 增量转换与构建缓存

遇到 `path`、`transform`、复杂 mask、非 normal blend 或其他尚未支持的节点时，转换器会让构建失败并指出具体节点，避免静默生成错误 UI。

## 本仓库构建

需要 JDK 17、Android SDK 36：

```bash
./gradlew :app:assembleDebug
```

`figma-svg-plugin` 是一个独立的 composite build，由根 `settings.gradle.kts` 的 `pluginManagement { includeBuild(...) }` 引入，Demo 因此可以直接应用它而不需要先发布。

发布验证：

```bash
./gradlew -p figma-svg-plugin publishToMavenLocal -PVERSION=1.0.0
./gradlew :figma-svg-view:publishToMavenLocal -PVERSION=1.0.0
```

## License

MIT
