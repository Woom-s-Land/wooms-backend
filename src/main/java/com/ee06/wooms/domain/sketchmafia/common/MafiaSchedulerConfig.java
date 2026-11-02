package com.ee06.wooms.domain.sketchmafia.common;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Dedicated scheduler for mafia phase timers, kept separate from the STOMP
 * heartbeat scheduler (wsTaskScheduler) so game timers can never starve
 * heartbeat delivery (or vice versa).
 */
@Configuration
public class MafiaSchedulerConfig {

    @Bean
    public ThreadPoolTaskScheduler mafiaTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("mafia-timer-");
        scheduler.initialize();
        return scheduler;
    }
}
