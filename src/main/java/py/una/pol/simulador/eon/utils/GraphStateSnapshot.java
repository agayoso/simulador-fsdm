package py.una.pol.simulador.eon.utils;

import java.math.BigDecimal;
import java.util.*;
import org.jgrapht.Graph;
import py.una.pol.simulador.eon.models.*;

/**
 * Captura y compara el estado completo del grafo para diagnóstico de rollback
 */
public class GraphStateSnapshot {
    
    private final Map<String, LinkState> links;
    private final long timestamp;
    private final String label;
    
    public GraphStateSnapshot(Graph<Integer, Link> graph, String label) {
        this.timestamp = System.currentTimeMillis();
        this.label = label;
        this.links = new HashMap<>();
        
        // Capturar estado de cada enlace
        for (Link link : graph.edgeSet()) {
            String key = linkKey(link);
            links.put(key, new LinkState(link));
        }
    }
    
    /**
     * Compara este snapshot con otro y reporta diferencias
     * @param other Snapshot a comparar
     * @param conflictSet Rutas conflictivas (para ignorar sus recursos)
     * @return Reporte de diferencias
     */
    public DifferenceReport compareTo(GraphStateSnapshot other, Set<EstablishedRoute> conflictSet) {
        DifferenceReport report = new DifferenceReport(this.label, other.label);
        
        // Construir set de slots que pertenecen al conflictSet
        Set<String> conflictSlots = buildConflictSlotsSet(conflictSet);
        
        // Comparar cada enlace
        for (Map.Entry<String, LinkState> entry : this.links.entrySet()) {
            String linkKey = entry.getKey();
            LinkState beforeState = entry.getValue();
            LinkState afterState = other.links.get(linkKey);
            
            if (afterState == null) {
                report.addError("Link " + linkKey + " desapareció del grafo");
                continue;
            }
            
            // Comparar cada core
            for (Map.Entry<Integer, CoreState> coreEntry : beforeState.cores.entrySet()) {
                int coreId = coreEntry.getKey();
                CoreState beforeCore = coreEntry.getValue();
                CoreState afterCore = afterState.cores.get(coreId);
                
                if (afterCore == null) {
                    report.addError("Core " + coreId + " en link " + linkKey + " desapareció");
                    continue;
                }
                
                // Comparar cada FS
                for (int fs = 0; fs < beforeCore.slots.size(); fs++) {
                    FSState beforeFS = beforeCore.slots.get(fs);
                    FSState afterFS = afterCore.slots.get(fs);
                    
                    String slotKey = linkKey + "/core" + coreId + "/fs" + fs;
                    
                    // ¿Este slot pertenece al conflictSet?
                    boolean isConflict = conflictSlots.contains(slotKey);
                    
                    // Detectar diferencias
                    if (beforeFS.free != afterFS.free) {
                        report.addDifference(slotKey, "free", 
                            beforeFS.free ? "libre" : "ocupado",
                            afterFS.free ? "libre" : "ocupado",
                            isConflict);
                    }
                    
                    if (beforeFS.lifetime != afterFS.lifetime) {
                        report.addDifference(slotKey, "lifetime",
                            String.valueOf(beforeFS.lifetime),
                            String.valueOf(afterFS.lifetime),
                            isConflict);
                    }
                    
                    if (beforeFS.crosstalk.compareTo(afterFS.crosstalk) != 0) {
                        // Solo reportar si la diferencia es significativa (>1e-15)
                        BigDecimal diff = beforeFS.crosstalk.subtract(afterFS.crosstalk).abs();
                        if (diff.compareTo(new BigDecimal("1e-15")) > 0) {
                            report.addDifference(slotKey, "crosstalk",
                                beforeFS.crosstalk.toString(),
                                afterFS.crosstalk.toString(),
                                isConflict);
                        }
                    }
                }
            }
        }
        
        return report;
    }
    
