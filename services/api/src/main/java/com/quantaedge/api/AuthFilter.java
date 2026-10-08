package com.quantaedge.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class AuthFilter extends OncePerRequestFilter {
  private final AuthService auth;
  public AuthFilter(AuthService auth){this.auth=auth;}

  @Override
  protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
      throws ServletException,IOException {
    String token=null;
    if(request.getCookies()!=null){
      for(Cookie cookie:request.getCookies()){
        if(AuthService.COOKIE.equals(cookie.getName())){token=cookie.getValue();break;}
      }
    }
    if(token!=null){
      AuthContext context=auth.current(token);
      if(context!=null) request.setAttribute("authContext",context);
    }
    chain.doFilter(request,response);
  }
}
