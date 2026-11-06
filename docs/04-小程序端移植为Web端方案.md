# 04 · 小程序端移植为 Web 端方案（原生 HTML + JS）

> ⚠️ **这是历史方案，已在另一份副本上实施完毕（2026-09-30 补注）**
>
> 本仓库 `library-seat-full` 至今**仍是小程序版**，并没有照本文改造成 Web 端 ——
> 这份手册当初是给**另一份副本 `trae/my`** 写的，那边已经按它改完了
> （产物是 `library-jidian-web/`，原生 HTML + CSS + JS）。
> 保留本文的理由：它对**小程序特有的 20 种微信 API、52 处调用点**做了完整清点，
> 论文里写「前端技术选型与移植成本」时可以直接引用。
>
> 另外，文中 §8「登录方案」当初列的三条路已经**尘埃落定**：
> 最终选的是**方案 A（学生账号密码）**，而且不只是 Web 端 —— **小程序端也一起改了**
> （现在是学号 + 密码 + 自助注册，`POST /api/auth/login` / `register`，微信登录已整体删除）。
> 因此文中所有围绕 `wechat-login` / `openid` 的描述都属于**改造前的状态**，不要照它改代码。

> 本文是**实施手册**，不是可行性分析。目标是把 `jidian-library/` 这个微信小程序端，
> 改造成一个**不依赖任何前端框架、原生 HTML + CSS + JS** 的 Web 端，功能对齐，
> 并且**后端一行都不用改**。
>
> 阅读顺序：先看 §1 的结论，再按 §5 的文件映射和 §6→§9 逐层动手，
> 每完成一个阶段就对照 §11 的里程碑验收。

---

## 1. 结论与工作量

**可行，而且是这个项目里比较划算的一条路。**

原工程具备"不用框架也能写得干净"的前提：

- **逻辑层本来就是零框架的**。`utils/config.js` 与 `utils/api.js` **一处 `wx.*` 都没有**
  （`api.js` 里唯一出现 `wx` 的地方是一句注释），就是普通 JS 模块，换个挂载方式即可复用；
- **视图极简**：9 个 WXML 里只有 8 种标签，**没有 canvas / swiper / map / picker**；
- **不用构建工具**：交付物是静态站点，挂任何静态托管都能跑，答辩时"双击就能演示"。

| 阶段 | 内容 | 工作量 |
| --- | --- | --- |
| 0 | 工程骨架 + 逻辑层搬迁（`config` / `api` / `request` / `ws` / `util`） | 0.5 天 |
| 1 | 登录页 + 首页 | 0.5 天 |
| 2 | 座位页 + 预约成功 | 1 天 |
| 3 | 我的预约 + 签到/离座 | 1 天 |
| 4 | 我的 + 意见反馈 | 0.5 天 |
| 5 | 同源部署 + 实时推送联调 | 0.5 天 |
| — | **合计** | **约 4~5 个工作日** |

---

## 2. 移植面量化（动手前的账）

这些数字是扫出来的，不是估的，用来判断"到底要改多少"：

| 项 | 数量 |
| --- | --- |
| 微信 API 种类 / 调用点 | **20 种 / 52 处**（页面 + `app.js` 占 41 处，`utils/` 只占 11 处） |
| JS 规模 | 15 个文件、2510 行 |
| 视图规模 | 9 个 WXML（38 KB）、11 个 WXSS（45 KB） |
| 模板条件分支 | `wx:if` 56 + `wx:elif` 13 + `wx:else` 14 = **83 个** |
| 循环 | `wx:for` 16 个（12 个带 `wx:key`） |
| 事件绑定 | `bindtap` 56 + `catchtap` 11 = **67 个** |
| 自定义数据属性 | `data-*` 19 个 |
| 样式里的 `rpx` | **846 处** |
| 页面导航调用 | `navigateTo` 10 / `switchTab` 7 / `navigateBack` 5 / `redirectTo` 3 / `reLaunch` 1 = 26 处 |
| 下拉刷新 | 4 个页面（首页 / 座位 / 我的预约 / 我的） |

**结论**：真正的新增工作集中在"视图渲染 + 事件绑定 + 样式换算"这三块，
逻辑层的搬运量很小。

---

## 3. 架构选择：多页面（MPA），不要写成 SPA

8 个页面有两种写法，**必须选前者**：

