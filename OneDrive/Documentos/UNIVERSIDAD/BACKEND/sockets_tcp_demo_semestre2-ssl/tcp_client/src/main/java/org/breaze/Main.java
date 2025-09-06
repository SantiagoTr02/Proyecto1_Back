package org.breaze;

import org.breaze.helpers.PropertiesManager;

public class Main {
    public static void main(String[] args) {
        try {
            PropertiesManager pm = new PropertiesManager();

            // Soportamos tanto server.host (archivo) como SERVER_HOST (env)
            String host = pm.getProperty("server.host");
            String portStr = pm.getProperty("server.port");

            // Alternativamente si las variables vienen con otro nombre (USER las pegó así)
            if (host == null) host = pm.getProperty("SERVER_HOST");
            if (portStr == null) portStr = pm.getProperty("SERVER_PORT");

            if (host == null || host.isBlank()) {
                System.err.println("❌ Falta 'server.host' (o env 'SERVER_HOST').");
                return;
            }
            if (portStr == null || portStr.isBlank()) {
                System.err.println("❌ Falta 'server.port' (o env 'SERVER_PORT').");
                return;
            }

            int port;
            try {
                port = Integer.parseInt(portStr.trim());
            } catch (NumberFormatException nfe) {
                System.err.println("❌ El valor de 'server.port' no es numérico: " + portStr);
                return;
            }

            // Truststore (opcional): buscar primero env var TRUSTSTORE_PATH/TRUSTSTORE_PASSWORD
            String truststorePath = pm.getProperty("truststore.path");
            String truststorePass = pm.getProperty("truststore.password");

            if (truststorePath == null) truststorePath = pm.getProperty("TRUSTSTORE_PATH");
            if (truststorePass == null) truststorePass = pm.getProperty("TRUSTSTORE_PASSWORD");

            if (truststorePath != null && !truststorePath.isBlank()) {
                // Configurar propiedades del sistema ANTES de crear sockets SSL
                System.setProperty("javax.net.ssl.trustStore", truststorePath);
                if (truststorePass != null) {
                    System.setProperty("javax.net.ssl.trustStorePassword", truststorePass);
                }
                System.out.println("🔐 Truststore configurado: " + truststorePath);
            } else {
                System.out.println("⚠ No se encontró truststore. Se usará el truststore por defecto del JRE.");
            }

            TCPClient client = new TCPClient(host, port);
            client.runMenu();

        } catch (Exception e) {
            e.printStackTrace();
            System.err.println("❌ Error iniciando el cliente: " + e.getMessage());
        }
    }
}
