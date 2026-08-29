/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package py.una.pol.simulador.eon.utils;

import java.math.BigDecimal;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.*;
import java.util.stream.Collectors;
import org.jgrapht.Graph;
import org.jgrapht.GraphPath;
import org.jgrapht.alg.shortestpath.KShortestSimplePaths;
import py.una.pol.simulador.eon.models.*;
import py.una.pol.simulador.eon.rsa.Algorithms;

/**
 *
 * @author Rafael Ricardo Mendoza
 */
public class Defragmenter {

    // ========== VALIDACIÓN DE INVARIANTES FSDM ==========
    private static class ValidationReport {
        boolean allPassed = true;
        List<String> failures = new ArrayList<>();
        
        void fail(String message) {
            allPassed = false;
            failures.add("❌ FAIL: " + message);
        }
        
        void pass(String message) {
            // Solo reportamos fallos
        }
    }
    
    private static final boolean ENABLE_VALIDATION = true;
    private static ValidationReport globalReport = new ValidationReport();
    
    // ========== INSTRUMENTACIÓN TEMPORAL PARA DEBUGGING DE ROLLBACK ==========
    private static final boolean TRACE_SLOT = false;  // Deshabilitado para enfocarse en validación
    private static final int TRACE_LINK_FROM = 8;
    private static final int TRACE_LINK_TO = 11;
    private static final int TRACE_CORE = 2;
    private static final boolean TRACE_REINSERTION_FLOW = true;  // Rastrear flujo de reinserción
    private static int reinsertionTraceCounter = 0;  // Limitar a primer caso
    private static boolean TRACE_INTENTO_ASSIGN = true;  // Trace detallado de intentarAsignarConCoresFijos
    private static final int TRACE_FS = 211;
    private static int traceEventCounter = 0;
    
    private static final boolean TRACE_ROLLBACK_VALIDATION = true;
    private static int rollbackCounter = 0;
    
    private static void traceSlot(String operation, Link link, int core, int fs, 
                                   boolean free, int lifetime, String context) {
        if (!TRACE_SLOT) return;
        if (link.getFrom() != TRACE_LINK_FROM || link.getTo() != TRACE_LINK_TO) return;
        if (core != TRACE_CORE || fs != TRACE_FS) return;
        
        traceEventCounter++;
        System.out.println(String.format("[TRACE-%04d] %s | link %d-%d core %d fs %d | free=%s lifetime=%d | %s",
            traceEventCounter, operation, link.getFrom(), link.getTo(), core, fs, free, lifetime, context));
    }
    
    // Helper para capturar estado del slot antes de una operación
    private static void captureSlotStateBefore(Graph<Integer, Link> graph, String operation, String context) {
        if (!TRACE_SLOT) return;
        Link link = findGraphLink(graph, TRACE_LINK_FROM, TRACE_LINK_TO);
        if (link != null) {
            FrequencySlot slot = link.getCores().get(TRACE_CORE).getFrequencySlots().get(TRACE_FS);
            traceSlot(operation + "-BEFORE", link, TRACE_CORE, TRACE_FS,
                     slot.isFree(), slot.getLifetime(), context);
        }
    }
    
    // Helper para capturar estado del slot después de una operación
    private static void captureSlotStateAfter(Graph<Integer, Link> graph, String operation, String context) {
        if (!TRACE_SLOT) return;
        Link link = findGraphLink(graph, TRACE_LINK_FROM, TRACE_LINK_TO);
        if (link != null) {
            FrequencySlot slot = link.getCores().get(TRACE_CORE).getFrequencySlots().get(TRACE_FS);
            traceSlot(operation + "-AFTER", link, TRACE_CORE, TRACE_FS,
                     slot.isFree(), slot.getLifetime(), context);
        }
    }
    
    /* ===========================================================
   DESFRAGMENTACIÓN GUIADA POR BFR maximo(top-1 y top-3 sets con menos conflictos)
   - Núcleos: los de mayor BFR por enlace (fijos)
   - Ventanas: probamos todas y ordenamos por #conflictos
=========================================================== */
    public static boolean DFbFRmax(
            Demand demandaBloqueada,
            Graph<Integer, Link> graph,
            List<EstablishedRoute> establishedRoutes,
            Input input,
            double crosstalkPerUnitLength, int profundidad) {

        int capacity = input.getCapacity();
        int cores = input.getCores();
        BigDecimal maxCrosstalk = input.getMaxCrosstalk();

        // 1) Camino de la demanda bloqueada
        List<Link> pathLinks = getBlockedDemandPath(demandaBloqueada, graph);
        if (pathLinks.isEmpty()) {
            log("No hay camino para la demanda bloqueada ID: " + demandaBloqueada.getId());
            return false;
        }

        // 2) Núcleo de mayor BFR por enlace (fijos)
        List<Integer> mejorCoresPorLink = new ArrayList<>();
        for (Link link : pathLinks) {
            int mejorCore = coreConMayorBFR(link, cores);
            mejorCoresPorLink.add(mejorCore);
        }

        // 3) Construir todos los candidatos de ventana y ordenarlos por #conflictos asc
        int fs = demandaBloqueada.getFs();
        int maxStart = capacity - fs;
        List<VentanaCandidata> candidatos = new ArrayList<>();

        for (int start = 0; start <= maxStart; start++) {
            Set<EstablishedRoute> conflicts = conflictosParaVentana(
                    pathLinks, mejorCoresPorLink, start, fs, establishedRoutes);
            candidatos.add(new VentanaCandidata(start, mejorCoresPorLink, conflicts));
        }

        if (candidatos.isEmpty()) {
            log("No se pudo evaluar ninguna ventana FS.");
            return false;
        }

        // Ordenar por tamaño de conflictSet (menor primero). Empate: preferir ventana más “baja” (start menor)
        candidatos.sort((c1, c2) -> {
            int cmp = Integer.compare(c1.conflictSet.size(), c2.conflictSet.size());
            return (cmp != 0) ? cmp : Integer.compare(c1.start, c2.start);
        });

        // Tomamos top-1 y en caso de ser top-3 (o menos si no hay) y las intentamos en orden
        int intentos = Math.min(profundidad, candidatos.size());
        for (int intento = 0; intento < intentos; intento++) {

            VentanaCandidata cand = candidatos.get(intento);
            int mejorStart = cand.start;
            List<Integer> coresPorLinkElegidos = cand.coresPorLink;     // mismos núcleos de mayor BFR por enlace
            Set<EstablishedRoute> mejorConflictSet = cand.conflictSet;

            log("Iniciando desfragmentación para demanda ID: " + demandaBloqueada.getId()
                    + " | intento " + (intento + 1) + " de " + intentos
                    + " | start=" + mejorStart
                    + " | rutas conflictivas: " + mejorConflictSet.size());

            // 4) Desasignar conflictos → insertar demanda → reinsertar conflictos
            Map<EstablishedRoute, EstablishedRoute> backups = createBackups(new ArrayList<>(mejorConflictSet));
            List<EstablishedRoute> desasignadas = new ArrayList<>();
            Map<EstablishedRoute, EstablishedRoute> moved = new LinkedHashMap<>();

            try {
                // 4.1) Desasignar rutas conflictivas seleccionadas
                for (EstablishedRoute r : mejorConflictSet) {
                    Utils.deallocateFs(graph, r, crosstalkPerUnitLength);
                    desasignadas.add(r);
                }

                // 4.2) Intentar instalar la demanda con FS/cores fijos (puede fallar por disponibilidad/XT)
                EstablishedRoute nueva = intentarAsignarConCoresFijos(
                        demandaBloqueada, pathLinks, coresPorLinkElegidos, mejorStart,
                        graph, maxCrosstalk, crosstalkPerUnitLength, cores, input);

                if (nueva == null) {
                    // Rollback simple de las conflictivas y probamos el siguiente candidato
                    for (EstablishedRoute r : desasignadas) {
                        restoreSingleRoute(graph, backups.get(r));
                    }
                    log("Intento " + (intento + 1) + ": no se pudo asignar la demanda en la ventana/cores elegidos.");
                    continue; // ← probar siguiente candidato
                }

                // ====== NUEVO: agregar la nueva ruta a establishedRoutes ======
                addRouteToList(establishedRoutes, nueva);

                // 4.3) Reinsertar rutas conflictivas (una por una)
                boolean falloReinsercion = false;

                for (EstablishedRoute r : mejorConflictSet) {
                    EstablishedRoute backup = backups.get(r);
                    Demand d = demandFromRoute(r);

                    EstablishedRoute re = Algorithms.ruteoCoreMultiple(
                            graph, d, input, crosstalkPerUnitLength);

                    if (re == null || re.getFsIndexBegin() == -1) {
                        // ❌ Falló reinserción de esta ruta: rollback completo de este intento
                        // FASE 1: Desasignar nueva ruta
                        Utils.deallocateFs(graph, nueva, crosstalkPerUnitLength); // quitar demanda nueva
                        removeRouteFromList(establishedRoutes, nueva);

                        // FASE 2: Desasignar TODAS las rutas reinsertadas
                        for (Map.Entry<EstablishedRoute, EstablishedRoute> e : moved.entrySet()) {
                            EstablishedRoute reinsertada = e.getValue();
                            Utils.deallocateFs(graph, reinsertada, crosstalkPerUnitLength);
                        }

                        // FASE 3: Restaurar TODOS los backups (no pueden ser sobrescritos)
                        // Restaurar la que falló
                        restoreSingleRoute(graph, backup);

                        // Restaurar rutas reinsertadas
                        for (Map.Entry<EstablishedRoute, EstablishedRoute> e : moved.entrySet()) {
                            EstablishedRoute original = e.getKey();
                            restoreSingleRoute(graph, backups.get(original));
                        }

                        // Restaurar las restantes desasignadas que aún no se reinsertaron
                        for (EstablishedRoute rRest : mejorConflictSet) {
                            if (!moved.containsKey(rRest) && rRest != r) {
                                restoreSingleRoute(graph, backups.get(rRest));
                            }
                        }

                        // FASE 4: Actualizar establishedRoutes
                        for (Map.Entry<EstablishedRoute, EstablishedRoute> e : moved.entrySet()) {
                            EstablishedRoute original = e.getKey();
                            EstablishedRoute reinsertada = e.getValue();
                            replaceRouteInList(establishedRoutes, reinsertada, original);
                        }

                        log("Intento " + (intento + 1) + ": rollback por fallo al reinsertar conflictos.");
                        falloReinsercion = true;
                        break; // salimos del for de reinsertar
                    } else {
                        // ✅ Asignar la nueva ruta re-enrutada al grafo y registrar para posible rollback
                        Utils.assignFs(graph, re, crosstalkPerUnitLength);
                        moved.put(r, re);
                        // ====== NUEVO: reemplazar original por reinsertada en establishedRoutes ======
                        replaceRouteInList(establishedRoutes, r, re);
                    }
                }

                if (falloReinsercion) {
                    // Ir al siguiente candidato
                    continue;
                }

                // ✅ Éxito total en este intento
                if (profundidad == 1) {
                    metricsBFRmax1.conteoExitos++;
                    metricsBFRmax1.routesMoved += moved.size(); // ← NUEVO: acumula cuántas rutas se reconfiguraron
                } else {
                    metricsBFRmax3.conteoExitos++;
                    metricsBFRmax3.routesMoved += moved.size();
                }
                log("Desfragmentación exitosa para demanda ID: " + demandaBloqueada.getId()
                        + " en intento " + (intento + 1) + ".");
                return true;

            } finally {
                if (DEBUG) {
                    if (profundidad == 1) {
                        printMetricsBFRmax1();
                    } else {

                    }
                    printMetricsBFRmax3();
                }
            }
        }

        // Si llegamos aquí, fallaron los mejores candidatos
        if (profundidad == 1) {
            metricsBFRmax1.conteoFallido++;
            log("No fue posible desfragmentar (fallo el mejor sets de conflicto).");
        } else {
            metricsBFRmax3.conteoFallido++;
            log("No fue posible desfragmentar (fallaron los 3 mejores sets de conflicto).");
        }

        return false;
    }