| 做法 | 说明 | 评价 |
| --- | --- | --- |
| **每个页面一个 HTML** | `location.href` 跳转，整页刷新 | ✅ **推荐**。心智模型和现在的"页面栈 + 各自 onLoad/onShow"几乎一一对应 |
| 单 HTML + 手写 hash 路由 + 手写渲染 | 要自己写路由、模板渲染、依赖更新、状态同步 | ❌ 不要。**这是在重新发明 Vue**，写到第 5 个页面就会失控 |

选 MPA 还有两个顺带的好处：

1. 每个页面是**真实文件**，部署时不需要配 SPA 的 history fallback；
2. `file://` 直接打开也能用（前提是不用 ES module，见 §12.7）。

---

## 4. 目标目录结构

```
student-web/
├── login.html                   登录
├── index.html                   首页
├── seats.html                   座位页
├── reserve-success.html         预约成功 / 详情（同页两种形态）
├── my-reserve.html              我的预约
├── signin.html                  签到 / 离座
├── mine.html                    我的
├── feedback.html                意见反馈
├── images/                      ← 11 张 PNG 原样搬（tabBar 6 张 + 卡片图标 5 张）
├── css/
│   ├── base.css                 ← 来自 app.wxss
│   ├── nav.css                  ← 来自 styles/nav.wxss
│   ├── components.css           ← 空状态组件（原 components/empty/）
│   └── page-<name>.css          ← 每个页面的 wxss
└── js/
    ├── config.js                ← 从 utils/config.js 搬，几乎不动
    ├── api.js                   ← 从 utils/api.js 搬，几乎不动
    ├── request.js               ← 重写网络层（fetch）
    ├── ws.js                    ← 改写连接层（WebSocket）
    ├── util.js                  ← 纯函数照搬 + 4 个交互换成 DOM
    ├── ui.js                    ← 新增：toast / loading / confirm 的 DOM 实现
    ├── escape.js                ← 新增：escapeHtml()，见 §12.1（**必须**）
    └── page-<name>.js           ← 每个页面的渲染与事件（要新写的部分）
```

---

## 5. 文件级映射表

| 小程序 | Web 对应 | 改动量 |
| --- | --- | --- |
| `utils/config.js` | `js/config.js` | **几乎为零**，见 §6.1 |
| `utils/api.js` | `js/api.js` | **几乎为零**，见 §6.2 |
| `utils/request.js` | `js/request.js` | 重写（约 80 行），见 §6.3 |
| `utils/ws.js` | `js/ws.js` | 改写连接部分（约 40 行），见 §6.4 |
| `utils/util.js` | `js/util.js` + `js/ui.js` | 8 个纯函数照搬，4 个交互重写，见 §6.5 |
| `utils/` 里没有的 | `js/escape.js` | **新增**，防 XSS |
| `app.js` | 不存在 | 大部分能力（globalData / 登录态）改用本地缓存，见 §9 |
| `app.json` 的 `pages` | 各 HTML 文件 | —— |
| `app.json` 的 `tabBar` | 各页共用的底部导航（`nav.js` 注入或复制一份） | 见 §7.6 |
| `app.wxss` | `css/base.css` | 需要 rpx 换算 |
| `styles/nav.wxss` | `css/nav.css` | 可以整个删掉，见 §7.5 |
| `pages/<name>/index.wxml` | `<name>.html` | 重写 |
| `pages/<name>/index.wxss` | `css/page-<name>.css` | rpx 换算 |
| `pages/<name>/index.js` | `js/page-<name>.js` | 重写渲染与事件 |
| `pages/<name>/index.json` | 不存在 | 配置项落到 HTML 里，见 §7.5 |
| `components/empty/` | `css/components.css` + 一个渲染函数 | 见 §7.4 |
| `images/` | `images/` | **原样搬** |

---

## 6. 逐层改造方法

### 6.1 `config.js` —— 直接搬

五个 `utils` 里唯一**完全不用改**的文件。它只导出配置对象，零 `wx.*`、零 DOM 依赖。

唯一的动作是**换挂载方式**（两种任选）：

```js
// 方式一：经典 script，挂到全局（file:// 也能用）
window.CONFIG = { /* 原 module.exports 的内容 */ }

// 方式二：ES module（需要起静态服务器）
export default { /* ... */ }
```

