package org.breaze;

import org.breaze.dto.FastaFile;
import org.breaze.dto.Person;
import org.breaze.helpers.Protocol;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.SocketTimeoutException;
import java.util.Scanner;

public class TCPClient {
    private SSLSocket socket;
    private DataOutputStream dos;
    private DataInputStream dis;

    public TCPClient(String host, int port) throws Exception {
        SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
        this.socket = (SSLSocket) factory.createSocket(host, port);
        this.socket.setSoTimeout(8000); // evita bloqueos indefinidos esperando respuesta
        this.dos = new DataOutputStream(socket.getOutputStream());
        this.dis = new DataInputStream(socket.getInputStream());
        System.out.println(" Conectado al servidor: " + host + ":" + port);
    }

    // ===== Menú principal =====
    public void runMenu() {
        Scanner sc = new Scanner(System.in);
        while (true) {
            System.out.println("\n========== MENÚ ==========");
            System.out.println("1) Registrar paciente");
            System.out.println("2) Consultar paciente por ID");
            System.out.println("3) Actualizar paciente por ID");
            System.out.println("4) Desactivar paciente por ID");
            System.out.println("0) Salir");
            System.out.print("Elige una opción: ");
            String op = sc.nextLine().trim();
            try {
                switch (op) {
                    case "1": createFlow(sc); break;
                    case "2": getFlow(sc); break;
                    case "3": updateFlow(sc); break;
                    case "4": deactivateFlow(sc); break;
                    case "0":
                        try {
                            dos.writeUTF("EXIT;patient;");
                            dos.flush();
                            try {
                                String resp = dis.readUTF();
                                System.out.println(" Respuesta: " + resp);
                            } catch (SocketTimeoutException ignored) {
                                System.out.println("ℹ Cierre sin respuesta del servidor (timeout).");
                            } catch (Exception ignored) {
                                System.out.println("ℹ Cierre sin respuesta del servidor.");
                            }
                        } catch (Exception ignored) {
                            System.out.println("ℹ No se pudo notificar EXIT (la conexión ya estaba cerrada).");
                        } finally {
                            safeClose();
                        }
                        System.out.println(" Saliendo...");
                        return;
                    default:
                        System.out.println("Opción inválida.");
                }
            } catch (Exception e) {
                e.printStackTrace();
                System.out.println(" Error en la operación. Intenta de nuevo.");
            }
        }
    }

    // ===== Opción 1: Registrar =====
    private void createFlow(Scanner sc) throws Exception {
        System.out.println("\n=== Registrar paciente ===");
        System.out.print("Nombre completo: ");
        String fullName = sc.nextLine().trim();
        System.out.print("Documento: ");
        String documentId = sc.nextLine().trim();
        System.out.print("Edad: ");
        String age = sc.nextLine().trim();
        System.out.print("Sexo (M/F): ");
        String sex = sc.nextLine().trim().toUpperCase();
        System.out.print("Email: ");
        String email = sc.nextLine().trim();
        System.out.print("Disease ID (F): ");
        String diseaseId = sc.nextLine().trim();
        System.out.print("Disease Name: ");
        String diseaseName = sc.nextLine().trim();
        System.out.println("Secuencia FASTA (una sola línea A/C/G/T/N): ");
        String fasta = sc.nextLine().trim();
        System.out.print("Notas clínicas: ");
        String notes = sc.nextLine().trim();

        String patientId = "P-" + System.currentTimeMillis();

        // Resumen + confirmación
        System.out.println("\n--- Confirma los datos ---");
        System.out.println("Patient ID: " + patientId);
        System.out.println("Nombre: " + fullName);
        System.out.println("Doc: " + documentId);
        System.out.println("Edad: " + age + " Sexo: " + sex);
        System.out.println("Email: " + email);
        System.out.println("Disease: " + diseaseId + " (" + diseaseName + ")");
        System.out.println("FASTA: " + (fasta.length()>40 ? fasta.substring(0,40)+"..." : fasta));
        System.out.println("Notas: " + (notes.length()>60 ? notes.substring(0,60)+"..." : notes));
        System.out.print("1=CONFIRMAR, 0=Cancelar: ");
        if (!"1".equals(sc.nextLine().trim())) {
            System.out.println("Cancelado.");
            return;
        }

        // Construir DTO Person y FastaFile (solo con los campos que el servidor espera en el mensaje mínimo)
        Person person = new Person();
        person.setFullName(fullName);
        person.setDocumentId(documentId);
        person.setAge(age);
        person.setSex(sex);
        person.setContactEmail(email);
        person.setClinicalNotes(notes);

        FastaFile fastaDto = new FastaFile(patientId, fasta);

        // LLAMADA AL MÉTODO MINIMAL QUE ARMA EXACTAMENTE EL MENSAJE QUE MOSTRASTE
        String msg = Protocol.buildCreateMinimal(person, fastaDto);
        sendAndPrint(msg);
    }

