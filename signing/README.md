# Release 签名（本地）

通用脚本在 **`~/tools/scripts/`**，不在各 Android 项目中重复维护。

## 1. 生成本仓库 keystore

```bash
~/tools/scripts/generate-release-keystore.sh "$(pwd)"
```

## 2. 写入 GitHub Secrets

```bash
# 项目 Release 签名（每个 App 各一套 keystore）
~/tools/scripts/setup-github-secrets.sh --project-dir "$(pwd)"

# 蒲公英 & 钉钉（多仓库共用，交互式引导输入）
~/tools/scripts/setup-shared-secrets.sh
```

## 3. CI 脚本来源

GitHub Actions 从 [wzystal/android-ci-scripts](https://github.com/wzystal/android-ci-scripts) 拉取 `_tools/` 目录，无需在各项目保留 `scripts/`。

## 4. 本地打 Release 包

```bash
./gradlew assembleRelease
```

密码见 `signing/secrets.local.properties`（仅本机，勿提交）。
