#!/usr/bin/env bash
# 将本地签名与钉钉 Webhook 写入 GitHub Secrets（需先 gh auth login）
set -euo pipefail

REPO="${1:-wzystal/pdf-studio}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SECRETS_FILE="$ROOT/signing/secrets.local.properties"
KEYSTORE="$ROOT/signing/release.jks"

if ! gh auth status >/dev/null 2>&1; then
  echo "请先执行: gh auth login -h github.com"
  exit 1
fi

if [[ ! -f "$SECRETS_FILE" ]] || [[ ! -f "$KEYSTORE" ]]; then
  echo "缺少 signing/release.jks 或 signing/secrets.local.properties"
  exit 1
fi

# shellcheck disable=SC1090
source "$SECRETS_FILE"

echo "写入仓库 $REPO 的 Secrets..."

gh secret set RELEASE_KEYSTORE_BASE64 --repo "$REPO" --body "$(base64 -i "$KEYSTORE" | tr -d '\n')"
gh secret set RELEASE_STORE_PASSWORD --repo "$REPO" --body "$STORE_PASSWORD"
gh secret set RELEASE_KEY_ALIAS --repo "$REPO" --body "$KEY_ALIAS"
gh secret set RELEASE_KEY_PASSWORD --repo "$REPO" --body "$KEY_PASSWORD"

if [[ -n "${DINGTALK_WEBHOOK:-}" ]]; then
  gh secret set DINGTALK_WEBHOOK --repo "$REPO" --body "$DINGTALK_WEBHOOK"
  echo "已设置 DINGTALK_WEBHOOK"
else
  echo ""
  echo "未设置环境变量 DINGTALK_WEBHOOK。请执行："
  echo "  export DINGTALK_WEBHOOK='你的钉钉机器人Webhook'"
  echo "  $0"
  echo "或："
  echo "  gh secret set DINGTALK_WEBHOOK --repo $REPO"
fi

echo "签名相关 Secrets 已写入。"
