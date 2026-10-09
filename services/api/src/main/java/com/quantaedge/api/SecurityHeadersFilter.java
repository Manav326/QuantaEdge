package com.quantaedge.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class SecurityHeadersFilter extends OncePerRequestFilter {
  private static final Logger LOGGER = LoggerFactory.getLogger(SecurityHeadersFilter.class);

  @Override
  protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
      throws ServletException,IOException {
    String uri=request.getRequestURI();
    boolean trace="PUT".equalsIgnoreCase(request.getMethod())
        && uri.startsWith("/api/v1/admin/lessons/")
        && uri.contains("/blocks/") && uri.endsWith("/asset");
    if(trace) LOGGER.info("HTTP_DIAG security-filter-enter method={} uri={} contentLength={} contentType={} remote={}",
        request.getMethod(),uri,request.getContentLengthLong(),request.getContentType(),request.getRemoteAddr());
    response.setHeader("X-Content-Type-Options","nosniff");
    response.setHeader("X-Frame-Options","DENY");
    response.setHeader("Referrer-Policy","strict-origin-when-cross-origin");
    response.setHeader("Permissions-Policy","camera=(),microphone=(),geolocation=()");
    if(request.isSecure()) {
      response.setHeader("Strict-Transport-Security","max-age=31536000; includeSubDomains");
    }
    if(uri.startsWith("/api/")) {
      response.setHeader("Cache-Control","no-store");
      response.setHeader("Pragma","no-cache");
    }
    try {
      chain.doFilter(request,response);
      if(trace) LOGGER.info("HTTP_DIAG security-filter-return status={} committed={}",response.getStatus(),response.isCommitted());
    } catch(IOException | ServletException | RuntimeException ex) {
      if(trace) LOGGER.error("HTTP_DIAG security-filter-failed status={}",response.getStatus(),ex);
      throw ex;
    } finally {
      if(trace) LOGGER.info("HTTP_DIAG security-filter-exit status={} committed={}",response.getStatus(),response.isCommitted());
    }
  }
}
