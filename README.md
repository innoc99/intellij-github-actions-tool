**English** | [한국어](README.ko.md)

# GitHub Actions Tool for IntelliJ

An IntelliJ IDEA plugin for monitoring GitHub Actions workflows, viewing logs, and triggering dispatches — all from within the IDE. Works with both GitHub and GitHub Enterprise.

---

## Features

| Feature | Description |
|---------|-------------|
| **Workflow Monitoring** | Real-time auto-refresh of workflow lists and run statuses |
| **Manual Dispatch** | Trigger `workflow_dispatch` workflows directly from the IDE |
| **Jobs/Steps Detail** | View Job trees and Step-level logs per Run |
| **Log Search** | Keyword search within Step logs with auto-highlight and expand |
| **Status/Branch Filter** | Filter by result (success, failure, etc.) and branch |
| **Quick Search** | Speed Search in the Run list — just start typing |
| **VCS Auto-Detection** | Automatically detects GitHub server/owner/repo from Git remote |
| **IntelliJ Account Integration** | Uses tokens from IntelliJ's registered GitHub accounts (no separate PAT needed) |
| **Network Auto-Recovery** | Switches to offline mode on disconnect, auto-recovers via health-check |

---

## Requirements

- **IntelliJ IDEA** 2024.1 ~ 2026.1 (Community / Ultimate)
- **Git** plugin enabled (built-in)
- **GitHub** plugin enabled (built-in)
- A **Git remote** configured in the project (GitHub or GitHub Enterprise)

---

## Installation

### 1. Download the plugin zip

Download the latest plugin zip:

> **[intellij-github-actions-tool-1.1.0.zip](dist/intellij-github-actions-tool-1.1.0.zip)**

Or download directly from the `dist/` folder in this repository.

### 2. Install in IntelliJ

1. Open IntelliJ IDEA.
2. Go to **Settings** (Mac: `⌘,` / Windows: `Ctrl+Alt+S`).
3. Select **Plugins** from the left menu.
4. Click the **gear icon (⚙)** at the top.
5. Select **Install Plugin from Disk...**
6. Choose the downloaded `intellij-github-actions-tool-1.1.0.zip`.
7. Click **OK** and **Restart IDE**.

### 3. Verify installation

After restart, a **GitHub Actions** tab should appear in the bottom tool bar.

> If not visible: **View** > **Tool Windows** > **GitHub Actions**

---

## Authentication

The plugin supports two authentication methods. **IntelliJ GitHub account integration** is enabled by default.

### Option 1: IntelliJ GitHub Account (default, recommended)

If you already have a GitHub account registered in IntelliJ, no additional setup is needed.

**To add a GitHub account:**

1. **Settings (⌘,)** > **Version Control** > **GitHub**
2. Click **+** > **Log In via GitHub...** or **Log In with Token...**
3. For GitHub Enterprise, enter the **Server** URL.

> The token must include **repo** and **workflow** scopes.

### Option 2: Personal Access Token

1. **Settings (⌘,)** > **Tools** > **GitHub Actions Tool**
2. Uncheck **"Use IntelliJ GitHub account settings"**.
3. Enter your **Personal Access Token**.
4. Click **Apply** or **OK**.

**To generate a token:**

1. GitHub > Profile > **Settings** > **Developer settings** > **Personal access tokens**
2. **Generate new token (classic)** with `repo` and `workflow` scopes.

---

## Plugin Settings

**Settings (⌘,)** > **Tools** > **GitHub Actions Tool**

### VCS Repository Info

GitHub server URL, owner, and repository are auto-detected from Git remote. No manual configuration needed.

### Monitoring Settings

| Setting | Description | Default |
|---------|-------------|---------|
| Auto-refresh enabled | Auto-refresh workflow run list | Enabled |
| Refresh interval (seconds) | Auto-refresh interval (minimum 10s) | 30s |

---

## Usage

### Opening the Tool Window

Click the **GitHub Actions** tab in the bottom tool bar.

> If not visible: **View** > **Tool Windows** > **GitHub Actions**

### Layout

The Tool Window has up to 4 areas:

```
┌──────────────┬──────────────────────┬──────────────────┐
│              │ [Status▾][Branch▾]   │                  │
│  ① Workflow  │  [Dispatch][Web][↻]  │                  │
│     Tree     ├──────────────────────┤  ④ Step Log      │
│              │  ② Run List          │     Panel        │
│              ├──────────────────────┤                  │
│              │  ③ Jobs Detail Tree  │                  │
└──────────────┴──────────────────────┴──────────────────┘
```

| Area | Description |
|------|-------------|
| **① Workflow Tree** | All workflows. Select "All History" or a specific workflow |
| **② Run List** | Recent runs for the selected workflow. Supports status/branch filters |
| **③ Jobs Detail Tree** | Job list grouped by name prefix (split by ` / `) |
| **④ Step Log Panel** | Collapsible step-by-step logs (shown when a Job is selected) |

### Workflow Dispatch

Only workflows with `workflow_dispatch` event can be triggered.

1. Select a workflow in **① Workflow Tree**.
2. Click **Dispatch** button or right-click > **"Dispatch"**.
3. Set branch and input parameters, then **Run**.

### Log Search

Enter a keyword in the **"Search logs..."** field in **④ Step Log Panel**:
- Matching lines are **highlighted in yellow**.
- Steps and groups containing matches are **auto-expanded**.

---

## Keyboard & Mouse Actions

| Action | Behavior |
|--------|----------|
| **Type** in Run list | Quick Search (Speed Search) |
| **Click** Run | Load Jobs detail tree |
| **Double-click** Run | Open in browser |
| **Right-click** Run | Context menu (Open in web) |
| **Right-click** Workflow | Context menu (Dispatch, Open in web) |
| **Click** Job | Show Step log panel |
| **Click** Step header | Toggle log collapse/expand |

---

## Tech Stack

- **Kotlin** + IntelliJ Platform SDK 2024.1+
- **OkHttp** — HTTP client
- **Gson** — JSON parsing
- **SnakeYAML** — Workflow YAML parsing
- **IntelliJ GitHub Plugin API** — Account token integration

---

## License

MIT License
