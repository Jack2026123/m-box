package com.github.tvbox.osc.ui.activity;

import android.annotation.SuppressLint;
import android.app.Activity;
import androidx.appcompat.app.AlertDialog;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.graphics.Bitmap;
import android.os.Message;
import android.os.SystemClock;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Bundle;
import android.text.TextUtils;
import android.content.pm.ActivityInfo;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.ConsoleMessage;
import android.webkit.SslErrorHandler;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.ai.AiAdLearner;
import com.github.tvbox.osc.api.ai.PublishPageResolver;
import com.github.tvbox.osc.api.loader.AiAdRuleStore;
import com.github.tvbox.osc.api.loader.BrowserNovelSpider;
import com.github.tvbox.osc.api.loader.BrowserPlaySpider;
import com.github.tvbox.osc.api.loader.RuleConfig;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.api.loader.WebSourceStore;
import com.github.tvbox.osc.api.loader.WebSpider;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.ui.custom.WebHomeBridge;
import com.github.tvbox.osc.ui.custom.WebHomeTemplates;
import com.github.tvbox.osc.base.BaseActivity;
import com.github.tvbox.osc.bean.VodInfo;
import com.github.tvbox.osc.databinding.ActivityBrowserBinding;
import com.github.tvbox.osc.reader.NovelReaderActivity;
import com.github.tvbox.osc.reader.NovelSettings;
import com.github.tvbox.osc.reader.NovelTheme;
import com.github.tvbox.osc.reader.NovelThemes;
import com.github.tvbox.osc.reader.ReaderContent;
import com.github.tvbox.osc.reader.ReaderSession;
import com.github.tvbox.osc.reader.ReaderSessions;
import com.github.tvbox.osc.reader.ReaderStore;
import com.github.tvbox.osc.ui.dialog.AiTaskBoard;
import com.github.tvbox.osc.ui.page.JumpKt;
import com.github.tvbox.osc.ui.theme.ViewTheme;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.api.loader.SiteLiveHost;
import com.github.tvbox.osc.util.KV;
import com.github.tvbox.osc.util.Notify;
import com.github.tvbox.osc.util.Sniffer;
import com.github.catvod.crawler.DebugLogStore;

import java.io.ByteArrayInputStream;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;


/**
 * r470：浏览器模式网页源宿主 —— 壳内像浏览器一样自由浏览（多级 JS 跳板/防红壳/UA 过滤站都能进），
 * 同时过滤网页广告弹窗、支持把网页内播放转交壳播放器。
 *
 * <p>三个需求点对应三组机制：</p>
 * <ul>
 *   <li><b>自由浏览</b>：标准 WebView（JS/DOM 存储/缩放），返回键网页内回退，跨页跳转不丢上下文；
 *       UA 白名单站主文档连接失败时自动以「追加后缀 UA」重试一次（通用降级，不锚死站点）。</li>
  *   <li><b>广告弹窗过滤</b>：三层 —— ①网络层拦截广告域名（订阅规则 + 内置表，媒体流豁免）；
 *       ②原生新窗口统一过滤 + JS/原生双层吞掉 alert/confirm/prompt；
 *       ③DOM 层注入脚本移除广告 iframe/跳转链接 + MutationObserver 防抖巡检动态插入（挑战页整页保护）。</li>
 *   <li><b>网页播放转壳播放器</b>：①网络嗅探：shouldInterceptRequest 捕获 .m3u8/.mp4 媒体请求
 *       （含跨域 iframe 内的），点亮「原生播放」按钮；②按钮点击：优先直取页面 video.currentSrc，
 *       取不到用最近嗅探地址，交给 {@link VideoActivity}（PUSH 直链路径）进壳播放器；
 *       ③blob:/MSE 型拿不到直链时保持网页播放（按钮不点亮），不破坏浏览。</li>
 * </ul>
 *
 * <p>与 CustomWebView（后台嗅探、验证页、清场脚本）完全独立：本页是用户前台浏览场景，
 * 不注入 REQ_HOOK/PAGE_PLAY_API，不碰解析超时 —— 嗅探播放链路（r307/不可破坏清单）零影响。</p>
 *
 * <p>3.34 AVBox 移植版：基类换 AVBox BaseActivity（Compose 式，init() 里 setContentView
 * 换掉 ComposeView）；播放走 browser_play / browser_novel 虚拟源进壳播放器/阅读器；
 * RuleConfig 用户规则换 AdBlocker（订阅 ads）；MusicTopInset 删；App.post 换 Handler；
 * onPageFinished 保留 AVBox 蜘蛛阅读页样式注入（/b? 小说 / /v? 漫画）。</p>
 */
public class BrowserActivity extends BaseActivity {

    /** 内置广告域名特征（网络层拦截；与 RuleConfig 用户规则叠加）。r472 扩表：中文圈广告联盟/统计追踪/360 信息流。 */
    private static final String[] AD_HOSTS = {
            "doubleclick", "googlesyndication", "googleadservices", "adservice", "adsystem",
            "adsterra", "popads", "popcash", "propellerads", "onclickads", "onclickalgo",
            "exoclick", "juicyads", "trafficjunky", "mgid.com", "taboola", "outbrain",
            "adnxs.com", "criteo", "hilltopads", "clickadu", "adcash", "adcashrevenue",
            "plugrush", "zeropark", "trafficshop", "coinzilla", "a-ads.com", "adskeeper",
            // r472：中文圈广告联盟/统计/360 信息流（cmlwsn.dyhs2 首页 149 广告图 + 368 外域链接全来自这批）
            "ssp.360.cn", "qhimg.com", "360kuai.com", "pos.baidu.com", "cpro.baidu.com",
            "hm.baidu.com", "union.baidu.com", "cnzz.com", "51.la", "umeng.com",
            "miaozhen", "ipinyou", "mediav", "admaster", "allyes", "tanx.com", "alimama",
            "sensorsdata", "growingio", "appsflyer", "jiathis", "bshare"
    };

    /** r472：灰产专用 TLD（推广友链域；host 末段匹配）。与 FILTER_JS 的 ADH 保持一致。 */
    private static final String[] AD_TLDS = {
            ".hair", ".beer", ".mom", ".autos", ".monster", ".homes", ".pics", ".best",
            ".yachts", ".icu", ".rest", ".cyou", ".sbs", ".cam", ".xxx", ".porn", ".adult", ".sexy",
            ".boats", ".lat"
    };

    /**
     * r472 增强版广告清理 JS（沙箱真实 DOM 三重验证：广告壳页全清 / 混合页保内容删广告 / 基线站零误删）：
     * A 域名表+怪TLD+路径特征节点（img 只认域名，防误杀正常轮播图）→ 上溯删无本域链接容器，否则删个体；
     * B 纯外域聚集区（100%）直接删；混排区（含本域）需高度<60% 视口（导航站主体保护）；
     * D 友链容器（hulian/friendlink/flink/partner/hezuo/youlian）只删容器内外域个体，本域保留；r485 加 hezuo/youlian（合作/友链拼音类名）；
     * H 无图全外链浮层（>=2 锚点全外域+0 图+去空白文本<=60；品牌词豁免镜像导航）r485；
     * C 推广话术条（永久发布页/防走丢）+ onclick 内联跳外域；
     * 统计经 console __TXAD__ 回传 Java 端记日志（值变化才记，2 秒节流）。
     */
    private static final String FILTER_JS =
                    "(function(){try{\n"
                    // Muse v58：Cloudflare/人机验证整页保护，清理脚本不得破坏挑战 iframe 与页面节点。
                    + "  function isChallenge(){try{var t=(document.title||'').toLowerCase();if(t.indexOf('请稍候')>=0||t.indexOf('just a moment')>=0)return true;var h=(document.documentElement.outerHTML||'').slice(0,200000).toLowerCase();return h.indexOf('challenges.cloudflare.com')>=0||h.indexOf('cf-challenge')>=0}catch(e){return false}}\n"
                    + "  if(isChallenge())return;\n"
                    // 新窗口统一交给原生 onCreateWindow 判定；不再由 JS 把 window.open 强改为主框导航。
                    + "  window.alert=function(){};window.confirm=function(){return false};window.prompt=function(){return null};\n"
                    + "  var ADH=/(doubleclick|googlesyndication|googleadservices|popads|popcash|propeller|onclickad|exoclick|juicyads|mgid|taboola|outbrain|adnxs|criteo|hilltopads|clickadu|adcash|plugrush|zeropark|coinzilla|a-ads|adskeeper|adsterra|trafficjunky|adsystem|adservice|ssp\\.360\\.cn|qhimg\\.com|pos\\.baidu\\.|cpro\\.baidu\\.|hm\\.baidu\\.|union\\.baidu\\.|cnzz\\.com|51\\.la|umeng\\.com|miaozhen|ipinyou|mediav|admaster|allyes|tanx\\.com|alimama|sensorsdata|growingio|appsflyer|jiathis|bshare|\\.(hair|beer|mom|autos|monster|homes|pics|best|yachts|icu|rest|cyou|sbs|cam|xxx|porn|adult|sexy|boats|lat)(\\/|$|:|\\?))/i;\n"
                    + "  var ADP=/(\\/ad\\/|\\/ads\\/|\\/advert|\\/gg\\/|guanggao|\\/ad\\.js|\\/ads\\.js|banner\\d*\\.js|banner\\d*\\.gif)/i;\n"
                    + "  var PROMO=/(永久(发布|网址|域名|地址)|防走丢|备用(网址|域名|地址)|永远可以找到本站)/;\n"
                    + "  var GAMBLE=/(澳门威尼斯人|威尼斯人|开元棋牌|太阳城|银河娱乐|新葡京|棋牌|电子游艺|街机|捕鱼|百家乐|牛牛|龙虎|时时彩|六合彩|彩票|投注|注册送|首存|日派送|大额无忧|彩金|红包|真人荷官|赌场|反水|爆大奖|电子游艺)/;\n"
                    + "  var ADTXT=/(免费看片|点击下载|下载APP|下载app|老司机必备|成人影视|福利爽片|看片神器|深夜福利|免费观看|顶级成人|强烈推荐|官方推荐|永久地址|获取最新网址)/;\n"
                    + "  var LINKBOX=/(hulian|friendlink|flink|partner|linkbox|hezuo|youlian)/i;\n"
                    + "  var PAGE_HOST=(window.__TX_PAGE_HOST__||location.hostname||'').toLowerCase();\n"
                    + "  var stat={adNode:0,adImg:0,adArea:0,promoBar:0,onclickJump:0,linkBox:0,adFloat:0};\n"
                    + "  var lastPush=0;\n"
                    + "  function sameHost(u){\n"
                    + "    try{\n"
                    + "      if(!u)return true;\n"
                    + "      var a=document.createElement('a');a.href=u;\n"
                    + "      var h=(a.hostname||'').toLowerCase();\n"
                    + "      if(!h)return true;\n"
                    + "      if(h===PAGE_HOST)return true;\n"
                    + "      var p1=PAGE_HOST.split('.').slice(-2).join('.'),p2=h.split('.').slice(-2).join('.');\n"
                    + "      return p1===p2;\n"
                    + "    }catch(e){return true}\n"
                    + "  }\n"
                    + "  function sameTld(u){\n"
                    + "    try{\n"
                    + "      if(!u)return true;\n"
                    + "      var a=document.createElement('a');a.href=u;\n"
                    + "      var h=(a.hostname||'').toLowerCase();\n"
                    + "      if(!h)return true;\n"
                    + "      var t1=PAGE_HOST.split('.').pop(),t2=h.split('.').pop();\n"
                    + "      return t1===t2;\n"
                    + "    }catch(e){return true}\n"
                    + "  }\n"
                    + "  function kill(el){if(el&&el.parentNode){try{el.parentNode.removeChild(el);return true}catch(e){}}return false}\n"
                    + "  function alive(el){return el&&document.documentElement&&document.documentElement.contains(el)}\n"
                    + "  function hasMedia(el){try{return !!(el.querySelector&&el.querySelector('video,iframe,embed,object,audio'))}catch(e){return false}}\n"
                    + "  function push(){\n"
                    + "    try{\n"
                    + "      var now=Date.now();\n"
                    + "      if(now-lastPush<2000)return;\n"
                    + "      lastPush=now;\n"
                    + "      console.log('__TXAD__'+JSON.stringify(stat));\n"
                    + "    }catch(e){}\n"
                    + "  }\n"
                    + "  function clean(root){try{if(isChallenge())return;\n"
                    + "    var i,el,t,p;\n"
                    + "    var nodes=root.querySelectorAll?root.querySelectorAll('iframe,a,img'):[];\n"
                    + "    for(i=0;i<nodes.length;i++){\n"
                    + "      el=nodes[i];if(!alive(el))continue;\n"
                    + "      t=(el.getAttribute('src')||el.getAttribute('href')||'');\n"
                    + "      if(!t)continue;\n"
                    + "      var hit=ADH.test(t)||(el.tagName!=='IMG'&&ADP.test(t));\n"
                    + "      if(hit&&sameHost(t))hit=false;\n"
                    + "      if(!hit&&t.toLowerCase().indexOf('channelcode=')>=0&&!sameHost(t))hit=true;\n"
                    + "      if(hit&&el.tagName==='IMG'&&sameTld(t))hit=false;\n"
                    + "      if(!hit)continue;\n"
                    + "      p=el.parentElement;var hop=0,dead=el;\n"
                    + "      while(p&&p!==document.body&&hop<3){\n"
                    + "        if(p.tagName==='DIV'){\n"
                    + "          var links=p.querySelectorAll('a[href]');\n"
                    + "          var own=false,j;\n"
                    + "          for(j=0;j<links.length;j++){if(sameHost(links[j].getAttribute('href'))){own=true;break}}\n"
                    + "          if(own)break;\n"
                    + "          dead=p;break;\n"
                    + "        }\n"
                    + "        p=p.parentElement;hop++;\n"
                    + "      }\n"
                    + "      if(hasMedia(dead))dead=el;\n"
                    + "      if(kill(dead)){if(el.tagName==='IMG')stat.adImg++;else stat.adNode++}\n"
                    + "    }\n"
                    + "    var areas=root.querySelectorAll?root.querySelectorAll('div,ul,aside,section,footer'):[];\n"
                    + "    for(i=0;i<areas.length;i++){\n"
                    + "      var d=areas[i];if(!alive(d))continue;\n"
                    + "      var as=[].slice.call(d.querySelectorAll('a[href]'));\n"
                    + "      if(as.length<3)continue;\n"
                    + "      var ext=[],ownCnt=0,k;\n"
                    + "      for(k=0;k<as.length;k++){if(sameHost(as[k].getAttribute('href')))ownCnt++;else ext.push(as[k])}\n"
                    + "      if(ext.length<3||ext.length/as.length<0.8)continue;\n"
                    + "      var hosts={};\n"
                    + "      for(k=0;k<ext.length;k++){try{var x=document.createElement('a');x.href=ext[k].getAttribute('href');var hn=(x.hostname||'').toLowerCase();if(hn)hosts[hn]=1}catch(e){}}\n"
                    + "      var cnt=0;for(var key in hosts)cnt++;\n"
                    + "      if(cnt<3)continue;\n"
                    + "      if(ownCnt>0){\n"
                    // r480：去掉 offsetHeight（循环内每次都强制整页重排 → 真机 WebView 主线程被拖死）
                    //   改用「链接数/文本量」判断是否大容器（导航区保护语义不变）
                    + "        var tlen=(d.textContent||'').replace(/\\s/g,'').length;\n"
                    + "        if(as.length>20||tlen>600)continue;\n"
                    + "      }\n"
                    + "      if(!hasMedia(d)&&kill(d))stat.adArea++;\n"
                    + "    }\n"
                    + "    if(root.querySelectorAll){\n"
                    + "      var boxes=root.querySelectorAll('[class*=\"hulian\"],[class*=\"friendlink\"],[class*=\"flink\"],[class*=\"partner\"],[class*=\"linkbox\"],[class*=\"hezuo\"],[class*=\"youlian\"]');\n"
                    + "      for(i=0;i<boxes.length;i++){\n"
                    + "        var bx=boxes[i];if(!alive(bx))continue;\n"
                    + "        var blinks=[].slice.call(bx.querySelectorAll('a[href]'));\n"
                    + "        for(var b=0;b<blinks.length;b++){\n"
                    + "          var ba=blinks[b];if(!alive(ba))continue;\n"
                    + "          if(!sameHost(ba.getAttribute('href'))){if(kill(ba))stat.linkBox++}\n"
                    + "        }\n"
                    + "      }\n"
                    + "    }\n"
                    + "    if(root.querySelectorAll){\n"
                    + "      var divs=root.querySelectorAll('div,section');\n"
                    + "      for(i=0;i<divs.length;i++){\n"
                    + "        var dv=divs[i];if(!alive(dv))continue;\n"
                    + "        var txt=(dv.textContent||'').slice(0,200);\n"
                    + "        if(PROMO.test(txt)){\n"
                    // r480：去掉 offsetHeight（强制重排）→ 改用文本长度 + 链接数
                    + "          if(dv.querySelector('a[href^=\"/\"]'))continue;\n"
                    + "        }\n"
                    + "      }\n"
                    + "      var ons=root.querySelectorAll('[onclick]');\n"
                    + "      for(i=0;i<ons.length;i++){\n"
                    + "        el=ons[i];if(!alive(el))continue;\n"
                    + "        var oc=el.getAttribute('onclick')||'';\n"
                    + "        if(!oc)continue;\n"
                    + "        var m=oc.match(/https?:\\/\\/[^'\"\\s]+/);\n"
                    + "        if(m&&!sameHost(m[0])){if(hasMedia(el)){try{el.removeAttribute('onclick')}catch(e){}}else if(kill(el))stat.onclickJump++;continue}\n"
                    + "        var t2=(el.textContent||'').slice(0,120);\n"
                    + "        if(PROMO.test(t2)&&kill(el))stat.promoBar++;\n"
                    + "      }\n"
                    + "    }\n"
                    // r476-D：图片广告位容器 —— 容器内 a 全为外域 + 有 img + 文本极少 → 广告位。
                    //   真机实证 dypj8：/abc/fixed_ui_*.js 注入的赌博 banner 每块仅 1-2 个 <a><img></a>，
                    //   图挂 img.alicdn.com 等正常 CDN，URL 域名表零命中；内容卡片必有本域链接 → 此判据安全。
                    + "    if(root.querySelectorAll){\n"
                    + "      var boxes2=root.querySelectorAll('div,section,aside,header,footer');\n"
                    + "      for(i=0;i<boxes2.length;i++){\n"
                    + "        var bx2=boxes2[i];if(!alive(bx2))continue;\n"
                    + "        if(bx2.querySelectorAll('img').length<1)continue;\n"
                    + "        var a2=[].slice.call(bx2.querySelectorAll('a[href]'));\n"
                    + "        if(a2.length<1)continue;\n"
                    + "        var extAll=true;\n"
                    + "        for(j=0;j<a2.length;j++){\n"
                    + "          var hh=a2[j].getAttribute('href')||'';\n"
                    + "          if(!hh||hh.charAt(0)==='#')continue;\n"
                    + "          if(sameHost(hh)){extAll=false;break}\n"
                    + "        }\n"
                    + "        if(!extAll)continue;\n"
                    + "        var tx2=(bx2.textContent||'').replace(/\\s/g,'').length;\n"
                    + "        if(tx2>40)continue;\n"
                    + "        if(!hasMedia(bx2)&&kill(bx2))stat.adArea++;\n"
                    + "      }\n"
                    + "    }\n"
                    // r476-E：灰产话术块（图1 底部「福利爽片/顶级成人影视/老司机必备/点击下载APP」类）
                    + "    if(root.querySelectorAll){\n"
                    + "      var boxes3=root.querySelectorAll('div,section,aside,footer,a');\n"
                    + "      for(i=0;i<boxes3.length;i++){\n"
                    + "        var bx3=boxes3[i];if(!alive(bx3))continue;\n"
                    + "        var tx3=(bx3.textContent||'').slice(0,200);\n"
                    + "        if(!tx3||tx3.length<6)continue;\n"
                    + "        var g=(tx3.match(GAMBLE)||[]).length;\n"
                    + "        if(g<1)continue;\n"
                    + "        if(g<2&&!ADTXT.test(tx3))continue;\n"
                    // r480：去掉 offsetHeight（强制重排）→ 改用链接/图片数量
                    + "        if(bx3.tagName==='A'){if(kill(bx3))stat.promoBar++;continue}if(bx3.querySelectorAll('a,img').length<=8&&!hasMedia(bx3)){if(kill(bx3))stat.promoBar++;continue}\n"
                    + "      }\n"
                    + "    }\n"
                    // r478-F：挂马广告位指纹（真机 dypj8 实证，判据 D 对它必然失效的原因）——
                    //   /abc/fixed_ui_<hash>.js 的 initLayout('b_<hash>') 按 class 命中页面里
                    //   已有的占位容器，注入后自打 data-fill-status='ok' 指纹；且 a.href 指向
                    //   **本站域的中转跳转** /000/flink/url.php?id=… （不是外域落地页）→
                    //   任何「按外域链接判广告」的判据都会放行它。三条形态判据并行：
                    //   ① data-fill-status 指纹属性（广告框架自打标记）
                    //   ② a 的 href 命中推广跳转中转路径（flink / url.php?id= / go.php / jump / out.php）
                    //   ③ a 内 img 为超宽扇条 banner（宽/高 > 4；广告位 padding-bottom 6.25%/12.5%/25%）
                    + "    if(root.querySelectorAll){\n"
                    + "      var fp=root.querySelectorAll('[data-fill-status]');\n"
                    + "      for(i=0;i<fp.length;i++){if(alive(fp[i])&&!hasMedia(fp[i])&&kill(fp[i]))stat.adArea++}\n"
                    + "      var FLK=/(fixed_ui|\\/flink\\/|flink\\/url|url\\.php\\?id=|\\/go\\.php|\\/jump\\.php|\\/out\\.php|\\/link\\.php|\\/redirect\\?)/i;\n"
                    + "      var as2=root.querySelectorAll('a[href]');\n"
                    + "      for(i=0;i<as2.length;i++){\n"
                    + "        var a2e=as2[i];if(!alive(a2e))continue;\n"
                    + "        var im2=a2e.querySelector('img');\n"
                    + "        if(!im2)continue;\n"
                    + "        var hr=a2e.getAttribute('href')||'';\n"
                    + "        var bad=!hr?false:FLK.test(hr);\n"
                    + "        if(!bad){try{var WW=im2.naturalWidth||0,HH=im2.naturalHeight||0;if(WW>0&&HH>0&&WW/HH>4)bad=true}catch(e){}}\n"
                    + "        if(!bad)continue;\n"
                    + "        var tx4=(a2e.textContent||'').replace(/\\s/g,'');\n"
                    + "        if(tx4.length>20)continue;\n"
                    + "        var anc=a2e.parentElement,dep=0,blk=a2e;\n"
                    + "        while(anc&&anc!==document.body&&dep<3){\n"
                    + "          var tn=anc.tagName;\n"
                    + "          if(tn==='DIV'||tn==='LI'||tn==='SECTION'||tn==='UL'||tn==='P'||tn==='SPAN'){blk=anc;break}\n"
                    + "          anc=anc.parentElement;dep++;\n"
                    + "        }\n"
                    + "        if(hasMedia(blk))blk=a2e;if(kill(blk))stat.adImg++;\n"
                    + "      }\n"
                    + "    }\n"
                    // r479-G：隐形覆盖层（触摸陷阱）—— 真机 fljtu20 实证：底部「横幅广告」实为 40 个
                    //   position:fixed + opacity:0.01 + 无任何内容(无 a/img/文本) 的 div，touchend 即跳外站
                    //   (https://<rand>1196c.hcxftcl.com:902/cc/1196?...)。正常站不会用「全透明且无内容的固定层」。
                    + "    if(root.querySelectorAll){\n"
                    // r480：改成「CSS 属性选择器原生锚定」——r479 版对每个 div/span 调
                    //   getComputedStyle + getBoundingClientRect（真机页面 186~351 个候选元素，
                    //   getBoundingClientRect 每次都强制整页重排）→ 移动端 WebView 主线程被拖死，
                    //   clean() 走不到末尾的 push()，表现为「一条统计都没有、广告一条没删」。
                    //   属性选择器由浏览器内部索引，零 JS 遍历；判断全用字符串，不触发重排。
                    + "      var ovs=root.querySelectorAll('[style*=\"opacity:0.0\"],[style*=\"opacity:0.01\"],[style*=\"opacity: .0\"],[style*=\"opacity:.0\"]');\n"
                    + "      for(i=0;i<ovs.length;i++){\n"
                    + "        var ov=ovs[i];if(!alive(ov))continue;\n"
                    + "        var ost=String(ov.getAttribute('style')||'');\n"
                    + "        if(!/position\\s*:\\s*fixed/i.test(ost))continue;\n"
                    + "        if(ov.querySelector('img,a,iframe,input,button,svg,video'))continue;\n"
                    + "        if((ov.textContent||'').replace(/\\s/g,'').length>0)continue;\n"
                    + "        if(kill(ov))stat.adNode++;\n"
                    + "      }\n"
                    + "    }\n"
                    // r485-H：无图全外链浮层（真机 fljtu20 实证：div.float-wrap 内 3 个 a.float-item
                    //   悬浮广告钮——class 挂在 <a> 上而非 div、无 img、文本极短（「GO」「成人短剧」）、
                    //   href 全外域灰产域 qmdj111.rhkvzmbp.cc / 365.sng.link / 1ycfbpvp.4rapjjxy.cc）
                    //   ——判据 D 因要求 img>=1 必然放行 → 用户误点源。形态：>=2 条 http(s) 锚点全外域 + 0 图 +
                    //   去空白文本<=60 字。品牌词豁免：主域 >=4 字母词出现在任一锚点 hostname
                    //   （fljtu20 → fljtu 保护 fljtu.xyz/fljtu19.sbs/fljtu18.xyz 镜像导航与 .tips 防走失区）。
                    //   联系块豁免（真机 live DOM 实证）：含 mailto:/tel: 锚点的容器是站方联系方式
                    //   （fljtu20 .text-muted：mailto+qyvipwl@gmail.com + t.me，python 模拟误判可杀、
                    //   真实 DOM mailto hostname 为空必不中 sameHost）→ 直接跳过；且只统计 http(s)
                    //   锚点，mailto/tel/#/javascript: 一律不算外链证据。
                    //   r480 性能约束：只用 textContent + querySelectorAll，零 getComputedStyle/offsetHeight。
                    + "    if(root.querySelectorAll){\n"
                    + "      var fts=(PAGE_HOST.match(/[a-z]{4,}/g)||[]);\n"
                    + "      var flts=root.querySelectorAll('div,section,aside');\n"
                    + "      for(i=0;i<flts.length;i++){\n"
                    + "        var bh=flts[i];if(!alive(bh))continue;\n"
                    + "        if(bh.querySelector('a[href^=\"mailto:\"],a[href^=\"tel:\"]'))continue;\n"
                    + "        if(bh.querySelector('a[href^=\"/\"],a[href^=\"./\"],a[href^=\"../\"]'))continue;\n"
                    + "        var ha0=[].slice.call(bh.querySelectorAll('a[href]'));\n"
                    + "        var ha=[];\n"
                    + "        for(var hi=0;hi<ha0.length;hi++){var h0=ha0[hi].getAttribute('href')||'';if(/^https?:/i.test(h0))ha.push(ha0[hi])}\n"
                    + "        if(ha.length<2)continue;\n"
                    + "        if(bh.querySelectorAll('img').length>0)continue;\n"
                    + "        if((bh.textContent||'').replace(/\\s/g,'').length>60)continue;\n"
                    + "        var extA=true,brd=false;\n"
                    + "        for(j=0;j<ha.length;j++){\n"
                    + "          var hh2=ha[j].getAttribute('href')||'';\n"
                    + "          if(!hh2||hh2.charAt(0)==='#')continue;\n"
                    + "          if(sameHost(hh2)){extA=false;break}\n"
                    + "          try{var ax=document.createElement('a');ax.href=hh2;var hn2=(ax.hostname||'').toLowerCase();for(var ti=0;ti<fts.length;ti++){if(hn2.indexOf(fts[ti])>=0){brd=true;break}}}catch(e){}\n"
                    + "          if(brd)break;\n"
                    + "        }\n"
                    + "        if(!extA||brd)continue;\n"
                    + "        if(!hasMedia(bh)&&kill(bh))stat.adFloat++;\n"
                    + "      }\n"
                    + "    }\n"
                    + "  }catch(e){try{stat.err=(stat.err||0)+1;window.__TXADERR__=String((e&&e.message)||'')+'@@'+String((e&&e.stack)||'').split('\\n').slice(0,3).join(' > ');}catch(_){}}}\n"
                    + "  clean(document);\n"
                    // r476：observer 健壮化 —— onPageStarted 提前注入时 documentElement 可能尚未就绪，
                    //   旧写法无效时仍置 __hhAd=1 → 后续注入不再建 observer（整页无动态清理），
                    //   且 observe(null) 抛异常被 catch 吞掉。改为建成功才置位、失败留待下次注入重试。
                    + "  if(!window.__hhAd){\n"
                    + "    try{\n"
                    + "      var rt=document.documentElement||document;\n"
                    // r487：observer 补 attributes 监听 —— 真机实证（r486logs fljtu20 14 页）：广告 SDK
                    //   先插入无 src 的占位节点、再延迟设 src/href（懒加载形态），childList 只监听节点增删
                    //   不监听属性变更 → 注入瞬间不触发 clean，只能等 3s 定时兜底（用户有可见窗）。
                    //   attributeFilter 限定 src/href 两类（轮播图等其它属性变更不触发），开销可控。
                    + "      var __cleanPending=0;\n"
                    + "      new MutationObserver(function(){if(__cleanPending)return;__cleanPending=setTimeout(function(){__cleanPending=0;clean(document)},120)}).observe(rt,{childList:true,subtree:true,attributes:true,attributeFilter:['src','href']});\n"
                    + "      window.__hhAd=1;\n"
                    + "      setInterval(function(){clean(document)},3000);\n"
                    // r480：统计**独立推送** —— 原来 push() 在 clean() 末尾，clean 一旦抛错/超时
                    //   统计就永不回传（真机实证：fljtu20/xiezhen8 一条 JS清理统计都没有）。
                    //   独立定时推送后，即使 clean 异常也能从 stat.err 看到错误定位。
                    + "      setInterval(push,2000);\n"
                    + "    }catch(e){}\n"
                    + "  }\n"
                    + "}catch(e){}})()\n";

    /** 3.56：muse-build CLEAN_JS 页面清理（与 AdDomains.java 同源域名表 + 结构清横幅 + 前贴片倒计时，
     *  自带 __museWebClean 一次守卫与 MutationObserver 持续清扫；__museJumpDone 不设，v77 外链清理由 isRealSite() 自判。 */
    private static final String[] CLEAN_JS_PARTS = {
        "(function(){if(window.__museWebClean)return;try{var __museCT=(document.title||'').toLowerCase();if(__museCT.in",
        "dexOf('请稍候')>=0||__museCT.indexOf('just a moment')>=0)return;var __museCH=document.documentElement.outerHTML.s",
        "lice(0,200000).toLowerCase();if(__museCH.indexOf('challenges.cloudflare.com')>=0||__museCH.indexOf('cf-challen",
        "ge')>=0)return;}catch(e){}window.__museWebClean=1;try{var __museHost=(location.hostname||'').toLowerCase();if(",
        "__museHost.indexOf('qiyou')>=0){if(window.WebSocket){var __museWS=window.WebSocket;window.WebSocket=function(u",
        ",p){var s=String(u==null?'':u);var xh='';try{xh=new URL(s,location.href).hostname;}catch(e){}if(xh&&xh.toLower",
        "Case()!==__museHost){window.__museAdWs=(window.__museAdWs||0)+1;var d=null;try{d=p===undefined?new __museWS('w",
        "ss://127.0.0.1:9/'):new __museWS('wss://127.0.0.1:9/',p);}catch(e){d=null;}if(d){try{d.close();}catch(e){}}if(",
        "!d){d={readyState:3,close:function(){},send:function(){throw new Error('muse: ws blocked');},addEventListener:",
        "function(){},removeEventListener:function(){},dispatchEvent:function(){return false;}};}return d;}return p===u",
        "ndefined?new __museWS(s):new __museWS(s,p);};window.WebSocket.prototype=__museWS.prototype;window.WebSocket.CO",
        "NNECTING=__museWS.CONNECTING;window.WebSocket.OPEN=__museWS.OPEN;window.WebSocket.CLOSING=__museWS.CLOSING;win",
        "dow.WebSocket.CLOSED=__museWS.CLOSED;}if(window.history&&window.history.pushState){var __musePS=window.history",
        ".pushState.bind(window.history);window.history.pushState=function(s,t,u){if(arguments.length>=3){var uu=u===un",
        "defined?'':String(u);if(uu===''||uu==='#'){window.__museAdBack=(window.__museAdBack||0)+1;return;}}if(argument",
        "s.length>=3)return __musePS(s,t,u);return __musePS(s,t);};}}}catch(e){}try{var __museSt=document.createElement",
        "('style');__museSt.textContent='.ad-item,.ec-ad,.ads_w,.ads_close_btn,.adspop,.gkktXd,.ec-ad-fixed,.ads_w.slid",
        "e-wap,.ec-ad.ads_w.slide-wap,#AdBottomLeader,#AdBottomRight,#AdPopUp,#AdRectangleBanner,#AdSlot_megabanner,#Ad",
        "banner,#AdsBannerTop,#AdsBottomContainer,#Ads_google_bottom_wide,#AdsenseBottom,#Banner728x90,#ad--article-bot",
        "tom-wrapper,#ad-Superbanner,#ad-banner-1,#ad-banner-atf,#ad-banner-bottom,#ad-banner-btf,#ad-banner-desktop,#a",
        "d-banner-image,#ad-banner-placement,#ad-banner-top,#ad-banner-wrap,#ad-banner_atf-label,#ad-billboard-bottom,#",
        "ad-block-bottom,#ad-bottom,#ad-bottom-banner,#ad-bottom-fixed,#ad-bottom-right-container,#ad-bottom-wrapper,#a",
        "d-box-bottom,#ad-container-banner,#ad-container-overlay,#ad-desktop-bottom,#ad-fixed-bottom,#ad-fullbanner-btf",
        ",#ad-fullbanner-outer,#ad-large-banner-top,#ad-leaderboard-bottom,#ad-leaderboard_bottom,#ad-main-bottom,#ad-o",
        "verlay,#ad-performanceFullbanner1,#ad-popup,#ad-popup-home,#ad-popup-int,#ad-small-banner,#ad-spot-bottom,#ad-",
        "sticky-footer-container,#ad-top-banner,#ad-unit-right-bottom-160-600,#ad-unit-top-banner,#adBanner1,#adBanner3",
        "36x280,#adBannerBottom,#adBannerHeader,#adBannerSpacer,#adBannerTable,#adBannerTop,#adBlockBanner,#adBlockOver",
        "lay,#adBottom,#adModal,#adMpuBottom,#adPopup,#adSlot-leaderBottom,#adSpaceBottom,#adSuperbanner,#ad_Banner,#ad",
        "_Bottom,#ad_banner,#ad_banner_1,#ad_banner_468x60,#ad_banner_728x90,#ad_banner_bot,#ad_banner_top,#ad_banners,",
        "#ad_bottom,#ad_cont_superbanner,#ad_div_bottom,#ad_from_bottom,#ad_fullbanner,#ad_middle_bottom,#ad_overlay,#a",
        "d_overlayer,#ad_popup_background,#ad_popup_wrapper,#ad_rect_bottom,#ad_slot_bottom,#ad_sticky_wrap,#ad_superba",
        "nner,#ad_topBanner,#ad_top_banner,#ad_topbanner,#adb_bottom,#adbanner-container,#adbanner1,#adbannerbox,#adban",
        "nerdiv,#adbannerleft,#adbannerright,#adbannerwidget,#adblockbottom,#adbottom,#adbottomleft,#adbottomright,#adb",
        "ox--page_bottom_ad,#adbox-topbanner,#adfloat,#admpubottom,#admpubottom2,#adpositionbottom,#adrightbottom,#ads-",
        "banner,#ads-banner-top,#ads-bottom,#ads-right-bottom,#ads-superBanner,#ads728bottom,#adsBottom,#adsMobileFixed",
        ",#adsTopMobileFixed,#ads_banner,#ads_banner1,#ads_banner_header,#ads_bottom,#ads_box_bottom,#ads_center_banner",
        ",#ads_in_modal,#ads_layout_bottom,#ads_leaderbottom,#ads_overlay,#ads_popup,#ads_superbanner1,#ads_superbanner",
        "2,#ads_top_banner,#ads_topbanner,#adsbottom,#adsense-bottom,#adsense-container-bottom,#adsense_banner_top,#ads",
        "ense_bottom_ad,#adsense_overlay,#adsense_r_side_sticky_container,#adslot-sticky-headerbar,#adspace-bottom,#ads",
        "pot-bottom,#adunit-article-bottom,#adv-Bottom,#adv-banner,#adv-banner-r,#adv-sticky-1,#adv-sticky-2,#advBoxBot",
        "tom,#adv_BoxBottom,#adv_overlay,#adv_overlay_content,#advert-banner,#advert-banner-container,#advert-banner-wr",
        "ap,#advert-banner2,#advert-top-banner,#advertBanner,#advertOverlay,#advert_banner,#advertiseBanner,#advertisem",
        "ent-bottom,#advertisement_banner,#advertisements_bottom,#advertising-banner,#advertising__banner__content,#adv",
        "t_bottom,#adzbanner,#adzonebanner,#after_bottom_ad,#article-bottom-ad,#articleBottom-ads,#article_ad_bottom,#a",
        "rticle_banner_ad,#banner-300x250,#banner-468x60,#banner-728,#banner-728x90,#banner-ad,#banner-ad-container,#ba",
        "nner-ad-large,#banner-ads,#banner-advert,#banner-lg-ad,#banner-native-ad,#banner-skyscraper,#banner300x250,#ba",
        "nner468,#banner468x60,#banner728,#banner728x90,#banner_ad_bottom,#banner_ad_footer,#banner_ad_module,#banner_a",
        "d_placeholder,#banner_ad_top,#banner_ads,#banner_adsense,#banner_adv,#banner_advertisement,#banner_adverts,#ba",
        "nner_content_ad,#banner_sedo,#banner_slot,#banner_spacer,#banner_topad,#banner_videoad,#banner_wrapper_top,#bl",
        "ock-adsense-banner-article-bottom,#block-adsense-banner-channel-bottom,#bottom-ad,#bottom-ad-1,#bottom-ad-area",
        ",#bottom-ad-banner,#bottom-ad-container,#bottom-ad-leaderboard,#bottom-ad-slot,#bottom-ad-tray,#bottom-ad-wrap",
        "per,#bottom-ads,#bottom-ads-bar,#bottom-ads-container,#bottom-not-ads,#bottom-side-ad,#bottom-sponsor-add,#bot",
        "tom_ad,#bottom_ad_728,#bottom_ad_area,#bottom_ad_box,#bottom_ad_region,#bottom_ad_unit,#bottom_ad_wrapper,#bot",
        "tom_ads,#bottom_banner_ad,#bottom_fixed_ad_overlay,#bottom_leader_ad,#bottom_sponsor_ads,#bottom_sponsored_lin",
        "ks,#bottom_text_ad,#content_bottom_ad,#content_bottom_ads,#desktop_middle_ad_fixed,#desktop_top_ad_fixed,#dfp-",
        "ad-bottom-wrapper,#dfp-ad-floating,#dfp-banner,#dfp-banner-popup,#dfp-top-banner,#div-ad-bottom,#div-ad-top_ba",
        "nner,#div_ad_float,#dnn_ad_banner,#dsk-banner-ad-a,#dsk-banner-ad-b,#dsk-banner-ad-c,#dsk-banner-ad-d,#em_ad_s",
        "uperbanner,#fixed-ad,#floating-ad-wrapper,#floating-ads,#floating-advert,#floating_ad,#floating_ad_container,#",
        "floating_ads_bottom_textcss_container,#footer-banner-ad,#full_banner_ad,#global-banner-ad,#google-ads-bottom,#",
        "google-ads-bottom-container,#gpt-ad-banner,#gpt-ad-side-bottom,#header-ad-bottom,#header-banner-728-90,#header",
        "-banner-ad,#header-banner-ad-wrapper,#header_ad_banner,#header_bottom_ad,#home-banner-ad,#home_bottom_ad,#hori",
        "zontal-banner-ad,#index-bottom-advert,#large-bottom-leaderboard-ad,#leaderboard-bottom-ad,#leaderboard_bottom_",
        "ad,#left-bottom-ad,#leftBanner-ad,#left_float_ad,#lg-banner-ad,#long-bottom-ad-wrapper,#lower-ad-banner,#m-ban",
        "ner-bannerAd,#medrec_bottom_ad,#medrectangle_banner,#modal-ad,#mpu_banner,#overlay-ad-bg,#overlay_ad,#popup_ad",
        "_wrapper,#post-bottom-ads,#rail-bottom-ad,#redirect-ad-modal,#right_bottom_ad,#section-ad-bottom,#show-sticky-",
        "ad,#side-banner-ad,#site_bottom_ad_div,#sm-banner-ad,#sp-adv-banner-top,#sponsor_bottom,#sponsored_link_bottom",
        ",#sticky-ad,#sticky-ad-bottom,#sticky-ad-container,#sticky-ad-header,#sticky-ads,#sticky-ads-top,#sticky-custo",
        "m-ads,#sticky-footer-ad,#sticky-footer-ads,#sticky-left-ad,#sticky-rail-ad,#sticky_sidebar_ads,#story-bottom-a",
        "d,#top-ad-banner,#top-banner-ad,#top-banner-ad-browser,#topBanner-ad,#top_ad_banner,#top_banner_ads,#topbanner",
        "_ad,#topbanner_sponsor,#video_overlay_ad,#zeus_top-banner,.AdBottomPage,.Ads-sticky,.AdsBottom,.AdsBoxBottom,.",
        "AdzerkBanner,.Banner300x250,.Banner468X60,._bottom_ad_wrapper,.ac-banner-ad,.ad--banner,.ad--banner2,.ad--bott",
        "om,.ad--bottom-label,.ad--bottommpu,.ad--inArticleBanner,.ad--overlayer,.ad--sticky,.ad-728-banner,.ad-SuperBa",
        "nner,.ad-adlink-bottom,.ad-banner-2,.ad-banner-250x600,.ad-banner-300,.ad-banner-300x250,.ad-banner-5,.ad-bann",
        "er-6,.ad-banner-728x90,.ad-banner-bottom-container,.ad-banner-box,.ad-banner-btf,.ad-banner-container,.ad-bann",
        "er-content,.ad-banner-full-wrapper,.ad-banner-header,.ad-banner-image,.ad-banner-inlisting,.ad-banner-leaderbo",
        "ard,.ad-banner-placeholder,.ad-banner-single,.ad-banner-smaller,.ad-banner-static,.ad-banner-top,.ad-banner-to",
        "p-wrapper,.ad-banner-wrapper,.ad-banners,.ad-bigbanner,.ad-bigbox-fixed,.ad-block-banner-container,.ad-block-b",
        "ottom,.ad-block-sticky-ad,.ad-bottom,.ad-bottom-container,.ad-bottom-right-container,.ad-bottom728x90,.ad-bott",
        "omLeft,.ad-bottomleader,.ad-bottomline,.ad-boxbottom,.ad-boxsticky,.ad-component-fullbanner2,.ad-container-ban",
        "ner-top,.ad-container-bottom,.ad-container__sticky-wrapper,.ad-fixed,.ad-float,.ad-floating,.ad-fullbanner,.ad",
        "-fullbanner-btf-container,.ad-fullbannernohieght,.ad-home-bottom,.ad-leader-bottom,.ad-m-banner,.ad-mobile--st",
        "icky,.ad-mobile-banner,.ad-mpu-bottom,.ad-mpufixed,.ad-overlay,.ad-popup,.ad-popup-content,.ad-rectangle-banne",
        "r,.ad-relatedbottom,.ad-single-bottom,.ad-slot--mpu-banner-ad,.ad-slot--top-banner-ad,.ad-slot-banner,.ad-spac",
        "e-topbanner,.ad-sticky,.ad-sticky-banner,.ad-sticky-bottom,.ad-sticky-container,.ad-sticky-slot,.ad-sticky-wra",
        "pper,.ad-stickyhero,.ad-stickyhero--standard,.ad-stickyhero-enable-mobile,.ad-superbanner,.ad-superbanner-node",
        ",.ad-top-banner,.ad-topbanner,.ad-wide-bottom,.ad-wrapper-sticky,.ad1-float,.ad1_bottom,.ad2-float,.ad300banne",
        "r,.adArticleBanner,.adBanner300x250,.adBanner728x90,.adBlockBottom,.adFullbanner,.adHeaderAdbanner,.adInnerLef",
        "tBottom,.adMrginBottom,.adOverlay,.adRectangleBanner,.adTopFloat,.ad__sticky,.ad_banner,.ad_banner2,.ad_banner",
        "_2,.ad_banner_250x250,.ad_banner_468,.ad_banner_728,.ad_banner_728x90_inner,.ad_banner_border,.ad_banner_div,.",
        "ad_big_banner,.ad_botbanner,.ad_bottom,.ad_bottom_728,.ad_bottom_leaderboard,.ad_bottom_left,.ad_bottom_mpu,.a",
        "d_bottom_space,.ad_center_bottom,.ad_container_bottom,.ad_content_bottom,.ad_custombanner,.ad_div_banner,.ad_f",
        "loat,.ad_floating_box,.ad_footer_super_banner,.ad_leader_bottom,.ad_megabanner,.ad_middle_banner,.ad_overlays,",
        ".ad_placeholder_d_sticky,.ad_side_rectangle_banner,.ad_top_banner,.ad_wrapper_fixed,.adace-adi-popup-wrapper,.",
        "adalert-overlayer,.adbanner-300-250,.adbanner-bottom,.adbanner1,.adbannerbox,.adbannerright,.adbannertop,.adbl",
        "ock-bottom,.adblock-popup,.adblock__banner,.adborderbottom,.adbottom,.adbottomright,.adbox-topbanner,.adboxTop",
        "Banner,.adbox_bottom,.adbutler-top-banner,.adbutler_top_banner,.adform__topbanner,.adfoxly-overlay,.adframe_ba",
        "nner,.adguru-modal-popup,.adpopup,.adriverBanner,.adrotate_top_banner,.ads--bottom-spacing,.ads-articlebottom,",
        ".ads-banner,.ads-banner-bottom,.ads-banner-js,.ads-banner-middle,.ads-banner-spacing,.ads-banner-top,.ads-bann",
        "er-top-right,.ads-bing-bottom,.ads-block-bottom-wrap,.ads-bottom,.ads-bottom-block,.ads-bottom-center,.ads-bot",
        "tom-content,.ads-bottom-left,.ads-bottom-right,.ads-google-bottom,.ads-horizontal-banner,.ads-relatedbottom,.a",
        "ds-sticky,.ads-superbanner,.ads-top-fixed,.adsBanner,.adsBannerLink,.adsBottom,.adsFixed,.adsSticky,.adsTopBan",
        "ner,.ads_banner,.ads_header_bottom,.ads_top_banner,.ads_topbanner,.adsbanner1,.adsbanner2,.adsbottom,.adsbotto",
        "mbox,.adsense-float,.adsense-overlay,.adsense-sticky-slide,.adsenseBannerArea,.adsense_bottom,.adsensefloat,.a",
        "dsfloat,.adsfloatpanel,.adslot-banner,.adslot_popup,.adspace_bottom,.adtech-banner,.adv-banner,.adv-bottom,.ad",
        "v-leaderboard-banner,.adv-top-banner,.advBottom,.advBottomHome,.adv_banner,.adv_banner_hor,.advads-sticky,.adv",
        "ert--banner-wrap,.advert--sticky,.advert-article-bottom,.advert-banner,.advert-banner-container,.advert-banner",
        "-holder,.advert-bannerad,.advert-bottom,.advert-overlay,.advert-sticky-wrapper,.advert-wingbanner-left,.advert",
        "-wingbanner-right,.advert1Banner,.advertBanner,.advertBottom,.advertFullBanner,.advertSuperBanner,.advert__ful",
        "lbanner,.advert_banner,.advert_banners,.advert_main_bottom,.advertisement-banner,.advertisement-bottom,.advert",
        "isementBanner,.advertising-banner,.advertising-fixed,.advertising-top-banner,.advertisingBanner,.advertising_b",
        "anner,.advertising_bottom_box,.advertisment-banner,.advertizing-banner,.adverts--banner,.adviewDFPBanner,.advt",
        "-banner-3,.adzbanner,.after_content_banner_advert,.anzeige_banner,.article-ad-bottom,.articleBottom-ads,.artic",
        "le_ads_banner,.article_bottom-ads,.article_bottom_ad,.banner--ad,.banner-125,.banner-300,.banner-300-100,.bann",
        "er-300-250,.banner-300x250,.banner-300x600,.banner-320-100,.banner-468,.banner-468-60,.banner-468x60,.banner-7",
        "28,.banner-728x90,.banner-ad-below,.banner-ad-block,.banner-ad-bottom-fixed,.banner-ad-container,.banner-ad-co",
        "ntianer,.banner-ad-footer,.banner-ad-image,.banner-ad-inner,.banner-ad-label,.banner-ad-large,.banner-ad-pos,.",
        "banner-ad-row,.banner-ad-skeleton-box,.banner-ad-space,.banner-ad-wrap,.banner-ad-wrapper,.banner-ad2,.banner-",
        "ads-right,.banner-ads-sidebar,.banner-adsense,.banner-adv,.banner-advert,.banner-advert-wrapper,.banner-advert",
        "isement,.banner-advertising,.banner-adverts,.banner-asd__title,.banner-buysellads,.banner-img > .pbl,.banner-s",
        "ponsorship,.banner-top-ads,.banner120x600,.banner160,.banner160x600,.banner200x200,.banner300,.banner300x250,.",
        "banner336,.banner336x280,.banner350,.banner468,.banner728,.banner728-ad,.banner728-container,.banner728x90,.ba",
        "nner_160x600,.banner_240x400,.banner_250x250,.banner_300_250,.banner_300x250,.banner_300x600,.banner_468_60,.b",
        "anner_468x60,.banner_728_90,.banner_ad-728x90,.banner_ad_300x250,.banner_ad_728x90,.banner_ad_container,.banne",
        "r_ad_footer,.banner_ad_full,.banner_ad_leaderboard,.banner_ad_link,.banner_ad_wrapper,.banner_ads1,.banner_rek",
        "lam,.banner_reklam2,.banner_slot,.bannergroup-ads,.banners_ad,.block-ads-bottom,.block-advertisement-banner-bl",
        "ock,.bottom-ad,.bottom-ad--bigbox,.bottom-ad-banner,.bottom-ad-box,.bottom-ad-container,.bottom-ad-desktop,.bo",
        "ttom-ad-large,.bottom-ad-placeholder,.bottom-ad-wrapper,.bottom-ad-zone,.bottom-ad2,.bottom-ads,.bottom-ads-co",
        "ntainer,.bottom-ads-sticky,.bottom-ads-wrapper,.bottom-banner-ad,.bottom-fixed-ad,.bottom-left-ad,.bottom-mobi",
        "le-ad,.bottom-mpu-ad,.bottom-post-ad-space,.bottom-post-ads,.bottom-right-advert,.bottom-side-advertisement,.b",
        "ottom_ad,.bottom_ad_block,.bottom_ad_placeholder,.bottom_ad_responsive,.bottom_ads,.bottom_banner_ad,.bottom_b",
        "anner_advert_text,.bottom_bar_ads,.bottom_left_advert,.bottom_right_ad,.bottom_side_ad,.bottom_sponsor,.bottom",
        "_sticky_ad,.box-fixed-ads,.brn-ads-sticky-wrapper,.c-ad--adStickyContainer,.c-advert-superbanner,.c-advertisin",
        "g__banner-area,.cb-ad-banner,.chapter-bottom-ads,.container-banner-ads,.container-bottom-ad,.content-ads-botto",
        "m,.content-bottom-ad,.ct-bottom-ads,.custom-advert-banner,.custom-sticky-ad-container,.custom_banner_ad,.dac__",
        "banner__wrapper,.desktop-ad-banner,.dfp-banner,.dfp-banner-slot,.dfp_ad_content_bottom,.entry-bottom-ad,.entry",
        "-bottom-ads,.first-banner-ad,.fixed-ad-aside,.fixed-ad-bottom,.fixed-ads,.fixed-bottom-ad,.fixed-sidebar-ad,.f",
        "ixed_ad,.fixed_advert_banner,.floated-ad,.floated_right_ad,.floating-ads,.floating-advert,.follower-ad-bottom,",
        ".footer-banner-ad,.footer-banner-ads,.footer-floating-ad,.footer_bottom_ad,.fullbanner_ad,.gallery-ad-overlay,",
        ".gnm-banner-ad,.google-ads-bottom,.google-bottom-ads,.gpt-leaderboard-banner,.gpt-mpu-banner,.guide__row--fixe",
        "d-ad,.has-fixed-bottom-ad,.head-banner468,.header-ad-banner,.header-banner-ad,.header-banner-ads,.header-banne",
        "r-advertising,.home-sticky-ad,.home_ad_bottom,.homepage-banner-ad,.inline-banner-ad,.is-sticky-ad,.js-ad-banne",
        "r-container,.js-ad-loader-bottom,.js-ad-unit-bottom,.js-dfp-ad-bottom,.js-no-sticky-ad,.js-overlay_ad,.js-stic",
        "ky-ad,.l-bottom-ads,.leaderboard-ad-fixed,.leaderboard_banner_ad,.m_banner_ads,.mc_floating_ad,.mid-article-ba",
        "nner-ad,.mid_banner_ad,.mj-floating-ad-wrapper,.moads-top-banner,.mob-hero-banner-ad-wrap,.mobile-fixed-ad,.mo",
        "bile_ad_banner,.modal-ad,.mpu-top-left-banner,.news_vibrant_ads_banner,.ng-ad-banner,.o-ad-banner-top,.oio-ban",
        "ner-zone,.overlay-ad,.overlay-ad-container,.overlay-ads,.overlay-box-ad,.overlay_ad,.p-ad-dfp-banner,.page-bot",
        "tom-fixed-ads,.player-ad-overlay,.pm-ads-banner,.pm-banner-ad,.profile_ad_bottom,.region-ad-bottom-leaderboard",
        ",.region-banner-ad,.show-sticky-ad,.sidebar-banner-ad,.sidebar-bottom-ad,.sidebar-sticky--ad,.single-bottom-ad",
        "s,.single-post-bottom-ads,.single_bottom_ad,.site-bottom-ad-slot,.sponsor-bottom,.sponsor-popup,.sponsor_popup",
        ",.sponsored-container-bottom,.sticky-ad,.sticky-ad-bottom,.sticky-ad-container,.sticky-ad-footer,.sticky-ad-he",
        "ader,.sticky-ad-wrapper,.sticky-ads,.sticky-ads-container,.sticky-ads-content,.sticky-advert-widget,.sticky-bo",
        "ttom-ad,.sticky-footer-ad,.sticky-footer-ad-container,.sticky-navbar-ad-container,.sticky-rail-ad-container,.s",
        "ticky-side-ad,.sticky-sidebar-ad,.sticky-top-ad-wrap,.sticky_ad_sidebar,.sticky_ad_wrapper,.sticky_ads,.top-ad",
        "-sticky,.top-ads-bottom-bar,.top-adsense-banner,.top-banner-468,.top-banner-ad,.top-banner-ad-container,.top-b",
        "anner-ad-wrapper,.top-banner-add,.top-banner-ads,.top-banner-advert,.top-fixed-ad,.top_ad_banner,.tpd-banner-a",
        "d-container,.tpd-banner-desktop,.vi-sticky-ad,.video-ad-bottom,.vjs-ad-overlay,.vodl-ad__bigsizebanner,.wp_ban",
        "nerize_banner_box,.wpx_bannerize_banner_box,.ww_ads_banner_wrapper,.boxOverContent__banner,.RC-AD-BOX-BOTTOM,.",
        "RC-AD-TOP-BANNER,.popup-box-ads,.amp-sticky-ad-custom,.amp-sticky-ads,#mobile-swipe-banner,#banner_pos1_ddb_0,",
        "#banner_pos2_ddb_0,#banner_pos3_ddb_0,#banner_pos4_ddb_0,.taboola-banner{display:none!important}';(document.he",
        "ad||document.documentElement).appendChild(__museSt);}catch(e){}var host=location.hostname;var __museAdSet=new ",
        "Set(\"005ad66f1d.com,10bet.com,152media.com,18porn.sex,18teensex.tv,19sex.live,1adda2699c.com,1av.to,1betandgon",
        "ow.com,1gbjadpsq.de,1pop.online,1push.io,206ads.com,20tracks.com,21f5f01ad3.com,21sexturycash.com,22523e8ad1.c",
        "om,22betpartners.com,22bettracking.online,22media.world,24affiliates.com,24click.top,24x7adservice.com,27bad22",
        "e4a.com,2mdn.net,2smarttracker.com,2track.info,300daytravel.com,360ads.com,365av.net,3c1d2ad9d8.com,3d-porn.co",
        ",3mads.com,3myad.com,492tracker.net,4ad4ba0610.com,4affiliate.net,4dsbanner.net,4kporn.xxx,4pornhd.com,4rabett",
        "raff.com,4tube.com,4wnetwork.com,567bets10.com,56ad42c3a8.com,570dad652f.com,5advertise.com,62ad.com,70ad2eae1",
        "e.com,777av.cc,840withnetworks.net,888media.net,888promos.com,8c56c86ead.com,8d1uavww.xyz,8f7cd5dfad.com,8sfdl",
        "adng.xyz,900bets10.com,901radius.net,930networks.net,99ad29e111.com,99ddb6ad48.com,a-ads.com,aa783adfeb.com,aa",
        "ddcount.com,aanetwork.vn,aavvsaqmktwmu.website,aaxads.com,abadit5rckb.com,abadit5rckd.com,abateadversity.com,a",
        "bethow.com,abnad.net,aboveaverageheavenly.com,abovemyselfsadness.com,abroadsuicidaleliminated.com,absoluporn.c",
        "om,absolutionthornleader.com,academywristadroit.com,acam-2.com,acceptableredheadcaviar.com,acceptancecrowdadve",
        "rtising.com,accomplishedpersuadedprinter.com,accoybrava.com,ace-adserver.com,acebet.com,aceporntube.com,achiev",
        "ablecpmrevenue.com,achievementseeingladder.com,acmexxx.com,acrossexterminatorfoggy.com,activejavgg124.fun,ad-a",
        "dapex.io,ad-arrow.com,ad-back.net,ad-balancer.net,ad-block-offer.com,ad-cheers.com,ad-delivery.net,ad-flow.com",
        ",ad-free.info,ad-m.asia,ad-mapps.com,ad-maven.com,ad-mixr.com,ad-nex.com,ad-opt.site,ad-recommend.com,ad-score",
        ".com,ad-server.co.za,ad-srv.net,ad-stir.com,ad-tech.ru,ad-vortex.com,ad.gt,ad.guru,ad.page,ad.plus,ad.style,ad",
        "1n.net,ad20.net,ad2391c451.com,ad2adnetwork.biz,ad2bitcoin.com,ad2f8c6f8e.com,ad2the.net,ad4.com.cn,ad4989.co.",
        "kr,ad4game.com,ad62c988a3.com,ad69.com,ad6media.fr,ad999.biz,adacado.com,adactioner.com,adacts.com,adadvisor.n",
        "et,adagora.com,adahumanhear.com,adalh-zcq.com,adalliance.io,adalso.com,adamantundergone.com,adamatic.co,adandh",
        "ub.com,adangle.online,adaos-ads.net,adapex.io,adappi.co,adaptationprodigalevil.com,adaptdivinity.com,adaptedes",
        "timatedcaress.com,adaptedmercifulinterruption.com,adapterwith1024.net,adapthopperpriest.com,adaptive5000.net,a",
        "daptunemployed.com,adaround.net,adarutoad.com,adasgsts.cc,adatrix.com,adbakbhknpczi.site,adbard.net,adbasket.n",
        "et,adbeacon.com,adbestnet.com,adbetclickin.pink,adbetnetwork.com,adbfkaoxvm.in,adbit.biz,adbits.online,adblade",
        ".com,adblck.com,adblock-360.com,adblock-guru.com,adblock-one-protection.com,adblock-tester.com,adblock-zen.com",
        ",adblockanalytics.com,adblocker-sentinel.net,adblockermax.com,adblockeromega.com,adblockerprofessional.com,adb",
        "lockersentinel.com,adblockstream.com,adblockstrtape.link,adblockstrtech.link,adbmi.com,adboost.it,adbooth.com,",
        "adbox.lv,adbpage.com,adbrite.com,adbro.me,adbuddiz.com,adbuff.com,adbull.com,adbutler-fermion.com,adbutler.com",
        ",adbwublzlyxvg.online,adbyss.com,adca.st,adcalm.com,adcannyxml.com,adcash.com,adcdnx.com,adcell.com,adcell.de,",
        "adcharriot.com,adcleanerpage.com,adclerks.com,adclick.pk,adclicka.com,adclickad.com,adclickafrica.com,adclickb",
        "yte.com,adclickmedia.com,adclickppc.com,adclicks.io,adcloud.net,adcolo.com,adcolony.com,adconscious.com,adcont",
        "ext.pl,adcovery.com,adcrax.com,addefenderplus.info,addictedpledgeabridge.com,addictrelive.com,addin1.name,addi",
        "nginstancesroadmap.com,addiply.com,additionalcasualcabinet.com,additionalheritagenose.com,additionalmedia.com,",
        "additionmillionaireimpending.com,addizhi.top,addkt.com,addoer.com,addonsmash.com,addoor.net,addotnet.com,addre",
        "ssimpertinenceclockwise.com,addroid.com,addroplet.com,addthief.com,addynamo.net,adecn.com,adehhurslxxmh.space,",
        "adelement.com,adelphic.net,ademails.com,adenc.co.kr,adengage.com,adentifi.com,adenza.dev,adeoplonrprkm.site,ad",
        "espresso.com,adevppl.com,adewpxnzsnvib.site,adex.media,adexchangeclear.com,adexchangecloud.com,adexchangedirec",
        "t.com,adexchangegate.com,adexchangeguru.com,adexchangemachine.com,adexchangeprediction.com,adexchangerapid.com",
        ",adexchangetracker.com,adexcite.com,adexmedias.com,adextrem.com,adf01.net,adfgetlink.net,adfinity.pro,adfinix.",
        "com,adflyer.media,adfoc.us,adfootprints.com,adforcast.com,adforgames.com,adforge.io,adform.net,adfox.cam,adfpo",
        "int.com,adfrontiers.com,adfusion.com,adfyre.co,adgage.es,adgatemedia.com,adgear.com,adgebra.co.in,adgebra.in,a",
        "dgitize.com,adglare.net,adglidepro.com,adgoi.com,adgorithms.com,adgrid.io,adgroups.com,adgrx.com,adhash.com,ad",
        "haven.com,adhealers.com,adherebarrowdomesticated.com,adherepreserveshrimp.com,adhese.be,adhese.com,adhese.net,",
        "adhigh.net,adhitzads.com,adhoc2.net,adhouse.pro,adhub.digital,adhub.media,adhunt.net,adikteev.com,adimpact.com",
        ",adinc.co.kr,adinc.kr,adinch.com,adingo.jp,adinplay.com,adintend.com,adinterax.com,adinvigorate.com,adipolo.co",
        "m,adipolosolutions.com,adiquity.com,adireland.com,adireto.com,adisfy.com,adisn.com,adit-media.com,adition.com,",
        "aditize.com,aditms.me,aditsafeweb.com,aditude.io,adjacenthairshag.com,adjal.com,adjector.com,adjkulzooirq.com,",
        "adjoininglearning.com,adjs.media,adjug.com,adjuggler.com,adjuggler.net,adjungle.com,adjust.com,adjustmentthesi",
        "lver.com,adjux.com,adk2x.com,adkaora.space,adkernel.com,adklimages.com,adklip.com,adkmbc.com,adknowledge.com,a",
        "dkonekt.com,adkora.com,adkova.com,adlane.info,adlatch.com,adlayer.net,adlegend.com,adligature.com,adlightning.",
        "com,adlink.net,adlive.io,adlogists.com,adlook.me,adloop.co,adlooxtracking.com,adlpartner.com,adlserq.com,adlts",
        "erv.com,adlux.com,adlzmqouddtim.space,admaaodfwkcuj.store,admachina.com,adman.gr,admana.net,admanmedia.com,adm",
        "aru.com,admasters.media,admatic.com.tr,admatic.de,admax.network,admaxim.com,admcmscyyhzem.online,admd.ink,admd",
        "oqipetokp.space,adme-net.com,adme2.click,admedia.com,admediaportal.com,admediatex.net,admedit.net,admedo.com,a",
        "dmeerkat.com,admeking.com,admeridianads.com,admetricspro.com,admicro.vn,admidainsight.com,administerembodiment",
        ".com,administersurlyunbelievable.com,admirableinstance.com,admirecallpalpate.com,admiredjumper.com,admiregarri",
        "sonnetwork.com,admissibleblinkbesiege.com,admissibleconference.com,admission.net,admissiondemeanourusage.com,a",
        "dmitad-connect.com,admitad.com,admittanceskirtanthony.com,admittedhi.com,admixer.md,admixer.net,admjmp.com,adm",
        "mjpbxebrfu.space,admoai.com,admob-cn.com,admob.com,admobe.com,admolo.com,admonishconcentrate.com,admonishfistg",
        "ipsy.com,admonishmentfastest.com,admonishmenttub.com,admost.com,admothreewallent.com,admpire.com,admulti.com,a",
        "dmunvlvrcsaf.online,adnade.net,adnami.io,adnami2.io,adnanny.com,adncdn.net,adnet.biz,adnet.com,adnet.de,adnet.",
        "lt,adnet.ru,adnetworkperformance.com,adnetwrk.com,adnext.fr,adnext.pl,adngin.com,adnico.jp,adnigma.com,adnimat",
        "ion.com,adnimo.com,adnitro.pro,adnium.com,adnmore.co.kr,adnotebook.com,adnow.com,adnuntius.com,adnxs-simple.co",
        "m,adnxs.com,adnxs.net,adobe.com,adocean.pl,adodishonourcrutch.com,adokutrtb.com,adolcgailvycg.site,adomic.com,",
        "adomik.com,adonnews.com,adonweb.ru,adop.co,adoperator.com,adoperatorx.com,adopexchange.com,adopsboost.com,adop",
        "star.uk,adoptim.com,adoptum.net,adorableoil.pro,adorableold.com,adorableorchid.com,adorablequestion.pro,adorar",
        "ama.com,adorika.com,adornmadeup.com,adornmenttaunt.com,adosia.com,adotic.com,adotmob.com,adoto.net,adotone.com",
        ",adotube.com,adpacks.com,adparlor.com,adpass.co.uk,adpatrof.com,adpay.com,adpays.net,adpeepshosted.com,adperfe",
        "ct.com,adperium.com,adpicmedia.net,adpinion.com,adpionier.de,adplsr.com,adplugg.com,adplxmd.com,adpmbexo.com,a",
        "dpmbexoxvid.com,adpmbglobal.com,adpmbtf.com,adpmbtj.com,adpmbts.com,adpnut.com,adpod.in,adpone.com,adpool.bet,",
        "adpopblzuma.in,adport.io,adport.net,adpredictive.com,adpresenter.de,adprime.com,adprnvjzubygw.online,adpushup.",
        "com,adqsqmeyixjaf.online,adquake.com,adquery.io,adquire.com,adqva.com,adqxchmsttoyz.online,adreactor.com,adrea",
        "dytractions.com,adrealclick.com,adrecord.com,adrecover.com,adrecreate.com,adrenalinemanipulativeact.com,adreno",
        "vate.com,adrent.net,adresellers.com,adrevenuerescue.com,adrevolver.com,adrianpeachdesign.com,adrifttenderly.co",
        "m,adright.co,adrino.io,adrise.de,adro.co,adrocket.com,adroitham.com,adroitirresponsibleshoes.com,adroitswarmcu",
        "ltural.com,adrokt.com,adroll.com,adrta.com,adrunnr.com,ads-abc.com,ads-adv.top,ads-bitcoin.com,ads-blocker.app",
        ",ads-blocker.pro,ads-pixiv.net,ads-tracking.com,ads-twitter.com,ads.cc,ads1-adnow.com,ads2550.bid,ads2ads.net,",
        "ads3-adnow.com,ads4g.pl,ads4media.online,ads4trk.com,ads5-adnow.com,ads6-adnow.com,ads7-adnow.com,adsafeprotec",
        "ted.com,adsafety.net,adsame.com,adsappier.com,adsarcade.com,adsb4trk.com,adsbar.online,adsbetnet.com,adsblocke",
        "rsentinel.info,adsbookie.com,adsboosters.xyz,adsbreak.com,adsbtrk.com,adsbuddy.net,adscale.de,adscampaign.net,",
        "adschill.com,adscienceltd.com,adsco.re,adscoreip.com,adscout.io,adscreendirect.com,adsdk.com,adsdot.ph,adsenix",
        ".com,adsensecamp.com,adsensecustomsearchads.com,adserv.mobi,adserve.com,adserve.ph,adserverplus.com,adserverpu",
        "b.com,adservicemedia.dk,adservingfactory.com,adservon.com,adservrs.com,adsession.com,adsessionserv.com,adsexse",
        ".com,adsfac.net,adsfast.com,adsforallmedia.com,adsgateway.net,adshack.com,adshnk.com,adshopping.com,adshostnet",
        ".com,adshot.de,adsiduous.com,adsight.nl,adsinstant.com,adsinteractive.com,adsjqaobchetb.space,adskape.ru,adske",
        "eper.co.uk,adskeeper.com,adskpak.com,adslidango.com,adslivecorp.com,adsloom.com,adslop.link,adslot.com,adslots",
        ".com,adslowezlzadr.space,adsmarch.online,adsmarket.com,adsmart.hk,adsmediabox.com,adsnative.com,adsninja.ca,ad",
        "sniper.ru,adsoftware.top,adsolutely.com,adsonar.com,adsparc.net,adspdbl.com,adspector.io,adspeed.com,adspeed.n",
        "et,adspirit.de,adsplay.in,adsplex.com,adspop.me,adspredictiv.com,adspruce.com,adspygl.xyz,adspyglass.com,adsqu",
        "ash.info,adsquasher.com,adsquasher.pro,adsquirrel.ai,adsratcarp.com,adsreference.com,adsring.com,adsrv.net,ads",
        "rv.wtf,adsrv4k.com,adsrvmedia.com,adsrvr.org,adstargeting.com,adstargets.com,adstean.com,adsterra.com,adsterra",
        "server.com,adstik.click,adstock.pro,adstoo.com,adstook.com,adstub.net,adstudio.cloud,adstuna.com,adsturn.com,a",
        "dsupply.com,adsupplyads.com,adsupplyads.net,adsupplyssl.com,adsurve.com,adsvert.com,adsvolum.com,adsvolume.com",
        ",adswam.com,adswizz.com,adsxtits.com,adsxtits.pro,adsxyz.com,adt328.com,adt545.net,adt567.net,adt574.com,adt59",
        "8.com,adtags.mobi,adtaily.com,adtaily.pl,adtarget.biz,adtarget.com.tr,adtarget.market,adtdp.com,adtear.com,adt",
        "ech.de,adtechium.com,adtechjp.com,adtechus.com,adtegrity.net,adtelligent.com,adteractive.com,adtheyhaveset.org",
        ",adthrive.com,adtival.network,adtlgc.com,adtng.com,adtoma.com,adtonement.com,adtonos.com,adtoox.com,adtotal.pl",
        ",adtpix.com,adtrace.org,adtraction.com,adtrafficquality.google,adtrcdn.io,adtrhogpvnwkt.space,adtrieval.com,ad",
        "trix.com,adtrue.com,adtrue24.com,adtumxjckljej.space,adult-sex-games.com,adult.xyz,adult3dcomics.com,adultadve",
        "rtising.net,adultadworld.com,adultasianporn.com,adultcamchatfree.com,adultcamfree.com,adultcamliveweb.com,adul",
        "tdeepfakes.cam,adultfilmdatabase.com,adultforce.com,adultfriendfinder.com,adultgameexchange.com,adultimate.net",
        ",adultlinkexchange.com,adultsense.com,adultsense.net,adultsense.org,adultsjuniorfling.com,adultswim.com,adunbl",
        "ocker.com,adup-tech.com,adv-adserver.com,advally.com,advancedadblocker.pro,advancedsystemrepairpro.com,advanci",
        "ngtheirs.com,advanseads.com,advantageroughplay.com,advard.com,advcash.com,adventori.com,adventurefeeds.com,adv",
        "enturehannahmechanic.com,adventurerforewordpatio.com,adventurouscomprehendhold.com,adverge.ai,adversal.com,adv",
        "ersaldisplay.com,adversalservers.com,adverserve.net,advertica.com,adverticum.net,advertipros.com,advertise.com",
        ",advertiserubyproximity.com,advertiserurl.com,advertiseserve.com,advertisespace.com,advertising-cdn.com,advert",
        "ising365.com,advertisingiq.com,advertisoo.com,advertjunction.com,advertlets.com,advertnative.com,advertone.ru,",
        "advertserve.com,advertsource.co.uk,advertur.ru,advfeeds.com,adviad.com,adviceglossaryeminence.com,advisesaltsa",
        "rmless.com,advisorthrowbible.com,adviva.net,advmaker.ru,advmd.com,advmedialtd.com,advombat.ru,advon.net,advoti",
        "onhot.com,advozcevf.com,advp1.com,advp2.com,advp3.com,advpx.com,advpy.com,advpz.com,advservert.com,advsnx.net,",
        "advtise.net,adw9.com,adwalte.info,adway.org,adwebster.com,adworldmedia.com,adworldmedia.net,adwx6vcj.com,adx-t",
        ".com,adx.io,adx.ws,adx1.com,adxadserv.com,adxapi.online,adxawkejwjdfh.site,adxbid.info,adxfire.in,adxfire.net,",
        "adxhand1.name,adxion.com,adxnexus.com,adxpansion.com,adxpartner.com,adxplay.com,adxpose.com,adxpremium.com,adx",
        "premium.services,adxpub.com,adxscope.com,adxsource.com,adxsrver.com,adxvsipicgwop.site,adxxx.biz,adxxxxxmost.c",
        "o,adyoulike.com,adysis.com,adywrlubfplrh.site,adz2you.xyz,adzbazar.com,adzblockersentinel.net,adzcivwjgktmz.si",
        "te,adzen.ai,adzerk.net,adziff.com,adzilla.meme,adzilla1.name,adzivijnyizgp.space,adzmedia.com,adzmob.com,adzou",
        "k.com,adzpier.com,adzs.com,adzs.nl,aehjiuheifpop.online,aemediatraffic.com,afdads.com,aff-track.net,aff1xstavk",
        "a.com,affbuzzads.com,affidavitheadfirstonward.com,affiliate-b.com,affiliate-robot.com,affiliate-wg.com,affilia",
        "teboutiquenetwork.com,affiliateedge.com,affiliateer.com,affiliatefuel.com,affiliatefuture.com,affiliategroove.",
        "com,affiliatelounge.com,affiliatemembership.com,affiliates.systems,affiliatesensor.com,affiliatesleague.com,af",
        "filiatestonybet.com,affiliatewindow.com,affstrack.com,afftrack.com,afftrackr.com,afiadwcsvntpn.space,afishamed",
        "ia.net,afrikad.com,afrsportsbetting.com,afterdownload.com,afterdownloads.com,afyads.com,agavstdlunmyb.website,",
        "agendadejection.com,aggravatingchicken.pro,aggravatingoil.com,agravicsensual.cyou,agvmufccuavus.online,ahadspl",
        "y.com,aheadpains.com,ahejadicyivel.site,aim4media.com,aipmedia.com,airlessstereoadvised.com,ak-tracker.com,aka",
        "vita.com,aknadnauhsmgu.space,al-adtech.com,alarmsportsnetwork.com,alexatracker.com,alienateparadise.com,alipro",
        "mo.com,alleliteads.com,alleviatepracticableaddicted.com,allmediadesk.com,allosponsor.com,allpornovids.com,allu",
        "desexptl.cyou,almightypush.com,alpha-affiliates.com,alphaporno.com,alterassumeaggravate.com,alternads.info,alt",
        "ernatewavyrid.com,alternativecpmgate.com,alturaadvertising.com,alumniheadfirstshamrock.com,amateurcouplewebcam",
        ".com,amateurporn.net,amazing-dating.com,amazon-adsystem.com,americanacademyofthermology.org,amesgraduatel.xyz,",
        "amididsavola.com,amperwave.net,amsacpcugjc.in,amuletsourcemadden.com,amusedradish.com,anacams.com,analsexstars",
        ".com,anastasiasaffiliate.com,ancestorheadquarters.com,aneadtwrlpqob.online,anguishaddition.com,angularpoppyrob",
        "bing.com,anjavjkadvun.in,anonymousads.com,antiadblock.info,antiadblocksystems.com,antique-average.com,antonyth",
        "wartlead.com,anyadx.live,anyclip-media.com,anymedia.lv,anyporn.com,anysex.com,aorzbyyrhlavn.site,apbrumavwr.in",
        ",apex-ad.com,apophishoggism.cfd,appendad.com,appetitecamisolereawake.com,apromoweb.com,aqua-adserver.com,araby",
        "ads.com,arbattadr.in,arcadebannerexchange.org,arcadechain.com,arcadiablankdenying.com,archipelagokennelleaders",
        "hip.com,arid-leadership.pro,arkadiumhosted.com,aromadenotes.cyou,arsesexuded.cfd,arxoplagziavr.site,asaunmavgi",
        "tw.com,ascendeummedia.com,ascentlitigateunsaved.com,asdasdad.net,asgadmp.cc,asgadsts.cc,asgclick.com,asgclickp",
        "p.com,asian-xxx-videos.com,asiangfsex.com,askdomainad.com,asktiava.com,asterouhavenev.org,asthmanotchcave.com,",
        "athyimemediat.org,atube.xxx,auavaxrfgyyrv.online,audiblyacademyadaptation.com,audience2media.com,august15downl",
        "oad.com,aurescadrachen.com,auroraveil.bid,authorizedimmediate.com,auto-ads6.site,autoads.asia,automatad.com,au",
        "totrader.ca,autotrader.co.za,auyydavghsuox.site,avads.co.uk,avads.live,avalancheofnews.com,avalanchetremorunfi",
        "lled.com,avariciousnobody.pro,avatrade.io,avatraffic.com,avazu.net,avazutracking.net,avbsqhunnykee.site,avcafz",
        "oz.in,avdludlmpseoe.space,avebeentriflin.com,aveherald.com,avelpwnzhdoyx.site,avenidageranic.cyou,avenueninete",
        "enthendorsement.com,average-movie.com,average-statement.com,averinierne.cyou,avestastupendfreit.cyou,avevumywa",
        "dmrw.website,avfay.com,avfbqhwuowien.online,avfdikaldj.in,avgle.com,avgony.xyz,avhlvyyhjhvli.site,avhtaapxml.c",
        "om,avhxlgougslod.space,aviatedmidair.com,avifwlqraveqt.online,avigatejungle.click,avilionrewrote.shop,avimdxze",
        "kvlhe.space,avjecheybdtqj.space,avjlllfpqvloi.website,avkauywgzdxlx.online,avkmgwykmovxx.site,avkszbsmzamaj.si",
        "te,avlmy.com,avmakravzqfvj.space,avmmsekplixsk.space,avmviiih.com,avn.com,avnhqqqoljvgf.space,avnqsnekvmpzu.si",
        "te,avnwqayeafuve.website,avocams.com,avouchlawsrethink.com,avouchuramil.com,avoutergubbins.qpon,avowdanielungr",
        "ave.cyou,avowingoxaliteglancer.qpon,avpjihtkjsnfe.space,avplayer.com,avqjufpki.in,avqovczcoanp.com,avsfppmelmk",
        "zr.online,avswajharwhe.in,avszjedalkciq.space,avthelkp.net,avtrrdtzmnmsv.website,avvpsstgoheny.online,avxsduff",
        "diewn.space,avxskbxkmtawx.space,avylshidkaggoj.com,avypcwqqhuz.com,avytybblrvweh.space,avzaqmaoryehd.website,a",
        "vzmhurdchosj.space,avzwgvitrfmsbg.com,avzxcwkpckxyz.space,awakengoaveserasion.cyou,awmradivlamtw.online,awrsfa",
        "di.in,axabpelvjfqav.site,axiaffiliates.com,axoxktmvnhtav.site,axupfpniadauk.site,ayads.co,ayadsdyieahbn.space,",
        "ayxbetuxafa.com,azadify.com,azoogleads.com,aztadtvrog.in,b677b1ad62.com,babelmedia.global,babesinporn.com,baby",
        "boomboomads.com,backbeatmedia.com,badgegirdle.com,badgertulip.com,badjocks.com,badjojo.com,badlandlispyippee.c",
        "om,badsllcvgillm.space,badwtokg.com,bagdigravy.com,balzadicyema.com,bancadeltempoidea.org,banner-iframe.com,ba",
        "nneradsday.com,bannerbank.ru,bannerboo.com,bannerbridge.net,bannerconnect.com,bannerconnect.net,bannerdealer.c",
        "om,bannerflow.com,bannerflow.net,bannerignition.co.za,banneroctober.store,banners5html2.com,bannersmall.com,ba",
        "nnersnack.com,bannersnack.net,bannerweb.com,baseporno.com,bavaroylocker.cyou,bavbfmokxwqtn.space,bavdxafbegkvr",
        ".space,bavggygpconuq.site,bavoozimaxiev.website,bc48fa9adc.com,bcaadnibyntzm.space,bckbhadz.com,bcwhnizcbethy.",
        "website,bdavjbhjpzjmt.space,beaconadnetwork.com,beavertron.com,bebraveknob.com,becausepop.com,beead.net,begins",
        "rainbowcamomile.com,behave.com,behavepassword.com,belavoplay.com,beleavenbg.com,bellamylavatory.com,benefit-ad",
        "s.com,bentadaptation.com,beringmedia.com,bestadload.com,bestadsforyou.com,bestcontenttrade.top,bestcpmnetwork.",
        "com,bestfunnyads.com,bestonlinecasino.club,bestrevenuenetwork.com,bet3000partners.com,bet365.com,bet365affilia",
        "tes.com,betads.xyz,betafot.com,betahit.click,betaoutinnovations.net,betclever.com,beterrakionan.com,betfair.co",
        "m,betforakiea.com,betforce.io,betgorebysson.club,betmasquerainchan.com,betmenaff.com,betoga.com,betotodilea.co",
        "m,betpartners.it,betplays.com,betpupitarr.com,betr-for-tod.com,betrad.com,betrapcb.cfd,betrayalbadger.com,betr",
        "ayedjoltmethod.com,betshucklean.com,betsson.com,bettentacruela.com,betteradsystem.com,betterbonnet.pro,betterc",
        "ollective.rocks,betterdatabase.pro,betterdomino.com,bettingcurly.com,bettingoffer.xyz,bettingpartners.com,bett",
        "ingpremier.com,bettraff.com,between-legs.com,betweenbrr.com,betweendigital.com,betweenprimarily.com,betwinner1",
        ".com,betzillopartners.com,betzone2000.com,beutavpysjojf.site,bexkpopzujdus.space,bf-ad.net,bgwsktadxvgjv.websi",
        "te,bhabhiporn.pro,bhavkiyrxbifv.space,bhwgdvpavsfzw.site,biadglomdzyys.online,bicyclebusgrave.work,bidclickmed",
        "ia.com,bidderads.com,biemedia.com,bigboxads.com,biodegradablegrand.com,biqadnwzega.in,bistredchavercaption.qpo",
        "n,bit-ad.com,bitadexchange.com,bitcoadz.io,bitmedia.io,bitonclick.com,bitspush.io,bizonads-ssp.com,bizzclick.c",
        "om,bjuojadykz.com,bkgwadxzovl.com,bkjblyuukoavf.site,blacksexmatch.com,bladdersdisarmaware.com,bladderssewing.",
        "com,bladeamendable.com,bladedescendant.com,bladespillowcase.com,bladswetis.com,blamads.com,blarcamxooysi.store",
        ",blaze-media.com,blinkedlanentablelanentableunavailable.com,blinksbobblepadding.com,blismedia.com,block-ad.com",
        ",blockadsnot.com,blockchain-ads.com,blogads.com,blogherads.com,blue-ribbonmacadamizeprovide.com,bluekai.com,bl",
        "uepromocode.com,bluerunningshadow.com,bluetoad.com,blwadijbvbttx.space,bmfads.com,bmmads.com,bngmadjd.de,bngpo",
        "p.com,boffoadsfeeds.com,bonad.io,bongacams.com,bongacams10.com,bongacams11.com,bongacams7.com,bongacams8.com,b",
        "ookadil.com,bookbannershop.com,bookofsex.com,boomads.com,boostads.net,boreaddingobserve.com,boskodating.com,bo",
        "unceads.net,bovinetimingunsaddle.com,bovmkbuiaddbb.website,boxlikepavers.com,bqszugpxadzlg.store,brainlyads.co",
        "m,brave.com,bravefork.com,braverybreezebinding.com,braveryriftraining.com,bravoteens.com,bravotrk.com,bravotub",
        "e.net,brazzersnetwork.com,breadmljlfdwx.site,bridgetrack.com,brigadedelegatesandbox.com,brightadnetwork.com,br",
        "itainbetrayalbands.com,broadensilkslush.com,broadlyjukeboxunrevised.com,broadsheetspikesnick.com,broadsidedais",
        "ysanta.com,broadstreetads.com,brodownloads.site,broggleceburbetire.qpon,brothypopuli.com,bruceleadx1.com,brxql",
        "wrzcpcvx.website,bsadcmesynzyb.store,bskuxhcaduumi.online,btagmedia.com,btccasinoexpert.com,bthtcgwaniavh.webs",
        "ite,btpnav.com,btpopoogrnqvj.site,btprmnav.com,bttrack.com,btxxxnav.com,bucketsofbanners.com,budadymskizcs.onl",
        "ine,buffetaccommodating.com,bukvwavcmonjv.space,burstyflavia.com,businessclick.com,butwehaveino.com,buyadvupfo",
        "r24.com,buysellads.com,buysellads.net,buzzadexchange.com,buzzadnetwork.com,buzzonclick.com,buzzparadise.com,bw",
        "qfyjaynjpad.site,bwwuveplotadr.site,byumhwhapayad.space,byxxxporn.com,bzltavhalapzv.online,bztzpzqpltlad.site,",
        "c21media.net,c4tracking01.com,ca.ijknabd.top,cachuadirked.top,cactusheadroomscaling.com,cadencebenefit.com,cad",
        "secs.com,cadsimz.com,cadskiz.com,cadwgsofoyxky.site,calombotubelet.com,cam-content.com,cam-lolita.net,cam4.com",
        ",cam4flat.com,cam4tracking.com,camailwhickerruddles.cyou,camaligraia.cyou,cambaddies.com,cambay.tv,cambioturki",
        "sh.com,camcaps.ac,camcaps.io,camcaps.to,camcrush.com,camdough.com,camduty.com,camelusnakulabiotite.cyou,cameoe",
        "dexcuse.com,camghosts.com,camhub.cc,camiocw.com,camliveshow.xyz,cammpaign.com,camomilegentlemennotable.com,cam",
        "onade.com,camonster.com,camonster.io,camouflagemm.com,campaignlook.com,campanaoutgrin.cyou,campartner.com,camp",
        "enosine.qpon,campfirecroutondecorator.com,camph.org,camplacecash.com,camptwined.com,cams.com,cams.gratis,camsa",
        "im.com,camschat.net,camsense.com,camshq.info,camsitecash.com,camsoda.com,camsoda1.com,camspacelive.com,camspla",
        "netlive.com,camterest.com,camvideos.tv,camzap.com,candyaversion.com,candynetwork.ai,cantorspaver.com,caravanex",
        "hale.com,carbonads.com,carbonads.net,carpalsgeneuradiiamb.qpon,cartoonporn.to,casalemedia.com,cash-ads.com,cas",
        "inobonusdeals.io,casinohacksforyou.com,casinousagevacant.com,casiyouaffiliates.com,casserolerehabilitationgrad",
        "ual.com,casumoaffiliates.com,catchtheclick.com,caveletganoine.qpon,cavergalere.com,cavernsinaite.com,cavewrap.",
        "care,cavuscecilydvornik.cfd,cavvfjxabrffj.website,cavzgoqiozmyc.space,cbhadfiwcdgsl.space,ccjzuavqrh.com,cdn-a",
        "dtech.com,cdn-adtrue.com,cdn4ads.com,cdnads.com,cdymqigrkwzav.site,ceasedaggravation.com,celeb-ads.com,celebji",
        "had.com,celebjihad.live,cellaradorn.com,cerxxxwjtxebr.site,cfivfadtlr.com,cgpjpvvcadzrp.store,chabadokor.com,c",
        "hainads.io,chalazataverns.com,chandrabinduad.com,changedadroitharassment.com,charltonmedia.com,cheemsporn.com,",
        "chiatlaadovus.website,chihuahuadoorstoppond.com,chikiporn.com,chipleader.com,chpadblock.com,chymiadolors.com,c",
        "hzhqwirsxmad.online,cibleclick.com,cieapkjfmavqd.site,cinemadeck.com,circaeavamper.com,circlejavgg124.fun,cita",
        "delexampleruckus.com,citadelpathstatue.com,citizenshadowrequires.com,citrusad.com,city-ads.de,cityadspix.com,c",
        "jgimadfnuztn.online,cjlmffavyoprm.site,ckiqxrqwnavqt.website,ckwouavsimupd.space,cladupius.com,clash-media.com",
        ",clavetriple.cyou,clavuvirameses.com,clbanners16.com,cleanmediaads.com,clearadnetwork.com,clearonclick.com,cle",
        "avebullydevaluation.com,cleverads.vn,clicadu.com,click-cdn.com,click-for-preview.com,click4.pro,clickable.com,",
        "clickad.pl,clickadilla.com,clickadsource.com,clickadu.com,clickadu.io,clickadu.net,clickagy.com,clickaine.com,",
        "clickallow.net,clickbank.net,clickbet88.com,clickblitzo.com,clickbooth.com,clickboothlnk.com,clickcash.com,cli",
        "ckcdn.co,clickcertain.com,clickco.net,clickdaly.com,clickdescentchristmas.com,clickexperts.net,clickfilter.co,",
        "clickfunnels.com,clickfuse.com,clickinc.com,clickintext.com,clickintext.net,clickiocdn.com,clickmagick.com,cli",
        "ckmediahub.com,clickmetertracking.com,clickmi.net,clickmobad.net,clickmon.co.kr,clicknano.com,clicknerd.com,cl",
        "icknplay.to,clickopop1000.com,clickoutcare.io,clickoutnetwork.care,clickpapa.com,clickpoint.com,clickppcbuzz.c",
        "om,clickprotects.com,clickpunch.icu,clickpupbit.com,clicks4tc.com,clicksgear.com,clicksor.com,clicksor.net,cli",
        "ckterra.net,clickthruhost.com,clickthruserver.com,clicktimes.bid,clicktoupdservice.online,clicktraceclick.com,",
        "clicktripz.com,clicktrixredirects.com,clickupto.com,clickwhore.com,clickwinks.com,clickwork7secure.com,clickxc",
        "hange.com,clinicadentalmariangeleslazcano.com,clivetadds.com,clladss.com,clobberprocurertightwad.com,clockwise",
        "leaderfilament.com,cloudbet.com,cloudcinemaheavily.com,cloudtrack-camp.com,clrqbqtonradt.online,clusiavacuo.qp",
        "on,cmadserver.de,cmfads.com,cnptuyxjmdave.site,cocambgwyeeym.website,cockpitadministration.com,codeonclick.com",
        ",coffeavirify.com,coinad.in,coinad.media,coinads.online,coinadster.com,coinmedia.co,collectbladders.com,comedi",
        "andesired.com,comfortclick.co.uk,comicad.net,commiseratebeamingbarricade.com,commonwealthgroovemadam.com,compa",
        "ssionatespreadinquire.com,complexnetwork.pro,comradegoodsfloor.com,concealtraveller.com,conciergeradialsuperno",
        "va.com,condescendingaveragerain.com,conditioneavesdroppingbarter.com,conduit-banners.com,connectad.io,connecti",
        "ngadjusthospitality.com,conscientiouscabbageadrift.com,constantlyauthoritativegradually.com,containingstripesa",
        "dmonish.com,contemplatepushed.com,contemplatewaryheadquarter.com,content-loader.com,contentcave.co.kr,contentc",
        "lick.co.uk,contextads.live,contextuads.com,continue-download.com,contributorshaveangry.com,controlslaverystuff",
        "ing.com,conxxx.pro,cookpad-ads.com,cooladblocker.info,coolerads.com,coolestinstead.com,cooperativepop.com,coor",
        "dinatedwave.pro,coreadness.com,cosponsorgarnetmorphing.com,counciladvertising.net,coursejavgg124.fun,courthous",
        "ecadenceconsiderable.com,couveaverral.cyou,covivado.club,coxmavefzvnqwl.com,cpaclickz.com,cpalabtracking.com,c",
        "palead.com,cpcmart.com,cpcstar.com,cpm.biz,cpm20.com,cpmadvisors.com,cpmbugaboo.com,cpmclktrk.online,cpmleader",
        ".com,cpmmedia.net,cpmprofitablenetwork.com,cpmrevenuegate.com,cpmroi.com,cpmspace.com,cpmstar.com,cpmterra.com",
        ",cpmtree.com,cpuzspopetlsg.space,cpvadvertise.com,cq.jqygnbfq.top,cqrksadeerzsp.space,cqvxrmwcwyvad.site,craal",
        "edcamistutu.qpon,cradaygo.com,crakbanner.com,crakmedia.com,crampleadspace.com,craniavestas.com,crannyadministe",
        "rvolunteer.com,cravesgage.cyou,crazyporn.xxx,crbetfcijuhtw.site,creativeformatsnetwork.com,crihbeppscadw.site,",
        "crispads.com,criteo.com,criteo.net,crockadilla.com,crocotube.com,crowderleavens.cyou,crowdsubsequentadmire.com",
        ",crpdadadgogyh.site,crtracklink.com,cruhtravbsuio.space,crwdcntrl.net,crypto-ads.net,cryptobeneluxbanner.care,",
        "cryptocoinsad.com,css-load.com,cstwpush.com,csxbezadpeazu.site,ctebiooadmfty.space,ctm-media.com,cuisineenvoya",
        "dvertise.com,cuoieadrraxle.space,cupis-lady-dreampartner.com,cursecrookadvantage.com,customads.co,cvyadqizofrs",
        "w.space,cylindrical-wave.com,czgacpadsspuj.space,czhavjzsxkcwa.space,czhjjavbyhuun.online,czuadkhcudkl.com,czu",
        "oszwava.in,d58ad0184a.com,da-ads.com,daadpefptsdqv.online,dacqfadozkvie.site,dadaejghzcnaq.site,dadmivxze.in,d",
        "adqcuxqdnecx.space,dads-banging-teens.com,dadsats.com,dadsimz.com,dadsoks.com,daftporn.com,dailyporn.club,daka",
        "fxgnqcpmm.website,dallavel.com,damagedbehaved.com,dantaradiohd.net,dargopastinadogsled.cyou,dark-reader.com,da",
        "rknessporn.com,date4sex.pro,datedeskads.com,datetrackservice.com,dating-amour-club.com,dating-honey-girls.com,",
        "dating-honeygirles.com,dating-masters-haven.com,dating-service.net,dating2cloud.org,datingadvicefree.com,datin",
        "gamateurs.com,datingero.com,datinggold.com,datinghoneygirls.com,datingmeetnet.com,datingprudethimble.com,datin",
        "gshall.life,datingsspads.com,datingstyle.top,datingtoday.top,datingtopgirls.com,datingtorrid.top,datingwithmee",
        ".net,datoporn.com,davincisgold.com,davipkybnzfji.site,davtwymjlx.com,davynetriumf.com,dbdhnavwbvhep.website,db",
        "occfzxlavyp.online,dcraddock.uk,dcssptrack.com,ddownload.com,de2bdad928.com,dead-put.com,deadelegantannotation",
        ".com,deadlinescreechvisit.com,deadlyfeasible.com,decknetwork.net,decorateadverb.com,dedating.online,dedicatedm",
        "edia.com,dedicatednetworks.com,deductgreedyheadroom.com,deeavouter.com,deepxtube.com,deltabravo.net,demandingo",
        "verdriveunthread.com,demdex.net,deofleadyetca.org,dependantcarefullyheavenly.com,dependentadequatemyth.com,dep",
        "leteheadphoneshoe.com,deployads.com,depriverippledecadence.com,depwwdntgavyc.website,despoteavesdropblazing.co",
        "m,detectbravecomfy.com,developermedia.com,dextralravi.cyou,dgafgadsgkjg.top,dggazqmcpmtwc.website,dgzykxavrvek",
        "j.site,dhovqdnulcadz.site,digipathmedia.com,digitaladvertisingalliance.org,digitalmediapp.com,digitalmediaworl",
        "d.tv,digitalpush.org,diningfreshmansavage.com,dinsbetrlsiqc.space,dinzfafad.in,directaclick.com,directadvert.r",
        "u,directcpmfwr.com,directnavbt.com,disableadblock.com,discoveredcowardlymadden.com,discretioningratitudeadvise",
        "dly.com,disgustingmad.com,disneyadvertising.com,disparagewardenbetrayal.com,displaycontentnetwork.com,displayn",
        "etworkcontent.com,displaynetworkprofit.com,disqusads.com,distractiontradingamass.com,dixyporn.com,djncmafidfcp",
        "m.online,dmzadtwonmlfn.online,dnavexch.com,dobbyporn.com,dollyspreadingbeer.com,domainadvertising.com,domestic",
        "atednavigationgrip.com,domipush.com,doodlesunshinecamp.com,dopaleads.com,doseofporn.com,doubleadserve.com,doub",
        "leclick-cn.net,doubleclick.com,doubleclick.net,down-paradise.com,download-file.org,download-performance.com,do",
        "wnloadboutique.com,downloaddecidedly.com,downloadoperagx.com,downloadyt.com,downstairspoppy.com,dpfwqzjkavjmb.",
        "site,dporn.com,dqhepocpcwspj.space,dradvice.in,dragstergibletshaded.com,draviteinosine.com,dreadedrevisableves",
        "t.com,dreadfullyemulateconservation.com,dreadfullynormallyinsist.com,dreadfulrulingextension.com,dreamteamaffi",
        "liates.com,droghvadutsov.space,dropdeadgems.org,dropkickmedia.com,droughtpavidsoths.cyou,drsmediaexchange.com,",
        "drtuber.com,dsadghrthysdfadwr3sdffsdaghedsa2gf.xyz,dtadnetwork.com,dtyamiadjnapm.space,dtzvavrnbzemd.space,duc",
        "hesspleasurelollipop.com,dulvxkusxdcpc.online,dunderaffiliates.com,dunhilltraveldeals.com,dupsavour.com,duregq",
        "gfvravp.space,duxlpoavm.in,dvwrcpmeeozgq.site,dvzycytrxbxav.space,dwlladiupirrc.site,dymqtojvadily.site,dynad.",
        "net,dz4ad.com,dziorhnav.in,dzubavstal.com,e7e0addf05.com,ea-tube.com,eadeuifduvres.site,eadsrv.com,eadziukwqze",
        "eb.space,earatikvavrsa.site,earbossysavvy.com,earringmavin.cyou,easy-ads.com,easy-dating.org,easyad.com,easyad",
        "sapp.com,easyporn.xxx,eavakddcdofik.site,eavesdropbogusye.com,eazyleads.com,ebannertraffic.com,ebayadservices.",
        "com,ebetoni.com,ebfclhdkkmeav.store,eboglvqdtadmz.space,ecknbonfiftad.website,eclick.vn,ecxufxavldnpd.site,ed4",
        "9f13aad.com,edcuaqnhioavg.com,editorovertimeradiation.com,edshookherhead.org,edzusegqavqik.store,eekjdpreeghad",
        ".com,effectivecpmcontent.com,effectivecpmgate.com,effectivecpmnetwork.com,effectivecpmrate.com,effectivegatecp",
        "m.com,effectiveratecpm.com,eforebuthehadsta.org,efrwdjavjkiqw.online,egadvertising.com,egotisticexcavateplywoo",
        "d.com,egyihjgwfzadc.site,ehaveagoldenbed.org,ehszycpcxrskg.website,ejadhobthckao.site,ekvhgfruqyadw.site,elado",
        "ve.com,elasticad.net,elatedcamera.com,eleavers.com,elephant-ads.com,elliottwave.com,ellipticaltrack.com,elntlr",
        "utxdavp.online,elogiado.info,elsespeakingbetraying.com,elutessheavednude.cyou,embraveanderun.com,emediate.dk,e",
        "mergencyadditions.com,eminentcleaveproduces.com,emjmeadlsjrho.online,emqyssutavqxc.online,enbpnlaiatvav.space,",
        "encoresweaved.com,engineladleworn.com,enporn.org,entertainenslave.com,eociugytvzhad.website,eoievavdtucqs.onli",
        "ne,eonsmedia.com,epbrwsksexzem.site,ephesiansavvyinto.com,epicclicks.net,epicgameads.com,epidermispushupvariab",
        "le.com,eporner.com,epxniaadddb.com,eqads.com,eradisciplinemomentary.com,ergonomicparadeupstroke.com,erhlyvjhet",
        "cad.online,ero-advertising.com,eroadvertising.com,erotikdating.com,errandchavish.cfd,eruptstarapinbetrail.cfd,",
        "erwknxujxxxbh.space,esawbynaavjmi.online,escalatenetwork.com,eskarsunleavesmokier.cyou,essayads.com,estrelabet",
        "partners.com,ethereumads.com,ethicalads.io,etjrkgaqrkfad.space,eudgjelqtadcq.website,euro4ads.de,euros4click.d",
        "e,evadereprimand.com,evadesickness.com,evasiuywadnox.space,everesttech.net,everydayporn.co,evmdamxavzsgk.websi",
        "te,evolvemediallc.com,ewesmedia.com,exactshaver.shop,excavatedefeatedshudder.com,excavatenearbywand.com,exchan",
        "ge4media.com,excitead.com,exjzuoukavbnv.space,exnesstrack.com,exoclick.com,exoticads.com,explorads.com,extensi",
        "ons-media.com,extensivetravelled.com,externalfavlink.com,extinguishbethpasta.com,extqoqykkcpmo.space,extremesc",
        "atporn.com,eyeofporn.com,eyeshadowkeynote.com,eyeviewads.com,eyqsrrwmvhavp.website,ezadblocker.com,ezdownloadp",
        "ro.info,ezvthdrknbfad.space,f30fpad7.xyz,f85fd721ad.com,fa85cadfb0.com,faceporn.com,fadegranted.com,fadexplosi",
        "on.com,fadppvjumphyh.online,fadsipz.com,fadskis.com,fadslimz.com,faithfullyreadiness.com,fallinmatches-paradis",
        "es.com,fam-ad.com,familyporner.com,fancytravelingrid.com,fantasticbet.com,fapnado.com,fapnado.xxx,fastclick.ne",
        "t,fastnativead.com,fastwavehilltop.com,fattenspopulum.com,favigirningnonly.cfd,favmaqyqgcirx.space,favorite-mi",
        "ne.com,favoriteinside.pro,favosevergery.cfd,favousshakha.cfd,favusraband.com,favy.cam,fbetlixgee.eu,fbmedia-ck",
        "l.com,fcqrzagadhlvn.online,fdadmvbteroqf.space,feadrope.net,feckfulmisaver.com,feed-ads.com,feedbackadmiralfor",
        "tnight.com,feedyourheadmag.com,fellowearnwave.com,fenacheaverage.com,ferntravelleddeduct.com,festaporno.com,fe",
        "sttube.com,feudalbetrayalcast.com,ffnetbeteeguu.site,fgvowyavmmcau.site,fiddlestructureparadise.com,file-uploa",
        "d.net,filestube.com,filingcarnivorousexclaim.com,financeads.net,financialfadinglobster.com,financialgobetweend",
        "ancer.com,findbetterresults.com,findcouponspromos.com,finddatinglocally.com,fiorewonyhade.online,fireworkadser",
        "vices.com,fireworkadservices1.com,fireworkswad.com,firstclass-download.com,firstload.de,fishermanwithavenge.co",
        "m,fixavcha.in,fixionmedia.com,fkdeavpxkjuoa.space,flagads.net,flairadscpc.com,flashadtools.com,flat-ads.com,fl",
        "aviar.com,flavidwarwolf.cfd,flavoryjehus.com,flirtclick-matches.com,flower-ads.com,fluavildemist.com,fluemenav",
        "id.qpon,fluxads.com,flyads1.com,flyingadvert.com,flymyads.com,fmsads.com,footballtradedirectory.com,footztube.",
        "com,forbesresistread.com,foremedia.net,forestysavory.qpon,forfrogadiertor.com,forgavedisciplinetolerance.com,f",
        "orgivenbeavervague.com,forkconnectinghead.com,foxpush.io,fpadserver.com,fqadcwyr.com,fractionsadly.com,fractur",
        "ebroadcastsaw.com,freakads.com,free-datings.com,freeadblockerbrowser.com,freeadultcomix.com,freecamsexposed.co",
        "m,freedomadnetwork.com,freefromads.com,freefromads.pro,freelivesex.tv,freelygreatestscammer.com,freeonlinegayp",
        "orn.com,freepopnews.skin,freeporn8.com,freepornxxx.mobi,freepublicporn.com,freesexcam365.com,freeuseporn.com,f",
        "reewebcams.com,freshforestroad.com,freshpops.net,frivol-ads.com,frnaqbeavtdvm.website,fryhzcadnypnn.online,fs-",
        "loader.com,fuck-dating.com,fuckbookdating.com,fufrbtjgadkncr.com,funnedravage.com,furiousexpansion.com,fusetra",
        "cking.com,fusionads.net,fusterquaver.com,futureads.io,fxavdiifzfjbw.website,fxgzkkroeavcj.space,fxrokhvydhadu.",
        "website,fxxavsxmuovyd.website,fyauuswqwavou.website,fytbadthewihr.store,gacurad9559.net,gadgetfortunatelysunny",
        ".com,gadgets360.com,gadlt.nl,gadmagckfy.com,gadnsohyud.com,gadsabs.com,gadsatz.com,gadslimz.com,gadspms.com,ga",
        "dspmz.com,gadssystems.com,galaxypush.com,galleries-pornstar.com,gambadudman.com,game-clicks.com,gameads.io,gam",
        "er-network.net,gamersad.com,gaming-adult.com,gamingadlt.com,gamingadult.com,gandrad.org,gapwadfcmeilz.space,ga",
        "solineimproveabroad.com,gatetotrustednetwork.com,gaveastound.com,gavedeliverknee.com,gavnuifk.xyz,gavotsallele",
        "u.com,gay4porn.com,gayadnetwork.com,gayadpros.com,gayporntube.com,gazeflavour.com,gbads.net,gbcamahjbcsff.site",
        ",gcpcvtdsdztbz.website,gcpczbniuuuoo.site,gdsavcfiitlxs.online,gearcambridge.com,geckad.com,geeunlessarcade.co",
        "m,genesismedia.com,geniad.net,geniusonclick.com,geoaddicted.net,georgianacaravanscoop.com,germaniavid.com,getf",
        "lowads.net,getjad.io,getmyads.com,getpopunder.com,gettubetv.com,gfadvpljfwagd.online,gfesbjnadvxrb.online,ggbe",
        "t-online.net,ggbetapk.com,ggbetpromo.com,ghepxcvmadltj.space,giantaffiliates.com,gibedencave.cfd,giftornado.co",
        "m,girls-romancehaven.com,gitads.dev,gjkrwpafad.com,gkvfndkdzavrv.online,glade-static.online,gladlydotselfevide",
        "nt.com,gladlythattoady.com,glaoxxxppcvwz.site,glkadcvafpqhe.online,global-adblocker.com,globaladblocker.com,gl",
        "obaladblocker.net,globaladmedia.com,globaladmedia.net,globaladsales.com,globaladv.net,globtube.com,gloporn.com",
        ",glsfreeads.com,gmads.net,gmmads.com,gmpavcpqcdhxu.space,gnawpush.com,gnopfoaddfxto.website,gnxnfkdgadbkf.onli",
        "ne,go-g3t-push.net,go2bdmbet.com,go2media.org,goads.pro,goadserver.com,goclicknext.com,godaddy.com,godlesscour",
        "ieradmirable.com,gofdtdxdtzkav.site,good-ads-online.com,goodadvert.ru,googleads-cn.com,googleadservices-cn.com",
        ",googleadservices.com,googleadsserving.cn,googlesyndication.com,googlevads-cn.com,gotibetho.pro,gotrackier.com",
        ",gotrackier.io,gourmetads.com,gpsecureads.com,gqavlsyfgmkeb.online,graciamediaweb.com,gradualtelevision.com,gr",
        "aduateamazingly.com,graduateangeles.com,graduatedfreezing.com,graduatedoutside.com,graduationpaltryherbal.com,",
        "gradwcntoprvn.online,granddadfindsponderous.com,grasshoppergainfulexcavator.com,gravegrandmother.com,gravelsem",
        "esterflourish.com,gravelspeorianstime.cyou,gravelyjovialelemental.com,gravenesswomanlesszestfully.com,gravenpi",
        "cked.com,graveuniversalapologies.com,gravicjibbs.com,gravitationtaxpayer.com,greenadblocker.com,greenads.org,g",
        "regalemedials.qpon,grievecamouflage.com,grimytradition.pro,gripdownload.co,gripping-bread.com,groovinads.com,g",
        "ross-ladder.pro,grvmedia.com,guacosravaka.com,guadscfwaweae.site,gunzblazingpromo.com,gupload.xyz,guvrwavupliw",
        ".in,gvcaffiliates.com,gwalaissexruj.site,gwdkavydmyuf.com,gwhbladbytxd.com,gyoqtcogunavk.site,h12-media.com,ha",
        "adqzvaencuq.online,hadronid.net,hadsanz.com,hadsecz.com,hadsokz.com,haffnetworkmm.com,hairdressergraduate.com,",
        "halogennetwork.com,harborjavgg124.fun,hardbritlads.com,harrenmedianetwork.com,hasaeblmtcadr.website,hattedrave",
        "d.com,hauledquaverguardian.com,haveameet.com,havefiliate.com,havelberme.qpon,havenclick.com,havenyl.xyz,haverd",
        "entata.cfd,havetohave.com,havingunderwear.com,havpcbrpaftjv.online,havstpqgejqbw.site,hazelstrictlyheadless.co",
        "m,hcjzvzvavb.com,hddrfyoifiuad.website,hdkbxadnbxvua.online,hdopxenavimwu.online,hdporn24.org,hdpornfree.xxx,h",
        "dpornmax.com,hdpornphotos.com,hdtube.porn,hduolaveybzmo.site,headacheaim.com,headbidder.net,headerbidding.ai,h",
        "eaderlift.com,headlinesplus.com,headphonedilution.com,headquartersfallclassify.com,healthtrader.com,heardspade",
        "dime.com,heateradvisoraplenty.com,heathertravelledpast.com,heathsleavenlinaloe.cfd,heavenfull.com,heavengabbyk",
        "atcina.cyou,heavenly-holiday.com,heavenmedia.com,heavenomitzoom.com,heavenspecificallygait.com,heavenstyler.cy",
        "ou,heavepacepallor.com,heavilyaflareknifes.qpon,heimalesssinpad.com,heldsadness.com,heliumads.com,hellporno.co",
        "m,helpfulavenue.com,herexxxtube.com,hetadinh.com,hetlmedia.com,hfblxccnycadt.website,hhbetevelthyg.site,hicpm1",
        "0.com,highcpmcreativeformat.com,highcpmgate.com,highcpmrevenuegate.com,highcpmrevenuenetwork.com,highperforman",
        "cecpm.com,highperformancecpmgate.com,highperformancecpmnetwork.com,highprofitnetwork.com,highratecpm.com,highr",
        "evenuecpm.com,highrevenuecpmnetwork.com,highrevenuenetwork.com,highwaycpmrevenue.com,hilltopads.com,hilltopads",
        ".net,hiltonbett.com,himediads.com,hiprofitnetworks.com,hitcpm.com,hityyzyxbadim.online,hjvewzbtkbetn.online,hm",
        "adkixrebk.in,hmdypadvcolcw.space,hnbanvxtkavun.site,hnntube.com,hntixkyrwyiav.website,hnzjwtcajadzi.space,hoad",
        "aphagoar.net,hoasvvtyavmbn.store,hockeyhavoc.com,hodlwvavzwt.com,hodnjqyavomuj.site,hollow-population.pro,holy",
        "familyradio.org,home-xxx-videos.com,homejavlive.com,homemade.xxx,honestbroadcasting.com,honestinfuriatemedia.c",
        "om,hooliganmedia.com,hopcleftirradiate.com,horse-racing-affiliate-program.co.uk,hostadsmarkets.com,hostave.net",
        ",hostave2.net,hostave3.net,hostave4.net,hot-bbw-tube.com,hot-sex-tube.com,hotadultvids.com,housejomadkc.com,ho",
        "utoucavus.cfd,hpadjptcyyafh.space,hpdipavuplwoe.website,hqporn.su,hqpornstream.com,hraayzvvjlead.site,hrlvadtk",
        "fqils.space,hrrlwaavrttks.online,hryaavcnugcpp.site,hsefhnuodtav.com,html-load.cc,html-load.com,huavdntfzhprm.",
        "online,hueads.com,hueadsxml.com,huluads.info,hunterlead.com,hurlmedia.design,hustlereadcoffeehouse.com,hvwoeav",
        "jjemhq.site,hwepopmhhsz.com,hwpnocpctu.com,hyenadata.com,hyhavaflmeguk.site,hyperbanner.net,hyperpromote.com,i",
        "adbsntna.com,iadnsamqheemb.space,iaeaadfleusbk.site,iajlxlnkkocav.space,ianoubcdoavip.website,iasbetaffiliates",
        ".com,iavlxljzdclmz.space,iavszerhxigkh.space,ibannerexchange.com,ibrapush.com,iceporn.com,iclickcdn.com,icypor",
        "no.com,idealmedia.io,idevaffiliate.com,idhoyoadekuzf.website,idreammedia.com,ieecfjcwadvrz.online,ieladhfhoicl",
        "v.online,ifadnfhgaozlo.store,igoraodadkwlk.space,iiasyavgjqwat.site,iionads.com,ijiyhavkjzvi.com,ijpopvca.com,",
        "ikqyazeadzqvo.space,ileuadlrdnpow.space,ilxilmadwxfah.space,imageadvantage.net,imbuemadeup.com,imediaaudiences",
        ".com,img-load.com,imgadult.com,imgkdetaddxre.space,imiclick.org,immediateequivalentstunning.com,immediatelydog",
        "head.com,impact-ad.jp,impactradius-go.com,impactradius.com,imperfect-dead.pro,imprasavariac.com,impressexaltsc",
        "ulptor.com,imyanmarads.com,in-appadvertising.com,in-page-push.com,in-page-push.net,inadequatebuoythin.com,inad",
        "equateconsolation.com,inadmissibleeyewitness.com,indiadesire.com,indianbannerexchange.com,indieclick.com,indig",
        "estiontreatmentgraduated.com,indlyment-stuador.com,indofad.com,indoleads.com,indyadvertising.com,inexpedientla",
        "ds.com,infectiousmedia.com,infinite-ads.com,infinityads.com,influads.com,infobetting.com,informedcleave.com,in",
        "heritedgeneralrailroad.com,inhousead.net,initiallybrigadebookstore.com,inkingabave.cfd,inktad.com,inliersilica",
        "mvalyl.cyou,inpage-push.net,inpagepush.com,inporn.com,inscribenaiveavoid.com,insightadz.com,insightspendadvanc",
        "ement.com,insitepromotion.com,inskinad.com,inskinmedia.com,instantbannercreator.com,instantpaydaynetwork.com,i",
        "nsteadempty.com,insteadjell.com,insteadmodification.com,instinctradiatorrook.com,insurads.com,intellibanners.c",
        "om,intelligenceadx.com,intellipopup.com,interactiveads.ai,interadv.net,interclick.com,intermediaryquay.com,int",
        "ermediategillsevent.com,internationalsexguide.nl,interworksmedia.co.kr,intimidatingsideway.com,intopicmedia.co",
        "m,intporn.com,intrapromotion.com,intravert.co,invadedisheartentrail.com,invadermonks.com,inviziads.com,inxxx.c",
        "om,ioadserve.com,iohgttbethmaj.online,iohsoavvddvmo.store,iomaduexiqtho.space,iononetravoy.com,ip-adress.com,i",
        "pornia.com,ipromote.com,ipvhqdchweavs.site,irbnydavspxxt.space,irfgithdpoadi.site,ironendeavour.com,irontube.n",
        "et,irradiatestartle.com,iseawave.com,itsmillionlead.com,iuzvusqotkhad.online,ivstracker.net,iyqtpifqeoavq.site",
        ",iyrrtosjcravm.online,izavjtqqznucm.site,jaavnacsdw.com,jacaminfley.com,jacamitympana.qpon,jackpotave.com,jack",
        "potcitycasino.com,jadcenter.com,jaded-actor.com,jaded-wing.com,jadedsingle.com,jadedtwist.com,jadehlbswdlqw.on",
        "line,jadeinvitelawsuit.com,jadjkuofzwad.com,jadkuwwtyp.in,jads.cc,jads.co,jadwidal.com,jagisjbavpwdj.space,jam",
        "anetwork.com,jangonetwork.com,jarvispopsu.com,jav-bukkake.net,jav.sb,java8.xyz,javascriptcdnlive.com,javbucks.",
        "com,javdock.com,javenglish.net,javfor.tv,javgg.eu,javgg.me,javhd-trk.com,javhd.com,javhdporn.live,javhub.net,j",
        "avideo.net,javkagiitfkuw.site,javlibrary.com,javmg.com,javmix.tv,javmost.com,javpornclub.com,javpwyyvcoorg.web",
        "site,javslon.com,javxxx.me,javynow.com,javyvkzwwsf.com,jbckwavvvsugv.space,jdfhwxboadwvg.online,jdrfaqinvrcpm.",
        "space,jeetyetmedia.com,jegiopad.top,jegjuitgvavuz.space,jespwdvojaavj.online,jetavbemsmnur.space,jewellerybroa",
        "dlyisabella.com,jewishadgroup.com,jewishcontentnetwork.com,jfoazkavnozuo.online,jghadikzlvvnr.space,jiawavgaks",
        "dbq.store,jicamasmiscrop.qpon,jilioadvrvpwm.site,jitterendeavour.com,jjqlzrxadgi.com,jmadyknjxonua.website,jmk",
        "ladoihjrsa.space,jnqbkpsymoads.site,jolrfeqxyavvj.site,jovialcadence.com,joxaviri.com,jpadxrydoiorz.online,jpa",
        "vgtzkbjpwi.site,jpbphraoemadf.online,jpkwoyveladnt.online,jradumbwgmggr.space,jsadapi.com,jsfeedadsget.com,jti",
        "avpaxso.in,jtxlzsuxadaxe.site,jubileebread.com,jubnaadserve.com,juddersexcusedligure.qpon,juiceadv.com,juicyad",
        "s.com,juicyads.me,jumboaffiliates.com,junbi-tracker.com,junkyadexchange.com,junmediadclikrmdi.com,justfullporn",
        ".net,justtube.net,jwrtoravx.com,jxavjloeqgrbz.space,jxcuocswkavpi.site,jxvhcvhdeaadf.site,jyjioxtadobac.websit",
        "e,jyusupfxadswy.site,jzhbethwfhnlq.space,kaadqpoorrhsy.site,kadam.ru,kadime.cc,kadmkyie.com,kagocvncavfqi.stor",
        "e,kappavowal.com,kavanga.ru,kavblcvs.com,kavcapfpjbhpn.space,kavekduhupvky.space,kavtqkbsyrpzt.site,kawai-jav.",
        "com,kbcjugyneadmk.online,kbwfirbugcpmk.site,kcpcmdepbvyjq.website,kdlgpcradvcmi.space,kebbyizzyavicula.cyou,kf",
        "gxuiyutbwav.space,kfpuejiakadbw.site,khiclickrkafw.online,kidsinstancesfavor.com,kinkadservercdn.com,kiomthavs",
        "ruuk.online,kjetdxtcammhc.com,kjkavoimtxart.online,kjmrldarxadmw.online,kkczadzgl.com,kllastroad.com,klvadvvrb",
        "usee.website,kneestolerableadulthood.com,kniravkrmejus.online,knwjmjujhadoo.site,koalaglade.com,kohadfzjolyfy.",
        "website,konraddiscrimination.com,konradissuesimperial.com,konradsheriff.com,konradterriblesect.com,kotqbebetyo",
        "eed.com,kpopsingers.com,krudavmbgnwrc.website,krzkasckoqvad.site,ktiebyesqavuz.online,kudtapgad.in,kunvertads.",
        "com,kvcmgvhnbadzq.online,kvjthavimymgk.online,kvsadman.com,kvylquisaduxr.store,kwgavfflgjpop.site,kwintravetoe",
        "r.cyou,kxgadtlettueg.website,kzzbdsattavdw.space,labadena.com,labisexecute.com,ladbrokesaffiliates.com.au,lade",
        "ykyxsaosi.site,ladgritperformed.com,ladhlbetxnjlm.space,ladiesmatchesfinder.com,ladsabs.com,ladsans.com,ladsec",
        "z.com,ladsips.com,ladsipz.com,ladskiz.com,ladyboygoo.com,ladysabryna-xxiqlkt.work,lalapush.com,lampshadescreen",
        ".com,landscaperedressave.com,lanxtkacpucpc.site,laptopprivacyado.com,lavatorydownybasket.com,lavatoryhitschool",
        "master.com,lavender64369.com,lavenderzenith.com,lavishboot.com,lavnkios.in,layer-ad.org,layingframingcomrade.c",
        "om,lblscpmfxuzqq.space,lbyarvladuyzy.space,lcpavtrwvavnq.website,ldqkyedvfepop.online,leadadvert.info,leadfaml",
        "y.com,leadgainz.com,leadimmensely.com,leading-back.com,leadmediapartners.com,leadnote.me,leadsecnow.com,leadsl",
        "eap.net,leadzu.com,leaveundo.com,leavingaileen.com,leavingneedyengaged.com,leithstaving.cfd,lemonadds.com,leon",
        "betvouum.com,leopardfavorite.com,leplvcbadrmgb.space,letsporn.com,lfavcwgydohba.store,lfstmedia.com,lgsmartad.",
        "com,lhkmedia.in,lhvavsdsnhxgz.website,liadm.com,libertystmedia.com,lifeporn.net,lifepromo.biz,lightmeadowwalk.",
        "com,likeads.com,likelihoodcheerleaderdefender.com,likexxxtube.com,limetreeloadherself.com,linkadvdirect.com,li",
        "nkonclick.com,linksex.club,linuxtracker.org,litavvcc.in,literallycircumscribetome.com,litterarenadisembroildis",
        "embroil.com,liveadexchanger.com,liveadoptimizer.com,livecam.com,livecams.com,livepromotools.com,livesexasian.c",
        "om,livesexbar.com,livesportmedia.eu,liveuniversenetwork.com,livexxx.me,livyncxvleiad.website,lkadhbifkzvtt.onl",
        "ine,lkqagavhhaqrp.space,lkseavjkzupfw.space,llqmbetbgjhyt.website,lmavfuplmvhbq.space,lmueckvzozqav.website,lo",
        "ad-res-static.lol,load-static-res.xyz,loadedbummer.com,loadercdn.com,loadingscripts.com,loadtime.org,lobefinti",
        "nklecleaved.cyou,loboclick.com,localedgemedia.com,locksadvancedrelease.com,lodgeastronautadore.com,logsaddicti",
        "on.com,lokiporn.com,loohaiturigavik.net,loppetickierbavoso.cyou,lotadversaryvolatile.com,lotongmamonadowses.qp",
        "on,lotritereavows.cfd,lotteryaffiliates.com,loupesstavedtriene.cyou,love-banner.com,love4porn.com,lovecam.com.",
        "br,loveflirt-haven.com,lpmediastorage.com,lrvpquoavttir.site,lscsexsnjacwr.site,ltnavwfpsffpi.space,lucidmedia",
        ".com,luckyads.pro,luckyforbet.com,luggedzabeta.com,lumbersavians.cyou,lunacyumpireunleave.cfd,lunatictoxicambi",
        "guity.com,lunlyqvavjkfc.website,lustytube.net,luxadv.com,luxupadva.com,lvwytobyemady.online,lwadm.com,lwgadm.c",
        "om,lwuntajpwadku.online,lwxmapxavophf.online,lxadqdidnsnzd.online,lxoukuwkzvada.online,lyconery-readset.com,lz",
        "udmwytadqrg.site,m2track.co,mad-consist.com,madadsmedia.com,madamconducted.com,madcheddar.net,madcpms.com,madd",
        "enwiped.com,madeupice.com,madeupregular.pro,madeupteamspupil.com,madeupvigourpoll.com,madmanbiological.com,mad",
        "nesscoupon.com,madqwcbhobeca.space,madratesforall.com,madrogueindulge.com,madsabs.com,madsans.com,madsecs.com,",
        "madserving.com,madslimz.com,madsone.com,madspmz.com,madurird.com,mafroad.com,magicads.nl,magnetisemedia.com,ma",
        "ideneyeshadow.com,mainadcenter.com,mainadv.com,majorads.site,maleficscamper.qpon,malisonrepope.com,mangadex.or",
        "g,mangadistrict.com,mangarawad.biz,mangoads.net,manipulativeslaves.com,manorflounderheady.com,mantisadnetwork.",
        "com,manureads.com,manysex.com,manysex.tube,mappilaaverrer.com,marimedia.com,marinadelifestyle.com,marinadeworr",
        "iesdurable.com,marketjavgg124.fun,markswebcams.com,marmaladestrikingcolourless.com,marrergraves.com,marsads.co",
        "m,martpleadescended.com,masqueradeegoismcurtain.com,masqueradejournalism.com,masqueradepatheticburden.com,math",
        "ads.com,mathtag.com,maturexxx.vip,mavq.net,mavrtracktor.com,maxiadv.com,maxonclick.com,maxvaluead.com,mbidadm.",
        "com,mbsspads.com,mcbadljmbhdni.online,mcepadnkovcy.in,mdroadmmbxrlj.space,me4track.com,meadow-assets.click,mea",
        "dow-static.xyz,media-412.com,media-amazon.com,media-click.ru,media-general.com,media-sapiens.com,media.net,med",
        "ia6degrees.com,mediaalpha.com,mediacandy.ai,mediacpm.pl,mediad2.jp,mediaf.media,mediaffiliation.com,mediaforce",
        ".com,mediaforge.com,mediafuse.com,mediahell.world,mediamixer.co.kr,mediapalmtree.com,mediaplex.com,mediasama.c",
        "om,mediatarget.com,mediatemple.net,mediative.ca,mediative.com,mediatradecraft.com,mediatrias.com,mediaupdate.c",
        "o.za,mediavine.com,mediaxchange.co,medleyads.com,medyanetads.com,meetradar.com,meetsexygirls.org,megaad.nz,meg",
        "adeliveryn.com,megaffiliates.com,megatube.xxx,mepuisgsxadha.site,mercenaryslaveryplays.com,mergedlava.com,mess",
        "yadvance.com,metavertising.com,mfadsrvr.com,mfeibqadbbzaz.site,mfyavsmiha.com,mgladthereisso.com,mgtckhufqjlad",
        ".store,mharavczumra.in,microad.jp,microad.net,microadinc.com,midas-network.com,midnighttraditionally.com,midpo",
        "pedge.com,migrationoctavian.com,mihmadovjkpi.in,mileporn.com,milftube.mobi,millennialmedia.com,mimosaavior.top",
        ",minklemonade.com,minutemedia-prebid.com,minutemediaservices.com,miraclethisaddicted.com,mixadvert.com,mk-ads.",
        "com,mkadujfywhxis.website,mkatakavcaxm.in,mktbqkzdzadrg.site,mladedda.net,mlnadvertising.com,mmadsgadget.com,m",
        "mdcokadaklfg.website,mnetads.com,mng-ads.com,moatads.com,mobileoffers-dld-download.com,mobiletracking.ru,mobip",
        "romote.com,mochicadermis.cyou,modelsgonebad.com,modulepush.com,mojoaffiliates.com,momentummedia.com.au,monadpl",
        "ug.com,monetixads.com,monthsexpressive.com,moonads.net,moonrocketaffiliates.com,mootermedia.com,mortypush.com,",
        "motaflavine.com,motherlyglad.com,moustacheoverloadwar.com,movad.de,movad.net,movcpm.com,moviead55.ru,moviemedi",
        "ahub.com,moviesex.org,mpanythathaveresultet.info,mpocadpbnevwa.site,mpsuadv.ru,mqdownload.com,mqedledjjjdad.we",
        "bsite,mqqhfyfavftyk.com,mrgrekeroad.com,mrporngeek.com,msads.net,msgpbwbfradny.website,mtdutavlkpzzz.site,mtwa",
        "taveihajz.space,muggurscamden.click,multiwall-ads.shop,muniadazzled.qpon,mutenessdollyheadlong.com,mvmrcygtoad",
        "gz.online,mxhkadwedflsr.online,mxpopad.com,my-promo7.com,myadcash.com,myadultimpressions.com,myaffiliates.com,",
        "myaudioads.com,myavlive.com,mybestcpm.com,mybetterdatings.com,mybrowseraddon.com,mycams.com,mycasinoaccounts.c",
        "om,mydatingstheladys-club.com,mydatingswomanonlines.com,mydatinguniverse.com,myeasetrack.com,myemailtracking.c",
        "om,mykocam.com,mypopads.com,myprecisionads.com,myreadingmanga.info,mysexgames.com,mysexjourney.com,mysticalado",
        "ptionocean.com,mythad.com,mywebclick.net,mywovcjadrtcb.online,mzafanowhdcpc.space,nababroadbirch.com,nadese.xy",
        "z,nads.io,nadwkwzfjnxzw.site,naiadexports.com,nailadversary.com,naivewidespreadbody.com,nameads.com,nanoadexch",
        "ange.com,narrowveneeringsavoury.com,nasbhavuovwzi.space,nasssmedia.com,nativclick.com,native-adserver.com,nati",
        "veadmatch.com,nativeads.com,nativeadsfeed.com,navaidaosmic.top,navajospoule.com,navalrememberedcarve.com,navie",
        "mytotgbg.online,navigatingnautical.xyz,navtlkdihvagi.online,navwmaakbo.com,navxhefspincz.space,navyhalfclinica",
        "l.com,navyrecentuproot.com,navyrecognition.com,navzkokpufm.in,nawpush.com,naxadrug.com,nbadgnaqqqrxx.website,n",
        "bdysfxadsfji.website,ndfhqtezbkavl.online,ndhadbeensoa.org,ndqpopziznuuq.space,neads.delivery,neatmadeiraairbo",
        "rne.com,necessarilymosquitoheadphones.com,neglectsquad.com,neladyhe.com,neptuntrack.com,netavenir.com,netcpms.",
        "com,netmedia.hu,netsolads.com,networkad.net,networkhubcontrol.com,networking444.net,networkmanag.com,networks1",
        "000.net,neudesicmediagroup.com,newjulads.com,newmayads.com,newoctads.com,newormedia.com,newosnotadprx.space,ne",
        "ws-headlines.co,newsadsppush.com,newsatads.com,newscadence.com,newsmaxfeednetwork.com,newsunads.com,newtab-med",
        "ia.com,newthuads.com,newtueads.com,nextclick.pl,nextmillmedia.com,nexxxt.biz,ngadverts.com,nglmedia.com,ngunme",
        "umeklav.online,nguswfavipgqm.website,ngvyktsuuyadu.online,nhatsadwafljs.site,nhbavbqogc.com,nhiaoadcolahl.webs",
        "ite,nhzylwcpmcchb.space,nikkiexxxads.com,nkyavipvkfxgo.online,noaaduliugvul.online,noavoahevugrib.com,nobblesp",
        "ush.cyou,nomadsbrand.com,nomadsfit.com,noncommittalsupposedlybroadcast.com,nonsenseaggravation.com,noosebangha",
        "d.com,norfolkbroads.com,northdownload.com,notifpushnext.net,novadune.com,novemberadventures.name,novibet.partn",
        "ers,nowfavourable.com,npcad.com,nrowumdcamr.in,nsfwadds.com,nsmartad.com,ntepadkozezji.online,ntkgkyalavlcs.sp",
        "ace,ntvpwpush.com,nutentertainintermediate.com,nutterguava.cyou,nvfigpopnczvp.online,nwnxyivttoaad.site,nxxxpm",
        "yejmudz.space,nylons-porno.com,nylonsporno.com,nythingamglad.com,nzavmqooblmpl.site,nzme-ads.co.nz,nznoxxx.com",
        ",nzwecadkryhup.site,oadehibut.xyz,oagbadcuwzruq.site,oavgofyxwyryj.online,oavqowdsenguy.online,oavsgdmtbealk.w",
        "ebsite,oavurognaurd.net,oavyctdvbrlpz.space,obliquescallionexcavator.com,oboxads.com,obsoletepaddlevehicular.c",
        "om,obstinacysexist.com,ocadnxsghpseo.space,ocoopdcpmqxpy.site,octopuspop.com,odnwadzre.in,oeubadryzvwyk.space,",
        "ofbdaktxxxldn.space,ofcamerupta.com,offergate-games-download1.com,offerstrack.net,ogavjmydvhvqe.com,oglalavood",
        "oo.world,ogzavfebqsdmy.online,ohlladamnak.in,ohmycams.com,oilcampshift.qpon,oirdwcadrzrqj.online,oivpkegadjuqc",
        ".site,oizfzrjavugyq.space,ojtuqqiohqadx.online,ojxtxmaivsavq.site,okadgpkptxakv.website,okrqmavp.com,oktavvavg",
        ".in,olayomad.com,oldgamesdownload.com,omegaadblock.net,omegadblocker.com,omni-ads.com,omtrdc.net,onad.eu,onals",
        "killsexkce.org,onclckpop.com,onclickads.net,onclickalgo.com,onclickclear.com,onclickgenius.com,onclickmax.com,",
        "onclickmega.com,onclickperformance.com,onclickprediction.com,onclickpredictiv.com,onclickpulse.com,onclicksupe",
        "r.com,onclicktop.com,onhercam.com,online-adnetwork.com,onlineporno.fun,onlinepromogift.com,onlinepromousa.com,",
        "onlyalad.net,onnetwork.tv,onsetblouselampshade.com,ontnkqwlnimav.online,onxgaladrj.in,ooadrhsqiofhv.space,oohk",
        "badeeaaem.site,ooozxxadrsnpo.space,oouutjpavsxfv.site,openadserving.com,openadultdirectory.com,openrtb-banner.",
        "com,openx.net,oplmbxdyndadi.website,opptmzpops.com,opsqbrqhqcamb.online,optad360.io,optad360.net,optiads.org,o",
        "ptidownloader.com,oqmiadfkmlcwz.website,orangeads.fr,orangeclickmedia.com,orfrsexlxozvc.site,orgyxxxhub.com,or",
        "igintube.com,orqlzuoqgad.com,osahsadctgzfa.site,osamadada.com,oscineabettal.cfd,oshacampelis.com,osiaffiliate.",
        "com,ospreymedialp.com,otaavlxnmqwyi.online,otoadom.com,ottadvisors.com,ottavvrdokror.website,ouiiadyzaieqx.spa",
        "ce,ourtecads.com,outbrain.com,outbrain.net,outrageoussquad.com,outrightphiladelphia.com,outstandingfrailabroad",
        ".com,overloadenjoy.com,overnightabroadcraftsman.com,ovgadhtcyvak.in,ovpniyavjvob.com,oxado.com,oxtracking.com,",
        "oxzmasxuadysh.space,ozadzoddxxxqs.online,ozonemedia.com,p-advg.com,packlenseavesdrop.com,paddleweekendnudge.co",
        "m,padgntohmxigt.site,padgzdbyelrca.online,padinventoryvile.com,padsabs.com,padsans.com,padsatz.com,padsims.com",
        ",padsimz.com,padskis.com,padslims.com,padspms.com,pakbanners.com,pantrycornyunload.com,pantyhosepornstars.com,",
        "papayads.net,paraderascaldefinitely.com,paradise8.com,paradizeconstruction.com,paradocs.ru,parlouroutlayfavor.",
        "com,partner-ads.com,partycasino.com,pas-rahav.com,patesihobbet.com,patwyavweuxzx.space,paulgravitynumeral.com,",
        "pavagedisawa.cfd,paviliongiddy.com,paviortummels.com,pavljbnheqajx.space,pavramzdvprsi.online,paxmedia.net,pay",
        "-click.ru,payloadz.com,payvclick.com,pblcpush.com,pboadsgyalldd.site,pc-ads.com,pcpcg47ycvwxzyuq8fkwu.cfd,pcyq",
        "advwrqsmc.online,peacefulmeadowwalk.com,peadeplete.com,peafzfaghradr.com,peakclick.com,peavyegeria.cyou,pebadi",
        "petto.cfd,pebblemedia.be,pedxbqnadnebr.space,peltishpushful.com,pennynetwork.com,pepperjamnetwork.com,perfectt",
        "oolmedia.com,performanceadexchange.com,performanceonclick.com,performancetrustednetwork.com,perimeteravertendl",
        "ess.com,perimeterravenousdelusional.com,perkcanada.com,perkyabroad.pro,peugyiwmgvadr.space,peukasrsihavele.org",
        ",pgammedia.com,pgmediaserve.com,pgzmbkdavgilh.online,phmtcfsfjtcpm.space,phrfushpadbco.website,phuakravmo.in,p",
        "ilotstracking.com,pip-pip-pop.com,pipaffiliates.com,pitdjtfxcamyx.site,pityneedsdads.com,pixellitomedia.com,pj",
        "yibsavpisod.space,plajwaduivmqg.space,planetwin365affiliate.com,playrainbet.com,pleasedmad.com,pleasetrack.com",
        ",plenomedia.com,pllsadrp.net,plowmenepoptic.com,ployimmediately.com,plucheavennel.com,plungeflavour.com,pnspih",
        "rxaduyk.site,poavoabe.net,poetizesuaver.cfd,pointclicktrack.com,polishleadennineteen.com,polisnetwork.io,pollu",
        "xnetwork.com,polyad.net,poorlyadjustvisual.com,pop5sjhspear.com,popads.media,popads.net,popander.com,popblocke",
        "rgold.com,popblockergold.info,popbounty.com,popbutler.com,popcash.net,popcornvod.com,popcpm.com,popdemission.c",
        "om,popedomwresterfingian.qpon,poperblocker.com,popexhale.com,popgoldblocker.info,pophandler.net,popiemarsala.c",
        "fd,popinacelektrameered.cyou,popjanuary.shop,poplardealtcarrying.com,poplartenth.com,popmansion.com,popmog.com",
        ",popmonetizer.com,popmonetizer.net,popmyads.com,poppastabour.com,popped.biz,poppyrimpace.com,poppysol.com,popr",
        "tb.com,popsads.net,popteen.pro,popular-tea.com,popularslant.com,populationeverydaygale.com,populumframmit.com,",
        "popunder.bid,popunderjs.com,popunderoctober.store,popunderstar.com,popunderz.com,popupblockergold.com,popupblo",
        "ckermax.app,popupclick.ru,popupdomination.com,popupgoldblocker.net,popupsblocker.org,popviking.com,popxperts.c",
        "om,popxyz.com,porchadvertise.com,porn-star.com,porn.com,porn.fr,porn300.com,porn99.net,pornalin.com,pornattitu",
        "de.com,pornav.net,pornconversions.com,porndeals.com,porndig.com,porndoe.com,porndroids.com,porndude.link,porne",
        "arn.com,pornerbros.com,pornforrelax.com,porngals4.com,porngames.adult,porngames.tv,porngamespass.com,pornglee.",
        "com,porngo.tube,porngray.com,pornhat.com,pornhb.me,pornicom.com,pornid.xxx,pornimg.xyz,pornintellect.com,pornj",
        ".com,pornjam.com,pornjv.com,pornkings.com,pornktube.tv,pornleep.com,pornmix.org,porno666.com,pornoegg.com,porn",
        "ogramxxx.com,pornohans.net,pornoheat.com,pornone.com,pornpapa.com,pornpics.com,pornpics.de,pornpoppy.com,pornr",
        "abbit.com,pornsex.rocks,pornstargold.com,porntez.com,porntrack.com,porntry.com,pornv.xxx,pornve.com,pornvids.f",
        "r,pornxbox.com,pornxp.com,pornxp.net,pornyhd.com,positivenetwork.pro,postaffiliatepro.com,powerad.ai,poweradbl",
        "ocker.com,powferads.com,ppuqlcyadikqe.website,pqnnvegqkyadt.space,preciousadequate.com,precisionclick.com,prec",
        "onyklavernbombax.cfd,predestinationicebergpredicament.com,predictivadnetwork.com,predictivadvertising.com,pred",
        "ominantleadingsprout.com,prefixbesidesbetray.com,premiumads.net,presscllad.com,presselhavedartoic.cfd,prestige",
        "casino.com,pretrackings.com,prettyroad.biz,prettytube.net,preventtributebroad.com,prickly-tradition.com,prideh",
        "ickintimidating.com,primaryads.com,prizeadultery.com,prmtracking.com,pro-adblocker.com,pro-advert.de,proadscdn",
        ".com,processedlizardadjoining.com,producedpigsforehead.com,profitablecpmgate.com,profitablecpmrate.com,profita",
        "blecpmratenetwork.com,profitabledisplaynetwork.com,profitablegatecpm.com,profitableratecpmnetwork.com,profitab",
        "letrustednetwork.com,programmesheadstoneconsult.com,projectjav.com,promo-bc.com,promo-reklama.ru,promo4partner",
        "s.com,promobenef.com,promocionesweb.com,promokrot.com,promoteconditionerkeyboard.com,promotools.biz,promowebst",
        "ar.com,propadsviews.com,propeller-tracking.com,propellerads.com,propellerads.tech,propellerclick.com,propeller",
        "pops.com,propositionvowelladen.com,proprietordauntaddition.com,prospershave.com,protectioncleave.com,proudradi",
        "sh.com,prplad.com,prplads.com,prpops.com,prtrackings.com,prwave.info,pryzmads.com,pseavibrsijgx.site,psjadixqz",
        "liqg.online,psxzadkhsaevp.space,psychologylosttraveling.com,ptwebcams.com,pubadx.one,pubfuture-ad.com,pubjzoxa",
        "v.com,publicidad.net,publpush.com,pubmatic.com,puffnetwork.com,puinnjsexnlbo.space,pulpyads.com,pulseadnetwork",
        ".com,pulseonclick.com,pumavobis.cyou,pumpbead.com,punctuationbandycontradict.com,puporn.com,pureadexchange.com",
        ",purpleads.io,push-notifications.top,push-sdk.com,push-sdk.net,push-sense.com,push.house,push1000.com,push1000",
        ".top,push1001.com,push1005.com,pushabl.com,pushadvert.bid,pushaffiliate.net,pushatomic.com,pushbizapi.com,push",
        "campaign.club,pushcentric.com,pushclk.com,pushdom.co,pushdomains.biz,pushelp.pro,pushflow.net,pushflow.org,pus",
        "himg.com,pushingtiling.com,pushinpage.com,pushking.net,pushlapush.com,pushmaster-in.xyz,pushmenews.com,pushmin",
        "e.com,pushmobilenews.com,pushnami.com,pushnews.org,pushno.com,pushnotice.xyz,pushpong.net,pushprofit.net,pushr",
        "ase.com,pushserve.xyz,pushtorm.net,pushub.net,pushwelcome.com,pushy.ai,pushyimmaculateinestimable.com,pvdnugjw",
        "yrkad.website,pvhlewexcpcqq.online,pvuznrtadztxu.space,pxlcrykadtsby.site,pxuxlswadjlfu.space,pydsexwwnwiuj.sp",
        "ace,pzmbetafgealw.online,q1media.com,qaeadnlcctjvh.online,qbiavgzkhyqji.space,qbkofavetnrgr.site,qcxavdkqorwri",
        ".space,qdemrnocqeadj.site,qetsjadxfjtzq.online,qgxbluhsgad.com,qhbetzsrzzslg.site,qhngmav.com,qjwmihzmadpsm.sp",
        "ace,qkosavpevkjzc.site,qoclick.com,qoqqgkzavy.com,qpadmalav.in,qpnicavmjtytk.site,qpnippagdkadi.online,qqdrbmd",
        "bcavgm.space,qsgjcqiqhaiad.online,qtnobmxbgavob.space,quackgraduation.com,quadrinhoseroticos.net,qualiclicks.c",
        "om,qualitydating.top,quanta-wave.com,quantserve.com,quantumadblocker.com,quantumads.com,quickads.net,quickload",
        "binpack.click,quilladot.org,qunskbadgzcdx.site,quwtzyadstt.com,qwxolavxykodb.site,qxdownload.com,qxgloyadavqvf",
        ".space,qxpxrbettkskb.space,qyeavpkdevzzc.site,qyexjolwpv6wvgbet5wp.cfd,rabbetget.cfd,rabbitporno.com,racebets.",
        "com,radarconsultation.com,radcffbpaqwyr.space,radiancelongitude.com,radiancerelaysacred.com,radiancethedevice.",
        "com,radiantack.com,radiantmedia.net,radiantspacecraftbell.com,radiantvalerelieve.com,radiodarn.com,radioonline",
        "hd.net,radioreference.com,radiusfellowship.com,radiusix.net,radiusmarketing.com,ragfishcleptavgases.cfd,ragged",
        "twinheadmaster.com,rakutenadvertising.io,ramppopulateresponse.com,rapidadexchange.com,ratedeaddisk.com,rationa",
        "lizeinadequatecomedian.com,rationalizeoverwhelmendeavour.com,raveigryeggfj.website,ravenfarm.tech,ravenousamus",
        "e.com,ravktufmpgula.site,ravm.tv,rbpksavddouil.store,rcamagqknhmx.com,rcygxjvmjadds.site,rdrbunaadhpyn.site,re",
        "activebetting.com,readiong.net,readme.ru,readpeak.com,readserv.com,readycelebrationleaf.com,realcfadsblog.com,",
        "realclick.co.kr,realgfporn.com,realxavounow.com,reasonable-bet.com,recamnjggepku.site,receiptcadenceresurrecti",
        "on.com,recipeaddingavenue.com,recipientpopulationregret.com,reclaimvanillademure.com,recordedheavinessexperien",
        "ce.com,rectadesc.cfd,recyclehorridleading.com,redads.com,rediads.com,redirect-ads.com,redpineapplemedia.com,re",
        "dtube.com,redtube.fm,reedsoutpop.com,refbanners.com,refbanners.website,referenceswarmerbread.com,regionads.ru,",
        "regionalavouchcreeping.com,regularseatradio.com,rehearsejuniorloaded.com,reliablebanners.com,rememberineffecti",
        "veadvisedly.com,remetalthecladayment.cfd,rencontreadultere.club,rentfavourablecashmere.com,resacaduende.com,re",
        "sentlocallycamomile.com,resentmentsadflew.com,resteddiabetepocket.com,restorepushed.com,retaingravely.com,reve",
        "alingplacingpopped.com,revenuecpmgate.com,revenuecpmnetwork.com,revenuenetwork.com,revenuenetworkcpm.com,revil",
        "lusiveheavily.com,revimedia.com,revive-adserver.net,revopush.com,revupads.com,rewardsaffiliates.com,rexadvert.",
        "xyz,rexpush.club,rfwmfadnncajl.site,rhinoaffiliates.com,rhombusads.com,rhubarbambassadorweep.com,richads.com,r",
        "ichwebmedia.com,rideradjective.com,ridgepilotavalanche.com,risingshadewhite.com,rivinadinomic.qpon,rmqgscbitoa",
        "dp.site,rmyhibtadohj.com,roadcontagion.com,roadwaspfang.com,robeheadlong.com,rocketmedia24.com,rockmostbet.com",
        ",rocoads.com,rodesquad.com,rogersmedia.com,rokymedia.com,rollbackavenge.com,roobet.com,roobetaffiliates.com,ro",
        "otzaffiliates.com,rotatingadult.com,roxyaffiliates.com,rpyztjadsbonh.store,rqerokcafoadv.space,rtb-media.me,rt",
        "bpop.com,rtbreachads.com,rteomxavwvnol.site,rubanners.com,rubiconproject.com,rummyaffiliates.com,ruscams.com,r",
        "yscxxxelrsdw.space,rytadlqujhwsz.site,rzedmincpjcam.website,s-adzone.com,sabavision.com,saczvfpaeadxv.space,sa",
        "dbaguette.com,sadbasindinner.com,saduvqpcfykcj.online,sadvvowejqfet.site,safelinktracker.com,sagaciousexceptin",
        "grate.com,sagsajijqpavd.space,saladattic.com,salbraddrepilly.com,salvador24.com,samsungads.com,sandracismabove",
        "average.com,sandwichconscientiousroadside.com,saravanacyetic.qpon,satisfactorybroadformidable.com,savageavowmi",
        "dland.com,savamea.org,save-editor.com,savefiles.com,saveit.us,saveterracehangnail.com,saveunstable.com,savines",
        "yohimbe.cfd,savinist.com,saviourcrimpycrimpyhem.com,savourbiggergait.com,savttuegxztst.site,saymedia.com,sbaff",
        "iliates.com,sbicrwatvnavf.online,sbsbetlinfevn.site,scamblefeedman.com,scamforgottenbyte.com,scammerfail.com,s",
        "cammerrecorder.com,scarlet-clicks.info,scatxxxporn.com,scholarshipexplosivepopular.com,sciouspavlovpundum.qpon",
        ",scorchads.com,scorecardresearch.com,scowpoppanasals.com,scqcigoladk.com,sdadarotfnhoy.website,sdk4push.com,sd",
        "omxqcaeuadm.site,sdtzadpayrgsc.space,seafoodclickwaited.com,seaofads.com,searchschoolsnetwork.com,searingcavel",
        "et.rest,seatrackingdomain.com,secondhandbrave.pro,secretbehindporn.com,secthatlead.com,secureaddisplay.com,sec",
        "ureleadsforever.com,secureleadsrn.com,sedatingnews.com,seizureblade.com,selectad.com,semperinshave.rest,sendwe",
        "bpush.com,sensible-ads.com,sensitiveclick.com,seriolavangloe.com,servedby-buysellads.com,servedbyadbutler.com,",
        "servemeads.com,server4ads.com,set-push.net,setravieso.com,setupad.com,setupad.net,sewboldlyavow.com,sex-and-fl",
        "irt.com,sex-chat.me,sex-techniques-and-positions.com,sex3.com,sexad.net,sexcelebrity.net,sexclic.com,sexcoolda",
        "ting.com,sexdatecash.com,sexei.net,sexemulator.com,sexfamilysim.net,sexfg.com,sexfinder.com,sexflirtbook.com,s",
        "exgangsters.com,sexiba.com,sexier.com,sexintheuk.com,sexlist.com,sexmature.fun,sexmoney.com,sexmotors.com,sexm",
        "y.site,sexpin.net,sexplaycam.com,sexsearch.com,sexstories69.com,sextadate.net,sextf.com,sextracker.com,sextube",
        "box.com,sextubepromo.com,sextubeweb.com,sextvx.com,sexuhot.com,sexvertise.com,sexvid.pro,sexvid.xxx,sexy-ch.co",
        "m,sexycams.com,sexypornvideo.net,sf-ads.io,shadowybalance.com,shadysetting.pro,sharemedia.rs,sharktube.net,sha",
        "uladubhe.com,shauladubhe.top,shaversoutjumphobnobs.cyou,shavesfoilermissus.cfd,shavingwitchcraft.com,shipmentd",
        "oorwaydecade.com,shoogloonetwork.com,shopmonthtravel.com,short-wave.info,showcasead.com,showmeyouradsnow.com,s",
        "hubadubadlskjfkf.com,siavtcwmgehxp.online,sibrdiarmuvav.site,sierrakonradreorganize.com,siftoctaveuntimed.qpon",
        ",signaturewoundedgraduation.com,silesiadeuces.com,sillorangesladen.com,silverleafroad.com,simple-affiliate.com",
        ",sinadolcoralla.cfd,sincerelydiscreetcomedian.com,sindonpavis.cyou,singlesexdates.com,sizednavahoduncify.cyou,",
        "sjadkbbvcmdfd.website,sjobmfxcchbad.online,sjzieadynfqtl.online,skeetads.com,skidwaverorigin.com,skipvideoads.",
        "com,skirlsravager.com,skyadsmart.com,skymedia.co.uk,skynetworkcdn.com,slaveguna.cfd,slavesthy.com,slavingaint.",
        "com,slippersexamination.com,slthavjtoxjoi.site,smac-ad.com,smadex.com,smart1adserver.com,smartadserver.com,sma",
        "rtadtags.com,smartclick.net,smartcpatrack.com,smartyads.com,smashpops.com,smentbrads.info,smwadnuydogrh.space,",
        "snack-media.com,snaeyjnavvojo.online,snathsextends.com,snbets.com,sneakyadministration.com,snipersex.com,snlaa",
        "vecjmpja.online,socalledpushedcollege.com,socialsex.biz,socialsex.com,socialsexnetwork.net,sofinpushpile.com,s",
        "oftforestmeadow.com,softonicads.com,softpopads.com,soldierreproduceadmiration.com,solicitorvalvemade.com,soloc",
        "pm.com,solutionsadultes.com,somehowluxuriousreader.com,sophiaaccoladewest.com,sorbetbungler.com,sordidsickness",
        "faded.com,sousefulhead.com,soxnhocmadklc.site,spadeharassrefusal.com,spaderonium.com,spadsync.com,spanpromo-li",
        "nk.com,sparrowaboveaveragedefence.com,sparta-tracking.xyz,special-promotions.online,specificmedia.com,speckenc",
        "ampment.com,speechcleopatraadmiral.com,speedingdeadlyplays.com,speedycomediandecember.com,sphinxtube.com,spmed",
        "iacdn.com,spokeoaffiliates.com,sponsorinserttimeout.com,sponsorpay.com,sponsorshipprickly.com,sportradar.com,s",
        "portradarserving.com,spotidownloader.com,sppopups.com,spreadingsinew.com,spreadpreferencetelevision.com,sprint",
        "rade.com,spry-glad.pro,srvupads.com,srzwbetxwdkpt.online,sseadlhhrusrj.website,ssqcqaavrncxt.online,stackadapt",
        ".com,stagepopkek.com,staggeredravehospitality.com,star-advertising.com,star-clicks.com,star-theladyfinds.com,s",
        "targamesaffiliate.com,starmobmedia.com,startwebpromo.com,statcamp.net,statsforads.com,staturebanner.com,staviz",
        "okhbhe.in,steadyglobe.com,steadyhelmet.com,sterilecontradiction.com,stickyadstv.com,stiffenshave.com,stimtavy.",
        "net,stinkyloadeddoctor.com,stop-adblocker.info,stopads-now.com,stopadzblock.net,stopblockads.com,storader.com,",
        "storage-ad.com,store4porn.com,straightenedphiladelphiarequire.com,streamjav.top,stringroadway.com,stripsaver.c",
        "om,striptube.net,studads.com,studiotrackmusic.com,sukaporn.com,sunglassesexpensive.com,sunmedia.tv,sunmediaads",
        ".com,sunporno.com,sunshineadditions.com,superadexchange.com,superonclick.com,superxxxfree.com,supposedlymadeup",
        ".com,supremeadblocker.com,suptube.com,surpavdggpjar.site,surplusaltarmadness.com,swak-adguard.pro,swarmpush.co",
        "m,swatowbetokenganam.cfd,sweetmedia.org,swesomepop.com,swiftladle.com,swimmingnarcotictravel.com,swmlidloaetad",
        ".site,swwpush.com,sygvwadaxjesn.space,synsads.com,syrupimmediate.com,systemads.net,systemleadb.com,taadd.com,t",
        "aboola.com,taboola.net,tacxemkinqtav.space,taddpxmmooi.com,tadodpfikom.in,tafdiywfnavss.site,tahrqfbavxmxf.sit",
        "e,takeads.com,tanagerpoppel.rest,tantoporno.com,tapingferocitymadeira.com,tapjoyads.com,taroads.com,taversroom",
        "thy.com,taversserang.cyou,tavgvouyakozy.website,tavlomikyionr.website,tavryuhcshdsr.website,tcpcharms.com,tead",
        "s-xo.com,teads.tv,teadumiccdoct.website,technologies2radar.net,technoratimedia.com,technoshadows.com,techporn.",
        "ph,tectrixsnavvle.com,teenporno.xxx,teensexgfs.com,tehseelaves.com,tejxthyxavhfb.site,teneralavarice.cfd,terma",
        "gewaving.com,terrapush.com,text-link-ads.com,tgtmedia.com,thavxaiwvxtja.website,the-adult-company.com,the-bina",
        "ry-trader.biz,theadgateway.com,theadsparks.com,thebetterdealz.com,thecoreadv.com,thelotter-affiliates.com,them",
        "ediaplanets.com,themselvespresentationfavourable.com,theplayadvisor.com,theporndude.com,thepornstar.com,thesis",
        "adornpathetic.com,thesocialsexnetwork.com,thingsmadenavy.com,third-tracking.com,thorpeslav.com,thoughtleadr.co",
        "m,thrad.ai,thrads.ai,threadpassivetrespass.com,thriftytraveler.com,thrixxx.com,thtrsihwdznav.site,thunderhead.",
        "com,tiatmuadwuhwy.online,tidycamel.com,tiedhamadjoin.com,tihmjppaavrmz.site,tiladlolbtu.in,tiltgardenheadlight",
        ".com,tinyadz.com,titanads1.com,titanads2.com,titanads3.com,titanads4.com,tjcfeyegncavs.online,tjesbxadovyot.si",
        "te,tjiyavxmtihln.space,tkpopd.cfd,tkpopp.com,tktube.com,tlavideo.com,tlrzavbrosrsv.space,tlucfqhtavxyx.space,t",
        "luihoeadocjs.store,tlvmedia.com,tnnfecqadqzck.online,toboads.com,toibjavyncxnc.site,tokelaunavet.cyou,tokenads",
        ".com,tomladvert.com,tomorrowlemonade.com,tonemedia.com,tonicheaviness.com,topadsservices.com,topadvert.ru,topb",
        "etfast.com,topclickguru.com,topcpmcreativeformat.com,topdealad.com,topdisplaynetwork.com,topprofitablecpm.com,",
        "toprevenuecpmnetwork.com,topsexcams.club,topsexmovs.com,toptopleads.com,toroadvertising.com,tossoffads.com,tot",
        "aladblock.com,totaladperformance.com,totalav.com,totymanglave.com,tpdads.com,tpmedia-reactads.com,tpopfgvlqytl",
        "m.online,tprfavksbqzmr.online,tqqavzfshkdsh.website,track-campaing.club,track-victoriadates.com,track4ref.com,",
        "trackad.cz,trackcherry.com,trackcitg.com,trackclicks00.com,tracker-2.com,tracker-tds.info,trackerflw.com,track",
        "errr.com,tracki.click,tracking-aws-5.com,tracking-zy.com,trackingmembers.com,trackingtraffo.com,trackmundo.com",
        ",tracks20.com,tracksfaster.com,trackspeeder.com,trackstracker.com,tracktds.com,tracktilldeath.club,trackuity.c",
        "om,trackvoluum.com,trackwilltrk.com,trackzd.com,tradbypass.com,tradeadexchange.com,tradedoubler.com,tradeexper",
        "t.net,tradehouse.media,tradepub.com,tradertimerz.media,tradetracker.net,traditionalboycottflung.com,traditiona",
        "lmusic.co.uk,traditionblindness.com,tradplusad.com,traffic-media.co.uk,trafficad-biz.com,trafficadbar.com,traf",
        "ficmediaserver.com,trailerheavendirectness.com,trainedwave.pro,trapdoortissuepush.com,travelandtourworld.com,t",
        "ravelaudience.com,travellingcrushslit.com,travellingpresidentding.com,travelscream.com,travelsplayboy.com,trav",
        "eltop.org,traverseseven.com,travidia.com,tremormedia.com,trimreaddrunken.com,tripcarriagewaving.com,tripsthorp",
        "elemonade.com,triumphantthingsreading.com,tronads.io,trustedcpmrevenue.com,trustphiladelphiacompelling.com,ts-",
        "tracker.com,tsadcebakufrd.site,tsmodelstube.com,tspops.com,tsuccwavchpvh.website,tsztqlwravxkl.site,ttfwotjeta",
        "vmk.website,ttzmedia.com,tube-sexs.com,tubeadnetwork.com,tubeadv.com,tubeadvertising.eu,tubecorp.com,tubecorpo",
        "rate.com,tubecup.net,tubeelite.com,tubemov.com,tubeoffline.com,tubepork.com,tuberel.com,tubetruck.com,tubeultr",
        "a.com,tubev.sex,tubewolf.com,tubexazimebeze.com,tubexo.tv,turboadv.com,turnstileunavailablesite.com,tvewiifmav",
        "niu.website,twads.gg,tweakpersuadelowest.com,twinpinenetwork.com,twiselaveria.com,twistads.com,twittad.com,txg",
        "tadnmmwwte.space,txxx.com,typhulaverging.cfd,uadllyad.com,uads.cc,uaeapcmjbmzad.space,uaqiwadxmsniz.online,uav",
        "efhtrbkbhq.online,uavnfdezxzote.site,ubercpm.com,ubgvqakukavmm.space,ublockpop.com,ucaavjcweklhn.space,ucnwhht",
        "aadaqp.site,udsqbadxjvzanh.com,ueleensadcyxh.site,ufauolqhhcpmd.space,ufidxaduyqqkq.site,ufirmirylfavb.online,",
        "ugadceetawiae.site,ugazuogyeeadt.online,uhuvadkubxjkv.website,uitopadxdy.com,uiuhawavotxsv.space,ukaadmlcfluko",
        ".site,ukaviayvjfydp.online,ukcampsite.co.uk,ulbruiqadlmaa.online,ulpradvpeaiqs.store,ultimedia.com,umljflgaddc",
        "dk.space,unavoidablestates.com,uncadyaewiajd.online,uncededtravoy.com,uncertainadministration.com,underclick.r",
        "u,undergroundportugueseheadache.com,unibet.co.uk,unibet.com,unidentifiedscammertravel.com,universalads.com,uni",
        "versityroadsidecircumstantial.com,unloadonly.com,unripe-head.pro,unrulymedia.com,unsettledradiator.com,unshave",
        "paraphs.com,untimely-promotion.com,unwavedmalarky.cyou,unweavederiv.com,uoutube.com,upbeatmeadow.com,uploadclo",
        "ud.pro,uploaded.net,uploadgig.com,upornia.com,uprivaladserver.net,uptopopunder.com,upush.co,upymgrtnacpco.site",
        ",urdatingassistant.com,urgayporn.com,urzdxiiugisad.site,usasexcams.com,usavmhnwphcmm.online,userimmediate.com,",
        "usesponsorarrange.com,ussmtrack.com,ust-ad.com,usuxodfefavbm.space,utavbvaervidi.site,utm-campaign.com,utrocaa",
        "drboym.space,utteredadisappo.com,utubepwhml.com,uubvkadmkvnys.space,uuwjrmvavkgrs.site,uvvsyeadlyrgb.space,uwz",
        "wqadqkcjgw.site,uyqwpnspgadkq.site,uyriroadwtdwu.space,uywfpprhavyog.site,uzpuaveerqjei.site,v8eaezd.cc,vaguem",
        "ad.com,valemedia.net,validclick.com,valuad.cloud,valuead.com,valueclick.cc,valueclick.com,valueclick.net,value",
        "clickmedia.com,variabilityaviationmutiny.com,vatanclick.ir,vavuwetus.com,vazijiavfyebf.site,vcmedia.com,vdezte",
        "mladdeb.online,vdhvldghccavh.site,vdxsvladcihnm.online,venturead.com,verbsponsor.com,verdecasino-offers.com,ve",
        "rdepromo.info,vertismedia.co.uk,vespymedia.com,vexilladuper.cyou,vfreecams.com,vhojdkvcadavb.space,viadata.sto",
        "re,viads.com,viads.net,vid-adblocker.com,vidcpm.com,video-adblocker.com,videoadstech.org,videocampaign.co,vide",
        "osprofitnetwork.com,viewablemedia.net,viiadaof.com,viibslav.com,vikablavflhe.com,vikiporn.com,villainous-track",
        ".pro,vipads.live,vipavkacrfujf.online,viptube.com,viradisazo.qpon,viralmediatech.com,virtuedeadlock.com,visari",
        "omedia.com,viscidvareusebetitle.cfd,visiads.com,vjzlvloavqarr.space,vkgtrack.com,vkipfmadahrwh.site,vkoxgtewza",
        "dqi.site,vkyieskdlavjt.space,vlogerads.com,vlwqyhgnslfad.space,vmpdmfacpctto.space,vnakjojlazcpm.online,voeqzu",
        "uvnwpad.website,voirpfhavircs.online,votepercussivepoplar.com,vowsavageskim.com,vpvlxuooad.com,vqkusfxadcdll.w",
        "ebsite,vradfvwfmwvaw.space,vrbcauaviehga.space,vrgbaevvadiwn.site,vrizead.com,vrstnadgljwiy.space,vrtzads.com,",
        "vsavbpsnxdrgt.online,vsexshop.ru,vtdoadqxkv.com,vtube.to,vucxfzsavoxdd.online,vuevgiavjbplr.space,vuiebktuavkc",
        "c.online,vulkanbet.me,vulnerableimmigrateaboveaverage.com,vvautxavycmwh.space,vvladxqdlpihl.online,vwzusobugmr",
        "ad.space,vxxgxicadjtia.space,vxyqwespdaadz.site,w00tmedia.net,wadecarbohydrate.com,waexhoxdqigad.space,wafmedi",
        "a6.com,wagsad.com,wailedshave.com,walkeralacrityfavorite.com,wangheebehav.com,wanlyavower.com,warashi-asian-po",
        "rnstars.fr,warilyclickframes.com,warmmeadowroad.com,washedadvance.com,washoffwaviercyanite.cfd,watchadsfree.co",
        "m,watchcpm.com,wavecdn.de,wavedsuspect.click,waveetiquette.com,waveletiricize.cfd,wavermiddayboundary.com,wavi",
        "esdaywork.cyou,wavingauradignity.com,wavtcdqwbuhiw.space,waxapushlite.com,wayanatomyunavailable.com,wayfterthe",
        "yhadgo.org,wbamedia.com,wcgbetyplakno.website,wclpvybetahei.site,wdahqumfpnmav.space,wdczlavbweeby.online,wdxu",
        "ugjqeavzv.space,wearadmiration.com,weaveradrenaline.com,weaversprinkle.com,weaveworkingheaviness.com,web-adblo",
        "cker.com,web3ads.net,webads.co.nz,webads.nl,webadserver.net,webbed-leadership.com,webcampromo.com,webcampromot",
        "ions.com,webcams.com,webcamtests.com,webclickengine.com,webclickmanager.com,webpushcloud.info,websitepromoserv",
        "er.com,webtradehub.com,weekheadlong.com,weforads.com,wellmadesurvey.pro,wellworn-lead.com,wetrackgames.com,wfb",
        "cfadgivxqd.space,wfiutburadoho.online,wfnetwork.com,wgfqayocqadzp.space,whaleads.com,wheebsadree.com,wheegneho",
        "ad.net,wheelchairavoid.com,wheelchairbewilderedwaver.com,whirlclick.com,whoadhoxtojg.com,whopping-click.com,wh",
        "orledebbets.cyou,whwdxzadairoq.website,widespreadcomponent.com,wifescamara.click,wigetmedia.com,williamhillcas",
        "ino.com,windfallleavebrawl.com,winneradequateablebodied.com,winneradsmedia.com,wisjevyyjdjad.site,withdrawtrad",
        "e.com,wktmzgeadgmje.com,wlkokwriavlw.com,wmmediacorp.com,wnt-s0me-push.net,wnvqeizwbpads.online,woffxxx.com,wo",
        "mxwsnxadxtl.space,wonderadblock.com,wootmedia.net,workdeadlinededicate.com,worldsbestcams.com,worried-advantag",
        "e.com,worthylighteravert.com,wowgwmezvkuav.site,wpadmngr.com,wpiadbxpy.in,wpnetwork.eu,wpush.org,wpushorg.com,",
        "wpushsdk.com,wqbeavbpztwsm.website,wravogram.cfd,wretchedadvantage.com,wsknwakvkeiad.site,wtg-ads.com,wtpiylrm",
        "avdfp.space,wtqhxggxngfad.site,wtzqznypmadeo.store,wujtefbetb.com,wunsofiubphad.space,wwads.cn,wwwadcntr.com,w",
        "wwpromoter.com,wwwxxx.uno,wzkqcamv.com,xad.com,xadcentral.com,xadpagwro.com,xadsmart.com,xapads.com,xavhiszxjs",
        "pce.website,xavions.shop,xbcpcn.com,xbhcyadhm.com,xbhoylaadarbq.website,xcelsiusadserver.com,xcpmfvlfcdltd.onl",
        "ine,xcrrdxmdadebs.site,xglvldsblpkad.online,xgwhfyaavzpyp.space,xhamstercams.com,xinbzybuosadk.website,xjav.tu",
        "be,xjoavfrckohwo.online,xjsgturadzcgo.online,xkavrcwjklaitf.com,xldadwsosaqiq.site,xlovecam.com,xmayktavadugm.",
        "site,xmladserver.com,xmtrading.com,xnxxporn.video,xnxxxnxx.site,xoaveubcbdpld.com,xogogowebcams.com,xrff-adgua",
        "rd.pro,xrqcamarbnhxg.website,xslbuoadsfsrf.space,xsqltdwuccamf.space,xtendmedia.com,xuytfncamofxt.site,xvidxxx",
        ".com,xwkvojadxcnyd.space,xxbifrkzadx.com,xxx-porn-tube.com,xxxbannerswap.com,xxxblackbook.com,xxxbox.me,xxxbp.",
        "tv,xxxbule.com,xxxdessert.com,xxxex.com,xxxfetish24.com,xxxhd.icu,xxxiijmp.com,xxxijmp.com,xxxivjmp.com,xxxmat",
        "ch.com,xxxoh.com,xxxpornhd.pro,xxxshake.com,xxxviiijmp.com,xxxviijmp.com,xxxvijmp.com,xxxvjmp.com,xxxvogue.net",
        ",xxxwap.pro,xxxwebtraffic.com,xxxxsx.com,xxxzbkhpjxzry.store,yadmarmizuogr.website,yads.tech,yadsunkzjtzlb.spa",
        "ce,yavecqiztteba.site,yavli.com,yavsmataemray.online,yavurxepklnzm.space,yazatadoolysups.cfd,ybb-network.com,y",
        "bcnevrcnyadv.site,ydjadyzuepuvf.site,ydwekhadydlnz.space,yekavcxlkbejc.online,yesporn.cam,yespornpleasexxx.com",
        ",yetterslave.com,yeyswxtehnave.site,yieldads.com,yieldlove-ad-serving.net,yjjvzmruicyav.online,ykdqqskuziadx.s",
        "ite,ykveifvdzadrl.online,yoads.net,yoc-adserver.com,yogadeprive.com,yopuademtfkwb.space,you-porn.com,youhavequ",
        "ited.com,youlamedia.com,youporn.com,youradexchange.com,yourquickads.com,youtube.com,yowiespopian.com,yoxfjudad",
        "p.com,yoyadsdom.com,ypcjgbavpkray.site,yporn.tv,ypztdrxavedah.space,yqepkjcpcxnay.space,yqopmxeyoaveu.online,y",
        "rrnadvdbvdow.space,yuintbradshed.com,yukclick.me,yumenetworks.com,yummyadvertiseexploded.com,yuppads.com,yuppy",
        "ads.com,yurekbkmhoadl.net,yuwseujcjavak.space,yvmads.com,ywiicadcuvilo.online,yx-ads6.com,yxaasylsdjado.site,y",
        "xrlvfirqiave.website,yyecdiehcvadf.site,yyxnoegkqkavu.space,zadlclqnequke.site,zaimads.com,zanox-affiliate.de,",
        "zarpop.com,zavcmzahutmqo.site,zavirand.com,zavnnfijuylnu.site,zavunmknjlpwl.space,zavylwpyuijzb.website,zbkrmw",
        "rzftiav.site,zbporn.com,zcbetcczfvylq.site,zcfpfbavrffxm.site,zeads.com,zealshaving.com,zebracave.com,zedporn.",
        "com,zenomedia.com,zerads.com,zesty-lead.pro,zetadeo.com,zeusadx.com,zextzccadpr.com,zfctrack.net,zgaxadxsycppa",
        ".website,zgijtonvzgadk.online,zhyhadnrqzpcd.site,ziffdavis.com,zimoccaengrave.shop,zimvlqwrtadmi.space,zipcryp",
        "ticbroadsheet.com,zixxsovtavprk.online,zkdwtdzlcadfu.site,zkvsavwzcbfaj.online,zkxweadue.com,zlavxbjuyekwo.sit",
        "e,zlzwhrhkavos.xyz,zotqhsyqbetkg.online,zoxlzvrqeqadk.site,zplxavnqigfrp.site,zpuavdcswqnml.online,zpxsadurflh",
        "s.com,zrbezdaddiahk.space,zrdubetejajen.website,zrygvvxyadvro.site,zsfbadlhjmpow.site,zsnweuiavdmzs.online,zte",
        "eavqibjsgj.space,ztrack.online,zumidxxxwcjob.site,zunoptjciavj.in,zuzaghbkajav.in,zvwjrwav.com,zvzpopazdasxh.w",
        "ebsite,zwkfzyjtadeuz.space,zxavziahazmyb.online,zyadpzcdswdre.com,zypenetwork.com,zytfycpmnlzio.online,eab.elb",
        "phzgw.cc,demjpgtf.zvpxyyql.cc,api2.jpfviwgkz.cc,xxycw341.com,vnhgr465.com,caow.us,583.cc,9521.com,2726.com,831",
        "9.com,9212.app,322.cc,384.tv,4477.tv,177.gg,5651.com,275.cc,391.cc,837.tv,1x4g6f2.com,aizhantj.com,0cy5aps.com",
        ",ax8rylb.com,7xkpxuw.com,4gl3g.z.com,mehqk5u.com,smun7ro.com,67x8epk.com,kvkluj8.com,lw1su4n.com,hcxftcl.com,n",
        "f65hwr.com,3dzmgtc.com,dkepyys.com,hv7hpkk.com,tq.qwehkg.top,ta.dqgjla.top,tz.erghsasq.top\".split(','));functi",
        "on isAdDomain(h){if(!h)return false;h=h.toLowerCase();if(__museAdSet.has(h))return true;var i=h.indexOf('.');w",
        "hile(i>=0){h=h.slice(i+1);if(__museAdSet.has(h))return true;i=h.indexOf('.');}return false;}function isAdUrl(u",
        "){try{var x=new URL(u,location.href);return !!x.hostname&&isAdDomain(x.hostname);}catch(e){return false;}}func",
        "tion isContent(el){if(!el||!el.querySelector)return false;if(el.querySelector('a[href*=\"/voddetail/\"],a[href*=",
        "\"/vodplay/\"],a[href*=\"/video/\"],a[href*=\"/player/\"],a[href*=\"/v5/\"]'))return true;if(el.querySelector('#go_btn",
        ",#time_badge,#read_zone'))return true;return false;}function foreign(u){try{var x=new URL(u,location.href);ret",
        "urn !!x.hostname&&x.hostname!==host;}catch(e){return false;}}function isRealSite(){var as=document.querySelectorAll(\'a[href]\');var n=0;for(var i=0;i<as.length;i++){var h=as[i].getAttribute(\'href\')||\'\';if(foreign(h))continue;var p=\'\';try{p=new URL(h,location.href).pathname;}catch(e){continue;}if(p.indexOf(\'/vodtype/\')===0||p.indexOf(\'/voddetail/\')===0||p.indexOf(\'/vodplay/\')===0){if(++n>=2)return true;}}return false;}function isPlaceholderHref(h){if(!h)return true;h",
        "=String(h).trim().toLowerCase();return h===''||h.charAt(0)==='#'||h.indexOf('javascript:')===0;}function inSam",
        "eHostLink(el){var a=el&&el.closest?el.closest('a'):null;return !!(a&&a.href&&!foreign(a.href));}function isOpa",
        "queImg(s){return s&&(s.indexOf('blob:')===0||s.indexOf('data:')===0);}function txt(el){return (el.innerText||'",
        "').replace(/\\s+/g,'');}function ageIdc(el){return ((el.id||'')+' '+((el.className&&el.className.baseVal!==unde",
        "fined)?el.className.baseVal:(el.className||''))).toLowerCase();}function isAgeGate(el){if(!el)return false;if(",
        "/agedisclaimer|age[-_]?verif|age[-_]?verification|age[-_]?gate|age[-_]?check|over18|under18|age[-_]?confirm/.t",
        "est(ageIdc(el)))return true;var t=(el.innerText||'');if(/这是个成人网站|我年满\\s*18\\s*岁|i\\s*am\\s*18|i'm\\s*18|verify your",
        " age|age verification/i.test(t))return true;if(t.length<=120&&t.indexOf('18')>=0&&/(成人|年满|\\bage\\b|adult|over 1",
        "8|under 18)/i.test(t))return true;return false;}\nfunction isChallenge(el){if(!el)return false;try{var __ct=(do",
        "cument.title||'').toLowerCase();if(__ct.indexOf('请稍候')>=0||__ct.indexOf('just a moment')>=0)return true;}catch",
        "(e){}if(el.tagName==='IFRAME'){var s0=el.src||(el.getAttribute?el.getAttribute('src'):'')||'';if(s0&&(s0.index",
        "Of('cloudflare')>=0||s0.indexOf('hcaptcha')>=0||s0.indexOf('recaptcha')>=0))return true;return false;}if(!el.q",
        "uerySelectorAll)return false;var frs=el.querySelectorAll('iframe');for(var i=0;i<frs.length;i++){var f=frs[i].",
        "src||(frs[i].getAttribute?frs[i].getAttribute('src'):'')||'';if(f&&(f.indexOf('cloudflare')>=0||f.indexOf('hca",
        "ptcha')>=0||f.indexOf('recaptcha')>=0))return true;}return false;}function inAgeGate(el){if(!el)return false;i",
        "f(isAgeGate(el))return true;var n=el.parentElement,p=0;while(n&&p<6){if(/agedisclaimer|age[-_]?verif|age[-_]?v",
        "erification|age[-_]?gate|age[-_]?check|over18|under18|age[-_]?confirm/.test(ageIdc(n)))return true;n=n.parentE",
        "lement;p++;}return false;}function kill(el){if(el&&el.parentNode)el.parentNode.removeChild(el);}function colla",
        "pse(el){var p=el,i=0;while(p&&i<3&&p!==document.body){if(txt(p).length===0&&!p.querySelector('img,video,iframe",
        ",form,input,button,select,textarea')){var n=p.parentElement;kill(p);p=n;i++;}else break;}}function collapseCard(a){var p=a,i=0;while(p&&i<3&&p!==document.body){var links=p.querySelectorAll(\'a[href]\');var hasReal=false;for(var k=0;k<links.length;k++){var h=links[k].getAttribute(\'href\')||\'\';if(!h)continue;if(isPlaceholderHref(h))continue;if(!foreign(links[k].href)){hasReal=true;break;}}if(hasReal)break;if(p.querySelector(\'img,video,iframe\'))break;if(txt(p).length>200)break;var n=p.parentElement;kill(p);p=n;i++;}}function killLoaderP",
        "adding(){var ss=document.querySelectorAll('style');for(var i=0;i<ss.length;i++){var st=ss[i];var id=st.id||'';",
        "if(id.slice(-9)!=='_style_id')continue;var t=(st.textContent||'').replace(/\\s/g,'');if(t.indexOf('padding-bott",
        "om:100px')>=0&&st.parentNode)st.parentNode.removeChild(st);}}function mainLink(card){var as=card.querySelector",
        "All('a[href]');for(var i=0;i<as.length;i++){var a=as[i];if(isPlaceholderHref(a.getAttribute('href')))continue;",
        "if(txt(a).length>=4)return a;}return as.length?as[0]:null;}\nfunction hideMixedForeignCards(){var lists=documen",
        "t.querySelectorAll('ul,ol');for(var li=0;li<lists.length;li++){var list=lists[li];if(!list.isConnected)continu",
        "e;if(list.closest('nav,header,footer'))continue;if(isAgeGate(list))continue;var cards=[];var kids=list.childre",
        "n;for(var k=0;k<kids.length;k++){var c=kids[k];if(c.nodeType!==1)continue;if(!c.querySelector('a[href]'))conti",
        "nue;cards.push(c);}if(cards.length<8)continue;var same=0,total=0;for(var m=0;m<cards.length;m++){var a=mainLin",
        "k(cards[m]);if(!a)continue;var href=a.getAttribute('href')||'';if(isPlaceholderHref(href))continue;total++;if(",
        "!foreign(href))same++;}if(total<8)continue;if(same<=total*0.5)continue;for(var n=0;n<cards.length;n++){var a2=",
        "mainLink(cards[n]);if(!a2||!a2.isConnected)continue;var h2=a2.getAttribute('href')||'';if(!h2)continue;if(fore",
        "ign(h2))kill(cards[n]);}}}\nfunction hideNoidDiv(){var n=document.getElementById('noiddiv');if(!n||!n.isConnect",
        "ed||n===document.body)return;if(n.querySelector&&n.querySelector('video'))return;n.style.display='none';}\nfunc",
        "tion hideHeadingPromoLinks(){var hs=document.querySelectorAll('h1 a[href],h2 a[href],h3 a[href],h4 a[href],h5 ",
        "a[href],h6 a[href]');for(var i=0;i<hs.length;i++){var a=hs[i];if(!a.isConnected)continue;if(inAgeGate(a))conti",
        "nue;var href=a.getAttribute('href')||'';if(!href)continue;if(foreign(href))a.style.display='none';}}\nfunction ",
        "noteHide(rule,el){try{if(!window.__museHideLog)window.__museHideLog=[];if(window.__museHideLog.length<20){var ",
        "cn=(el.className&&el.className.baseVal!==undefined)?el.className.baseVal:(el.className||'');window.__museHideL",
        "og.push(rule+':'+(el.tagName||'')+'.'+String(cn).slice(0,30));}}catch(e){}}\nfunction sweep(){killLoaderPadding",
        "();killPreRoll();pruneExtraVideos();",
        // v76：假分类/假视频（跳转完成后，外链 <a> 藏掉；真分类/真视频是站内相对路径）+ 赌博横幅墙（javascript:void(0) 含图 <a> 藏掉）。
        // v77：直接进站也激活——isRealSite()（页含 ≥2 站内正片链接即真实站）时同样触发；发布站页无站内正片链接，不触发。
        "/*v77*/if(window.__museJumpDone||isRealSite()){var __museFAs=document.querySelectorAll('a[href]');for(var __museFI=0;__museFI<__museFAs.length;__museFI++){var __museFA=__museFAs[__museFI];if(!__museFA.isConnected)continue;if(inAgeGate(__museFA))continue;if(isContent(__museFA))continue;if(!foreign(__museFA.href))continue;var __museFP=__museFA.parentElement;kill(__museFA);collapseCard(__museFP);}var __museVAs=document.querySelectorAll('a[href^=\"javascript:void(0)\"]');for(var __museVI=0;__museVI<__museVAs.length;__museVI++){var __museVA=__museVAs[__museVI];if(!__museVA.isConnected)continue;if(inAgeGate(__museVA))continue;if(!__museVA.querySelector('img'))continue;var __museVP=__museVA.parentElement;kill(__museVA);collapseCard(__museVP);}}",
        "var as=document.querySelectorAll('a[href]');for(var i=0;i<as.length;i++){v",
        "ar a=as[i];if(!a.isConnected)continue;if(inAgeGate(a))continue;if(!isAdUrl(a.href))continue;var rel=(a.getAttr",
        "ibute('rel')||'').toLowerCase();var spon=rel.indexOf('sponsored')>=0;var hasImg=!!a.querySelector('img');var h",
        "asBg=false;if(!spon){var dv2=a.querySelectorAll('div');for(var q=0;q<dv2.length;q++){var de=dv2[q];if(de.getAt",
        "tribute('x-bg-loader-url')||de.getAttribute('data-bg')){hasBg=true;break;}var dc=getComputedStyle(de);if((dc.b",
        "ackgroundImage||'').indexOf('url(')>=0){hasBg=true;break;}}}if(!spon&&!hasImg&&!hasBg)continue;if(!spon&&hasIm",
        "g&&!hasBg&&txt(a).length>8)continue;var p=a.parentElement;kill(a);collapse(p);}var bas=document.querySelectorA",
        "ll('a[href]');for(var i=0;i<bas.length;i++){var a=bas[i];if(!a.isConnected)continue;if(inAgeGate(a))continue;i",
        "f(isAdUrl(a.href))continue;if(isBannerAd(a)){var p=a.parentElement;kill(a);collapse(p);}}var imgs=document.que",
        "rySelectorAll('img');for(var j=0;j<imgs.length;j++){var im=imgs[j];if(!im.isConnected)continue;if(inAgeGate(im",
        "))continue;if(im.closest('a'))continue;var s=im.currentSrc||im.src||im.getAttribute('data-original')||'';if(s&",
        "&isAdUrl(s)){var q=im.parentElement;var w2=im.parentElement,hid2=false;while(w2&&w2!==document.body){var csw2=",
        "getComputedStyle(w2);if(csw2.position==='fixed'||csw2.position==='absolute'){var rw2=w2.getBoundingClientRect(",
        ");if(rw2.width>=window.innerWidth*0.6&&rw2.height>=window.innerHeight*0.35){w2.style.display='none';hid2=true;",
        "}else if(rw2.width>=window.innerWidth*0.4&&rw2.bottom>=window.innerHeight-2&&rw2.top>=window.innerHeight*0.5&&",
        "rw2.height>=60){w2.style.display='none';hid2=true;}break;}w2=w2.parentElement;}if(!hid2){kill(im);collapse(q);",
        "}continue;}if(s&&isOpaqueImg(s)){if(im.__museLb)continue;if(recentTap()&&window.__museTapImg){im.__museLb=1;co",
        "ntinue;}var cs2=getComputedStyle(im);if(cs2.position==='fixed'||cs2.position==='absolute'){var r2=im.getBoundi",
        "ngClientRect();if(r2.width>=window.innerWidth*0.4||r2.height>=window.innerHeight*0.5){var w=im.parentElement,h",
        "id=false;while(w&&w!==document.body){var csw=getComputedStyle(w);if(csw.position==='fixed'||csw.position==='ab",
        "solute'){var rw=w.getBoundingClientRect();if(rw.width>=window.innerWidth*0.4||rw.height>=window.innerHeight*0.",
        "5){w.style.display='none';hid=true;}break;}w=w.parentElement;}if(!hid)kill(im);}}}}var dvs=document.querySelec",
        "torAll('div');for(var k=0;k<dvs.length;k++){var d=dvs[k];if(!d.isConnected)continue;if(inAgeGate(d))continue;i",
        "f(inSameHostLink(d))continue;if(isContent(d))continue;if(d.querySelector&&d.querySelector('video'))continue;if",
        "(isClickTrap(d)){kill(d);continue;}if(isChallenge(d))continue;if(isModal(d)||isOpaqueModal(d)||isBigOverlay(d)",
        ")d.style.display='none';}var all=document.querySelectorAll('div,section,aside,ins,iframe');for(var m=0;m<all.l",
        "ength;m++){var el=all[m];if(!el.isConnected)continue;if(inAgeGate(el))continue;if(isChallenge(el))continue;if(",
        "inSameHostLink(el))continue;if(isContent(el))continue;if(el.querySelector&&el.querySelector('video'))continue;",
        "var hr=null;if(isAdIframe(el))hr='isAdIframe';else if(isFixedBottomIframe(el))hr='isFixedBottomIframe';else if",
        "(isBottomBar(el))hr='isBottomBar';else if(isBottomAd(el))hr='isBottomAd';else if(isBottomFlowAd(el))hr='isBott",
        "omFlowAd';else if(isFloatAd(el))hr='isFloatAd';else if(isAdLinkGrid(el))hr='isAdLinkGrid';else if(isFixedBotto",
        "mAd(el))hr='isFixedBottomAd';else if(isGenericBottomBanner(el))hr='isGenericBottomBanner';else if(isFixedBottomImageCardRow(el))hr='isFixedBottomImageCardRow';else if(isFixedBotto",
        "mImageAd(el))hr='isFixedBottomImageAd';else if(isGenericMidBanner(el))hr='isGenericMidBanner';else if(isFixedT",
                "opBanner(el))hr='isFixedTopBanner';else if(isBottomPornAd(el))hr='isBottomPornAd';if(hr){noteHide(hr,el);el.st",
        "yle.display='none';}}var bare=document.querySelectorAll('img,video');for(var bm=0;bm<bare.length;bm++){var be=",
        "bare[bm];if(!be.isConnected)continue;if(isFixedBottomBareMedia(be)){noteHide('isFixedBottomBareMedia',be);be.s",
        "tyle.display='none';}}hideSpriteBarGroup();var links=document.querySelectorAll('a[href]');for(var q=0;q<links.",
        "l",
        "ength;q++){var lk=lin",
        "ks[q];if(!lk.isConnected)continue;if(inAgeGate(lk))continue;if(isAdImageLink(lk)||isBottomPornAd(lk)){lk.style",
        ".display='none';continue;}if(foreign(lk.href)||isAdUrl(lk.href)){var fbr=fixedBottomAdRoot(lk);if(fbr){noteHid",
        "e('isFixedBottomAd',fbr);fbr.style.display='none';}}}var cbs=document.querySelectorAll('[class*=\"close\"],[id*=",
        "\"close\"]');for(var n=0;n<cbs.length;n++){var cb=cbs[n];if(!isAdClose((cb.className&&cb.className.baseVal!==und",
        "efined?cb.className.baseVal:cb.className||'')+' '+(cb.id||'')))continue;var slot=cb.closest('div,section,aside",
        ",ins');if(slot&&slot!==document.body&&!slot.querySelector('video'))slot.style.display='none';}hideMixedForeign",
        "Cards();hideNoidDiv();hideHeadingPromoLinks();hideMyavliveIframes();}function hideMyavliveIframes(){var frs=do",
        "cument.querySelectorAll('iframe');for(var i=0;i<frs.length;i++){var fr=frs[i];if(!fr.isConnected)continue;if(i",
        "sChallenge(fr))continue;if(inAgeGate(fr))continue;var s=(fr.src||fr.getAttribute('src')||'').toLowerCase();if(",
        "s.indexOf('myavlive')<0&&s.indexOf('mavrtracktor')<0)continue;var p=fr.parentElement;noteHide('myavliveIframe'",
        ",fr);kill(fr);if(p)collapse(p);}}function isNavMenu(el){if(!el||!el.querySelectorAll)return false;if(el.tagNam",
        "e==='NAV')return true;if(el.closest){try{var nv=el.closest('nav,[role=\"navigation\"]');if(nv)return true;}catch",
        "(e){}}var as=el.querySelectorAll('a[href]');if(as.length<3)return false;var same=0;for(var i=0;i<as.length;i++",
        "){if(!foreign(as[i].href))same++;}return same>=3&&same/as.length>0.6;}\nfunction isModal(el){if(!el||el===docum",
        "ent.body)return false;if(isAgeGate(el))return false;if(isChallenge(el))return false;if(isNavMenu(el))return fa",
        "lse;if(el.querySelector('video,iframe'))return false;var cs=getComputedStyle(el);if(cs.position!=='fixed'&&cs.",
        "position!=='absolute')return false;var r=el.getBoundingClientRect();if(r.width<window.innerWidth*0.6||r.height",
        "<window.innerHeight*0.35)return false;var as=el.querySelectorAll('a[href]');for(var i=0;i<as.length;i++){if(is",
        "AdUrl(as[i].href))return true;}var ims=el.querySelectorAll('img');for(var j=0;j<ims.length;j++){var s=ims[j].c",
        "urrentSrc||ims[j].src||'';if(s&&isAdUrl(s))return true;}return false;}function isBottomBar(el){if(!el||el===do",
        "cument.body)return false;if(el.querySelector('.dplayer'))return false;var cs=getComputedStyle(el);if(cs.positi",
        "on!=='fixed'&&cs.position!=='sticky'&&cs.position!=='absolute')return false;var r=el.getBoundingClientRect();i",
        "f(r.width<window.innerWidth*0.4)return false;if(r.bottom<window.innerHeight-2)return false;if(r.top<window.inn",
        "erHeight*0.5)return false;var as=el.querySelectorAll('a[href]');for(var i=0;i<as.length;i++){if(isAdUrl(as[i].",
        "href))return true;}var ims=el.querySelectorAll('img');for(var j=0;j<ims.length;j++){var s=ims[j].currentSrc||i",
        "ms[j].src||'';if(s&&isAdUrl(s))return true;}var frs=el.querySelectorAll('iframe');for(var k=0;k<frs.length;k++",
        "){var f=frs[k].src||'';if(f&&isAdUrl(f))return true;}if(el.tagName==='IFRAME'){var s2=el.src||'';if(s2&&isAdUr",
        "l(s2))return true;}return false;}function isBottomAd(el){if(!el||el===document.body)return false;if(isAgeGate(",
        "el))return false;if(el.querySelector('.dplayer'))return false;var cs=getComputedStyle(el);if(cs.position!=='fi",
        "xed'&&cs.position!=='sticky'&&cs.position!=='absolute')return false;var r=el.getBoundingClientRect();if(r.widt",
        "h<window.innerWidth*0.4)return false;if(r.bottom<window.innerHeight-2)return false;if(r.top<window.innerHeight",
        "*0.5)return false;if(r.height<60&&r.height<window.innerHeight*0.08)return false;if(el.tagName==='IFRAME')retur",
        "n true;var im=el.querySelector('img');var vd=el.querySelector('video');var bg=cs.backgroundImage||'';var bgF=f",
        "alse;if(bg.indexOf('url(')>=0){var bm=bg.match(/url\\(['\"]?([^'\")]+)['\"]?\\)/);if(bm&&bm[1]&&isAdUrl(bm[1]))bgF=",
        "true;}var fl=false;var fas=el.querySelectorAll('a[href]');for(var fi=0;fi<fas.length;fi++){if(isAdUrl(fas[fi].",
        "href)){fl=true;break;}}if(!im&&!vd&&!bgF&&!fl)return false;if(cs.position==='absolute'){var fa=false;var abs=e",
        "l.querySelectorAll('a[href]');for(var ai=0;ai<abs.length;ai++){if(isAdUrl(abs[ai].href)){fa=true;break;}}var f",
        "src='';if(im)fsrc=im.currentSrc||im.src||'';if(!vd&&!fa&&!(fsrc&&isAdUrl(fsrc)))return false;}return true;}fun",
        "ction pruneExtraVideos(){var players=document.querySelectorAll('div.dplayer');for(var i=0;i<players.length;i++",
        "){var d=players[i];if(!d.isConnected)continue;var vs=d.querySelectorAll('video');if(vs.length<2)continue;var k",
        "eep=null;for(var k=0;k<vs.length;k++){if(!vs[k].classList.contains('ads_video')){keep=vs[k];break;}}if(!keep)k",
        "eep=vs[0];for(var j=0;j<vs.length;j++){var av=vs[j];if(av===keep)continue;var wp=av.parentElement;if(av.parent",
        "Node)av.parentNode.removeChild(av);if(wp&&wp!==d&&wp.children.length===0&&wp.parentNode)wp.parentNode.removeCh",
        "ild(wp);}}}function isBottomFlowAd(el){if(!el||el===document.body)return false;if(isAgeGate(el))return false;i",
        "f(el.querySelector('video,iframe'))return false;var cs=getComputedStyle(el);if(cs.position==='fixed'||cs.posit",
        "ion==='sticky')return false;var r=el.getBoundingClientRect();if(r.width>=window.innerWidth*0.9&&r.height>=wind",
        "ow.innerHeight*0.9)return false;var _ims0=el.querySelectorAll('img');if(_ims0.length>=10)return false;var _cx=",
        "window.innerWidth/2,_cy=window.innerHeight/2;if(r.left<=_cx&&_cx<=r.right&&r.top<=_cy&&_cy<=r.bottom)return fa",
        "lse;var docBot=r.top+(window.scrollY||0)+r.height;var dh=document.documentElement.scrollHeight||0;if(docBot<dh",
        "-400)return false;if(r.width<window.innerWidth*0.5||r.height<80)return false;var cn=((el.className&&el.classNa",
        "me.baseVal!==undefined)?el.className.baseVal:(el.className||''))+' '+(el.id||'');if(/footer|copyright/i.test(c",
        "n))return false;var ims=el.querySelectorAll('img');for(var i=0;i<ims.length;i++){var s=ims[i].currentSrc||ims[",
        "i].src||'';if(isOpaqueImg(s))return true;}var as=el.querySelectorAll('a[href]');for(var j=0;j<as.length;j++){v",
        "ar a=as[j];if(isAdUrl(a.href)&&a.querySelector('img'))return true;}for(var j2=0;j2<as.length;j2++){var a2=as[j",
        "2];if(foreign(a2.href)){var im2=a2.querySelector('img');if(im2)return true;}}var fbg=cs.backgroundImage||'';if",
        "(fbg.indexOf('url(')>=0){var fbm=fbg.match(/url\\(['\"]?([^'\")]+)['\"]?\\)/);if(fbm&&fbm[1]&&isAdUrl(fbm[1]))retur",
        "n true;}return false;}function isBottomPornAd(el){if(!el||el===document.body)return false;if(inAgeGate(el))ret",
        "urn false;if(isContent(el))return false;var cs=getComputedStyle(el);var r=el.getBoundingClientRect();var isFix",
        "ed=cs.position==='fixed'||cs.position==='sticky'||cs.position==='absolute';if(isFixed){if(r.bottom<window.inne",
        "rHeight-2)return false;if(r.top<window.innerHeight*0.5)return false;}else{if(r.top<window.innerHeight*0.7)retu",
        "rn false;}if(r.width<window.innerWidth*0.4)return false;if(r.height<40||r.height>300)return false;var t=(el.in",
        "nerText||'');if(!/(约炮|兼职上门|国产自拍|性感少妇|裸聊)/.test(t))return false;if(el.querySelector('img,video'))return true;va",
        "r bg=cs.backgroundImage||'';if(bg.indexOf('url(')>=0)return true;var kids=el.querySelectorAll('*');for(var k=0",
        ";k<kids.length;k++){var kb=getComputedStyle(kids[k]).backgroundImage||'';if(kb.indexOf('url(')>=0)return true;",
        "}return false;}\nfunction isAdImageLink(el){if(!el||el.tagName!=='A')return false;if(inAgeGate(el))return false",
        ";if(isContent(el))return false;var href=el.getAttribute('href')||'';if(!href)return false;if(!foreign(href)&&!",
        "isAdUrl(href))return false;var txt=(el.innerText||'').trim();if(txt.length>8)return false;var cs=getComputedSt",
        "yle(el);var bg=cs.backgroundImage||'';if(bg.indexOf('url(')>=0)return true;var im=el.querySelector('img');if(i",
        "m){var ir=im.getBoundingClientRect();if(ir.width>=100&&ir.height>=60)return true;var src=im.currentSrc||im.src",
        "||'';if(src&&(foreign(src)||isAdUrl(src)))return true;}return false;}\nfunction isAdIframe(el){if(!el||el.tagNa",
        "me!=='IFRAME')return false;if(isAgeGate(el))return false;if(inAgeGate(el))return false;var src=el.src||el.getA",
        "ttribute('src')||'';if(!src)return false;var h='';try{h=new URL(src,location.href).hostname.toLowerCase();}cat",
        "ch(e){}if(h.indexOf('cloudflare')>=0||h.indexOf('hcaptcha')>=0||h.indexOf('recaptcha')>=0)return false;var r=e",
        "l.getBoundingClientRect();if(r.width<100||r.height<40)return false;var ar=r.width/Math.max(1,r.height);if(r.wi",
        "dth>=300&&r.height>=150&&ar>1.5&&ar<1.9)return false;if(el.hasAttribute&&el.hasAttribute('allowfullscreen')&&r",
        ".width>=300&&r.height>=150)return false;if(isAdUrl(src))return true;if(ar>=3)return true;if(!foreign(src)&&!is",
        "AdUrl(src))return false;if(ar>=1.0&&ar<=1.4&&r.width>=250&&r.height>=200)return true;return false;}function is",
        "FixedBottomIframe(el){if(!el||el.tagName!=='IFRAME')return false;if(isAgeGate(el))return false;if(isContent(el",
        "))return false;var cs=getComputedStyle(el);if(cs.position!=='fixed'&&cs.position!=='absolute'&&cs.position!=='",
        "sticky')return false;var r=el.getBoundingClientRect();if(r.width<window.innerWidth*0.4)return false;if(r.botto",
        "m<window.innerHeight-2)return false;if(r.top<window.innerHeight*0.5)return false;if(r.height<60||r.height>250)",
        "return false;var ar=r.width/Math.max(1,r.height);if(r.width>=300&&r.height>=150&&ar>1.5&&ar<1.9)return false;i",
        "f(el.hasAttribute&&el.hasAttribute('allowfullscreen')&&r.width>=300&&r.height>=150)return false;var src=el.src",
        "||el.getAttribute('src')||'';var h='';try{h=new URL(src,location.href).hostname.toLowerCase();}catch(e){}if(h.",
        "indexOf('cloudflare')>=0||h.indexOf('hcaptcha')>=0||h.indexOf('recaptcha')>=0)return false;if(src&&(foreign(sr",
        "c)||isAdUrl(src)))return true;if(!src)return true;if(ar>=3)return true;return false;}\nfunction fixedBottomAdRo",
        "ot(el){if(!el)return null;if(isAgeGate(el))return null;if(inAgeGate(el))return null;var cur=el,depth=0;while(c",
        "ur&&cur!==document.body&&depth<4){if(isFixedBottomAd(cur))return cur;cur=cur.parentElement;depth++;}return nul",
        "l;}\nfunction isFixedBottomAd(el){if(!el||el===document.body)return false;if(isAgeGate(el))return false;if(isCo",
        "ntent(el))return false;var cs=getComputedStyle(el);if(cs.position!=='fixed'&&cs.position!=='absolute'&&cs.posi",
        "tion!=='sticky')return false;var r=el.getBoundingClientRect();if(r.width<window.innerWidth*0.4)return false;if",
        "(r.bottom<window.innerHeight-2)return false;if(r.top<window.innerHeight*0.5)return false;if(r.height<60||r.hei",
        "ght>250)return false;var hasForeign=false;if(el.tagName==='IFRAME'){var fs=el.src||'';if(fs&&(foreign(fs)||isA",
        "dUrl(fs)))hasForeign=true;}var as=el.querySelectorAll('a[href]');for(var j=0;j<as.length;j++){if(foreign(as[j]",
        ".href)){hasForeign=true;break;}}if(!hasForeign){var ims=el.querySelectorAll('img');for(var i=0;i<ims.length;i+",
        "+){var s=ims[i].currentSrc||ims[i].src||'';if(s&&(foreign(s)||isAdUrl(s))){hasForeign=true;break;}}}if(!hasFor",
        "eign){var frs=el.querySelectorAll('iframe');for(var k=0;k<frs.length;k++){var f=frs[k].src||'';if(f&&(foreign(",
        "f)||isAdUrl(f))){hasForeign=true;break;}}}if(!hasForeign){var bg=cs.backgroundImage||'';if(bg.indexOf('url(')>",
        "=0){var bm=bg.match(/url\\(['\"]?([^'\")]+)['\"]?\\)/);if(bm&&bm[1]&&(foreign(bm[1])||isAdUrl(bm[1])))hasForeign=tr",
        "ue;}}if(!hasForeign)return false;var hasVisual=false;if(el.tagName==='IFRAME')hasVisual=true;else if(el.queryS",
        "elector('img'))hasVisual=true;if(!hasVisual){var bg2=cs.backgroundImage||'';if(bg2.indexOf('url(')>=0)hasVisua",
        "l=true;}if(!hasVisual){var kids=el.querySelectorAll('*');for(var k2=0;k2<kids.length;k2++){var kb=getComputedS",
        "tyle(kids[k2]).backgroundImage||'';if(kb.indexOf('url(')>=0){hasVisual=true;break;}}}if(!hasVisual&&el.querySe",
        "lector('iframe'))hasVisual=true;if(!hasVisual)return false;return true;}\nfunction isFixedBottomImageAd(el){if(",
        "!el||el===document.body)return false;if(isAgeGate(el))return false;if(isChallenge(el))return false;if(isConten",
        "t(el))return false;if(isNavMenu(el))return false;if(el.querySelector&&el.querySelector('video'))return false;v",
        "ar cs=getComputedStyle(el);if(cs.position!=='fixed'&&cs.position!=='absolute'&&cs.position!=='sticky')return f",
        "alse;var r=el.getBoundingClientRect();if(r.width<window.innerWidth*0.4)return false;if(r.bottom<window.innerHe",
        "ight-2)return false;if(r.top<window.innerHeight*0.5)return false;if(r.height<60)return false;var",
        " hasVisual=false;if(el.tagName==='IMG'||el.tagName==='IFRAME')hasVisual=true;else if(el.querySelector&&el.quer",
        "ySelector('img,iframe'))hasVisual=true;if(!hasVisual){var bg=cs.backgroundImage||'';if(bg.indexOf('url(')>=0)h",
        "asVisual=true;}if(!hasVisual){var kids=el.querySelectorAll('*');for(var k=0;k<kids.length;k++){var kb=getCompu",
        "tedStyle(kids[k]).backgroundImage||'';if(kb.indexOf('url(')>=0){hasVisual=true;break;}}}if(!hasVisual)return f",
                "alse;return true;}\nfunction isFixedBottomBareMedia(el){if(!el||el===document.body)return false;if(el.tagName!=",
                "='IMG'&&el.tagName!=='VIDEO')return false;if(isAgeGate(el))return false;if(isChallenge(el))return false;if(isC",
                "ontent(el))return false;if(isNavMenu(el))return false;if(el.closest){try{if(el.closest('.dplayer'))return fals",
                "e;}catch(e){}}var cs=getComputedStyle(el);if(cs.position!=='fixed'&&cs.position!=='absolute'&&cs.position!=='s",
                "ticky')return false;var r=el.getBoundingClientRect();if(r.width<window.innerWidth*0.4)return false;if(r.bottom",
                "<window.innerHeight-2)return false;if(r.top<window.innerHeight*0.5)return false;var s='';try{s=el.currentSrc||",
                "el.src||'';}catch(e){s=el.src||'';}var cross=false;if(s&&(foreign(s)||isAdUrl(s)))cross=true;var click=false;i",
                "f(el.getAttribute){try{if(el.getAttribute('onclick'))click=true;}catch(e2){}}if(!click){try{if(el.onclick)clic",
                "k=true;}catch(e3){}}if(!cross&&!click)return false;return true;}\nfunction isImageCard(c){if(!c||c.nodeType!==1",
                ")return false;if(c.tagName==='IMG')return fals",
        "e;var hasImg=false;var ch=c.children;for(var i=0;i<ch.length;i++){if(ch[i].tagName==='IMG'){hasImg=true;break;",
        "}}if(!hasImg){var b=getComputedStyle(c).backgroundImage||'';if(b.indexOf('url(')>=0)hasImg=true;}if(!hasImg)re",
        "turn false;return txt(c).length<=16;}\nfunction isFixedBottomImageCardRow(el){if(!el||el===document.body)return",
        " false;if(isAgeGate(el)||inAgeGate(el))return false;if(isChallenge(el))return false;if(isContent(el))return fa",
        "lse;if(el.tagName==='NAV')return false;if(el.closest){try{if(el.closest('nav,[role=\"navigation\"]'))return fals",
        "e;}catch(e){}}var isClose=function(n){if(!n||n.nodeType!==1)return false;var t=((n.textContent||'')+(n.value||",
        "'')).replace(/\\s+/g,'');if(t==='\\u00d7'||t==='\\u2715'||t==='\\u2716'||t==='\\u2573'||t==='x'||t==='X'||t==='\\u51",
        "73\\u95ed')return true;var s=((n.id||'')+' '+((n.className&&n.className.baseVal!==undefined)?n.className.baseVa",
        "l:(n.className||''))+' '+(n.getAttribute?n.getAttribute('aria-label')||'':'')).toLowerCase();if(s.indexOf('clo",
        "se')>=0||s.indexOf('dismiss')>=0)return true;return false;};if(el.querySelector&&el.querySelector('video,.dpla",
        "yer'))return false;if(el.querySelector){if(el.querySelector('form,select,textarea'))return false;var bs=el.que",
        "rySelectorAll('button,input');for(var bi=0;bi<bs.length;bi++){if(bs[bi].tagName==='INPUT'&&(bs[bi].type||'').t",
        "oLowerCase()==='hidden')continue;if(!isClose(bs[bi]))return false;}}var an=el,depth=0,found=false;while(an&&an",
        "!==document.body&&depth<=2){var cs=getComputedStyle(an);if(cs.position==='fixed'||cs.position==='absolute'||cs",
        ".position==='sticky'){found=true;break;}an=an.parentElement;depth++;}if(!found)return false;var r=an.getBoundi",
        "ngClientRect();if(r.width<window.innerWidth*0.02||r.height<window.innerHeight*0.01)return false;if(r.bottom<wi",
        "ndow.innerHeight-2)return false;if(r.top<window.innerHeight*0.5)return false;var as=el.querySelectorAll('a[hre",
        "f]');for(var i=0;i<as.length;i++){var h=as[i].getAttribute('href')||'';if(isPlaceholderHref(h))continue;if(!fo",
        "reign(as[i].href))return false;}var cards=0;var kids=el.children;for(var k=0;k<kids.length;k++){if(kids[k].tag",
        "Name==='BUTTON'||kids[k].tagName==='INPUT')continue;if(isClose(kids[k]))continue;if(isImageCard(kids[k])){card",
        "s++;continue;}var gcs=kids[k].children;for(var g=0;g<gcs.length;g++){if(gcs[g].tagName==='BUTTON'||gcs[g].tagN",
        "ame==='INPUT')continue;if(isClose(gcs[g]))continue;if(isImageCard(gcs[g]))cards++;}}if(cards<2)return false;re",
        "turn true;}\nfunction hideSpriteBarGroup(){var hidden=0;var isSpriteLike=function(el){if(!el||el.nodeType!==1||",
        "el===document.body)return false;var tag=el.tagName;if(tag==='IMG'||tag==='VIDEO'||tag==='BUTTON'||tag==='INPUT",
        "'||tag==='FORM'||tag==='SELECT'||tag==='TEXTAREA'||tag==='A'||tag==='IFRAME'||tag==='SCRIPT'||tag==='STYLE'||t",
        "ag==='NAV')return false;try{if(isContent(el))return false;if(isAgeGate(el)||inAgeGate(el))return false;if(isCh",
        "allenge(el))return false;if(el.closest){var nv=el.closest('nav,[role=\"navigation\"]');if(nv)return false;}}catc",
        "h(e){return false;}if(txt(el).length>0)return false;if(el.children&&el.children.length>0)return false;if(el.qu",
        "erySelector&&el.querySelector('a[href],img,video,button,input,form,select,textarea,iframe'))return false;var c",
        "s;try{cs=getComputedStyle(el);}catch(e2){return false;}if(!cs)return false;var pos=cs.position;if(pos!=='fixed",
        "'&&pos!=='absolute'&&pos!=='sticky')return false;var bg=cs.backgroundImage||'';if(bg.indexOf('url(')<0)return ",
        "false;var r=el.getBoundingClientRect();if(r.width<window.innerWidth*0.02||r.height<window.innerHeight*0.01)ret",
        "urn false;return true;};var isSeed=function(el){if(!isSpriteLike(el))return false;var r=el.getBoundingClientRe",
        "ct();if(r.bottom<window.innerHeight-2)return false;if(r.top<window.innerHeight*0.5)return false;return true;};",
        "var fire=function(el,type){try{el.dispatchEvent(new Event(type));return;}catch(e){}try{var ev=document.createE",
        "vent('Event');ev.initEvent(type,false,false);el.dispatchEvent(ev);}catch(e2){}};var removeSpriteStyle=function",
        "(tag){var low=tag.toLowerCase();var styles=document.querySelectorAll('style');for(var s=0;s<styles.length;s++)",
        "{var st=styles[s];var tx=st.textContent||'';if(tx.length>2000)continue;var lx=tx.toLowerCase();if(lx.indexOf(l",
        "ow)<0)continue;if(lx.indexOf('background-image')<0)continue;if(lx.indexOf('url(')<0)continue;if(st.parentNode)",
        "st.parentNode.removeChild(st);}};try{var body=document.body;if(!body)return 0;var kids=body.children;if(!kids|",
        "|kids.length===0)return 0;var groups={};var order=[];var i,j,k;for(i=0;i<kids.length;i++){var t=kids[i].tagNam",
        "e;if(!groups[t]){groups[t]=[];order.push(t);}groups[t].push(kids[i]);}for(var g=0;g<order.length;g++){var tagG",
        "=order[g];var members=groups[tagG];var likes=[];var seeds=0;var top=1000000000;for(j=0;j<members.length;j++){v",
        "ar m=members[j];if(isSpriteLike(m)){likes.push(m);var rr=m.getBoundingClientRect();if(rr.top<top)top=rr.top;if",
        "(isSeed(m))seeds++;}}if(seeds<4||likes.length<4)continue;var barH=window.innerHeight-top;var closeEl=null;var ",
        "tol=window.innerHeight*0.05;for(k=0;k<kids.length;k++){var c=kids[k];if(c.tagName===tagG)continue;if(groups[c.",
        "tagName].length!==1)continue;var ct=c.tagName;if(ct==='IMG'||ct==='VIDEO'||ct==='BUTTON'||ct==='INPUT'||ct==='",
        "FORM'||ct==='SELECT'||ct==='TEXTAREA'||ct==='A'||ct==='IFRAME'||ct==='SCRIPT'||ct==='STYLE'||ct==='NAV')contin",
        "ue;if(txt(c).length>0)continue;if(c.children&&c.children.length>0)continue;if(c.querySelector&&c.querySelector",
        "('a[href],img,video,button,input,form,select,textarea,iframe'))continue;var bad=false;try{if(isContent(c)||isA",
        "geGate(c)||inAgeGate(c)||isChallenge(c))bad=true;}catch(e3){bad=true;}if(bad)continue;var cs2;try{cs2=getCompu",
        "tedStyle(c);}catch(e4){continue;}if(!cs2)continue;var pos2=cs2.position;if(pos2!=='fixed'&&pos2!=='absolute'&&",
        "pos2!=='sticky')continue;var bg2=cs2.backgroundImage||'';if(bg2.indexOf('url(')<0)continue;var r2=c.getBoundin",
        "gClientRect();if(r2.width<=0||r2.height<=0)continue;if(r2.bottom>=window.innerHeight-2)continue;if(r2.bottom<t",
        "op-tol||r2.bottom>top+tol)continue;closeEl=c;break;}if(closeEl){fire(closeEl,'touchstart');if(closeEl.isConnec",
        "ted)fire(closeEl,'click');}for(j=0;j<likes.length;j++){likes[j].style.display='none';hidden++;}if(likes.length",
        ">0)noteHide('hideSpriteBarGroup',likes[0]);removeSpriteStyle(tagG);var pb=body.style.paddingBottom||'';if(pb){",
        "var n=parseInt(pb,10);if(!isNaN(n)&&Math.abs(n-barH)<=2)body.style.paddingBottom='';}}}catch(e){}return hidden",
        ";}\nfunction isGenericBottomBanner(el){if(!el||el===document.body)return false;if(isAgeGate(el)",
        ")return false;if(isChallenge(el))return false;if(isContent(el))return false;if(isNavMenu(el))return false;if(e",
        "l.querySelector&&el.querySelector('video'))return false;var cs=getComputedStyle(el);if(cs.position!=='fixed'&&",
        "cs.position!=='absolute'&&cs.position!=='sticky')return false;var r=el.getBoundingClientRect();if(r.width<wind",
        "ow.innerWidth*0.4)return false;if(r.bottom<window.innerHeight-60)return false;if(r.top<window.innerHeight*0.5)",
        "return false;if(r.height<60||r.height>250)return false;var hasForeign=false;if(el.tagName==='IFRAME'){var fs=e",
        "l.src||'';if(fs&&foreign(fs))hasForeign=true;}var as=el.querySelectorAll('a[href]');for(var j=0;j<as.length;j+",
        "+){if(foreign(as[j].href)){hasForeign=true;break;}}if(!hasForeign){var ims=el.querySelectorAll('img');for(var ",
        "i=0;i<ims.length;i++){var s=ims[i].currentSrc||ims[i].src||'';if(s&&foreign(s)){hasForeign=true;break;}}}if(!h",
        "asForeign){var frs=el.querySelectorAll('iframe');for(var k=0;k<frs.length;k++){var f=frs[k].src||'';if(f&&fore",
        "ign(f)){hasForeign=true;break;}}}if(!hasForeign)return false;var hasVisual=false;if(el.tagName==='IFRAME')hasV",
        "isual=true;else if(el.querySelector('img'))hasVisual=true;if(!hasVisual){var bg2=cs.backgroundImage||'';if(bg2",
        ".indexOf('url(')>=0)hasVisual=true;}if(!hasVisual){var kids=el.querySelectorAll('*');for(var k2=0;k2<kids.leng",
        "th;k2++){var kb=getComputedStyle(kids[k2]).backgroundImage||'';if(kb.indexOf('url(')>=0){hasVisual=true;break;",
                "}}}if(!hasVisual&&el.querySelector('iframe'))hasVisual=true;if(!hasVisual)return false;return true;}\nvar __mus",
                "eSameHostStats=null;function sameHostLinkStats(){if(__museSameHostStats)return __museSameHostStats;var as=docu",
                "ment.querySelectorAll('a[href]');var same=0,total=0;for(var i=0;i<as.length;i++){var h=as[i].getAttribute('hre",
                "f')||'';if(isPlaceholderHref(h))continue;total++;if(!foreign(as[i].href))same++;}__museSameHostStats={same:sam",
                "e,total:total};return __museSameHostStats;}\nfunction ",
        "isGenericMidBanner(el){if(!el||el===document.body)return false;var __st=sameHostLinkStats();if(__st.same<2||__",
        "st.same*10<__st.total)return false;if(isAgeGate(el))return false;if(isChallenge(el",
        "))return false;if(isContent(el))return false;if(isNavMenu(el))return false;if(el.querySelector&&el.querySelect",
        "or(\'video\'))return false;if(el.querySelector&&el.querySelector(\'iframe\'))return false;var cs=getComputedStyle(",
        "el);if(cs.position===\'fixed\'||cs.position===\'sticky\')return false;var r=el.getBoundingClientRect();if(r.width<",
        "window.innerWidth*0.02||r.height<window.innerHeight*0.01)return false;var pe=el.parentElement;if(!pe)return fa",
        "lse;var pr=pe.getBoundingClientRect();if(r.width<pr.width*0.8)return false;var gp=pe.parentElement;if(gp){var ",
        "gr=gp.getBoundingClientRect();if(pr.width<gr.width*0.5)return false;}var hasForeign=false;var as=el.querySelec",
        "torAll(\'a[href]\');for(var j=0;j<as.length;j++){var h=as[j].getAttribute(\'href\')||\'\';if(isPlaceholderHref(h))co",
        "ntinue;if(foreign(as[j].href))hasForeign=true;else return false;}if(!hasForeign)return false;var hasVisual=fal",
        "se;if(el.querySelector(\'img\'))hasVisual=true;if(!hasVisual){var bg=cs.backgroundImage||\'\';if(bg.indexOf(\'url(\'",
        ")>=0)hasVisual=true;}if(!hasVisual){var kids=el.querySelectorAll(\'*\');for(var k2=0;k2<kids.length;k2++){var kb",
        "=getComputedStyle(kids[k2]).backgroundImage||\'\';if(kb.indexOf(\'url(\')>=0){hasVisual=true;break;}}}if(!hasVisua",
        "l)return false;return true;}\nfunction isFixedTopBanner(el){if(!el||el===document.body)return fal",
        "se;if(isAgeGate(el))return false;if(isChallenge(el))return false;if(isContent(el))return false;if(isNavMenu(el",
        "))return false;if(el.querySelector&&el.querySelector('video'))return false;var cs=getComputedStyle(el);if(cs.p",
        "osition!=='fixed')return false;var r=el.getBoundingClientRect();if(r.width<window.innerWidth*0.4)return false;",
        "if(r.bottom>=window.innerHeight-60)return false;if(r.height<60||r.height>250)return false;var hasForeign=false",
        ";var as=el.querySelectorAll('a[href]');for(var j=0;j<as.length;j++){if(foreign(as[j].href)){hasForeign=true;br",
        "eak;}}if(!hasForeign){var ims=el.querySelectorAll('img');for(var i=0;i<ims.length;i++){var s=ims[i].currentSrc",
        "||ims[i].src||'';if(s&&foreign(s)){hasForeign=true;break;}}}if(!hasForeign)return false;var hasVisual=false;if",
        "(el.querySelector('img'))hasVisual=true;if(!hasVisual){var bg=cs.backgroundImage||'';if(bg.indexOf('url(')>=0)",
        "hasVisual=true;}if(!hasVisual){var kids=el.querySelectorAll('*');for(var k2=0;k2<kids.length;k2++){var kb=getC",
        "omputedStyle(kids[k2]).backgroundImage||'';if(kb.indexOf('url(')>=0){hasVisual=true;break;}}}if(!hasVisual&&el",
        ".querySelector('iframe'))hasVisual=true;if(!hasVisual)return false;return true;}\nfunction isAdLinkGrid(el){if(",
        "!el||el===document.body)return false;if(isAgeGate(el))return false;if(isContent(el))return false;if(el.querySe",
        "lector('video,iframe'))return false;if(el.querySelector('p'))return false;var as=el.querySelectorAll('a[href]'",
        ");if(as.length<8)return false;var total=0,foreignCount=0,shortText=0;for(var i=0;i<as.length;i++){var a=as[i];",
        "var href=a.getAttribute('href')||'';if(!href)continue;total++;if(foreign(href))foreignCount++;var t=txt(a);if(",
        "t.length>0&&t.length<=6)shortText++;}if(total<8)return false;if(foreignCount<total*0.7)return false;if(shortTe",
        "xt<total*0.7)return false;return true;}\nfunction isBannerAd(a){if(!a||a===document.body)return false;if(inAgeG",
        "ate(a))return false;if(isContent(a))return false;var href=a.getAttribute('href')||'';if(!href)return false;if(",
        "!foreign(href))return false;var im=a.querySelector('img');if(!im)return false;if(txt(a).length>8)return false;",
        "var r=im.getBoundingClientRect();if(r.width<window.innerWidth*0.4)return false;if(r.height<20)return false;ret",
        "urn true;}\nfunction isFloatAd(el){if(!el||el===document.body)return false;if(isAgeGate(el))return false;if(el.",
        "querySelector('.dplayer'))return false;var cs=getComputedStyle(el);if(cs.position!=='fixed'&&cs.position!=='ab",
        "solute')return false;var r=el.getBoundingClientRect();if(r.width>=window.innerWidth*0.6)return false;if(r.heig",
        "ht>=window.innerHeight*0.4)return false;if(r.width<20||r.height<20)return false;if(r.bottom<window.innerHeight",
        "-120)return false;if(r.top<window.innerHeight*0.4)return false;var hasClose=false;var cbs=el.querySelectorAll(",
        "'[class*=\"close\"],[id*=\"close\"]');if(cbs.length>0)hasClose=true;if(!hasClose){var t0=txt(el);if(t0.indexOf('\\u",
        "00d7')>=0||t0.indexOf('\\u2715')>=0||t0.indexOf('\\u2573')>=0)hasClose=true;}if(!hasClose)return false;var hasCa",
        "rousel=false;var t=txt(el);if(/\\d+\\s*\\/\\s*\\d+/.test(t))hasCarousel=true;if(!hasCarousel){var pg=el.querySelect",
        "orAll('[class*=\"pagination\"],[class*=\"swiper-pagination\"],[class*=\"dot\"],[class*=\"dots\"],[class*=\"indicator\"]'",
        ");if(pg.length>0)hasCarousel=true;}var hasForeign=false;var as=el.querySelectorAll('a[href]');for(var i=0;i<as",
        ".length;i++){var h=as[i].href||'';if(h&&(foreign(h)||isAdUrl(h))){hasForeign=true;break;}}if(!hasForeign){var ",
        "ims=el.querySelectorAll('img');for(var j=0;j<ims.length;j++){var s2=ims[j].currentSrc||ims[j].src||'';if(s2&&(",
        "foreign(s2)||isAdUrl(s2))){hasForeign=true;break;}}}if(!hasForeign&&el.tagName==='A'){var h0=el.href||'';if(h0",
        "&&(foreign(h0)||isAdUrl(h0)))hasForeign=true;}if(hasForeign||hasCarousel)return true;return false;}function is",
        "AdClose(cls){var t=(' '+cls+' ').toLowerCase().split(/[\\s_-]+/);var a=false,c=false;for(var i=0;i<t.length;i++",
        "){if(t[i]==='ad'||t[i]==='ads')a=true;if(t[i].indexOf('close')>=0)c=true;}return a&&c;}window.__museLastTap=0;",
        "window.__museTapImg=false;document.addEventListener('click',function(e){window.__museLastTap=Date.now();var t=",
        "e?e.target:null;var ti=false;if(t&&t.closest){if(t.closest('img'))ti=true;else{var a=t.closest('a');if(a&&a.qu",
        "erySelector('img'))ti=true;}}window.__museTapImg=ti;},true);function recentTap(){return (Date.now()-(window.__",
        "museLastTap||0))<1500;}function isClickTrap(el){if(!el||el===document.body)return false;if(el.querySelector('v",
        "ideo,iframe,img'))return false;if(txt(el).length>0)return false;var cs=getComputedStyle(el);if(cs.position!=='",
        "fixed')return false;var op=parseFloat(cs.opacity);if(!(op<0.05))return false;var r=el.getBoundingClientRect();",
        "if(r.width<window.innerWidth*0.02&&r.height<window.innerHeight*0.02)return false;return true;}function isOpaqu",
        "eModal(el){if(!el||el===document.body)return false;if(el.__museLb)return false;if(isAgeGate(el))return false;i",
        "f(isChallenge(el))return false;if(isNavMenu(el))return false;if(el.querySelector('video,iframe'))return false;",
        "var cs=getComputedStyle(el);if(cs.position!=='fixed'&&cs.position!=='absolute')return false;var r=el.getBoundi",
        "ngClientRect();if(r.width<window.innerWidth*0.4&&r.height<window.innerHeight*0.5)return false;var ims=el.query",
        "SelectorAll('img');var hasOpaque=false;for(var i=0;i<ims.length;i++){var s=ims[i].currentSrc||ims[i].src||'';i",
        "f(isOpaqueImg(s)){hasOpaque=true;break;}}if(!hasOpaque)return false;if(recentTap()&&window.__museTapImg){el.__",
        "museLb=1;return false;}return true;}function isBigOverlay(el){if(!el||el===document.body)return false;if(el.__",
        "museLb)return false;if(isAgeGate(el))return false;if(isChallenge(el))return false;if(isNavMenu(el))return fals",
        "e;if(el.querySelector('video,iframe'))return false;var cs=getComputedStyle(el);if(cs.position!=='fixed'&&cs.po",
        "sition!=='absolute')return false;var r=el.getBoundingClientRect();if(r.width<window.innerWidth*0.8||r.height<w",
        "indow.innerHeight*0.7)return false;if(!el.querySelector('img'))return false;if(recentTap()&&window.__museTapIm",
        "g){el.__museLb=1;return false;}return true;}function preRollBox(cd){var n=cd;while(n&&n!==document.body){if(n.",
        "querySelector&&n.querySelector('video'))return n;n=n.parentElement;}return cd.parentElement||cd;}function adOv",
        "erlay(cd,box){if(!box||!cd)return null;var v=box.querySelector('video');if(!v)return null;var cur=cd;while(cur",
        ".parentElement&&cur.parentElement!==box){cur=cur.parentElement;}if(cur===cd)return null;if(cur.contains&&cur.c",
        "ontains(v))return null;return cur;}function clickNeutral(el){if(!el)return;var oo=window.open;try{window.open=",
        "function(){return null;};el.click();}catch(e){}try{window.open=oo;}catch(e){}}function killPreRollOne(cd){if(!",
        "cd||!cd.isConnected)return;var box=preRollBox(cd);if(!box)return;var ov=adOverlay(cd,box);var scope=ov||box;va",
        "r img=scope.querySelector('.dplayer-pre-img');if(!img){var ims=scope.querySelectorAll('img'),best=null,ba=0;fo",
        "r(var i=0;i<ims.length;i++){var r=ims[i].getBoundingClientRect(),a=r.width*r.height;if(a>ba){ba=a;best=ims[i];",
        "}}img=best;}if(img)clickNeutral(img);var cd2=cd,box2=box,ov2=ov,img2=img;setTimeout(function(){if(!cd2.isConne",
        "cted)return;if(ov2&&ov2.parentNode){ov2.parentNode.removeChild(ov2);}else{var ns=box2.querySelectorAll('.dplay",
        "er-pre-bg,.dplayer-pre-img,.dplayer-pre-countdown');for(var k=0;k<ns.length;k++){if(ns[k].parentNode)ns[k].par",
        "entNode.removeChild(ns[k]);}var vs=box2.querySelectorAll('video');for(var vi=1;vi<vs.length;vi++){var av=vs[vi",
        "];var wp=av.parentElement;if(av.parentNode)av.parentNode.removeChild(av);if(wp&&wp!==box2&&wp.children.length=",
        "==0&&wp.parentNode)wp.parentNode.removeChild(wp);}if(img2&&img2.parentNode){var ip=img2.parentElement;img2.par",
        "entNode.removeChild(img2);if(ip&&ip!==box2&&ip.children.length===0&&ip.parentNode)ip.parentNode.removeChild(ip",
        ");}if(cd2.parentNode)cd2.parentNode.removeChild(cd2);}box2.classList.remove('dplayer-pre-playing');box2.classL",
        "ist.remove('dplayer-pre-clickable');var v=box2.querySelector('video');if(v){try{v.play();}catch(e){}}},200);}f",
        "unction killPreRoll(){var cds=document.querySelectorAll('.dplayer-pre-countdown');for(var i=0;i<cds.length;i++",
        "){killPreRollOne(cds[i]);}var els=document.querySelectorAll('div,span,button');for(var j=0;j<els.length;j++){v",
        "ar el2=els[j];if(!el2.isConnected)continue;if(el2.querySelector('video,iframe'))continue;var t=txt(el2);if(t.l",
        "ength>16)continue;if(/^(广告)?(\\d{1,2}:\\d{2}|\\d+s?|\\d+秒)后(自动)?(关闭|关)$/.test(t)){killPreRollOne(el2);}else if(/^(",
        "跳过广告|SkipAd)$/i.test(t)){var h=el2.tagName==='A'?(el2.getAttribute('href')||''):'';if(el2.tagName!=='A'||h.ind",
        "exOf('javascript:')===0)clickNeutral(el2);}}}function run(){__museSameHostStats=null;if(document.fullscreenEle",
        "ment)return;try{sweep();}",
        "catch(e){}}run();setTimeout(run,1200);setTimeout(run,3500);var sweepTimer=setInterval(run,2000);setTimeout(fun",
        "ction(){try{clearInterval(sweepTimer);}catch(e){}},300000);if(document.addEventListener){document.addEventList",
        "ener('fullscreenchange',function(){if(!document.fullscreenElement){var vs=document.querySelectorAll('video');f",
        "or(var i=0;i<vs.length;i++){var v=vs[i];if(v.style&&v.style.display==='none')v.style.display='';var p=v.parent",
        "Element;while(p&&p!==document.body){if(p.style&&p.style.display==='none')p.style.display='';p=p.parentElement;",
        "}}setTimeout(function(){run();},300);}});}if(window.addEventListener){window.addEventListener('pageshow',funct",
        "ion(){run();});}if(document.addEventListener){document.addEventListener('visibilitychange',function(){if(!docu",
        "ment.hidden)run();});}var pending=null;var ob=new MutationObserver(function(){if(pending)return;pending=setTim",
        "eout(function(){pending=null;run();},100);});if(document.documentElement)ob.observe(document.documentElement,{",
        "childList:true,subtree:true,attributes:true,attributeFilter:['style']});setTimeout(function(){try{ob.disconnec",
        "t();}catch(e){}},300000);var preOb=new MutationObserver(function(muts){for(var i=0;i<muts.length;i++){var ns=m",
        "uts[i].addedNodes;for(var j=0;j<ns.length;j++){var n=ns[j];if(!n||n.nodeType!==1)continue;var cd=null;if(n.cla",
        "ssList&&n.classList.contains('dplayer-pre-countdown'))cd=n;else if(n.querySelector)cd=n.querySelector('.dplaye",
        "r-pre-countdown');if(cd){killPreRollOne(cd);return;}}}});if(document.documentElement)preOb.observe(document.do",
        "cumentElement,{childList:true,subtree:true});setTimeout(function(){try{preOb.disconnect();}catch(e){}},600000)",
        ";})();"
    };
    private static final String CLEAN_JS;
    static {
        StringBuilder cleanSb = new StringBuilder();
        for (String p : CLEAN_JS_PARTS) cleanSb.append(p);
        CLEAN_JS = cleanSb.toString();
    }

    /** 直取页面内 video 地址（优先 currentSrc；blob: 返回 blob 供按钮降级提示）。 */
    private static final String VIDEO_PROBE =
            "(function(){try{var v=document.querySelector('video');if(!v)return '';"
                    + "var s=v.currentSrc||v.src||'';"
                    + "if(!s&&v.querySelector){var q=v.querySelector('source');if(q)s=q.currentSrc||q.src||''}"
                    + "return s}catch(e){return ''}})()";

    /**
     * r473：枚举页面全部视频地址（video/video>source/媒体链接 a[href]）——多集页面（一页多视频）
     * 出集数列表用。返回 JSON 字符串数组；blob:/空地址排除，a[href] 截 ? 后按扩展名过滤。
     */
    private static final String EPISODE_PROBE =
            "(function(){try{var out=[],seen={};"
                    + "function add(u){try{if(!u)return;if(u.indexOf('blob:')===0)return;"
                    + "if(u.indexOf('//')===0)u=location.protocol+u;"
                    + "if(/^https?:/i.test(u)&&!seen[u]){seen[u]=1;out.push(u)}}catch(e){}}"
                    + "var vs=document.querySelectorAll('video');"
                    + "for(var i=0;i<vs.length;i++){var v=vs[i];add(v.currentSrc||v.src||'');"
                    + "var ss=v.querySelectorAll('source');for(var j=0;j<ss.length;j++)add(ss[j].currentSrc||ss[j].src||'')}"
                    + "var as=document.querySelectorAll('a[href]');"
                    + "for(var k=0;k<as.length;k++){var h=as[k].href||'';"
                    + "var p=h.split('?')[0].toLowerCase();"
                    + "if(/\\.(mp4|m3u8|flv|mov|webm|avi|ts)$/.test(p))add(h)}"
                    // r479：选集导航链接（多集站）—— 媒体直链抽不到 ≥2 条时，抽「选集」页面链接：
                    // r479：选集导航链接（多集站）—— 媒体直链抽不到 ≥2 条时，抽「选集」页面链接。
                    // r483：选集形态自适应重写（治「假集数」与「javascript:; 真选集漏收」，真机实证 nnyy.la）：
                    //   ① 同 vid 过滤：/vodplay/{vid}-{sid}-{nid}.html 型链接必须与当前页同 vid 才收 ——
                    //      「猜你喜欢」推荐位是跨 vid 的 vodplay 链接（30059/99331/121145），原判据全收
                    //      → 集数面板 7 条全是别的视频 → ExoPlayer 播 HTML 页 MALFORMED（用户报「选集不全/播不了」）；
                    //      无 vid 可比（slug 型 URL）时回退「容器≥2兄弟 或 文本像集数+容器像选集」旧判据不回归。
                    //   ② onclick/data-nid 合成：真选集是 <li data-nid=N><a href="javascript:;"
                    //      onclick="play(sid,N)">第N集</a></li> —— href 非法被跳过，137 个真选集全漏
                    //      → 用当前页 URL 的 {vid}-{sid}-{nid} 模板逐集替换 nid 合成选集页 URL（≥2 条才收）。
                    + "if(out.length<2){"
                    // r481：移除 /sid/N、/nid/N —— 那是「详情页分页」的通用形态
                    //   （xiezhen8 实证：8.php/topic/detail/id/N/sid/M.html 是图片集分页，不是选集），
                    //   把它当选集 → 图片站被判「N 集视频」→ 图片查看器被视频播放器顶掉。
                    + "var EPHREF=/\\/(play|vodplay|episode|watch|view)\\b|\\/vod\\/play\\b/i;"
                    + "var EPTXT=/第\\s*\\d{1,3}\\s*[集话]|^EP\\s*\\d{1,3}$/i;"
                    + "var EPSIG=/(play|episode|list|选集|剧集|集数|playlist)/i;"
                    + "var cnt={},eps={},aa=document.querySelectorAll('a[href]'),tSig=String(document.title||'');"
                    + "var cur=(location.href||'').split('?')[0];"
                    + "var CM=/(\\d+)-(\\d+)-(\\d+)(\\.html?)?$/;"
                    + "var mc=cur.match(CM),vid=mc?mc[1]:'';"
                    + "for(var m=0;m<aa.length;m++){"
                    + "var A=aa[m],hh=A.href||'',tt=(A.textContent||'').replace(/\\s/g,'');"
                    + "var par=A.parentElement;if(!par)continue;"
                    + "var sig=String(par.className||'')+' '+(par.id||'');"
                    // r483-②：真选集合成 —— data-nid（自身/父容器）或 onclick play(sid,N) 提取集号
                    + "var nidA=A.getAttribute('data-nid')||'';"
                    + "if(!nidA)nidA=par.getAttribute('data-nid')||'';"
                    + "if(!nidA){var oc=String(A.getAttribute('onclick')||'');"
                    + "var pm=oc.match(/play\\s*\\(\\s*['\"]?[\\w-]+['\"]?\\s*,\\s*['\"]?(\\d+)['\"]?\\s*\\)/i);if(pm)nidA=pm[1]}"
                    + "if(nidA&&vid&&(EPTXT.test(tt)||EPSIG.test(sig)||/^\\d{1,3}$/.test(tt))){"
                    + "var u2=mc?cur.replace(CM,mc[1]+'-'+mc[2]+'-'+nidA+'$4'):'';"
                    + "if(u2&&u2!==cur)eps[u2]=1}"
                    // r483-①：同 vid 过滤 —— EPHREF 链接与当前页不同 vid 一律排除（推荐位）；无 vid 回退旧证据不回归
                    + "if(!/^https?:/i.test(hh))continue;"
                    + "var sib=par.querySelectorAll('a[href]').length>=2;"
                    + "var lm=hh.split('?')[0].match(CM);"
                    + "if(EPHREF.test(hh)){"
                    + "if(vid&&lm){if(lm[1]!==vid)continue}"
                    + "else if(!(sib||(EPTXT.test(tt)&&(EPSIG.test(sig)||EPSIG.test(tSig)))))continue}"
                    + "else{if(!(EPTXT.test(tt)&&(EPSIG.test(sig)||EPSIG.test(tSig))))continue;"
                    + "if(!sib)continue}"
                    + "if(!cnt[hh])cnt[hh]=1}"
                    + "var ks=Object.keys(cnt),ek=Object.keys(eps);"
                    // r483：合成选集优先（真集数）；合成不足 2 条才回退导航链接（可能是推荐位噪声）
                    + "if(ek.length>=2&&ek.length<=200){for(var q=0;q<ek.length;q++)add(ek[q])}"
                    + "else if(ks.length>=2&&ks.length<=200){for(var q2=0;q2<ks.length;q2++)add(ks[q2])}"
                    + "}"
                    + "return JSON.stringify(out)}catch(e){return '[]'}})()";

    /** r489：线路探测 —— 苹果 CMS 型播放页的线路 tab + 选集面板（麦田 mty8/mtyy2 实测）：
     *   tab：.anthology-tab a.vod-playerUrl（文本=线路名，.badge=集数，data-form=源标识）；
     *   面板：.anthology-list-box，按文档顺序与 tab 一一对应，每面板内 a[href] 为该线路的集。
     *   返回 {"lines":[{"name":"NB源","badge":"268","episodes":[{"name":"01","url":"…"},…]},…]}；
     *   无 tab 或面板数量对不上时返回空线路，走 sid 分组兜底。 */
    private static final String LINE_PROBE =
            "(function(){try{var tabs=document.querySelectorAll('.anthology-tab a.vod-playerUrl');"
                    + "var panels=document.querySelectorAll('.anthology-list-box');"
                    + "if(tabs.length<2||panels.length!==tabs.length)return JSON.stringify({lines:[]});"
                    + "var lines=[];"
                    + "for(var i=0;i<tabs.length;i++){var t=tabs[i];"
                    + "var badge=t.querySelector('.badge');var bc=badge?(badge.textContent||'').replace(/\\s/g,''):'';"
                    + "var name=(t.textContent||'').replace(/\\s/g,'');if(bc)name=name.replace(bc,'');"
                    + "var eps=[],seen={};var as=panels[i].querySelectorAll('a[href]');"
                    + "for(var j=0;j<as.length;j++){var h=as[j].href||'';var nm=(as[j].textContent||'').replace(/\\s/g,'');"
                    + "if(!h||!nm||seen[h])continue;seen[h]=1;eps.push({name:nm,url:h})}"
                    + "if(eps.length>0)lines.push({name:name||('线路'+(i+1)),badge:bc,episodes:eps})}"
                    + "return JSON.stringify({lines:lines})}catch(e){return JSON.stringify({lines:[]})}})()";

    /** r474：图集探测——页面大图 ≥3 张判图集页，交内置图片查看器（用户需求 4）。
     *  r477：改「正文容器优先 + 兜底全局尺寸」双态（真机实证 meizi5 详情页正文 8 张只探到 2 张）：
     *   ① 正文容器（entry-content/post-content/article-content/content-area 等通用形态）内的图
     *      **不看尺寸**（懒加载未完成时 naturalWidth=0，旧判据直接 continue → 详情页被判「无内容」）；
     *   ② data-src/data-original 优先于 src（懒加载占位时 im.src 是 lazy.gif 非空 → 旧顺序永远取不到真图）；
     *   ③ 排杂质：推荐位/头像/logo 类名与 _face. 封面特征、占位图 URL
     *      （真机实证：抓到的 32 张里混入 VOL_350_face.jpg 等首页列表推荐封面）；
     *   ④ 无正文容器时退回全局 ≥600（旧行为，保既有图集站不回归）。
     */
    private static final String IMG_PROBE =
            "(function(){try{var out=[],seen={},lpSeen={},lpCnt=0;"
                    + "function add(u){try{if(!u||u.indexOf('data:')===0||seen[u])return;seen[u]=1;"
                    + "if(u.indexOf('//')===0)u=location.protocol+u;out.push(u)}catch(e){}}"
                    // r479：图片真源属性自适应 —— 各站懒加载属性名不同（tuiimg 用自定义的 realsrc，
                    //   还有 data-echo/data-actualsrc/lazysrc…）；先扫已知白名单，再扫描未知属性兜底，
                    //   值必须「像图片 URL」（// 或 http 开头）才算。治「取不到真图 → 图=0/1」。
                    + "function realSrcOf(im){\n"
                    + "  var C=['data-src','data-original','data-lazy-src','data-lazysrc','realsrc','data-realsrc','data-echo','data-actualsrc','data-url','data-img','data-image','lazysrc','origin-src','_src','x-src'];\n"
                    + "  for(var i=0;i<C.length;i++){var v=im.getAttribute(C[i]);if(v&&(v.indexOf('//')===0||v.indexOf('http')===0))return v}\n"
                    + "  var at=im.attributes;\n"
                    + "  for(var j=0;j<at.length;j++){\n"
                    + "    var n=at[j].name,v2=at[j].value;\n"
                    + "    if(n==='src'||n==='alt'||n==='width'||n==='height'||n==='class'||n==='id'||n==='style'||n==='title'||n==='loading'||n==='sizes'||n==='srcset')continue;\n"
                    + "    if(!v2||!(v2.indexOf('//')===0||v2.indexOf('http')===0))continue;\n"
                    + "    var lv=v2.toLowerCase();\n"
                    + "    if(lv.indexOf('.jpg')>0||lv.indexOf('.jpeg')>0||lv.indexOf('.png')>0||lv.indexOf('.webp')>0||lv.indexOf('.gif')>0)return v2;\n"
                    + "  }\n"
                    + "  return '';\n"
                    + "}\n"
                    + "var BAD=/(attachment-|gridzone|related|recommend|thumbnail|avatar|logo|icon|placeholder|spacer|banner|ad[-_]|_face\\.)/i;"
                    + "var PH=/(lazy|placeholder|blank|spacer|loading|default|grey|\\.gif$)/i;"
                    + "var SEL=['.entry-content','.post-content','.article-content','.article-body','.content-area','.main-content','#content','article'];"
                    + "var roots=[];"
                    + "for(var k=0;k<SEL.length;k++){var ns=document.querySelectorAll(SEL[k]);for(var q=0;q<ns.length;q++)roots.push(ns[q])}"
                    + "if(!roots.length)roots=[document];"
                    + "for(var r=0;r<roots.length;r++){"
                    + "var inRoot=(roots[r]!==document);"
                    + "var is=roots[r].querySelectorAll('img');"
                    + "for(var i=0;i<is.length&&out.length<120;i++){var im=is[i];"
                    + "var cls=String(im.className||'');if(BAD.test(cls))continue;"
                    + "var ds=realSrcOf(im);"
                    + "var rc=im.currentSrc||im.src||'';"
                    + "if(!ds&&PH.test(rc))continue;"
                    + "var u=ds||rc;if(!u)continue;"
                    + "if(BAD.test(u))continue;"
                    // r477：素材目录兜底 —— 站点公共素材（logo/图标/背景）常在根级 image(s)/img/static/assets
                    //   目录，而正文图在 uploads/content/photo/media 下（真机实证 meizi5 /image/xiaojiejie.png
                    //   被收进图集）；该形态过滤零依赖渲染尺寸，双保险。
                    + "var ASSET=/\\/(image|images|img|static|assets|templates|style|skin|theme|public)s?\\//i;"
                    + "if(inRoot&&ASSET.test(u)&&!/(upload|content|photo|pic|file|media|attach)/i.test(u))continue;"
                    + "var w=im.naturalWidth||0,h=im.naturalHeight||0;"
                    + "if(inRoot){if(w>0&&w<600)continue;}else{if(w<600||h<600)continue;}"
                    + "var pa=im.closest?im.closest('a'):null;"
                    + "if(pa&&pa.href&&!/\\.(jpe?g|png|webp|gif)(\\?|#|$)/i.test(pa.href)&&!lpSeen[pa.href]){lpSeen[pa.href]=1;lpCnt++}"
                    + "add(u)}}"
                    // r486：列表页判据 lp —— 分类/书库页封面图各挂互异「文档链接」锚（x33yq 分类页
                    //   18 张封面全在 <a href="/xiaoshuo_N.html"> 里 → 旧判据把分类页劫持进图片查看器，
                    //   真机日志「图=18 → 🖼 查看图片」）。≥8 个互异文档链接（非图片扩展名）→ 判列表页
                    //   不收图；图集页正文图要么无 <a>、要么 <a> 指向大图文件（图片扩展名不计入）→ 零误伤。
                    + "if(lpCnt>=8)return '[]';"
                    + "return JSON.stringify(out)}catch(e){return '[]'}})()";

    /** r474：音频探测——audio/audio>source 直链 + a[href 音频扩展名]，交播放器（用户需求 4）。 */
    private static final String AUDIO_PROBE =
            "(function(){try{var out=[],seen={};"
                    + "function add(u){try{if(!u||u.indexOf('blob:')===0||seen[u])return;seen[u]=1;"
                    + "if(u.indexOf('//')===0)u=location.protocol+u;out.push(u)}catch(e){}}"
                    + "var as=document.querySelectorAll('audio');"
                    + "for(var i=0;i<as.length;i++){add(as[i].currentSrc||as[i].src||'');"
                    + "var ss=as[i].querySelectorAll('source');for(var j=0;j<ss.length;j++)add(ss[j].currentSrc||ss[j].src||'')}"
                    + "var ls=document.querySelectorAll('a[href]');"
                    + "var re=/\\.(mp3|m4a|flac|wav|aac|ogg)(\\?|#|$)/i;"
                    + "for(var k=0;k<ls.length&&out.length<40;k++){var h=ls[k].href||'';"
                    + "if(re.test(h))add(h)}"
                    + "var sc=document.querySelectorAll('script');"
                    + "var re2=/https?:\\/\\/[^\"'\\s\\\\]+\\.(?:mp3|m4a|aac|flac|wav|ogg)(?=[\"'?\\s]|$)/gi;"
                    + "for(var m=0;m<sc.length&&out.length<40;m++){var t=sc[m].textContent||'';"
                    + "var mm;while((mm=re2.exec(t))!==null&&out.length<40)add(mm[0])}"
                    + "return JSON.stringify(out)}catch(e){return '[]'}})()";

    /** r474：正文探测——找页面最大文字块（>2000 字判小说页），交内置阅读器（用户需求 4）。
     *  r479：改为「限定正文容器 + 排除公共块 + 无容器不判」——真机实证 gaze.red 视频页
     *  被抽出 11671 字「正文」，实际是**站点全局模板**（打赏墙 + 评论列表）：换另一个视频页
     *  抽出的文本字节级同值。原实现「取页面最长文字块」必然命中这种模板块 → 视频页被判小说。 */
    private static final String TEXT_PROBE =
            "(function(){try{"
                    + "var SEL=['.entry-content','.post-content','.article-content','.article-body','.content-area','.main-content','.chapter-content','.read-content','.novel-content','.detail-content','.show-content','#content','.content','.chapter','.txt','#txt','.text','.read','.article','article'];"
                    + "var EXC=/(comment|review|reward|donate|footer|sidebar|nav|header|menu|related|recommend|reply|pinglun|dashang|打赏|评论|公告|声明|友情|ad[-_]|banner|推荐|猜你|热门)/i;"
                    + "var best='',n=0;"
                    + "for(var k=0;k<SEL.length;k++){"
                    + "var ns=document.querySelectorAll(SEL[k]);"
                    + "for(var i=0;i<ns.length;i++){"
                    + "var el=ns[i];"
                    + "var sig=String(el.className||'')+' '+(el.id||'');"
                    + "if(EXC.test(sig))continue;"
                    + "var t=el.innerText||'';"
                    + "var l=t.replace(/\\s+/g,'').length;"
                    + "if(l>n){n=l;best=t}"
                    + "}}"
                    + "if(n<50){n=0;best=''}"
                    // r480：同时回传「视频信号词」计数 —— 真机实证（日志 11:00:29 文字=3666 图=89 视频=false → 🖼 查看图片）：
                    //   视频站详情页 DOM 无 <video>（播放器在 iframe/后注入）、网络也没嗅到媒体请求时
                    //   hasVideo=false → 按钮被判「查看图片」；而视频页正文/简介里必然出现
                    //   「选集/线路/播放/第N集/更新至」等语义词，用它做页面级视频信号。
                    + "var vs=(best.match(/选集|线路|播放|更新至|连载|第\\d{1,4}集|全\\d{1,4}集|在线观看|片源|高清|HD|BD/g)||[]).length;"
                    // r482：结构视频信号 v2 —— 治「视频判图片」（gaze.red 型：视频在 iframe/JS 播放器、
                    //   DOM 无 video、网络没嗅到 → 旧判据全空 → 图集分流劫持）。静态取证：gaze.red play 页
                    //   静态 HTML 是 14888 字 JS 壳（零 iframe/零信号词），内容全靠 JS 渲染；URL /play/{hex}
                    //   是最稳信号，辅以播放器 iframe/容器与 script 内联流地址。
                    //   反向安全（cmlwsn 图集首页）：静态 HTML 零 video/零 iframe/零 m3u8（广告视频是
                    //   fixed_ui 脚本后注入，探测时点 DOM 尚无）→ v2=0 不误伤图集页。
                    + "var v2=0;"
                    + "try{"
                    + "var hl=location.href.toLowerCase();"
                    + "if(/\\/(vodplay|bofang|watch|episode|play)(\\/|\\?)/.test(hl))v2++;"
                    + "var fs=document.querySelectorAll('iframe[src]');"
                    + "for(var q=0;q<fs.length;q++){if(/player|embed|bofang|\\/play/i.test(fs[q].src||'')){v2++;break}}"
                    + "if(document.querySelectorAll('[class*=player],[id*=player]').length)v2++;"
                    + "var ss=document.querySelectorAll('script');"
                    + "for(var q=0;q<ss.length;q++){if(/\\.m3u8|\\.mp4/.test(ss[q].textContent||'')){v2++;break}}"
                    + "}catch(e){}"
                    // r482：图集切换信号 gs —— 治「懒加载单图图集」（tuiimg 型：页面只有 1 张当前图 +
                    //   goshownext() 点击切换，IMG_PROBE 只收 1 张 → 图=1 < 3 → 判「无内容」→ 用户点播放报失败）。
                    //   信号：① 正文 img 带 onclick 切换（goshownext/showpic…）；② script 内联连续编号数组
                    //   [id,"dir/",cur,total]（总数可靠，沙箱实证 _pd=[4146,"009/4146/",1,33,0,4145,0,228]）。
                    //   反向安全：视频站详情页无 img[onclick] 切换；maccms 播放数据是 JSON 对象非此数组形态。
                    + "var gs=0;"
                    + "try{"
                    + "var im=document.querySelectorAll('img[onclick]');"
                    + "for(var q=0;q<im.length;q++){if(/next|show|swap|change|turn/i.test(im[q].getAttribute('onclick')||'')){gs++;break}}"
                    + "var sc=document.querySelectorAll('script');"
                    + "for(var q=0;q<sc.length;q++){if(/\\[\\s*\\d+\\s*,\\s*\"[^\"]*\\/\"\\s*,\\s*\\d+\\s*,\\s*\\d{1,4}\\s*[,\\]]/.test(sc[q].textContent||'')){gs++;break}}"
                    + "}catch(e){}"
                    // r486：小说结构信号 nv —— 治「小说无法进阅读器/只读单章」：
                    //   ① URL 含小说路径词（/read/ /book/ /chapter/ /novel/ /txt/ /xiaoshuo/ /xs/）；
                    //   ② 页面 ≥3 个「第N章/节/回/卷」锚且与首个锚**同目录** —— 真目录页章节锚同
                    //     /read/{book}/；分类页推荐块散布在不同书目录 → 不误触发（x33yq /type/ 实测 19 锚全异目录）。
                    //   真机实证 x33yq 章节页正文 829~1754 字 < 2000 旧阈值判「无内容」；
                    //   反向安全：视频站「第N集」、漫画站「第N话」不命中；图集站 URL 无小说词。
                    + "var nv=0;"
                    + "try{"
                    + "var hl=location.href.toLowerCase();"
                    + "if(/\\/(read|novel|book|chapter|txt)\\//.test(hl)||/xiaoshuo|\\/xs\\//.test(hl))nv++;"
                    + "var ca=document.querySelectorAll('a');"
                    + "var cn=0,cdir='';"
                    + "for(var q=0;q<ca.length&&cn<3;q++){var ct=(ca[q].textContent||'').replace(/\\s+/g,'');if(/^第[一二三四五六七八九十百千零〇\\d]{1,7}[章节回卷]/.test(ct)){var ch=ca[q].href||'';var cd=ch.substring(0,ch.lastIndexOf('/')+1);if(!cdir)cdir=cd;if(cd===cdir)cn++}}"
                    + "if(cn>=3)nv++;"
                    + "}catch(e){}"
                    + "return JSON.stringify({len:n,text:best.slice(0,8000),vid:vs,v2:v2,gs:gs,nv:nv})}catch(e){return '{\"len\":0,\"text\":\"\",\"vid\":0,\"v2\":0,\"gs\":0,\"nv\":0}'}})()";

    private ActivityBrowserBinding binding;
    private View customView;                    // 3.39：全屏视频的 custom view（null=未全屏）
    private WebChromeClient.CustomViewCallback customViewCallback;
    private String home;                    // 入口首页
    private java.util.List<String> mCandidates; // r475：候选镜像域名列表（按顺序轮换）
    private int mCandIdx = 0;                   // r475：当前候选下标
    private static final long CANDIDATE_TIMEOUT_MS = 6000; // 候选短超时：6 秒没收到任何东西就跳下一个
    private int candidateGen = 0;               // 候选代次（防旧定时器误触）
    private boolean candidateFinished;          // 当前候选主文档是否已完成
    private Runnable candidateTimer;            // 候选短超时定时器
    private String mSiteKey = "";               // r475：源 key（记活域名用）
    private boolean mWebHomeMode;               // 1.17：当前是否在 HTML 首页模式
    private WebHomeBridge mWebHomeBridge;       // 1.17：HTML 首页的桥（只在模板页挂载）

    /** 1.17：导入本地模板（系统文件选择器，不限类型；.html 单个导入，.zip 解包全导入，拷进模板目录并切过去）。 */
    private final ActivityResultLauncher<String[]> mTemplateImportLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri == null) return;
                new Thread(() -> {
                    WebHomeTemplates.ImportResult r = WebHomeTemplates.importFrom(BrowserActivity.this, uri);
                    runOnUiThread(() -> {
                        if (r == null) {
                            Notify.show("导入失败，请选 .html 模板或 .zip 模板包");
                            return;
                        }
                        WebHomeTemplates.setCurrent(BrowserActivity.this, r.first);
                        Notify.show(r.count > 1 ? "已导入 " + r.count + " 个模板" : "已导入模板：" + r.first);
                        if (mWebHomeMode) {
                            binding.browserWeb.loadUrl(WebHomeTemplates.urlFor(BrowserActivity.this, r.first));
                        }
                    });
                }).start();
            });
    private volatile String lastSniff;      // 最近嗅探到的媒体直链
    private volatile long sniffAt;          // 嗅探时间戳（过期判定）
    private boolean uaRetried;              // UA 降级重试只做一次
    private final ConcurrentHashMap<String, Integer> netBlock = new ConcurrentHashMap<>();   // r472：网络层拦截聚合（host→次数）
    private volatile String lastAdStat = "";                                                // r472：JS 清理统计去重（值变化才记）
    private String pageTitle;               // r473：当前页标题（集数面板/播放器标题用）
    private volatile String pageHost;       // r473：当前页 host（广告拦截本站豁免基准）
    private volatile String homeHost;       // r473：入口站 host（豁免基准）
    private int probeGen = 0;               // r483：探测代次（导航递增，旧复探作废）
    private boolean reprobed = false;       // r483：本页是否已延迟复探过（只补一次，防循环）
    private int adBounce = 0;               // r487：广告落地页弹回计数（正常页清零；连续弹回上限 3 防循环）
    // Muse v61~v93：发布页/中转页跳转链。只有仍在跳转链中的页面可跨主域，落地真实站后恢复严格拦截。
    private String anchorUrl = "";
    private String jumpHost = "";
    private boolean userGestured = false;
    private boolean followJumpHost = false;
    private boolean jumpUsed = false;
    private String jumpOriginHost = null;
    private String jumpAnchorHost = null;
    private String jumpRelayHost = null;
    private long jumpChainUntil = 0;
    private boolean aiFixRunning = false;   // 3.68 B：真站进行中（防重复点）
    // Muse v56/v100：Cookie 持久化与空白页延时复核回滚。
    private boolean blankRecovered = false;
    private Runnable blankRecheck;
    private Runnable forceShowWeb;   // 3.73：先藏页等首扫兜底 —— CLEAN_JS 回调 5 秒不回就强制显示，页面不一直藏着
    private final LinkedHashMap<String, Boolean> mediaSeen = new LinkedHashMap<>();   // r473：本页媒体收集（有序去重，集数列表数据源）

    /** r479：媒体去重键 —— .m3u8 按目录归一（同目录的 index/master/720p/1080p 等变体 = 同一视频），
     *  其它媒体取去查询串的完整 URL。治「单集页被网络嗅探收成 2 条 → 合成集数=2」。 */
    private static String mediaKeyOf(String u) {
        if (TextUtils.isEmpty(u)) return "";
        String s = u.split("\\?")[0].toLowerCase();
        if (!s.contains(".m3u8")) return s;
        int i = s.lastIndexOf('/');
        return i > 0 ? s.substring(0, i) : s;
    }

    /** r482：m3u8 目录祖先归一 —— master（…/TxFKJ2Mr/index.m3u8）与其码率变体
     *  （…/TxFKJ2Mr/1500kb/hls/index.m3u8）目录呈祖先关系 = 同一视频只算一集。
     *  真机实证：fhv2026 广告视频 master+variant 两条 m3u8 都进 mediaSeen（r479 按目录归一，
     *  但变体在子目录 → 归一失败）→ cmlwsn 图集首页弹出「集数面板 2 集」全是广告。
     *  仅 m3u8 参与（mp4 目录嵌套是不同集，不并）。 */
    private static boolean isM3u8Ancestor(String u1, String u2) {
        if (u1 == null || u2 == null) return false;
        String a = u1.split("\\?")[0], b = u2.split("\\?")[0];
        if (!a.toLowerCase().contains(".m3u8") || !b.toLowerCase().contains(".m3u8")) return false;
        String ka = mediaKeyOf(a), kb = mediaKeyOf(b);
        return ka.startsWith(kb + "/") || kb.startsWith(ka + "/");
    }

    /** r481：本地媒体直链判定（视频/音频容器；用于「选集误命中」的反例护栏）。 */
    private static boolean looksLikeMediaUrl(String u) {
        if (TextUtils.isEmpty(u)) return false;
        String s = u.split("\\?")[0].toLowerCase();
        return s.matches(".*\\.(mp4|m3u8|mpd|flv|mov|webm|avi|ts|mkv|mp3|m4a|aac|flac|wav|ogg)$");
    }
    private volatile boolean hasVideo;                          // r474：页面有视频（内容分流基准）
    private volatile int pageTextLen;                           // r474：正文长度（>2000 字判小说页）
    private volatile int pageVidSig;                             // r480：页面视频信号词计数（DOM 无 video 时的兜底判据）
    private volatile int pageVidStruct;                          // r482：结构视频信号计数（播放路径/播放器 iframe/容器/内联流地址）
    private volatile int pageGseq;                               // r482：图集切换信号（img[onclick] 切换 / 内联编号数组）
    private volatile int pageNovel;                              // r486：小说结构信号（URL 小说路径词 / ≥3 个「第N章」锚）
    private volatile String pageText = "";                      // r474：正文内容（novel:// 载荷）
    private volatile List<String> pageImages = new ArrayList<>();   // r474：大图列表（≥3 判图集页）
    private volatile List<String> pageAudios = new ArrayList<>();   // r474：音频直链列表

    public static void start(Activity activity, String url, String title) {
        if (activity == null || TextUtils.isEmpty(url)) return;
        Intent intent = new Intent(activity, BrowserActivity.class);
        intent.putExtra("url", url);
        intent.putExtra("title", title);
        activity.startActivity(intent);
    }
    /** 1.17：带源 key 入口（网页源进 HTML 首页用；key 为空时按首页 host 在网页源清单里匹配）。 */
    public static void start(Activity activity, String url, String title, String siteKey) {
        if (activity == null || TextUtils.isEmpty(url)) return;
        Intent intent = new Intent(activity, BrowserActivity.class);
        intent.putExtra("url", url);
        intent.putExtra("title", title);
        intent.putExtra("siteKey", siteKey == null ? "" : siteKey);
        activity.startActivity(intent);
    }
    /** r475：带候选镜像列表 + 源 key 入口；加载失败按列表顺序轮换，并记活域名。 */
    public static void start(Activity activity, java.util.List<String> candidates, String title, String key) {
        if (activity == null || candidates == null || candidates.isEmpty()) return;
        Intent intent = new Intent(activity, BrowserActivity.class);
        intent.putStringArrayListExtra("candidates", new ArrayList<>(candidates));
        intent.putExtra("url", candidates.get(0));
        intent.putExtra("title", title);
        intent.putExtra("siteKey", key);
        activity.startActivity(intent);
    }

    /**
     * AVBox 的 BaseActivity.onCreate 先 setContentView(ComposeView) 再调 init()；
     * 浏览器是 ViewBinding 页，这里再 setContentView 一次换掉 ComposeView（可重复调用）。
     * 浏览页不套壳壁纸，保持网页原色。
     */
    @Override
    protected void init() {
        binding = ActivityBrowserBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        applyToolbarTheme();
        initView(null);
        initEvent();
    }

    /** r477：工具栏跟随应用主题。应用是 Compose Material3 主题（AppThemeState），不是 Android night-mode，
     *  静态 @color/window_background 永远是浅色，黑色主题下工具栏一直是白色。改用 ViewTheme 的 surface/onSurface。 */
    private void applyToolbarTheme() {
        if (binding == null) return;
        int surface = ViewTheme.INSTANCE.surfaceArgb(this);
        int onSurface = ViewTheme.INSTANCE.onSurfaceArgb(this);
        binding.browserToolbar.setBackgroundColor(surface);
        binding.browserBack.setColorFilter(onSurface);
        binding.browserTitle.setTextColor(onSurface);
        binding.browserWebHome.setTextColor(onSurface);
        binding.browserAiFix.setColorFilter(onSurface);
        binding.browserReload.setColorFilter(onSurface);
        refreshAiPassTint();
        refreshAiProcessTint();
    }

    private void initView(Bundle savedInstanceState) {
        home = getIntent().getStringExtra("url");
        // r475：候选镜像列表与源 key；无显式候选时用 home 兜底成单元素列表
        mCandidates = getIntent().getStringArrayListExtra("candidates");
        mSiteKey = getIntent().getStringExtra("siteKey") == null ? "" : getIntent().getStringExtra("siteKey");
        mCandIdx = 0;
        if ((mCandidates == null || mCandidates.isEmpty()) && !TextUtils.isEmpty(home)) {
            mCandidates = new ArrayList<>();
            mCandidates.add(home);
        }
        anchorUrl = home == null ? "" : home;
        homeHost = hostOf(home);   // r473：本站豁免基准
        jumpHost = homeHost;       // Muse v75：入口默认按发布页处理，真实站落地后自动结束跳转链
        String title = getIntent().getStringExtra("title");
        binding.browserTitle.setText(TextUtils.isEmpty(title) ? "浏览器" : title);
        initWeb();
        applySystemBarInsets();
        DebugLogStore.add("browser", "打开 " + home);
        initWebHomeButton();
        // 1.17：该网页源开了 HTML 首页开关 → 先显示 html 模板（模板里「打开原网页」可回原站）。
        if (shouldStartWebHome()) {
            enterWebHomeMode();
        } else if (!TextUtils.isEmpty(home) && !isLocalServerUrl(home)) {
            binding.browserWeb.loadUrl(home);
        } else if (!TextUtils.isEmpty(home) && isLocalServerUrl(home)) {
            DebugLogStore.add("browser", "忽略本地服务地址，不作为浏览首页: " + home);
        }
    }

    // ============ 1.17 WebHome：网页源 HTML 首页 ============

    /**
     * 当前入口是不是网页源：有 key 直接取源；没 key 时按首页 host 在网页源清单里匹配
     * （从管理页/长按菜单进来的入口不带 key，靠这个兜底；匹配到就把 mSiteKey 补上）。
     */
    private SourceBean webHomeSource() {
        try {
            if (!TextUtils.isEmpty(mSiteKey)) {
                SourceBean bean = ApiConfig.get().getSource(mSiteKey);
                if (bean != null && "web".equals(bean.getApi())) return bean;
            }
            if (!TextUtils.isEmpty(homeHost)) {
                for (String[] item : WebSourceStore.list()) {
                    if (item.length > 1 && homeHost.equals(WebSourceStore.host(item[1]))) {
                        SourceBean bean = WebSourceStore.toSite(item[0], item[1]);
                        if (bean != null) {
                            mSiteKey = bean.getKey();
                            return bean;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private boolean canWebHome() {
        return webHomeSource() != null;
    }

    private boolean shouldStartWebHome() {
        SourceBean bean = webHomeSource();
        return bean != null && WebSourceStore.isWebHome(bean.getExt());
    }

    private void initWebHomeButton() {
        binding.browserWebHome.setVisibility(canWebHome() ? View.VISIBLE : View.GONE);
    }

    private void toggleWebHome() {
        if (mWebHomeMode) exitWebHomeMode();
        else if (canWebHome()) enterWebHomeMode();
    }

    private void enterWebHomeMode() {
        SourceBean bean = webHomeSource();
        if (bean == null) return;
        if (mWebHomeBridge == null) {
            mWebHomeBridge = new WebHomeBridge(this, binding.browserWeb, bean, home, url -> {
                exitWebHomeMode();
                binding.browserWeb.loadUrl(url);
            });
        }
        binding.browserWeb.addJavascriptInterface(mWebHomeBridge, "AVBox");
        mWebHomeMode = true;
        binding.browserWebHome.setText("网页");
        binding.browserWeb.loadUrl(WebHomeTemplates.urlFor(this, WebHomeTemplates.current(this)));
    }

    private void exitWebHomeMode() {
        mWebHomeMode = false;
        try {
            binding.browserWeb.removeJavascriptInterface("AVBox");
        } catch (Throwable ignored) {
        }
        binding.browserWebHome.setText("HTML");
        if (!TextUtils.isEmpty(home) && !isLocalServerUrl(home)) binding.browserWeb.loadUrl(home);
    }

    /** 1.17：长按 HTML 换模板（默认模板 + 已导入的），也可以从这里导入本地 html。
     * 1.18：模板页里的「换模板」按钮也走这里；加保护，弹层或文件选择器出问题只提示、不让浏览器闪退。
     * 1.19：Material 弹层在 AppTheme（AppCompat，非 MaterialComponents）下弹不出，改用 AppCompat 的
     * AlertDialog.Builder；失败提示带上异常原因，方便定位。
     * 1.23：长按列表项删除导入的模板（内置默认模板不让删），删正在用的自动切回默认模板并刷新列表。 */
    public void showTemplatePicker() {
        if (isFinishing()) return;
        try {
            List<WebHomeTemplates.Template> templates = WebHomeTemplates.list(this);
            String current = WebHomeTemplates.current(this);
            CharSequence[] names = new CharSequence[templates.size()];
            int checked = 0;
            for (int i = 0; i < templates.size(); i++) {
                WebHomeTemplates.Template t = templates.get(i);
                names[i] = t.isDefault() ? "默认模板" : t.name;
                if (t.name.equals(current)) checked = i;
            }
            AlertDialog dialog = new AlertDialog.Builder(this)
                    .setTitle("切换模板")
                    .setSingleChoiceItems(names, checked, (d, which) -> {
                        try {
                            WebHomeTemplates.Template t = templates.get(which);
                            WebHomeTemplates.setCurrent(this, t.name);
                            d.dismiss();
                            if (mWebHomeMode) {
                                binding.browserWeb.loadUrl(WebHomeTemplates.urlFor(this, t.name));
                            } else if (canWebHome()) {
                                enterWebHomeMode();
                            }
                        } catch (Throwable t) {
                            Notify.show("切换模板失败");
                        }
                    })
                    .setNeutralButton("导入本地模板", (d, which) -> {
                        try {
                            mTemplateImportLauncher.launch(new String[]{"*/*"});
                        } catch (Throwable t) {
                            Notify.show("打不开文件选择器");
                        }
                    })
                    .setNegativeButton("取消", null)
                    .create();
            dialog.show();
            try {
                dialog.getListView().setOnItemLongClickListener((parent, view, position, id) -> {
                    try {
                        WebHomeTemplates.Template t = templates.get(position);
                        if (t.isDefault()) {
                            Notify.show("默认模板不能删除");
                            return true;
                        }
                        String delName = t.name;
                        boolean wasCurrent = delName.equals(WebHomeTemplates.current(BrowserActivity.this));
                        new AlertDialog.Builder(BrowserActivity.this)
                                .setTitle("删除模板")
                                .setMessage("删除「" + delName + "」？")
                                .setPositiveButton("删除", (cd, which) -> {
                                    try {
                                        if (WebHomeTemplates.delete(BrowserActivity.this, delName)) {
                                            Notify.show("已删除「" + delName + "」");
                                            if (wasCurrent && mWebHomeMode) {
                                                binding.browserWeb.loadUrl(WebHomeTemplates.urlFor(BrowserActivity.this, ""));
                                            }
                                        } else {
                                            Notify.show("删除失败");
                                        }
                                    } catch (Throwable e) {
                                        Notify.show("删除失败");
                                    }
                                    dialog.dismiss();
                                    showTemplatePicker();
                                })
                                .setNegativeButton("取消", null)
                                .show();
                    } catch (Throwable e) {
                        Notify.show("删除失败");
                    }
                    return true;
                });
            } catch (Throwable ignored) {
            }
        } catch (Throwable t) {
            String msg = t.getMessage();
            Notify.show("换模板失败：" + t.getClass().getSimpleName() + (msg == null ? "" : " " + msg));
        }
    }

    /** r474：顶部工具栏避让状态栏（用户报「被状态栏压住无法点」）、browserPlay 避让底部手势条。
     * 顶部复用 MusicTopInset（真实 inset + 取不到时回落系统尺寸，与音乐页同口径）；
     * 底部单独给悬浮钮加导航栏/挖孔 inset，两个 listener 挂不同 View 互不覆盖。 */
    private void applySystemBarInsets() {
        // 3.34 AVBox：无 MusicTopInset，顶部沿用系统栏默认避让；只给悬浮钮加底部手势条 inset。
        final View play = binding.browserPlay;
        final int basePlayMargin = ((FrameLayout.LayoutParams) play.getLayoutParams()).bottomMargin;
        ViewCompat.setOnApplyWindowInsetsListener(play, (v, insets) -> {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) v.getLayoutParams();
            lp.bottomMargin = basePlayMargin + insets.getInsets(WindowInsetsCompat.Type.navigationBars() | WindowInsetsCompat.Type.displayCutout()).bottom;
            v.setLayoutParams(lp);
            return insets;
        });
    }

    private void initEvent() {
        binding.browserBack.setOnClickListener(v -> onBack());
        binding.browserReload.setOnClickListener(v -> binding.browserWeb.reload());
        binding.browserWebHome.setOnClickListener(v -> toggleWebHome());
        binding.browserWebHome.setOnLongClickListener(v -> {
            showTemplatePicker();
            return true;
        });
        binding.browserAiProcess.setOnClickListener(v -> onAiProcessToggle());
        binding.browserAiPass.setOnClickListener(v -> onAiPassToggle());
        binding.browserAiFix.setOnClickListener(v -> onAiFix());
        binding.browserPlay.setOnClickListener(v -> playNative());
        refreshAiProcessTint();   // 3.68 C：开关初态按入口站 host 读（默认关）
        refreshAiPassTint();   // 3.70：放行开关初态按入口站 host 读（默认关）
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void initWeb() {
        WebSettings s = binding.browserWeb.getSettings();
        s.setJavaScriptEnabled(true);
        // 1.22：导入的模板存在 files/webhome/templates/，走 file:// 地址；新版 WebView 默认不许
        //   file:// 读应用内部文件（内置模板走 android_asset 不受影响，所以之前看着是好的），
        //   不开这个导入的模板全是 ERR_ACCESS_DENIED。
        s.setAllowFileAccess(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setTextZoom(100);
        s.setSupportMultipleWindows(true);   // 3.54：放行用户亲手点的新窗口（window.open/target=_blank），见 onCreateWindow；JS 自动弹的仍由下一行 + isUserGesture 拦
        s.setJavaScriptCanOpenWindowsAutomatically(false);   // 广告弹窗主通道：JS 自动开窗
        CookieManager.getInstance().setAcceptThirdPartyCookies(binding.browserWeb, true);
        CookieManager.getInstance().flush();

        binding.browserWeb.setWebViewClient(new WebViewClient() {

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request == null ? null : request.getUrl();
                if (uri == null) return false;
                if (request.hasGesture()) userGestured = true;
                String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
                // 1.22：放行 file（导入的模板是 files 目录下的 file:// 地址；之前一律拦，非 http(s) 全 return true）。
                if (!"http".equals(scheme) && !"https".equals(scheme) && !"file".equals(scheme)) return true;
                String url = uri.toString();
                String targetHost = hostOf(url);
                if (isAd(targetHost, url)) {
                    DebugLogStore.add("browser", "拦截跳转 " + targetHost);
                    return true;
                }
                // Muse v107：同站 /pop 也会把单窗口 WebView 的片页劫成空白弹窗页。
                if (isPopunderPath(uri.getPath())) {
                    DebugLogStore.add("browser", "拦截弹窗路径 " + uri.getPath());
                    return true;
                }
                String currentHost = hostOf(view.getUrl());
                if (TextUtils.isEmpty(currentHost)) currentHost = hostOf(anchorUrl);
                if (!sameSite(targetHost, currentHost)) {
                    // 修复：用户亲手点的链接（有手势）直接放行。之前连手势跳转也拦，
                    // 导致站内点个链接跳到播放域名就被“拦截跨站跳转”拦掉，一直打不开。
                    // 广告域名已在上面 isAd 拦过，这里只放行非广告的手势跳转。
                    if (request.hasGesture()) {
                        DebugLogStore.add("browser", "放行手势跨站跳转 " + currentHost + " → " + targetHost);
                        return false;
                    }
                    // Muse v61/v74：发布页人工点按及自动跳转均放行，但目标广告规则仍优先生效。
                    if (isJumpHost(currentHost)) {
                        markJump(currentHost);
                        if (request.hasGesture()) followJumpHost = true;
                        DebugLogStore.add("browser", "放行发布页跳转 " + currentHost + " → " + targetHost);
                        return false;
                    }
                    // Muse v92：中转 URL 携带当前站地址；v93：10 秒窗口内跟进后续 30x。
                    if (!request.hasGesture() && isJumpServiceUrl(url, currentHost)) {
                        jumpAnchorHost = currentHost;
                        jumpRelayHost = targetHost;
                        jumpChainUntil = SystemClock.uptimeMillis() + 10000;
                        markJump(currentHost);
                        DebugLogStore.add("browser", "放行中转跳转 " + currentHost + " → " + targetHost);
                        return false;
                    }
                    if (!request.hasGesture() && jumpAnchorHost != null
                            && SystemClock.uptimeMillis() < jumpChainUntil
                            && (jumpAnchorHost.equals(currentHost) || jumpAnchorHost.equals(jumpRelayHost) || jumpRelayHost.equals(currentHost))) {
                        jumpChainUntil = SystemClock.uptimeMillis() + 10000;
                        return false;
                    }
                    // Muse v106：只拦目标导航，不 stopLoading，避免把当前正片页一并卸成空白。
                    DebugLogStore.add("browser", "拦截跨站跳转 " + targetHost + " ← " + currentHost
                            + (request.hasGesture() ? "（手势）" : "（无手势）"));
                    return true;
                }
                int port = uri.getPort();
                if (!isSelfHost(targetHost) && port > 0 && port != 80 && port != 443 && port != 8080 && port != 8443 && port != 8888) {
                    DebugLogStore.add("browser", "拦截广告跳转(非标端口 " + port + ") " + targetHost);
                    return true;
                }
                return false;
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                String host = request.getUrl().getHost();
                // 3.73：广告加载器拦截（muse-build v53/广告加载器/奇优）—— 在 isAd 分支之前，放行开着时
                //   同样跳过；只拦请求本身，媒体嗅探在下面照常走（不 early return）。
                if (!AiAdRuleStore.isPassthrough(pageHost) && isAdLoaderRequest(url, request.getUrl().getPath())) {
                    if (!TextUtils.isEmpty(host)) {   // r472 同款：按 host 聚合，onPageFinished 汇总输出
                        String key = host.toLowerCase();
                        Integer old = netBlock.get(key);
                        netBlock.put(key, old == null ? 1 : old + 1);
                    }
                    return empty();
                }
                // 3.70：放行开着时本站当普通浏览器用 —— 跳过 AdBlocker.isAd 与 AI 规则域名档，
                //   不拦截；媒体嗅探在下面照常走（只跳过拦截分支，不 early return）。
                if (!AiAdRuleStore.isPassthrough(pageHost) && !TextUtils.isEmpty(host) && isAd(host, url) && !isMediaRequest(request.getUrl().getPath())) {
                    String key = host.toLowerCase();   // r472：按 host 聚合，onPageFinished 汇总输出（防 149 张图刷屏）
                    Integer old = netBlock.get(key);
                    netBlock.put(key, old == null ? 1 : old + 1);
                    return empty();
                }
                // 网络层嗅探媒体直链（覆盖跨域 iframe / 页面 JS 拼地址的场景）：只点亮按钮，不自动起播，
                // 防止列表页 banner 轮播视频把浏览劫持进播放器。
                String low = url.toLowerCase();
                int q = low.indexOf('?');
                String path = q > 0 ? low.substring(0, q) : low;
                // P0-1：视频判定加 muse 的 Sniffer（分站规则 regex/exclude + 通用视频后缀/特征），
                // 补上纯后缀判断抓不到的站内视频（如 video/tos、rtmp、分站规则命中的地址）。
                if ((isSniffableMediaUrl(path) || Sniffer.isVideoFormat(url)) && !url.equals(lastSniff)) {
                    lastSniff = url;
                    sniffAt = System.currentTimeMillis();
                    synchronized (mediaSeen) {   // r473：按序收集本页全部媒体（集数列表数据源，上限 60 条防溢出）
                        // r479：同视频多变体归一 —— HLS 的 index/master/720p/1080p 等同目录 m3u8 只算一集
                        //  （真机实证：单集页被网络嗅探收成 2 条 → 合成集数=2，用户报「网页只有一集却变成两集」）
                        String mk = mediaKeyOf(url);
                        boolean dup = false;
                        for (String k : mediaSeen.keySet()) {
                            if (mediaKeyOf(k).equals(mk) || isM3u8Ancestor(url, k)) { dup = true; break; }   // r482：+目录祖先归一（master/码率变体只算一集）
                        }
                        if (!dup) {
                            mediaSeen.put(url, Boolean.TRUE);
                            if (mediaSeen.size() > 60) {
                                Iterator<String> it = mediaSeen.keySet().iterator();
                                if (it.hasNext()) { it.next(); it.remove(); }
                            }
                        }
                    }
                    DebugLogStore.add("browser", "唳探媒体 " + url);
                    new Handler(Looper.getMainLooper()).post(() -> binding.browserPlay.setVisibility(View.VISIBLE));
                }
                return super.shouldInterceptRequest(view, request);
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                binding.browserProgress.setVisibility(View.VISIBLE);
                binding.browserProgress.setProgress(0);
                updateJumpState(url);
                pageHost = hostOf(url);   // r473：本站豁免基准（当前页）
                // 候选短超时：只对候选首页本身计时（站内点链接不计时）；6 秒没收到东西就跳下一个
                if (isCandidateUrl(url)) armCandidateTimer(); else disarmCandidateTimer();
                refreshAiProcessTint();   // 3.68 C：换站按新站 host 重读开关态，不沿用上一站的态
                refreshAiPassTint();   // 3.70：换站按新站 host 重读放行态
                blankRecovered = false;
                if (blankRecheck != null) binding.browserWeb.removeCallbacks(blankRecheck);
                pageTitle = null;         // r473：新页面重置标题与媒体收集
                synchronized (mediaSeen) { mediaSeen.clear(); }
                hasVideo = false;   // r474：内容分流状态随新页面重置
                pageTextLen = 0; pageText = ""; pageImages = new ArrayList<>(); pageAudios = new ArrayList<>();
                pageVidSig = 0; pageVidStruct = 0; pageGseq = 0;   // r482：视频/图集信号随新页重置（原漏重置，上一页残留会误判）
                pageNovel = 0;   // r486：小说信号随新页重置
                probeGen++;   // r483：导航后旧复探作废
                reprobed = false;
                binding.browserPlay.setVisibility(View.GONE);   // r483：按钮随新页复位（原残留上一页「播放视频」→ 真机实证无内容页仍可点）
                // 3.73：先藏页等首扫（muse-build v96/v97）—— 页面先藏，等 CLEAN_JS 首扫跑完再显示，
                //   防横幅先闪一下再消失；全屏 custom view 时不动（browserWeb 已是 GONE，回调别打架）。
                if (customView == null) {
                    view.setVisibility(View.INVISIBLE);
                    if (forceShowWeb != null) view.removeCallbacks(forceShowWeb);
                    forceShowWeb = () -> { if (customView == null) view.setVisibility(View.VISIBLE); };
                    view.postDelayed(forceShowWeb, 5000);
                }
                // r476：FILTER_JS 提前到 onPageStarted 注入 —— 赶在站点广告脚本（挂马站的
                //   fixed_ui_*.js 等）之前建好 MutationObserver + 清场，治「广告先渲染、
                //   onPageFinished 才删」的滞后面；observer 未就绪时下次 onPageFinished 会重试建。
                // 3.70：放行开着时本站不注 FILTER_JS（整段是广告清理 + Cloudflare 护栏 + 对话框
                //   覆写，无 jumpHost 跳转链逻辑 —— 跳转链是原生 Java 的 jumpHost/onCreateWindow，
                //   不注 JS 不影响它；护栏本身是“清理时别碰验证页”，不清理就用不上）。
                if (!AiAdRuleStore.isPassthrough(pageHost)) view.evaluateJavascript(FILTER_JS, null);
                // 3.68 C：本站 AI 触发器（只含禁止自动开窗类，与 buildSiteJs 各用各的守卫，
                //   两份都注不冲突）；开关关着不注。3.70：放行开着时记录规则也不生效（短路盖住，规则不删）。
                if (!AiAdRuleStore.isPassthrough(pageHost) && AiAdRuleStore.isEnabled(pageHost)) {
                    String triggerJs = AiAdRuleStore.buildTriggerJs(pageHost);
                    if (!TextUtils.isEmpty(triggerJs)) view.evaluateJavascript(triggerJs, null);
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                binding.browserProgress.setVisibility(View.GONE);
                // 主框完成：当前候选已加载，停掉短超时计时
                if (url != null && url.equals(view.getUrl())) {
                    candidateFinished = true;
                    disarmCandidateTimer();
                }
                if (url != null && url.equals(view.getUrl()) && (url.startsWith("http://") || url.startsWith("https://"))) {
                    KV.put(HawkConfig.BROWSER_LAST_URL, url);
                    // r475：本次成功打开的就是当前可用镜像，记下活域名（10分钟），下次网页 Tab 优先开它
                    String liveHost = hostOf(url);
                    if (!TextUtils.isEmpty(liveHost) && !TextUtils.isEmpty(mSiteKey)) SiteLiveHost.note(mSiteKey, liveHost);
                }
                // 3.70：放行开着时本站不注 FILTER_JS（见 onPageStarted 处注释，整段可跳过）。
                if (!AiAdRuleStore.isPassthrough(pageHost)) view.evaluateJavascript(FILTER_JS, null);
                // 3.56：muse-build CLEAN_JS 页面清理（主框才跑；__museJumpDone 不设，v77 外链清理由 isRealSite() 自判）。
                // 3.70：放行开着时本站不注 CLEAN_JS 也不注 buildSiteJs（记录规则被短路盖住，规则不删）。
                if (!AiAdRuleStore.isPassthrough(pageHost) && url != null && url.equals(view.getUrl())) {
                    // 3.73：CLEAN_JS 首扫跑完再显示（muse-build v97）—— 回调里显示，分类页加载快也不抢在首扫前闪横幅墙。
                    view.evaluateJavascript(CLEAN_JS, value -> { if (customView == null) view.setVisibility(View.VISIBLE); });
                    // 3.68 C：本站 AI 规则 JS（CLEAN_JS 之后注，主框才跑；开关关着不注）。
                    if (AiAdRuleStore.isEnabled(pageHost)) {
                        String siteJs = AiAdRuleStore.buildSiteJs(pageHost);
                        if (!TextUtils.isEmpty(siteJs)) view.evaluateJavascript(siteJs, null);
                    }
                } else {
                    // 3.73：不注 CLEAN_JS 的分支（放行开着、非主框等）当场显示，别让页面一直藏着。
                    if (customView == null) view.setVisibility(View.VISIBLE);
                }
                // 3.34 AVBox：蜘蛛自渲染阅读页样式注入（/b? 小说 / /v? 漫画，跟主题走）。
                if (url != null && url.contains("/b?")) {
                    view.evaluateJavascript(novelInjectJs(), null);
                } else if (url != null && url.contains("/v?")) {
                    view.evaluateJavascript(comicInjectJs(), null);
                }
                // r479：视频判定完成后再分流（原 probeVideo()/probeContent() 同步连调，
                //   hasVideo 只在 VIDEO_PROBE 异步回调里置位 → probeContent 里判 hasVideo 恒为 false，
                //   视频页被图片优先级劫持：用户报「明明是视频，按钮却显示显示图片」）。
                probeVideoThenContent();
                // r472：网络层拦截汇总（host 聚合后输出，避免单图单条刷屏）
                if (!netBlock.isEmpty()) {
                    StringBuilder sb = new StringBuilder();
                    Iterator<Map.Entry<String, Integer>> it = netBlock.entrySet().iterator();
                    while (it.hasNext()) {
                        Map.Entry<String, Integer> e = it.next();
                        if (sb.length() > 0) sb.append(", ");
                        sb.append(e.getKey()).append("×").append(e.getValue());
                    }
                    netBlock.clear();
                    DebugLogStore.add("browser", "页面完成 " + url + " | 网络层拦截 " + sb);
                } else {
                    DebugLogStore.add("browser", "页面完成 " + url);
                }
                // r487：广告落地页弹回 —— HTTP 30x 重定向/表单 POST/并发跳转竞态**不经过**
                //   shouldOverrideUrlLoading（真机实证：r478:9300-9301「拦截跳转 unj.yjdh890.com」与
                //   1ms 后「页面完成 …:8851/tg.htm」并存；r486logs cvz.xseebb905:8321 / jqi.pcbc204:8621 /
                //   urp.xsanan982:8521 三个 tg.htm 落地页全部「页面完成」且无任何拦截行 —— 站内中转链接
                //   被点击放行后服务端 30x 拉到广告页，导航判据链完全不参与）。
                //   修法：页面完成瞬间用 isAd 复查落地 URL，命中即自动 goBack 弹回源页，用户基本无感；
                //   isAd 对本站域豁免（isSelfHost），正常站永不受影响。防循环：连续弹回上限 3 次。
                try {
                    String bounceHost = Uri.parse(url).getHost();
                    if (isAd(bounceHost, url)) {
                        if (adBounce < 3 && binding.browserWeb.canGoBack()) {
                            adBounce++;
                            DebugLogStore.add("browser", "广告落地页弹回 " + url + " ×" + adBounce);
                            binding.browserWeb.post(() -> binding.browserWeb.goBack());
                        } else if (adBounce >= 3) {
                            DebugLogStore.add("browser", "广告落地页弹回超限断链 " + url);
                            binding.browserWeb.stopLoading();
                        }
                    } else {
                        adBounce = 0;
                    }
                } catch (Throwable ignored) {
                }
                // Muse v93/v56：页面落地即结束中转链；验证 Cookie 落盘，重进不再反复挑战。
                clearJumpChain();
                CookieManager.getInstance().flush();
                // Muse v77：直接打开真实 MacCMS 站也结束发布页状态，避免真实站假链接获得跨站豁免。
                if (!TextUtils.isEmpty(jumpHost) && jumpHost.equals(hostOf(url))) {
                    view.evaluateJavascript("(function(){var as=document.querySelectorAll('a[href]');var n=0;for(var i=0;i<as.length;i++){var h=as[i].getAttribute('href')||'';if(h.indexOf('/vodtype/')===0||h.indexOf('/voddetail/')===0||h.indexOf('/vodplay/')===0){if(++n>=2)return true;}}return false;})()", value -> {
                        if ("true".equals(value)) {
                            jumpHost = "";
                            jumpUsed = false;
                            jumpOriginHost = null;
                        }
                    });
                }
                recoverBlankPage(view, url);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request == null || !request.isForMainFrame()) return;
                // UA 白名单站通用降级：主文档连接失败且未重试过 → 追加后缀 UA 重试一次。
                // 实测形如 51cg 的站：对无标记 UA 拒连、对带 App 标记的 UA 放行（通用行为，不锚死域名）。
                if (!uaRetried) {
                    uaRetried = true;
                    String ua = binding.browserWeb.getSettings().getUserAgentString();
                    if (!ua.contains("HikerView")) {
                        binding.browserWeb.getSettings().setUserAgentString(ua + " HikerView/1.0");
                        DebugLogStore.add("browser", "UA 降级重试 " + request.getUrl());
                        view.post(() -> view.loadUrl(request.getUrl().toString()));
                    }
                } else if (tryNextCandidate()) {
                    DebugLogStore.add("browser", "主文档加载失败，已切换下一候选镜像");
                }
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.proceed();   // 与 CustomWebView 同策略：跳板链证书链不全时放行
            }
        });

        binding.browserWeb.setWebChromeClient(new WebChromeClient() {

            // 3.39：视频全屏 —— 网页视频点全屏时系统调这里；之前没实现，视频只能嵌在页面里播。
            @Override
            public void onShowCustomView(View view, WebChromeClient.CustomViewCallback callback) {
                if (customView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                customView = view;
                customViewCallback = callback;
                ((ViewGroup) getWindow().getDecorView()).addView(view, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                binding.browserWeb.setVisibility(View.GONE);
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
                // 3.40：全屏自动转方向 —— 按视频实际宽高定（跟详情页 applyVideoOrientation
                // 同款思路：横视频转横屏、竖短剧转竖屏；拿不到尺寸默认横屏；Manifest 已有
                // configChanges，转屏不重建 Activity，视频不断）。
                applyFullscreenOrientation();
            }

            @Override
            public void onHideCustomView() {
                exitFullscreen();
            }

            // Muse v73/v74/v107：临时 WebView 只负责取得新窗口 URL，统一过滤后转当前页。
            // 发布页自动开窗、用户 target=_blank 可用；广告域、/pop 与真实站自动跨站弹窗不放行。
            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
                if (resultMsg == null || !(resultMsg.obj instanceof WebView.WebViewTransport)) return false;
                WebView tmp = new WebView(view.getContext());
                tmp.getSettings().setJavaScriptEnabled(true);
                tmp.setWebViewClient(new WebViewClient() {
                    private boolean handled = false;

                    private void redirectToMain(WebView v, String url) {
                        if (handled || TextUtils.isEmpty(url) || url.startsWith("about:")) return;
                        handled = true;
                        if (isAllowedNewWindowUrl(url, isUserGesture)) {
                            DebugLogStore.add("browser", "新窗口转当前页 " + url);
                            if (!hostOf(url).equals(hostOf(binding.browserWeb.getUrl()))) followJumpHost = true;
                            binding.browserWeb.loadUrl(url);
                        } else {
                            DebugLogStore.add("browser", "拦截新窗口 " + url + (isUserGesture ? "（手势）" : "（无手势）"));
                        }
                        v.post(() -> {
                            try { v.destroy(); } catch (Throwable ignored) { }
                        });
                    }

                    @Override
                    public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest request) {
                        if (request != null && request.isForMainFrame()) redirectToMain(v, request.getUrl().toString());
                        return true;
                    }

                    @Override
                    public boolean shouldOverrideUrlLoading(WebView v, String url) {
                        redirectToMain(v, url);
                        return true;
                    }

                    @Override
                    public void onPageStarted(WebView v, String url, Bitmap favicon) {
                        redirectToMain(v, url);
                    }
                });
                WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
                transport.setWebView(tmp);
                resultMsg.sendToTarget();
                return true;
            }

            @Override
            public boolean onConsoleMessage(ConsoleMessage cm) {
                // r472：接收 FILTER_JS 的 __TXAD__ 清理统计；值变化才记（JS 端 2 秒节流 + 此处去重）
                try {
                    if (cm != null && cm.message() != null && cm.message().startsWith("__TXAD__")) {
                        String stat = cm.message().substring(8);
                        if (!stat.equals(lastAdStat)) {
                            lastAdStat = stat;
                            DebugLogStore.add("browser", "JS清理统计 " + stat);
                        }
                    }
                } catch (Throwable ignored) {
                }
                return super.onConsoleMessage(cm);
            }

            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                binding.browserProgress.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
                binding.browserProgress.setProgress(newProgress);
            }

            @Override
            public void onReceivedTitle(WebView view, String title) {
                if (!TextUtils.isEmpty(title)) {
                    pageTitle = title;   // r473：集数面板/播放器标题用
                    binding.browserTitle.setText(title);
                }
            }

            // r487：JS 对话框原生兜底 —— FILTER_JS 覆写 alert/confirm/prompt 是唯一防线，但存在三个绕过口：
            //   ① onPageStarted 注入有竞态窗（页面早期脚本先于覆写执行）；② iframe 内的 window 是独立
            //   作用域，主帧覆写管不到（onJsAlert 对 iframe 弹窗同样回调）；③ 页面反覆写（重新赋值
            //   window.alert）。广告/赌博站的弹窗几乎全是恐吓与诱导（「手机已中毒」「点击领取」），
            //   正常影视/小说站的 alert 极少且非关键路径 —— 全部吞掉并记日志，可观测可回退。
            @Override
            public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
                DebugLogStore.add("browser", "拦截JS弹窗 alert: " + briefJs(message));
                result.cancel();
                return true;
            }

            @Override
            public boolean onJsConfirm(WebView view, String url, String message, JsResult result) {
                DebugLogStore.add("browser", "拦截JS弹窗 confirm: " + briefJs(message));
                result.cancel();
                return true;
            }

            @Override
            public boolean onJsPrompt(WebView view, String url, String message, String defaultValue, JsPromptResult result) {
                DebugLogStore.add("browser", "拦截JS弹窗 prompt: " + briefJs(message));
                result.cancel();
                return true;
            }
        });
    }

    /** 探测页面内 video：有非空 src 就点亮按钮（blob: 也点亮，点击时若无法直取则提示继续网页播放）。 */
    /** 探测页面内 video：有非空 src 就点亮按钮（blob: 也点亮，点击时若无法直取则提示继续网页播放）。
     *  r479：改为 probeVideoThenContent —— 视频判定完成后**再**跑内容分流。
     *  真因（真机实证）：原 onPageFinished 里 probeVideo()/probeContent() 同步连调，而 hasVideo 只在
     *  VIDEO_PROBE 的异步回调里置位 → probeContent 里 `if (hasVideo) return` 恒不生效，
     *  视频页被图片/正文优先级劫持（用户报「明明是视频，按钮却显示显示图片」「视频被塞进小说阅读器」）。
     *  另：DOM 无 video（播放器在 iframe/后注入）但网络层已嗅到媒体请求 → 同样算视频页。 */
    private void probeVideoThenContent() {
        binding.browserWeb.evaluateJavascript(VIDEO_PROBE, value -> {
            String v = value == null ? "" : value.replace("\"", "").trim();
            if (!TextUtils.isEmpty(v) && !"null".equals(v)) {
                hasVideo = true;   // r474：有视频页不做图文音分流
                binding.browserPlay.setVisibility(View.VISIBLE);
                DebugLogStore.add("browser", "页面视频 " + v);
            } else if (!mediaSeen.isEmpty()) {
                // r482：mediaSeen 不再单独作为视频页证据 —— cmlwsn 实证：图集首页嵌自动播放
                //  广告视频（fhv2026 master+variant 两条 m3u8 进 mediaSeen）→ hasVideo=true →
                //  按钮变「播放视频」→ 集数面板 2 集全是广告。视频判型统一收敛 isVideoPage()。
                DebugLogStore.add("browser", "网络嗅探媒体 " + mediaSeen.size() + " 条（r482 起不再单独判视频页）");
            }
            probeContent();
        });
    }

    /**
     * r473：「原生播放」对标参考 App——网页视频抽成集数列表：0 条走旧兜底链（单 video 直取 →
     * 最近唳探 → 提示），1 条直接起播，≥2 条弹集数面板（App 播放全部 N 集 / 逐集点击）。
     * 起播走 browser_play 虚拟源（VideoActivity 完整 VOD 结构：标题/线路「网页视频」/N 集），不再是 PUSH 单直链。
     */
    private void playNative() {
        binding.browserWeb.evaluateJavascript(EPISODE_PROBE, value -> {
            List<String> urls = parseEpisodeList(value);
            synchronized (mediaSeen) {   // r473：DOM 没抽到的用网络唳探补充（保持页面顺序，唳探追加在后）
                for (String u : mediaSeen.keySet()) if (!urls.contains(u)) urls.add(u);
            }

            // r481：回归修复 —— 选集抽取对图片站误命中（xiezhen8 的 /sid/N.html 分页被当成 14 集，
            //   真机日志：[r473] 网页视频收集 14 条 + 首集 …/sid/2.html），导致图片站直接进视频
            //   播放器（集数面板），图片查看器再也进不去（用户报「把原来能正常调用图片阅读器给修坏了」）。
            //   判据（形态自适应）：本页**没有任何视频证据**（DOM 无 video、网络没嗅到媒体、页面无视频
            //   信号词）却有 ≥3 张大图 → 是图集页，弃用选集结果，交给 routeContent() 走图片分流。
            //   反向：只要有一条视频证据，选集结果照常使用（不动视频站）。
            //   收紧（反例验证后）：① 只处理**多条**（单集本就是媒体直链，不动）；
            //   ② 只清「**全部都不是媒体直链**」的结果 —— 真正的选集误命中抽到的都是页面 URL；
            //      只要有一条是 mp4/m3u8/mp3 就说明是真集地址，一律不动（保视频站/音乐站。）
            boolean anyMedia = false;
            for (String u : urls) {
                if (looksLikeMediaUrl(u)) { anyMedia = true; break; }
            }
            // r482：判据收敛 isVideoPage() —— cmlwsn 型（图集首页嵌广告视频，urls 全是广告 m3u8，
            //   anyMedia=true + mediaSeen 非空 → r481 护栏双条件都不满足 → 面板 2 集全是广告）：
            //   isVideoPage()=false（大图 89 张图集形态优先）→ 弃用选集结果改走图片分流。
            //   xiezhen8 型不变（全为页面 URL）；音乐站保真（mp3 不属视频容器直链，一律不动）。
            boolean videoOnly = true;   // r482：全部为视频容器直链（无音频/页面 URL 混入）
            for (String u : urls) {
                if (!looksLikeVideoUrl(u)) { videoOnly = false; break; }
            }
            if (urls.size() >= 2 && !isVideoPage() && pageImages.size() >= 3
                    && ((videoOnly && pageAudios.isEmpty()) || (!anyMedia && mediaSeen.isEmpty()))) {
                DebugLogStore.add("browser", "[r482] 图集形态（" + pageImages.size() + " 张）且无视频页信号 → 弃用选集结果 "
                        + urls.size() + " 条（" + (videoOnly ? "全为视频直链=广告贴片" : "全为页面 URL") + "），改走图片分流");
                urls.clear();
            }
            // r483：直链与页面 URL 分流（治「集数面板点开 MALFORMED」——真机实证 nnyy：推荐位 vodplay
            //   网页 URL 被 ExoPlayer 当直链解析失败；也治「点选集播不了」）：
            //   ① 已嗅到媒体直链（当前集就绪）→ 页面型选集 URL 只用于网页内切换，不进播放器；
            //   ② 全是页面 URL（javascript:; 合成选集等）→ 集数面板逐集在浏览器内切换，嗅到直链后再原生播放；
            //   ③ 单条页面 URL → 直接网页内打开（不喂播放器）。
            List<String> mediaUrls = new ArrayList<>(), pageUrls = new ArrayList<>();
            for (String u : urls) (looksLikeMediaUrl(u) ? mediaUrls : pageUrls).add(u);
            if (!mediaUrls.isEmpty() && !pageUrls.isEmpty()) {
                DebugLogStore.add("browser", "[r483] 页面已嗅到直链 " + mediaUrls.size() + " 条，页面型选集 "
                        + pageUrls.size() + " 条不进播放器（防 MALFORMED）");
                urls = mediaUrls;
            } else if (mediaUrls.isEmpty() && pageUrls.size() >= 2) {
                DebugLogStore.add("browser", "[r483] 选集 " + pageUrls.size() + " 条全是页面 URL → 面板逐集网页切换");
                showEpisodeSheetWithLines(pageUrls, true);
                return;
            } else if (mediaUrls.isEmpty() && pageUrls.size() == 1) {
                DebugLogStore.add("browser", "[r483] 单条页面 URL → 网页内打开 " + sub(pageUrls.get(0), 100));
                Notify.show("已在网页内打开选集，起播后点悬浮球原生播放");
                binding.browserWeb.loadUrl(pageUrls.get(0));
                return;
            }
            DebugLogStore.add("browser", "[r473] 网页视频收集 " + urls.size() + " 条 标题=" + pageTitle);
            if (urls.size() == 1) {
                DebugLogStore.add("browser", "[r473] 网页视频单集起播 " + sub(urls.get(0), 100));
                playBrowserUrls(pageTitle, urls, 0);
                return;
            }
            if (urls.size() > 1) {
                DebugLogStore.add("browser", "[r473] 网页视频集数面板 " + urls.size() + " 集");
                showEpisodeSheetWithLines(urls, false);
                return;
            }

            // r474：无视频时内容分流——图片站→图片查看器、小说站→阅读器、音乐站→播放器（用户需求 4）
            if (urls.isEmpty()) {
                // r477：详情页可能刚导航、onPageFinished 未到 → 探测数据还是空的（真机实证：图集站详情页
                //   连点 5 次都报「原生播放失败」，而 7 秒后 onPageFinished 才补上探测）。
                //   点按钮时实时补探一轮，探测完成后直接走内容分流；仍无内容才走兑底链。
                if (pageImages.isEmpty() && pageAudios.isEmpty() && pageTextLen == 0) {
                    DebugLogStore.add("browser", "[r477] 点击时暂无探测数据 → 实时补探一轮再分流");
                    probeContent(true);
                    return;
                }
                if (routeContent()) return;
            }
            playNativeFallback();
        });
    }

    /** r470 兑底链：单 video 直取 → 最近唢探 → blob/无地址提示。r477 抽出：playNative 与
     *  「点击时实时补探」两条路径共用，避免重复实现（r460 同类双副本教训）。 */
    private void playNativeFallback() {
        binding.browserWeb.evaluateJavascript(VIDEO_PROBE, v -> {
                String src = v == null ? "" : v.replace("\"", "").trim();
                if (TextUtils.isEmpty(src) || "null".equals(src) || src.startsWith("blob:")) {
                    if (!TextUtils.isEmpty(lastSniff) && System.currentTimeMillis() - sniffAt < 120000) {
                        DebugLogStore.add("browser", "原生播放(唳探) " + lastSniff);
                        playSingleUrl(lastSniff);
                        return;
                    }
                    if (src.startsWith("blob:")) {
                        DebugLogStore.add("browser", "视频走加密流，留在网页播放");
                        Notify.show("视频走加密流，请在网页内播放");
                        return;
                    }
                    DebugLogStore.add("browser", "原生播放失败：未检测到视频地址");
                    Notify.show("未检测到可提取的视频地址");
                    return;
                }
                if (src.startsWith("http")) {
                    DebugLogStore.add("browser", "原生播放 " + src);
                    playSingleUrl(src);
                } else {
                    DebugLogStore.add("browser", "原生播放失败：地址无法识别 " + src);
                    Notify.show("视频地址无法识别");
                }
            });
    }

    /** r474：内容分流探测（onPageFinished）——图片站/音乐站/小说站调起对应阅读器/播放器（用户需求 4）。
     *  r477：加 thenRoute —— 用户点「查看图片/播放」时若还没探测过（详情页刚导航、onPageFinished 未到，
     *  真机实证连点 5 次全报「原生播放失败」），实时补探一轮后直接走内容分流，仍无内容才进兑底链。 */
    private void probeContent() {
        probeContent(false);
    }

    private void probeContent(final boolean thenRoute) {
        if (hasVideo) {   // 有视频页保持「原生播放」语义，不做图文音分流
            if (thenRoute) playNativeFallback();
            return;
        }
        binding.browserWeb.evaluateJavascript(AUDIO_PROBE, v -> {
            pageAudios = parseEpisodeList(v);
            binding.browserWeb.evaluateJavascript(IMG_PROBE, v2 -> {
                pageImages = parseEpisodeList(v2);
                binding.browserWeb.evaluateJavascript(TEXT_PROBE, v3 -> {
                    parseTextProbe(v3);
                    if (thenRoute) {
                        DebugLogStore.add("browser", "[r477] 实时补探完成 文字=" + pageTextLen + " 图="
                                + pageImages.size() + " 音=" + pageAudios.size() + " → 立即分流");
                        if (!routeContent()) playNativeFallback();
                    } else {
                        String label = updatePlayButton();
                        if (label == null && !reprobed) scheduleReprobe();   // r483：首探全零 → 1.5s 后复探一次（JS 渲染/懒加载形态）
                    }
                });
            });
        });
    }

    /** r483：首探全零延迟复探 —— 真机实证（r483 日志）：nnyy/sm455/qmtsw/meizi5 等站 onPageFinished
     *  后 19-48ms 探测全零（JS 渲染未完成/图片懒加载未启动）→ 按钮永不出现 = 用户报「个别网站视频和图片都探测不到」。
     *  每次页面加载只复探一次（1.5s 后）；复探前发生导航（probeGen 变化）则作废，防跨页误触发；
     *  复探仍全零说明页面真无内容（如 ddys 跳转壳页 5775B href=0）。 */
    private void scheduleReprobe() {
        reprobed = true;
        final int gen = probeGen;
        binding.browserWeb.postDelayed(() -> {
            if (gen != probeGen || isFinishing() || binding == null) return;
            DebugLogStore.add("browser", "[r483] 首探全零 → 1.5s 后延迟复探（JS 渲染/懒加载形态）");
            probeContent(false);
        }, 1500);
    }

    /** r475：分流优先级重排 音频→图片→正文（r474 正文优先导致 gaze 视频页/qmtsw 听书页误判「阅读正文」）。
     * 正文最泛（任何页面文字都>2000），音频最特异（有音频直链必是听书/音乐页）；
     * 正文分支加「无音频无大图」前置否决。探测结果无论分流与否都打一行日志（r472 埋点铁律）。 */
    private String updatePlayButton() {   // r483：返回分流标签（null=无内容，调用方据此触发延迟复探）
        String label = null;
        // r479：视频优先 —— 原实现只有「音频→图片≥3→正文>2000」三个分支，**根本没有视频分支**，
        //   视频页里只要有 ≥3 张海报/剧照就被判「查看图片」（用户报「明明是视频，按钮却显示显示图片」）。
        //   视频证据：DOM 有 <video>（hasVideo）或网络层嗅到媒体请求（mediaSeen 非空）。
        //   r480：再加「页面级视频信号词」—— 真机实证 cmlwsn.dyhs2.makeup 详情页
        //   （文字=3666 图=89 视频=false → 🖼 查看图片）DOM 无 <video>（播放器在 iframe/后注入）、
        //   网络也没嗅到媒体请求，但正文里满是「选集/线路/播放/第N集」等视频语义词。
        // r482：双向误判修复 —— ① vid 判据收敛 isVideoPage()：mediaSeen 非空不再单独判视频
        //   （cmlwsn 图集首页嵌广告视频被误判「播放视频」）；② 音频提前到视频前：qmtsw /play/ 型
        //   听书页 URL 命中结构信号，但音频直链更特异（r475 结论），先判音乐。
        boolean vid = pageAudios.isEmpty() && isVideoPage();
        if (vid) label = "▶ 播放视频";
        else if (!pageAudios.isEmpty()) label = "🎵 播放音乐";
        else if (pageNovel >= 1) label = "📖 阅读小说";   // r486：小说结构信号（章节页/目录页/介绍页）
        else if (pageImages.size() >= 3 || (pageGseq >= 1 && !pageImages.isEmpty())) label = "🖼 查看图片";
        else if (pageTextLen > 2000) label = "📖 阅读正文";
        DebugLogStore.add("browser", "[r479] 内容分流探测 文字=" + pageTextLen + " 图=" + pageImages.size()
                + " 音=" + pageAudios.size() + " 视频=" + vid + " 小说=" + pageNovel + (label == null ? " → 无内容" : " → " + label));
        if (label == null) {
            binding.browserPlay.setVisibility(View.GONE);   // r483：无内容时隐藏按钮（原残留上一页按钮致误点）
            return null;
        }
        binding.browserPlay.setText(label);
        binding.browserPlay.setVisibility(View.VISIBLE);
        return label;
    }

    /** r482：页面级视频判型（综合信号）——治「视频↔图片双向误判」。
     *  正向（视频判图片，gaze.red 型）：视频在 iframe/JS 播放器、DOM 无 video、网络没嗅到时，
     *    靠结构信号（URL 播放路径 /play/、播放器 iframe/容器、script 内联 .m3u8/.mp4）判视频页。
     *  反向（图片判视频，cmlwsn 型）：图集首页嵌自动播放广告视频 → mediaSeen 收到广告 m3u8，
     *    但大图 ≥3 张的图集形态优先 → 不判视频页（mediaSeen 仅在无图集形态时作证据）。
     *  音频页（qmtsw /play/ 型听书）：音频直链更特异，调用方须先判音频再调本方法。 */
    private boolean isVideoPage() {
        if (hasVideo || pageVidStruct >= 1 || pageVidSig >= 3) return true;
        return !mediaSeen.isEmpty() && pageImages.size() < 3;
    }

    /** r482：视频容器直链（无音频扩展名）——cmlwsn 护栏用：图集页混进的广告贴片全是视频直链。 */
    private static boolean looksLikeVideoUrl(String u) {
        if (TextUtils.isEmpty(u)) return false;
        String s = u.split("\\?")[0].toLowerCase();
        return s.matches(".*\\.(mp4|m3u8|mpd|flv|mov|webm|avi|ts|mkv)$");
    }

    /** r482：bplay 起播头来源 —— Referer=当前页 URL、UA=WebView UA。
     *  真机实证 fhv2026：WebView 能拉到 m3u8（带页面上下文），ExoPlayer 裸请求拿到非流内容 →
     *  3003 UnrecognizedInputFormatException（None of the extractors could read the stream）。 */
    public String bplayReferer() {
        try {
            return binding.browserWeb == null ? "" : String.valueOf(binding.browserWeb.getUrl());
        } catch (Throwable t) {
            return "";
        }
    }

    public String bplayUa() {
        try {
            return binding.browserWeb == null || binding.browserWeb.getSettings() == null
                    ? "" : String.valueOf(binding.browserWeb.getSettings().getUserAgentString());
        } catch (Throwable t) {
            return "";
        }
    }

    /** r474：TEXT_PROBE 回包解析（{len,text}）。 */
    private void parseTextProbe(String value) {
        try {
            String json = value;
            if (json.startsWith("\"") && json.endsWith("\"")) json = new JSONTokener(json).nextValue().toString();
            JSONObject obj = new JSONObject(json);
            pageTextLen = obj.optInt("len", 0);
            pageText = obj.optString("text", "");
            // r480：页面级视频信号词计数（治「视频站详情页 DOM 无 video → 按钮显示查看图片」）
            pageVidSig = obj.optInt("vid", 0);
            pageVidStruct = obj.optInt("v2", 0);   // r482：结构视频信号（播放路径/播放器 iframe/容器/内联流地址）
            pageGseq = obj.optInt("gs", 0);         // r482：图集切换信号（懒加载单图图集判据）
            pageNovel = obj.optInt("nv", 0);   // r486：小说结构信号（URL 路径词 / 章节锚计数）
        } catch (Throwable ignored) {
            pageTextLen = 0;
            pageText = "";
            pageVidSig = 0;
            pageVidStruct = 0;
            pageGseq = 0;
            pageNovel = 0;
        }
    }

    /** r474：内容分流执行——正文→novel:// 阅读器、图集→pics:// 图片查看器、音频→播放器（单首直投/多首集数）。 */
    private boolean routeContent() {
        if (!pageAudios.isEmpty()) {
            if (pageAudios.size() == 1) {
                DebugLogStore.add("browser", "[r475] 音乐分流单曲 " + sub(pageAudios.get(0), 100));
                playSingleUrl(pageAudios.get(0));
            } else {
                DebugLogStore.add("browser", "[r475] 音乐分流 " + pageAudios.size() + " 首 → 播放器集数面板");
                playBrowserUrls(pageTitle, pageAudios, 0);
            }
            return true;
        }
        // r482：视频页不做图集/正文分流 —— gaze.red 型（视频在 iframe/JS 播放器、DOM 无 video、
        //   嗅探也空）旧判据全空 → 图集 4 张（贴片）劫持进图片查看器；现在结构信号判视频页，
        //   返回 false 让调用方走 playNativeFallback（保持网页播放语义）。
        //   音频已在上分支先判（qmtsw /play/ 型听书页 URL 也命中结构信号，音频更特异）。
        if (isVideoPage()) {
            DebugLogStore.add("browser", "[r482] 视频页信号（DOM/结构/嗅探）→ 不做图集/正文分流");
            return false;
        }
        // r486：小说分支 —— nv 结构信号（URL 小说路径词 / ≥3 个「第N章」锚）。
        //   治「小说无法正常拉取进阅读器 + 拉取成功也只有一章节」：章节页正文 <2000 字旧判无内容、
        //   目录页 text=0；routeNovel 抽「第N章」锚组整本章节列表（≥2 章）进阅读器按需回源。
        if (pageNovel >= 1) {
            routeNovel();
            return true;
        }
        if (pageImages.size() >= 3 || (pageGseq >= 1 && !pageImages.isEmpty())) {
            // r476：图集「分页全量」抓取（用户报「图片很多张但阅读器只能看第一页」）。
            //   原先直接拿 WebView 当前 DOM 的大图（图集站一页 5 张 → 只出 4 张，fp.xiezhen8 实证 76P/16 页）。
            //   改为复用网页源图集链路（AES 解密 + PAGE_list + 连续编号推导 + 分页扩展）抓全量，
            //   后台线程执行不阻塞 UI；抓取失败/为 0 时回退首屏结果。
            routeGalleryAll(new ArrayList<>(pageImages));
            return true;
        }
        if (pageTextLen > 2000 && !TextUtils.isEmpty(pageText)) {
            String payload = novelPayload();
            if (!payload.isEmpty()) {
                DebugLogStore.add("browser", "[r475] 小说分流 正文 " + pageTextLen + " 字 → novel:// 内置阅读器");
                List<String> one = new ArrayList<>();
                one.add(payload);
                playBrowserUrls(pageTitle, one, 0);
                return true;
            }
        }
        return false;
    }

    /**
     * r476：图集全量抓取（后台线程）—— 浏览器模式图集分流入口。
     *
     * <p>复用 {@link WebSpider#galleryImagesFrom(String)} 图集链路
     * （AES 图集解密 + PAGE_list 分页 + 连续编号推导 + 普通 /page/N 分页扩展），治「一页 N 张、共 M 页」
     * 的图集站只能看第一页。失败或结果不增时回退 WebView 首屏图片（不阻塞、不空手）。
     */
    private void routeGalleryAll(final List<String> domImgs) {
        final String pageUrl = binding.browserWeb.getUrl();
        final String title = pageTitle;
        final int domSize = domImgs == null ? 0 : domImgs.size();
        DebugLogStore.add("browser", "[r476] 图集分流 首屏 " + domSize + " 张 → 后台抓全量分页…");
        new Thread(() -> {
            long t0 = System.currentTimeMillis();
            List<String> full = new ArrayList<>();
            try {
                if (!TextUtils.isEmpty(pageUrl) && pageUrl.startsWith("http")) {
                    WebSpider sp =
                            new WebSpider("browser_gallery", pageUrl, pageUrl);
                    List<String> all = sp.galleryImagesFrom(pageUrl);
                    if (all != null) full.addAll(all);
                }
            } catch (Throwable t) {
                DebugLogStore.add("browser", "[r476] 图集全量抓取异常 " + t);
            }
            long cost = System.currentTimeMillis() - t0;
            List<String> out;
            if (full.size() > domSize) {
                java.util.LinkedHashSet<String> merged = new java.util.LinkedHashSet<>(full);
                if (domImgs != null) merged.addAll(domImgs);
                out = new ArrayList<>(merged);
            } else {
                out = domImgs == null ? new ArrayList<>() : domImgs;
            }
            DebugLogStore.add("browser", "[r476] 图集全量抓取 " + out.size() + " 张（全量 " + full.size()
                    + " / 首屏 " + domSize + "，耗时 " + cost + "ms）");
            final List<String> fin = out;
            runOnUiThread(() -> {
                if (fin.isEmpty()) {
                    Notify.show("未提取到图片");
                    return;
                }
                List<String> one = new ArrayList<>();
                one.add("pics://" + TextUtils.join("&&", fin));
                DebugLogStore.add("browser", "[r476] 进图片查看器 " + fin.size() + " 张");
                playBrowserUrls(title, one, 0);
            });
        }, "gallery-expand").start();
    }

    /** r486：小说章节探测 JS ——「第N章/第N节/第N回」锚枚举（去重、上限 300）；
     *  锚不足 30 且页面有「全部章节/章节目录」链接时回传目录页地址（cat），Java 侧复用
     *  WebSpider.novelChaptersFrom 跟随抓完整列表（介绍页跟随 + 分页聚合，形态自适应不锚死单站）。 */
    private static final String NOVEL_PROBE =
            "(function(){try{var chs=[],seen={},cat='';"
                    + "var re=/^第[一二三四五六七八九十百千零〇\\d]{1,7}[章节回卷]/;"
                    + "var as=document.querySelectorAll('a[href]');"
                    + "for(var i=0;i<as.length&&chs.length<300;i++){"
                    + "var t=(as[i].textContent||'').replace(/\\s+/g,'');"
                    + "if(t.length<2||t.length>60||!re.test(t))continue;"
                    + "var h=as[i].href||'';"
                    + "if(!h||h.indexOf('http')!==0||seen[h])continue;seen[h]=1;chs.push([t,h])}"
                    + "if(chs.length<30){var ls=document.querySelectorAll('a');"
                    + "for(var j=0;j<ls.length;j++){var tt=(ls[j].textContent||'').replace(/\\s+/g,'');"
                    + "if(/^(全部章节|查看全部章节|章节目录|完整目录|查看目录|目录)$/.test(tt)&&/^https?:/.test(ls[j].href||'')){cat=ls[j].href;break}}}"
                    + "return JSON.stringify({chs:chs,cat:cat})}catch(e){return '{\"chs\":[],\"cat\":\"\"}'}})()";

    /** r486：小说分流 —— nv 结构信号命中后：
     *  ① 页面「第N章」锚 ≥2 → 整本进内置阅读器（章节名$章节页URL，独立虚拟源 browser_novel 按需回源）；
     *  ② 锚不足时跟随「全部章节/目录」链接（NOVEL_PROBE 的 cat）复用 WebSpider.novelChaptersFrom
     *     抓完整目录（介绍页跟随 + 分页聚合）；③ 都没有 → 单章 novelPayload 兜底（r475 行为）。 */
    private void routeNovel() {
        final String pageUrl = binding.browserWeb.getUrl();
        final String title = pageTitle;
        binding.browserWeb.evaluateJavascript(NOVEL_PROBE, v -> {
            List<String[]> chs = new ArrayList<>();
            String cat = "";
            try {
                String json = v;
                if (json != null && json.startsWith("\"") && json.endsWith("\"")) json = new JSONTokener(json).nextValue().toString();
                JSONObject obj = new JSONObject(json);
                JSONArray arr = obj.optJSONArray("chs");
                if (arr != null) {
                    for (int i = 0; i < arr.length() && i < 300; i++) {
                        JSONArray it = arr.optJSONArray(i);
                        if (it != null && it.length() >= 2) chs.add(new String[]{it.optString(0), it.optString(1)});
                    }
                }
                cat = obj.optString("cat", "");
            } catch (Throwable e) {
                DebugLogStore.add("browser", "[r486] NOVEL_PROBE 解析失败 " + e);
            }
            final List<String[]> domChs = chs;
            final String catUrl = cat;
            new Thread(() -> {
                List<String[]> full = domChs;
                try {
                    WebSpider sp =
                            new WebSpider("browser_novel", pageUrl, pageUrl);
                    // r486：**当前页也过一遍 Java 聚合** —— 目录页 DOM 锚只是第 1 页（x33yq 实测
                    //   首页 51 章锚、完整 148 章在 _2/_3 页），novelChaptersFrom 会跟「下一页」聚合；
                    //   介绍页/章节页由其内部「介绍页→目录页跟随」拿到全书。比 DOM 多才采用。
                    List<String[]> agg = sp.novelChaptersFrom(pageUrl);
                    if (agg != null && agg.size() > full.size()) full = agg;
                    // r486：锚不足且页面给了「全部章节/目录」链接 → 跟目录页抓完整目录。
                    if (full.size() < 30 && !TextUtils.isEmpty(catUrl) && catUrl.startsWith("http")) {
                        List<String[]> more = sp.novelChaptersFrom(catUrl);
                        if (more != null && more.size() > full.size()) full = more;
                    }
                } catch (Throwable t) {
                    DebugLogStore.add("browser", "[r486] 目录页跟随失败 " + t);
                }
                DebugLogStore.add("browser", "[r486] NOVEL_PROBE 页面锚 " + domChs.size()
                        + (full.size() > domChs.size() ? " → 目录页跟随后 " + full.size() : ""));
                final List<String[]> fin = full;
                runOnUiThread(() -> launchNovelReader(fin, title));
            }, "novel-catalog").start();
        });
    }

    /** r486：章节列表 ≥2 → 内置阅读器整本（browser_novel 回源）；否则单章正文兜底。 */
    private void launchNovelReader(List<String[]> chs, String title) {
        if (chs != null && chs.size() >= 2) {
            DebugLogStore.add("browser", "[r486] 小说分流 整本 " + chs.size() + " 章 → 内置阅读器（browser_novel 按需回源）");
            ArrayList<String> items = new ArrayList<>();
            for (String[] c : chs) if (c != null && c.length >= 2) items.add(c[0] + "$" + c[1]);
            launchNovelReaderAvbox(items, TextUtils.isEmpty(title) ? "小说" : title);
            return;
        }
        String payload = novelPayload();
        if (!payload.isEmpty()) {
            DebugLogStore.add("browser", "[r486] 小说分流 无章节列表 → 单章正文进阅读器 " + pageTextLen + " 字");
            List<String> one = new ArrayList<>();
            one.add(payload);
            playBrowserUrls(title, one, 0);
        } else {
            Notify.show("未提取到小说内容");
        }
    }

    /** r486：注册域（末两段）比较 —— www.xx.com ↔ m.xx.com 视为同域放行；跨主域跳转判异。 */
    private static boolean sameRegistrableDomain(String a, String b) {
        if (TextUtils.isEmpty(a) || TextUtils.isEmpty(b)) return false;
        String ra = registrable(a), rb = registrable(b);
        return !ra.isEmpty() && ra.equals(rb);
    }

    private static String registrable(String host) {
        String s = host == null ? "" : host.toLowerCase(java.util.Locale.ROOT);
        String[] parts = s.split("\\.");
        if (parts.length < 2) return s;
        String last2 = parts[parts.length - 2] + "." + parts[parts.length - 1];
        // 常见多级公共后缀必须再向上取一段，避免不同 co.uk/com.cn 站被误判同站。
        if (parts.length >= 3 && "co.uk,org.uk,ac.uk,com.cn,net.cn,org.cn,gov.cn,edu.cn,co.jp,com.au,net.au,org.au,com.hk,com.tw,co.kr,com.sg".contains(last2)) {
            return parts[parts.length - 3] + "." + last2;
        }
        return last2;
    }

    /** r474：novel:// 载荷（壳协议：{"content":正文,"title":章节名}，与 WebSpider 分流同构）。 */
    private String novelPayload() {
        try {
            JSONObject obj = new JSONObject();
            obj.put("content", pageText);
            obj.put("title", TextUtils.isEmpty(pageTitle) ? "网页正文" : pageTitle);
            return "novel://" + obj;
        } catch (Throwable e) {
            return "";
        }
    }

    /** r473：解析 EPISODE_PROBE 回包（evaluateJavascript 的字符串回包带外层引号+转义）。 */
    private List<String> parseEpisodeList(String value) {
        List<String> urls = new ArrayList<>();
        try {
            if (TextUtils.isEmpty(value) || "null".equals(value)) return urls;
            String json = value;
            if (json.startsWith("\"") && json.endsWith("\"")) json = new JSONTokener(json).nextValue().toString();
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                String u = arr.optString(i, "");
                if (u.startsWith("http")) urls.add(u);
            }
        } catch (Throwable ignored) {
        }
        return urls;
    }

    /** 3.68 B：live DOM 入口候选探针 —— 收 a[href]（文本≤60 字）与 button[onclick] 里的
     *  跳转地址（绝对 URL 与 location.href/window.open 相对地址），JSON 数组回传，
     *  喂 PublishPageResolver.resolveCandidates 判定真入口（不重新抓取页面）。 */
    private static final String PUBLISH_PROBE =
            "(function(){try{var out=[];"
                    + "var as=document.querySelectorAll('a[href]');"
                    + "for(var i=0;i<as.length&&out.length<200;i++){var h=as[i].href||'';if(!h)continue;"
                    + "out.push({u:h,t:(as[i].textContent||'').replace(/\\s+/g,' ').trim().slice(0,60)})}"
                    + "var bs=document.querySelectorAll('button');"
                    + "for(var j=0;j<bs.length&&out.length<300;j++){var oc=bs[j].getAttribute('onclick')||'';"
                    + "var t=(bs[j].textContent||'').replace(/\\s+/g,' ').trim().slice(0,60);"
                    + "var m=oc.match(/https?:\\/\\/[^'\"\\s]+/);if(m){out.push({u:m[0],t:t});continue;}"
                    + "var m2=oc.match(/(?:window\\.open\\s*\\(\\s*|location\\.href\\s*=\\s*|location\\s*=\\s*)['\"]([^'\"]+)['\"]/);"
                    + "if(m2)out.push({u:m2[1],t:t})}"
                    + "return JSON.stringify(out)}catch(e){return '[]'}})()";

    /** 3.68 B：顶栏「真站」—— 用 live WebView 已有 DOM 判定发布页真入口并直接跳。
     *  仍是网页源、不能搜。判定走 PublishPageResolver.resolveCandidates（后台判定、主线程回调）；
     *  定真入口后先把 jumpHost 更新为真站 host 再 loadUrl（否则后续跳转会被跨站拦截拦回来），
     *  跟进机制仍走现有 updateJumpState / shouldOverrideUrlLoading，不另起豁免。 */
    private void onAiFix() {
        if (aiFixRunning) {
            Notify.show("真站进行中…");
            return;
        }
        if (AiTaskBoard.INSTANCE.getActiveTitle() != null) {
            Notify.show("已有任务在跑：" + AiTaskBoard.INSTANCE.getActiveTitle());
            return;
        }
        String currentUrl = binding.browserWeb == null ? "" : String.valueOf(binding.browserWeb.getUrl());
        if (TextUtils.isEmpty(currentUrl) || !currentUrl.startsWith("http")) {
            Notify.show("当前页不是网页，无法修");
            return;
        }
        // 先停掉空白页复核回滚，否则 AI 还在判、页面已被自动回滚到入口。
        if (blankRecheck != null) {
            binding.browserWeb.removeCallbacks(blankRecheck);
            blankRecheck = null;
        }
        aiFixRunning = true;
        AiTaskBoard.INSTANCE.setActive("真站");
        AiTaskBoard.INSTANCE.log("真站：正在取当前页链接…" + currentUrl);
        Notify.show("真站进行中…");
        binding.browserWeb.evaluateJavascript(PUBLISH_PROBE, value -> {
            String json = unescapeJsString(value);
            if (TextUtils.isEmpty(json)) json = "[]";
            PublishPageResolver.resolveCandidates(currentUrl, json, result -> {
                aiFixRunning = false;
                AiTaskBoard.INSTANCE.setActive(null);
                if (isFinishing() || binding == null) return;
                if (result == null) {
                    DebugLogStore.add("browser", "真站失败：解析失败");
                    Notify.show("真站失败：解析失败");
                    return;
                }
                if (!result.isPublishPage()) {
                    DebugLogStore.add("browser", "真站：不是发布页 " + currentUrl);
                    AiTaskBoard.INSTANCE.log("真站：不是发布页，按普通站处理");
                    Notify.show("不是发布页，无需修");
                    return;
                }
                String target = result.getFinalUrl();
                // 先把 jumpHost 更新为真站 host 再 loadUrl；落地后 v77 的 vod 链接检测会按现有机制结束跳转链。
                jumpHost = hostOf(target);
                followJumpHost = true;
                DebugLogStore.add("browser", "真站：真入口 " + target + "（" + result.getKind() + "）");
                AiTaskBoard.INSTANCE.log("真站：真入口 " + target + "（" + result.getKind() + "）");
                Notify.show("已找到真入口，正在跳转…");
                binding.browserWeb.loadUrl(target);
            });
        });
    }

    /** evaluateJavascript 的字符串回包带外层引号+转义，先解包再用（与 parseEpisodeList 同法）。 */
    private static String unescapeJsString(String value) {
        try {
            if (TextUtils.isEmpty(value) || "null".equals(value)) return "";
            String s = value;
            if (s.startsWith("\"") && s.endsWith("\"")) s = new JSONTokener(s).nextValue().toString();
            return s;
        } catch (Throwable ignored) {
            return "";
        }
    }

    /** 3.68 C：顶栏「记录」开关 —— 开：已有规则直接用，无规则走 AiAdLearner 学习；
     *  关：只关开关、规则留着（不删桶），当前页 reload 一次恢复被清元素。 */
    private void onAiProcessToggle() {
        String host = TextUtils.isEmpty(pageHost) ? homeHost : pageHost;
        if (TextUtils.isEmpty(host)) {
            Notify.show("当前页未知站点");
            return;
        }
        if (AiAdLearner.isLearning()) {
            Notify.show("AI 正在学习中…");
            return;
        }
        if (AiAdRuleStore.isEnabled(host)) {
            AiAdRuleStore.setEnabled(host, false);
            refreshAiProcessTint();
            DebugLogStore.add("browser", "记录已关 " + host + "（规则留着）");
            binding.browserWeb.reload();
            return;
        }
        if (AiAdRuleStore.hasRules(host)) {
            AiAdRuleStore.setEnabled(host, true);
            refreshAiProcessTint();
            DebugLogStore.add("browser", "记录已开 " + host + "（已有规则直接用）");
            binding.browserWeb.reload();
            return;
        }
        String learnUrl = binding.browserWeb == null ? "" : String.valueOf(binding.browserWeb.getUrl());
        if (TextUtils.isEmpty(learnUrl) || !learnUrl.startsWith("http")) {
            Notify.show("当前页不是网页，无法学习");
            return;
        }
        // 学习中禁用开关（回调里恢复）。
        binding.browserAiProcess.setEnabled(false);
        binding.browserAiProcess.setAlpha(0.5f);
        Notify.show("记录学习中…");
        AiAdLearner.learn(this, learnUrl, new AiAdLearner.Callback() {
            @Override
            public void onSuccess(String h, int domains, int selectors, int triggers) {
                if (isFinishing() || binding == null) return;
                binding.browserAiProcess.setEnabled(true);
                binding.browserAiProcess.setAlpha(1.0f);
                AiAdRuleStore.setEnabled(h, true);
                refreshAiProcessTint();
                DebugLogStore.add("browser", "记录学习完成 " + h + " 域名 " + domains + " 选择器 " + selectors + " 触发器 " + triggers);
                AiTaskBoard.INSTANCE.log("记录学习完成：" + h + " 域名 " + domains + " 选择器 " + selectors + " 触发器 " + triggers);
                Notify.show("记录已开：学到 " + (domains + selectors + triggers) + " 条规则");
                binding.browserWeb.reload();
            }

            @Override
            public void onFailure(String h, String reason) {
                if (isFinishing() || binding == null) return;
                binding.browserAiProcess.setEnabled(true);
                binding.browserAiProcess.setAlpha(1.0f);
                AiAdRuleStore.setEnabled(h, false);
                refreshAiProcessTint();
                AiTaskBoard.INSTANCE.log("记录学习失败：" + reason);
                DebugLogStore.add("browser", "记录学习失败 " + h + "：" + reason);
                Notify.show("记录学习失败：" + reason);
            }
        });
    }

    /** 3.68 C：记录开关 tint —— 开着切主题主色，关着回默认前景色；换站按新站 host 重读。 */
    private void refreshAiProcessTint() {
        if (binding == null) return;
        String host = TextUtils.isEmpty(pageHost) ? homeHost : pageHost;
        boolean on = !TextUtils.isEmpty(host) && AiAdRuleStore.isEnabled(host);
        // r477：关着用 ViewTheme 的 onSurface（跟随应用主题），不用 textColorPrimary（浅色主题属性，黑色主题下图标会看不见）。
        int color = on ? themeColor(R.attr.colorPrimary, 0xFF6200EE) : ViewTheme.INSTANCE.onSurfaceArgb(this);
        binding.browserAiProcess.setColorFilter(color);
    }

    /** 3.70：顶栏「放行」开关 —— 开：本站当普通浏览器用（静态拦截 + FILTER_JS + CLEAN_JS /
     *  记录规则全跳过），关：恢复原来拦截；规则本身不删，放行关掉即恢复生效。
     *  与记录开关互不干扰：放行开着时记录开关仍可点、仍可学习（学习走离屏 WebView，本来就全放行）。 */
    private void onAiPassToggle() {
        String host = TextUtils.isEmpty(pageHost) ? homeHost : pageHost;
        if (TextUtils.isEmpty(host)) {
            Notify.show("当前页未知站点");
            return;
        }
        boolean next = !AiAdRuleStore.isPassthrough(host);
        AiAdRuleStore.setPassthrough(host, next);
        refreshAiPassTint();
        DebugLogStore.add("browser", "放行已" + (next ? "开 " : "关 ") + host);
        Notify.show("放行已" + (next ? "开 " : "关 ") + host);
        binding.browserWeb.reload();
    }

    /** 3.70：放行开关 tint —— 同记录开关口径，开着切主题主色，关着回默认前景色；换站按新站 host 重读。 */
    private void refreshAiPassTint() {
        if (binding == null) return;
        String host = TextUtils.isEmpty(pageHost) ? homeHost : pageHost;
        boolean on = !TextUtils.isEmpty(host) && AiAdRuleStore.isPassthrough(host);
        // r477：关着用 ViewTheme 的 onSurface（跟随应用主题），不用 textColorPrimary。
        int color = on ? themeColor(R.attr.colorPrimary, 0xFF6200EE) : ViewTheme.INSTANCE.onSurfaceArgb(this);
        binding.browserAiPass.setColorFilter(color);
    }

    /**
     * r473：网页视频集数面板（对标参考 App「1. xxx001 / 2. xxx002 / App 播放全部 N 集」）。
     * 「播放全部」从第 1 集起播（壳播放器自动连播/上下集）；逐集行点击直接播该集。
     */
    private void showEpisodeSheet(List<String> urls, boolean pageMode) {
        int count = urls.size();
        String[] names = new String[count];
        for (int i = 0; i < count; i++) {
            if (pageMode) {
                // r483：页面型选集（javascript:; 合成/同 vid 链接）—— URL 尾段就是集号（…-{vid}-{sid}-{nid}.html）
                java.util.regex.Matcher pm = java.util.regex.Pattern.compile("-(\\d+)(\\.html?)?$").matcher(urls.get(i));
                names[i] = pm.find() ? "第" + pm.group(1) + "集" : "第" + (i + 1) + "集";
            } else {
                String label = episodeLabel(urls.get(i));
                names[i] = "第" + (i + 1) + "集" + (label.isEmpty() ? "" : " · " + label);
            }
        }
        String title = TextUtils.isEmpty(pageTitle) ? "网页视频" : sub(pageTitle, 24);
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(title + " · " + count + " 集")
                .setItems(names, (d, w) -> {
                    if (pageMode) {
                        // r483：页面型选集逐集在网页内切换，该集页面起播嗅到直链后点悬浮球「原生播放」接管
                        //  （直链就绪后 playNative 只收直链，不会再把页面 URL 喂给播放器）。
                        DebugLogStore.add("browser", "[r483] 网页切换选集 第" + (w + 1) + "集 " + sub(urls.get(w), 100));
                        Notify.show("已切到第 " + (w + 1) + " 集，起播后点悬浮球原生播放");
                        binding.browserWeb.loadUrl(urls.get(w));
                    } else {
                        DebugLogStore.add("browser", "[r473] 点选集数 第" + (w + 1) + "集 " + sub(urls.get(w), 100));
                        playBrowserUrls(pageTitle, urls, w);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null);
        if (!pageMode) {
            builder.setPositiveButton("App 播放全部 " + count + " 集", (d, w) -> {
                DebugLogStore.add("browser", "[r473] 播放全部 " + count + " 集");
                playBrowserUrls(pageTitle, urls, 0);
            });
        }
        builder.show();
    }

    /** r489：LINE_PROBE 解析出的线路（一行 = 一个 tab + 一组选集）。 */
    private static class PlayLine {
        final String name;
        final List<Ep> eps = new ArrayList<>();

        PlayLine(String name) {
            this.name = name;
        }
    }

    private static class Ep {
        final String name;
        final String url;

        Ep(String name, String url) {
            this.name = name;
            this.url = url;
        }
    }

    /** r489：解析 LINE_PROBE 的 JSON。badge 校验：面板集数与 tab 角标对不上 → 整组探测不可信，返回空走 sid 兜底。 */
    private List<PlayLine> parseLineProbe(String value) {
        List<PlayLine> lines = new ArrayList<>();
        try {
            JSONObject obj = new JSONObject(value);
            JSONArray arr = obj.optJSONArray("lines");
            if (arr == null) return lines;
            String cur = binding.browserWeb == null ? "" : String.valueOf(binding.browserWeb.getUrl());
            String vid = vidOf(cur);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject lo = arr.optJSONObject(i);
                if (lo == null) continue;
                String name = lo.optString("name", "").trim();
                String badge = lo.optString("badge", "").trim();
                JSONArray eps = lo.optJSONArray("episodes");
                if (eps == null || eps.length() == 0 || name.isEmpty()) continue;
                PlayLine line = new PlayLine(name);
                for (int j = 0; j < eps.length(); j++) {
                    JSONObject eo = eps.optJSONObject(j);
                    if (eo == null) continue;
                    String u = eo.optString("url", "");
                    String nm = eo.optString("name", "").trim();
                    if (u.isEmpty() || nm.isEmpty() || !u.startsWith("http")) continue;
                    if (!sameVid(u, vid)) continue;
                    boolean dup = false;
                    for (Ep e : line.eps) if (e.url.equals(u)) {
                        dup = true;
                        break;
                    }
                    if (!dup) line.eps.add(new Ep(nm, u));
                }
                if (line.eps.isEmpty()) continue;
                if (!badge.isEmpty()) {
                    try {
                        if (Integer.parseInt(badge) != line.eps.size()) {
                            DebugLogStore.add("browser", "[r489] 线路 " + name + " badge=" + badge + " 实得=" + line.eps.size() + " 不一致，丢弃整组探测");
                            return new ArrayList<>();
                        }
                    } catch (NumberFormatException ignored) {
                    }
                }
                lines.add(line);
            }
        } catch (Throwable e) {
            DebugLogStore.add("browser", "[r489] LINE_PROBE 解析失败 " + e);
        }
        return lines;
    }

    /** r489：从 URL 取 vid（{vid}-{sid}-{nid}.html 形态），取不到为空。 */
    private static String vidOf(String url) {
        if (url == null) return "";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)-(\\d+)-(\\d+)(\\.html?)?$").matcher(url.split("\\?")[0]);
        return m.find() ? m.group(1) : "";
    }

    /** r489：同 vid 过滤（r483 判据）—— 非 vodplay 形态的 URL 不受限，直接放行。 */
    private static boolean sameVid(String url, String curVid) {
        if (curVid == null || curVid.isEmpty()) return true;
        String v = vidOf(url);
        return v.isEmpty() || v.equals(curVid);
    }

    /** r489 兜底：无线路 tab 时，把页面型选集按 {vid}-{sid}-{nid} 的 sid 分组，每组一条线路（名取 sid）。 */
    private List<PlayLine> groupPageUrlsBySid(List<String> pageUrls) {
        java.util.TreeMap<String, PlayLine> map = new java.util.TreeMap<>();
        String cur = binding.browserWeb == null ? "" : String.valueOf(binding.browserWeb.getUrl());
        String vid = vidOf(cur);
        for (String u : pageUrls) {
            if (u == null || !u.startsWith("http")) continue;
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)-(\\d+)-(\\d+)(\\.html?)?$").matcher(u.split("\\?")[0]);
            if (!m.find()) continue;
            if (!vid.isEmpty() && !m.group(1).equals(vid)) continue;
            String sid = m.group(2);
            String nid = m.group(3);
            PlayLine line = map.get(sid);
            if (line == null) {
                line = new PlayLine("线路" + sid);
                map.put(sid, line);
            }
            boolean dup = false;
            for (Ep e : line.eps) if (e.url.equals(u)) {
                dup = true;
                break;
            }
            if (!dup) line.eps.add(new Ep("第" + nid + "集", u));
        }
        List<PlayLine> out = new ArrayList<>(map.values());
        out.removeIf(l -> l.eps.isEmpty());
        return out.size() >= 2 ? out : new ArrayList<>();
    }

    /** r489：线路是否覆盖了全部待选集（防面板漏集导致选集面板丢集）。 */
    private static boolean linesCover(List<PlayLine> lines, List<String> urls) {
        java.util.HashSet<String> have = new java.util.HashSet<>();
        for (PlayLine l : lines) for (Ep e : l.eps) have.add(e.url);
        for (String u : urls) if (!have.contains(u)) return false;
        return true;
    }

    /** r489：有线路结构时用线路面板，否则回退旧单列面板（老站零回归）。 */
    private void showEpisodeSheetWithLines(List<String> urls, boolean pageMode) {
        binding.browserWeb.evaluateJavascript(LINE_PROBE, lineValue -> {
            List<PlayLine> lines = parseLineProbe(lineValue);
            if (lines.size() < 2 || !linesCover(lines, urls)) {
                lines = groupPageUrlsBySid(urls);
            }
            if (lines.size() >= 2) {
                StringBuilder names = new StringBuilder();
                for (PlayLine l : lines) {
                    if (names.length() > 0) names.append("、");
                    names.append(l.name).append(l.eps.size()).append("集");
                }
                DebugLogStore.add("browser", "[r489] 线路面板 " + lines.size() + " 条线路（" + names + "）");
                showLineSheet(lines, pageMode);
            } else {
                showEpisodeSheet(urls, pageMode);
            }
        });
    }

    /** r489：线路选集面板 —— 先选线路（带集数），再选集。 */
    private void showLineSheet(List<PlayLine> lines, boolean pageMode) {
        String title = TextUtils.isEmpty(pageTitle) ? "网页视频" : sub(pageTitle, 24);
        String[] lineNames = new String[lines.size()];
        for (int i = 0; i < lines.size(); i++) lineNames[i] = lines.get(i).name + " · " + lines.get(i).eps.size() + "集";
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(title + " · " + lines.size() + "条线路")
                .setItems(lineNames, (d, w) -> showLineEpisodes(lines, w, pageMode, title))
                .setNegativeButton(android.R.string.cancel, null);
        builder.setPositiveButton("播放全部线路", (d, w) -> {
            DebugLogStore.add("browser", "[r489] 播放全部线路 " + lines.size() + " 条");
            playBrowserUrlsLines(title, lines, -1, -1);
        });
        builder.show();
    }

    /** r489：某条线路的选集 —— 点集直接推壳播放器播（页面型走 parse=1 现场嗅探）。 */
    private void showLineEpisodes(List<PlayLine> lines, int lineIdx, boolean pageMode, String title) {
        PlayLine line = lines.get(lineIdx);
        String[] names = new String[line.eps.size()];
        for (int i = 0; i < line.eps.size(); i++) names[i] = line.eps.get(i).name;
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(line.name + " · " + line.eps.size() + "集")
                .setItems(names, (d, w) -> {
                    Ep ep = line.eps.get(w);
                    DebugLogStore.add("browser", "[r489] 点选集数 " + line.name + " " + ep.name + " " + sub(ep.url, 100));
                    playBrowserUrlsLines(title, lines, lineIdx, w);
                })
                .setNegativeButton(android.R.string.cancel, null);
        // r489：页面型整条线路也能推（parse=1 按需嗅探），与媒体直链型一致。
        builder.setPositiveButton("播放本线路全部 " + line.eps.size() + " 集", (d, w) -> {
            DebugLogStore.add("browser", "[r489] 播放本线路全部 " + line.name + " " + line.eps.size() + " 集");
            playBrowserUrlsLines(title, lines, lineIdx, -1);
        });
        builder.show();
    }

    /** r489：多线路起播 —— epIdx>=0 只推该集，lineIdx>=0 推整条线路，lineIdx<0 推全部线路。 */
    private void playBrowserUrlsLines(String title, List<PlayLine> lines, int lineIdx, int epIdx) {
        try {
            JSONArray arr = new JSONArray();
            if (epIdx >= 0 && lineIdx >= 0 && lineIdx < lines.size()) {
                PlayLine line = lines.get(lineIdx);
                if (epIdx < line.eps.size()) {
                    JSONObject lo = new JSONObject();
                    lo.put("name", line.name);
                    JSONArray eps = new JSONArray();
                    JSONObject eo = new JSONObject();
                    eo.put("name", line.eps.get(epIdx).name);
                    eo.put("url", line.eps.get(epIdx).url);
                    eps.put(eo);
                    lo.put("eps", eps);
                    arr.put(lo);
                }
            } else if (lineIdx >= 0 && lineIdx < lines.size()) {
                arr.put(toJsonLine(lines.get(lineIdx)));
            } else {
                for (PlayLine line : lines) arr.put(toJsonLine(line));
            }
            if (arr.length() == 0) return;
            String payload = BrowserPlaySpider.browserPlayIdLines(title, arr, bplayReferer(), bplayUa());
            if (TextUtils.isEmpty(payload)) {
                Notify.show("未检测到可提取的视频地址");
                return;
            }
            JumpKt.jumpToDetail(this, payload, BrowserPlaySpider.KEY, title, null, false, true, true);
        } catch (Throwable e) {
            DebugLogStore.add("browser", "[r489] 多线路起播失败 " + e);
        }
    }

    private static JSONObject toJsonLine(PlayLine line) throws org.json.JSONException {
        JSONObject lo = new JSONObject();
        lo.put("name", line.name);
        JSONArray eps = new JSONArray();
        for (Ep ep : line.eps) {
            JSONObject eo = new JSONObject();
            eo.put("name", ep.name);
            eo.put("url", ep.url);
            eps.put(eo);
        }
        lo.put("eps", eps);
        return lo;
    }

    /** r473：集数标签——地址尾段带 ≥2 位数字（xxx001）时用尾号，与 SiteApi 集名规则一致。 */
    private String episodeLabel(String url) {
        try {
            String tail = url;
            int cut = Math.max(tail.lastIndexOf('/'), tail.lastIndexOf('?'));
            if (cut >= 0) tail = tail.substring(cut + 1);
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{2,})[^/]*$").matcher(tail);
            if (m.find()) return m.group(1);
        } catch (Throwable ignored) {
        }
        return "";
    }

    /** 3.40：全屏方向按视频宽高定 —— 读全屏元素的 video 尺寸，竖的转竖屏、横的转横屏，拿不到默认横屏。 */
    private void applyFullscreenOrientation() {
        try {
            binding.browserWeb.evaluateJavascript("(function(){var el=document.fullscreenElement||document.webkitFullscreenElement;var v=null;if(el){v=el.tagName==='VIDEO'?el:el.querySelector('video');}if(!v)v=document.querySelector('video');if(!v||!v.videoWidth||!v.videoHeight)return '';return v.videoWidth+'x'+v.videoHeight;})();", value -> {
                try {
                    String s = value == null ? "" : value.replace("\"", "").trim();
                    String[] parts = s.split("x");
                    if (parts.length == 2) {
                        int w = Integer.parseInt(parts[0]);
                        int h = Integer.parseInt(parts[1]);
                        if (w > 0 && h > 0) {
                            setRequestedOrientation(h > w ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT : ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
                            return;
                        }
                    }
                } catch (Throwable ignored) {
                }
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
            });
        } catch (Throwable ignored) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        }
    }

    /** 3.39：退出视频全屏（返回键先退全屏，再回退网页）。 */
    private void exitFullscreen() {
        if (customView == null) return;
        ((ViewGroup) getWindow().getDecorView()).removeView(customView);
        customView = null;
        if (customViewCallback != null) {
            customViewCallback.onCustomViewHidden();
            customViewCallback = null;
        }
        binding.browserWeb.setVisibility(View.VISIBLE);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        // 3.40：退出全屏恢复方向策略（跟系统/锁竖/锁横，跟详情页退出全屏同款）。
        setRequestedOrientation(orientationPolicyValue());
    }

    /** 返回键语义：全屏先退全屏；网页能回退就回退，不能才退出页面（跨页浏览不丢上下文）。 */
    private void onBack() {
        if (customView != null) {
            exitFullscreen();
            return;
        }
        // 1.20：HTML 首页模式下，点一次返回直接退出浏览器，不走网页历史（每次 HTML/网页切换都记一条历史，不处理要点好几次才能退出）。
        if (mWebHomeMode) {
            finish();
            return;
        }
        if (binding.browserWeb.canGoBack()) binding.browserWeb.goBack();
        else finish();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            onBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    /** Muse v72~v76：跟进发布页自身 30x；真实站落地后撤销跨站跳转豁免。 */
    private void updateJumpState(String url) {
        boolean follow = !userGestured || followJumpHost;
        followJumpHost = false;
        String newHost = hostOf(url);
        if (follow && !TextUtils.isEmpty(jumpHost) && !TextUtils.isEmpty(newHost)
                && !newHost.equalsIgnoreCase(jumpHost) && !isAd(newHost, url)) {
            jumpHost = newHost;
        }
        if (jumpUsed && jumpOriginHost != null && !TextUtils.isEmpty(newHost)
                && !newHost.equalsIgnoreCase(jumpOriginHost)) {
            jumpHost = "";
            jumpUsed = false;
            jumpOriginHost = null;
        }
    }

    private void markJump(String originHost) {
        jumpUsed = true;
        jumpOriginHost = originHost;
    }

    private boolean isJumpHost(String currentHost) {
        return !TextUtils.isEmpty(jumpHost) && !TextUtils.isEmpty(currentHost)
                && jumpHost.equalsIgnoreCase(currentHost);
    }

    private void clearJumpChain() {
        jumpAnchorHost = null;
        jumpRelayHost = null;
        jumpChainUntil = 0;
    }

    private boolean isJumpServiceUrl(String targetUrl, String currentHost) {
        return !TextUtils.isEmpty(targetUrl) && !TextUtils.isEmpty(currentHost)
                && targetUrl.toLowerCase(java.util.Locale.ROOT).contains(currentHost.toLowerCase(java.util.Locale.ROOT));
    }

    /** 新窗口与主框导航同一口径；同站自动弹窗仍需手势，发布页例外用于 setTimeout 丢失手势的场景。 */
    private boolean isAllowedNewWindowUrl(String url, boolean hasGesture) {
        if (TextUtils.isEmpty(url)) return false;
        Uri uri;
        try { uri = Uri.parse(url); } catch (Throwable e) { return false; }
        String scheme = uri.getScheme();
        if (!"http".equals(scheme) && !"https".equals(scheme)) return false;
        if (isPopunderPath(uri.getPath())) return false;
        String targetHost = hostOf(url);
        if (isAd(targetHost, url)) return false;
        String currentHost = hostOf(binding.browserWeb.getUrl());
        if (TextUtils.isEmpty(currentHost)) currentHost = hostOf(anchorUrl);
        if (isJumpHost(currentHost)) {
            markJump(currentHost);
            return true;
        }
        if (sameSite(targetHost, currentHost)) return hasGesture;
        if (!hasGesture && isJumpServiceUrl(url, currentHost)) {
            jumpAnchorHost = currentHost;
            jumpRelayHost = targetHost;
            jumpChainUntil = SystemClock.uptimeMillis() + 10000;
            markJump(currentHost);
            return true;
        }
        return false;
    }

    private boolean isPopunderPath(String path) {
        if (TextUtils.isEmpty(path)) return false;
        String p = path.toLowerCase(java.util.Locale.ROOT);
        if (p.endsWith("/")) p = p.substring(0, p.length() - 1);
        return p.equals("/pop") || p.equals("/popunder") || p.equals("/popup")
                || p.endsWith("/pop") || p.endsWith("/popunder") || p.endsWith("/popup");
    }

    /** 广告名单可能误中轮换视频域：流、分片、密钥与音频请求不拦截，只拦截脚本/图片/iframe 等广告负载。 */
    private boolean isMediaRequest(String path) {
        if (TextUtils.isEmpty(path)) return false;
        String p = path.toLowerCase(java.util.Locale.ROOT);
        return p.endsWith(".m3u8") || p.endsWith(".mp4") || p.endsWith(".ts") || p.endsWith(".m4s")
                || p.endsWith(".mpd") || p.endsWith(".flv") || p.endsWith(".mov") || p.endsWith(".webm")
                || p.endsWith(".key") || p.endsWith(".mp3") || p.endsWith(".m4a") || p.endsWith(".aac");
    }

    /** 只把主媒体当一集；ts/m4s/key 是分片或密钥，不进入选集列表。 */
    private boolean isSniffableMediaUrl(String path) {
        if (TextUtils.isEmpty(path)) return false;
        String p = path.toLowerCase(java.util.Locale.ROOT);
        return p.endsWith(".m3u8") || p.endsWith(".mp4") || p.endsWith(".mpd")
                || p.endsWith(".flv") || p.endsWith(".mov") || p.endsWith(".webm");
    }

    /** Muse v100/v56：SPA 首次 onPageFinished 可能尚未水合，1.5 秒后仍为完全空白才回入口一次。 */
    private void recoverBlankPage(WebView view, String url) {
        final String blankJs = "(function(){try{var t=(document.title||'').trim()==='';var b=(document.body?document.body.innerText.trim():'')==='';var n=document.images.length;return (t?'1':'0')+','+(b?'1':'0')+','+n;}catch(e){return '0,0,0';}})()";
        view.evaluateJavascript(blankJs, value -> {
            String val = value;
            if (val != null && val.length() >= 2 && val.startsWith("\"") && val.endsWith("\"")) val = val.substring(1, val.length() - 1);
            if (!"1,1,0".equals(val) || blankRecovered || TextUtils.isEmpty(anchorUrl) || anchorUrl.equals(url)) return;
            if (blankRecheck != null) view.removeCallbacks(blankRecheck);
            blankRecheck = () -> view.evaluateJavascript(blankJs, again -> {
                String re = again;
                if (re != null && re.length() >= 2 && re.startsWith("\"") && re.endsWith("\"")) re = re.substring(1, re.length() - 1);
                if (!"1,1,0".equals(re) || blankRecovered || TextUtils.isEmpty(anchorUrl) || anchorUrl.equals(url)) return;
                blankRecovered = true;
                DebugLogStore.add("browser", "空白页复核后回滚入口 " + anchorUrl);
                view.loadUrl(anchorUrl);
            });
            view.postDelayed(blankRecheck, 1500);
        });
    }

    /** r487：JS 弹窗消息摘要（压空白、截 60 字）—— 拦截JS弹窗 日志用。 */
    private static String briefJs(String s) {
        String m = s == null ? "" : s.replaceAll("\\s+", " ").trim();
        return m.length() > 60 ? m.substring(0, 60) + "…" : m;
    }

    /** r472：网络/导航层广告判定 —— 用户规则 + host 特征表 + 灰产 TLD；路径特征只对 script/css 生效（防误杀正常轮播图）。 */
    private boolean isAd(String host, String url) {
        // r473：本站豁免——灰产 TLD 表（.sbs 等）会命中站点自身域名（r472 回归实证：www.fljtu20.sbs
        // 主文档被网络层整域拦截 → 白屏）。页面/入口自身的域永远放行：宁漏勿杀（白屏比广告严重）。
        // r478：广告配置型注入必须**先于本站豁免**判定 —— 真机实证（dypj8，09:27 日志）：
        //   广告代码就放在站点自己域下（https://dyg.dypj8.pics/abc/fixed_ui_5fd3ec.js
        //   → fetch /abc/data_b43fc7.json；哈希每轮轮换但目录/前缀不变），
        //   而本站豁免先返回 false → 配置照常加载 → 满屏赌博 banner 一条不删
        //   （用户报「全是广告，一点都没少」）。形态判据，与具体站点/域名无关。
        //   r476 误把本判据放在下文本站豁免之后，等于永不生效。
        if (url != null) {
            String u0 = url.toLowerCase();
            if ((u0.endsWith(".js") || u0.endsWith(".json") || u0.contains(".js?") || u0.contains(".json?"))
                    && (u0.contains("fixed_ui") || u0.contains("fixed_ad") || u0.contains("ad_config")
                    || u0.contains("adconfig") || u0.contains("data_ad") || u0.contains("ad_data")
                || u0.contains("ads_data") || u0.contains("adjson") || u0.contains("ad_json")
                || u0.contains("fixed_jump"))) {
                return true;
            }
        }
        // r488：站内挂马中转拦截（先于本站豁免——形态判据与域名无关，r478 铁律）：dypj8/dkb 同族
        //   挂马件把任意点击劫持到本站域 /000/flink/url.php?id=…（30x 到广告落地页）。r487 弹回只兜
        //   落地页，中转本身放行会造成「弹回→中转→再跳」循环；导航层拦掉中转即掐断整条链。
        if (url != null) {
            String u0 = url.toLowerCase();
            if (u0.contains("/flink/") || u0.contains("url.php?id=") || u0.contains("click.php?id=")) return true;
        }
        // r473：本站豁免——灰产 TLD 表（.sbs 等）会命中站点自身域名（r472 回归实证：www.fljtu20.sbs
        // 主文档被网络层整域拦截 → 白屏）。页面/入口自身的域永远放行：宁漏勿杀（白屏比广告严重）。
        if (isSelfHost(host)) return false;
        // P0-1：用户规则层改走 RuleConfig（订阅配置 ads 主机表，与 AdBlocker 同源；
        // 边界匹配 + AdDomains + 正则规则，与原来 AdBlocker.isAd 同款判定）。
        if (RuleConfig.get().isAdHost(host)) return true;
        // 3.68 C：本站 AI 规则（开关开着才查；第一个参数是本站 host=规则桶键，不是请求 host）。
        //   调用点已过媒体豁免（shouldInterceptRequest 的 !isMediaRequest），此处不再重复判媒体。
        if (AiAdRuleStore.isEnabled(pageHost) && AiAdRuleStore.isAdDomain(pageHost, url)) return true;
        String low = host == null ? "" : host.toLowerCase();
        for (String ad : AD_HOSTS) if (low.contains(ad)) return true;
        // r475：同灰产 TLD 豁免 —— fljtu20.sbs 的图片资源域 wzbrk29/30.sbs 与主站注册域不同
        // （isSelfHost 的同父域救不了）→ 被 AD_TLDS 整 TLD 拦截 → 站点图片全挂（用户报「图片打不开」）。
        // 吃瓜/防红站普遍「主域+轮换图床」同 TLD（站长批量注册）；当前页/入口站就在该灰产 TLD 时，
        // 同 TLD 资源域大概率是自家图床 → 放行；跨 TLD 灰产广告（.sbs 站里的 .hair/.beer 广告）仍拦。
        String pageLow = pageHost == null ? "" : pageHost.toLowerCase();
        String homeLow = homeHost == null ? "" : homeHost.toLowerCase();
        for (String tld : AD_TLDS) {
            if (low.endsWith(tld) && !pageLow.endsWith(tld) && !homeLow.endsWith(tld)) return true;
        }
        // r475：推广落地页形态判据（真机实证：xseebb905.com:8321 / pcbc204.com:8621 / xsanan982.com:8521
        // 广告劫持页全部 /tg.htm?channel=XXX + 高位端口；域名随机轮换但路径/端口形态恒定，
        // 静态域名表永远追不上。isSelfHost 已豁免本站，此处只对无关域生效：
        // ① /tg.htm|.html 跳板页（广告联盟通用名，正常内容站不用）② channel= 推广参数 + 高位端口(>8000) 双铁证。
        if (url != null) {
            String u = url.toLowerCase();
            if (u.contains("/tg.htm")) return true;
            // channel= 推广参数 + 高位端口(>8000) 双铁证（8321/8521/8621 实证；正常站 80/443）
            if (u.contains("channel=")) {
                try {
                    if (android.net.Uri.parse(url).getPort() > 8000) return true;
                } catch (Throwable ignored) {
                }
            }
        }
        // r485：广告联盟投放/点击链形态判据（真机 fljtu20 两次独立实证）——
        //   投放脚本 https://141196c.hcxftcl.com:902/d/1196?c=1&n=bakujbij 与
        //   https://141196g.nf65hwr.com:902/sc/1196?n=bakujbij（DOM 层杀掉又被反复注入，
        //   真机日志 adNode 43→83 持续增长）；点击落地 https://xjaidsjv.wpfh6vg.com:902/cc/1196?
        //   advertiser_ad_id=1012&webmaster_ad_id=…（r479 已实证同联盟 141196c…:902/cc/1196）。
        //   域名随机轮换（hcxftcl/nf65hwr/wpfh6vg 同一联盟）、.com 不在灰产 TLD 表、
        //   902<8000 不中 r479 高位端口规则 → 现有判据全漏。只认形态不锚域名：
        //   ① 联盟点击指纹参数 advertiser_ad_id= / webmaster_ad_id=（两次实证的点击链指纹）；
        //   ② 特权端口（0<port<1024 且非 80/443）+ 路径 ^/[a-z]{1,4}/\d+/?$
        //   （/d/1196 /sc/1196 /cc/1196 投放与点击同构）。isAd 同时服务
        //   shouldInterceptRequest（断投放脚本）与 shouldOverrideUrlLoading（断点击跳转），
        //   一处添加两层生效。置于 isSelfHost 之后：联盟域永远不是本站域，
        //   且避免本站 :902 资源被误杀白屏。
        if (url != null) {
            String u = url.toLowerCase();
            if (u.contains("advertiser_ad_id=") || u.contains("webmaster_ad_id=")) return true;
            // r488：channelCode= 推广渠道码（8kvod 实证：top.js 顶横幅 + xs2.js 假推荐卡 + 导航「妹子」
            //   全指向 af1621it.zhuozhoutn.com/mbs17.html?channelCode=ggys2；与 r475 channel= 同族指纹，
            //   无端口依赖、域名随机轮换，只认参数形态。isSelfHost 已豁免本站。
            if (u.contains("channelcode=")) return true;
            try {
                android.net.Uri pu = android.net.Uri.parse(url);
                int port = pu.getPort();
                String path = pu.getPath() == null ? "" : pu.getPath().toLowerCase();
                // r488：媒体/图片/字体直链豁免 —— 直播源常用 :8000+ 无扩展名分片路径，先豁免再判端口形态
                boolean mediaLike = path.endsWith(".m3u8") || path.endsWith(".ts") || path.endsWith(".mp4")
                        || path.endsWith(".m4a") || path.endsWith(".aac") || path.endsWith(".flv")
                        || path.endsWith(".mp3") || path.endsWith(".key") || path.endsWith(".jpg")
                        || path.endsWith(".jpeg") || path.endsWith(".png") || path.endsWith(".webp")
                        || path.endsWith(".gif") || path.endsWith(".css") || path.endsWith(".woff")
                        || path.endsWith(".woff2") || path.endsWith(".ttf") || path.endsWith(".ico");
                // r488：扩 r485 端口判据 —— xiezhen8 实证投放脚本/点击落地移到 :8005
                //   （148150g.82gd88v.com:8005/sc/8150?n=oziathod、<rand>8150gc.qiq15od.com:8005/cc/8150），
                //   与 r485 的 :902/d/1196 同构：非标端口 + ^/[a-z]{1,4}/\d+/?$ 两段路径。
                //   902<1024 旧判据不再单独保留，统一为「非标端口(豁免 8000/8080/8443/8888) + 两段路径」。
                if (!mediaLike && port > 0 && port != 80 && port != 443 && port != 8000 && port != 8080 && port != 8443 && port != 8888
                        && path.matches("^/[a-z]{1,4}/\\d+/?$")) {
                    return true;
                }
            } catch (Throwable ignored) {
            }
        }
        if (url != null) {
            String u = url.toLowerCase();
            if (u.endsWith(".js") || u.endsWith(".css") || u.contains(".js?") || u.contains(".css?")) {
                return u.contains("/ad/") || u.contains("/ads/") || u.contains("/advert")
                        || u.contains("guanggao") || u.contains("/ad.js") || u.contains("/ads.js");
            }
        }
        return false;
    }

    /** 3.73：广告加载器总判据（muse-build v53/广告加载器/奇优，原样条件，顺序照原版）——
     *  ① isAdLoaderUrl：混淆 js 拉的远程广告脚本，域名按日期轮换，按子串/端口+路径拦，通用；
     *  ② 奇优站内 gdw.js/xs.js/xs2.js；③ path 恰好是 /static/jquery.js；
     *  ④ path 含 ad_poll；⑤ path 含 /back_flow。 */
    private boolean isAdLoaderRequest(String url, String path) {
        if (isAdLoaderUrl(url)) return true;
        if (isQiyouSite() && isQiyouAdScript(url)) return true;
        if ("/static/jquery.js".equals(path)) return true;
        if (path != null && path.toLowerCase(java.util.Locale.ROOT).contains("ad_poll")) return true;
        if (path != null && path.toLowerCase(java.util.Locale.ROOT).contains("/back_flow")) return true;
        return false;
    }

    /** 3.73：奇优站判定（muse-build isQiyouSite）—— AVBox 按现有 FILTER_JS 里 __museHost
     *  含 qiyou 的口径，认当前页 host（pageHost 已是小写）。 */
    private boolean isQiyouSite() {
        return pageHost != null && pageHost.contains("qiyou");
    }

    /** 3.73：奇优广告脚本（muse-build isQiyouAdScript）—— gdw.js（广告加载器，写 #noiddiv +
     *  WebSocket 远程代码注底部横幅）、xs.js/xs2.js（小说推荐卡注入器）；只拦文件名子串，不带 query 串；
     *  不进全局黑名单，只在奇优站内拦，避免误伤别的站。 */
    private boolean isQiyouAdScript(String url) {
        if (TextUtils.isEmpty(url)) return false;
        String u = url.toLowerCase(java.util.Locale.ROOT);
        return u.contains("gdw.js") || u.contains("xs.js") || u.contains("xs2.js");
    }

    /** 3.73：广告加载器（muse-build isAdLoaderUrl，原样）—— 混淆 js 拉的远程广告脚本，域名按日期轮换。 */
    private boolean isAdLoaderUrl(String url) {
        if (TextUtils.isEmpty(url)) return false;
        String u = url.toLowerCase(java.util.Locale.ROOT);
        // v3 PLMM 底飘：d.douytmvbf.site 是底条广告脚本加载器；域名固定、后缀随机，按子串拦。
        if (u.contains("douytmvbf")) return true;
        // v94 按活动根除：路径模板 /cc|/d|/sc/<活动编号> 是活动的骨架，端口非 80/443 就拦。
        if (isAdLoaderCampaignPath(u)) return true;
        // v91 根上拦：端口 + 路径是广告网的基础设施；v94 起退成后备（只补裸 /数字 路径那一档）。
        if (isAdLoaderPortPath(u)) return true;
        // 麦田网（8265）/耐看网（8210）/麦田新活动（1244）域名与广告 id 子串。
        return u.contains("8265c") || u.contains("8265t") || u.contains("4gl3g") || u.contains("1x4g6f2") || u.contains("aizhan") || u.contains("0cy5aps") || u.contains("ax8rylb") || u.contains("7xkpxuw")
                || u.contains("8210gc") || u.contains("mehqk5u") || u.contains("smun7ro") || u.contains("67x8epk") || u.contains("kvkluj8") || u.contains("lw1su4n") || u.contains("brlvflxk")
                || u.contains("1244c") || u.contains("1244g") || u.contains("hcxftcl") || u.contains("nf65hwr") || u.contains("3dzmgtc") || u.contains("dkepyys") || u.contains("hv7hpkk") || u.contains("rjhtcqjm");
    }

    /** 3.73：广告加载器的端口 + 路径指纹（muse-build v91 isAdLoaderPortPath，原样）——
     *  端口 8004/8005/902/20094/20095/992，路径 /cc|/d|/sc/数字 或 /数字；域名随便换都拦。 */
    private boolean isAdLoaderPortPath(String u) {
        int schemeEnd = u.indexOf("://");
        if (schemeEnd < 0) return false;
        int hostStart = schemeEnd + 3;
        int slash = u.indexOf('/', hostStart);
        if (slash < 0) return false;
        int colon = u.lastIndexOf(':', slash);
        if (colon <= hostStart) return false;
        int port;
        try {
            port = Integer.parseInt(u.substring(colon + 1, slash));
        } catch (NumberFormatException e) {
            return false;
        }
        if (port != 8004 && port != 8005 && port != 902 && port != 20094 && port != 20095 && port != 992) return false;
        String path = u.substring(slash);
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        int h = path.indexOf('#');
        if (h >= 0) path = path.substring(0, h);
        String[] parts = path.split("/");
        // /cc/8210、/d/8210、/sc/8210 -> ["", "cc", "8210"]；/8210（WS） -> ["", "8210"]
        if (parts.length == 3 && ("cc".equals(parts[1]) || "d".equals(parts[1]) || "sc".equals(parts[1])) && isDigits(parts[2])) return true;
        if (parts.length == 2 && isDigits(parts[1])) return true;
        return false;
    }

    /** 3.73：按活动根除的通用规则（muse-build v94 isAdLoaderCampaignPath，原样）——
     *  路径 /cc|/d|/sc/<数字> + 显式非常用端口（非 80/443）；裸 /<数字> 路径不进这条，免得误伤正经接口；视频后缀永不拦。 */
    private boolean isAdLoaderCampaignPath(String u) {
        int schemeEnd = u.indexOf("://");
        if (schemeEnd < 0) return false;
        int hostStart = schemeEnd + 3;
        int slash = u.indexOf('/', hostStart);
        if (slash < 0) return false;
        int colon = u.lastIndexOf(':', slash);
        if (colon <= hostStart) return false; // 没写端口 = 默认端口（80/443），不是加载器
        int port;
        try {
            port = Integer.parseInt(u.substring(colon + 1, slash));
        } catch (NumberFormatException e) {
            return false;
        }
        if (port == 80 || port == 443) return false;
        String path = u.substring(slash);
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        int h = path.indexOf('#');
        if (h >= 0) path = path.substring(0, h);
        if (isMediaRequest(path)) return false;
        String[] parts = path.split("/");
        // /cc/1244、/d/1244、/sc/1244 -> ["", "cc", "1244"]
        if (parts.length == 3 && ("cc".equals(parts[1]) || "d".equals(parts[1]) || "sc".equals(parts[1])) && isDigits(parts[2])) return true;
        return false;
    }

    private boolean isDigits(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) return false;
        }
        return true;
    }

    /** r473：host 是否属于本站（当前页或入口站；同父域算本站——静态资源常挂 cdn.xxx.sbs 等子域）。 */
    private boolean isSelfHost(String host) {
        String h = host == null ? "" : host.toLowerCase().trim();
        if (h.isEmpty()) return false;
        return sameSite(h, pageHost) || sameSite(h, homeHost);
    }

    /** r473：同父域判定（末两段相同），与 FILTER_JS 的 sameHost 语义一致。 */
    private boolean sameSite(String a, String b) {
        if (TextUtils.isEmpty(a) || TextUtils.isEmpty(b)) return false;
        String ah = a.toLowerCase(java.util.Locale.ROOT), bh = b.toLowerCase(java.util.Locale.ROOT);
        if (ah.equals(bh) || ah.endsWith("." + bh) || bh.endsWith("." + ah)) return true;
        return sameRegistrableDomain(ah, bh);
    }

    /** 3.73 修复：home 若指向内置 RemoteServer（127.x/localhost/::1），不作为可浏览首页。 */
    private static boolean isLocalServerUrl(String url) {
        if (url == null) return true;
        String s = url.trim().toLowerCase(java.util.Locale.ROOT);
        int di = s.indexOf("://");
        if (di >= 0) s = s.substring(di + 3);
        if (s.startsWith("//")) s = s.substring(2);
        int slash = s.indexOf('/'); if (slash >= 0) s = s.substring(0, slash);
        int q = s.indexOf('?'); if (q >= 0) s = s.substring(0, q);
        int hash = s.indexOf('#'); if (hash >= 0) s = s.substring(0, hash);
        if (s.startsWith("[")) return true; // IPv6 回环 [::1]
        int colon = s.lastIndexOf(':');
        if (colon > 0 && s.substring(colon + 1).matches("\\d+")) s = s.substring(0, colon);
        return s.isEmpty() || s.equals("localhost") || s.equals("::1")
                || s.equals("0.0.0.0") || s.startsWith("127.");
    }

    /** r475：候选镜像轮换 —— 加载失败时试下一个非本地地址，重置 UA 重试标志，并同步入口基准。 */
    private boolean tryNextCandidate() {
        disarmCandidateTimer();
        if (mCandidates == null || mCandIdx + 1 >= mCandidates.size()) return false;
        mCandIdx++;
        String next = mCandidates.get(mCandIdx);
        if (TextUtils.isEmpty(next) || isLocalServerUrl(next)) return tryNextCandidate();
        uaRetried = false;          // 新镜像允许再做一次 UA 降级重试
        home = next;
        anchorUrl = next;
        homeHost = hostOf(next);
        jumpHost = homeHost;
        final String u = next;
        DebugLogStore.add("browser", "候选轮换 → " + u);
        if (binding != null && binding.browserWeb != null) {
            binding.browserWeb.post(() -> binding.browserWeb.loadUrl(u));
        }
        return true;
    }

    /** 候选短超时：6 秒内主文档没收到任何东西（progress 仍为 0）就跳下一个候选，死地址不等太久。 */
    private void armCandidateTimer() {
        disarmCandidateTimer();
        candidateFinished = false;
        final int gen = ++candidateGen;
        candidateTimer = () -> {
            if (gen != candidateGen || candidateFinished) return;
            if (binding == null || binding.browserWeb == null) return;
            if (binding.browserWeb.getProgress() > 0) return; // 已有响应，正在加载，不跳
            if (tryNextCandidate()) {
                DebugLogStore.add("browser", "候选 6 秒无响应，已跳下一地址");
            }
        };
        if (binding != null && binding.browserWeb != null) {
            binding.browserWeb.postDelayed(candidateTimer, CANDIDATE_TIMEOUT_MS);
        }
    }

    private void disarmCandidateTimer() {
        if (candidateTimer != null && binding != null && binding.browserWeb != null) {
            binding.browserWeb.removeCallbacks(candidateTimer);
        }
        candidateTimer = null;
    }

    /** 该 URL 是否是候选首页之一（忽略末尾斜杠）。 */
    private boolean isCandidateUrl(String url) {
        if (TextUtils.isEmpty(url) || mCandidates == null) return false;
        String u = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        for (String c : mCandidates) {
            String cc = c.endsWith("/") ? c.substring(0, c.length() - 1) : c;
            if (u.equals(cc)) return true;
        }
        return false;
    }

    /** r473：从 URL 提取小写 host。 */
    private String hostOf(String url) {
        if (TextUtils.isEmpty(url)) return "";
        try {
            String h = Uri.parse(url).getHost();
            return h == null ? "" : h.toLowerCase();
        } catch (Throwable e) {
            return "";
        }
    }

    private WebResourceResponse empty() {
        return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (binding != null) binding.browserWeb.onResume();
    }

    @Override
    protected void onPause() {
        if (binding != null) binding.browserWeb.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        try {
            if (mWebHomeBridge != null) mWebHomeBridge.destroy();
            CookieManager.getInstance().flush();
            if (binding != null) {
                if (blankRecheck != null) binding.browserWeb.removeCallbacks(blankRecheck);
                binding.browserWeb.loadUrl("about:blank");
                binding.browserWeb.destroy();
            }
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }

    // ============ 3.34 AVBox 播放接线（browser_play / browser_novel 虚拟源） ============

    /** 单条直链进壳播放器 —— 经 browser_play 虚拟源（1 集 VOD），带起播头。 */
    private void playSingleUrl(String url) {
        if (TextUtils.isEmpty(url)) return;
        List<String> one = new ArrayList<>();
        one.add(url);
        playBrowserUrls(pageTitle, one, 0);
    }

    /**
     * 多集/图集/音频/正文进壳播放器 —— bplay 载荷 + jumpToDetail(browser_play, purePlayer)。
     * index>0 是集数面板点某集：详情页无集号直达入口，整单会从第 1 集起播，所以只包被点的那一集；
     * index=0（播放全部/单集）包整单，壳播放器内可上下集。
     */
    private void playBrowserUrls(String title, List<String> urls, int index) {
        if (urls == null || urls.isEmpty()) return;
        List<String> play = new ArrayList<>();
        if (index > 0 && index < urls.size()) {
            play.add(urls.get(index));
        } else {
            play.addAll(urls);
        }
        String payload = BrowserPlaySpider.browserPlayId(title, play, bplayReferer(), bplayUa());
        if (TextUtils.isEmpty(payload)) {
            Notify.show("未检测到可提取的视频地址");
            return;
        }
        JumpKt.jumpToDetail(this, payload, BrowserPlaySpider.KEY, title, null, false, true, true);
    }

    /**
     * 小说整本进 AVBox 阅读器 —— 章节列表（名$章节页 URL）建成 VodInfo + ReaderSession，
     * 章节正文由 BrowserNovelSpider 按需回源（WebSpider.novelPagePayload）。
     */
    private void launchNovelReaderAvbox(List<String> items, String title) {
        if (items == null || items.isEmpty()) return;
        try {
            VodInfo vod = new VodInfo();
            vod.sourceKey = BrowserNovelSpider.KEY;
            vod.id = title;
            vod.name = title;
            vod.playFlag = "网页";
            vod.playIndex = 0;
            java.util.LinkedHashMap<String, java.util.List<VodInfo.VodSeries>> map = new java.util.LinkedHashMap<>();
            java.util.List<VodInfo.VodSeries> eps = new ArrayList<>();
            String firstUrl = "";
            for (String it : items) {
                int d = it == null ? -1 : it.indexOf('$');
                String n = d > 0 ? it.substring(0, d) : it;
                String u = d >= 0 ? it.substring(d + 1) : "";
                if (TextUtils.isEmpty(u)) continue;
                if (firstUrl.isEmpty()) firstUrl = u;
                eps.add(new VodInfo.VodSeries(n, u));
            }
            if (eps.isEmpty()) {
                Notify.show("未提取到小说内容");
                return;
            }
            map.put("网页", eps);
            vod.seriesMap = map;
            ReaderContent.Novel content = new ReaderContent.Novel(null, firstUrl, new java.util.HashMap<>());
            ReaderSession session = ReaderSession.Companion.fromVod(vod, content, null);
            String token = ReaderSessions.INSTANCE.put(session);
            NovelReaderActivity.Companion.start(this, token);
        } catch (Throwable t) {
            DebugLogStore.add("browser", "[r486] 小说整本进阅读器失败 " + t);
            Notify.show("未提取到小说内容");
        }
    }

    // ============ 3.34 AVBox 蜘蛛阅读页样式注入（/b? 小说 / /v? 漫画，跟主题走） ============

    /** ARGB 长整型转 CSS #RRGGBB（去 alpha，蜘蛛页样式不吃 alpha）。 */
    private static String cssHex(long argb) {
        return String.format("#%06X", 0xFFFFFFL & argb);
    }

    /** 解析当前主题属性色（取不到回落 fallback）。 */
    /** 日志截断（AVBox 的 CatVod Util 没有 substring(String, int)；解析器 Utils.substring 是去尾不是截断，不能用）。 */
    private static String sub(String s, int n) {
        return s == null ? "" : (s.length() > n ? s.substring(0, n) : s);
    }

    private int themeColor(int attr, int fallback) {
        try {
            TypedValue tv = new TypedValue();
            if (getTheme() != null && getTheme().resolveAttribute(attr, tv, true)) return tv.data;
        } catch (Throwable ignored) {
        }
        return fallback;
    }

    /** 小说页注入：改蜘蛛页自带的 CSS 变量（小说取原生小说阅读器的当前设置：主题＋字号＋行高）。 */
    private String novelInjectJs() {
        try {
            NovelSettings st = ReaderStore.INSTANCE.novelSettings();
            NovelTheme theme = NovelThemes.INSTANCE.at(st.getThemeIndex());
            String bg = cssHex(theme.getBackground());
            String fg = cssHex(theme.getText());
            String fs = st.getFontSizeSp() + "px";
            String lh = String.valueOf(st.getLineHeightMult());
            return "(function(){var r=document.documentElement.style;"
                    + "r.setProperty('--bg','" + bg + "');"
                    + "r.setProperty('--fc','" + fg + "');"
                    + "r.setProperty('--fs','" + fs + "');"
                    + "r.setProperty('--lh','" + lh + "');"
                    + "var s=document.getElementById('avbox-theme');"
                    + "if(!s){s=document.createElement('style');s.id='avbox-theme';document.head.appendChild(s);}"
                    + "s.textContent='#panel{background:" + bg + " !important;color:" + fg + " !important;}';"
                    + "})();";
        } catch (Throwable t) {
            return "";
        }
    }

    /** 漫画页注入：蜘蛛 _CSS 全是写死值（白底黑字、橙色按钮），用 !important 规则盖过去（跟主题走）。 */
    private String comicInjectJs() {
        try {
            String bg = cssHex(themeColor(android.R.attr.colorBackground, 0xFFFFFFFF) & 0xFFFFFFFFL);
            String fg = cssHex(themeColor(com.google.android.material.R.attr.colorOnBackground, 0xFF000000) & 0xFFFFFFFFL);
            String accent = cssHex(themeColor(com.github.tvbox.osc.R.attr.colorPrimary, 0xFF6200EE) & 0xFFFFFFFFL);
            String css = "body{background:" + bg + " !important;color:" + fg + " !important;}"
                    + ".top{background:" + bg + " !important;border-bottom-color:transparent !important;}"
                    + ".h1{color:" + fg + " !important;}"
                    + "select{background:" + bg + " !important;color:" + fg + " !important;}"
                    + ".nav{color:" + accent + " !important;background:transparent !important;border-color:" + accent + " !important;}"
                    + ".foot a{color:" + accent + " !important;background:transparent !important;border-color:" + accent + " !important;}"
                    + ".pic img{background:" + bg + " !important;}"
                    + ".ph{background:" + bg + " !important;}";
            return "(function(){var s=document.getElementById('avbox-theme');"
                    + "if(!s){s=document.createElement('style');s.id='avbox-theme';document.head.appendChild(s);}"
                    + "s.textContent='" + css + "';})();";
        } catch (Throwable t) {
            return "";
        }
    }
}
