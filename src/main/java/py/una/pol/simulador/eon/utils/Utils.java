/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 */
package py.una.pol.simulador.eon.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.jgrapht.Graph;
import org.jgrapht.graph.SimpleWeightedGraph;
import org.jgrapht.graph.DirectedWeightedMultigraph;
import py.una.pol.simulador.eon.models.AssignFsResponse;
import py.una.pol.simulador.eon.models.Core;
import py.una.pol.simulador.eon.models.Demand;
import py.una.pol.simulador.eon.models.EstablishedRoute;
import py.una.pol.simulador.eon.models.Link;
import py.una.pol.simulador.eon.models.enums.TopologiesEnum;

/**
 * Utilerías generales
 *
 * 
 */
public class Utils {

    /**
     * Umbral para detectar modo FSDM (Fiber Switched Division Multiplexing).
     * En FSDM las fibras son físicamente independientes y no existe crosstalk entre ellas.
     * Si crosstalkPerUnitLength < FSDM_CROSSTALK_THRESHOLD, se omite la actualización de crosstalk.
     */
    private static final double FSDM_CROSSTALK_THRESHOLD = 1e-10;

    /**
     * Flag para habilitar detección de sobrescritura de recursos en assignFs().
     * Cuando está habilitado, imprime diagnósticos si assignFs() intenta asignar sobre un slot ya ocupado.
     */
    private static final boolean ENABLE_ASSIGNFS_OVERWRITE_DETECTION = false;  // Desactivado para corridas experimentales normales

    /**
     * Creates the graph that represents the optical network
     *
     * @param topology Topology selected for the network
     * @param numberOfCores Quantity of cores in each link
     * @param fsWidth Width of the frequency slots
     * @param capacity Quantity of frequency slots in a core
     * @return Graph that represents the optical network
     * @throws IOException Error de I/O
     * @throws IllegalArgumentException Parámetros no válidos
     */
    public static Graph<Integer, Link> createTopology(TopologiesEnum topology, int numberOfCores,
            BigDecimal fsWidth, Integer capacity)
            throws IOException, IllegalArgumentException {

        ObjectMapper objectMapper = new ObjectMapper();
        Graph<Integer, Link> g = new DirectedWeightedMultigraph<>(Link.class);
        InputStream is = ResourceReader.getFileFromResourceAsStream(topology.filePath());
        JsonNode object = objectMapper.readTree(is);

        for (int i = 0; i < object.get("network").size(); i++) {
            g.addVertex(i);
        }
        int vertex = 0;
        for (JsonNode node : object.get("network")) {
            for (int i = 0; i < node.get("connections").size(); i++) {
                int connection = node.get("connections").get(i).intValue();
                int distance = node.get("distance").get(i).intValue();
                
                // Crear un solo conjunto de cores compartido por ambas direcciones
                List<Core> sharedCores = new ArrayList<>();
                for (int j = 0; j < numberOfCores; j++) {
                    Core core = new Core(fsWidth, capacity);
                    sharedCores.add(core);
                }

                // Crear dos Links direccionales compartiendo los mismos cores
                Link linkForward = new Link(distance, sharedCores, vertex, connection);
                Link linkBackward = new Link(distance, sharedCores, connection, vertex);
                
                g.addEdge(vertex, connection, linkForward);
                g.addEdge(connection, vertex, linkBackward);
                g.setEdgeWeight(linkForward, distance);
                g.setEdgeWeight(linkBackward, distance);
            }
            vertex++;
        }
        return g;
    }

    /**
     * Genera una lista de demandas en base a los argumentos de entrada
     *
     * @param lambda Cantidad de demandas a insertar por unidad de tiempo
     * @param totalTime Tiempo total de simulación
     * @param fsMin Cantidad mínima de ranuras que puede ocupar una demanda
     * @param fsMax Cantidad máxima de ranuras que puede ocupar una demanda
     * @param cantNodos Cantidad de nodos de la red
     * @param HT Erlang/Lambda
     * @param demandId Identificador de la última demanda generada
     * @param insertionTime Tiempo de inserción de las demanda
     * @return Lista de demandas generadas
     */
    public static List<Demand> generateDemands(Integer lambda, Integer totalTime,
            Integer fsMin, Integer fsMax, Integer cantNodos, Integer HT, Integer demandId, Integer insertionTime) {
        List<Demand> demands = new ArrayList<>();
        Random rand;
        Integer demandasQuantity = MathUtils.poisson(lambda);
        for (Integer j = demandId; j < demandasQuantity + demandId; j++) {
            rand = new Random();
            Integer source = rand.nextInt(cantNodos);
            Integer destination = rand.nextInt(cantNodos);
            Integer fs = (int) (Math.random() * (fsMax - fsMin + 1)) + fsMin;
            while (source.equals(destination)) {
                destination = rand.nextInt(cantNodos);
            }
            Integer tLife = MathUtils.getLifetime(HT);
            demands.add(new Demand(j, source, destination, fs, tLife, false, insertionTime));
        }
        return demands;
    }

