package com.infinityisland.controller;

import com.infinityisland.dao.user.User;
import com.infinityisland.repositories.UserRepository;
import com.infinityisland.service.AssessmentService;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
@Path("/assessments")
@Produces(MediaType.APPLICATION_JSON)
public class AssessmentResource {
    private final AssessmentService tests;
    private final UserRepository users;
    public AssessmentResource(AssessmentService tests, UserRepository users) {
        this.tests = tests; this.users = users;
    }
    private User user(String pin) {
        if (pin == null || pin.isBlank()) throw new NotAuthorizedException("PIN required");
        return users.findByPin(pin).orElseThrow(() -> new NotAuthorizedException("Invalid PIN"));
    }
    public record Start(String beginKey, String session) {}
    public record Update(String session, Integer position, String action, String answer) {}

    @GET @Path("current")
    public Map<String, Object> current(@HeaderParam("x-pin") String pin) {
        var state = AssessmentService.student(tests.current(user(pin).getId()));
        return Collections.singletonMap("attempt", state);
    }
    @POST @Path("start") @Consumes(MediaType.APPLICATION_JSON)
    public AssessmentService.StudentState start(@HeaderParam("x-pin") String pin, Start body) {
        if (body == null) throw new BadRequestException("Request required");
        return AssessmentService.student(tests.start(user(pin), body.beginKey(), body.session()));
    }
    @POST @Path("{id}") @Consumes(MediaType.APPLICATION_JSON)
    public AssessmentService.StudentState update(@HeaderParam("x-pin") String pin, @PathParam("id") String id, Update body) {
        if (body == null || body.position() == null) throw new BadRequestException("Question required");
        return AssessmentService.student(tests.update(user(pin).getId(), id, body.session(), body.position(), body.action(), body.answer()));
    }
}
