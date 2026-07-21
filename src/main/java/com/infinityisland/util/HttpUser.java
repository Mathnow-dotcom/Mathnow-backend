package com.infinityisland.util;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Extracts a user id from common places used by the Node/React client:
 * - request attribute "userId" (if a filter already set it)
 * - headers: x-user-id, x-uid, user-id, userid
 * - cookies: uid, userId
 * - query param: userId
 *
 * If an id is found, it's also written back into the request attribute so
 * downstream code can read req.getAttribute("userId") uniformly.
 */
public final class HttpUser {

    private HttpUser() {}

    public static String resolveUserId(HttpServletRequest req) {
        // 1) request attribute
        Object attr = req.getAttribute("userId");
        if (attr instanceof String s && !s.isBlank()) return s;

        // 2) headers
        String[] headerNames = {"x-user-id", "X-User-Id", "x-uid", "user-id", "userid"};
        for (String h : headerNames) {
            String v = req.getHeader(h);
            if (v != null && !v.isBlank()) {
                req.setAttribute("userId", v);
                return v;
            }
        }

        // 3) cookies
        Cookie[] cookies = req.getCookies();
        if (cookies != null) {
            for (Cookie c : cookies) {
                if ("uid".equalsIgnoreCase(c.getName()) || "userId".equalsIgnoreCase(c.getName())) {
                    String v = c.getValue();
                    if (v != null && !v.isBlank()) {
                        req.setAttribute("userId", v);
                        return v;
                    }
                }
            }
        }

        // 4) query param
        String qp = req.getParameter("userId");
        if (qp != null && !qp.isBlank()) {
            req.setAttribute("userId", qp);
            return qp;
        }

        // nothing found
        return null;
    }
}