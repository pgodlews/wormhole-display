#!/bin/bash
set -euo pipefail
repo_dir="$(cd "$(dirname "$0")/.." && pwd)"
ux="$repo_dir/app/src/main/cpp/uxplay/lib"
test_dir="$(mktemp -d "${TMPDIR:-/tmp}/wormhole-native.XXXXXX")"
trap 'rm -rf "$test_dir"' EXIT
"${CC:-clang}" -g -O1 -fsanitize="${SANITIZERS:-undefined}" -fno-omit-frame-pointer -fno-sanitize-recover=all \
  -DPLIST_230 -DTARGET_POSIX -D_XOPEN_SOURCE=700 -D_DARWIN_C_SOURCE \
  -include signal.h -I"$ux" -I"$repo_dir/app/src/main/cpp/deps/plist/include" \
  "$repo_dir/tests/native/mirror_regression.c" "$ux/byteutils.c" "$ux/netutils.c" "$ux/logger.c" \
  -lpthread -o "$test_dir/mirror-regression"
if [ "${GUARD_MALLOC:-0}" = 1 ]; then
  MALLOC_STRICT_SIZE=1 DYLD_INSERT_LIBRARIES=/usr/lib/libgmalloc.dylib "$test_dir/mirror-regression"
else
  "$test_dir/mirror-regression"
fi
"${CC:-clang}" -g -O1 -fsanitize="${SANITIZERS:-undefined}" -fno-omit-frame-pointer -fno-sanitize-recover=all \
  -I"$repo_dir/app/src/main/cpp" \
  "$repo_dir/tests/native/dmap_test.c" "$repo_dir/app/src/main/cpp/dmap.c" -o "$test_dir/dmap-test"
"$test_dir/dmap-test"
