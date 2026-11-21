// Zalo PC 翻译注入 v1.0.0 — 听（收消息自动翻）+ 说（译按钮）
// 注入方式：compact-app.html 里加 <script src="zalocn-translate.js">
// 不改 Zalo 逻辑、不自动发消息；翻译走 bilibili 公开 API（免密钥），失败回落 Google。
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
  function callBilibili(text, target, cb) {
    var headers = { 'Content-Type': 'application/json' };
    if (CFG.apiKey) headers['Authorization'] = 'Bearer ' + CFG.apiKey;
    fetch(CFG.apiUrl, {
      method: 'POST', headers: headers,
      body: JSON.stringify({
        model: CFG.model,
        messages: [
          { role: 'system', content: systemPrompt(target) },
          { role: 'user', content: text }
        ],
        stream: false
      })
    }).then(function (r) { if (!r.ok) throw new Error('HTTP ' + r.status); return r.json(); })
      .then(function (j) {
        var out = j && j.choices && j.choices[0] && j.choices[0].message && j.choices[0].message.content;
        if (out) cb(true, String(out).trim()); else cb(false, '');
      })
      .catch(function () { cb(false, ''); });
  }
  function callGoogle(text, target, cb) {
    var tl = target === 'zh-hans' ? 'zh-CN' : target;
    fetch('https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=' + encodeURIComponent(tl) + '&dt=t&q=' + encodeURIComponent(text))
      .then(function (r) { if (!r.ok) throw new Error('HTTP ' + r.status); return r.json(); })
      .then(function (j) {
        var out = '';
        try { out = j[0].map(function (x) { return x[0]; }).join(''); } catch (e) {}
        if (out) cb(true, out); else cb(false, '');
      })
      .catch(function () { cb(false, ''); });
  }

  // ---------- 听：收消息自动翻 ----------
  var translatedIds = {};   // data-qId -> true
  var textCache = {};      // 原文 -> 译文

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
      var t0 = (item.innerText || '').trim();
      if (t0.length < 2) return '';
    }
    // 优先找消息正文容器
    var cand = item.querySelector('.msg-content')
      || item.querySelector('.card-content')
      || item.querySelector('.message-text')
      || item.querySelector('[class*="message-text"]');
    var text = '';
    if (cand) text = (cand.innerText || '').trim();
    if (!text) {
      // 兜底：取整条 innerText，去掉发送者名/时间等首尾行
      var lines = (item.innerText || '').split('\n').map(function (s) { return s.trim(); }).filter(Boolean);
      // 去掉纯时间行（如 21:30）和单字行
      lines = lines.filter(function (s) { return !/^\d{1,2}:\d{2}$/.test(s) && !/^(上午|下午|昨天|今天)/.test(s); });
      text = lines.join('\n').trim();
    }
    return text;
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
  function processItem(item) {
    if (!CFG.autoTranslate) return;
    if (!item.classList || !item.classList.contains('chat-item')) return;
    if (item.classList.contains('me')) return;              // 自己的消息不翻
    if (!item.classList.contains('to-me')) return;           // 只翻收到的（to-me）
    if (item.getAttribute('data-zalocn-done')) return;
    var qid = item.getAttribute('data-qId') || '';
    if (qid && translatedIds[qid]) { item.setAttribute('data-zalocn-done', '1'); return; }
    var text = extractText(item);
    if (!text || !needsTranslate(text, CFG.incomingTarget)) {
      item.setAttribute('data-zalocn-done', '1');
      if (qid) translatedIds[qid] = true;
      return;
    }
    item.setAttribute('data-zalocn-done', '1');
    if (qid) translatedIds[qid] = true;
    if (textCache[text]) { insertTranslation(item, textCache[text]); return; }
    translateText(text, CFG.incomingTarget, function (ok, out) {
      if (!ok || !out || out === text) return;
      textCache[text] = out;
      if (!document.contains(item)) return;
      insertTranslation(item, out);
    });
  }
  function scanAll() {
    var items = document.querySelectorAll('.chat-item.to-me');
    for (var i = 0; i < items.length; i++) processItem(items[i]);
  }
  var observer = null;
  function startObserver() {
    var root = listContainer();
    if (observer) observer.disconnect();
    observer = new MutationObserver(function (muts) {
      for (var i = 0; i < muts.length; i++) {
        var m = muts[i];
        for (var j = 0; j < m.addedNodes.length; j++) {
          var n = m.addedNodes[j];
          if (n.nodeType !== 1) continue;
          if (n.classList && n.classList.contains('chat-item')) processItem(n);
          else if (n.querySelectorAll) {
            var subs = n.querySelectorAll('.chat-item.to-me');
            for (var k = 0; k < subs.length; k++) processItem(subs[k]);
          }
        }
      }
    });
    observer.observe(root, { childList: true, subtree: true });
    log('observer started on', root.className || root.tagName);
  }

  // ---------- 说：译按钮 ----------
  var btnInstalled = false;
  function getEditor() {
    return document.querySelector('.ProseMirror[contenteditable="true"]')
      || document.querySelector('.chat-input-container [contenteditable="true"]')
      || document.querySelector('[contenteditable="true"]');
  }
  function getEditorText(ed) {
    return (ed.innerText || ed.textContent || '').replace(/\u200b/g, '').trim();
  }
  function setEditorText(ed, text) {
    ed.focus();
    var sel = window.getSelection();
    sel.selectAllChildren(ed);
    var ok = false;
    try { ok = document.execCommand('insertText', false, text); } catch (e) {}
    if (!ok) {
      // 兜底：直接设文本并派发 input 事件
      try {
        sel.deleteFromDocument();
        document.execCommand('insertText', false, text);
      } catch (e2) {
        ed.textContent = text;
        ed.dispatchEvent(new Event('input', { bubbles: true }));
      }
    }
    ed.focus();
  }
  function installTranslateButton() {
    if (btnInstalled) return;
    var group = document.querySelector('.normal-buttons-group')
      || document.querySelector('.chat-input-btns')
      || document.querySelector('.rtf-buttons-group');
    var ed = getEditor();
    if (!group || !ed) return;
    if (group.querySelector('.zalocn-translate-btn')) { btnInstalled = true; return; }
    var btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'zalocn-translate-btn';
    btn.textContent = '译';
    btn.title = '翻译成' + langName(CFG.outgoingTarget) + '（填回输入框，不自动发）';
    btn.style.cssText = 'margin:0 4px;padding:2px 10px;border:1px solid #0068FF;border-radius:6px;background:#fff;color:#0068FF;font-size:13px;font-weight:600;cursor:pointer;flex:none;';
    btn.addEventListener('click', function (ev) {
      ev.preventDefault(); ev.stopPropagation();
      var editor = getEditor();
      if (!editor) return;
      var text = getEditorText(editor);
      if (!text) { btn.textContent = '先输入文字'; setTimeout(function () { btn.textContent = '译'; }, 1500); return; }
      if (CFG.outgoingTarget === 'zh-hans' && isChinese(text)) { btn.textContent = '已是中文'; setTimeout(function () { btn.textContent = '译'; }, 1500); return; }
      btn.textContent = '翻译中…'; btn.disabled = true;
      translateText(text, CFG.outgoingTarget, function (ok, out) {
        btn.disabled = false; btn.textContent = '译';
        if (!ok || !out) { btn.textContent = '翻译失败'; setTimeout(function () { btn.textContent = '译'; }, 1500); return; }
        setEditorText(getEditor(), out);
      });
    });
    group.insertBefore(btn, group.firstChild);
    btnInstalled = true;
    log('translate button installed');
  }

  // ---------- 启动 ----------
  function boot() {
    try {
      scanAll();
      startObserver();
      installTranslateButton();
      // 输入框是后加载的，轮询补装按钮
      var tries = 0;
      var timer = setInterval(function () {
        tries++;
        installTranslateButton();
        if (btnInstalled || tries > 40) clearInterval(timer);
      }, 500);
      // 切换会话时重扫
      setInterval(scanAll, 3000);
      log('zalocn translate v1.0.0 ready, incoming->' + CFG.incomingTarget + ', outgoing->' + CFG.outgoingTarget);
    } catch (e) { log('boot error', e); }
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot);
  else boot();
})();
