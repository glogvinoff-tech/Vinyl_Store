$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
python scripts/init-env.py
if ($LASTEXITCODE -ne 0) { throw 'Не удалось создать .env. Проверьте Python 3.' }
docker compose --env-file .env -f compose.kr3.yml up -d --build --wait
if ($LASTEXITCODE -ne 0) { throw 'Не удалось запустить контейнеры. Проверьте Docker Desktop.' }
Write-Host 'SPIN: http://localhost:8080/ | Управление: http://localhost:8080/admin.html | Письма: http://localhost:8025/'