> ⚠️ `server.baseUrl` 在 Web 端建议**留空或写成相对路径**，配合"同源部署"（§10）用。
> 如果填死的绝对地址，换穿透域名时要来改这里 —— 同源部署能让你**彻底不用改**。

### 6.2 `api.js` —— 几乎直接搬

**零 `wx.*` 依赖**（唯一一个 `wx` 出现在注释里）。它做了三件很值钱的事，全都与平台无关：

1. 接口语义化（`getSeats` / `createReservation` / `leaveSeat` …）；
2. 字段归一化（snake_case → 驼峰、`seat_code` 解析行列、阅览室归并、时间格式处理）；
3. 状态文案与配色（`seatStatusLabel` / `seatStatusColor` / `reserveStatusTag`）。

动作同样只是换挂载方式，并把 `require('./config')` / `require('./request')` 改成
经典 script 下的全局引用或 ES module 的 `import`。

> **这一条是整个移植里最省的地方**：`api.js` 300 多行、`groupRooms` / `getSeatMap` /
> `countStats` 这些聚合逻辑全部照搬，页面里只负责把结果画成 HTML。

### 6.3 `request.js` —— 重写成 `fetch`

原来 4 处 `wx.*`：`wx.request` + `wx.getStorageSync` / `setStorageSync` / `removeStorageSync`。
**错误码翻译逻辑一个字都不用改**（401 清 token、`body.code` 与 HTTP 状态码两条判断线、
裸数据兼容），只换底层。

```js
/* ---- token：wx.getStorageSync → localStorage，1:1 对应 ---- */
const KEY = (window.CONFIG.server && window.CONFIG.server.tokenKey) || 'jidian_token'
const getToken   = () => localStorage.getItem(KEY) || ''
const setToken   = (t) => { if (t) localStorage.setItem(KEY, t) }
const clearToken = () => localStorage.removeItem(KEY)

/* ---- 请求：wx.request → fetch ---- */
async function send(method, path, data) {
  if (!isConfigured()) throw makeError('unconfigured', '尚未配置后端地址', 'unconfigured')

  const token = getToken()
  const ctrl = new AbortController()
  // fetch 没有 timeout 参数，必须自己用 AbortController 实现
  const timer = setTimeout(() => ctrl.abort(), (window.CONFIG.server.timeout) || 15000)

  let res
  try {
    res = await fetch(baseUrl() + path, {
      method,
      signal: ctrl.signal,
      headers: Object.assign(
        { 'Content-Type': 'application/json' },
        token ? { Authorization: 'Bearer ' + token } : {}
      ),
      body: method === 'GET' ? undefined : JSON.stringify(data || {})
    })
  } catch (e) {
    if (e && e.name === 'AbortError') throw makeError('timeout', '请求超时，请检查网络或后端服务', 'timeout')
    throw makeError('network', '网络不可达：请确认后端已启动、地址正确', 'network')
  } finally {
    clearTimeout(timer)
  }

  const body = await res.json().catch(() => ({}))

  if (res.status === 401) { clearToken(); throw makeError(401, '登录已失效，请重新登录', 'unauthorized') }
  if (res.status < 200 || res.status >= 300) {
    throw makeError(res.status, body.message || STATUS_TEXT[res.status] || ('HTTP ' + res.status), 'http')
  }
  if (typeof body.code !== 'undefined') {
    if (body.code === 200) return body.data
    throw makeError(body.code, body.message || STATUS_TEXT[body.code])
  }
  return body            // 兼容后端返回裸数据
}
```

**两个 Web 特有的注意点：**

1. **`fetch` 没有超时**，必须用 `AbortController`，否则后端挂了页面会一直转圈；
2. **会触发 CORS 预检**：因为带了 `Authorization` 头 + `Content-Type: application/json`，
   浏览器会先发一个 `OPTIONS`。后端 `WebMvcConfig` 已经配了
   `allowedMethods(... "OPTIONS")` 和 `allowedHeaders("*")`，**现在是通的**。
   但如果哪天把 `allowedMethods` 收紧，所有带 token 的请求会**集体失败**（预检被拒，
   连 401 都看不到）—— 这一点要写进后端的"不要改的配置"。

### 6.4 `ws.js` —— 改写连接层

**协议层一字不改**：地址仍由 `baseUrl` 推导（`https→wss` / `http→ws`）、token 仍放查询参数
（浏览器的 WebSocket **同样不能自定义请求头**，这个限制和小程序一模一样）、
仍只认三种消息、订阅/退订接口不变、指数重连策略不变。

