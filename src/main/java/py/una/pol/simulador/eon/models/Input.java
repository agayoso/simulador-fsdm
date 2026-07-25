/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package py.una.pol.simulador.eon.models;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import py.una.pol.simulador.eon.models.enums.RSAEnum;
import py.una.pol.simulador.eon.models.enums.TopologiesEnum;

/**
 * Parámetros de entrada del simulador
 *
 *
 */
@Data
public class Input {

    /**
     * Tiempo total de simulación
     */
    private Integer simulationTime;
    /**
     * Cantidad de demandas a insertar
     */
    private Integer demands;
    /**
     * Listado de topologías a simular
     */
    private List<TopologiesEnum> topologies;
    /**
     * Listado de algoritmos a utilizar
     */
    private List<RSAEnum> algorithms;
    /**
     * Ancho de banda de las ranuras de frecuencias
     */
    private BigDecimal fsWidth;
    /**
     * Capacidad total de cada enlace
     */
    private Integer capacity;
    /**
     * Volumen de tráfico
     */
    private Integer erlang;
    /**
     * Cantidad de demandas promedio a insertar por unidad de tiempo
     */
    private Integer lambda;
    /**
     * Cantidad mínima de ranuras de frecuencia que puede ocupar cada demanda
     */
    private Integer fsRangeMin;
    /**
     * Cantidad máxima de ranuras de frecuencia que puede ocupar cada demanda
     */
    private Integer fsRangeMax;
    /**
     * Cantidad de núcleos de la red
     */
    private Integer cores;
    /**
     * Nivel máximo de crosstalk tolerable
     */
    private BigDecimal maxCrosstalk;
    /**
     * Listado de Crosstalk por unidad de longitud (h) de las distintas fibras a
     * simular
     */
    private List<Double> crosstalkPerUnitLenghtList;

    /**
     * Cantidad de fibras que forman un grupo en arquitectura FSDM
     */
    private Integer fibrasPorGrupo;

    /**
     * Estructura de grupos de fibras calculada automáticamente.
     * Cada elemento de la lista externa es un grupo.
     * Cada grupo contiene los índices de las fibras que lo componen.
     */
    private List<List<Integer>> grupos;

    /**
     * Calcula automáticamente los grupos de fibras basándose en la cantidad
     * total de fibras (cores) y la cantidad de fibras por grupo.
     *
     * Ejemplo: 6 fibras, 2 fibras por grupo →
     *   Grupo 0: [0, 1]
     *   Grupo 1: [2, 3]
     *   Grupo 2: [4, 5]
     */
    public void calcularGrupos() {
        if (cores == null || fibrasPorGrupo == null || fibrasPorGrupo == 0) {
            return;
        }

        grupos = new ArrayList<>();
        int cantidadGrupos = cores / fibrasPorGrupo;

        for (int g = 0; g < cantidadGrupos; g++) {
            List<Integer> grupo = new ArrayList<>();
            for (int f = 0; f < fibrasPorGrupo; f++) {
                grupo.add(g * fibrasPorGrupo + f);
            }
            grupos.add(grupo);
        }
    }

}
