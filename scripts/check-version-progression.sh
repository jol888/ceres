#!/usr/bin/env bash
# Strict version progression: versionCode and versionName must both increase
# whenever the tree differs from the base ref.
# Usage:
#   ./scripts/check-version-progression.sh [base-ref]
# base-ref defaults to origin/master (or master).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
GRADLE="$ROOT/app/build.gradle.kts"
BASE_REF="${1:-}"

if [[ -z "$BASE_REF" ]]; then
  if git -C "$ROOT" rev-parse --verify origin/master >/dev/null 2>&1; then
    BASE_REF="origin/master"
  else
    BASE_REF="master"
  fi
fi

extract() {
  local file="$1"
  local key="$2"
  grep -E "^[[:space:]]*${key}[[:space:]]*=" "$file" | head -n1 | sed -E "s/.*=[[:space:]]*//;s/[\"']//g;s/[[:space:]]*$//"
}

version_gt() {
  local a="$1" b="$2"
  [[ "$a" != "$b" ]] && [[ "$(printf '%s\n' "$a" "$b" | sort -V | tail -n1)" == "$a" ]]
}

tip() {
  echo "::notice title=版本递进::$1"
}

fail() {
  echo "::error file=app/build.gradle.kts::$1"
  FAILED=1
}

FAILED=0

if [[ ! -f "$GRADLE" ]]; then
  echo "::error::找不到 $GRADLE"
  exit 1
fi

NEW_CODE="$(extract "$GRADLE" versionCode)"
NEW_NAME="$(extract "$GRADLE" versionName)"

if [[ -z "$NEW_CODE" || -z "$NEW_NAME" ]]; then
  echo "::error file=app/build.gradle.kts::无法解析当前 versionCode / versionName"
  tip "在 app/build.gradle.kts 的 defaultConfig 中设置 versionCode = N 与 versionName = \"x.y.z\""
  exit 1
fi

if ! [[ "$NEW_CODE" =~ ^[0-9]+$ ]]; then
  echo "::error file=app/build.gradle.kts::versionCode 必须是正整数，当前为「$NEW_CODE」"
  exit 1
fi

BASE_FILE="$(mktemp)"
cleanup() { rm -f "$BASE_FILE"; }
trap cleanup EXIT

if ! git -C "$ROOT" show "${BASE_REF}:app/build.gradle.kts" >"$BASE_FILE" 2>/dev/null; then
  tip "基准 ${BASE_REF} 尚无 app/build.gradle.kts，跳过递进校验（首次提交）。"
  echo "current versionCode=$NEW_CODE versionName=$NEW_NAME"
  exit 0
fi

OLD_CODE="$(extract "$BASE_FILE" versionCode)"
OLD_NAME="$(extract "$BASE_FILE" versionName)"

echo "基准 ${BASE_REF}: versionCode=${OLD_CODE:-?} versionName=${OLD_NAME:-?}"
echo "当前:           versionCode=${NEW_CODE} versionName=${NEW_NAME}"

if [[ -z "$OLD_CODE" || -z "$OLD_NAME" ]]; then
  tip "基准分支无法解析版本，跳过递进校验。"
  exit 0
fi

if ! [[ "$OLD_CODE" =~ ^[0-9]+$ ]]; then
  fail "基准 versionCode「$OLD_CODE」非法"
  exit 1
fi

# Versions unchanged: only OK when tracked tree matches base (ignore untracked files).
if [[ "$NEW_CODE" == "$OLD_CODE" && "$NEW_NAME" == "$OLD_NAME" ]]; then
  if git -C "$ROOT" diff --quiet "$BASE_REF" -- && git -C "$ROOT" diff --quiet --cached "$BASE_REF" --; then
    tip "相对 ${BASE_REF} 无变更，版本校验通过。"
    exit 0
  fi
  fail "相对 ${BASE_REF} 有变更，但 versionCode/versionName 仍为 ${OLD_CODE}/${OLD_NAME}"
  tip "请在 app/build.gradle.kts 中同时递增：versionCode ≥ $((OLD_CODE + 1))，versionName > ${OLD_NAME}"
  echo
  echo "—— CI 提示 ——"
  echo "1. 相对 ${BASE_REF} 的每次变更都必须抬高 versionCode 与 versionName。"
  echo "2. versionCode 为正整数且必须 > 基准；versionName 用点分数字且必须严格大于基准。"
  echo "3. 本地自检： ./scripts/check-version-progression.sh ${BASE_REF}"
  exit 1
fi

if (( NEW_CODE <= OLD_CODE )); then
  fail "versionCode 必须严格递增：当前 ${NEW_CODE}，基准 ${OLD_CODE}（需要 ≥ $((OLD_CODE + 1))）"
  tip "修改 app/build.gradle.kts：versionCode = $((OLD_CODE + 1))"
fi

if ! version_gt "$NEW_NAME" "$OLD_NAME"; then
  fail "versionName 必须严格大于基准：当前「${NEW_NAME}」，基准「${OLD_NAME}」（按语义化版本 sort -V）"
  tip "例如基准为 ${OLD_NAME} 时，改为更高的点分版本（如抬高末位）"
fi

if (( FAILED )); then
  echo
  echo "—— CI 提示 ——"
  echo "1. 相对 ${BASE_REF} 的每次变更都必须同时抬高 versionCode 与 versionName。"
  echo "2. versionCode 为正整数且必须 > 基准；versionName 用点分数字，且必须严格大于基准。"
  echo "3. 本地自检： ./scripts/check-version-progression.sh ${BASE_REF}"
  exit 1
fi

tip "版本递进通过：${OLD_CODE}/${OLD_NAME} → ${NEW_CODE}/${NEW_NAME}"
echo "OK"
