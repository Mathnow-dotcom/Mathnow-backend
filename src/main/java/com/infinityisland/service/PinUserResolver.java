package com.infinityisland.service;

import com.infinityisland.dao.user.User;
import org.springframework.stereotype.Service;

@Service
public class PinUserResolver {
    private final CachedUserService cachedUsers;

    public PinUserResolver(CachedUserService cachedUsers) {
        this.cachedUsers = cachedUsers;
    }

    public String ensureUserId(String pin) {
        if (pin == null || pin.isBlank()) return null;

        var byPin = cachedUsers.findByPin(pin);
        if (byPin.isPresent()) return byPin.get().getId();

        var byId = cachedUsers.findById(pin);
        if (byId.isPresent()) {
            var u = byId.get();
            if (u.getPin() == null || u.getPin().isBlank()) {
                u.setPin(pin);
                cachedUsers.save(u);
            }
            return u.getId();
        }

        var u = new User();
        u.setPin(pin);
        u.setDisplayName("PIN " + pin);
        cachedUsers.save(u);
        return u.getId();
    }
}