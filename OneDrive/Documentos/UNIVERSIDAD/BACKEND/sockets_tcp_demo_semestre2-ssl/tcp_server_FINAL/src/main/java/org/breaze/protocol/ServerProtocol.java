package org.breaze.protocol;

import org.breaze.logging.AuditLogger;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Mantiene la misma API y respuestas que el original, pero con utilidades separadas.
 */
public class ServerProtocol implements Protocol {

    // ======= Rutas y headers =======
    private static final Path CSV_PATH         = Paths.get("src/main/data_storage/patiens/patiens.csv");
    private static final Path DETECTIONS_CSV   = Paths.get("src/main/data_storage/patiens/detections.csv");
    private static final Path PATIENT_FASTA_DIR= Paths.get("src/main/disease_db/FASTAS");
    private static final Path CATALOG_CSV      = Paths.get("src/main/disease_db/catalog.csv");
    private static final Path SIGNATURES_CSV   = Paths.get("src/main/disease_db/signatures.csv");

    private static final String CSV_HEADER = String.join(",",
            "patient_id","full_name","document_id","F","contact_email","registration_date",
            "age","sex","clinical_notes","checksum_fasta","file_size_bytes","fasta_path","active"
    );
    private static final String DETECTIONS_HEADER = String.join(",",
            "detection_id","patient_id","disease_id","disease_name","pattern","created_at"
    );
    private static final String[] HEADERS = CSV_HEADER.split(",");

    // ======= Estado (cargado una vez) =======
    private final Map<String, String> catalog;          // diseaseId -> ref sequence (opcional)
    private final Map<String, String> diseaseNames;     // diseaseId -> name
    private final Map<String, Integer> diseaseSeverity; // diseaseId -> severity
    private final LinkedHashMap<String, String> signatures; // pattern -> diseaseId

    private final Object csvLock = new Object();

    public ServerProtocol() {
        DiseaseDB db = new DiseaseDB(CATALOG_CSV, SIGNATURES_CSV);
        this.catalog         = db.getCatalog();
        this.diseaseNames    = db.getDiseaseNames();
        this.diseaseSeverity = db.getDiseaseSeverity();
        this.signatures      = db.getSignatures();

        CsvIO.ensureFileWithHeader(CSV_PATH, CSV_HEADER);
        CsvIO.ensureFileWithHeader(DETECTIONS_CSV, DETECTIONS_HEADER);
        FastaIO.ensureDir(PATIENT_FASTA_DIR);
    }

    // ======= Manejo de comandos =======
    @Override
    public String processMessage(String request) {
        System.out.println("Recibido del cliente: " + request);
        AuditLogger.info("PROCESS_REQUEST", Map.of("msg", request));
        if (request == null || request.trim().isEmpty()) {
            AuditLogger.warn("EMPTY_REQUEST", Map.of());
            return "ERROR;empty_request";
        }

        try {
            String[] parts = request.split("\\|");
            String command = parts[0].trim().toUpperCase(Locale.ROOT);

            switch (command) {
                case "CREATE_PATIENT":
                    return handleCreatePatient(Arrays.copyOfRange(parts, 1, parts.length));
                case "GET_PATIENT":
                    return handleGetPatient(Arrays.copyOfRange(parts, 1, parts.length));
                case "UPDATE_PATIENT":
                    return handleUpdatePatient(Arrays.copyOfRange(parts, 1, parts.length));
                case "DEACTIVATE_PATIENT":
                    return handleDeactivatePatient(Arrays.copyOfRange(parts, 1, parts.length));
                default:
                    AuditLogger.info("UNKNOWN_COMMAND", Map.of("cmd", command));
                    return " Recibido: " + request + " | Enfermedades cargadas: " + catalog.keySet();
            }

        } catch (Exception e) {
            e.printStackTrace();
            AuditLogger.error("SERVER_EXCEPTION", new HashMap<String,String>() {{
                put("type", e.getClass().getSimpleName());
                put("msg", String.valueOf(e.getMessage()));
            }});
            return "ERROR;exception;" + e.getClass().getSimpleName() + ";" + e.getMessage();
        }
    }

