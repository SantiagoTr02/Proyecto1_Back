package org.breaze.protocol; //Ubica la clase en el paquete

import org.breaze.logging.AuditLogger; //Logger propio para auditar operaciones

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;//Las diferentes importaciones se usan para archivos, hashing, rutas, etc..
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class ServerProtocol implements Protocol { //Punto de entrada para procesar mensajes/comandos con el cliente

    // Diferentes rutas usadas para guardar los valores de pacientes, detecciones, fastas, catálogos y firmas
    private static final Path CSV_PATH         = Paths.get("src/main/data_storage/patiens/patiens.csv");
    private static final Path DETECTIONS_CSV   = Paths.get("src/main/data_storage/patiens/detections.csv");
    private static final Path PATIENT_FASTA_DIR= Paths.get("src/main/disease_db/FASTAS");
    private static final Path CATALOG_CSV      = Paths.get("src/main/disease_db/catalog.csv");
    private static final Path SIGNATURES_CSV   = Paths.get("src/main/disease_db/signatures.csv");

    //Define el orden de las columnas del CSV pacientes
    private static final String CSV_HEADER = String.join(",",
            "patient_id","full_name","document_id","F","contact_email","registration_date",
            "age","sex","clinical_notes","checksum_fasta","file_size_bytes","fasta_path","active"
    );

    //Define el orden de las columnas del CSV detecciones
    private static final String DETECTIONS_HEADER = String.join(",",
            "detection_id","patient_id","disease_id","disease_name","pattern","created_at"
    );

    //MAP estructura que almacena pares clave -> valor K->V
    //Mapear orden de columnas filas <-> diccionarios (Conexión entre diferentes datos, fuentes o sistemas)
    private static final String[] HEADERS = CSV_HEADER.split(",");

    //Cuando se carga el sistema, DiseaseDB llena el mapa desde catalog.csv
    private final Map<String, String> catalog;          // diseaseId -> ref sequence (opcional)
    //COVID - ATCGACTGA

    //Cuando devuelves datos al cliente, en vez de dar solo el código interno, le muestras el nombre de la enfermedad
    private final Map<String, String> diseaseNames;     // diseaseId -> name

    //Cuando un paciente tiene múltiples coincidencias genéticas, se ordenan por severidad
    private final Map<String, Integer> diseaseSeverity; // diseaseId -> severity

    //Patrón genético en mayúsculas. Se utiliza para detectar enfermedades buscando
    //patrones dentro de la secuencia genética del paciente
    private final LinkedHashMap<String, String> signatures; // pattern -> diseaseId
    //AGTCAGTC -> Covid

    private final Object csvLock = new Object();
    //Permite bloquear los CSV por múltiples peticiones, para que no hayan errores o información errada


    //Carga los CSV - Endermedades
    public ServerProtocol() {
        DiseaseDB db = new DiseaseDB(CATALOG_CSV, SIGNATURES_CSV); //Pasa rutas donde se encuentran os archivos
        this.catalog         = db.getCatalog();//GUARDAN LOS DAROS CARGADOS EN MEMORIA
        this.diseaseNames    = db.getDiseaseNames();//GUARDAN LOS DAROS CARGADOS EN MEMORIA
        this.diseaseSeverity = db.getDiseaseSeverity();//GUARDAN LOS DAROS CARGADOS EN MEMORIA
        this.signatures      = db.getSignatures();//GUARDAN LOS DAROS CARGADOS EN MEMORIA

        CsvIO.ensureFileWithHeader(CSV_PATH, CSV_HEADER);
        CsvIO.ensureFileWithHeader(DETECTIONS_CSV, DETECTIONS_HEADER);//Se asegura de que existan los archivos
        FastaIO.ensureDir(PATIENT_FASTA_DIR);                    //Si no existe lo crea y guarda, si existe no hace nada
    }   //Se asegura de que exista el dir FASTA

    // Valida la entrada, la parte y la manda al handler
    @Override
    public String processMessage(String request) {
        System.out.println("Recibido del cliente: " + request);
        AuditLogger.info("PROCESS_REQUEST", Map.of("msg", request)); //Se deja una traza de la información ECO
        if (request == null || request.trim().isEmpty()) {
            AuditLogger.warn("EMPTY_REQUEST", Map.of()); //Si es vacia informa
            return "ERROR;empty_request";
        }

        try {
            String[] parts = request.split("\\|"); //Se hace literal ya que solo | es lógico
                            //Inicial-Elimina espacios-Vuelve a Mayuscula
            String command = parts[0].trim().toUpperCase(Locale.ROOT);

            //parts[0] = "CREATE_PATIENT"
            //parts[1] = "full_name=Juan"
            //parts[2] = "document_id=123"
            //Después, parts[0] se usa para saber qué comando ejecutar,
            //y parts[1] ... parts[n] son enviados a los handlers para procesar los parámetros.


            switch (command) { //Decide que hacer según el comando

                //Valida campos obligatorios (full_name, document_id).
                //Limpia y guarda archivos FASTA si se incluyen.
                //Detecta patrones genéticos usando signatures.
                //Escribe una nueva fila en patiens.csv.
                //Registra detecciones en detections.csv si aplica.
                case "CREATE_PATIENT":
                    return handleCreatePatient(Arrays.copyOfRange(parts, 1, parts.length));

                //Busca en patiens.csv el registro del paciente
                //Carga sus datos básicos y diagnósticos relacionados desde detections.csv.
                case "GET_PATIENT":
                    return handleGetPatient(Arrays.copyOfRange(parts, 1, parts.length));

                //Busca el paciente por patient_id.
                //Verifica si está activo (no permite actualizar pacientes desactivados).
                //Valida datos nuevos (por ejemplo, age debe ser numérico).
                //Si hay FASTA nuevo, lo limpia, guarda y recalcula checksum.
                //Reescribe la fila del paciente en patiens.csv.
                case "UPDATE_PATIENT":
                    return handleUpdatePatient(Arrays.copyOfRange(parts, 1, parts.length));

                //Busca el paciente por patient_id.
                //Verifica si ya estaba desactivado.
                //Actualiza el campo active en patiens.csv a false.
                case "DEACTIVATE_PATIENT":
                    return handleDeactivatePatient(Arrays.copyOfRange(parts, 1, parts.length));
                default:
                    AuditLogger.info("UNKNOWN_COMMAND", Map.of("cmd", command));
                    return " Recibido: " + request + " | Enfermedades cargadas: " + catalog.keySet();
            }

        } catch (Exception e) {
            e.printStackTrace();
            AuditLogger.error("SERVER_EXCEPTION", new HashMap<String,String>() {{ //Se registra el error en el servicio de Auditoria
                put("type", e.getClass().getSimpleName());
                put("msg", String.valueOf(e.getMessage()));
            }});
            return "ERROR;exception;" + e.getClass().getSimpleName() + ";" + e.getMessage();
        }
    }

    //Crea pacientes
    //Opcionalmente guarda su FASTA, ejecuta detección por firmas, registra detecciones y escribe una fila en el CSV de pacientes
    private String handleCreatePatient(String[] argParts) throws Exception {
        //Claves esperadas
        //Claves esperadas: patient_id (opcional), full_name, document_id, disease_id (opcional), contact_email, age, sex, clinical_notes, fasta_content.
        Map<String, String> kv = parseKeyValues(argParts);


        //INFORMACIÓN
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


        //Requiere nombre o documento
        if (fullName.isEmpty() || documentId.isEmpty()) {
            AuditLogger.warn("CREATE_PATIENT_BAD_INPUT", Map.of("reason","missing_fullname_or_document"));
            return "ERROR;missing_required_fields;need full_name and document_id";
        }

        //Solo recibe M o F
        if (!sex.isEmpty() && !sex.matches("(?i)M|F")) {
            AuditLogger.warn("CREATE_PATIENT_BAD_INPUT", Map.of("reason","invalid_sex", "sex", sex));
            return "ERROR;invalid_sex;expected M or F";
        }

        //Valor numerico
        if (!age.isEmpty() && !age.matches("\\d+")) {
            AuditLogger.warn("CREATE_PATIENT_BAD_INPUT", Map.of("reason","invalid_age", "age", age));
            return "ERROR;invalid_age;expected integer";
        }

        // FASTA
        String checksumFasta = "";
        String fileSizeBytes = "";
        String fastaPath     = "";
        String cleaned       = FastaIO.cleanSequence(fastaContent); // deja solo ACGTN y mayusculas


        //Pertenece al metodo handleCreatePatient y se encarga de procesar la secuencia genética enviada en el parámetro fasta_content.
        //El objetivo es:
        //Limpiar la secuencia.
        //Guardar el archivo .fasta.
        //Calcular su hash y tamaño.
        //Generar la ruta para almacenarla en el CSV.
        //Manejar casos donde no hay FASTA o la secuencia es inválida.

        if (!cleaned.isEmpty()) {
            Path fastaFile = PATIENT_FASTA_DIR.resolve("patient_" + patientId + ".fasta"); //Si hay secuencia valida guarda el archivo
            FastaIO.writePatientFasta(fastaFile, patientId, cleaned); //Escribe el archivo - Metodo al final

            //Calcular metadatos
            byte[] data = Files.readAllBytes(fastaFile); //lee el archivo en bytes
            checksumFasta = FastaIO.sha256Hex(data); //Calcula el hash
            fileSizeBytes = String.valueOf(data.length); //Tamaño del archivo
            fastaPath     = fastaFile.toString(); //Guarda la ruta de donde se guardan los archivos

            System.out.println("🧬 FASTA guardado en: " + fastaFile.toAbsolutePath());
            System.out.println("   bytes=" + fileSizeBytes + " checksum=" + checksumFasta); //Log informativo
        } else if (!fastaContent.isBlank()) {
            System.out.println(" FASTA recibido pero quedó vacío tras limpieza. No se guardará archivo."); //Esta limpieza se hace si el cliente no escribe caracteres validos en su archivo
        } else {
            System.out.println("No se envió fasta_content. Se omite archivo FASTA."); // Si no se adjunta no se hace nada
        }

        // Detección por firmas (todas las coincidencias, ordenadas por severidad)
        //Se encarga de detectar posibles enfermedades en la secuencia FASTA enviada por el paciente, usando patrones genéticos almacenados en signatures.csv
        List<String[]> hits = detectAllSignatures(cleaned); //Cleanes -> Secuencia genetica del paciente - Hits -> Almacena las coincidencias encontradas
        if (!hits.isEmpty()) {
            if (diseaseId.isBlank()) { //Si no se adjunta este ID en el archivo, el hits tomara el valor de la eenfermedad
                diseaseId = hits.get(0)[0]; // más severa
            }
            for (String[] hit : hits) { //Se recorren todas las coincidencias
                String dId   = hit[0];
                String pat   = hit[1];
                String dName = diseaseNames.getOrDefault(dId, dId);
                DetectionStore.append(DETECTIONS_CSV, genDetectionId(), patientId, dId, dName, pat); //Escibe una nueva fila la cual se almacena en el CSV de los pacientes
            }
            AuditLogger.info("CREATE_DIAG_DETECTIONS", new HashMap<String,String>() {{ //Registra como una auditoria - ID, # Detecciones e enerfemadad mas severa
                put("patient_id", patientId);
                put("count", String.valueOf(hits.size()));
                put("top_disease", hits.get(0)[0]);
            }});
        }

        // Escribir fila del paciente, se respeta el orden para que los datos mapeen de la forma correcta
        List<String> row = Arrays.asList(
                CsvIO.csv(patientId), CsvIO.csv(fullName), CsvIO.csv(documentId), CsvIO.csv(diseaseId),
                CsvIO.csv(contactEmail), CsvIO.csv(registration), CsvIO.csv(age), CsvIO.csv(sex),
                CsvIO.csv(clinicalNotes), CsvIO.csv(checksumFasta), CsvIO.csv(fileSizeBytes),
                CsvIO.csv(fastaPath), CsvIO.csv("true")
        );
        synchronized (csvLock) {
            CsvIO.appendLine(CSV_PATH, String.join(",", row)); //Bloqueo para evitar desfases de informacion
        }

        Map<String, String> meta = new HashMap<>();
        meta.put("patient_id", patientId);
        meta.put("document_id", documentId); //Auditoria de exito con la info presentada
        meta.put("disease_id", diseaseId);
        meta.put("has_fasta", String.valueOf(!cleaned.isEmpty()));
        AuditLogger.info("CREATE_PATIENT_OK", meta);

        // Respuesta con diagnósticos (si hubo)
        String extra = buildDiagnosisPayload(hits);
        return "OK;patient_created;" + patientId + extra;
    }

    // Obtener paciente
    //Este handler lee un paciente del CSV y arma una respuesta estructurada, incluyendo (si existen) sus detecciones.
    private String handleGetPatient(String[] argParts) {
        Map<String, String> kv = parseKeyValues(argParts); //compara con las claves del mapeo para traer la informacion correcta
        String rawPid = kv.getOrDefault("patient_id", "").trim();
        if (rawPid.isEmpty()) { // Si es vacio lo audita y muestra error
            AuditLogger.warn("GET_PATIENT_BAD_INPUT", Map.of("reason","missing_patient_id"));
            return "ERROR;missing_patient_id";
        }

        String patientId = normalizePatientId(rawPid); //Antepone el P- si no se tenia
        Map<String, String> row = findPatientRowById(patientId);//Recorre el csv patiens y lo parse, si no lo tiene muestra error
        if (row == null) {
            AuditLogger.warn("GET_PATIENT_NOT_FOUND", Map.of("patient_id", patientId));
            return "ERROR;not_found;" + patientId;
        }

        String diseaseId   = row.getOrDefault("F", ""); //Devuelve el nombre del usuario, si no hay devuelve el mismo ID registrado
        String diseaseName = Optional.ofNullable(diseaseNames.get(diseaseId)).filter(s -> !s.isBlank()).orElse(diseaseId);

        StringBuilder payload = new StringBuilder(); //Construye un texto plano el cual muestra todos los campos incluidos en su registro
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
        if (!dets.isEmpty()) { //Lee el archivo detections y devuelve una lista con esos parametros
            payload.append(";diagnosis_count=").append(dets.size());
            int idx = 1;
            for (String[] d : dets) {
                payload.append(";diagnosis_").append(idx).append("_id=").append(d[0])
                        .append("|diagnosis_").append(idx).append("_name=").append(d[1])//Si hay detecciones agrega el count para enumerarlas
                        .append("|diagnosis_").append(idx).append("_pattern=").append(d[2]);
                idx++;
            }
        }

        int diagCount = dets.size();
        AuditLogger.info("GET_PATIENT_OK", new HashMap<String,String>() {{
            put("patient_id", patientId); //Genera la auditoria en los LOGS
            put("diagnosis_count", String.valueOf(diagCount));
        }});
        return "OK;patient;" + payload;
    }

    // Actualizar paciente
    //Modifica campos de un paciente existente, puede actualizar su FASTA, y reescribe la fila en el CSV
    private String handleUpdatePatient(String[] argParts) throws Exception {
        Map<String, String> kv = parseKeyValues(argParts); //Mapea las claves k-v

        String rawPid = kv.getOrDefault("patient_id", "").trim(); //Exige el ID para realizar la actualización de datos
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
        }//Verifica que exista o no esta desactivado el usuario, si lo esta, no se puede actualizar
        if ("false".equalsIgnoreCase(current.getOrDefault("active", "true"))) {
            AuditLogger.warn("UPDATE_BLOCKED_INACTIVE", Map.of("patient_id", patientId));
            return "ERROR;inactive_patient;" + patientId;
        }

        String newSex = kv.get("sex"); //Si incluyen el SEX en un cambio debe de ser F o M
        if (newSex != null && !newSex.isBlank() && !newSex.matches("(?i)M|F")) {
            AuditLogger.warn("UPDATE_PATIENT_BAD_INPUT", Map.of("reason","invalid_sex","sex", newSex));
            return "ERROR;invalid_sex;expected M or F";
        }
        String newAge = kv.get("age");// Si incluyen el AGE en un cambio debe de ser entero
        if (newAge != null && !newAge.isBlank() && !newAge.matches("\\d+")) {
            AuditLogger.warn("UPDATE_PATIENT_BAD_INPUT", Map.of("reason","invalid_age","age", newAge));
            return "ERROR;invalid_age;expected integer";
        }

        String newFastaContent = kv.remove("fasta_content");
        String checksumFasta = null, fileSizeBytes = null, fastaPath = null;

        //Si el request trae fasta_content:
        //Se limpia (solo A/C/G/T/N, mayúsculas).
        //Si queda algo, se sobrescribe patient_<id>.fasta, se calcula SHA-256 y tamaño, y se preparan los 3 metadatos para escribir en el CSV:
        //checksum_fasta, file_size_bytes, fasta_path.
        //Si la limpieza lo deja vacío, no se toca el archivo.

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

        Set<String> updatable = Set.of("full_name","document_id","F","contact_email","age","sex","clinical_notes","active"); //Archivos que si se actualizaran, si se pueden actualizar
        final String finalChecksum = checksumFasta;
        final String finalFileSize = fileSizeBytes;
        final String finalFastaPath = fastaPath;


        //Lee todo el CSV en memoria,
        //Modifica el Map de la fila objetivo con el Consumer,
        //Reescribe el CSV completo.
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
            put("fields", kv.keySet().toString());//Audita el exito o el error de la operacion, con su ID y el FASTA actualizado
            put("fasta_updated", String.valueOf(finalFastaPath != null));
        }});
        return "OK;patient_updated;" + patientId;
    }

    // ======= DEACTIVATE_PATIENT =======
    private String handleDeactivatePatient(String[] argParts) {
        Map<String, String> kv = parseKeyValues(argParts); //Convierte las claves a MAP
        String rawPid = kv.getOrDefault("patient_id", "").trim();//Se necesita el ID del paciete, si no muestra error
        if (rawPid.isEmpty()) {
            AuditLogger.warn("DEACTIVATE_BAD_INPUT", Map.of("reason","missing_patient_id")); //Audita el log para su trazabilidad
            return "ERROR;missing_patient_id";
        }
        String patientId = normalizePatientId(rawPid);

        //BUSCA AL PACIENTE, SI LO ENCUENTRA PASA DE ACTIVE A FLASE SI NO MUESTRA ERROR
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
            return "ERROR;not_found;" + patientId; //Lee todo el CSV, modifica la fila y reescribe el archivo completo para su actualización
        }

        AuditLogger.info("DEACTIVATE_PATIENT_OK", Map.of("patient_id", patientId));
        return "OK;patient_deactivated;" + patientId; //Auditoria y respuesta
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


    //Recibir una secuencia genética limpia (cleaned).
    //Compararla contra patrones conocidos (signatures).
    //Devolver todas las coincidencias encontradas.
    //Ordenarlas de mayor a menor severidad según

    private String buildDiagnosisPayload(List<String[]> hits) { //Se crea la lista que contendrá todas las coincidencias encontradas.
        if (hits.isEmpty()) return "";//Si la secuencia está vacía → no busca nada.
                                                // Si no hay firmas cargadas (signatures.csv vacío) → devuelve lista vacía inmediatamente.
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
        }//Clave (pattern) → secuencia genética característica.
            // Valor (diseaseId) → enfermedad asociada.
        return sb.toString();
    }

    //Busca una fila de paciente en el CSV y la devuelve como Map<columna, valor>.
    private Map<String, String> findPatientRowById(String patientId) {
        try (BufferedReader br = Files.newBufferedReader(CSV_PATH, StandardCharsets.UTF_8)) { //Abre patiens.csv en UTF-8 y asegura el cierre automático del lector.
            String header = br.readLine(); //Leer y validar el header
            if (header == null) return null;
            //Consume la primera línea (cabecera).
            //Si el archivo está vacío → no hay datos.

            String line;
            while ((line = br.readLine()) != null) { //Recorrer cada línea (cada paciente)
                String[] vals = CsvIO.splitCsvSimple(line, HEADERS.length); //Parsear la línea con soporte de comillas
                if (vals.length != HEADERS.length) continue;

                Map<String,String> row = new HashMap<>();//Construir el Map columna → valor
                for (int i = 0; i < HEADERS.length; i++) { //Recorre en orden el array HEADERS para asignar cada columna.
                    row.put(HEADERS[i], CsvIO.unquote(vals[i]));
                }
                if (patientId.equals(row.get("patient_id"))) return row;//Si el patient_id coincide → devuelve el mapa completo de esa fila.
                // Si no, sigue iterando.
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }//No encontrad0
    // Si termina el archivo sin coincidencia → return null.



    //Modificar una fila existente en el CSV de pacientes y reescribir todo el archivo con la versión actualizada.
    private boolean updateRow(String patientId, java.util.function.Consumer<Map<String,String>> updater) {
        //patientId: ID del paciente a actualizar.
        //updater: función (Consumer<Map<String,String>>) que define qué campos modificar en la fila encontrada.
        //Retorno:
        //true ->la fila existía y fue actualizada.
        //false -> no se encontró el paciente → no se reescribió el CSV.
        List<Map<String,String>> all = readAllRows();
        //Abre patiens.csv.
        //Lee cada línea.
        //Crea un Map<columna, valor> por fila.
        //Devuelve una List<Map<String,String>>.
        boolean found = false;
        for (Map<String,String> row : all) {
            if (patientId.equals(row.get("patient_id"))) {
                updater.accept(row);
                found = true;
                break;
            }
        }
        //Recorre todas las filas (O(n)).
        //Si encuentra coincidencia en patient_id:
        //Ejecuta el Consumer updater sobre la fila.
        //Marca found=true.
        //Sale del bucle (solo actualiza una fila).
        if (!found) return false;
        writeAllRows(all);//Si no encontró la fila → devuelve false.
        return true;
    }


    //Cada elemento = un paciente.
    //Cada paciente = Map<columna, valor>.
    //Si el CSV está vacío → devuelve una lista vacía, nunca null
    private List<Map<String,String>> readAllRows() {
        List<Map<String,String>> out = new ArrayList<>();
        try (BufferedReader br = Files.newBufferedReader(CSV_PATH, StandardCharsets.UTF_8)) {
            String header = br.readLine(); //Valida header, si esta vacio devuelve la lista vacia
            if (header == null) return out;
            String line;//Recorre cada línea desde la segunda (después del header).Cada line representa un paciente.
            while ((line = br.readLine()) != null) {
                String[] vals = CsvIO.splitCsvSimple(line, HEADERS.length);//Divide la línea en columnas. Maneja comillas y comas dentro de campos correctamente.
                if (vals.length != HEADERS.length) continue;
                Map<String,String> row = new HashMap<>(); //Inserta en el mapa clave=columna, valor=contenido.
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

    // Reescribe COMPLETAMENTE el CSV de pacientes con las filas provistas.
    private void writeAllRows(List<Map<String,String>> rows) {
        try (BufferedWriter bw = Files.newBufferedWriter(CSV_PATH, StandardCharsets.UTF_8,
                // Si existe, anexa el archivo si no lo crea y lo abre para la escritura
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {

            // 1) Encabezado fijo CSV
            bw.write(CSV_HEADER);
            bw.newLine();

            for (Map<String,String> row : rows) {
                // Preparamos la lista de valores ordenados según HEADERS
                List<String> ordered = new ArrayList<>(HEADERS.length);
                for (String h : HEADERS) {
                    // si falta una columna, escribe vacío
                    //escapa comillas, comas, saltos de línea según reglas CSV
                    ordered.add(CsvIO.csv(row.getOrDefault(h, "")));
                }
                // Unimos por coma y escribimos la línea
                bw.write(String.join(",", ordered));
                bw.newLine();
            }
        } catch (IOException e) {
            // Mnesaje de Excepcion
            throw new RuntimeException("No pude reescribir el CSV", e);
        }
    }

    // Convierte las claves K-V en map
    private static Map<String, String> parseKeyValues(String[] arr) {
        Map<String, String> map = new HashMap<>();
        for (String s : arr) {
            // Busca el primer = como separador
            int idx = s.indexOf('=');
            if (idx > 0) {
                String k = s.substring(0, idx).trim();       // clave a la izquierda
                String v = s.substring(idx + 1).trim();      // valor a la derecha
                map.put(k, v);
            }
        }
        return map;
    }

    // Utilidades pequeñas
    private static String nowIso() { return LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME); }
    private static String genPatientId() { return "P-" + System.currentTimeMillis(); }
    private static String genDetectionId() { return "D-" + System.currentTimeMillis(); }
    // Normaliza IDs permitiendo que el cliente envíe el ID con la P inicial o sin esta
    private static String normalizePatientId(String raw) { return raw.startsWith("P-") ? raw : ("P-" + raw); }
}


class DiseaseDB {
    // Mapas precargados desde CSV
    //diseaseId -> secuencia FASTA de referencia (si hay archivo)
    private final Map<String, String> catalog = new HashMap<>();
    // diseaseNames: diseaseId -> nombre
    private final Map<String, String> diseaseNames = new HashMap<>();
    // diseaseSeverity: diseaseId -> severidad numérica
    private final Map<String, Integer> diseaseSeverity = new HashMap<>();
    // signatures: patrón -> diseaseId
    private final LinkedHashMap<String, String> signatures = new LinkedHashMap<>();

    // Al construir, carga catálogo de enfermedades y firmas
    DiseaseDB(Path catalogCsv, Path signaturesCsv) {
        loadCatalog(catalogCsv);
        loadSignatures(signaturesCsv);
    }

    // Getters de mapas precargados
    Map<String, String> getCatalog() { return catalog; }
    Map<String, String> getDiseaseNames() { return diseaseNames; }
    Map<String, Integer> getDiseaseSeverity() { return diseaseSeverity; }
    LinkedHashMap<String, String> getSignatures() { return signatures; }

    // Carga catalog.csv: nombre, severidad y secuencia de referencia
    private void loadCatalog(Path catalogCsv) {
        try (BufferedReader br = Files.newBufferedReader(catalogCsv, StandardCharsets.UTF_8)) {
            br.readLine();
            String line;
            while ((line = br.readLine()) != null) {
                if (line.trim().isEmpty()) continue;        // ignora líneas en blanco
                String[] parts = line.split(",");
                if (parts.length < 3) continue;             // requiere al menos ID, nombre y severidad

                String diseaseId   = parts[0].trim().toUpperCase(Locale.ROOT);
                String diseaseName = parts[1].trim();
                String severityStr = parts[2].trim();
                String fastaFile   = (parts.length >= 4) ? parts[3].trim() : "";

                int sev = 0;
                try { sev = Integer.parseInt(severityStr); } catch (NumberFormatException ignored) {}
                //mapas de nombre y severidad
                diseaseNames.put(diseaseId, diseaseName);
                diseaseSeverity.put(diseaseId, sev);

                // Si hay ruta de FASTA de referencia, intenta leer y almacenar la secuencia
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

    // Carga signatures.csv: patrón (clave) -> diseaseId (valor)
    private void loadSignatures(Path signaturesCsv) {
        if (!Files.exists(signaturesCsv)) {
            System.out.println(" No hay signatures.csv; no se hará diagnóstico por firmas.");
            return;
        }
        try (BufferedReader br = Files.newBufferedReader(signaturesCsv, StandardCharsets.UTF_8)) {
            br.readLine();
            String line;
            while ((line = br.readLine()) != null) {
                if (line.trim().isEmpty()) continue;  // ignora líneas en blanco
                String[] parts = line.split(",");
                if (parts.length < 2) continue;       // requiere patrón y diseaseId

                // Se almacenan en mayúsculas para comparaciones consistentes
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
    // Garantiza que el archivo exista con el header correcto, crea directorios si faltan
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

    // Agrega una línea al final del CSV, crea el archivo si no existe
    static void appendLine(Path path, String line) throws IOException {
        Files.write(path, Collections.singletonList(line),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    static String csv(String val) {
        if (val == null) return "";
        String v = val.replace("\"", "\"\""); // escapa comillas dobles
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
            return "\"" + v + "\"";           // envuelve en comillas si hace falta
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
                inQuotes = !inQuotes; // alterna estado al encontrar comillas
                cur.append(c);
            } else if (c == ',' && !inQuotes) {
                // separador de columna solo si NO estamos dentro de comillas
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        // agrega el último fragmento acumulado
        out.add(cur.toString());
        return out.toArray(new String[0]);
    }

    // Quita comillas exteriores y desescapa comillas dobles
    static String unquote(String s) {
        if (s == null) return "";
        s = s.trim();
        if (s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2) {
            // elimina comillas exteriores y convierte "" en "
            s = s.substring(1, s.length()-1).replace("\"\"", "\"");
        }
        return s;
    }
}

class FastaIO {
    // Asegura existencia de directorio para FASTA
    static void ensureDir(Path dir) {
        try { Files.createDirectories(dir); } catch (IOException e) { throw new RuntimeException(e); }
    }

    // Limpia una secuencia solo ACGTN y en mayusculas
    static String cleanSequence(String content) {
        if (content == null || content.isBlank()) return "";
        return content.replaceAll("[^ACGTNacgtn]", "").toUpperCase(Locale.ROOT);
    }

    // Escribe un archivo FASTA con header >patientID y la secuencia en la línea siguiente
    static void writePatientFasta(Path file, String patientId, String sequence) throws IOException {
        String content = ">" + patientId + System.lineSeparator() + sequence + System.lineSeparator();
        Files.write(file, content.getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    }

    // Lee un archivo FASTA y concatena las líneas de secuencia, omite headers que empiezan con >
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

    // Calcula SHA-256 de un arreglo de bytes y lo devuelve en hex
    static String sha256Hex(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] d = md.digest(data);
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}

class DetectionStore {
    // Agrega una detección al CSV de detecciones con timestamp actual en ISO
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

    static List<String[]> readByPatient(Path detectionsCsv, String patientId, Map<String,String> diseaseNames) {
        List<String[]> dets = new ArrayList<>();
        if (!Files.exists(detectionsCsv)) return dets;
        try (BufferedReader br = Files.newBufferedReader(detectionsCsv, StandardCharsets.UTF_8)) {
            br.readLine(); // header
            String line;
            while ((line = br.readLine()) != null) {
                // Cada línea debería tener 6 columnas: id, patient, dId, dName, pattern, created
                String[] vals = CsvIO.splitCsvSimple(line, 6);
                if (vals.length != 6) continue;

                String pid = CsvIO.unquote(vals[1]);
                if (!patientId.equals(pid)) continue; // salta si no corresponde al paciente

                String dId   = CsvIO.unquote(vals[2]);
                // Si el nombre viene vacío, lo resuelve por el mapa diseaseNames
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

