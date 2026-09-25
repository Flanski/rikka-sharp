# Rikka# — AI Agent 与剧情文字游戏方向

> **Rikka#**（读作 Rikka Sharp）是 [Rikka+](https://github.com/heikeyangle-code/rikkahub-plus) 的小幅升级分支。
> `#` 表示在原地向上走了一小步：**去掉与主线无关的部分，把力量集中在两个方向** ——
> **AI Agent 软件开发** 与 **剧情 AI 文字游戏**。

[**简体中文**](README.md) | [**English**](README_EN.md)

---

## 它是什么

一个跑在手机上的原生 Android AI 客户端（Kotlin + Jetpack Compose + Material You + ObjectBox），
可对接任意 OpenAI / Anthropic / Google 兼容 API，并在本地提供**可读写的执行环境**与
**完整的角色扮演叙事栈**。

两条主线：

### 一、AI Agent 软件开发

让模型真正"动手"而不只是聊天：

| 能力 | 说明 |
|---|---|
| **工作区（Workspace）** | 内置 proot + Ubuntu 24.04 rootfs，AI 拥有真实的 Linux 文件系统与 shell（`workspace_read_file` / `write_file` / `edit_file` / `shell`） |
| **SSH 客户端** | 直接操作远程开发机：`ssh_exec` / `ssh_upload` / `ssh_download` / `ssh_ls` / `ssh_hosts`。**凭据与主机一一绑定**，AI 只能按主机别名调用，从参数层面杜绝"拿 A 的密钥连 B" |
| **网络检索** | `fetch_url`（正文抽取 + HTML→Markdown + 分页续读）、`github_search`（官方 Search API）、`wikipedia_search`，**全部无需 API Key** |
| **本地执行** | `execute_command`（shell）、`execute_python`（Chaquopy CPython）、`eval_javascript`（QuickJS 持久上下文）、`calculator` |
| **文件与任务** | `file_*` 读写编辑、`task_*` 子任务、记忆、技能（Skills）、MCP 客户端 |
| **工作区外挂** | Skills 目录、MCP 服务器、可读写的 `/workspace`、`/upload`、`/tool_outputs` |

### 二、剧情 AI 文字游戏

继承自 Rikka+ 的完整 SillyTavern 生态兼容层：

| 能力 | 说明 |
|---|---|
| **角色卡** | SillyTavern V2/V3 角色卡无损导入导出（PNG / JSON），含内嵌世界书编辑器 |
| **世界书（Lorebook）** | 官方注入语义对齐：选择性触发 / 分组 / 递归扫描 / 粘性 / token 预算 |
| **剧情工具** | 导演备注（Author's Note）、人设（Persona）、宏引擎 2.0、斜杠命令 |
| **群聊** | 多角色同场对话与发言调度 |
| **提示词工程** | 可编程提示注入、占位符系统、预设管理 |

---

## 相对 Rikka+ 的改动

| 类别 | 改动 |
|---|---|
| **移除** | 命理排盘整套功能（十二套引擎 / 解读模板 / Python 路由 / 相关 CI 步骤）——与上述两个方向无关，且占据约 48MB 体积与大量构建时间 |
| **新增** | `fetch_url`、`github_search`、`wikipedia_search` 三个无 Key 检索工具（`WebResearchTools.kt`） |
| **新增** | SSH 客户端：5 个工具 + 设置页「SSH 客户端」（主机与密钥管理、连接测试） |
| **调整** | `applicationId` 改为 `me.rerere.rikkasharp`，显示名 `RIKKA #`，可与 RikkaHub / Rikka+ 共存 |
| **精简** | CI 工作流由 227 行减至 48 行；移除命理 Python 依赖包（`offline_pkgs/`，33MB）与 18 个命理构建脚本 |

> 如果你需要命理功能，请使用上游 [Rikka+](https://github.com/heikeyangle-code/rikkahub-plus)。

---

## 上游关系

```
rikkahub/rikkahub                 (上游主干，AGPL-3.0)
      └── heikeyangle-code/rikkahub-plus   (Rikka+：酒馆 + 命理)
                └── 本仓库 Rikka#          (去掉命理，强化 Agent 与剧情游戏)
```

三个仓库同为 **AGPL-3.0**。本仓库保留了完整的 LICENSE 与上游归属信息。

---

## 构建

本仓库自带 GitHub Actions 工作流（`.github/workflows/build.yml`），**push 即触发**，无需配置任何 secrets
（签名用的 `app/app.key` 与 `app/google-services.json` 已随仓库提供）：

```bash
git clone <本仓库地址>
cd <仓库目录>
git push origin main        # 或任何在 build.yml 的 branches 列表中的分支
```

约 20–40 分钟后，在仓库 **Actions** 页面下载产物：`rikkahub-plus-fresh`（`app/build/outputs/apk/release/*arm64-v8a*.apk`）。

本地构建需要 JDK 17+、Android SDK（compileSdk 37）、NDK（`workspace` 模块含 C++）与 Node/pnpm：

```bash
echo "storeFile=app.key"      >  local.properties
echo "storePassword=android"  >> local.properties
echo "keyAlias=androiddebugkey" >> local.properties
echo "keyPassword=android"    >> local.properties
./gradlew assembleRelease -x :web:buildWebUi
```

---

## 致谢

- **[rikkahub/rikkahub](https://github.com/rikkahub/rikkahub)** — 上游：多提供商 Android LLM 客户端
- **[heikeyangle-code/rikkahub-plus](https://github.com/heikeyangle-code/rikkahub-plus)** — Rikka+：酒馆兼容层、群聊、宏引擎等
- 以及 Rikka+ 所整合的众多开源组件

依 AGPL-3.0 分发。若你在网络上提供本软件的修改版本服务，需同样以 AGPL-3.0 开放源码。
