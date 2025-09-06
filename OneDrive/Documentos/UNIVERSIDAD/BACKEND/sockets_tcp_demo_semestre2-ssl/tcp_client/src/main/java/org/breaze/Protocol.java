package org.breaze;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public class Protocol {

    // Escapa caracteres que podrían romper el protocolo (|, =, saltos)
    public static String esc(String v) {
        if (v == null) return "";
        return v.replace("\\", "\\\\")
                .replace("\r", " ")
                .replace("\n", " ")
                .replace("|", "\\|")
                .replace("=", "\\=");
    }

    public static String buildCreate(Map<String, String> kv) {
        Objects.requireNonNull(kv.get("full_name"), "full_name requerido");
        Objects.requireNonNull(kv.get("document_id"), "document_id requerido");

        StringBuilder sb = new StringBuilder("CREATE_PATIENT");
        appendPairs(sb, kv);
        return sb.toString();
    }

    public static String buildGet(String patientId) {
        return "GET_PATIENT|patient_id=" + esc(patientId);
    }

    public static String buildUpdate(String patientId, Map<String, String> updates) {
        StringBuilder sb = new StringBuilder("UPDATE_PATIENT|patient_id=").append(esc(patientId));
        appendPairs(sb, updates);
        return sb.toString();
    }

    public static String buildDeactivate(String patientId) {
        return "DEACTIVATE_PATIENT|patient_id=" + esc(patientId);
    }

    // helper
    private static void appendPairs(StringBuilder sb, Map<String, String> kv) {
        for (Map.Entry<String, String> e : kv.entrySet()) {
            String k = e.getKey();
            String v = e.getValue();
            if (v == null || v.isBlank()) continue; // sólo enviamos lo que tenga valor
            sb.append("|").append(k).append("=").append(esc(v.trim()));
        }
    }

    // Buildercito cómodo para create/update
    public static class KV extends LinkedHashMap<String,String> {
        public KV putv(String k, String v){ if(v!=null && !v.isBlank()) this.put(k, v); return this;}
}
}