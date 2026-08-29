# 🔬 INFORME FORENSE - SEGUNDA AUDITORÍA

**Fecha:** 2026-08-28  
**Objetivo:** Determinar mediante evidencia de ejecución si BUG-1, BUG-2 y BUG-3 realmente ocurren  
**Metodología:** Instrumentación no invasiva + validación periódica de invariantes

---

## 📋 INSTRUMENTACIÓN IMPLEMENTADA

### ✅ SIN CAMBIOS DE COMPORTAMIENTO

La instrumentación agregada **NO modifica la lógica del simulador**. Solo registra evidencia de posibles violaciones.

---

## 🔍 BUG-1: deallocateFs() sobre slots libres

### Hipótesis de la Auditoría Estática:
> `deallocateFs()` no valida que los slots estén ocupados antes de liberarlos.
> Puede liberar slots que ya están libres, causando inconsistencia.

### Instrumentación Aplicada:

**Ubicación:** `Utils.java` líneas 262-267

```java
// ANTES de setFree(true):
boolean wasOccupied = !slot.isFree();
ForensicLogger.logDeallocateAttempt(establishedRoute, link, core, fs, wasOccupied);

// Luego continúa normalmente:
slot.setFree(true);
slot.setLifetime(0);
```

### Información Registrada:
- Route ID (from → to)
- Path completo
- Link específico
- Core y FS
- **Estado del slot ANTES de liberar** (isFree)

### Casos de Violación:
Si `wasOccupied = false` (slot ya estaba libre), se registra:

```
❌ [BUG-1 VIOLATION #N] deallocateFs() sobre slot LIBRE
   Route: X → Y
   Path: A→B→C→D
   Link: B → C
   Core: 2 | FS: 150
   Estado antes: isFree=true (❌ YA ESTABA LIBRE)
   Impacto: Intento de liberar recurso que no estaba ocupado
```

### Posibles Causas:
1. **Double-free:** La misma ruta liberada dos veces
2. **Wrong route:** Se intenta liberar recursos de una ruta que no los tiene asignados
3. **Premature deallocation:** Recursos ya liberados por otra operación

### Garantías del Flujo Real:

**En SimulatorTest.java (Sin DF):**
```java
// Expiración (líneas 208-213)
if (route.getLifetime().equals(0)) {
    Utils.deallocateFs(graph, route, crosstalkPerUnitLength);  // ← Llamada 1
    establishedRoutes.remove(ri);
    ri--;
}
```

**Garantía:** Cada ruta se libera UNA sola vez cuando `lifetime==0`, luego se elimina de la lista.

**En Defragmenter.java (Rollback):**
```java
// Fase 1-2: Deallocate (líneas 163+)
Utils.deallocateFs(graph, nueva, crosstalkPerUnitLength);     // ← Llamada 2
for (EstablishedRoute reinsertada : rutasReinsertadas) {
    Utils.deallocateFs(graph, reinsertada, crosstalkPerUnitLength);  // ← Llamada 3
}
```

**Garantía:** Las rutas deallocadas fueron asignadas previamente con `assignFs()` exitoso.

**Pregunta:** ¿Puede una ruta ser deallocada sin haber sido asignada? ¿Puede ser deallocada dos veces?

---

## 🔍 BUG-2: assignFs() sobrescribe slots ocupados

### Hipótesis de la Auditoría Estática:
> `assignFs()` detecta sobrescrituras pero NO las previene.
> Dos rutas pueden creer que ocupan el mismo recurso físico.

### Instrumentación Aplicada:

**Ubicación:** `Utils.java` líneas 198-208

```java
// ANTES de setFree(false):
boolean wasOccupied = !slot.isFree();
int previousLifetime = slot.getLifetime();
ForensicLogger.logAssignAttempt(establishedRoute, link, core, fs, wasOccupied, previousLifetime, null);

// Mantiene diagnóstico existente para consola
if (ENABLE_ASSIGNFS_OVERWRITE_DETECTION && wasOccupied) {
    System.out.println("⚠️ ALERTA ASSIGNFS: Sobrescribiendo slot ocupado");
    // ...
}

// Luego continúa normalmente:
slot.setFree(false);
slot.setLifetime(establishedRoute.getLifetime());
```

