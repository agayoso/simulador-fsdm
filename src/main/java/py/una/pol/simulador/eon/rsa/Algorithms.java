/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package py.una.pol.simulador.eon.rsa;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import org.jgrapht.Graph;
import org.jgrapht.GraphPath;
import org.jgrapht.alg.shortestpath.KShortestSimplePaths;
import py.una.pol.simulador.eon.models.Demand;
import py.una.pol.simulador.eon.models.EstablishedRoute;
import py.una.pol.simulador.eon.models.FrequencySlot;
import py.una.pol.simulador.eon.models.Input;
import py.una.pol.simulador.eon.models.Link;
import py.una.pol.simulador.eon.utils.Utils;


public class Algorithms {

    /**
     * Umbral para detectar modo FSDM (Fiber Switched Division Multiplexing).
     * En FSDM las fibras son físicamente independientes y no existe crosstalk entre ellas.
     * Si crosstalkPerUnitLength < FSDM_CROSSTALK_THRESHOLD, se desactiva toda la lógica de crosstalk.
     */
    private static final double FSDM_CROSSTALK_THRESHOLD = 1e-10;
    
    // Flag para activar trace detallado durante reinserción (controlada desde Defragmenter)
    public static boolean TRACE_RUTEO_DETAIL = false;

    /**
     * Algoritmo RSA sin conmutación de núcleos
     *
     * @param graph Grafo de la topología de la red
     * @param demand Demanda a insertar
     * @param capacity Capacidad de la red
     * @param cores Cantidad total de núcleos
     * @param maxCrosstalk Máximo nivel de crosstalk permitido
     * @param crosstalkPerUnitLength Crosstalk por unidad de longitud (h) de la
     * fibra
     * @return Ruta establecida, o null si hay bloqueo
     */
    public static EstablishedRoute ruteoCoreUnico(Graph<Integer, Link> graph, Demand demand, Integer capacity, Integer cores, BigDecimal maxCrosstalk, Double crosstalkPerUnitLength) {
        int k = 0;

        List<GraphPath<Integer, Link>> kspPlaced = new ArrayList<>();
        List<List<Integer>> kspPlacedCores = new ArrayList<>();
        Integer fsIndexBegin = null;
        Integer selectedIndex;
        // Iteramos los KSP elegidos

        KShortestSimplePaths<Integer, Link> kspFinder = new KShortestSimplePaths<>(graph);
        List<GraphPath<Integer, Link>> kspaths = kspFinder.getPaths(demand.getSource(), demand.getDestination(), 5);
        while (k < kspaths.size() && kspaths.get(k) != null) {
            fsIndexBegin = null;
            GraphPath<Integer, Link> ksp = kspaths.get(k);
            // Recorremos los FS
            for (int i = 0; i < capacity - demand.getFs(); i++) {
                for (int core = 0; core < cores; core++) {
                    List<Link> enlacesLibres = new ArrayList<>();
                    List<Integer> kspCores = new ArrayList<>();
                    List<BigDecimal> crosstalkFSList = new ArrayList<>();
                    // Se inicializa la lista de valores de crosstalk para cada slot de frecuencia del bloque
                    for (int fsCrosstalkIndex = 0; fsCrosstalkIndex < demand.getFs(); fsCrosstalkIndex++) {
                        crosstalkFSList.add(BigDecimal.ZERO);
                    }
                    // Se recorre la ruta
                    for (Link link : ksp.getEdgeList()) {
                        if (core < cores) {
                            // Se obtiene los slots de frecuencia a verificar
                            List<FrequencySlot> bloqueFS = link.getCores().get(core).getFrequencySlots().subList(i, i + demand.getFs());

                            // Controla si está ocupado por una demanda
                            if (isFSBlockFree(bloqueFS)) {

                                // Control de crosstalk
                                for (int fsCrosstalkIndex = 0; fsCrosstalkIndex < demand.getFs(); fsCrosstalkIndex++) {
                                    // Control de crosstalk en la ruta elegida
                                    BigDecimal crosstalkRuta = crosstalkFSList.get(fsCrosstalkIndex);
                                    if (isCrosstalkFree(bloqueFS.get(fsCrosstalkIndex), maxCrosstalk, crosstalkRuta)) {
                                        // Control de crosstalk en los cores vecinos
                                        if (isNextToCrosstalkFreeCores(link, maxCrosstalk, core, i, demand.getFs(), crosstalkPerUnitLength)) {
                                            enlacesLibres.add(link);
                                            kspCores.add(core);
                                            fsIndexBegin = i;
                                            selectedIndex = k;
                                            crosstalkRuta = crosstalkRuta.add(Utils.toDB(Utils.XT(Utils.getCantidadVecinos(core), crosstalkPerUnitLength, link.getDistance())));
                                            crosstalkFSList.set(fsCrosstalkIndex, crosstalkRuta);
                                            fsCrosstalkIndex = demand.getFs();
                                            // Si todos los enlaces tienen el mismo bloque de FS libre, se agrega la ruta a la lista de rutas establecidas.
                                            if (enlacesLibres.size() == ksp.getEdgeList().size()) {
                                                kspPlaced.add(kspaths.get(selectedIndex));
                                                kspPlacedCores.add(kspCores);
                                                k = kspaths.size();
                                                i = capacity;
                                                core = cores;
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            k++;
        }
        EstablishedRoute establisedRoute;
        if (fsIndexBegin != null && !kspPlaced.isEmpty()) {
            establisedRoute = new EstablishedRoute(kspPlaced.get(0).getEdgeList(),
                    fsIndexBegin, demand.getFs(), demand.getLifetime(),
                    demand.getSource(), demand.getDestination(), kspPlacedCores.get(0));
        } else {
            //System.out.println("Bloqueo");
            establisedRoute = null;
        }
        return establisedRoute;

    }

    /**
     * Algoritmo RSA con conmutación de núcleos
     *
     * @param graph Grafo de la topología de la red
     * @param demand Demanda a insertar
     * @param input Configuración del simulador (contiene capacity, cores, maxCrosstalk, fibrasPorGrupo)
     * @param crosstalkPerUnitLength Crosstalk por unidad de longitud (h) de la
     * fibra
     * @return Ruta establecida, o null si hay bloqueo
     */
    public static EstablishedRoute ruteoCoreMultiple(Graph<Integer, Link> graph, Demand demand, Input input, Double crosstalkPerUnitLength) {
        // CAMBIO 3: Calcular FS necesarios por fibra según agrupamiento FSDM
        int fsNecesariosPorFibra = (int) Math.ceil((double) demand.getFs() / input.getFibrasPorGrupo());

        Integer capacity = input.getCapacity();
        Integer cores = input.getCores();
        BigDecimal maxCrosstalk = input.getMaxCrosstalk();
        
        List<GraphPath<Integer, Link>> kspPlaced = new ArrayList<>();
        List<List<Integer>> kspPlacedCores = new ArrayList<>();
        Integer fsIndexBegin = null;
        Integer selectedIndex = 0;
        
        if (TRACE_RUTEO_DETAIL) {
            System.out.println("[TRACE-RUTEO] Iniciando ruteoCoreMultiple:");
            System.out.println("[TRACE-RUTEO]   demand.getFs()=" + demand.getFs() + 
                             ", fibrasPorGrupo=" + input.getFibrasPorGrupo() + 
                             " -> fsNecesariosPorFibra=" + fsNecesariosPorFibra);
            System.out.println("[TRACE-RUTEO]   Grupos disponibles: " + input.getGrupos());
        }
        
        // FSDM: Probar cada grupo de fibras independientemente
        // Una demanda solo puede usar las fibras de UN grupo
        int grupoIndex = 0;
        for (List<Integer> grupo : input.getGrupos()) {
            grupoIndex++;
            if (!kspPlaced.isEmpty()) {
                break; // Ya encontramos un grupo con espacio
            }
            
            if (TRACE_RUTEO_DETAIL) {
                System.out.println("[TRACE-RUTEO] Probando grupo #" + grupoIndex + ": " + grupo);
            }
            
            int k = 0;
            // Iteramos los KSP elegidos
            KShortestSimplePaths<Integer, Link> kspFinder = new KShortestSimplePaths<>(graph);
            List<GraphPath<Integer, Link>> kspaths = kspFinder.getPaths(demand.getSource(), demand.getDestination(), 5);
            
            if (TRACE_RUTEO_DETAIL) {
                System.out.println("[TRACE-RUTEO]   KSP paths encontrados: " + kspaths.size());
            }
            
            while (k < kspaths.size() && kspaths.get(k) != null) {
                fsIndexBegin = null;
                GraphPath<Integer, Link> ksp = kspaths.get(k);
                
                if (TRACE_RUTEO_DETAIL && k == 0) {
                    System.out.println("[TRACE-RUTEO]   Probando KSP #" + (k+1) + ", enlaces: " + ksp.getEdgeList().size());
                    StringBuilder pathStr = new StringBuilder();
                    for (Link link : ksp.getEdgeList()) {
                        if (pathStr.length() > 0) pathStr.append("-");
                        pathStr.append(link.getFrom());
                    }
                    if (!ksp.getEdgeList().isEmpty()) {
                        pathStr.append("-").append(ksp.getEdgeList().get(ksp.getEdgeList().size()-1).getTo());
                    }
                    System.out.println("[TRACE-RUTEO]     Path: " + pathStr);
                }
                
                // Recorremos los FS
                for (int i = 0; i < capacity - demand.getFs(); i++) {
                    List<Integer> kspCores = new ArrayList<>();
                    List<BigDecimal> crosstalkFSList = new ArrayList<>();
                    for (int fsCrosstalkIndex = 0; fsCrosstalkIndex < fsNecesariosPorFibra; fsCrosstalkIndex++) {
                        crosstalkFSList.add(BigDecimal.ZERO);
                    }
                    
                    boolean bloqueDisponibleEnTodosEnlaces = true;
                    int linkIndexFail = -1;
                    String motivoFallo = "";
                    
                    // FSDM: Verificar que el bloque esté disponible en TODAS las fibras del grupo en TODOS los enlaces
                    int linkIndex = 0;
                    for (Link link : ksp.getEdgeList()) {
                        boolean enlaceOk = true;
                        
                        if (i >= capacity - fsNecesariosPorFibra) {
                            enlaceOk = false;
                            if (linkIndexFail == -1) {
                                linkIndexFail = linkIndex;
                                motivoFallo = "FS fuera de rango (i=" + i + " >= capacity-fsNecesariosPorFibra=" + (capacity-fsNecesariosPorFibra) + ")";
                            }
                        } else {
                            // Verificar TODAS las fibras del grupo en este enlace
                            for (int core : grupo) {
                                List<FrequencySlot> bloqueFS = link.getCores().get(core).getFrequencySlots().subList(i, i + fsNecesariosPorFibra);
                                
                                if (!isFSBlockFree(bloqueFS)) {
                                    enlaceOk = false;
                                    if (linkIndexFail == -1) {
                                        linkIndexFail = linkIndex;
                                        motivoFallo = "FS ocupados (link " + link.getFrom() + "-" + link.getTo() + ", core " + core + ", fs " + i + "-" + (i+fsNecesariosPorFibra-1) + ")";
                                    }
                                    break;
                                }
                                
                                if (!isFsBlockCrosstalkFree(bloqueFS, maxCrosstalk, crosstalkFSList)) {
                                    enlaceOk = false;
                                    if (linkIndexFail == -1) {
                                        linkIndexFail = linkIndex;
                                        motivoFallo = "Crosstalk excedido (link " + link.getFrom() + "-" + link.getTo() + ", core " + core + ")";
                                    }
                                    break;
                                }
                                
                                if (!isNextToCrosstalkFreeCores(link, maxCrosstalk, core, i, fsNecesariosPorFibra, crosstalkPerUnitLength)) {
                                    enlaceOk = false;
                                    if (linkIndexFail == -1) {
                                        linkIndexFail = linkIndex;
                                        motivoFallo = "Crosstalk vecinos excedido (link " + link.getFrom() + "-" + link.getTo() + ", core " + core + ")";
                                    }
                                    break;
                                }
                            }
                        }
                        
                        if (enlaceOk) {
                            // Este enlace tiene el bloque disponible en TODAS las fibras del grupo
                            // Añadir TODAS las fibras del grupo para este enlace
                            for (int core : grupo) {
                                kspCores.add(core);
                            }
                            
                            // Actualizar crosstalk acumulado (aunque en FSDM se bypassea)
                            for (int core : grupo) {
                                for (int crosstalkFsListIndex = 0; crosstalkFsListIndex < crosstalkFSList.size(); crosstalkFsListIndex++) {
                                    BigDecimal crosstalkRuta = crosstalkFSList.get(crosstalkFsListIndex);
                                    crosstalkRuta = crosstalkRuta.add(Utils.toDB(Utils.XT(Utils.getCantidadVecinos(core), crosstalkPerUnitLength, link.getDistance())));
                                    crosstalkFSList.set(crosstalkFsListIndex, crosstalkRuta);
                                }
                            }
                        } else {
                            bloqueDisponibleEnTodosEnlaces = false;
                            break; // Este grupo no funciona para esta posición FS
                        }
                        linkIndex++;
                    }
                    
                    // Si TODOS los enlaces tienen el bloque disponible en TODAS las fibras del grupo
                    if (bloqueDisponibleEnTodosEnlaces && kspCores.size() == ksp.getEdgeList().size() * grupo.size()) {
                        kspPlaced.add(ksp);
                        kspPlacedCores.add(kspCores);
                        fsIndexBegin = i;
                        selectedIndex = k;
                        k = kspaths.size(); // Salir del loop de paths
                        i = capacity; // Salir del loop de FS
                        break;
                    } else if (TRACE_RUTEO_DETAIL && k == 0 && i == 0) {
                        // Solo reportar fallo del primer intento de FS en el primer KSP
                        if (!bloqueDisponibleEnTodosEnlaces) {
                            System.out.println("[TRACE-RUTEO]     Fallo en fs=0: " + motivoFallo);
                        } else if (kspCores.size() != ksp.getEdgeList().size() * grupo.size()) {
                            System.out.println("[TRACE-RUTEO]     Fallo en fs=0: Tamaño incorrecto kspCores (" + 
                                             kspCores.size() + " != " + (ksp.getEdgeList().size() * grupo.size()) + 
                                             " = " + ksp.getEdgeList().size() + " enlaces * " + grupo.size() + " fibras/grupo)");
                        }
                    }
                }
                k++;
            }
        }
        EstablishedRoute establisedRoute;
        if (fsIndexBegin != null && !kspPlaced.isEmpty()) {
            // FSDM: fsWidth = fsNecesariosPorFibra, originalDemandFs = demand.getFs(), fibrasPorGrupo explícito
            establisedRoute = new EstablishedRoute(kspPlaced.get(0).getEdgeList(),
                    fsIndexBegin, fsNecesariosPorFibra, demand.getLifetime(),
                    demand.getSource(), demand.getDestination(), kspPlacedCores.get(0), demand.getFs(), input.getFibrasPorGrupo());
        } else {
            //System.out.println("Bloqueo");
            establisedRoute = null;
        }
        return establisedRoute;

    }

    public static Boolean isFSBlockFree(List<FrequencySlot> bloqueFS) {
        for (FrequencySlot fs : bloqueFS) {
            if (!fs.isFree()) {
                return false;
            }
        }
        return true;
    }

    public static Boolean isCrosstalkFree(FrequencySlot fs, BigDecimal maxCrosstalk, BigDecimal crosstalkRuta) {
        BigDecimal crosstalkActual = crosstalkRuta.add(fs.getCrosstalk());
        return crosstalkActual.compareTo(maxCrosstalk) <= 0;
    }

    public static Boolean isFsBlockCrosstalkFree(List<FrequencySlot> fss, BigDecimal maxCrosstalk, List<BigDecimal> crosstalkRuta) {
        for (int i = 0; i < fss.size(); i++) {
            BigDecimal crosstalkActual = crosstalkRuta.get(i).add(fss.get(i).getCrosstalk());
            if (crosstalkActual.compareTo(maxCrosstalk) > 0) {
                return false;
            }
        }
        return true;
    }

    public static Boolean isNextToCrosstalkFreeCores(Link link, BigDecimal maxCrosstalk, Integer core, Integer fsIndexBegin, Integer fsWidth, Double crosstalkPerUnitLength) {
        // FSDM: Si el crosstalk es despreciable (fibras físicamente aisladas), omitir verificación
        if (crosstalkPerUnitLength < FSDM_CROSSTALK_THRESHOLD) {
            return true;
        }

        // SDM: Verificación de crosstalk inter-core (topología de cores adyacentes)
        List<Integer> vecinos = Utils.getCoreVecinos(core);
        for (Integer coreVecino : vecinos) {
            for (Integer i = fsIndexBegin; i < fsIndexBegin + fsWidth; i++) {
                FrequencySlot fsVecino = link.getCores().get(coreVecino).getFrequencySlots().get(i);
                if (!fsVecino.isFree()) {
                    BigDecimal crosstalkASumar = Utils.toDB(Utils.XT(Utils.getCantidadVecinos(core), crosstalkPerUnitLength, link.getDistance()));
                    BigDecimal crosstalk = fsVecino.getCrosstalk().add(crosstalkASumar);
                    //BigDecimal crosstalkDB = Utils.toDB(crosstalk.doubleValue());
                    if (crosstalk.compareTo(maxCrosstalk) >= 0) {
                        return false;
                    }
                }
            }
        }
        return true;
    }
}
