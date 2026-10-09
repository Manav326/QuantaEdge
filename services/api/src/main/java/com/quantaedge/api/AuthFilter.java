package com.quantaedge.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class AuthFilter extends OncePerRequestFilter {
  private static final Logger LOGGER = LoggerFactory.getLogger(AuthFilter.class);
  private final AuthService auth;
  public AuthFilter(AuthService auth){this.auth=auth;}
  @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
      throws ServletException,IOException {
    String uri=request.getRequestURI();
    boolean trace="PUT".equalsIgnoreCase(request.getMethod())
        && uri.startsWith("/api/v1/admin/lessons/")
        && uri.contains("/blocks/") && uri.endsWith("/asset");
    if(trace) LOGGER.info("HTTP_DIAG auth-filter-enter uri={} hasCookies={}",uri,request.getCookies()!=null);
    try {
      String token=null;
      if(request.getCookies()!=null) for(Cookie cookie:request.getCookies())
        if(AuthService.COOKIE.equals(cookie.getName())) { token=cookie.getValue(); break; }
      if(trace) LOGGER.info("HTTP_DIAG auth-filter-cookie-found={}",token!=null);
      if(token!=null) {
        AuthContext context=auth.current(token);
        if(trace) LOGGER.info("HTTP_DIAG auth-filter-context-present={}",context!=null);
        if(context!=null) request.setAttribute("authContext",context);
      }
      chain.doFilter(request,response);
      if(trace) LOGGER.info("HTTP_DIAG auth-filter-chain-returned status={} committed={}",response.getStatus(),response.isCommitted());
    } catch(IOException | ServletException | RuntimeException ex) {
      if(trace) LOGGER.error("HTTP_DIAG auth-filter-failed uri={} status={}",uri,response.getStatus(),ex);
      throw ex;
    } finally {
      if(trace) LOGGER.info("HTTP_DIAG auth-filter-exit status={} committed={}",response.getStatus(),response.isCommitted());
    }
  }
}
