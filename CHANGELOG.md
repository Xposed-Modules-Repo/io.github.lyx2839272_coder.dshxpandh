# Changelog

格式按 [Keep a Changelog](https://keepachangelog.com/)，版本号对应 `versionCode`。

## v27 — 2026-11-24

### 新增

- sdcard 配置读不到时输出 `usr-noperm <路径>`，区分「没配」和「没权限」

### 修正（重要）

- **v26 的「不开终端」说法不严谨**：模块读 `/sdcard` 依赖**目标 App** 的存储权限
  - 本机 UC 有 `MANAGE_EXTERNAL_STORAGE`（所有文件访问），所以能读
  - 无此权限的 App 读不到，会静默回退到内置配置
  - README 已改成「② 不用开终端（前提：目标 App 有所有文件访问权限）」

---
## v26 — 2026-11-24

### 新增

- **按包名的 sdcard 配置**：`/sdcard/Download/xhook/<包名>.json`（日志标记 `#usr`）
  - 优先级高于 APK 内置的 `#own`，低于 App 私有目录的 `#ext`
  - 意义：**不用命令行、不用脚本**改配置 —— 用手机自带文件管理器新建一个 json 即可
  - 实测：`ready v26 #usr hooks=2`，`hooked TelephonyManager.getDeviceId x2`
- 备用路径 `/sdcard/xhook/<包名>.json`

### 修复

- 有内置配置的 App（UC / 浏览器）之前无法被 sdcard 配置覆盖，#own 挡住了 #glo

---

## v25 — 2026-11-24

### 移除

- **UI（ConfigActivity）** —— ColorOS 上 `shared_prefs` 写不进去，勾选的配方注入进程读不到
- `XSharedPreferences` 读取代码（55 行）
- `xposedsharedprefs` meta-data

### 结果

- APK 30,298 → **26,202 字节**（-13.5%）
- 配置全部走文件（`#ext` / `#usr` / `#own` / `#glo` / `#def`），不依赖 UI

---

## v24 — 2026-11-24

### 尝试（未成功）

- `onCreate` 第一行强制 `getSharedPreferences().commit()`，想让 ColorOS 建出数据目录
- 结论：数据目录由系统在首次启动时创建；root 手工建目录后 App 仍不落盘，这条路走不通

---

## v23 — 2026-11-24

### 新增

- UI 支持导入自己的 JSON 配置（`ACTION_GET_CONTENT` 选文件 → 存 `custom` 字段）
- 模块合并 `custom` 的 hooks / actions / 采样参数

---

## v22 — 2026-11-24

### 改进

- UI 字号放大：标题 28 / 复选框 21 / 说明 15 / 警告 14
- 加「配置归属」说明块

---

## v21 — 2026-11-24

### 新增

- UI 加配置归属提示：哪些由模块自己存、哪些 DSH 可写、作用域要在 LSPosed 里勾

---

## v20 — 2026-11-24

### 新增

- 每个配方的 checkbox 下加一行中文说明（做什么、影响什么）

---

## v19 — 2026-11-24

### 新增

- `ConfigActivity`：勾选配方 → 存 `SharedPreferences("xhook")`
- 模块侧用 `XSharedPreferences` 读勾选结果并合并内置配方
- `#glo` 从 1 个路径扩到 4 个候选

---

## v17 – v18 — 2026-11-23

### 新增

- 7 个配方编进 APK `assets/recipes/`：`cipher-log` / `fake-location` / `fake-ua` / `fake-device-id` / `fake-android-id` / `block-logs` / `block-su`
- 配置里写 `"recipe": "名称"` 即套用，不用重装 APK

### 修复

- `build.sh` 只打包 `assets/xposed_init`，导致 `assets/profiles` 和 `assets/recipes` 全丢

---

## v1 – v16 — 2026-11-22 ~ 11-23

### 核心能力（五类）

| 能力 | 配置 | 实测 |
|---|---|---|
| 抓加解密明文 | `mode: log` | `IN/OUT javax.crypto.Cipher.doFinal` |
| 改返回值 | `mode: return` | `OVR android.util.Log.isLoggable -> false` |
| 改入参 | `mode: args` | `ARG ... 0:[ByteArrayPool]->[FAKE-TAG]` |
| 条件匹配 | `when: {i, contains}` | 只在参数含关键词时生效 |
| 调静态方法 | `actions: call` | `ACT java.util.TimeZone.getDefault -> Asia/Shanghai` |
| 读 Cookie | `actions: cookie` | `COOKIE https://... -> null` |

### 关键修复

- **Application 时序** —— `handleLoadPackage` 时 `currentApplication()` 为 null，`CookieManager` 必 NPE；actions 改挂 `Application.onCreate` 的 `afterHookedMethod`
- **namespace 隔离** —— 全局视角的 `/data/user/0/<pkg>` 与 App 自己的不是同一份，写配置必须 `nsenter -t <pid> -m --`
- **版本漂移** —— 反复覆盖安装但 versionCode 不变时 LSPosed 认为模块没变更，一直用旧路径；`build.sh` 自动同步 `VER` 常量与 `versionCode`
- **采样节流** —— `sample` / `window_ms` 共用配额，保证 IN/OUT 成对
- **大 buffer** —— `head` / `tail` 首尾截断，中间显示 `(+N B)`
- **不可打印字节** —— 输出 `\xx` 而不是全变成 `.`
- **hook 冲突** —— 明确输出 `hook-conflict <方法> <异常>`
- **findClass 失败** —— 降级到 `Class.forName(cls, false, cl)`

### 早期审计（12 项）

无节流拖慢 App、日志无限膨胀、大 buffer 只见开头、子进程重复注入、return_value 类型少、配置无热更新、hook 冲突静默、findClass 失败即死、description 暴露、TAG 可检索、无 handleUnhook、arr 多参数开销、system 包无效 IO —— 除「配置热更新」和「handleUnhook」外全部修复（该两项按用户要求不上）。
