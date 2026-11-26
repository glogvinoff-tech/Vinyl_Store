#!/usr/bin/env python3
"""Создаёт локальный .env, не перезаписывая существующие настройки."""
from pathlib import Path
import secrets, base64
root=Path(__file__).resolve().parents[1]
p=root/'.env'
if p.exists():
    print('.env уже существует; настройки сохранены.')
else:
    text=(root/'.env.example').read_text()
    text=text.replace('REPLACE_WITH_RANDOM_PASSWORD',secrets.token_urlsafe(24))
    text=text.replace('REPLACE_WITH_RANDOM_BASE64_32_BYTES',base64.b64encode(secrets.token_bytes(32)).decode())
    text=text.replace('REPLACE_WITH_STRONG_TEMPORARY_PASSWORD','Sp1!'+secrets.token_urlsafe(20))
    p.write_text(text);p.chmod(0o600)
    print('Создан .env. Логин и временный пароль администратора находятся в этом файле.')
