package com.papertranslator.settings;

/**
 * @param memoryMiB memory this service may use: a cgroup limit when one is set, else physical memory
 * @param detected  false when probing failed and conservative fallback values are used
 */
public record HardwareInfo(int cores, long memoryMiB, boolean detected) {

    static final HardwareInfo FALLBACK = new HardwareInfo(4, 8192, false);
}
