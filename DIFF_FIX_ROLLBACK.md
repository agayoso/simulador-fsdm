# DIFF EXACTO - FIX DEL ROLLBACK

## ✅ COMPILACIÓN EXITOSA

```
[INFO] BUILD SUCCESS
[INFO] Total time:  7.745 s
```

---

## CAMBIOS REALIZADOS

Se modificaron **3 bloques** en `Defragmenter.java`, todos con el mismo patrón de fix.

---

## 1️⃣ BLOQUE 1: DFbFRmax (líneas 192-228)

### ❌ ANTES (orden buggy)

```java
if (re == null || re.getFsIndexBegin() == -1) {
    // ❌ Falló reinserción de esta ruta: rollback completo de este intento
    Utils.deallocateFs(graph, nueva, crosstalkPerUnitLength);
    removeRouteFromList(establishedRoutes, nueva);

    // Restaurar la que falló
    restoreSingleRoute(graph, backup);  // ← Se restaura PRIMERO

    // Rollback de todas las ya reinsertadas (moved)
    for (Map.Entry<EstablishedRoute, EstablishedRoute> e : moved.entrySet()) {
        EstablishedRoute original = e.getKey();
        EstablishedRoute reinsertada = e.getValue();
        Utils.deallocateFs(graph, reinsertada, crosstalkPerUnitLength);  // ← SOBRESCRIBE
        restoreSingleRoute(graph, backups.get(original));
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
    break;
}
```

### ✅ DESPUÉS (orden fixed)

```java
if (re == null || re.getFsIndexBegin() == -1) {
    // ❌ Falló reinserción de esta ruta: rollback completo de este intento
    // FASE 1: Desasignar nueva ruta
    Utils.deallocateFs(graph, nueva, crosstalkPerUnitLength);
    removeRouteFromList(establishedRoutes, nueva);

    // FASE 2: Desasignar TODAS las rutas reinsertadas
    for (Map.Entry<EstablishedRoute, EstablishedRoute> e : moved.entrySet()) {
        EstablishedRoute reinsertada = e.getValue();
        Utils.deallocateFs(graph, reinsertada, crosstalkPerUnitLength);
    }

    // FASE 3: Restaurar TODOS los backups (no pueden ser sobrescritos)
    // Restaurar la que falló
    restoreSingleRoute(graph, backup);  // ← Se restaura AL FINAL

    // Restaurar rutas reinsertadas
    for (Map.Entry<EstablishedRoute, EstablishedRoute> e : moved.entrySet()) {
        EstablishedRoute original = e.getKey();
        restoreSingleRoute(graph, backups.get(original));
    }

    // Restaurar las restantes desasignadas que aún no se reinsertaron
    for (EstablishedRoute rRest : mejorConflictSet) {
        if (!moved.containsKey(rRest) && rRest != r) {
            restoreSingleRoute(graph, backups.get(rRest));
        }
    }

    // FASE 4: Actualizar establishedRoutes
    for (Map.Entry<EstablishedRoute, EstablishedRoute> e : moved.entrySet()) {
        EstablishedRoute original = e.getKey();
        EstablishedRoute reinsertada = e.getValue();
        replaceRouteInList(establishedRoutes, reinsertada, original);
    }

    log("Intento " + (intento + 1) + ": rollback por fallo al reinsertar conflictos.");
    falloReinsercion = true;
    break;
}
```

### CAMBIO CLAVE
- **ANTES**: `restore → deallocate → restore` (en un solo loop)
- **DESPUÉS**: `deallocate (todas) → restore (todas) → replaceRouteInList (todas)`

---

## 2️⃣ BLOQUE 2: DFbFRmin (líneas 389-425)

### ❌ ANTES

```java
if (re == null || re.getFsIndexBegin() == -1) {
    // ❌ Falló reinserción de esta ruta: rollback completo de este intento
    Utils.deallocateFs(graph, nueva, crosstalkPerUnitLength);
    removeRouteFromList(establishedRoutes, nueva);

    // Restaurar la que falló
    restoreSingleRoute(graph, backup);

    // Rollback de todas las ya reinsertadas (moved)
    for (Map.Entry<EstablishedRoute, EstablishedRoute> e : moved.entrySet()) {
        EstablishedRoute original = e.getKey();
        EstablishedRoute reinsertada = e.getValue();
        Utils.deallocateFs(graph, reinsertada, crosstalkPerUnitLength);
        restoreSingleRoute(graph, backups.get(original));
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
    break;
}
```

### ✅ DESPUÉS

