package com.github.tvbox.osc.api.loader;

import android.text.TextUtils;

import com.github.catvod.utils.Prefers;
import com.github.tvbox.osc.bean.SourceBean;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * r243：用户手动添加的「网页源」清单（一个网址 = 一条源，不需要 py / js / jar / 文件）。
 *
 * <p>存储：{@code Prefers} 键 {@code web_sources}，一行一条，格式 {@code 名称|网址|分组|失效|发布页}。
 * r346 之前是两段 {@code 名称|网址}；r349 加第 4 段「失效标记」（"1"=失效）；r350 加第 5 段「发布页」
 * （域名失效时从发布页自动找新域名，参考 jable_失效自愈版 py 源）。后两段都可缺省，旧数据自动补空。
 * 之所以不用 JSON：这里只有五个字段，行文本最省事、也不会因为用户手改文本就整份解析失败。
 *
 * <p>读取：{@code ApiConfig.parseJson} 解析完订阅里的站点后，把这份清单追加进站点列表
 * （见 {@code ApiConfig.parseJson} 的网页源注入段），所以添加后重载首页即可看到，不用改订阅文件。
 * （AVBox 移植版：原 VodConfig/Site 改为 ApiConfig/SourceBean。）
 */
public class WebSourceStore {

    private static final String KEY = "web_sources";
    // r392：上限 500 → 1000 —— 用户明确「上限 500 还是不够，至少要 1000」。
    private static final int MAX = 1000;

    /** 当前清单，元素为 {名称, 网址, 分组, 失效标记, 发布页}（第 4 段 "1"=失效；第 5 段=发布页/项目地址；旧数据自动补空）。 */
    public static List<String[]> list() {
        List<String[]> out = new ArrayList<>();
        String text = Prefers.getString(KEY, "");
        if (TextUtils.isEmpty(text)) return out;
        for (String raw : text.split("\\r?\\n")) {
            String line = raw == null ? "" : raw.trim();
            if (line.isEmpty()) continue;
            int bar = line.indexOf('|');
            String name = bar > 0 ? line.substring(0, bar).trim() : "";
            String rest = bar >= 0 ? line.substring(bar + 1) : line;
            int bar2 = rest.indexOf('|');
            String url = bar2 >= 0 ? rest.substring(0, bar2).trim() : rest.trim();
            // r349：第 4 段失效标记（"1"=失效）。旧版把第 2 个 | 之后整段当分组（吞尾），
            // 改为限段解析。r350 再加第 5 段发布页：dead 之后再有 | 才是 pub（网址天然不含 |）。
            // 旧 3 段/4 段数据 dead/pub 缺省 = 正常/未配置。
            String tail = bar2 >= 0 ? rest.substring(bar2 + 1).trim() : "";
            String group = tail;
            String dead = "";
            String pub = "";
            int bar3 = tail.indexOf('|');
            if (bar3 >= 0) {
                group = tail.substring(0, bar3).trim();
                String tail4 = tail.substring(bar3 + 1).trim();
                int bar4 = tail4.indexOf('|');
                if (bar4 >= 0) {
                    dead = tail4.substring(0, bar4).trim();
                    pub = tail4.substring(bar4 + 1).trim();
                } else {
                    dead = tail4;
                }
            }
            // r352：迁移 r350/r351 错位数据 —— 旧 save() 空段不写竖线，发布页被挤到分组位/失效位
            // （落盘 missav|url|x99dh.pro/ 读回 group=x99dh.pro/、pub 永远空 → 自愈永远『未配置发布页』）。
            // 发布页存盘前必过 normalize（http 开头），分组名/失效标记不可能 http 开头，判据零误伤。
            if (TextUtils.isEmpty(pub)) {
                boolean gHttp = group.startsWith("http://") || group.startsWith("https://");
                boolean dHttp = dead.startsWith("http://") || dead.startsWith("https://");
                if (gHttp) { pub = group; group = ""; }                                    // 场景A：分组空时 pub 左移到分组位
                else if ("1".equals(group) && dHttp) { pub = dead; dead = "1"; group = ""; } // 场景C：失效标记跟着左移
                else if (dHttp) { pub = dead; dead = ""; }                                  // 场景B：有分组时 pub 左移到失效位
            }
            if (TextUtils.isEmpty(url)) continue;
            if (TextUtils.isEmpty(name)) name = defaultName(url);
            out.add(new String[]{name, url, group, "1".equals(dead) ? "1" : "", pub});
        }
        return out;
    }

