# Runs `sbt <commands...>` on Windows, retrying on known sbt boot-race /
# fast-fail flakiness (actions/runner-images#13629, sbt/sbt#6777). Shared by the
# scripted and e2e CI steps so the retry logic lives in one place. `-Command`
# takes one OR MORE sbt commands: each array element is passed to sbt as a
# separate argument (= a separate command), so multi-command callers must pass
# an array (`-Command "a","b"`), not one space-joined string, which sbt would
# try to parse as a single command.
[CmdletBinding()]
param(
  [Parameter(Mandatory = $true)] [string[]] $Command,
  [Parameter(Mandatory = $true)] [string] $Label,
  [int] $MaxAttempts = 3
)

$logFile = Join-Path $env:RUNNER_TEMP "sbt-$Label.log"
for ($attempt = 1; $attempt -le $MaxAttempts; $attempt++) {
  if (Test-Path $logFile) { Remove-Item $logFile -Force }
  & sbt @Command *>&1 | Tee-Object -FilePath $logFile
  $exit = $LASTEXITCODE
  if ($exit -eq 0) { exit 0 }
  $log = if (Test-Path $logFile) { Get-Content $logFile -Raw } else { '' }
  $retryReason = $null
  if ($log -match 'Nonzero exit value: -1073740791') {
    $retryReason = 'Windows fast-fail (-1073740791); see actions/runner-images#13629'
  } elseif ($log -match 'Could not create lock for .*sbt-(load|server).*_lock' -or $log -match 'ServerAlreadyBootingException') {
    $retryReason = 'sbt named-pipe lock race on boot; see sbt/sbt#6777'
  }
  if ($attempt -lt $MaxAttempts -and $retryReason) {
    Write-Host "::warning::$retryReason. Retrying $Label (attempt $($attempt + 1)/$MaxAttempts)."
    Get-Process -Name java,sbt,sbtn,node,esbuild,electron -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
    Start-Sleep -Seconds 10
    continue
  }
  exit $exit
}