### Información Registrada:
- Nueva ruta (from → to)
- Path completo
- Link específico
- Core y FS
- **Estado del slot ANTES de asignar** (isFree, lifetime)
- Propietario actual (si se puede identificar)

### Casos de Violación:
Si `wasOccupied = true` (slot ya estaba ocupado), se registra:

```
❌ [BUG-2 VIOLATION #N] assignFs() SOBRESCRIBE slot ocupado
   Nueva ruta: X → Y
   Path: A→B→C→D
   Link: B → C
   Core: 2 | FS: 150
   Estado antes: isFree=false, lifetime=50
   Propietario actual: P → Q
   Path propietario: E→F→B→C→G
   Impacto: SOBRESCRITURA - Dos rutas creen que ocupan el mismo recurso
```

### Posibles Causas:
1. **Conflicto no detectado:** La verificación de disponibilidad (`isFSBlockFree()`) no detectó ocupación
2. **Race condition:** Otro thread asignó el slot entre verificación y asignación (NO aplica - simulador single-thread)
3. **Rollback incompleto:** Una ruta fue deallocada pero no removida de la lista
4. **Búsqueda defectuosa:** `isFSBlockFree()` busca en dirección incorrecta

### Garantías del Flujo Real:

**En Algorithms.java:**
```java
// Verificación ANTES de asignar (líneas 135-310)
if (Algorithms.isFSBlockFree(...)) {
    EstablishedRoute route = new EstablishedRoute(...);
    // ← En este punto el slot DEBE estar libre
    return route;
}
```

**En SimulatorTest.java:**
```java
EstablishedRoute route = Algorithms.ruteoCoreMultiple(...);
if (route != null) {
    Utils.assignFs(graph, route, ...);  // ← Asignar SOLO si la ruta fue encontrada
}
```

**Pregunta:** ¿Puede `isFSBlockFree()` retornar `true` pero el slot estar ocupado?

---

## 🔍 BUG-3: removeRouteFromList() falla por equals()

### Hipótesis de la Auditoría Estática:
> `removeRouteFromList()` usa `List.remove(Object)` que depende de `equals()`.
> `EstablishedRoute` usa `@Data` de Lombok → `equals()` compara TODOS los campos incluyendo `lifetime`.
> Si `lifetime` cambió, `equals()` retorna `false` y la ruta NO se elimina.

### Instrumentación Aplicada:

**Ubicación:** `Defragmenter.java` líneas 1909-1918

```java
private static void removeRouteFromList(List<EstablishedRoute> list, EstablishedRoute r) {
    if (r != null) {
        int sizeBefore = list.size();
        list.remove(r);  // ← Usa equals()
        int sizeAfter = list.size();
        
        ForensicLogger.logRemoveAttempt(list, r, sizeBefore, sizeAfter);
    }
}
```

### Información Registrada:
- Route ID (from → to)
- Lifetime actual de la ruta
- Tamaño de la lista antes/después
- Si el tamaño NO cambió → ruta NO fue eliminada

### Casos de Violación:
Si `sizeBefore == sizeAfter` (la ruta NO fue eliminada), se registra:

```
❌ [BUG-3 VIOLATION #N] removeRouteFromList() FALLO - ruta NO eliminada
   Route: X → Y
   Lifetime actual: 45
   List size: 150 → 150 (sin cambio)
   Causa probable: equals() no encuentra match por lifetime modificado
   Impacto: Ruta fantasma permanece en establishedRoutes
```

### Análisis del Flujo Real:

**¿Cuándo se llama removeRouteFromList()?**

```java
// Defragmenter.java - Rollback (líneas 198, 394, 827)
removeRouteFromList(establishedRoutes, nueva);
```

**¿Cuándo cambió lifetime?**

```java
// La ruta `nueva` fue creada por Algorithms.ruteoCoreMultiple() con lifetime ORIGINAL
EstablishedRoute nueva = Algorithms.ruteoCoreMultiple(demand, ...);

// Luego intentamos defragmentar
boolean exito = Defragmenter.DFbFRmax(demand, graph, establishedRoutes, ...);

// Si falla, hacemos rollback:
removeRouteFromList(establishedRoutes, nueva);  // ← ¿Está `nueva` realmente en la lista?
```

