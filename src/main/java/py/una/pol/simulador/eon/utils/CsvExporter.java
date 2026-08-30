package py.una.pol.simulador.eon.utils;

import py.una.pol.simulador.eon.models.Input;
import py.una.pol.simulador.eon.models.enums.TopologiesEnum;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Exportador CSV completamente desacoplado del simulador.
 * Genera automáticamente un archivo resultados_fsdm.csv con los datos del experimento.
 */
public class CsvExporter {

    private static final String CSV_FILE = "resultados_fsdm.csv";
    private static final String CSV_SEPARATOR = ",";

    /**
     * Exporta los resultados de un experimento al archivo CSV.
     * Crea el archivo con cabecera si no existe, o agrega una nueva fila si existe.
     * 
     * @param topology Topología de red utilizada
     * @param input Configuración de entrada del simulador
     * @param erlang Carga en Erlangs
     * @param totalDemandas Total de demandas procesadas
     * @param bloqueosSinDF Bloqueos sin desfragmentación
     * @param bloqueoBFRmax1 Bloqueos DFbFRmax P1
     * @param bloqueoBFRmax3 Bloqueos DFbFRmax P3
     * @param bloqueoBFRmin1 Bloqueos DFbFRmin P1
     * @param bloqueoBFRmin3 Bloqueos DFbFRmin P3
     * @param bloqueoFullRuteoMin1 Bloqueos DFfullRuteoMin P1
     * @param bloqueoFullRuteoMin3 Bloqueos DFfullRuteoMin P3
     * @param tiempoBFRmax1 Tiempo de ejecución DFbFRmax P1 (nanosegundos)
     * @param tiempoBFRmax3 Tiempo de ejecución DFbFRmax P3 (nanosegundos)
     * @param tiempoBFRmin1 Tiempo de ejecución DFbFRmin P1 (nanosegundos)
     * @param tiempoBFRmin3 Tiempo de ejecución DFbFRmin P3 (nanosegundos)
     * @param tiempoFullRuteoMin1 Tiempo de ejecución DFfullRuteoMin P1 (nanosegundos)
     * @param tiempoFullRuteoMin3 Tiempo de ejecución DFfullRuteoMin P3 (nanosegundos)
     */
    public static void exportarResultado(
            TopologiesEnum topology,
            Input input,
            int erlang,
            int totalDemandas,
            int bloqueosSinDF,
            int bloqueoBFRmax1, int bloqueoBFRmax3,
            int bloqueoBFRmin1, int bloqueoBFRmin3,
            int bloqueoFullRuteoMin1, int bloqueoFullRuteoMin3,
            long tiempoBFRmax1, long tiempoBFRmax3,
            long tiempoBFRmin1, long tiempoBFRmin3,
            long tiempoFullRuteoMin1, long tiempoFullRuteoMin3
    ) {
        try {
            File csvFile = new File(CSV_FILE);
            boolean fileExists = csvFile.exists();

            // Si el archivo no existe, crear con cabecera
            if (!fileExists) {
                crearArchivoConCabecera();
            }

            // Agregar fila con datos del experimento
            agregarFilaExperimento(
                topology, input, erlang, totalDemandas,
                bloqueosSinDF,
                bloqueoBFRmax1, bloqueoBFRmax3,
                bloqueoBFRmin1, bloqueoBFRmin3,
                bloqueoFullRuteoMin1, bloqueoFullRuteoMin3,
                tiempoBFRmax1, tiempoBFRmax3,
                tiempoBFRmin1, tiempoBFRmin3,
                tiempoFullRuteoMin1, tiempoFullRuteoMin3
            );

        } catch (IOException e) {
            System.err.println("Error al exportar resultados a CSV: " + e.getMessage());
        }
    }

