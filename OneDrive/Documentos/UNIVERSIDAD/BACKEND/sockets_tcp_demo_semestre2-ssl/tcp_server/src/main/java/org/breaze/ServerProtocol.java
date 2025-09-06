package org.breaze;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;              // <--- nuevo
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class ServerProtocol {

    // ======= Configuración CSV =======
    private static final Path CSV_PATH = Paths.get("src/main/data_storage/patiens/patiens.csv");
    private static final String CSV_HEADER = String.join(",",
            "patient_id",
            "full_name",
            "document_id",
            "F",                 // disease_id
            "contact_email",
            "registration_date",
            "age",
            "sex",
            "clinical_notes",
            "checksum_fasta",
            "file_size_bytes",
            "fasta_path",
            "active"
    );

    // Carpeta para guardar FASTA de pacientes
    private static final Path PATIENT_FASTA_DIR = Paths.get("src/main/disease_db/FASTAS");

    // Mapa: diseaseId -> secuencia de referencia (catálogo)
    private final Map<String, String> catalog = new HashMap<>();

    public ServerProtocol() {
        loadCatalog();
        ensureCsvWithHeader();
        ensureDir(PATIENT_FASTA_DIR);
    }

    // === Carga catálogo (igual que tenías) ===
    private void loadCatalog() {
        String line;
        try (BufferedReader br = new BufferedReader(new FileReader(
                "src/main/disease_db/catalog.csv"))) {

            br.readLine(); // saltar encabezado
            while ((line = br.readLine()) != null) {
                String[] parts = line.split(",");
                String diseaseId = parts[0];
                String diseaseName = parts[1];
                String fastaFile = parts[3]; // columna fasta_file

                String fastaPath = "src/main/disease_db/" + fastaFile;
                String sequence = readFasta(fastaPath);
                catalog.put(diseaseId, sequence);

                System.out.println("✅ Cargada enfermedad: " + diseaseName + " (" + diseaseId + ")");
            }
        } catch (IOException e) {
            e.printStackTrace();
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
            e.printStackTrace();
        }
        return sequence.toString();
    }

    // ======= Manejo de comandos y escritura CSV =======
    public String processMessage(String request) {
        System.out.println("📩 Recibido del cliente: " + request);
        if (request == null || request.trim().isEmpty()) {
            return "ERROR;empty_request";
        }

        try {
            // Formato: CREATE_PATIENT|key=value|key=value|...
            String[] parts = request.split("\\|");
            String command = parts[0].trim().toUpperCase(Locale.ROOT);

            if ("CREATE_PATIENT".equals(command)) {
                Map<String, String> kv = parseKeyValues(Arrays.copyOfRange(parts, 1, parts.length));

                // Campos esperados
                String patientId     = kv.getOrDefault("patient_id", genPatientId());
                String fullName      = kv.getOrDefault("full_name", "");
                String documentId    = kv.getOrDefault("document_id", "");
                String diseaseId     = kv.getOrDefault("disease_id", ""); // columna F
                String contactEmail  = kv.getOrDefault("contact_email", "");
                String registration  = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                String age           = kv.getOrDefault("age", "");
                String sex           = kv.getOrDefault("sex", "");
                String clinicalNotes = kv.getOrDefault("clinical_notes", "");
                String fastaContent  = kv.getOrDefault("fasta_content", ""); // <--- NUEVO (texto ACGT...)

                // Estos los llenamos si hay fasta_content
                String checksumFasta = "";
                String fileSizeBytes = "";
                String fastaPath     = "";

                // Validaciones mínimas
                if (fullName.isEmpty() || documentId.isEmpty()) {
                    return "ERROR;missing_required_fields;need full_name and document_id";
                }
                if (!sex.isEmpty() && !sex.matches("(?i)M|F")) return "ERROR;invalid_sex;expected M or F";
                if (!age.isEmpty() && !age.matches("\\d+"))   return "ERROR;invalid_age;expected integer";

                // Si vino secuencia FASTA del PACIENTE, persistimos y calculamos metadata
                if (!fastaContent.isBlank()) {
                    // normaliza: solo letras válidas y en mayúscula
                    String cleaned = fastaContent.replaceAll("[^ACGTNacgtn]", "").toUpperCase(Locale.ROOT);

                    // Archivo FASTA por paciente
                    Path fastaFile = PATIENT_FASTA_DIR.resolve("patient_" + patientId + ".fasta");
                    writePatientFasta(fastaFile, patientId, cleaned);

                    // Metadata
                    byte[] data = Files.readAllBytes(fastaFile);
                    checksumFasta = sha256Hex(data);
                    fileSizeBytes = String.valueOf(data.length);
                    fastaPath     = fastaFile.toString();
                }

                // Escribimos en el CSV en el orden EXACTO requerido
                List<String> row = Arrays.asList(
                        csv(patientId),
                        csv(fullName),
                        csv(documentId),
                        csv(diseaseId),      // F
                        csv(contactEmail),
                        csv(registration),
                        csv(age),
                        csv(sex),
                        csv(clinicalNotes),
                        csv(checksumFasta),
                        csv(fileSizeBytes),
                        csv(fastaPath),
                        csv("true")
                );
                String line = String.join(",", row);
                appendCsvLine(line);

                return "OK;patient_created;" + patientId;
            }

            // Eco por defecto para debug
            return "✅ Recibido: " + request + " | Enfermedades cargadas: " + catalog.keySet();

        } catch (Exception e) {
            e.printStackTrace();
            return "ERROR;exception;" + e.getClass().getSimpleName() + ";" + e.getMessage();
        }
    }

    // --------- helpers CSV / parsing / FASTA paciente ---------
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

    private static String genPatientId() {
        return "P-" + System.currentTimeMillis();
    }

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
                Files.write(CSV_PATH,
                        Collections.singletonList(CSV_HEADER),
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE);
                System.out.println("🗂️ CSV creado con encabezado en: " + CSV_PATH.toAbsolutePath());
            } else {
                System.out.println("🗂️ CSV existente: " + CSV_PATH.toAbsolutePath());
            }
        } catch (IOException e) {
            throw new RuntimeException("No pude preparar el CSV en " + CSV_PATH.toAbsolutePath(), e);
        }
    }

    private static void appendCsvLine(String line) throws IOException {
        Files.write(CSV_PATH,
                Collections.singletonList(line),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
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
}
