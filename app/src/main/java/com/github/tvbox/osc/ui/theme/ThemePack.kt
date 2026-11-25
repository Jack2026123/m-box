package com.github.tvbox.osc.ui.theme

import android.content.Context
import android.graphics.Color as AndroidColor
import com.github.tvbox.osc.api.LocalSource
import com.github.tvbox.osc.util.KV
import com.materialkolor.PaletteStyle
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

// 2.99 主题包一键导入（接 2026-11-13 晚被叫停的 2.98 原计划，设计已定）：
// 包 = 文件夹或 zip，里面 theme.json（全套 ThemePreset 参数）+ wallpaper.jpg。
// 导入后包存在 filesDir/theme_packs/<id>/（theme.json + wallpaper.jpg）+ KV 记 id 列表；
// 包是额外选项；App 内仍保留默认主题。

data class ThemePack(
    val id: String,
    val name: String,
    val subtitle: String,
    val preset: ThemePreset,
    val wallpaperFile: File?,
    /** 3.20 胶囊风格："water" = 水滴 / "light" = 轻透；null = 包里没写，应用时不动原值。 */
    val capsuleStyle: String? = null,
    /** 3.20 胶囊组底色 ARGB；null = 包里没写，应用时不动原值。 */
    val capsuleBaseColorArgb: Int? = null,
)

object ThemePackStore {
    const val KEY_LIST = "theme_pack_list"
    const val KEY_FOLDER = "theme_pack_folder"
    const val KEY_ENABLED = "theme_pack_enabled"
    const val KEY_CURRENT = "theme_pack_current_id"

    fun isEnabled(): Boolean = KV.get(KEY_ENABLED, true)
    fun setEnabled(enabled: Boolean) {
        KV.put(KEY_ENABLED, enabled)
    }

    fun lastFolder(): String = KV.get(KEY_FOLDER, "")

    fun currentId(): String = KV.get(KEY_CURRENT, "")
    fun clearCurrent() {
        KV.delete(KEY_CURRENT)
    }

    fun packsDir(context: Context): File = File(context.filesDir, "theme_packs")

    private fun packDir(context: Context, id: String): File = File(packsDir(context), id)

    /** 已导入的包（按 KV 列表顺序；theme.json 缺失或损坏的包跳过）。 */
    fun list(context: Context): List<ThemePack> {
        val ids = readIdList()
        val out = ArrayList<ThemePack>()
        for (id in ids) {
            val dir = packDir(context, id)
            val themeJson = File(dir, "theme.json")
            if (!themeJson.isFile) continue
            val preset = parseThemeJsonFile(themeJson) ?: continue
            val (capsuleStyle, capsuleBaseColor) = parseCapsuleOptionsFile(themeJson)
            val wallpaper = File(dir, "wallpaper.jpg").takeIf { it.isFile }
            out.add(ThemePack(preset.id, preset.name, preset.subtitle, preset, wallpaper, capsuleStyle, capsuleBaseColor))
        }
        return out
    }

