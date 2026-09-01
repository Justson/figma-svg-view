# figma-svg-core

SVG 解析、校验与 spec 编码的共享实现。**这不是一个 Gradle 模块**，而是一个被两边同时编译的源码目录：

- `figma-svg-plugin` —— 构建期解析 SVG 并编码成 JSON
- `figma-svg-view` —— 运行期解析 SVG（或解码 JSON）

两边各自通过 `srcDir("../figma-svg-core/src/main/kotlin")` 引入。

## 为什么不做成独立模块

做成模块要么让 aar 多一个传递依赖（与「零传递依赖、不干扰宿主工程版本」的策略冲突），要么要处理 composite build 之间的依赖替换和 JitPack 多 artifact 发布。共享源码目录用最小代价达到了真正的目的：**支持范围只定义一次**，构建期拒绝的节点和运行期拒绝的节点永远一致。

## 约束

这里的代码只能用纯 JVM API（`javax.xml`、`java.math`、Kotlin 标准库）。

- 不能引用 `android.*`，否则 Gradle 插件编译不过
- 不能引用 `org.gradle.*`，否则 Android 侧编译不过
- 不能用 `org.json`（JVM 上没有）——JSON 写在 `FigmaSvgSpecCodec`，读在 `figma-svg-view` 的 `FigmaSvgSourceParser`
