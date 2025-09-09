package org.breaze.logic;

import org.breaze.services.PatientService;
import java.util.Scanner;

public class Application {

    public static void main(String[] args) {
        new Application().runApp();
    }

    public void runApp() {
        try {
            Scanner sc = new Scanner(System.in);
            PatientService service = new PatientService();

            while (true) {
                System.out.println("\n========== MENÚ ==========");
                System.out.println("1) Registrar paciente");
                System.out.println("2) Consultar paciente por ID");
                System.out.println("3) Actualizar paciente por ID");
                System.out.println("4) Desactivar paciente por ID");
                System.out.println("0) Salir");
                System.out.print("Elige una opción: ");

                String opcion = sc.nextLine().trim();
                if (opcion.equals("0")) {
                    System.out.println("👋 Saliendo...");
                    service.close();
                    break;
                }

                switch (opcion) {
                    case "1": service.registerPatient(sc); break;
                    case "2": service.getPatient(sc); break;
                    case "3": service.updatePatient(sc); break;
                    case "4": service.deactivatePatient(sc); break;
                    default: System.out.println("❌ Opción inválida");
                }
            }

            sc.close();
        } catch (Exception e) {
            System.out.println("Error general en la aplicación: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
