package org.breaze.logging;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class AuditLogger {
    private static final Path LOG_PATH = Paths.get("src/main/data_storage/patiens/server.log");
    private static final ThreadLocal<String> REMOTE = new ThreadLocal<>();

    public static void setRemote(String remote) { REMOTE.set(remote); }
    public static void clearRemote() { REMOTE.remove(); }

    private static void ensureFile() {
        try {
            Files.createDirectories(LOG_PATH.getParent());
            if (Files.notExists(LOG_PATH)) {
                Files.write(LOG_PATH, Collections.singletonList(""), StandardCharsets.UTF_8, StandardOpenOption.CREATE);
            }
        } catch (Exception e) {
            // no lanzar; auditoría no debe tumbar el server
        }
    }

    public static void info(String action, Map<String, String> kv) { write("INFO", action, kv); }
    public static void warn(String action, Map<String, String> kv) { write("WARN", action, kv); }
    public static void error(String action, Map<String, String> kv) { write("ERROR", action, kv); }

    private static synchronized void write(String level, String action, Map<String,String> kv) {
        try {
            ensureFile();
            String ts = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            String remote = Optional.ofNullable(REMOTE.get()).orElse("-");
            StringBuilder sb = new StringBuilder();
            sb.append(ts).append(" ")
                    .append(level).append(" ")
                    .append(action).append(" ")
                    .append("remote=").append(remote);

            if (kv != null) {
                for (Map.Entry<String, String> e : kv.entrySet()) {
                    sb.append(" ").append(e.getKey()).append("=")
                            .append(e.getValue() == null ? "" : sanitize(e.getValue()));
                }
            }
            sb.append(System.lineSeparator());
            Files.write(LOG_PATH, Collections.singletonList(sb.toString()),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {
            // nunca tumbar por logging
        }
    }

    private static String sanitize(String v) {
        // evita saltos de línea en el log
        return v.replace("\r"," ").replace("\n"," ");
    }
}
