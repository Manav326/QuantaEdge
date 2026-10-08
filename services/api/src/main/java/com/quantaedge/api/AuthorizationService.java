package com.quantaedge.api;

import org.springframework.stereotype.Component;

@Component
public class AuthorizationService {
  public AuthContext requireAuth(AuthContext context) {
    if(context==null || !context.isAuthenticated()) throw new SecurityException("Authentication required");
    return context;
  }
  public AuthContext requireAdmin(AuthContext context) {
    context=requireAuth(context);
    if(!context.isAdmin()) throw new SecurityException("Admin access required");
    return context;
  }
  public AuthContext requireParent(AuthContext context) {
    context=requireAuth(context);
    if(!context.isParent()) throw new SecurityException("Parent access required");
    return context;
  }
}
