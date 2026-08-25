/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */

/*  
*   Author: Andres Gayoso
*   Se modifica el simulador emulando
*   una red FSDM o agrupamiento de fibras
*   
*   Tema: Evaluación del impacto de la agrupación espacial de fibras
*   sobre la desfragmentación en redes ópticas elásticas con tráfico dinámico.
*/ 
package py.una.pol.simulador.eon;

import java.io.IOException;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.jgrapht.Graph;
import py.una.pol.simulador.eon.models.AssignFsResponse;
import py.una.pol.simulador.eon.models.Demand;
import py.una.pol.simulador.eon.models.EstablishedRoute;
import py.una.pol.simulador.eon.models.Input;
import py.una.pol.simulador.eon.models.Link;
import py.una.pol.simulador.eon.models.enums.RSAEnum;
import static py.una.pol.simulador.eon.models.enums.RSAEnum.CORE_UNICO;
import static py.una.pol.simulador.eon.models.enums.RSAEnum.MULTIPLES_CORES;
import py.una.pol.simulador.eon.models.enums.TopologiesEnum;
import py.una.pol.simulador.eon.rsa.Algorithms;
import py.una.pol.simulador.eon.utils.CsvExporter;
import py.una.pol.simulador.eon.utils.Defragmenter;
import py.una.pol.simulador.eon.utils.MathUtils;
import py.una.pol.simulador.eon.utils.Utils;


public class SimulatorTest {

    /**
     * Configuración inicial para el simulador
     *
     * @param erlang Erlang para la simulación
     * @return Datos de entrada del simulador
     */
    private Input getTestingInput(Integer erlang) {
        Input input = new Input();

        input.setDemands(5000);
        input.setTopologies(new ArrayList<>());
        //input.getTopologies().add(TopologiesEnum.NSFNET);
        input.getTopologies().add(TopologiesEnum.USNET);
        //input.getTopologies().add(TopologiesEnum.JPNNET);
        input.setFsWidth(new BigDecimal("12.5"));
        input.setFsRangeMax(8);
        input.setFsRangeMin(2);
        input.setCapacity(320);

        // ========== CONFIGURACIÓN AGRUPAMIENTO FSDM (ÚNICO PUNTO DE CONFIGURACIÓN) ==========
        input.setCores(4);              // Cantidad total de fibras
        input.setFibrasPorGrupo(2);     // Fibras por grupo
        input.calcularGrupos();         // Calcula automáticamente los grupos: [[0,1], [2,3], [4,5]]
        // ====================================================================================

        input.setLambda(5);
        input.setErlang(erlang);
        input.setAlgorithms(new ArrayList<>());
        //input.getAlgorithms().add(RSAEnum.CORE_UNICO);
        input.getAlgorithms().add(RSAEnum.MULTIPLES_CORES);
        input.setSimulationTime(MathUtils.getSimulationTime(input.getDemands(), input.getLambda()));
        input.setMaxCrosstalk(new BigDecimal("0.003162277660168379331998893544")); // XT = -25 dB
        //input.setMaxCrosstalk(new BigDecimal("0.031622776601683793319988935444")); // XT = -15 dB
        input.setCrosstalkPerUnitLenghtList(new ArrayList<>());
        //input.getCrosstalkPerUnitLenghtList().add((2 * Math.pow(0.0035, 2) * 0.080) / (4000000 * 0.000045));// H = 0.0035 → acoplamiento fuerte (núcleos muy cercanos o sin aislamiento).
        // input.getCrosstalkPerUnitLenghtList().add((2 * Math.pow(0.00040, 2) * 0.050) / (4000000 * 0.000040)); //H = 0.00040 → acoplamiento intermedio.

        input.getCrosstalkPerUnitLenghtList().add((2 * Math.pow(0.00000003, 2) * 0.055) / (4000000 * 0.000045)); //H = 0.00000003 → acoplamiento extremadamente débil (fibras aisladas - FSDM).

        // Mantener tus parámetros base pero ajustar:
        return input;
    }

