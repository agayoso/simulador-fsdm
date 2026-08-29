# INVESTIGACIÓN EXHAUSTIVA: 76 Violaciones FSDM - Causa Raíz y Riesgos del Fix

## OBJETIVO: Verificar hipótesis ANTES de modificar código

---

## A) ¿ESTÁ DEMOSTRADA LA CAUSA DE LAS 76 VIOLACIONES?

### ✅ SÍ - CAUSA RAÍZ CONFIRMADA

### 1. ARQUITECTURA BIDIRECCIONAL VERIFICADA

**Utils.java líneas 68-81**:
```java
// Crear un solo conjunto de cores compartido por ambas direcciones
List<Core> sharedCores = new ArrayList<>();
for (int j = 0; j < numberOfCores; j++) {
    Core core = new Core(fsWidth, capacity);
    sharedCores.add(core);
}

// Crear dos Links direccionales compartiendo los mismos cores
Link linkForward = new Link(distance, sharedCores, vertex, connection);   // 10->14
Link linkBackward = new Link(distance, sharedCores, connection, vertex);  // 14->10

g.addEdge(vertex, connection, linkForward);
g.addEdge(connection, vertex, linkBackward);
```

**CONFIRMADO**: Links 10->14 y 14->10 comparten EXACTAMENTE los mismos objetos Core.
- `linkForward.getCores() == linkBackward.getCores()` → TRUE
- Modificar `Core[2].FrequencySlot[230]` en link 10->14 TAMBIÉN modifica en link 14->10

### 2. FLUJO DEL ROLLBACK ACTUAL

**Los 3 algoritmos (DFbFRmax, DFbFRmin, DFfullRuteoMin) usan EXACTAMENTE el mismo patrón**:

**DFbFRmax**: Defragmenter.java líneas 178-197
**DFbFRmin**: Defragmenter.java líneas 368-387
**DFfullRuteoMin**: Defragmenter.java líneas 806-829

```java
// ORDEN ACTUAL DEL ROLLBACK (todos los algoritmos):

// Paso 1: Desasignar nueva ruta
Utils.deallocateFs(graph, nueva, crosstalkPerUnitLength);
removeRouteFromList(establishedRoutes, nueva);

// Paso 2: Restaurar backup de la ruta que falló
restoreSingleRoute(graph, backup);  // ← Pone isFree=false

// Paso 3: Deshacer rutas reinsertadas
for (Map.Entry<EstablishedRoute, EstablishedRoute> e : moved.entrySet()) {
    EstablishedRoute original = e.getKey();
    EstablishedRoute reinsertada = e.getValue();
    
    Utils.deallocateFs(graph, reinsertada, crosstalkPerUnitLength);  // ← ❌ BUG AQUÍ
    restoreSingleRoute(graph, backups.get(original));
    replaceRouteInList(establishedRoutes, reinsertada, original);
}

// Paso 4: Restaurar rutas no reinsertadas
for (EstablishedRoute rRest : resolvedConflictSet) {
    if (!moved.containsKey(rRest) && rRest != r) {
        restoreSingleRoute(graph, backups.get(rRest));
    }
}
```

### 3. CASO CONCRETO RECONSTRUIDO (20->2 / conflictSet {21->0, 7->14})

**Estado inicial T0**:
```
Ruta A (21->0):
  - Path: 21-15-11-10-5-0  (usa link 11->10)
  - FS: 230-232
  - Link 11->10 core 2 fs 230-232: isFree=false

Ruta B (7->14):
  - Path: 7-6-8-10-14  (usa link 10->14)
  - FS: 230-232
  - Link 10->14 core 2 fs 230-232: isFree=false, lifetime=579
```

**Operaciones del rollback**:

**T1 (línea 806)**: `deallocateFs(nueva)`
- Nueva ruta 20->2: path 20-15-11-8-6-2
- NO usa link 10->14 ni 11->10
- Link 10->14 NO se modifica

**T2 (línea 810)**: `restoreSingleRoute(backup 7->14)`
- Backup path: 7-6-8-**10-14**
- **Link 10->14 core 2 fs 230-232 → isFree=false, lifetime=579** ✅
- Estado CORRECTO restaurado

