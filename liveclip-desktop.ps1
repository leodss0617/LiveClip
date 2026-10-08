$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
$python = Get-Command python -ErrorAction SilentlyContinue
if ($python) { & $python.Source -c "import sys; sys.exit(0 if sys.version_info >= (3,9) else 1)" *> $null; if ($LASTEXITCODE -ne 0) { $python = $null } }
if (-not $python) {
    $pythonPath = Join-Path $env:LOCALAPPDATA 'Programs\Python\Python312\python.exe'
    if (-not (Test-Path $pythonPath)) {
        if (-not (Get-Command winget -ErrorAction SilentlyContinue)) { throw 'Instale Python 3.12 (python.org) e execute liveclip novamente.' }
        Write-Host 'Instalando Python gratuito para verificar e aplicar as atualizações locais.'
        winget install --id Python.Python.3.12 --exact --scope user --accept-package-agreements --accept-source-agreements
        if ($LASTEXITCODE -ne 0) { throw 'A instalação do Python falhou. Instale Python 3.12 e execute liveclip novamente.' }
    }
    if (-not (Test-Path $pythonPath)) { throw 'Abra outro terminal para reconhecer Python e execute liveclip novamente.' }
    $python = @{ Source = $pythonPath }
}
$downloads = $env:LIVECLIP_DOWNLOADS
if (-not $downloads) {
    $folderKey = Get-ItemProperty 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Explorer\User Shell Folders'
    $downloads = [Environment]::ExpandEnvironmentVariables($folderKey.'{374DE290-123F-4565-9164-39C4925E467B}')
    if (-not $downloads) { $downloads = Join-Path $env:USERPROFILE 'Downloads' }
}
& $python.Source (Join-Path $PSScriptRoot 'desktop-update.py') $PSScriptRoot $downloads
if ($LASTEXITCODE -ne 0) { throw 'A atualização foi recusada. Consulte a mensagem acima.' }
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    $dockerBin = Join-Path $env:ProgramFiles 'Docker\Docker\resources\bin'
    if (Test-Path (Join-Path $dockerBin 'docker.exe')) { $env:PATH = "$dockerBin;$env:PATH" }
    else {
        if (-not (Get-Command winget -ErrorAction SilentlyContinue)) { throw 'Instale Docker Desktop e WSL 2 e execute liveclip novamente.' }
        Write-Host 'Instalando Docker Desktop. WSL 2, virtualização ou reinício podem exigir sua intervenção.'
        winget install --id Docker.DockerDesktop --exact --accept-package-agreements --accept-source-agreements
        if ($LASTEXITCODE -ne 0) { throw 'A instalação do Docker falhou. Verifique WSL 2 e execute liveclip novamente.' }
        $env:PATH = "$dockerBin;$env:PATH"
        if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { throw 'Reinicie se solicitado, abra outro terminal e execute liveclip novamente.' }
    }
}
& (Join-Path $PSScriptRoot 'iniciar.ps1') @args
