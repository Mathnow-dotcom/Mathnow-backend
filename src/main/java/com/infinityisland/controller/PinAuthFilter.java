package com.infinityisland.controller;

import com.infinityisland.dao.user.User;
import com.infinityisland.repositories.UserRepository;
import com.infinityisland.service.GameConfigService;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

import com.infinityisland.util.ErrorResponse;

import java.io.IOException;
import java.util.Optional;
import java.util.Set;

@Provider
@PreMatching
public class PinAuthFilter implements ContainerRequestFilter {

  private static final Logger log = LoggerFactory.getLogger(PinAuthFilter.class);

  @Inject
  private UserRepository userRepository;

  @Inject
  private GameConfigService gameConfigService;

  // Routes that require any authenticated user
  private static final String[] PROTECTED_PREFIXES = new String[]{
          "/api/quiz", "/api/user", "/api/analytics", "/api/config"
  };

  // Routes that require ADMIN access only
  private static final Set<String> ADMIN_ONLY_PREFIXES = Set.of(
          "/config",
          "/admin",
          "/api/config",
          "/api/admin"
  );

  @Override
  public void filter(ContainerRequestContext ctx) throws IOException {
    String path = "/" + ctx.getUriInfo().getPath();
    log.debug("[DEBUG] Filter path: {}", path);
    String pin = ctx.getHeaderString("x-pin");

    // Check if admin-only route
    boolean isAdminRoute = ADMIN_ONLY_PREFIXES.stream().anyMatch(path::startsWith);

    if (isAdminRoute) {
      // Use GameConfigService to validate admin PIN
      if (!gameConfigService.isValidAdminPin(pin)) {
        ctx.abortWith(ErrorResponse.respond(Response.Status.FORBIDDEN,
                "Admin access required"));
        return;
      }
      // Admin authenticated
      ctx.setProperty("isAdmin", true);
      ctx.setProperty("adminPin", pin);
      return;
    }

    // Check if user-protected route
    boolean needsAuth = false;
    for (String p : PROTECTED_PREFIXES) {
      if (path.startsWith(p)) {
        needsAuth = true;
        break;
      }
    }

    if (!needsAuth) {
      return;
    }

    // Require PIN for protected routes
    if (pin == null || pin.isBlank()) {
      ctx.abortWith(ErrorResponse.respond(Response.Status.UNAUTHORIZED, "PIN missing"));
      return;
    }

    Optional<User> user = userRepository.findByPin(pin);
    if (user.isEmpty()) {
      ctx.abortWith(ErrorResponse.respond(Response.Status.UNAUTHORIZED, "Invalid PIN"));
      return;
    }

    ctx.setProperty("userId", user.get().getId());
  }

}