# Figma SVG View

把 Figma 导出的 SVG 放进 Android 工程，由通用 `FigmaSvgView` 直接读取 SVG，或者使用可选的 Gradle plugin 在构建期转成更紧凑的 JSON 绘制参数。View 会自动识别两种格式。

仓库内包含：

- `figma-svg-core`：SVG 解析与校验的共享源码，构建期与运行期共用同一份（不是 Gradle 模块，见其 README）
- `figma-svg-plugin`：可选的 SVG → JSON 绘制参数 Gradle plugin
- `figma-svg-view`：通用 Android View
- `app`：可运行 Demo，含模糊椭圆背景与 path / transform 验证画面

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
    implementation("com.github.Justson.figma-svg-view:figma-svg-view:1.1.0")
}
```

aar 的 pom 不带任何传递依赖，不会影响宿主工程的版本。代价是 `androidx.core`（`PathParser` 用来解析 path data）、`androidx.annotation` 与 `kotlin-stdlib` 都声明为 `compileOnly`，需要由使用方提供——任何用到 appcompat 的工程都已经有了。

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
    id("io.github.justson.figma-svg") version "1.1.0"
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
- `<path>`，含 `fill-rule="evenodd"` 与全部路径命令（含弧线）
- `<ellipse>`
- 十六进制 `fill`、`fill-opacity`、`opacity`
- `transform`：`translate` / `scale` / `rotate` / `skewX` / `skewY` / `matrix`，可嵌套组合
- 单个共享的矩形 alpha `<mask>`
- Figma 常见的透明 `feFlood`、normal `feBlend`、`feGaussianBlur`
- `filterUnits="userSpaceOnUse"` 的滤镜裁剪范围
- `fit_xy`、`fit_center`、`center_crop`
- Gradle 增量转换与构建缓存

遇到尚未支持的节点时，转换器会让构建失败并指出具体节点，避免静默生成错误 UI。目前会被拒绝的包括：渐变 `fill`、`stroke`、`<text>`、`<image>`、非矩形或渐变 mask、非 normal blend、`feGaussianBlur` 以外的滤镜链（阴影、`feColorMatrix` 等）。

还有一条与模糊相关的限制：**带 `feGaussianBlur` 的图形只允许平移、旋转和等比缩放**。高斯模糊在用户空间是各向同性的，相似变换下它仍是各向同性的（只是被缩放），`BlurMaskFilter` 能精确还原；而 skew 或非等比缩放会要求各向异性模糊，`BlurMaskFilter` 做不到，所以这种组合会让构建失败而不是画错。

### 不打算支持的

- **模糊任意子树**：`BlurMaskFilter` 模糊的是形状的 alpha 遮罩，多色内容会糊成一团。做对需要离屏渲染 + 真高斯，而 `RenderEffect` 要 API 31+、RenderScript 已废弃
- **纯几何图标**：没有模糊的图标直接用 VectorDrawable（Android Studio 可以直接导入 SVG），系统级渲染更成熟。这个库存在的理由是 VectorDrawable 做不了高斯模糊

## 本仓库构建

需要 JDK 17、Android SDK 36：

```bash
./gradlew :app:assembleDebug
```

`figma-svg-plugin` 是一个独立的 composite build，由根 `settings.gradle.kts` 的 `pluginManagement { includeBuild(...) }` 引入，Demo 因此可以直接应用它而不需要先发布。

发布验证：

```bash
./gradlew -p figma-svg-plugin publishToMavenLocal -PVERSION=1.1.0
./gradlew :figma-svg-view:publishToMavenLocal -PVERSION=1.1.0
```

## License

MIT