    // ======= CREATE_PATIENT =======
    private String handleCreatePatient(String[] argParts) throws Exception {
        Map<String, String> kv = parseKeyValues(argParts);

        String patientId     = kv.getOrDefault("patient_id", genPatientId());
        String fullName      = kv.getOrDefault("full_name", "");
        String documentId    = kv.getOrDefault("document_id", "");
        String diseaseId     = kv.getOrDefault("disease_id", "");
        String contactEmail  = kv.getOrDefault("contact_email", "");
        String registration  = nowIso();
        String age           = kv.getOrDefault("age", "");
        String sex           = kv.getOrDefault("sex", "");
        String clinicalNotes = kv.getOrDefault("clinical_notes", "");
        String fastaContent  = kv.getOrDefault("fasta_content", "");

        if (fullName.isEmpty() || documentId.isEmpty()) {
            AuditLogger.warn("CREATE_PATIENT_BAD_INPUT", Map.of("reason","missing_fullname_or_document"));
            return "ERROR;missing_required_fields;need full_name and document_id";
        }
        if (!sex.isEmpty() && !sex.matches("(?i)M|F")) {
            AuditLogger.warn("CREATE_PATIENT_BAD_INPUT", Map.of("reason","invalid_sex", "sex", sex));
            return "ERROR;invalid_sex;expected M or F";
        }
        if (!age.isEmpty() && !age.matches("\\d+")) {
            AuditLogger.warn("CREATE_PATIENT_BAD_INPUT", Map.of("reason","invalid_age", "age", age));
            return "ERROR;invalid_age;expected integer";
        }

        // FASTA
        String checksumFasta = "";
        String fileSizeBytes = "";
        String fastaPath     = "";
        String cleaned       = FastaIO.cleanSequence(fastaContent);

        if (!cleaned.isEmpty()) {
            Path fastaFile = PATIENT_FASTA_DIR.resolve("patient_" + patientId + ".fasta");
            FastaIO.writePatientFasta(fastaFile, patientId, cleaned);

            byte[] data = Files.readAllBytes(fastaFile);
            checksumFasta = FastaIO.sha256Hex(data);
            fileSizeBytes = String.valueOf(data.length);
            fastaPath     = fastaFile.toString();

            System.out.println("🧬 FASTA guardado en: " + fastaFile.toAbsolutePath());
            System.out.println("   bytes=" + fileSizeBytes + " checksum=" + checksumFasta);
        } else if (!fastaContent.isBlank()) {
            System.out.println(" FASTA recibido pero quedó vacío tras limpieza. No se guardará archivo.");
        } else {
            System.out.println("No se envió fasta_content. Se omite archivo FASTA.");
        }

        // Detección por firmas (todas las coincidencias, ordenadas por severidad)
        List<String[]> hits = detectAllSignatures(cleaned);
        if (!hits.isEmpty()) {
            if (diseaseId.isBlank()) {
                diseaseId = hits.get(0)[0]; // más severa
            }
            for (String[] hit : hits) {
                String dId   = hit[0];
                String pat   = hit[1];
                String dName = diseaseNames.getOrDefault(dId, dId);
                DetectionStore.append(DETECTIONS_CSV, genDetectionId(), patientId, dId, dName, pat);
            }
            AuditLogger.info("CREATE_DIAG_DETECTIONS", new HashMap<String,String>() {{
                put("patient_id", patientId);
                put("count", String.valueOf(hits.size()));
                put("top_disease", hits.get(0)[0]);
            }});
        }

        // Escribir fila del paciente
        List<String> row = Arrays.asList(
                CsvIO.csv(patientId), CsvIO.csv(fullName), CsvIO.csv(documentId), CsvIO.csv(diseaseId),
                CsvIO.csv(contactEmail), CsvIO.csv(registration), CsvIO.csv(age), CsvIO.csv(sex),
                CsvIO.csv(clinicalNotes), CsvIO.csv(checksumFasta), CsvIO.csv(fileSizeBytes),
                CsvIO.csv(fastaPath), CsvIO.csv("true")
        );
        synchronized (csvLock) {
            CsvIO.appendLine(CSV_PATH, String.join(",", row));
        }

        Map<String, String> meta = new HashMap<>();
        meta.put("patient_id", patientId);
        meta.put("document_id", documentId);
        meta.put("disease_id", diseaseId);
        meta.put("has_fasta", String.valueOf(!cleaned.isEmpty()));
        AuditLogger.info("CREATE_PATIENT_OK", meta);

        // Respuesta con diagnósticos (si hubo)
        String extra = buildDiagnosisPayload(hits);
        return "OK;patient_created;" + patientId + extra;
    }

