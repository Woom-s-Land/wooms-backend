package com.ee06.wooms.global.logging;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

@Aspect
@Component
public class ServiceLoggingAspect {

    private static final Logger bizLog = LoggerFactory.getLogger("business.event");

    @Value("${log.sample-rate}")
    private double sampleRate;

    @Value("${log.slow-ms}")
    private long slowMs;

    // 호출 깊이(탑 레벨 판정)
    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);

    @Around("execution(public * com.ee06.wooms.domain..*Service.*(..))")
    public Object aroundService(ProceedingJoinPoint pjp) throws Throwable {
        DEPTH.set(DEPTH.get() + 1);
        long t0 = System.nanoTime();
        Throwable err = null;

        try {
            return pjp.proceed();
        } catch (Throwable e) {
            err = e;            // 주의: 재대입됨 → effectively final 아님
            throw e;            // 반드시 원본 재던지기
        } finally {
            long took = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);

            try {
                boolean topLevel = DEPTH.get() == 1;
                if (topLevel) {
                    boolean isError = (err != null);
                    boolean isSlow  = took >= slowMs;
                    boolean emit    = isError || isSlow || ThreadLocalRandom.current().nextDouble() < sampleRate;

                    if (emit) {
                        String event = deriveEventName(pjp);

                        final String eventF = event;
                        final long tookF = took;
                        final boolean isErrorF = isError;
                        final String errorNameF = isErrorF ? err.getClass().getSimpleName() : null;

                        Runnable writeNow = () -> {
                            if (isErrorF) {
                                bizLog.info("business_event event={} success=false durationMs={} error={}",
                                        eventF, tookF, errorNameF);
                            } else {
                                bizLog.info("business_event event={} success=true durationMs={}",
                                        eventF, tookF);
                            }
                        };

                        if (TransactionSynchronizationManager.isActualTransactionActive()) {
                            // 커밋 후에만 기록 (롤백 시 미기록)
                            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                                @Override
                                public void afterCommit() { writeNow.run(); }
                            });
                        } else {
                            // 트랜잭션이 없으면 즉시 기록
                            writeNow.run();
                        }
                    }
                }
            } finally {
                DEPTH.set(Math.max(DEPTH.get() - 1, 0));
            }
        }
    }

    private static String deriveEventName(ProceedingJoinPoint pjp) {
        MethodSignature sig = (MethodSignature) pjp.getSignature();
        Method m = sig.getMethod();
        String cls = m.getDeclaringClass().getSimpleName().replaceFirst("Service$", "");
        String md  = m.getName();
        return (toKebab(cls) + "." + toKebab(md)).toLowerCase(Locale.ROOT);
    }

    private static String toKebab(String s) {
        return s
                .replaceAll("([a-z0-9])([A-Z])", "$1-$2")
                .replaceAll("([A-Z])([A-Z][a-z])", "$1-$2");
    }
}
