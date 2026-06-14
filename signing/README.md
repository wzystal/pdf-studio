# Release 签名（本地）

- `release.jks`：Release 签名证书（**已 gitignore，务必备份**）
- `secrets.local.properties`：密码（**已 gitignore**）
- 根目录 `keystore.properties`：本地 Gradle 用（**已 gitignore**）

## 写入 GitHub Secrets

```bash
gh auth login -h github.com
export DINGTALK_WEBHOOK='钉钉机器人Webhook完整URL'
bash scripts/setup-github-secrets.sh
```

## 本地打 Release 包

```bash
./gradlew assembleRelease
# app/build/outputs/apk/release/app-release.apk
```

密码见 `signing/secrets.local.properties`（仅本机，勿提交）。
