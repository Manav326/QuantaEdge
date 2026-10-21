package com.quantaedge.api;

public record AuthContext(
    Long userId,
    Long studentId,
    Long staffId,
    String role,
    String displayName,
    String workspace) {

  /** Preserve existing employee constructor call sites while keeping their scope explicit at login. */
  public AuthContext(Long userId, Long studentId, Long staffId, String role, String displayName) {
    this(userId, studentId, staffId, role, displayName, null);
  }

  /** Keeps existing parent/student constructor call sites source-compatible. */
  public AuthContext(Long userId, Long studentId, String role, String displayName) {
    this(userId, studentId, null, role, displayName, null);
  }

  public boolean isAuthenticated() {
    return userId != null || studentId != null || staffId != null;
  }

  public boolean isEmployee() {
    return staffId != null;
  }

  /** ADMIN is an education-workspace administrator, never an HRMS-wide privilege shortcut. */
  public boolean isAdmin() {
    return staffId != null && "ADMIN".equals(role) && !"HRMS".equals(workspace);
  }

  public boolean isParent() {
    return userId != null && "PARENT".equals(role);
  }

  public boolean isStudent() {
    return studentId != null;
  }
}
