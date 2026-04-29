package dev.codex.k8slens.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnProperty(name = "kubernetes.desktop.access-token")
public final class DesktopAccessFilter extends OncePerRequestFilter {

    private static final String TOKEN_PARAMETER = "desktopAccessToken";
    private static final String TOKEN_COOKIE = "K8S_LENS_DESKTOP_TOKEN";

    private final String accessToken;
    private final byte[] accessTokenBytes;

    public DesktopAccessFilter(@Value("${kubernetes.desktop.access-token}") String accessToken) {
        this.accessToken = accessToken;
        this.accessTokenBytes = accessToken.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        if (accessToken.isBlank()) {
            filterChain.doFilter(request, response);
            return;
        }

        String tokenParameter = request.getParameter(TOKEN_PARAMETER);
        if (matchesToken(tokenParameter)) {
            setDesktopCookie(response);
            filterChain.doFilter(request, response);
            return;
        }

        if (matchesToken(cookieToken(request))) {
            filterChain.doFilter(request, response);
            return;
        }

        response.sendError(HttpServletResponse.SC_NOT_FOUND);
    }

    private boolean matchesToken(String candidate) {
        if (candidate == null) {
            return false;
        }

        return MessageDigest.isEqual(candidate.getBytes(StandardCharsets.UTF_8), accessTokenBytes);
    }

    private String cookieToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }

        for (Cookie cookie : cookies) {
            if (TOKEN_COOKIE.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }

        return null;
    }

    private void setDesktopCookie(HttpServletResponse response) {
        response.addHeader(
                "Set-Cookie",
                TOKEN_COOKIE + "=" + accessToken + "; Path=/; HttpOnly; SameSite=Strict"
        );
    }
}