```java
if (re == null || re.getFsIndexBegin() == -1) {
    // ❌ Falló reinserción de esta ruta: rollback completo de este intento
    // FASE 1: Desasignar nueva ruta
    Utils.deallocateFs(graph, nueva, crosstalkPerUnitLength);
    removeRouteFromList(establishedRoutes, nueva);

    // FASE 2: Desasignar TODAS las rutas reinsertadas
    for (Map.Entry<EstablishedRoute, EstablishedRoute> e : moved.entrySet()) {
        EstablishedRoute reinsertada = e.getValue();
        Utils.deallocateFs(graph, reinsertada, crosstalkPerUnitLength);
    }

    // FASE 3: Restaurar TODOS los backups (no pueden ser sobrescritos)
    // Restaurar la que falló
    restoreSingleRoute(graph, backup);

    // Restaurar rutas reinsertadas
    for (Map.Entry<EstablishedRoute, EstablishedRoute> e : moved.entrySet()) {
        EstablishedRoute original = e.getKey();
        restoreSingleRoute(graph, backups.get(original));
    }

    // Restaurar las restantes desasignadas que aún no se reinsertaron
    for (EstablishedRoute rRest : mejorConflictSet) {
        if (!moved.containsKey(rRest) && rRest != r) {
            restoreSingleRoute(graph, backups.get(rRest));
        }
    }

    // FASE 4: Actualizar establishedRoutes
    for (Map.Entry<EstablishedRoute, EstablishedRoute> e : moved.entrySet()) {
        EstablishedRoute original = e.getKey();
        EstablishedRoute reinsertada = e.getValue();
        replaceRouteInList(establishedRoutes, reinsertada, original);
    }

    log("Intento " + (intento + 1) + ": rollback por fallo al reinsertar conflictos.");
    falloReinsercion = true;
    break;
}
```

### CAMBIO CLAVE
Idéntico al Bloque 1: Separación de fases deallocate → restore → update

---

## 3️⃣ BLOQUE 3: DFfullRuteoMin (líneas 820-880)

### ❌ ANTES

```java
captureSlotStateBefore(graph, "ROLLBACK-START", 
                      "Falló reinserción de ruta " + r.getFrom() + "->" + r.getTo() +
                      ", iniciando rollback completo");

Utils.deallocateFs(graph, nueva, crosstalkPerUnitLenght);
captureSlotStateAfter(graph, "ROLLBACK-DEALLOCATE-NUEVA", "Desasignó nueva ruta");
removeRouteFromList(establishedRoutes, nueva);

// Restaurar la que falló y reponer en lista
restoreSingleRoute(graph, backup);

// Deshacer re-ruteadas previas
for (Map.Entry<EstablishedRoute, EstablishedRoute> e : moved.entrySet()) {
    EstablishedRoute original = e.getKey();
    EstablishedRoute reinsertada = e.getValue();
    captureSlotStateBefore(graph, "ROLLBACK-UNDO-MOVED",
                          "Deshaciendo ruta " + reinsertada.getFrom() + "->" + reinsertada.getTo() +
                          " cores:" + reinsertada.getPathCores() + " fs:" + reinsertada.getFsIndexBegin() + "-" +
                          (reinsertada.getFsIndexBegin() + reinsertada.getFsWidth() - 1));
    Utils.deallocateFs(graph, reinsertada, crosstalkPerUnitLenght);
    captureSlotStateAfter(graph, "ROLLBACK-DEALLOCATE-MOVED", "Desasignó ruta reinsertada");
    restoreSingleRoute(graph, backups.get(original));
    replaceRouteInList(establishedRoutes, reinsertada, original);
}

// Restaurar las restantes aún no reinsertadas
for (EstablishedRoute rRest : resolvedConflictSet) {
    if (!moved.containsKey(rRest) && rRest != r) {
        captureSlotStateBefore(graph, "ROLLBACK-RESTORE-REMAINING",
                              "Restaurando ruta no reinsertada " + rRest.getFrom() + "->" + rRest.getTo());
        restoreSingleRoute(graph, backups.get(rRest));
    }
}

captureSlotStateAfter(graph, "ROLLBACK-END", "Rollback completo");
```

### ✅ DESPUÉS

