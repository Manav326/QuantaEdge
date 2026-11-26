#!/usr/bin/env node
/**
 * Guard the Admin shared-workspace boundary against legacy nested layout grids.
 *
 * AdminWorkspaceLayout owns the persistent sidebar and responsive navigation.
 * A child route must not add a second .admin-shell grid when its own sidebar is
 * suppressed by AdminWorkspaceContext; doing so can leave an empty 230px track.
 */
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";

function read(path) {
  return readFileSync(resolve(process.cwd(), path), "utf8");
}

const layout = read("apps/admin/app/components/AdminWorkspaceLayout.tsx");
const sidebar = read("apps/admin/app/components/AdminSidebar.tsx");
const theme = read("apps/admin/app/globals.css");
const notifications = read("apps/admin/app/notifications/AdminNotificationsPageClient.tsx");

assert.match(layout, /<div className="qe-admin-workspace-layout">[\s\S]*?<AdminSidebar[\s\S]*?persistent \/>[\s\S]*?<div className="qe-admin-workspace-page-slot"/,
  "The shared Admin workspace must own the persistent sidebar and route content slot.");
assert.match(sidebar, /if \(insideWorkspace && !persistent\) return null;/,
  "Nested non-persistent AdminSidebar instances must remain suppressed inside the shared workspace.");
assert.match(theme, /\.qe-admin-workspace-page-slot>main\.admin-shell\{display:block;grid-template-columns:minmax\(0,1fr\)/,
  "Legacy page-level main.admin-shell routes must be normalized to a single full-width content column.");
assert.match(theme, /\.qe-admin-workspace-page-slot>main\.admin-shell>\.admin-sidebar\{display:none!important\}/,
  "Legacy page-level sidebars must not duplicate the shared workspace sidebar.");

const institutionBranchStart = notifications.indexOf("if (identityType === 'INSTITUTION')");
const institutionBranchEnd = notifications.indexOf('return <NotificationStudio surface="admin" />', institutionBranchStart);
assert.notEqual(institutionBranchStart, -1, "Institution notification branch must remain present.");
assert.notEqual(institutionBranchEnd, -1, "Admin notification fallback branch must remain present.");
const institutionBranch = notifications.slice(institutionBranchStart, institutionBranchEnd);
assert.doesNotMatch(institutionBranch, /className=["'`]admin-shell["'`]/,
  "Institution notification content must not create a nested admin-shell grid inside the shared workspace.");
assert.match(institutionBranch, /<div>\s*<main className="admin-main">/,
  "Institution notification studio and inbox must render in normal flow within the shared page slot.");

console.log("Admin shared-workspace nested-shell contract passed.");
