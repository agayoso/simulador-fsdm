package py.una.pol.simulador.eon.utils;

import py.una.pol.simulador.eon.models.EstablishedRoute;
import py.una.pol.simulador.eon.models.Link;
import py.una.pol.simulador.eon.models.FrequencySlot;
import py.una.pol.simulador.eon.models.Core;
import org.jgrapht.Graph;

import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.IOException;
import java.util.*;

/**
 * Instrumentación forense para auditoría de corrección del simulador.
 * NO MODIFICA EL COMPORTAMIENTO, solo registra evidencia de posibles violaciones.
 */
public class ForensicLogger {
    
    private static final boolean ENABLE_FORENSICS = true;
    private static PrintWriter writer;
    private static int violationCount = 0;
    
    // Contadores de violaciones por tipo
    private static int deallocateFreeSlotCount = 0;
    private static int assignOccupiedSlotCount = 0;
    private static int removeFailureCount = 0;
    private static int doubleOwnershipCount = 0;
    private static int orphanSlotCount = 0;
    private static int routeWithoutResourcesCount = 0;
    
    static {
        if (ENABLE_FORENSICS) {
            try {
                writer = new PrintWriter(new FileWriter("FORENSIC_LOG.txt", false));
                writer.println("=".repeat(120));
                writer.println("FORENSIC LOG - AUDITORÍA DE CORRECCIÓN");
                writer.println("Objetivo: Determinar si BUG-1, BUG-2, BUG-3 realmente ocurren durante ejecución");
                writer.println("=".repeat(120));
                writer.println();
                writer.flush();
            } catch (IOException e) {
                System.err.println("⚠️ No se pudo crear archivo FORENSIC_LOG.txt: " + e.getMessage());
            }
        }
    }
    
    /**
     * BUG-1 FORENSICS: deallocateFs() sobre slots libres
     */
    public static void logDeallocateAttempt(EstablishedRoute route, Link link, int core, int fs, boolean wasOccupied) {
        if (!ENABLE_FORENSICS) return;
        
        if (!wasOccupied) {
            deallocateFreeSlotCount++;
            violationCount++;
            
            writer.println("❌ [BUG-1 VIOLATION #" + deallocateFreeSlotCount + "] deallocateFs() sobre slot LIBRE");
            writer.println("   Route: " + route.getFrom() + " → " + route.getTo());
            writer.println("   Path: " + formatPath(route.getPath()));
            writer.println("   Link: " + link.getFrom() + " → " + link.getTo());
            writer.println("   Core: " + core + " | FS: " + fs);
            writer.println("   Estado antes: isFree=true (❌ YA ESTABA LIBRE)");
            writer.println("   Impacto: Intento de liberar recurso que no estaba ocupado");
            writer.println();
            writer.flush();
        }
    }
    
    /**
     * BUG-2 FORENSICS: assignFs() sobre slots ocupados
     */
    public static void logAssignAttempt(EstablishedRoute route, Link link, int core, int fs, 
                                        boolean wasOccupied, int lifetime, EstablishedRoute currentOwner) {
        if (!ENABLE_FORENSICS) return;
        
        if (wasOccupied) {
            assignOccupiedSlotCount++;
            violationCount++;
            
            writer.println("❌ [BUG-2 VIOLATION #" + assignOccupiedSlotCount + "] assignFs() SOBRESCRIBE slot ocupado");
            writer.println("   Nueva ruta: " + route.getFrom() + " → " + route.getTo());
            writer.println("   Path: " + formatPath(route.getPath()));
            writer.println("   Link: " + link.getFrom() + " → " + link.getTo());
            writer.println("   Core: " + core + " | FS: " + fs);
            writer.println("   Estado antes: isFree=false, lifetime=" + lifetime);
            
            if (currentOwner != null) {
                writer.println("   Propietario actual: " + currentOwner.getFrom() + " → " + currentOwner.getTo());
                writer.println("   Path propietario: " + formatPath(currentOwner.getPath()));
            } else {
                writer.println("   Propietario actual: ❓ DESCONOCIDO (slot ocupado pero sin ruta identificada)");
            }
            
            writer.println("   Impacto: SOBRESCRITURA - Dos rutas creen que ocupan el mismo recurso");
            writer.println();
            writer.flush();
        }
    }
    
