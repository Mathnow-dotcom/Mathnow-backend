package com.infinityisland.dao.resource;

public class LoginPinResponse {
    String token;
    AuthUser user;

    public LoginPinResponse(String token, AuthUser user) {
        this.token = token;
        this.user = user;
    }

    public LoginPinResponse() {
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public AuthUser getUser() {
        return user;
    }

    public void setUser(AuthUser user) {
        this.user = user;
    }
}
