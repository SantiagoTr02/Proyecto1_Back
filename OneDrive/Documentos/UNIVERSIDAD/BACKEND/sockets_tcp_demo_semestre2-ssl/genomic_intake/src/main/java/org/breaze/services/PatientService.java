package org.breaze.services;

import org.breaze.dto.FastaFile;
import org.breaze.dto.Person;
import org.breaze.helpers.FastaManager;
import org.breaze.helpers.ProtocolManager;
import org.breaze.network.TCPClientSLL;

import java.util.Scanner;

public class PatientService {

    private final TCPClientSLL client;
    private final ProtocolManager protocol;

    public PatientService() throws Exception {
        this.client = new TCPClientSLL(); // se conecta con configuration.properties
        this.protocol = new ProtocolManager();
    }

    public void registerPatient(Scanner sc) throws Exception {
        System.out.println("\n=== Registrar paciente ===");
        Person p = readPersonInfo(sc);

        System.out.print("Ruta del archivo FASTA: ");
        String fastaPath = sc.nextLine().trim();
        FastaFile fasta = FastaManager.readFasta(fastaPath);

        String message = protocol.buildCreatePatient(p, fasta);
        String response = client.sendMessage(message);
        System.out.println("📨 Servidor: " + response);
    }

    public void getPatient(Scanner sc) throws Exception {
        System.out.println("\n=== Consultar paciente ===");
        System.out.print("ID del paciente: ");
        String id = sc.nextLine().trim();

        String message = protocol.buildGetPatient(id);
        String response = client.sendMessage(message);
        System.out.println("📨 Servidor: " + response);
    }

    public void updatePatient(Scanner sc) throws Exception {
        System.out.println("\n=== Actualizar paciente ===");
        System.out.print("ID del paciente: ");
        String id = sc.nextLine().trim();

        Person updates = readPersonInfo(sc);

        System.out.print("Ruta del archivo FASTA (enter si no cambia): ");
        String fastaPath = sc.nextLine().trim();
        FastaFile fasta = fastaPath.isEmpty() ? null : FastaManager.readFasta(fastaPath);

        String message = protocol.buildUpdatePatient(id, updates, fasta);
        String response = client.sendMessage(message);
        System.out.println("📨 Servidor: " + response);
    }

    public void deactivatePatient(Scanner sc) throws Exception {
        System.out.println("\n=== Desactivar paciente ===");
        System.out.print("ID del paciente: ");
        String id = sc.nextLine().trim();

        String message = protocol.buildDeactivatePatient(id);
        String response = client.sendMessage(message);
        System.out.println("📨 Servidor: " + response);
    }

    public void close() {
        client.close();
    }

    // === Método auxiliar para leer info de la persona ===
    private Person readPersonInfo(Scanner sc) {
        Person p = new Person();
        System.out.print("Nombre completo: ");
        p.setFullName(sc.nextLine().trim());
        System.out.print("Documento: ");
        p.setDocumentId(sc.nextLine().trim());
        System.out.print("Edad: ");
        try { p.setAge(Integer.parseInt(sc.nextLine().trim())); } catch (Exception ex) { p.setAge(0); }
        System.out.print("Sexo (M/F/O): ");
        String s = sc.nextLine().trim();
        p.setSex(s.isEmpty() ? 'O' : s.charAt(0));
        System.out.print("Email: ");
        p.setContactEmail(sc.nextLine().trim());
        System.out.print("Notas clínicas: ");
        p.setClinicalNotes(sc.nextLine().trim());
        return p;
    }
}