    /**
     * BUG-3 FORENSICS: removeRouteFromList() falla por equals()
     */
    public static void logRemoveAttempt(List<EstablishedRoute> list, EstablishedRoute route, 
                                       int sizeBefore, int sizeAfter) {
        if (!ENABLE_FORENSICS) return;
        
        if (sizeBefore == sizeAfter && sizeBefore > 0) {
            // La ruta NO fue eliminada (size no cambió)
            removeFailureCount++;
            violationCount++;
            
            writer.println("❌ [BUG-3 VIOLATION #" + removeFailureCount + "] removeRouteFromList() FALLO - ruta NO eliminada");
            writer.println("   Route: " + route.getFrom() + " → " + route.getTo());
            writer.println("   Lifetime actual: " + route.getLifetime());
            writer.println("   List size: " + sizeBefore + " → " + sizeAfter + " (sin cambio)");
            writer.println("   Causa probable: equals() no encuentra match por lifetime modificado");
            writer.println("   Impacto: Ruta fantasma permanece en establishedRoutes");
            writer.println();
            writer.flush();
        }
    }
    
    /**
     * INVARIANTE A-G: Validación global
     */
    public static ValidationResult validateGlobalInvariants(Graph<Integer, Link> graph, 
                                                            List<EstablishedRoute> establishedRoutes,
                                                            int tick) {
        if (!ENABLE_FORENSICS) return new ValidationResult(true);
        
        ValidationResult result = new ValidationResult(true);
        
        // INVARIANTE A: establishedRoutes ↔ recursos físicos (bidireccional)
        validateInvariantA(graph, establishedRoutes, result);
        
        // INVARIANTE B: Sin doble asignación
        validateInvariantB(graph, establishedRoutes, result);
        
        // INVARIANTE C: Toda ruta activa tiene recursos
        validateInvariantC(graph, establishedRoutes, result);
        
        // INVARIANTE D: Ninguna ruta expirada permanece
        validateInvariantD(establishedRoutes, result);
        
        // INVARIANTE E: Ningún slot ocupado sin propietario
        validateInvariantE(graph, establishedRoutes, result);
        
        if (!result.passed()) {
            writer.println("🔴 [GLOBAL VALIDATION FAILURE] Tick " + tick);
            for (String violation : result.violations) {
                writer.println("   " + violation);
            }
            writer.println();
            writer.flush();
        }
        
        return result;
    }
    
    private static void validateInvariantA(Graph<Integer, Link> graph, 
                                          List<EstablishedRoute> routes, 
                                          ValidationResult result) {
        // A1: Toda ruta en establishedRoutes → sus recursos están ocupados
        for (EstablishedRoute route : routes) {
            for (int li = 0; li < route.getPath().size(); li++) {
                Link link = route.getPath().get(li);
                int fibrasPorGrupo = route.getFibrasPorGrupo();
                
                for (int f = 0; f < fibrasPorGrupo; f++) {
                    Integer core = route.getPathCores().get(li * fibrasPorGrupo + f);
                    
                    for (int fs = route.getFsIndexBegin(); 
                         fs < route.getFsIndexBegin() + route.getFsWidth(); fs++) {
                        
                        FrequencySlot slot = link.getCores().get(core).getFrequencySlots().get(fs);
                        
                        if (slot.isFree()) {
                            result.fail("[INVARIANTE-A1] Ruta " + route.getFrom() + "→" + route.getTo() + 
                                " reclama slot LIBRE: link " + link.getFrom() + "-" + link.getTo() +
                                " core " + core + " fs " + fs);
                            routeWithoutResourcesCount++;
                        }
                    }
                }
            }
        }
        
        // A2: Todo recurso ocupado → existe ruta que lo reclama
        for (Link link : graph.edgeSet()) {
            for (int core = 0; core < link.getCores().size(); core++) {
                for (int fs = 0; fs < link.getCores().get(core).getFrequencySlots().size(); fs++) {
                    FrequencySlot slot = link.getCores().get(core).getFrequencySlots().get(fs);
                    
                    if (!slot.isFree()) {
                        boolean found = false;
                        
                        for (EstablishedRoute route : routes) {
                            if (routeUsesSlot(route, link, core, fs)) {
                                found = true;
                                break;
                            }
                        }
                        
                        if (!found) {
                            result.fail("[INVARIANTE-A2] Slot OCUPADO sin ruta: link " + 
                                link.getFrom() + "-" + link.getTo() + 
                                " core " + core + " fs " + fs + 
                                " lifetime=" + slot.getLifetime());
                            orphanSlotCount++;
                        }
                    }
                }
            }
        }
    }
    
