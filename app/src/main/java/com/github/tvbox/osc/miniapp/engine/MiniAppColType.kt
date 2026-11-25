package com.github.tvbox.osc.miniapp.engine

enum class MiniAppColType(
    val itemType: Int,
    val code: String,
    val leftRight: Int,
    val spanCount: Int,
    val landSpanCount: Int,
) {
    TEXT_1(5, "text_1", 0, 60, 60),
    TEXT_2(6, "text_2", 10, 30, 15),
    TEXT_3(11, "text_3", 10, 20, 20),
    TEXT_4(12, "text_4", 10, 15, 15),
    TEXT_5(44, "text_5", 5, 12, 12),
    TEXT_CENTER_1(18, "text_center_1", 0, 60, 60),
    MOVIE_1(4, "movie_1", 0, 60, 60),
    MOVIE_2(9, "movie_2", 12, 30, 15),
    MOVIE_3(1, "movie_3", 12, 20, 10),
    MOVIE_1_LEFT_PIC(20, "movie_1_left_pic", 0, 60, 60),
    MOVIE_1_VERTICAL_PIC(21, "movie_1_vertical_pic", 0, 60, 60),
    MOVIE_1_VERTICAL_PIC_BLUR(45, "movie_1_vertical_pic_blur", 0, 60, 60),
    MOVIE_3_MARQUEE(27, "movie_3_marquee", 12, 20, 10),
    PIC_1(7, "pic_1", 0, 60, 60),
    PIC_2(8, "pic_2", 12, 30, 15),
    PIC_3(10, "pic_3", 3, 20, 20),
    PIC_1_FULL(19, "pic_1_full", 0, 60, 60),
    PIC_1_CENTER(51, "pic_1_center", 0, 60, 60),
    PIC_3_SQUARE(30, "pic_3_square", 0, 20, 20),
    PIC_1_CARD(32, "pic_1_card", 0, 60, 60),
    PIC_2_CARD(47, "pic_2_card", 10, 30, 15),
    ICON_1_SEARCH(33, "icon_1_search", 0, 60, 60),
    ICON_2(17, "icon_2", 10, 30, 15),
    ICON_2_ROUND(35, "icon_2_round", 10, 30, 15),
    ICON_3_ROUND_FILL(53, "icon_3_round_fill", 10, 20, 20),
    ICON_3_FILL(55, "icon_3_fill", 10, 20, 20),
    ICON_3_SMALL(25, "icon_small_3", 10, 20, 20),
    ICON_4(13, "icon_4", 10, 15, 10),
    ICON_4_CARD(36, "icon_4_card", 10, 15, 10),
    ICON_4_SMALL(14, "icon_small_4", 10, 15, 10),
    ICON_4_ROUND(15, "icon_round_4", 10, 15, 10),
    ICON_4_ROUND_SMALL(16, "icon_round_small_4", 10, 15, 10),
    ICON_5(49, "icon_5", 10, 12, 12),
    ICON_5_NO_CROP(58, "icon_5_no_crop", 10, 12, 12),
    LONG_TEXT(22, "long_text", 0, 60, 60),
    RICH_TEXT(24, "rich_text", 0, 60, 60),
    AVATAR(28, "avatar", 0, 60, 60),
    TEXT_ICON(50, "text_icon", 0, 60, 60),
    X5_WEB_VIEW(34, "x5_webview_single", 0, 60, 60),
    VIDEO(56, "video", 0, 60, 60),
    HEADER(2, "header", 0, 60, 60),
    FOOTER(3, "footer", 0, 60, 60),
    LINE(23, "line", 0, 60, 60),
    LINE_BLANK(26, "line_blank", 0, 60, 60),
    BLANK_BLOCK(29, "blank_block", 0, 60, 60),
    BIG_BLANK_BLOCK(31, "big_blank_block", 0, 60, 60),
    FLEX_BUTTON(37, "flex_button", 2, 60, 60),
    SCROLL_BUTTON(38, "scroll_button", 2, 60, 60),
    CARD_PIC_2(39, "card_pic_2", 15, 30, 15),
    CARD_PIC_3(48, "card_pic_3", 15, 20, 10),
    CARD_PIC_3_CENTER(52, "card_pic_3_center", 15, 20, 10),
    CARD_PIC_1(42, "card_pic_1", 15, 60, 60),
    CARD_PIC_2_2(40, "card_pic_2_2", 15, 30, 15),
    CARD_PIC_2_2_LEFT(41, "card_pic_2_2_left", 15, 30, 15),
    INPUT(43, "input", 12, 60, 60),
    BIG_BIG_BLANK_BLOCK(46, "big_big_blank_block", 0, 60, 60),
    ICON_1_LEFT_PIC(57, "icon_1_left_pic", 0, 60, 60);

    companion object {
        const val TEMP_TYPE_ICON_3_LIST = -3

        fun getByCode(code: String?): MiniAppColType {
            if (code == null) return MOVIE_3
            for (v in values()) {
                if (v.code == code) return v
            }
            return MOVIE_3
        }

        fun getByItemType(itemType: Int): MiniAppColType {
            for (v in values()) {
                if (v.itemType == itemType) return v
            }
            if (itemType == TEMP_TYPE_ICON_3_LIST) return ICON_3_SMALL
            return MOVIE_3
        }

        fun getLeftRightByItemType(itemType: Int): Int {
            if (itemType == TEMP_TYPE_ICON_3_LIST) return ICON_3_SMALL.leftRight
            for (v in values()) {
                if (v.itemType == itemType) return v.leftRight
            }
            return 0
        }

        fun getSpanCountByItemType(itemType: Int): Int {
            for (v in values()) {
                if (v.itemType == itemType) return v.spanCount
            }
            if (itemType == TEMP_TYPE_ICON_3_LIST) return ICON_3_SMALL.spanCount
            return 60
        }

        fun getLandSpanCountByItemType(itemType: Int): Int {
            for (v in values()) {
                if (v.itemType == itemType) return v.landSpanCount
            }
            if (itemType == TEMP_TYPE_ICON_3_LIST) return ICON_3_SMALL.landSpanCount
            return 60
        }

        fun getCodeArray(): List<String> {
            val out = ArrayList<String>()
            for (v in values()) {
                if (v == HEADER || v == FOOTER) continue
                out.add(v.code)
            }
            return out
        }
    }
}
