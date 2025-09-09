package org.breaze.network;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileInputStream;
import java.util.Properties;

public class TCPClientSLL {

    private SSLSocket socket;
    private DataOutputStream dos;
    private DataInputStream dis;

    // 🔹 Constructor sin parámetros: lee configuration.properties
    public TCPClientSLL() throws Exception {
        Properties p = new Properties();
        p.load(new FileInputStream("configuration.properties"));

        String host = p.getProperty("SERVER_HOST", "localhost");
        int port = Integer.parseInt(p.getProperty("SERVER_PORT", "2020"));
        String truststore = p.getProperty("TRUSTSTORE_PATH");
        String trustpass = p.getProperty("TRUSTSTORE_PASSWORD");

        // Configurar SSL
        System.setProperty("javax.net.ssl.trustStore", truststore);
        System.setProperty("javax.net.ssl.trustStorePassword", trustpass);
        System.setProperty("javax.net.ssl.trustStoreType", "PKCS12");

        // Crear socket SSL
        SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
        this.socket = (SSLSocket) factory.createSocket(host, port);
        this.dos = new DataOutputStream(socket.getOutputStream());
        this.dis = new DataInputStream(socket.getInputStream());

        socket.startHandshake();
        System.out.println("🔗 Conectado al servidor: " + host + ":" + port);
    }

    // 🔹 Ahora devuelve String con la respuesta
    public String sendMessage(String msg) throws Exception {
        dos.writeUTF(msg);
        dos.flush();

        String resp = dis.readUTF();
        return resp;
    }

    public void close() {
        try { if (dos != null) dos.close(); } catch (Exception ignored) {}
        try { if (dis != null) dis.close(); } catch (Exception ignored) {}
        try { if (socket != null) socket.close(); } catch (Exception ignored) {}
    }
}
