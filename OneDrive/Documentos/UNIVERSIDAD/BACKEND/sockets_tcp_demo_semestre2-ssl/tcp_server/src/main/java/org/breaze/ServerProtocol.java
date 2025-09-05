package org.breaze;

import java.io.*;
import java.util.*;

public class ServerProtocol {

    // Mapa: diseaseId -> secuencia genética
    private Map<String, String> catalog = new HashMap<>();

    public ServerProtocol() {
        loadCatalog();
    }

    // Método para cargar el catálogo
    private void loadCatalog() {
        String line;
        try (BufferedReader br = new BufferedReader(new FileReader(
                "src/main/disease_db/catalog.csv"))) {

            br.readLine(); // saltar encabezado
            while ((line = br.readLine()) != null) {
                String[] parts = line.split(",");
                String diseaseId = parts[0];
                String diseaseName = parts[1];
                String fastaFile = parts[3]; // 👈 ahora columna fasta_file

                // Construir la ruta relativa al disease_db
                String fastaPath = "src/main/disease_db/" + fastaFile;

                // Leer el archivo FASTA
                String sequence = readFasta(fastaPath);
                catalog.put(diseaseId, sequence);

                System.out.println("✅ Cargada enfermedad: " + diseaseName + " (" + diseaseId + ")");
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }



    // Método para leer un archivo FASTA
    private String readFasta(String filename) {
        StringBuilder sequence = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new FileReader(filename))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (!line.startsWith(">")) { // ignorar encabezado FASTA
                    sequence.append(line.trim());
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return sequence.toString();
    }

    // Procesar mensajes (provisional, solo eco)
    public String processMessage(String request) {
        System.out.println("📩 Recibido del cliente: " + request);
        return "✅ Recibido: " + request + " | Enfermedades cargadas: " + catalog.keySet();
    }
}
