/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package py.una.pol.simulador.eon.utils;

import java.math.BigDecimal;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.*;
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

    /* ===========================================================
   DESFRAGMENTACIÓN GUIADA POR BFR maximo(top-1 y top-3 sets con menos conflictos)
   - Núcleos: los de mayor BFR por enlace (fijos)
   - Ventanas: probamos todas y ordenamos por #conflictos
=========================================================== */
    public static boolean DFbFRmax(
            Demand demandaBloqueada,
            Graph<Integer, Link> graph,
            List<EstablishedRoute> establishedRoutes,
            int capacity, int cores,
            BigDecimal maxCrosstalk, double crosstalkPerUnitLength, int profundidad) {

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
                        graph, maxCrosstalk, crosstalkPerUnitLength, cores);

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
                            graph, d, capacity, cores, maxCrosstalk, crosstalkPerUnitLength);

                    if (re == null || re.getFsIndexBegin() == -1) {
                        // ❌ Falló reinserción de esta ruta: rollback completo de este intento
                        Utils.deallocateFs(graph, nueva, crosstalkPerUnitLength); // quitar demanda nueva
                        removeRouteFromList(establishedRoutes, nueva);

                        // Restaurar la que falló
                        restoreSingleRoute(graph, backup);

                        // Rollback de todas las ya reinsertadas (moved)
                        for (Map.Entry<EstablishedRoute, EstablishedRoute> e : moved.entrySet()) {
                            EstablishedRoute original = e.getKey();
                            EstablishedRoute reinsertada = e.getValue();
                            Utils.deallocateFs(graph, reinsertada, crosstalkPerUnitLength);
                            restoreSingleRoute(graph, backups.get(original));

                            // ====== NUEVO: volver a dejar la original en establishedRoutes ======
                            replaceRouteInList(establishedRoutes, reinsertada, original);
                        }

                        // Restaurar las restantes desasignadas que aún no se reinsertaron
                        for (EstablishedRoute rRest : mejorConflictSet) {
                            if (!moved.containsKey(rRest) && rRest != r) {
                                restoreSingleRoute(graph, backups.get(rRest));
                            }
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
            int capacity, int cores,
            BigDecimal maxCrosstalk, double crosstalkPerUnitLength, int profundidad) {

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
                        graph, maxCrosstalk, crosstalkPerUnitLength, cores);

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
                            graph, d, capacity, cores, maxCrosstalk, crosstalkPerUnitLength);

                    if (re == null || re.getFsIndexBegin() == -1) {
                        // ❌ Falló reinserción de esta ruta: rollback completo de este intento
                        Utils.deallocateFs(graph, nueva, crosstalkPerUnitLength); // quitar demanda nueva
                        removeRouteFromList(establishedRoutes, nueva);

                        // Restaurar la que falló
                        restoreSingleRoute(graph, backup);

                        // Rollback de todas las ya reinsertadas (moved)
                        for (Map.Entry<EstablishedRoute, EstablishedRoute> e : moved.entrySet()) {
                            EstablishedRoute original = e.getKey();
                            EstablishedRoute reinsertada = e.getValue();
                            Utils.deallocateFs(graph, reinsertada, crosstalkPerUnitLength);
                            restoreSingleRoute(graph, backups.get(original));
                            // ====== NUEVO: volver a dejar la original en establishedRoutes ======
                            replaceRouteInList(establishedRoutes, reinsertada, original);
                        }

                        // Restaurar las restantes desasignadas que aún no se reinsertaron
                        for (EstablishedRoute rRest : mejorConflictSet) {
                            if (!moved.containsKey(rRest) && rRest != r) {
                                restoreSingleRoute(graph, backups.get(rRest));
                            }
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
            int capacity, int cores,
            BigDecimal maxCrosstalk, double crosstalkPerUnitLenght, int profundidad) {

        // 1) Camino de la demanda
        List<Link> pathLinks = getBlockedDemandPath(demandaBloqueada, graph);
        if (pathLinks == null || pathLinks.isEmpty()) {
            log("No hay camino para la demanda bloqueada ID: " + demandaBloqueada.getId());
            return false;
        }

        // 2) Guards de tamaños usando el primer enlace del path
        int fs = demandaBloqueada.getFs();
        int slotsSize = pathLinks.get(0).getCores().get(0).getFrequencySlots().size();
        int maxStart = slotsSize - fs;
        if (fs <= 0 || maxStart < 0) {
            log("No se pudo evaluar ninguna ventana FS (fs/slots inválidos).");
            return false;
        }

// 3) Construir candidatos (min-suma por ventana)

List<VentanaMinSum> candidatos = new ArrayList<>();

        for (int start = 0; start <= maxStart; start++) {
            VentanaMinSum cand = evaluarVentanaMinSuma(pathLinks, start, fs, cores, establishedRoutes);
            if (cand != null) {
                candidatos.add(cand);
            }
        }
        if (candidatos.isEmpty()) {
            log("No se pudo evaluar ninguna ventana FS (min-suma).");
            return false;
        }

// ordenar por sumaMinConflictos asc; en empate, start más chico
        candidatos.sort((a, b) -> {
            int cmp = Integer.compare(a.sumaMinConflictos, b.sumaMinConflictos);
            return (cmp != 0) ? cmp : Integer.compare(a.start, b.start);
        });

// 4) Intentar top-k según profundidad
        int intentos = Math.min(profundidad, candidatos.size());
        for (int intento = 0; intento < intentos; intento++) {
            VentanaMinSum best = candidatos.get(intento);

            int bestStart = best.start;
            List<Integer> bestCoresPorLink = best.coresPorLink;
            Set<EstablishedRoute> bestConflictSet = best.conflictSet;

            log("FullRuteoMin (prof=" + profundidad + ") ID " + demandaBloqueada.getId()
                    + " | intento " + (intento + 1) + "/" + intentos
                    + " | start=" + bestStart
                    + " | sumaMinConflictos=" + best.sumaMinConflictos
                    + " | #conflictivas=" + bestConflictSet.size());

            Map<EstablishedRoute, EstablishedRoute> backups = createBackups(new ArrayList<>(bestConflictSet));
            List<EstablishedRoute> desasignadas = new ArrayList<>();
            Map<EstablishedRoute, EstablishedRoute> moved = new LinkedHashMap<>();

            try {
                // 4.1) Desasignar conflictivas 
                for (EstablishedRoute r : bestConflictSet) {
                    Utils.deallocateFs(graph, r, crosstalkPerUnitLenght);
                    desasignadas.add(r);
                }

                // 4.2) Insertar la demanda bloqueada
                EstablishedRoute nueva = intentarAsignarConCoresFijos(
                        demandaBloqueada, pathLinks, bestCoresPorLink, bestStart,
                        graph, maxCrosstalk, crosstalkPerUnitLenght, cores);

                if (nueva == null) {
                    // rollback simple de slots 
                    for (EstablishedRoute r : desasignadas) {
                        restoreSingleRoute(graph, backups.get(r));
                    }
                    log("Intento " + (intento + 1)
                            + ": no se pudo asignar la demanda en la ventana/cores elegidos.");
                    continue;
                }

                // === agregar la nueva ruta a establishedRoutes ===
                addRouteToList(establishedRoutes, nueva);

                // 4.3) Reinsertar conflictivas
                boolean falloReinsercion = false;

                for (EstablishedRoute r : bestConflictSet) {
                    EstablishedRoute backup = backups.get(r);
                    Demand d = demandFromRoute(r);

                    EstablishedRoute re = Algorithms.ruteoCoreMultiple(
                            graph, d, capacity, cores, maxCrosstalk, crosstalkPerUnitLenght);

                    if (re == null || re.getFsIndexBegin() == -1) {
                        // ❌ rollback total de este intento
                        Utils.deallocateFs(graph, nueva, crosstalkPerUnitLenght);
                        removeRouteFromList(establishedRoutes, nueva);

                        // Restaurar la que falló y reponer en lista
                        restoreSingleRoute(graph, backup);

                        // Deshacer re-ruteadas previas
                        for (Map.Entry<EstablishedRoute, EstablishedRoute> e : moved.entrySet()) {
                            EstablishedRoute original = e.getKey();
                            EstablishedRoute reinsertada = e.getValue();
                            Utils.deallocateFs(graph, reinsertada, crosstalkPerUnitLenght);
                            restoreSingleRoute(graph, backups.get(original));
                            replaceRouteInList(establishedRoutes, reinsertada, original);
                        }

                        // Restaurar las restantes aún no reinsertadas
                        for (EstablishedRoute rRest : bestConflictSet) {
                            if (!moved.containsKey(rRest) && rRest != r) {
                                restoreSingleRoute(graph, backups.get(rRest));
                            }
                        }

                        log("Intento " + (intento + 1) + ": rollback por fallo al reinsertar.");
                        falloReinsercion = true;
                        break;
                    } else {
                        // ✅ asignar re-ruteada + actualizar lista activa
                        Utils.assignFs(graph, re, crosstalkPerUnitLenght);
                        moved.put(r, re);
                        replaceRouteInList(establishedRoutes, r, re);
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

                // Traemos el core de la ruta que coincide con el link de la demanda bloqueada 
                Integer coreRuta = r.getPathCores().get(idx);
                //vemos si tiene el mismo core en ese enlace, compara core de la posicion del enlace de ruta 
                //que pasa por el link de la demanda bloqueada con el core con mayor bfr de ese link
                if (coreRuta == null || coreRuta != core) {
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
            if (li.getFrom() == from && li.getTo() == to) {
                return i;
            }
        }
        return -1;
    }
    
    private static EstablishedRoute intentarAsignarConCoresFijos(
        Demand demanda,
        List<Link> pathLinks,
        List<Integer> pathCores,
        int start,
        Graph<Integer, Link> graph,
        BigDecimal maxCrosstalk,
        double crosstalkPerUnitLength,
        int cores) {

    int width = demanda.getFs();
    if (pathLinks == null || pathLinks.isEmpty()) {
        return null;
    }

    int slotsSize = pathLinks.get(0).getCores().get(0).getFrequencySlots().size();
    if (width <= 0 || start < 0 || start + width > slotsSize) {
        return null;
    }

    List<BigDecimal> crosstalkFSList =
            new ArrayList<>(Collections.nCopies(width, BigDecimal.ZERO));

    for (int li = 0; li < pathLinks.size(); li++) {
        Link link = pathLinks.get(li);
        int core = pathCores.get(li);

        if (!isBlockAvailable(
                link, core, start, width, maxCrosstalk,
                crosstalkFSList, crosstalkPerUnitLength)) {
            return null;
        }

        updateCrosstalkFSList(
                crosstalkFSList, core, link, start, crosstalkPerUnitLength, width);
    }

    EstablishedRoute nueva = new EstablishedRoute(
            pathLinks,
            start,
            width,
            demanda.getLifetime(),
            demanda.getSource(),
            demanda.getDestination(),
            new ArrayList<>(pathCores)
    );

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
        d.setFs(r.getFsWidth());
        d.setLifetime(r.getLifetime());
        // si tu Demand tiene ID/tiempo, setéalos si hace falta
        return d;
    }

    private static class DefragMetrics {

        public int conteoExitos;
        public int conteoFallido;
        public int routesMoved; //acumula rutas reconfiguradas en éxitos
    }

    private static final DefragMetrics metricsBFRmax1 = new DefragMetrics();
    private static final DefragMetrics metricsBFRmax3 = new DefragMetrics();

    private static final DefragMetrics metricsBFRmin1 = new DefragMetrics();
    private static final DefragMetrics metricsBFRmin3 = new DefragMetrics();

    private static final DefragMetrics metricsFullRuteoMin1 = new DefragMetrics();
    private static final DefragMetrics metricsFullRuteoMin3 = new DefragMetrics();

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
     createBackups
   =========================================================== */
    private static Map<EstablishedRoute, EstablishedRoute> createBackups(List<EstablishedRoute> routes) {
        Map<EstablishedRoute, EstablishedRoute> backups = new HashMap<>();
        for (EstablishedRoute route : routes) {
            if (route != null) {
                EstablishedRoute backup = copyRoute(route);
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
    
    private static boolean isBlockAvailable(
        Link link,
        int core,
        int start,
        int width,
        BigDecimal maxCrosstalk,
        List<BigDecimal> crosstalkFSList,
        double crosstalkPerUnitLength) {

    List<FrequencySlot> fsSlots = link.getCores()
            .get(core)
            .getFrequencySlots()
            .subList(start, start + width);

    if (!Algorithms.isFSBlockFree(fsSlots)) {
        return false;
    }

    if (!Algorithms.isFsBlockCrosstalkFree(fsSlots, maxCrosstalk, crosstalkFSList)) {
        return false;
    }

    if (!Algorithms.isNextToCrosstalkFreeCores(
            link, maxCrosstalk, core, start, width, crosstalkPerUnitLength)) {
        return false;
    }

    int activeNeighbors = Algorithms.CalculaVecinosConCrosstalk(link, core, start, width);
    BigDecimal xtToAdd = Utils.toDB(
            Utils.XT(activeNeighbors, crosstalkPerUnitLength, link.getDistance())
    );

    for (int i = 0; i < width; i++) {
        BigDecimal newValue = crosstalkFSList.get(i).add(xtToAdd);

        if (activeNeighbors > 0 && newValue.compareTo(maxCrosstalk) > 0) {
            return false;
        }
    }

    return true;
}

private static void updateCrosstalkFSList(
        List<BigDecimal> crosstalkFSList,
        int core,
        Link link,
        int start,
        double crosstalkPerUnitLength,
        int width) {
    int activeNeighbors = Algorithms.CalculaVecinosConCrosstalk(link, core, start, width);
    BigDecimal xtToAdd = Utils.toDB(
            Utils.XT(activeNeighbors, crosstalkPerUnitLength, link.getDistance())
    );

    for (int i = 0; i < width; i++) {
        crosstalkFSList.set(i, crosstalkFSList.get(i).add(xtToAdd));
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
                copiedPath.add(new Link(link.getDistance(), copiedCores, link.getFrom(), link.getTo()));
            }
            return new EstablishedRoute(copiedPath, route.getFsIndexBegin(), route.getFsWidth(),
                    route.getLifetime(), route.getFrom(), route.getTo(), new ArrayList<>(route.getPathCores()));
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

        for (int i = 0; i < originalRoute.getPath().size(); i++) {
            Link copyLink = originalRoute.getPath().get(i);
            Link graphLink = findGraphLink(graph, copyLink.getFrom(), copyLink.getTo());

            if (graphLink != null) {
                for (int c = 0; c < graphLink.getCores().size(); c++) {
                    List<FrequencySlot> graphSlots = graphLink.getCores().get(c).getFrequencySlots();
                    List<FrequencySlot> backupSlots = copyLink.getCores().get(c).getFrequencySlots();

                    for (int fs = 0; fs < graphSlots.size(); fs++) {
                        graphSlots.get(fs).setFree(backupSlots.get(fs).isFree());
                        graphSlots.get(fs).setLifetime(backupSlots.get(fs).getLifetime());
                        graphSlots.get(fs).setCrosstalk(backupSlots.get(fs).getCrosstalk());
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
   Evalúa una ventana: para CADA enlace del camino, prueba TODOS
   los cores [0..cores-1], cuenta conflictos; elige el core con
   MENOS conflictos (tie: menor coreIndex; opcional: mayor BFR).
   Devuelve la suma de esos mínimos y la unión de las conflictivas.
----------------------------------------------------------- */
    private static VentanaMinSum evaluarVentanaMinSuma(
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
            double mejorBfr = -1.0; // BFR del core actualmente elegido para este enlace

            for (int c = 0; c < cores; c++) {
                Set<EstablishedRoute> conf = conflictosEnLinkCoreVentana(
                        link, c, start, width, establishedRoutes);

                int candSize = conf.size();
                double candBfr = bfrDeCore(link, c);

                boolean esMejor = false;

                if (mejorConf == null) {
                    // primer candidato
                    esMejor = true;
                } else if (candSize < mejorConf.size()) {
                    // menos conflictos gana
                    esMejor = true;
                } else if (candSize == mejorConf.size()) {
                    // desempate 1: mayor BFR
                    if (candBfr > mejorBfr) {
                        esMejor = true;
                    } else if (candBfr == mejorBfr && c < mejorCore) {
                        // desempate 2: índice de core más bajo
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
                // seguridad: no se pudo elegir core en este enlace
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

            // ¿Lo hace por el mismo core?
            Integer coreRuta = r.getPathCores().get(idx);
            if (coreRuta == null || coreRuta != core) {
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
            list.remove(r);
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

}