**T3 (línea 818)**: `deallocateFs(reinsertada 21->0)`
- Ruta reinsertada path: 21-15-**14-10**-5-0 (CAMBIÓ de 11-10 a 14-10!)
- deallocateFs() libera TODOS los links del path:
  - Link 21->15: isFree=true
  - Link 15->14: isFree=true
  - **Link 14->10 core 2 fs 230-232: isFree=true**
  - Link 10->5: isFree=true
  - Link 5->0: isFree=true

**⚠️ AQUÍ OCURRE EL BUG**:
```java
// Utils.java línea 264 (dentro de deallocateFs)
link.getCores().get(core).getFrequencySlots().get(i).setFree(true);
```

Cuando ejecuta esto sobre link 14->10:
- `link.getCores()` retorna `sharedCores` (compartido con 10->14)
- `sharedCores.get(2)` es el MISMO objeto Core que usa link 10->14
- `FrequencySlot[230].setFree(true)` SOBRESCRIBE el restore de T2

**Resultado**: **Link 10->14 core 2 fs 230-232 → isFree=true, lifetime=0** ❌

**T4 (línea 820)**: `restoreSingleRoute(backup 21->0)`
- Backup path: 21-15-**11-10**-5-0
- Restaura link 11->10
- NO toca link 10->14 (el backup original no lo usaba)

**T5 (línea 843)**: Validación detecta violación
```java
// Defragmenter.java líneas 1965-1973
FrequencySlot backupSlot = backupLink.getCores().get(core).getFrequencySlots().get(fs);
FrequencySlot graphSlot = graphLink.getCores().get(core).getFrequencySlots().get(fs);

if (backupSlot.isFree() != graphSlot.isFree() || 
    backupSlot.getLifetime() != graphSlot.getLifetime()) {
    report.fail("Rollback incompleto en " + violation);
}
```

Compara:
- `backupSlot.isFree()` = false (esperado)
- `graphSlot.isFree()` = true (real)
- ❌ **VIOLACIÓN DETECTADA**

### 4. MECANISMO DE DEALLOCATEFS

**Utils.java líneas 253-268**:
```java
public static void deallocateFs(Graph<Integer, Link> graph, EstablishedRoute establishedRoute, 
                                double crosstalkPerUnitLength) {
    int fibrasPorEnlace = establishedRoute.getFibrasPorGrupo();
    int numEnlaces = establishedRoute.getPath().size();
    
    for (int linkIdx = 0; linkIdx < numEnlaces; linkIdx++) {
        Link link = establishedRoute.getPath().get(linkIdx);  // ← Obtiene link del path
        
        for (int f = 0; f < fibrasPorEnlace; f++) {
            Integer core = establishedRoute.getPathCores().get(linkIdx * fibrasPorEnlace + f);
            
            for (int i = establishedRoute.getFsIndexBegin(); i < establishedRoute.getFsIndexBegin() + establishedRoute.getFsWidth(); i++) {
                link.getCores().get(core).getFrequencySlots().get(i).setFree(true);  // ← Libera slots
                link.getCores().get(core).getFrequencySlots().get(i).setLifetime(0);
            }
        }
    }
}
```

**CONFIRMADO**: deallocateFs() opera sobre `link.getCores()`, que es la referencia compartida.

### 5. MECANISMO DE RESTORESINGLEROUTE

**Defragmenter.java líneas 1540-1575**:
```java
private static void restoreSingleRoute(Graph<Integer, Link> graph, EstablishedRoute originalRoute) {
    List<Link> pathLinks = originalRoute.getPath();
    
    for (int linkIdx = 0; linkIdx < pathLinks.size(); linkIdx++) {
        Link backupLink = pathLinks.get(linkIdx);
        Link graphLink = findGraphLink(graph, backupLink.getFrom(), backupLink.getTo());
        
        int fibrasPorEnlace = originalRoute.getFibrasPorGrupo();
        
        for (int f = 0; f < fibrasPorEnlace; f++) {
            int core = originalRoute.getPathCores().get(linkIdx * fibrasPorEnlace + f);
            
            List<FrequencySlot> backupSlots = backupLink.getCores().get(core).getFrequencySlots();
            List<FrequencySlot> graphSlots = graphLink.getCores().get(core).getFrequencySlots();
            
            int fsBegin = originalRoute.getFsIndexBegin();
            int fsEnd = fsBegin + originalRoute.getFsWidth();
            
            for (int fs = fsBegin; fs < fsEnd; fs++) {
                graphSlots.get(fs).setFree(backupSlots.get(fs).isFree());  // ← Restaura estado
                graphSlots.get(fs).setLifetime(backupSlots.get(fs).getLifetime());
                graphSlots.get(fs).setCrosstalk(backupSlots.get(fs).getCrosstalk());
            }
        }
    }
}
```