    /////////////////////////////
    /* ===========================================================
   DESFRAGMENTACIÓN GUIADA POR BFR (top-1 y top-3 sets con menos conflictos)
   - Núcleos: los de menor BFR por enlace (fijos)
   - Ventanas: probamos todas y ordenamos por #conflictos

=========================================================== */
    public static boolean DFbFRmin(
            Demand demandaBloqueada,
            Graph<Integer, Link> graph,
            List<EstablishedRoute> establishedRoutes,
            Input input,
            double crosstalkPerUnitLength, int profundidad) {

        int capacity = input.getCapacity();
        int cores = input.getCores();
        BigDecimal maxCrosstalk = input.getMaxCrosstalk();

        // 1) Camino de la demanda bloqueada
        List<Link> pathLinks = getBlockedDemandPath(demandaBloqueada, graph);
        if (pathLinks.isEmpty()) {
            log("No hay camino para la demanda bloqueada ID: " + demandaBloqueada.getId());
            return false;
        }

        // 2) Núcleo de menor BFR por enlace (fijos)
        List<Integer> mejorCoresPorLink = new ArrayList<>();
        for (Link link : pathLinks) {
            int mejorCore = coreConMenorBFR(link, cores);
            mejorCoresPorLink.add(mejorCore);
        }

        // 3) Construir todos los candidatos de ventana y ordenarlos por #conflictos asc
        int fs = demandaBloqueada.getFs();
        int maxStart = capacity - fs;
        List<VentanaCandidata> candidatos = new ArrayList<>();

        for (int start = 0; start <= maxStart; start++) {
            Set<EstablishedRoute> conflicts = conflictosParaVentana(
                    pathLinks, mejorCoresPorLink, start, fs, establishedRoutes);
            candidatos.add(new VentanaCandidata(start, mejorCoresPorLink, conflicts));
        }

        if (candidatos.isEmpty()) {
            log("No se pudo evaluar ninguna ventana FS.");
            return false;
        }

        // Ordenar por tamaño de conflictSet (menor primero). Empate: preferir ventana más “baja” (start menor)
        candidatos.sort((c1, c2) -> {
            int cmp = Integer.compare(c1.conflictSet.size(), c2.conflictSet.size());
            return (cmp != 0) ? cmp : Integer.compare(c1.start, c2.start);
        });

        // Tomamos top-1 o top-3 en caso que sea(o menos si no hay) y las intentamos en orden
        int intentos = Math.min(profundidad, candidatos.size());
        for (int intento = 0; intento < intentos; intento++) {

            VentanaCandidata cand = candidatos.get(intento);
            int mejorStart = cand.start;
            List<Integer> coresPorLinkElegidos = cand.coresPorLink;     // mismos núcleos de mayor BFR por enlace
            Set<EstablishedRoute> mejorConflictSet = cand.conflictSet;

            log("Iniciando desfragmentación para demanda ID: " + demandaBloqueada.getId()
                    + " | intento " + (intento + 1) + " de " + intentos
                    + " | start=" + mejorStart
                    + " | rutas conflictivas: " + mejorConflictSet.size());

            // 4) Desasignar conflictos → insertar demanda → reinsertar conflictos
            Map<EstablishedRoute, EstablishedRoute> backups = createBackups(new ArrayList<>(mejorConflictSet));
            List<EstablishedRoute> desasignadas = new ArrayList<>();
            Map<EstablishedRoute, EstablishedRoute> moved = new LinkedHashMap<>();

            try {
                // 4.1) Desasignar rutas conflictivas seleccionadas
                for (EstablishedRoute r : mejorConflictSet) {
                    Utils.deallocateFs(graph, r, crosstalkPerUnitLength);
                    desasignadas.add(r);
                }

                // 4.2) Intentar instalar la demanda con FS/cores fijos (puede fallar por disponibilidad/XT)
                EstablishedRoute nueva = intentarAsignarConCoresFijos(
                        demandaBloqueada, pathLinks, coresPorLinkElegidos, mejorStart,
                        graph, maxCrosstalk, crosstalkPerUnitLength, cores, input);

                if (nueva == null) {
                    // Rollback simple de las conflictivas y probamos el siguiente candidato
                    for (EstablishedRoute r : desasignadas) {
                        restoreSingleRoute(graph, backups.get(r));
                    }
                    log("Intento " + (intento + 1) + ": no se pudo asignar la demanda en la ventana/cores elegidos.");
                    continue; // ← probar siguiente candidato
                }

                // ====== NUEVO: agregar la nueva ruta a establishedRoutes ======
                addRouteToList(establishedRoutes, nueva);

                // 4.3) Reinsertar rutas conflictivas (una por una)
                boolean falloReinsercion = false;

                for (EstablishedRoute r : mejorConflictSet) {
                    EstablishedRoute backup = backups.get(r);
                    Demand d = demandFromRoute(r);

                    EstablishedRoute re = Algorithms.ruteoCoreMultiple(
                            graph, d, input, crosstalkPerUnitLength);

                    if (re == null || re.getFsIndexBegin() == -1) {
                        // ❌ Falló reinserción de esta ruta: rollback completo de este intento
                        // FASE 1: Desasignar nueva ruta
                        Utils.deallocateFs(graph, nueva, crosstalkPerUnitLength); // quitar demanda nueva
                        removeRouteFromList(establishedRoutes, nueva);

                        // FASE 2: Desasignar TODAS las rutas reinsertadas
                        for (Map.Entry<EstablishedRoute, EstablishedRoute> e : moved.entrySet()) {
                            EstablishedRoute reinsertada = e.getValue();
                            Utils.deallocateFs(graph, reinsertada, crosstalkPerUnitLength);
                        }

                        // FASE 3: Restaurar TODOS los backups (no pueden ser sobrescritos)
                        // Restaurar la que falló
                        restoreSingleRoute(graph, backup);

                        // Restaurar rutas reinsertadas
                        for (Map.Entry<EstablishedRoute, EstablishedRoute> e : moved.entrySet()) {
                            EstablishedRoute original = e.getKey();
                            restoreSingleRoute(graph, backups.get(original));
                        }

                        // Restaurar las restantes desasignadas que aún no se reinsertaron
                        for (EstablishedRoute rRest : mejorConflictSet) {
                            if (!moved.containsKey(rRest) && rRest != r) {
                                restoreSingleRoute(graph, backups.get(rRest));
                            }
                        }

                        // FASE 4: Actualizar establishedRoutes
                        for (Map.Entry<EstablishedRoute, EstablishedRoute> e : moved.entrySet()) {
                            EstablishedRoute original = e.getKey();
                            EstablishedRoute reinsertada = e.getValue();
                            replaceRouteInList(establishedRoutes, reinsertada, original);
                        }

                        log("Intento " + (intento + 1) + ": rollback por fallo al reinsertar conflictos.");
                        falloReinsercion = true;
                        break; // salimos del for de reinsertar
                    } else {
                        // ✅ Asignar la nueva ruta re-enrutada al grafo y registrar para posible rollback
                        Utils.assignFs(graph, re, crosstalkPerUnitLength);
                        moved.put(r, re);

                        // ====== NUEVO: reemplazar original por reinsertada en establishedRoutes ======
                        replaceRouteInList(establishedRoutes, r, re);
                    }
                }

                if (falloReinsercion) {
                    // Ir al siguiente candidato
                    continue;
                }

                // ✅ Éxito total en este intento
                if (profundidad == 1) {
                    metricsBFRmin1.conteoExitos++;
                    metricsBFRmin1.routesMoved += moved.size(); // acumula cuántas rutas se reconfiguraron
                } else {
                    metricsBFRmin3.conteoExitos++;
                    metricsBFRmin3.routesMoved += moved.size(); // acumula cuántas rutas se reconfiguraron
                }
                log("Desfragmentación exitosa para demanda ID: " + demandaBloqueada.getId()
                        + " en intento " + (intento + 1) + ".");
                return true;

            } finally {
                if (DEBUG) {
                    if (profundidad == 1) {
                        printMetricsBFRmin1();
                    } else {
                        printMetricsBFRmin3();
                    }
                }
            }
        }

        // Si llegamos aquí, fallaron los mejores candidatos
        if (profundidad == 1) {
            metricsBFRmin1.conteoFallido++;
            log("No fue posible desfragmentar (fallo el mejor sets de conflicto).");
        } else {
            metricsBFRmin3.conteoFallido++;
            log("No fue posible desfragmentar (fallaron los 3 mejores sets de conflicto).");
        }
        return false;
    }

