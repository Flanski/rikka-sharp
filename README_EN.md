# Rikka# — AI Agents & Narrative Text Games

> **Rikka#** (read "Rikka Sharp") is a small step up from [Rikka+](https://github.com/heikeyangle-code/rikkahub-plus).
> The `#` means "a modest step further": **drop what is off-mission and focus on two directions** —
> **AI agent software development** and **narrative AI text games**.

[**简体中文**](README.md) | [**English**](README_EN.md)

---

## What it is

A native Android AI client (Kotlin + Jetpack Compose + Material You + ObjectBox) that talks to any
OpenAI / Anthropic / Google-compatible API, and ships a **real execution environment** plus a
**complete role-play narrative stack** on device.

### 1. AI agent software development

| Capability | Description |
|---|---|
| **Workspace** | Bundled proot + Ubuntu 24.04 rootfs — the model gets a real Linux filesystem and shell (`workspace_read_file` / `write_file` / `edit_file` / `shell`) |
| **SSH client** | Work on a remote dev box: `ssh_exec` / `ssh_upload` / `ssh_download` / `ssh_ls` / `ssh_hosts`. **Credentials are bound to each host**, so the model can only reference a host by name — it is impossible to use host A's key against host B |
| **Web research** | `fetch_url` (article extraction + HTML→Markdown + paging), `github_search` (official Search API), `wikipedia_search` — **all without any API key** |
| **Local execution** | `execute_command` (shell), `execute_python` (Chaquopy CPython), `eval_javascript` (persistent QuickJS), `calculator` |
| **Files & tasks** | `file_*`, `task_*`, memory, Skills, MCP client |
| **Extensibility** | Skills directory, MCP servers, read-write `/workspace`, `/upload`, `/tool_outputs` |

### 2. Narrative AI text games

A SillyTavern-compatible layer inherited from Rikka+:

| Capability | Description |
|---|---|
| **Character cards** | Lossless SillyTavern V2/V3 import/export (PNG / JSON) with a built-in embedded-lorebook editor |
| **Lorebooks** | Official injection semantics: selective triggering, groups, recursive scanning, sticky entries, token budget |
| **Narrative tools** | Author's Note, Persona, Macro Engine 2.0, slash commands |
| **Group chat** | Multiple characters in one scene, with speaker scheduling |
| **Prompt engineering** | Programmable prompt injection, placeholder system, preset management |

---

## Changes vs Rikka+

| Kind | Change |
|---|---|
| **Removed** | The entire Chinese-metaphysics (命理) feature set — twelve divination engines, interpretation templates, Python routes and their CI steps. Off-mission, and it cost ~48 MB and a lot of build time |
| **Added** | Three key-less research tools: `fetch_url`, `github_search`, `wikipedia_search` (`WebResearchTools.kt`) |
| **Added** | SSH client: 5 tools plus a "SSH 客户端" settings page (host/key management, connection test) |
| **Adjusted** | `applicationId` → `me.rerere.rikkasharp`, display name `RIKKA #`, coexists with RikkaHub / Rikka+ |
| **Slimmed** | CI workflow 227 → 48 lines; removed divination Python packages (`offline_pkgs/`, 33 MB) and 18 divination build scripts |

> Need the divination features? Use upstream [Rikka+](https://github.com/heikeyangle-code/rikkahub-plus).

---

## Upstream lineage

```
rikkahub/rikkahub                       (upstream, AGPL-3.0)
      └── heikeyangle-code/rikkahub-plus (Rikka+: Tavern + divination)
                └── this repository      (Rikka#: divination removed, agents & narrative emphasized)
```

All three are **AGPL-3.0**. This repository keeps the full LICENSE and upstream attribution.

---

## Build

GitHub Actions is included (`.github/workflows/build.yml`) and runs **on push** with **no secrets required**
(`app/app.key` and `app/google-services.json` ship with the repository):

```bash
git clone <this repo>
git push origin main        # any branch listed in build.yml
```

After ~20–40 minutes, download the artifact `rikkahub-plus-fresh` from the **Actions** tab
(`app/build/outputs/apk/release/*arm64-v8a*.apk`).

Local builds need JDK 17+, Android SDK (compileSdk 37), NDK (the `workspace` module has C++) and Node/pnpm.

---

## Credits

- **[rikkahub/rikkahub](https://github.com/rikkahub/rikkahub)** — upstream multi-provider Android LLM client
- **[heikeyangle-code/rikkahub-plus](https://github.com/heikeyangle-code/rikkahub-plus)** — Rikka+: Tavern compatibility, group chat, macro engine
- Plus the many open-source components Rikka+ integrates

Distributed under AGPL-3.0.