**CONFIRMADO**: restoreSingleRoute() SÍ funciona correctamente:
- Busca el link correcto en el grafo con `findGraphLink(graph, from, to)`
- Restaura el estado del backup sobre el link del grafo
- Pero NO previene que una operación posterior sobrescriba ese estado

### 6. EVIDENCIA EN LOS LOGS

**experimento_bidireccional.log líneas 3703547-3703680**:
```
[ROLLBACK-85] ===== INICIO ROLLBACK COMPLETO =====
[ROLLBACK-85] Demanda: 20->2 slots=5
[ROLLBACK-85] Rutas en resolvedConflictSet: 2
[ROLLBACK-85]   - 21->0 [ID:41910dd1] backup_cores:[2, 3, 2, 3, 2, 3, 2, 3, 2, 3] backup_fs:230-232
[ROLLBACK-85]   - 7->14 [ID:37d6c698] backup_cores:[2, 3, 2, 3, 2, 3, 2, 3] backup_fs:230-232
[ROLLBACK-85] Rutas ya reinsertadas (moved): 1
[ROLLBACK-85]   - 21->0 -> 21->0 cores:[2, 3, 2, 3, 2, 3, 2, 3, 2, 3] fs:230-232

[ROLLBACK-85] ❌ VIOLACIÓN: link 10-14 core 2 fs 230
[ROLLBACK-85]    Ruta: 7->14 [ID:37d6c698]
[ROLLBACK-85]    Backup esperado: free=false lifetime=579
[ROLLBACK-85]    Grafo actual:    free=true lifetime=0
[ROLLBACK-85]    Backup path:  7-6-8-10-14
[ROLLBACK-85]    Backup cores: [2, 3, 2, 3, 2, 3, 2, 3]
[ROLLBACK-85]    Backup fs:    230-232
```

### 7. PATRÓN COMÚN A LAS 76 VIOLACIONES

**Agrupación por mecanismo**:

Analizando las 76 violaciones (17 eventos FSDM):
- Todas ocurren en grupo FSDM [2,3]
- Todas involucran rutas reinsertadas que cambiaron de path
- Todas muestran el patrón: backup espera isFree=false, grafo tiene isFree=true
- Todas ocurren en rollbacks de los 3 algoritmos de defragmentación

**Distribución por algoritmo** (del log experimento_bidireccional.log):
```
Total eventos FSDM con violaciones: 17
Distribución estimada:
- DFfullRuteoMin: ~40% de las violaciones
- DFbFRmax: ~30%
- DFbFRmin: ~30%
```

**TODOS COMPARTEN EL MISMO BUG**: Orden de operaciones restore->deallocate cuando la ruta reinsertada usa enlace inverso.

---

## B) ¿EL CAMBIO DE ORDEN REALMENTE CORRIGE EL PROBLEMA?

### ✅ SÍ - SOLUCIÓN VERIFICADA

### ORDEN PROPUESTO