    // ===== Opción 2: Consultar =====
    private void getFlow(Scanner sc) throws Exception {
        System.out.println("\n=== Consultar paciente por ID ===");
        System.out.print("Patient ID (ej: P-123456): ");
        String pid = sc.nextLine().trim();
        if (pid.isBlank()) {
            System.out.println("ID vacío.");
            return;
        }
        String msg = Protocol.buildGet(pid);
        sendAndPrint(msg);
    }

    // ===== Opción 3: Actualizar =====
// ===== Opción 3: Actualizar =====
    private void updateFlow(Scanner sc) throws Exception {
        System.out.println("\n=== Actualizar paciente por ID ===");
        System.out.print("Patient ID: ");
        String pid = sc.nextLine().trim();
        if (pid.isBlank()) {
            System.out.println("ID vacío.");
            return;
        }

        System.out.println("Deja vacío lo que no quieras actualizar.");
        System.out.print("Nombre completo: ");
        String fullName = sc.nextLine().trim();
        System.out.print("Documento: ");
        String documentId = sc.nextLine().trim();
        System.out.print("Edad: ");
        String age = sc.nextLine().trim();
        System.out.print("Sexo (M/F): ");
        String sex = sc.nextLine().trim().toUpperCase();
        System.out.print("Email: ");
        String email = sc.nextLine().trim();
        System.out.print("Disease ID (F): ");
        String diseaseId = sc.nextLine().trim();
        System.out.print("Disease Name: ");
        String diseaseName = sc.nextLine().trim();
        System.out.print("Notas clínicas: ");
        String notes = sc.nextLine().trim();

        // Construir DTO Person con los posibles cambios (solo campos no vacíos serán enviados)
        Person person = new Person();
        person.setPatientId(pid);
        person.setFullName(fullName);
        person.setDocumentId(documentId);
        person.setAge(age);
        person.setSex(sex);
        person.setContactEmail(email);
        person.setDiseaseId(diseaseId);
        person.setDiseaseName(diseaseName);
        person.setClinicalNotes(notes);

        // Preguntar si desea actualizar FASTA
        FastaFile newFasta = null;
        System.out.print("¿Deseas actualizar la secuencia FASTA? (1=Sí / 0=No): ");
        String wantFasta = sc.nextLine().trim();
        if ("1".equals(wantFasta)) {
            System.out.println("Secuencia FASTA nueva (una sola línea A/C/G/T/N): ");
            String fastaNew = sc.nextLine().trim();
            newFasta = new FastaFile(pid, fastaNew);
        }

        String msg;
        if (newFasta != null) {
            // Usamos la versión que recibe Person + FastaFile: guarda fasta, recalcula checksum/size y arma el update incluyendo esos campos.
            msg = Protocol.buildUpdate(person, newFasta);
            // Este método (según la versión que montamos antes) incluye patient_id + solo campos no vacíos + checksum/file_size/fasta_path
        } else {
            // No hay fasta: construimos un KV con los campos (igual que antes)
            Protocol.KV updates = new Protocol.KV()
                    .putv("full_name", person.getFullName())
                    .putv("document_id", person.getDocumentId())
                    .putv("age", person.getAge())
                    .putv("sex", person.getSex())
                    .putv("contact_email", person.getContactEmail())
                    .putv("disease_id", person.getDiseaseId())
                    .putv("disease_name", person.getDiseaseName())
                    .putv("clinical_notes", person.getClinicalNotes());

            msg = Protocol.buildUpdate(pid, updates);
        }

        sendAndPrint(msg);
    }


    // ===== Opción 4: Desactivar =====
    private void deactivateFlow(Scanner sc) throws Exception {
        System.out.println("\n=== Desactivar paciente (lógico) ===");
        System.out.print("Patient ID: ");
        String pid = sc.nextLine().trim();
        if (pid.isBlank()) {
            System.out.println("ID vacío.");
            return;
        }
        System.out.print("Confirmar desactivación de " + pid + " (1=Sí / 0=No): ");
        if (!"1".equals(sc.nextLine().trim())) {
            System.out.println("Cancelado.");
            return;
        }
        String msg = Protocol.buildDeactivate(pid);
        sendAndPrint(msg);
    }

    // ===== Envío y lectura =====
    private void sendAndPrint(String msg) throws Exception {
        System.out.println("\n➡ Enviando: " + msg);
        dos.writeUTF(msg);
        dos.flush();
        String response;
        try {
            response = dis.readUTF();
        } catch (SocketTimeoutException ste) {
            System.out.println(" Timeout esperando respuesta del servidor.");
            return;
        }
        System.out.println(" Respuesta: " + response);
    }

    // ===== Cierre seguro =====
    private void safeClose() {
        try { if (dos != null) dos.close(); } catch (Exception ignored) {}
        try { if (dis != null) dis.close(); } catch (Exception ignored) {}
        try { if (socket != null) socket.close(); } catch (Exception ignored) {}
    }
}
