package org.breaze.helpers;

import javax.net.ssl.*;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.KeyStore;

/**
 * Crea SSLContext a partir de truststore/keystore.
 * Código simple y directo, sin helpers complejos.
 */
public class ConfigSSL {

    public static SSLContext buildSslContext(String truststorePath,
                                             String truststorePass,
                                             String keystorePath,
                                             String keystorePass,
                                             String keystoreType) throws Exception {

        TrustManager[] trustManagers = null;
        if (truststorePath != null && !truststorePath.isEmpty()) {
            KeyStore ts = KeyStore.getInstance(keystoreType == null ? KeyStore.getDefaultType() : keystoreType);
            InputStream isTs = new FileInputStream(truststorePath);
            try {
                ts.load(isTs, (truststorePass == null ? null : truststorePass.toCharArray()));
            } finally {
                try { isTs.close(); } catch (Exception ex) {}
            }
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(ts);
            trustManagers = tmf.getTrustManagers();
        }

        KeyManager[] keyManagers = null;
        if (keystorePath != null && !keystorePath.isEmpty()) {
            KeyStore ks = KeyStore.getInstance(keystoreType == null ? KeyStore.getDefaultType() : keystoreType);
            InputStream isKs = new FileInputStream(keystorePath);
            try {
                ks.load(isKs, (keystorePass == null ? null : keystorePass.toCharArray()));
            } finally {
                try { isKs.close(); } catch (Exception ex) {}
            }
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, (keystorePass == null ? null : keystorePass.toCharArray()));
            keyManagers = kmf.getKeyManagers();
        }

        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(keyManagers, trustManagers, null);
        return ctx;
    }
}
