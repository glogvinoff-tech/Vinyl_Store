"""Fetch album artwork once; retain exact matches and provenance for review."""
import json, re, time, unicodedata, urllib.request, urllib.parse, hashlib
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor, as_completed
ROOT=Path(__file__).resolve().parents[2]
CACHE=ROOT/'scripts/catalog/cache';CACHE.mkdir(exist_ok=True)
OUT=ROOT/'frontend/public/assets';OUT.mkdir(exist_ok=True)
def get(url):
 import subprocess
 time.sleep(1)
 result=subprocess.run(['powershell.exe','-NoProfile','-ExecutionPolicy','Bypass','-File',str(ROOT/'scripts/catalog/fetch-http.ps1'),url],capture_output=True,timeout=40)
 if result.returncode:raise RuntimeError(result.stderr.decode('utf-8',errors='replace')[:200])
 return result.stdout
def norm(text):
 return re.sub(r'[^\w]+',' ',''.join(c for c in unicodedata.normalize('NFKD',text.lower()) if not unicodedata.combining(c))).strip()
def score(r,c):
 a=norm(r['artist']);b=norm(c['artist']['name']);t=norm(r['title']);u=norm(c['title'])
 artist=1 if a==b else .85 if a in b or b in a else len(set(a.split())&set(b.split()))/max(len(set(a.split())),len(set(b.split())))
 title=1 if t==u else .93 if u.startswith(t+' ') or t.startswith(u+' ') else len(set(t.split())&set(u.split()))/max(len(set(t.split())),len(set(u.split())))
 return artist*title

def fetch(r):
 key=hashlib.sha256((r['artist']+'|'+r['title']).encode()).hexdigest()[:16];cache=CACHE/(key+'.json')
 if cache.exists():
  cached=json.loads(cache.read_text(encoding='utf-8'))
  if 'cover' in cached:return cached
 result=dict(r)
 try:
  q='artist:"'+r['artist']+'" album:"'+r['title']+'"'
  url='https://api.deezer.com/search?'+urllib.parse.urlencode({'q':q,'limit':30})
  tracks=json.loads(get(url)).get('data',[])
  candidates=list({t['album']['id']:{**t['album'], 'artist':t['artist'], 'link':'https://www.deezer.com/album/'+str(t['album']['id'])} for t in tracks}.values())
  if not candidates:
   url='https://api.deezer.com/search/album?'+urllib.parse.urlencode({'q':r['artist']+' '+r['title'],'limit':50})
   candidates=json.loads(get(url)).get('data',[])
  candidates.sort(key=lambda c:score(r,c),reverse=True)
  result['candidates']=[{'artist':c['artist']['name'],'title':c['title'],'id':c['id'],'score':round(score(r,c),3),'cover':c['cover_big'],'url':c['link']} for c in candidates[:3]]
  if candidates and score(r,candidates[0])>=.83:
   c=candidates[0];name='catalog-'+key+'.jpg';image=get(c['cover_big'])
   if not image.startswith(b'\xff\xd8'):raise ValueError('Not a JPEG')
   (OUT/name).write_bytes(image)
   result.update(cover='/assets/'+name,imageSource=c['cover_big'],metadataSource=c['link'],matchedArtist=c['artist']['name'],matchedTitle=c['title'])
  else:result['error']='No confident match'
 except Exception as e:result['error']=str(e)
 cache.write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8');time.sleep(2);return result
if __name__=='__main__':
 import sys
 records=json.loads((ROOT/'scripts/catalog/selection.json').read_text(encoding='utf-8'))
 if len(sys.argv)>1:records=records[int(sys.argv[1]):int(sys.argv[2])]
 results=[]
 with ThreadPoolExecutor(max_workers=2) as pool:
  futures=[pool.submit(fetch,r) for r in records]
  for i,f in enumerate(as_completed(futures),1):
   r=f.result();results.append(r)
   if r.get('error'):print('REVIEW',r['artist'],r['title'],r['error'],flush=True)
   elif i%20==0:print('Downloaded',i,'/',len(records),flush=True)
 (ROOT/'scripts/catalog/resolved.json').write_text(json.dumps(results,ensure_ascii=False,indent=2),encoding='utf-8')
 print('Ready',sum('cover' in r for r in results),'of',len(results),flush=True)
