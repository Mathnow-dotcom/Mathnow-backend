package com.infinityisland.controller;

import com.infinityisland.dao.resource.LoginPinRequest;
import com.infinityisland.service.AuthService;
import com.infinityisland.util.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.springframework.stereotype.Component;

@Component
@Path("/auth")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class AuthResource {
  private static final Logger log = LoggerFactory.getLogger(AuthResource.class);
  private final AuthService authService;

  public AuthResource(AuthService authService) {
    this.authService = authService;
  }

  @POST
  @Path("/login-pin")
  public Response loginPin(LoginPinRequest body) {
    try {
      if (body == null || body.getPin() == null || body.getPin().isBlank()) {
        return Response.status(400).entity(ErrorResponse.simple("PIN required")).build();
      }

      if (body.getName() == null || body.getName().isBlank()) {
        return Response.status(400).entity(ErrorResponse.simple("Name required")).build();
      }

      if (body.getSchool() == null || body.getSchool().isBlank()) {
        return Response.status(400).entity(ErrorResponse.simple("School required")).build();
      }

      if (body.getGrade() == null || body.getGrade().isBlank()) {
        return Response.status(400).entity(ErrorResponse.simple("Grade required")).build();
      }

      var result = authService.loginPin(body.getName(), body.getPin(), body.getSchool(), body.getGrade());

      return Response.ok(new AuthDtos.LoginResponse(result.user(), result.token())).build();

    } catch (AuthService.ProfileMismatchException mismatch) {
      return Response.status(401)
              .entity(ErrorResponse.of(mismatch.getMessage(), mismatch.getCode()))
              .build();

    } catch (IllegalArgumentException invalid) {
      return Response.status(400).entity(ErrorResponse.of(invalid.getMessage(), "INVALID_LOGIN")).build();

    } catch (Exception e) {
      log.error("Login error", e);
      return Response.status(500).entity(ErrorResponse.of(e.getMessage())).build();
    }
  }
}
