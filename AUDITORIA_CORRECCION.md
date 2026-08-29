# 🔍 AUDITORÍA DE CORRECCIÓN DEL SIMULADOR EON

**Fecha:** 2026-08-28  
**Objetivo:** Determinar si podemos confiar en los datos producidos por el simulador para realizar experimentos de tesis  
**Metodología:** Análisis exhaustivo del código + propuesta de invariantes + diseño de pruebas

---

## 📋 RESUMEN EJECUTIVO

### Estado general: ⚠️ **REQUIERE VALIDACIÓN ADICIONAL**

**Correcciones ya implementadas:**
- ✅ Detección bidireccional en `posicionDelEnlaceEnRuta()`
- ✅ Orden de rollback (4 fases: deallocate all → restore all → update all)
- ✅ 76 violaciones FSDM → 0

**Hallazgos críticos de esta auditoría:**
- 🔴 **3 bugs confirmados** que afectan corrección
- 🟡 **5 áreas no demostradas** que requieren instrumentación
- 🟢 **4 comportamientos confirmados como correctos**
- ⚪ **2 problemas de reproducibilidad** (no afectan corrección pero impiden experimentos A/B)

---

## 🔴 BUGS CONFIRMADOS DE CORRECCIÓN

### BUG-1: `deallocateFs()` no valida que los slots estén realmente ocupados
**Severidad:** 🔴 CRÍTICA  
**Estado:** CONFIRMADO por inspección de código

**Código problemático:**
```java
// Utils.java líneas 264+
for (int i = establishedRoute.getFsIndexBegin(); i < establishedRoute.getFsIndexBegin() + establishedRoute.getFsWidth(); i++) {
    link.getCores().get(core).getFrequencySlots().get(i).setFree(true);  // ← Sin validación
    link.getCores().get(core).getFrequencySlots().get(i).setLifetime(0);
}
```

**Problema:**
- `deallocateFs()` libera slots **sin verificar** que estén ocupados
- Si se llama dos veces sobre la misma ruta → libera slots que ya están libres
- Si otra ruta usa ese slot → lo libera incorrectamente
- **Posible corrupción:** ruta A ocupando slot, `deallocateFs(ruta B)` lo libera por error

**Impacto:**
- Rutas fantasma: recursos ocupados por rutas que ya no existen en `establishedRoutes`
- Recursos liberados prematuramente
- Inconsistencia entre `establishedRoutes` y estado físico

**Invariante violada:**
```
INVARIANTE-8: deallocateFs() debe operar SOLAMENTE sobre recursos ocupados
  ∀ slot liberado: slot.isFree() == false ANTES de deallocateFs()
```

**Evidencia necesaria:**
- Instrumentar `deallocateFs()` para detectar liberación de slots libres
- Contador de "double-free" attempts

---

### BUG-2: `assignFs()` detecta sobrescrituras pero no las previene
**Severidad:** 🔴 CRÍTICA  
**Estado:** CONFIRMADO por instrumentación existente

**Código existente:**
```java
// Utils.java líneas 202-213
if (ENABLE_ASSIGNFS_OVERWRITE_DETECTION) {
    boolean wasOccupied = !link.getCores().get(core).getFrequencySlots().get(i).isFree();
    if (wasOccupied) {
        System.out.println("\n⚠️ ALERTA ASSIGNFS: Sobrescribiendo slot ocupado");
        // ... imprime diagnóstico ...
    }
}
// ← Continúa la asignación de todos modos
link.getCores().get(core).getFrequencySlots().get(i).setFree(false);
```

**Problema:**
- La detección existe pero es **pasiva**
- El simulador continúa ejecutando después de sobrescribir
- Los datos posteriores son **no confiables**

**Impacto:**
- Doble asignación del mismo recurso físico
- Dos rutas creen que ocupan el mismo slot
- Bloqueos artificialmente bajos (recursos sobrescritos en lugar de generar bloqueo)
- **Los resultados experimentales NO son válidos si ocurre una sobrescritura**

**Invariante violada:**
```
INVARIANTE-9: assignFs() debe operar SOLAMENTE sobre recursos libres
  ∀ slot asignado: slot.isFree() == true ANTES de assignFs()
  Si violado → simulación debe ABORTARSE (resultado inválido)
```

**Propuesta:**
```java
if (wasOccupied) {
    System.err.println("❌ CORRUPCIÓN DETECTADA: assignFs() sobre slot ocupado");
    System.err.println("La simulación NO ES VÁLIDA y debe descartarse");
    throw new IllegalStateException("Sobrescritura de recursos detectada");
}
```

---

### BUG-3: `removeRouteFromList()` usa `remove(Object)` que depende de `equals()`
**Severidad:** 🟡 MEDIA (potencialmente crítica)  
**Estado:** CONFIRMADO por inspección de código

**Código problemático:**
```java
// Defragmenter.java línea 1911
private static void removeRouteFromList(List<EstablishedRoute> list, EstablishedRoute r) {
    if (r != null) {
        list.remove(r);  // ← Usa equals() de Lombok @Data
    }
}
```

