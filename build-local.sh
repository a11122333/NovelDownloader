#!/usr/bin/env bash
# 本地（aarch64 容器）构建包装脚本。
#
# 与原 build.sh 的区别仅在于环境适配：
#   1. PROJ 指向本脚本所在目录，避免依赖 /workspace/NovelDownloader
#   2. 强制 UTF-8，否则 Java 无法处理含中文的工作区路径
#   3. JDK 使用 arm64 路径（/usr/lib/jvm/java-17-openjdk-arm64）
#
# 关于 aapt2 / zipalign：官方 build-tools 里这两个是 x86_64 二进制，
# 本机为 aarch64，已在 /opt/android-sdk/build-tools/34.0.0/ 下替换为
# qemu-x86_64-static 包装脚本，原调用方式不变。
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

export PROJ="$DIR"
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64
export PATH="$JAVA_HOME/bin:$PATH"
export LANG=C.UTF-8
export LC_ALL=C.UTF-8
export JAVA_TOOL_OPTIONS="-Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8"

exec bash "$DIR/build.sh" "$@"
