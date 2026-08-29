# ANÁLISIS FORENSE: Caso link 10-14 fs 230-232

## OBJETIVO 4: Reconstruir UN caso completo

### Estado inicial (T0)
```
Ruta A: 21->0
  - Path: 21-15-11-10-5-0
  - Cores: [2,3,2,3,2,3,2,3,2,3]
  - FS: 230-232
  - Lifetime: 32
  - Link 11-10 ocupa fs:230-232

Ruta B: 7->14
  - Path: 7-6-8-10-14
  - Cores: [2,3,2,3,2,3,2,3]
  - FS: 230-232
  - Lifetime: 579
  - Link 10-14 ocupa fs:230-232
```

### Paso 1: Detección de conflicto
- Demanda bloqueada: 20->2 (fs=5, width=3)
- Algoritmo: DFfullRuteoMin P1
- ConflictSet detectado: {21->0, 7->14}
- Ventana elegida: fs:230-232 (suma mínima de conflictos=2)

### Paso 2: Backups creados
```
Backup A: 21->0 [ID:306cab9]
  - Path: 21-15-11-10-5-0
  - FS: 230-232
  - Cada slot: isFree=false, lifetime=32

Backup B: 7->14 [ID:270a1193]
  - Path: 7-6-8-10-14
  - FS: 230-232
  - Cada slot: isFree=false, lifetime=579
```

### Paso 3: Desasignación (línea 692 Defragmenter.java)
```
Utils.deallocateFs(graph, ruta A);  // Libera fs:230-232 en link 11-10
Utils.deallocateFs(graph, ruta B);  // Libera fs:230-232 en link 10-14
```
**Estado después**: Todos los slots fs:230-232 → isFree=true, lifetime=0

### Paso 4: Nueva ruta asignada ✅ ÉXITO
```
Nueva ruta: 20->2
  - Path: 20-15-11-8-6-2
  - Cores: [2,3,2,3,2,3,2,3,2,3]
  - FS: 230-232
  - Ocupa links: 20-15, 15-11, 11-8, 8-6, 6-2
  - NO ocupa link 10-14
```

### Paso 5: Reinserción ruta A ✅ ÉXITO
```
Ruta reinsertada: 21->0 [nueva instancia]
  - Path NUEVO: 21-15-14-10-5-0  (CAMBIÓ de 11-10 a 14-10)
  - Cores: [2,3,2,3,2,3,2,3,2,3]
  - FS: 230-232
  - Ocupa links: 21-15, 15-14, 14-10, 10-5, 5-0
  - ⚠️ CRÍTICO: Usa link 14-10 (INVERSO de 10-14)
```
**Estado después**: Link 14-10 ocupa fs:230-232 → isFree=false
**NOTA**: 14-10 y 10-14 comparten EXACTAMENTE los mismos objetos Core

### Paso 6: Reinserción ruta B ❌ FALLA
```
Intento reinsertar: 7->14
  - ruteoCoreMultiple() retorna NULL
  - Motivo: No hay FS disponibles en todos los KSP paths
```

### Paso 7: ROLLBACK COMPLETO (líneas 770-843)

#### 7.1. Desasignar nueva ruta (línea 806)
```java
Utils.deallocateFs(graph, nueva, crosstalkPerUnitLenght);
```
Libera fs:230-232 en links: 20-15, 15-11, 11-8, 8-6, 6-2
Link 10-14 NO se toca (nueva ruta no lo usaba)

#### 7.2. Restaurar ruta que falló (línea 810)
```java
restoreSingleRoute(graph, backup);  // backup = 7->14
```
**CRÍTICO**: Restaura fs:230-232 en link 10-14:
```java
// Defragmenter.java línea 1552-1555
graphSlots.get(fs).setFree(backupSlots.get(fs).isFree());  // false
graphSlots.get(fs).setLifetime(backupSlots.get(fs).getLifetime());  // 579
```
**Estado después**: Link 10-14 fs:230-232 → isFree=false, lifetime=579 ✅

#### 7.3. Deshacer rutas reinsertadas (líneas 813-821)
```java
for (Map.Entry<EstablishedRoute, EstablishedRoute> e : moved.entrySet()) {
    EstablishedRoute original = e.getKey();  // 21->0 original
    EstablishedRoute reinsertada = e.getValue();  // 21->0 reinsertada
    
    // LÍNEA 818: Desasignar ruta reinsertada
    Utils.deallocateFs(graph, reinsertada, crosstalkPerUnitLenght);
    
    // LÍNEA 820: Restaurar backup original
    restoreSingleRoute(graph, backups.get(original));
}
```

**⚠️ AQUÍ OCURRE LA VIOLACIÓN:**

Línea 818 ejecuta:
```java
Utils.deallocateFs(graph, reinsertada);  // reinsertada = 21->0 path:21-15-14-10-5-0
```

Esto libera fs:230-232 en TODOS los links del path:
- Link 21-15 → fs:230-232 → isFree=true
- Link 15-14 → fs:230-232 → isFree=true
- **Link 14-10** → fs:230-232 → isFree=true ❌
- Link 10-5 → fs:230-232 → isFree=true
- Link 5-0 → fs:230-232 → isFree=true

