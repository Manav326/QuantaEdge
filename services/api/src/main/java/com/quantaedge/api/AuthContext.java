package com.quantaedge.api;

public record AuthContext(Long userId, Long studentId, String role, String displayName) {
  public boolean isAuthenticated() { return userId != null || studentId != null; }
  public boolean isAdmin() { return "ADMIN".equals(role); }
  public boolean isParent() { return "PARENT".equals(role); }
  public boolean isStudent() { return studentId != null; }
}