    /**
     * Construye set de strings identificando slots ocupados por el conflictSet
     */
    private Set<String> buildConflictSlotsSet(Set<EstablishedRoute> conflictSet) {
        Set<String> slots = new HashSet<>();
        
        if (conflictSet == null) {
            return slots;
        }
        
        for (EstablishedRoute route : conflictSet) {
            if (route == null || route.getPath() == null) continue;
            
            int fibrasPorGrupo = route.getFibrasPorGrupo();
            int fsBegin = route.getFsIndexBegin();
            int fsWidth = route.getFsWidth();
            
            for (int linkIdx = 0; linkIdx < route.getPath().size(); linkIdx++) {
                Link link = route.getPath().get(linkIdx);
                String linkKey = linkKey(link);
                
                // Todas las fibras del grupo en este enlace
                for (int f = 0; f < fibrasPorGrupo; f++) {
                    int core = route.getPathCores().get(linkIdx * fibrasPorGrupo + f);
                    
                    // Todos los FS ocupados por esta ruta
                    for (int fs = fsBegin; fs < fsBegin + fsWidth; fs++) {
                        slots.add(linkKey + "/core" + core + "/fs" + fs);
                    }
                }
            }
        }
        
        return slots;
    }
    
    private String linkKey(Link link) {
        return link.getFrom() + "-" + link.getTo();
    }
    
    /**
     * Estado de un enlace
     */
    private static class LinkState {
        Map<Integer, CoreState> cores;
        
        LinkState(Link link) {
            cores = new HashMap<>();
            for (int c = 0; c < link.getCores().size(); c++) {
                cores.put(c, new CoreState(link.getCores().get(c)));
            }
        }
    }
    
    /**
     * Estado de un core
     */
    private static class CoreState {
        List<FSState> slots;
        
        CoreState(Core core) {
            slots = new ArrayList<>();
            for (FrequencySlot fs : core.getFrequencySlots()) {
                slots.add(new FSState(fs));
            }
        }
    }
    
    /**
     * Estado de un frequency slot
     */
    private static class FSState {
        boolean free;
        int lifetime;
        BigDecimal crosstalk;
        
        FSState(FrequencySlot fs) {
            this.free = fs.isFree();
            this.lifetime = fs.getLifetime();
            this.crosstalk = new BigDecimal(fs.getCrosstalk().toString());
        }
    }
    
    /**
     * Reporte de diferencias entre dos snapshots
     */
    public static class DifferenceReport {
        private final String beforeLabel;
        private final String afterLabel;
        private final List<Difference> differences;
        private final List<String> errors;
        
        DifferenceReport(String beforeLabel, String afterLabel) {
            this.beforeLabel = beforeLabel;
            this.afterLabel = afterLabel;
            this.differences = new ArrayList<>();
            this.errors = new ArrayList<>();
        }
        
        void addDifference(String resource, String field, String before, String after, boolean isConflict) {
            differences.add(new Difference(resource, field, before, after, isConflict));
        }
        
        void addError(String error) {
            errors.add(error);
        }
        
        public boolean hasDifferences() {
            return !differences.isEmpty() || !errors.isEmpty();
        }
        
        public boolean hasNonConflictDifferences() {
            return differences.stream().anyMatch(d -> !d.isConflict) || !errors.isEmpty();
        }
        
        public String getReport() {
            if (!hasDifferences()) {
                return "✅ No se detectaron diferencias entre " + beforeLabel + " y " + afterLabel;
            }
            
            StringBuilder sb = new StringBuilder();
            sb.append("\n");
            sb.append("🚨 DIFERENCIAS DETECTADAS ENTRE ").append(beforeLabel).append(" Y ").append(afterLabel).append("\n");
            sb.append("=".repeat(80)).append("\n");
            
            if (!errors.isEmpty()) {
                sb.append("\n❌ ERRORES CRÍTICOS:\n");
                for (String error : errors) {
                    sb.append("  - ").append(error).append("\n");
                }
            }
            
            if (!differences.isEmpty()) {
                // Separar diferencias en conflicto vs no conflicto
                List<Difference> conflictDiffs = new ArrayList<>();
                List<Difference> nonConflictDiffs = new ArrayList<>();
                
                for (Difference d : differences) {
                    if (d.isConflict) {
                        conflictDiffs.add(d);
                    } else {
                        nonConflictDiffs.add(d);
                    }
                }
                
                if (!nonConflictDiffs.isEmpty()) {
                    sb.append("\n🔥 DIFERENCIAS EN RECURSOS NO CONFLICTIVOS (CORRUPCIÓN COLATERAL):\n");
                    int count = 0;
                    for (Difference d : nonConflictDiffs) {
                        if (count < 20) { // Limitar salida
                            sb.append(String.format("  %s | %s: %s → %s\n",
                                d.resource, d.field, d.before, d.after));
                            count++;
                        }
                    }
                    if (nonConflictDiffs.size() > 20) {
                        sb.append(String.format("  ... y %d diferencias más\n", nonConflictDiffs.size() - 20));
                    }
                    sb.append(String.format("\n  TOTAL RECURSOS NO CONFLICTIVOS ALTERADOS: %d\n", nonConflictDiffs.size()));
                }
                
                if (!conflictDiffs.isEmpty()) {
                    sb.append(String.format("\n✓ Diferencias en recursos conflictivos (esperado): %d\n", conflictDiffs.size()));
                }
            }
            
            sb.append("=".repeat(80)).append("\n");
            return sb.toString();
        }
        