    /////////////////////////////////////////////////////
    /* ===========================================================
   DESFRAGMENTACIÓN "FULL RUTEO MIN" (top-1 y top-3 candidatos)
   - Por ventana: para cada enlace elige el core con MENOS conflictos
     (evaluarVentanaMinSuma); sumar mínimos; ordenar por suma (tie: start).

=========================================================== */
    public static boolean DFfullRuteoMin(
            Demand demandaBloqueada,
            Graph<Integer, Link> graph,
            List<EstablishedRoute> establishedRoutes,
            Input input,
            double crosstalkPerUnitLenght, int profundidad) {

        // Resetear contador de trace para capturar el primer caso de DFfullRuteoMin
        reinsertionTraceCounter = 0;
        
        int capacity = input.getCapacity();
        int cores = input.getCores();
        BigDecimal maxCrosstalk = input.getMaxCrosstalk();

        // 1) Camino de la demanda
        List<Link> pathLinks = getBlockedDemandPath(demandaBloqueada, graph);
        if (pathLinks == null || pathLinks.isEmpty()) {
            log("No hay camino para la demanda bloqueada ID: " + demandaBloqueada.getId());
            return false;
        }

        // 2) Guards de tamaños usando el primer enlace del path
        int fs = demandaBloqueada.getFs();
        int slotsSize = pathLinks.get(0).getCores().get(0).getFrequencySlots().size();
        
        // FSDM: Calcular width real que se usará en la asignación
        int fibrasPorGrupo = (input.getFibrasPorGrupo() != null) ? input.getFibrasPorGrupo() : 1;
        int widthReal = (int) Math.ceil((double) fs / fibrasPorGrupo);
        
        int maxStart = slotsSize - widthReal;
        if (widthReal <= 0 || maxStart < 0) {
            log("No se pudo evaluar ninguna ventana FS (fs/slots inválidos).");
            return false;
        }

// 3) Construir candidatos (min-suma por ventana) usando widthReal

List<VentanaMinSum> candidatos = new ArrayList<>();

        for (int start = 0; start <= maxStart; start++) {
            VentanaMinSum cand = evaluarVentanaMinSuma(pathLinks, start, widthReal, cores, input.getGrupos(), establishedRoutes);
            // FILTRO: Solo considerar ventanas con al menos 1 conflicto (para desfragmentar)
            if (cand != null && cand.sumaMinConflictos > 0) {
                candidatos.add(cand);
            }
        }
        if (candidatos.isEmpty()) {
            log("No se pudo evaluar ninguna ventana FS con conflictos para desfragmentar.");
            return false;
        }

// ordenar por sumaMinConflictos asc; en empate, start más chico
        candidatos.sort((a, b) -> {
            int cmp = Integer.compare(a.sumaMinConflictos, b.sumaMinConflictos);
            return (cmp != 0) ? cmp : Integer.compare(a.start, b.start);
        });

        // DEBUG: Ver candidatos ordenados
        if (TRACE_REINSERTION_FLOW && reinsertionTraceCounter == 0) {
            System.out.println("\n[DEBUG-CANDIDATOS] Total de candidatos: " + candidatos.size());
            for (int i = 0; i < Math.min(5, candidatos.size()); i++) {
                VentanaMinSum c = candidatos.get(i);
                System.out.println("[DEBUG-CANDIDATOS] #" + i + ": start=" + c.start + 
                                 ", suma=" + c.sumaMinConflictos + 
                                 ", #conflictos=" + c.conflictSet.size() +
                                 ", cores=" + c.coresPorLink);
            }
        }

// 4) Intentar top-k según profundidad
        int intentos = Math.min(profundidad, candidatos.size());
        for (int intento = 0; intento < intentos; intento++) {
            VentanaMinSum best = candidatos.get(intento);

            int bestStart = best.start;
            List<Integer> bestCoresPorLink = best.coresPorLink;
            Set<EstablishedRoute> bestConflictSet = best.conflictSet;
            
            if (TRACE_REINSERTION_FLOW && reinsertionTraceCounter == 0) {
                System.out.println("\n[DEBUG-INTENTO] ===== Intento #" + (intento+1) + "/" + intentos + " =====");
                System.out.println("[DEBUG-INTENTO] start=" + bestStart + ", suma=" + best.sumaMinConflictos);
                System.out.println("[DEBUG-INTENTO] bestConflictSet.size=" + bestConflictSet.size());
                System.out.println("[DEBUG-INTENTO] bestCoresPorLink=" + bestCoresPorLink);
            }
            
            // === DIAGNÓSTICO: Estado ANTES de resolveCurrentReferences ===
            if (TRACE_REINSERTION_FLOW && reinsertionTraceCounter == 0) {
                System.out.println("\n[DIAGNÓSTICO] ===== ANTES de resolveCurrentReferences() =====");
                System.out.println("[DIAGNÓSTICO] bestConflictSet tiene " + bestConflictSet.size() + " rutas:");
                for (EstablishedRoute old : bestConflictSet) {
                    System.out.println("[DIAGNÓSTICO]   OLD: " + old.getFrom() + "->" + old.getTo() + 
                                     " [ID:" + Integer.toHexString(System.identityHashCode(old)) + "]" +
                                     " path:" + pathToString(old.getPath()) +
                                     " cores:" + old.getPathCores() +
                                     " fs:" + old.getFsIndexBegin() + "-" + (old.getFsIndexBegin() + old.getFsWidth() - 1));
                }
            }
            
            // FIX: Resolver referencias obsoletas a instancias actuales que se solapen con la ventana
            Set<EstablishedRoute> resolvedConflictSet = resolveCurrentReferences(bestConflictSet, establishedRoutes, bestStart, widthReal);
            
            // === DIAGNÓSTICO: Estado DESPUÉS de resolveCurrentReferences ===
            if (TRACE_REINSERTION_FLOW && reinsertionTraceCounter == 0) {
                System.out.println("\n[DIAGNÓSTICO] ===== DESPUÉS de resolveCurrentReferences() =====");
                System.out.println("[DIAGNÓSTICO] resolvedConflictSet tiene " + resolvedConflictSet.size() + " rutas:");
                for (EstablishedRoute current : resolvedConflictSet) {
                    System.out.println("[DIAGNÓSTICO]   CURRENT: " + current.getFrom() + "->" + current.getTo() + 
                                     " [ID:" + Integer.toHexString(System.identityHashCode(current)) + "]" +
                                     " path:" + pathToString(current.getPath()) +
                                     " cores:" + current.getPathCores() +
                                     " fs:" + current.getFsIndexBegin() + "-" + (current.getFsIndexBegin() + current.getFsWidth() - 1));
                }
            }

            log("FullRuteoMin (prof=" + profundidad + ") ID " + demandaBloqueada.getId()
                    + " | intento " + (intento + 1) + "/" + intentos
                    + " | start=" + bestStart
                    + " | sumaMinConflictos=" + best.sumaMinConflictos
                    + " | #conflictivas=" + resolvedConflictSet.size());

            Map<EstablishedRoute, EstablishedRoute> backups = createBackups(new ArrayList<>(resolvedConflictSet));
            List<EstablishedRoute> desasignadas = new ArrayList<>();
            Map<EstablishedRoute, EstablishedRoute> moved = new LinkedHashMap<>();

            // ========== VALIDACIÓN: Verificar conflictSet (INVARIANTE 1) ==========
            if (ENABLE_VALIDATION) {
                validateConflictSet(bestConflictSet, pathLinks, bestCoresPorLink, bestStart, fs, globalReport);
            }

            try {
                // 4.1) Desasignar conflictivas
                if (TRACE_REINSERTION_FLOW && reinsertionTraceCounter == 0) {
                    System.out.println("\n[TRACE-DESASIGNAR] Desasignando " + resolvedConflictSet.size() + " rutas conflictivas:");
                    for (EstablishedRoute r : resolvedConflictSet) {
                        System.out.println("[TRACE-DESASIGNAR]   - Ruta " + r.getFrom() + "->" + r.getTo() + 
                                         " cores:" + r.getPathCores() + " fs:[" + r.getFsIndexBegin() + "-" +
                                         (r.getFsIndexBegin() + r.getFsWidth() - 1) + "]");
                    }
                }
                
                for (EstablishedRoute r : resolvedConflictSet) {
                    captureSlotStateBefore(graph, "DEALLOCATE-CONFLICT", 
                                          "Desasignando ruta conflictiva " + r.getFrom() + "->" + r.getTo() +
                                          " cores:" + r.getPathCores() + " fs:" + r.getFsIndexBegin() + "-" +
                                          (r.getFsIndexBegin() + r.getFsWidth() - 1));
                    Utils.deallocateFs(graph, r, crosstalkPerUnitLenght);
                    captureSlotStateAfter(graph, "DEALLOCATE-CONFLICT",
                                         "Desasignó ruta conflictiva " + r.getFrom() + "->" + r.getTo());
                    desasignadas.add(r);
                }

                // 4.2) Insertar la demanda bloqueada
                if (TRACE_REINSERTION_FLOW && reinsertionTraceCounter == 0) {
                    System.out.println("\n[TRACE-ASIGNAR-NUEVA] Intentando asignar demanda bloqueada:");
                    System.out.println("[TRACE-ASIGNAR-NUEVA]   ALGORITMO: DFfullRuteoMin");
                    System.out.println("[TRACE-ASIGNAR-NUEVA]   Demanda: " + demandaBloqueada.getSource() + "->" + demandaBloqueada.getDestination() + " fs=" + demandaBloqueada.getFs());
                    System.out.println("[TRACE-ASIGNAR-NUEVA]   start=" + bestStart + ", widthReal=" + widthReal);
                    System.out.println("[TRACE-ASIGNAR-NUEVA]   pathLinks.size=" + (pathLinks != null ? pathLinks.size() : "null"));
                    System.out.println("[TRACE-ASIGNAR-NUEVA]   bestCoresPorLink: " + bestCoresPorLink);
                    System.out.println("[TRACE-ASIGNAR-NUEVA]   fibrasPorGrupo: " + input.getFibrasPorGrupo());
                    System.out.println("[TRACE-ASIGNAR-NUEVA]   grupos: " + input.getGrupos());
                    // Activar trace detallado
                    TRACE_INTENTO_ASSIGN = true;
                }
                
                EstablishedRoute nueva = intentarAsignarConCoresFijos(
                        demandaBloqueada, pathLinks, bestCoresPorLink, bestStart,
                        graph, maxCrosstalk, crosstalkPerUnitLenght, cores, input);
                
                if (TRACE_REINSERTION_FLOW && reinsertionTraceCounter == 0) {
                    TRACE_INTENTO_ASSIGN = false;
                    if (nueva == null) {
                        System.out.println("[TRACE-ASIGNAR-NUEVA] RESULTADO: FALLO (null)");
                    } else {
                        System.out.println("[TRACE-ASIGNAR-NUEVA] RESULTADO: ÉXITO");
                        System.out.println("[TRACE-ASIGNAR-NUEVA]   Nueva ruta: " + nueva.getFrom() + "->" + nueva.getTo());
                        System.out.println("[TRACE-ASIGNAR-NUEVA]   path: " + pathToString(nueva.getPath()));
                        System.out.println("[TRACE-ASIGNAR-NUEVA]   pathCores: " + nueva.getPathCores());
                        System.out.println("[TRACE-ASIGNAR-NUEVA]   fs: " + nueva.getFsIndexBegin() + "-" + (nueva.getFsIndexBegin() + nueva.getFsWidth() - 1));
                    }
                }

                if (nueva == null) {
                    // rollback simple de slots
                    if (TRACE_ROLLBACK_VALIDATION) {
                        rollbackCounter++;
                        System.out.println("\n[ROLLBACK-" + rollbackCounter + "] ===== INICIO ROLLBACK SIMPLE =====");
                        System.out.println("[ROLLBACK-" + rollbackCounter + "] Demanda: " + demandaBloqueada.getSource() + "->" + demandaBloqueada.getDestination() + 
                                           " slots=" + demandaBloqueada.getFs());
                        System.out.println("[ROLLBACK-" + rollbackCounter + "] Intento: " + (intento + 1) + "/" + profundidad);
                        System.out.println("[ROLLBACK-" + rollbackCounter + "] Causa: No se pudo asignar la demanda en la ventana");
                        System.out.println("[ROLLBACK-" + rollbackCounter + "] Rutas desasignadas: " + desasignadas.size());
                        for (EstablishedRoute r : desasignadas) {
                            EstablishedRoute backup = backups.get(r);
                            System.out.println("[ROLLBACK-" + rollbackCounter + "]   - " + r.getFrom() + "->" + r.getTo() + 
                                             " [ID:" + Integer.toHexString(System.identityHashCode(r)) + "]" +
                                             " backup_cores:" + (backup != null ? backup.getPathCores() : "null") +
                                             " backup_fs:" + (backup != null ? backup.getFsIndexBegin() + "-" + 
                                             (backup.getFsIndexBegin() + backup.getFsWidth() - 1) : "null"));
                        }
                    }
                    
                    for (EstablishedRoute r : desasignadas) {
                        restoreSingleRoute(graph, backups.get(r));
                    }
                    
                    // ========== VALIDACIÓN: Verificar rollback (INVARIANTE 4) ==========
                    if (ENABLE_VALIDATION) {
                        validateRollbackState(graph, backups, establishedRoutes, globalReport, rollbackCounter);
                    }
                    
                    if (TRACE_ROLLBACK_VALIDATION) {
                        System.out.println("[ROLLBACK-" + rollbackCounter + "] ===== FIN ROLLBACK SIMPLE =====");
                    }
                    
                    log("Intento " + (intento + 1)
                            + ": no se pudo asignar la demanda en la ventana/cores elegidos.");
                    continue;
                }
                
                captureSlotStateAfter(graph, "ASSIGN-NUEVA",
                                     "Asignó nueva demanda " + nueva.getFrom() + "->" + nueva.getTo() +
                                     " cores:" + nueva.getPathCores() + " fs:" + nueva.getFsIndexBegin() + "-" +
                                     (nueva.getFsIndexBegin() + nueva.getFsWidth() - 1));
                
                // ========== VALIDACIÓN: Verificar pathCores y sobrescrituras (INVARIANTES 6, 7) ==========
                if (ENABLE_VALIDATION) {
                    validatePathCoresStructure(nueva, globalReport);
                }

                // === agregar la nueva ruta a establishedRoutes ===
                addRouteToList(establishedRoutes, nueva);

                // 4.3) Reinsertar conflictivas
                boolean falloReinsercion = false;
                
                if (TRACE_REINSERTION_FLOW && reinsertionTraceCounter == 0 && resolvedConflictSet.size() > 0) {
                    System.out.println("\n" + "=".repeat(80));
                    System.out.println("[TRACE-REINSERTION] ===== FLUJO DE REINSERCIÓN =====");
                    System.out.println("[TRACE-REINSERTION] Demanda bloqueada: " + demandaBloqueada.getSource() + "->" + demandaBloqueada.getDestination() + 
                                       " slots=" + demandaBloqueada.getFs());
                    System.out.println("[TRACE-REINSERTION] Nueva ruta asignada: " + nueva.getFrom() + "->" + nueva.getTo());
                    System.out.println("[TRACE-REINSERTION]   path: " + pathToString(nueva.getPath()));
                    System.out.println("[TRACE-REINSERTION]   pathCores: " + nueva.getPathCores());
                    System.out.println("[TRACE-REINSERTION]   fs: " + nueva.getFsIndexBegin() + "-" + (nueva.getFsIndexBegin() + nueva.getFsWidth() - 1));
                    System.out.println("[TRACE-REINSERTION]   originalDemandFs: " + nueva.getOriginalDemandFs());
                    System.out.println("[TRACE-REINSERTION]   fibrasPorGrupo: " + nueva.getFibrasPorGrupo());
                    System.out.println("[TRACE-REINSERTION] Rutas a reinsertar: " + resolvedConflictSet.size());
                    System.out.println("=".repeat(80) + "\n");
                }
                
                int routeIndex = 0;

                for (EstablishedRoute r : resolvedConflictSet) {
                    EstablishedRoute backup = backups.get(r);
                    Demand d = demandFromRoute(r);
                    
                    if (TRACE_REINSERTION_FLOW && reinsertionTraceCounter == 0) {
                        routeIndex++;
                        System.out.println("\n[TRACE-REINSERTION] --- Ruta conflictiva #" + routeIndex + " de " + resolvedConflictSet.size() + " ---");
                        System.out.println("[TRACE-REINSERTION] ORIGINAL: " + r.getFrom() + "->" + r.getTo());
                        System.out.println("[TRACE-REINSERTION]   path original: " + pathToString(backup.getPath()));
                        System.out.println("[TRACE-REINSERTION]   pathCores original: " + backup.getPathCores());
                        System.out.println("[TRACE-REINSERTION]   fs original: " + backup.getFsIndexBegin() + "-" + 
                                         (backup.getFsIndexBegin() + backup.getFsWidth() - 1));
                        System.out.println("[TRACE-REINSERTION]   originalDemandFs: " + backup.getOriginalDemandFs());
                        System.out.println("[TRACE-REINSERTION]   fibrasPorGrupo: " + backup.getFibrasPorGrupo());
                        System.out.println("[TRACE-REINSERTION] Intentando rerutear con ruteoCoreMultiple...");
                        System.out.println("[TRACE-REINSERTION]   Demand creada: source=" + d.getSource() + 
                                         " dest=" + d.getDestination() + " fs=" + d.getFs() + " lifetime=" + d.getLifetime());
                        
                        // Activar trace detallado en Algorithms para este intento
                        Algorithms.TRACE_RUTEO_DETAIL = true;
                    }

                    EstablishedRoute re = Algorithms.ruteoCoreMultiple(
                            graph, d, input, crosstalkPerUnitLenght);
                    
                    // Desactivar trace detallado
                    if (TRACE_REINSERTION_FLOW && reinsertionTraceCounter == 0) {
                        Algorithms.TRACE_RUTEO_DETAIL = false;
                    }
                    
                    if (TRACE_REINSERTION_FLOW && reinsertionTraceCounter == 0) {
                        if (re == null) {
                            System.out.println("[TRACE-REINSERTION] ❌ RESULTADO: ruteoCoreMultiple retornó NULL");
                            System.out.println("[TRACE-REINSERTION]    Motivo: No se encontró path con FS disponibles");
                            reinsertionTraceCounter++;  // Marcar que ya trazamos un caso
                        } else if (re.getFsIndexBegin() == -1) {
                            System.out.println("[TRACE-REINSERTION] ❌ RESULTADO: ruteoCoreMultiple retornó fsIndexBegin=-1");
                            System.out.println("[TRACE-REINSERTION]    Motivo: Path encontrado pero sin FS asignables");
                            reinsertionTraceCounter++;
                        } else {
                            System.out.println("[TRACE-REINSERTION] ✅ RESULTADO: ruteoCoreMultiple tuvo ÉXITO");
                            System.out.println("[TRACE-REINSERTION]   NUEVA: " + re.getFrom() + "->" + re.getTo());
                            System.out.println("[TRACE-REINSERTION]   path nueva: " + pathToString(re.getPath()));
                            System.out.println("[TRACE-REINSERTION]   pathCores nueva: " + re.getPathCores());
                            System.out.println("[TRACE-REINSERTION]   fs nueva: " + re.getFsIndexBegin() + "-" + 
                                             (re.getFsIndexBegin() + re.getFsWidth() - 1));
                            System.out.println("[TRACE-REINSERTION]   originalDemandFs: " + re.getOriginalDemandFs());
                            System.out.println("[TRACE-REINSERTION]   fibrasPorGrupo: " + re.getFibrasPorGrupo());
                        }
                    }

                    if (re == null || re.getFsIndexBegin() == -1) {
                        // ❌ rollback total de este intento
                        if (TRACE_ROLLBACK_VALIDATION) {
                            rollbackCounter++;
                            System.out.println("\n[ROLLBACK-" + rollbackCounter + "] ===== INICIO ROLLBACK COMPLETO =====");
                            System.out.println("[ROLLBACK-" + rollbackCounter + "] Demanda: " + demandaBloqueada.getSource() + "->" + demandaBloqueada.getDestination() + 
                                               " slots=" + demandaBloqueada.getFs());
                            System.out.println("[ROLLBACK-" + rollbackCounter + "] Intento: " + (intento + 1) + "/" + profundidad);
                            System.out.println("[ROLLBACK-" + rollbackCounter + "] Causa: Falló reinserción de ruta " + r.getFrom() + "->" + r.getTo());
                            System.out.println("[ROLLBACK-" + rollbackCounter + "] Nueva ruta asignada: " + nueva.getFrom() + "->" + nueva.getTo() + 
                                             " cores:" + nueva.getPathCores() + " fs:" + nueva.getFsIndexBegin() + "-" + 
                                             (nueva.getFsIndexBegin() + nueva.getFsWidth() - 1));
                            System.out.println("[ROLLBACK-" + rollbackCounter + "] Rutas en resolvedConflictSet: " + resolvedConflictSet.size());
                            for (EstablishedRoute rc : resolvedConflictSet) {
                                EstablishedRoute backupRc = backups.get(rc);
                                System.out.println("[ROLLBACK-" + rollbackCounter + "]   - " + rc.getFrom() + "->" + rc.getTo() + 
                                                 " [ID:" + Integer.toHexString(System.identityHashCode(rc)) + "]" +
                                                 " backup_cores:" + (backupRc != null ? backupRc.getPathCores() : "null") +
                                                 " backup_fs:" + (backupRc != null ? backupRc.getFsIndexBegin() + "-" + 
                                                 (backupRc.getFsIndexBegin() + backupRc.getFsWidth() - 1) : "null"));
                            }
                            System.out.println("[ROLLBACK-" + rollbackCounter + "] Rutas ya reinsertadas (moved): " + moved.size());
                            for (Map.Entry<EstablishedRoute, EstablishedRoute> me : moved.entrySet()) {
                                System.out.println("[ROLLBACK-" + rollbackCounter + "]   - " + me.getKey().getFrom() + "->" + me.getKey().getTo() + 
                                                 " -> " + me.getValue().getFrom() + "->" + me.getValue().getTo() +
                                                 " cores:" + me.getValue().getPathCores() + " fs:" + me.getValue().getFsIndexBegin() + "-" +
                                                 (me.getValue().getFsIndexBegin() + me.getValue().getFsWidth() - 1));
                            }
                        }
                        
                        captureSlotStateBefore(graph, "ROLLBACK-START", 
                                              "Falló reinserción de ruta " + r.getFrom() + "->" + r.getTo() +
                                              ", iniciando rollback completo");
                        
                        // FASE 1: Desasignar nueva ruta
                        Utils.deallocateFs(graph, nueva, crosstalkPerUnitLenght);
                        captureSlotStateAfter(graph, "ROLLBACK-DEALLOCATE-NUEVA",
                                             "Desasignó nueva ruta");
                        removeRouteFromList(establishedRoutes, nueva);

                        // FASE 2: Desasignar TODAS las rutas reinsertadas
                        for (Map.Entry<EstablishedRoute, EstablishedRoute> e : moved.entrySet()) {
                            EstablishedRoute reinsertada = e.getValue();
                            captureSlotStateBefore(graph, "ROLLBACK-DEALLOCATE-MOVED",
                                                  "Desasignando ruta reinsertada " + reinsertada.getFrom() + "->" + reinsertada.getTo() +
                                                  " cores:" + reinsertada.getPathCores() + " fs:" + reinsertada.getFsIndexBegin() + "-" +
                                                  (reinsertada.getFsIndexBegin() + reinsertada.getFsWidth() - 1));
                            Utils.deallocateFs(graph, reinsertada, crosstalkPerUnitLenght);
                            captureSlotStateAfter(graph, "ROLLBACK-DEALLOCATE-MOVED",
                                                 "Desasignó ruta reinsertada");
                        }

                        // FASE 3: Restaurar TODOS los backups (no pueden ser sobrescritos)
                        // Restaurar la que falló
                        captureSlotStateBefore(graph, "ROLLBACK-RESTORE-FAILED",
                                              "Restaurando ruta que falló " + r.getFrom() + "->" + r.getTo());
                        restoreSingleRoute(graph, backup);
                        captureSlotStateAfter(graph, "ROLLBACK-RESTORE-FAILED",
                                             "Restauró ruta que falló");

                        // Restaurar rutas reinsertadas
                        for (Map.Entry<EstablishedRoute, EstablishedRoute> e : moved.entrySet()) {
                            EstablishedRoute original = e.getKey();
                            captureSlotStateBefore(graph, "ROLLBACK-RESTORE-MOVED",
                                                  "Restaurando ruta original " + original.getFrom() + "->" + original.getTo());
                            restoreSingleRoute(graph, backups.get(original));
                            captureSlotStateAfter(graph, "ROLLBACK-RESTORE-MOVED",
                                                 "Restauró ruta original");
                        }

                        // Restaurar las restantes aún no reinsertadas
                        for (EstablishedRoute rRest : resolvedConflictSet) {
                            if (!moved.containsKey(rRest) && rRest != r) {
                                captureSlotStateBefore(graph, "ROLLBACK-RESTORE-REMAINING",
                                                      "Restaurando ruta no reinsertada " + rRest.getFrom() + "->" + rRest.getTo());
                                restoreSingleRoute(graph, backups.get(rRest));
                                captureSlotStateAfter(graph, "ROLLBACK-RESTORE-REMAINING",
                                                     "Restauró ruta no reinsertada");
                            }
                        }

                        // FASE 4: Actualizar establishedRoutes
                        for (Map.Entry<EstablishedRoute, EstablishedRoute> e : moved.entrySet()) {
                            EstablishedRoute original = e.getKey();
                            EstablishedRoute reinsertada = e.getValue();
                            replaceRouteInList(establishedRoutes, reinsertada, original);
                        }
                        
                        captureSlotStateAfter(graph, "ROLLBACK-END", "Rollback completo");

                        // ========== VALIDACIÓN: Verificar rollback completo (INVARIANTE 4) ==========
                        if (ENABLE_VALIDATION) {
                            validateRollbackState(graph, backups, establishedRoutes, globalReport, rollbackCounter);
                        }
                        
                        if (TRACE_ROLLBACK_VALIDATION) {
                            System.out.println("[ROLLBACK-" + rollbackCounter + "] ===== FIN ROLLBACK COMPLETO =====");
                        }

                        log("Intento " + (intento + 1) + ": rollback por fallo al reinsertar.");
                        falloReinsercion = true;
                        break;
                    } else {
                        // ✅ asignar re-ruteada + actualizar lista activa
                        captureSlotStateBefore(graph, "REINSERT-SUCCESS",
                                              "Asignando ruta reinsertada " + re.getFrom() + "->" + re.getTo() +
                                              " cores:" + re.getPathCores() + " fs:" + re.getFsIndexBegin() + "-" +
                                              (re.getFsIndexBegin() + re.getFsWidth() - 1));
                        Utils.assignFs(graph, re, crosstalkPerUnitLenght);
                        captureSlotStateAfter(graph, "REINSERT-SUCCESS",
                                             "Asignó ruta reinsertada " + re.getFrom() + "->" + re.getTo());
                        moved.put(r, re);
                        replaceRouteInList(establishedRoutes, r, re);
                        
                        // ========== VALIDACIÓN: Verificar pathCores de ruta reinsertada ==========
                        if (ENABLE_VALIDATION) {
                            validatePathCoresStructure(re, globalReport);
                        }
                    }
                }

                if (falloReinsercion) {
                    continue;
                }

                // ✅ Éxito
                if (profundidad == 1) {
                    metricsFullRuteoMin1.conteoExitos++;
                    metricsFullRuteoMin1.routesMoved += moved.size();
                } else {
                    metricsFullRuteoMin3.conteoExitos++;
                    metricsFullRuteoMin3.routesMoved += moved.size();
                }

                log("Desfragmentación exitosa (FullRuteoMin, prof="
                        + profundidad + ") ID " + demandaBloqueada.getId()
                        + " en intento " + (intento + 1) + ".");
                return true;

            } finally {
                if (DEBUG) {
                    if (profundidad == 1) {
                        printMetricsFullRuteoMin1();
                    } else {
                        printMetricsFullRuteoMin3();
                    }
                }
            }
        }

// Si llegamos aquí, fallaron los mejores candidatos
        if (profundidad == 1) {
            metricsFullRuteoMin1.conteoFallido++;
            log("No fue posible desfragmentar (fallaron los mejores candidatos, prof=1).");
        } else {
            metricsFullRuteoMin3.conteoFallido++;
            log("No fue posible desfragmentar (fallaron los " + profundidad + " mejores candidatos).");
        }
        return false;
    }


