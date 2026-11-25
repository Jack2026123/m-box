package com.github.tvbox.osc.reader

object WebReaderHtml {

    fun escapeText(text: String): String {
        val out = StringBuilder(text.length)
        for (ch in text) {
            when (ch) {
                '&' -> out.append("&amp;")
                '<' -> out.append("&lt;")
                '>' -> out.append("&gt;")
                '"' -> out.append("&quot;")
                '\'' -> out.append("&#39;")
                else -> out.append(ch)
            }
        }
        return out.toString()
    }

    /**
     * 激进方案：彻底放弃 CSS multi-column。
     * 左右翻页改用 JS 精确测量 + 按页切片，兼容性远高于 column 布局。
     *
     * 接口保持兼容：
     * - JS 调用 AVReader.pageCount(n)
     * - Kotlin 端继续用 showPage(page) 滚动 / 切换
     */
    fun buildNovelHtml(
        title: String,
        bodyText: String,
        textColorHex: String,
        bgColorHex: String,
        fontSizePx: Int,
        lineHeight: Float,
        paddingPx: Int = 24,
        accentColorHex: String? = null,
        paged: Boolean = false,
    ): String {
        val paragraphs = bodyText.split(Regex("\\n\\s*\\n"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val body = if (paragraphs.isEmpty()) {
            "<p>" + escapeText(bodyText.trim()) + "</p>"
        } else {
            paragraphs.joinToString("\n") {
                "<p>" + escapeText(it).replace("\n", "<br>") + "</p>"
            }
        }
        val accentRule = if (accentColorHex != null) {
            "h1{font-size:1.18em;font-weight:600;text-align:center;letter-spacing:0.08em;line-height:1.4;margin:0 0 18px 0;" +
                "padding-bottom:12px;border-bottom:1px solid " + accentColorHex + "40;" +
                "color:" + textColorHex + ";}"
        } else {
            "h1{font-size:1.18em;font-weight:600;text-align:center;letter-spacing:0.08em;line-height:1.4;margin:0 0 18px 0;" +
                "color:" + textColorHex + ";}"
        }
        val titleHtml = "<h1>" + escapeText(title) + "</h1>"

        val typo = "" +
            "font-family:\"Noto Serif CJK SC\",\"Songti SC\",\"SimSun\",\"Noto Serif\",serif;" +
            "font-size:" + fontSizePx + "px;" +
            "line-height:" + lineHeight + ";" +
            "letter-spacing:0.02em;" +
            "word-break:break-word;" +
            "-webkit-text-size-adjust:100%;" +
            "text-rendering:optimizeLegibility;" +
            "-webkit-font-smoothing:antialiased;"

        if (paged) {
            // ========== 激进左右翻页：JS 分页 ==========
            // 不再使用 column-width。先把全文放进隐藏测量区，
            // 按可视高度切成多页，再只显示当前页。彻底避开
            // Android WebView 多栏空白问题。
            return """
<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
<style>
*,*:before,*:after{box-sizing:border-box;}
html,body{margin:0;padding:0;width:100%;height:100%;overflow:hidden;
background:${bgColorHex} !important;color:${textColorHex} !important;}
#measure{
  position:absolute;left:-9999px;top:0;width:100%;visibility:hidden;
  padding:32px 28px;
  background:${bgColorHex};color:${textColorHex};
  $typo
}
#viewport{
  position:absolute;left:0;top:0;right:0;bottom:0;
  width:100%;height:100%;overflow:hidden;
  background:${bgColorHex} !important;
}
#page{
  width:100%;height:100%;overflow:hidden;
  padding:32px 28px;
  background:${bgColorHex} !important;color:${textColorHex} !important;
  $typo
  font-feature-settings:"kern" 1;
  text-align:justify;
  -webkit-hyphens:none;
}
#viewport{
  box-shadow:inset 0 0 0 1px ${textColorHex}0A;
}
#page p{
  text-align:justify;
  text-indent:2em;
  margin:0 0 1.15em 0;
  letter-spacing:0.025em;
}
#page h1, #measure h1{${accentRule.removePrefix("h1{").removeSuffix("}")}}
#page p, #measure p{
  margin:0 0 0.95em 0;text-indent:2em;
  color:${textColorHex} !important;
}
</style>
</head>
<body>
<div id="measure">$titleHtml$body</div>
<div id="viewport"><div id="page"></div></div>
<script>
(function(){
  var pages = [];
  var current = 0;
  var measure = document.getElementById('measure');
  var pageEl = document.getElementById('page');

  // 1.86 诊断：每一步都经 AVReader.jsLog 写进悬浮日志，机上出白时看
  // 日志就知道死在哪一步（JS 没启动／量区空／没切出页／渲染 0 字）。
  function jsLog(msg){
    try{
      if(window.AVReader && window.AVReader.jsLog){
        AVReader.jsLog(msg);
      }
    }catch(e){}
  }
  jsLog('paged-js start');
  if(!measure || !pageEl){
    jsLog('paged-js missing measure/page');
    return;
  }

  function reportCount(){
    try{
      if(window.AVReader && window.AVReader.pageCount){
        AVReader.pageCount(Math.max(1, pages.length));
      }
    }catch(e){}
  }

  function buildPages(){
    var keep = current;
    pages = [];
    var viewH = window.innerHeight || document.documentElement.clientHeight || 1;
    var pad = 64; // 2.48 补丁把分页内边距改成 32px 上下，合计 64（原 30px 上下=60，补丁漏改这里）
    var maxH = Math.max(100, viewH - pad);

    // 把测量区里的所有块级节点拿出来按高度装箱
    var nodes = Array.prototype.slice.call(measure.children);
    jsLog('paged-js build nodes=' + nodes.length + ' viewH=' + viewH + ' maxH=' + maxH);
    if(nodes.length === 0){
      pages = ['<p></p>'];
      current = Math.max(0, Math.min(keep, pages.length - 1));
      render();
      reportCount();
      return;
    }

    var pageHtml = '';
    var used = 0;

    // 在 #measure 里直接量：子节点已经按 #page 的精确几何（同宽、同内边距、
    // 同字体）排好，offsetHeight 就是它在页里的真实高度；再加上下边距
    // （offsetHeight 不含 margin）。宁可把页算不满，也不能把字裁掉。
    // 原补丁用探针量：探针宽 = 测量区 clientWidth（含内边距），比页内容宽
    // 宽出两侧内边距，量出来偏矮，每页末尾几行会被裁掉。
    function nodeHeight(node){
      var cs = window.getComputedStyle(node);
      var mb = parseFloat(cs.marginBottom) || 0;
      var mt = parseFloat(cs.marginTop) || 0;
      return (node.offsetHeight || 0) + mb + mt;
    }

    function esc(s){
      return s.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');
    }

    // 把一个超高段落按字切成多页：按 <br> 分段，每段按字二分出能塞进
    // 一页的最大字数；续页不首行缩进。中文按字切就是正常换行，不丢字。
    // 原补丁是整段单页、超出一屏的部分直接被裁掉丢字，大字号时必现。
    // 非 p 节点（如标题）理论上不可能超高，原样单页返回。
    function splitTall(node, maxH){
      if(node.tagName.toLowerCase() !== 'p') return [node.outerHTML];
      var runs = [];
      var kids = node.childNodes;
      for(var i=0; i<kids.length; i++){
        if(kids[i].nodeType === 3) runs.push(kids[i].nodeValue);
        else if(kids[i].nodeName === 'BR') runs.push('\n');
        else runs.push(kids[i].textContent || '');
      }
      var text = runs.join('');
      var tmp = document.createElement('p');
      measure.appendChild(tmp);
      function chunkHeight(html){
        tmp.innerHTML = html;
        var cs = window.getComputedStyle(tmp);
        return (tmp.offsetHeight || 0) + (parseFloat(cs.marginBottom) || 0);
      }
      var out = [];
      try{
        var rest = text;
        var first = true;
        while(rest.length > 0){
          var lo = 0, hi = rest.length;
          while(lo < hi){
            var mid = Math.ceil((lo + hi) / 2);
            var html = esc(rest.slice(0, mid)).replace(/\n/g, '<br>');
            if(chunkHeight(html) <= maxH) lo = mid; else hi = mid - 1;
          }
          var take = Math.max(1, lo);
          var part = rest.slice(0, take);
          out.push('<p' + (first ? '' : ' style="text-indent:0"') + '>' +
            esc(part).replace(/\n/g, '<br>') + '</p>');
          rest = rest.slice(take);
          first = false;
        }
      } finally {
        measure.removeChild(tmp);
      }
      return out;
    }

    for(var i=0; i<nodes.length; i++){
      var html = nodes[i].outerHTML;
      var h = nodeHeight(nodes[i]);
      if(h <= 0) h = 20;

      // 单段就超高：按字切成多页，不丢字
      if(h > maxH){
        if(pageHtml){
          pages.push(pageHtml);
          pageHtml = '';
          used = 0;
        }
        var chunks = splitTall(nodes[i], maxH);
        for(var k=0; k<chunks.length; k++) pages.push(chunks[k]);
        continue;
      }

      if(used + h > maxH && pageHtml){
        pages.push(pageHtml);
        pageHtml = html;
        used = h;
      } else {
        pageHtml += html;
        used += h;
      }
    }
    if(pageHtml){
      var probe = document.createElement('p');
      probe.setAttribute('style','text-align:center;text-indent:0;margin:0;');
      probe.textContent = '— 本章完 —';
      measure.appendChild(probe);
      var markerH = probe.offsetHeight || 20;
      measure.removeChild(probe);
      if(used + markerH <= maxH){
        pageHtml += '<p style="text-align:center;text-indent:0;margin:0;">— 本章完 —</p>';
      }
    }
    if(pageHtml) pages.push(pageHtml);
    if(pages.length === 0) pages = ['<p></p>'];

    // 重排不丢页：换字号是新文档（current 本来就是 0，Kotlin 随后按保存
    // 的页恢复）；转屏是同文档重排，留住当前页。
    current = Math.max(0, Math.min(keep, pages.length - 1));
    jsLog('paged-js pages=' + pages.length);
    render();
    reportCount();
  }

  function render(){
    var html = pages[current] || '';
    pageEl.innerHTML = html;
    jsLog('paged-js render page=' + current + ' chars=' + html.length);
  }

  // 给 Kotlin 端调用的兼容接口
  window.__goPage = function(idx){
    if(pages.length === 0) return;
    current = Math.max(0, Math.min(pages.length-1, idx|0));
    render();
  };

  // 注意：不要用 defineProperty 劫持 scrollLeft / clientWidth——原生属性
  // 不一定能被实例属性盖住，盖不住整个分页脚本就死了。
  // Kotlin 端 showPage 改成优先直接调 window.__goPage（见 WebReaderScreen），
  // 旧 scrollLeft 路径只留作没有 __goPage 的文档的兜底。

  function start(){
    setTimeout(buildPages, 30);
    setTimeout(buildPages, 120);
    setTimeout(buildPages, 350);
  }
  if(document.readyState === 'complete') start();
  else window.addEventListener('load', start);
  window.addEventListener('resize', function(){ setTimeout(buildPages, 80); });
})();
</script>
</body>
</html>
            """.trimIndent()
        }

        // ---------- 上下滚动模式（保持美化） ----------
        return """
<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=5.0,user-scalable=yes">
<style>
*,*:before,*:after{box-sizing:border-box;}
html,body{margin:0;padding:0;background:${bgColorHex} !important;}
body{
  padding:${paddingPx + 2}px ${paddingPx + 4}px ${paddingPx + 40}px;
  background:${bgColorHex} !important;
  color:${textColorHex} !important;
  $typo
  text-align:justify;
  font-feature-settings:"kern" 1;
}
$accentRule
h1{padding-top:0.35em;letter-spacing:0.11em;text-wrap:balance;}
p{margin:0 0 1.08em 0;text-indent:2em;color:${textColorHex} !important;text-align:justify;letter-spacing:0.025em;}
</style>
</head>
<body>
$titleHtml$body
</body>
</html>
        """.trimIndent()
    }

    fun normalizeComicImageUrl(rawUrl: String): String {
        var url = rawUrl.trim()
        if (url.startsWith("pics://", ignoreCase = true)) {
            url = url.substring("pics://".length)
        }
        val suffix = Regex("(?i)@(Referer|User-Agent|Cookie|X-Legado-Source-Key)=")
        val match = suffix.find(url)
        if (match != null) url = url.substring(0, match.range.first)
        if (url.startsWith("//")) url = "https:$url"
        return url
    }

    fun buildComicHtml(imageUrls: List<String>, bgColorHex: String = "#000000"): String {
        val imgs = imageUrls.joinToString("\n") { rawUrl ->
            val url = normalizeComicImageUrl(rawUrl)
            "<div class=\"img-wrap\"><img src=\"" + escapeText(url) +
                "\" loading=\"lazy\" referrerpolicy=\"no-referrer\" alt=\"\"></div>"
        }
        val readyScript = """
<script>
(function(){
  function allDone(){
    var imgs=document.images;
    for(var i=0;i<imgs.length;i++){if(!imgs[i].complete)return false;}
    return true;
  }
  function report(){
    if(allDone()&&window.ComicBridge&&window.ComicBridge.imagesReady){
      ComicBridge.imagesReady();
    }
  }
  if(allDone()){report();}
  else{
    var imgs=document.images;
    for(var i=0;i<imgs.length;i++){
      imgs[i].addEventListener('load',report);
      imgs[i].addEventListener('error',report);
    }
  }
})();
</script>
        """.trimIndent()
        return """
<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=3,user-scalable=yes">
<style>
*,*:before,*:after{box-sizing:border-box;}
html,body{margin:0;padding:0;background:$bgColorHex;-webkit-text-size-adjust:100%;}
body{padding:0 0 20px 0;background:$bgColorHex;}
.img-wrap{margin:0 0 3px 0;background:$bgColorHex;overflow:hidden;}
img{display:block;width:100%;height:auto;max-width:100%;vertical-align:top;background:transparent;object-fit:contain;image-rendering:auto;}
.img-wrap:last-child{margin-bottom:0;}
@media (min-width:600px){body{max-width:900px;margin:0 auto;}img{width:100%;}}
</style>
</head>
<body>
$imgs
$readyScript
</body>
</html>
        """.trimIndent()
    }
}
