#!/bin/sh
# Builds the helper into macos/lingo-helper/build/lingo-helper (needs Xcode's command line tools, on a Mac).
# The desktop build bundles it into Lingo Lock.app (desktop/build.gradle.kts, syncAppResources).
set -e
cd "$(dirname "$0")"
mkdir -p build
swiftc -O -target arm64-apple-macos13.0 main.swift -o build/lingo-helper \
    -framework AppKit -framework AVFoundation -framework Speech \
    -Xlinker -sectcreate -Xlinker __TEXT -Xlinker __info_plist -Xlinker Info.plist
codesign --force --sign - build/lingo-helper
echo "Built $(pwd)/build/lingo-helper"
