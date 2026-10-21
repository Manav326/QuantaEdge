#!/usr/bin/env node
import { readFile } from 'node:fs/promises';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
const root=resolve(dirname(fileURLToPath(import.meta.url)),'..');
const [page,en,hi]=await Promise.all([
 readFile(join(root,'apps/admin/app/source-ingestion/page.tsx'),'utf8'),
 readFile(join(root,'localization/catalogs/english.json'),'utf8').then(JSON.parse),
 readFile(join(root,'localization/catalogs/hinglish.json'),'utf8').then(JSON.parse),
]);
const failures=[];
const ph=s=>[...s.matchAll(/\{([A-Za-z][A-Za-z0-9_]*)\}/g)].map(m=>m[1]).sort().join('|');
const used=new Set([...page.matchAll(/t\(['"](admin\.sourceIngestion\.[A-Za-z0-9.]+)['"]/g)].map(m=>m[1]).filter(k=>!k.endsWith('.')));
if(!page.includes("import { useUiTranslation } from '../i18n/useUiTranslation';"))failures.push('central UI translation hook missing');
for(const k of used){if(typeof en[k]!=='string'||typeof hi[k]!=='string')failures.push('missing catalog key '+k);else if(ph(en[k])!==ph(hi[k]))failures.push('placeholder mismatch '+k);}
for(const k of ['admin.sourceIngestion.status.QUEUED','admin.sourceIngestion.status.DOWNLOADING','admin.sourceIngestion.status.REVIEW','admin.sourceIngestion.status.APPROVED','admin.sourceIngestion.status.REJECTED','admin.sourceIngestion.status.FAILED','admin.sourceIngestion.status.INCOMPLETE','admin.sourceIngestion.status.UNAVAILABLE','admin.sourceIngestion.status.PENDING','admin.sourceIngestion.status.RUNNING','admin.sourceIngestion.status.COMPLETE'])if(typeof en[k]!=='string'||typeof hi[k]!=='string'||ph(en[k])!==ph(hi[k]))failures.push('missing or mismatched status key '+k);
for(const literal of ['Source ingestion & chapter review','Reuse database source records, download official books','Choose a class and subject first.','Add the source title and official HTTPS URL.','Source downloads must use HTTPS.','Reject this entire source ingestion?','Search title, provider, URL or edition…','Register a new subject/source','Check database & start download','No cached books for this class/medium','Unavailable books and missing chapters','No ingestion jobs yet. Start with a source above.','Create chapter PDFs for review','Approve book & approved chapters','Source title, official HTTPS URL, and the printed edition/reprint year or session are required before approval'])if(page.includes(literal))failures.push('embedded UI copy '+literal);
if(/setError\(['"][A-Z]/.test(page)||/setNotice\(['"][A-Z]/.test(page))failures.push('static error/notice sentence remains embedded in event handler');
if(failures.length){console.error('Admin Source Ingestion localization contract failed:\n- '+failures.join('\n- '));process.exitCode=1;}
else console.log('Admin Source Ingestion localization contract passed: '+used.size+' central keys, paired locales, placeholder parity, status labels and embedded-copy guard.');
