# DSHxpandh

**DSHxpandh — a lightweight LSPosed module for runtime Java hooking on Android — see encrypted traffic, override return values, rewrite arguments.**

轻量 LSPosed 模块：在任意 App 进程内 hook Java 方法，看明文、改返回值、改入参。

包名 `io.github.lyx2839272_coder.dshxpandh`｜命令行脚本仍叫 `xhook.sh`（沿用旧名，未改动）

## What it does / 能做什么

| Capability | Config | Example |
|---|---|---|
| 抓加密明文 | `mode: log` | hook `Cipher.doFinal`，拿到 AES 前后的数据 |
| 改返回值 | `mode: return` | 让 `isVip()` 永远返回 true |
| 改入参 | `mode: args` | 把设备 ID、定位、时间戳换成假的 |
| 条件匹配 | `when` | 只对含 `su` 的命令生效，其他调用放行 |
| 调静态方法 | `actions: call` | 主动读某个值 |
| 读 Cookie | `actions: cookie` | 拿 WebView 的 cookie |
| 引用内置配方 | `"recipe": "名字"` | 不用重装 APK 就换 hook 目标 |

## Install / 安装

> **只装 APK 就能用，三种做法：**
>
> **① 什么都不做** —— 勾上作用域后自动抓加解密明文（见「零脚本模式」）。
>
> **② 放一个自己的配置文件（不用开终端）**
>
> ```
> /sdcard/Download/xhook/<包名>.json
> ```
>
> ⚠️ **前提：目标 App 必须有「所有文件访问」权限**（`MANAGE_EXTERNAL_STORAGE`）。
> 浏览器、网盘、文件管理器通常有；聊天工具、游戏、大部分 App 没有。
>
> 没有这个权限的 App，模块读不到 sdcard 文件，日志会打 `usr-noperm <路径>`。
> 这种情况只能走 ③。
>
> **③ 用脚本（需要 root）** —— 把配置写进 App 私有目录，任何 App 都能改。
```bash
# 1. 装模块（或用 xhook-install.sh 自解包）
pm install -r xhook-v30.apk
```

```
# 2. LSPosed 里启用
模块列表 → DSHxpandh → 打开开关 → 勾作用域 → 退出页面（不退出不保存）

# 3. 重启目标 App（不是重启手机）
```

## Zero-config mode / 只装 APK 能做什么

**不装 `xhook.sh` 也能用。** 模块自带内置配置，装完勾上作用域就生效。

```
装 APK + LSPosed 勾作用域
  ↓ 没有外部配置时自动回退
  ↓
#own  assets/profiles/<包名>.json   ← 针对特定 App 的内置配置
#def  assets/profiles/default.json  ← 任何 App 的通用兜底
```

**`default.json` 自动 hook 这 6 个点，只记录、不改行为：**

| 目标类 | 方法 | 拿到什么 |
|---|---|---|
| `javax.crypto.Cipher` / `Mac` | `doFinal` | AES / HMAC 的明文和密文 |
| `java.security.MessageDigest` | `digest` | MD5 / SHA 的原文 |
| `java.security.Signature` | `sign` | 签名原文 |
| `android.util.Base64` | `encode` / `decode` | Base64 前后 |
| `java.net.URLEncoder` | `encode` | URL 编码前 |

实测（删掉目标 App 全部外部配置后重启）：

```
ready v25 #own hooks=1 sample=2/1000ms head=300 tail=300
hooked javax.crypto.Cipher.doFinal x7
```

### 能力分层

| 你能拿到 | 需要什么 |
|---|---|
| 加解密明文、摘要原文、签名原文 | **只要 APK** |
| 换配方（伪造定位 / 改设备号 / 拦 su） | 要写配置 → 需要 root shell |
| 改返回值、改入参 | 同上 |
| 查日志（`/data/adb/lspd/log/`） | root |

想要第二档，装 `xhook.sh`（单文件，无依赖，`bash xhook.sh deps` 自检）。
## Usage / 用法

```bash
./xhook.sh deps                        # 自检（root / python3 / nsenter）
./xhook.sh list                        # 哪些 App 在跑、有没有外部配置
./xhook.sh recipe list                 # 7 个内置配方
./xhook.sh recipe apply cipher-log com.tencent.mm
./xhook.sh restart com.tencent.mm
./xhook.sh log com.tencent.mm
```

