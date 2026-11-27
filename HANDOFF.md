# GNSS Link 交接文档

> 面向接手的开发者 / AI。读完这份文档应该能独立完成：改功能、编译、发版、排查线上问题。
> 最后更新：2026-11-27，对应 v1.3.7（versionCode 25）。

---

## 1. 这是什么

一个 Android 应用：把一台手机的 GPS 定位通过局域网（UDP / 蓝牙）发给另一台设备，接收端把收到的坐标注入为**系统模拟位置**，让没有 GPS 的车机、平板也能用高德 / 百度 / Google 地图导航。

- 仓库：`github.com/zhaokelei/gnss-link`（公开）
- 官网：`https://gps.iqiq.cn`（作者主页 `www.iqiq.cn`）
- 作者：小磊
- 语言：Kotlin，界面为**原生 XML + View**（v1.3.6 起，之前是 WebView）

## 2. 当前状态

| 项 | 值 |
| --- | --- |
| 版本 | `versionName 1.3.7` / `versionCode 25` |
| 分支 | `main`（唯一主分支，CI 只跟 push 到 main） |
| minSdk / targetSdk / compileSdk | 19 / 34 / 34 |
| 构建产物 | `gnss-link-universal.apk` 约 5.24 MB |
| 线上分发 | GitHub Release + 自建 FTP（`/download/`） |

**发布过的版本**：1.3.3（授权页独立）→ 1.3.4（保活悬浮窗 + 车机点击修复）→ 1.3.5（控件自绘）→ 1.3.6（界面原生化）→ 1.3.7（修启动闪退）。

## 3. 目录结构

```
gnss-link/
├── .github/workflows/
│   ├── build-apk.yml        # push main 自动：编译 → 校验签名 → 发 Release → 同步 FTP
│   └── legacy-smoke.yml     # 手动触发：云模拟器跑一遍，验证能装能启动
├── docs/update-log.txt      # 应用内更新日志（车机端读这份判断版本）
├── gpslink/                 # Android 工程（Android Studio 直接打开）
│   ├── app/src/main/java/top/wxip/gpslink/
│   ├── app/src/main/res/
│   └── app/src/test/        # Robolectric 冒烟测试
├── ftp.MD                   # FTP 凭据（本地文件，已被 .gitignore 排除）
├── README.md / README_EN.md
└── local.properties         # 本机 SDK 路径，不入库，新机器要自建
```

## 4. 源码地图

### 4.1 入口与界面

| 文件 | 职责 |
| --- | --- |
| `MainActivity.kt`（约 1200 行） | 唯一的 Activity。负责：原生界面渲染、事件绑定、权限申请、服务启停、更新检查。**所有 UI 逻辑都在这一个文件里** |
| `res/layout/activity_main.xml` | 根布局：五页容器 + 底部五等分导航 |
| `res/layout/page_link.xml` | 连接页：角色分段、开始/停止、链路信息、定位数据 |
| `res/layout/page_sat.xml` | 卫星页：圆盘 + 信号柱 + 统计 |
| `res/layout/page_access.xml` | 授权页：按系统版本分档的授权状态、按钮、adb 命令 |
| `res/layout/page_settings.xml` | 设置页：连接参数、频率、保活、自启、轨迹、NMEA、日志 |
| `res/layout/page_about.xml` | 关于页：版本、内核、更新、作者链接 |
| `res/values/styles.xml` | 全部控件样式（`GnssCard` / `GnssBtn` / `GnssSeg` / `GnssField` …） |
| `res/values/colors.xml` + `values-night/colors.xml` | 明暗双色板，深色靠资源限定符自动切换 |
| `res/drawable/*.xml` | 卡片、按钮、开关、dock、分段控件的自绘背景 |

### 4.2 核心业务