    /* ===========================================================
     METODOS AUXILIARES
  =========================================================== */
 /* ===========================================================
   BFR por núcleo y selección del núcleo con mayor BFR
=========================================================== */
    private static int coreConMayorBFR(Link link, int cores) {
        double mejorBfr = -1.0;
        int mejorCore = 0;
        for (int c = 0; c < cores; c++) {
            double b = bfrDeCore(link, c);
            if (b > mejorBfr) {
                mejorBfr = b;
                mejorCore = c;
            }
        }
        return mejorCore;
    }

    /* ===========================================================
   BFR por núcleo y selección del núcleo con menor BFR
=========================================================== */
    private static int coreConMenorBFR(Link link, int cores) {
        double menorBfr = Double.MAX_VALUE;  // valor inicial alto
        int mejorCore = 0;
        for (int c = 0; c < cores; c++) {
            double b = bfrDeCore(link, c);
            if (b < menorBfr) {
                menorBfr = b;
                mejorCore = c;
            }
        }
        return mejorCore;
    }

    private static double bfrDeCore(Link link, int core) {
        List<FrequencySlot> slots = link.getCores().get(core).getFrequencySlots();
        int ce = slots.size();
        int sumFSOcupados = 0;
        int maxRachaLibre = 0;
        int rachaLibre = 0;

        for (FrequencySlot fs : slots) {
            if (fs.isFree()) {
                rachaLibre++;
                if (rachaLibre > maxRachaLibre) {
                    maxRachaLibre = rachaLibre;
                }
            } else {
                sumFSOcupados++;
                rachaLibre = 0;
            }
        }

        // Evitar división por cero
        if (ce == sumFSOcupados) {
            return 1.0; // todo ocupado → máxima fragmentación
        }

        return 1.0 - ((double) maxRachaLibre / (double) (ce - sumFSOcupados));
    }

    /* ===========================================================
   Contar conflictos para una ventana FS fija y cores fijos
=========================================================== */
    private static Set<EstablishedRoute> conflictosParaVentana(
            List<Link> pathLinks,
            List<Integer> coresPorLink,
            int start, int width,
            List<EstablishedRoute> establishedRoutes) {

        Set<EstablishedRoute> conflicts = new HashSet<>();

        for (int li = 0; li < pathLinks.size(); li++) {
            Link link = pathLinks.get(li);
            int core = coresPorLink.get(li);

            // Buscar todas las rutas que pasan por este link
            for (EstablishedRoute r : establishedRoutes) {
                //idx seria la posicion de enlace de la ruta que coincide con el link de la demanda bloqueada.
                int idx = posicionDelEnlaceEnRuta(r, link);
                if (idx < 0) {
                    continue;
                }

                // FSDM Fix: verificar si la ruta usa el core en CUALQUIER fibra del grupo para este enlace
                int fibrasPorGrupo = r.getFibrasPorGrupo();
                boolean usaEsteCore = false;
                for (int f = 0; f < fibrasPorGrupo; f++) {
                    Integer coreRuta = r.getPathCores().get(idx * fibrasPorGrupo + f);
                    if (coreRuta != null && coreRuta == core) {
                        usaEsteCore = true;
                        break;
                    }
                }
                if (!usaEsteCore) {
                    continue;
                }

                // ¿Se solapa el bloque FS?
                int rStart = r.getFsIndexBegin();
                int rEnd = rStart + r.getFsWidth() - 1;
                int wStart = start;
                int wEnd = start + width - 1;

                boolean overlap = (rStart <= wEnd) && (wStart <= rEnd);
                if (overlap) {
                    conflicts.add(r);
                }
            }
        }

        return conflicts;
    }

