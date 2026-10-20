# DSH 手机控制

用安卓手机远程操作电脑上的 **DeepSeek Harness 桌面端**。所有对话、命令、文件操作都在电脑上执行；手机只负责发消息、点按钮、看结果。

```
┌─────────────┐  局域网 / Tailscale / HTTPS  ┌──────────────────┐      127.0.0.1      ┌────────────────────┐
│ 安卓 App    │  ──────────────────────> │ DSH Mobile Bridge│  ─────────────────> │ DSH 桌面端 Web GUI │
│ WebView     │  <────────────────────── │ (电脑上的 Node)  │  <───────────────── │ 127.0.0.1:<随机端口>│
└─────────────┘   配对 Cookie + 转发      └──────────────────┘   注入登录 Cookie   └────────────────────┘
```

- 不复制 DSH、不另开一个 agent 进程，直接转发**当前正在运行的桌面端**页面和 WebSocket。
- 电脑端读取本机 `~/.dsh/.credentials.yaml` 里的浏览器会话签名密钥，在桥接内部生成 DSH 登录 Cookie；手机永远拿不到这个密钥。
- 手机只持有桥接自己签发的配对 Cookie。配对链接 = 密码，泄露后重启桥接并加 `--reset-pair` 即可作废。

## 快速开始

### 1. 电脑

1. 保持 **DeepSeek Harness 桌面端** 打开（就是你现在用的这个窗口）。
2. 确认已安装 **Node.js 20+**：终端运行 `node -v`。
3. 双击本目录的 **`start-mobile.bat`**。
4. 终端会显示：

   - 手机配对链接：`http://<电脑局域网IP>:8787/pair/<随机令牌>`
   - 一个二维码（用 App 扫）
   - 本机 DSH 地址和 Cookie 校验结果

5. 第一次运行时 Windows 防火墙可能弹窗，请勾选 **专用网络** 并允许 Node.js。若没弹窗但手机连不上，见“排查”。

### 2. 手机

1. 安装 `dsh-mobile.apk`（本目录，约 0.3 MB）。
   - 用数据线传到手机，或 `adb install -r dsh-mobile.apk`。
   - 也可以手机浏览器打开 `http://<电脑IP>:8787/app.apk` 直接下载（桥接启动后）。
   - Android 会提示“未知来源应用”，允许安装即可。
2. 打开 **DSH 手机控制**。
3. 点 **扫码**，扫电脑终端里的二维码；或点 **粘贴** 粘贴配对链接。
4. 连接成功后就是桌面端同一个界面，可以直接发消息、审批命令、看输出。

> 手机和电脑必须在同一个局域网（同一 Wi-Fi，或电脑开了热点给手机连）。
> 需要跨网络时，优先用下面的 **Tailscale**；如果要在公网服务器/域名上访问，用 **公网反代/隧道** 方案，不要直接把 8787 端口映射出去。

## 不在同一局域网：Tailscale（推荐）

Tailscale 是基于 WireGuard 的组网工具，不需要公网 IP、不用在路由器上开端口。电脑和手机各装一个、登录同一账号，就会进入同一个虚拟局域网；桥接仍然只监听 Tailscale 地址，公网扫描不到 8787。

1. 电脑安装 Tailscale：<https://tailscale.com/download/windows>，登录。
2. 手机安装 Tailscale（应用商店搜 Tailscale），登录**同一账号**。
3. 电脑上双击 **`start-tailscale.bat`**。它会自动读取本机 `100.x.y.z` 地址，并用这个地址启动桥接。
4. 手机确认 Tailscale 已连接（通知栏有 VPN 图标），打开 App 扫终端二维码，或浏览器先访问 `http://100.x.y.z:8787/healthz` 测试。
5. 如果手机打不开，管理员 PowerShell 执行一次防火墙规则：

   ```powershell
   netsh advfirewall firewall add rule name="DSH Mobile Tailscale" dir=in action=allow protocol=TCP localport=8787 remoteip=100.64.0.0/10
   ```

也可以手动指定地址：

```bat
start-mobile.bat --bind 100.x.y.z
```

说明：

