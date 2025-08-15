package com.ee06.wooms.domain.chat;

import com.ee06.wooms.domain.chat.entity.Woom;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class SessionRepository {
    private final ConcurrentMap<String, Woom> sessionCache = new ConcurrentHashMap<>();

    public void put(String key, Woom value) {
        sessionCache.put(key, value);
    }

    public Woom get(String key) { return sessionCache.get(key); }

    public void remove(String key) { this.sessionCache.remove(key); }

    public int size() {
        return this.sessionCache.size();
    }
}
