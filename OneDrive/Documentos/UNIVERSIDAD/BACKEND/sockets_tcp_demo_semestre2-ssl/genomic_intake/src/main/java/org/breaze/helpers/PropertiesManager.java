package org.breaze.helpers;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Properties;

public class PropertiesManager {
    private Properties props;

    public PropertiesManager() {
        props = new Properties();
        // try load from resources
        try {
            InputStream is = getClass().getResourceAsStream("/configuration.properties");
            if (is != null) {
                props.load(is);
                is.close();
                return;
            }
        } catch (Exception e) {
            // ignore, try file in project root
        }

        // fallback: file in root
        try {
            File f = new File("configuration.properties");
            if (f.exists()) {
                FileInputStream fis = new FileInputStream(f);
                props.load(fis);
                fis.close();
            }
        } catch (Exception e) {
            // if can't load, props stays empty
        }
    }

    public String get(String key, String defaultValue) {
        String v = props.getProperty(key);
        return v == null ? defaultValue : v;
    }

    public String get(String key) {
        return props.getProperty(key);
    }
}