    // ======= GET_PATIENT =======
    private String handleGetPatient(String[] argParts) {
        Map<String, String> kv = parseKeyValues(argParts);
        String rawPid = kv.getOrDefault("patient_id", "").trim();
        if (rawPid.isEmpty()) {
            AuditLogger.warn("GET_PATIENT_BAD_INPUT", Map.of("reason","missing_patient_id"));
            return "ERROR;missing_patient_id";
        }

        String patientId = normalizePatientId(rawPid);
        Map<String, String> row = findPatientRowById(patientId);
        if (row == null) {
            AuditLogger.warn("GET_PATIENT_NOT_FOUND", Map.of("patient_id", patientId));
            return "ERROR;not_found;" + patientId;
        }

        String diseaseId   = row.getOrDefault("F", "");
        String diseaseName = Optional.ofNullable(diseaseNames.get(diseaseId)).filter(s -> !s.isBlank()).orElse(diseaseId);

        StringBuilder payload = new StringBuilder();
        payload.append("patient_id=").append(row.getOrDefault("patient_id",""))
                .append("|full_name=").append(row.getOrDefault("full_name",""))
                .append("|document_id=").append(row.getOrDefault("document_id",""))
                .append("|disease_id=").append(diseaseId)
                .append("|disease_name=").append(diseaseName)
                .append("|contact_email=").append(row.getOrDefault("contact_email",""))
                .append("|registration_date=").append(row.getOrDefault("registration_date",""))
                .append("|age=").append(row.getOrDefault("age",""))
                .append("|sex=").append(row.getOrDefault("sex",""))
                .append("|clinical_notes=").append(row.getOrDefault("clinical_notes",""))
                .append("|checksum_fasta=").append(row.getOrDefault("checksum_fasta",""))
                .append("|file_size_bytes=").append(row.getOrDefault("file_size_bytes",""))
                .append("|fasta_path=").append(row.getOrDefault("fasta_path",""))
                .append("|active=").append(row.getOrDefault("active",""));

        List<String[]> dets = DetectionStore.readByPatient(DETECTIONS_CSV, patientId, diseaseNames);
        if (!dets.isEmpty()) {
            payload.append(";diagnosis_count=").append(dets.size());
            int idx = 1;
            for (String[] d : dets) {
                payload.append(";diagnosis_").append(idx).append("_id=").append(d[0])
                        .append("|diagnosis_").append(idx).append("_name=").append(d[1])
                        .append("|diagnosis_").append(idx).append("_pattern=").append(d[2]);
                idx++;
            }
        }

        int diagCount = dets.size();
        AuditLogger.info("GET_PATIENT_OK", new HashMap<String,String>() {{
            put("patient_id", patientId);
            put("diagnosis_count", String.valueOf(diagCount));
        }});
        return "OK;patient;" + payload;
    }