    /**
     * Simulador
     *
     * @param args Argumentos de entrada (Vacío)
     */
    public static void main(String[] args) {
        try {
            createTable();
            // Datos de entrada
            for (int erlang = 2500; erlang <= 2500; erlang = erlang + 1000) {

                //Contadores de tiempo de ejecución de cada estrategia
                long tiempoTotalDFbFRmax1 = 0L;
                long tiempoTotalDFbFRmax3 = 0L;

                long tiempoTotalDFbFRmin1 = 0L;
                long tiempoTotalDFbFRmin3 = 0L;

                long tiempoTotalDFfullRuteoMin1 = 0L;
                long tiempoTotalDFfullRuteoMin3 = 0L;



                int bloqueos_sd = 0;
                int bloqueoBFRmax1 = 0;
                int bloqueoBFRmax3=0;

                int bloqueoBFRmin1 = 0;
                int bloqueoBFRmin3=0;

                int bloqueoFullRuteoMin1=0;
                  int bloqueoFullRuteoMin3=0;


                int demandaNro_sd = 1;
                int demandaNro_cdBFR1 = 1;
                int demandaNro_cdBFR3 = 1;

                int demandaNro_cdBFRmin1 = 1;
                int demandaNro_cdBFRmin3 = 1;

                int demandaNro_FullRuteoMin1=1;
                  int demandaNro_FullRuteoMin3=1;

                Input input = new SimulatorTest().getTestingInput(erlang);
                for (TopologiesEnum topology : input.getTopologies()) {

                    // Resetear métricas de desfragmentación al inicio de cada experimento
                    Defragmenter.resetAllMetrics();

                    // Se genera la red de acuerdo a los datos de entrada
                    Graph<Integer, Link> graph = Utils.createTopology(topology,
                           input.getCores(), input.getFsWidth(), input.getCapacity());

                    // Contador de demandas utilizado para identificación
                    Integer demandsQ = 1;
                    List<List<Demand>> listaDemandas = new ArrayList<>();
                    for (int i = 0; i < input.getSimulationTime(); i++) {
                        List<Demand> demands = Utils.generateDemands(input.getLambda(),
                                input.getSimulationTime(), input.getFsRangeMin(),
                                input.getFsRangeMax(), graph.vertexSet().size(),
                                input.getErlang() / input.getLambda(), demandsQ, i);

                        demandsQ += demands.size();
                        listaDemandas.add(demands);
                    }

                    ////////////////////////////////////////////////////////////////////////////////
                /* ===========================================================
                    Simulador sin desfragmenta
                 =========================================================== */
                    for (Double crosstalkPerUnitLength : input.getCrosstalkPerUnitLenghtList()) {
                        for (RSAEnum algorithm : input.getAlgorithms()) {
                            graph = Utils.createTopology(topology,
                                    input.getCores(), input.getFsWidth(), input.getCapacity());
                            // Lista de rutas establecidas durante la simulación
                            List<EstablishedRoute> establishedRoutes = new ArrayList<>();
                            System.out.println("Inicializando simulación del RSA " + algorithm.label() + " para erlang: " + (erlang) + " para la topología " + topology.label() + " y H = " + crosstalkPerUnitLength.toString());
                            // Iteración de unidades de tiempo
                            for (int i = 0; i < input.getSimulationTime(); i++) {
                                System.out.println("Tiempo: " + (i + 1));
                                // Generación de demandas para la unidad de tiempo
                                List<Demand> demands = listaDemandas.get(i);

                                for (Demand demand : demands) {
                                    demandaNro_sd++;
                                    EstablishedRoute establishedRoute=null;
                                    switch (algorithm) {
                                        case CORE_UNICO -> {
                    //                        establishedRoute = Algorithms.ruteoCoreUnico(graph, demand, input.getCapacity(), input.getCores(), input.getMaxCrosstalk(), crosstalkPerUnitLength);
                                        }
                                        case MULTIPLES_CORES -> {
                                            establishedRoute = Algorithms.ruteoCoreMultiple(graph, demand, input, crosstalkPerUnitLength);
                                        }
                                        default -> {
                                            establishedRoute = null;
                                        }
                                    }

                                    if (establishedRoute == null || establishedRoute.getFsIndexBegin() == -1) {
                                        //Bloqueo
                                        demand.setBlocked(true);
                                        insertData(algorithm.label(), topology.label(), "" + i, "" + demand.getId(), "" + erlang, crosstalkPerUnitLength.toString());
                                        bloqueos_sd++;

                                    } else {
                                        //Ruta establecida
                                        AssignFsResponse response = Utils.assignFs(graph, establishedRoute, crosstalkPerUnitLength);
                                        establishedRoute = response.getRoute();
                                        graph = response.getGraph();
                                        establishedRoutes.add(establishedRoute);
                                    }

                                }

                                for (EstablishedRoute route : establishedRoutes) {
                                    route.subLifeTime();
                                }

                                for (int ri = 0; ri < establishedRoutes.size(); ri++) {
                                    EstablishedRoute route = establishedRoutes.get(ri);
                                    if (route.getLifetime().equals(0)) {
                                        Utils.deallocateFs(graph, route, crosstalkPerUnitLength);
                                        establishedRoutes.remove(ri);
                                        ri--;
                                    }
                                }
                            }
                            System.out.println("TOTAL DE BLOQUEOS SIN DESFRAGMENTACION: " + bloqueos_sd);
                            System.out.println("Cantidad de demandas: " + demandaNro_sd);
                            System.out.println(System.lineSeparator());
                        }
                    }
                    //////////////////////////////////////////////////
                    /////////////////////////////////////////////////
                 /* ===========================================================
                    Simulador con desfragmentacion, BFR MAXIMO y re-ruteo minimo profundidad 1 con las mismas demandas
                 =========================================================== */
                   long inicioDFbFRmax1 = System.nanoTime();
                    for (Double crosstalkPerUnitLength : input.getCrosstalkPerUnitLenghtList()) {
                        for (RSAEnum algorithm : input.getAlgorithms()) {
                            graph = Utils.createTopology(topology,
                                    input.getCores(), input.getFsWidth(), input.getCapacity());
                            // Lista de rutas establecidas durante la simulación
                            List<EstablishedRoute> establishedRoutes = new ArrayList<>();
                            System.out.println("Inicializando simulación del RSA " + algorithm.label() + " para erlang: " + (erlang) + " para la topología " + topology.label() + " y H = " + crosstalkPerUnitLength.toString());
                            // Iteración de unidades de tiempo
                            for (int i = 0; i < input.getSimulationTime(); i++) {
                                System.out.println("Tiempo: " + (i + 1));
                                // Generación de demandas para la unidad de tiempo
                                List<Demand> demands = listaDemandas.get(i);
                                for (Demand demand : demands) {
                                    demandaNro_cdBFR1++;
                                    EstablishedRoute establishedRoute=null;
                                    switch (algorithm) {
                                        case CORE_UNICO -> {
                        //                    establishedRoute = Algorithms.ruteoCoreUnico(graph, demand, input.getCapacity(), input.getCores(), input.getMaxCrosstalk(), crosstalkPerUnitLength);
                                        }
                                        case MULTIPLES_CORES -> {
                                            establishedRoute = Algorithms.ruteoCoreMultiple(graph, demand, input, crosstalkPerUnitLength);
                                        }
                                        default -> {
                                            establishedRoute = null;
                                        }
                                    }

                                    if (establishedRoute == null || establishedRoute.getFsIndexBegin() == -1) {
                                        boolean desfragExitoso;
                                        // Si la demanda está bloqueada, intentar desfragmentar el enlace para resolver el conflicto
                                        System.out.println("COMIENZA A DESFRAGMENTAR CON DFbFRmax PROFUNDIDAD 1: ");

                                         desfragExitoso = Defragmenter.DFbFRmax(demand, graph, establishedRoutes, input, input.getCrosstalkPerUnitLenghtList().get(0),1);

                                        if (!desfragExitoso) {
                                            // Si no se pudo resolver el bloqueo, registrar el bloqueo
                                            demand.setBlocked(true);
                                            //System.out.println("Nuevo Bloqueo, no se pudo resolver bloqueo");
                                            insertData(input.getAlgorithms().get(0).label(), topology.label(), "" + i, "" + demand.getId(), "" + erlang, input.getCrosstalkPerUnitLenghtList().get(0).toString());
                                            bloqueoBFRmax1++;
                                        }

                                    } else {
                                        //Ruta establecida
                                        AssignFsResponse response = Utils.assignFs(graph, establishedRoute, crosstalkPerUnitLength);
                                        establishedRoute = response.getRoute();
                                        graph = response.getGraph();
                                        establishedRoutes.add(establishedRoute);
                                    }
                                }

                                for (EstablishedRoute route : establishedRoutes) {
                                    route.subLifeTime();
                                }

                                for (int ri = 0; ri < establishedRoutes.size(); ri++) {
                                    EstablishedRoute route = establishedRoutes.get(ri);
                                    if (route.getLifetime().equals(0)) {
                                        Utils.deallocateFs(graph, route, crosstalkPerUnitLength);
                                        establishedRoutes.remove(ri);
                                        ri--;
                                    }
                                }
                            }
                            System.out.println("TOTAL DE BLOQUEOS CON DESFRAGMENTACION DFbFRmax PROFUNDIDAD 1: " + bloqueoBFRmax1);
                            System.out.println("Cantidad de demandas: " + demandaNro_cdBFR1);
                            System.out.println(System.lineSeparator());
                        }
                    }
                    tiempoTotalDFbFRmax1 += System.nanoTime() - inicioDFbFRmax1;
                                                           //////////////////////////////////////////////////
                    /////////////////////////////////////////////////
                 /* ===========================================================
                    Simulador con desfragmentacion, BFR MAXIMO y re-ruteo minimo profundidad 3 con las mismas demandas
                 =========================================================== */
                    long inicioDFbFRmax3 = System.nanoTime();
                    for (Double crosstalkPerUnitLength : input.getCrosstalkPerUnitLenghtList()) {
                        for (RSAEnum algorithm : input.getAlgorithms()) {
                            graph = Utils.createTopology(topology,
                                    input.getCores(), input.getFsWidth(), input.getCapacity());
                            // Lista de rutas establecidas durante la simulación
                            List<EstablishedRoute> establishedRoutes = new ArrayList<>();
                            System.out.println("Inicializando simulación del RSA " + algorithm.label() + " para erlang: " + (erlang) + " para la topología " + topology.label() + " y H = " + crosstalkPerUnitLength.toString());
                            // Iteración de unidades de tiempo
                            for (int i = 0; i < input.getSimulationTime(); i++) {
                                System.out.println("Tiempo: " + (i + 1));
                                // Generación de demandas para la unidad de tiempo
                                List<Demand> demands = listaDemandas.get(i);
                                for (Demand demand : demands) {
                                    demandaNro_cdBFR3++;
                                    EstablishedRoute establishedRoute=null;
                                    switch (algorithm) {
                                        case CORE_UNICO -> {
                      //                      establishedRoute = Algorithms.ruteoCoreUnico(graph, demand, input.getCapacity(), input.getCores(), input.getMaxCrosstalk(), crosstalkPerUnitLength);
                                        }
                                        case MULTIPLES_CORES -> {
                                            establishedRoute = Algorithms.ruteoCoreMultiple(graph, demand, input, crosstalkPerUnitLength);
                                        }
                                        default -> {
                                            establishedRoute = null;
                                        }
                                    }

                                    if (establishedRoute == null || establishedRoute.getFsIndexBegin() == -1) {
                                        boolean desfragExitoso;
                                        // Si la demanda está bloqueada, intentar desfragmentar el enlace para resolver el conflicto
                                        System.out.println("COMIENZA A DESFRAGMENTAR CON DFbFRmax PROFUNDIDAD 3: ");

                                         desfragExitoso = Defragmenter.DFbFRmax(demand, graph, establishedRoutes, input, input.getCrosstalkPerUnitLenghtList().get(0),3);

                                        if (!desfragExitoso) {
                                            // Si no se pudo resolver el bloqueo, registrar el bloqueo
                                            demand.setBlocked(true);
                                            //System.out.println("Nuevo Bloqueo, no se pudo resolver bloqueo");
                                            insertData(input.getAlgorithms().get(0).label(), topology.label(), "" + i, "" + demand.getId(), "" + erlang, input.getCrosstalkPerUnitLenghtList().get(0).toString());
                                            bloqueoBFRmax3++;
                                        }

                                    } else {
                                        //Ruta establecida
                                        AssignFsResponse response = Utils.assignFs(graph, establishedRoute, crosstalkPerUnitLength);
                                        establishedRoute = response.getRoute();
                                        graph = response.getGraph();
                                        establishedRoutes.add(establishedRoute);
                                    }
                                }

                                for (EstablishedRoute route : establishedRoutes) {
                                    route.subLifeTime();
                                }

                                for (int ri = 0; ri < establishedRoutes.size(); ri++) {
                                    EstablishedRoute route = establishedRoutes.get(ri);
                                    if (route.getLifetime().equals(0)) {
                                        Utils.deallocateFs(graph, route, crosstalkPerUnitLength);
                                        establishedRoutes.remove(ri);
                                        ri--;
                                    }
                                }
                            }
                            System.out.println("TOTAL DE BLOQUEOS CON DESFRAGMENTACION DFbFRmax PROFUNDIDAD 3: " + bloqueoBFRmax3);
                            System.out.println("Cantidad de demandas: " + demandaNro_cdBFR3);
                            System.out.println(System.lineSeparator());
                        }
                    }
                    tiempoTotalDFbFRmax3 += System.nanoTime() - inicioDFbFRmax3;
                                                           //////////////////////////////////////////////////
                    /////////////////////////////////////////////////
                 /* ===========================================================
                    Simulador con desfragmentacion, BFR MINIMO y re-ruteo minimo profundidad 1 con las mismas demandas
                 =========================================================== */
                    long inicioDFbFRmin1 = System.nanoTime();
                    for (Double crosstalkPerUnitLength : input.getCrosstalkPerUnitLenghtList()) {
                        for (RSAEnum algorithm : input.getAlgorithms()) {
                            graph = Utils.createTopology(topology,
                                    input.getCores(), input.getFsWidth(), input.getCapacity());
                            // Lista de rutas establecidas durante la simulación
                            List<EstablishedRoute> establishedRoutes = new ArrayList<>();
                            System.out.println("Inicializando simulación del RSA " + algorithm.label() + " para erlang: " + (erlang) + " para la topología " + topology.label() + " y H = " + crosstalkPerUnitLength.toString());
                            // Iteración de unidades de tiempo
                            for (int i = 0; i < input.getSimulationTime(); i++) {
                                System.out.println("Tiempo: " + (i + 1));
                                // Generación de demandas para la unidad de tiempo
                                List<Demand> demands = listaDemandas.get(i);
                                for (Demand demand : demands) {
                                    demandaNro_cdBFRmin1++;
                                    EstablishedRoute establishedRoute=null;
                                    switch (algorithm) {
                                        case CORE_UNICO -> {
                    //                        establishedRoute = Algorithms.ruteoCoreUnico(graph, demand, input.getCapacity(), input.getCores(), input.getMaxCrosstalk(), crosstalkPerUnitLength);
                                        }
                                        case MULTIPLES_CORES -> {
                                            establishedRoute = Algorithms.ruteoCoreMultiple(graph, demand, input, crosstalkPerUnitLength);
                                        }
                                        default -> {
                                            establishedRoute = null;
                                        }
                                    }

                                    if (establishedRoute == null || establishedRoute.getFsIndexBegin() == -1) {
                                        boolean desfragExitoso;
                                        // Si la demanda está bloqueada, intentar desfragmentar el enlace para resolver el conflicto
                                        System.out.println("COMIENZA A DESFRAGMENTAR CON DFbFRmin PROFUNDIDAD 1: ");

                                        desfragExitoso = Defragmenter.DFbFRmin(demand, graph, establishedRoutes, input, input.getCrosstalkPerUnitLenghtList().get(0),1);

                                        if (!desfragExitoso) {
                                            // Si no se pudo resolver el bloqueo, registrar el bloqueo
                                            demand.setBlocked(true);
                                            //System.out.println("Nuevo Bloqueo, no se pudo resolver bloqueo");
                                            insertData(input.getAlgorithms().get(0).label(), topology.label(), "" + i, "" + demand.getId(), "" + erlang, input.getCrosstalkPerUnitLenghtList().get(0).toString());
                                            bloqueoBFRmin1++;
                                        }

                                    } else {
                                        //Ruta establecida
                                        AssignFsResponse response = Utils.assignFs(graph, establishedRoute, crosstalkPerUnitLength);
                                        establishedRoute = response.getRoute();
                                        graph = response.getGraph();
                                        establishedRoutes.add(establishedRoute);
                                    }
                                }

                                for (EstablishedRoute route : establishedRoutes) {
                                    route.subLifeTime();
                                }

                                for (int ri = 0; ri < establishedRoutes.size(); ri++) {
                                    EstablishedRoute route = establishedRoutes.get(ri);
                                    if (route.getLifetime().equals(0)) {
                                        Utils.deallocateFs(graph, route, crosstalkPerUnitLength);
                                        establishedRoutes.remove(ri);
                                        ri--;
                                    }
                                }
                            }
                            System.out.println("TOTAL DE BLOQUEOS CON DESFRAGMENTACION DFbFRmin PROFUNDIDAD 1: " + bloqueoBFRmin1);
                            System.out.println("Cantidad de demandas: " + demandaNro_cdBFRmin1);
                            System.out.println(System.lineSeparator());
                        }
                    }
                    tiempoTotalDFbFRmin1 += System.nanoTime() - inicioDFbFRmin1;
                                        /////////////////////////////////////////////////
                 /* ===========================================================
                    Simulador con desfragmentacion, BFR MINIMO y re-ruteo minimo profundidad 3 con las mismas demandas
                 =========================================================== */
                    long inicioDFbFRmin3 = System.nanoTime();
                    for (Double crosstalkPerUnitLength : input.getCrosstalkPerUnitLenghtList()) {
                        for (RSAEnum algorithm : input.getAlgorithms()) {
                            graph = Utils.createTopology(topology,
                                    input.getCores(), input.getFsWidth(), input.getCapacity());
                            // Lista de rutas establecidas durante la simulación
                            List<EstablishedRoute> establishedRoutes = new ArrayList<>();
                            System.out.println("Inicializando simulación del RSA " + algorithm.label() + " para erlang: " + (erlang) + " para la topología " + topology.label() + " y H = " + crosstalkPerUnitLength.toString());
                            // Iteración de unidades de tiempo
                            for (int i = 0; i < input.getSimulationTime(); i++) {
                                System.out.println("Tiempo: " + (i + 1));
                                // Generación de demandas para la unidad de tiempo
                                List<Demand> demands = listaDemandas.get(i);
                                for (Demand demand : demands) {
                                    demandaNro_cdBFRmin3++;
                                    EstablishedRoute establishedRoute=null;
                                    switch (algorithm) {
                                        case CORE_UNICO -> {
                            //                establishedRoute = Algorithms.ruteoCoreUnico(graph, demand, input.getCapacity(), input.getCores(), input.getMaxCrosstalk(), crosstalkPerUnitLength);
                                        }
                                        case MULTIPLES_CORES -> {
                                            establishedRoute = Algorithms.ruteoCoreMultiple(graph, demand, input, crosstalkPerUnitLength);
                                        }
                                        default -> {
                                            establishedRoute = null;
                                        }
                                    }

                                    if (establishedRoute == null || establishedRoute.getFsIndexBegin() == -1) {
                                        boolean desfragExitoso;
                                        // Si la demanda está bloqueada, intentar desfragmentar el enlace para resolver el conflicto
                                        System.out.println("COMIENZA A DESFRAGMENTAR CON DFbFRmin PROFUNDIDAD 3: ");

                                         desfragExitoso = Defragmenter.DFbFRmin(demand, graph, establishedRoutes, input, input.getCrosstalkPerUnitLenghtList().get(0),3);

                                        if (!desfragExitoso) {
                                            // Si no se pudo resolver el bloqueo, registrar el bloqueo
                                            demand.setBlocked(true);
                                            //System.out.println("Nuevo Bloqueo, no se pudo resolver bloqueo");
                                            insertData(input.getAlgorithms().get(0).label(), topology.label(), "" + i, "" + demand.getId(), "" + erlang, input.getCrosstalkPerUnitLenghtList().get(0).toString());
                                            bloqueoBFRmin3++;
                                        }

                                    } else {
                                        //Ruta establecida
                                        AssignFsResponse response = Utils.assignFs(graph, establishedRoute, crosstalkPerUnitLength);
                                        establishedRoute = response.getRoute();
                                        graph = response.getGraph();
                                        establishedRoutes.add(establishedRoute);
                                    }
                                }

                                for (EstablishedRoute route : establishedRoutes) {
                                    route.subLifeTime();
                                }

                                for (int ri = 0; ri < establishedRoutes.size(); ri++) {
                                    EstablishedRoute route = establishedRoutes.get(ri);
                                    if (route.getLifetime().equals(0)) {
                                        Utils.deallocateFs(graph, route, crosstalkPerUnitLength);
                                        establishedRoutes.remove(ri);
                                        ri--;
                                    }
                                }
                            }
                            System.out.println("TOTAL DE BLOQUEOS CON DESFRAGMENTACION DFbFRmin PROFUNDIDAD 3: " + bloqueoBFRmin3);
                            System.out.println("Cantidad de demandas: " + demandaNro_cdBFRmin3);
                            System.out.println(System.lineSeparator());
                        }
                    }
                    tiempoTotalDFbFRmin3 += System.nanoTime() - inicioDFbFRmin3;

                        //////////////////////////////////////////////////
                    /////////////////////////////////////////////////
                 /* ===========================================================
                    Simulador con desfragmentacion, full re-ruteo minimo profundidad 1 con las mismas demandas
                 =========================================================== */
                    long inicioDFfullRuteoMin1 = System.nanoTime();
                    for (Double crosstalkPerUnitLength : input.getCrosstalkPerUnitLenghtList()) {
                        for (RSAEnum algorithm : input.getAlgorithms()) {
                            graph = Utils.createTopology(topology,
                                    input.getCores(), input.getFsWidth(), input.getCapacity());
                            // Lista de rutas establecidas durante la simulación
                            List<EstablishedRoute> establishedRoutes = new ArrayList<>();
                            System.out.println("Inicializando simulación del RSA " + algorithm.label() + " para erlang: " + (erlang) + " para la topología " + topology.label() + " y H = " + crosstalkPerUnitLength.toString());
                            // Iteración de unidades de tiempo
                            for (int i = 0; i < input.getSimulationTime(); i++) {
                                System.out.println("Tiempo: " + (i + 1));
                                // Generación de demandas para la unidad de tiempo
                                List<Demand> demands = listaDemandas.get(i);
                                for (Demand demand : demands) {
                                    demandaNro_FullRuteoMin1++;
                                    EstablishedRoute establishedRoute=null;
                                    switch (algorithm) {
                                        case CORE_UNICO -> {
                          //                  establishedRoute = Algorithms.ruteoCoreUnico(graph, demand, input.getCapacity(), input.getCores(), input.getMaxCrosstalk(), crosstalkPerUnitLength);
                                        }
                                        case MULTIPLES_CORES -> {
                                            establishedRoute = Algorithms.ruteoCoreMultiple(graph, demand, input, crosstalkPerUnitLength);
                                        }
                                        default -> {
                                            establishedRoute = null;
                                        }
                                    }

                                    if (establishedRoute == null || establishedRoute.getFsIndexBegin() == -1) {
                                        boolean desfragExitoso;
                                        // Si la demanda está bloqueada, intentar desfragmentar el enlace para resolver el conflicto
                                        System.out.println("COMIENZA A DESFRAGMENTAR CON DFfullRuteoMin PROFUNDIDAD 1: ");

                                         desfragExitoso = Defragmenter.DFfullRuteoMin(demand, graph, establishedRoutes, input, input.getCrosstalkPerUnitLenghtList().get(0),1);

                                        if (!desfragExitoso) {
                                            // Si no se pudo resolver el bloqueo, registrar el bloqueo
                                            demand.setBlocked(true);
                                            //System.out.println("Nuevo Bloqueo, no se pudo resolver bloqueo");
                                            insertData(input.getAlgorithms().get(0).label(), topology.label(), "" + i, "" + demand.getId(), "" + erlang, input.getCrosstalkPerUnitLenghtList().get(0).toString());
                                            bloqueoFullRuteoMin1++;
                                        }

                                    } else {
                                        //Ruta establecida
                                        AssignFsResponse response = Utils.assignFs(graph, establishedRoute, crosstalkPerUnitLength);
                                        establishedRoute = response.getRoute();
                                        graph = response.getGraph();
                                        establishedRoutes.add(establishedRoute);
                                    }
                                }

                                for (EstablishedRoute route : establishedRoutes) {
                                    route.subLifeTime();
                                }

                                for (int ri = 0; ri < establishedRoutes.size(); ri++) {
                                    EstablishedRoute route = establishedRoutes.get(ri);
                                    if (route.getLifetime().equals(0)) {
                                        Utils.deallocateFs(graph, route, crosstalkPerUnitLength);
                                        establishedRoutes.remove(ri);
                                        ri--;
                                    }
                                }
                            }
                            System.out.println("TOTAL DE BLOQUEOS CON DESFRAGMENTACION DFfullRuteoMin PROFUNDIDAD 1: " + bloqueoFullRuteoMin1);
                            System.out.println("Cantidad de demandas:  " + demandaNro_FullRuteoMin1);
                            System.out.println(System.lineSeparator());
                        }
                    }
                    tiempoTotalDFfullRuteoMin1 += System.nanoTime() - inicioDFfullRuteoMin1;

                        //////////////////////////////////////////////////
                    /////////////////////////////////////////////////
                 /* ===========================================================
                    Simulador con desfragmentacion, full re-ruteo minimo profundidad 3 con las mismas demandas
                 =========================================================== */
                    long inicioDFfullRuteoMin3 = System.nanoTime();
                    for (Double crosstalkPerUnitLength : input.getCrosstalkPerUnitLenghtList()) {
                        for (RSAEnum algorithm : input.getAlgorithms()) {
                            graph = Utils.createTopology(topology,
                                    input.getCores(), input.getFsWidth(), input.getCapacity());
                            // Lista de rutas establecidas durante la simulación
                            List<EstablishedRoute> establishedRoutes = new ArrayList<>();
                            System.out.println("Inicializando simulación del RSA " + algorithm.label() + " para erlang: " + (erlang) + " para la topología " + topology.label() + " y H = " + crosstalkPerUnitLength.toString());
                            // Iteración de unidades de tiempo
                            for (int i = 0; i < input.getSimulationTime(); i++) {
                                System.out.println("Tiempo: " + (i + 1));
                                // Generación de demandas para la unidad de tiempo
                                List<Demand> demands = listaDemandas.get(i);
                                for (Demand demand : demands) {
                                    demandaNro_FullRuteoMin3++;
                                    EstablishedRoute establishedRoute=null;
                                    switch (algorithm) {
                                        case CORE_UNICO -> {
                                //            establishedRoute = Algorithms.ruteoCoreUnico(graph, demand, input.getCapacity(), input.getCores(), input.getMaxCrosstalk(), crosstalkPerUnitLength);
                                        }
                                        case MULTIPLES_CORES -> {
                                            establishedRoute = Algorithms.ruteoCoreMultiple(graph, demand, input, crosstalkPerUnitLength);
                                        }
                                        default -> {
                                            establishedRoute = null;
                                        }
                                    }

                                    if (establishedRoute == null || establishedRoute.getFsIndexBegin() == -1) {
                                        boolean desfragExitoso;
                                        // Si la demanda está bloqueada, intentar desfragmentar el enlace para resolver el conflicto
                                        System.out.println("COMIENZA A DESFRAGMENTAR CON DFfullRuteoMin PROFUNDIDAD 3: ");

                                         desfragExitoso = Defragmenter.DFfullRuteoMin(demand, graph, establishedRoutes, input, input.getCrosstalkPerUnitLenghtList().get(0),3);

                                        if (!desfragExitoso) {
                                            // Si no se pudo resolver el bloqueo, registrar el bloqueo
                                            demand.setBlocked(true);
                                            //System.out.println("Nuevo Bloqueo, no se pudo resolver bloqueo");
                                            insertData(input.getAlgorithms().get(0).label(), topology.label(), "" + i, "" + demand.getId(), "" + erlang, input.getCrosstalkPerUnitLenghtList().get(0).toString());
                                            bloqueoFullRuteoMin3++;
                                        }

                                    } else {
                                        //Ruta establecida
                                        AssignFsResponse response = Utils.assignFs(graph, establishedRoute, crosstalkPerUnitLength);
                                        establishedRoute = response.getRoute();
                                        graph = response.getGraph();
                                        establishedRoutes.add(establishedRoute);
                                    }
                                }

                                for (EstablishedRoute route : establishedRoutes) {
                                    route.subLifeTime();
                                }

                                for (int ri = 0; ri < establishedRoutes.size(); ri++) {
                                    EstablishedRoute route = establishedRoutes.get(ri);
                                    if (route.getLifetime().equals(0)) {
                                        Utils.deallocateFs(graph, route, crosstalkPerUnitLength);
                                        establishedRoutes.remove(ri);
                                        ri--;
                                    }
                                }
                            }
                            System.out.println("TOTAL DE BLOQUEOS CON DESFRAGMENTACION DFfullRuteoMin PROFUNDIDAD 3: " + bloqueoFullRuteoMin3);
                            System.out.println("Cantidad de demandas:  " + demandaNro_FullRuteoMin3);
                            System.out.println(System.lineSeparator());
                        }
                    }
                      tiempoTotalDFfullRuteoMin3 += System.nanoTime() - inicioDFfullRuteoMin3;

                    // Imprimir resumen formateado del experimento
                    imprimirResumenExperimento(
                        topology,
                        input,
                        erlang,
                        demandaNro_sd - 1, // Restamos 1 porque empieza en 1
                        bloqueos_sd,
                        bloqueoBFRmax1, bloqueoBFRmax3,
                        bloqueoBFRmin1, bloqueoBFRmin3,
                        bloqueoFullRuteoMin1, bloqueoFullRuteoMin3,
                        tiempoTotalDFbFRmax1, tiempoTotalDFbFRmax3,
                        tiempoTotalDFbFRmin1, tiempoTotalDFbFRmin3,
                        tiempoTotalDFfullRuteoMin1, tiempoTotalDFfullRuteoMin3
                    );

                }
            }

        } catch (IOException | IllegalArgumentException ex) {
            System.out.println(ex.getMessage());
        }

    }