    /**
     * Crea el archivo CSV con la cabecera
     */
    private static void crearArchivoConCabecera() throws IOException {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(CSV_FILE))) {
            StringBuilder header = new StringBuilder();
            
            // Configuración del experimento
            header.append("Topologia").append(CSV_SEPARATOR);
            header.append("Fibras").append(CSV_SEPARATOR);
            header.append("FibrasPorGrupo").append(CSV_SEPARATOR);
            header.append("CantidadGrupos").append(CSV_SEPARATOR);
            header.append("Erlangs").append(CSV_SEPARATOR);
            header.append("DemandasProcesadas").append(CSV_SEPARATOR);
            
            // Bloqueos
            header.append("Bloqueos_SinDF").append(CSV_SEPARATOR);
            header.append("Bloqueos_BFRmax_P1").append(CSV_SEPARATOR);
            header.append("Bloqueos_BFRmax_P3").append(CSV_SEPARATOR);
            header.append("Bloqueos_BFRmin_P1").append(CSV_SEPARATOR);
            header.append("Bloqueos_BFRmin_P3").append(CSV_SEPARATOR);
            
            // Métricas DFbFRmax P1
            header.append("BFRmax_P1_Exitos").append(CSV_SEPARATOR);
            header.append("BFRmax_P1_Fallos").append(CSV_SEPARATOR);
            header.append("BFRmax_P1_RutasReconfig").append(CSV_SEPARATOR);
            
            // Métricas DFbFRmax P3
            header.append("BFRmax_P3_Exitos").append(CSV_SEPARATOR);
            header.append("BFRmax_P3_Fallos").append(CSV_SEPARATOR);
            header.append("BFRmax_P3_RutasReconfig").append(CSV_SEPARATOR);
            
            // Métricas DFbFRmin P1
            header.append("BFRmin_P1_Exitos").append(CSV_SEPARATOR);
            header.append("BFRmin_P1_Fallos").append(CSV_SEPARATOR);
            header.append("BFRmin_P1_RutasReconfig").append(CSV_SEPARATOR);
            
            // Métricas DFbFRmin P3
            header.append("BFRmin_P3_Exitos").append(CSV_SEPARATOR);
            header.append("BFRmin_P3_Fallos").append(CSV_SEPARATOR);
            header.append("BFRmin_P3_RutasReconfig").append(CSV_SEPARATOR);
            
            // Tiempos de ejecución (en milisegundos)
            header.append("Tiempo_BFRmax_P1_ms").append(CSV_SEPARATOR);
            header.append("Tiempo_BFRmax_P3_ms").append(CSV_SEPARATOR);
            header.append("Tiempo_BFRmin_P1_ms").append(CSV_SEPARATOR);
            header.append("Tiempo_BFRmin_P3_ms");
            
            writer.write(header.toString());
            writer.newLine();
        }
    }

    /**
     * Agrega una fila con los datos del experimento
     */
    private static void agregarFilaExperimento(
            TopologiesEnum topology,
            Input input,
            int erlang,
            int totalDemandas,
            int bloqueosSinDF,
            int bloqueoBFRmax1, int bloqueoBFRmax3,
            int bloqueoBFRmin1, int bloqueoBFRmin3,
            int bloqueoFullRuteoMin1, int bloqueoFullRuteoMin3,
            long tiempoBFRmax1, long tiempoBFRmax3,
            long tiempoBFRmin1, long tiempoBFRmin3,
            long tiempoFullRuteoMin1, long tiempoFullRuteoMin3
    ) throws IOException {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(CSV_FILE, true))) {
            StringBuilder row = new StringBuilder();
            
            // Configuración del experimento
            row.append(topology.label()).append(CSV_SEPARATOR);
            row.append(input.getCores()).append(CSV_SEPARATOR);
            row.append(input.getFibrasPorGrupo()).append(CSV_SEPARATOR);
            row.append(input.getGrupos().size()).append(CSV_SEPARATOR);
            row.append(erlang).append(CSV_SEPARATOR);
            row.append(totalDemandas).append(CSV_SEPARATOR);
            
            // Bloqueos
            row.append(bloqueosSinDF).append(CSV_SEPARATOR);
            row.append(bloqueoBFRmax1).append(CSV_SEPARATOR);
            row.append(bloqueoBFRmax3).append(CSV_SEPARATOR);
            row.append(bloqueoBFRmin1).append(CSV_SEPARATOR);
            row.append(bloqueoBFRmin3).append(CSV_SEPARATOR);
            
            // Métricas de cada heurística (reutilizando datos ya calculados en Defragmenter)
            agregarMetricasHeuristica(row, Defragmenter.metricsBFRmax1);
            agregarMetricasHeuristica(row, Defragmenter.metricsBFRmax3);
            agregarMetricasHeuristica(row, Defragmenter.metricsBFRmin1);
            agregarMetricasHeuristica(row, Defragmenter.metricsBFRmin3);
            
            // Tiempos de ejecución (convertir de nanosegundos a milisegundos)
            row.append(nanosToMillis(tiempoBFRmax1)).append(CSV_SEPARATOR);
            row.append(nanosToMillis(tiempoBFRmax3)).append(CSV_SEPARATOR);
            row.append(nanosToMillis(tiempoBFRmin1)).append(CSV_SEPARATOR);
            row.append(nanosToMillis(tiempoBFRmin3));
            
            writer.write(row.toString());
            writer.newLine();
        }
    }

    /**
     * Agrega las métricas de una heurística a la fila CSV
     */
    private static void agregarMetricasHeuristica(StringBuilder row, Defragmenter.DefragMetrics metrics) {
        row.append(metrics.conteoExitos).append(CSV_SEPARATOR);
        row.append(metrics.conteoFallido).append(CSV_SEPARATOR);
        row.append(metrics.routesMoved).append(CSV_SEPARATOR);
    }

    /**
     * Formatea un double con 2 decimales usando punto como separador decimal
     */
    private static String formatDouble(double value) {
        return String.format("%.2f", value).replace(',', '.');
    }

    /**
     * Convierte nanosegundos a milisegundos
     */
    private static long nanosToMillis(long nanos) {
        return nanos / 1_000_000;
    }
}
