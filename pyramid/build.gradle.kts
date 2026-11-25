plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.chaquopy)
}

android {
    namespace = "com.undcover.freedom.pyramid"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        ndk {
            abiFilters += setOf("arm64-v8a")
        }
    }

    buildTypes {
        debug {
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
}

chaquopy {
    defaultConfig {
        version = "3.10"
        val explicitBuildPython = (project.findProperty("buildPython") as String?)
            ?: System.getenv("CHAQUOPY_BUILD_PYTHON")
        val defaultBuildPython = File(
            System.getProperty("user.home"),
            "AppData/Local/Programs/Python/Python310/python.exe",
        ).absolutePath
        listOfNotNull(explicitBuildPython, defaultBuildPython)
            .firstOrNull { file(it).exists() }
            ?.let { buildPython(it) }
        pip {
            // 2026-11-21: 华为云 PyPI 镜像在这台沙箱里不稳定（requests-2.34.1 报 403，
            // chaquopy-libxslt 连接被重置），改用阿里云镜像；其余不变。
            options("-i", "https://mirrors.aliyun.com/pypi/simple/")
            options("--extra-index-url", "https://chaquo.com/pypi-13.1")
            install("lxml")
            install("ujson")
            install("pyquery==2.0.2")
            install("requests")
            install(file("wheels/jsonpath-0.54-py3-none-any.whl").absolutePath)
            install("cachetools")
            install("pycryptodome")
            install("beautifulsoup4")
        }
    }
    sourceSets {
        getByName("main") {
            setSrcDirs(listOf("src/python"))
        }
    }
}

// ---------------------------------------------------------------------------
// Chaquopy 会在 build/python/ 下生成 proguard-rules.pro，但 clean 之后这个目录被删掉，
// 而 :pyramid:mergeDebugConsumerProguardFiles 又会先于 Chaquopy 的生成任务执行，
// 于是全量构建（./gradlew clean assembleDebug）会报：
//   Supplied consumer proguard configuration does not exist:
//   .../pyramid/build/python/proguard-rules.pro
//
// 增量构建因为目录还在，一直没暴露这个问题。
// 这里在配置阶段先把文件写出来，保证任何构建顺序下它都存在。
val chaquopyProguard = layout.buildDirectory.file("python/proguard-rules.pro")

val ensureChaquopyProguard by tasks.registering {
    description = "确保 Chaquopy 的 consumer proguard 文件存在"
    val target = chaquopyProguard.get().asFile
    outputs.file(target)
    doLast {
        target.parentFile.mkdirs()
        if (!target.exists()) {
            target.writeText(
                "# Chaquopy 生成占位。内容为空是正常的：\n" +
                    "# Python 代码通过 Chaquopy 运行时不参与 Kotlin/Java 混淆，\n" +
                    "# 所以这里不需要任何 keep 规则。\n",
            )
        }
    }
}

// 让所有 consumer proguard 合并任务依赖上面这个任务
tasks.matching { it.name.contains("ConsumerProguardFiles") }.configureEach {
    dependsOn(ensureChaquopyProguard)
}