- 配对链接会变成 `http://100.x.y.z:8787/pair/...`；Tailscale 负责加密和身份，桥接内部仍是 HTTP，不要把这个地址再映射到公网。
- 手机丢失或链接泄露：先在 Tailscale 后台移除手机设备，再运行 `start-mobile.bat --reset-pair`。
- 电脑休眠会断线；用手机前把电源计划设为“不睡眠”。
- 公司网络封 WireGuard 时 Tailscale 可能连不上，可改用下面的 Cloudflare Tunnel + Access。
- 同类替代：ZeroTier、WireGuard、frp；最省事的是 Tailscale。
- 不想装组网工具，也可以用 RustDesk/Parsec 远程桌面控制整台电脑，但那就不是 DSH 专用界面了。

## 公网访问：自己的域名 / 服务器

可以。桥接只跑在电脑上，但外网要能进来，还需要一个“公网入口”：

- 家宽有**公网 IPv4**：域名解析到公网 IP，路由器把 80/443 转发到电脑，Caddy 申请证书并反代；
- 家宽是 **CGNAT/没有公网 IP**：用 Cloudflare Tunnel，或在有公网 IP 的 VPS 上跑 frp 中转；
- 只给一两台自己的设备用，**Tailscale 仍然是最省事、最安全的方案**，不需要域名和公网入口。

桥接本身不直接监听公网，而是绑定 `127.0.0.1`，由 **Caddy、Cloudflare Tunnel 或 frp** 在公网侧终止 TLS，再反代到 `127.0.0.1:8787`。这样证书、域名、访问日志都在你手里，8787 不需要对公网开放。

电脑上这样启动：

```bat
start-mobile.bat --bind 127.0.0.1 --public-url https://dsh.example.com
```

`--public-url` 的作用：

- 配对链接和安装包地址改成 `https://dsh.example.com/...`；
- 允许该域名的 `Host` 和 `Origin`（否则桥接的防 DNS rebinding 检查会拒绝）；
- 配对 Cookie 自动加 `Secure`（必须经 HTTPS 访问）；
- **它不负责证书和监听**，TLS 由下面的反代/隧道完成。

### 方案 A：Caddy + 公网 IP

适合家里/公司宽带有公网 IPv4，或已有云服务器。把域名 A 记录指向公网 IP，路由器只转发 **80/443** 到电脑，Windows 防火墙放行 **80/443**，不要转发或放行 8787。

```powershell
# 电脑上安装 Caddy 后，前台运行（自动申请 Let's Encrypt 证书）
caddy reverse-proxy --from dsh.example.com --to 127.0.0.1:8787
```

另一个终端启动桥接（上面的 `start-mobile.bat ... --public-url https://dsh.example.com`），然后用手机扫二维码。Caddy 默认会保留 Host 并支持 WebSocket，DSH 的实时连接可以直接用。

### 方案 B：Cloudflare Tunnel（没有公网 IP 也能用）

适合 CGNAT、家宽没有公网 IP，或不想在路由器开端口。电脑装 `cloudflared`，先建隧道拿到固定域名（Named Tunnel），再让 Cloudflare 把 `https://dsh.example.com` 转到 `http://127.0.0.1:8787`。临时测试也可以用：

```powershell
cloudflared tunnel --url http://127.0.0.1:8787
```

它会输出一个 `https://<随机>.trycloudflare.com` 地址；把这个地址作为 `--public-url` 启动桥接即可。临时隧道每次重启域名都会变，只适合试用。

建议在 Cloudflare 侧再加 **Access**（只允许你的邮箱/Google 账号），这样即使配对链接泄露，陌生人也会先被身份验证挡住。WebView 能正常走 Access 的登录跳转。

### 方案 C：frp / VPS 中转

有云服务器但家里没有公网 IP 时，在 VPS 上跑 frps，电脑跑 frpc，把 `127.0.0.1:8787` 映射到 VPS 的本地端口，再由 VPS 上的 Caddy/Nginx 提供 `https://dsh.example.com`。启动桥接时同样加 `--public-url https://dsh.example.com --bind 127.0.0.1`。

### 公网注意事项

- **必须 HTTPS**。不要把 `http://公网IP:8787` 直接暴露；配对 Cookie 和对话内容都会明文传输。
- 只让反代/隧道访问 `127.0.0.1:8787`；防火墙不要对公网开放 8787。
- 配对链接仍然是唯一入口，等同于电脑操作权限；泄露后 `start-mobile.bat --reset-pair` 作废旧链接。
- 反代后面所有请求的来源 IP 都是 `127.0.0.1`，配对限流会变成全局 10 次/分钟；令牌本身有 192 位随机量，正常爆破不了。
- 电脑休眠、隧道进程退出都会断线；长期使用把电源计划设为“不睡眠”，并把 cloudflared/Caddy 注册为服务。

