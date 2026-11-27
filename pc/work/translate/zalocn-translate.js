// Zalo PC 翻译注入 v1.0.10 — 听（收消息自动翻）+ 说（译按钮）+ 设置面板
// 注入方式：index.html 与 compact-app.html 里加 <script src="zalocn-translate.js">
// （v1.0.1：主窗口加载 index.html，只注 compact-app 的话主窗口不加载）
// v1.0.2：按钮持久化（切换会话重装）、观察器重挂、翻译失败重试 3 次后显示提示
// v1.0.3：fetch 加 15 秒超时（API 挂起会卡死整个翻译队列）、boot 各步独立容错
// v1.0.4：根因修复——主窗口正常消息走 Frame 布局，容器是
//   [data-component="message-content-view"][data-qId]（id=message-frame_*），
//   根本没有 .chat-item 类，旧选择器 .chat-item.to-me 一条都找不到。
//   改用 [data-qId] 选全部消息容器，按 me / not-me 类判断收发。
// v1.0.5：v1.0.4 自动翻译已通，但点「译」报“找不到输入框”。
//   getEditor 改从按钮就近找：按钮在 .normal-buttons-group 里，
//   同属 .chat-input-container，左栏 #chat-input-content-id 就是输入区，
//   在里面找可编辑元素（contenteditable/textarea/role=textbox），
//   再加全局兜底；setEditorText 同时支持 contenteditable 与 textarea。
// v1.0.6：两件事——① 自己发的越南语也翻译（translateOwn，默认开）；
//   ② 设置面板（齿轮按钮+浮层：收/说语言、API、提示词、自动翻译、
//   翻译自己消息、测试；改完自动保存，立即生效）。
// v1.0.7：补“‘译’按钮同时保留原文”选项（和安卓面板同名选项一致，
//   默认开：中文一行 + 越南语一行填回；关了只填越南语）。
(function () {
  'use strict';
  if (window.__zalocn_translate_loaded) return;
  window.__zalocn_translate_loaded = true;
  try { localStorage.setItem('zalocn-status', 'ZALOCN boot v1.0.11 ' + Date.now()); } catch (e) {}

  // ---------- 配置（默认；可用 localStorage zalocn-config 覆盖） ----------
  var DEFAULTS = {
    apiUrl: 'https://index-translate.bilibili.com/v1/chat/completions',
    apiKey: '',
    model: 'Index-Translate-35B-A3B',
    incomingTarget: 'zh-hans',   // 听：收到的消息翻成中文
    outgoingTarget: 'vi',        // 说：译按钮把中文翻成越南语
    autoTranslate: true,
    translateOwn: true,          // 自己发的消息也翻（翻成 incomingTarget，方便核对）
    keepOriginal: true,          // “译”按钮同时保留原文：中文一行 + 越南语一行；关了只填越南语
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
  // 设置面板下拉框的语言列表（和安卓面板一致）
  var LANGS = [
    ['zh-hans', '中文（简体）'], ['zh-hant', '中文（繁體）'], ['vi', '越南语'],
    ['en', '英语'], ['ko', '韩语'], ['th', '泰语'], ['lo', '老挝语'],
    ['km', '高棉语'], ['my', '缅甸语'], ['ms', '马来语'], ['id', '印尼语'], ['ja', '日语']
  ];

  // ---------- 翻译 ----------
  var inflight = 0, queue = [];
  function translateText(text, targetLang, cb) {
    queue.push({ text: text, target: targetLang, cb: cb });
    pump();
  }
  // v1.0.8：原来的 bilibili 公开接口已不稳定（对普通请求返回 invalid_request，
  // 或直接回原文），而 Google gtx 在本机被拦，导致“译文·中文”里显示原越南语。
  // 现在：MyMemory（无需密钥，实测可用）优先 → bilibili → Google，且每一步都
  // 校验译文是否真的换成了目标语言，没换就当失败继续兜底。
  function srcCode(text) {
    if (/[\u4e00-\u9fff]/.test(text)) return 'zh-CN';
    if (/[\u0103\u00e2\u0111\u00ea\u00f4\u01a1\u01b0\u00e0-\u1ef9]/i.test(text)) return 'vi';
    return 'en';
  }
  function tgtCode(target) {
    if (target === 'zh-hans') return 'zh-CN';
    if (target === 'zh-hant') return 'zh-TW';
    return target;
  }
  // 纯数字/表情/极短消息不需要翻译，静默跳过（避免满屏“翻译失败”）。
  function worthTranslating(text) {
    var t = String(text || '').trim();
    if (t.length < 4) return false;
    return /[A-Za-z\u00C0-\u1EF9]{2,}/.test(t);
  }
  function isValidTranslation(src, target, out) {
    if (!out) return false;
    var a = String(src).replace(/\s+/g, ' ').trim();
    var b = String(out).replace(/\s+/g, ' ').trim();
    if (!b) return false;
    if (a.toLowerCase() === b.toLowerCase()) return false;
    if (target.indexOf('zh') === 0) return /[\u4e00-\u9fff]/.test(b);
    if (target === 'vi') return /[a-zA-Z\u00C0-\u1EF9]/.test(b) && !/[\u4e00-\u9fff]/.test(b);
    return true;
  }
  function callMyMemory(text, target, cb) {
    var pair = srcCode(text) + '|' + tgtCode(target);
    var url = 'https://api.mymemory.translated.net/get?q=' + encodeURIComponent(text) +
      '&langpair=' + encodeURIComponent(pair);
    fetchWithTimeout(url, null, 15000, function (ok, j) {
      if (!ok || !j) { cb(false, typeof j === 'string' ? j : 'E'); return; }
      var d = j.responseData || {};
      var out = d.translatedText || '';
      if (out && /INVALID|QUOTA|MYMEMORY WARNING/i.test(out)) { cb(false, ''); return; }
      if (out) cb(true, String(out).trim()); else cb(false, '');
    });
  }
  // v1.0.11：引擎顺序改为 bilibili 优先。
  // 实测（2026-11-27）：MyMemory 免费额度按 IP 每天限额，超额后 200 返回 "MYMEMORY WARNING"；
  // Google gtx 在本机被限流成 429；bilibili index-translate 无密钥可用且译文质量更好。
  // 另外把状态写进 document.title，便于外部（自动化/支持）读到翻译是否在跑。
  var STAT = { ok: 0, fail: 0, last: '', lastSrc: '' };
  function tel() {
    var line = 'ZALOCN v1.0.11 ok=' + STAT.ok + ' fail=' + STAT.fail + ' q=' + queue.length +
      (STAT.last ? ' last=' + STAT.last : '') +
      (STAT.lastSrc ? ' src=' + STAT.lastSrc : '');
    try { localStorage.setItem('zalocn-status', line + ' t=' + Date.now()); } catch (e) {}
  }
  function pump() {
    if (inflight >= 3 || !queue.length) return;
    var job = queue.shift();
    inflight++;
    var NAMES = ['bilibili', 'mymemory', 'google'];
    var tries = [
      function (d) { callBilibili(job.text, job.target, d); },
      function (d) { callMyMemory(job.text, job.target, d); },
      function (d) { callGoogle(job.text, job.target, d); }
    ];
    var fails = [];
    function next(i) {
      if (i >= tries.length) {
        inflight--;
        STAT.fail++;
        STAT.last = 'all-failed(' + fails.join(',') + ')';
        tel();
        job.cb(false, '');
        pump();
        return;
      }
      tries[i](function (ok, out) {
        if (ok && isValidTranslation(job.text, job.target, out)) {
          inflight--;
          STAT.ok++;
          STAT.last = '';
          tel();
          job.cb(true, out);
          pump();
          return;
        }
        fails.push(NAMES[i] + ':' + (typeof out === 'string' && out.length <= 8 ? out : '-'));
        if (!STAT.lastSrc) { STAT.lastSrc = String(job.text).replace(/\s+/g, ' ').slice(0, 28); }
        next(i + 1);
      });
    }
    next(0);
  }
  // v1.0.9：bilibili 官方用法是“把指令写在 user 消息里”，而不是 system role
  // （模型会对纯 system 指令给出“保留原文/只润色”的结果）。同时带上官方要求的
  // chat_template_kwargs.enable_thinking=false，避免思考文本替代译文。
  function systemPrompt(target) {
    var p = '请将以下文本翻译为' + langName(target) +
      '，直接输出翻译结果，不要进行任何解释。保留占位符、数字、链接与换行不变。';
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
      timer = setTimeout(function () { try { ctrl.abort(); } catch (e) {} finish(false, 'T'); }, ms);
    } else {
      timer = setTimeout(function () { finish(false, 'T'); }, ms);
    }
    fetch(url, opts)
      .then(function (r) { if (!r.ok) throw new Error('H' + r.status); return r.json(); })
      .then(function (j) { finish(true, j); })
      .catch(function (e) { finish(false, String((e && e.message) || 'E').slice(0, 8)); });
  }
  function callBilibili(text, target, cb) {
    var headers = { 'Content-Type': 'application/json' };
    if (CFG.apiKey) headers['Authorization'] = 'Bearer ' + CFG.apiKey;
    fetchWithTimeout(CFG.apiUrl, {
      method: 'POST', headers: headers,
      body: JSON.stringify({
        model: CFG.model,
        messages: [
          { role: 'user', content: systemPrompt(target) + '\n\n' + text }
        ],
        temperature: 0.0,
        max_tokens: 1024,
        stream: false,
        chat_template_kwargs: { enable_thinking: false }
      })
    }, 15000, function (ok, j) {
      if (!ok || !j) { cb(false, typeof j === 'string' ? j : 'E'); return; }
      var out = j && j.choices && j.choices[0] && j.choices[0].message && j.choices[0].message.content;
      if (out) cb(true, String(out).trim()); else cb(false, '');
    });
  }
  function callGoogle(text, target, cb) {
    var tl = target === 'zh-hans' ? 'zh-CN' : target;
    fetchWithTimeout('https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=' + encodeURIComponent(tl) + '&dt=t&q=' + encodeURIComponent(text),
      null, 15000, function (ok, j) {
      if (!ok || !j) { cb(false, typeof j === 'string' ? j : 'E'); return; }
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
    if (target === 'zh-hans' || target === 'zh-hant') {
      // v1.0.12：自己发的消息是「中文 + 越南语」双语（保留原文发送），
      // 用 isChinese（≥50%）判定不够，会把混合消息排进翻译队列 →
      // 三个引擎都返回原文 → 判定失败 → 3 次后给用户自己的消息盖上"翻译失败"。
      // 改成 CJK 占比 ≥ 30% 就认为已经是中文，不翻。
      var m = text.match(/[\u4e00-\u9fff]/g);
      var cjk = m ? m.length : 0;
      var visible = text.replace(/\s/g, '').length || 1;
      return (cjk / visible) < 0.3;
    }
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
    if (isOutgoing(item)) return;   // 自己的消息不插"翻译失败"，避免误伤
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
    // 自己发的消息：translateOwn 开时也翻（翻成 incomingTarget，方便核对自己发了什么）；
    // 关时跳过。已是目标语言的（比如自己发的中文）由 needsTranslate 挡下，不会重复翻。
    if (isOutgoing(item) && !CFG.translateOwn) return;
    if (item.getAttribute('data-zalocn-done')) return;
    var qid = item.getAttribute('data-qId') || '';
    if (qid && translatedIds[qid]) { markDone(item, qid); return; }
    if (qid && translatingIds[qid]) return;                  // 正在翻译中，等回调
    var text = extractText(item);
    if (!text || !needsTranslate(text, CFG.incomingTarget) || !worthTranslating(text)) {
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
  // 找输入框：优先从按钮就近找——按钮在 .normal-buttons-group 里，
  // 同属 .chat-input-container，左栏 #chat-input-content-id 就是输入区；
  // 在里面找可编辑元素（contenteditable / textarea / role=textbox）。
  // 再加全局兜底（.zcinput-root、ProseMirror、任意 contenteditable）。
  function getEditor(fromBtn) {
    var container = null;
    if (fromBtn && fromBtn.closest) {
      try { container = fromBtn.closest('.chat-input-container'); } catch (e) {}
    }
    if (!container) container = document.querySelector('.chat-input-container');
    if (container) {
      var ed = container.querySelector('[contenteditable]:not([contenteditable="false"])')
        || container.querySelector('textarea')
        || container.querySelector('[role="textbox"]');
      if (ed) return ed;
      // 输入区容器本身也可能就是可编辑元素
      var content = container.querySelector('#chat-input-content-id')
        || container.querySelector('.chat-input-content')
        || container.querySelector('.chat-input-scrollable');
      if (content) {
        var inner = content.querySelector('[contenteditable]') || content.querySelector('textarea');
        if (inner) return inner;
        if (content.isContentEditable) return content;
      }
    }
    var root = document.querySelector('.zcinput-root[data-component="rtf-container"]')
      || document.querySelector('.zcinput-root');
    if (root) {
      var ed2 = root.querySelector('[contenteditable]:not([contenteditable="false"])');
      if (ed2) return ed2;
    }
    return document.querySelector('.ProseMirror[contenteditable="true"]')
      || document.querySelector('.chat-input-container [contenteditable="true"]')
      || document.querySelector('[contenteditable="true"]')
      || document.querySelector('[contenteditable]:not([contenteditable="false"])')
      || document.querySelector('textarea');
  }
  function getEditorText(ed) {
    if (!ed) return '';
    // textarea/input 用 value，contenteditable 用 innerText/textContent
    if (ed.tagName === 'TEXTAREA' || ed.tagName === 'INPUT') return (ed.value || '').trim();
    return getText(ed).replace(/\u200b/g, '').trim();
  }
  function setEditorText(ed, text) {
    if (!ed) return false;
    // textarea/input：直接设 value 并派发 input 事件
    if (ed.tagName === 'TEXTAREA' || ed.tagName === 'INPUT') {
      try {
        ed.focus();
        ed.value = text;
        ed.dispatchEvent(new Event('input', { bubbles: true }));
        ed.dispatchEvent(new Event('change', { bubbles: true }));
        return true;
      } catch (e) { return false; }
    }
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
      var editor = getEditor(btn);
      // 各失败分支给明确提示，不静默：找不到输入框 / 没输文字 / 翻译失败
      if (!editor) { btn.textContent = '找不到输入框'; log('translate button: editor not found'); setTimeout(function () { btn.textContent = '译'; }, 2000); return; }
      var text = getEditorText(editor);
      if (!text) { btn.textContent = '先输入文字'; setTimeout(function () { btn.textContent = '译'; }, 1500); return; }
      if (CFG.outgoingTarget === 'zh-hans' && isChinese(text)) { btn.textContent = '已是中文'; setTimeout(function () { btn.textContent = '译'; }, 1500); return; }
      btn.textContent = '翻译中…'; btn.disabled = true;
      translateText(text, CFG.outgoingTarget, function (ok, out) {
        btn.disabled = false; btn.textContent = '译';
        if (!ok || !out) { btn.textContent = '翻译失败'; log('translate button: api failed'); setTimeout(function () { btn.textContent = '译'; }, 2000); return; }
        // keepOriginal 开：原文一行 + 译文一行填回；关：只填译文（和安卓面板同名选项一致）
        var filled = CFG.keepOriginal ? (text + '\n' + out) : out;
        if (!setEditorText(getEditor(btn), filled)) { btn.textContent = '填回失败'; log('translate button: setEditorText failed'); setTimeout(function () { btn.textContent = '译'; }, 2000); }
      });
    });
    group.insertBefore(btn, group.firstChild);
    installSettingsButton(group);
    log('translate button installed');
    return true;
  }

  // ---------- 设置面板（和安卓面板一样可调） ----------
  // 浮层挂在 document.body 下（React 管不着，不会被重渲染删掉）；
  // 齿轮按钮和「译」按钮一起装在 .normal-buttons-group 里，同样走持久化重装。
  var panelEl = null;
  function langOptionsHtml(selected) {
    var h = '';
    for (var i = 0; i < LANGS.length; i++) {
      h += '<option value="' + LANGS[i][0] + '"' + (LANGS[i][0] === selected ? ' selected' : '') + '>' + LANGS[i][1] + '</option>';
    }
    return h;
  }
  function buildSettingsPanel() {
    if (panelEl && document.contains(panelEl)) return panelEl;
    var p = document.createElement('div');
    p.className = 'zalocn-settings-panel';
    p.style.cssText = 'position:fixed;right:16px;bottom:96px;width:340px;max-height:72vh;overflow-y:auto;z-index:999999;background:#fff;border:1px solid #e5e7eb;border-radius:12px;box-shadow:0 8px 32px rgba(0,0,0,.18);padding:16px;font-family:inherit;font-size:13px;color:#1f2937;display:none;';
    p.innerHTML =
      '<div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:10px;">' +
        '<div style="font-size:15px;font-weight:700;">翻译设置</div>' +
        '<button type="button" data-act="close" style="border:none;background:none;font-size:18px;cursor:pointer;color:#6b7280;">×</button>' +
      '</div>' +
      '<div style="color:#6b7280;font-size:12px;margin-bottom:12px;">改完自动保存，立即生效。</div>' +
      '<label style="display:block;margin-bottom:4px;font-weight:600;">收到的消息翻译成</label>' +
      '<select data-field="incomingTarget" style="width:100%;padding:6px;border:1px solid #d1d5db;border-radius:6px;margin-bottom:10px;">' + langOptionsHtml(CFG.incomingTarget) + '</select>' +
      '<label style="display:block;margin-bottom:4px;font-weight:600;">“译”按钮翻译成</label>' +
      '<select data-field="outgoingTarget" style="width:100%;padding:6px;border:1px solid #d1d5db;border-radius:6px;margin-bottom:10px;">' + langOptionsHtml(CFG.outgoingTarget) + '</select>' +
      '<label style="display:block;margin-bottom:10px;"><input type="checkbox" data-field="autoTranslate"' + (CFG.autoTranslate ? ' checked' : '') + '> 信息自动翻译（收到消息自动出译文）</label>' +
      '<label style="display:block;margin-bottom:12px;"><input type="checkbox" data-field="translateOwn"' + (CFG.translateOwn ? ' checked' : '') + '> 翻译自己发送的消息（自己发的越南语也翻成中文，方便核对）</label>' +
      '<label style="display:block;margin-bottom:12px;"><input type="checkbox" data-field="keepOriginal"' + (CFG.keepOriginal ? ' checked' : '') + '> “译”按钮同时保留原文（中文一行 + 越南语一行；不勾只填越南语）</label>' +
      '<label style="display:block;margin-bottom:4px;font-weight:600;">API 地址</label>' +
      '<input type="text" data-field="apiUrl" value="' + (CFG.apiUrl || '').replace(/"/g, '&quot;') + '" style="width:100%;padding:6px;border:1px solid #d1d5db;border-radius:6px;margin-bottom:10px;box-sizing:border-box;">' +
      '<label style="display:block;margin-bottom:4px;font-weight:600;">API 密钥（可空）</label>' +
      '<input type="password" data-field="apiKey" value="' + (CFG.apiKey || '').replace(/"/g, '&quot;') + '" placeholder="bilibili 公开接口不用填" style="width:100%;padding:6px;border:1px solid #d1d5db;border-radius:6px;margin-bottom:10px;box-sizing:border-box;">' +
      '<label style="display:block;margin-bottom:4px;font-weight:600;">模型</label>' +
      '<input type="text" data-field="model" value="' + (CFG.model || '').replace(/"/g, '&quot;') + '" style="width:100%;padding:6px;border:1px solid #d1d5db;border-radius:6px;margin-bottom:10px;box-sizing:border-box;">' +
      '<label style="display:block;margin-bottom:4px;font-weight:600;">翻译提示词（只对大模型生效）</label>' +
      '<textarea data-field="customPrompt" rows="3" placeholder="地名对照合并写，如 老街-Lào Cai，河内-Hà Nội" style="width:100%;padding:6px;border:1px solid #d1d5db;border-radius:6px;margin-bottom:12px;box-sizing:border-box;resize:vertical;"></textarea>' +
      '<div style="display:flex;gap:8px;align-items:center;">' +
        '<button type="button" data-act="test" style="padding:6px 14px;border:1px solid #0068FF;border-radius:6px;background:#0068FF;color:#fff;font-size:13px;cursor:pointer;">测试当前配置</button>' +
        '<span data-role="saved" style="font-size:12px;color:#16a34a;"></span>' +
      '</div>' +
      '<div data-role="test-result" style="margin-top:8px;font-size:12px;color:#374151;white-space:pre-wrap;word-break:break-word;"></div>';
    p.querySelector('[data-field="customPrompt"]').value = CFG.customPrompt || '';
    // 改完自动保存（change 事件；文本框失焦时触发；测试/关闭前也会先保存一次）
    p.addEventListener('change', function () { saveFromPanel(); });
    p.querySelector('[data-act="close"]').addEventListener('click', function () { saveFromPanel(); closeSettings(); });
    p.querySelector('[data-act="test"]').addEventListener('click', function () { testFromPanel(); });
    document.body.appendChild(p);
    panelEl = p;
    return p;
  }
  function saveFromPanel() {
    var p = panelEl;
    if (!p || !document.contains(p)) return;
    var cfg = {};
    try { cfg = JSON.parse(localStorage.getItem('zalocn-config') || '{}') || {}; } catch (e) { cfg = {}; }
    var fields = p.querySelectorAll('[data-field]');
    for (var i = 0; i < fields.length; i++) {
      var f = fields[i], k = f.getAttribute('data-field');
      if (f.type === 'checkbox') cfg[k] = !!f.checked;
      else cfg[k] = f.value;
    }
    try { localStorage.setItem('zalocn-config', JSON.stringify(cfg)); } catch (e) {}
    CFG = loadConfig();
    // 「译」按钮标题跟着新目标语言走
    var btn = document.querySelector('.zalocn-translate-btn');
    if (btn) btn.title = '翻译成' + langName(CFG.outgoingTarget) + '（填回输入框，不自动发）';
    var saved = p.querySelector('[data-role="saved"]');
    if (saved) {
      saved.textContent = '已保存';
      setTimeout(function () { if (saved) saved.textContent = ''; }, 1500);
    }
    log('settings saved, incoming->' + CFG.incomingTarget + ', outgoing->' + CFG.outgoingTarget);
  }
  function testFromPanel() {
    var p = panelEl;
    if (!p) return;
    saveFromPanel();
    var res = p.querySelector('[data-role="test-result"]');
    if (res) res.textContent = '正在测试…';
    // 测中文→“译”按钮目标语言，能通就说明 API 配置可用
    translateText('你好，今天天气怎么样？', CFG.outgoingTarget, function (ok, out) {
      if (!res || !document.contains(res)) return;
      if (ok && out) res.textContent = '当前配置能正常使用。测试译文：' + out;
      else res.textContent = '测试失败：API 不通或配置有误，请检查 API 地址/密钥/模型与网络。';
    });
  }
  function openSettings() {
    var p = buildSettingsPanel();
    // 每次打开都按当前配置刷新表单值
    var fields = p.querySelectorAll('[data-field]');
    for (var i = 0; i < fields.length; i++) {
      var f = fields[i], k = f.getAttribute('data-field');
      if (f.type === 'checkbox') f.checked = !!CFG[k];
      else if (f.tagName === 'SELECT' || f.tagName === 'INPUT' || f.tagName === 'TEXTAREA') f.value = CFG[k] || '';
    }
    var res = p.querySelector('[data-role="test-result"]');
    if (res) res.textContent = '';
    p.style.display = 'block';
  }
  function closeSettings() {
    if (panelEl) panelEl.style.display = 'none';
  }
  function toggleSettings() {
    if (panelEl && panelEl.style.display === 'block') closeSettings();
    else openSettings();
  }
  // 齿轮按钮：和「译」按钮一起装，点开设置面板
  function installSettingsButton(group) {
    if (document.querySelector('.zalocn-settings-btn')) return true;
    if (!group) return false;
    var btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'zalocn-settings-btn';
    btn.textContent = '⚙';
    btn.title = '翻译设置';
    btn.style.cssText = 'margin:0 4px;padding:2px 8px;border:1px solid #d1d5db;border-radius:6px;background:#fff;color:#374151;font-size:13px;cursor:pointer;flex:none;';
    btn.addEventListener('click', function (ev) {
      ev.preventDefault(); ev.stopPropagation();
      toggleSettings();
    });
    group.appendChild(btn);
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
    // 诊断用：把 DOM 观测结果落到 localStorage（无 DevTools 时唯一的观测通道）
    try {
      var _c = listContainer();
      localStorage.setItem('zalocn-boot', JSON.stringify({
        v: '1.0.11',
        msgs: document.querySelectorAll('[data-qId]').length,
        list: _c ? String(_c.id || _c.className || _c.tagName).slice(0, 60) : 'none',
        btn: !!document.querySelector('.zalocn-translate-btn'),
        group: !!document.querySelector('.normal-buttons-group'),
        auto: CFG.autoTranslate,
        href: location.href.slice(-40)
      }));
    } catch (e) {}
    log('zalocn translate v1.0.10 ready, incoming->' + CFG.incomingTarget + ', outgoing->' + CFG.outgoingTarget);
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot);
  else boot();
})();
