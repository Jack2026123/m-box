import org.gradle.api.initialization.resolve.RepositoriesMode

pluginManagement {
    repositories {
        // 本地离线仓库：只在本机沙箱存在时引用。Java 在本沙箱里连不上外网
        // （HTTPS 经代理会被重置），依赖已用 curl 预先下载到这里
        // （~/workspace/tools/fetch-gradle-deps.py），构建时加 --offline 只用本地仓库。
        // GitHub Actions 上没有这个目录，跳过它，直接走 google()/mavenCentral()。
        // 注意：不能用顶层变量，pluginManagement/dependencyResolutionManagement
        // 块里看不到脚本顶层的 val，所以把 File 判断直接写在块里。
        if (File("/home/hatch/workspace/gradle-repo").isDirectory) {
            maven { url = uri("/home/hatch/workspace/gradle-repo") }
        }
        google()
        mavenCentral()
        gradlePluginPortal()
        maven("https://jitpack.io")
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/releases")
    }
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "com.chaquo.python") {
                useModule("com.chaquo.python:gradle:${requested.version}")
            }
        }
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        // 本地离线仓库：只在目录存在时引用（见 pluginManagement 里的注释）
        if (File("/home/hatch/workspace/gradle-repo").isDirectory) {
            maven { url = uri("/home/hatch/workspace/gradle-repo") }
        }
        google()
        mavenCentral()
        maven("http://4thline.org/m2") {
            isAllowInsecureProtocol = true
        }
        maven("https://jitpack.io")
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/releases")
    }
}

rootProject.name = "AVBox"
include(":app")
include(":quickjs")
include(":pyramid")
include(":libs:backdrop")
