#!/usr/bin/env node
import { readFile } from 'node:fs/promises';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
const root=resolve(dirname(fileURLToPath(import.meta.url)),'..');
const [page,en,hi]=await Promise.all([
 readFile(join(root,'apps/admin/app/classes/page.tsx'),'utf8'),
 readFile(join(root,'localization/catalogs/english.json'),'utf8').then(JSON.parse),
 readFile(join(root,'localization/catalogs/hinglish.json'),'utf8').then(JSON.parse),
]);
const failures=[];
const ph=s=>[...s.matchAll(/\{([A-Za-z][A-Za-z0-9_]*)\}/g)].map(m=>m[1]).sort().join('|');
const keys=new Set([...page.matchAll(/(?:t|tRef\.current)\(['"]admin\.classes\.([A-Za-z0-9]+)['"]/g)].map(m=>'admin.classes.'+m[1]));
if(!page.includes("import { useUiTranslation } from '../i18n/useUiTranslation';"))failures.push('central hook missing');
for(const k of keys){if(!en[k]||!hi[k])failures.push('missing key '+k);else if(ph(en[k])!==ph(hi[k]))failures.push('placeholder mismatch '+k);}
for(const literal of ['<h1>Live & recorded classes</h1>','<h2>Schedule a live class</h2>','<h2>Scheduled and completed classes</h2>'])if(page.includes(literal))failures.push('embedded UI text '+literal);
if(failures.length){console.error('Admin Classes localization contract failed:\n- '+failures.join('\n- '));process.exitCode=1;}else console.log('Admin Classes localization contract passed: '+keys.size+' semantic keys, both locales and placeholder parity.');
