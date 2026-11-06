#!/usr/bin/env bash
# Web 默认使用本地文件数据库；inspect 子命令无需数据库。
set -euo pipefail
task_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
task_java_home="${DATAGUARD_JAVA_HOME:-}"
if [[ -z "$task_java_home" && -f "$task_root/.setup/java21-home.txt" ]]; then
  IFS= read -r task_java_home < "$task_root/.setup/java21-home.txt"
fi
if [[ -z "$task_java_home" && -x /usr/libexec/java_home ]]; then
  task_java_home="$(/usr/libexec/java_home -v 21 2>/dev/null || true)"
fi
task_java="java"
if [[ -n "$task_java_home" ]]; then
  if [[ ! -x "$task_java_home/bin/java" ]]; then
    echo '找不到配置的 Java；请设置 DATAGUARD_JAVA_HOME 为 JDK 21 路径。' >&2
    exit 2
  fi
  task_java="$task_java_home/bin/java"
elif ! command -v java >/dev/null 2>&1; then
  echo '找不到 Java；请安装 JDK 21 并设置 DATAGUARD_JAVA_HOME。' >&2
  exit 2
fi
cd "$task_root"
if [[ ! -f target/dataguard-1.1.0.jar ]]; then
  echo '先执行 bash scripts/mvn.sh clean verify 生成正式 JAR。' >&2
  exit 2
fi
exec "$task_java" -jar target/dataguard-1.1.0.jar "$@"
