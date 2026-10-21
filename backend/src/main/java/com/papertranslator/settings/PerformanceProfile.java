package com.papertranslator.settings;

/**
 * One complete set of performance values. BabelDOC still runs as a single
 * process; tiers only change its page batch size, model concurrency and the
 * memory guard around it.
 */
public record PerformanceProfile(int qps, int pagesPerChunk, int memoryLimitMiB, int minAvailableMiB,
                                 int maxSwapUsedMiB, int timeoutSeconds, int queueCapacity) {

    public static final String AUTO = "auto";
    public static final String MAX = "max";
    public static final String MANUAL = "manual";

    /** Memory kept free for the operating system and other programs. */
    static final int SYSTEM_RESERVE_MIB = 2048;
    /** Measured BabelDOC working set on long papers; the guard never goes below it on larger hosts. */
    static final int BABELDOC_PEAK_MIB = 2850;

    public int stableQps() {
        return Math.max(1, qps / 2);
    }

    /** Recommended values for {@link #AUTO} or {@link #MAX} on the given hardware. */
    public static PerformanceProfile recommend(String tier, HardwareInfo hardware) {
        boolean max = MAX.equals(tier);
        long memory = hardware.memoryMiB();
        int pages;
        int qps;
        if (memory < 6 * 1024) {
            pages = 1;
            qps = max ? 4 : 2;
        } else if (memory < 12 * 1024) {
            pages = max ? 5 : 3;
            qps = max ? 6 : 4;
        } else if (memory < 24 * 1024) {
            pages = max ? 10 : 5;
            qps = max ? 8 : 6;
        } else {
            pages = max ? 20 : 10;
            qps = max ? 12 : 8;
        }
        qps = Math.max(1, Math.min(qps, hardware.cores() * 2));
        int memoryLimit = memory < 6 * 1024
                ? (int) Math.min(BABELDOC_PEAK_MIB, Math.max(1024, memory * 7 / 10))
                : (int) Math.max(BABELDOC_PEAK_MIB, memory - SYSTEM_RESERVE_MIB);
        return new PerformanceProfile(qps, pages, memoryLimit,
                max ? 256 : 400, max ? 2400 : 1200, 21600, max ? 10 : 5);
    }

    /** Bounds for manual values, shown next to each field. */
    public record Range(int min, int max) {
        public boolean contains(int value) {
            return value >= min && value <= max;
        }
    }

    public static final Range QPS = new Range(1, 32);
    public static final Range PAGES_PER_CHUNK = new Range(1, 50);
    public static final Range TIMEOUT_SECONDS = new Range(300, 86400);
    public static final Range QUEUE_CAPACITY = new Range(1, 50);
    public static final Range PROVIDER_MAX_QPS = new Range(1, 64);
    public static final Range MAX_HISTORY = new Range(1, 500);

    public static Range memoryLimitRange(HardwareInfo hardware) {
        return new Range(1024, (int) Math.max(1024, hardware.memoryMiB()));
    }
}
