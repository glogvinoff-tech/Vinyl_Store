#!/usr/bin/env python3
"""Проверка миграции данных КР №1 в изолированной схеме; всё откатывается."""
from pathlib import Path
import subprocess, uuid
root=Path(__file__).resolve().parents[1]
schema='qa_legacy_'+uuid.uuid4().hex
sql=f'BEGIN; CREATE SCHEMA {schema}; SET LOCAL search_path TO {schema};\n'
sql+=(root/'src/main/resources/database.sql').read_text()+'\n'
for file in sorted((root/'backend/src/main/resources/db/migration').glob('V*.sql')):
    sql+=file.read_text()+'\n'
sql+='''
DO $$ BEGIN
 IF (SELECT count(*) FROM purchase_order WHERE legacy_order_id IS NOT NULL) <> (SELECT count(*) FROM orders) THEN
   RAISE EXCEPTION 'Order count mismatch'; END IF;
 IF (SELECT count(*) FROM vinyl_record WHERE legacy_vinyl_id IS NOT NULL) <> (SELECT count(*) FROM vinyl) THEN
   RAISE EXCEPTION 'Vinyl count mismatch'; END IF;
 IF EXISTS(SELECT 1 FROM orders o JOIN purchase_order p ON p.legacy_order_id=o.id
   WHERE p.total_price<>o.total_price OR p.quantity<>o.quantity OR p.status<>o.status OR p.legacy_customer_id<>o.user_id) THEN
   RAISE EXCEPTION 'Legacy data mismatch'; END IF;
END $$;
ROLLBACK;
'''
r=subprocess.run(['docker','compose','--env-file','.env','-f','compose.kr3.yml','exec','-T','db','sh','-c','psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"'],cwd=root,input=sql,text=True,capture_output=True)
if r.returncode:
    print(r.stderr);raise SystemExit(r.returncode)
print('OK: исходные заказы, суммы, количество, статусы и связи покупателей перенесены; проверочная схема удалена откатом.')
