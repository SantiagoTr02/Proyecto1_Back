package org.breaze;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class ServerProtocol {

    // ======= NUEVO: configuración CSV =======
    // Cambia esto si usas "patiens/patiens.csv"
    private static final Path CSV_PATH = Paths.get("src/main/data_storage/patiens/patiens.csv");
    private static final String CSV_HEADER = String.join(",",
            "patient_id",
            "full_name",
            "document_id",
            "F",                 // usamos disease_id aquí
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

    // Mapa: diseaseId -> secuencia genética (como ya lo tienes)
    private final Map<String, String> catalog = new HashMap<>();

    public ServerProtocol() {
        loadCatalog();
        ensureCsvWithHeader();
    }

    // === Carga catálogo (igual que el tuyo) ===
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

    // ======= NUEVO: manejo de comandos y escritura CSV =======
    public String processMessage(String request) {
        System.out.println("📩 Recibido del cliente: " + request);
        if (request == null || request.trim().isEmpty()) {
            return "ERROR;empty_request";
        }

        try {
            // Formato esperado: CREATE_PATIENT|key=value|key=value|...
            String[] parts = request.split("\\|");
            String command = parts[0].trim().toUpperCase(Locale.ROOT);

            if ("CREATE_PATIENT".equals(command)) {
                Map<String, String> kv = parseKeyValues(Arrays.copyOfRange(parts, 1, parts.length));

                // Campos esperados (los que no vengan, quedan vacíos)
                String patientId      = kv.getOrDefault("patient_id", genPatientId());
                String fullName       = kv.getOrDefault("full_name", "");
                String documentId     = kv.getOrDefault("document_id", "");
                String diseaseId      = kv.getOrDefault("disease_id", ""); // lo pondremos en columna F
                String contactEmail   = kv.getOrDefault("contact_email", "");
                String registration   = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                String age            = kv.getOrDefault("age", "");
                String sex            = kv.getOrDefault("sex", "");
                String clinicalNotes  = kv.getOrDefault("clinical_notes", "");
                String checksumFasta  = kv.getOrDefault("checksum_fasta", "");
                String fileSizeBytes  = kv.getOrDefault("file_size_bytes", "");
                String fastaPath      = kv.getOrDefault("fasta_path", "");
                String active         = kv.getOrDefault("active", "true");

                // Validaciones mínimas
                if (fullName.isEmpty() || documentId.isEmpty()) {
                    return "ERROR;missing_required_fields;need full_name and document_id";
                }
                // (Opcional) valida sex y age
                if (!sex.isEmpty() && !sex.matches("(?i)M|F")) {
                    return "ERROR;invalid_sex;expected M or F";
                }
                if (!age.isEmpty() && !age.matches("\\d+")) {
                    return "ERROR;invalid_age;expected integer";
                }
                // (Opcional) valida disease si te interesa:
                // if (!diseaseId.isEmpty() && !catalog.containsKey(diseaseId)) { ... }

                // Orden exacto de columnas requerido
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
                        csv(active)
                );
                String line = String.join(",", row);
                appendCsvLine(line);

                return "OK;patient_created;" + patientId;
            }

            // Si no es un comando conocido, mantenemos tu eco para debug
            return "✅ Recibido: " + request + " | Enfermedades cargadas: " + catalog.keySet();

        } catch (Exception e) {
            e.printStackTrace();
            return "ERROR;exception;" + e.getClass().getSimpleName() + ";" + e.getMessage();
        }
    }

    // --------- helpers CSV / parsing ---------
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
}
