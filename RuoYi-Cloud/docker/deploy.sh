#!/bin/sh
set -eu
cd "$(dirname "$0")"
case "${1:-}" in
  base) docker compose up -d ruoyi-mysql ruoyi-redis ruoyi-nacos ;;
  modules) docker compose up -d ruoyi-nginx ruoyi-gateway ruoyi-auth ruoyi-modules-system ruoyi-modules-job ;;
  devtools) docker compose --profile devtools up -d ruoyi-modules-gen ;;
  monitoring) docker compose --profile monitoring up -d ruoyi-visual-monitor ;;
  stop) docker compose --profile devtools --profile monitoring stop ;;
  *) echo "Usage: sh deploy.sh [base|modules|devtools|monitoring|stop]" >&2; exit 1 ;;
esac
