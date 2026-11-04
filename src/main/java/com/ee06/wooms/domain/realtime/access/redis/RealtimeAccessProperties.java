package com.ee06.wooms.domain.realtime.access.redis;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "wooms.realtime.access")
public class RealtimeAccessProperties {
    private boolean enabled = true;
    private String channel = "wooms.realtime.access.v1";
    private int publishQueueCapacity = 256;
}
