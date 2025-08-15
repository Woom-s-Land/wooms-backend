package com.ee06.wooms.domain.chat;

import com.ee06.wooms.domain.chat.entity.Channel;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class ChannelRepository {
    private final ConcurrentMap<UUID, Channel> channelCache = new ConcurrentHashMap<>();

    public void put(UUID key, Channel value) {
        channelCache.put(key, value);
    }

    public Channel get(UUID key) {
        return channelCache.computeIfAbsent(key, k -> new Channel());
//        return channelCache.getOrDefault(key, new Channel());
    }
    public void remove(UUID key) { this.channelCache.remove(key); }

    public int size() {
        return this.channelCache.size();
    }
}