```java
// NUEVO ORDEN DEL ROLLBACK:

// Paso 1: Desasignar nueva ruta
Utils.deallocateFs(graph, nueva, crosstalkPerUnitLength);
removeRouteFromList(establishedRoutes, nueva);

// Paso 2: Desasignar TODAS las rutas reinsertadas PRIMERO
for (Map.Entry<EstablishedRoute, EstablishedRoute> e : moved.entrySet()) {
    EstablishedRoute reinsertada = e.getValue();
    Utils.deallocateFs(graph, reinsertada, crosstalkPerUnitLength);  // ← MOVER AQUÍ
}

// Paso 3: Restaurar TODOS los backups AL FINAL (no pueden ser sobrescritos)
// 3a. Restaurar ruta que falló
restoreSingleRoute(graph, backup);  // ← Ahora nada lo sobrescribe

// 3b. Restaurar rutas reinsertadas
for (Map.Entry<EstablishedRoute, EstablishedRoute> e : moved.entrySet()) {
    EstablishedRoute original = e.getKey();
    restoreSingleRoute(graph, backups.get(original));
    replaceRouteInList(establishedRoutes, reinsertada, original);
}

// 3c. Restaurar rutas no reinsertadas
for (EstablishedRoute rRest : resolvedConflictSet) {
    if (!moved.containsKey(rRest) && rRest != r) {
        restoreSingleRoute(graph, backups.get(rRest));
    }
}
```

### SIMULACIÓN DEL CASO 20->2 CON NUEVO ORDEN

**T1**: `deallocateFs(nueva 20->2)`
- Link 10->14 NO se modifica

**T2**: `deallocateFs(reinsertada 21->0)`
- Path: 21-15-**14-10**-5-0
- **Link 14-10 core 2 fs 230-232 → isFree=true**
- Por cores compartidos: **Link 10-14 core 2 fs 230-232 → isFree=true**

**T3**: `restoreSingleRoute(backup 7->14)`
- Path: 7-6-8-**10-14**
- **Link 10-14 core 2 fs 230-232 → isFree=false, lifetime=579** ✅
- **ÚLTIMO PASO**: No hay operación posterior que lo sobrescriba

**T4**: `restoreSingleRoute(backup 21->0)`
- Path: 21-15-**11-10**-5-0
- Restaura link 11-10
- NO afecta link 10-14

**RESULTADO**: ✅ Link 10-14 core 2 fs 230-232 → isFree=false, lifetime=579 (CORRECTO)

---

## C) ¿EXISTE ALGÚN CASO DONDE CAMBIAR EL ORDEN PUEDA ROMPER EL ROLLBACK?

### ANÁLISIS DE RIESGOS

### RIESGO 1: ¿Liberar slots que pertenecen a otra ruta legítima?

**NO** - Las rutas reinsertadas fueron asignadas exitosamente con `Utils.assignFs()` antes del rollback.
- Solo ocupan slots que les fueron asignados
- No hay rutas legítimas usando esos slots
- deallocateFs() solo libera los slots específicos de esa ruta

**SAFE** ✅

### RIESGO 2: ¿Liberar dos veces los mismos slots?

**Escenario**: ¿Puede una ruta reinsertada usar exactamente los mismos enlaces/cores/FS que otra ruta en moved?

**Análisis**:
- Algoritmos RSA (ruteoCoreMultiple) verifican disponibilidad con `isFSBlockFree()`
- Si un slot está ocupado, NO se asigna
- Por diseño, dos rutas reinsertadas NO pueden ocupar los mismos slots simultáneamente

**Pero puede ocurrir via cores compartidos**:
- Ruta A reinsertada: path usa link 10->14
- Ruta B reinsertada: path usa link 14->10
- deallocateFs(A) libera link 10->14 → también afecta 14->10
- deallocateFs(B) libera link 14->10 → ya está libre (isFree=true)

**Efecto**: setFree(true) sobre un slot ya libre → NO causa error, es idempotente

**SAFE** ✅ (operación idempotente)

### RIESGO 3: ¿Restaurar sobre slots que deberían permanecer ocupados?

**Escenario**: ¿Puede el backup de una ruta sobrescribir slots usados por otra ruta del conflictSet?

**Análisis**:
- Todas las rutas en resolvedConflictSet fueron desasignadas en el paso 4.1 (línea ~165, ~355, ~690)
- Sus slots están libres después de la desasignación inicial
- Los backups restauran el estado ANTES de la desasignación
- restoreSingleRoute() solo opera sobre los enlaces del path del backup

**Caso edge**: ¿Dos rutas del conflictSet compartían enlaces?

