package com.instagram.common.session;

/** Stand-in for Instagram's session class, which UnsentIdentity resolves by this name. */
public class UserSession {
    private final String userId;

    public UserSession(String userId) { this.userId = userId; }

    public String getUserId() { return userId; }
}