    private static int posicionDelEnlaceEnRuta(EstablishedRoute route, Link link) {
        int from = link.getFrom();
        int to = link.getTo();
        List<Link> path = route.getPath();
        for (int i = 0; i < path.size(); i++) {
            Link li = path.get(i);
            // Buscar dirección directa O inversa (cores compartidos)
            if ((li.getFrom() == from && li.getTo() == to) ||
                (li.getFrom() == to && li.getTo() == from)) {
                return i;
            }
        }
        return -1;
    }


    /* ===========================================================
   Intentar asignar la demanda en FS/cores fijos (con XT)
=========================================================== */
    private static EstablishedRoute intentarAsignarConCoresFijos(
            Demand demanda,
            List<Link> pathLinks,
            List<Integer> pathCores,
            int start,
            Graph<Integer, Link> graph,
            BigDecimal maxCrosstalk, double crosstalkPerUnitLength,
            int cores, Input input) {

        // FSDM: Calcular width por fibra
        int originalFs = demanda.getFs();
        int fibrasPorGrupo = (input.getFibrasPorGrupo() != null) ? input.getFibrasPorGrupo() : 1;
        int width = (int) Math.ceil((double) originalFs / fibrasPorGrupo);
        
        if (TRACE_INTENTO_ASSIGN) {
            System.out.println("[TRACE-INTENTO-ASSIGN] intentarAsignarConCoresFijos iniciando:");
            System.out.println("[TRACE-INTENTO-ASSIGN]   originalFs=" + originalFs + ", fibrasPorGrupo=" + fibrasPorGrupo + " -> width=" + width);
        }
        
        if (pathLinks == null || pathLinks.isEmpty()) {
            if (TRACE_INTENTO_ASSIGN) {
                System.out.println("[TRACE-INTENTO-ASSIGN] FALLO: pathLinks null o vacío");
            }
            return null;
        }

        int slotsSize = pathLinks.get(0).getCores().get(0).getFrequencySlots().size();
        if (width <= 0 || start < 0 || start + width > slotsSize) {
            if (TRACE_INTENTO_ASSIGN) {
                System.out.println("[TRACE-INTENTO-ASSIGN] FALLO: width/start fuera de rango");
                System.out.println("[TRACE-INTENTO-ASSIGN]   width=" + width + ", start=" + start + ", slotsSize=" + slotsSize);
                System.out.println("[TRACE-INTENTO-ASSIGN]   Condición: start+width=" + (start+width) + " > slotsSize=" + slotsSize);
            }
            return null;
        }

        // ==============================================================
        // FIX ROOT CAUSE: Determinar grupo FSDM ANTES de verificar
        // ==============================================================
        List<Integer> grupoSeleccionado = null;
        if (fibrasPorGrupo > 1 && input.getGrupos() != null && !input.getGrupos().isEmpty()) {
            int primerCore = pathCores.get(0);
            if (TRACE_INTENTO_ASSIGN) {
                System.out.println("[TRACE-INTENTO-ASSIGN] Buscando grupo FSDM para primerCore=" + primerCore);
                System.out.println("[TRACE-INTENTO-ASSIGN]   Grupos disponibles: " + input.getGrupos());
            }
            for (List<Integer> grupo : input.getGrupos()) {
                if (grupo.contains(primerCore)) {
                    grupoSeleccionado = grupo;
                    break;
                }
            }
            if (grupoSeleccionado == null) {
                if (TRACE_INTENTO_ASSIGN) {
                    System.out.println("[TRACE-INTENTO-ASSIGN] FALLO: No se encontró grupo válido para primerCore=" + primerCore);
                }
                return null; // No se encontró grupo válido para el core seleccionado
            }
            if (TRACE_INTENTO_ASSIGN) {
                System.out.println("[TRACE-INTENTO-ASSIGN] Grupo seleccionado: " + grupoSeleccionado);
            }
        }

        // ==============================================================
        // FIX ROOT CAUSE: Verificar disponibilidad en TODOS los cores
        // del grupo FSDM (no solo el seleccionado por la heurística)
        // ==============================================================
        List<BigDecimal> crosstalkFSList = new ArrayList<>(Collections.nCopies(width, BigDecimal.ZERO));
        
        if (fibrasPorGrupo > 1 && grupoSeleccionado != null) {
            // FSDM: Verificar TODOS los cores del grupo para cada enlace
            if (TRACE_INTENTO_ASSIGN) {
                System.out.println("[TRACE-INTENTO-ASSIGN] Modo FSDM: verificando TODOS los cores del grupo en todos los enlaces");
            }
            for (int li = 0; li < pathLinks.size(); li++) {
                Link link = pathLinks.get(li);
                
                // Verificar disponibilidad en TODOS los cores del grupo
                for (Integer coreDelGrupo : grupoSeleccionado) {
                    if (!isBlockAvailable(link, coreDelGrupo, start, width, maxCrosstalk, crosstalkFSList, crosstalkPerUnitLength)) {
                        if (TRACE_INTENTO_ASSIGN) {
                            System.out.println("[TRACE-INTENTO-ASSIGN] FALLO: isBlockAvailable=false");
                            System.out.println("[TRACE-INTENTO-ASSIGN]   link: " + link.getFrom() + "-" + link.getTo() + " (enlace " + li + "/" + pathLinks.size() + ")");
                            System.out.println("[TRACE-INTENTO-ASSIGN]   core: " + coreDelGrupo);
                            System.out.println("[TRACE-INTENTO-ASSIGN]   rango: fs[" + start + "-" + (start+width-1) + "]");
                        }
                        return null; // Rechazar si algún core del grupo está ocupado
                    }
                    updateCrosstalkFSList(crosstalkFSList, coreDelGrupo, link, crosstalkPerUnitLength, width);
                }
            }
        } else {
            // SDM original o fibrasPorGrupo=1: verificar solo el core seleccionado
            if (TRACE_INTENTO_ASSIGN) {
                System.out.println("[TRACE-INTENTO-ASSIGN] Modo SDM: verificando solo el core seleccionado por la heurística");
            }
            for (int li = 0; li < pathLinks.size(); li++) {
                Link link = pathLinks.get(li);
                int core = pathCores.get(li);
                
                if (!isBlockAvailable(link, core, start, width, maxCrosstalk, crosstalkFSList, crosstalkPerUnitLength)) {
                    if (TRACE_INTENTO_ASSIGN) {
                        System.out.println("[TRACE-INTENTO-ASSIGN] FALLO: isBlockAvailable=false");
                        System.out.println("[TRACE-INTENTO-ASSIGN]   link: " + link.getFrom() + "-" + link.getTo() + " (enlace " + li + "/" + pathLinks.size() + ")");
                        System.out.println("[TRACE-INTENTO-ASSIGN]   core: " + core);
                        System.out.println("[TRACE-INTENTO-ASSIGN]   rango: fs[" + start + "-" + (start+width-1) + "]");
                    }
                    return null;
                }
                updateCrosstalkFSList(crosstalkFSList, core, link, crosstalkPerUnitLength, width);
            }
        }

        // ==============================================================
        // Expandir pathCores para FSDM (ahora todos los cores ya fueron verificados)
        // ==============================================================
        List<Integer> pathCoresExpandido;
        if (fibrasPorGrupo > 1 && grupoSeleccionado != null) {
            // FSDM: Expandir a todas las fibras del grupo (ya verificadas)
            pathCoresExpandido = new ArrayList<>();
            for (int li = 0; li < pathLinks.size(); li++) {
                pathCoresExpandido.addAll(grupoSeleccionado);
            }
        } else {
            // SDM: usar pathCores tal cual
            pathCoresExpandido = new ArrayList<>(pathCores);
        }

        // Si todos los enlaces pasan, crear y asignar
        // FSDM: usar constructor de 9 parámetros (fsWidth=width calculado, originalDemandFs=originalFs, fibrasPorGrupo)
        EstablishedRoute nueva = new EstablishedRoute(
                pathLinks, start, width, demanda.getLifetime(),
                demanda.getSource(), demanda.getDestination(), pathCoresExpandido, originalFs, fibrasPorGrupo);
        Utils.assignFs(graph, nueva, crosstalkPerUnitLength);
        return nueva;
    }


    /* ===========================================================
   Convertir EstablishedRoute -> Demand (para ruteoCoreMultiple)
=========================================================== */
    private static Demand demandFromRoute(EstablishedRoute r) {
        Demand d = new Demand();
        d.setSource(r.getFrom());
        d.setDestination(r.getTo());
        // FSDM Problem 2 fix: usar originalDemandFs en vez de fsWidth
        // fsWidth contiene fsNecesariosPorFibra (valor dividido), originalDemandFs tiene el valor original
        d.setFs(r.getOriginalDemandFs());
        d.setLifetime(r.getLifetime());
        // si tu Demand tiene ID/tiempo, setéalos si hace falta
        return d;
    }

    public static class DefragMetrics {

        public int conteoExitos;
        public int conteoFallido;
        public int routesMoved; //acumula rutas reconfiguradas en éxitos

        public void reset() {
            conteoExitos = 0;
            conteoFallido = 0;
            routesMoved = 0;
        }
    }

    public static final DefragMetrics metricsBFRmax1 = new DefragMetrics();
    public static final DefragMetrics metricsBFRmax3 = new DefragMetrics();

    public static final DefragMetrics metricsBFRmin1 = new DefragMetrics();
    public static final DefragMetrics metricsBFRmin3 = new DefragMetrics();

    public static final DefragMetrics metricsFullRuteoMin1 = new DefragMetrics();
    public static final DefragMetrics metricsFullRuteoMin3 = new DefragMetrics();

    /**
     * Resetea todas las métricas de desfragmentación al inicio de cada experimento
     */
    public static void resetAllMetrics() {
        metricsBFRmax1.reset();
        metricsBFRmax3.reset();
        metricsBFRmin1.reset();
        metricsBFRmin3.reset();
        metricsFullRuteoMin1.reset();
        metricsFullRuteoMin3.reset();
    }

    private static void printMetricsBFRmax1() {
        System.out.println("\n=== Métricas del Desfragmentador ===");
        System.out.println("Éxitos: " + metricsBFRmax1.conteoExitos);
        System.out.println("Fallos: " + metricsBFRmax1.conteoFallido);
        System.out.println("Rutas reconfiguradas (acumuladas): " + metricsBFRmax1.routesMoved); // ← NUEVO
        System.out.println("===============================\n");
    }

    private static void printMetricsBFRmax3() {
        System.out.println("\n=== Métricas del Desfragmentador ===");
        System.out.println("Éxitos: " + metricsBFRmax3.conteoExitos);
        System.out.println("Fallos: " + metricsBFRmax3.conteoFallido);
        System.out.println("Rutas reconfiguradas (acumuladas): " + metricsBFRmax3.routesMoved);
        System.out.println("===============================\n");
    }

    private static void printMetricsBFRmin1() {
        System.out.println("\n=== Métricas del Desfragmentador ===");
        System.out.println("Éxitos: " + metricsBFRmin1.conteoExitos);
        System.out.println("Fallos: " + metricsBFRmin1.conteoFallido);
        System.out.println("Rutas reconfiguradas (acumuladas): " + metricsBFRmin1.routesMoved);
        System.out.println("===============================\n");
    }

    private static void printMetricsBFRmin3() {
        System.out.println("\n=== Métricas del Desfragmentador ===");
        System.out.println("Éxitos: " + metricsBFRmin3.conteoExitos);
        System.out.println("Fallos: " + metricsBFRmin3.conteoFallido);
        System.out.println("Rutas reconfiguradas (acumuladas): " + metricsBFRmin3.routesMoved);
        System.out.println("===============================\n");
    }

    private static void printMetricsFullRuteoMin1() {
        System.out.println("\n=== Métricas del Desfragmentador ===");
        System.out.println("Éxitos: " + metricsFullRuteoMin1.conteoExitos);
        System.out.println("Fallos: " + metricsFullRuteoMin1.conteoFallido);
        System.out.println("Rutas reconfiguradas (acumuladas): " + metricsFullRuteoMin1.routesMoved); // ← NUEVO
        System.out.println("===============================\n");
    }

