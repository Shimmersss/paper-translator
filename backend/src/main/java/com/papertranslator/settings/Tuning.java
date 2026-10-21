package com.papertranslator.settings;

import com.papertranslator.config.BabelDocConfig;
import com.papertranslator.config.TranslationConfig;

/**
 * Performance knobs read at the moment they are used, so a change made on the
 * settings page applies to the next task without a restart.
 */
public interface Tuning {

    /** Concurrent model requests for a new task. */
    int qps();

    /** Concurrency after a memory-pressure downgrade. */
    int stableQps();

    /** Highest concurrency a caller may request explicitly. */
    int maxQps();

    int pagesPerChunk();

    int memoryLimitMiB();

    int minAvailableMiB();

    int maxSwapUsedMiB();

    int timeoutSeconds();

    int queueCapacity();

    int maxHistory();

    /** The values from application.yml / environment only, as before the settings page existed. */
    static Tuning fromConfig(BabelDocConfig babel, TranslationConfig translation) {
        return new Tuning() {
            @Override public int qps() { return Math.max(1, translation.getMaxQps()); }
            @Override public int stableQps() { return Math.max(1, Math.min(translation.getStableQps(), qps())); }
            @Override public int maxQps() { return qps(); }
            @Override public int pagesPerChunk() { return Math.max(1, babel.getMaxPagesPerChunk()); }
            @Override public int memoryLimitMiB() { return babel.getResourceCgroupLimitMiB(); }
            @Override public int minAvailableMiB() { return babel.getResourceMinAvailableMiB(); }
            @Override public int maxSwapUsedMiB() { return babel.getResourceMaxSwapUsedMiB(); }
            @Override public int timeoutSeconds() { return babel.getTimeoutSeconds(); }
            @Override public int queueCapacity() { return Math.max(1, translation.getQueueCapacity()); }
            @Override public int maxHistory() { return Math.max(1, translation.getMaxHistory()); }
        };
    }
}
