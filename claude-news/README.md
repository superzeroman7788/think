# Claude 每日资讯

这是一个本地静态网页，用于每天查看 Claude / Anthropic 资讯。无需后端、无需构建，双击 `index.html` 即可打开。

## 如何打开

1. 打开 `claude-news/index.html`。
2. 页面加载后会自动尝试拉取 Anthropic 官方资讯源。
3. 点击页面右上角的「刷新」按钮可重新拉取。

## 数据源说明

页面优先尝试读取 Anthropic 官方公开地址：

- `https://www.anthropic.com/news/rss.xml`
- `https://www.anthropic.com/news/feed.xml`
- `https://www.anthropic.com/rss.xml`
- `https://www.anthropic.com/feed.xml`
- `https://www.anthropic.com/news`

浏览器直接打开本地文件时，官方站点可能因为 CORS、网络或 feed 路径变化而拒绝读取。发生失败时，页面会自动回退到 `app.js` 内置的示例资讯数据，避免白屏或报错中断。

## 展示规则

- 按发布时间倒序排列。
- 最多显示 20 条，超过 20 条会丢弃。
- 每条资讯包含标题、日期、简短摘要和可点击的原文链接。

## 替换为其他 feed

编辑 `claude-news/app.js` 顶部的 `FEED_URLS` 数组，把需要优先尝试的 RSS、Atom 或新闻页地址放在前面即可。页面会按数组顺序逐个尝试，成功解析第一个可用源后停止。