**Problema con Lombok @Data:**
```java
@Data  // ← Genera equals() basado en TODOS los campos
public class EstablishedRoute {
    private Integer fsIndexBegin;
    private Integer fsWidth;
    private Integer lifetime;  // ← CAMBIA en cada tick
    private List<Link> path;
    private List<Integer> pathCores;
    // ...
}
```

**Escenario problemático:**
1. Ruta establecida: `route1` con `lifetime=100`
2. Se agrega a `establishedRoutes`
3. Pasa 1 tick: `route1.subLifeTime()` → `lifetime=99`
4. Intento de remover: `establishedRoutes.remove(route1)`
5. **Fallo:** `equals()` compara `lifetime=99` vs `lifetime=100` en la lista

**Impacto:**
- Rutas no se eliminan correctamente de `establishedRoutes`
- Acumulación de rutas "fantasma"
- Intentos de deallocación sobre rutas que ya no deberían existir

**Invariante violada:**
```
INVARIANTE-10: establishedRoutes debe reflejar exactamente las rutas activas
  |establishedRoutes| == número de rutas con recursos asignados
```

**Propuesta de fix:**
```java
// Usar identidad de objeto en lugar de equals()
private static void removeRouteFromList(List<EstablishedRoute> list, EstablishedRoute r) {
    if (r != null) {
        list.removeIf(route -> route == r);  // Comparación por identidad
    }
}
```

**O mejor: usar índice explícito**
```java
// SimulatorTest.java línea 204
for (int ri = 0; ri < establishedRoutes.size(); ri++) {
    EstablishedRoute route = establishedRoutes.get(ri);
    if (route.getLifetime().equals(0)) {
        Utils.deallocateFs(graph, route, crosstalkPerUnitLength);
        establishedRoutes.remove(ri);  // ← Ya usa índice (CORRECTO)
        ri--;
    }
}
```

**Conclusión:** El código de expiración en `SimulatorTest` es CORRECTO (usa índice), pero `removeRouteFromList()` en `Defragmenter` es PROBLEMÁTICO.

---

## 🟡 ÁREAS NO DEMOSTRADAS (REQUIEREN INSTRUMENTACIÓN)

### ND-1: Consistencia `establishedRoutes` ↔ recursos físicos
**Estado:** NO DEMOSTRADO

**Pregunta:**
¿Existe siempre correspondencia biunívoca entre:
- Rutas en `establishedRoutes`
- Slots marcados como ocupados (`isFree=false`)

**Invariante propuesta:**
```
INVARIANTE-11: Consistencia bidireccional establecida-física
  A) ∀ ruta ∈ establishedRoutes → sus recursos en el grafo tienen isFree=false
  B) ∀ slot con isFree=false → ∃ ruta ∈ establishedRoutes que lo reclama
```

**Prueba necesaria:**
```java
public static ValidationReport validateGlobalConsistency(
    Graph<Integer, Link> graph, 
    List<EstablishedRoute> establishedRoutes) {
    
    ValidationReport report = new ValidationReport();
    
    // A) Verificar ruta → recursos
    for (EstablishedRoute route : establishedRoutes) {
        for (int li = 0; li < route.getPath().size(); li++) {
            Link link = route.getPath().get(li);
            int fibrasPorGrupo = route.getFibrasPorGrupo();
            
            for (int f = 0; f < fibrasPorGrupo; f++) {
                Integer core = route.getPathCores().get(li * fibrasPorGrupo + f);
                
                for (int fs = route.getFsIndexBegin(); 
                     fs < route.getFsIndexBegin() + route.getFsWidth(); fs++) {
                    
                    FrequencySlot slot = link.getCores().get(core).getFrequencySlots().get(fs);
                    
                    if (slot.isFree()) {
                        report.fail("Ruta " + route.getFrom() + "->" + route.getTo() + 
                            " reclama slot LIBRE: link " + link.getFrom() + "-" + link.getTo() +
                            " core " + core + " fs " + fs);
                    }
                }
            }
        }
    }
    
    // B) Verificar recursos → ruta
    for (Link link : graph.edgeSet()) {
        for (int core = 0; core < link.getCores().size(); core++) {
            for (int fs = 0; fs < link.getCores().get(core).getFrequencySlots().size(); fs++) {
                FrequencySlot slot = link.getCores().get(core).getFrequencySlots().get(fs);
                
                if (!slot.isFree()) {
                    // Buscar ruta que reclame este slot
                    boolean found = false;
                    
                    for (EstablishedRoute route : establishedRoutes) {
                        if (routeUsesSlot(route, link, core, fs)) {
                            found = true;
                            break;
                        }
                    }
                    
                    if (!found) {
                        report.fail("Slot OCUPADO sin ruta asociada: link " + 
                            link.getFrom() + "-" + link.getTo() + 
                            " core " + core + " fs " + fs + 
                            " lifetime=" + slot.getLifetime());
                    }
                }
            }
        }
    }
    
    return report;
}

private static boolean routeUsesSlot(EstablishedRoute route, Link link, int core, int fs) {
    for (int li = 0; li < route.getPath().size(); li++) {
        Link routeLink = route.getPath().get(li);
        
        if (routeLink.getFrom() == link.getFrom() && routeLink.getTo() == link.getTo()) {
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
```

**Llamar en:** Después de cada operación crítica (assign, deallocate, defrag exitosa, rollback)

