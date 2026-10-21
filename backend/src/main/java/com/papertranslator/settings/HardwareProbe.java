package com.papertranslator.settings;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Detects cores and usable memory on Linux, macOS and Windows. */
final class HardwareProbe {

    private static final long UNLIMITED = Long.MAX_VALUE / 2;

    private HardwareProbe() {
    }

    static HardwareInfo detect() {
        int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
        long physical = physicalMemoryBytes();
        long cgroup = cgroupLimitBytes();
        long usable = physical > 0 && cgroup > 0 ? Math.min(physical, cgroup) : Math.max(physical, cgroup);
        if (usable <= 0) {
            return new HardwareInfo(cores, HardwareInfo.FALLBACK.memoryMiB(), false);
        }
        return new HardwareInfo(cores, usable / (1024 * 1024), true);
    }

    private static long physicalMemoryBytes() {
        try {
            if (ManagementFactory.getOperatingSystemMXBean()
                    instanceof com.sun.management.OperatingSystemMXBean os) {
                return os.getTotalMemorySize();
            }
        } catch (Throwable ignored) {
            // Non-HotSpot JVMs may not expose the extended bean.
        }
        return -1;
    }

    /** Smallest memory limit of this process's cgroup (v2 or v1), or -1 when none applies. */
    private static long cgroupLimitBytes() {
        List<Path> candidates = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(Path.of("/proc/self/cgroup"))) {
                String[] parts = line.split(":", 3);
                if (parts.length != 3) continue;
                String relative = parts[2].replaceFirst("^/", "");
                if (parts[1].isEmpty()) {
                    candidates.add(Path.of("/sys/fs/cgroup").resolve(relative).resolve("memory.max"));
                } else if (parts[1].contains("memory")) {
                    candidates.add(Path.of("/sys/fs/cgroup/memory").resolve(relative).resolve("memory.limit_in_bytes"));
                }
            }
        } catch (Exception ignored) {
            return -1;
        }
        candidates.add(Path.of("/sys/fs/cgroup/memory.max"));
        candidates.add(Path.of("/sys/fs/cgroup/memory/memory.limit_in_bytes"));
        long smallest = -1;
        for (Path path : candidates) {
            try {
                if (!Files.isRegularFile(path)) continue;
                String value = Files.readString(path).trim();
                if (value.equals("max")) continue;
                long limit = Long.parseLong(value);
                if (limit <= 0 || limit >= UNLIMITED) continue;
                smallest = smallest < 0 ? limit : Math.min(smallest, limit);
            } catch (Exception ignored) {
                // Unreadable controller files mean "no limit we can use".
            }
        }
        return smallest;
    }
}