| 文件 | 职责 |
| --- | --- |
| `core/LinkBus.kt` | 单例事件总线 + `LinkStatus` 状态快照。服务 ↔ 界面唯一通道 |
| `core/Prefs.kt` | SharedPreferences 封装，所有参数持久化 |
| `core/Protocol.kt` | 协议常量（默认端口 7007、NMEA 端口 10110、频率档位） |
| `core/LocationFrame.kt` | 定位帧 + JSON 编解码 + 转 `Location`（含 extras） |
| `core/SatelliteMonitor.kt` | `GnssStatus` 订阅，产出 `SatInfo` |
| `core/Nmea.kt` / `NmeaServer.kt` | NMEA over TCP 输出（10110 端口） |
| `core/TrackRecorder.kt` | 轨迹落盘与列表 |
| `core/Gnss.kt` / `Geo.kt` / `NetInfo.kt` / `RateProbe.kt` / `SatWire.kt` | 星座展示规则、距离、本机 IP、采样实测、卫星快照编码 |
| `service/SendService.kt` | 发送端前台服务（读 GPS → 编码 → UDP/蓝牙） |
| `service/ReceiveService.kt` | 接收端前台服务（收帧 → 注入模拟位置 / 回放轨迹） |
| `service/BootReceiver.kt` | 开机自启 |
| `mock/MockLocationEngine.kt` | `addTestProvider` / `setTestProviderLocation`，以及按系统版本分档的授权检测（4.4-5.1 走系统开关，6.0+ 走 AppOps） |
| `transport/BtSender.kt` / `BtReceiver.kt` | 蓝牙 RFCOMM |
| `ui/SatelliteSkyView.kt` | 卫星圆盘自绘（方位 + 高度角） |
| `ui/SatelliteBarsView.kt` | 信号柱自绘（按行绘制，**自管测量高度**） |
| `ui/OverlayHud.kt` | 保活悬浮小胶囊（可拖动，仅后台显示） |

## 5. 界面是怎么驱动的

这是本项目最需要理解的一层，改动界面必须先看懂这里。

```
SendService / ReceiveService
        │ 状态变化
        ▼
    LinkBus.status  ──回调──►  MainActivity.onStatus()
                                    │
                                    ▼
                            emit(type, data)      ← 唯一 UI 出口
                                    │
                                    ▼
                            render(type, data)     ← 写原生控件
```

`emit()` 原来是往 WebView 注入 JS，现在内部直接调 `render()`。**这意味着所有 `publishXxx()`、所有 `LinkBus.log()` 调用点都不用改。**

### `render` 支持的 type

| type | data 结构 | 渲染到 |
| --- | --- | --- |
| `init` | 配置 JSON（sid/port/host/useBt/intervalMs/injectMs/autoStart/trackEnabled/nmeaEnabled/nmeaPort/version/engine） | 设置页全部输入与开关、关于页版本 |
| `checks` | location/mock/battery/background/keepAlive/overlay | 授权页状态行、保活提示 |
| `access` | sdk/androidName/tier/granted/steps/why/adb/adbCheck | 授权页分档说明与 adb 命令 |
| `status` | running/role/peer/online/localIp/rateHz/lat/lon/accuracy/fixCount/nmeaPort | 连接页徽章与数据、NMEA 地址 |
| `satellites` | items 数组 + source | 圆盘、信号柱、统计（**实际用 `lastSatList` 而非 JSON**） |
| `log` | 字符串数组 | 设置页日志区（取最后 80 行） |
| `tracks` | items 数组（name/points/path） | 轨迹列表；首条路径存进 `uiFirstTrack` 供回放 |
| `bt` | 设备名 | 设置页蓝牙设备行 |
| `notice` | 文本 | Toast |
| `update-check` | tag/latest/current/size | 关于页提示 + 原生 `AlertDialog` |
| `update-progress` | value（0-100） | 关于页「正在下载 x%」 |
| `update-ready` | path/size | 原生对话框，确认后安装 |
| `update-log` | text | 对话框展示更新日志 |
| `motion` | — | 忽略（原生不需要） |

### 界面事件怎么回到业务

