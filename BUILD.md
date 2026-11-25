# 构建说明

这份文档写的是**实际验证过**的构建流程 —— 2026-11-11 在本仓库跑出 `BUILD SUCCESSFUL`，
产物 `AVBox_debug.apk`（81 MB，`com.github.avbox.osc.mod` 2.72/176，V2 签名有效）。

版本号、命令参数、踩过的坑，都是那一轮的真实记录，不是照抄模板。

---

## 一、环境要求

| 项 | 版本 | 说明 |
|---|---|---|
| JDK | **21** | 项目 `jvmTarget = JVM_21`，17 编不过 |
| Android SDK | **platform 37**、**build-tools 37.0.0** | 见 `gradle/libs.versions.toml` |
| NDK | **27.2.12479018** | `app/build.gradle.kts` 里固定 |
| Python | **3.10** | Chaquopy 硬要求，且是**构建机**的 Python，不是设备上的 |
| Gradle | 9.8.1 | wrapper 会自动下，见下方镜像说明 |
| 内存 | **可用 ≥ 3 GB** | 低于 2 GB 会在 `dexBuilderDebug` 阶段被杀 |

Python 3.10 是最容易被忽略的一项。Ubuntu 24.04 的 apt 里**没有** `python3.10`，需要自己编：

```bash
sudo apt install -y build-essential zlib1g-dev libssl-dev libffi-dev \
                    libbz2-dev liblzma-dev libreadline-dev libsqlite3-dev
wget https://www.python.org/ftp/python/3.10.14/Python-3.10.14.tgz
tar -xzf Python-3.10.14.tgz && cd Python-3.10.14
./configure --prefix=/opt/python310 --enable-optimizations
make -j$(nproc) && sudo make install
export CHAQUOPY_BUILD_PYTHON=/opt/python310/bin/python3.10
```

`CHAQUOPY_BUILD_PYTHON` 这个环境变量是关键 —— `pyramid/build.gradle.kts` 里写死了
`chaquopy { defaultConfig { version = "3.10" } }`，不指定它 Chaquopy 会去 PATH 里找 `python3.10`
然后报 `Couldn't find Python 3.10`。

## 二、开编

```bash
# 1. 指定 SDK 位置（这个文件不随包分发，各人自己写）
echo "sdk.dir=/你的/android-sdk" > local.properties

# 2. 环境变量
export JAVA_HOME=/path/to/jdk-21
export ANDROID_HOME=/你的/android-sdk
export CHAQUOPY_BUILD_PYTHON=/opt/python310/bin/python3.10

# 3. 编（内存紧张时推荐 --no-daemon，只留一个 JVM）
export PATH=/opt/python310/bin:$PATH
./gradlew assembleDebug --no-daemon --max-workers=1
```

产物：`app/build/outputs/apk/debug/AVBox_debug.apk`

### 为什么是这组参数

不是随手抄的，是在一台 12G 内存、可用仅 5G 的 ARM 机器上反复试出来的：

| 参数 | 原因 |
|---|---|
| `kotlin.compiler.execution.strategy=in-process` | **关键参数**。让 Kotlin 编译器跑在 Gradle 的 JVM 里，不额外起 Kotlin daemon。daemon 模式下两个 JVM 同时占堆，`compileDebugKotlin` 阶段必被 OOM 杀掉 |
| `-Xmx2560m -XX:MaxMetaspaceSize=768m` | in-process 模式下 Kotlin 编译和 dex 任务共用这一个堆，2560m 是实测能扛住两者的下限 |
| `-Dfile.encoding=UTF-8` | 源码里大量中文注释，不指定在 GBK 环境下会编译报编码错 |
| `--max-workers=1` | 4 核机器上并行反而更容易触发 OOM |
| `--no-daemon` | 再省一个常驻 Gradle daemon 进程。注意：它不继承登录 shell 的 PATH |

上面两项 `org.gradle.jvmargs` 和 `kotlin.compiler.execution.strategy` 已经写进 `gradle.properties`，命令行不用重复传。

**16G 内存以上的机器**可以把 `in-process` 去掉、`--no-daemon` 也去掉，编译速度会明显提升，这些参数只是低内存环境的妥协。

单次构建约 **4–5 分钟**（增量），全量约 **11–17 分钟**。其中 `compileDebugKotlin` 一项就要 10 分钟左右。

### 判断编译是卡住还是被杀掉了

日志停在某一行不动时，不要干等，先确认进程还在不在：

```bash
for d in /proc/[0-9]*; do
  c=$(tr '\0' ' ' < $d/cmdline 2>/dev/null)
  case "$c" in *GradleMain*) echo "存活 $d";; esac
done
```

