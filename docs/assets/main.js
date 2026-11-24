/* Codenvi website behaviour: code highlighter, copy buttons, burger menu, docs sections. No dependencies. */
(function () {
  "use strict";
  document.documentElement.classList.add("js");

  /* ---------- Syntax highlighter ---------- */
  function esc(s) {
    return s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
  }

  var STR = '"(?:\\\\.|[^"\\\\\\n])*"';
  var LANGS = {
    bash: [
      ["c", "#[^\\n]*"],
      ["s", STR + "|'[^'\\n]*'"],
      ["p", "\\$\\{?[A-Za-z_]\\w*\\}?"],
      ["o", "(?<![\\w-])--?[A-Za-z][\\w-]*"],
      ["f", "\\b(?:ssh-keygen|git|npm|npx|cd|cp|ssh|cat|codenvi-admin|pm2|dotnet|keytool|node|export|curl|sudo|mkdir|chmod)\\b"],
      ["n", "\\b\\d+(?:\\.\\d+)?\\b"],
      ["x", "[|&;<>(){}\\[\\]=]+"]
    ],
    json: [
      ["p", STR + "(?=\\s*:)"],
      ["s", STR],
      ["n", "-?\\b\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?\\b"],
      ["k", "\\b(?:true|false|null)\\b"],
      ["x", "[{}\\[\\],:]"]
    ],
    js: [
      ["c", "//[^\\n]*|/\\*[\\s\\S]*?\\*/"],
      ["s", STR + "|'(?:\\\\.|[^'\\\\\\n])*'|`(?:\\\\.|[^`\\\\])*`"],
      ["n", "\\b\\d+(?:\\.\\d+)?\\b"],
      ["k", "\\b(?:const|let|var|function|return|if|else|for|while|await|async|new|import|export|from|class|true|false|null|undefined|typeof)\\b"],
      ["f", "\\b[A-Za-z_$][\\w$]*(?=\\()"],
      ["x", "[{}()\\[\\];,.:=<>!+\\-*/&|?]+"]
    ],
    py: [
      ["c", "#[^\\n]*"],
      ["s", STR + "|'(?:\\\\.|[^'\\\\\\n])*'"],
      ["n", "\\b\\d+(?:\\.\\d+)?\\b"],
      ["k", "\\b(?:def|class|return|if|elif|else|for|while|import|from|as|with|try|except|raise|in|not|and|or|None|True|False|lambda|pass)\\b"],
      ["f", "\\b[A-Za-z_]\\w*(?=\\()"],
      ["x", "[{}()\\[\\];,.:=<>!+\\-*/&|]+"]
    ],
    yaml: [
      ["c", "#[^\\n]*"],
      ["p", "[\\w.-]+(?=\\s*:(?:\\s|$))"],
      ["s", STR + "|'[^'\\n]*'"],
      ["n", "\\b\\d+(?:\\.\\d+)?\\b"],
      ["k", "\\b(?:true|false|null|yes|no)\\b"],
      ["x", "[:\\-\\[\\]{},|>]"]
    ],
    kotlin: [
      ["c", "//[^\\n]*|/\\*[\\s\\S]*?\\*/"],
      ["s", STR],
      ["t", "@\\w+"],
      ["n", "\\b\\d+(?:\\.\\d+)?[fL]?\\b"],
      ["k", "\\b(?:val|var|fun|class|object|interface|return|if|else|when|for|while|import|package|null|true|false|is|in|override|private|public)\\b"],
      ["f", "\\b[A-Za-z_]\\w*(?=\\()"],
      ["x", "[{}()\\[\\];,.:=<>!+\\-*/&|?]+"]
    ],
    http: [
      ["c", "#[^\\n]*"],
      ["k", "\\b(?:GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS)\\b"],
      ["p", "^[A-Za-z][\\w-]*(?=:)"],
      ["s", "/[\\w/{}:.?=&%-]*"],
      ["n", "\\b\\d{3}\\b"],
      ["x", "[:]"]
    ],
    env: [
      ["c", "#[^\\n]*"],
      ["p", "^[A-Za-z_][A-Za-z0-9_]*(?==)"],
      ["x", "="],
      ["s", "(?<==)[^\\n]+"]
    ],
    text: []
  };
  var ALIAS = { sh: "bash", shell: "bash", powershell: "bash", ps1: "bash", javascript: "js", python: "py", kt: "kotlin", yml: "yaml", dotenv: "env", plain: "text", txt: "text" };
  var compiled = {};

  function compile(lang) {
    if (compiled[lang]) return compiled[lang];
    var rules = LANGS[lang] || [];
    var src = rules.map(function (r) { return "(" + r[1] + ")"; }).join("|");
    compiled[lang] = { rules: rules, re: rules.length ? new RegExp(src, "gm") : null };
    return compiled[lang];
  }

  function highlight(code, lang) {
    lang = ALIAS[lang] || lang;
    var c = compile(lang);
    if (!c.re) return esc(code);
    var out = "", last = 0, m;
    c.re.lastIndex = 0;
    while ((m = c.re.exec(code)) !== null) {
      if (m[0] === "") { c.re.lastIndex++; continue; }
      var cls = null;
      for (var i = 0; i < c.rules.length; i++) {
        if (m[i + 1] !== undefined) { cls = c.rules[i][0]; break; }
      }
      out += esc(code.slice(last, m.index));
      out += '<span class="tok-' + cls + '">' + esc(m[0]) + "</span>";
      last = m.index + m[0].length;
    }
    return out + esc(code.slice(last));
  }

  function copyText(text) {
    if (navigator.clipboard && window.isSecureContext) {
      return navigator.clipboard.writeText(text);
    }
    return new Promise(function (resolve, reject) {
      var ta = document.createElement("textarea");
      ta.value = text;
      ta.setAttribute("readonly", "");
      ta.style.cssText = "position:fixed;top:0;left:0;opacity:0";
      document.body.appendChild(ta);
      ta.select();
      try { document.execCommand("copy") ? resolve() : reject(); } catch (e) { reject(e); }
      document.body.removeChild(ta);
    });
  }

  function iconUse(name) {
    return '<svg class="icon" aria-hidden="true"><use href="#i-' + name + '"/></svg>';
  }

  function enhanceCode() {
    var blocks = document.querySelectorAll("pre > code[data-lang]");
    Array.prototype.forEach.call(blocks, function (code) {
      var pre = code.parentNode;
      var raw = code.textContent.replace(/^\n+|\s+$/g, "");
      var lang = (code.getAttribute("data-lang") || "text").toLowerCase();
      var title = code.getAttribute("data-title") || lang;
      try { code.innerHTML = highlight(raw, lang); } catch (e) { code.textContent = raw; }

      var fig = document.createElement("div");
      fig.className = "code-block";
      var bar = document.createElement("div");
      bar.className = "code-bar";
      bar.innerHTML = '<span class="dots" aria-hidden="true"><i></i><i></i><i></i></span><span class="code-title"></span>' +
        '<button type="button" class="copy" aria-label="Copy code">' + iconUse("copy") + "<span>Copy</span></button>";
      bar.querySelector(".code-title").textContent = title;
      pre.setAttribute("tabindex", "0");
      pre.parentNode.insertBefore(fig, pre);
      fig.appendChild(bar);
      fig.appendChild(pre);

      var btn = bar.querySelector(".copy"), timer;
      btn.addEventListener("click", function () {
        copyText(raw).then(function () {
          btn.classList.add("done");
          btn.querySelector("span").textContent = "Copied";
          btn.querySelector("use").setAttribute("href", "#i-check");
          clearTimeout(timer);
          timer = setTimeout(function () {
            btn.classList.remove("done");
            btn.querySelector("span").textContent = "Copy";
            btn.querySelector("use").setAttribute("href", "#i-copy");
          }, 1800);
        }, function () {});
      });
    });
  }

  /* ---------- Burger menu ---------- */
  var root = document.documentElement;
  var burger, mq = window.matchMedia("(max-width: 820px)");

  function setMenu(open) {
    if (!burger) return;
    root.classList.toggle("menu-open", open);
    burger.setAttribute("aria-expanded", open ? "true" : "false");
    burger.setAttribute("aria-label", open ? "Close menu" : "Open menu");
    var use = burger.querySelector("use");
    if (use) use.setAttribute("href", open ? "#i-x" : "#i-menu");
  }
  function closeMenu() { setMenu(false); }
  window.NH_closeMenu = closeMenu;

  function initMenu() {
    burger = document.querySelector(".burger");
    if (!burger) return;
    burger.addEventListener("click", function () { setMenu(!root.classList.contains("menu-open")); });
    document.addEventListener("keydown", function (e) {
      if (e.key === "Escape" && root.classList.contains("menu-open")) { closeMenu(); burger.focus(); }
    });
    var target = document.getElementById(burger.getAttribute("aria-controls"));
    if (target) {
      target.addEventListener("click", function (e) {
        if (e.target.closest && e.target.closest("a")) closeMenu();
      });
    }
    var onChange = function () { if (!mq.matches) closeMenu(); };
    if (mq.addEventListener) mq.addEventListener("change", onChange); else mq.addListener(onChange);
  }

  /* ---------- Docs sections ---------- */
  function initDocs() {
    var sections = Array.prototype.slice.call(document.querySelectorAll(".doc-section"));
    if (!sections.length) return;
    var links = Array.prototype.slice.call(document.querySelectorAll("[data-section]"));
    var siteName = (document.title.split(" | ").pop() || "Codenvi").trim();
    var first = sections[0].id;

    function find(id) {
      for (var i = 0; i < sections.length; i++) if (sections[i].id === id) return sections[i];
      return null;
    }
    function show(id, push) {
      var sec = find(id) || find(first);
      sections.forEach(function (s) { s.classList.toggle("active", s === sec); });
      links.forEach(function (a) {
        if (a.getAttribute("data-section") === sec.id) a.setAttribute("aria-current", "true");
        else a.removeAttribute("aria-current");
      });
      var h1 = sec.querySelector("h1");
      document.title = (h1 ? h1.textContent : sec.id) + " | " + siteName;
      if (push && location.hash !== "#" + sec.id) history.pushState(null, "", "#" + sec.id);
      window.scrollTo(0, 0);
      closeMenu();
    }

    document.addEventListener("click", function (e) {
      var a = e.target.closest ? e.target.closest("[data-section],[data-goto]") : null;
      if (!a) return;
      var id = a.getAttribute("data-section") || a.getAttribute("data-goto");
      e.preventDefault();
      show(id, true);
    });
    window.addEventListener("popstate", function () { show(location.hash.slice(1), false); });
    show(location.hash.slice(1), false);
    setTimeout(function () { window.scrollTo(0, 0); }, 0);
  }

  document.addEventListener("DOMContentLoaded", function () {
    enhanceCode();
    initMenu();
    initDocs();
  });
})();
