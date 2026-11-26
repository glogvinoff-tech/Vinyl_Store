import { chromium, expect } from '@playwright/test';
import http from 'node:http';
import { readFile, mkdir } from 'node:fs/promises';
import { resolve, extname } from 'node:path';
const root=resolve('../backend/src/main/resources/static');
const server=http.createServer(async(req,res)=>{
  try {const path=new URL(req.url,'http://localhost').pathname;const file=resolve(root,'.'+path); if(!file.startsWith(root))throw Error();
    const data=await readFile(extname(path)?file:resolve(root,'index.html'));
    res.setHeader('Content-Type',({'.js':'application/javascript','.css':'text/css','.png':'image/png','.svg':'image/svg+xml','.mp3':'audio/mpeg','.html':'text/html'})[extname(path)]||'text/html');res.end(data);
  }catch{res.statusCode=404;res.end();}
});
await new Promise(r=>server.listen(4179,'127.0.0.1',r));
let browser;
try {
 browser=await chromium.launch({headless:true,channel:process.env.PLAYWRIGHT_CHANNEL || "msedge"}); const page=await browser.newPage(); const errors=[];page.on('pageerror',e=>errors.push(e.message));
 const record={id:'00000000-0000-0000-0000-000000000001',name:'Test Album',artist:'Artist',price:1000,salePrice:850,discountPercent:15,stock:5,tags:['Цветной винил','Русское'],images:[],cover:'/assets/1-imgImage50.png',active:true,version:0};
 let saved;
 await page.route('**/api/**',route=>{
  const path=new URL(route.request().url()).pathname;
  if(path === '/api/images') return route.fulfill({json:{url:record.cover}});
  if(path === '/api/records' && route.request().method() === 'POST') {saved=route.request().postDataJSON(); return route.fulfill({status:201,json:{...record,...saved}});}
  const body=path==='/api/auth/refresh'?{accessToken:'test',user:{id:'admin',displayName:'Admin',roles:['ADMIN']}}:path==='/api/records'?[record]:{content:[record],totalPages:1};
  return route.fulfill({json:body});
 });
 await page.goto('http://127.0.0.1:4179/catalog');
 await page.getByRole('searchbox',{name:'Поиск пластинок'}).fill('цветной винил');
 await expect(page.locator('.album')).toHaveCount(1);
 await page.locator('.album').getByRole('button',{name:'В корзину',exact:true}).click();
 await page.getByRole('spinbutton',{name:'Количество в корзине'}).fill('4');
 await expect(page.getByRole('spinbutton',{name:'Количество в корзине'})).toHaveValue('4');
 await page.getByRole('button',{name:'Удалить все',exact:true}).click();
 await expect(page.locator('.album').getByRole('button',{name:'В корзину',exact:true})).toBeVisible();
 await page.goto('http://127.0.0.1:4179/admin?new=1');
 await expect(page.getByRole('dialog')).toBeVisible();
 await expect(page.getByLabel('Скидка, %')).toBeVisible();
 await expect(page.locator('input[type=file]')).toHaveCount(1);
 await page.getByLabel('Цветной винил',{exact:true}).check();
 await mkdir('../.playwright', {recursive:true});
 await page.screenshot({path:'../.playwright/admin-preview.png',fullPage:true});
 await page.getByLabel('Артикул',{exact:true}).fill('NEW-001');
 await page.getByLabel('Название',{exact:true}).fill('New Album');
 await page.getByLabel('Исполнитель',{exact:true}).fill('Artist');
 await page.getByLabel('Скидка, %').fill('20');
 const transfer=await page.evaluateHandle(()=>{const dt=new DataTransfer();dt.items.add(new File(['test'],'cover.png',{type:'image/png'}));return dt;});
 await page.locator('.image-dropzone').dispatchEvent('drop',{dataTransfer:transfer});
 await expect(page.locator('.image-previews img')).toHaveCount(1);
 await page.getByRole('button',{name:'Сохранить',exact:true}).click();
 await expect(page.getByRole('dialog')).toHaveCount(0);
 expect(saved.discountPercent).toBe(20);expect(saved.images).toEqual([record.cover]);expect(saved.tags).toContain('Цветной винил');
 await page.goto('http://127.0.0.1:4179/catalog');
 await page.getByRole('button',{name:'Воспроизвести',exact:true}).click();
 await expect.poll(()=>page.locator('.interactive-radio-vinyl img').evaluate(img=>img.style.transform)).not.toBe('rotate(0deg)');
 const first=await page.locator('.interactive-radio-vinyl img').evaluate(img=>img.style.transform);
 await expect.poll(()=>page.locator('.interactive-radio-vinyl img').evaluate(img=>img.style.transform)).not.toBe(first);
 await page.setViewportSize({width:390,height:844});
 await page.locator('.album').getByRole('button',{name:'В корзину',exact:true}).click();
 await page.getByRole('spinbutton',{name:'Количество в корзине'}).fill('3');
 await page.goto('http://127.0.0.1:4179/cart');
 await page.getByLabel('Выбрать все',{exact:true}).check();
 await page.getByRole('button',{name:'Удалить выбранные',exact:true}).click();
 await expect(page.getByText('Корзина пуста.',{exact:false})).toBeVisible();
 if(errors.length) throw Error(errors.join('\n'));
 console.log('PASS: catalog search, quantity/removal, admin editor, radio rotation; no page errors');
}finally{await browser?.close();server.close();}

