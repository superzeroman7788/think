(function () {
  var MAX_ITEMS = 20;
  var FEED_URLS = [
    "https://www.anthropic.com/news/rss.xml",
    "https://www.anthropic.com/news/feed.xml",
    "https://www.anthropic.com/rss.xml",
    "https://www.anthropic.com/feed.xml",
    "https://www.anthropic.com/news"
  ];

  var sampleNews = [
    {
      title: "Claude 资讯示例：关注 Anthropic News",
      date: "2026-06-01T08:00:00Z",
      summary: "当浏览器无法直接读取官方页面或 feed 时，此示例数据会用于保持页面可读。联网后点击刷新会再次尝试官方源。",
      link: "https://www.anthropic.com/news"
    },
    {
      title: "Claude 资讯示例：模型发布与产品更新",
      date: "2026-05-28T08:00:00Z",
      summary: "这里展示标题、日期、摘要和原文链接的卡片格式。替换 feed 地址后，页面会继续按发布时间倒序保留最多 20 条。",
      link: "https://www.anthropic.com/claude"
    },
    {
      title: "Claude 资讯示例：研究、安全与企业案例",
      date: "2026-05-20T08:00:00Z",
      summary: "示例条目覆盖常见 Claude 资讯类型，便于断网或 CORS 失败时验证页面不会白屏。",
      link: "https://www.anthropic.com/research"
    }
  ];

  var list = document.getElementById("news-list");
  var statusEl = document.getElementById("status");
  var sourceLabel = document.getElementById("source-label");
  var updatedLabel = document.getElementById("updated-label");
  var countLabel = document.getElementById("count-label");
  var refreshButton = document.getElementById("refresh-button");
  var template = document.getElementById("news-card-template");

  function decodeHtml(value) {
    var textarea = document.createElement("textarea");
    textarea.innerHTML = value || "";
    return textarea.value;
  }

  function stripHtml(value) {
    return decodeHtml(String(value || "").replace(/<[^>]+>/g, " ")).replace(/\s+/g, " ").trim();
  }

  function shortSummary(value) {
    var text = stripHtml(value);
    if (!text) {
      return "暂无摘要。";
    }
    return text.length > 150 ? text.slice(0, 147) + "..." : text;
  }

  function getText(node, selectors) {
    for (var i = 0; i < selectors.length; i += 1) {
      var found = node.querySelector(selectors[i]);
      if (found && found.textContent.trim()) {
        return found.textContent.trim();
      }
    }
    return "";
  }

  function getLink(node) {
    var atomLink = node.querySelector("link[href]");
    if (atomLink) {
      return atomLink.getAttribute("href");
    }
    var linkText = getText(node, ["link"]);
    return linkText || "https://www.anthropic.com/news";
  }

  function parseXmlFeed(text) {
    var doc = new DOMParser().parseFromString(text, "application/xml");
    if (doc.querySelector("parsererror")) {
      throw new Error("官方源不是可解析的 XML feed");
    }

    var nodes = Array.prototype.slice.call(doc.querySelectorAll("item, entry"));
    if (!nodes.length) {
      throw new Error("官方 XML feed 中没有资讯条目");
    }

    return nodes.map(function (node) {
      return {
        title: getText(node, ["title"]),
        date: getText(node, ["pubDate", "published", "updated", "dc\\:date"]),
        summary: shortSummary(getText(node, ["description", "summary", "content", "content\\:encoded"])),
        link: getLink(node)
      };
    });
  }

  function parseAnthropicNewsPage(text) {
    var doc = new DOMParser().parseFromString(text, "text/html");
    var anchors = Array.prototype.slice.call(doc.querySelectorAll('a[href*="/news/"]'));
    var seen = {};
    var items = [];

    anchors.forEach(function (anchor) {
      var href = anchor.getAttribute("href") || "";
      var title = stripHtml(anchor.textContent);
      if (!title || title.length < 12 || href === "/news" || seen[href]) {
        return;
      }
      seen[href] = true;
      items.push({
        title: title,
        date: new Date().toISOString(),
        summary: "来自 Anthropic 官方 News 页面。官方页面未提供结构化摘要时，页面会显示此简短说明。",
        link: href.indexOf("http") === 0 ? href : "https://www.anthropic.com" + href
      });
    });

    if (!items.length) {
      throw new Error("官方 News 页面中没有找到可展示的资讯链接");
    }
    return items;
  }

  function normalizeItems(items) {
    return items
      .filter(function (item) {
        return item && item.title && item.link;
      })
      .map(function (item) {
        var timestamp = Date.parse(item.date);
        return {
          title: stripHtml(item.title),
          date: Number.isNaN(timestamp) ? new Date().toISOString() : new Date(timestamp).toISOString(),
          summary: shortSummary(item.summary),
          link: item.link
        };
      })
      .sort(function (a, b) {
        return Date.parse(b.date) - Date.parse(a.date);
      })
      .slice(0, MAX_ITEMS);
  }

  function formatDateParts(dateText) {
    var date = new Date(dateText);
    return {
      month: date.toLocaleDateString("zh-CN", { month: "short" }),
      day: date.toLocaleDateString("zh-CN", { day: "2-digit" }),
      year: date.toLocaleDateString("zh-CN", { year: "numeric" })
    };
  }

  function setStatus(message, kind) {
    statusEl.textContent = message;
    statusEl.dataset.kind = kind || "info";
  }

  function render(items, sourceName, statusMessage, statusKind) {
    list.innerHTML = "";
    items.forEach(function (item) {
      var parts = formatDateParts(item.date);
      var fragment = template.content.cloneNode(true);
      var titleLink = fragment.querySelector(".news-link");
      var readLink = fragment.querySelector(".read-link");

      fragment.querySelector(".date-month").textContent = parts.month;
      fragment.querySelector(".date-day").textContent = parts.day;
      fragment.querySelector(".date-year").textContent = parts.year;
      titleLink.textContent = item.title;
      titleLink.href = item.link;
      fragment.querySelector(".summary").textContent = item.summary;
      readLink.href = item.link;
      list.appendChild(fragment);
    });

    sourceLabel.textContent = sourceName;
    updatedLabel.textContent = "刷新时间：" + new Date().toLocaleString("zh-CN");
    countLabel.textContent = items.length + " / " + MAX_ITEMS;
    setStatus(statusMessage, statusKind);
  }

  function fetchWithTimeout(url) {
    var controller = new AbortController();
    var timer = window.setTimeout(function () {
      controller.abort();
    }, 7000);

    return fetch(url, {
      cache: "no-store",
      mode: "cors",
      signal: controller.signal
    }).finally(function () {
      window.clearTimeout(timer);
    });
  }

  function parseByContent(url, text) {
    if (url.indexOf("/news") !== -1 && !/^\s*</.test(text)) {
      throw new Error("官方源返回了非 XML/HTML 内容");
    }
    if (/^\s*<(rss|feed|rdf:RDF)\b/i.test(text)) {
      return parseXmlFeed(text);
    }
    if (url.indexOf("/news") !== -1) {
      return parseAnthropicNewsPage(text);
    }
    return parseXmlFeed(text);
  }

  async function loadOfficialNews() {
    var errors = [];
    for (var i = 0; i < FEED_URLS.length; i += 1) {
      var url = FEED_URLS[i];
      try {
        var response = await fetchWithTimeout(url);
        if (!response.ok) {
          throw new Error("HTTP " + response.status);
        }
        var text = await response.text();
        var items = normalizeItems(parseByContent(url, text));
        if (!items.length) {
          throw new Error("没有可展示条目");
        }
        return { items: items, source: url };
      } catch (error) {
        errors.push(url + ": " + error.message);
      }
    }
    throw new Error(errors.join(" | "));
  }

  async function refreshNews() {
    refreshButton.disabled = true;
    refreshButton.textContent = "刷新中...";
    setStatus("正在尝试读取 Anthropic 官方资讯源...", "info");

    try {
      var result = await loadOfficialNews();
      render(result.items, "官方源", "已显示 Anthropic 官方资讯，按发布时间倒序保留最多 20 条。", "info");
    } catch (error) {
      console.error(error);
      render(
        normalizeItems(sampleNews),
        "内置示例",
        "官方源暂时无法读取，已切换到示例数据；页面仍可正常浏览，稍后可点击刷新重试。",
        "error"
      );
    } finally {
      refreshButton.disabled = false;
      refreshButton.textContent = "刷新";
    }
  }

  refreshButton.addEventListener("click", refreshNews);
  refreshNews();
})();
