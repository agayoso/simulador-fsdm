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
import lombok.Data;
import org.jgrapht.Graph;
import org.jgrapht.GraphPath;
import org.jgrapht.alg.shortestpath.KShortestSimplePaths;
import py.una.pol.simulador.eon.models.Demand;
import py.una.pol.simulador.eon.models.EstablishedRoute;
import py.una.pol.simulador.eon.models.FrequencySlot;
import py.una.pol.simulador.eon.models.Link;
import py.una.pol.simulador.eon.utils.Utils;


public class Algorithms {

    /**
     * Algoritmo RSA con conmutación de núcleos
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
    
    
   /**
 * RSA con conmutación de núcleos usando vecinos activos para el cálculo de crosstalk.
 * Mantiene la firma clásica para no romper el simulador ni los desfragmentadores.
 */
public static EstablishedRoute ruteoCoreMultiple(
        Graph<Integer, Link> graph,
        Demand demand,
        Integer capacity,
        Integer cores,
        BigDecimal maxCrosstalk,
        Double crosstalkPerUnitLength) {

    KShortestSimplePaths<Integer, Link> kspFinder = new KShortestSimplePaths<>(graph);
    List<GraphPath<Integer, Link>> kspaths =
            kspFinder.getPaths(demand.getSource(), demand.getDestination(), 5);

    if (kspaths == null || kspaths.isEmpty()) {
        return null;
    }

    for (GraphPath<Integer, Link> path : kspaths) {
        if (path == null) {
            continue;
        }

        for (int fsIndex = 0; fsIndex <= capacity - demand.getFs(); fsIndex++) {
            AllocationResult result = tryAllocatePath(
                    path, fsIndex, demand, cores, maxCrosstalk, crosstalkPerUnitLength
            );

            if (result.isSuccess()) {
                return new EstablishedRoute(
                        path.getEdgeList(),
                        result.getFsIndex(),
                        demand.getFs(),
                        demand.getLifetime(),
                        demand.getSource(),
                        demand.getDestination(),
                        result.getAssignedCores()
                );
            }
        }
    }

    return null;
}

/**
 * Intenta asignar núcleos a todos los enlaces de una ruta candidata
 * para un bloque de espectro específico.
 */
private static AllocationResult tryAllocatePath(
        GraphPath<Integer, Link> path,
        int fsIndex,
        Demand demand,
        Integer totalCores,
        BigDecimal maxCrosstalk,
        Double crosstalkPerUnitLength) {

    AllocationResult result = new AllocationResult();
    result.setFsIndex(fsIndex);

    List<Link> links = path.getEdgeList();
    List<Integer> currentCores = new ArrayList<>();
    List<Integer> neighborCounts = new ArrayList<>();

    // Crosstalk acumulado por slot a lo largo de la ruta
    List<BigDecimal> routeCrosstalkPerFS = new ArrayList<>();
    for (int i = 0; i < demand.getFs(); i++) {
        routeCrosstalkPerFS.add(BigDecimal.ZERO);
    }

    int maxDist = 0;

    for (Link link : links) {
        boolean linkAllocated = false;

        for (int core = 0; core < totalCores; core++) {

            // 1. Bloque de espectro libre
            List<FrequencySlot> block = link.getCores()
                    .get(core)
                    .getFrequencySlots()
                    .subList(fsIndex, fsIndex + demand.getFs());

            if (!isFSBlockFree(block)) {
                result.setFragmentationError(true);
                continue;
            }

            // 2. Crosstalk local del bloque + acumulado previo de la ruta
            if (!isFsBlockCrosstalkFree(block, maxCrosstalk, routeCrosstalkPerFS)) {
                result.setCrosstalkError(true);
                continue;
            }

            // 3. Verificar impacto sobre núcleos vecinos ya ocupados
            if (!isNextToCrosstalkFreeCores(
                    link, maxCrosstalk, core, fsIndex, demand.getFs(), crosstalkPerUnitLength)) {
                result.setCrosstalkError(true);
                continue;
            }

            // 4. Calcular XT agregado por este enlace según vecinos activos
            int activeNeighbors = CalculaVecinosConCrosstalk(link, core, fsIndex, demand.getFs());
            BigDecimal linkXT = Utils.toDB(
                    Utils.XT(activeNeighbors, crosstalkPerUnitLength, link.getDistance())
            );

            List<BigDecimal> tempCrosstalk = new ArrayList<>(routeCrosstalkPerFS);
            boolean limitExceeded = false;

            for (int i = 0; i < demand.getFs(); i++) {
                BigDecimal newVal = tempCrosstalk.get(i).add(linkXT);
                tempCrosstalk.set(i, newVal);

                // Regla del modelo: solo bloquear por exceso si hay vecinos activos
                if (activeNeighbors > 0 && newVal.compareTo(maxCrosstalk) > 0) {
                    limitExceeded = true;
                }
            }

            if (limitExceeded) {
                result.setCrosstalkError(true);
                continue;
            }

            // Asignación exitosa para este enlace
            currentCores.add(core);
            neighborCounts.add(activeNeighbors);
            routeCrosstalkPerFS = tempCrosstalk;

            if (link.getDistance() > maxDist) {
                maxDist = link.getDistance();
            }

            linkAllocated = true;
            break;
        }

        if (!linkAllocated) {
            result.setCapacityError(true);
            return result;
        }
    }

    result.setSuccess(true);
    result.setAssignedCores(currentCores);
    result.setCrosstalkNeighbors(neighborCounts);
    result.setMaxDistance(maxDist);
    return result;
}

public static Boolean isFSBlockFree(List<FrequencySlot> bloqueFS) {
    for (FrequencySlot fs : bloqueFS) {
        if (!fs.isFree()) {
            return false;
        }
    }
    return true;
}

public static Boolean isCrosstalkFree(
        FrequencySlot fs,
        BigDecimal maxCrosstalk,
        BigDecimal crosstalkRuta) {

    BigDecimal crosstalkActual = crosstalkRuta.add(fs.getCrosstalk());
    return crosstalkActual.compareTo(maxCrosstalk) <= 0;
}

/**
 * Verifica que el crosstalk ya existente en el bloque candidato,
 * sumado al acumulado actual de la ruta, no supere el umbral.
 */
public static Boolean isFsBlockCrosstalkFree(
        List<FrequencySlot> fss,
        BigDecimal maxCrosstalk,
        List<BigDecimal> crosstalkRuta) {

    for (int i = 0; i < fss.size(); i++) {
        BigDecimal crosstalkActual = crosstalkRuta.get(i).add(fss.get(i).getCrosstalk());
        if (crosstalkActual.compareTo(maxCrosstalk) > 0) {
            return false;
        }
    }
    return true;
}

/**
 * Verifica que el XT agregado a los vecinos ocupados no supere el umbral,
 * usando la cantidad de vecinos realmente activos.
 */
public static Boolean isNextToCrosstalkFreeCores(
        Link link,
        BigDecimal maxCrosstalk,
        Integer core,
        Integer fsIndexBegin,
        Integer fsWidth,
        Double crosstalkPerUnitLength) {

    List<Integer> vecinos = Utils.getCoreVecinos(core);
    int activeNeighbors = CalculaVecinosConCrosstalk(link, core, fsIndexBegin, fsWidth);

    for (Integer coreVecino : vecinos) {
        for (int i = fsIndexBegin; i < fsIndexBegin + fsWidth; i++) {
            FrequencySlot fsVecino = link.getCores()
                    .get(coreVecino)
                    .getFrequencySlots()
                    .get(i);

            if (!fsVecino.isFree()) {
                BigDecimal crosstalkASumar = Utils.toDB(
                        Utils.XT(activeNeighbors, crosstalkPerUnitLength, link.getDistance())
                );
                BigDecimal crosstalk = fsVecino.getCrosstalk().add(crosstalkASumar);

                if (activeNeighbors > 0 && crosstalk.compareTo(maxCrosstalk) >= 0) {
                    return false;
                }
            }
        }
    }
    return true;
}

/**
 * Cuenta cuántos núcleos vecinos están realmente activos en el bloque de FS.
 * Debe ser public static porque también lo usa Defragmenter.
 */
public static int CalculaVecinosConCrosstalk(
        Link link,
        Integer core,
        Integer fsIndexBegin,
        Integer fsWidth) {

    int vecinoAfectado = 0;
    List<Integer> vecinos = Utils.getCoreVecinos(core);

    for (Integer coreVecino : vecinos) {
        boolean ocupado = false;

        for (int i = fsIndexBegin; i < fsIndexBegin + fsWidth; i++) {
            FrequencySlot fsVecino = link.getCores()
                    .get(coreVecino)
                    .getFrequencySlots()
                    .get(i);

            if (!fsVecino.isFree()) {
                ocupado = true;
                break;
            }
        }

        if (ocupado) {
            vecinoAfectado++;
        }
    }

    return vecinoAfectado;
}

@Data
private static class AllocationResult {
    private boolean success = false;
    private int fsIndex;
    private boolean crosstalkError = false;
    private boolean fragmentationError = false;
    private boolean capacityError = false;

    private List<Integer> assignedCores;
    private List<Integer> crosstalkNeighbors;
    private int maxDistance;
} 
    
    
    
    
    
}
