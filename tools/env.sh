# SPDX-License-Identifier: GPL-3.0-or-later
# Source this: `. tools/env.sh`. Sets up the build environment.
#   - Local settings first: a git-ignored private/env.sh, if present (e.g. TABCAST_TOOLCHAIN, TABCAST_DEBUG_KEYSTORE,
#     TABCAST_KEYDIR, FDROID).
#   - TABCAST_TOOLCHAIN set: a self-contained toolchain folder with jdk17/, sdk/ (platform 35, build-tools 35.0.0,
#     NDK 27.2.12479018) and gradle-home/.
#   - Otherwise the JAVA_HOME and ANDROID_HOME (or ANDROID_SDK_ROOT) you already have.
_root=$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")/.." && pwd)
# shellcheck disable=SC1091
[ -f "$_root/private/env.sh" ] && . "$_root/private/env.sh"
if [ -n "${TABCAST_TOOLCHAIN:-}" ]; then
    export JAVA_HOME=$TABCAST_TOOLCHAIN/jdk17
    export ANDROID_HOME=$TABCAST_TOOLCHAIN/sdk
    export GRADLE_USER_HOME=$TABCAST_TOOLCHAIN/gradle-home
fi
export ANDROID_HOME=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
export ANDROID_SDK_ROOT=$ANDROID_HOME
[ -n "$ANDROID_HOME" ] || echo "tools/env.sh: set ANDROID_HOME (Android SDK) or TABCAST_TOOLCHAIN" >&2
[ -n "${JAVA_HOME:-}" ] && export PATH=$JAVA_HOME/bin:$PATH
[ -d "$ANDROID_HOME/build-tools/35.0.0" ] && export PATH=$ANDROID_HOME/build-tools/35.0.0:$PATH
[ -f "$_root/local.properties" ] || echo "sdk.dir=$ANDROID_HOME" > "$_root/local.properties"