    /**
     * Calcula el valor de Crosstalk en un núcleo
     *
     * @param n Número de cores vecinos
     * @param h Crosstalk por Unidad de Longitud
     * @param L Longitud del enlace
     * @return Crosstalk
     */
    public static double XT(int n, double h, int L) {
        double XT = 0;
        for (int i = 0; i < n; i++) {
            XT = XT + (h * (L * 1000));
        }
        return XT;
    }

    /**
     * Calcula la cantidad de nucleos adyacentes para un núcleo en una red de 7
     * núcleos
     *
     * @param core Núcleo a utilizar para encontrar la cantidad de vecinos
     * @return Cantidad de vecinos del núcleo
     */
    public static int getCantidadVecinos(int core) {
        if (core == 6) {
            return 6;
        }
        return 3;
    }

    /**
     * Conversión a decibelios
     *
     * @param value Valor de crosstalk
     * @return Valor de crosstalk en decibelios
     */
    public static BigDecimal toDB(double value) {
        try {
            //return new BigDecimal(10D*Math.log10(value));
        } catch (Exception ex) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(value);
    }

    /**
     * Función de asignación de conexiones a la red
     *
     * @param graph Red
     * @param establishedRoute Ruta a establecer
     * @param crosstalkPerUnitLength Crosstalk por unidad de distancia de la
     * fibra
     * @return Respuesta de la operación
     */
    public static AssignFsResponse assignFs(Graph<Integer, Link> graph, EstablishedRoute establishedRoute, Double crosstalkPerUnitLength) {
        // FSDM: pathCores contiene múltiples cores por enlace (fibrasPorGrupo cores por enlace)
        // SDM original: pathCores contiene un core por enlace
        // Usar fibrasPorGrupo explícito de la ruta (fuente de verdad)
        int fibrasPorEnlace = establishedRoute.getFibrasPorGrupo();
        int numEnlaces = establishedRoute.getPath().size();
        
        for (int linkIdx = 0; linkIdx < numEnlaces; linkIdx++) {
            Link link = establishedRoute.getPath().get(linkIdx);
            
            // Asignar en todas las fibras de este enlace (1 en SDM, fibrasPorGrupo en FSDM)
            for (int f = 0; f < fibrasPorEnlace; f++) {
                Integer core = establishedRoute.getPathCores().get(linkIdx * fibrasPorEnlace + f);
                
                for (int i = establishedRoute.getFsIndexBegin(); i < establishedRoute.getFsIndexBegin() + establishedRoute.getFsWidth(); i++) {
                    // ========== FORENSIC AUDIT: Detectar sobrescritura de slots ocupados (BUG-2) ==========
                    boolean wasOccupied = !link.getCores().get(core).getFrequencySlots().get(i).isFree();
                    int previousLifetime = link.getCores().get(core).getFrequencySlots().get(i).getLifetime();
                    
                    // Registrar en log forense si se intenta sobrescribir
                    ForensicLogger.logAssignAttempt(establishedRoute, link, core, i, wasOccupied, previousLifetime, null);
                    
                    // Mantener diagnóstico existente para consola
                    if (ENABLE_ASSIGNFS_OVERWRITE_DETECTION && wasOccupied) {
                        System.out.println("\n⚠️ ALERTA ASSIGNFS: Sobrescribiendo slot ocupado");
                        System.out.println("  Ruta que intenta asignar: " + establishedRoute.getFrom() + "->" + establishedRoute.getTo() 
                            + " | lifetime: " + establishedRoute.getLifetime());
                        System.out.println("  Link: " + link.getFrom() + "-" + link.getTo() 
                            + " | Core: " + core 
                            + " | FS: " + i);
                        System.out.println("  Estado ANTERIOR: free=ocupado, lifetime=" + previousLifetime);
                        System.out.println("  Estado que se va a sobrescribir: free=ocupado -> libre (INCORRECTO), lifetime=" + previousLifetime + " -> " + establishedRoute.getLifetime());
                        System.out.println("  Rango de asignación: fs[" + establishedRoute.getFsIndexBegin() + "-" + (establishedRoute.getFsIndexBegin() + establishedRoute.getFsWidth() - 1) + "]");
                        System.out.println("  Cores usados por esta ruta: " + establishedRoute.getPathCores());
                    }
                    // ========== FIN FORENSIC AUDIT ==========
                    
                    link.getCores().get(core).getFrequencySlots().get(i).setFree(false);
                    link.getCores().get(core).getFrequencySlots().get(i).setLifetime(establishedRoute.getLifetime());
                }
                
                // FSDM: Omitir actualización de crosstalk si las fibras son físicamente independientes
                if (crosstalkPerUnitLength >= FSDM_CROSSTALK_THRESHOLD) {
                    // SDM: Actualizar crosstalk en cores vecinos
                    List<Integer> coreVecinos = getCoreVecinos(core);
                    for (int i = establishedRoute.getFsIndexBegin(); i < establishedRoute.getFsIndexBegin() + establishedRoute.getFsWidth(); i++) {
                        for (Integer coreIndex = 0; coreIndex < link.getCores().size(); coreIndex++) {
                            if (!core.equals(coreIndex) && coreVecinos.contains(coreIndex)) {
                                double crosstalk = XT(getCantidadVecinos(coreIndex), crosstalkPerUnitLength, link.getDistance());
                                BigDecimal crosstalkDB = toDB(crosstalk);
                                // Con DirectedWeightedMultigraph y cores compartidos, actualizar link.getCores() 
                                // automáticamente actualiza el core compartido por el Link en dirección inversa
                                link.getCores().get(coreIndex).getFrequencySlots().get(i).setCrosstalk(link.getCores().get(coreIndex).getFrequencySlots().get(i).getCrosstalk().add(crosstalkDB));
                            }
                        }
                    }
                }
            }
        }
        AssignFsResponse response = new AssignFsResponse(graph, establishedRoute);
        return response;
    }