---

### ND-2: Enlaces bidireccionales - todas las operaciones respetan simetría
**Estado:** PARCIALMENTE VALIDADO

**Ya validado:**
- ✅ `createTopology()` crea cores compartidos correctamente
- ✅ `posicionDelEnlaceEnRuta()` reconoce direcciones inversas

**No validado:**
- ❓ ¿Todas las búsquedas de enlaces respetan bidireccionalidad?
- ❓ ¿`findGraphLink()` solo busca dirección exacta?

**Código a revisar:**
```java
// Defragmenter.java línea 1615
private static Link findGraphLink(Graph<Integer, Link> graph, int from, int to) {
    return graph.edgeSet().stream()
            .filter(link -> link.getFrom() == from && link.getTo() == to)
            .findFirst()
            .orElse(null);
}
```

**Análisis:**
- `findGraphLink()` busca **solo dirección exacta** (from→to)
- Usado en `restoreSingleRoute()` para restaurar backups
- Backup tiene la dirección correcta (copia de la ruta original)
- **CORRECTO:** No necesita buscar dirección inversa aquí

**Pregunta pendiente:**
¿Hay alguna otra búsqueda de enlaces que compare direcciones sin considerar bidireccionalidad?

**Búsqueda recomendada:**
```bash
grep -n "link.getFrom()" src/main/java/**/*.java | grep "link.getTo()"
# Revisar cada comparación manual de direcciones
```

---

### ND-3: Expiración de rutas - liberación completa
**Estado:** CÓDIGO APARENTEMENTE CORRECTO, NO PROBADO

**Código de expiración:**
```java
// SimulatorTest.java líneas 200-210
for (EstablishedRoute route : establishedRoutes) {
    route.subLifeTime();
}

for (int ri = 0; ri < establishedRoutes.size(); ri++) {
    EstablishedRoute route = establishedRoutes.get(ri);
    if (route.getLifetime().equals(0)) {
        Utils.deallocateFs(graph, route, crosstalkPerUnitLength);  // ← Liberar recursos
        establishedRoutes.remove(ri);                               // ← Remover de lista
        ri--;                                                       // ← Ajustar índice
    }
}
```

**Análisis:**
- ✅ Usa índice (evita problema de `equals()`)
- ✅ Ajusta índice después de `remove()`
- ✅ Llama `deallocateFs()` antes de remover
- ⚠️ **Asume** que `deallocateFs()` funciona correctamente

**Invariante a validar:**
```
INVARIANTE-12: Expiración libera completamente los recursos
  ANTES: ruta con lifetime=1, slots ocupados
  route.subLifeTime() → lifetime=0
  deallocateFs() + remove()
  DESPUÉS: slots libres, ruta no en establishedRoutes
```

**Prueba necesaria:**
```java
@Test
public void testExpiration() {
    // Setup: crear topología y ruta
    Graph<Integer, Link> graph = Utils.createTopology(...);
    EstablishedRoute route = crearRutaDePrueba();
    route.setLifetime(2);
    
    // Capturar estado inicial
    List<EstablishedRoute> routes = new ArrayList<>();
    Utils.assignFs(graph, route, h);
    routes.add(route);
    
    Set<String> slotsBefore = getSlotsOcupados(graph, route);
    assertEquals(false, slotsBefore.isEmpty(), "Debe haber slots ocupados");
    
    // Simular 2 ticks
    route.subLifeTime();  // lifetime=1
    route.subLifeTime();  // lifetime=0
    
    // Expiración
    Utils.deallocateFs(graph, route, h);
    routes.remove(route);
    
    // Validar: todos los slots deben estar libres
    Set<String> slotsAfter = getSlotsOcupados(graph, route);
    assertEquals(true, slotsAfter.isEmpty(), 
        "Después de expiración NO debe haber slots ocupados. Slots residuales: " + slotsAfter);
    
    // Validar: ruta no debe estar en lista
    assertFalse(routes.contains(route));
}
```

---

### ND-4: Crosstalk refleja estado actual
**Estado:** NO DEMOSTRADO

**Problema potencial:**
¿El crosstalk calculado corresponde al estado actual de las rutas establecidas?

**Escenarios de riesgo:**
1. Ruta A se establece → crosstalk actualizado
2. Ruta B se establece → crosstalk actualizado
3. Ruta A expira → `deallocateFs()` libera slots
4. **¿Se resta el crosstalk que Ruta A aportaba?**

**Código de deallocateFs (crosstalk):**
```java
// Utils.java líneas 273+
if (crosstalkPerUnitLength >= FSDM_CROSSTALK_THRESHOLD) {
    List<Integer> coreVecinos = getCoreVecinos(core);
    for (int i = establishedRoute.getFsIndexBegin(); 
         i < establishedRoute.getFsIndexBegin() + establishedRoute.getFsWidth(); i++) {
        for (Integer coreIndex = 0; coreIndex < link.getCores().size(); coreIndex++) {
            if (!core.equals(coreIndex) && coreVecinos.contains(coreIndex)) {
                double crosstalk = XT(getCantidadVecinos(coreIndex), crosstalkPerUnitLength, link.getDistance());
                BigDecimal crosstalkDB = toDB(crosstalk);
                link.getCores().get(coreIndex).getFrequencySlots().get(i).setCrosstalk(
                    link.getCores().get(coreIndex).getFrequencySlots().get(i).getCrosstalk().subtract(crosstalkDB));  // ← RESTA
            }
        }
    }
}
```

