# MyReader

MyReader 是一个原生 Android Java 阅读器应用。

## 功能

- 书架页面
- 从手机本地导入 `.txt`、`.epub` 文档
- 阅读 txt、epub 内容
- EPUB 阅读会保留正文图片，并按页面宽高缩放显示
- 阅读页按需加载当前位置附近内容，不会打开时一次性读入整本小说
- 阅读页使用分页显示，不使用滑动滚动阅读
- 阅读页全屏显示；点击左半屏上一页，右半屏下一页，点击中间区域显示或隐藏底部状态栏
- 底部状态栏显示时间、阅读进度、书架、字体大小和阅读设置按钮
- 自动保存阅读进度，下次打开继续上次位置
- 阅读设置：
  - 背景色
  - 字体色
  - 背景色和字体色支持 RGB 自定义
  - 字体大小
  - 行间距，支持压缩到比默认更紧
  - 字间距，支持压缩到比默认更紧
  - 左右边距，最小 0
  - 上下边距，最小 0

## 构建环境

工程复用 `D:/03_work/09_android/FilmWord` 下的 Android SDK、JDK 和 Gradle wrapper：

- Android Gradle Plugin: `8.6.1`
- Gradle: `8.7`
- compileSdk / targetSdk: `35`
- minSdk: `23`
- JDK: `D:/03_work/09_android/FilmWord/.jdk/jdk-17`
- SDK: `D:/03_work/09_android/FilmWord/.android-sdk`

## 构建

```powershell
.\gradlew.bat assembleDebug
```

生成的 debug APK 位于：

```text
app/build/outputs/apk/debug/app-debug.apk
```
