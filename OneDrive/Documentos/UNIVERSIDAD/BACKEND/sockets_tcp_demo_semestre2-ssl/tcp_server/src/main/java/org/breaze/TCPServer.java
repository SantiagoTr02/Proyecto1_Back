package org.breaze;

import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSocket;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public class TCPServer {
    private int serverPort;
    private ServerProtocol protocol;

    public TCPServer(int serverPort) {
        this.serverPort = serverPort;
        this.protocol = new ServerProtocol();
    }

    public void start() {
        try {
            SSLServerSocketFactory sslSocketFactory = (SSLServerSocketFactory) SSLServerSocketFactory.getDefault();
            SSLServerSocket serverSocket = (SSLServerSocket) sslSocketFactory.createServerSocket(serverPort);
            System.out.println("✅ Server started on port: " + serverPort);

            // Solo 1 cliente por ahora
            SSLSocket clientSocket = (SSLSocket) serverSocket.accept();
            System.out.println("🔗 Client connected!");

            DataInputStream dis = new DataInputStream(clientSocket.getInputStream());
            DataOutputStream dos = new DataOutputStream(clientSocket.getOutputStream());

            String message = dis.readUTF();
            System.out.println("📩 Received: " + message);

            // Usamos el protocolo
            String response = protocol.processMessage(message);

            dos.writeUTF(response);
            dos.flush();
            System.out.println("📤 Sent: " + response);

            clientSocket.close();
            serverSocket.close();
            System.out.println("🛑 Server stopped (handled 1 client).");

        } catch (IOException e) {
            System.out.println("❌ Server error: " + e.getMessage());
        }
    }
}