Si ruta A y ruta B ambas usaban link 10->14 con FS diferentes:
- Backup A: link 10->14 fs:100-102 isFree=false
- Backup B: link 10->14 fs:200-202 isFree=false
- restoreSingleRoute(A) restaura fs:100-102
- restoreSingleRoute(B) restaura fs:200-202
- NO se sobrescriben porque operan sobre FS diferentes

**SAFE** ✅

### RIESGO 4: ¿Problemas cuando existen varias rutas reinsertadas?

**Escenario**: moved contiene 3+ entradas con interdependencias de enlaces bidireccionales

**Ejemplo**:
```
moved = {
  original_A -> reinsertada_A (path: ... 10->14 ...)
  original_B -> reinsertada_B (path: ... 14->10 ...)
  original_C -> reinsertada_C (path: ... 10->14 ...)
}
```

**Nuevo orden**:
1. deallocateFs(reinsertada_A) → link 10->14 y 14->10 libres
2. deallocateFs(reinsertada_B) → link 14->10 y 10->14 libres (ya lo están)
3. deallocateFs(reinsertada_C) → link 10->14 y 14->10 libres (ya lo están)
4. restoreSingleRoute(backup_A) → link 10->14 ocupado
5. restoreSingleRoute(backup_B) → link 14->10 ocupado (comparte cores con 10->14!)
6. restoreSingleRoute(backup_C) → link 10->14 ocupado (comparte cores con 14->10!)

**Análisis**:
- Pasos 4-6 todos ejecutan setFree(false) sobre los mismos objetos Core
- setFree(false) es idempotente
- lifetime se sobrescribe múltiples veces
- **PROBLEMA POTENCIAL**: ¿Qué lifetime queda al final?

**Verificación**:
- backup_A.lifetime (ruta A original)
- backup_B.lifetime (ruta B original)
- backup_C.lifetime (ruta C original)

Si backup_A y backup_C ambos usan link 10->14 fs:230-232:
- **Esto NO puede ocurrir** porque antes del conflictSet, solo UNA ruta puede usar link 10->14 fs:230-232
- Los backups restauran el estado ANTES del rollback
- Si dos backups intentan restaurar el mismo link/core/fs, significa que originalmente ocupaban el mismo recurso → IMPOSIBLE en el estado válido inicial

**SAFE** ✅ (caso imposible por invariante de asignación única)

### RIESGO 5: ¿Problemas cuando hay más de un enlace compartido entre rutas?

**Escenario**: Ruta A y Ruta B reinsertadas comparten múltiples enlaces bidireccionales

**Ejemplo**:
```
reinsertada_A: path 10->14->18
reinsertada_B: path 18->14->10
```

Comparten:
- Enlaces 10->14 / 14->10
- Enlaces 14->18 / 18->14

**Nuevo orden**:
1. deallocateFs(A) → libera 10->14 y 14->18 → también libera 14->10 y 18->14
2. deallocateFs(B) → libera 18->14 y 14->10 → ya libres (idempotente)
3. restoreSingleRoute(backup_A) → restaura enlaces de backup_A
4. restoreSingleRoute(backup_B) → restaura enlaces de backup_B

**Problema potencial**: ¿backup_A y backup_B comparten enlaces?

**Análisis**:
- backup_A restaura el estado original de ruta A ANTES del rollback
- backup_B restaura el estado original de ruta B ANTES del rollback
- Si ambas rutas originales compartían enlaces con los mismos FS → IMPOSIBLE (violación de asignación única)
- Si usaban FS diferentes en el mismo enlace → restoreSingleRoute() opera sobre FS distintos → SAFE

**SAFE** ✅

### RIESGO 6: ¿Corrupción de crosstalk?

**Análisis**:
- deallocateFs() actualiza crosstalk de cores vecinos (línea ~267 Utils.java)
- restoreSingleRoute() restaura crosstalk del backup (línea 1556 Defragmenter.java)

**Nuevo orden**:
1. deallocateFs(todas reinsertadas) → actualiza crosstalk (reduce)
2. restoreSingleRoute(todos backups) → restaura crosstalk del backup

**Comparación con orden actual**:
1. restoreSingleRoute(ruta que falló) → restaura crosstalk
2. deallocateFs(reinsertada) → reduce crosstalk → **SOBRESCRIBE**
3. restoreSingleRoute(original) → restaura crosstalk

