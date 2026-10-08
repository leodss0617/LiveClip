$ErrorActionPreference = 'Stop'
$bin = Join-Path $env:LOCALAPPDATA 'LiveClip\bin'
New-Item -ItemType Directory -Force -Path $bin | Out-Null
$launcher = Join-Path $PSScriptRoot 'liveclip-desktop.ps1'
if ($launcher.Contains('%')) { throw 'Mova a pasta LiveClip para um caminho sem o caractere %.' }
$command = "@echo off`r`npowershell.exe -NoProfile -ExecutionPolicy Bypass -File `"$launcher`" %*`r`nexit /b %errorlevel%`r`n"
[IO.File]::WriteAllText((Join-Path $bin 'liveclip.cmd'), $command, [Text.Encoding]::Default)
$current = [Environment]::GetEnvironmentVariable('Path', 'User')
if (($current -split ';') -notcontains $bin) { [Environment]::SetEnvironmentVariable('Path', "$bin;$current", 'User') }
$env:PATH = "$bin;$env:PATH"
Write-Host 'Comando instalado. Mantenha esta pasta no lugar. Abra outro terminal e digite: liveclip'
Write-Host 'Python e Docker Desktop serão instalados se necessário; WSL 2 ou reinício podem exigir sua intervenção.'