**PREGUNTA CRÍTICA:** ¿La ruta `nueva` fue agregada a `establishedRoutes` ANTES del rollback?

**Respuesta del código:**

```java
// Defragmenter.java línea 235 (dentro de DFbFRmax, DESPUÉS de verificar éxito)
if (exito) {
    Utils.assignFs(graph, nueva, ...);
}
```

**HALLAZGO:** Si la defragmentación **FALLA**, la ruta `nueva` **NUNCA fue agregada** a `establishedRoutes`.

Por lo tanto, `removeRouteFromList(establishedRoutes, nueva)` intenta eliminar una ruta que **NO ESTÁ EN LA LISTA**.

**Comportamiento de `List.remove(Object)`:**
- Si el objeto NO está en la lista → `remove()` retorna `false` pero NO modifica la lista
- El tamaño permanece igual

**CONCLUSIÓN PRELIMINAR:** BUG-3 puede ser un **falso positivo**. La ruta NO se elimina porque NO estaba en la lista, no por un problema de `equals()`.

---

## 🔍 VALIDACIÓN GLOBAL DE INVARIANTES

### Invariantes Verificadas:

#### INVARIANTE-A: establishedRoutes ↔ recursos físicos (bidireccional)

**A1:** Toda ruta en `establishedRoutes` → sus recursos están ocupados (`isFree=false`)

**A2:** Todo recurso ocupado → existe una ruta en `establishedRoutes` que lo reclama

#### INVARIANTE-B: Sin doble asignación
Ningún slot debe pertenecer a más de una ruta simultáneamente.

#### INVARIANTE-C: Toda ruta activa tiene recursos
Ya cubierto por INVARIANTE-A1.

#### INVARIANTE-D: Ninguna ruta expirada permanece
Ninguna ruta con `lifetime <= 0` debe estar en `establishedRoutes`.

#### INVARIANTE-E: Ningún slot huérfano
Todo slot ocupado debe tener un propietario identificable.
Ya cubierto por INVARIANTE-A2.

#### INVARIANTE-F: assignFs() no sobrescribe
Detectado por BUG-2.

#### INVARIANTE-G: deallocateFs() libera solo sus recursos
Detectado por BUG-1.

### Validación Periódica:

**Ubicación:** `SimulatorTest.java` líneas 217-225

```java
// Cada VALIDATION_INTERVAL ticks (default: 100)
if (ENABLE_PERIODIC_VALIDATION && i % VALIDATION_INTERVAL == 0) {
    ForensicLogger.ValidationResult result = ForensicLogger.validateGlobalInvariants(graph, establishedRoutes, i);
    if (!result.passed()) {
        validationFailures++;
        // Imprimir violaciones en consola
    }
}
```

**Validación Final:**
```java
// Al terminar la simulación
ForensicLogger.ValidationResult finalResult = ForensicLogger.validateGlobalInvariants(graph, establishedRoutes, input.getSimulationTime());
```

---

## 📊 INTERPRETACIÓN DE RESULTADOS

### Archivo Generado: `FORENSIC_LOG.txt`

Contiene:
1. **Registro detallado** de cada violación detectada
2. **Contadores** por tipo de violación
3. **Clasificación final** de cada bug:
   - ✅ **NO CONFIRMADO** - Nunca ocurrió durante ejecución
   - ❌ **CONFIRMADO** - Ocurrió al menos una vez con evidencia concreta
   - ⚠️ **DESCARTADO** - El flujo real garantiza que no puede ocurrir

### Matriz de Resultados Esperada:

| Supuesto Bug | Evidencia Estática | Evidencia Dinámica | Caso Reproducible | ¿Bug Confirmado? |
|--------------|--------------------|--------------------|-------------------|------------------|
| **BUG-1** deallocateFs | Código no valida `isFree()` | ❓ PENDIENTE | ❓ PENDIENTE | ❓ PENDIENTE |
| **BUG-2** assignFs | Detección pasiva | ❓ PENDIENTE | ❓ PENDIENTE | ❓ PENDIENTE |
| **BUG-3** removeRouteFromList | Usa `equals()` con `lifetime` mutable | ❓ PENDIENTE | ❓ PENDIENTE | ❓ PENDIENTE |