```js
/* ---- 小程序 ---- */
const task = wx.connectSocket({ url, timeout: 8000 })
task.onOpen(() => { connected = true })
task.onMessage(res => dispatch(res && res.data))
task.onClose(() => { if (!closedByUs) scheduleRetry() })
task.onError(err => debugLog('warn', err))
task.close({ code: 1000, reason: 'client close' })

/* ---- Web ---- */
let socket = new WebSocket(url)
socket.onopen    = () => { connected = true }
socket.onmessage = (ev) => dispatch(ev.data)          // ← 参数形态不同
socket.onclose   = () => { connected = false; if (!closedByUs) scheduleRetry() }
socket.onerror   = (ev) => debugLog('warn', ev)
socket.close(1000, 'client close')                    // ← 参数形态不同
```

需要留意的差异只有两处：`onMessage` 的回调参数从 `{ data }` 变成事件对象、
`close()` 的参数从对象变成两个位置参数。**其余 90% 的代码（订阅表、重连计数、
`ensure()` 幂等、`socketUrl()` 推导、消息归一化）直接复用。**

### 6.5 `util.js` —— 拆成两半

| 原方法 | 去向 |
| --- | --- |
| `weekdayCN` `nowText` `toTimestamp` `diffMinutes` `minutesText` `countdownText` `clockOf` `dayLabel` | **照搬**，纯函数 |
| `toast` | → `ui.js`，用 DOM 实现 |
| `loading` / `hideLoading` | → `ui.js`，用一个遮罩 div |
| `confirm` | → `ui.js`，用 `<dialog>` 或自写模态，**保持返回 `Promise<boolean>` 的签名** |

`confirm` 保持签名不变有个好处：`signin.html` 那些"确认离座？"的调用点可以原样搬。

---

## 7. 视图层移植规则

### 7.1 标签映射表

| WXML | HTML | 备注 |
| --- | --- | --- |
| `<view>` | `<div>` | 445 处，最基础 |
| `<text>` | `<span>` | 79 处 |
| `<block>` | **直接摊平** | 19 处。它只是模板容器，不产生节点 |
| `<image src mode="aspectFit">` | `<img>` + `object-fit: contain` | 11 处 |
| `<input>` | `<input>` | 3 处。`bindinput` → `input` 事件，取值从 `e.detail.value` 变 `e.target.value` |
| `<textarea>` | `<textarea>` | 1 处，同上 |
| `<button>` | `<button>` | 2 处 |
| `<scroll-view scroll-y>` | `<div style="overflow-y:auto">` | 4 处 |

### 7.2 `{{ }}` 数据绑定 —— 三种渲染策略

按复杂度选，不要一把梭：

1. **静态内容**：直接写死在 HTML 里。首页大量文案是静态的，这一部分**零成本**；
2. **列表 / 条件块**：用 `innerHTML` + 模板字符串（占绝大多数）。
   **此时必须用 `escapeHtml()` 包住每一个插值**，见 §12.1；
3. **单个数值**：`el.textContent = value`，不碰 HTML，天然安全。

推荐每个页面写成这样一个结构：

```js
// js/page-seats.js
const api = window.API
let state = { room: null, seats: [], stats: null }

function render() {
  document.getElementById('stat-free').textContent = state.stats.free      // 安全
  document.getElementById('grid').innerHTML = state.seats.map(cellHtml).join('')  // 列表
}

function cellHtml(s) {
  const cls = ['seat', 'seat-' + api.seatStatusKey(s.status), s.online ? '' : 'off'].join(' ')
  return `<div class="${cls}" data-id="${s.id}">
            <span>${escapeHtml(s.seatNo)}</span>
            ${s.online ? '' : '<i class="badge-off">离线</i>'}
          </div>`
}
```

### 7.3 模板指令映射

| WXML | Web |
| --- | --- |
| `wx:if` / `wx:elif` / `wx:else`（共 83 处） | `if / else if / else`，或 `.map()` 里返回空串 |
| `wx:for` + `wx:key`（16 处） | `Array.prototype.map()` |
| `bindtap="fn"`（56 处） | 事件委托：在容器上绑一个 `click`，用 `e.target.closest('[data-id]')` 找目标 |
| `catchtap`（11 处） | `e.stopPropagation()` |
| `data-xxx="..."`（19 处） | `data-xxx` + `dataset.xxx`（**写法一样**） |
| `hidden`（1 处） | `style.display = 'none'` 或 `hidden` 属性 |