**Análisis:**
- ✅ `assignFs()` suma crosstalk
- ✅ `deallocateFs()` **resta** crosstalk
- ✅ Arquitectura correcta

**Invariante a validar:**
```
INVARIANTE-13: Crosstalk refleja rutas activas
  crosstalk de un slot = suma de contribuciones de rutas vecinas ocupadas actualmente
```

**Prueba necesaria:**
```java
@Test
public void testCrosstalkConsistency() {
    Graph<Integer, Link> graph = Utils.createTopology(...);
    
    // Establecer ruta que cause crosstalk en vecino
    EstablishedRoute route1 = crearRuta(core=0);
    Utils.assignFs(graph, route1, h);
    
    BigDecimal crosstalkCore1 = obtenerCrosstalkSlot(graph, link, core=1, fs);
    assertTrue(crosstalkCore1.compareTo(BigDecimal.ZERO) > 0, 
        "Debe haber crosstalk en core vecino");
    
    // Liberar ruta
    Utils.deallocateFs(graph, route1, h);
    
    BigDecimal crosstalkDespues = obtenerCrosstalkSlot(graph, link, core=1, fs);
    assertEquals(BigDecimal.ZERO, crosstalkDespues,
        "Crosstalk debe volver a cero después de liberar");
}
```

---

### ND-5: Defragmentación exitosa - estado consistente
**Estado:** PARCIALMENTE VALIDADO

**Ya validado:**
- ✅ INVARIANTE 3: rutas reconfiguradas exitosamente tienen recursos asignados

**No validado:**
- ❓ ¿Las rutas ANTIGUAS fueron completamente desasignadas?
- ❓ ¿No quedan slots fantasma de las rutas movidas?

**Invariante adicional necesaria:**
```
INVARIANTE-14: Defragmentación exitosa no deja slots huérfanos
  DESPUÉS de defrag exitosa con moved={r1→r1', r2→r2'}:
    A) r1 y r2 no están en establishedRoutes
    B) r1' y r2' están en establishedRoutes
    C) Slots de r1 y r2 están libres
    D) Slots de r1' y r2' están ocupados
```

**Prueba necesaria:** Capturar snapshot completo antes/después y comparar.

---

## 🟢 COMPORTAMIENTOS CONFIRMADOS COMO CORRECTOS

### OK-1: Arquitectura de enlaces bidireccionales
**Estado:** ✅ CONFIRMADO CORRECTO

**Código:**
```java
// Utils.java líneas 68-81
List<Core> sharedCores = new ArrayList<>();
for (int j = 0; j < numberOfCores; j++) {
    Core core = new Core(fsWidth, capacity);
    sharedCores.add(core);
}

Link linkForward = new Link(distance, sharedCores, vertex, connection);
Link linkBackward = new Link(distance, sharedCores, connection, vertex);

g.addEdge(vertex, connection, linkForward);
g.addEdge(connection, vertex, linkBackward);
```

**Análisis:**
- ✅ Ambos enlaces comparten **exactamente los mismos objetos** `Core`
- ✅ Modificar `linkForward.getCores().get(0)` modifica automáticamente `linkBackward.getCores().get(0)`
- ✅ Arquitectura física correcta para fibras bidireccionales

**Conclusión:** La representación de enlaces bidireccionales es **semánticamente correcta**.

---

### OK-2: Detección bidireccional en posicionDelEnlaceEnRuta()
**Estado:** ✅ CONFIRMADO CORRECTO (fix ya aplicado)

**Código:**
```java
// Defragmenter.java líneas 1028-1029
if ((li.getFrom() == from && li.getTo() == to) ||
    (li.getFrom() == to && li.getTo() == from)) {
    return i;
}
```

**Análisis:**
- ✅ Reconoce tanto `A→B` como `B→A`
- ✅ Evita fallo en rollback cuando ruta reinsertada usa dirección inversa

---

### OK-3: Orden de rollback (4 fases)
**Estado:** ✅ CONFIRMADO CORRECTO (fix ya aplicado)

**Código:**
```java
// FASE 1: deallocate(nueva)
// FASE 2: deallocate(ALL reinsertadas)
// FASE 3: restore(backup) + restore(ALL originales)
// FASE 4: replaceRouteInList(ALL)
```

**Análisis:**
- ✅ Todas las desasignaciones antes de todas las restauraciones
- ✅ Evita sobrescritura en enlaces inversos
- ✅ 76 violaciones → 0

---

### OK-4: Preservación de metadatos en copyRoute()
**Estado:** ✅ CONFIRMADO CORRECTO

**Código:**
```java
// Defragmenter.java línea 1547
return new EstablishedRoute(copiedPath, route.getFsIndexBegin(), route.getFsWidth(),
    route.getLifetime(), route.getFrom(), route.getTo(), 
    new ArrayList<>(route.getPathCores()), 
    route.getOriginalDemandFs(),      // ← Preservado
    route.getFibrasPorGrupo());       // ← Preservado
```

