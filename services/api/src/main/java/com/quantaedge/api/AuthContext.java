package com.quantaedge.api;

public record AuthContext(
    Long userId,
    Long studentId,
    Long staffId,
    String role,
    String displayName) {

  /** Keeps existing student/parent constructor call sites source-compatible. */
  public AuthContext(Long userId, Long studentId, String role, String displayName) {
    this(userId, studentId, null, role, displayName);
  }

  public boolean isAuthenticated() {
    return userId != null || studentId != null || staffId != null;
  }

  public boolean isEmployee() {
    return staffId != null;
  }

  public boolean isAdmin() {
    return staffId != null && "ADMIN".equals(role);
  }

  public boolean isParent() {
    return userId != null && "PARENT".equals(role);
  }

  public boolean isStudent() {
    return studentId != null;
  }
}