    /**
     * Imprime un resumen formateado del experimento de simulación FSDM
     */
    private static void imprimirResumenExperimento(
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
        System.out.println("\n\n");
        System.out.println("============================================================");
        System.out.println("              FSDM REACTIVE DEFRAGMENTATION");
        System.out.println("============================================================");

        // Identificador del experimento
        String experimentId = String.format("%s_F%d_G%d_E%d",
            topology.label(),
            input.getCores(),
            input.getFibrasPorGrupo(),
            erlang);
        System.out.println("\nExperiment ID: " + experimentId);

        // CONFIGURACIÓN
        System.out.println("\nCONFIGURACIÓN");
        System.out.println("------------------------------------------------------------");
        System.out.printf("%-22s : %s%n", "Topología", topology.label());
        System.out.printf("%-22s : %d%n", "Fibras", input.getCores());
        System.out.printf("%-22s : %d%n", "Fibras por grupo", input.getFibrasPorGrupo());
        System.out.printf("%-22s : %d%n", "Cantidad de grupos", input.getGrupos().size());
        System.out.printf("%-22s : %d Erlangs%n", "Carga", erlang);
        System.out.printf("%-22s : %d%n", "Demandas procesadas", totalDemandas);

        // RESULTADOS
        System.out.println("\nRESULTADOS");
        System.out.println("------------------------------------------------------------");
        System.out.println("Sin desfragmentación");
        System.out.printf("  Bloqueos           : %d (%.3f%%)%n",
            bloqueosSinDF, (bloqueosSinDF * 100.0 / totalDemandas));
        System.out.println();

        System.out.println("DFbFRmax P1");
        System.out.printf("  Bloqueos           : %d (%.3f%%)%n",
            bloqueoBFRmax1, (bloqueoBFRmax1 * 100.0 / totalDemandas));
        System.out.println();

        System.out.println("DFbFRmax P3");
        System.out.printf("  Bloqueos           : %d (%.3f%%)%n",
            bloqueoBFRmax3, (bloqueoBFRmax3 * 100.0 / totalDemandas));
        System.out.println();

        System.out.println("DFbFRmin P1");
        System.out.printf("  Bloqueos           : %d (%.3f%%)%n",
            bloqueoBFRmin1, (bloqueoBFRmin1 * 100.0 / totalDemandas));
        System.out.println();

        System.out.println("DFbFRmin P3");
        System.out.printf("  Bloqueos           : %d (%.3f%%)%n",
            bloqueoBFRmin3, (bloqueoBFRmin3 * 100.0 / totalDemandas));
        System.out.println();

        System.out.println("DFfullRuteoMin P1");
        System.out.printf("  Bloqueos           : %d (%.3f%%)%n",
            bloqueoFullRuteoMin1, (bloqueoFullRuteoMin1 * 100.0 / totalDemandas));
        System.out.println();

        System.out.println("DFfullRuteoMin P3");
        System.out.printf("  Bloqueos           : %d (%.3f%%)%n",
            bloqueoFullRuteoMin3, (bloqueoFullRuteoMin3 * 100.0 / totalDemandas));

        // DESEMPEÑO DE LAS HEURÍSTICAS
        System.out.println("\nDESEMPEÑO DE LAS HEURÍSTICAS");
        System.out.println("------------------------------------------------------------");

        // Obtener métricas reales desde Defragmenter
        imprimirMetricasHeuristica("DFbFRmax P1", Defragmenter.metricsBFRmax1);
        imprimirMetricasHeuristica("DFbFRmax P3", Defragmenter.metricsBFRmax3);
        imprimirMetricasHeuristica("DFbFRmin P1", Defragmenter.metricsBFRmin1);
        imprimirMetricasHeuristica("DFbFRmin P3", Defragmenter.metricsBFRmin3);
        imprimirMetricasHeuristica("DFfullRuteoMin P1", Defragmenter.metricsFullRuteoMin1);
        imprimirMetricasHeuristica("DFfullRuteoMin P3", Defragmenter.metricsFullRuteoMin3);
        // TIEMPOS
        System.out.println("\nTIEMPOS");
        System.out.println("------------------------------------------------------------");
        System.out.printf("%-22s : %s%n", "DFbFRmax P1", formatDuration(tiempoBFRmax1));
        System.out.printf("%-22s : %s%n", "DFbFRmax P3", formatDuration(tiempoBFRmax3));
        System.out.printf("%-22s : %s%n", "DFbFRmin P1", formatDuration(tiempoBFRmin1));
        System.out.printf("%-22s : %s%n", "DFbFRmin P3", formatDuration(tiempoBFRmin3));
        System.out.printf("%-22s : %s%n", "DFfullRuteoMin P1", formatDuration(tiempoFullRuteoMin1));
        System.out.printf("%-22s : %s%n", "DFfullRuteoMin P3", formatDuration(tiempoFullRuteoMin3));

        System.out.println("\n============================================================\n\n");

        // Imprimir reporte de validación de invariantes
        Defragmenter.printValidationReport();

        // Exportar resultados a CSV
        CsvExporter.exportarResultado(
            topology, input, erlang, totalDemandas,
            bloqueosSinDF,
            bloqueoBFRmax1, bloqueoBFRmax3,
            bloqueoBFRmin1, bloqueoBFRmin3,
            bloqueoFullRuteoMin1, bloqueoFullRuteoMin3,
            tiempoBFRmax1, tiempoBFRmax3,
            tiempoBFRmin1, tiempoBFRmin3,
            tiempoFullRuteoMin1, tiempoFullRuteoMin3
        );
    }

