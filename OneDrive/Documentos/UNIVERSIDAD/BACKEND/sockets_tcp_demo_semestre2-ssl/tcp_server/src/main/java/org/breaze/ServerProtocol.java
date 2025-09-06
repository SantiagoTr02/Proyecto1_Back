package org.breaze;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class ServerProtocol {

    // ======= CSVs =======
    private static final Path CSV_PATH = Paths.get("src/main/data_storage/patiens/patiens.csv");
    private static final Path DETECTIONS_CSV = Paths.get("src/main/data_storage/patiens/detections.csv");
    private static final String CSV_HEADER = String.join(",",
            "patient_id","full_name","document_id","F","contact_email","registration_date",
            "age","sex","clinical_notes","checksum_fasta","file_size_bytes","fasta_path","active"
    );
    private static final String DETECTIONS_HEADER = String.join(",",
            "detection_id","patient_id","disease_id","disease_name","pattern","created_at"
    );
    private static final String[] HEADERS = CSV_HEADER.split(",");

    // Carpeta para guardar FASTA de pacientes
    private static final Path PATIENT_FASTA_DIR = Paths.get("src/main/disease_db/FASTAS");

    // Archivos de conocimiento
    private static final Path CATALOG_CSV = Paths.get("src/main/disease_db/catalog.csv");
    private static final Path SIGNATURES_CSV = Paths.get("src/main/disease_db/signatures.csv");

    // Catálogo
    private final Map<String, String> catalog = new HashMap<>();           // diseaseId -> ref sequence (opcional)
    private final Map<String, String> diseaseNames = new HashMap<>();      // diseaseId -> name
    private final Map<String, Integer> diseaseSeverity = new HashMap<>();  // diseaseId -> severity

    // Firmas: pattern -> diseaseId
    private final LinkedHashMap<String, String> signatures = new LinkedHashMap<>();

    public ServerProtocol() {
        loadCatalog();
        loadSignatures();
        ensureCsvWithHeader();
        ensureDetectionsCsv();
        ensureDir(PATIENT_FASTA_DIR);
    }

    // === Carga catálogo (fasta_file opcional, IDs normalizados) ===
    private void loadCatalog() {
        try (BufferedReader br = Files.newBufferedReader(CATALOG_CSV, StandardCharsets.UTF_8)) {
            String header = br.readLine(); // skip encabezado
            String line;
            while ((line = br.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                String[] parts = line.split(",");
                // Esperamos: disease_id,name,severity[,fasta_file]
                if (parts.length < 3) continue;

                String diseaseId   = parts[0].trim().toUpperCase(Locale.ROOT);
                String diseaseName = parts[1].trim();
                String severityStr = parts[2].trim();
                String fastaFile   = (parts.length >= 4) ? parts[3].trim() : "";

                int sev = 0;
                try { sev = Integer.parseInt(severityStr); } catch (NumberFormatException ignored) {}

                // SIEMPRE cargamos nombre y severidad (lo usa el diagnóstico por firmas)
                diseaseNames.put(diseaseId, diseaseName);
                diseaseSeverity.put(diseaseId, sev);

                // Referencia opcional (no usada por firmas). No falla si no existe.
                if (!fastaFile.isEmpty()) {
                    Path ref = Paths.get("src/main/disease_db").resolve(fastaFile).normalize();
                    if (Files.exists(ref)) {
                        String sequence = readFasta(ref.toString());
                        catalog.put(diseaseId, sequence);
                    } else {
                        System.out.println("ℹ️ Referencia no encontrada para " + diseaseId + ": " + ref.toAbsolutePath());
                    }
                }

                System.out.println("✅ Cargada enfermedad: " + diseaseName + " (" + diseaseId + "), severity=" + sev);
            }
        } catch (IOException e) {
            System.out.println("⚠️ No pude cargar catalog.csv: " + e.getMessage());
        }
    }

    // === Carga firmas (normaliza patrón e ID) ===
    private void loadSignatures() {
        if (!Files.exists(SIGNATURES_CSV)) {
            System.out.println("ℹ️ No hay signatures.csv; no se hará diagnóstico por firmas.");
            return;
        }
        try (BufferedReader br = Files.newBufferedReader(SIGNATURES_CSV, StandardCharsets.UTF_8)) {
            String header = br.readLine(); // skip encabezado
            String line;
            while ((line = br.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                String[] parts = line.split(",");
                if (parts.length < 2) continue;
                String patternRaw   = parts[0].trim();
                String diseaseIdRaw = parts[1].trim();

                String pattern   = patternRaw.toUpperCase(Locale.ROOT);
                String diseaseId = diseaseIdRaw.toUpperCase(Locale.ROOT);

                if (!pattern.isEmpty() && !diseaseId.isEmpty()) {
                    signatures.put(pattern, diseaseId);
                    System.out.println("🧭 Firma cargada: " + pattern + " → " + diseaseId);
                }
            }
        } catch (IOException e) {
            System.out.println("⚠️ No pude cargar signatures.csv: " + e.getMessage());
        }
    }

    private String readFasta(String filename) {
        StringBuilder sequence = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new FileReader(filename))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (!line.startsWith(">")) sequence.append(line.trim());
            }
        } catch (IOException e) {
            // referencia opcional: si falla, seguimos
        }
        return sequence.toString();
    }

    // ======= Manejo de comandos =======
    public String processMessage(String request) {
        System.out.println("📩 Recibido del cliente: " + request);
        if (request == null || request.trim().isEmpty()) {
            return "ERROR;empty_request";
        }

        try {
            String[] parts = request.split("\\|");
            String command = parts[0].trim().toUpperCase(Locale.ROOT);

            // ===== CREATE_PATIENT =====
            if ("CREATE_PATIENT".equals(command)) {
                Map<String, String> kv = parseKeyValues(Arrays.copyOfRange(parts, 1, parts.length));

                String patientId     = kv.getOrDefault("patient_id", genPatientId());
                String fullName      = kv.getOrDefault("full_name", "");
                String documentId    = kv.getOrDefault("document_id", "");
                String diseaseId     = kv.getOrDefault("disease_id", ""); // columna F (puede venir vacío)
                String contactEmail  = kv.getOrDefault("contact_email", "");
                String registration  = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                String age           = kv.getOrDefault("age", "");
                String sex           = kv.getOrDefault("sex", "");
                String clinicalNotes = kv.getOrDefault("clinical_notes", "");
                String fastaContent  = kv.getOrDefault("fasta_content", ""); // texto ACGTN…

                String checksumFasta = "";
                String fileSizeBytes = "";
                String fastaPath     = "";

                if (fullName.isEmpty() || documentId.isEmpty()) {
                    return "ERROR;missing_required_fields;need full_name and document_id";
                }
                if (!sex.isEmpty() && !sex.matches("(?i)M|F")) return "ERROR;invalid_sex;expected M or F";
                if (!age.isEmpty() && !age.matches("\\d+"))   return "ERROR;invalid_age;expected integer";

                // Guardar FASTA de PACIENTE (si llegó)
                String cleaned = "";
                if (!fastaContent.isBlank()) {
                    cleaned = fastaContent.replaceAll("[^ACGTNacgtn]", "").toUpperCase(Locale.ROOT);
                    if (cleaned.isEmpty()) {
                        System.out.println("⚠️ FASTA recibido pero quedó vacío tras limpieza. No se guardará archivo.");
                    } else {
                        Path fastaFile = PATIENT_FASTA_DIR.resolve("patient_" + patientId + ".fasta");
                        writePatientFasta(fastaFile, patientId, cleaned);

                        byte[] data = Files.readAllBytes(fastaFile);
                        checksumFasta = sha256Hex(data);
                        fileSizeBytes = String.valueOf(data.length);
                        fastaPath     = fastaFile.toString();

                        System.out.println("🧬 FASTA guardado en: " + fastaFile.toAbsolutePath());
                        System.out.println("   bytes=" + fileSizeBytes + " checksum=" + checksumFasta);
                    }
                } else {
                    System.out.println("ℹ️ No se envió fasta_content. Se omite archivo FASTA.");
                }

                // === Diagnóstico por firmas (TODAS las coincidencias)
                // Recorremos todas las firmas y juntamos las que estén contenidas en la secuencia
                List<String[]> hits = new ArrayList<>(); // cada item: [diseaseId, pattern]
                if (!cleaned.isEmpty() && !signatures.isEmpty()) {
                    for (Map.Entry<String, String> e : signatures.entrySet()) {
                        String pattern = e.getKey();
                        String dId = e.getValue().toUpperCase(Locale.ROOT);
                        if (cleaned.contains(pattern)) {
                            hits.add(new String[]{ dId, pattern });
                        }
                    }
                    // Ordenar por severidad descendente (más severa primero)
                    hits.sort((a, b) -> {
                        int sa = diseaseSeverity.getOrDefault(a[0], 0);
                        int sb = diseaseSeverity.getOrDefault(b[0], 0);
                        return Integer.compare(sb, sa);
                    });

                    if (!hits.isEmpty()) {
                        // Si el cliente NO envió disease_id, usamos la detección principal (mayor severidad)
                        if (diseaseId.isBlank()) {
                            diseaseId = hits.get(0)[0];
                        }
                        // Registrar TODAS las detecciones
                        for (String[] hit : hits) {
                            String dId = hit[0];
                            String pat = hit[1];
                            String dName = diseaseNames.getOrDefault(dId, "");
                            if (dName == null || dName.isBlank()) dName = dId; // fallback
                            appendDetection(genDetectionId(), patientId, dId, dName, pat);
                        }
                    }
                }

                // Escribir fila de paciente
                List<String> row = Arrays.asList(
                        csv(patientId), csv(fullName), csv(documentId), csv(diseaseId),
                        csv(contactEmail), csv(registration), csv(age), csv(sex), csv(clinicalNotes),
                        csv(checksumFasta), csv(fileSizeBytes), csv(fastaPath), csv("true")
                );
                appendCsvLine(String.join(",", row));

                // Devolver también TODAS las detecciones (si las hubo)
                String extra = "";
                if (!hits.isEmpty()) {
                    StringBuilder sb = new StringBuilder();
                    sb.append(";diagnosis_count=").append(hits.size());
                    int idx = 1;
                    for (String[] hit : hits) {
                        String dId = hit[0];
                        String pat = hit[1];
                        String dName = diseaseNames.getOrDefault(dId, "");
                        if (dName == null || dName.isBlank()) dName = dId; // fallback
                        sb.append(";diagnosis_").append(idx).append("_id=").append(dId)
                                .append("|diagnosis_").append(idx).append("_name=").append(dName)
                                .append("|diagnosis_").append(idx).append("_pattern=").append(pat);
                        idx++;
                    }
                    extra = sb.toString();
                }

                return "OK;patient_created;" + patientId + extra;
            }


            // ===== GET_PATIENT =====
            if ("GET_PATIENT".equals(command)) {
                Map<String, String> kv = parseKeyValues(Arrays.copyOfRange(parts, 1, parts.length));
                String rawPid = kv.getOrDefault("patient_id", "").trim();
                if (rawPid.isEmpty()) return "ERROR;missing_patient_id";

                String patientId = normalizePatientId(rawPid);

                Map<String, String> row = findPatientRowById(patientId);
                if (row == null) return "ERROR;not_found;" + patientId;

                String diseaseId   = row.getOrDefault("F", "");
                String diseaseName = diseaseNames.getOrDefault(diseaseId, "");
                if (diseaseName == null || diseaseName.isBlank()) {
                    diseaseName = diseaseId; // fallback visible
                }

                String payload =
                        "patient_id=" + row.getOrDefault("patient_id","") +
                                "|full_name=" + row.getOrDefault("full_name","") +
                                "|document_id=" + row.getOrDefault("document_id","") +
                                "|disease_id=" + diseaseId +
                                "|disease_name=" + diseaseName +
                                "|contact_email=" + row.getOrDefault("contact_email","") +
                                "|registration_date=" + row.getOrDefault("registration_date","") +
                                "|age=" + row.getOrDefault("age","") +
                                "|sex=" + row.getOrDefault("sex","") +
                                "|clinical_notes=" + row.getOrDefault("clinical_notes","") +
                                "|checksum_fasta=" + row.getOrDefault("checksum_fasta","") +
                                "|file_size_bytes=" + row.getOrDefault("file_size_bytes","") +
                                "|fasta_path=" + row.getOrDefault("fasta_path","") +
                                "|active=" + row.getOrDefault("active","");

                return "OK;patient;" + payload;
            }

            // ===== UPDATE_PATIENT ===== (bloquea inactivos)
            if ("UPDATE_PATIENT".equals(command)) {
                Map<String, String> kv = parseKeyValues(Arrays.copyOfRange(parts, 1, parts.length));

                String rawPid = kv.getOrDefault("patient_id", "").trim();
                if (rawPid.isEmpty()) return "ERROR;missing_patient_id";
                String patientId = normalizePatientId(rawPid);
                kv.remove("patient_id");

                Map<String, String> current = findPatientRowById(patientId);
                if (current == null) return "ERROR;not_found;" + patientId;
                if ("false".equalsIgnoreCase(current.getOrDefault("active", "true"))) {
                    return "ERROR;inactive_patient;" + patientId;
                }

                String newSex = kv.get("sex");
                if (newSex != null && !newSex.isBlank() && !newSex.matches("(?i)M|F")) {
                    return "ERROR;invalid_sex;expected M or F";
                }
                String newAge = kv.get("age");
                if (newAge != null && !newAge.isBlank() && !newAge.matches("\\d+")) {
                    return "ERROR;invalid_age;expected integer";
                }

                String newFastaContent = kv.remove("fasta_content");
                String checksumFasta = null, fileSizeBytes = null, fastaPath = null;
                if (newFastaContent != null && !newFastaContent.isBlank()) {
                    String cleaned = newFastaContent.replaceAll("[^ACGTNacgtn]", "").toUpperCase(Locale.ROOT);
                    if (!cleaned.isEmpty()) {
                        Path fastaFile = PATIENT_FASTA_DIR.resolve("patient_" + patientId + ".fasta");
                        writePatientFasta(fastaFile, patientId, cleaned);
                        byte[] data = Files.readAllBytes(fastaFile);
                        checksumFasta = sha256Hex(data);
                        fileSizeBytes = String.valueOf(data.length);
                        fastaPath     = fastaFile.toString();
                        System.out.println("🧬 FASTA actualizado en: " + fastaFile.toAbsolutePath());
                    } else {
                        System.out.println("⚠️ FASTA en UPDATE quedó vacío tras limpieza. No se actualizará archivo.");
                    }
                }

                Set<String> updatable = new HashSet<>(Arrays.asList(
                        "full_name","document_id","F","contact_email","age","sex","clinical_notes","active"
                ));
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

                if (!ok) return "ERROR;not_found;" + patientId;
                return "OK;patient_updated;" + patientId;
            }

            // ===== DEACTIVATE_PATIENT =====
            if ("DEACTIVATE_PATIENT".equals(command)) {
                Map<String, String> kv = parseKeyValues(Arrays.copyOfRange(parts, 1, parts.length));

                String rawPid = kv.getOrDefault("patient_id", "").trim();
                if (rawPid.isEmpty()) return "ERROR;missing_patient_id";
                String patientId = normalizePatientId(rawPid);

                Map<String, String> row = findPatientRowById(patientId);
                if (row == null) return "ERROR;not_found;" + patientId;

                if ("false".equalsIgnoreCase(row.getOrDefault("active", "true"))) {
                    return "ERROR;already_inactive;" + patientId;
                }

                boolean ok = updateRow(patientId, r -> r.put("active", "false"));
                if (!ok) return "ERROR;not_found;" + patientId;

                return "OK;patient_deactivated;" + patientId;
            }

            // ===== Eco por defecto =====
            return "✅ Recibido: " + request + " | Enfermedades cargadas: " + catalog.keySet();

        } catch (Exception e) {
            e.printStackTrace();
            return "ERROR;exception;" + e.getClass().getSimpleName() + ";" + e.getMessage();
        }
    }

    // ===== Diagnóstico por firmas =====
    private static class DetectionResult {
        final String diseaseId;
        final String pattern;
        final int severity;
        DetectionResult(String d, String p, int s) { diseaseId=d; pattern=p; severity=s; }
    }
    // ===== Diagnóstico por firmas (múltiples) =====
    private static class DetMatch {
        final String diseaseId;
        final String pattern;
        final int severity;

        DetMatch(String d, String p, int s) {
            this.diseaseId = d;
            this.pattern = p;
            this.severity = s;
        }
    }


    private DetectionResult detectDiseaseBySignatures(String cleanedSeq) {
        DetectionResult best = null;
        for (Map.Entry<String, String> e : signatures.entrySet()) {
            String pattern = e.getKey();
            String dId = e.getValue();
            if (cleanedSeq.contains(pattern)) {
                int sev = diseaseSeverity.getOrDefault(dId, 0);
                if (best == null || sev > best.severity) {
                    best = new DetectionResult(dId, pattern, sev);
                }
            }
        }
        return best;
    }

    // Devuelve TODAS las enfermedades cuyos patrones aparezcan en la secuencia.
