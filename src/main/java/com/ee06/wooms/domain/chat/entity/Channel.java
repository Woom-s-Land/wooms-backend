package com.ee06.wooms.domain.chat.entity;

import com.ee06.wooms.domain.chat.dto.MoveMessage;

import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class Channel {

    private final ConcurrentMap<String, Woom> wooms = new ConcurrentHashMap<>();
    private static String key(String nickname, UUID woomsId) {
        return nickname + "|" + woomsId;
    }

    public void addWoom(Woom woom) {
        if (woom == null || woom.getNickname() == null || woom.getWoomsId() == null) return;
        wooms.put(key(woom.getNickname(), woom.getWoomsId()), woom);
    }

    public void removeWoom(Woom woom) {
        if (woom == null || woom.getNickname() == null || woom.getWoomsId() == null) return;
        wooms.remove(key(woom.getNickname(), woom.getWoomsId()));
    }

    public void moveWoom(UUID woomsId, MoveMessage moveMessage) {
        if (moveMessage == null || moveMessage.getNickname() == null || woomsId == null) return;
        String k = key(moveMessage.getNickname(), woomsId);
        wooms.compute(k, (ignored, existing) -> {
            if (existing == null) {
                Woom nw = new Woom(moveMessage.getNickname(), moveMessage.getCostume(), woomsId);
                nw.move(moveMessage);
                return nw;
            } else {
                existing.move(moveMessage);
                return existing;
            }
        });
    }

    public Collection<Woom> getWooms() {
        return wooms.values();
    }
}
