# Release 签名（本地）

- `release.jks`：Release 签名证书（**已 gitignore，务必备份**）
- `secrets.local.properties`：密码（**已 gitignore**）
- 根目录 `keystore.properties`：本地 Gradle 用（**已 gitignore**）

## 1. 生成本仓库 keystore（每个 Android 项目各一套）

```bash
bash scripts/generate-release-keystore.sh
```

## 2. 写入 GitHub Secrets

```bash
gh auth login -h github.com
export DINGTALK_WEBHOOK='钉钉机器人 Webhook 完整 URL'

# 写入当前 git remote 对应仓库
bash scripts/setup-github-secrets.sh

# 或指定 owner/repo（复制模板到新项目时用）
bash scripts/setup-github-secrets.sh wzystal/pdf-studio
bash scripts/setup-github-secrets.sh wzystal pdf-studio
```

验证：

```bash
gh secret list --repo wzystal/pdf-studio
```

## 3. 本地打 Release 包

```bash
./gradlew assembleRelease
# app/build/outputs/apk/release/app-release.apk
```

密码见 `signing/secrets.local.properties`（仅本机，勿提交）。