## Built-in recipes / 内置配方

| Recipe | Effect |
|---|---|
| `cipher-log` | 抓 Cipher / Mac 加解密明文 |
| `fake-location` | 伪造经纬度（39.9042, 116.4074） |
| `fake-ua` | 改 WebView User-Agent |
| `fake-device-id` | IMEI / IMSI / DeviceId 返回 0 |
| `fake-android-id` | 只改 `android_id` 这个 key |
| `block-logs` | 关掉日志输出 |
| `block-su` | 拦 su 命令，伪装无 root |

## Config format / 配置格式

写进 `/data/user/0/<pkg>/files/xhook.json`（App 私有目录）：

```json
{
  "process": "main",
  "sample": 3,
  "window_ms": 1000,
  "head": 200,
  "tail": 200,
  "max_logs": 400,
  "hooks": [
    {"class": "javax.crypto.Cipher", "method": "doFinal", "mode": "log"},
    {"class": "com.x.User", "method": "isVip", "mode": "return", "return_value": "true"},
    {"class": "com.x.Device", "method": "getId", "mode": "args", "set": [{"i":0,"v":"FAKE"}]},
    {"class": "java.io.File", "method": "exists", "mode": "return",
     "when": {"i":0,"contains":"su"}, "return_value": "false"}
  ],
  "actions": [
    {"type": "call", "class": "java.util.TimeZone", "method": "getDefault"},
    {"type": "cookie", "url": "https://example.com"}
  ]
}
```

## Config lookup order / 配置回退顺序

找到第一个存在的就用它，不再往下找：

| # | 路径 | 标记 | 谁能写 |
|---|---|---|---|
| 1 | `/data/user/0/<pkg>/files/xhook.json` | `#ext` | 需要 root（App 私有目录） |
| 2 | `/sdcard/Download/xhook/<包名>.json` | `#usr` | **任何文件管理器** |
| 3 | `/sdcard/xhook/<包名>.json` | `#usr` | **任何文件管理器** |
| 4 | APK 内置 `assets/profiles/<包名>.json` | `#own` | 内置，针对特定 App |
| 5 | `/sdcard/DSH-Output/xhook/config.json` | `#glo` | 全局，所有 App 共用 |
| 6 | APK 内置 `assets/profiles/default.json` | `#def` | 内置兜底 |

**日志里能看模块最终用了哪个**：

```
core [com.xxx] ready v26 #usr hooks=2 sample=3/1000ms head=200 tail=200
                          ^^^^ 这里就是来源
```
## Verify / 怎么确认真的在跑

**不要看 `/proc/<pid>/maps`。** LSPosed 2.x 用 zygisk 注入，maps 里没有 `lsp` 字样（本机实测 0 条，但模块正常工作）。

看日志：

```bash
adb shell su -c 'tail -30 /data/adb/lspd/log/modules*.log | grep "core \["'
```

成功的标志：

```
core [com.xxx] ready v26 #ext hooks=1 sample=3/1000ms head=200 tail=200
core [com.xxx] hooked javax.crypto.Cipher.doFinal x7
core [com.xxx] hooked javax.crypto.Mac.doFinal x3
```

- **`ready`** — 模块加载成功，后面的 `#ext` / `#usr` / `#own` / `#glo` / `#def` 是配置来源
- **`hooked <方法> x7`** — 挂上了 7 个重载；`x0` 表示这个类在目标 App 里被替换了，挂不上

**没有 `ready` 的三种原因**：

| 现象 | 原因 |
|---|---|
| 完全没有该 App 的行 | 不在 LSPosed 作用域 |
| 勾了但没日志 | 勾完没退出设置页（ColorOS 不保存） |
| Chrome 永远没有 | 有独立 zygote，LSPosed 注不进 |

用脚本一眼看：

```bash
xhook list
```

```
包名                            运行  注入  外部配置
------------------------------------------------------------------
com.UCMobile                     yes   yes   288
```

`注入=no` 但 `运行=yes` → 重启那个 App；还不行就是上面三种原因。
## Channels / 权限通道

`xhook.sh` 自动探测可用的最高权限通道，按顺序：

```
psh  →  su  →  rish  →  shizuku
```

`xhook.sh deps` 会打印当前通道和能力：