    /**
     * Imprime las métricas de una heurística de desfragmentación
     */
    private static void imprimirMetricasHeuristica(String nombre, Defragmenter.DefragMetrics metrics) {
        int totalIntentos = metrics.conteoExitos + metrics.conteoFallido;
        double successRate = totalIntentos > 0
            ? (metrics.conteoExitos * 100.0 / totalIntentos)
            : 0.0;
        double promedioRutasPorExito = metrics.conteoExitos > 0
            ? ((double) metrics.routesMoved / metrics.conteoExitos)
            : 0.0;

        System.out.println(nombre);
        System.out.printf("  Éxitos             : %d%n", metrics.conteoExitos);
        System.out.printf("  Fallos             : %d%n", metrics.conteoFallido);
        System.out.printf("  Success Rate       : %.2f%%%n", successRate);
        System.out.printf("  Rutas reconfig.    : %d%n", metrics.routesMoved);
        System.out.printf("  Promedio/éxito     : %.2f%n", promedioRutasPorExito);
        System.out.println();
    }

    //Metodo para imprimir el tiempo total de cada estrategia.
    private static String formatDuration(long nanos) {
        long totalMillis = nanos / 1_000_000;
        long minutos = totalMillis / 60000;
        long segundos = (totalMillis % 60000) / 1000;
        long milisegundos = totalMillis % 1000;

        return minutos + " min " + segundos + " s " + milisegundos + " ms";
    }