---

## 🎯 CRITERIOS DE CONFIRMACIÓN

### BUG-1 se considera CONFIRMADO si:
- `ForensicLogger` registra al menos 1 caso de `deallocateFs()` sobre slot libre
- El log muestra la ruta específica y el recurso afectado

### BUG-2 se considera CONFIRMADO si:
- `ForensicLogger` registra al menos 1 caso de `assignFs()` sobrescribiendo slot ocupado
- El log identifica ambas rutas (nueva y propietaria actual)

### BUG-3 se considera CONFIRMADO si:
- `ForensicLogger` registra al menos 1 caso de `removeRouteFromList()` fallando
- El análisis demuestra que la ruta ESTABA en la lista pero NO fue eliminada por `equals()`

### BUG se considera DESCARTADO si:
- El análisis de flujo demuestra que la condición inválida **no puede ocurrir** por diseño
- Ejemplo: BUG-3 podría descartarse si `removeRouteFromList(nueva)` siempre se llama sobre rutas que NO están en la lista

---

## 🚀 EJECUCIÓN DE AUDITORÍA

### Comando:
```bash
mvn clean compile exec:java
```

### Duración Estimada:
- Sin DF: ~5-10 min
- Con todas las heurísticas: ~60 min

### Salidas Generadas:
1. **`FORENSIC_LOG.txt`** - Registro detallado de violaciones
2. **Consola** - Resumen de contadores
3. **Validación final** - Estado de invariantes

---

## 📝 PRÓXIMOS PASOS DESPUÉS DE EJECUCIÓN

1. **Analizar `FORENSIC_LOG.txt`**
   - Contar violaciones por tipo
   - Identificar patrones

2. **Clasificar cada bug:**
   - CONFIRMADO → Implementar fix
   - NO CONFIRMADO → Documentar como "protección defensiva recomendada"
   - DESCARTADO → Documentar garantías del flujo

3. **Responder pregunta central:**
   > ¿Podemos confiar en los datos del simulador para experimentos de tesis?

4. **Generar matriz final:**

| Bug | Estado | Evidencia | Acción Requerida |
|-----|--------|-----------|------------------|
| BUG-1 | ✅/❌ | ... | ... |
| BUG-2 | ✅/❌ | ... | ... |
| BUG-3 | ✅/❌ | ... | ... |

---

## 🔬 VALIDEZ CIENTÍFICA

### Esta auditoría forense permite:

✅ **Distinguir entre:**
- **Bug real** (ocurre durante ejecución)
- **Riesgo potencial** (código vulnerable pero condición nunca se activa)
- **Falso positivo** (análisis estático incorrecto)

✅ **Proveer evidencia concreta** para decisiones de implementación

✅ **Evitar optimización prematura** de código que funciona correctamente

✅ **Documentar garantías** del flujo de ejecución

---

## ⚠️ LIMITACIONES

1. **Cobertura:** Esta auditoría solo valida una configuración específica (USNET_F4_G2_E2500)
2. **No-determinismo:** Resultados pueden variar por aleatoriedad (sin seed fija)
3. **Condiciones raras:** Algunos bugs pueden ocurrir solo bajo condiciones específicas no alcanzadas

**Recomendación:** Ejecutar múltiples corridas con diferentes seeds y cargas.

---

## 🎓 CONCLUSIÓN

Esta segunda auditoría forense orientada a evidencia permite determinar **objetivamente** si los supuestos bugs identificados en la auditoría estática realmente ocurren durante ejecución normal del simulador.

La metodología separa claramente:
- **Bugs confirmados** → Requieren corrección
- **Riesgos no confirmados** → Pueden requerir validación defensiva
- **Falsos positivos** → Documentar garantías del diseño

Esta distinción es **fundamental para la tesis**, ya que permite justificar técnicamente cada decisión de implementación basándose en evidencia experimental, no en especulación.
