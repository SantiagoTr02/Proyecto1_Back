package org.breaze;

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
        this.dis  = new DataInputStream(socket.getInputStream());
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
                    case "2": getFlow(sc);    break;
                    case "3": updateFlow(sc); break;
                    case "4": deactivateFlow(sc); break;
                    case "0":
                        // --- ENVÍA EXIT Y CIERRA LIMPIO ---
                        try {
                            dos.writeUTF("EXIT");
                            dos.flush();
                            // intenta leer BYE (no obligatorio)
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

        System.out.println("Secuencia FASTA (una sola línea A/C/G/T/N): ");
        String fasta = sc.nextLine().trim();

        System.out.print("Notas clínicas: ");
        String notes = sc.nextLine().trim();

        // Resumen + confirmación
        System.out.println("\n--- Confirma los datos ---");
        System.out.println("Nombre: " + fullName);
        System.out.println("Doc:    " + documentId);
        System.out.println("Edad:   " + age + "   Sexo: " + sex);
        System.out.println("Email:  " + email);
        System.out.println("F:      " + diseaseId);
        System.out.println("FASTA:  " + (fasta.length()>40 ? fasta.substring(0,40)+"..." : fasta));
        System.out.println("Notas:  " + (notes.length()>60 ? notes.substring(0,60)+"..." : notes));
        System.out.print("1=CONFIRMAR, 0=Cancelar: ");
        if (!"1".equals(sc.nextLine().trim())) { System.out.println("Cancelado."); return; }

        // Construir mensaje CREATE
        Protocol.KV kv = new Protocol.KV()
                .putv("full_name", fullName)
                .putv("document_id", documentId)
                .putv("age", age)
                .putv("sex", sex)
                .putv("contact_email", email)
                .putv("disease_id", diseaseId)
                .putv("fasta_content", fasta)
                .putv("clinical_notes", notes);

        String msg = Protocol.buildCreate(kv);
        sendAndPrint(msg);
    }

    // ===== Opción 2: Consultar =====
    private void getFlow(Scanner sc) throws Exception {
        System.out.println("\n=== Consultar paciente por ID ===");
        System.out.print("Patient ID (ej: P-123456): ");
        String pid = sc.nextLine().trim();
        if (pid.isBlank()) { System.out.println("ID vacío."); return; }

        String msg = Protocol.buildGet(pid);
        sendAndPrint(msg);
    }

    // ===== Opción 3: Actualizar =====
    private void updateFlow(Scanner sc) throws Exception {
        System.out.println("\n=== Actualizar paciente por ID ===");
        System.out.print("Patient ID: ");
        String pid = sc.nextLine().trim();
        if (pid.isBlank()) { System.out.println("ID vacío."); return; }

        System.out.println("Deja vacío lo que no quieras actualizar.");
        System.out.print("Nombre completo: "); String fullName = sc.nextLine().trim();
        System.out.print("Documento: ");       String documentId = sc.nextLine().trim();
        System.out.print("Edad: ");            String age = sc.nextLine().trim();
        System.out.print("Sexo (M/F): ");      String sex = sc.nextLine().trim().toUpperCase();
        System.out.print("Email: ");           String email = sc.nextLine().trim();
        System.out.print("Disease ID (F): ");  String diseaseId = sc.nextLine().trim();
        System.out.print("Notas clínicas: ");  String notes = sc.nextLine().trim();

        // NUEVO: permitir actualizar la secuencia FASTA del paciente
        System.out.println("Secuencia FASTA nueva (una sola línea A/C/G/T/N).");
        System.out.println("(Déjalo vacío si NO quieres cambiar el genoma):");
        String fasta = sc.nextLine().trim();

        // Resumen + confirmación
        System.out.println("\n--- Confirma los datos a actualizar ---");
        if (!fullName.isEmpty())   System.out.println("Nombre:       " + fullName);
        if (!documentId.isEmpty()) System.out.println("Documento:    " + documentId);
        if (!age.isEmpty())        System.out.println("Edad:         " + age);
        if (!sex.isEmpty())        System.out.println("Sexo:         " + sex);
        if (!email.isEmpty())      System.out.println("Email:        " + email);
        if (!diseaseId.isEmpty())  System.out.println("F (disease):  " + diseaseId);
        if (!notes.isEmpty())      System.out.println("Notas:        " + (notes.length()>60?notes.substring(0,60)+"...":notes));
        if (!fasta.isEmpty())      System.out.println("FASTA:        " + (fasta.length()>40?fasta.substring(0,40)+"...":fasta));
        System.out.print("1=CONFIRMAR, 0=Cancelar: ");
        if (!"1".equals(sc.nextLine().trim())) { System.out.println("Cancelado."); return; }

        // Construye KV solo con lo que no esté vacío
        Protocol.KV updates = new Protocol.KV()
                .putv("full_name", fullName)
                .putv("document_id", documentId)
                .putv("age", age)
                .putv("sex", sex)
                .putv("contact_email", email)
                .putv("disease_id", diseaseId)
                .putv("clinical_notes", notes);

        // NUEVO: incluir fasta_content si el usuario escribió algo
        if (!fasta.isEmpty()) {
            updates.putv("fasta_content", fasta);
        }

        String msg = Protocol.buildUpdate(pid, updates);
        sendAndPrint(msg);
    }

    // ===== Opción 4: Desactivar =====
    private void deactivateFlow(Scanner sc) throws Exception {
        System.out.println("\n=== Desactivar paciente (lógico) ===");
        System.out.print("Patient ID: ");
        String pid = sc.nextLine().trim();
        if (pid.isBlank()) { System.out.println("ID vacío."); return; }

        System.out.print("Confirmar desactivación de " + pid + " (1=Sí / 0=No): ");
        if (!"1".equals(sc.nextLine().trim())) { System.out.println("Cancelado."); return; }

        String msg = Protocol.buildDeactivate(pid);
        sendAndPrint(msg);
    }

    // ===== Envío y lectura (UTF framing) =====
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
        try { if (socket != null) socket.close(); } catch (Exception ignored){}
}
}