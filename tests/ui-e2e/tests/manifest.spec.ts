import { test, expect } from '@playwright/test';
import { loadUiManifest } from '../support/manifest';

test('checked-out source routes match the QA register on this commit', async () => {
  const manifest = await loadUiManifest();
  expect(manifest.sourceSha).toBeTruthy();
  expect(manifest.summary.registeredWebPages).toBeGreaterThan(0);
  expect(manifest.summary.sourceWebPages).toBeGreaterThan(0);
  expect(manifest.summary.registeredWebPages).toBe(manifest.summary.sourceWebPages);
  expect(manifest.summary.registeredNativeRoutes).toBeGreaterThan(0);
  expect(manifest.summary.scenarios).toBeGreaterThan(0);
  expect(new Set(manifest.routes.map(route => route.app)).size).toBe(3);
  expect(new Set(manifest.routes.map(route => route.file)).size).toBe(manifest.routes.length);
  expect(manifest.screens.every(screen => screen.scenario_ids.trim().length > 0)).toBe(true);
});