**事件委托是个关键技巧**：67 个事件绑定如果每个都 `addEventListener`，
列表重绘后要重新绑；用**一个容器上的委托**，重绘多少次都不用管。
座位图的 30 个格子、我的预约的卡片列表都适合这么写。

### 7.4 空状态组件

`components/empty/` 是个四件套，Web 里不需要组件体系，写成两个函数即可：

```js
function emptyHtml(text) { return `<div class="empty"><img src="images/empty.png"><p>${escapeHtml(text)}</p></div>` }
function toggleEmpty(boxEl, show, text) { boxEl.innerHTML = show ? emptyHtml(text) : '' }
```

### 7.5 页面配置与自定义导航栏

8 个页面的 `index.json` **全部**是 `"navigationStyle": "custom"` ——
也就是说你现在是自己画导航栏的（`app.js` 里取 `statusBarHeight` 就是为了它）。

**Web 端这一整块可以删掉**：

- 浏览器没有状态栏，不需要 `statusBarHeight`；
- 每个 HTML 里直接用普通 `<header>` 做标题栏，或者干脆用 `<h1>`；
- `styles/nav.wxss`（28 行）**整个不搬**；
- `enablePullDownRefresh` 与 `stopPullDownRefresh`（4 个页面）→ **换成页面右上角一个"刷新"按钮**，
  不用硬做下拉手势。

### 7.6 底部 tabBar

小程序的 tabBar 是原生组件，多页面方案里要在每个 tab 页面各写一份。
建议写一个 `js/nav.js` 注入，避免复制粘贴：

```js
// js/nav.js —— 在每个 tab 页面底部调用
function renderTabBar(active) {
  const items = [
    { key: 'index',      text: '首页',     href: 'index.html',      icon: 'images/tab_home.png',     on: 'images/tab_home_on.png' },
    { key: 'my-reserve', text: '我的预约', href: 'my-reserve.html', icon: 'images/tab_calendar.png', on: 'images/tab_calendar_on.png' },
    { key: 'mine',       text: '我的',     href: 'mine.html',       icon: 'images/tab_user.png',     on: 'images/tab_user_on.png' }
  ]
  document.body.insertAdjacentHTML('beforeend',
    '<nav class="tabbar">' + items.map(it => `
      <a class="tab ${it.key === active ? 'on' : ''}" href="${it.href}">
        <img src="${it.key === active ? it.on : it.icon}"><span>${it.text}</span>
      </a>`).join('') + '</nav>')
}
```

只有 **3 个 tab**，其余 5 个页面用普通链接跳转。

### 7.7 样式：846 处 `rpx` 怎么换算

小程序里 `750rpx = 屏幕宽`。三种方案：

| 方案 | 做法 | 评价 |
| --- | --- | --- |
| **直接除 2 转 px** | 750rpx → 375px 逻辑宽，即 `px = rpx / 2` | ✅ **推荐**。一次性脚本扫过去，桌面浏览器正好是手机宽度的居中卡片 |
| `calc(100vw / 750 * N)` | 真·响应式 | 太啰嗦，846 处改下来不现实 |
| `rem` + 动态根字号 | 设 `html{font-size: calc(100vw/7.5)}`，`1rem=100rpx` | 需要把 rpx 值除以 100，也是批量脚本 |

用第一种，一条命令过一遍（先备份）：

```bash
# 示意：把 CSS 里所有 "16rpx" 这类值除以 2
python -c "
import re,glob
for f in glob.glob('css/**/*.css', recursive=True):
    s=open(f,encoding='utf-8').read()
    s=re.sub(r'([0-9.]+)rpx', lambda m: ('%.4g' % (float(m.group(1))/2))+'px', s)
    open(f,'w',encoding='utf-8').write(s)
"
```

再给 `body` 加一个桌面友好的容器（原设计是手机竖屏）：

```css
body { background:#F4F7FC; }                      /* 颜色来自 app.json 的 backgroundColor */
#app { max-width: 420px; margin: 0 auto; background:#fff; min-height: 100vh; }
```

### 7.8 页面生命周期与导航映射