界面侧统一调 `act(action, json)`，它内部走 `Bridge().dispatch(action, payload)`——**保留了原来 33 个桥接动作的完整 switch**，所以新增界面控件时只要调对应的 action 即可，不用重写业务。

已有 action：`start` / `stop` / `configure` / `keepalive` / `overlay-permission` / `mock` / `battery` / `background` / `permissions` / `recheck` / `pick-bt` / `probe` / `autostart` / `copy` / `track-toggle` / `tracks` / `track-play` / `track-delete` / `nmea` / `orientation` / `check-update` / `download-update` / `install-update` / `skip-version` / `fetch-log` / `open-url`。

## 6. 编译

### 6.1 环境

| 项 | 本机路径 |
| --- | --- |
| JDK | `C:/Users/Administrator/android-tools/jdk-17.0.21+9` |
| Android SDK | `C:/Users/Administrator/android-tools/sdk` |
| SDK 组件 | `platforms/android-34`、`build-tools` |

`local.properties` 必须写成（**正斜杠 + 冒号转义**，写错会报「文件名、目录名或卷标语法不正确」）：

```properties
sdk.dir=C\:/Users/Administrator/android-tools/sdk
```

### 6.2 命令

```bash
# 只验证 Kotlin 编译（最快，约 1 分钟）
./gradlew :app:compileReleaseKotlin --console=plain -Dorg.gradle.daemon=false

# 资源 + 布局检查（改了 layout 必跑）
./gradlew :app:processDebugResources --offline

# 出可安装的调试包（本地唯一能出的包）
./gradlew :app:assembleDebug --offline
# 产物：gpslink/app/build/outputs/apk/debug/app-universal-debug.apk

# 启动与切页冒烟测试（改了布局必须跑，约 2 分钟）
./gradlew :app:testDebugUnitTest --offline
```

### 6.3 为什么本地出不了正式包

`app/build.gradle.kts` 的 release 签名读 `keystore/gpslink.jks`，**本机没有这个文件**（keystore 不进仓库）。所以：

- 本地只能 `assembleDebug`
- 正式包由 CI 用 Secrets 还原 keystore 后签名
- 调试包与正式包签名不同，**安装前必须先卸载**，会清数据

## 7. 发版（每次都要按这个顺序）

1. 改 `gpslink/app/build.gradle.kts`：`versionCode` +1、`versionName` 改新版本
2. 改 `docs/update-log.txt` **头部两行** + 追加新版本条目：
   ```
   最新版本：1.3.8
   安装包：gnss-link-1.3.8.apk
   ```
   ⚠️ 头部没改，车机端不会提示更新（CI 只会 warning 不会失败）
3. 改 `README.md` 的 `## 当前版本 x.y.z`
4. 编译 + 跑测试（见 6.2）
5. `git add -A && git commit -m "vX.Y.Z：..."`，**在 main 上**提交
6. `git push origin main`
7. 等 CI（约 3-5 分钟）→ 自动做四件事：编译 → 校验签名 → 发 GitHub Release → 同步 FTP
8. 验证（必做）：
   ```bash
   # Release 是否有新 tag
   curl -s -H "Authorization: token <PAT>" \
     https://api.github.com/repos/zhaokelei/gnss-link/releases/latest | grep tag_name
   # 官网能否下载（应与本地体积一致）
   curl -s -o /dev/null -w "%{http_code} %{size_download}\n" \
     https://gps.iqiq.cn/download/gnss-link-X.Y.Z.apk
   # 日志头部是否已更新
   curl -s "https://gps.iqiq.cn/download/update-log.txt?t=$(date +%s)" | head -5
   ```

## 8. CI 细节

`.github/workflows/build-apk.yml`，触发条件：push 到 `main`/`master`、打 `v*` tag、PR、手动。

步骤：检出 → JDK 17 → Android SDK → 缓存 → **还原 keystore** → 编译（32/64/通用）→ 校验签名 → 重命名 → 上传 artifact ×3 → 发 Release → **同步 FTP**。

