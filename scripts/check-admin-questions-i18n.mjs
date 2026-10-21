#!/usr/bin/env node
import { readFile } from 'node:fs/promises';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
const root=resolve(dirname(fileURLToPath(import.meta.url)),'..');
const [page,en,hi]=await Promise.all([
 readFile(join(root,'apps/admin/app/questions/page.tsx'),'utf8'),
 readFile(join(root,'localization/catalogs/english.json'),'utf8').then(JSON.parse),
 readFile(join(root,'localization/catalogs/hinglish.json'),'utf8').then(JSON.parse),
]);
const fail=[];
const placeholders=s=>[...s.matchAll(/\{([A-Za-z][A-Za-z0-9_]*)\}/g)].map(m=>m[1]).sort().join('|');
const keys=new Set([...page.matchAll(/t\('admin\.questions\.([A-Za-z0-9]+)'/g)].map(m=>'admin.questions.'+m[1]));
if(!page.includes("import { useUiTranslation } from '../i18n/useUiTranslation';"))fail.push('missing central translation hook');
if(!page.includes("import { AdminWorkspaceContext } from '../components/AdminWorkspaceContext';"))fail.push('missing shared locale context');
for(const k of keys){if(!en[k]||!hi[k])fail.push('missing catalog key '+k);else if(placeholders(en[k])!==placeholders(hi[k]))fail.push('placeholder mismatch '+k);}
for(const literal of ['Question review & history','Question queue could not be loaded.','A rejection reason is required.','No questions match this filter','Question history could not be loaded.','Before / after question snapshot','Search question, topic, chapter or micro-topic']){
 if(page.includes('>'+literal+'<')||page.includes("'"+literal+"'")||page.includes('"'+literal+'"'))fail.push('embedded untranslated literal '+literal);
}
if(fail.length){console.error('Admin Questions localization contract failed:\n- '+fail.join('\n- '));process.exitCode=1;}
else console.log('Admin Questions localization contract passed: '+keys.size+' semantic keys, both locales and matching placeholders.');