| 小程序 | Web |
| --- | --- |
| `onLoad(options)` | 页面脚本直接执行 / `DOMContentLoaded`；参数用 `new URLSearchParams(location.search)` |
| `onShow()` | 多页面方案里**每次进入天然是全新加载**，不需要额外处理 |
| `onUnload()` | 整页跳转时旧页面被浏览器销毁，**WebSocket 会自动关闭**，不必手动退订 |
| `wx.navigateTo({url})` | `location.href = url` |
| `wx.redirectTo` | `location.replace(url)` |
| `wx.navigateBack()` | `history.back()` |
| `wx.reLaunch({url})` | `location.replace(url)` |
| `wx.switchTab({url})` | `location.href = url`（tab 页面就是普通 HTML） |
| `onPullDownRefresh` + `stopPullDownRefresh` | 一个"刷新"按钮，调同一个 `load()` |

> ⚠️ **唯一需要在 Web 端主动关连接的地方是"退出登录"**（`ws.close()`），
> 因为那时页面没销毁，旧 token 的连接还挂着。

### 7.9 页面参数与页面间传值

| 小程序 | Web |
| --- | --- |
| `?id=1001` | **完全一样**，`location.search` |
| `from=detail`（预约成功页两种形态） | 完全一样 |
| `app.globalData.reserveFilter`（我的预约页的筛选条件） | 跳转前写进 `sessionStorage`，目标页读取后清掉 |

---

## 8. 登录方案（唯一需要改后端的点）

这是整个移植里**唯一的架构性障碍**：小程序的身份标识是 `openid`，
而 `openid` 只能由 `wx.login` 拿到的 `code` 换出来 —— **浏览器做不到这件事**。

后端目前也只有一个登录入口：`POST /api/auth/wechat-login`（免 JWT）。三条路：

### 方案 A：加一套学生账号密码登录（**推荐**）

- **后端**：`user` 表加 `student_no` + `password`（BCrypt）两列；
  新增 `POST /api/auth/login`（与 `wechat-login` 一起加进 `WebMvcConfig` 的 exclude）；
  返回体与 `wechat-login` **完全一致**（`token` / `user_id` / `nickname`），前端零适配。
- **前端**：`login.html` 两个输入框 + 一个按钮。
- **论文叙事**：从"只有微信登录"升级为"双端统一账号体系（小程序走微信、Web 走学号密码）"，
  这是**加分项**。
- **工作量**：0.5~1 天。

### 方案 B：扫码登录（答辩最好看，但最费）

- **后端**：新增 `qrcode_session` 表（ticket / 状态 / userId / 过期时间）+ 三个接口
  （生成 ticket、轮询状态、小程序侧确认）。
- **小程序**：加一个"扫码确认"页面。
- **前端**：显示二维码 + 轮询。
- **工作量**：2~3 天。

### 方案 C：演示后门（只用于过渡，别交付）

- 后端加一个仅在 dev profile 生效的 `dev-login`，或用预置 openid 直接签发 token。
- **工作量**：0.2 天。

### ⚡ 过渡技巧：先用管理员 token 把前端跑通

**关键事实**：后端设计上允许**管理员 token 访问所有学生接口**（它读到的座位列表和学生
看到的完全一样，不涉及越权）。所以：

> **在第 0~4 阶段，你可以完全不动后端** —— 用 `admin / Admin@123` 调
> `POST /api/admin/login` 拿一个 JWT 塞进本地缓存，学生端 Web 就能跑通全流程。
> 等页面都调好了，最后再补方案 A 的学生登录。

这样能把"改后端"这件事**推到最后一刻**，避免前后端同时改导致问题互相掩盖。

---

## 9. `app.js` 的能力往哪搬

原来 `app.js` 提供三样东西，Web 里分别处理：

| 原能力 | Web 方案 |
| --- | --- |
| `globalData.userInfo` | 本地缓存（`sessionStorage` 或 `localStorage` 的一个 `userInfo` 键） |
| `globalData.statusBarHeight` | **删掉**（自定义导航栏在 Web 不需要） |
| `isLogin()` / `clearSession()` / `toLogin()` | 写成 `js/auth.js` 里的三个小函数，每个页面顶部引一下 |
| `displayName(userInfo)`（昵称为空显示"微信用户"） | 照搬。**但注意 Web 端建议改成"同学"之类**，因为学生不再一定是微信用户 |

