package org.breaze.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO que representa al "paciente" tal como lo queremos enviar.
 * Incluye campos usados por el protocolo (patientId, full_name, etc.)
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class Person {
    private String patientId;        // P-...
    private String fullName;         // full_name
    private String documentId;       // document_id
    private String age;              // age
    private String sex;              // sex
    private String contactEmail;     // contact_email
    private String diseaseId;        // disease_id
    private String diseaseName;      // disease_name
    private String clinicalNotes;    // clinical_notes
    private String checksumFasta;    // checksum_fasta (rellenado por Protocol)
    private String fileSizeBytes;    // file_size_bytes (rellenado por Protocol)
    private String fastaPath;        // fasta_path (rellenado por Protocol)
    private boolean active;          // active
}
