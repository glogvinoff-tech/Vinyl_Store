import test from 'node:test';
import assert from 'node:assert/strict';
import { matchesSearch, matchesCategory } from '../src/catalog.js';
import { recordModel, api } from '../src/api/client.js';

const record = {title:'Альбом', artist:'Артист', tags:['Цветной винил', 'Подарочные издания', 'Русское'], price:1000, salePrice:850, discountPercent:15};
test('search finds edition names, Russian case and singular gift edition', () => {
  for (const q of ['цветной винил', 'подарочное издание', 'РУССКОЕ', 'скидки']) assert.ok(matchesSearch(record,q), q);
  assert.equal(matchesSearch(record,'джаз'),false);
});
test('categories use explicit tags and actual discount', () => {
  assert.ok(matchesCategory(record,'Цветной винил',[]));
  assert.ok(matchesCategory(record,'Скидки',[]));
  assert.equal(matchesCategory({...record,discountPercent:0,description:'скидка'},'Скидки',[]),false);
});
test('catalog model preserves base price for editing and uses sale price for cart', () => {
  const model = recordModel(record);
  assert.equal(model.originalPrice,1000);
  assert.equal(model.price,850);
});
test('upload sends multipart body without overriding browser boundary', async () => {
  const original = globalThis.fetch;
  const body = new FormData(); body.append('file',new Blob(['image'],{type:'image/png'}),'cover.png');
  globalThis.fetch = async (_,options) => {
    assert.equal(options.body,body);
    assert.equal(options.headers.has('Content-Type'),false);
    return new Response(JSON.stringify({url:'/api/images/example'}));
  };
  try { await api('images',body); } finally { globalThis.fetch = original; }
});
