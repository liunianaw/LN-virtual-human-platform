#!/bin/sh
set -eu
cd "$(dirname "$0")"
# Build first. Database import is a separate, explicit operation.
mkdir -p nginx/html/dist
cp -R ../../RuoYi-Cloud-Vue3/dist/. nginx/html/dist/
copy_jar() {
  mkdir -p "$2/jar"
  cp "$1" "$2/jar/"
}
copy_jar ../ruoyi-gateway/target/ruoyi-gateway.jar ./ruoyi/gateway
copy_jar ../ruoyi-auth/target/ruoyi-auth.jar ./ruoyi/auth
copy_jar ../ruoyi-modules/ruoyi-system/target/ruoyi-modules-system.jar ./ruoyi/modules/system
copy_jar ../ruoyi-modules/ruoyi-job/target/ruoyi-modules-job.jar ./ruoyi/modules/job
for profile in "$@"; do
  case "$profile" in
    devtools) copy_jar ../ruoyi-modules/ruoyi-gen/target/ruoyi-modules-gen.jar ./ruoyi/modules/gen ;;
    monitoring) copy_jar ../ruoyi-visual/ruoyi-monitor/target/ruoyi-visual-monitor.jar ./ruoyi/visual/monitor ;;
    *) echo "Unknown profile: $profile" >&2; exit 1 ;;
  esac
done