需要的 Secrets：

| 名称 | 用途 |
| --- | --- |
| `SIGNING_KEY_BASE64` | keystore 的 base64 |
| `SIGNING_KEY_ALIAS` | 别名 |
| `SIGNING_STORE_PASSWORD` | keystore 口令 |
| `SIGNING_KEY_PASSWORD` | key 口令 |
| `FTP_HOST` | FTP 主机（含端口） |
| `FTP_USER` | FTP 用户 |
| `FTP_PASS` | FTP 密码 |

`legacy-smoke.yml` 是**手动触发**的云模拟器冒烟：装包、启动、检查不白屏不崩溃。注意它原本面向 WebView 界面，v1.3.6 删除 `assets/ui` 后，里面若有页面元素检查需要复核。

## 9. FTP 分发（手工同步）

### 9.1 凭据

写在仓库根目录 `ftp.MD`（**已被 .gitignore 排除，不会进仓库**）：

| 项 | 值 |
| --- | --- |
| 地址 | `ftp://140.143.141.192:21` |
| 用户 | `gpslink` |
| 密码 | 见 `CREDENTIALS.md`（本地文件，已 gitignore） |
| 目录 | `/download/` |

> 完整凭据（FTP 密码、两个 GitHub PAT、keystore 口令、Secrets 取值、代理地址）都在
> 仓库根目录的 `CREDENTIALS.md` 里。**该文件已被 `.gitignore` 排除，不会进仓库**——
> 这是刻意的：本仓库公开，明文令牌提交上去会被 GitHub 自动吊销。
> 交接时它随压缩包一起给，但不要提交、不要贴到公开渠道。

> ⚠️ 这组凭据**曾经硬编码在旧提交里**（`35648bf`、`31971e7` 的 `MainActivity.kt`），仓库公开，视为已泄露。正式接手第一件事应该是去服务器改密码，然后同步更新：FTP 服务器、GitHub Secrets 的 `FTP_PASS`、本地 `ftp.MD`。

### 9.2 手工上传

```bash
H="140.143.141.192:21"; U="gpslink"; P="<密码>"

# 安装包（版本号必须与文件名一致）
curl -T "gpslink/app/build/outputs/apk/debug/app-universal-debug.apk" \
  "ftp://$U:$P@$H/download/gnss-link-1.3.8.apk" -o /dev/null -w "%{http_code}\n"

# 更新日志（覆盖同名文件）
curl -T "docs/update-log.txt" \
  "ftp://$U:$P@$H/download/update-log.txt" -o /dev/null -w "%{http_code}\n"
```

成功返回 `226`。传完必须回读体积校验：

```bash
curl -s -I "ftp://$U:$P@$H/download/gnss-link-1.3.8.apk" | grep -i content-length
```

### 9.3 常见报错

| 报错 | 原因 | 处理 |
| --- | --- | --- |
| `552 Disk full - please upload later` | 服务器磁盘满 | 联系用户清理，或删旧版本 APK（每个约 5MB） |
| `550` / `Server denied you to change to the given directory` | 账号没有该目录权限，或账号错 | `curl -l ftp://用户:密码@主机/` 看根目录实际内容；本项目账号是 `gpslink`，不是 `xiaolei`（那是另一个站点） |
| `curl: (9)` | 同上 | 同上 |
| 传完体积不符 | 只写了一半 | 重传；服务器 15 分钟无操作会断开 |

## 10. 踩过的坑（务必先读）

按影响从大到小：

1. **自定义 style 必须自带 `layout_width` / `layout_height`**
   v1.3.6 就是因为 `GnssCard`、`GnssLabel`、`GnssDockLabel` 漏了这两个属性，使用处只写 `style="@style/GnssCard"`，inflate 时抛 `You must supply a layout_width attribute` → **一打开就闪退**，而 **aapt 编译期完全不报**。改了布局一定要跑 Robolectric 测试。

