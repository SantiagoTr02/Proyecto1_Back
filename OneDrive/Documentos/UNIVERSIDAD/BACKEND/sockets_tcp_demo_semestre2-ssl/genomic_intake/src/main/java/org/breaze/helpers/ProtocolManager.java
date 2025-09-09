package org.breaze.helpers;

import org.breaze.dto.FastaFile;
import org.breaze.dto.Person;

public class ProtocolManager {

    public String buildCreatePatient(Person p, FastaFile fasta) {
        return "CREATE_PATIENT" +
                "|full_name=" + p.getFullName() +
                "|document_id=" + p.getDocumentId() +
                "|age=" + p.getAge() +
                "|sex=" + p.getSex() +
                "|contact_email=" + p.getContactEmail() +
                "|fasta_content=" + fasta.getContent() +
                "|clinical_notes=" + p.getClinicalNotes();
    }

    public String buildGetPatient(String id) {
        return "GET_PATIENT|patient_id=" + id;
    }

    public String buildUpdatePatient(String id, Person updates, FastaFile fasta) {
        StringBuilder sb = new StringBuilder("UPDATE_PATIENT|patient_id=" + id);
        if (updates.getFullName() != null && !updates.getFullName().isEmpty())
            sb.append("|full_name=").append(updates.getFullName());
        if (updates.getDocumentId() != null && !updates.getDocumentId().isEmpty())
            sb.append("|document_id=").append(updates.getDocumentId());
        if (updates.getAge() > 0)
            sb.append("|age=").append(updates.getAge());
        if (updates.getSex() != 0)
            sb.append("|sex=").append(updates.getSex());
        if (updates.getContactEmail() != null && !updates.getContactEmail().isEmpty())
            sb.append("|contact_email=").append(updates.getContactEmail());
        if (updates.getClinicalNotes() != null && !updates.getClinicalNotes().isEmpty())
            sb.append("|clinical_notes=").append(updates.getClinicalNotes());
        if (fasta != null)
            sb.append("|fasta_content=").append(fasta.getContent());
        return sb.toString();
    }

    public String buildDeactivatePatient(String id) {
        return "DEACTIVATE_PATIENT|patient_id=" + id;
    }
}
