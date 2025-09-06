package org.breaze.helpers;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Locale;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

public class PropertiesManager {
    private Properties properties;
    private final String CONFIGURATION_PROPERTIES = "configuration.properties";

    public PropertiesManager() {
        properties = new Properties();

        // 1) Intentar cargar desde archivo en el working directory (p. ej. raíz del proyecto)
        try {
            File f = new File(CONFIGURATION_PROPERTIES);
            if (f.exists() && f.isFile()) {
                try (FileInputStream fis = new FileInputStream(f)) {
                    properties.load(fis);
                    return;
                }
            }
        } catch (Exception e) {
            Logger.getLogger(PropertiesManager.class.getName()).log(Level.WARNING, "No se pudo leer configuration.properties desde working dir", e);
        }

        // 2) Intentar cargar como recurso de clase (src/main/resources)
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(CONFIGURATION_PROPERTIES)) {
            if (is != null) {
                properties.load(is);
                return;
            }
        } catch (Exception e) {
            Logger.getLogger(PropertiesManager.class.getName()).log(Level.WARNING, "No se pudo leer configuration.properties desde classpath", e);
        }

        Logger.getLogger(PropertiesManager.class.getName()).log(Level.INFO, "configuration.properties no encontrada; se usarán valores desde variables de entorno si están presentes");
    }

    /**
     * Obtiene el valor buscando:
     * 1) Variable de entorno (transforma key: server.host -> SERVER_HOST)
     * 2) Properties file (key tal cual)
     * Retorna null si no existe.
     */
    public String getProperty(String key) {
        if (key == null) return null;
        // Transformar a nombre de env var: server.host -> SERVER_HOST
        String envName = key.toUpperCase(Locale.ROOT).replace('.', '_');
        String envValue = System.getenv(envName);
        if (envValue != null && !envValue.isBlank()) {
            return envValue;
        }
        // Soporta además env vars ya definidas tal cual (por si el usuario usó SERVER_HOST directamente)
        String directEnv = System.getenv(key);
        if (directEnv != null && !directEnv.isBlank()) {
            return directEnv;
        }
        // fallback a properties file
        String prop = this.properties.getProperty(key);
        if (prop != null && !prop.isBlank()) return prop;
        // también soporta MAYOR_ESCRITURA en .properties (opcional)
        prop = this.properties.getProperty(key.toLowerCase(Locale.ROOT));
        if (prop != null && !prop.isBlank()) return prop;
        prop = this.properties.getProperty(key.toUpperCase(Locale.ROOT));
        if (prop != null && !prop.isBlank()) return prop;
        return null;
    }
}
