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
for(const literal of ["Join window before start","Off — no early-join time gate","15 minutes (default)","Join window must be between 0 and 1440 minutes.","PRIVATE MEETING URL · STAFF ONLY","Choose teacher for this class date…","Loading recordings…","No recorded classes in this view","Recorded class library","Upload an MP4, WebM or QuickTime file up to 500 MB.","What will students learn or prepare for?","Save join window","Choose recording"])if(page.includes(literal))failures.push('embedded UI text '+literal);
if(failures.length){console.error('Admin Classes localization contract failed:\n- '+failures.join('\n- '));process.exitCode=1;}else console.log('Admin Classes localization contract passed: '+keys.size+' semantic keys, both locales and placeholder parity; session and recording literal guards passed.');
