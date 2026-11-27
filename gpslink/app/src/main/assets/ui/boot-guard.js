/* 启动自检：完整版靠 ES module 加载 app.js，而 Chromium 61 以下内核不认 module，
   script 会被整个忽略 —— 页面只剩静态 HTML，点任何按钮都只有一圈焦点框、没有响应。
   手机内核新看不出来，老车机必现。

   app.js 能执行时第一行会置 window.__gnssBooted，这里超时还没置位就说明完整版跑不起来，
   直接换到纯 ES5 的精简界面。注意必须是独立文件：页面 CSP 是 script-src 'self'，
   内联脚本会被拦掉，兜底逻辑自己也失效。 */
(function () {
  setTimeout(function () {
    if (window.__gnssBooted) return;
    var target = "../ui-lite/index.html";
    try {
      location.replace(target);
    } catch (e) {
      location.href = target;
    }
  }, 2000);
})();