    private fun readIdList(): List<String> {
        val raw = KV.get(KEY_LIST, "")
        if (raw.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun writeIdList(ids: List<String>) {
        val arr = JSONArray()
        ids.forEach { arr.put(it) }
        KV.put(KEY_LIST, arr.toString())
    }

    private fun addId(id: String) {
        val ids = readIdList().toMutableList()
        if (!ids.contains(id)) {
            ids.add(id)
            writeIdList(ids)
        }
    }

    fun removePack(context: Context, id: String) {
        val dir = packDir(context, id)
        try {
            dir.deleteRecursively()
        } catch (e: Exception) {
        }
        writeIdList(readIdList().filter { it != id })
        if (currentId() == id) clearCurrent()
    }

    /** 解析 theme.json（文件）成 ThemePreset；seedArgb 是 "#FF…" 串，mode 是 ThemeMode int，style 是 PaletteStyle 名串。 */
    fun parseThemeJsonFile(file: File): ThemePreset? {
        return try {
            val json = JSONObject(file.readText())
            parseThemeJson(json)
        } catch (e: Exception) {
            null
        }
    }

    fun parseThemeJson(json: JSONObject): ThemePreset? {
        return try {
            val id = json.optString("id").trim()
            if (id.isEmpty()) return null
            val style = try {
                PaletteStyle.valueOf(json.optString("style", "TonalSpot"))
            } catch (e: Exception) {
                PaletteStyle.TonalSpot
            }
            ThemePreset(
                id = id,
                name = json.optString("name", id),
                subtitle = json.optString("subtitle", ""),
                mode = json.optInt("mode", ThemeMode.FOLLOW_SYSTEM),
                seedArgb = AndroidColor.parseColor(json.optString("seedArgb", "#FF6750A4")),
                style = style,
                pureBlack = json.optBoolean("pureBlack", false),
                reliefProfile = json.optInt("reliefProfile", 0),
                elevation = json.optDouble("elevation", 0.0).toFloat(),
                curvature = json.optInt("curvature", 0),
                lightBrightness = json.optInt("lightBrightness", 120),
                cornerScale = json.optDouble("cornerScale", 1.0).toFloat(),
                highTransparency = json.optInt("highTransparency", 0),
                posterTransparency = json.optInt("posterTransparency", 0),
                ambientLevel = json.optInt("ambientLevel", 0),
                frostedLevel = json.optInt("frostedLevel", 0),
                wallpaperLevel = json.optInt("wallpaperLevel", 40),
                detailVeilLevel = json.optInt("detailVeilLevel", 40),
                glassBlurDp = json.optDouble("glassBlurDp", 0.0).toFloat(),
                glassDistortionDp = json.optDouble("glassDistortionDp", 0.0).toFloat(),
                glassTranslucency = json.optDouble("glassTranslucency", 0.0).toFloat(),
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 3.20 theme.json 里的胶囊字段（可选，ThemePreset 不装它们——那是主题色结构，胶囊风格是控制主题的）。
     * capsuleStyle 只认 "water"/"light"/"raised"，其他值当没写；capsuleBaseColor 是 "#…" hex，解析失败当没写。
     * 两个都缺失 = 老包，返回 (null, null)，应用时不动原值。
     */
    fun parseCapsuleOptions(json: JSONObject): Pair<String?, Int?> {
        val rawStyle = json.optString("capsuleStyle", "").trim().lowercase()
        val style = if (rawStyle == ControlTheme.CAPSULE_STYLE_WATER || rawStyle == ControlTheme.CAPSULE_STYLE_LIGHT || rawStyle == ControlTheme.CAPSULE_STYLE_RAISED || rawStyle == ControlTheme.CAPSULE_STYLE_DEEP_BUTTON || rawStyle == ControlTheme.CAPSULE_STYLE_FLAT_BUTTON) rawStyle else null
        val baseColor: Int? = try {
            val raw = json.optString("capsuleBaseColor", "").trim()
            if (raw.isEmpty()) null else AndroidColor.parseColor(raw)
        } catch (e: Exception) {
            null
        }
        return style to baseColor
    }

    fun parseCapsuleOptionsFile(file: File): Pair<String?, Int?> {
        return try {
            parseCapsuleOptions(JSONObject(file.readText()))
        } catch (e: Exception) {
            null to null
        }
    }

    /**
     * 扫一个文件夹，认两种形式：子文件夹（含 theme.json）与 zip（含 theme.json）。
     * 每个包的 theme.json + wallpaper.jpg 拷到 filesDir/theme_packs/<id>/，id 记进 KV 列表。
     * 返回新导入的包（已导入过的 id 重新导入会覆盖文件，不重复记）。
     */
    fun scanFolder(folder: File, context: Context): List<ThemePack> {
        val imported = ArrayList<ThemePack>()
        if (!folder.isDirectory) return imported
        KV.put(KEY_FOLDER, folder.absolutePath)
        val files = try {
            folder.listFiles().orEmpty()
        } catch (e: Exception) {
            emptyArray()
        }
        for (sub in files) {
            if (sub.isDirectory) {
                val themeJson = File(sub, "theme.json")
                if (themeJson.isFile) {
                    importFromFiles(themeJson, File(sub, "wallpaper.jpg"), context)?.let { imported.add(it) }
                }
            } else if (sub.isFile && sub.name.endsWith(".zip", ignoreCase = true)) {
                imported.addAll(importFromZip(sub, context))
            }
        }
        return imported
    }

    private fun importFromFiles(themeJson: File, wallpaper: File, context: Context): ThemePack? {
        val preset = parseThemeJsonFile(themeJson) ?: return null
        val (capsuleStyle, capsuleBaseColor) = parseCapsuleOptionsFile(themeJson)
        val dir = packDir(context, preset.id)
        return try {
            dir.mkdirs()
            themeJson.copyTo(File(dir, "theme.json"), overwrite = true)
            if (wallpaper.isFile) {
                wallpaper.copyTo(File(dir, "wallpaper.jpg"), overwrite = true)
            }
            addId(preset.id)
            ThemePack(
                preset.id,
                preset.name,
                preset.subtitle,
                preset,
                File(dir, "wallpaper.jpg").takeIf { it.isFile },
                capsuleStyle,
                capsuleBaseColor,
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun importFromZip(zipFile: File, context: Context): List<ThemePack> {
        val imported = ArrayList<ThemePack>()
        try {
            ZipFile(zipFile).use { zip ->
                val entries = zip.entries().toList()
                // 单包 zip：theme.json 在根目录；多包 zip（如 6 套合包）：每套在各自子文件夹里
                val roots = entries.filter { it.name == "theme.json" }
                val nested = entries.filter { it.name.endsWith("/theme.json") }
                if (roots.isNotEmpty()) {
                    val themeJson = readZipEntryToTemp(zip, "theme.json") ?: return imported
                    val wallpaper = readZipEntryToTemp(zip, "wallpaper.jpg")
                    importFromFiles(themeJson, wallpaper ?: File(""), context)?.let { imported.add(it) }
                    themeJson.delete()
                    wallpaper?.delete()
                } else {
                    for (entry in nested) {
                        val packDirName = entry.name.substringBefore("/")
                        val themeJson = readZipEntryToTemp(zip, entry.name) ?: continue
                        val wallpaper = readZipEntryToTemp(zip, "$packDirName/wallpaper.jpg")
                        importFromFiles(themeJson, wallpaper ?: File(""), context)?.let { imported.add(it) }
                        themeJson.delete()
                        wallpaper?.delete()
                    }
                }
            }
        } catch (e: Exception) {
        }
        return imported
    }

    private fun readZipEntryToTemp(zip: ZipFile, entryName: String): File? {
        val entry = zip.getEntry(entryName) ?: return null
        return try {
            val temp = File.createTempFile("themepack_", ".tmp")
            zip.getInputStream(entry).use { input ->
                temp.outputStream().use { output -> input.copyTo(output) }
            }
            if (temp.length() == 0L) {
                temp.delete()
                null
            } else {
                temp
            }
        } catch (e: Exception) {
            null
        }
    }

    /** 应用一个包：按包参数写主题 KV（走 ThemePresets.apply 同款写法）+ 包壁纸经 WallpaperStore 设为壁纸。IO 线程调用。 */
    fun applyPack(pack: ThemePack): Boolean {
        ThemePresets.apply(pack.preset)
        // 3.20 包里带胶囊字段才写胶囊组（走 ControlThemeStore 现有 setter，不直接写 KV）；
        // 老包两个字段都是 null，原值不动。
        pack.capsuleStyle?.let { style ->
            ControlThemeStore.setGroupCapsuleStyle(ControlGroup.CAPSULE, style)
        }
        pack.capsuleBaseColorArgb?.let { argb ->
            ControlThemeStore.setGroupBaseColorArgb(ControlGroup.CAPSULE, argb)
        }
        // r485：包里带壁纸图时也别动视频壁纸——用户特意设的视频，换主题包不该悄悄清掉
        // （applyLocal 会删视频文件，删了找不回来）。有视频在位时跳过包壁纸，只套主题参数；
        // 没视频时照旧套用包壁纸。图片壁纸不受影响（包图顶掉旧图是原来的意思）。
        val wallpaper = pack.wallpaperFile
        if (wallpaper != null && wallpaper.isFile && !WallpaperStore.video) {
            try {
                wallpaper.inputStream().use { WallpaperStore.applyLocal(it) }
            } catch (e: Exception) {
            }
        }
        KV.put(KEY_CURRENT, pack.id)
        return true
    }

    /** 文件夹浏览器起步目录：上次选的文件夹 / 首个扫描目录 / Download。 */
    fun defaultStartDir(): String {
        val last = lastFolder()
        if (last.isNotEmpty() && File(last).isDirectory) return last
        return try {
            val cfg = LocalSource.loadSettings()
            val enabled = LocalSource.stringList(cfg, "scan_enabled_dirs")
            if (enabled.isNotEmpty()) return enabled.first()
            val dirs = LocalSource.stringList(cfg, "scan_dirs")
            if (dirs.isNotEmpty()) dirs.first() else "/storage/emulated/0/Download"
        } catch (e: Exception) {
            "/storage/emulated/0/Download"
        }
    }
}