    private static void validateInvariantB(Graph<Integer, Link> graph, 
                                          List<EstablishedRoute> routes, 
                                          ValidationResult result) {
        // Cada slot debe pertenecer a máximo 1 ruta
        for (Link link : graph.edgeSet()) {
            for (int core = 0; core < link.getCores().size(); core++) {
                for (int fs = 0; fs < link.getCores().get(core).getFrequencySlots().size(); fs++) {
                    FrequencySlot slot = link.getCores().get(core).getFrequencySlots().get(fs);
                    
                    if (!slot.isFree()) {
                        List<EstablishedRoute> owners = new ArrayList<>();
                        
                        for (EstablishedRoute route : routes) {
                            if (routeUsesSlot(route, link, core, fs)) {
                                owners.add(route);
                            }
                        }
                        
                        if (owners.size() > 1) {
                            StringBuilder sb = new StringBuilder();
                            sb.append("[INVARIANTE-B] DOBLE ASIGNACIÓN: link ")
                              .append(link.getFrom()).append("-").append(link.getTo())
                              .append(" core ").append(core).append(" fs ").append(fs)
                              .append(" ocupado por ").append(owners.size()).append(" rutas: ");
                            
                            for (EstablishedRoute owner : owners) {
                                sb.append(owner.getFrom()).append("→").append(owner.getTo()).append(" ");
                            }
                            
                            result.fail(sb.toString());
                            doubleOwnershipCount++;
                        }
                    }
                }
            }
        }
    }
    
    private static void validateInvariantC(Graph<Integer, Link> graph, 
                                          List<EstablishedRoute> routes, 
                                          ValidationResult result) {
        // Ya cubierto por validateInvariantA (A1)
    }
    
    private static void validateInvariantD(List<EstablishedRoute> routes, ValidationResult result) {
        for (EstablishedRoute route : routes) {
            if (route.getLifetime() <= 0) {
                result.fail("[INVARIANTE-D] Ruta EXPIRADA permanece en lista: " + 
                    route.getFrom() + "→" + route.getTo() + " lifetime=" + route.getLifetime());
            }
        }
    }
    
    private static void validateInvariantE(Graph<Integer, Link> graph, 
                                          List<EstablishedRoute> routes, 
                                          ValidationResult result) {
        // Ya cubierto por validateInvariantA (A2)
    }
    