    // ======= UPDATE_PATIENT =======
    private String handleUpdatePatient(String[] argParts) throws Exception {
        Map<String, String> kv = parseKeyValues(argParts);

        String rawPid = kv.getOrDefault("patient_id", "").trim();
        if (rawPid.isEmpty()) {
            AuditLogger.warn("UPDATE_PATIENT_BAD_INPUT", Map.of("reason","missing_patient_id"));
            return "ERROR;missing_patient_id";
        }
        String patientId = normalizePatientId(rawPid);
        kv.remove("patient_id");

        Map<String, String> current = findPatientRowById(patientId);
        if (current == null) {
            AuditLogger.warn("UPDATE_PATIENT_NOT_FOUND", Map.of("patient_id", patientId));
            return "ERROR;not_found;" + patientId;
        }
        if ("false".equalsIgnoreCase(current.getOrDefault("active", "true"))) {
            AuditLogger.warn("UPDATE_BLOCKED_INACTIVE", Map.of("patient_id", patientId));
            return "ERROR;inactive_patient;" + patientId;
        }

        String newSex = kv.get("sex");
        if (newSex != null && !newSex.isBlank() && !newSex.matches("(?i)M|F")) {
            AuditLogger.warn("UPDATE_PATIENT_BAD_INPUT", Map.of("reason","invalid_sex","sex", newSex));
            return "ERROR;invalid_sex;expected M or F";
        }
        String newAge = kv.get("age");
        if (newAge != null && !newAge.isBlank() && !newAge.matches("\\d+")) {
            AuditLogger.warn("UPDATE_PATIENT_BAD_INPUT", Map.of("reason","invalid_age","age", newAge));
            return "ERROR;invalid_age;expected integer";
        }

        String newFastaContent = kv.remove("fasta_content");
        String checksumFasta = null, fileSizeBytes = null, fastaPath = null;

        if (newFastaContent != null && !newFastaContent.isBlank()) {
            String cleaned = FastaIO.cleanSequence(newFastaContent);
            if (!cleaned.isEmpty()) {
                Path fastaFile = PATIENT_FASTA_DIR.resolve("patient_" + patientId + ".fasta");
                FastaIO.writePatientFasta(fastaFile, patientId, cleaned);
                byte[] data = Files.readAllBytes(fastaFile);
                checksumFasta = FastaIO.sha256Hex(data);
                fileSizeBytes = String.valueOf(data.length);
                fastaPath     = fastaFile.toString();
                System.out.println(" FASTA actualizado en: " + fastaFile.toAbsolutePath());
            } else {
                System.out.println(" FASTA en UPDATE quedó vacío tras limpieza. No se actualizará archivo.");
            }
        }

        Set<String> updatable = Set.of("full_name","document_id","F","contact_email","age","sex","clinical_notes","active");
        final String finalChecksum = checksumFasta;
        final String finalFileSize = fileSizeBytes;
        final String finalFastaPath = fastaPath;

        boolean ok = updateRow(patientId, row -> {
            for (Map.Entry<String, String> e : kv.entrySet()) {
                String k = e.getKey();
                String v = e.getValue();
                if (v == null || v.isBlank()) continue;
                if (k.equalsIgnoreCase("disease_id")) k = "F";
                if (updatable.contains(k)) row.put(k, v.trim());
            }
            if (finalChecksum != null) row.put("checksum_fasta", finalChecksum);
            if (finalFileSize != null) row.put("file_size_bytes", finalFileSize);
            if (finalFastaPath != null) row.put("fasta_path", finalFastaPath);
        });

        if (!ok) {
            AuditLogger.warn("UPDATE_PATIENT_NOT_FOUND", Map.of("patient_id", patientId));
            return "ERROR;not_found;" + patientId;
        }

        AuditLogger.info("UPDATE_PATIENT_OK", new HashMap<String,String>() {{
            put("patient_id", patientId);
            put("fields", kv.keySet().toString());
            put("fasta_updated", String.valueOf(finalFastaPath != null));
        }});
        return "OK;patient_updated;" + patientId;
    }

    // ======= DEACTIVATE_PATIENT =======
    private String handleDeactivatePatient(String[] argParts) {
        Map<String, String> kv = parseKeyValues(argParts);
        String rawPid = kv.getOrDefault("patient_id", "").trim();
        if (rawPid.isEmpty()) {
            AuditLogger.warn("DEACTIVATE_BAD_INPUT", Map.of("reason","missing_patient_id"));
            return "ERROR;missing_patient_id";
        }
        String patientId = normalizePatientId(rawPid);

        Map<String, String> row = findPatientRowById(patientId);
        if (row == null) {
            AuditLogger.warn("DEACTIVATE_NOT_FOUND", Map.of("patient_id", patientId));
            return "ERROR;not_found;" + patientId;
        }
        if ("false".equalsIgnoreCase(row.getOrDefault("active", "true"))) {
            AuditLogger.warn("DEACTIVATE_ALREADY_INACTIVE", Map.of("patient_id", patientId));
            return "ERROR;already_inactive;" + patientId;
        }

        boolean ok = updateRow(patientId, r -> r.put("active", "false"));
        if (!ok) {
            AuditLogger.warn("DEACTIVATE_NOT_FOUND", Map.of("patient_id", patientId));
            return "ERROR;not_found;" + patientId;
        }

        AuditLogger.info("DEACTIVATE_PATIENT_OK", Map.of("patient_id", patientId));
        return "OK;patient_deactivated;" + patientId;
    }