## 手机端功能

- 扫码 / 粘贴配对，配对状态保存在本机。
- 完整 DSH Web GUI：对话、工具审批、终端输出、文件预览。
- 文件选择上传（WebView 文件选择器）。
- 下载文件（交给系统下载管理器）。
- 顶部状态：连接中 / 已连接 / 配对失效 / 连接失败。
- 菜单：刷新、字体 85%–130%、横竖屏切换、浏览器打开、重新配对、关于。
- 返回键：网页内后退；再按两次退出。
- 失败页提供“重试”和“重新配对”，不会把错误吞成白屏。

## 电脑端命令

在 `bridge` 目录运行：

```bat
node server.mjs                  :: 默认 8787，自动选局域网 IP
node server.mjs --port 9000      :: 换端口
node server.mjs --bind 192.168.1.10
node server.mjs --reset-pair     :: 作废旧配对链接，生成新的
node server.mjs --dsh http://127.0.0.1:62734
node server.mjs --no-qr          :: 只打印链接，不打印二维码
node server.mjs --bind 127.0.0.1 --public-url https://dsh.example.com
                                  :: 公网反代/隧道模式
```

- 配对令牌、桥接签名密钥、DSH 地址缓存在 `%USERPROFILE%\.dsh-mobile\`。
- `pair-url.txt` 里是当前配对链接；`config.json` 含密钥，勿分享。
- 桥接默认只绑定一个私有局域网 IPv4，不监听 `0.0.0.0`；加了 `--public-url` 且没写 `--bind` 时，默认只绑 `127.0.0.1`，供本机反代/隧道连接。
- DSH 桌面端重启后端口可能变化，桥接会自动扫描本机监听端口重新发现，不需要改配置。

## 安全说明

- 桥接只接受：
  - Host 为 localhost / 本机局域网 IP / 显式 `--bind` 的地址 / `--public-url` 的域名；
  - Origin 与本机地址或 `--public-url` 完全一致；
  - 带有效桥接配对 Cookie 的请求。
- 会拒绝 `Sec-Fetch-Site: cross-site`，降低 DNS rebinding / CSRF 风险。
- 转发时剥离手机带来的 `dsh-auth-*` Cookie，只注入桥接自己生成的 DSH Cookie；DSH 的登录 Cookie 不会下发到手机。
- 配对接口有每 IP 每分钟 10 次的限流。
- 同一 Wi-Fi 下默认是 **HTTP**，流量不加密，别在不可信公共 Wi-Fi 使用；跨网络优先 Tailscale/WireGuard，或按上面用 HTTPS 反代/隧道。公网 `--public-url https://...` 时配对 Cookie 带 `Secure`，只能走 HTTPS。
- 配对链接等同于电脑操作权限。不要把二维码/链接发群里；怀疑泄露就 `start-mobile.bat --reset-pair`。

## 构建 APK

