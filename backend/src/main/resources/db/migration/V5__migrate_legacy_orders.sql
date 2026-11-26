-- UUID детерминированы исходными ID. Исходные таблицы КР №1–2 остаются доступными.
ALTER TABLE purchase_order ALTER COLUMN album_title TYPE VARCHAR(160);
ALTER TABLE purchase_order ALTER COLUMN artist TYPE VARCHAR(120);
ALTER TABLE purchase_order ADD COLUMN legacy_order_id BIGINT UNIQUE REFERENCES orders(id);
ALTER TABLE purchase_order ADD COLUMN legacy_customer_id BIGINT REFERENCES users(id);
ALTER TABLE vinyl_record ADD COLUMN legacy_vinyl_id BIGINT UNIQUE REFERENCES vinyl(id);
INSERT INTO vinyl_record(id,code,name,artist,genre,price,stock,active,created_at,updated_at,version,legacy_vinyl_id)
 SELECT md5('vinyl:'||id)::uuid,'LEGACY-LP-'||id,title,artist,genre,price,stock,TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0,id FROM vinyl;
INSERT INTO purchase_order(id,external_id,album_title,artist,customer_name,email,phone,ordered_on,
 record_id,status,total_price,quantity,paid,priority_level,archived,created_at,updated_at,version,legacy_order_id,legacy_customer_id)
 SELECT md5('order:'||o.id)::uuid,'LEGACY-'||o.id,o.album_title,o.artist,u.full_name,u.email,u.phone,o.created_at::date,
 (SELECT r.id FROM vinyl_record r WHERE r.name=o.album_title AND r.artist=o.artist ORDER BY r.code LIMIT 1),
 o.status,o.total_price,o.quantity,o.status IN ('PAID','SHIPPED','COMPLETED'),
 CASE WHEN o.status IN ('CANCELED','COMPLETED') THEN 'NOT_APPLICABLE' WHEN o.status='SHIPPED' THEN 'LOW'
 WHEN o.status='PAID' AND o.total_price>=10000 THEN 'CRITICAL' WHEN o.status='PAID' THEN 'HIGH'
 WHEN o.total_price>=5000 THEN 'MEDIUM' ELSE 'LOW' END,
 FALSE,o.created_at AT TIME ZONE 'Europe/Moscow',CURRENT_TIMESTAMP,0,o.id,u.id
 FROM orders o JOIN users u ON u.id=o.user_id;
ALTER TABLE purchase_order ADD CONSTRAINT chk_order_status CHECK(status IN ('NEW','PAID','SHIPPED','COMPLETED','CANCELED'));
ALTER TABLE purchase_order ADD CONSTRAINT chk_order_priority CHECK(priority_level IN ('LOW','MEDIUM','HIGH','CRITICAL','NOT_APPLICABLE'));
CREATE INDEX idx_order_paid ON purchase_order(paid);
CREATE INDEX idx_order_created ON purchase_order(created_at);
