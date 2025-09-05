package org.breaze;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.Scanner;

public class TCPClient {

    private SSLSocket socket;
    private DataOutputStream dos;
    private DataInputStream dis;

    public TCPClient(String host, int port) throws Exception {
        SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
        this.socket = (SSLSocket) factory.createSocket(host, port);
        // Evita bloqueos indefinidos esperando respuesta
        this.socket.setSoTimeout(8000); // 8s

        this.dos = new DataOutputStream(socket.getOutputStream());
        this.dis  = new DataInputStream(socket.getInputStream());

        System.out.println("🔗 Conectado al servidor: " + host + ":" + port);
    }

    public void startInteractiveCreatePatient() {
        try (Scanner sc = new Scanner(System.in)) {
            System.out.println("\n=== Registro de Paciente ===");

            System.out.print("Nombre completo: ");
            String fullName = sc.nextLine().trim();

            System.out.print("Número de documento: ");
            String documentId = sc.nextLine().trim();

            System.out.print("Edad: ");
            int age = Integer.parseInt(sc.nextLine().trim());

            System.out.print("Sexo (M/F): ");
            String sex = sc.nextLine().trim().toUpperCase();

            System.out.print("Correo electrónico: ");
            String contactEmail = sc.nextLine().trim();

            System.out.print("ID de enfermedad (disease_id): ");
            String diseaseId = sc.nextLine().trim();

            System.out.println("\nSecuencia FASTA (pegar solo la línea con ACGT...): ");
            String fastaContent = sc.nextLine().trim();

            System.out.print("Notas clínicas: ");
            String clinicalNotes = sc.nextLine().trim();

            // Normaliza para que TODO sea 1 sola línea (aunque ya no dependemos de '\n', es buena práctica)
            fastaContent  = fastaContent.replace("\n", "").replace("\r", "").trim();
            clinicalNotes = clinicalNotes.replace("\n", " ").replace("\r", " ").trim();

            // Resumen + Confirmación
            System.out.println("\n===== Confirma los datos =====");
            System.out.println("Nombre:          " + fullName);
            System.out.println("Documento:       " + documentId);
            System.out.println("Edad:            " + age);
            System.out.println("Sexo:            " + sex);
            System.out.println("Email:           " + contactEmail);
            System.out.println("Disease ID:      " + diseaseId);
            System.out.println("FASTA (preview): " + (fastaContent.length() > 40 ? fastaContent.substring(0, 40) + "..." : fastaContent));
            System.out.println("Notas:           " + (clinicalNotes.length() > 60 ? clinicalNotes.substring(0, 60) + "..." : clinicalNotes));
            System.out.print("Presiona 1 para CONFIRMAR, 0 para CANCELAR: ");

            String confirm;
            while (true) {
                confirm = sc.nextLine().trim();
                if ("1".equals(confirm) || "0".equals(confirm)) break;
                System.out.print("Opción inválida. Escribe 1 (confirmar) o 0 (cancelar): ");
            }
            if ("0".equals(confirm)) {
                System.out.println("❎ Operación cancelada. No se envió nada.");
                return;
            }

            // Construir mensaje (protocolo por pares key=value usando UTF framing)
            String msg = "CREATE_PATIENT"
                    + "|full_name=" + fullName
                    + "|document_id=" + documentId
                    + "|age=" + age
                    + "|sex=" + sex
                    + "|contact_email=" + contactEmail
                    + "|disease_id=" + diseaseId
                    + "|fasta_content=" + fastaContent
                    + "|clinical_notes=" + clinicalNotes;

            System.out.println("\n➡ Enviando al servidor (UTF):");
            System.out.println(msg);

            // ENVIAR con UTF framing (coincide con el server)
            dos.writeUTF(msg);
            dos.flush();

            // LEER respuesta con UTF framing
            String response;
            try {
                response = dis.readUTF();
            } catch (SocketTimeoutException ste) {
                System.out.println("⏱ El servidor no respondió en 8s. Revisa que el server haga writeUTF(...) + flush().");
                return;
            }

            System.out.println("\n📩 Respuesta del servidor: " + response);

        } catch (NumberFormatException nfe) {
            System.out.println("❌ Edad inválida. Debe ser un número entero.");
        } catch (Exception e) {
            e.printStackTrace();
            System.out.println("❌ Error al registrar paciente.");
        }
    }

    public void close() throws IOException {
        try { if (dos != null) dos.close(); } catch (IOException ignored) {}
        try { if (dis != null) dis.close(); } catch (IOException ignored) {}
        try { if (socket != null) socket.close(); } catch (IOException ignored){}
}
}