package com.mimo.findyoudevice

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** 网页解锁会话有效期（滚动续期） */
private const val SESSION_TTL_MS = 6L * 60 * 60 * 1000

/**
 * 主机端局域网 Web 服务（等效 NanoHTTPD 的极简实现）。
 *
 * 由于约定“禁止额外第三方库”，工程未引入 org.nanohttpd，本类以 ServerSocket 提供同名接口与
 * 等价路由——原 NanoHTTPD 的思路是在子类覆写 `serve(session)`；此处提供 `route` 高序函数作为
 * 同样易扩展的路由钩子。若后续引入 NanoHTTPD，可平移路由体而无需改协议/鉴权/报警逻辑。
 *
 * 安全与行为要点：
 *  - 绑定 0.0.0.0:1145，对局域网全部接口监听（客户端 Http 直连即可发现，无需定位权限）；
 *  - GET  /info            免鉴权，返回 {"model":Build.MODEL,"battery":NN}；
 *  - GET  /                解锁页：输入主机密码 POST /verify，成功获得会话 token；
 *  - POST /verify          鉴权（密码/Basic/X-Auth-Token），通过则签发 6h 会话 token；
 *  - GET  /console?t=..    仿客户端的单机控制台（仅本机）：查找/停止/状态轮询，token 有效即可进入；
 *  - POST /find            鉴权后触发查找（默认 15s；lock=1 或主机开启锁定查找=无限；dur=秒覆盖）；
 *  - POST /stop            鉴权后立即停止本机查找（不限触发方）；
 *  - GET  /status?t=..     控制台轮询：running/lock/remaining/battery/lastFind；
 *    鉴权方式：Authorization: Basic base64(admin:明文) 后 SHA-256 比对 / X-Auth-Token=存储哈希或会话 token / 表单 t=token。
 *  - 每次成功触发都把最近查找时间写入 SharedPreferences（KEY_LAST_FIND）。
 */