**Análisis:**
- ✅ Usa constructor de 9 parámetros
- ✅ Preserva `originalDemandFs` y `fibrasPorGrupo`
- ✅ Copia profunda de `pathCores` y `path`

---

## ⚪ PROBLEMAS DE REPRODUCIBILIDAD

### REPRO-1: Generación aleatoria sin seed fija
**Severidad:** ⚪ NO AFECTA CORRECCIÓN, IMPIDE EXPERIMENTOS CONTROLADOS

**Fuentes de aleatoriedad:**

1. **MathUtils.poisson()** - cantidad de demandas por tiempo
   ```java
   a = (Math.random() * 1) + 0;  // ← Sin seed
   ```

2. **MathUtils.getLifetime()** - duración de demandas
   ```java
   a = (Math.random() * 1) + 0;  // ← Sin seed
   ```

3. **Utils.generateDemands()** - source, destination, fs
   ```java
   rand = new Random();  // ← Sin seed, usa timestamp
   Integer source = rand.nextInt(cantNodos);
   Integer destination = rand.nextInt(cantNodos);
   Integer fs = (int) (Math.random() * (fsMax - fsMin + 1)) + fsMin;
   ```

**Impacto:**
- Cada corrida genera workload diferente
- Imposible comparar OLD vs FIXED con mismo workload
- Comparación 299 vs 357 es **científicamente inválida**

**Solución recomendada:**
Ver documento anterior sobre serialización de demandas.

---

### REPRO-2: Diferencia en número de demandas (5011 vs 5037)
**Severidad:** ⚪ ESPERADO POR ALEATORIEDAD

**Explicación:**
```
input.setDemands(5000);  // ← Target, no exacto
```

La generación Poisson produce cantidad variable:
- Corrida 1: 5011 demandas
- Corrida 2: 5037 demandas
- Diferencia: +26 demandas (+0.5%)

**No es un bug:** Es comportamiento esperado de distribución Poisson.

**Impide comparación:** Sí, porque workloads distintos.

---

## 📊 PLAN DE PRUEBAS CONCRETAS

### TEST-1: Asignación/Liberación simétrica
**Objetivo:** Validar que `deallocateFs()` invierte exactamente `assignFs()`

```java
@Test
public void testAssignDeallocateSymmetry() {
    // Setup
    Graph<Integer, Link> graph = Utils.createTopology(TopologiesEnum.USNET, 4, ...);
    EstablishedRoute route = crearRutaDePrueba();
    
    // Capturar snapshot ANTES
    GraphSnapshot before = captureGraphState(graph);
    
    // Asignar
    Utils.assignFs(graph, route, h);
    
    // Verificar asignación
    ValidationReport reportAssign = new ValidationReport();
    validateRouteAssigned(graph, route, reportAssign);
    assertTrue(reportAssign.passed(), "assignFs debe marcar todos los slots correctamente");
    
    // Desasignar
    Utils.deallocateFs(graph, route, h);
    
    // Capturar snapshot DESPUÉS
    GraphSnapshot after = captureGraphState(graph);
    
    // Comparar: debe ser idéntico
    assertTrue(before.equals(after), 
        "Estado del grafo debe ser idéntico antes de assign y después de deallocate\n" +
        "Diferencias: " + before.diff(after));
}

private GraphSnapshot captureGraphState(Graph<Integer, Link> graph) {
    // Serializar estado completo: todos los slots de todos los cores de todos los enlaces
    Map<String, SlotState> state = new HashMap<>();
    
    for (Link link : graph.edgeSet()) {
        for (int core = 0; core < link.getCores().size(); core++) {
            for (int fs = 0; fs < link.getCores().get(core).getFrequencySlots().size(); fs++) {
                FrequencySlot slot = link.getCores().get(core).getFrequencySlots().get(fs);
                String key = link.getFrom() + "-" + link.getTo() + ":" + core + ":" + fs;
                state.put(key, new SlotState(slot.isFree(), slot.getLifetime(), slot.getCrosstalk()));
            }
        }
    }
    
    return new GraphSnapshot(state);
}
```

---

### TEST-2: Bidireccionalidad física
**Objetivo:** Verificar que A→B y B→A comparten recursos

```java
@Test
public void testBidirectionalSharing() {
    Graph<Integer, Link> graph = Utils.createTopology(TopologiesEnum.USNET, 4, ...);
    
    // Obtener ambas direcciones
    Link linkAB = graph.getAllEdges(0, 1).iterator().next();  // 0→1
    Link linkBA = graph.getAllEdges(1, 0).iterator().next();  // 1→0
    
    // Modificar un slot en dirección A→B
    linkAB.getCores().get(0).getFrequencySlots().get(10).setFree(false);
    linkAB.getCores().get(0).getFrequencySlots().get(10).setLifetime(999);
    
    // Verificar que B→A ve el mismo cambio
    assertFalse(linkBA.getCores().get(0).getFrequencySlots().get(10).isFree(),
        "Enlace inverso debe ver el slot ocupado");
    assertEquals(999, linkBA.getCores().get(0).getFrequencySlots().get(10).getLifetime(),
        "Enlace inverso debe ver el mismo lifetime");
    
    // Verificar que son el MISMO objeto
    assertSame(linkAB.getCores().get(0), linkBA.getCores().get(0),
        "Ambas direcciones deben compartir el mismo objeto Core");
}
```

