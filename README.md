# Android Agent 0.6.0

红米 K40 用的原生 Kotlin / Compose 本地助手。包名保持 dev.channing.androidagent。

1. 安装 ARM64 APK，打开 Android Agent。先试「打开微信」或「打开蓝牙设置」。
2. 在「模型、隐私与权限」下载或导入官方 Qwen2.5-1.5B Q4_K_M。需要约 1.12 GB 存储；首次下载需要网络。文件经大小和 SHA-256 校验后才启用，普通下载可续传。
3. 点「语音输入」授权麦克风，讲话后核对识别文字，再点准备。桌面可添加小组件。快捷设置中可添加 Android Agent 磁贴。
4. 通过系统设置为默认数字助手的入口在隐私设置内。快捷手势和角色是否可用取决于 ROM。助手只在主动触发后录音。
5. 网页控制台适合设备配对及 CPU 线程设置。完成配对后，在手机点「同步高级配置」。已有本地功能无需持续连接后台。

「给张三发QQ，说我马上到」准备草稿并显示名字、渠道和原文。确认后可复制并打开 QQ，在 QQ 中亲自定位联系人和发送。地图路线以及浏览器搜索交给外部应用，其网络能力由各应用决定。

## 构建
```sh
bash scripts/fetch_llama.sh
bash scripts/fetch_speech.sh
./gradlew --no-daemon --max-workers=2 testDebugUnitTest lintDebug assembleDebug
```
使用 JDK 17、Android SDK 35、NDK 27.2.12479018 和 CMake 3.22.1。GitHub Actions 配置在 .github/workflows/android.yml，生成 ARM64 APK。语音模型包含在 APK 中，Qwen 权重安装后单独下载。固定依赖由构建脚本校验后获取。