    /**
     * Inserta los datos en la BD
     *
     * @param rsa Algoritmo RSA utilizado
     * @param topologia Topología de la red
     * @param tiempo Tiempo del bloqueo
     * @param demanda Demanda bloqueada
     * @param erlang Erlang de la simulación
     * @param h Crosstalk por unidad de longitud de la simulación
     */
    public static void insertData(String rsa, String topologia, String tiempo, String demanda, String erlang, String h) {
        Connection c;

        Statement stmt;

        try {

            Class.forName("org.sqlite.JDBC");

            c = DriverManager.getConnection("jdbc:sqlite:simulador.db");

            c.setAutoCommit(false);

            stmt = c.createStatement();
            String sql = "INSERT INTO Bloqueos (rsa, topologia, tiempo, demanda, erlang, h) "
                    + "VALUES ('" + rsa + "','" + topologia + "', '" + tiempo + "' ,'" + demanda + "', " + "'" + erlang + "', " + "'" + h + "')";
            stmt.executeUpdate(sql);
            stmt.close();
            c.commit();
            c.close();
        } catch (ClassNotFoundException | SQLException e) {
            System.out.println(e.getClass().getName() + ": " + e.getMessage());
            System.exit(0);
        }
    }

    /**
     * Generación de la tabla de resultados
     */
    public static void createTable() {
        Connection c;

        Statement stmt;

        try {

            Class.forName("org.sqlite.JDBC");

            c = DriverManager.getConnection("jdbc:sqlite:simulador.db");

            System.out.println("Database Opened...\n");

            stmt = c.createStatement();

            String dropTable = "DROP TABLE Bloqueos ";

            String sql = "CREATE TABLE IF NOT EXISTS Bloqueos "
                    + "("
                    + "erlang TEXT NOT NULL, "
                    + "rsa TEXT NOT NULL, "
                    + " topologia TEXT NOT NULL, "
                    + " h TEXT NOT NULL, "
                    + " tiempo TEXT NOT NULL, "
                    + " demanda TEXT NOT NULL) ";
            try {
                stmt.executeUpdate(dropTable);
            } catch (SQLException ex) {
                System.out.println(ex.getMessage());
            }
            stmt.executeUpdate(sql);
            stmt.close();
            c.close();
        } catch (ClassNotFoundException | SQLException e) {
            System.out.println(e.getClass().getName() + ": " + e.getMessage());
            System.exit(0);
        }
    }
}