---

### TEST-3: Rollback completo
**Objetivo:** Validar que rollback restaura estado idéntico

```java
@Test
public void testRollbackCompleteness() {
    Graph<Integer, Link> graph = Utils.createTopology(...);
    List<EstablishedRoute> routes = new ArrayList<>();
    
    // Establecer varias rutas
    for (int i = 0; i < 10; i++) {
        EstablishedRoute route = crearRutaAleatoria(graph);
        Utils.assignFs(graph, route, h);
        routes.add(route);
    }
    
    // Capturar estado ANTES de defragmentación
    GraphSnapshot before = captureGraphState(graph);
    List<EstablishedRoute> routesBefore = new ArrayList<>(routes);
    
    // Intentar defragmentación que falle
    Demand demandaImposible = new Demand(999, 0, 10, 320, 100, false, 50); // Imposible
    boolean exito = Defragmenter.DFbFRmax(demandaImposible, graph, routes, input, h, 3);
    
    assertFalse(exito, "Defragmentación debe fallar con demanda imposible");
    
    // Capturar estado DESPUÉS de rollback
    GraphSnapshot after = captureGraphState(graph);
    
    // VALIDAR: estado IDÉNTICO
    assertTrue(before.equals(after),
        "Rollback debe restaurar estado EXACTO\nDiferencias:\n" + before.diff(after));
    
    // VALIDAR: lista de rutas IDÉNTICA
    assertEquals(routesBefore.size(), routes.size(), "Cantidad de rutas debe ser igual");
    
    for (int i = 0; i < routesBefore.size(); i++) {
        EstablishedRoute routeBefore = routesBefore.get(i);
        EstablishedRoute routeAfter = routes.get(i);
        
        // Comparar por identidad (misma instancia)
        assertSame(routeBefore, routeAfter, 
            "Ruta " + i + " debe ser la misma instancia después de rollback");
    }
}
```

---

### TEST-4: Defragmentación exitosa consistente
**Objetivo:** Validar estado después de reconfiguración exitosa

```java
@Test
public void testSuccessfulDefragConsistency() {
    Graph<Integer, Link> graph = Utils.createTopology(...);
    List<EstablishedRoute> routes = new ArrayList<>();
    
    // Crear escenario con fragmentación
    crearEscenarioFragmentado(graph, routes);
    
    // Intentar defragmentación
    Demand demandaBloqueada = crearDemandaBloqueadaPorFragmentacion();
    boolean exito = Defragmenter.DFfullRuteoMin(demandaBloqueada, graph, routes, input, h, 3);
    
    if (exito) {
        // VALIDAR: consistencia global
        ValidationReport report = validateGlobalConsistency(graph, routes);
        assertTrue(report.passed(),
            "Después de defrag exitosa debe haber consistencia total\n" + report.getFailures());
        
        // VALIDAR: nueva demanda asignada
        boolean nuevaEncontrada = routes.stream()
            .anyMatch(r -> r.getFrom().equals(demandaBloqueada.getSource()) &&
                          r.getTo().equals(demandaBloqueada.getDestination()));
        assertTrue(nuevaEncontrada, "Nueva demanda debe estar en establishedRoutes");
        
        // VALIDAR: sin sobrescrituras
        ValidationReport reportOverwrites = new ValidationReport();
        for (EstablishedRoute route : routes) {
            validateNoOverwrites(graph, route, reportOverwrites);
        }
        assertTrue(reportOverwrites.passed(), 
            "No debe haber sobrescrituras\n" + reportOverwrites.getFailures());
    }
}
```

---

### TEST-5: Expiración completa
**Objetivo:** Validar liberación completa al expirar

```java
@Test
public void testExpirationCompleteness() {
    Graph<Integer, Link> graph = Utils.createTopology(...);
    List<EstablishedRoute> routes = new ArrayList<>();
    
    // Crear ruta con lifetime=2
    EstablishedRoute route = crearRutaDePrueba();
    route.setLifetime(2);
    Utils.assignFs(graph, route, h);
    routes.add(route);
    
    // Verificar asignación
    Set<String> slotsOcupados = getSlotsOcupados(graph, route);
    assertFalse(slotsOcupados.isEmpty(), "Debe haber slots ocupados después de assign");
    
    // Simular 2 ticks
    for (int tick = 0; tick < 2; tick++) {
        for (EstablishedRoute r : routes) {
            r.subLifeTime();
        }
        
        for (int ri = 0; ri < routes.size(); ri++) {
            EstablishedRoute r = routes.get(ri);
            if (r.getLifetime().equals(0)) {
                Utils.deallocateFs(graph, r, h);
                routes.remove(ri);
                ri--;
            }
        }
    }
    
    // VALIDAR: ruta no existe
    assertFalse(routes.contains(route), "Ruta expirada no debe estar en lista");
    
    // VALIDAR: todos los slots liberados
    Set<String> slotsPostExpiracion = getSlotsOcupados(graph, route);
    assertTrue(slotsPostExpiracion.isEmpty(),
        "Todos los slots deben estar libres después de expiración\n" +
        "Slots residuales: " + slotsPostExpiracion);
}

private Set<String> getSlotsOcupados(Graph<Integer, Link> graph, EstablishedRoute route) {
    Set<String> ocupados = new HashSet<>();
    
    for (int li = 0; li < route.getPath().size(); li++) {
        Link link = route.getPath().get(li);
        int fibrasPorGrupo = route.getFibrasPorGrupo();
        
        for (int f = 0; f < fibrasPorGrupo; f++) {
            Integer core = route.getPathCores().get(li * fibrasPorGrupo + f);
            
            for (int fs = route.getFsIndexBegin(); 
                 fs < route.getFsIndexBegin() + route.getFsWidth(); fs++) {
                
                FrequencySlot slot = link.getCores().get(core).getFrequencySlots().get(fs);
                
                if (!slot.isFree()) {
                    ocupados.add(link.getFrom() + "-" + link.getTo() + ":" + core + ":" + fs);
                }
            }
        }
    }
    
    return ocupados;
}
```

