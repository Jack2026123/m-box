package com.github.tvbox.osc.ui.dialog

import android.app.Dialog
import android.content.Context
import android.text.InputType
import android.text.TextUtils
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import com.github.tvbox.osc.R
import com.github.tvbox.osc.api.ai.AiConfig
import com.github.tvbox.osc.api.ai.AiRepair
import com.github.tvbox.osc.ui.theme.ViewTheme
import com.github.tvbox.osc.util.Notify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

// 1.45 AI 修源设置（按昏黄 AiRepairSettingsDialog 的 AVBox 形态，经典 View）。
// 字段：接口地址 / Key（打码回显，未改动不覆盖）/ 模型（手填＋拉取单选）/
// 轮次 / 取证开关（日志、联网）。诊断无 Key 可用，模型修源需 Key。
// 对话框走 LightDialog + 单层 ScrollView（与本地源对话框同款），内容无
// 内层滚动，不踩 AVBoxAlertDialog 外层滚动 + 内层滚动的嵌套闪退。
class AiRepairSettingsDialog private constructor(
    private val context: Context,
    private val onSaved: (() -> Unit)?,
) {

    companion object {
        fun show(context: Context, onSaved: (() -> Unit)? = null) {
            AiRepairSettingsDialog(context, onSaved).build()
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val holder = arrayOfNulls<Dialog>(1)

    private lateinit var baseUrlInput: EditText
    private lateinit var keyInput: EditText
    private lateinit var modelInput: EditText
    private lateinit var roundsInput: EditText
    private lateinit var logBox: CheckBox
    private lateinit var webBox: CheckBox
    private lateinit var testResult: TextView
    private var keyShown: String = ""

    private fun dp(value: Int): Int = Math.round(value * context.resources.displayMetrics.density)

    private fun label(text: String): TextView {
        val view = TextView(context)
        view.text = text
        view.textSize = ViewTheme.BODY_SP
        view.setTextColor(ViewTheme.onSurfaceArgb(context))
        view.typeface = ViewTheme.mediumTypeface()
        val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        params.topMargin = 16
        view.layoutParams = params
        return view
    }

    private fun note(text: String): TextView {
        val view = TextView(context)
        view.text = text
        view.textSize = ViewTheme.SECONDARY_SP
        view.setTextColor(ViewTheme.onSurfaceVariantArgb(context))
        return view
    }

    private fun check(text: String, checked: Boolean): CheckBox {
        val box = CheckBox(context)
        box.text = text
        box.isChecked = checked
        ViewTheme.tintCompound(context, box)
        box.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        return box
    }

    private fun input(hint: String, value: String, inputType: Int): EditText {
        val edit = EditText(context)
        edit.hint = hint
        edit.setSingleLine(true)
        edit.inputType = inputType
        if (!TextUtils.isEmpty(value)) edit.setText(value)
        ViewTheme.styleInput(context, edit)
        return edit
    }

    private fun build() {
        val root = LinearLayout(context)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(4, 0, 4, 0)

        root.addView(label("接口地址"))
        baseUrlInput = input("https://api.openai.com/v1", AiConfig.getBaseUrl(), InputType.TYPE_TEXT_VARIATION_URI)
        root.addView(baseUrlInput)

        root.addView(label("API Key"))
        keyShown = if (AiConfig.hasApiKey()) "已保存：" + AiConfig.masked(AiConfig.getApiKey()) else ""
        keyInput = input("可空；诊断无 Key 也可用，模型修源需 Key", keyShown, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        root.addView(keyInput)

        root.addView(label("模型"))
        val modelRow = LinearLayout(context)
        modelRow.orientation = LinearLayout.HORIZONTAL
        modelInput = input("如 gpt-4o-mini", AiConfig.getModel(), InputType.TYPE_CLASS_TEXT)
        modelInput.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        val fetchButton = Button(context)
        fetchButton.text = "拉取"
        ViewTheme.styleTonalButton(context, fetchButton)
        val fetchParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        fetchParams.marginStart = dp(8)
        fetchButton.layoutParams = fetchParams
        fetchButton.setOnClickListener { fetchModels(fetchButton) }
        modelRow.addView(modelInput)
        modelRow.addView(fetchButton)
        root.addView(modelRow)

        root.addView(label("修复轮次"))
        roundsInput = input("写源/修复轮次，1-20，默认 3", AiConfig.getRounds().toString(), InputType.TYPE_CLASS_NUMBER)
        root.addView(roundsInput)

        logBox = check("诊断时读取悬浮日志作证据", AiConfig.isLogEnabled())
        root.addView(logBox)
        root.addView(note("无 Key 也可用；关闭则只按规则与域名分型"))
        webBox = check("诊断时联网检查域名", AiConfig.isWebEnabled())
        root.addView(webBox)
        root.addView(note("联网检查订阅域名是否可达，关闭则跳过"))

        val testButton = Button(context)
        testButton.text = "连通性测试"
        ViewTheme.styleTonalButton(context, testButton)
        val testParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        testParams.topMargin = dp(16)
        testButton.layoutParams = testParams
        testButton.setOnClickListener { testConnection(testButton) }
        root.addView(testButton)

        testResult = TextView(context)
        testResult.textSize = ViewTheme.SECONDARY_SP
        testResult.setTextColor(ViewTheme.onSurfaceVariantArgb(context))
        testResult.setTextIsSelectable(true)
        testResult.visibility = android.view.View.GONE
        val resultParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        resultParams.topMargin = dp(10)
        testResult.layoutParams = resultParams
        root.addView(testResult)

        root.addView(note("诊断（取证、分型、域名检查）无 Key 可用；模型修源与整站重写需在上面填 Key 后才能开，未配置时不发任何模型请求。"))

        val scroll = ScrollView(context)
        scroll.addView(root)

        holder[0] = LightDialog.create(
            context, "AI 模型", scroll,
            "保存",
            { save() },
            "取消",
            { holder[0]?.dismiss() },
        )
        holder[0]?.setOnDismissListener { scope.cancel() }
        holder[0]?.show()
    }

    private fun currentKey(): String {
        val typed = keyInput.text?.toString()?.trim() ?: ""
        if (TextUtils.isEmpty(typed) || typed == keyShown) return AiConfig.getApiKey()
        return typed
    }

    private fun save() {
        val base = baseUrlInput.text?.toString()?.trim() ?: ""
        if (TextUtils.isEmpty(base)) {
            Notify.show("请填写接口地址")
            return
        }
        val picked = modelInput.text?.toString()?.trim() ?: ""
        if (TextUtils.isEmpty(picked)) {
            Notify.show("请填写模型")
            return
        }
        AiConfig.setBaseUrl(base)
        AiConfig.setModel(picked)
        val typedKey = keyInput.text?.toString()?.trim() ?: ""
        if (!TextUtils.isEmpty(typedKey) && typedKey != keyShown) AiConfig.setApiKey(typedKey)
        try {
            AiConfig.setRounds((roundsInput.text?.toString()?.trim() ?: "").toInt())
        } catch (ignored: Throwable) {
        }
        AiConfig.setLogEnabled(logBox.isChecked)
        AiConfig.setWebEnabled(webBox.isChecked)
        holder[0]?.dismiss()
        onSaved?.invoke()
    }

    private fun fetchModels(button: Button) {
        val base = baseUrlInput.text?.toString()?.trim() ?: ""
        if (TextUtils.isEmpty(base)) {
            Notify.show("请填写接口地址")
            return
        }
        val useKey = currentKey()
        if (TextUtils.isEmpty(useKey)) {
            Notify.show("请先填写 Key（拉取模型列表需要 Key）")
            return
        }
        button.isEnabled = false
        button.text = "拉取中…"
        scope.launch {
            try {
                val models = AiRepair.listModels(base, useKey)
                if (holder[0]?.isShowing != true) return@launch
                button.isEnabled = true
                button.text = "拉取"
                if (models.isEmpty()) {
                    Notify.show("拉取到了 0 个模型")
                    return@launch
                }
                showModelPicker(models)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (holder[0]?.isShowing != true) return@launch
                button.isEnabled = true
                button.text = "拉取"
                Notify.show("拉取失败：" + (e.message ?: e.javaClass.simpleName))
            }
        }
    }

    private fun showModelPicker(models: List<String>) {
        val box = LinearLayout(context)
        box.orientation = LinearLayout.VERTICAL
        val group = RadioGroup(context)
        group.orientation = RadioGroup.VERTICAL
        box.addView(group)
        val scroll = ScrollView(context)
        scroll.addView(box)
        val pickerHolder = arrayOfNulls<Dialog>(1)
        for (name in models) {
            val item = RadioButton(context)
            item.text = name
            item.setSingleLine(true)
            ViewTheme.tintCompound(context, item)
            item.layoutParams = RadioGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            item.setOnClickListener {
                modelInput.setText(name)
                pickerHolder[0]?.dismiss()
            }
            group.addView(item)
            if (name == modelInput.text?.toString()?.trim()) group.check(item.id)
        }
        pickerHolder[0] = LightDialog.create(
            context, "选择模型（" + models.size + " 个）", scroll,
            "取消",
            { pickerHolder[0]?.dismiss() },
            null, null,
        )
        pickerHolder[0]?.show()
    }

    private fun testConnection(button: Button) {
        val base = baseUrlInput.text?.toString()?.trim() ?: ""
        if (TextUtils.isEmpty(base)) {
            Notify.show("请填写接口地址")
            return
        }
        val picked = modelInput.text?.toString()?.trim() ?: ""
        if (TextUtils.isEmpty(picked)) {
            Notify.show("请填写模型")
            return
        }
        val useKey = currentKey()
        if (TextUtils.isEmpty(useKey)) {
            Notify.show("请先填写 Key（连通性测试需要 Key）")
            return
        }
        button.isEnabled = false
        button.text = "测试中…"
        testResult.visibility = android.view.View.VISIBLE
        testResult.text = "测试中…"
        scope.launch {
            try {
                val result = AiRepair.testModel(base, useKey, picked)
                if (holder[0]?.isShowing != true) return@launch
                button.isEnabled = true
                button.text = "连通性测试"
                testResult.visibility = android.view.View.VISIBLE
                testResult.text = if (result.ok) {
                    "测试通过（耗时 " + result.elapsedMs + "ms）：" + result.message
                } else {
                    "测试失败：" + result.message
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (holder[0]?.isShowing != true) return@launch
                button.isEnabled = true
                button.text = "连通性测试"
                testResult.visibility = android.view.View.VISIBLE
                testResult.text = "测试失败：" + (e.message ?: e.javaClass.simpleName)
            }
        }
    }
}