    /** r346：已使用的分组名（去重，保持添加顺序），供「归类」下拉复用。 */
    public static List<String> groups() {
        List<String> out = new ArrayList<>();
        for (String[] item : list()) if (!TextUtils.isEmpty(item[2]) && !out.contains(item[2])) out.add(item[2]);
        return out;
    }

    /** 转成站点对象，交给 ApiConfig 合并进站点列表。 */
    public static List<SourceBean> sites() {
        List<SourceBean> out = new ArrayList<>();
        for (String[] item : list()) {
            // r349：失效源不注入首页站点列表（移入失效源列表后不再挂首页，恢复时再回来）
            if (item.length > 3 && "1".equals(item[3])) continue;
            SourceBean site = toSite(item[0], item[1]);
            if (site != null) out.add(site);
        }
        return out;
    }

    public static SourceBean toSite(String name, String url) {
        try {
            SourceBean bean = new SourceBean();
            bean.setKey("web_" + slug(url));
            bean.setName(name);
            bean.setType(3);
            bean.setApi("web");
            bean.setExt(url);
            bean.setSearchable(1);
            return bean;
        } catch (Throwable e) {
            return null;
        }
    }

    /** 添加或更新一条（同网址覆盖名称与分组），返回是否新增成功。 */
    /** 添加或更新一条（同网址覆盖名称与分组），返回是否新增成功。 */
    public static boolean add(String inputUrl, String inputName, String group) {
        return add(inputUrl, inputName, group, null);
    }

    /** r350：带发布页的添加。pub 为 null = 保持原值（迁移场景），空串 = 清空，非空 = 覆盖。 */
    public static boolean add(String inputUrl, String inputName, String group, String pub) {
        String url = normalize(inputUrl);
        if (TextUtils.isEmpty(url)) return false;
        String name = TextUtils.isEmpty(inputName) ? defaultName(url) : inputName.trim();
        List<String[]> items = list();
        for (String[] item : items) {
            if (sameSite(item[1], url)) {
                item[0] = name;
                item[1] = url;
                item[2] = group == null ? "" : group.trim();
                // r349：同 host 重新添加视为恢复（清失效标记）
                if (item.length > 3) item[3] = "";
                // r350：发布页 —— null 保持原值（updateUrl 迁移用），非 null 覆盖（清空也生效）
                if (item.length > 4 && pub != null) item[4] = pub.trim();
                save(items);
                // 1.27：默认全选 HTML——新加/重加的源默认用 HTML 首页
                setWebHome(url, true);
                return true;
            }
        }
        if (items.size() >= MAX) return false;
        items.add(new String[]{name, url, group == null ? "" : group.trim(), "", pub == null ? "" : pub.trim()});
        save(items);
        // 1.27：默认全选 HTML——新加的源默认用 HTML 首页
        setWebHome(url, true);
        return true;
    }

    /** r346：编辑一条（按原网址定位，改名称与分组；r348 起改网址走 updateUrl——同域名改路径原位、换域名删旧加新）。 */
    public static boolean update(String url, String name, String group) {
        String target = normalize(url);
        List<String[]> items = list();
        for (String[] item : items) {
            if (sameSite(item[1], target)) {
                item[0] = TextUtils.isEmpty(name) ? item[0] : name.trim();
                item[2] = group == null ? "" : group.trim();
                save(items);
                return true;
            }
        }
        return false;
    }

    /** r348：编辑一条（允许改网址）。同 host 只改路径原位更新；换 host 删旧加新（key 由 host 派生）；
     *  新网址与现有其他条目同 host 时并入该条目（名称/分组以本次输入为准）。网址留空 = 只改名称/分组。 */
    public static boolean updateUrl(String oldUrl, String inputUrl, String name, String group) {
        String target = normalize(inputUrl);
        if (TextUtils.isEmpty(target)) return update(oldUrl, name, group);
        List<String[]> items = list();
        int oldIdx = -1;
        for (int i = 0; i < items.size(); i++) {
            if (sameSite(items.get(i)[1], oldUrl)) { oldIdx = i; break; }
        }
        if (oldIdx < 0) return false;
        String[] old = items.get(oldIdx);
        if (sameSite(old[1], target)) {
            old[0] = TextUtils.isEmpty(name) ? old[0] : name.trim();
            old[1] = target;
            old[2] = group == null ? "" : group.trim();
            save(items);
            return true;
        }
        items.remove(oldIdx);
        save(items);
        // r350：换域名删旧加新时，发布页必须迁移 —— 否则下次域名失效就无法再自愈了
        String oldPub = old.length > 4 ? old[4] : "";
        return add(target, TextUtils.isEmpty(name) ? old[0] : name.trim(), group, oldPub);
    }

