param(
    [int]$Port = 8787
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$state = Join-Path $root '.state'
New-Item -ItemType Directory -Force -Path $state | Out-Null
$env:DSH_MOBILE_HOME = $state

$cf = Join-Path $state 'cloudflared.exe'
if (-not (Test-Path $cf)) {
    $cmd = Get-Command cloudflared -ErrorAction SilentlyContinue
    if ($cmd) {
        $cf = $cmd.Source
    } else {
        Write-Host "cloudflared.exe not found." -ForegroundColor Red
        Write-Host "Put it at: $cf"
        Write-Host "Or install: winget install --id Cloudflare.cloudflared"
        exit 1
    }
}

# Refuse to start a second bridge on the same port.
try {
    $health = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/healthz" -TimeoutSec 2
    if ($health.service -eq 'dsh-mobile-bridge') {
        Write-Host "A bridge is already listening on 127.0.0.1:$Port." -ForegroundColor Yellow
        $pairFile = Join-Path $state 'pair-url.txt'
        if (Test-Path $pairFile) {
            Write-Host "Current pair URL:"
            Get-Content $pairFile
        }
        Write-Host "Close that bridge window first, then run this script again."
        exit 0
    }
} catch {
    # Nothing listening: expected.
}

$tunnelOut = Join-Path $state 'cloudflared.out.log'
$tunnelErr = Join-Path $state 'cloudflared.err.log'
Remove-Item $tunnelOut, $tunnelErr -Force -ErrorAction SilentlyContinue

Write-Host "Starting Cloudflare quick tunnel ..."
$tunnel = Start-Process -FilePath $cf `
    -ArgumentList @('tunnel', '--url', "http://127.0.0.1:$Port", '--no-autoupdate') `
    -RedirectStandardOutput $tunnelOut `
    -RedirectStandardError $tunnelErr `
    -WindowStyle Hidden `
    -PassThru

$publicUrl = $null
for ($i = 0; $i -lt 60; $i++) {
    Start-Sleep -Milliseconds 500
    $text = @()
    if (Test-Path $tunnelOut) { $text += Get-Content $tunnelOut -Raw -ErrorAction SilentlyContinue }
    if (Test-Path $tunnelErr) { $text += Get-Content $tunnelErr -Raw -ErrorAction SilentlyContinue }
    $m = [regex]::Match(($text -join "`n"), 'https://[a-z0-9-]+\.trycloudflare\.com')
    if ($m.Success) {
        $publicUrl = $m.Value
        break
    }
    if ($tunnel.HasExited) { break }
}

if (-not $publicUrl) {
    Write-Host "Could not get a Cloudflare quick-tunnel URL." -ForegroundColor Red
    Write-Host "See $tunnelErr"
    if (-not $tunnel.HasExited) { Stop-Process -Id $tunnel.Id -Force -ErrorAction SilentlyContinue }
    exit 1
}

Write-Host "Public URL: $publicUrl" -ForegroundColor Green
Write-Host "Starting DSH Mobile Bridge ..."
Write-Host "Press Ctrl+C to stop both the bridge and the tunnel."
Write-Host ""

try {
    & node (Join-Path $root 'bridge\server.mjs') --bind 127.0.0.1 --port $Port --public-url $publicUrl
} finally {
    if ($tunnel -and -not $tunnel.HasExited) {
        Stop-Process -Id $tunnel.Id -Force -ErrorAction SilentlyContinue
    }
}
