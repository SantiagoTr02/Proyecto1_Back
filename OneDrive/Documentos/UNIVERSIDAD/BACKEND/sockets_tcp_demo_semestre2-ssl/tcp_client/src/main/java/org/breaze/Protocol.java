package org.breaze;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;

public class Protocol {
    // Construye un mensaje CREATE_PATIENT simple
    public static String createPatientMessage(String fullName, String documentId, int age, String sex) {
        return "CREATE_PATIENT|full_name=" + sanitize(fullName)
                + "|document_id=" + sanitize(documentId)
                + "|age=" + age
                + "|sex=" + sanitize(sex);
    }

    // Evita caracteres que rompan el protocolo (muy simple)
    private static String sanitize(String s) {
        if (s == null) return "";
        return s.replace("|", " ").replace("=","");
 }
    public static String calculateChecksum(File file) throws Exception {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        try (InputStream is = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = is.read(buffer)) != -1) {
                md.update(buffer, 0, read);
            }
        }
        byte[] digest = md.digest();
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

}
