#!/usr/bin/env node
import { readFile } from 'node:fs/promises';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
const root=resolve(dirname(fileURLToPath(import.meta.url)),'..');
const [page,en,hi]=await Promise.all([
 readFile(join(root,'apps/admin/app/assessments/page.tsx'),'utf8'),
 readFile(join(root,'localization/catalogs/english.json'),'utf8').then(JSON.parse),
 readFile(join(root,'localization/catalogs/hinglish.json'),'utf8').then(JSON.parse),
]);
const failures=[];
const ph=s=>[...s.matchAll(/\{([A-Za-z][A-Za-z0-9_]*)\}/g)].map(m=>m[1]).sort().join('|');
const keys=new Set([...page.matchAll(/(?:t|tRef\.current)\('admin\.assessments\.([A-Za-z0-9]+)'/g)].map(m=>'admin.assessments.'+m[1]));
if(!page.includes("import { useUiTranslation } from '../i18n/useUiTranslation';"))failures.push('central hook missing');
for(const k of keys){if(!en[k]||!hi[k])failures.push('missing key '+k);else if(ph(en[k])!==ph(hi[k]))failures.push('placeholder mismatch '+k);}
for(const literal of ['Enter a title for this test.','Question bank could not be loaded.','Build & publish tests','Teacher review queue','All statuses','Assign permitted staff checker…','TEST-LEVEL REVIEW','Certificate for this result','REFERENCE ANSWER / RUBRIC · STAFF ONLY','Who checked this test','Search test, student or chapter…','No assessments in this filter']){
 if(page.includes('>'+literal+'<')||page.includes("'"+literal+"'")||page.includes('"'+literal+'"'))failures.push('embedded literal '+literal);
}
if(failures.length){console.error('Admin Assessments localization contract failed:\\n- '+failures.join('\\n- '));process.exitCode=1;}else console.log('Admin Assessments localization contract passed: '+keys.size+' semantic keys, both locales and matching placeholders.');
