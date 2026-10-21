#!/usr/bin/env node
import { readFile } from 'node:fs/promises';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
const root=resolve(dirname(fileURLToPath(import.meta.url)),'..');
const [page,en,hi]=await Promise.all([
 readFile(join(root,'apps/admin/app/resources/page.tsx'),'utf8'),
 readFile(join(root,'localization/catalogs/english.json'),'utf8').then(JSON.parse),
 readFile(join(root,'localization/catalogs/hinglish.json'),'utf8').then(JSON.parse),
]);
const failures=[];
const ph=s=>[...s.matchAll(/\{([A-Za-z][A-Za-z0-9_]*)\}/g)].map(m=>m[1]).sort().join('|');
const used=new Set([...page.matchAll(/t\(['"](admin\.resources\.[A-Za-z0-9.]+)['"]/g)].map(m=>m[1]));
if(!page.includes("import { useUiTranslation } from '../i18n/useUiTranslation';"))failures.push('central UI translation hook missing');
for(const k of used){if(typeof en[k]!=='string'||typeof hi[k]!=='string')failures.push('missing catalog key '+k);else if(ph(en[k])!==ph(hi[k]))failures.push('placeholder mismatch '+k);}
const dynamicKeys=['admin.resources.statusPending','admin.resources.statusReviewed','admin.resources.statusNeedsReview','admin.resources.statusApproved','admin.resources.statusFailed','admin.resources.statusRunning','admin.resources.statusPrivate','admin.resources.statusComplete','admin.resources.unknownStatus','admin.resources.mainTextbook','admin.resources.partVolume','admin.resources.supplementary','admin.resources.workbook','admin.resources.teacherGuide','admin.resources.referenceBook','admin.resources.otherResource'];
for(const k of dynamicKeys){if(typeof en[k]!=='string'||typeof hi[k]!=='string')failures.push('missing dynamic-helper key '+k);else if(ph(en[k])!==ph(hi[k]))failures.push('dynamic-helper placeholder mismatch '+k);}
for(const literal of ['The textbook library could not be loaded.','The stored PDF library could not be loaded.','Choose the existing textbook PDF file first.','Give this PDF a recognizable library title.','PDF upload failed.','Select a PDF from the library or upload it first.','Select the chapter that this PDF belongs to.','Enter the title students should see.','Add the source title, official HTTPS URL, and edition/session before attaching this learning resource.','The official source URL must use HTTPS.','Resource attached as a draft. Review the metadata','The resource status could not be changed.','Textbook PDF library','Reuse a stored textbook PDF or add it once','One PDF. Multiple learning placements.','No assignments match these filters','This page could not be rendered. Try again','Choose an existing PDF or upload it once','No classified books yet. Upload a complete PDF above to start.','OCR required before trusting automated inventory','Finish the human review','Manual chapter page ranges','Create private draft resources from these saved ranges','Add a PDF to the library','Attach PDF to a book or chapter','Draft placement prepared for ','Chapter ranges could not be saved.'])if(page.includes(literal))failures.push('embedded UI copy '+literal);
if(/setError\(['"][A-Z]/.test(page)||/setNotice\(['"][A-Z]/.test(page))failures.push('static error/notice sentence remains embedded');
if(failures.length){console.error('Admin Resources localization contract failed:\n- '+failures.join('\n- '));process.exitCode=1;}
else console.log('Admin Resources localization contract passed: '+used.size+' static keys, paired locales, placeholder parity, dynamic label coverage and embedded-copy guard.');
