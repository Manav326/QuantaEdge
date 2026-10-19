package com.quantaedge.api;

import java.util.UUID;

/** Stable identity for the local-only demo learner; never use as a QA-pack run ID. */
final class LocalPreviewIdentity {
  static final UUID PUBLIC_ID = UUID.fromString("7f4c7e1a-1b21-4db9-a6f6-9d4a1f1e7a31");
  static final String ENVIRONMENT = "LOCAL_PREVIEW";

  private LocalPreviewIdentity() {}
}
