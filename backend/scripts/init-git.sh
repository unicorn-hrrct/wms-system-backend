#!/usr/bin/env bash
# ============================================================
# Git 本地仓库初始化（一次性）
# 适用：Windows + Git Bash（已自带 /usr/bin/bash 与 git）
# 用法：
#   1) 在 Gitee 上创建一个空仓库（不要勾选"使用 README 初始化"）
#   2) 把下面 GITEE_REPO_URL 改为你自己的 Gitee 仓库地址（SSH 或 HTTPS 都行）
#   3) 在项目根目录执行：bash scripts/init-git.sh
# ============================================================

set -euo pipefail

# ==== 必填项：Gitee 仓库地址 ====
# 示例：
#   SSH : git@gitee.com:yourname/wms-backend.git
#   HTTPS: https://gitee.com/yourname/wms-backend.git
GITEE_REPO_URL="git@gitee.com:yourname/wms-backend.git"

# ==== 可选项 ====
GIT_USER_NAME="软件技术毕业实训项目组"
GIT_USER_EMAIL="dev@example.com"
DEFAULT_BRANCH="main"

# ==== 不要改 ====
# 脚本位于 backend/scripts/，需要回到项目根（两级父目录）
PROJECT_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$PROJECT_ROOT"

echo "==> 检查 git 可用性"
git --version

echo "==> 配置 user.name / user.email（仅本仓库生效）"
git config user.name  "$GIT_USER_NAME"
git config user.email "$GIT_USER_EMAIL"
git config core.autocrlf  false
git config core.eol       lf
git config init.defaultBranch "$DEFAULT_BRANCH"

if [ -d .git ]; then
  echo "==> 已存在 .git 目录，跳过 init（如需重建请先 rm -rf .git）"
else
  echo "==> git init（默认分支 $DEFAULT_BRANCH）"
  git init -b "$DEFAULT_BRANCH"
fi

echo "==> 检查 .gitignore 是否覆盖关键项"
for pat in target/ .idea/ effective-pom wms-api-sequence.zip .claude/; do
  if grep -qE "^${pat%/}\$|^${pat%/}\\\$|^${pat%/}\$|^\\*?${pat%/}" .gitignore; then
    echo "    [OK] $pat"
  else
    echo "    [WARN] $pat 未在 .gitignore 中，请确认是否需要排除"
  fi
done

echo "==> 当前工作目录：$(pwd)"
echo "==> 应在项目根（包含 LICENSE / backend/ / docs/ / reports/）"

echo "==> git add -A"
git add -A

echo "==> 暂存文件统计（Top 20）"
git diff --cached --stat | sort -k2 -nr | head -20 || true

echo "==> 总览：本次提交将包含的文件数"
git diff --cached --numstat | wc -l

echo
echo "==> 是否立即提交？[y/N]"
read -r ans
case "$ans" in
  y|Y|yes|YES)
    git commit -m "chore: bootstrap springboot-demo scaffold (v1.2 API docs aligned)

- 初始化 Spring Boot 4.1.0 + Java 21 脚手架
- PostgreSQL 18 + Redis 8 + RabbitMQ 4 容器化（compose.yaml）
- 五大模块代码组织：Platform / Catalog / Procurement / IVP / Sales
- API 文档 v1.2（Sales 模块反馈修复 + IVP 模块反馈修复）
- wms-api-sequence/ 时序图文档 14 篇
- LICENSE (MIT)、README、.gitignore"
    echo "==> commit 完成"
    ;;
  *)
    echo "==> 已跳过 commit，可手动执行：git commit -m \"...\""
    ;;
esac

if [ -z "${GITEE_REPO_URL//yourname/}" ]; then
  echo
  echo "==> ⚠️  未配置 GITEE_REPO_URL（仍为占位值），跳过远程添加"
  echo "    编辑本脚本顶部 GITEE_REPO_URL 后重跑，或手动执行："
  echo "    git remote add origin <your-gitee-url>"
  echo "    git push -u origin $DEFAULT_BRANCH"
else
  echo "==> 添加远程：origin -> $GITEE_REPO_URL"
  git remote remove origin 2>/dev/null || true
  git remote add origin "$GITEE_REPO_URL"
  git remote -v

  echo
  echo "==> 是否立即推送到 Gitee？[y/N]"
  read -r ans
  case "$ans" in
    y|Y|yes|YES)
      git push -u origin "$DEFAULT_BRANCH"
      ;;
    *)
      echo "==> 已跳过 push，可手动执行：git push -u origin $DEFAULT_BRANCH"
      ;;
  esac
fi

echo "==> 完成 🎉"
echo "    远程地址：$(git remote get-url origin 2>/dev/null || echo '<未配置>')"
echo "    当前分支：$(git branch --show-current)"