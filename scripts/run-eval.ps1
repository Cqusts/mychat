<#
.SYNOPSIS
    需求流水线评测一键脚本。

.DESCRIPTION
    把手工跑评测时会踩的坑都处理掉：
      - token 从 electron-store 的 config.json 里自动找，并逐个验活
      - groupId / sessionId 从后端日志里自动抓
      - PowerShell 5.1 会把 UTF-8 响应按 ISO-8859-1 解码，这里按字节解
      - 跑批期间自动轮询进度，跑完自动出报告并存文件

.EXAMPLE
    # 试水一条，确认环境通了
    .\run-eval.ps1 -SmokeTest

.EXAMPLE
    # 正式跑：10 条需求各跑 2 次
    .\run-eval.ps1

.EXAMPLE
    # 跑完补上成本，重新出报告
    .\run-eval.ps1 -ReportOnly -CostYuan 16.6
#>
param(
    [string]$Api        = "http://localhost:5050/api",
    [string]$Token      = "",
    [string]$GroupId    = "",
    [string]$SessionId  = "",
    [string]$TasksFile  = "",
    [string]$LogFile    = "D:\mychat\logs\mychat.log",
    [int]   $Repeat     = 2,
    [double]$CostYuan   = -1,
    [int]   $PollSeconds = 60,
    [switch]$SmokeTest,
    [switch]$ReportOnly,
    [switch]$KeepHistory
)

$ErrorActionPreference = "Stop"
# 注意：这里刻意不设 [Console]::OutputEncoding。
# PS 5.1 下设成 UTF8 会让中文每个字渲染两遍（"查找"显示成"查查找找"）。
# 脚本自己的中文是 .NET 字符串，输出到 GBK 控制台由 .NET 自动转换，本来就不会乱；
# 真正需要处理的是 API 响应的字节解码，那个在 Invoke-Api 里单独做

$SmokeRequirement = "给 StringTools 增加一个手机号脱敏方法，中间四位替换成星号，并补上单元测试"

function Write-Step($text)  { Write-Host "`n=== $text ===" -ForegroundColor Cyan }
function Write-Ok($text)    { Write-Host "  OK  $text" -ForegroundColor Green }
function Write-Warn2($text) { Write-Host "  !!  $text" -ForegroundColor Yellow }
function Write-Fail($text)  { Write-Host "  XX  $text" -ForegroundColor Red }

<#
    统一的接口调用。
    PS 5.1 的 Invoke-RestMethod 不看响应里的 charset，一律按 ISO-8859-1 解，
    中文必然乱码。所以这里拿原始字节自己按 UTF-8 解一遍再转 JSON。
#>
function Invoke-Api {
    param([string]$Path, [hashtable]$Body, [string]$UseToken)

    $headers = @{ token = $UseToken }
    try {
        if ($Body) {
            $resp = Invoke-WebRequest "$Api$Path" -Headers $headers -Method Post -Body $Body -UseBasicParsing
        } else {
            $resp = Invoke-WebRequest "$Api$Path" -Headers $headers -Method Post -UseBasicParsing
        }
    } catch {
        throw "请求 $Path 失败：$($_.Exception.Message)（后端起来了吗？）"
    }

    if ($resp.RawContentStream) {
        $text = [System.Text.Encoding]::UTF8.GetString($resp.RawContentStream.ToArray())
    } else {
        $text = $resp.Content
    }
    return $text | ConvertFrom-Json
}

<#
    找出当前有效的 token。
    electron-store 把它存成 "<用户ID>token"，历次登录的都堆在一个 config.json 里，
    大部分已经失效，所以逐个拿去调接口验活
#>
function Find-ValidToken {
    param([string]$RequireGroupId = "")

    Write-Step "查找可用 token"
    $candidates = @()
    Get-ChildItem $env:APPDATA -Recurse -Depth 2 -Filter config.json -ErrorAction SilentlyContinue |
        ForEach-Object {
            try {
                $json = Get-Content $_.FullName -Raw -Encoding UTF8 | ConvertFrom-Json
                $json.PSObject.Properties |
                    Where-Object { $_.Name -match '^U\d+token$' } |
                    ForEach-Object { $candidates += $_.Value }
            } catch { }
        }

    if ($candidates.Count -eq 0) {
        throw "config.json 里没有任何 token。先在客户端登录一次。"
    }
    Write-Host "  找到 $($candidates.Count) 个候选，逐个验活…"

    #给了群号就必须拿群号去验：/eval/status 不校验群成员，
    #任何没过期的账号都能通过它，挑出来的很可能是另一个不在群里的账号，
    #跑批时才报"你不在这个群里"
    $probePath = "/eval/status"
    if ($RequireGroupId) {
        $probePath = "/chat/queryAiTaskRunning?contactId=$RequireGroupId"
        Write-Host "  按群 $RequireGroupId 校验成员身份"
    }

    $liveButNotMember = 0
    foreach ($candidate in ($candidates | Select-Object -Unique)) {
        try {
            $r = Invoke-Api -Path $probePath -UseToken $candidate
            if ($r.status -eq "success") {
                Write-Ok "token: $candidate"
                return $candidate
            }
            if ($r.info -match "不在这个群|请求参数错误") { $liveButNotMember++; continue }
            if ($r.info -match "登录")                    { continue }
            #既不是登录失效也不是群成员问题，说明 token 是好的、是别的配置没开
            throw "token 有效，但接口报错：$($r.info)"
        } catch {
            if ($_.Exception.Message -match "token 有效") { throw }
        }
    }

    if ($liveButNotMember -gt 0) {
        throw ("有 $liveButNotMember 个 token 还有效，但对应账号都不在群 $RequireGroupId 里。" +
               "请用那个在群里的账号登录客户端，然后重跑本脚本。")
    }
    throw "所有 token 都失效了。去客户端退出登录再登一次，然后重跑本脚本。"
}

