# Faster local builds

QuantaEdge uses Docker Compose for the production-like local stack. BuildKit caches Maven/npm downloads and Docker layers; the local script now keeps those caches enabled by default.

## First start

Run from the repository root in PowerShell:

```powershell
.\scripts\quantaedge-local.ps1 -Action up
```

The first build still needs to download dependencies and compile each service. Subsequent builds should reuse unchanged layers.

## Rebuild just the component you changed

```powershell
# Student/parent web app
.\scripts\quantaedge-local.ps1 -Action rebuild -Service web

# Education admin
.\scripts\quantaedge-local.ps1 -Action rebuild -Service admin

# HRMS / People Operations
.\scripts\quantaedge-local.ps1 -Action rebuild -Service people

# Java API
.\scripts\quantaedge-local.ps1 -Action rebuild -Service api
```

Use `-Action up -Service web` for the same cached, targeted workflow. A targeted build assumes the base stack has already been built once; the selected service is rebuilt while its dependencies use their existing images.

## When a full build is needed

```powershell
# Rebuild every service, keeping the Docker cache
.\scripts\quantaedge-local.ps1 -Action rebuild

# Rebuild every service from scratch (slow; use for cache/build diagnosis)
.\scripts\quantaedge-local.ps1 -Action clean-build
```

`clean-build` deliberately passes Docker's `--no-cache`; do not use it for routine code changes. `reset` is for database/volume resets and deletes local Compose volumes, so it should not be used merely to rebuild code.

## What changed

- Per-service `.dockerignore` files keep host `node_modules`, Next.js `.next`, Maven `target`, environment secrets and local output out of Docker build contexts.
- The Next.js images cache npm downloads and skip npm's network audit during an image build. Maven dependencies use a persistent BuildKit cache mount.
- Routine rebuilds retain Docker layer caching and can target only `web`, `admin`, `people`, or `api`.
- The standalone Android workflow is now a manual fallback. The main CI workflow continues to produce the APK on each main commit, avoiding duplicate automatic Android builds.

These changes reduce repeated work; the first build or a dependency/toolchain change still needs to do the corresponding work.
