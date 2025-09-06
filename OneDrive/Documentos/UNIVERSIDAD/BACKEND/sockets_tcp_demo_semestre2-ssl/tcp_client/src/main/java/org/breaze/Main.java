package org.breaze;

import java.io.FileInputStream;
import java.util.Properties;

public class Main {
    public static void main(String[] args) {
        try {
            Properties p = new Properties();
            p.load(new FileInputStream("configuration.properties"));

            String host = p.getProperty("SERVER_HOST", "192.168.1.22");
            int port = Integer.parseInt(p.getProperty("SERVER_PORT", "2020"));
            String truststore = p.getProperty("TRUSTSTORE_PATH", "truststore.jks");
            String trustpass = p.getProperty("TRUSTSTORE_PASSWORD", "cliente123");

            System.setProperty("javax.net.ssl.trustStore", truststore);
            System.setProperty("javax.net.ssl.trustStorePassword", trustpass);

            TCPClient client = new TCPClient(host, port);
            client. runMenu();

        } catch (Exception e) {
            e.printStackTrace();
}
    }
}