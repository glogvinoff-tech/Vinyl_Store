"""Add catalog records through the existing admin API (POST /api/records).

Reads the curated selection (scripts/catalog/selection.json), resolves artwork
(Deezer search; local /assets covers are reused, new images go through the
existing POST /api/images upload), assigns tags (пометки) and discounts, then
creates records via the admin REST endpoint. Idempotent: record codes that
already exist on the server are skipped.

Usage:
  python scripts/catalog/add-records.py --email admin@spin.local --password *** \
      [--base http://localhost:8080] [--dry-run]
"""
import argparse, hashlib, json, re, sys, time, unicodedata, urllib.parse, urllib.request, uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SELECTION = ROOT / 'scripts/catalog/selection.json'
CACHE = ROOT / 'scripts/catalog/cache'
STATE = ROOT / 'scripts/catalog/added.json'

# Русские названия жанров выборки -> коды, которые уже понимает фронтенд
# (genreNames в frontend/src/api/client.js). Новые жанры хранятся русскими
# названиями — они совпадают с плитками жанров, маппинг не требуется.
GENRE_CODES = {
    'Рок': 'Rock', 'Поп': 'Pop', 'Классика': 'Classical', 'Джаз': 'Jazz',
    'Панк': 'Punk', 'Хип-хоп': 'Hip Hop', 'Кантри': 'Country', 'Инди': 'Indie',
    'Электроника': 'Electronic',
}

PRICES = [2990, 3490, 3990, 4490, 4990, 5490, 5990, 6490, 6990, 7490, 7990, 8490, 8990, 9490]
DISCOUNTS = [10, 15, 20, 25, 30]

UA = {'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)'}


def norm(text):
    stripped = ''.join(c for c in unicodedata.normalize('NFKD', text.lower()) if not unicodedata.combining(c))
    return re.sub(r'[^\w]+', ' ', stripped).strip()


def score(entry, candidate):
    a, b = norm(entry['artist']), norm(candidate['artist']['name'])
    t, u = norm(entry['title']), norm(candidate['title'])
    artist = 1 if a == b else .85 if a in b or b in a else len(set(a.split()) & set(b.split())) / max(len(set(a.split())), len(set(b.split())))
    title = 1 if t == u else .93 if u.startswith(t + ' ') or t.startswith(u + ' ') else len(set(t.split()) & set(u.split())) / max(len(set(t.split())), len(set(u.split())))
    return artist * title


def http(url, data=None, headers=None, binary=False, method=None, timeout=30):
    req = urllib.request.Request(url, data=data, headers={**UA, **(headers or {})}, method=method)
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        body = resp.read()
        return body if binary else json.loads(body) if body else None


class Api:
    def __init__(self, base, email, password):
        self.base = base.rstrip('/')
        auth = http(self.base + '/api/auth/login', json.dumps(
            {'email': email, 'password': password, 'clientType': 'NATIVE'}).encode(),
            {'Content-Type': 'application/json'})
        if auth['user'].get('passwordChangeRequired'):
            sys.exit('У администратора требуется смена временного пароля — смените его и повторите.')
        self.token = auth['accessToken']
        print(f"Вход выполнен: {auth['user']['email']} ({', '.join(auth['user']['roles'])})")

    def get(self, path):
        return http(self.base + '/api/' + path, headers={'Authorization': 'Bearer ' + self.token})

    def post_json(self, path, payload):
        return http(self.base + '/api/' + path, json.dumps(payload).encode(),
                    {'Authorization': 'Bearer ' + self.token, 'Content-Type': 'application/json'})

    def upload_image(self, data, filename):
        boundary = uuid.uuid4().hex
        body = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{filename}"\r\n'
                f'Content-Type: image/jpeg\r\n\r\n').encode() + data + f'\r\n--{boundary}--\r\n'.encode()
        return http(self.base + '/api/images', body,
                    {'Authorization': 'Bearer ' + self.token,
                     'Content-Type': f'multipart/form-data; boundary={boundary}'})


def deezer_cover(entry):
    """JPEG-байты обложки с Deezer либо None."""
    key = hashlib.sha256((entry['artist'] + '|' + entry['title']).encode()).hexdigest()[:16]
    cache_file = CACHE / (key + '.json')
    if cache_file.exists():
        cached = json.loads(cache_file.read_text(encoding='utf-8'))
        image = ROOT / 'frontend/public/assets' / cached.get('cover', '').split('/')[-1]
        if cached.get('cover') and image.exists():
            return image.read_bytes(), cached.get('matchedTitle')
    q = urllib.parse.urlencode({'q': f"{entry['artist']} {entry['title']}", 'limit': 50})
    candidates = [c for c in json.loads(http('https://api.deezer.com/search/album?' + q)).get('data', [])
                  if c.get('cover_big')]
    candidates.sort(key=lambda c: score(entry, c), reverse=True)
    if not candidates:
        return None, None
    best = candidates[0]
    if score(entry, best) < .8:
        return None, None
    return http(best['cover_big'], binary=True), best['title']


