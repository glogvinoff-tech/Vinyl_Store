#!/usr/bin/env python3
"""Архив исходников для сдачи: без секретов, IDE, target, JavaFX-примера преподавателя."""
from pathlib import Path
import zipfile
root=Path(__file__).resolve().parents[1]
output=root/'exports/spin-kr3-submission.zip'
output.parent.mkdir(exist_ok=True)
paths=['frontend','.dockerignore','backend','recommender','docs/kr3','examples','scripts','src','pom.xml','README.md','README_NEURAL_NETWORK.md','README_SALES_FORECAST.md','.env.example','compose.kr3.yml','docker-compose.yml','.gitignore','docs/er-diagram.md']
excluded={'target','dist','__pycache__','.DS_Store','node_modules','.idea','.git'}
count=0
with zipfile.ZipFile(output,'w',zipfile.ZIP_DEFLATED) as z:
 for relative in paths:
  p=root/relative
  files=p.rglob('*') if p.is_dir() else [p]
  for f in files:
   if not f.is_file() or excluded.intersection(f.relative_to(root).parts):continue
   if any(str(f.relative_to(root)).startswith('backend/src/main/resources/static/'+d+'/') for d in ['frontend-assets','design','fonts','audio']):continue
   if f.name=='.env' or (f.name.startswith('.env.') and f.name!='.env.example'):continue
   z.write(f,f.relative_to(root));count+=1
print(f'{output.name}: {count} файлов, {output.stat().st_size//1024//1024} МБ; .env и результаты сборки исключены.')