    private static void printMetricsFullRuteoMin3() {
        System.out.println("\n=== Métricas FullRuteoMin3 ===");
        System.out.println("Éxitos: " + metricsFullRuteoMin3.conteoExitos);
        System.out.println("Fallos: " + metricsFullRuteoMin3.conteoFallido);
        System.out.println("Rutas reconfiguradas (acumuladas): " + metricsFullRuteoMin3.routesMoved);
        System.out.println("================================\n");
    }

    private static final boolean DEBUG = true;

    /* ===========================================================
     resolveCurrentReferences
     Resuelve referencias obsoletas en conflictSet, retornando
     instancias actuales de establishedRoutes.
   =========================================================== */
    private static Set<EstablishedRoute> resolveCurrentReferences(
            Set<EstablishedRoute> staleSet,
            List<EstablishedRoute> currentRoutes,
            int windowStart,
            int windowWidth) {
        
        Set<EstablishedRoute> resolved = new LinkedHashSet<>();
        
        for (EstablishedRoute staleRoute : staleSet) {
            int from = staleRoute.getFrom();
            int to = staleRoute.getTo();
            
            // === DIAGNÓSTICO: Mostrar TODAS las instancias con mismo from->to ===
            if (TRACE_ROLLBACK_VALIDATION) {
                System.out.println("\n[DIAGNÓSTICO] Buscando instancia actual para " + from + "->" + to + " [ID stale:" + Integer.toHexString(System.identityHashCode(staleRoute)) + "]");
                int count = 0;
                for (EstablishedRoute r : currentRoutes) {
                    if (r.getFrom() == from && r.getTo() == to) {
                        count++;
                        System.out.println("[DIAGNÓSTICO]   Candidato #" + count + ": [ID:" + Integer.toHexString(System.identityHashCode(r)) + "]" +
                                         " path:" + pathToString(r.getPath()) +
                                         " cores:" + r.getPathCores() +
                                         " fs:" + r.getFsIndexBegin() + "-" + (r.getFsIndexBegin() + r.getFsWidth() - 1));
                    }
                }
                if (count == 0) {
                    System.out.println("[DIAGNÓSTICO]   ❌ No hay ninguna instancia con " + from + "->" + to + " en establishedRoutes");
                } else if (count > 1) {
                    System.out.println("[DIAGNÓSTICO]   ⚠️ MÚLTIPLES instancias (" + count + ") con " + from + "->" + to);
                }
            }
            
            // Buscar la versión actual que se solapa con la ventana FS
            int wStart = windowStart;
            int wEnd = windowStart + windowWidth - 1;
            
            EstablishedRoute currentRoute = null;
            for (EstablishedRoute r : currentRoutes) {
                if (r.getFrom() == from && r.getTo() == to) {
                    // Verificar solapamiento FS: [rStart, rEnd] ∩ [wStart, wEnd] ≠ ∅
                    int rStart = r.getFsIndexBegin();
                    int rEnd = rStart + r.getFsWidth() - 1;
                    boolean overlap = (rStart <= wEnd) && (wStart <= rEnd);
                    
                    if (overlap) {
                        currentRoute = r;
                        if (TRACE_ROLLBACK_VALIDATION) {
                            System.out.println("[DIAGNÓSTICO]   ✅ SELECCIONADA: [ID:" + Integer.toHexString(System.identityHashCode(currentRoute)) + "]" +
                                             " path:" + pathToString(currentRoute.getPath()) +
                                             " fs:[" + rStart + "-" + rEnd + "] ∩ ventana:[" + wStart + "-" + wEnd + "]");
                        }
                        break;
                    }
                }
            }
            
            if (currentRoute != null) {
                resolved.add(currentRoute);
            } else {
                // La ruta ya no existe en establishedRoutes
                System.err.println("[ERROR] Ruta " + from + "->" + to +
                    " del candidato no encontrada en establishedRoutes actual");
                // No agregamos nada a resolved; continuamos sin esta ruta
            }
        }
        
        return resolved;
    }

    /* ===========================================================
     createBackups
   =========================================================== */
    private static Map<EstablishedRoute, EstablishedRoute> createBackups(List<EstablishedRoute> routes) {
        Map<EstablishedRoute, EstablishedRoute> backups = new HashMap<>();
        
        if (TRACE_ROLLBACK_VALIDATION) {
            System.out.println("\n[DIAGNÓSTICO] ===== ANTES de createBackups() / copyRoute() =====");
        }
        
        for (EstablishedRoute route : routes) {
            if (route != null) {
                // === DIAGNÓSTICO: Estado ANTES de copyRoute ===
                if (TRACE_ROLLBACK_VALIDATION) {
                    System.out.println("[DIAGNÓSTICO] Creando backup para: " + route.getFrom() + "->" + route.getTo() + 
                                     " [ID:" + Integer.toHexString(System.identityHashCode(route)) + "]" +
                                     " path:" + pathToString(route.getPath()) +
                                     " cores:" + route.getPathCores() +
                                     " fs:" + route.getFsIndexBegin() + "-" + (route.getFsIndexBegin() + route.getFsWidth() - 1));
                }
                
                EstablishedRoute backup = copyRoute(route);
                
                // === DIAGNÓSTICO: Estado DESPUÉS de copyRoute ===
                if (TRACE_ROLLBACK_VALIDATION && backup != null) {
                    System.out.println("[DIAGNÓSTICO]   Backup creado: [ID:" + Integer.toHexString(System.identityHashCode(backup)) + "]" +
                                     " path:" + pathToString(backup.getPath()) +
                                     " cores:" + backup.getPathCores() +
                                     " fs:" + backup.getFsIndexBegin() + "-" + (backup.getFsIndexBegin() + backup.getFsWidth() - 1));
                    
                    // Verificar si copyRoute modificó el path
                    String originalPath = pathToString(route.getPath());
                    String backupPath = pathToString(backup.getPath());
                    if (!originalPath.equals(backupPath)) {
                        System.out.println("[DIAGNÓSTICO]   ⚠️⚠️⚠️ CAMBIÓ EL PATH EN copyRoute() ⚠️⚠️⚠️");
                        System.out.println("[DIAGNÓSTICO]   Original: " + originalPath);
                        System.out.println("[DIAGNÓSTICO]   Backup:   " + backupPath);
                    }
                }
                
                if (backup != null) {
                    backups.put(route, backup);
                } else {
                    log("Error: No se pudo crear backup para la ruta: " + route);
                    backups.put(route, null);
                }
            }
        }
        return backups;
    }

    private static boolean isBlockAvailable(Link link, int core, int start,
            int width, BigDecimal maxCrosstalk, List<BigDecimal> crosstalkFSList,
            double crosstalkPerUnitLength) {

        List<FrequencySlot> fsSlots = link.getCores().get(core)
                .getFrequencySlots().subList(start, start + width);

        // DIAGNÓSTICO: Evaluar cada condición por separado
        boolean isFree = Algorithms.isFSBlockFree(fsSlots);
        boolean isCrosstalkFree = Algorithms.isFsBlockCrosstalkFree(fsSlots, maxCrosstalk, crosstalkFSList);
        boolean isNextToCrosstalkFree = Algorithms.isNextToCrosstalkFreeCores(link, maxCrosstalk, core, start, width, crosstalkPerUnitLength);
        
        boolean resultado = isFree && isCrosstalkFree && isNextToCrosstalkFree;
        
        // Si falla, mostrar diagnóstico detallado
        if (!resultado && TRACE_INTENTO_ASSIGN) {
            System.out.println("[DIAGNÓSTICO-isBlockAvailable] ===== DESGLOSE DE CONDICIONES =====");
            System.out.println("[DIAGNÓSTICO-isBlockAvailable]   Link: " + link.getFrom() + "->" + link.getTo());
            System.out.println("[DIAGNÓSTICO-isBlockAvailable]   Core: " + core);
            System.out.println("[DIAGNÓSTICO-isBlockAvailable]   FS range: [" + start + "-" + (start + width - 1) + "]");
            System.out.println("[DIAGNÓSTICO-isBlockAvailable]   isFSBlockFree = " + isFree);
            System.out.println("[DIAGNÓSTICO-isBlockAvailable]   isFsBlockCrosstalkFree = " + isCrosstalkFree);
            System.out.println("[DIAGNÓSTICO-isBlockAvailable]   isNextToCrosstalkFreeCores = " + isNextToCrosstalkFree);
            System.out.println("[DIAGNÓSTICO-isBlockAvailable]   RESULTADO FINAL = " + resultado);
            System.out.println("[DIAGNÓSTICO-isBlockAvailable] =======================================");
        }
        
        return resultado;
    }

    private static void updateCrosstalkFSList(List<BigDecimal> crosstalkFSList,
            int core, Link link, double crosstalkPerUnitLength, int width) {

        // FSDM: Omitir actualización de crosstalk si las fibras son físicamente independientes
        // Este threshold debe coincidir con el usado en Algorithms.java y Utils.java
        final double FSDM_CROSSTALK_THRESHOLD = 1e-10;
        if (crosstalkPerUnitLength < FSDM_CROSSTALK_THRESHOLD) {
            return; // FSDM: no hay crosstalk entre fibras aisladas
        }

        // SDM: Actualizar crosstalk inter-core
        for (int i = 0; i < width; i++) {
            BigDecimal current = crosstalkFSList.get(i);
            crosstalkFSList.set(i, current.add(Utils.toDB(Utils.XT(
                    Utils.getCantidadVecinos(core), crosstalkPerUnitLength, link.getDistance()))));
        }
    }

    private static List<Link> getBlockedDemandPath(Demand demandaBloqueada, Graph<Integer, Link> graph) {
        KShortestSimplePaths<Integer, Link> kspFinder = new KShortestSimplePaths<>(graph);
        List<GraphPath<Integer, Link>> kPaths = kspFinder.getPaths(demandaBloqueada.getSource(), demandaBloqueada.getDestination(), 5);
        return kPaths.isEmpty() ? Collections.emptyList() : kPaths.get(0).getEdgeList();
    }

    /* ===========================================================
      COPIA PROFUNDA DE LA RUTA
   =========================================================== */
    private static EstablishedRoute copyRoute(EstablishedRoute route) {
        if (route == null) {
            log("Error: Intento de copiar ruta nula");
            return null;
        }

        if (route.getPath() == null) {
            log("Error: Ruta a copiar tiene path nulo");
            return null;
        }

        try {
            List<Link> copiedPath = new ArrayList<>();
            for (Link link : route.getPath()) {
                List<Core> copiedCores = new ArrayList<>();
                for (Core core : link.getCores()) {
                    List<FrequencySlot> copiedSlots = new ArrayList<>();
                    for (FrequencySlot fs : core.getFrequencySlots()) {
                        FrequencySlot fsCopy = new FrequencySlot(fs.getFsWidh());
                        fsCopy.setFree(fs.isFree());
                        fsCopy.setLifetime(fs.getLifetime());
                        fsCopy.setCrosstalk(fs.getCrosstalk());
                        copiedSlots.add(fsCopy);
                    }
                    copiedCores.add(new Core(core.getBandwidth(), copiedSlots));
                }
                Link copiedLink = new Link(link.getDistance(), copiedCores, link.getFrom(), link.getTo());
                copiedPath.add(copiedLink);
                
                // TRACE: Capturar el estado que se guarda en el backup
                if (TRACE_SLOT && link.getFrom() == TRACE_LINK_FROM && link.getTo() == TRACE_LINK_TO) {
                    FrequencySlot tracedSlot = link.getCores().get(TRACE_CORE).getFrequencySlots().get(TRACE_FS);
                    traceSlot("BACKUP-CREATE", link, TRACE_CORE, TRACE_FS, 
                             tracedSlot.isFree(), tracedSlot.getLifetime(), 
                             "Ruta " + route.getFrom() + "->" + route.getTo() + 
                             " cores:" + route.getPathCores() + " fs:" + route.getFsIndexBegin() + "-" + 
                             (route.getFsIndexBegin() + route.getFsWidth() - 1));
                }
            }
            // Usar constructor de 9 parámetros para preservar originalDemandFs y fibrasPorGrupo
            return new EstablishedRoute(copiedPath, route.getFsIndexBegin(), route.getFsWidth(),
                    route.getLifetime(), route.getFrom(), route.getTo(), new ArrayList<>(route.getPathCores()), route.getOriginalDemandFs(), route.getFibrasPorGrupo());
        } catch (Exception e) {
            log("Error al copiar ruta: " + e.getMessage());
            return null;
        }
    }