    public static void remove(String url) {
        if (TextUtils.isEmpty(url)) return;
        List<String[]> items = list();
        items.removeIf(item -> sameSite(item[1], url));
        save(items);
    }

    /** r346：批量删除（多选删除用），返回实际删除的条数。 */
    public static int removeList(List<String> urls) {
        if (urls == null || urls.isEmpty()) return 0;
        List<String[]> items = list();
        int before = items.size();
        items.removeIf(item -> {
            for (String u : urls) if (sameSite(item[1], u)) return true;
            return false;
        });
        save(items);
        return before - items.size();
    }

    /** r346：批量归类（多选移动到分组用），返回实际改动的条数。 */
    public static int moveGroup(List<String> urls, String group) {
        if (urls == null || urls.isEmpty()) return 0;
        String g = group == null ? "" : group.trim();
        List<String[]> items = list();
        int changed = 0;
        for (String[] item : items) {
            for (String u : urls) {
                if (sameSite(item[1], u) && (!g.equals(item[2]) || (item.length > 3 && "1".equals(item[3])))) {
                    item[2] = g;
                    // r349：归类即恢复（归类是用户主动行为，失效标记一并清）
                    if (item.length > 3) item[3] = "";
                    changed++;
                    break;
                }
            }
        }
        if (changed > 0) save(items);
        return changed;
    }

    /** r349：批量标记失效/恢复。失效标记独立于分组字段 —— 分组保留（恢复后自动回原分类桶），
     *  只有实际发生状态变化的才计数落盘。归类（moveGroup）与重新添加（add）会顺带清失效标记。 */
    public static int markDead(List<String> urls, boolean dead) {
        if (urls == null || urls.isEmpty()) return 0;
        List<String[]> items = list();
        int changed = 0;
        for (String[] item : items) {
            for (String u : urls) {
                if (!sameSite(item[1], u)) continue;
                String cur = item.length > 3 ? item[3] : "";
                String next = dead ? "1" : "";
                if (!cur.equals(next)) {
                    if (item.length > 3) item[3] = next;
                    changed++;
                }
                break;
            }
        }
        if (changed > 0) save(items);
        return changed;
    }

    /** r349：恢复失效源（清失效标记，保留原分组）。 */
    public static int restore(List<String> urls) {
        return markDead(urls, false);
    }

    /** r349：当前失效源 url 清单，供检测流程与 UI 判断。 */
    public static List<String> deadUrls() {
        List<String> out = new ArrayList<>();
        for (String[] item : list()) if (item.length > 3 && "1".equals(item[3])) out.add(item[1]);
        return out;
    }

    /** r350：取某源的发布页/项目地址（按 host 匹配），未配置返回空串。 */
    public static String publishOf(String url) {
        String h = host(url);
        if (TextUtils.isEmpty(h)) return "";
        for (String[] item : list()) {
            if (host(item[1]).equals(h)) return item.length > 4 ? item[4] : "";
        }
        return "";
    }

    /** r350：保存某源的发布页（按 host 匹配原位更新；host 不存在返回 false）。 */
    public static boolean setPublish(String url, String pub) {
        String h = host(url);
        if (TextUtils.isEmpty(h)) return false;
        List<String[]> items = list();
        for (String[] item : items) {
            if (host(item[1]).equals(h)) {
                if (item.length > 4) item[4] = pub == null ? "" : pub.trim();
                save(items);
                return true;
            }
        }
        return false;
    }

    public static void clear() {
        Prefers.put(KEY, "");
    }

    public static boolean contains(String url) {
        String target = normalize(url);
        for (String[] item : list()) if (sameSite(item[1], target)) return true;
        return false;
    }

    /** 1.17：网页源默认用 HTML 首页的开关（按网址存，Prefers 键 webhome_enabled，一行一个网址）。 */
    private static final String KEY_WEBHOME = "webhome_enabled";

    public static boolean isWebHome(String url) {
        if (TextUtils.isEmpty(url)) return false;
        String text = Prefers.getString(KEY_WEBHOME, "");
        if (TextUtils.isEmpty(text)) return false;
        for (String line : text.split("\\r?\\n")) {
            if (url.equals(line.trim())) return true;
        }
        return false;
    }