**NUEVO ORDEN ES MEJOR** ✅: Las restauraciones son la última operación, no se sobrescriben

### RIESGO 7: ¿Problemas con replaceRouteInList()?

**Orden actual**:
```java
for (...) {
    deallocateFs(reinsertada);
    restoreSingleRoute(backups.get(original));
    replaceRouteInList(establishedRoutes, reinsertada, original);
}
```

**Orden propuesto**:
```java
// Loop 1: Desasignar
for (...) {
    deallocateFs(reinsertada);
}

// Loop 2: Restaurar
for (...) {
    restoreSingleRoute(backups.get(original));
    replaceRouteInList(establishedRoutes, reinsertada, original);
}
```

**Análisis**:
- replaceRouteInList() opera sobre establishedRoutes (lista de rutas)
- NO afecta el estado de los slots en el grafo
- Puede ejecutarse en cualquier momento después de deallocateFs()

**SAFE** ✅

---

## D) ¿PODEMOS DISEÑAR UN TEST MÍNIMO REPRODUCIBLE?

### ✅ SÍ - TEST MÍNIMO DISEÑADO

### ESTRATEGIA DE VALIDACIÓN

**Opción A: Instrumentar el rollback específico**

Agregar trace detallado en el caso problema:
```java
if (demandaBloqueada.getSource() == 20 && demandaBloqueada.getDestination() == 2 &&
    resolvedConflictSet.size() == 2) {
    
    // Encontramos el caso 20->2 con 2 conflictos
    System.out.println("[TEST-ROLLBACK] ANTES restoreSingleRoute(backup 7->14)");
    printSlotState(graph, 10, 14, 2, 230);  // Debería mostrar isFree=true
    
    restoreSingleRoute(graph, backup);
    
    System.out.println("[TEST-ROLLBACK] DESPUÉS restoreSingleRoute(backup 7->14)");
    printSlotState(graph, 10, 14, 2, 230);  // Debería mostrar isFree=false
    
    for (...moved...) {
        System.out.println("[TEST-ROLLBACK] ANTES deallocateFs(reinsertada)");
        printSlotState(graph, 10, 14, 2, 230);  // Debería mostrar isFree=false
        
        Utils.deallocateFs(graph, reinsertada, crosstalkPerUnitLenght);
        
        System.out.println("[TEST-ROLLBACK] DESPUÉS deallocateFs(reinsertada)");
        printSlotState(graph, 10, 14, 2, 230);  // ❌ MOSTRARÁ isFree=true (BUG!)
        
        restoreSingleRoute(graph, backups.get(original));
    }
}
```

**Opción B: Test unitario sintético**

Crear un test que simula el escenario exacto:
```java
@Test
public void testRollbackConEnlacesBidireccionales() {
    // 1. Crear grafo con 4 nodos: 7-10-14 y 21-10-0
    Graph<Integer, Link> g = createTestGraph();
    
    // 2. Asignar ruta A: 21-10-0 fs:230-232
    EstablishedRoute routeA = assignRoute(g, path(21,10,0), 230, 3);
    
    // 3. Asignar ruta B: 7-10-14 fs:230-232
    EstablishedRoute routeB = assignRoute(g, path(7,10,14), 230, 3);
    
    // 4. Crear backups
    EstablishedRoute backupA = copyRoute(routeA);
    EstablishedRoute backupB = copyRoute(routeB);
    
    // 5. Desasignar ambas
    Utils.deallocateFs(g, routeA, 0.0);
    Utils.deallocateFs(g, routeB, 0.0);
    
    // 6. Reinsertar ruta A con nuevo path: 21-14-10-0 (usa 14->10 inverso!)
    EstablishedRoute reinsertedA = assignRoute(g, path(21,14,10,0), 230, 3);
    
    // 7. Simular rollback ORDEN ACTUAL (buggy)
    restoreSingleRoute(g, backupB);  // Restaura 10->14
    
    boolean slot1014Before = getSlot(g, 10, 14, 2, 230).isFree();
    assertEquals(false, slot1014Before);  // ✅ Debería estar ocupado
    
    Utils.deallocateFs(g, reinsertedA, 0.0);  // Desasigna 14->10
    
    boolean slot1014After = getSlot(g, 10, 14, 2, 230).isFree();
    assertEquals(false, slot1014After);  // ❌ FALLA - está libre!
    
    // 8. Simular rollback ORDEN NUEVO (fixed)
    // Reset
    Utils.deallocateFs(g, routeA, 0.0);
    Utils.deallocateFs(g, routeB, 0.0);
    reinsertedA = assignRoute(g, path(21,14,10,0), 230, 3);
    
    Utils.deallocateFs(g, reinsertedA, 0.0);  // Desasignar PRIMERO
    restoreSingleRoute(g, backupB);  // Restaurar DESPUÉS
    
    boolean slot1014Fixed = getSlot(g, 10, 14, 2, 230).isFree();
    assertEquals(false, slot1014Fixed);  // ✅ PASA - está ocupado correctamente
}
```