def build_tags(entry, digest):
    tags = list(entry.get('tags') or [])
    if entry['format'] == 'CD' and 'CD' not in tags:
        tags.append('CD')
    if entry['year'] <= 1980 and entry['format'] != 'CD':
        tags.append('Редкий винтаж')
    if entry['format'] == 'LP' and digest % 5 == 0:
        tags.append('Цветной винил')
    if digest % 9 == 0:
        tags.append('Подарочные издания')
    if digest % 13 == 0:
        tags.append('Эксклюзивы')
    return tags[:20]


def describe(entry, tags):
    kind = 'CD-издание' if entry['format'] == 'CD' else 'Виниловое издание'
    notes = {'Цветной винил': 'Цветной винил.', 'Подарочные издания': 'Подарочное издание.',
             'Редкий винтаж': 'Редкий винтаж.', 'Эксклюзивы': 'Эксклюзив магазина.',
             'Русское': 'Отечественная классика.', 'CD': 'Компакт-диск.'}
    extra = ' '.join(notes[t] for t in tags if t in notes)
    return f"{kind} альбома «{entry['title']}» — {entry['artist']} ({entry['year']}). {extra}".strip()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--email', required=True)
    parser.add_argument('--password', required=True)
    parser.add_argument('--base', default='http://localhost:8080')
    parser.add_argument('--dry-run', action='store_true')
    args = parser.parse_args()
    sys.stdout.reconfigure(encoding='utf-8')

    selection = json.loads(SELECTION.read_text(encoding='utf-8'))
    state = json.loads(STATE.read_text(encoding='utf-8')) if STATE.exists() else {}

    api = Api(args.base, args.email, args.password)
    existing = {r['code'] for r in api.get('records?activeOnly=false')}
    print(f'На сервере уже {len(existing)} записей каталога')

    created = skipped = failed = 0
    for index, entry in enumerate(selection):
        code = f'LP-{index + 14:03d}'
        if code in existing:
            skipped += 1
            continue
        digest = int(hashlib.sha256((entry['artist'] + entry['title']).encode()).hexdigest(), 16)
        tags = build_tags(entry, digest)
        payload = {
            'code': code,
            'name': entry['title'][:200],
            'artist': entry['artist'][:120],
            'genre': GENRE_CODES.get(entry['genre'], entry['genre']),
            'releaseYear': entry['year'],
            'price': PRICES[digest % len(PRICES)],
            'stock': 3 + digest % 13,
            'description': describe(entry, tags),
            'active': True,
            'tags': tags,
            'images': [],
            'discountPercent': DISCOUNTS[digest % len(DISCOUNTS)] if digest % 11 == 0 else 0,
        }
        try:
            key = hashlib.sha256((entry['artist'] + '|' + entry['title']).encode()).hexdigest()[:16]
            if key in state:
                payload['cover'] = state[key]['cover']
            else:
                image, matched = deezer_cover(entry)
                if image is None:
                    print(f'НЕТ ОБЛОЖКИ: {entry["artist"]} — {entry["title"]}', flush=True)
                    failed += 1
                    continue
                if args.dry_run:
                    payload['cover'] = '/api/images/00000000-0000-0000-0000-000000000000'
                else:
                    payload['cover'] = api.upload_image(image, 'cover.jpg')['url']
                state[key] = {'cover': payload['cover'], 'matched': matched}
                STATE.write_text(json.dumps(state, ensure_ascii=False, indent=1), encoding='utf-8')
            if args.dry_run:
                print('DRY', json.dumps(payload, ensure_ascii=False)[:160], flush=True)
            else:
                api.post_json('records', payload)
                created += 1
                if created % 20 == 0:
                    print(f'Создано {created}…', flush=True)
            time.sleep(0.05)
        except Exception as e:
            failed += 1
            print(f'ОШИБКА {code} {entry["artist"]} — {entry["title"]}: {e}', flush=True)
    print(f'Готово: создано {created}, пропущено {skipped}, ошибок {failed}')


if __name__ == '__main__':
    main()