2. **本机无法渲染界面**
   模拟器 AEHD 加速被拒（`ERROR_ACCESS_DENIED`），system-image 反复下载失败（目录只留 1K 占位）。所以视觉问题只能靠：Robolectric 跑逻辑 + 用户装包看。想截图就走 CI 的 `legacy-smoke.yml`（云端有 KVM）。

3. **原生没有 ready 握手**
   原来 JS 会发 `ready` 触发首屏数据，原生版必须在 `onCreate` 里手动调 `publishConfig/publishChecks/publishTracks/publishBtLabel/onStatus/publishSatellites`，否则界面一片空白。

4. **`android:selected` 不是布局属性**
   dock 选中态必须代码 `isSelected = true`，并且子控件要 `android:duplicateParentState="true"` 才会跟着变色。`style` 的 `parent` 也不能写 View 类名（`parent="LinearLayout"` 会 aapt 报错）。

5. **SwitchCompat 的图片属性是 `android:thumb` / `android:track`**
   不是 `app:thumb` / `app:track`，写错会编译失败。

6. **API 19 禁用清单**
   不能用：`ripple`、`paddingHorizontal`、`marginHorizontal`、`backgroundTint`、`foreground`、`gap`、`@supports`、`color-mix`。按压态一律用 selector。minSdk 是 19，别写 21+ 属性。

7. **历史坑（WebView 时代，留作背景）**
   老车机内核不支持 ES module，`<script type="module">` 会被整段忽略 → 整页按钮无响应只留焦点框。当时的兜底思路是启动自检降级到纯 ES5 精简版；`assets/ui` 已在 v1.3.7 删除，相关代码不再存在。**如果看到"车机点不动"的旧反馈，那是历史问题，已由原生重写解决。**

8. **`joblog.zip` 泄露过密钥**
   早期提交里打包过构建日志，含签名信息。历史不能通过删除文件清除，已在 `run_secret_scanning` 里确认。若之后要彻底清理历史，用 `git filter-repo` 后 force push，代价是所有人要重新 clone。

9. **FTP 账号曾经配错**
   CI Secrets 里的 `FTP_USER` 一度是 `xiaolei`（另一个站点），表现为上传 552 以为磁盘满，实际是目录不存在。已改为 `gpslink`。

## 11. 硬性约定（用户明确要求过的）

1. **全程简体中文**：注释、提交信息、文档、界面文案。代码标识符可以英文。
2. **不要出现「基于 wxip/gnsslink 重做」这类上游引用**，已全部删除过。
3. **凭据不进仓库**：keystore、`ftp.MD`、PAT 一律 gitignore。README 也不写具体分发域名路径。
4. **每次发版必须改 `docs/update-log.txt` 头部**，否则车机收不到更新提示。
5. **只在 main 上提交推送**，CI 只跟 main。
6. 站点信息对外只写主页 `www.iqiq.cn`，更新源地址编译进应用内，不在 README 里列出。

## 12. 已知未完成 / 建议下一步

| 项 | 说明 |
| --- | --- |
| **界面视觉未在真机验证** | v1.3.6/1.3.7 只过了 Robolectric 逻辑测试，没有任何人在车机上看过实际效果。装包后重点看：dock 选中态、开关样式、输入框光标色、横屏两栏是否合理 |
| **Android 4.4 未实测** | minSdk 19 但没在真机上跑过。风险点：`SatelliteSkyView` 自绘、`SwitchCompat` 主题依赖 |
| **更新流程未端到端验证** | 原生 `AlertDialog` 的三条路径（立即更新 / 跳过 / 稍后）没有真实升级场景验证过 |
| **ABI 分包未生效** | Release 里三个 APK 体积完全一样（都是 universal），说明 split 配置没起作用，可以优化 |
| **轨迹管理偏弱** | 设置页只支持"回放最新一条"，不能选择、删除 |
| **保活悬浮窗视觉未验证** | `OverlayHud` 的小胶囊尺寸/位置只在代码层面确认过 |
| **云端截图 workflow 需复核** | `legacy-smoke.yml` 原本针对 WebView 页面，删 assets 后可能失效 |