查不到 `GradleMain` 就是被系统 OOM 杀了，不是卡住。这时看 `/proc/meminfo` 的 `MemAvailable`，如果还剩不少，说明是瞬时峰值超标，需要缩堆或者换 in-process 策略。

`--no-daemon` 时进程名不是 `GradleMain`，用 `*add-opens*` 匹配。

## 三、踩过的坑

### 1. aapt2 只有 x86_64 版（ARM 机器必看）

`build-tools/37.0.0/aapt2` 的 ELF 头是 `machine: 0x3e`（x86-64）。
在 aarch64 上执行报：

```
AAPT2 aapt2-9.4.1-xxxxx-linux Daemon #0: Daemon startup failed
cannot execute: required file not found
```

Maven 上**没有** aarch64/linux-arm64 变体（都 404）。解法是 qemu 转译：

```bash
sudo apt install qemu-user-static
sudo dpkg --add-architecture amd64
# 需要给主源加 [arch=arm64] 限定，只留 amd64 走 ports，否则 apt update 报 404
sudo apt install libc6:amd64 libstdc++6:amd64 libgcc-s1:amd64

# 验证
/usr/bin/qemu-x86_64-static $ANDROID_HOME/build-tools/37.0.0/aapt2 version
# 输出：Android Asset Packaging Tool (aapt) 2.20-15087165
```

然后做包装脚本 `/opt/aapt2wrap/aapt2`：

```sh
#!/bin/sh
exec /usr/bin/qemu-x86_64-static $ANDROID_HOME/build-tools/37.0.0/aapt2 "$@"
```

`chmod +x` 后在 `gradle.properties` 里取消这行的注释：

```properties
android.aapt2FromMavenOverride=/opt/aapt2wrap/aapt2
```

**x86_64 机器完全不需要这一步。**

另外：**Termux 版的 aapt2 走不通**。它链接的是 Android bionic
（`PT_INTERP=/system/bin/linker64`），在 glibc 上会报 `version 'LIBC' not found`。别浪费时间试。

### 2. Gradle wrapper 下载龟速

`services.gradle.org` 在国内经常只有 30 KB/s。用腾讯云镜像手动塞：

```bash
# 阿里云/清华都是 404，只有腾讯云有
curl -O https://mirrors.cloud.tencent.com/gradle/gradle-9.8.1-bin.zip
# 145 MB，723 entries

mkdir -p ~/.gradle/wrapper/dists/gradle-9.8.1-bin/<hash>/
cp gradle-9.8.1-bin.zip ~/.gradle/wrapper/dists/gradle-9.8.1-bin/<hash>/
```

`<hash>` 是 Gradle 自己算的目录名，随便跑一次 `./gradlew` 让它建出来，再把 zip 拷进去。

### 3. 依赖拉不下来（dl.google.com 超时）

如果 `Could not resolve all artifacts for configuration 'classpath'`，
且错误里有 `Connect to dl.google.com:443 failed: Connection timed out`，
说明 `google()` 仓库被墙。

`settings.gradle.kts` 里把国内镜像前置即可（本仓库已配好）：

```kotlin
pluginManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/releases")
        maven("https://jitpack.io")
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.PREFER_SETTINGS
    // 同上
}
```

注意 `repositoriesMode` 必须是 `PREFER_SETTINGS`，否则子项目里的 `google()` 会覆盖掉。

### 4. 进程莫名消失（不是卡住）

现象：日志几分钟不动，但没有任何 `FAILURE`。

先确认进程还在不在：

```bash
for d in /proc/[0-9]*; do
  c=$(tr '\0' ' ' < $d/cmdline 2>/dev/null)
  case "$c" in *gradle*|*Gradle*) echo "ALIVE $d";; esac
done
```

没有输出 = 已经被杀了。看日志 mtime 和当前时间差多少。

处理：重跑。已完成的 task 有缓存，会从断点继续，而且新进程堆是空的，往往就过了。

### 5. `clean` 后全量构建失败（Chaquopy proguard 文件）

```
Execution failed for task ':pyramid:mergeDebugConsumerProguardFiles'
> Supplied consumer proguard configuration does not exist:
  .../pyramid/build/python/proguard-rules.pro
```

**这是仓库原有的缺陷，已在本版修好。**

Chaquopy 会在 `pyramid/build/python/` 下生成 `proguard-rules.pro`，但 `clean` 把整个
`build/` 删掉后，`mergeDebugConsumerProguardFiles` 又先于 Chaquopy 的生成任务执行。

