# NuvioXTV 构建环境记录

> 用途:打包前快速确认环境;定位各工具/目录的实际路径。
> 更新日期:2026-11-15

## 项目本体

| 项 | 路径 |
|---|---|
| 项目根目录 | `/Users/lggsuper/Documents/Projects-Android/NuvioXTV` |
| 构建脚本(根) | `build.gradle.kts`、`settings.gradle.kts`、`gradle.properties` |
| App 模块 | `app/build.gradle.kts` |
| SDK 配置 | `local.properties` → `sdk.dir=/Volumes/extdisk/Android/Sdk` |
| 签名 Keystore | `nuviotv.jks`(项目根目录,alias=`nuviotv`,密码见 build.gradle.kts 默认值 `815787`) |

## 工具链

| 工具 | 路径 | 备注 |
|---|---|---|
| JDK 17(推荐) | `/Users/lggsuper/Library/Java/JavaVirtualMachines/ms-17.0.16/Contents/Home` | AGP 8.x 推荐,本机默认 `java` 是 25,需显式指定 |
| JDK 25 | `/Users/lggsuper/Library/Java/JavaVirtualMachines/openjdk-25/Contents/Home` | Oracle,默认 `java`/`javac` 指向它,**不要**用于本工程 |
| Android SDK | `/Volumes/extdisk/Android/Sdk` | 外置磁盘 `extdisk` 上 |
| NDK | `/Volumes/extdisk/Android/Sdk/ndk/29.0.14206865` | build.gradle 指定 `ndkVersion = "29.0.14206865"`(DOVI 关闭时未实际编译 native) |
| build-tools | `35.0.0` 与 `36.0.0`(已装) | 用 `36.0.0`(对应 compileSdk 36) |
| platforms | `android-36`(已装) | compileSdk/targetSdk = 36 |
| cmdline-tools | `/Volumes/extdisk/Android/Sdk/cmdline-tools/latest` | |
| Gradle | 8.13(项目内置 wrapper) | `gradle-wrapper.properties` 中 `distributionUrl` |

## 构建命令(经实测可用)

```bash
cd /Users/lggsuper/Documents/Projects-Android/NuvioXTV
export JAVA_HOME=/Users/lggsuper/Library/Java/JavaVirtualMachines/ms-17.0.16/Contents/Home
export ANDROID_HOME=/Volumes/extdisk/Android/Sdk
./gradlew assembleFullRelease --no-build-cache
```

## 产物

| 产物 | 路径 |
|---|---|
| 发布 APK(按 ABI 拆分) | `app/build/outputs/apk/full/release/` |
|  | `app-full-arm64-v8a-release.apk`(电视/盒子主用) |
|  | `app-full-armeabi-v7a-release.apk` |
|  | `app-full-x86_64-release.apk`(模拟器) |

版本:`1.1.0-beta.9`(versionCode 1072),已用 `nuviotv.jks` 签名,可直接 `adb install -r app-full-arm64-v8a-release.apk`。

## ⚠️ 重要坑:构建缓存污染

- 现象:`minifyFullReleaseWithR8` 报 `Missing class Xxx`(如 `PlayerDisplayModeUtils`、`StableMap`、`StableSet` 等),且**每次缺的类不一样**。并非源文件缺失(git 已跟踪、本地存在)。
- 根因:`gradle.properties` 中 `org.gradle.caching=true`,从 Gradle 构建缓存恢复了**不完整的 `compileFullReleaseKotlin` 产物**,R8 拿不到全部类。
- 解决:`clean` 无效(仍走缓存),必须加 `--no-build-cache` 从源头全量重编。
- 若想恢复缓存:清掉本地缓存再正常编译即可 → `rm -rf ~/.gradle/caches/build-cache-1`,然后 `./gradlew assembleFullRelease`。

## 变体说明

- Flavor:`full`(标准/旁载/内置应用内更新)与 `playstore`(Play 商店,应用 ID `com.nuvio.app`)。
- BuildType:`debug` / `release` / `benchmark`。
- 日常打包用 `assembleFullRelease`;要 AAB 用 `bundleFullRelease`。