```
channel=psh python3=1 nsenter=1 appdir=1 lspdlog=1
        ^^^                       ^^^^^^^ ^^^^^^^
        通道                      写App私有目录 / 读lspd日志
```

### 能力矩阵

| 操作 | root (psh/su) | rish-root (Sui) | rish-shell (Shizuku ADB) |
|---|---|---|---|
| 写 App 私有目录 (`#ext`) | ✅ 需 nsenter | ✅ | ❌ |
| 写 `/sdcard/Download/xhook/` (`#usr`) | ✅ | ✅ | ✅ |
| 读 `/data/adb/lspd` 日志 | ✅ | ✅ | ❌ |
| 读 `logcat` | ✅ | ✅ | ✅ |
| `pm install` / `pm grant` | ✅ | ✅ | ✅ |
| `am force-stop` / `am start` | ✅ | ✅ | ✅ |

### 无 root 时脚本怎么退化

| 命令 | 退化行为 |
|---|---|
| `push_cfg` | 写 `/sdcard/Download/xhook/<包名>.json`，并提示 `pm grant` |
| `log` | 改读 `logcat`（而不是 `/data/adb/lspd/log`） |
| `build` | 中转目录不变，`pm install` 照常 |

### rish 的两个前提（容易漏）

**1. `rish` 文件要自己建。** 它不是随 Shizuku 装好的，要按 Shizuku App 内的教程导出 `rish` + `rish_shizuku.dex`。

**2. 必须设 `RISH_APPLICATION_ID`** 为终端 App 的包名，否则默认值 `PKG` 会直接失败：

```bash
export RISH_APPLICATION_ID=com.termux
rish -c 'id'
```

### 关键限制：Shizuku 跑不了 hook

**rish 是「借 shell 命令」，不是「注入进程」。**

```
Shizuku 能做：am / pm / settings / cmd / dumpsys  →  让系统替它干活
hook 需要：  在目标 App 进程内改 ART 方法表      →  必须在那个进程内部
```

模块入口 `handleLoadPackage` 只能由 LSPosed（或 LSPatch 注入的等价物）调用。**没有 root 就没有 LSPosed，Shizuku 补不上这一层。**

无 root 环境唯一的路是 LSPatch 重打包（`inserting dex and so into the target APK`），但它 v0.6 停在 2023-11-23，Android 16 未验证。

### 未验证声明

**rish 分支没有实测**：本机未安装 Shizuku，`command -v rish` 为空，只有 psh 路径被真正跑过。

要验证需要：装 Shizuku → 导出 rish 文件 → 设 `RISH_APPLICATION_ID` → 跑 `xhook.sh deps` 看 `channel=` 是否为 `rish-shell` 或 `rish-root`。
## Architecture / 架构

```
xhook.sh          命令行接口，写配置 + 重启 + 读日志
  ↓ nsenter（突破 App namespace 隔离）
/data/user/0/<pkg>/files/xhook.json
  ↓ LSPosed 注入目标 App 进程时读取
HookEntry.java    hook 引擎：解析配置 → 装 hook → 输出到 XposedBridge.log
  ↓
/data/adb/lspd/log/modules*.log
```

## Build / 编译

```bash
export ANDROID_JAR=/path/to/android.jar
export XAPI_JAR=/path/to/xapi.jar
export KEYSTORE=/path/to/your.ks
cd module && bash build.sh
```

## Known limits / 已知限制

| 限制 | 原因 |
|---|---|
| 必须有 root + LSPosed | 入口 `handleLoadPackage` 由 LSPosed 在 zygote fork 时调用，没有框架 APK 就是普通应用 |
| 有独立 zygote 的 App（Chrome）注入不进 | LSPosed 机制限制 |
| 只 hook Java，不碰 native .so | 当前实现范围 |
| 改配置要重启目标 App | 配置在进程启动时读一次 |
| `mode: block` 未实现 | 用 `return` 代替 |
| ColorOS 上 UI 保存的配置读不到 | prefs 不落盘（已移除 UI） |

## Why not frida / 为什么不用 frida

frida 需要独立进程 + ptrace 注入，内存占用大；低内存手机上会触发系统杀进程。

LSPosed 在 **zygote fork 时**把模块塞进新进程的 classpath，**零额外进程、几乎零内存开销**。

## License

MIT