---

## 10. 部署与内网穿透（同源方案）

**把前端和后端放在同一个端口**，穿透只需要**一条隧道**：

```
把构建好的 student-web/ 整个丢进
  library-seat/src/main/resources/static/
```

Spring Boot **会自动在 `/` 提供它，一行代码都不用写**。
（当前后端既没有 `static/` 目录、也没有任何 resource handler，但那正是 Boot 的默认约定；
而且 JWT 拦截器只挂 `/api/**`，静态文件不会被拦。）

然后**一条隧道指向 8080**，同时满足三件事：

| 用途 | 路径 |
| --- | --- |
| 别人打开页面 | `/` |
| 页面调接口 | `/api/**` |
| OneNET 推送设备数据 | `/api/onenet/**` |
| 实时推送 | `/ws/seats` |

**全部同源** → 没有跨域、WebSocket 不用单独配域名、没有 mixed content 风险。
所以前端里所有地址都写成**相对路径**：

```js
// 同源部署时，config.server.baseUrl 留空，api.js 里的路径本身就是 /api/...
// request.js 的 baseUrl() 返回空串，拼出来就是 /api/seats —— 天然同源
server: { baseUrl: '', timeout: 15000, tokenKey: 'jidian_token' }
```

**这样换穿透域名时，只需要改 OneNET 的推送 URL 一处**（前端、后端都不用动）。

> 开发阶段仍然可以起一个静态服务器（`python -m http.server 5173`）方便热改，
> 但那时 `baseUrl` 要指到 `http://localhost:8080`，并且依赖后端的 CORS（已放开）。
> **只有演示/上线才拷进 `static/`。**

### 换成 Web 端后"消失"的配置

| 项 | 小程序 | Web 端 |
| --- | --- | --- |
| 微信后台 request 合法域名 | 必须配，只收已备案 HTTPS | **不存在** |
| 微信后台 socket 合法域名 | 必须配（第二个输入框，最容易漏） | **不存在** |
| 微信 IP 白名单（40164） | 必须加后端出口 IP，**换网络就失效** | 换掉微信登录后**不需要** |
| 手机端「开发调试」开关 | 必须开，否则 `url not in domain list` | **不存在** |
| 能否用 cpolar 域名 | **配不进白名单**，只能靠开发调试绕过 | ✅ 浏览器不挑域名，**直接能用** |

---

## 11. 分阶段实施步骤（每步都有验收标准）

### 阶段 0：骨架 + 逻辑层搬迁（0.5 天）

1. 建 `student-web/` 目录，把 `images/` 整包拷过来；
2. 搬 `config.js` / `api.js`（改挂载方式），重写 `request.js` / `ws.js`，
   拆 `util.js`；
3. 写 `escape.js`、`ui.js`。

**验收**：起静态服务器 + 后端，在浏览器控制台执行
`API.getSeats().then(console.log)` → 打印出 `[{seatCode:'A4-203', row:2, col:3, ...}]`。

### 阶段 1：登录 + 首页（0.5 天）

**验收**：打开 `index.html` 能看到阅览室名称与座位数统计（先用管理员 token 过渡，见 §8）。

### 阶段 2：座位页 + 预约成功（1 天）

**验收**：座位图正确排版（`A4-203` 应落在第 2 行第 3 列），点空闲座位能真的预约成功，
`reserve-success.html` 显示预约号与倒计时。

### 阶段 3：我的预约 + 签到/离座（1 天）

**验收**：5 个筛选 tab 都能用；签到页四种状态（待签到 / 使用中 / 暂离 / 终态）分支正确；
暂离 → 回座 → 离座三个动作都能改后端状态。

### 阶段 4：我的 + 意见反馈（0.5 天）

**验收**：改昵称能生效并落库；校园卡号未绑定时显示"暂无"；反馈页静态表单不发请求。

### 阶段 5：同源部署 + 实时推送（0.5 天）

**验收**：把 `student-web/` 拷进 `static/`，一条隧道访问；用 Postman 伪造一条设备上报，
**页面上对应座位自动变色**（证明 WebSocket 通了）。

---

## 12. 风险与注意事项

### 12.1 ⚠️ XSS —— 这是最容易翻车的一条

小程序的模板引擎**自动转义**，手拼 HTML 就**不会**了。而"我的"页的**昵称是用户可改的**，
反馈页还有用户输入。必须在所有插值处用它：

