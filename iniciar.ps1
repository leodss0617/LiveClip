$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
& (Join-Path $PSScriptRoot "instalar-comando-windows.ps1")
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    throw 'Instale Docker Desktop para uso pessoal: https://www.docker.com/products/docker-desktop/ . Ative WSL 2, reinicie e execute ABRIR-NO-WINDOWS.bat novamente.'
}
docker compose version
if ($LASTEXITCODE -ne 0) { throw 'Instale e inicie o Docker Desktop: https://docs.docker.com/get-started/get-docker/' }
docker info *> $null
if ($LASTEXITCODE -ne 0) {
    $desktopPath = Join-Path $env:ProgramFiles 'Docker\Docker\Docker Desktop.exe'
    if (Test-Path $desktopPath) { Start-Process $desktopPath }
    Write-Host 'Aguardando Docker Desktop iniciar (até 2 minutos)...'
    $ready = $false
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        docker info *> $null
        if ($LASTEXITCODE -eq 0) { $ready = $true; break }
        Start-Sleep -Seconds 2
    }
    if (-not $ready) { throw 'Abra Docker Desktop e verifique WSL 2. Depois execute novamente.' }
}
if (-not (Test-Path '.env')) {
    $bytes = New-Object byte[] 24
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    $rng.GetBytes($bytes)
    $rng.Dispose()
    $passwordValue = ([BitConverter]::ToString($bytes)).Replace('-', '').ToLowerInvariant()
    [System.IO.File]::WriteAllText((Join-Path $PSScriptRoot '.env'), "LIVECLIP_PASSWORD=$passwordValue`nBIND_ADDRESS=0.0.0.0`n")
}
docker compose up -d --build
if ($LASTEXITCODE -ne 0) { throw 'A inicialização falhou. Consulte: docker compose logs' }
Write-Host 'Painel no PC: http://localhost:8080'
Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
    Where-Object { $_.IPAddress -ne '127.0.0.1' -and $_.IPAddress -notlike '169.254.*' } |
    ForEach-Object { Write-Host "Endereço possível no celular (mesmo Wi-Fi): http://$($_.IPAddress):8080" }
Get-Content '.env' | Where-Object { $_ -match '^LIVECLIP_PASSWORD=' } |
    ForEach-Object { Write-Host ($_ -replace '^LIVECLIP_PASSWORD=', 'Senha do painel: ') }
Write-Host 'Mantenha o PC ligado e sem suspensão. Libere acesso no firewall apenas na rede privada.'
Start-Process 'http://localhost:8080'
Write-Host 'Sua senha está na linha LIVECLIP_PASSWORD do arquivo .env. Não compartilhe esse arquivo.'
Write-Host 'O primeiro início baixa os modelos. Acompanhe: docker compose logs -f model-setup studio'