<#
    从后端日志里抓 groupId 和 sessionId。
    群里每来一条消息，MessageHandler 都会把整个 JSON 打进日志，两个值都在里面
#>
function Find-SessionInfo {
    Write-Step "从日志中读取 groupId / sessionId"
    if (-not (Test-Path $LogFile)) {
        throw "找不到日志文件 $LogFile。用 -LogFile 指定，或用 -GroupId / -SessionId 直接传值。"
    }
    $pattern = '"contactId":"(G\w+)".*?"sessionId":"(\w+)"'
    $match = Select-String -Path $LogFile -Pattern $pattern -Encoding UTF8 |
             Select-Object -Last 1
    if (-not $match) {
        throw "日志里没找到群消息。先在那个拉了 5 个助手的群里随便发一条消息，再重跑。"
    }
    $groups = $match.Matches[0].Groups
    Write-Ok "groupId=$($groups[1].Value)  sessionId=$($groups[2].Value)"
    return @{ GroupId = $groups[1].Value; SessionId = $groups[2].Value }
}

function Show-Report {
    param([string]$UseToken, [double]$Cost)
    $query = ""
    if ($Cost -ge 0) { $query = "?totalCostYuan=$Cost" }
    $r = Invoke-Api -Path "/eval/reportText$query" -UseToken $UseToken
    Write-Host ""
    Write-Host $r.data

    $out = Join-Path (Get-Location) ("eval-report-" + (Get-Date -Format "yyyyMMdd-HHmmss") + ".txt")
    $r.data | Out-File -FilePath $out -Encoding utf8
    Write-Ok "报告已存到 $out"
}

# ==================== 主流程 ====================

if ($ReportOnly) {
    #出报告不涉及群，随便一个有效 token 就行
    if (-not $Token) { $Token = Find-ValidToken }
    Show-Report -UseToken $Token -Cost $CostYuan
    return
}

#顺序要紧：先拿到群号，才能按"是不是这个群的成员"去挑 token
if (-not $GroupId -or -not $SessionId) {
    $info = Find-SessionInfo
    if (-not $GroupId)   { $GroupId   = $info.GroupId }
    if (-not $SessionId) { $SessionId = $info.SessionId }
}

if (-not $Token) {
    $Token = Find-ValidToken -RequireGroupId $GroupId
} else {
    Write-Ok "使用传入的 token"
}

# 需求集
if ($SmokeTest) {
    $requirements = $SmokeRequirement
    $Repeat = 1
    Write-Step "试水模式：只跑 1 条简单需求"
} else {
    if (-not $TasksFile) {
        $TasksFile = Join-Path $PSScriptRoot "..\mychat-java\eval-tasks.txt"
    }
    if (-not (Test-Path $TasksFile)) { throw "找不到需求集文件 $TasksFile" }
    $requirements = Get-Content $TasksFile -Raw -Encoding UTF8
    $count = ($requirements -split "`n" | Where-Object { $_.Trim() -and -not $_.Trim().StartsWith("#") }).Count
    Write-Step "正式模式：$count 条需求 x $Repeat 次 = $($count * $Repeat) 个任务"
}

# 清历史。新旧数据混在一起算出来的指标没有意义
if (-not $KeepHistory) {
    Write-Step "清空历史记录"
    $null = Invoke-Api -Path "/eval/clear" -UseToken $Token
    $check = Invoke-Api -Path "/eval/status" -UseToken $Token
    if ($check.data.recorded -ne 0) {
        throw "清空失败，当前还有 $($check.data.recorded) 条记录，先手工处理"
    }
    Write-Ok "已清空"
}

Write-Step "发起跑批"
$r = Invoke-Api -Path "/eval/batch" -UseToken $Token -Body @{
    groupId      = $GroupId
    sessionId    = $SessionId
    requirements = $requirements
    repeat       = $Repeat
}
if ($r.status -ne "success") { throw "发起失败：$($r.info)" }
Write-Ok "已提交 $($r.data.taskCount) 个任务"
Write-Warn2 "跑批期间别关后端、别发第二批、别手动点群里的停止按钮"

Write-Step "等待完成（每 $PollSeconds 秒查一次，Ctrl+C 可随时退出，不影响后台继续跑）"
$start = Get-Date
while ($true) {
    Start-Sleep -Seconds $PollSeconds
    $s = Invoke-Api -Path "/eval/status" -UseToken $Token
    $elapsed = [int]((Get-Date) - $start).TotalMinutes
    Write-Host ("  [{0,3} 分钟] 已归档 {1} 个 | {2}" -f $elapsed, $s.data.recorded, $s.data.progress)
    if (-not $s.data.running) { break }
}
Write-Ok "跑批结束，共耗时 $([int]((Get-Date) - $start).TotalMinutes) 分钟"

Write-Step "评测报告"
if ($CostYuan -lt 0) {
    Write-Warn2 "没传 -CostYuan，token 成本那一格会留空"
    Write-Warn2 "去大模型控制台看跑批前后的消费差值，然后："
    Write-Warn2 "  .\run-eval.ps1 -ReportOnly -CostYuan 16.6"
}
Show-Report -UseToken $Token -Cost $CostYuan
