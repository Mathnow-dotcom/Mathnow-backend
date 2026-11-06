package com.infinityisland.service;

import com.infinityisland.dao.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AuthServiceProfileTest {
    private CachedUserService cachedUsers;
    private UserService userService;
    private DailyService dailyService;
    private AppUsageService appUsageService;
    private AuthService auth;

    @BeforeEach
    void setUp() {
        cachedUsers = mock(CachedUserService.class);
        userService = mock(UserService.class);
        dailyService = mock(DailyService.class);
        appUsageService = mock(AppUsageService.class);
        auth = new AuthService(cachedUsers, userService, dailyService, appUsageService);

        when(cachedUsers.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(appUsageService.start(anyString()))
                .thenReturn(new AppUsageService.Usage("session", "2026-11-05", 0, 0));
    }

    @Test
    void legacyUserBindsSchoolAndGradeOnFirstValidLogin() {
        User legacy = user("user-1", "2468", "Alex");
        when(cachedUsers.findByPin("2468")).thenReturn(Optional.of(legacy));

        AuthService.LoginResult result = auth.loginPin("alex", "2468", "school b", "4");

        assertEquals("School B", legacy.getSchool());
        assertEquals("4", legacy.getGrade());
        assertEquals("School B", result.user().school);
        assertEquals("4", result.user().grade);
        verify(cachedUsers).save(legacy);
    }

    @Test
    void boundUserMustMatchNameSchoolAndGrade() {
        User bound = user("user-2", "1357", "Maya");
        bound.setSchool("School A");
        bound.setGrade("K");
        when(cachedUsers.findByPin("1357")).thenReturn(Optional.of(bound));

        AuthService.ProfileMismatchException schoolError = assertThrows(
                AuthService.ProfileMismatchException.class,
                () -> auth.loginPin("Maya", "1357", "School C", "K")
        );
        assertEquals("INCORRECT_SCHOOL", schoolError.getCode());

        AuthService.ProfileMismatchException gradeError = assertThrows(
                AuthService.ProfileMismatchException.class,
                () -> auth.loginPin("Maya", "1357", "School A", "5")
        );
        assertEquals("INCORRECT_GRADE", gradeError.getCode());

        AuthService.ProfileMismatchException nameError = assertThrows(
                AuthService.ProfileMismatchException.class,
                () -> auth.loginPin("Someone Else", "1357", "School A", "K")
        );
        assertEquals("INCORRECT_NAME", nameError.getCode());
        verify(cachedUsers, never()).save(any(User.class));
    }

    @Test
    void newUserIsCreatedWithCanonicalSchoolAndGrade() {
        when(cachedUsers.findByPin("9999")).thenReturn(Optional.empty());
        User created = user("user-3", "9999", "Sam");
        created.setSchool("School C");
        created.setGrade("12");
        when(userService.createUser("Sam", "9999", "School C", "12")).thenReturn(created);

        AuthService.LoginResult result = auth.loginPin("Sam", "9999", "school c", "12");

        assertEquals("School C", result.user().school);
        assertEquals("12", result.user().grade);
        verify(userService).createUser("Sam", "9999", "School C", "12");
    }

    @Test
    void rejectsUnsupportedSchoolAndGradeBeforeLookingUpPin() {
        assertThrows(IllegalArgumentException.class,
                () -> auth.loginPin("Sam", "9999", "Unknown School", "4"));
        assertThrows(IllegalArgumentException.class,
                () -> auth.loginPin("Sam", "9999", "School A", "13"));
        verifyNoInteractions(cachedUsers);
    }

    private User user(String id, String pin, String name) {
        User user = new User();
        user.setId(id);
        user.setPin(pin);
        user.setName(name);
        user.setDisplayName(name);
        return user;
    }
}
