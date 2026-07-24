# ============================================================
# Gitee 推送脚本（Windows PowerShell）
# 用法：
#   1) 在 Gitee 上创建一个空仓库（不要勾选"使用 README 初始化"）
#   2) 编辑本脚本顶部 $GiteeRepoUrl 变量
#   3) 在 backend/ 目录下执行：
#        powershell -ExecutionPolicy Bypass -File scripts\push-gitee.ps1
#      （脚本会自动回到项目根进行 git 操作）
# ============================================================

$ErrorActionPreference = 'Stop'
# 脚本位于 backend/scripts/，需要回到项目根（两级父目录）
$ProjectRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Set-Location $ProjectRoot

# ==== 必填项：Gitee 仓库地址 ====
$GiteeRepoUrl = 'git@gitee.com:yourname/wms-backend.git'  # ← 改成你的仓库地址
$DefaultBranch = 'main'

# ==== 检查 git ====
if (-not (Get-Command git -ErrorAction SilentlyContinue)) {
    throw 'git 未安装或未加入 PATH'
}

# ==== 检查是否已初始化 ====
if (-not (Test-Path '.git')) {
    Write-Host '==> 未检测到 .git，正在初始化...' -ForegroundColor Cyan
    git init -b $DefaultBranch
    git config user.name  '软件技术毕业实训项目组'
    git config user.email 'dev@example.com'
    git config core.autocrlf $false
}

# ==== 配置 user（如未配置）====
if (-not (git config user.name))  { git config user.name  '软件技术毕业实训项目组' }
if (-not (git config user.email)) { git config user.email 'dev@example.com' }

# ==== 远程地址（占位值检查）====
if ($GiteeRepoUrl -like '*yourname*') {
    Write-Host '==> ⚠️  $GiteeRepoUrl 仍是占位值，请先在本脚本顶部填写真实仓库地址' -ForegroundColor Yellow
    exit 1
}

# ==== 添加远程 ====
$existing = git remote get-url origin 2>$null
if ($existing) {
    Write-Host "==> 已存在远程 origin -> $existing"
    if ($existing -ne $GiteeRepoUrl) {
        Write-Host '==> 与脚本中的 URL 不一致，更新...' -ForegroundColor Yellow
        git remote set-url origin $GiteeRepoUrl
    }
} else {
    git remote add origin $GiteeRepoUrl
}
git remote -v

# ==== stage & 提示提交 ====
git add -A
$stagedCount = (git diff --cached --numstat | Measure-Object).Count
Write-Host "==> 待提交文件数：$stagedCount"

if ($stagedCount -eq 0) {
    Write-Host '==> 没有待提交的内容，跳过 commit / push' -ForegroundColor Yellow
    exit 0
}

Write-Host '==> 是否立即提交并推送？[y/N]' -ForegroundColor Cyan
$ans = Read-Host
if ($ans -notin @('y','Y','yes','YES')) {
    Write-Host '==> 已跳过 commit / push，可手动执行后续步骤'
    exit 0
}

$hasCommit = $false
try {
    git rev-parse --verify HEAD 2>$null | Out-Null
    $hasCommit = $true
} catch {
    $hasCommit = $false
}

if (-not $hasCommit) {
    git commit -m 'chore: bootstrap springboot-demo scaffold (v1.2 API docs aligned)

- 初始化 Spring Boot 4.1.0 + Java 21 脚手架
- PostgreSQL 18 + Redis 8 + RabbitMQ 4 容器化
- 五大模块代码组织：Platform / Catalog / Procurement / IVP / Sales
- API 文档 v1.2（Sales / IVP 模块反馈修复）
- wms-api-sequence/ 时序图文档
- LICENSE (MIT)、README、.gitignore'
} else {
    Write-Host '==> 已存在历史 commit，跳过自动 commit（如需新提交请手动执行）'
}

# ==== 推送 ====
Write-Host "==> git push -u origin $DefaultBranch" -ForegroundColor Cyan
git push -u origin $DefaultBranch

if ($LASTEXITCODE -eq 0) {
    Write-Host '==> 🎉 推送成功' -ForegroundColor Green
    $repoUrl = git remote get-url origin
    Write-Host "    仓库地址：$repoUrl"
} else {
    Write-Host '==> ❌ 推送失败，请检查网络与 Gitee 凭证' -ForegroundColor Red
    exit 1
}