```js
// js/escape.js
function escapeHtml(s) {
  return String(s == null ? '' : s).replace(/[&<>"']/g, (c) => (
    { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]
  ))
}
```

**规则**：只要是用模板字符串拼 HTML，里面的**每一个** `${}` 都要包 `escapeHtml()`；
能用 `textContent` 的地方优先用 `textContent`（天然安全）。

### 12.2 mixed content

页面是 `https://xxx.cpolar.top` 而 API 打到 `http://...`，浏览器会直接拦。
**同源方案（§10）自动规避**，这是选它的主要理由之一。

### 12.3 带宽只有 1 Mbps

cpolar 免费版就这个带宽。**别引 Element Plus / ECharts 这类大库**——
这也反过来支持"原生 HTML/JS"这个选择（`library-admin` 的 CSS 有 362 KB，
学生端不该有这种体积）。

### 12.4 token 的存储位置

小程序 storage 没有第三方脚本注入的问题，浏览器有。
建议：token 有效期别设太长（后端 `JWT_EXPIRE_MINUTES` 现在是 720 分钟），
有条件的话换成 httpOnly Cookie。**这一点值得写进论文的安全设计一节。**

### 12.5 每个页面各连一条 WebSocket

多页面方案里跳页会重连一次（小程序是全局一条）。功能上没影响，
但建议**只让首页 / 座位页 / 我的预约页订阅** —— 正好和现在的实现一致（签到页本来就不订阅，
它每 3 秒轮询）。

### 12.6 `fetch` 没有超时

必须用 `AbortController`（`request.js` 的示例已经写了）。
忘了这一步的症状是：后端没起时页面一直转圈，没有任何提示。

### 12.7 别指望 `file://` 直开

- 用 **ES module**（`import`）→ `file://` 会被 CORS 拦；
- `file://` 页面里 `fetch` 到 `http://localhost:8080`，Origin 是 `null`，
  后端那套 `allowedOriginPatterns("*")` **认不认 `null` 没有实测过**。

**结论**：交给别人演示时，用 `python -m http.server`（他机器有 Python）或
VSCode 的 Live Server 打开；或者干脆走 §10 的 `static/` 同源部署。
想彻底摆脱服务器，就全程用**经典 `<script>` + 全局命名空间**（不用 `import`）。

### 12.8 昵称默认值

`config.business.defaultNickname` 现在是 `'微信用户'`。Web 端的学生不一定是微信用户，
建议在 Web 端改成 `'同学'` 或者做变量区分（小程序显示"微信用户"、Web 显示"同学"）。

---

## 13. 附录：速查对照表

| 类别 | 小程序 | Web |
| --- | --- | --- |
| 网络 | `wx.request` | `fetch` + `AbortController` |
| 实时 | `wx.connectSocket` | `new WebSocket` |
| 存储 | `wx.getStorageSync` / `setStorageSync` | `localStorage` |
| 提示 | `wx.showToast` / `showModal` | 自写 DOM（`ui.js`） |
| 系统信息 | `wx.getWindowInfo` | 不需要 |
| 选图 | `wx.chooseMedia` + `wx.previewImage` | `<input type="file">` + 自写预览 |
| 登录 | `wx.login` → code → openid | **见 §8** |
| 导航 | `wx.navigateTo` 等 5 个 | `location.href` / `replace` / `history.back()` |
| 刷新 | `onPullDownRefresh` | 刷新按钮 |
| 模板 | `{{ }}` + `wx:if` / `wx:for` | 模板字符串 + `if` / `map` |
| 样式 | `rpx` | `px`（÷2） |
| 组件 | 自定义组件四件套 | 渲染函数 + CSS 类 |

---

## 14. 相关文档

| 文档 | 关系 |
| --- | --- |
| [01-小程序说明](jidian-library/01-小程序说明.md) | 被移植对象的完整说明（接口契约、状态口径、页面职责） |
| [02-OneNET接入与物模型设计](02-OneNET接入与物模型设计.md) | 设备侧契约，与前端形态无关，Web 端不需要看 |
| [03-答辩要点与演示脚本](03-答辩要点与演示脚本.md) | 移植完成后演示脚本要按新端更新 |
| [library-admin/01-管理端说明](library-admin/01-管理端说明.md) | 已有的另一个 Web 端，可参考其工程组织方式 |