    /**
     * Función de desasignación de conexiones a la red
     *
     * @param graph Red
     * @param establishedRoute Ruta a establecer
     * @param crosstalkPerUnitLength Crosstalk por unidad de distancia de la
     * fibra
     */
    public static void deallocateFs(Graph<Integer, Link> graph, EstablishedRoute establishedRoute, Double crosstalkPerUnitLength) {
        // FSDM: pathCores contiene múltiples cores por enlace (fibrasPorGrupo cores por enlace)
        // SDM original: pathCores contiene un core por enlace
        // Usar fibrasPorGrupo explícito de la ruta (fuente de verdad)
        int fibrasPorEnlace = establishedRoute.getFibrasPorGrupo();
        int numEnlaces = establishedRoute.getPath().size();
        
        for (int linkIdx = 0; linkIdx < numEnlaces; linkIdx++) {
            Link link = establishedRoute.getPath().get(linkIdx);
            
            // Desasignar en todas las fibras de este enlace (1 en SDM, fibrasPorGrupo en FSDM)
            for (int f = 0; f < fibrasPorEnlace; f++) {
                Integer core = establishedRoute.getPathCores().get(linkIdx * fibrasPorEnlace + f);
                
                for (int i = establishedRoute.getFsIndexBegin(); i < establishedRoute.getFsIndexBegin() + establishedRoute.getFsWidth(); i++) {
                    // ========== FORENSIC AUDIT: Detectar liberación de slots libres (BUG-1) ==========
                    boolean wasOccupied = !link.getCores().get(core).getFrequencySlots().get(i).isFree();
                    ForensicLogger.logDeallocateAttempt(establishedRoute, link, core, i, wasOccupied);
                    // ========== FIN FORENSIC AUDIT ==========
                    
                    link.getCores().get(core).getFrequencySlots().get(i).setFree(true);
                    link.getCores().get(core).getFrequencySlots().get(i).setLifetime(0);
                }
                
                // FSDM: Omitir actualización de crosstalk si las fibras son físicamente independientes
                if (crosstalkPerUnitLength >= FSDM_CROSSTALK_THRESHOLD) {
                    // SDM: Actualizar crosstalk en cores vecinos
                    List<Integer> coreVecinos = getCoreVecinos(core);
                    for (int i = establishedRoute.getFsIndexBegin(); i < establishedRoute.getFsIndexBegin() + establishedRoute.getFsWidth(); i++) {
                        for (Integer coreIndex = 0; coreIndex < link.getCores().size(); coreIndex++) {
                            if (!core.equals(coreIndex) && coreVecinos.contains(coreIndex)) {
                                double crosstalk = XT(getCantidadVecinos(coreIndex), crosstalkPerUnitLength, link.getDistance());
                                BigDecimal crosstalkDB = toDB(crosstalk);
                                // Con DirectedWeightedMultigraph y cores compartidos, actualizar link.getCores() 
                                // automáticamente actualiza el core compartido por el Link en dirección inversa
                                link.getCores().get(coreIndex).getFrequencySlots().get(i).setCrosstalk(link.getCores().get(coreIndex).getFrequencySlots().get(i).getCrosstalk().subtract(crosstalkDB));
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Obtiene los índices de los núcleos vecinos para un núcleo de la fibra
     *
     * @param coreActual Núcleo de la fibra
     * @return Núcleos adyacentes al núcleo actual
     */
    public static List<Integer> getCoreVecinos(Integer coreActual) {
        List<Integer> vecinos = new ArrayList<>();
        switch (coreActual) {
            case 0 -> {
                vecinos.add(1);
                vecinos.add(5);
                vecinos.add(6);
            }
            case 1 -> {
                vecinos.add(0);
                vecinos.add(2);
                vecinos.add(6);
            }
            case 2 -> {
                vecinos.add(1);
                vecinos.add(3);
                vecinos.add(6);
            }
            case 3 -> {
                vecinos.add(2);
                vecinos.add(4);
                vecinos.add(6);
            }
            case 4 -> {
                vecinos.add(3);
                vecinos.add(5);
                vecinos.add(6);
            }
            case 5 -> {
                vecinos.add(0);
                vecinos.add(4);
                vecinos.add(6);
            }
            case 6 -> {
                vecinos.add(0);
                vecinos.add(1);
                vecinos.add(2);
                vecinos.add(3);
                vecinos.add(4);
                vecinos.add(5);
            }
        }
        return vecinos;
    }

}
