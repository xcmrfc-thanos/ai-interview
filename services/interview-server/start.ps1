$ErrorActionPreference = "Stop"
$root = $PSScriptRoot
$projectRoot = Split-Path (Split-Path $root -Parent) -Parent

# Model dir: default third_party/mica-voice/models (overridable via env)
$env:MICA_VOICE_MODELS_DIR = if ($env:MICA_VOICE_MODELS_DIR) { $env:MICA_VOICE_MODELS_DIR } else { (Join-Path $projectRoot "third_party/mica-voice/models").Replace("\", "/") }
# 前端 SPA 产物：web/ 构建输出（运行时直接读文件系统，不进源码树）
$env:WEB_DIST_DIR = if ($env:WEB_DIST_DIR) { $env:WEB_DIST_DIR } else { (Join-Path $projectRoot "web/dist").Replace("\", "/") }
$env:SERVER_PORT = if ($env:SERVER_PORT) { $env:SERVER_PORT } else { "18081" }
$env:SERVER_HOST = if ($env:SERVER_HOST) { $env:SERVER_HOST } else { "127.0.0.1" }
# 业务数据库：复用项目根 instance/ai_interview_local.db（22 表含真实数据）
$env:AI_INTERVIEW_DATABASE_URL = if ($env:AI_INTERVIEW_DATABASE_URL) { $env:AI_INTERVIEW_DATABASE_URL } else { (Join-Path $projectRoot "instance/ai_interview_local.db").Replace("\", "/") }
# LLM/ASR 配置：从项目根 .env 自动加载（未设置环境变量时），ASR_BACKEND 与 py 端共用
$envFile = Join-Path $projectRoot ".env"
if (Test-Path $envFile) {
    Get-Content $envFile -Encoding UTF8 | ForEach-Object {
            if ($_ -match '^\s*(LLM_[A-Z_]+|ASR_[A-Z_]+|MICA_VOICE_[A-Z_]+|SHERPA_[A-Z_]+|APP_SECRET_KEY|CONFIG_ENCRYPTION_KEY)=(.*)$') {
            $key = $matches[1]; $value = $matches[2].Trim().Trim('"').Trim("'")
            if (-not [Environment]::GetEnvironmentVariable($key)) { Set-Item -Path "env:$key" -Value $value }
        }
    }
}
# LLM (optional; /api/copilot/answer unavailable when unset)
# $env:LLM_API_KEY = "..."
# $env:LLM_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1"
# $env:LLM_MODEL = "qwen-plus"

Push-Location $root
try {
    mvn -q package
    if ($LASTEXITCODE -ne 0) { throw "mvn package failed" }

    # 端口占用检测：残留的 interview-server 实例自动清理；其他程序占用则报错
    $listener = Get-NetTCPConnection -LocalPort $env:SERVER_PORT -State Listen -ErrorAction SilentlyContinue
    if ($listener) {
        $owner = Get-CimInstance Win32_Process -Filter "ProcessId=$($listener.OwningProcess)" -ErrorAction SilentlyContinue
        if ($owner -and $owner.CommandLine -match "InterviewServerApplication") {
            Write-Host "检测到残留 interview-server 实例 (PID $($listener.OwningProcess))，正在清理..."
            Stop-Process -Id $listener.OwningProcess -Force
            Start-Sleep -Seconds 1
        } else {
            throw "端口 $env:SERVER_PORT 已被其他程序占用 (PID $($listener.OwningProcess))，请先释放端口"
        }
    }

    $cp = @("$root\target\classes") + (Get-ChildItem "$root\target\lib\*.jar" | ForEach-Object { $_.FullName })
    # -Dfile.encoding=UTF-8: feat SSE 解析用平台默认字符集，中文 Windows 默认 GBK 会吞掉
    # UTF-8 多字节末尾与 0x5C(反斜杠) 组合（如 "你自己\"），导致流式 JSON 解析失败
    & java "-Dfile.encoding=UTF-8" -cp ($cp -join ";") com.aiinterview.server.InterviewServerApplication
} finally {
    Pop-Location
}