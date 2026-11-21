// Zalo PC 翻译注入 v1.0.4 — 听（收消息自动翻）+ 说（译按钮）
// 注入方式：index.html 与 compact-app.html 里加 <script src="zalocn-translate.js">
// （v1.0.1：主窗口加载 index.html，只注 compact-app 的话主窗口不加载）
// v1.0.2：按钮持久化（切换会话重装）、观察器重挂、翻译失败重试 3 次后显示提示
// v1.0.3：fetch 加 15 秒超时（API 挂起会卡死整个翻译队列）、boot 各步独立容错
// v1.0.4：根因修复——主窗口正常消息走 Frame 布局，容器是
//   [data-component="message-content-view"][data-qId]（id=message-frame_*），
//   根本没有 .chat-item 类，旧选择器 .chat-item.to-me 一条都找不到，
//   所以无自动翻译、无失败提示。改用 [data-qId] 选全部消息容器，
//   按 me / not-me 类判断收发（Frame 版自己消息带 me 类，对方消息没有）。
//   输入框改用 .zcinput-root 里的 contenteditable（Zalo 自用选择器）；
//   按钮点击各失败分支给明确提示（找不到输入框 / 先输入文字 / 翻译失败）。
(function () {
  'use strict';
  if (window.__zalocn_translate_loaded) return;
  window.__zalocn_translate_loaded = true;

  // ---------- 配置（默认；可用 localStorage zalocn-config 覆盖） ----------
  var DEFAULTS = {
    apiUrl: 'https://index-translate.bilibili.com/v1/chat/completions',
    apiKey: '',
    model: 'Index-Translate-35B-A3B',
    incomingTarget: 'zh-hans',   // 听：收到的消息翻成中文
    outgoingTarget: 'vi',        // 说：译按钮把中文翻成越南语
    autoTranslate: true,
    customPrompt: ''             // 追加提示词（地名对照等）
  };
  function loadConfig() {
    var cfg = {};
    try {
      var raw = localStorage.getItem('zalocn-config');
      if (raw) cfg = JSON.parse(raw) || {};
    } catch (e) {}
    if (window.ZALOCN_CONFIG) {
      for (var k in window.ZALOCN_CONFIG) cfg[k] = window.ZALOCN_CONFIG[k];
    }
    var out = {};
    for (var k in DEFAULTS) out[k] = (cfg[k] !== undefined && cfg[k] !== null && cfg[k] !== '') ? cfg[k] : DEFAULTS[k];
    return out;
  }
  var CFG = loadConfig();

  // ---------- 工具 ----------
  function log() { try { console.log.apply(console, ['[ZALOCN]'].concat([].slice.call(arguments))); } catch (e) {} }
  // 取元素文本：innerText 为空时（隐藏元素/某些环境）回落到 textContent。
  function getText(el) { try { return (el.innerText || el.textContent || ''); } catch (e) { return ''; } }
  function isChinese(s) { var m = s.match(/[\u4e00-\u9fff]/g); return m && m.length >= s.replace(/\s/g, '').length * 0.5; }
  function hasCJK(s) { return /[\u4e00-\u9fff]/.test(s); }
  function langName(code) {
    return { 'zh-hans': '中文', 'zh-hant': '繁體中文', 'vi': '越南语', 'en': '英语', 'ko': '韩语', 'th': '泰语', 'lo': '老挝语', 'km': '高棉语', 'my': '缅甸语', 'ms': '马来语', 'id': '印尼语', 'ja': '日语' }[code] || code;
  }

  // ---------- 翻译 ----------
  var inflight = 0, queue = [];
  function translateText(text, targetLang, cb) {
    queue.push({ text: text, target: targetLang, cb: cb });
    pump();
  }
  function pump() {
    if (inflight >= 3 || !queue.length) return;
    var job = queue.shift();
    inflight++;
    callBilibili(job.text, job.target, function (ok, out) {
      if (ok) { inflight--; job.cb(true, out); pump(); return; }
      callGoogle(job.text, job.target, function (ok2, out2) {
        inflight--;
        job.cb(ok2, out2);
        pump();
      });
    });
  }
  function systemPrompt(target) {
    var p = 'You are a translator. Translate the user message into ' + langName(target) + '. Output ONLY the translation, no explanation, no quotes, no extra text. Keep placeholders, numbers, links and line breaks unchanged.';
    if (CFG.customPrompt) p += '\n另外，请遵守以下翻译要求：' + CFG.customPrompt;
    return p;
  }
  // fetch 超时：API 挂起（一直不回）会卡死翻译队列，15 秒没回就当失败，
  // 走兜底或重试，绝不无限等。
  function fetchWithTimeout(url, opts, ms, cb) {
    var done = false;
    var timer = null;
    var ctrl = null;
    try {
      if (window.AbortController) {
        ctrl = new AbortController();
        if (opts) opts.signal = ctrl.signal;
      }
    } catch (e) {}
    function finish(ok, val) {
      if (done) return;
      done = true;
      if (timer) clearTimeout(timer);
      cb(ok, val);
    }
    if (ctrl) {
      timer = setTimeout(function () { try { ctrl.abort(); } catch (e) {} finish(false, ''); }, ms);
    } else {
      timer = setTimeout(function () { finish(false, ''); }, ms);
    }
    fetch(url, opts)
      .then(function (r) { if (!r.ok) throw new Error('HTTP ' + r.status); return r.json(); })
      .then(function (j) { finish(true, j); })
      .catch(function () { finish(false, ''); });
  }
  function callBilibili(text, target, cb) {
    var headers = { 'Content-Type': 'application/json' };
    if (CFG.apiKey) headers['Authorization'] = 'Bearer ' + CFG.apiKey;
    fetchWithTimeout(CFG.apiUrl, {
      method: 'POST', headers: headers,
      body: JSON.stringify({
        model: CFG.model,
        messages: [
          { role: 'system', content: systemPrompt(target) },
          { role: 'user', content: text }
        ],
        stream: false
      })
    }, 15000, function (ok, j) {
      if (!ok || !j) { cb(false, ''); return; }
      var out = j && j.choices && j.choices[0] && j.choices[0].message && j.choices[0].message.content;
      if (out) cb(true, String(out).trim()); else cb(false, '');
    });
  }
  function callGoogle(text, target, cb) {
    var tl = target === 'zh-hans' ? 'zh-CN' : target;
    fetchWithTimeout('https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=' + encodeURIComponent(tl) + '&dt=t&q=' + encodeURIComponent(text),
      null, 15000, function (ok, j) {
      if (!ok || !j) { cb(false, ''); return; }
      var out = '';
      try { out = j[0].map(function (x) { return x[0]; }).join(''); } catch (e) {}
      if (out) cb(true, out); else cb(false, '');
    });
  }

  // ---------- 听：收消息自动翻 ----------
  var translatedIds = {};   // data-qId -> true
  var textCache = {};      // 原文 -> 译文
  var translatingIds = {}; // data-qId -> true（正在翻译中，防止重复请求）
  var failCount = {};      // data-qId -> 失败次数（满 3 次后显示“翻译失败”并停止重试）

  function listContainer() {
    return document.querySelector('.message-view__list')
      || document.querySelector('.message-view__scroll__inner')
      || document.querySelector('#messageView')
      || document.body;
  }
  // 提取一条消息的纯文本（只处理纯文字消息）
  function extractText(item) {
    // 跳过带图片/文件/视频/语音/贴纸等非文字内容的消息
    if (item.querySelector('img.chat-message-picture__photo, video, audio, .chat-message-picture, .file-item, .sticker, [class*="sticker"]')) {
      // 有图也可能带文字说明；先只跳过纯媒体消息：若文字极短则跳过
      var t0 = getText(item).trim();
      if (t0.length < 2) return '';
    }
    // 优先找消息正文容器
    var cand = item.querySelector('.msg-content')
      || item.querySelector('.card-content')
      || item.querySelector('.message-text')
      || item.querySelector('[class*="message-text"]')
      || item.querySelector('[data-component="message-text-content"]');
    var text = '';
    if (cand) text = getText(cand).trim();
    if (!text) {
      // 兜底：取整条 innerText，去掉时间/已读/反应计数等噪音行
      var lines = getText(item).split('\n').map(function (s) { return s.trim(); }).filter(Boolean);
      lines = lines.filter(function (s) {
        if (/^\d{1,2}:\d{2}$/.test(s)) return false;                 // 纯时间 23:18
        if (/^(上午|下午|昨天|今天)/.test(s)) return false;           // 日期词
        if (/^(已读|Seen|Đã xem|Đã nhận)$/i.test(s)) return false;   // 已读回执
        if (/^\d+$/.test(s)) return false;                           // 反应计数
        if (/^[👍❤️😂😮😢🙏👏🥰😍🤣😆😊]+$/.test(s)) return false;    // 纯表情
        return true;
      });
      text = lines.join('\n').trim();
    }
    return text;
  }
  // 判断一条消息是不是自己发的。Frame 布局：自己消息的容器带 me 类，
  // 对方消息没有；chat-item 布局：自己是 not-me，对方是 to-me。
  // Custom 布局的方向类可能在父级，往上查两层。
  function isOutgoing(item) {
    var el = item;
    for (var i = 0; i < 3 && el; i++, el = el.parentElement) {
      if (!el.classList) continue;
      if (el.classList.contains('me') || el.classList.contains('not-me')) return true;
      if (el.classList.contains('to-me')) return false;
    }
    return false;
  }
  function needsTranslate(text, target) {
    if (!text || text.length < 1) return false;
    if (text.length > 2000) return false;
    if (target === 'zh-hans' || target === 'zh-hant') return !isChinese(text);
    if (target === 'vi') return !/[àáâãèéêìíòóôõùúýỳỹỵựửữừứơớờởỡợảấầẩẫậắằẳẵặẹẻẽếềểễệỉịọỏốồổỗộớờởỡợụủứừửữựỳỵỷỹ]/i.test(text) && !hasCJK(text) ? true : !hasCJK(text);
    return true;
  }
  function insertTranslation(item, translated) {
    if (item.querySelector(':scope > .zalocn-trans-box')) return;
    var box = document.createElement('div');
    box.className = 'zalocn-trans-box';
    box.setAttribute('data-zalocn', '1');
    box.style.cssText = 'margin:6px 0 2px 0;padding:8px 10px;border:1px solid #e5e7eb;border-left:3px solid #0068FF;border-radius:8px;background:#f8fafc;font-size:13px;line-height:1.6;color:#1f2937;white-space:pre-wrap;word-break:break-word;max-width:100%;';
    var label = document.createElement('div');
    label.textContent = '译文 · ' + langName(CFG.incomingTarget);
    label.style.cssText = 'font-size:11px;color:#0068FF;margin-bottom:4px;font-weight:600;';
    var body = document.createElement('div');
    body.textContent = translated;
    box.appendChild(label);
    box.appendChild(body);
    // 插到消息气泡后面（消息内容容器的父级末尾）
    var anchor = item.querySelector('.card') || item.querySelector('.msg-content') || item;
    if (anchor && anchor !== item && anchor.parentNode) {
      anchor.parentNode.insertBefore(box, anchor.nextSibling);
    } else {
      item.appendChild(box);
    }
  }
  function insertFailNote(item) {
    if (item.querySelector(':scope > .zalocn-trans-box')) return;
    var box = document.createElement('div');
    box.className = 'zalocn-trans-box';
    box.setAttribute('data-zalocn', 'fail');
    box.style.cssText = 'margin:6px 0 2px 0;padding:4px 10px;font-size:12px;color:#9ca3af;';
    box.textContent = '翻译失败（请检查网络/API），点输入框「译」可手动翻译';
    var anchor = item.querySelector('.card') || item.querySelector('.msg-content') || item;
    if (anchor && anchor !== item && anchor.parentNode) {
      anchor.parentNode.insertBefore(box, anchor.nextSibling);
    } else {
      item.appendChild(box);
    }
  }
  function markDone(item, qid) {
    item.setAttribute('data-zalocn-done', '1');
    if (qid) { translatedIds[qid] = true; delete translatingIds[qid]; }
  }
  function processItem(item) {
    if (!CFG.autoTranslate) return;
    if (!item.getAttribute) return;
    if (isOutgoing(item)) return;                       // 自己的消息不翻
    if (item.getAttribute('data-zalocn-done')) return;
    var qid = item.getAttribute('data-qId') || '';
    if (qid && translatedIds[qid]) { markDone(item, qid); return; }
    if (qid && translatingIds[qid]) return;                  // 正在翻译中，等回调
    var text = extractText(item);
    if (!text || !needsTranslate(text, CFG.incomingTarget)) {
      markDone(item, qid);
      return;
    }
    if (textCache[text]) { insertTranslation(item, textCache[text]); markDone(item, qid); return; }
    if (qid) translatingIds[qid] = true;
    translateText(text, CFG.incomingTarget, function (ok, out) {
      if (qid) delete translatingIds[qid];
      if (!document.contains(item)) return;
      if (ok && out && out !== text) {
        textCache[text] = out;
        insertTranslation(item, out);
        markDone(item, qid);
        return;
      }
      // 失败：重试（最多 3 次），3 次都失败才显示提示并标记完成
      var n = (qid ? (failCount[qid] || 0) : 0) + 1;
      if (qid) failCount[qid] = n;
      log('translate failed for qid', qid, 'attempt', n);
      if (n >= 3) {
        insertFailNote(item);
        markDone(item, qid);
      }
      // 未满 3 次：不标记完成，下次扫描重试
    });
  }
  function scanAll() {
    // 主窗口正常消息走 Frame 布局，容器是 [data-qId]（id=message-frame_*），
    // 没有 .chat-item 类；chat-item 只用于非 Frame/非 Custom 布局。
    // 所以用 [data-qId] 选全部消息容器，收发由 isOutgoing 判断。
    var items = document.querySelectorAll('[data-qId]');
    if (items.length && !scanAll._logged) { scanAll._logged = true; log('scanAll found', items.length, 'messages (data-qId)'); }
    for (var i = 0; i < items.length; i++) processItem(items[i]);
  }
  var observer = null, observedRoot = null;
  function startObserver() {
    var root = listContainer();
    if (observer && observedRoot === root && document.contains(root)) return; // 已在监听同一容器
    if (observer) observer.disconnect();
    observedRoot = root;
    observer = new MutationObserver(function (muts) {
      for (var i = 0; i < muts.length; i++) {
        var m = muts[i];
        for (var j = 0; j < m.addedNodes.length; j++) {
          var n = m.addedNodes[j];
          if (n.nodeType !== 1) continue;
          if (n.getAttribute && n.getAttribute('data-qId')) processItem(n);
          else if (n.querySelectorAll) {
            var subs = n.querySelectorAll('[data-qId]');
            for (var k = 0; k < subs.length; k++) processItem(subs[k]);
          }
        }
      }
    });
    observer.observe(root, { childList: true, subtree: true });
    log('observer started on', root.className || root.tagName);
  }

  // ---------- 说：译按钮 ----------
  function getEditor() {
    // Zalo 输入框是 ProseMirror/TipTap，根容器是 .zcinput-root[data-component="rtf-container"]，
    // 可编辑区是里面的 [contenteditable]。Zalo 自用选择器就是这个。
    var root = document.querySelector('.zcinput-root[data-component="rtf-container"]')
      || document.querySelector('.zcinput-root');
    if (root) {
      var ed = root.querySelector('[contenteditable]:not([contenteditable="false"])');
      if (ed) return ed;
    }
    return document.querySelector('.ProseMirror[contenteditable="true"]')
      || document.querySelector('.chat-input-container [contenteditable="true"]')
      || document.querySelector('[contenteditable="true"]');
  }
  function getEditorText(ed) {
    if (!ed) return '';
    return getText(ed).replace(/\u200b/g, '').trim();
  }
  function setEditorText(ed, text) {
    if (!ed) return false;
    try { ed.focus(); } catch (e) {}
    var ok = false;
    try {
      var sel = window.getSelection();
      sel.selectAllChildren(ed);
      ok = document.execCommand('insertText', false, text);
    } catch (e) {}
    if (!ok) {
      // 兜底：先清后填，再派发 input 事件让 ProseMirror 同步状态
      try {
        document.execCommand('selectAll', false, null);
        ok = document.execCommand('insertText', false, text);
      } catch (e2) {}
    }
    if (!ok) {
      try {
        ed.textContent = text;
        ed.dispatchEvent(new Event('input', { bubbles: true }));
        ok = true;
      } catch (e3) {}
    }
    try { ed.focus(); } catch (e) {}
    return ok;
  }
  // 持久化安装：切换会话时输入区会重建，按钮会被一起销毁；
  // 每轮检查按钮是否还在 DOM 里，不在就重装（不靠一次性 flag）。
  function installTranslateButton() {
    var existing = document.querySelector('.zalocn-translate-btn');
    if (existing && document.contains(existing)) return true;
    var group = document.querySelector('.normal-buttons-group')
      || document.querySelector('.chat-input-btns')
      || document.querySelector('.rtf-buttons-group');
    var ed = getEditor();
    if (!group || !ed) return false;
    var btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'zalocn-translate-btn';
    btn.textContent = '译';
    btn.title = '翻译成' + langName(CFG.outgoingTarget) + '（填回输入框，不自动发）';
    btn.style.cssText = 'margin:0 4px;padding:2px 10px;border:1px solid #0068FF;border-radius:6px;background:#fff;color:#0068FF;font-size:13px;font-weight:600;cursor:pointer;flex:none;';
    btn.addEventListener('click', function (ev) {
      ev.preventDefault(); ev.stopPropagation();
      var editor = getEditor();
      // 各失败分支给明确提示，不静默：找不到输入框 / 没输文字 / 翻译失败
      if (!editor) { btn.textContent = '找不到输入框'; log('translate button: editor not found'); setTimeout(function () { btn.textContent = '译'; }, 2000); return; }
      var text = getEditorText(editor);
      if (!text) { btn.textContent = '先输入文字'; setTimeout(function () { btn.textContent = '译'; }, 1500); return; }
      if (CFG.outgoingTarget === 'zh-hans' && isChinese(text)) { btn.textContent = '已是中文'; setTimeout(function () { btn.textContent = '译'; }, 1500); return; }
      btn.textContent = '翻译中…'; btn.disabled = true;
      translateText(text, CFG.outgoingTarget, function (ok, out) {
        btn.disabled = false; btn.textContent = '译';
        if (!ok || !out) { btn.textContent = '翻译失败'; log('translate button: api failed'); setTimeout(function () { btn.textContent = '译'; }, 2000); return; }
        if (!setEditorText(getEditor(), out)) { btn.textContent = '填回失败'; log('translate button: setEditorText failed'); setTimeout(function () { btn.textContent = '译'; }, 2000); }
      });
    });
    group.insertBefore(btn, group.firstChild);
    log('translate button installed');
    return true;
  }

  // ---------- 启动 ----------
  function boot() {
    // 每步独立容错：某一步抛错也不影响常驻巡检启动。
    try { scanAll(); } catch (e) { log('boot scanAll error', e); }
    try { startObserver(); } catch (e) { log('boot observer error', e); }
    try { installTranslateButton(); } catch (e) { log('boot button error', e); }
    // 常驻巡检：切换会话会重建输入区与消息列表，按钮与观察器都要重挂；
    // 翻译失败的条目也靠本轮重试（最多 3 次）。
    setInterval(function () {
      try { installTranslateButton(); } catch (e) { log('tick button error', e); }
      try { startObserver(); } catch (e) { log('tick observer error', e); }
      try { scanAll(); } catch (e) { log('tick scan error', e); }
    }, 2000);
    log('zalocn translate v1.0.4 ready, incoming->' + CFG.incomingTarget + ', outgoing->' + CFG.outgoingTarget);
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot);
  else boot();
})();
