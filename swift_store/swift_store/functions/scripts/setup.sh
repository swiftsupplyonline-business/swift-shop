#!/usr/bin/env bash
# scripts/setup.sh — First-time project setup

set -euo pipefail

echo "🚀 Swift Shop — Project Setup"
echo "================================"

# Check Java 17
java_version=$(java -version 2>&1 | awk -F '"' '/version/ {print $2}' | cut -d'.' -f1)
if [ "$java_version" -lt 17 ]; then
  echo "❌ Java 17 required. Found Java $java_version"
  exit 1
fi
echo "✅ Java $java_version"

# Check Android SDK
if [ -z "${ANDROID_HOME:-}" ] && [ -z "${ANDROID_SDK_ROOT:-}" ]; then
  echo "⚠️  ANDROID_HOME not set. Android Studio will set this automatically."
fi

# Check google-services.json
if [ ! -f "app/google-services.json" ]; then
  echo "❌ app/google-services.json not found"
  echo "   Download from Firebase Console → Project Settings → Android app"
  exit 1
fi
echo "✅ google-services.json found"

# Check gradle.properties for required keys
required_keys=("MAPS_API_KEY" "MOPAY_BASE_URL" "BACKEND_BASE_URL")
for key in "${required_keys[@]}"; do
  if ! grep -q "^$key=" gradle.properties 2>/dev/null; then
    echo "❌ $key missing from gradle.properties"
    exit 1
  fi
done
echo "✅ gradle.properties configured"

# Verify Gradle wrapper
if [ ! -f "gradlew" ]; then
  echo "❌ gradlew not found"
  exit 1
fi
chmod +x gradlew
echo "✅ Gradle wrapper ready"

echo ""
echo "Building project (dev debug)…"
./gradlew :app:assembleDevDebug --quiet

echo ""
echo "Running unit tests…"
./gradlew :app:testDevDebugUnitTest --quiet

echo ""
echo "✅ Setup complete!"
echo ""
echo "Next steps:"
echo "  ./gradlew :app:installDevDebug      # Install on connected device"
echo "  ./gradlew :app:assembleProductionRelease  # Production build"
echo "  firebase deploy --only firestore    # Deploy rules + indexes"