    // ======= Auxiliares de diagnóstico =======
    private List<String[]> detectAllSignatures(String cleaned) {
        List<String[]> hits = new ArrayList<>();
        if (!cleaned.isEmpty() && !signatures.isEmpty()) {
            for (Map.Entry<String, String> e : signatures.entrySet()) {
                String pattern = e.getKey();
                String dId = e.getValue().toUpperCase(Locale.ROOT);
                if (cleaned.contains(pattern)) hits.add(new String[]{ dId, pattern });
            }
            hits.sort((a, b) -> Integer.compare(
                    diseaseSeverity.getOrDefault(b[0], 0),
                    diseaseSeverity.getOrDefault(a[0], 0)
            ));
        }
        return hits;
    }

    private String buildDiagnosisPayload(List<String[]> hits) {
        if (hits.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append(";diagnosis_count=").append(hits.size());
        int idx = 1;
        for (String[] hit : hits) {
            String dId   = hit[0];
            String pat   = hit[1];
            String dName = Optional.ofNullable(diseaseNames.get(dId)).filter(s -> !s.isBlank()).orElse(dId);
            sb.append(";diagnosis_").append(idx).append("_id=").append(dId)
                    .append("|diagnosis_").append(idx).append("_name=").append(dName)
                    .append("|diagnosis_").append(idx).append("_pattern=").append(pat);
            idx++;
        }
        return sb.toString();
    }

    // ======= CSV read/write helpers (igual lógica que el original, centralizada) =======
    private Map<String, String> findPatientRowById(String patientId) {
        try (BufferedReader br = Files.newBufferedReader(CSV_PATH, StandardCharsets.UTF_8)) {
            String header = br.readLine();
            if (header == null) return null;

            String line;
            while ((line = br.readLine()) != null) {
                String[] vals = CsvIO.splitCsvSimple(line, HEADERS.length);
                if (vals.length != HEADERS.length) continue;

                Map<String,String> row = new HashMap<>();
                for (int i = 0; i < HEADERS.length; i++) {
                    row.put(HEADERS[i], CsvIO.unquote(vals[i]));
                }
                if (patientId.equals(row.get("patient_id"))) return row;
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    private boolean updateRow(String patientId, java.util.function.Consumer<Map<String,String>> updater) {
        List<Map<String,String>> all = readAllRows();
        boolean found = false;
        for (Map<String,String> row : all) {
            if (patientId.equals(row.get("patient_id"))) {
                updater.accept(row);
                found = true;
                break;
            }
        }
        if (!found) return false;
        writeAllRows(all);
        return true;
    }

    private List<Map<String,String>> readAllRows() {
        List<Map<String,String>> out = new ArrayList<>();
        try (BufferedReader br = Files.newBufferedReader(CSV_PATH, StandardCharsets.UTF_8)) {
            String header = br.readLine();
            if (header == null) return out;
            String line;
            while ((line = br.readLine()) != null) {
                String[] vals = CsvIO.splitCsvSimple(line, HEADERS.length);
                if (vals.length != HEADERS.length) continue;
                Map<String,String> row = new HashMap<>();
                for (int i = 0; i < HEADERS.length; i++) {
                    row.put(HEADERS[i], CsvIO.unquote(vals[i]));
                }
                out.add(row);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return out;
    }

    private void writeAllRows(List<Map<String,String>> rows) {
        try (BufferedWriter bw = Files.newBufferedWriter(CSV_PATH, StandardCharsets.UTF_8,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            bw.write(CSV_HEADER);
            bw.newLine();
            for (Map<String,String> row : rows) {
                List<String> ordered = new ArrayList<>(HEADERS.length);
                for (String h : HEADERS) ordered.add(CsvIO.csv(row.getOrDefault(h, "")));
                bw.write(String.join(",", ordered));
                bw.newLine();
            }
        } catch (IOException e) {
            throw new RuntimeException("No pude reescribir el CSV", e);
        }
    }

    // ======= Utiles inline (sin cambios de comportamiento) =======
    private static Map<String, String> parseKeyValues(String[] arr) {
        Map<String, String> map = new HashMap<>();
        for (String s : arr) {
            int idx = s.indexOf('=');
            if (idx > 0) {
                String k = s.substring(0, idx).trim();
                String v = s.substring(idx + 1).trim();
                map.put(k, v);
            }
        }
        return map;
    }

    private static String nowIso() { return LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME); }
    private static String genPatientId() { return "P-" + System.currentTimeMillis(); }
    private static String genDetectionId() { return "D-" + System.currentTimeMillis(); }
    private static String normalizePatientId(String raw) { return raw.startsWith("P-") ? raw : ("P-" + raw); }
}

/* ======================= Utils ======================= */

class DiseaseDB {
    private final Map<String, String> catalog = new HashMap<>();
    private final Map<String, String> diseaseNames = new HashMap<>();
    private final Map<String, Integer> diseaseSeverity = new HashMap<>();
    private final LinkedHashMap<String, String> signatures = new LinkedHashMap<>();

    DiseaseDB(Path catalogCsv, Path signaturesCsv) {
        loadCatalog(catalogCsv);
        loadSignatures(signaturesCsv);
    }

    Map<String, String> getCatalog() { return catalog; }
    Map<String, String> getDiseaseNames() { return diseaseNames; }
    Map<String, Integer> getDiseaseSeverity() { return diseaseSeverity; }
    LinkedHashMap<String, String> getSignatures() { return signatures; }

    private void loadCatalog(Path catalogCsv) {
        try (BufferedReader br = Files.newBufferedReader(catalogCsv, StandardCharsets.UTF_8)) {
            br.readLine(); // header
            String line;
            while ((line = br.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                String[] parts = line.split(",");
                if (parts.length < 3) continue;

                String diseaseId   = parts[0].trim().toUpperCase(Locale.ROOT);
                String diseaseName = parts[1].trim();
                String severityStr = parts[2].trim();
                String fastaFile   = (parts.length >= 4) ? parts[3].trim() : "";

                int sev = 0;
                try { sev = Integer.parseInt(severityStr); } catch (NumberFormatException ignored) {}

                diseaseNames.put(diseaseId, diseaseName);
                diseaseSeverity.put(diseaseId, sev);

                if (!fastaFile.isEmpty()) {
                    Path ref = Paths.get("src/main/disease_db").resolve(fastaFile).normalize();
                    if (Files.exists(ref)) {
                        String sequence = FastaIO.readFasta(ref.toString());
                        catalog.put(diseaseId, sequence);
                    } else {
                        System.out.println("ℹ Referencia no encontrada para " + diseaseId + ": " + ref.toAbsolutePath());
                    }
                }
                System.out.println(" Cargada enfermedad: " + diseaseName + " (" + diseaseId + "), severity=" + sev);
            }
        } catch (IOException e) {
            System.out.println(" No pude cargar catalog.csv: " + e.getMessage());
        }
    }

    private void loadSignatures(Path signaturesCsv) {
        if (!Files.exists(signaturesCsv)) {
            System.out.println(" No hay signatures.csv; no se hará diagnóstico por firmas.");
            return;
        }
        try (BufferedReader br = Files.newBufferedReader(signaturesCsv, StandardCharsets.UTF_8)) {
            br.readLine(); // header
            String line;
            while ((line = br.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                String[] parts = line.split(",");
                if (parts.length < 2) continue;
                String pattern   = parts[0].trim().toUpperCase(Locale.ROOT);
                String diseaseId = parts[1].trim().toUpperCase(Locale.ROOT);

                if (!pattern.isEmpty() && !diseaseId.isEmpty()) {
                    signatures.put(pattern, diseaseId);
                    System.out.println(" Firma cargada: " + pattern + " → " + diseaseId);
                }
            }
        } catch (IOException e) {
            System.out.println(" No pude cargar signatures.csv: " + e.getMessage());
        }
    }
}

class CsvIO {
    static void ensureFileWithHeader(Path path, String header) {
        try {
            Files.createDirectories(path.getParent());
            if (Files.notExists(path)) {
                Files.write(path, Collections.singletonList(header), StandardCharsets.UTF_8, StandardOpenOption.CREATE);
                System.out.println(" CSV creado con encabezado en: " + path.toAbsolutePath());
            } else {
                System.out.println(" CSV existente: " + path.toAbsolutePath());
            }
        } catch (IOException e) {
            throw new RuntimeException("No pude preparar el CSV en " + path.toAbsolutePath(), e);
        }
    }

    static void appendLine(Path path, String line) throws IOException {
        Files.write(path, Collections.singletonList(line),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    static String csv(String val) {
        if (val == null) return "";
        String v = val.replace("\"", "\"\"");
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
            return "\"" + v + "\"";
        }
        return v;
    }

    static String[] splitCsvSimple(String line, int expectedCols) {
        List<String> out = new ArrayList<>(expectedCols);
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
                cur.append(c);
            } else if (c == ',' && !inQuotes) {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        return out.toArray(new String[0]);
    }

    static String unquote(String s) {
        if (s == null) return "";
        s = s.trim();
        if (s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2) {
            s = s.substring(1, s.length()-1).replace("\"\"", "\"");
        }
        return s;
    }
}

class FastaIO {
    static void ensureDir(Path dir) {
        try { Files.createDirectories(dir); } catch (IOException e) { throw new RuntimeException(e); }
    }

    static String cleanSequence(String content) {
        if (content == null || content.isBlank()) return "";
        return content.replaceAll("[^ACGTNacgtn]", "").toUpperCase(Locale.ROOT);
    }

    static void writePatientFasta(Path file, String patientId, String sequence) throws IOException {
        String content = ">" + patientId + System.lineSeparator() + sequence + System.lineSeparator();
        Files.write(file, content.getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    }

    static String readFasta(String filename) {
        StringBuilder sequence = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new FileReader(filename))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (!line.startsWith(">")) sequence.append(line.trim());
            }
        } catch (IOException ignored) {}
        return sequence.toString();
    }

    static String sha256Hex(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] d = md.digest(data);
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}

class DetectionStore {
    static void append(Path detectionsCsv, String detectionId, String patientId, String diseaseId, String diseaseName, String pattern) {
        String created = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        String line = String.join(",", Arrays.asList(
                CsvIO.csv(detectionId), CsvIO.csv(patientId), CsvIO.csv(diseaseId),
                CsvIO.csv(diseaseName), CsvIO.csv(pattern), CsvIO.csv(created)
        ));
        try {
            CsvIO.appendLine(detectionsCsv, line);
        } catch (IOException e) {
            System.out.println(" No pude escribir detección: " + e.getMessage());
        }
    }

    /**
     * Devuelve lista de [disease_id, disease_name, pattern] para un paciente.
     */
    static List<String[]> readByPatient(Path detectionsCsv, String patientId, Map<String,String> diseaseNames) {
        List<String[]> dets = new ArrayList<>();
        if (!Files.exists(detectionsCsv)) return dets;
        try (BufferedReader br = Files.newBufferedReader(detectionsCsv, StandardCharsets.UTF_8)) {
            br.readLine(); // header
            String line;
            while ((line = br.readLine()) != null) {
                String[] vals = CsvIO.splitCsvSimple(line, 6);
                if (vals.length != 6) continue;
                String pid = CsvIO.unquote(vals[1]);
                if (!patientId.equals(pid)) continue;

                String dId   = CsvIO.unquote(vals[2]);
                String dName = Optional.ofNullable(CsvIO.unquote(vals[3]))
                        .filter(s -> !s.isBlank())
                        .orElseGet(() -> diseaseNames.getOrDefault(dId, dId));
                String pat   = CsvIO.unquote(vals[4]);
                dets.add(new String[]{ dId, dName, pat });
            }
        } catch (IOException ioe) {
            System.out.println("️ No pude leer detections.csv: " + ioe.getMessage());
        }
        return dets;
    }
}
