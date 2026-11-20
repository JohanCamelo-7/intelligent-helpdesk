# Crea el archivo .env a partir de .env.example con contraseñas y secretos aleatorios (Windows PowerShell).
# Uso: powershell -ExecutionPolicy Bypass -File scripts\preparar-env.ps1 [-Forzar]
param([switch]$Forzar)
$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..")

if ((Test-Path .env) -and -not $Forzar) {
  Write-Host ".env ya existe; no se modificó. Usa -Forzar para regenerarlo."
  exit 0
}
if (Test-Path .env) { Copy-Item .env .env.bak -Force }

$chars = [char[]]'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789'
function Rand([int]$n) {
  $bytes = New-Object byte[] $n
  [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
  -join ($bytes | ForEach-Object { $chars[$_ % $chars.Length] })
}

$adminPass = "Admin$(Rand 10)7"
$out = foreach ($line in Get-Content .env.example) {
  if     ($line -like 'JWT_SECRET=*')     { "JWT_SECRET=$(Rand 64)" }
  elseif ($line -like 'ADMIN_PASSWORD=*') { "ADMIN_PASSWORD=$adminPass" }
  elseif ($line -match '^([A-Z0-9_]+)=cambia-') { "$($Matches[1])=$(Rand 24)" }
  else   { $line }
}
# UTF-8 sin BOM y saltos de línea LF, como espera Docker Compose
[System.IO.File]::WriteAllText((Join-Path (Get-Location) ".env"), (($out -join "`n") + "`n"), (New-Object System.Text.UTF8Encoding $false))

Write-Host "Archivo .env creado con secretos aleatorios."
Write-Host "  Usuario administrador:   $((Select-String '^ADMIN_EMAIL=' .env).Line.Split('=')[1])"
Write-Host "  Contraseña:              $adminPass"
Write-Host "  Airflow (usuario admin): $((Select-String '^AIRFLOW_ADMIN_PASSWORD=' .env).Line.Split('=')[1])"
Write-Host "Guárdalas: también quedan en el archivo .env."
