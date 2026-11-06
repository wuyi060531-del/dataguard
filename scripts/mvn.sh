#!/usr/bin/env bash
# 仅为本进程选择 Java，不更改全局 JDK。DATAGUARD_JAVA_HOME 可显式指定。
set -euo pipefail
task_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
task_java_home="${DATAGUARD_JAVA_HOME:-}"
if [[ -z "$task_java_home" && -f "$task_root/.setup/java21-home.txt" ]]; then
  IFS= read -r task_java_home < "$task_root/.setup/java21-home.txt"
fi
if [[ -z "$task_java_home" && -x /usr/libexec/java_home ]]; then
  task_java_home="$(/usr/libexec/java_home -v 21 2>/dev/null || true)"
fi
if [[ -n "$task_java_home" ]]; then
  if [[ ! -x "$task_java_home/bin/java" ]]; then
    echo '找不到配置的 Java；请设置 DATAGUARD_JAVA_HOME 为 JDK 21 路径。' >&2
    exit 2
  fi
  export JAVA_HOME="$task_java_home"
fi
cd "$task_root"
exec "$task_root/mvnw" "$@"
