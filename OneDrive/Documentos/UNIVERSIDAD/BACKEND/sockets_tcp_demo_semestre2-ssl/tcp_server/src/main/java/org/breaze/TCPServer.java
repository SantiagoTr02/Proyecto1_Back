package org.breaze;

import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSocket;
import java.io.IOException;

public class TCPServer {
    private final int serverPort;
    private final ServerProtocol protocol;

    public TCPServer(int serverPort) {
        this.serverPort = serverPort;
        this.protocol = new ServerProtocol();
    }

    public void start() {
        try {
            SSLServerSocketFactory sslSocketFactory = (SSLServerSocketFactory) SSLServerSocketFactory.getDefault();
            SSLServerSocket serverSocket = (SSLServerSocket) sslSocketFactory.createServerSocket(serverPort);
            System.out.println("✅ Server started on port: " + serverPort);

            while (true) { // ⬅️ aceptar clientes indefinidamente
                SSLSocket clientSocket = (SSLSocket) serverSocket.accept();
                System.out.println("🔗 Client connected!");
                // Un hilo por cliente
                new Thread(new ClientWorker(clientSocket, protocol)).start();
            }
        } catch (IOException e) {
            System.out.println("❌ Server error: " + e.getMessage());
        }
    }
}