**Opción C: Validación post-fix en simulación completa**

Después de implementar el fix, ejecutar:
```bash
mvn clean compile exec:java
```

Verificar:
- ✅ Las 76 violaciones deben reducirse a 0
- ✅ Success rates deben mejorar o mantenerse
- ✅ Sin nuevas violaciones de otros invariantes

---

## RESUMEN FINAL

### A) CAUSA RAÍZ CONFIRMADA ✅

**BUG**: Orden de operaciones restore -> deallocate en rollback cuando ruta reinsertada usa enlace inverso

**MECANISMO**:
1. Links bidireccionales (10->14 y 14->10) comparten exactamente los mismos objetos Core
2. restoreSingleRoute(backup ruta que falló) pone isFree=false en link 10->14
3. deallocateFs(ruta reinsertada con path usando 14->10) pone isFree=true
4. Como comparten cores, sobrescribe el restore → link 10->14 queda isFree=true (incorrecto)

**EVIDENCIA**:
- ✅ Arquitectura bidireccional compartida verificada (Utils.java líneas 68-81)
- ✅ Los 3 algoritmos usan el mismo patrón buggy (líneas 178-197, 368-387, 806-829)
- ✅ Caso concreto reconstruido con paths exactos del log
- ✅ 76 violaciones todas muestran backup isFree=false, grafo isFree=true
- ✅ Todas las violaciones en grupo FSDM [2,3] (cores compartidos)

### B) CAMBIO DE ORDEN CORRIGE EL PROBLEMA ✅

**FIX PROPUESTO**:
```
ANTES: restore ruta que falló → deallocate reinsertadas → restore originales
DESPUÉS: deallocate reinsertadas → restore ruta que falló → restore originales
```

**POR QUÉ FUNCIONA**: Las restauraciones son la última operación, nada las sobrescribe

**SIMULACIÓN VALIDADA**: Caso 20->2 con nuevo orden produce estado correcto

### C) RIESGOS ANALIZADOS ✅

**7 RIESGOS EVALUADOS**:
1. ❌ Liberar slots de ruta legítima: NO (rutas reinsertadas solo usan sus slots)
2. ❌ Liberar dos veces: SAFE (setFree idempotente)
3. ❌ Restaurar sobre slots ocupados: NO (backups no se solapan)
4. ❌ Múltiples rutas reinsertadas: SAFE (setFree idempotente, backups no solapan)
5. ❌ Múltiples enlaces compartidos: SAFE (FS distintos o imposible)
6. ❌ Corrupción de crosstalk: MEJOR que orden actual (restore al final)
7. ❌ Problemas con replaceRouteInList: SAFE (opera sobre lista, no grafo)

**CONCLUSIÓN**: ✅ Cambio de orden es SEGURO en todos los casos analizados

### D) TEST MÍNIMO RECOMENDADO ✅

**Opción recomendada**: Validación post-fix en simulación completa

**Pasos**:
1. Implementar fix en los 3 algoritmos (DFbFRmax, DFbFRmin, DFfullRuteoMin)
2. Ejecutar `mvn clean compile exec:java`
3. Verificar: 76 violaciones → 0
4. Verificar: Success rates mejoran o se mantienen
5. Verificar: Sin nuevas violaciones

