package com.papertranslator.settings;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;

/** settings.json next to the task directory, written atomically and readable only by the owner. */
public class SettingsStore {

    private static final Logger log = LoggerFactory.getLogger(SettingsStore.class);
    private final Path file;
    private final ObjectMapper mapper;

    public SettingsStore(Path file, ObjectMapper mapper) {
        this.file = file.toAbsolutePath().normalize();
        this.mapper = mapper;
    }

    public Path file() {
        return file;
    }

    public ObjectNode load() {
        if (!Files.isRegularFile(file)) return mapper.createObjectNode();
        try {
            if (mapper.readTree(file.toFile()) instanceof ObjectNode node) return node;
            throw new IOException("根节点不是 JSON 对象");
        } catch (IOException e) {
            // Keep the unreadable file for the user instead of silently overwriting it.
            Path aside = file.resolveSibling(file.getFileName() + ".invalid-" + System.currentTimeMillis());
            try {
                Files.move(file, aside);
                log.warn("设置文件无法解析，已改名为 {} 并使用 .env / 默认值: {}", aside, e.getMessage());
            } catch (IOException moveError) {
                log.warn("设置文件无法解析且无法改名: {}", file, moveError);
            }
            return mapper.createObjectNode();
        }
    }

    public void save(ObjectNode settings) throws IOException {
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), "settings-", ".json.tmp");
        try {
            restrictToOwner(temporary);
            mapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), settings);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /** 0600 where POSIX permissions exist; on Windows the user-profile ACL already applies. */
    private static void restrictToOwner(Path path) throws IOException {
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        }
    }
}
