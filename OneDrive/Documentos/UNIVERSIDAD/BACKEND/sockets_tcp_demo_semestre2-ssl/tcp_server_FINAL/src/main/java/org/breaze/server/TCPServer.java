package org.breaze.server;

import org.breaze.logging.AuditLogger;
import org.breaze.protocol.ServerProtocol;

import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSocket;
import java.io.IOException;
import java.util.Map;

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

            while (true) {
                // Aceptamos al cliente
                SSLSocket clientSocket = (SSLSocket) serverSocket.accept();
                String remote = clientSocket.getInetAddress().getHostAddress() + ":" + clientSocket.getPort();

                // Guardamos el remoto en el contexto para la auditoría
                AuditLogger.setRemote(remote);
                AuditLogger.info("CLIENT_CONNECTED", Map.of(
                        "remote", remote,
                        "port", String.valueOf(serverPort)
                ));

                System.out.println("🔗 Client connected! " + remote);

                // Lanzamos hilo por cliente
                new Thread(() -> {
                    try {
                        new ClientWorker(clientSocket, protocol).run();
                        AuditLogger.info("CLIENT_FINISHED", Map.of(
                                "remote", remote
                        ));
                    } catch (Exception e) {
                        AuditLogger.error("CLIENT_ERROR", Map.of(
                                "remote", remote,
                                "error", e.getMessage()
                        ));
                    } finally {
                        AuditLogger.info("CLIENT_DISCONNECTED", Map.of(
                                "remote", remote
                        ));
                        AuditLogger.clearRemote();
                    }
                }).start();
            }
        } catch (IOException e) {
            AuditLogger.error("SERVER_ERROR", Map.of(
                    "port", String.valueOf(serverPort),
                    "error", e.getMessage()
            ));
            System.out.println("❌ Server error: " + e.getMessage());
        }
    }
}