class WebServer(
    private val context: Context,
    private val port: Int = 1145,
) {

    /** 统一响应结构 */
    data class Response(val code: Int, val mime: String, val body: String)

    /** 一次已解析的 HTTP 请求会话 */
    data class Session(
        val method: String,
        val rawUri: String,
        val headers: Map<String, String>,
        val bodyText: String,
    ) {
        val path: String get() = rawUri.substringBefore('?')

        /** case-insensitive 头读取 */
        fun header(name: String): String? = headers.entries
            .firstOrNull { it.key.equals(name, true) }?.value

        /** 简单 URLForm 查询参数读取（不 URL 解码数字以外的情况足够） */
        fun query(name: String): String? =
            rawUri.substringAfter('?', "")
                .takeIf { it.isNotBlank() }
                ?.split('&')
                ?.mapNotNull { seg ->
                    val kv = seg.split('=', limit = 2)
                    if (kv.size == 2 && kv[0] == name) kv[1] else null
                }
                ?.firstOrNull()
    }

    private val running = AtomicBoolean(false)
    @Volatile private var serverSocket: ServerSocket? = null

    // 注意：stop() 会对两个池 shutdownNow()；再次 start() 前必须重建（池一旦 shutdown 不可复用，
    // 否则 execute 抛 RejectedExecutionException → 表现为“服务关掉后再也开不起来”）。
    @Volatile private var ioPool: ExecutorService = Executors.newCachedThreadPool()
    @Volatile private var rootActionExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    /** 网页“解锁”签发的会话 token：token → 过期时刻（滚动续期，6h） */
    private val sessions = ConcurrentHashMap<String, Long>()
    private val random = SecureRandom()
    private fun newToken(): String {
        val bytes = ByteArray(18)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
    private fun issueSession(): String {
        val t = newToken()
        sessions[t] = System.currentTimeMillis() + SESSION_TTL_MS
        return t
    }
    /** 校验会话 token（命中则滚动续期）；同时顺带清理过期项 */
    private fun sessionOk(t: String?): Boolean {
        if (t.isNullOrBlank()) return false
        val now = System.currentTimeMillis()
        sessions.entries.removeIf { it.value < now }
        val exp = sessions[t] ?: return false
        sessions[t] = now + SESSION_TTL_MS
        return true
    }

    /** 与会话无关的通用鉴权：密码(明文/Basic) 或 X-Auth-Token(=存储哈希 或 会话token) */
    private fun authorized(s: Session, withSessionToken: Boolean): Boolean {
        val storedHash = Prefs.getPasswordHash(context)
        val tokenCandidate = s.header("X-Auth-Token")?.takeIf { it.isNotBlank() }
        // 1) 会话 token：X-Auth-Token 头 / query t / form body 的 t（控制台 fetch 走 body）
        if (withSessionToken &&
            (sessionOk(tokenCandidate) ||
                sessionOk(s.query("t")) ||
                sessionOk(parseFormField(s.bodyText, "t")))
        ) return true
        // 2) X-Auth-Token == 存储哈希（客户端兼容：直接把密码哈希发来）
        if (tokenCandidate != null && storedHash.isNotBlank() &&
            storedHash.equals(tokenCandidate, ignoreCase = true)
        ) return true
        // 3) 明文密码：Basic(admin:明文) / JSON password / 表单 pass / query pass
        val basicPass = s.header("Authorization")?.takeIf { it.startsWith("Basic", true) }
            ?.substringAfter("Basic", "")
            ?.trim()
            ?.let { decodeBasic(it) }
            ?.second
        val jsonPass = parseJsonPassword(s.bodyText)
        val formPass = parseFormField(s.bodyText, "pass")
        val queryPass = s.query("pass")
        val password = listOfNotNull(basicPass, jsonPass, formPass, queryPass).firstOrNull()
        return password != null && Prefs.verifyPassword(context, password)
    }

    private fun authFail(): Response =
        ResponseText(401, "text/plain; charset=utf-8", "unauthorized")

    val isRunning: Boolean get() = running.get()

    /** 可扩展路由钩子（类似 NanoHTTPD 覆写 serve）。默认实现放 serve 方法中 */
    var route: (Session) -> Response = ::serve

    fun start() {
        if (!running.compareAndSet(false, true)) return
        try {
            val sock = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), port))
            }
            serverSocket = sock
        } catch (e: Exception) {
            running.set(false)
            throw IllegalStateException("Web 端口绑定失败($port): ${e.message}", e)
        }
        // 重建被 stop() 关闭的线程池（支持同一实例多次开/关循环）
        if (ioPool.isShutdown) ioPool = Executors.newCachedThreadPool()
        if (rootActionExecutor.isShutdown) rootActionExecutor = Executors.newSingleThreadExecutor()
        ioPool.execute(::acceptLoop)
    }

    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        runCatching { ioPool.shutdownNow() }
        runCatching { rootActionExecutor.shutdownNow() }
    }

    private fun acceptLoop() {
        while (running.get()) {
            val sock = try { serverSocket?.accept() } catch (_: Exception) { null }
                ?: if (running.get()) continue else break
            ioPool.execute { dispatch(sock) }
        }
    }

    // ---------------- HTTP 协议层 ----------------

    private fun dispatch(sock: Socket) {
        sock.soTimeout = 8000
        runCatching {
            val reader = BufferedReader(InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8))
            val requestLine = reader.readLine() ?: return@runCatching
            val req = requestLine.split(' ')
            if (req.size < 2) return@runCatching
            val method = req[0].uppercase()
            val uri = req[1]

            val headers = HashMap<String, String>()
            var contentLength = 0
            var line: String?
            while (reader.readLine().also { line = it } != null && line!!.isNotBlank()) {
                val idx = line!!.indexOf(':')
                if (idx > 0) {
                    val k = line!!.substring(0, idx).trim()
                    val v = line!!.substring(idx + 1).trim()
                    headers[k] = v
                    if (k.equals("Content-Length", true)) contentLength = v.toIntOrNull() ?: 0
                }
            }

            val body = readExact(reader, contentLength)
            val session = Session(method, uri, headers, body)

            write(sock, route(session))
        }
    }

    private fun readExact(reader: BufferedReader, len: Int): String {
        if (len <= 0) return ""
        val buf = CharArray(len)
        var off = 0
        while (off < len) {
            val r = reader.read(buf, off, len - off)
            if (r < 0) break
            off += r
        }
        return String(buf, 0, off)
    }

    private fun write(sock: Socket, resp: Response) {
        val bytes = resp.body.toByteArray(StandardCharsets.UTF_8)
        val writer = BufferedWriter(OutputStreamWriter(sock.getOutputStream(), StandardCharsets.UTF_8))
        writer.write("HTTP/1.1 ${resp.code} ${phrase(resp.code)}\r\n")
        writer.write("Content-Type: ${resp.mime}\r\n")
        writer.write("Content-Length: ${bytes.size}\r\n")
        writer.write("Connection: close\r\n")
        writer.write("Access-Control-Allow-Origin: *\r\n")
        writer.write("\r\n")
        writer.write(resp.body)
        writer.flush()
    }

    private fun phrase(code: Int) = when (code) {
        200 -> "OK"; 400 -> "Bad Request"; 401 -> "Unauthorized"
        403 -> "Forbidden"; 404 -> "Not Found"; 405 -> "Method Not Allowed"
        500 -> "Internal Server Error"; else -> "OK"
    }

    // ---------------- 默认路由（类似 NanoHTTPD.serve） ----------------

    private fun serve(s: Session): Response = when (s.path) {
        "/info" -> serveInfo()
        "/verify" -> serveVerify(s)
        "/console" -> serveConsole(s)
        "/status" -> serveStatus(s)
        "/find" -> serveFind(s)
        "/stop" -> serveStop(s)
        "/" -> serveHome()
        else -> ResponseText(404, "text/plain; charset=utf-8", "not found")
    }

    /** 首页：解锁页。输入密码 POST /verify，成功获得会话 token 并跳转 /console（控制台，不再直接触发报警） */
    private fun serveHome(): Response {
        val html = """
            <!DOCTYPE html>
            <html lang="zh">
            <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>Find You Device</title>
            <style>
              :root { color-scheme: light dark; }
              html[data-theme="dark"] { color-scheme: dark; }
              html[data-theme="light"] { color-scheme: light; }
              body { font-family: -apple-system, Roboto, "Noto Sans CJK SC", sans-serif;
                     margin: 0; background: #f4f4f4; color: #1c1b1f; }
              .page { max-width: 420px; margin: 0 auto; padding: 40px 20px 32px; }
              .topbar { display: flex; align-items: center; justify-content: space-between; }
              h1 { font-size: 22px; font-weight: 600; margin: 0; }
              .theme-btn { border: 1px solid rgba(128,128,128,.4); background: transparent; color: inherit;
                           border-radius: 999px; padding: 6px 14px; font-size: 13px; cursor: pointer; }
              .card { background: #fff; border-radius: 16px; padding: 20px; margin-top: 16px;
                      box-shadow: 0 1px 3px rgba(0,0,0,.12); }
              .meta { font-size: 12px; opacity: .65; margin-top: 10px; line-height: 1.6; }
              label { display: block; font-size: 13px; margin: 14px 0 6px; opacity: .8; }
              input { width: 100%; box-sizing: border-box; padding: 12px; border-radius: 12px;
                      border: 1px solid rgba(128,128,128,.35); font-size: 16px; background: transparent;
                      color: inherit; }
              button.primary { margin-top: 18px; width: 100%; padding: 13px; font-size: 16px;
                               font-weight: 600; border: 0; border-radius: 999px;
                               background: #6750a4; color: #fff; cursor: pointer; }
              button.primary:disabled { opacity: .6; }
              a { color: inherit; }
              #msg { margin-top: 14px; font-size: 14px; min-height: 22px; line-height: 1.5; }
              #msg.ok { color: #2e7d32; }
              #msg.err { color: #c62828; }
              html[data-theme="dark"] body { background: #141218; color: #e6e0e9; }
              html[data-theme="dark"] .card { background: #211f26; box-shadow: none; }
              html[data-theme="dark"] #msg.ok { color: #81c784; }
              html[data-theme="dark"] #msg.err { color: #ef9a9a; }
            </style>
            </head>
            <body>
            <div class="page">
              <div class="topbar">
                <h1>Find You Device</h1>
                <button class="theme-btn" id="themeBtn" type="button">主题</button>
              </div>
              <div class="card">
                <div class="meta" id="hostInfo">正在读取主机信息…</div>
                <label for="pass">密码（主机未设置密码时留空）</label>
                <input type="password" id="pass" name="pass" autocomplete="off" placeholder="请输入主机设置的密码">
                <button class="primary" id="unlockBtn" type="button">解锁</button>
                <div id="msg" role="status"></div>
                <div class="meta">解锁后进入本机设备控制台（可触发查找、停止查找）。</div>
              </div>
            </div>
            <script>
              var themeBtn = document.getElementById('themeBtn');
              var themes = ['auto', 'light', 'dark'];
              var themeNames = ['跟随系统', '浅色', '深色'];
              var cur = localStorage.getItem('fyd_theme') || 'auto';
              function applyTheme(t) {
                cur = t;
                localStorage.setItem('fyd_theme', t);
                if (t === 'auto') document.documentElement.removeAttribute('data-theme');
                else document.documentElement.setAttribute('data-theme', t);
                themeBtn.textContent = themeNames[themes.indexOf(t)];
              }
              applyTheme(cur);
              themeBtn.addEventListener('click', function () {
                applyTheme(themes[(themes.indexOf(cur) + 1) % themes.length]);
              });

              fetch('/info').then(function (r) { return r.json(); })
                .then(function (j) {
                  var t = (j.model || '未知型号') + (j.battery >= 0 ? ' · 电量 ' + j.battery + '%' : '');
                  document.getElementById('hostInfo').textContent = t;
                }).catch(function () {
                  document.getElementById('hostInfo').textContent = '主机信息读取失败';
                });

              var btn = document.getElementById('unlockBtn');
              var msg = document.getElementById('msg');
              btn.addEventListener('click', function () {
                btn.disabled = true;
                msg.className = '';
                msg.textContent = '正在解锁…';
                var pass = document.getElementById('pass').value;
                var body = new URLSearchParams();
                body.append('pass', pass);
                fetch('/verify', {
                  method: 'POST',
                  headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
                  body: body.toString()
                }).then(function (r) {
                  if (r.status === 200) {
                    return r.json().then(function (j) {
                      localStorage.setItem('fyd_token', j.token);
                      msg.className = 'ok';
                      msg.textContent = '解锁成功，正在进入控制台…';
                      location.href = '/console?t=' + encodeURIComponent(j.token);
                    });
                  } else if (r.status === 401) {
                    msg.className = 'err'; msg.textContent = '密码错误（401）：请检查后重试';
                    btn.disabled = false;
                  } else {
                    msg.className = 'err'; msg.textContent = '解锁失败，状态码 ' + r.status;
                    btn.disabled = false;
                  }
                }).catch(function () {
                  msg.className = 'err';
                  msg.textContent = '请求失败：无法连接主机，请确认与主机在同一网络';
                  btn.disabled = false;
                });
              });
            </script>
            </body>
            </html>
        """.trimIndent()
        return Response(200, "text/html; charset=utf-8", html)
    }

    /** 免鉴权：主机型号 + 电量JSON（供扫端口设备自动入库） */
    private fun serveInfo(): Response {
        val json = JSONObject()
            .put("model", Build.MODEL ?: "unknown")
            .put("battery", RootShell.query("dumpsys battery")?.levelOrNull() ?: -1)
        return ResponseText(200, "application/json; charset=utf-8", json.toString())
    }

    /**
     * /verify：网页“解锁”。密码校验通过后签发 6h 会话 token（控制台页面使用）。
     * 支持与 /find 相同的鉴权来源；主机未设密码时留空即可。
     */
    private fun serveVerify(s: Session): Response {
        if (s.method != "POST") return ResponseText(405, "text/plain; charset=utf-8", "method not allowed")
        if (!authorized(s, withSessionToken = false)) return authFail()
        val token = issueSession()
        return Response(200, "application/json; charset=utf-8",
            JSONObject().put("ok", true).put("token", token).toString())
    }

    /** 免 Root 电量读取（控制台 2s 轮询用，避免频繁触发 su） */
    private fun batteryNoRoot(): Int = runCatching {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
    }.getOrDefault(-1)

    /**
     * /status：控制台轮询状态（需会话 token）。
     * 返回 running/lock(本次是否锁定)/remainingMs/battery/lastFind。
     */
    private fun serveStatus(s: Session): Response {
        if (!authorized(s, withSessionToken = true)) return authFail()
        val json = JSONObject()
            .put("ok", true)
            .put("model", Build.MODEL ?: "unknown")
            .put("battery", batteryNoRoot())
            .put("running", AlarmController.isRunning)
            .put("lock", AlarmController.isLocked)
            .put("remainingMs", AlarmController.remainingMs())
            .put("lastFind", Prefs.getLastFind(context))
        return ResponseText(200, "application/json; charset=utf-8", json.toString())
    }

    /**
     * /console：仿客户端的单机设备控制台（仅当前主机，无设备列表）。
     * 需要 ?t= 会话 token（来自 /verify 解锁）；提供“查找 / 停止查找 / 状态轮询”。
     */
    private fun serveConsole(s: Session): Response {
        val token = s.query("t")
        if (!sessionOk(token)) {
            val html = """
                <!DOCTYPE html><html lang="zh"><meta charset="utf-8">
                <body style="font-family:sans-serif;max-width:420px;margin:64px auto;padding:0 20px;text-align:center">
                <h2>会话已过期或未解锁</h2>
                <p>请返回首页输入密码解锁后进入控制台。</p>
                <p><a href="/">返回解锁页</a></p>
                </body></html>
            """.trimIndent()
            return Response(401, "text/html; charset=utf-8", html)
        }
        val html = consoleHtml(token!!)
        return Response(200, "text/html; charset=utf-8", html)
    }

    /** 单机控制台 HTML（仿客户端卡片；token 已由服务端校验） */
    private fun consoleHtml(token: String): String = """
        <!DOCTYPE html>
        <html lang="zh">
        <head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <title>设备控制台 · Find You Device</title>
        <style>
          :root { color-scheme: light dark; }
          html[data-theme="dark"] { color-scheme: dark; }
          html[data-theme="light"] { color-scheme: light; }
          body { font-family: -apple-system, Roboto, "Noto Sans CJK SC", sans-serif;
                 margin: 0; background: #f4f4f4; color: #1c1b1f; }
          .page { max-width: 480px; margin: 0 auto; padding: 24px 16px 40px; }
          .topbar { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
          h1 { font-size: 20px; font-weight: 600; margin: 0; }
          .chip-btn { border: 1px solid rgba(128,128,128,.4); background: transparent; color: inherit;
                      border-radius: 999px; padding: 6px 12px; font-size: 12px; cursor: pointer; }
          .card { background: #fff; border-radius: 18px; padding: 16px; margin-top: 14px;
                  box-shadow: 0 1px 3px rgba(0,0,0,.10); }
          .row { display: flex; align-items: center; gap: 10px; }
          .dot { width: 10px; height: 10px; border-radius: 50%; background: #9e9e9e; }
          .dot.on { background: #2e7d32; }
          .dot.busy { background: #c62828; }
          .name { font-size: 16px; font-weight: 600; flex: 1; }
          .sub { font-size: 12px; opacity: .65; margin-top: 8px; line-height: 1.7; }
          .state-line { font-size: 13px; margin-top: 6px; }
          .btn { display: block; width: 100%; box-sizing: border-box; margin-top: 10px; padding: 12px;
                 border: 0; border-radius: 999px; font-size: 15px; font-weight: 600; cursor: pointer;
                 background: #6750a4; color: #fff; }
          .btn:disabled { opacity: .4; }
          .btn.stop { background: #b3261e; }
          .switch-row { display: flex; align-items: center; justify-content: space-between;
                        margin-top: 12px; font-size: 14px; }
          .switch { position: relative; width: 46px; height: 26px; }
          .switch input { opacity: 0; width: 0; height: 0; }
          .slider { position: absolute; inset: 0; background: #bbb; border-radius: 999px;
                    transition: .2s; }
          .slider:before { content: ""; position: absolute; width: 20px; height: 20px; left: 3px; top: 3px;
                           background: #fff; border-radius: 50%; transition: .2s; }
          .switch input:checked + .slider { background: #6750a4; }
          .switch input:checked + .slider:before { transform: translateX(20px); }
          #msg { margin-top: 12px; font-size: 13px; min-height: 18px; }
          #msg.err { color: #c62828; }
          #msg.ok { color: #2e7d32; }
          html[data-theme="dark"] body { background: #141218; color: #e6e0e9; }
          html[data-theme="dark"] .card { background: #211f26; box-shadow: none; }
          html[data-theme="dark"] .slider { background: #5a5560; }
          html[data-theme="dark"] #msg.err { color: #ef9a9a; }
          html[data-theme="dark"] #msg.ok { color: #81c784; }
        </style>
        </head>
        <body>
        <div class="page">
          <div class="topbar">
            <h1>设备控制台</h1>
            <div>
              <button class="chip-btn" id="themeBtn" type="button">主题</button>
              <button class="chip-btn" id="exitBtn" type="button">退出</button>
            </div>
          </div>

          <div class="card">
            <div class="row">
              <div class="dot" id="dot"></div>
              <span class="name" id="devName">本机（当前主机）</span>
              <span class="chip-btn" id="stateChip">读取中</span>
            </div>
            <div class="sub" id="devSub">正在读取设备信息…</div>
          </div>

          <div class="card">
            <div class="row">
              <span style="font-weight:600;flex:1">查找本机</span>
            </div>
            <div class="state-line" id="stateLine">状态：空闲</div>
            <label class="switch-row">锁定查找（持续响铃直到手动停止）
              <span class="switch">
                <input type="checkbox" id="lockSw">
                <span class="slider"></span>
              </span>
            </label>
            <button class="btn" id="btnFind" type="button">查找</button>
            <button class="btn stop" id="btnStop" type="button" disabled>停止查找</button>
            <div id="msg" role="status"></div>
          </div>
        </div>

        <script>
          var TOKEN = '__TOKEN__';
          var msg = document.getElementById('msg');

          function showMsg(t, cls) {
            msg.className = cls || '';
            msg.textContent = t;
          }

          // ---- 主题 ----
          var themeBtn = document.getElementById('themeBtn');
          var themes = ['auto', 'light', 'dark'];
          var themeNames = ['跟随系统', '浅色', '深色'];
          var cur = localStorage.getItem('fyd_theme') || 'auto';
          function applyTheme(t) {
            cur = t;
            localStorage.setItem('fyd_theme', t);
            if (t === 'auto') document.documentElement.removeAttribute('data-theme');
            else document.documentElement.setAttribute('data-theme', t);
            themeBtn.textContent = themeNames[themes.indexOf(cur)];
          }
          applyTheme(cur);
          themeBtn.addEventListener('click', function () {
            applyTheme(themes[(themes.indexOf(cur) + 1) % themes.length]);
          });

          document.getElementById('exitBtn').addEventListener('click', function () {
            localStorage.removeItem('fyd_token');
            location.href = '/';
          });

          // ---- 状态轮询（2.5s）----
          var running = false;
          var lockNow = false;
          function fmtLast(ms) {
            if (!ms || ms <= 0) return '从未';
            var d = new Date(ms);
            function p(n) { return (n < 10 ? '0' : '') + n; }
            return d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate()) +
                   ' ' + p(d.getHours()) + ':' + p(d.getMinutes()) + ':' + p(d.getSeconds());
          }
          function poll() {
            fetch('/status?t=' + encodeURIComponent(TOKEN), { cache: 'no-store' })
              .then(function (r) { return r.ok ? r.json() : Promise.reject(new Error(String(r.status))); })
              .then(function (j) {
                running = !!j.running;
                lockNow = !!j.lock;
                var model = j.model || '未知型号';
                var bat = (j.battery >= 0) ? ' · 电量 ' + j.battery + '%' : '';
                document.getElementById('devName').textContent = model;
                document.getElementById('devSub').textContent =
                  '本机（当前主机） · 上次查找 ' + fmtLast(j.lastFind) + ' · 局域网地址 :1145';
                var dot = document.getElementById('dot');
                dot.className = 'dot' + (running ? ' busy' : ' on');
                var line = document.getElementById('stateLine');
                var chip = document.getElementById('stateChip');
                if (running) {
                  if (lockNow) {
                    line.textContent = '状态：查找中（锁定模式，将持续到手动停止）';
                    chip.textContent = '查找中';
                  } else {
                    var sec = Math.max(0, Math.round((j.remainingMs || 0) / 1000));
                    line.textContent = '状态：查找中（约 ' + sec + ' 秒后自动停止）';
                    chip.textContent = sec + 's';
                  }
                } else {
                  line.textContent = '状态：空闲';
                  chip.textContent = '空闲';
                }
                document.getElementById('btnFind').disabled = running;
                document.getElementById('btnStop').disabled = !running;
              })
              .catch(function (e) {
                showMsg('状态读取失败（' + e.message + '），请确认仍与主机同一网络', 'err');
              });
          }

          // 查找：锁定与否由触发方此刻决定（勾选"锁定查找"= 持续响铃直到手动/远程停止）
          document.getElementById('btnFind').addEventListener('click', function () {
            var btn = this;
            btn.disabled = true;
            showMsg('正在触发查找…');
            var body = new URLSearchParams();
            body.append('t', TOKEN);
            if (document.getElementById('lockSw').checked) body.append('lock', '1');
            fetch('/find', {
              method: 'POST',
              headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
              body: body.toString()
            }).then(function (r) {
              if (!r.ok) throw new Error('HTTP ' + r.status);
              return r.json();
            }).then(function (j) {
              if (j.ok) {
                showMsg(j.lock ? '已触发查找：持续响铃/闪光（锁定模式，可点“停止查找”结束）'
                               : '已触发查找：约 15 秒自动停止，也可提前停止', 'ok');
                poll();
              } else showMsg('触发失败', 'err');
            }).catch(function (e) { showMsg('请求失败：' + e.message, 'err'); btn.disabled = false; });
          });

          document.getElementById('btnStop').addEventListener('click', function () {
            showMsg('正在停止…');
            var body = new URLSearchParams();
            body.append('t', TOKEN);
            fetch('/stop', {
              method: 'POST',
              headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
              body: body.toString()
            }).then(function (r) {
              if (!r.ok) throw new Error('HTTP ' + r.status);
              return r.json();
            }).then(function (j) {
              showMsg(j.ok ? '已停止查找' : '停止失败', j.ok ? 'ok' : 'err');
              poll();
            }).catch(function (e) { showMsg('停止请求失败：' + e.message, 'err'); });
          });

          poll();
          setInterval(poll, 2500);
        </script>
        </body>
        </html>
    """.trimIndent().replace("__TOKEN__", token)

    /**
     * /find：鉴权 → 触发查找（不进入控制台，保持客户端远程触发语义）。
     * 支持 GET(query pass/lock/dur) 与 POST(form/JSON/Basic) 鉴权；GET 返回简单结果页。
     * 时长规则：lock=1/true → 锁定无限；未 lock 时 dur=秒覆盖默认 15s；均缺省跟随主机锁定开关。
     */
    private fun serveFind(s: Session): Response {
        if (s.method != "GET" && s.method != "POST")
            return ResponseText(405, "text/plain; charset=utf-8", "method not allowed")
        if (!authorized(s, withSessionToken = true)) return authFail()

        // 触发参数：form/query 的 lock、dur（秒）；锁定与否由触发方显式决定，
        // 不再存在主机端全局锁定开关
        fun param(name: String): String? = parseFormField(s.bodyText, name) ?: s.query(name)
        val lockRaw = param("lock")
        val lockReq = lockRaw != null && (lockRaw.equals("1", true) || lockRaw.equals("true", true) || lockRaw.equals("on", true))
        val lockFinal = lockReq
        val durSec = param("dur")?.toLongOrNull()
        val durationMs = if (lockFinal) 0L else ((durSec?.times(1000)) ?: AlarmController.DEFAULT_FIND_MS)

        // 鉴权通过 → 记录最近查找时间并触发
        Prefs.setLastFind(context, System.currentTimeMillis())
        AlarmController.start(context, lockFinal, durationMs)

        val desc = if (lockFinal) "查找中：响铃+闪光将持续，直到在本机/客户端/网页停止" else "查找中：响铃+闪光约 ${durationMs / 1000} 秒后自动停止"
        if (s.method == "GET") {
            return Response(200, "text/html; charset=utf-8",
                "<!DOCTYPE html><html lang=\"zh\"><meta charset=\"utf-8\"><body style=\"font-family:sans-serif;max-width:420px;margin:48px auto;padding:0 20px\"><h2>已触发查找</h2><p>$desc</p><p><a href=\"/\">返回</a></p></body></html>")
        }
        return Response(200, "application/json; charset=utf-8",
            JSONObject().put("ok", true).put("lock", lockFinal).put("durationMs", durationMs).toString())
    }

    /**
     * /stop：停止本机当前查找（远程客户端/网页/本机均可用）。需与 /find 相同鉴权。
     */
    private fun serveStop(s: Session): Response {
        if (s.method != "POST" && s.method != "GET")
            return ResponseText(405, "text/plain; charset=utf-8", "method not allowed")
        if (!authorized(s, withSessionToken = true)) return authFail()
        AlarmController.stop()
        return Response(200, "application/json; charset=utf-8", """{"ok":true,"stopped":true}""")
    }

    /** 解析 application/x-www-form-urlencoded 的指定字段（返回首个匹配，未 decode 亦可） */
    private fun parseFormField(body: String, name: String): String? {
        if (body.isBlank()) return null
        val decoded = try {
            java.net.URLDecoder.decode(body, StandardCharsets.UTF_8.name())
        } catch (_: Exception) { return null }
        return decoded.split('&').mapNotNull { seg ->
            val kv = seg.split('=', limit = 2)
            if (kv.size == 2 && kv[0] == name) kv[1] else null
        }.firstOrNull()
    }

    private fun decodeBasic(base64: String): Pair<String, String>? = runCatching {
        val decoded = String(Base64.getDecoder().decode(base64), StandardCharsets.UTF_8)
        val parts = decoded.split(':', limit = 2)
        if (parts.size == 2) parts[0] to parts[1] else null
    }.getOrNull()

    private fun parseJsonPassword(body: String): String? {
        if (body.isBlank()) return null
        return try { JSONObject(body).optString("password").takeIf { it.isNotBlank() } }
        catch (_: Exception) { null }
    }

    // ---------------- 工具 ----------------

    private fun ResponseText(code: Int, mime: String, body: String) = Response(code, mime, body)
}

