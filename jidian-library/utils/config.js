/**
 * 全局配置
 * ------------------------------------------------------------
 * 小程序端**只与自建 Spring Boot 后端通信**，不直连 OneNET。
 *
 * 数据链路：
 *   STM32 + ESP8266 ──MQTT──▶ OneNET ──HTTP 推送──▶ Spring Boot ──▶ MySQL
 *                                                          ▲
 *                                                          │ HTTPS
 *                                                     微信小程序
 *
 * 也就是说 OneNET 只承担「设备 → 后端」这一段；小程序侧拿到的一切数据
 * （座位状态、预约、用户资料、校园卡绑定）都来自后端接口。
 *
 * 接口清单见仓库文档《01-小程序说明》第 7 节「接口契约」，路径：
 *   docs/jidian-library/01-小程序说明.md（相对仓库根 library-seat-full/）
 * 开发约束与踩坑见同目录《04-小程序开发注意事项》。
 * 注意这些文档在**小程序包之外**，不会随小程序上传，仅作开发/答辩参考。
 */
module.exports = {
  /* ============================================================
   * 后端服务地址
   * ------------------------------------------------------------
   * ← 部署好 Spring Boot 之后，把它的地址填到 baseUrl：
   *    本机调试      'http://localhost:8080'            ← 当前使用
   *    局域网真机    'http://192.168.1.10:8080'
   *    内网穿透      'https://<你的>.r27.cpolar.top'      ← 变了只改这一行
   *    线上          'https://api.example.com'
   *
   * 只需改这一行：HTTP 走 utils/request.js、实时推送走 utils/ws.js，
   * 后者用 base.replace(/^http/, 'ws') 从同一个值推导，
   * 所以 https → wss、http → ws 不用另配。
   *
   * 地址留空时小程序不会发起任何请求，页面直接提示
   * 「尚未配置后端地址」，不显示任何本地假数据。
   *
   * ⚠️ 关于真机和域名白名单（2026-09-28 实测确认）：
   *   · 微信后台的「request / socket 合法域名」**只接受 HTTPS 且已备案的域名**，
   *     cpolar.top 不是本馆备案的域名，**配不进去**，这条路走不通；
   *   · 所以真机只能靠"开发调试"绕过校验，且要两步：
   *       1) 开发者工具「详情 → 本地设置」勾选
   *          「不校验合法域名、web-view（业务域名）、TLS 版本以及 HTTPS 证书」；
   *       2) **手机上还要再开一次**：打开小程序 → 右上角「···」→ 开发调试 → 打开。
   *          只做第 1 步，真机会报「url not in domain list」。
   *   · 用 https/wss 而不是 http/ws：socket 的合法域名只认 wss，
   *     而且真机对明文的容忍度更低，走 https 少一层风险。
   *
   * ⚠️ cpolar 免费版的随机域名约 24 小时轮换、客户端重启就会变。
   *   地址一变，**只改下面这一行**即可；想省事就在 cpolar 控制台用
   *   「预留 → 保留二级子域名」把它固定下来（免费套餐是否包含，以控制台为准）。
   * ========================================================== */
  server: {
    baseUrl: 'http://7050882c.r26.cpolar.top',
    /** 请求超时（毫秒）。经公网穿透比本机慢，给宽一点 */
    timeout: 15000,
    /** 本地缓存 token 用的 key */
    tokenKey: 'jidian_token',

    /* --------------------------------------------------------
     * 实时推送（规范 §18 / §46）
     * ------------------------------------------------------
     * socket 地址由 baseUrl 推导：https → wss、http → ws，
     * 所以只需要改 baseUrl 一处。
     *
     * 握手时把 JWT 放在查询参数 token= 上 —— 小程序和浏览器的
     * WebSocket 都不能自定义请求头，后端 TokenHandshakeInterceptor
     * 在握手期校验，无效就直接 403，客户端连 open 都收不到。
     * ------------------------------------------------------ */
    wsPath: '/ws/seats',
    /** 断线重连：第一次等 2 秒，之后翻倍，封顶 20 秒 */
    wsReconnectMinMs: 2000,
    wsReconnectMaxMs: 20000
  },

  /**
   * 调试日志（新增）
   * ------------------------------------------------------
   * 交付版保持 false：utils/ws.js 里的 5 处异常日志全部走 debugLog()，
   * 开关关闭时一行都不会往控制台打。
   * 联调 WebSocket 连不上、消息解析失败这类问题时改成 true 即可，
   * 不用改代码。
   */
  debug: {
    log: false
  },

  /**
   * 后端接口路径（与《实际编码规范》第十三节统一 API 总表一致）
   * 单独列出来便于与后端对齐；改路径只改这里。
   */
  api: {
    register: '/api/auth/register',
    login: '/api/auth/login',
    userMe: '/api/users/me',
    seats: '/api/seats',
    reservations: '/api/reservations',
    reservationCurrent: '/api/reservations/current'
  },

  /** 应用信息 */
  appInfo: {
    name: '机电图书馆',
    subName: '座位预约与学习管理',
    version: '2.0.0'
  },

  /**
   * 业务常量（与规范第二十四、二十五节一致）
   * 超时释放与设备离线判定都由后端定时任务执行，小程序端只做展示。
   */
  business: {
    /** 预约后未签到的超时释放时长（分钟）—— RESERVED → TIMEOUT → FREE */
    reserveExpireMin: 15,
    /** 暂离未回座的超时释放时长（分钟）—— AWAY → COMPLETED → FREE */
    awayExpireMin: 30,
    /** 设备离线判定时长（秒） */
    offlineTimeoutSec: 120,
    /** 昵称为空时展示的默认名（注册时昵称是选填项，可以不填） */
    defaultNickname: '同学'
  },

  /**
   * 座位业务状态（规范 5.1）
   * 与数据库 seat.status 的 TINYINT 取值一一对应；
   * 颜色按规范第 39 / 40 节：空闲绿、已预约黄、使用中红、暂离灰、异常红闪。
   */
  seatStatus: [
    { code: 0, key: 'free', label: '空闲', color: '#22C08A' },
    { code: 1, key: 'reserved', label: '已预约', color: '#F5A623' },
    { code: 2, key: 'using', label: '使用中', color: '#F0554B' },
    { code: 3, key: 'away', label: '暂离', color: '#9AA4B8' },
    { code: 4, key: 'alarm', label: '异常', color: '#F0554B' }
  ],

  /** 预约状态（规范 5.2）与数据库 reservation.status 的字符串取值一一对应 */
  reservationStatus: [
    { key: 'RESERVED', label: '待签到', tag: 'tag-warning' },
    { key: 'USING', label: '使用中', tag: 'tag-danger' },
    { key: 'AWAY', label: '暂离', tag: 'tag-grey' },
    { key: 'COMPLETED', label: '已完成', tag: 'tag-success' },
    { key: 'CANCELLED', label: '已取消', tag: 'tag-grey' },
    { key: 'TIMEOUT', label: '已超时', tag: 'tag-danger' }
  ]
}
