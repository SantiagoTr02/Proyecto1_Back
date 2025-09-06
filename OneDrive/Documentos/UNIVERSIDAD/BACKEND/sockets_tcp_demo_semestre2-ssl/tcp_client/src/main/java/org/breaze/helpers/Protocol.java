package org.breaze.helpers;

import org.breaze.dto.FastaFile;
import org.breaze.dto.Person;
import org.breaze.enums.ActionEnum;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.io.File;
import java.io.FileWriter;

/**
 * Protocol: mantiene métodos previos (compatibilidad) y añade buildCreateMinimal
 * que arma el mensaje exactamente como tu servidor espera para CREATE_PATIENT.
 */
public class Protocol {
    private static final ProtocolManager manager = new ProtocolManager();

    public static class KV {
        private final LinkedHashMap<String, String> map = new LinkedHashMap<>();
        public KV putv(String key, String value) { map.put(key, value == null ? "" : value); return this; }
        public Map<String,String> asMap() { return map; }
    }

    // ------------------------------
    // Nuevo: arma mensaje minimal en formato:
    // CREATE_PATIENT|full_name=...|document_id=...|age=...|sex=...|contact_email=...|fasta_content=...|clinical_notes=...
    // ------------------------------
    public static String buildCreateMinimal(Person person, FastaFile fastaFile) {
        if (person == null) throw new IllegalArgumentException("person requerido");

        StringJoiner sj = new StringJoiner("|", "|", "");
        // En el orden que pediste
        sj.add("full_name=" + sanitize(person.getFullName()));
        sj.add("document_id=" + sanitize(person.getDocumentId()));
        sj.add("age=" + sanitize(person.getAge()));
        sj.add("sex=" + sanitize(person.getSex()));
        sj.add("contact_email=" + sanitize(person.getContactEmail()));
        // fasta_content: preferimos el content del FastaFile si viene, si no usar person.fastaPath o vacío
        String fastaContent = "";
        if (fastaFile != null && fastaFile.getContent() != null) {
            fastaContent = fastaFile.getContent();
        }
        sj.add("fasta_content=" + sanitize(fastaContent));
        sj.add("clinical_notes=" + sanitize(person.getClinicalNotes()));

        return "CREATE_PATIENT" + sj.toString();
    }

    // ------------------------------
    // (Opcional) helper para compatibilidad previa: buildCreate desde DTO y guardar FASTA + checksum
    // (No cambia a buildCreateMinimal; lo dejamos si necesitas la versión anterior)
    // ------------------------------
    public static String buildCreate(Person person, FastaFile fastaFile) throws Exception {
        // versión completa que guarda fasta y añade checksum/file_size/path (si la quieres seguir usando)
        String patientId = person.getPatientId();
        if (patientId == null || patientId.isBlank()) patientId = "P-" + System.currentTimeMillis();
        String fastaContent = fastaFile != null ? fastaFile.getContent() : "";
        String fastaPath = "src\\main\\disease_db\\FASTAS\\patient_" + patientId + ".fasta";
        File f = new File(fastaPath);
        f.getParentFile().mkdirs();
        try (FileWriter fw = new FileWriter(f)) {
            fw.write(">" + patientId + "\n");
            fw.write(fastaContent + "\n");
        }
        String checksum = sha256(fastaContent);
        long fileSize = f.length();
        person.setPatientId(patientId);
        person.setChecksumFasta(checksum);
        person.setFileSizeBytes(String.valueOf(fileSize));
        person.setFastaPath(fastaPath);
        person.setActive(true);

        KV kv = new KV()
                .putv("patient_id", person.getPatientId())
                .putv("full_name", person.getFullName())
                .putv("document_id", person.getDocumentId())
                .putv("disease_id", person.getDiseaseId())
                .putv("disease_name", person.getDiseaseName())
                .putv("contact_email", person.getContactEmail())
                .putv("registration_date", java.time.OffsetDateTime.now().toString())
                .putv("age", person.getAge())
                .putv("sex", person.getSex())
                .putv("clinical_notes", person.getClinicalNotes())
                .putv("checksum_fasta", person.getChecksumFasta())
                .putv("file_size_bytes", person.getFileSizeBytes())
                .putv("fasta_path", person.getFastaPath())
                .putv("active", String.valueOf(person.isActive()));

        return manager.buildMessage(ActionEnum.CREATE_PATIENT, kv.asMap()).getPayload();
    }

    // ------------------------------
    // Util: sanitize para evitar '|' y '=' y saltos de línea que rompen el protocolo
    // ------------------------------
    private static String sanitize(String v) {
        if (v == null) return "";
        // Escapar '|' y '=' sustituyéndolos por códigos o eliminando saltos de línea
        return v.replace("|", "%7C").replace("=", "%3D").replace("\r", " ").replace("\n", " ");
    }

    // ------------------------------
    // helper checksum
    // ------------------------------
    private static String sha256(String data) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest((data == null ? "" : data).getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : hash) hex.append(String.format("%02x", b));
        return hex.toString();
    }

    // Mantengo buildGet/buildUpdate/buildDeactivate para compatibilidad (si ya las tienes)
    public static String buildGet(String patientId) {
        KV kv = new KV().putv("patient_id", patientId);
        return manager.buildMessage(org.breaze.enums.ActionEnum.GET_PERSON, kv.asMap()).getPayload();
    }

    public static String buildUpdate(String patientId, KV updates) {
        LinkedHashMap<String, String> map = new LinkedHashMap<>();
        map.put("patient_id", patientId);
        map.putAll(updates.asMap());
        return manager.buildMessage(org.breaze.enums.ActionEnum.UPDATE_PERSON_METADATA, map).getPayload();
    }

    public static String buildDeactivate(String patientId) {
        KV kv = new KV().putv("patient_id", patientId);
        return manager.buildMessage(org.breaze.enums.ActionEnum.DEACTIVATE_PERSON, kv.asMap()).getPayload();
    }
}