    private static boolean routeUsesSlot(EstablishedRoute route, Link link, int core, int fs) {
        for (int li = 0; li < route.getPath().size(); li++) {
            Link routeLink = route.getPath().get(li);
            
            // Buscar dirección exacta O inversa (bidireccionalidad)
            if ((routeLink.getFrom() == link.getFrom() && routeLink.getTo() == link.getTo()) ||
                (routeLink.getFrom() == link.getTo() && routeLink.getTo() == link.getFrom())) {
                
                int fibrasPorGrupo = route.getFibrasPorGrupo();
                
                for (int f = 0; f < fibrasPorGrupo; f++) {
                    Integer routeCore = route.getPathCores().get(li * fibrasPorGrupo + f);
                    
                    if (routeCore.equals(core) && 
                        fs >= route.getFsIndexBegin() && 
                        fs < route.getFsIndexBegin() + route.getFsWidth()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
    
    private static String formatPath(List<Link> path) {
        if (path == null || path.isEmpty()) return "[]";
        
        StringBuilder sb = new StringBuilder();
        sb.append(path.get(0).getFrom());
        for (Link link : path) {
            sb.append("→").append(link.getTo());
        }
        return sb.toString();
    }
    
    /**
     * Cierre y resumen final
     */
    public static void finish() {
        if (!ENABLE_FORENSICS || writer == null) return;
        
        writer.println();
        writer.println("=".repeat(120));
        writer.println("RESUMEN FINAL DE AUDITORÍA FORENSE");
        writer.println("=".repeat(120));
        writer.println();
        
        writer.println("VIOLACIONES DETECTADAS POR TIPO:");
        writer.println();
        writer.println("  BUG-1 (deallocateFs sobre slots libres):         " + deallocateFreeSlotCount);
        writer.println("  BUG-2 (assignFs sobrescribe slots ocupados):     " + assignOccupiedSlotCount);
        writer.println("  BUG-3 (removeRouteFromList falla):               " + removeFailureCount);
        writer.println("  INVARIANTE-B (doble asignación):                 " + doubleOwnershipCount);
        writer.println("  INVARIANTE-A2/E (slots huérfanos):               " + orphanSlotCount);
        writer.println("  INVARIANTE-A1/C (rutas sin recursos):            " + routeWithoutResourcesCount);
        writer.println();
        writer.println("  TOTAL DE VIOLACIONES:                            " + violationCount);
        writer.println();
        
        writer.println("=".repeat(120));
        writer.println("CLASIFICACIÓN DE BUGS:");
        writer.println("=".repeat(120));
        writer.println();
        
        if (deallocateFreeSlotCount > 0) {
            writer.println("  BUG-1: ❌ CONFIRMADO - " + deallocateFreeSlotCount + " casos de deallocateFs() sobre slots libres");
        } else {
            writer.println("  BUG-1: ✅ NO CONFIRMADO - Nunca se llamó deallocateFs() sobre slots libres");
        }
        
        if (assignOccupiedSlotCount > 0) {
            writer.println("  BUG-2: ❌ CONFIRMADO - " + assignOccupiedSlotCount + " casos de assignFs() sobrescribiendo slots ocupados");
        } else {
            writer.println("  BUG-2: ✅ NO CONFIRMADO - Nunca se sobrescribieron slots ocupados");
        }
        
        if (removeFailureCount > 0) {
            writer.println("  BUG-3: ❌ CONFIRMADO - " + removeFailureCount + " casos de removeRouteFromList() fallando");
        } else {
            writer.println("  BUG-3: ✅ NO CONFIRMADO - Todas las eliminaciones fueron exitosas");
        }
        
        writer.println();
        writer.println("=".repeat(120));
        
        writer.close();
        
        // También imprimir resumen en consola
        System.out.println();
        System.out.println("=".repeat(120));
        System.out.println("FORENSIC AUDIT SUMMARY");
        System.out.println("=".repeat(120));
        System.out.println("BUG-1 violations: " + deallocateFreeSlotCount);
        System.out.println("BUG-2 violations: " + assignOccupiedSlotCount);
        System.out.println("BUG-3 violations: " + removeFailureCount);
        System.out.println("TOTAL violations: " + violationCount);
        System.out.println("Ver FORENSIC_LOG.txt para detalles completos");
        System.out.println("=".repeat(120));
    }
    
    /**
     * Clase auxiliar para resultados de validación
     */
    public static class ValidationResult {
        private boolean passed;
        private List<String> violations = new ArrayList<>();
        
        public ValidationResult(boolean passed) {
            this.passed = passed;
        }
        
        public void fail(String violation) {
            this.passed = false;
            this.violations.add(violation);
        }
        
        public boolean passed() {
            return passed;
        }
        
        public List<String> getViolations() {
            return violations;
        }
    }
}