    public static void setWebHome(String url, boolean on) {
        if (TextUtils.isEmpty(url)) return;
        List<String> kept = new ArrayList<>();
        String text = Prefers.getString(KEY_WEBHOME, "");
        if (!TextUtils.isEmpty(text)) {
            for (String line : text.split("\\r?\\n")) {
                String t = line.trim();
                if (!TextUtils.isEmpty(t) && !t.equals(url)) kept.add(t);
            }
        }
        if (on) kept.add(url);
        StringBuilder sb = new StringBuilder();
        for (String s : kept) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(s);
        }
        Prefers.put(KEY_WEBHOME, sb.toString());
    }

    /** 1.23：全部启用/停用 HTML 首页（网页源管理页「全选」）。 */
    public static void setAllWebHome(boolean on) {
        if (!on) {
            Prefers.put(KEY_WEBHOME, "");
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (String[] item : list()) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(item[1]);
        }
        Prefers.put(KEY_WEBHOME, sb.toString());
    }

    /** 1.27：默认全选 HTML——首次进入网页源管理页时，把已有源全部启用 HTML 首页（只做一次，之后以用户点的全选为准）。 */
    private static final String KEY_WEBHOME_DEFAULT_APPLIED = "webhome_all_default_applied";

    public static void applyAllWebHomeDefault() {
        if (Prefers.getBoolean(KEY_WEBHOME_DEFAULT_APPLIED, false)) return;
        setAllWebHome(true);
        Prefers.put(KEY_WEBHOME_DEFAULT_APPLIED, true);
    }

    public static String statusText() {
        int count = list().size();
        return count == 0 ? "未添加" : ("已添加 " + count + " 个");
    }

    private static void save(List<String[]> items) {
        StringBuilder sb = new StringBuilder();
        for (String[] item : items) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(item[0]).append('|').append(item[1]);
            String g = item.length > 2 ? item[2] : "";
            String d = item.length > 3 && "1".equals(item[3]) ? "1" : "";
            String p = item.length > 4 ? item[4] : "";
            // r352：发布页是位置语义字段，有值时中间段必须写全占位 —— 旧写法空段不写竖线，
            // 落盘 missav|url|x99dh.pro/ 会被 list() 读回成分组，publishOf 永远空 → 自愈永远『未配置发布页』
            // （r351 真机日志 6 次『跳过自愈 www.missav09.com（未配置发布页）』实证，沙箱 11 用例复现）。
            if (!TextUtils.isEmpty(p)) sb.append('|').append(g).append('|').append(d).append('|').append(p);
            else {
                // r346/r349 旧省略式（无发布页时保持，与旧版本 App 互读兼容）
                if (!TextUtils.isEmpty(g)) sb.append('|').append(g);
                if (!TextUtils.isEmpty(d)) sb.append("|1");
            }
        }
        Prefers.put(KEY, sb.toString());
    }

    /** 补协议、去空格、去末尾斜杠之外的噪音；不是网址则返回空串。 */
    public static String normalize(String input) {
        if (TextUtils.isEmpty(input)) return "";
        String url = input.trim().replace("\u3000", " ").trim();
        int space = url.indexOf(' ');
        if (space > 0) url = url.substring(0, space);
        if (!url.startsWith("http")) {
            if (!url.contains(".")) return "";
            url = "https://" + url;
        }
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        if (url.length() <= "https://".length() + 2) return "";
        return url + "/";
    }

    private static boolean sameSite(String a, String b) {
        return host(normalize(a)).equals(host(normalize(b)));
    }

    public static String host(String url) {
        String text = TextUtils.isEmpty(url) ? "" : url.trim();
        int start = text.indexOf("://");
        if (start >= 0) text = text.substring(start + 3);
        int slash = text.indexOf('/');
        if (slash >= 0) text = text.substring(0, slash);
        int colon = text.indexOf(':');
        if (colon >= 0) text = text.substring(0, colon);
        return text.toLowerCase(Locale.ROOT);
    }

    private static String defaultName(String url) {
        String h = host(url);
        if (TextUtils.isEmpty(h)) return "网页源";
        String head = h.startsWith("www.") ? h.substring(4) : h;
        int dot = head.indexOf('.');
        return dot > 0 ? head.substring(0, dot) : head;
    }

    private static String slug(String url) {
        String h = host(url).replaceAll("[^a-z0-9]", "");
        return h.isEmpty() ? String.valueOf(Math.abs(url.hashCode())) : h;
    }
}