## 13. 快速排障

```bash
# 装包看崩溃（需要 adb 连上设备）
adb install -r gpslink/app/build/outputs/apk/debug/app-universal-debug.apk
adb logcat -c && adb logcat | grep -E "AndroidRuntime|gpslink"

# 模拟位置授权是否生效
adb shell appops get top.wxip.gpslink android:mock_location

# 用命令行强制授权（车机裁掉开发者选项时）
adb shell appops set top.wxip.gpslink android:mock_location allow

# 验证 NMEA 输出
nc <手机IP> 10110

# 手动触发云端冒烟（需在仓库有权限）
curl -X POST -H "Authorization: token <PAT>" \
  -H "Accept: application/vnd.github+json" \
  https://api.github.com/repos/zhaokelei/gnss-link/actions/workflows/legacy-smoke.yml/dispatches \
  -d '{"ref":"main"}'
```

国内访问 `api.github.com` 需要代理（本机常用 `127.0.0.1:10999`）。

## 14. 版本与文件对照

| 版本 | 关键变更 |
| --- | --- |
| 1.3.3 | 授权独立成页，底部五页签 |
| 1.3.4 | 后台保活悬浮窗；修复车机全站按钮无响应（module 降级 + 焦点环） |
| 1.3.5 | 控件外观全部自绘，去掉浏览器默认控件 |
| 1.3.6 | 界面彻底原生化，移除 WebView |
| 1.3.7 | 修复 1.3.6 启动闪退（样式缺布局尺寸）；删除网页资源；加冒烟测试 |


## 15. 凭据与环境速查

完整明文在 `CREDENTIALS.md`。这里只列「有哪些、在哪、怎么改」，方便快速定位。

| 凭据 | 位置 | 说明 |
| --- | --- | --- |
| GitHub 账号 | `zhaokelei` | SSH 已配好，直接 `git push` 即可，无需令牌 |
| GitHub classic PAT | `CREDENTIALS.md` §1.1 | 调 API 用；国内必须走代理 `127.0.0.1:10999` |
| GitHub fine-grained PAT | `CREDENTIALS.md` §1.1 | 备用，classic 失效时用 |
| FTP 密码 | `CREDENTIALS.md` §2 | 同时存在 `ftp.MD`；改密码要同步服务器 / Secrets / 本地三处 |
| 签名 keystore | `keystore/gpslink.jks`（本地缺失） | 只能从 Secrets 的 `SIGNING_KEY_BASE64` 解码还原 |
| keystore 口令 | `gpslink2026`（别名 `gpslink`） | 写在 `build.gradle.kts` 的 fallback 里，本身就是公开信息 |
| GitHub Secrets | 仓库 Settings → Secrets | 8 项，清单与取值见 `CREDENTIALS.md` §4 |
| 代理 | `127.0.0.1:10999` | 仅本机需要，访问 `api.github.com` 时加 `-x` |

调用 API 的正确姿势（令牌从文件里读，不要写死在命令历史里）：

```bash
PAT=$(grep -A1 'classic PAT' CREDENTIALS.md | tail -1 | tr -d ' `')
curl -s -x http://127.0.0.1:10999 -H "Authorization: token $PAT" \
  https://api.github.com/repos/zhaokelei/gnss-link/releases/latest | grep tag_name
```

三条安全底线：

1. `CREDENTIALS.md`、`ftp.MD`、`*.jks` 一律不进仓库（已在 `.gitignore`）。
2. 令牌只用于 API 调用，不进 APK、不进日志、不进截图。
3. 一旦发现泄露：GitHub 侧吊销令牌；FTP 侧改密码；keystore 无法更换（换了老用户就没法覆盖升级），只能靠 SECRETS 保密。

---

有任何和本文档不一致的地方，以代码为准，并顺手更新这份文档。
