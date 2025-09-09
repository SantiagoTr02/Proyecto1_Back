package org.breaze.helpers;

import org.breaze.dto.FastaFile;
import org.breaze.exceptions.InvalidFastaException;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

public class FastaManager {

    public static FastaFile readFasta(String filePath) throws InvalidFastaException {
        File f = new File(filePath);
        if (!f.exists()) {
            throw new InvalidFastaException("FASTA file not found: " + filePath);
        }

        BufferedReader br = null;
        try {
            br = new BufferedReader(new FileReader(f));
            String line;
            String id = null;
            StringBuilder sb = new StringBuilder();

            while ((line = br.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                if (line.startsWith(">")) {
                    if (id == null) {
                        id = line.substring(1).trim();
                    } else {
                        // si hay otro header, concatenamos secuencias (comportamiento simple)
                        // alternativamente podríamos lanzar excepción
                    }
                } else {
                    String s = line.trim().toUpperCase();
                    // validar caracteres básicos ACGTN
                    for (int i = 0; i < s.length(); i++) {
                        char c = s.charAt(i);
                        if (c != 'A' && c != 'C' && c != 'G' && c != 'T' && c != 'N') {
                            throw new InvalidFastaException("Invalid char in FASTA: " + c);
                        }
                    }
                    sb.append(s);
                }
            }

            if (id == null) {
                throw new InvalidFastaException("FASTA header not found (line starting with '>')");
            }
            if (sb.length() == 0) {
                throw new InvalidFastaException("FASTA has no sequence content");
            }

            FastaFile fasta = new FastaFile();
            fasta.setId(id);
            fasta.setContent(sb.toString());
            return fasta;

        } catch (InvalidFastaException e) {
            throw e;
        } catch (Exception e) {
            throw new InvalidFastaException("Error reading FASTA: " + e.getMessage(), e);
        } finally {
            try { if (br != null) br.close(); } catch (Exception ex) { }
        }
    }
}
