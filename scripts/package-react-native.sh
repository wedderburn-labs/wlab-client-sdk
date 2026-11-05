#!/usr/bin/env bash
# Builds the self-contained React Native tarball for a fork release: the wrapper
# plus the Android SDK (as a small Maven repo) and the iOS XCFramework.
# Needs an Android SDK and Xcode. Usage: scripts/package-react-native.sh 1.1.4-wlab.1
set -euo pipefail
VERSION="${1:?usage: package-react-native.sh <version>}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
M2="$ROOT/build/wlab-m2"
RN="$ROOT/react-native"
cd "$ROOT"

echo "==> Publishing the Android SDK to a local repo"
rm -rf "$M2"
RELEASE_VERSION="$VERSION" UNSIGNED_RELEASE=1 ./gradlew :core:publishToMavenLocal \
  "-Dmaven.repo.local=$M2" --no-configuration-cache --console=plain

echo "==> Building the iOS XCFramework"
RELEASE_VERSION="$VERSION" ./gradlew :core:assembleTraccarClientSDKReleaseXCFramework \
  --no-configuration-cache --console=plain

echo "==> Assembling the package"
rm -rf "$RN/android/maven" "$RN/ios/TraccarClientSDK.xcframework"
mkdir -p "$RN/android/maven"
cp -R "$M2/za" "$RN/android/maven/"
rm -rf "$RN"/android/maven/za/co/wlab/wlab-client-sdk-ios*
find "$RN/android/maven" \( -name '*-sources.jar' -o -name '*-javadoc.jar' -o -name '*.asc' -o -name 'maven-metadata-local.xml' \) -delete
cp -R "$ROOT/core/build/XCFrameworks/release/TraccarClientSDK.xcframework" "$RN/ios/"
cp "$ROOT/LICENSE" "$RN/LICENSE"
sed -i.bak "s|wlab-client-sdk:[^\"]*|wlab-client-sdk:$VERSION|" "$RN/android/build.gradle"
rm "$RN/android/build.gradle.bak"

cd "$RN"
npm version "$VERSION" --no-git-tag-version --allow-same-version --ignore-scripts
npm ci
npm run typecheck
npm pack

TARBALL="$RN/wlab-client-sdk-$VERSION.tgz"
echo "==> Checking the tarball"
LIST="$(tar tzf "$TARBALL")"
grep -q "package/lib/module/index.js" <<<"$LIST"
grep -q "package/LICENSE" <<<"$LIST"
grep -q "package/ios/TraccarClientSDK.xcframework/Info.plist" <<<"$LIST"
grep -q "package/android/maven/za/co/wlab/wlab-client-sdk-android/$VERSION/" <<<"$LIST"
grep -q "package/android/maven/za/co/wlab/wlab-client-sdk/$VERSION/" <<<"$LIST"
ls -lh "$TARBALL"
echo "==> Done: $TARBALL"