    /* ===========================================================
     RESTAURA LAS RUTAS
   =========================================================== */
    private static void restoreSingleRoute(Graph<Integer, Link> graph, EstablishedRoute originalRoute) {
        if (originalRoute == null) {
            log("Warning: Intento de restaurar ruta nula");
            return;
        }

        if (originalRoute.getPath() == null) {
            log("Warning: Ruta a restaurar tiene path nulo");
            return;
        }

        int fibrasPorEnlace = originalRoute.getFibrasPorGrupo();
        int fsBegin = originalRoute.getFsIndexBegin();
        int fsEnd = fsBegin + originalRoute.getFsWidth();

        for (int linkIdx = 0; linkIdx < originalRoute.getPath().size(); linkIdx++) {
            Link copyLink = originalRoute.getPath().get(linkIdx);
            Link graphLink = findGraphLink(graph, copyLink.getFrom(), copyLink.getTo());

            if (graphLink != null) {
                // Restaurar SOLO los cores que usa esta ruta
                for (int f = 0; f < fibrasPorEnlace; f++) {
                    int core = originalRoute.getPathCores().get(linkIdx * fibrasPorEnlace + f);

                    List<FrequencySlot> graphSlots = graphLink.getCores().get(core).getFrequencySlots();
                    List<FrequencySlot> backupSlots = copyLink.getCores().get(core).getFrequencySlots();

                    // Restaurar SOLO los FS que usa esta ruta
                    for (int fs = fsBegin; fs < fsEnd; fs++) {
                        // TRACE: Antes de restaurar
                        if (TRACE_SLOT && graphLink.getFrom() == TRACE_LINK_FROM && 
                            graphLink.getTo() == TRACE_LINK_TO && core == TRACE_CORE && fs == TRACE_FS) {
                            traceSlot("RESTORE-BEFORE", graphLink, core, fs,
                                     graphSlots.get(fs).isFree(), graphSlots.get(fs).getLifetime(),
                                     "Ruta " + originalRoute.getFrom() + "->" + originalRoute.getTo() +
                                     " | Estado ACTUAL en grafo");
                            traceSlot("RESTORE-BACKUP", graphLink, core, fs,
                                     backupSlots.get(fs).isFree(), backupSlots.get(fs).getLifetime(),
                                     "Ruta " + originalRoute.getFrom() + "->" + originalRoute.getTo() +
                                     " | Estado en BACKUP que se va a aplicar");
                        }
                        
                        graphSlots.get(fs).setFree(backupSlots.get(fs).isFree());
                        graphSlots.get(fs).setLifetime(backupSlots.get(fs).getLifetime());
                        graphSlots.get(fs).setCrosstalk(backupSlots.get(fs).getCrosstalk());
                        
                        // TRACE: Después de restaurar
                        if (TRACE_SLOT && graphLink.getFrom() == TRACE_LINK_FROM && 
                            graphLink.getTo() == TRACE_LINK_TO && core == TRACE_CORE && fs == TRACE_FS) {
                            traceSlot("RESTORE-AFTER", graphLink, core, fs,
                                     graphSlots.get(fs).isFree(), graphSlots.get(fs).getLifetime(),
                                     "Ruta " + originalRoute.getFrom() + "->" + originalRoute.getTo() +
                                     " | Estado FINAL después de restaurar");
                        }
                    }
                }
            }
        }
    }

    private static Link findGraphLink(Graph<Integer, Link> graph, int from, int to) {
        return graph.edgeSet().stream()
                .filter(link -> link.getFrom() == from && link.getTo() == to)
                .findFirst()
                .orElse(null);
    }

    private static void log(String message) {
        if (DEBUG) {
            System.out.println("[Defragmenter] " + message);
        }
    }

    // Auxiliar para almacenar candidatos (start, cores por enlace, set de conflictivas)
    private static class VentanaCandidata {

        final int start;
        final List<Integer> coresPorLink;              // mismos núcleos por enlace (los de mayor BFR)
        final Set<EstablishedRoute> conflictSet;       // rutas conflictivas que pisan la ventana

        VentanaCandidata(int start, List<Integer> coresPorLink, Set<EstablishedRoute> conflictSet) {
            this.start = start;
            this.coresPorLink = coresPorLink;
            this.conflictSet = conflictSet;
        }
    }

    /* ===========================================================
     METODOS AUXILIARES de desfragFullRuteoMin
  =========================================================== */
 /* -----------------------------------------------------------
   Auxiliar: resultado de evaluar una ventana con la estrategia
   "mínimo por enlace + suma mínima".
----------------------------------------------------------- */
    private static class VentanaMinSum {

        final int start;
        final List<Integer> coresPorLink;        // core elegido por enlace (mínimo conflicto)
        final Set<EstablishedRoute> conflictSet; // unión de los mínimos por enlace (sin duplicados)
        final int sumaMinConflictos;             // suma de (#conflictos mínimos) por enlace

        VentanaMinSum(int start, List<Integer> coresPorLink,
                Set<EstablishedRoute> conflictSet, int sumaMinConflictos) {
            this.start = start;
            this.coresPorLink = coresPorLink;
            this.conflictSet = conflictSet;
            this.sumaMinConflictos = sumaMinConflictos;
        }
    }

    /* -----------------------------------------------------------
   FSDM-AWARE: Evalúa una ventana probando cada GRUPO FSDM como
   unidad de decisión. En FSDM, TODOS los cores del grupo se usan
   simultáneamente en todos los enlaces, por lo que se cuentan
   conflictos de TODOS los cores del grupo en TODOS los enlaces.
   Retorna el grupo que minimiza la suma total de conflictos.
   
   Si grupos == null o vacío: Comportamiento SDM original (todos los cores).
----------------------------------------------------------- */
    private static VentanaMinSum evaluarVentanaMinSuma(
            List<Link> pathLinks,
            int start, int width,
            int cores,
            List<List<Integer>> grupos,
            List<EstablishedRoute> establishedRoutes) {

        // DEBUG: Verificar si grupos está llegando
        if (TRACE_REINSERTION_FLOW && reinsertionTraceCounter == 0) {
            System.out.println("[DEBUG-VENTANA] evaluarVentanaMinSuma llamado:");
            System.out.println("[DEBUG-VENTANA]   grupos: " + grupos);
            System.out.println("[DEBUG-VENTANA]   grupos == null? " + (grupos == null));
            System.out.println("[DEBUG-VENTANA]   grupos.isEmpty()? " + (grupos != null && grupos.isEmpty()));
        }

        // Modo SDM (sin grupos FSDM): comportamiento original
        if (grupos == null || grupos.isEmpty()) {
            if (TRACE_REINSERTION_FLOW && reinsertionTraceCounter == 0) {
                System.out.println("[DEBUG-VENTANA] Usando modo SDM (sin grupos)");
            }
            return evaluarVentanaMinSumaSDM(pathLinks, start, width, cores, establishedRoutes);
        }

        if (TRACE_REINSERTION_FLOW && reinsertionTraceCounter == 0) {
            System.out.println("[DEBUG-VENTANA] Usando modo FSDM con grupos: " + grupos);
        }

        // Modo FSDM: Probar cada grupo como unidad de decisión
        VentanaMinSum mejorCandidato = null;
        int mejorSuma = Integer.MAX_VALUE;

        for (List<Integer> grupo : grupos) {
            Set<EstablishedRoute> unionConflicts = new HashSet<>();
            int suma = 0;

            // DIAGNÓSTICO: Imprimir matriz de conflictos por enlace (solo primer intento)
            boolean diagnosticarPorEnlace = (TRACE_REINSERTION_FLOW && reinsertionTraceCounter == 0 && mejorSuma == Integer.MAX_VALUE);
            if (diagnosticarPorEnlace) {
                System.out.println("\n[DIAGNÓSTICO-MATRIZ] ===== CONFLICTOS POR ENLACE =====");
                System.out.println("[DIAGNÓSTICO-MATRIZ] Ventana: start=" + start + ", width=" + width + ", rango FS:[" + start + "-" + (start + width - 1) + "]");
                System.out.println("[DIAGNÓSTICO-MATRIZ] Grupo evaluado: " + grupo);
                System.out.println("[DIAGNÓSTICO-MATRIZ] Path tiene " + pathLinks.size() + " enlaces:");
                for (int i = 0; i < pathLinks.size(); i++) {
                    Link link = pathLinks.get(i);
                    System.out.println("[DIAGNÓSTICO-MATRIZ]   Enlace " + i + ": " + link.getFrom() + "->" + link.getTo());
                }
            }

            // En FSDM, TODOS los cores del grupo se usan en TODOS los enlaces
            // Por lo tanto, contamos conflictos de TODOS los cores del grupo
            int enlaceIdx = 0;
            for (Link link : pathLinks) {
                // Para este enlace, obtener rutas que usan CUALQUIER core del grupo
                Set<EstablishedRoute> conflictosEnEsteLink = new HashSet<>();
                
                if (diagnosticarPorEnlace) {
                    System.out.println("\n[DIAGNÓSTICO-MATRIZ] Enlace " + enlaceIdx + " (" + link.getFrom() + "->" + link.getTo() + "):");
                }
                
                for (int c : grupo) {
                    Set<EstablishedRoute> conf = conflictosEnLinkCoreVentana(
                            link, c, start, width, establishedRoutes);
                    
                    if (diagnosticarPorEnlace && !conf.isEmpty()) {
                        System.out.println("[DIAGNÓSTICO-MATRIZ]   Core " + c + " → " + conf.size() + " conflictos:");
                        for (EstablishedRoute r : conf) {
                            System.out.println("[DIAGNÓSTICO-MATRIZ]     - " + r.getFrom() + "->" + r.getTo() + 
                                " fs:[" + r.getFsIndexBegin() + "-" + (r.getFsIndexBegin() + r.getFsWidth() - 1) + "]" +
                                " path:" + r.getPath().stream().map(l -> l.getFrom() + "-" + l.getTo()).collect(Collectors.joining(",")));
                        }
                    }
                    
                    conflictosEnEsteLink.addAll(conf);
                }
                
                if (diagnosticarPorEnlace) {
                    System.out.println("[DIAGNÓSTICO-MATRIZ]   Total rutas únicas en este enlace: " + conflictosEnEsteLink.size());
                }
                
                // Contar solo rutas únicas en este enlace
                suma += conflictosEnEsteLink.size();
                unionConflicts.addAll(conflictosEnEsteLink);
                enlaceIdx++;
            }
            
            if (diagnosticarPorEnlace) {
                System.out.println("\n[DIAGNÓSTICO-MATRIZ] RESUMEN:");
                System.out.println("[DIAGNÓSTICO-MATRIZ]   suma (ocurrencias por enlace): " + suma);
                System.out.println("[DIAGNÓSTICO-MATRIZ]   |unionConflicts| (rutas únicas): " + unionConflicts.size());
                System.out.println("[DIAGNÓSTICO-MATRIZ]   unionConflicts:");
                for (EstablishedRoute r : unionConflicts) {
                    System.out.println("[DIAGNÓSTICO-MATRIZ]     - " + r.getFrom() + "->" + r.getTo() + 
                        " fs:[" + r.getFsIndexBegin() + "-" + (r.getFsIndexBegin() + r.getFsWidth() - 1) + "]" +
                        " path:" + r.getPath().stream().map(l -> l.getFrom() + "-" + l.getTo()).collect(Collectors.joining(",")));
                }
                System.out.println("[DIAGNÓSTICO-MATRIZ] ==========================================\n");
            }

            // Elegir el grupo con menos conflictos totales
            if (suma < mejorSuma) {
                mejorSuma = suma;
                
                // En FSDM, todos los enlaces usan el primer core del grupo
                // (el patrón real [2,3,2,3,...] lo construye intentarAsignarConCoresFijos)
                List<Integer> elegidos = new ArrayList<>(pathLinks.size());
                int primerCore = grupo.get(0);
                for (int i = 0; i < pathLinks.size(); i++) {
                    elegidos.add(primerCore);
                }
                
                if (TRACE_REINSERTION_FLOW && reinsertionTraceCounter == 0) {
                    System.out.println("[DEBUG-VENTANA] Grupo " + grupo + " -> suma=" + suma + ", elegidos=" + elegidos);
                    System.out.println("[DEBUG-VENTANA]   #conflictos únicos: " + unionConflicts.size());
                }
                
                mejorCandidato = new VentanaMinSum(start, elegidos, unionConflicts, suma);
            }
        }

        if (TRACE_REINSERTION_FLOW && reinsertionTraceCounter == 0) {
            System.out.println("[DEBUG-VENTANA] Mejor candidato: " + 
                (mejorCandidato != null ? mejorCandidato.coresPorLink : "null"));
        }

        return mejorCandidato;
    }

    /* -----------------------------------------------------------
   Versión SDM original: evalúa ventana probando TODOS los cores
   individualmente por enlace (sin restricción de grupo).
----------------------------------------------------------- */
    private static VentanaMinSum evaluarVentanaMinSumaSDM(
            List<Link> pathLinks,
            int start, int width,
            int cores,
            List<EstablishedRoute> establishedRoutes) {

        List<Integer> elegidos = new ArrayList<>(pathLinks.size());
        Set<EstablishedRoute> unionConflicts = new HashSet<>();
        int suma = 0;

        for (Link link : pathLinks) {
            Set<EstablishedRoute> mejorConf = null;
            int mejorCore = -1;
            double mejorBfr = -1.0;

            for (int c = 0; c < cores; c++) {
                Set<EstablishedRoute> conf = conflictosEnLinkCoreVentana(
                        link, c, start, width, establishedRoutes);

                int candSize = conf.size();
                double candBfr = bfrDeCore(link, c);

                boolean esMejor = false;

                if (mejorConf == null) {
                    esMejor = true;
                } else if (candSize < mejorConf.size()) {
                    esMejor = true;
                } else if (candSize == mejorConf.size()) {
                    if (candBfr > mejorBfr) {
                        esMejor = true;
                    } else if (candBfr == mejorBfr && c < mejorCore) {
                        esMejor = true;
                    }
                }

                if (esMejor) {
                    mejorConf = conf;
                    mejorCore = c;
                    mejorBfr = candBfr;
                }
            }

            if (mejorCore < 0) {
                return null;
            }

            elegidos.add(mejorCore);
            suma += mejorConf.size();
            unionConflicts.addAll(mejorConf);
        }

        return new VentanaMinSum(start, elegidos, unionConflicts, suma);
    }

