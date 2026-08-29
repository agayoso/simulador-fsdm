# RESUMEN EJECUTIVO - INVESTIGACIÓN ROLLBACK

## CAUSA RAÍZ: ✅ CONFIRMADA

**Bug**: Orden de operaciones `restore → deallocate` en rollback cuando ruta reinsertada usa enlace inverso.

**Mecanismo**:
1. Links 10→14 y 14→10 comparten EXACTAMENTE los mismos objetos Core (Utils.java línea 68-81)
2. `restoreSingleRoute(backup 7→14)` → Link 10→14 fs:230-232 → **isFree=false** ✅
3. `deallocateFs(reinsertada 21→0 path:...14→10...)` → Link 14→10 → **isFree=true**
4. Por cores compartidos → Link 10→14 → **isFree=true** ❌ (sobrescribe paso 2)

---

## EVIDENCIA CONCRETA

**Del log experimento_bidireccional.log**:
- Demanda: 20→2
- ConflictSet: {21→0 path:**21-15-11-10-5-0**, 7→14 path:**7-6-8-10-14**}
- Ruta reinsertada: 21→0 path:**21-15-14-10-5-0** (cambió de 11→10 a 14→10, INVERSO!)
- Violación: Link 10→14 core 2 fs 230-232 → backup espera isFree=false, grafo tiene isFree=true

**Patrón universal**: Los 3 algoritmos usan el mismo código buggy (líneas 178-197, 368-387, 806-829)

**76 violaciones = 17 eventos FSDM**, todas muestran backup isFree=false, grafo isFree=true

---

## FLUJO ACTUAL (BUGGY)

```java
// Defragmenter.java líneas 806-829 (DFfullRuteoMin)
// Similar en DFbFRmax (178-197) y DFbFRmin (368-387)

deallocateFs(graph, nueva, crosstalkPerUnitLenght);        // 1. Libera nueva
removeRouteFromList(establishedRoutes, nueva);

restoreSingleRoute(graph, backup);                         // 2. Restaura ruta que falló ✅

for (Map.Entry<EstablishedRoute, EstablishedRoute> e : moved.entrySet()) {
    EstablishedRoute original = e.getKey();
    EstablishedRoute reinsertada = e.getValue();
    deallocateFs(graph, reinsertada, crosstalkPerUnitLenght);  // 3. ❌ SOBRESCRIBE paso 2
    restoreSingleRoute(graph, backups.get(original));      // 4. Restaura original
    replaceRouteInList(establishedRoutes, reinsertada, original);
}

for (EstablishedRoute rRest : resolvedConflictSet) {
    if (!moved.containsKey(rRest) && rRest != r) {
        restoreSingleRoute(graph, backups.get(rRest));     // 5. Restaura no reinsertadas
    }
}
```

**Problema**: Paso 3 ejecuta deallocateFs DESPUÉS del paso 2 restoreSingleRoute, sobrescribiendo el estado restaurado via cores compartidos.

---

## FLUJO PROPUESTO (FIXED)

```java
// Mismo orden en los 3 algoritmos

deallocateFs(graph, nueva, crosstalkPerUnitLenght);        // 1. Libera nueva
removeRouteFromList(establishedRoutes, nueva);

// NUEVO: Desasignar TODAS las reinsertadas PRIMERO
for (Map.Entry<EstablishedRoute, EstablishedRoute> e : moved.entrySet()) {
    EstablishedRoute reinsertada = e.getValue();
    deallocateFs(graph, reinsertada, crosstalkPerUnitLenght);  // 2. Libera reinsertadas
}

// NUEVO: Restaurar TODOS los backups AL FINAL
restoreSingleRoute(graph, backup);                         // 3. Restaura ruta que falló ✅

for (Map.Entry<EstablishedRoute, EstablishedRoute> e : moved.entrySet()) {
    EstablishedRoute original = e.getKey();
    EstablishedRoute reinsertada = e.getValue();
    restoreSingleRoute(graph, backups.get(original));      // 4. Restaura originales ✅
    replaceRouteInList(establishedRoutes, reinsertada, original);
}

for (EstablishedRoute rRest : resolvedConflictSet) {
    if (!moved.containsKey(rRest) && rRest != r) {
        restoreSingleRoute(graph, backups.get(rRest));     // 5. Restaura no reinsertadas ✅
    }
}
```