---

### TEST-6: Consistencia global periódica
**Objetivo:** Ejecutar validación completa durante simulación

```java
@Test
public void testFullSimulationWithValidation() {
    Input input = getTestingInput(2500);
    TopologiesEnum topology = TopologiesEnum.USNET;
    
    Graph<Integer, Link> graph = Utils.createTopology(topology, 4, ...);
    List<EstablishedRoute> routes = new ArrayList<>();
    
    // Generar demandas
    List<List<Demand>> workload = generarWorkload(input);
    
    int validationInterval = 100;  // Validar cada 100 ticks
    int validationFailures = 0;
    
    for (int tick = 0; tick < input.getSimulationTime(); tick++) {
        List<Demand> demands = workload.get(tick);
        
        // Procesar demandas
        for (Demand demand : demands) {
            EstablishedRoute route = Algorithms.ruteoCoreMultiple(graph, demand, input, h);
            if (route != null) {
                Utils.assignFs(graph, route, h);
                routes.add(route);
            }
        }
        
        // Expiración
        for (EstablishedRoute r : routes) {
            r.subLifeTime();
        }
        
        for (int ri = 0; ri < routes.size(); ri++) {
            EstablishedRoute r = routes.get(ri);
            if (r.getLifetime().equals(0)) {
                Utils.deallocateFs(graph, r, h);
                routes.remove(ri);
                ri--;
            }
        }
        
        // Validación periódica
        if (tick % validationInterval == 0) {
            ValidationReport report = validateGlobalConsistency(graph, routes);
            if (!report.passed()) {
                System.err.println("❌ CORRUPCIÓN DETECTADA en tick " + tick);
                System.err.println(report.getFailures());
                validationFailures++;
            }
        }
    }
    
    // Validación final
    ValidationReport finalReport = validateGlobalConsistency(graph, routes);
    assertTrue(finalReport.passed(),
        "Simulación debe terminar en estado consistente\n" + finalReport.getFailures());
    
    assertEquals(0, validationFailures, 
        "No debe haber fallas de validación durante la simulación");
}
```

---

## 📋 INVARIANTES FALTANTES

### Resumen de nuevas invariantes propuestas:

| ID | Invariante | Prioridad |
|----|------------|-----------|
| **INVARIANTE-8** | `deallocateFs()` solo libera slots ocupados | 🔴 CRÍTICA |
| **INVARIANTE-9** | `assignFs()` solo asigna slots libres (abort si viola) | 🔴 CRÍTICA |
| **INVARIANTE-10** | `establishedRoutes` refleja rutas activas exactamente | 🔴 CRÍTICA |
| **INVARIANTE-11** | Consistencia bidireccional establecida↔física | 🔴 CRÍTICA |
| **INVARIANTE-12** | Expiración libera completamente recursos | 🟡 ALTA |
| **INVARIANTE-13** | Crosstalk refleja rutas activas actuales | 🟡 ALTA |
| **INVARIANTE-14** | Defrag exitosa no deja slots huérfanos | 🟡 ALTA |

**Invariantes existentes (ya implementadas):**
- ✅ INVARIANTE-1: Rutas en conflictSet ocupan recursos
- ✅ INVARIANTE-2: Rutas fuera de conflictSet no modificadas
- ✅ INVARIANTE-3: Reconfiguraciones exitosas correctas
- ✅ INVARIANTE-4: Rollbacks restauran estado idéntico
- ✅ INVARIANTE-5: assignFs/deallocateFs usan mismos recursos
- ✅ INVARIANTE-6: pathCores FSDM correctamente estructurado
- ✅ INVARIANTE-7: Sin sobrescrituras

---

## 🎯 RESPUESTA A LA PREGUNTA CENTRAL

### **"¿Podemos confiar en los datos producidos por este simulador para realizar experimentos de tesis?"**

**Respuesta:** ⚠️ **CONDICIONAL - DESPUÉS DE CORRECCIONES Y VALIDACIÓN**

#### **Estado actual:**

1. **🔴 Bugs críticos detectados:**
   - `deallocateFs()` no valida slots ocupados → puede liberar slots incorrectos
   - `assignFs()` detecta pero no previene sobrescrituras → datos inválidos
   - `removeRouteFromList()` usa `equals()` con `lifetime` variable → posibles rutas fantasma

