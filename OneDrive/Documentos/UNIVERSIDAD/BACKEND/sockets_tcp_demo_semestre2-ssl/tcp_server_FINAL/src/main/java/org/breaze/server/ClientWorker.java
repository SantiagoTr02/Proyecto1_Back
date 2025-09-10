package org.breaze.server;

import org.breaze.protocol.ServerProtocol;
import javax.net.ssl.SSLSocket;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

class ClientWorker implements Runnable {

    private final SSLSocket socket;
    private final ServerProtocol protocol;

    ClientWorker(SSLSocket socket, ServerProtocol protocol) {
        this.socket = socket;
        this.protocol = protocol;
    }

    @Override
    public void run() {
        try (SSLSocket s = socket;
             DataInputStream dis = new DataInputStream(s.getInputStream());
             DataOutputStream dos = new DataOutputStream(s.getOutputStream())) {

            // Opcional: evita bloqueos infinitos si el cliente desaparece
            s.setSoTimeout(0); // 0 = sin timeout; pon 30000 si quieres 30s

            while (true) {
                String message;
                try {
                    message = dis.readUTF(); // ⬅️ espera próximo comando del cliente
                } catch (IOException eof) {
                    System.out.println("👋 Client disconnected.");
                    break;
                }

                System.out.println("📩 Received: " + message);

                // Salida voluntaria
                String upper = message.trim().toUpperCase();
                if (upper.equals("EXIT") || upper.equals("QUIT")) {
                    dos.writeUTF("BYE");
                    dos.flush();
                    System.out.println("👋 Client requested exit. Closing socket.");
                    break;
                }

                // Procesar comando
                String response = protocol.processMessage(message);

                // Responder
                dos.writeUTF(response);
                dos.flush();
                System.out.println("📤 Sent: " + response);
            }

        } catch (IOException e) {
            System.out.println("⚠️ Worker error: " + e.getMessage());
        } finally {
            try { socket.close(); } catch (IOException ignored) {}
            System.out.println("🛑 Client socket closed.");
        }
    }
}