        private static class Difference {
            String resource;
            String field;
            String before;
            String after;
            boolean isConflict;
            
            Difference(String resource, String field, String before, String after, boolean isConflict) {
                this.resource = resource;
                this.field = field;
                this.before = before;
                this.after = after;
                this.isConflict = isConflict;
            }
        }
    }
    
    /**
     * Verifica que la lista de rutas sea consistente (sin duplicados, sin nulos)
     */
    public static class RoutesValidator {
        public static String validate(List<EstablishedRoute> routes, String label) {
            StringBuilder sb = new StringBuilder();
            sb.append("Validación de establishedRoutes (").append(label).append("):\n");
            
            // Contar nulos
            long nullCount = routes.stream().filter(r -> r == null).count();
            if (nullCount > 0) {
                sb.append("  ❌ Rutas nulas: ").append(nullCount).append("\n");
            }
            
            // Detectar duplicados (por referencia)
            Set<EstablishedRoute> uniqueRoutes = new HashSet<>(routes);
            if (uniqueRoutes.size() < routes.size()) {
                sb.append("  ❌ Rutas duplicadas: ").append(routes.size() - uniqueRoutes.size()).append("\n");
            }
            
            // Contar rutas válidas
            long validRoutes = routes.stream()
                .filter(r -> r != null && r.getPath() != null && !r.getPath().isEmpty())
                .count();
            
            sb.append("  Total rutas: ").append(routes.size()).append("\n");
            sb.append("  Rutas válidas: ").append(validRoutes).append("\n");
            sb.append("  Rutas únicas: ").append(uniqueRoutes.size()).append("\n");
            
            return sb.toString();
        }
        
        public static String compare(List<EstablishedRoute> before, List<EstablishedRoute> after, String context) {
            StringBuilder sb = new StringBuilder();
            sb.append("\nComparación de establishedRoutes (").append(context).append("):\n");
            
            int sizeBefore = before.size();
            int sizeAfter = after.size();
            
            if (sizeBefore != sizeAfter) {
                sb.append("  🚨 DIFERENCIA DE TAMAÑO: antes=").append(sizeBefore)
                  .append(" después=").append(sizeAfter).append("\n");
            }
            
            // Rutas presentes antes pero no después
            Set<EstablishedRoute> beforeSet = new HashSet<>(before);
            Set<EstablishedRoute> afterSet = new HashSet<>(after);
            
            Set<EstablishedRoute> removed = new HashSet<>(beforeSet);
            removed.removeAll(afterSet);
            
            Set<EstablishedRoute> added = new HashSet<>(afterSet);
            added.removeAll(beforeSet);
            
            if (!removed.isEmpty()) {
                sb.append("  ❌ Rutas eliminadas: ").append(removed.size()).append("\n");
            }
            
            if (!added.isEmpty()) {
                sb.append("  ❌ Rutas añadidas: ").append(added.size()).append("\n");
            }
            
            if (removed.isEmpty() && added.isEmpty() && sizeBefore == sizeAfter) {
                sb.append("  ✅ La lista contiene las mismas rutas\n");
            }
            
            return sb.toString();
        }
    }
}
