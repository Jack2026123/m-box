import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// 新版 AndroidX 已把 -ktx 扩展类合并进主包，旧版 -ktx 构件（activity-ktx 1.7.0、
// lifecycle-runtime-ktx 2.6.1）与新版主包（activity 1.13.0、lifecycle-runtime 2.11.0）
// 含同一批类，会报 Duplicate class；runtime-annotation 的 -jvm 变体与 -android
// 变体也是同一批类。主包里已有这些类，直接把旧的/重复的变体排除。
configurations.all {
    exclude(group = "androidx.activity", module = "activity-ktx")
    exclude(group = "androidx.lifecycle", module = "lifecycle-runtime-ktx")
    exclude(group = "androidx.compose.runtime", module = "runtime-annotation-jvm")
}

android {
    namespace = "com.github.tvbox.osc"
    compileSdk = libs.versions.compileSdk.get().toInt()
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        applicationId = "com.github.avbox.osc.mod"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 311
        versionName = "1.27"
        multiDexEnabled = true
        ndk {
            abiFilters += setOf("arm64-v8a")
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            // 多平台库的 common jar 里带 .knm（Kotlin/Native 元数据）与 commonMain 清单，
            // 安卓包用不上，且多个库里有同名文件会冲突出错，这里统一排除；
            // annotation 与 annotation-jvm 两个包里的 LICENSE.txt 也同名冲突，
            // 许可证文本运行时用不上，一并排除
            excludes += setOf(
                "META-INF/DEPENDENCIES", "META-INF/beans.xml", "**/*.knm",
                // 多平台库的元数据都在 <X>Main/default/ 下面（commonMain、redirectCommonMain、
                // skikoMain……），安卓包用不上，且多个库里有同名文件会冲突出错，这里统一排除；
                // annotation 与 annotation-jvm 两个包里的 LICENSE.txt 也同名冲突，
                // 许可证文本运行时用不上，一并排除
                "**Main/default/**",
                "META-INF/kotlin-project-structure-metadata.json",
                "META-INF/**/LICENSE.txt", "META-INF/**/LICENSE",
                "META-INF/**/NOTICE.txt", "META-INF/**/NOTICE",
                // 不同版本同库的 .version 元数据文件也会同名冲突，运行时用不上
                // （**/ 至少要一级目录，直接在 META-INF/ 下的文件要用 META-INF/*.version）
                "META-INF/*.version", "META-INF/**/*.version"
            )
        }
    }

    androidResources {
        localeFilters += listOf("en", "zh", "zh-rCN", "b+zh+Hant", "zh-rTW", "zh-rHK")
    }

    sourceSets {
        getByName("main") {
            java.directories += "src/python/java"
        }
    }

    signingConfigs {
        val storeFilePath = project.findProperty("RELEASE_STORE_FILE") as String? ?: ".key/app-release.jks"
        val storeFileResolved = rootProject.file(storeFilePath)
        if (storeFileResolved.exists()) {
            create("release") {
                storeFile = storeFileResolved
                storePassword = project.findProperty("RELEASE_STORE_PASSWORD") as String
                keyAlias = project.findProperty("RELEASE_KEY_ALIAS") as String
                keyPassword = project.findProperty("RELEASE_KEY_PASSWORD") as String
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = false
            // 签名：.key/app-release.jks 不存在时自动退化用 debug 证书（配置在 signingConfigs 里判断），
            // 所以这个仓库不带 jks 也能出 release 包，只是证书显示为 Android Debug。
            signingConfigs.findByName("release")?.let { signingConfig = it }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro", "proguard-python.pro")
        }
    }
    splits {
        abi {
            isEnable = false
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
        buildConfig = true
        viewBinding = true
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
    }
}
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            output.outputFileName.set("AVBox_${variant.buildType}.apk")
        }
    }
}

configurations {
    all {
        exclude(group = "androidx.media3", module = "media3-exoplayer")
        exclude(group = "androidx.media3", module = "media3-exoplayer-dash")
        exclude(group = "androidx.media3", module = "media3-exoplayer-hls")
        exclude(group = "androidx.media3", module = "media3-exoplayer-rtsp")
        exclude(group = "androidx.media3", module = "media3-common")
        exclude(group = "androidx.media3", module = "media3-datasource")
        exclude(group = "androidx.media3", module = "media3-datasource-rtmp")
        exclude(group = "androidx.media3", module = "media3-database")
        exclude(group = "androidx.media3", module = "media3-ui")
        exclude(group = "androidx.media3", module = "media3-effect")
        exclude(group = "androidx.media3", module = "media3-extractor")
        exclude(group = "androidx.media3", module = "media3-decoder")
        exclude(group = "androidx.media3", module = "media3-session")
        exclude(group = "androidx.media3", module = "media3-container")
    }
}

dependencies {
    api(fileTree("libs") { include("*.jar", "*.aar") })

    constraints {
        implementation("androidx.concurrent:concurrent-futures:1.2.0")
        implementation("com.google.guava:listenablefuture:9999.0-empty-to-avoid-conflict-with-guava")
    }

    implementation("com.google.guava:guava:33.6.0-android")

    implementation(libs.nanohttpd)
    implementation(libs.cling.core)
    implementation(libs.cling.support)
    compileOnly(libs.cdi.api)
    compileOnly(libs.javax.inject)
    compileOnly(libs.javax.annotation.api)
    compileOnly(libs.javax.servlet.api)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.media)
    implementation(libs.okhttp)
    implementation(libs.okhttp.dnsoverhttps)
    implementation(libs.kotlinx.coroutines.android)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.sqlite.bundled)
    implementation(libs.okio)
    implementation(libs.gson)
    implementation(libs.autosize)
    implementation(libs.xstream) {
        exclude(group = "xmlpull", module = "xmlpull")
        exclude(group = "xpp3", module = "xpp3_min")
    }
    implementation(libs.eventbus)
    implementation(libs.mmkv)
    implementation(libs.danmaku.flame.master)

    // 2.86：真变声 Kempo（纯 Kotlin，音调+共振峰独立，Apache 2.0）

    implementation(project(":quickjs"))
    implementation(project(":pyramid"))

    implementation(libs.xx.permissions)
    implementation(libs.jsoup)
    implementation(libs.commons.io)
    implementation(libs.juniversalchardet)
    implementation(libs.zxing.core)
    implementation(libs.sardine) {
        exclude(group = "xpp3", module = "xpp3")
    }

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.coil.gif)
    implementation(libs.materialkolor)
    implementation(libs.google.material)
    implementation(project(":libs:backdrop"))
    implementation(libs.capsule)

    coreLibraryDesugaring(libs.desugar.jdk.libs)

    testImplementation(libs.junit)

    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.compose.ui.test.manifest)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
}

configurations.configureEach {
    exclude(group = "io.antmedia", module = "rtmp-client")
}
