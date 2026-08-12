#!/usr/bin/env bash
set -euo pipefail

KEYSTORE="${1:-flashcard-release.jks}"
ALIAS="${2:-flashcard}"

echo "This creates your permanent Android release signing key. Keep it private and backed up."
keytool -genkeypair -v \
  -keystore "$KEYSTORE" \
  -alias "$ALIAS" \
  -keyalg RSA \
  -keysize 4096 \
  -validity 10000

echo
echo "Base64 value for GitHub secret ANDROID_KEYSTORE_BASE64:"
base64 < "$KEYSTORE" | tr -d '\n'
echo
echo
echo "Also create these GitHub Actions secrets:"
echo "ANDROID_KEYSTORE_PASSWORD"
echo "ANDROID_KEY_ALIAS=$ALIAS"
echo "ANDROID_KEY_PASSWORD"