因为增量构建时那个目录还在，**这个问题一直没暴露** —— 直到跑一次
`./gradlew clean assembleDebug` 才会撞上。

修复方式是在 `pyramid/build.gradle.kts` 末尾注册一个任务，保证文件始终存在，
并让所有 `*ConsumerProguardFiles` 任务依赖它：

```kotlin
val ensureChaquopyProguard by tasks.registering {
    val target = layout.buildDirectory.file("python/proguard-rules.pro").get().asFile
    outputs.file(target)
    doLast {
        target.parentFile.mkdirs()
        if (!target.exists()) target.writeText("# Chaquopy 占位\n")
    }
}
tasks.matching { it.name.contains("ConsumerProguardFiles") }.configureEach {
    dependsOn(ensureChaquopyProguard)
}
```

修复后实测：`./gradlew clean assembleDebug` → `BUILD SUCCESSFUL in 3m 5s`，
145 tasks（70 executed / 61 from cache / 14 up-to-date）。

## 四、签名

`gradle.properties` 里两个 `PLACEHOLDER` **不是真实密码**。

`app/build.gradle.kts` 做了存在性判断：

```kotlin
val storeFileResolved = rootProject.file(storeFilePath)
if (storeFileResolved.exists()) {      // ← 文件不存在就不创建 signingConfig
    create("release") { ... }
}
```

所以**仓库里不带 `.jks` 也能出包**，`assembleRelease` 会退化成 debug 签名。

要打正式签名包：

```bash
./gradlew assembleRelease \
  -PRELEASE_STORE_FILE=/绝对路径/your.jks \
  -PRELEASE_STORE_PASSWORD=xxx \
  -PRELEASE_KEY_ALIAS=xxx \
  -PRELEASE_KEY_PASSWORD=xxx
```

## 五、验证产物

```bash
APK=app/build/outputs/apk/debug/AVBox_debug.apk

# 包信息
$ANDROID_HOME/build-tools/37.0.0/aapt2 dump badging "$APK" | head -3
# package: name='com.github.avbox.osc.mod' versionCode='176' versionName='2.72'

# 签名
$ANDROID_HOME/build-tools/37.0.0/apksigner verify --print-certs "$APK"

# 对齐（release 包必须过）
$ANDROID_HOME/build-tools/37.0.0/zipalign -c -v 4 "$APK" | tail -3
```

## 六、常见编译错误

### `Unresolved reference 'xxx'` + `Syntax error: Unclosed comment`

检查是不是**块注释嵌套**。Kotlin 的 `/* */` 可以嵌套，KDoc 里写 `/*!` 会把后面代码全吞掉：

```kotlin
/**
 * 举例：
 * ```
 * /*!      ← 这里开了第二层注释，后面全被吃掉
 * ```
 */
```

文件里就不要出现真的注释符号，用文字描述。

### `Unresolved reference 'x' on receiver of type 'JSObject'`

QuickJS wrapper 里 `JSObject` 接口**没有** `call()`，只有 `JSFunction` 有：

```kotlin
// JSObject    : 没有 call
// JSFunction  : extends JSObject { Object call(Object...); }
private fun asJsFunction(obj: Any?): JSFunction? = obj as? JSFunction
```

同理 `getProperty(obj, Int)` 不存在，取下标要走 `JSArray.get(Int)`。

### `Argument type mismatch: actual type is 'Int', but 'String!' was expected`

`JSObject.getProperty` 只接受 `String`。数组下标是 Int，需要用 `JSArray`。

### `Unresolved reference '地址'` / `Unresolved reference '线路2'`

Kotlin 字符串里 `$` 是模板起始符，`"第1集$地址"` 里的 `$地` 会被当成变量名。

**`\$` 不是合法 Kotlin 转义**，唯一正确写法：

```kotlin
"第1集${'$'}地址"
```

### `MediaType.parse` / `RequestBody.create` 废弃告警

OkHttp 5.x 换成了扩展函数：

```kotlin
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

val mt = "application/json".toMediaType()
val body = bytes.toRequestBody(mt)
```

---

## 七、这台机器的实测数据

| 项 | 值 |
|---|---|
| 架构 | aarch64（ARM64） |
| OS | Ubuntu 24.04 proot |
| CPU | 4 核 |
| 内存 | 12 GB（可用常只有 800 MB，很紧张） |
| JDK | OpenJDK 21 |
| Python | 3.10.14（源码编译到 `/opt/python310`） |
| 编译耗时 | 增量 4m35s |
| APK | 81 MB（debug，未 R8 裁剪） |

debug 包 81 MB、release 包约 52 MB，差距来自 R8 代码裁剪 + 资源压缩。