本项目不依赖 Gradle，使用已缓存的 Android SDK 组件直接构建：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\android\build.ps1
```

依赖：

- JDK 11+（脚本默认 `D:\New Folder`，找不到时回退到 PATH 里的 `javac`）
- Android SDK Platform 33、Build-Tools 33.0.2、platform-tools
- 缓存位置：`<workspace>\.android-build\sdk`
- ZXing core 3.5.3：`android\libs\core-3.5.3.jar`

产物：`dsh-mobile.apk`，用 `.state\dsh-mobile.keystore` 签名。
**请备份 `.state\dsh-mobile.keystore` 和 `.state\dsh-mobile.keystore.pass`**，否则以后无法覆盖安装升级。

当前 APK：295,474 字节，SHA-256 `6974E6D18CB4424ADE3096F5FB2F93906DA36EDD146619E1D63C4BE34BEA88B0`。

安装：

```bat
adb install -r dsh-mobile.apk
```

## 已验证 / 未验证

已在电脑端实测通过：

- 未配对访问返回 `401`，有效配对链接返回 `303` 并下发 Cookie；
- 通过桥接加载 DSH 首页 `200`，HTML 104,642 字节，含 `__DSH_BOOT__`；
- WebSocket `/api/remote.mux` 经桥接返回 `101 Switching Protocols`；
- 公网模式用模拟 `Host`/`Origin` 验证：HTTPS 公网地址配对返回带 `Secure` 的 Cookie，首页 `200`，WebSocket `101`，HTTP 来源的 `Origin` 被 `403` 拒绝；
- APK 通过 `apksigner` 的 v1/v2/v3 签名校验，package `com.dsh.mobile`，minSdk 23 / targetSdk 33。

**没有用真实域名/Caddy/Cloudflare 做过端到端测试**（当前环境没有域名和公网入口），只验证了桥接侧的 Host/Origin/Cookie/WebSocket 逻辑；真实反代请按“公网访问”一节配置后先用手机浏览器打开 `/healthz` 确认。

**未在真机/模拟器上运行过**（当前环境没有 adb 设备，也没有可用的模拟器）。第一次装到手机后如果闪退或扫码黑屏，按下面“排查”抓 `adb logcat`。

## 排查

**手机打开显示“需要配对”/ HTTP 401**
- 链接不完整或被复制错。重新扫终端二维码，或重新运行 `start-mobile.bat --reset-pair`。

**手机一直“连接失败”**
- 确认手机和电脑同一网段：手机浏览器打开 `http://<电脑IP>:8787/healthz`，应返回 JSON。
- Windows 防火墙：允许 Node.js 的“专用网络”入站；或管理员终端执行：
  `netsh advfirewall firewall add rule name="DSH Mobile" dir=in action=allow protocol=TCP localport=8787`
- 公司/酒店 Wi-Fi 常开启 AP 隔离，手机无法访问电脑；换手机热点给电脑连，或电脑开热点。

**终端说“未找到 DSH”**
- 先打开 DSH 桌面端，再重启 `start-mobile.bat`。
- 仍找不到时显式指定：`start-mobile.bat --dsh http://127.0.0.1:62734`（端口以 DSH 实际为准）。

**二维码在终端里显示乱码**
- 用 Windows Terminal，或 `chcp 65001` 后再运行；也可以直接复制 `pair-url.txt` 里的链接粘贴到 App。

**公网域名打开 403 / 502**
- 403：`--public-url` 必须和手机地址完全一致（`https://`、域名、非默认端口都不能差）；反代要保留原始 `Host`，并把 `Origin` 原样转发。
- 502：反代指向的地址不对。桥接在公网模式下默认只监听 `127.0.0.1`，反代/隧道应连 `http://127.0.0.1:8787`；如果反代在另一台机器，启动时加 `--bind 0.0.0.0` 或具体网卡 IP，并限制防火墙来源。
- 手机打不开 `https://...`：先确认隧道/Caddy 本身能打开 `/healthz`；配对 Cookie 带 `Secure`，不能用 `http://` 访问公网域名。
- 页面能开但实时输出不动：反代必须支持 WebSocket（Caddy 默认支持；Nginx 需要 `proxy_http_version 1.1` 和 `Upgrade`/`Connection` 头）。

**页面能开但排版很挤**
- DSH Web GUI 是桌面布局，没有手机宽度断点。建议横屏 + 菜单里把字体调到 85%–100%；复杂操作还是在电脑上做。

**App 闪退**
- 目前 APK 只做了编译/签名校验，未在真机跑过。若闪退，把手机型号、Android 版本和 `adb logcat -s DshMobile DshScan AndroidRuntime` 的输出发我，我按日志修。

## 目录

```
dsh-mobile/
├─ dsh-mobile.apk          已签名安装包
├─ start-mobile.bat        电脑端一键启动（局域网/公网反代）
├─ start-tailscale.bat     跨网络启动（Tailscale）
├─ bridge/
│  ├─ server.mjs           反向代理 + 配对 + Cookie 注入
│  └─ package.json
├─ android/
│  ├─ build.ps1            无 Gradle 构建脚本
│  ├─ AndroidManifest.xml
│  ├─ java/com/dsh/mobile/ MainActivity.java / ScanActivity.java
│  ├─ res/                 主题、图标、字符串
│  └─ libs/core-3.5.3.jar  ZXing 扫码
└─ .state/                 本机签名密钥（勿提交/分享）
```