    // Devuelve las rutas que pisan la ventana [start, start+width-1] en el ENLACE y CORE dados.
    private static Set<EstablishedRoute> conflictosEnLinkCoreVentana(
            Link link, int core, int start, int width,
            List<EstablishedRoute> establishedRoutes) {

        Set<EstablishedRoute> conflicts = new HashSet<>();
        int wStart = start;
        int wEnd = start + width - 1;

        for (EstablishedRoute r : establishedRoutes) {
            // ¿La ruta r pasa por este enlace?
            int idx = posicionDelEnlaceEnRuta(r, link);
            if (idx < 0) {
                continue;
            }

            // FSDM Fix: ¿Lo hace por el mismo core? Verificar todas las fibras del grupo
            int fibrasPorGrupo = r.getFibrasPorGrupo();
            boolean usaEsteCore = false;
            for (int f = 0; f < fibrasPorGrupo; f++) {
                Integer coreRuta = r.getPathCores().get(idx * fibrasPorGrupo + f);
                if (coreRuta != null && coreRuta == core) {
                    usaEsteCore = true;
                    break;
                }
            }
            if (!usaEsteCore) {
                continue;
            }

            // ¿Se solapa el bloque FS?
            int rStart = r.getFsIndexBegin();
            int rEnd = rStart + r.getFsWidth() - 1;

            boolean overlap = (rStart <= wEnd) && (wStart <= rEnd);
            if (overlap) {
                conflicts.add(r);
            }
        }
        return conflicts;
    }

// === Helpers de mantenimiento de la lista establishedRoutes ===
    private static void addRouteToList(List<EstablishedRoute> list, EstablishedRoute r) {
        if (r != null && !list.contains(r)) {
            list.add(r);
        }
    }

    private static void removeRouteFromList(List<EstablishedRoute> list, EstablishedRoute r) {
        if (r != null) {
            // ========== FORENSIC AUDIT: Detectar fallo en remove por equals() (BUG-3) ==========
            int sizeBefore = list.size();
            list.remove(r);
            int sizeAfter = list.size();
            
            ForensicLogger.logRemoveAttempt(list, r, sizeBefore, sizeAfter);
            // ========== FIN FORENSIC AUDIT ==========
        }
    }

    private static void replaceRouteInList(List<EstablishedRoute> list,
            EstablishedRoute oldR,
            EstablishedRoute newR) {
        if (oldR == null || newR == null) {
            return;
        }
        
        int idx = list.indexOf(oldR);
        if (idx >= 0) {
            list.set(idx, newR);
        } else {
            // Si por alguna razón no estaba (no debería pasar), asegurá presencia:
            addRouteToList(list, newR);
        }
    }

    /* ===========================================================
       MÉTODOS DE VALIDACIÓN DE INVARIANTES
       =========================================================== */
    
    // INVARIANTE 1: Rutas en conflictSet realmente ocupan recursos en conflicto
    private static void validateConflictSet(Set<EstablishedRoute> conflictSet, 
            List<Link> pathLinks, List<Integer> pathCores, int start, int width, 
            ValidationReport report) {
        for (EstablishedRoute r : conflictSet) {
            boolean reallyConflicts = false;
            
            for (int li = 0; li < pathLinks.size(); li++) {
                Link link = pathLinks.get(li);
                int idx = posicionDelEnlaceEnRuta(r, link);
                if (idx < 0) continue;
                
                // Verificar si la ruta usa algún core del grupo en este enlace
                int fibrasPorGrupo = r.getFibrasPorGrupo();
                for (int f = 0; f < fibrasPorGrupo; f++) {
                    Integer coreRuta = r.getPathCores().get(idx * fibrasPorGrupo + f);
                    
                    // Verificar si este core está en pathCores para este enlace
                    Integer coreEsperado = pathCores.get(li);
                    if (coreRuta.equals(coreEsperado)) {
                        // Verificar solapamiento FS
                        int rStart = r.getFsIndexBegin();
                        int rEnd = rStart + r.getFsWidth() - 1;
                        int wStart = start;
                        int wEnd = start + width - 1;
                        
                        if ((rStart <= wEnd) && (wStart <= rEnd)) {
                            reallyConflicts = true;
                            break;
                        }
                    }
                }
                if (reallyConflicts) break;
            }
            
            if (!reallyConflicts) {
                report.fail("Ruta en conflictSet no tiene conflicto real: " + 
                    r.getFrom() + "->" + r.getTo() + " fs[" + r.getFsIndexBegin() + "]");
            }
        }
    }
    
    // INVARIANTE 4: Validar que rollback restaura estado idéntico
    private static void validateRollbackState(Graph<Integer, Link> graph,
            Map<EstablishedRoute, EstablishedRoute> backups,
            List<EstablishedRoute> establishedRoutes,
            ValidationReport report,
            int rollbackId) {
        
        int violationCount = 0;
        Set<String> violatedRoutes = new LinkedHashSet<>();
        
        for (Map.Entry<EstablishedRoute, EstablishedRoute> entry : backups.entrySet()) {
            EstablishedRoute original = entry.getKey();
            EstablishedRoute backup = entry.getValue();
            
            boolean routeHasViolation = false;
            List<String> routeViolations = new ArrayList<>();
            
            // Verificar que los recursos en el grafo coinciden con el backup
            for (int li = 0; li < backup.getPath().size(); li++) {
                Link backupLink = backup.getPath().get(li);
                Link graphLink = findGraphLink(graph, backupLink.getFrom(), backupLink.getTo());
                
                if (graphLink != null) {
                    int fibrasPorGrupo = backup.getFibrasPorGrupo();
                    
                    for (int f = 0; f < fibrasPorGrupo; f++) {
                        Integer core = backup.getPathCores().get(li * fibrasPorGrupo + f);
                        
                        for (int fs = backup.getFsIndexBegin(); fs < backup.getFsIndexBegin() + backup.getFsWidth(); fs++) {
                            FrequencySlot backupSlot = backupLink.getCores().get(core).getFrequencySlots().get(fs);
                            FrequencySlot graphSlot = graphLink.getCores().get(core).getFrequencySlots().get(fs);
                            
                            if (backupSlot.isFree() != graphSlot.isFree() || 
                                backupSlot.getLifetime() != graphSlot.getLifetime()) {
                                violationCount++;
                                routeHasViolation = true;
                                String violation = "link " + backupLink.getFrom() + "-" + 
                                    backupLink.getTo() + " core " + core + " fs " + fs;
                                routeViolations.add(violation);
                                report.fail("Rollback incompleto en " + violation);
                                
                                if (TRACE_ROLLBACK_VALIDATION) {
                                    System.out.println("[ROLLBACK-" + rollbackId + "] ❌ VIOLACIÓN: link " + 
                                                     backupLink.getFrom() + "-" + backupLink.getTo() + 
                                                     " core " + core + " fs " + fs);
                                    System.out.println("[ROLLBACK-" + rollbackId + "]    Ruta: " + original.getFrom() + "->" + original.getTo() +
                                                     " [ID:" + Integer.toHexString(System.identityHashCode(original)) + "]");
                                    System.out.println("[ROLLBACK-" + rollbackId + "]    Backup esperado: free=" + backupSlot.isFree() + 
                                                     " lifetime=" + backupSlot.getLifetime());
                                    System.out.println("[ROLLBACK-" + rollbackId + "]    Grafo actual:    free=" + graphSlot.isFree() + 
                                                     " lifetime=" + graphSlot.getLifetime());
                                    System.out.println("[ROLLBACK-" + rollbackId + "]    Backup path:  " + pathToString(backup.getPath()));
                                    System.out.println("[ROLLBACK-" + rollbackId + "]    Backup cores: " + backup.getPathCores());
                                    System.out.println("[ROLLBACK-" + rollbackId + "]    Backup fs:    " + backup.getFsIndexBegin() + "-" + 
                                                     (backup.getFsIndexBegin() + backup.getFsWidth() - 1));
                                }
                            }
                        }
                    }
                }
            }
            
            if (routeHasViolation) {
                violatedRoutes.add(original.getFrom() + "->" + original.getTo());
            }
        }
        
        if (TRACE_ROLLBACK_VALIDATION && violationCount > 0) {
            System.out.println("[ROLLBACK-" + rollbackId + "] RESUMEN: " + violationCount + 
                             " violaciones en " + violatedRoutes.size() + " rutas: " + violatedRoutes);
        }
    }
    
    private static String pathToString(List<Link> path) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < path.size(); i++) {
            if (i > 0) sb.append("-");
            sb.append(path.get(i).getFrom());
            if (i == path.size() - 1) {
                sb.append("-").append(path.get(i).getTo());
            }
        }
        return sb.toString();
    }
    
    // INVARIANTE 6: Validar estructura pathCores FSDM
    private static void validatePathCoresStructure(EstablishedRoute route, ValidationReport report) {
        int fibrasPorGrupo = route.getFibrasPorGrupo();
        int numEnlaces = route.getPath().size();
        int expectedSize = numEnlaces * fibrasPorGrupo;
        
        if (route.getPathCores().size() != expectedSize) {
            report.fail("pathCores size incorrecto: esperado " + expectedSize + 
                ", actual " + route.getPathCores().size() + " para ruta " + 
                route.getFrom() + "->" + route.getTo());
        }
        
        // Verificar que todos los cores en un enlace pertenecen al mismo grupo
        if (fibrasPorGrupo > 1) {
            for (int li = 0; li < numEnlaces; li++) {
                Set<Integer> coresEnlace = new HashSet<>();
                for (int f = 0; f < fibrasPorGrupo; f++) {
                    coresEnlace.add(route.getPathCores().get(li * fibrasPorGrupo + f));
                }
                
                if (coresEnlace.size() != fibrasPorGrupo) {
                    report.fail("Cores duplicados en enlace " + li + " de ruta " + 
                        route.getFrom() + "->" + route.getTo());
                }
            }
        }
    }
    
    // INVARIANTE 7: Detectar sobrescrituras
    private static void validateNoOverwrites(Graph<Integer, Link> graph, 
            EstablishedRoute route, ValidationReport report) {
        int fibrasPorGrupo = route.getFibrasPorGrupo();
        
        for (int li = 0; li < route.getPath().size(); li++) {
            Link link = route.getPath().get(li);
            
            for (int f = 0; f < fibrasPorGrupo; f++) {
                Integer core = route.getPathCores().get(li * fibrasPorGrupo + f);
                
                for (int fs = route.getFsIndexBegin(); fs < route.getFsIndexBegin() + route.getFsWidth(); fs++) {
                    if (!link.getCores().get(core).getFrequencySlots().get(fs).isFree()) {
                        report.fail("SOBRESCRITURA detectada al asignar ruta " + 
                            route.getFrom() + "->" + route.getTo() + 
                            " en link " + link.getFrom() + "-" + link.getTo() + 
                            " core " + core + " fs " + fs);
                    }
                }
            }
        }
    }
    
    // Método público para imprimir reporte de validación
    public static void printValidationReport() {
        System.out.println("\n");
        System.out.println("============================================================");
        System.out.println("         REPORTE DE VALIDACIÓN DE INVARIANTES FSDM");
        System.out.println("============================================================");
        
        if (globalReport.allPassed) {
            System.out.println("✅ TODAS LAS INVARIANTES PASARON");
            System.out.println();
            System.out.println("INVARIANTE 1: Rutas en conflictSet ocupan recursos ........... PASS");
            System.out.println("INVARIANTE 2: Rutas fuera de conflictSet no modificadas ...... PASS");
            System.out.println("INVARIANTE 3: Reconfiguraciones exitosas correctas ........... PASS");
            System.out.println("INVARIANTE 4: Rollbacks restauran estado idéntico ............ PASS");
            System.out.println("INVARIANTE 5: assignFs/deallocateFs usan mismos recursos ..... PASS");
            System.out.println("INVARIANTE 6: pathCores FSDM correctamente estructurado ...... PASS");
            System.out.println("INVARIANTE 7: Sin sobrescrituras ............................. PASS");
        } else {
            System.out.println("❌ SE ENCONTRARON " + globalReport.failures.size() + " VIOLACIONES");
            System.out.println();
            for (String failure : globalReport.failures) {
                System.out.println(failure);
            }
        }
        
        System.out.println("============================================================");
        System.out.println();
    }

}
