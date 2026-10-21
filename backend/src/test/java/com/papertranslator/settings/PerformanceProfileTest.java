package com.papertranslator.settings;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PerformanceProfileTest {

    private static PerformanceProfile auto(int cores, long memoryGiB) {
        return PerformanceProfile.recommend(PerformanceProfile.AUTO, new HardwareInfo(cores, memoryGiB * 1024, true));
    }

    private static PerformanceProfile max(int cores, long memoryGiB) {
        return PerformanceProfile.recommend(PerformanceProfile.MAX, new HardwareInfo(cores, memoryGiB * 1024, true));
    }

    @Test
    void followsTheTierTableForEachMemoryBand() {
        assertEquals(1, auto(16, 4).pagesPerChunk());
        assertEquals(2, auto(16, 4).qps());
        assertEquals(4, max(16, 4).qps());
        assertEquals(3, auto(16, 8).pagesPerChunk());
        assertEquals(4, auto(16, 8).qps());
        assertEquals(5, max(16, 8).pagesPerChunk());
        assertEquals(6, max(16, 8).qps());
        assertEquals(5, auto(16, 16).pagesPerChunk());
        assertEquals(10, max(16, 16).pagesPerChunk());
        assertEquals(8, max(16, 16).qps());
        assertEquals(10, auto(16, 32).pagesPerChunk());
        assertEquals(8, auto(16, 32).qps());
        assertEquals(20, max(16, 32).pagesPerChunk());
        assertEquals(12, max(16, 32).qps());
    }

    @Test
    void capsConcurrencyAtTwiceTheCoreCount() {
        assertEquals(4, max(2, 32).qps());
        assertEquals(2, auto(1, 32).qps());
    }

    @Test
    void keepsTheProductionGuardOnSmallHostsAndReservesTwoGigabytesOnLargeOnes() {
        assertEquals(2850, auto(2, 4).memoryLimitMiB());
        assertEquals(16 * 1024 - 2048, auto(8, 16).memoryLimitMiB());
        assertTrue(max(8, 16).maxSwapUsedMiB() > auto(8, 16).maxSwapUsedMiB());
        assertEquals(1, auto(8, 4).stableQps());
        assertEquals(3, auto(8, 12).stableQps());
    }
}
