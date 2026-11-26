import json,urllib.request
from pathlib import Path
p=Path('scripts/catalog/rhino-products.json')
if not p.exists():p.write_text(json.dumps(json.load(urllib.request.urlopen('https://store.rhino.com/products.json?limit=250',timeout=30)),ensure_ascii=False,indent=2),encoding='utf-8')
d=json.loads(p.read_text(encoding='utf-8'))['products']
for x in d:
 text=(x['title']+' '+(x['body_html'] or '')).lower()
 if any(w in text for w in ['color','box set','boxed set','exclusive','mono']):print(x['id'],x['vendor'],x['title'],x['handle'])

