package com.github.tvbox.osc.reading

/**
 * Minimal traditional -> simplified Chinese conversion for
 * `java.t2s(...)`: a mapping of common characters only (novel text is
 * dominated by a few hundred high-frequency glyphs). Characters without
 * a mapping pass through unchanged. This is deliberately NOT a full
 * OpenCC table — it exists so t2s-dependent rules return simplified
 * text instead of throwing on the missing API.
 */
object T2S {

    // Space-separated "traditional+simplified" pairs; malformed tokens
    // are dropped at load so one bad token can never shift the table.
    private const val PAIRS = """
麼么 這这 們们 個个 來来 時时 過过 還还 進进 遠远 運运 輸输 達达 遲迟 適适 邊边 選选 遞递 郵邮 關关 開开 門门 問问 聞闻 閱阅 閣阁 閒闲 間间 闊阔 闖闯 闌阑 長长 動动 靜静 態态 勢势 勁劲 勞劳 勵励 勸劝 務务 勝胜
負负 貨货 貧贫 貪贪 貴贵 賤贱 買买 賣卖 貸贷 費费 賀贺 賊贼 賓宾 賞赏 賠赔 賢贤 賬账 質质 賴赖 贈赠 贏赢 趕赶 趨趋 踐践 踴踊 車车 軍军 軒轩 軟软 輕轻 較较 輛辆 輝辉 輩辈 輪轮 辭辞 農农 迴回 逕径 連连 週周 遍遍 遊游 遙遥 遜逊 遷迁 邁迈 邇迩 邏逻 鄉乡 鄭郑 鄧邓
醫医 醬酱 釀酿 釋释 裡里 書书 畫画 晝昼 曆历 國国 圍围 園园 圓圆 圖图 團团 壘垒 壩坝 壓压 厭厌 厲厉 廠厂 廈厦 廳厅 廚厨 廟庙 廣广 愛爱 憂忧 慶庆 憲宪 憶忆 懷怀 戲戏 戰战 戶户 戀恋 執执 堅坚 墊垫 墜坠 墳坟 層层 牆墙 壯壮 聲声 壺壶 壽寿 夢梦 夥伙 夾夹 奪夺 奮奋 妝妆 婦妇 媽妈 娛娱 嬰婴
嬌娇 嬸婶 孃娘 學学 孫孙 寧宁 寶宝 實实 寵宠 審审 寫写 寬宽 寢寝 對对 尋寻 導导 屆届 屬属 峽峡 島岛 嶺岭 嶽岳 巔巅 幣币 師师 帳账 帶带 幟帜 幫帮 幹干 幾几 庫库 廁厕 廢废 廬庐 強强 彈弹 彙汇 從从 徵征 徹彻 應应 懇恳 懸悬 懲惩 話话 語语 說说 讀读 誰谁 課课 調调 談谈 請请 論论 諾诺 謀谋 謝谢 講讲 謎谜 謂谓 諷讽
諸诸 諮咨 諺谚 謁谒 謄誊 謊谎 謾谩 識识 證证 議议 護护 譽誉 譯译 譴谴 變变 讓让 讕谰 讜谠 貝贝 見见 視视 親亲 覺觉 覽览 觀观 觸触 訂订 訃讣 討讨 訝讶 訥讷 訛讹 認认 誓誓 誣诬 誌志 誦诵 該该 詮诠 詰诘 試试 詩诗 詫诧 詔诏 魚鱼 鳥鸟 鳳凤 鳴鸣 鴨鸭 鴿鸽 鵝鹅 鶯莺 鷹鹰 鹹咸 鹽盐 麗丽 麥麦 麩麸 麵面 黨党 黴霉 黃黄 點点 齡龄
齒齿 龍龙 龜龟 風风 飛飞 飯饭 飲饮 飼饲 飽饱 餓饿 館馆 餃饺 餅饼 餛馄 饅馒 馬马 駕驾 駛驶 駢骈 駭骇 駐驻 騎骑 騙骗 騷骚 驛驿 驟骤 驢驴 驥骥 驪骊 髒脏 體体 髮发 鬆松 鬥斗 鬧闹 鬱郁 魘魇 頁页 頂顶 項项 順顺 須须 頒颁 頌颂 頗颇 領领 頤颐 頰颊 頸颈 頻频 題题 額额 顏颜 願愿 顛颠 類类 顧顾 顯显 顫颤 電电 雲云 靈灵 霧雾 霽霁 霹霹 韋韦
韜韬 韓韩 響响 預预 頑顽 頓顿 頜颌 頡颉 顆颗 馮冯 馳驰 馴驯 馭驭 馱驮 線线 總总 經经 綠绿 紅红 紙纸 級级 練练 續续 給给 結结 統统 絲丝 織织 終终 維维 綱纲 緯纬 緣缘 緩缓 締缔 緻致 綜综 綢绸 綿绵 緊紧 緒绪 緋绯 綾绫 綺绮 綵彩 網网 羅罗 罰罚 罷罢 罵骂 習习 聖圣 聰聪 職职 聯联 聽听 聳耸 腦脑 脅胁 脈脉 腳脚 脫脱 腫肿 腸肠 膚肤 膽胆 膾脍
膿脓 臉脸 臘腊 臟脏 臥卧 臨临 與与 興兴 舊旧 舉举 舖铺 艙舱 艱艰 艷艳 藝艺 節节 莊庄 莖茎 莢荚 莧苋 華华 萊莱 萤萤 藍蓝 藥药 藹蔼 蘋苹 蘿萝 蘚藓 蘆芦 蘇苏 蘭兰 術术 衛卫 衝冲 襪袜 裝装 補补 製制 複复 褲裤 褻亵 襖袄 襯衬 襲袭 規规 覓觅 覘觇 覦觎 覬觊 覯觏 覲觐 覷觑 計计 訟讼 訓训 訕讪 訖讫 託托 記记 詢询 詛诅 詞词 詠咏 詡诩 詣诣
"""

    private val map: Map<Char, Char> = PAIRS
        .split(Regex("\\s+"))
        .filter { it.length == 2 }
        .associate { it[0] to it[1] }

    fun convert(text: String): String {
        if (text.isEmpty()) return text
        val sb = StringBuilder(text.length)
        for (c in text) sb.append(map[c] ?: c)
        return sb.toString()
    }
}