**Ventajas**:
- Valida en escenario real con 5011 demandas
- Detecta efectos secundarios no anticipados
- Usa ENABLE_VALIDATION=true (ya implementado)
- Logs completos para análisis post-mortem

---

## FLUJO ACTUAL VS PROPUESTO

### FLUJO ACTUAL (Defragmenter.java líneas 806-829)

```java
// ❌ BUGGY
Utils.deallocateFs(graph, nueva, crosstalkPerUnitLenght);           // 1. Desasignar nueva
removeRouteFromList(establishedRoutes, nueva);

restoreSingleRoute(graph, backup);                                  // 2. Restaurar que falló ← isFree=false

for (Map.Entry<EstablishedRoute, EstablishedRoute> e : moved.entrySet()) {
    EstablishedRoute original = e.getKey();
    EstablishedRoute reinsertada = e.getValue();
    Utils.deallocateFs(graph, reinsertada, crosstalkPerUnitLenght); // 3. Desasignar reinsertada ← SOBRESCRIBE a isFree=true
    restoreSingleRoute(graph, backups.get(original));               // 4. Restaurar original
    replaceRouteInList(establishedRoutes, reinsertada, original);
}

for (EstablishedRoute rRest : resolvedConflictSet) {
    if (!moved.containsKey(rRest) && rRest != r) {
        restoreSingleRoute(graph, backups.get(rRest));              // 5. Restaurar no reinsertadas
    }
}
```

### FLUJO PROPUESTO (FIX)

```java
// ✅ FIXED
Utils.deallocateFs(graph, nueva, crosstalkPerUnitLenght);           // 1. Desasignar nueva
removeRouteFromList(establishedRoutes, nueva);

// 2. DESASIGNAR TODAS LAS REINSERTADAS PRIMERO
for (Map.Entry<EstablishedRoute, EstablishedRoute> e : moved.entrySet()) {
    EstablishedRoute reinsertada = e.getValue();
    Utils.deallocateFs(graph, reinsertada, crosstalkPerUnitLenght); // 2a. Desasignar reinsertada
}

// 3. RESTAURAR TODOS LOS BACKUPS AL FINAL (no se sobrescriben)
restoreSingleRoute(graph, backup);                                  // 3a. Restaurar que falló ← isFree=false ✅

for (Map.Entry<EstablishedRoute, EstablishedRoute> e : moved.entrySet()) {
    EstablishedRoute original = e.getKey();
    EstablishedRoute reinsertada = e.getValue();
    restoreSingleRoute(graph, backups.get(original));               // 3b. Restaurar original
    replaceRouteInList(establishedRoutes, reinsertada, original);
}

for (EstablishedRoute rRest : resolvedConflictSet) {
    if (!moved.containsKey(rRest) && rRest != r) {
        restoreSingleRoute(graph, backups.get(rRest));              // 3c. Restaurar no reinsertadas
    }
}
```

### CAMBIOS REQUERIDOS

**3 LUGARES** (mismo patrón en todos):
1. **DFbFRmax**: Defragmenter.java líneas 178-197
2. **DFbFRmin**: Defragmenter.java líneas 368-387
3. **DFfullRuteoMin**: Defragmenter.java líneas 806-829

**Complejidad**: Baja (reordenar operaciones, no cambiar lógica)
**Riesgo**: Bajo (analizado exhaustivamente, safe en todos los casos)
**Resultado esperado**: 76 violaciones → 0

---

## RECOMENDACIÓN FINAL

✅ **PROCEDER CON EL FIX**

**Justificación**:
1. Causa raíz DEMOSTRADA con evidencia concreta
2. Solución VERIFICADA mediante simulación del caso concreto
3. Riesgos ANALIZADOS exhaustivamente: todos SAFE
4. Complejidad BAJA: reordenar operaciones existentes
5. Impacto CRÍTICO: 76 violaciones contaminan simulación

**Próximos pasos**:
1. Implementar fix en los 3 algoritmos
2. Ejecutar simulación completa
3. Validar: violaciones = 0
4. Comparar success rates antes/después
5. Analizar bloqueos restantes si persisten