/** 从 stdout 解析 dumpsys battery 的 level 行取数字 */
private fun String?.levelOrNull(): Int? = this
    ?.lineSequence()
    ?.firstOrNull { it.contains("level", true) }
    ?.filter { it.isDigit() }
    ?.takeIf { it.isNotBlank() }
    ?.toIntOrNull()

/**
 * Root 指令执行封装。每步都 try/catch，任一条（含缺失、无权限）都不会向外抛导致崩溃。
 */
internal object RootShell {

    /** 常见 su 位置（Magisk/KernelSU/系统 xbin 等），逐个尝试 */
    private val SU_CANDIDATES = listOf(
        "su", "/system/bin/su", "/sbin/su",
        "/system/xbin/su", "/vendor/bin/su",
    )

    /** 执行单条 su -c 指令，捕获一切异常返回 null 或输出。commands 逐个保底。 */
    fun execOrIgnore(cmd: String): String? {
        for (su in SU_CANDIDATES) {
            val r = tryExec(su, cmd)
            // 只要该 su 能启动执行（无论退出码），就采用其结果；否则换下一个路径
            if (r.first) return r.second
        }
        return null
    }

    /** true=进程成功启动并等待完成；second 为 stdout（非零退出码时为 null） */
    private fun tryExec(su: String, cmd: String): Pair<Boolean, String?> = try {
        val pb = ProcessBuilder(su, "-c", cmd)
        val p = pb.start()
        // 仍潜在 root 挂起：读取后再 wait 有死锁风险；用读线程异步取
        val out = StringBuilder()
        val t = Thread {
            p.inputStream.bufferedReader(StandardCharsets.UTF_8)
                .useLines { lines -> lines.forEach { out.append(it).append('\n') } }
        }
        t.start()
        val errCode = p.waitFor()
        t.join(1500)
        true to (if (errCode == 0) out.toString() else null)
    } catch (_: Exception) {
        // 该 su 路径不存在/无法启动 → 交由调用方尝试下一个候选
        false to null
    }

    /** 便捷：单条 root 查询 */
    fun query(cmd: String): String? = execOrIgnore(cmd)
}