```java
captureSlotStateBefore(graph, "ROLLBACK-START", 
                      "Falló reinserción de ruta " + r.getFrom() + "->" + r.getTo() +
                      ", iniciando rollback completo");

// FASE 1: Desasignar nueva ruta
Utils.deallocateFs(graph, nueva, crosstalkPerUnitLenght);
captureSlotStateAfter(graph, "ROLLBACK-DEALLOCATE-NUEVA", "Desasignó nueva ruta");
removeRouteFromList(establishedRoutes, nueva);

// FASE 2: Desasignar TODAS las rutas reinsertadas
for (Map.Entry<EstablishedRoute, EstablishedRoute> e : moved.entrySet()) {
    EstablishedRoute reinsertada = e.getValue();
    captureSlotStateBefore(graph, "ROLLBACK-DEALLOCATE-MOVED",
                          "Desasignando ruta reinsertada " + reinsertada.getFrom() + "->" + reinsertada.getTo() +
                          " cores:" + reinsertada.getPathCores() + " fs:" + reinsertada.getFsIndexBegin() + "-" +
                          (reinsertada.getFsIndexBegin() + reinsertada.getFsWidth() - 1));
    Utils.deallocateFs(graph, reinsertada, crosstalkPerUnitLenght);
    captureSlotStateAfter(graph, "ROLLBACK-DEALLOCATE-MOVED", "Desasignó ruta reinsertada");
}

// FASE 3: Restaurar TODOS los backups (no pueden ser sobrescritos)
// Restaurar la que falló
captureSlotStateBefore(graph, "ROLLBACK-RESTORE-FAILED",
                      "Restaurando ruta que falló " + r.getFrom() + "->" + r.getTo());
restoreSingleRoute(graph, backup);
captureSlotStateAfter(graph, "ROLLBACK-RESTORE-FAILED", "Restauró ruta que falló");

// Restaurar rutas reinsertadas
for (Map.Entry<EstablishedRoute, EstablishedRoute> e : moved.entrySet()) {
    EstablishedRoute original = e.getKey();
    captureSlotStateBefore(graph, "ROLLBACK-RESTORE-MOVED",
                          "Restaurando ruta original " + original.getFrom() + "->" + original.getTo());
    restoreSingleRoute(graph, backups.get(original));
    captureSlotStateAfter(graph, "ROLLBACK-RESTORE-MOVED", "Restauró ruta original");
}

// Restaurar las restantes aún no reinsertadas
for (EstablishedRoute rRest : resolvedConflictSet) {
    if (!moved.containsKey(rRest) && rRest != r) {
        captureSlotStateBefore(graph, "ROLLBACK-RESTORE-REMAINING",
                              "Restaurando ruta no reinsertada " + rRest.getFrom() + "->" + rRest.getTo());
        restoreSingleRoute(graph, backups.get(rRest));
        captureSlotStateAfter(graph, "ROLLBACK-RESTORE-REMAINING", "Restauró ruta no reinsertada");
    }
}

// FASE 4: Actualizar establishedRoutes
for (Map.Entry<EstablishedRoute, EstablishedRoute> e : moved.entrySet()) {
    EstablishedRoute original = e.getKey();
    EstablishedRoute reinsertada = e.getValue();
    replaceRouteInList(establishedRoutes, reinsertada, original);
}

captureSlotStateAfter(graph, "ROLLBACK-END", "Rollback completo");
```

### CAMBIO CLAVE
Mismo patrón pero con instrumentación captureSlotState:
- Loop 1: Solo deallocate
- Loop 2: Solo restore
- Loop 3: Solo replaceRouteInList

---

## RESUMEN DE CAMBIOS

### QUÉ SE MODIFICÓ

**Estructura del rollback en los 3 algoritmos:**

| Fase | Antes | Después |
|------|-------|---------|
| 1 | deallocateFs(nueva) | deallocateFs(nueva) |
| 2 | **restoreSingleRoute(backup)** ← restaura | Loop: deallocateFs(reinsertadas) ← desasigna |
| 3 | Loop: deallocate + restore + replace | **restoreSingleRoute(backup)** ← restaura al final |
| 4 | Restore no reinsertadas | Loop: restore(originales) |
| 5 | - | Loop: restore(no reinsertadas) |
| 6 | - | Loop: replaceRouteInList() |

### POR QUÉ FUNCIONA

**ANTES**: `restoreSingleRoute(backup)` ejecuta **ANTES** de `deallocateFs(reinsertadas)`
- Si ruta reinsertada usa enlace inverso (14→10) al del backup (10→14)
- Y comparten cores (arquitectura bidireccional)
- Entonces deallocateFs **sobrescribe** el restore → BUG

**DESPUÉS**: `deallocateFs(reinsertadas)` ejecuta **ANTES** de `restoreSingleRoute(backup)`
- Todos los deallocate terminan primero
- Todas las restauraciones ejecutan al final
- Nada puede sobrescribirlas → FIX

---

## CONFIRMACIONES

✅ **Compilación exitosa** sin errores ni warnings relevantes  
✅ **Solo 1 archivo modificado**: `Defragmenter.java`  
✅ **Solo 3 bloques cambiados**: DFbFRmax, DFbFRmin, DFfullRuteoMin  
✅ **Ninguna otra lógica modificada**: assignFs, deallocateFs, Utils, Link, Core, posicionDelEnlaceEnRuta sin cambios  
✅ **Semántica preservada**: Mismo set de operaciones, solo cambió el orden  
✅ **establishedRoutes sin cambios prematuros**: replaceRouteInList se ejecuta en Fase 4 como antes

---

## RESULTADO ESPERADO

**Antes del fix**:
- 76 violaciones FSDM (17 eventos)
- Success rates: 15-55%

**Después del fix**:
- 0 violaciones FSDM ← Las restauraciones no se sobrescriben
- Success rates: ≥ 15-55% (mantener o mejorar)