2. **🟡 Áreas no validadas:**
   - Consistencia global establecida↔física (nunca verificada)
   - Comportamiento de expiración (parece correcto pero no probado)
   - Crosstalk (arquitectura correcta pero no validado dinámicamente)

3. **✅ Correcciones exitosas:**
   - Enlaces bidireccionales correctamente implementados
   - Detección bidireccional en posicionDelEnlaceEnRuta()
   - Orden de rollback (4 fases)
   - 76 violaciones FSDM → 0

#### **Recomendación:**

**ANTES de usar para tesis:**

1. ✅ **Corregir BUG-1, BUG-2, BUG-3** (críticos)
2. ✅ **Implementar TEST-1 a TEST-6** (validación exhaustiva)
3. ✅ **Ejecutar TEST-6** (simulación completa con validación periódica)
4. ✅ **Implementar serialización de demandas** (reproducibilidad)
5. ✅ **Generar workload canónico** para experimentos
6. ✅ **Validar que todas las invariantes PASS** durante simulación completa

**DESPUÉS de validación:**
- ✅ Ejecutar experimentos A/B con workload reproducible
- ✅ Múltiples réplicas con diferentes seeds
- ✅ Análisis estadístico de resultados

#### **Confianza por componente:**

| Componente | Confianza | Acción requerida |
|------------|-----------|------------------|
| Arquitectura enlaces bidireccionales | ✅ ALTA | Ninguna |
| Rollback (4 fases) | ✅ ALTA | Ninguna |
| Detección bidireccional | ✅ ALTA | Ninguna |
| `assignFs()` | 🔴 BAJA | Corregir BUG-2 + validación |
| `deallocateFs()` | 🔴 BAJA | Corregir BUG-1 + validación |
| Expiración | 🟡 MEDIA | Validar TEST-5 |
| Defragmentación exitosa | 🟡 MEDIA | Validar TEST-4 |
| Consistencia global | ⚪ DESCONOCIDA | Implementar TEST-6 |

---

## 📝 PRÓXIMOS PASOS PROPUESTOS

### **Fase 1: Corrección de bugs críticos** (1-2 días)
1. Implementar validación estricta en `assignFs()` con `IllegalStateException`
2. Implementar validación estricta en `deallocateFs()`
3. Corregir `removeRouteFromList()` para usar identidad o índices

### **Fase 2: Implementación de pruebas** (2-3 días)
1. Implementar clases de soporte: `GraphSnapshot`, `ValidationReport`, etc.
2. Implementar TEST-1 a TEST-6
3. Ejecutar pruebas unitarias

### **Fase 3: Validación integral** (1 día)
1. Ejecutar TEST-6 (simulación completa con validación periódica)
2. Analizar resultados
3. Corregir bugs adicionales si se detectan

### **Fase 4: Reproducibilidad** (1-2 días)
1. Implementar serialización de demandas
2. Generar workload canónico USNET_F4_G2_E2500
3. Validar que diferentes corridas con mismo workload producen resultados idénticos

### **Fase 5: Experimentos A/B** (según necesidad)
1. Ejecutar OLD vs FIXED con workload canónico
2. Comparar métricas
3. Análisis estadístico

---

## 📌 CLASIFICACIÓN DE HALLAZGOS

### 🔴 BUGS DE CORRECCIÓN CONFIRMADOS (3)
- **BUG-1:** `deallocateFs()` no valida slots ocupados
- **BUG-2:** `assignFs()` no previene sobrescrituras
- **BUG-3:** `removeRouteFromList()` usa `equals()` problemático

### 🟡 REQUIERE INSTRUMENTACIÓN (5)
- **ND-1:** Consistencia `establishedRoutes` ↔ recursos físicos
- **ND-2:** Todas las búsquedas de enlaces respetan bidireccionalidad
- **ND-3:** Expiración libera completamente recursos
- **ND-4:** Crosstalk refleja estado actual
- **ND-5:** Defragmentación exitosa sin slots huérfanos

### ✅ COMPORTAMIENTO CORRECTO CONFIRMADO (4)
- **OK-1:** Arquitectura enlaces bidireccionales
- **OK-2:** Detección bidireccional en `posicionDelEnlaceEnRuta()`
- **OK-3:** Orden de rollback (4 fases)
- **OK-4:** Preservación de metadatos en `copyRoute()`

### ⚪ PROBLEMAS DE REPRODUCIBILIDAD (2)
- **REPRO-1:** Generación aleatoria sin seed fija
- **REPRO-2:** Cantidad de demandas variable (esperado por Poisson)

---

## 🎓 CONCLUSIÓN

El simulador ha avanzado significativamente con las correcciones bidireccionales y de rollback, **pero requiere validación adicional antes de ser considerado confiable para experimentos de tesis**.

Los bugs críticos detectados (BUG-1, BUG-2, BUG-3) deben corregirse y las invariantes propuestas deben validarse mediante las pruebas TEST-1 a TEST-6.

Una vez completadas las correcciones y validaciones, el simulador estará en condiciones de producir datos científicamente confiables para la investigación.

**El objetivo NO es optimizar desempeño, sino garantizar corrección semántica.**