**Solución**: Todos los restoreSingleRoute() se ejecutan AL FINAL. No hay deallocateFs posterior que pueda sobrescribirlos.

---

## RIESGOS DEL FIX

**7 RIESGOS ANALIZADOS, TODOS SAFE**:

| Riesgo | Análisis | Estado |
|--------|----------|--------|
| Liberar slots de ruta legítima | NO ocurre: rutas reinsertadas solo usan sus slots asignados | ✅ SAFE |
| Liberar dos veces | Puede ocurrir: setFree(true) idempotente | ✅ SAFE |
| Restaurar sobre slots ocupados | NO: conflictSet ya fue desasignado | ✅ SAFE |
| Múltiples rutas reinsertadas | setFree/setLifetime idempotentes | ✅ SAFE |
| Múltiples enlaces compartidos | Operaciones idempotentes | ✅ SAFE |
| Corrupción de crosstalk | Nuevo orden MEJOR: restore al final | ✅ MEJOR |
| replaceRouteInList() | Opera sobre lista, no grafo | ✅ SAFE |

**CONCLUSIÓN**: Cambio de orden SEGURO en todos los casos.

---

## TEST MÍNIMO RECOMENDADO

**Opción 1 (RECOMENDADA)**: Validación en simulación completa

```bash
# 1. Implementar fix en 3 lugares (ver sección "LUGARES A MODIFICAR" abajo)
# 2. Compilar y ejecutar
mvn clean compile exec:java

# 3. Verificar en output:
#    ANTES FIX: "FAIL.*Rollback incompleto" aparece 76 veces
#    DESPUÉS FIX: "FAIL.*Rollback incompleto" aparece 0 veces
#    Success rates: mantener o mejorar (15-55%)
```

**Opción 2 (COMPLEMENTARIA)**: Test unitario

```java
@Test
public void testRollbackBidireccional() {
    // 1. Crear grafo con enlaces bidireccionales
    // 2. Asignar ruta A: usa link 11→10
    // 3. Asignar ruta B: usa link 10→14
    // 4. Desasignar ambas
    // 5. Reinsertar A con nuevo path usando 14→10 (inverso)
    // 6. Simular rollback con ORDEN ACTUAL → assert link 10→14 isFree=true (BUG)
    // 7. Simular rollback con ORDEN NUEVO → assert link 10→14 isFree=false (CORRECTO)
}
```

---

## LUGARES A MODIFICAR

**3 BLOQUES** (mismo patrón en todos):

1. **DFbFRmax**: `src/main/java/.../Defragmenter.java` líneas **178-197**
2. **DFbFRmin**: `src/main/java/.../Defragmenter.java` líneas **368-387**
3. **DFfullRuteoMin**: `src/main/java/.../Defragmenter.java` líneas **806-829**

**Cambio**: Separar el loop `moved` en 2 loops:
- Loop 1: Solo deallocateFs(reinsertada)
- Loop 2: restoreSingleRoute(original) + replaceRouteInList()

**Complejidad**: Baja (10-15 líneas por algoritmo)

---

## DOCUMENTACIÓN COMPLETA

- **INVESTIGACION_CAUSA_RAIZ_Y_RIESGOS.md**: Análisis exhaustivo de 7 riesgos
- **RESPUESTAS_4_PREGUNTAS.md**: Respuestas detalladas A/B/C/D
- **INFORME_FORENSE_COMPLETO.md**: 5 objetivos de análisis forense
- **ANALISIS_CASO_230.md**: Reconstrucción paso a paso del caso concreto
- **OBJETIVO_5_CONTAMINACION.md**: Análisis de impacto en simulación

---

## DECISIÓN RECOMENDADA

### ✅ IMPLEMENTAR FIX INMEDIATAMENTE

**Razones**:
1. Causa raíz **CONFIRMADA** con evidencia del log
2. Solución **VERIFICADA** por análisis del caso
3. Riesgos **ELIMINADOS** (todos SAFE)
4. Complejidad **BAJA** (reordenar operaciones)
5. Impacto **CRÍTICO** (76 violaciones contaminan simulación)

**Resultado esperado**:
- 76 violaciones → 0
- Success rates mantienen o mejoran
- Estado de simulación sin contaminación