// Si un mismo diseaseId tiene varias firmas en la secuencia, devolvemos al menos una coincidencia;
// puedes extenderlo para acumular todas las firmas por enfermedad si quieres.
    private List<DetMatch> detectAllDiseasesBySignatures(String cleanedSeq) {
        List<DetMatch> matches = new ArrayList<>();
        // Para evitar duplicados por misma enfermedad, recuerda el primero o el de mayor severidad
        Map<String, DetMatch> bestPerDisease = new HashMap<>();

        for (Map.Entry<String, String> e : signatures.entrySet()) {
            String pattern = e.getKey();
            String dId = e.getValue();
            if (cleanedSeq.contains(pattern)) {
                int sev = diseaseSeverity.getOrDefault(dId, 0);
                DetMatch candidate = new DetMatch(dId, pattern, sev);

                // Conserva la coincidencia de MAYOR severidad por enfermedad (o la primera, si prefieres)
                DetMatch existing = bestPerDisease.get(dId);
                if (existing == null || candidate.severity > existing.severity) {
                    bestPerDisease.put(dId, candidate);
                }
            }
        }
        matches.addAll(bestPerDisease.values());

        // Ordena desc por severidad (la más severa primera)
        matches.sort((a, b) -> Integer.compare(b.severity, a.severity));
        return matches;
    }


    // ===== CSV utils =====
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

    private static String genPatientId() { return "P-" + System.currentTimeMillis(); }
    private static String genDetectionId() { return "D-" + System.currentTimeMillis(); }
    private static String normalizePatientId(String raw) { return raw.startsWith("P-") ? raw : ("P-" + raw); }

    private static String csv(String val) {
        if (val == null) return "";
        String v = val.replace("\"", "\"\"");
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
            return "\"" + v + "\"";
        }
        return v;
    }

    private static void ensureCsvWithHeader() {
        try {
            Files.createDirectories(CSV_PATH.getParent());
            if (Files.notExists(CSV_PATH)) {
                Files.write(CSV_PATH, Collections.singletonList(CSV_HEADER), StandardCharsets.UTF_8, StandardOpenOption.CREATE);
                System.out.println("🗂️ CSV creado con encabezado en: " + CSV_PATH.toAbsolutePath());
            } else {
                System.out.println("🗂️ CSV existente: " + CSV_PATH.toAbsolutePath());
            }
        } catch (IOException e) {
            throw new RuntimeException("No pude preparar el CSV en " + CSV_PATH.toAbsolutePath(), e);
        }
    }

    private static void ensureDetectionsCsv() {
        try {
            Files.createDirectories(DETECTIONS_CSV.getParent());
            if (Files.notExists(DETECTIONS_CSV)) {
                Files.write(DETECTIONS_CSV, Collections.singletonList(DETECTIONS_HEADER), StandardCharsets.UTF_8, StandardOpenOption.CREATE);
                System.out.println("🗂️ detections.csv creado en: " + DETECTIONS_CSV.toAbsolutePath());
            }
        } catch (IOException e) {
            throw new RuntimeException("No pude preparar detections.csv en " + DETECTIONS_CSV.toAbsolutePath(), e);
        }
    }

    private static void appendCsvLine(String line) throws IOException {
        Files.write(CSV_PATH, Collections.singletonList(line), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static void appendDetection(String detectionId, String patientId, String diseaseId, String diseaseName, String pattern) {
        String created = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        String line = String.join(",", Arrays.asList(
                csv(detectionId), csv(patientId), csv(diseaseId), csv(diseaseName), csv(pattern), csv(created)
        ));
        try {
            Files.write(DETECTIONS_CSV, Collections.singletonList(line), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.out.println("⚠️ No pude escribir detección: " + e.getMessage());
        }
    }

    private static void ensureDir(Path dir) {
        try { Files.createDirectories(dir); } catch (IOException e) { throw new RuntimeException(e); }
    }

    private static void writePatientFasta(Path file, String patientId, String sequence) throws IOException {
        String content = ">" + patientId + System.lineSeparator() + sequence + System.lineSeparator();
        Files.write(file, content.getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    }

    private static String sha256Hex(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] d = md.digest(data);
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    // ===== CSV read/write helpers =====
    private Map<String, String> findPatientRowById(String patientId) {
        try (BufferedReader br = Files.newBufferedReader(CSV_PATH, StandardCharsets.UTF_8)) {
            String header = br.readLine();
            if (header == null) return null;

            String[] cols = header.split(",");
            String line;
            while ((line = br.readLine()) != null) {
                String[] vals = splitCsvSimple(line, cols.length);
                if (vals.length != cols.length) continue;

                Map<String,String> row = new HashMap<>();
                for (int i = 0; i < cols.length; i++) {
                    row.put(cols[i], unquote(vals[i]));
                }
                if (patientId.equals(row.get("patient_id"))) {
                    return row;
                }
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
                String[] vals = splitCsvSimple(line, HEADERS.length);
                if (vals.length != HEADERS.length) continue;
                Map<String,String> row = new HashMap<>();
                for (int i = 0; i < HEADERS.length; i++) {
                    row.put(HEADERS[i], unquote(vals[i]));
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
                for (String h : HEADERS) ordered.add(csv(row.getOrDefault(h, "")));
                bw.write(String.join(",", ordered));
                bw.newLine();
            }
        } catch (IOException e) {
            throw new RuntimeException("No pude reescribir el CSV", e);
        }
    }

    // CSV split simple (maneja comillas dobles básicas)
    private static String[] splitCsvSimple(String line, int expectedCols) {
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

    private static String unquote(String s) {
        if (s == null) return "";
        s = s.trim();
        if (s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2) {
            s = s.substring(1, s.length()-1).replace("\"\"", "\"");
        }
        return s;
    }
}
