#!/bin/bash
set -euo pipefail

KEY_DIR=".key"
KEYSTORE="$KEY_DIR/app-release.jks"
ALIAS="key0"
STORE_PASS="123456"
KEY_PASS="123456"

if [ -f "$KEYSTORE" ]; then
  echo "已存在 $KEYSTORE，跳过生成。"
  ls -l "$KEYSTORE"
  exit 0
fi

mkdir -p "$KEY_DIR"

echo "正在生成 Release 签名密钥..."
keytool -genkeypair -v \
  -keystore "$KEYSTORE" \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000 \
  -alias "$ALIAS" \
  -storepass "$STORE_PASS" \
  -keypass "$KEY_PASS" \
  -dname "CN=AVBox, OU=Dev, O=AVBox, L=Unknown, S=Unknown, C=CN"

echo ""
echo "生成完成: $KEYSTORE"
ls -l "$KEYSTORE"
echo ""
echo "本地打 Release 包命令:"
echo "  ./gradlew assembleRelease"
echo ""
echo "如需上传到 GitHub Actions，请执行:"
echo "  base64 -w 0 $KEYSTORE"
echo "然后把输出填到仓库 Secrets 的 KEYSTORE_BASE64"