**PROBLEMA**: Link 14-10 y Link 10-14 comparten los MISMOS objetos Core.

Cuando Utils.deallocateFs() libera los slots en link 14-10, también libera los slots en link 10-14 porque apuntan al mismo objeto.

**Estado después línea 818**: Link 10-14 fs:230-232 → **isFree=true** (SOBRESCRIBIÓ el restore de línea 810!)

Línea 820 ejecuta:
```java
restoreSingleRoute(graph, backups.get(original));  // backup = 21->0 original
```

Esto restaura fs:230-232 en link 11-10 (path original: 21-15-11-10-5-0)
Link 10-14 NO se toca (el backup original no lo usaba)

### Paso 8: Validación (línea 843)
```java
validateRollbackState(graph, backups, establishedRoutes, globalReport, rollbackCounter);
```

Compara backup B (7->14) vs grafo:
```
Backup B link 10-14 core 2 fs 230:
  - isFree=false, lifetime=579

Grafo link 10-14 core 2 fs 230:
  - isFree=true, lifetime=0

❌ VIOLACIÓN: Rollback incompleto en link 10-14 core 2 fs 230
```

## CAUSA RAÍZ

El rollback sigue este orden:
1. **Línea 810**: restoreSingleRoute(backup 7->14) → Link 10-14 → isFree=false ✅
2. **Línea 818**: deallocateFs(reinsertada 21->0) → Link 14-10 → isFree=true → **AFECTA link 10-14 porque comparten cores!** ❌
3. **Línea 820**: restoreSingleRoute(backup 21->0) → Link 11-10 → NO toca link 10-14

La desasignación de una ruta reinsertada **sobrescribe** la restauración anterior debido a que los links bidireccionales comparten objetos Core.

## OBJETIVO 3: Significado de "Rollback incompleto"

**Código validador** (Defragmenter.java líneas 1965-1973):
```java
FrequencySlot backupSlot = backupLink.getCores().get(core).getFrequencySlots().get(fs);
FrequencySlot graphSlot = graphLink.getCores().get(core).getFrequencySlots().get(fs);

if (backupSlot.isFree() != graphSlot.isFree() || 
    backupSlot.getLifetime() != graphSlot.getLifetime()) {
    violationCount++;
    report.fail("Rollback incompleto en " + violation);
}
```

**Significado**: El estado del grafo después del rollback NO coincide con el estado guardado en el backup.

**Específicamente en este caso**:
- Backup esperaba: isFree=false, lifetime=579 (slot ocupado por ruta 7->14)
- Grafo tiene: isFree=true, lifetime=0 (slot libre)

**Conclusión**: restoreSingleRoute() SÍ funciona correctamente, pero una operación posterior (deallocateFs de otra ruta) sobrescribe el estado restaurado debido a que los links bidireccionales comparten objetos Core.

## OBJETIVO 2: Mapa del rollback

**Algoritmo**: DFfullRuteoMin P1  
**Demanda bloqueada**: 20->2 (fs=5)  
**ConflictSet**: {21->0, 7->14}  
**Ventana**: fs:230-232  
**Rutas desasignadas**: 21->0, 7->14  
**Nueva ruta**: 20->2 path:20-15-11-8-6-2 fs:230-232 (ÉXITO)  
**Reinserciones**:
  - 21->0: path:21-15-14-10-5-0 fs:230-232 (ÉXITO) ← usa link 14-10
  - 7->14: NULL (FALLA)

**Punto de fallo**: Reinserción de 7->14 retorna NULL  
**Rollback ejecutado**:
1. Desasigna nueva ruta 20->2
2. Restaura backup 7->14 → link 10-14 → isFree=false ✅
3. Desasigna ruta reinsertada 21->0 → link 14-10 → **sobrescribe link 10-14 → isFree=true** ❌
4. Restaura backup 21->0 → link 11-10 → NO afecta link 10-14

**Cambios en establishedRoutes**:
- Antes rollback: [nueva 20->2, reinsertada 21->0, todas las demás]
- Después rollback: [original 21->0, original 7->14, todas las demás]

## ARCHIVOS/MÉTODOS/LÍNEAS RELEVANTES

### Defragmenter.java
- **Línea 770**: Inicio del rollback completo
- **Línea 806**: Desasigna nueva ruta
- **Línea 810**: Restaura ruta que falló (7->14) ← Pone isFree=false en link 10-14
- **Línea 818**: Desasigna ruta reinsertada (21->0) ← **SOBRESCRIBE link 10-14 a isFree=true**
- **Línea 820**: Restaura backup original (21->0)
- **Línea 843**: Validación detecta violación
- **Líneas 1540-1570**: restoreSingleRoute() (funciona correctamente)
- **Líneas 1950-2000**: validateRollbackState() (detecta la violación)

### Utils.java
- **Líneas 253-268**: deallocateFs() libera slots en TODOS los links del path
- **Línea 264**: `fs.setFree(true)` ← Pone isFree=true
- **Líneas 68-81**: Arquitectura bidireccional con cores compartidos

## Logs de evidencia
- experimento_bidireccional.log líneas 3703547-3703680 (caso completo)
- caso_230_completo.txt (extracto)
