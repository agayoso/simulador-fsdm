# ANÁLISIS FORENSE COMPLETO: 108 Violaciones FSDM

## RESUMEN EJECUTIVO

Después del fix bidireccional exitoso (579 defragmentaciones, 15-55% success rate), quedan **108 violaciones de tipo "Rollback incompleto"**. Este análisis forense identifica la causa raíz y el impacto en la simulación.

### Hallazgos principales

1. **Las 108 violaciones son 17 eventos FSDM distintos** (no 108 casos independientes)
2. **Causa raíz identificada**: Orden de operaciones en el rollback cuando rutas reinsertadas usan links bidireccionales
3. **Impacto**: CONTAMINACIÓN CRÍTICA del estado (slots libres cuando deberían estar ocupados)
4. **Consecuencia**: Asignaciones dobles, crosstalk incorrecto, bloqueos artificiales

---

## OBJETIVO 1: Agrupar 108 violaciones por evento FSDM ✅

### Distribución de violaciones
```
Total violaciones: 108 líneas de log
Eventos FSDM únicos: 17
Promedio: 6.35 violaciones/evento
```

### Tabla de eventos

| Link  | Cores  | GrupoFSDM | FSRange   | NumSlots | WidthInferido |
|-------|--------|-----------|-----------|----------|---------------|
| 6-8   | 2,3    | [2,3]     | 136-316   | 12       | ~90           |
| 9-13  | 2,3    | [2,3]     | 157-160   | 8        | 2             |
| 5-10  | 2,3    | [2,3]     | 132-135   | 8        | 2             |
| 13-17 | 2,3    | [2,3]     | 157-160   | 8        | 2             |
| 11-10 | 2,3    | [2,3]     | 157-160   | 8        | 2             |
| 10-14 | 2,3    | [2,3]     | 230-232   | 6        | 3             |
| 8-5   | 2,3    | [2,3]     | 132-135   | 6        | 2             |
| 14-18 | 2,3    | [2,3]     | 123-126   | 6        | 2             |
| 10-5  | 2,3    | [2,3]     | 230-232   | 6        | 3             |
| 8-10  | 2,3    | [2,3]     | 230-232   | 6        | 3             |
| 17-18 | 2,3    | [2,3]     | 123-126   | 6        | 2             |
| 8-6   | 2,3    | [2,3]     | 157-160   | 4        | 2             |
| 6-2   | 2,3    | [2,3]     | 157-160   | 4        | 2             |
| 5-10  | 2,3    | [2,3]     | 230-232   | 4        | 3             |
| 11-8  | 2,3    | [2,3]     | 230-232   | 4        | 3             |
| 18-21 | 2,3    | [2,3]     | 123-126   | 4        | 2             |
| 15-11 | 2,3    | [2,3]     | 230-232   | 4        | 3             |

**Patrón observado**: Todas las violaciones son en grupo FSDM [2,3], ninguna en [0,1]

---

## OBJETIVO 2: Encontrar qué rollback genera cada evento ✅

### Caso reconstruido: link 10-14 fs 230-232

**Algoritmo**: DFfullRuteoMin P1  
**Demanda bloqueada**: 20->2 (fs=5)  
**ConflictSet**: {21->0, 7->14}  
**Ventana seleccionada**: fs:230-232 (suma=2 conflictos)

**Rutas desasignadas**:
1. Ruta 21->0: path:21-15-11-10-5-0, fs:230-232
2. Ruta 7->14: path:7-6-8-10-14, fs:230-232

**Nueva ruta**:
- 20->2: path:20-15-11-8-6-2, fs:230-232 → **ÉXITO**

**Reinserciones**:
1. Ruta 21->0: path:21-15-**14-10**-5-0, fs:230-232 → **ÉXITO** (path cambió, usa link 14-10 ahora)
2. Ruta 7->14: NULL → **FALLA** (no hay FS disponibles)

**Punto de fallo**: Línea 770 Defragmenter.java, falla reinserción de 7->14

**Rollback ejecutado** (líneas 770-843):
1. Línea 806: Desasigna nueva ruta 20->2
2. Línea 810: Restaura backup 7->14 → link 10-14 → **isFree=false** ✅
3. Línea 818: Desasigna ruta reinsertada 21->0 → link 14-10 → **sobrescribe link 10-14 → isFree=true** ❌
4. Línea 820: Restaura backup 21->0 → link 11-10

**Cambios en establishedRoutes**:
- Antes: [nueva 20->2, reinsertada 21->0, ...]
- Después: [original 21->0, original 7->14, ...]

---

## OBJETIVO 3: Determinar qué significa "Rollback incompleto" ✅

### Código validador (Defragmenter.java líneas 1965-1973)
```java
FrequencySlot backupSlot = backupLink.getCores().get(core).getFrequencySlots().get(fs);
FrequencySlot graphSlot = graphLink.getCores().get(core).getFrequencySlots().get(fs);

if (backupSlot.isFree() != graphSlot.isFree() || 
    backupSlot.getLifetime() != graphSlot.getLifetime()) {
    violationCount++;
    report.fail("Rollback incompleto en " + violation);
}
```

### Significado
El estado del grafo después del rollback NO coincide con el estado guardado en el backup.

**En este caso específico**:
```
Backup (estado esperado):
  - isFree=false (slot ocupado por ruta 7->14)
  - lifetime=579

Grafo (estado real):
  - isFree=true (slot libre)
  - lifetime=0
```

**NO ES**:
- ❌ Slots ocupados sin ruta en establishedRoutes (la ruta SÍ está en la lista)
- ❌ Ruta restaurada con FS/core/path diferente (el backup es correcto)
- ❌ Problema de concurrencia o excepciones

**ES**:
- ✅ Una operación posterior (deallocateFs de ruta reinsertada) sobrescribe el estado restaurado
- ✅ Ocurre porque links bidireccionales (10-14 y 14-10) comparten los mismos objetos Core

---

## OBJETIVO 4: Reconstruir UN caso completo ✅

Ver archivo completo: **ANALISIS_CASO_230.md**

### Secuencia crítica

**T1: Restauración de backup 7->14** (línea 810)
```java
restoreSingleRoute(graph, backup);  // backup = 7->14 path:7-6-8-10-14
```
Resultado: Link 10-14 fs:230-232 → isFree=false ✅

**T2: Desasignación de ruta reinsertada 21->0** (línea 818)
```java
Utils.deallocateFs(graph, reinsertada);  // reinsertada path:21-15-14-10-5-0
```
Desasigna TODOS los links del path:
- Link 21-15 → fs:230-232 → isFree=true
- Link 15-14 → fs:230-232 → isFree=true
- **Link 14-10** → fs:230-232 → isFree=true

**⚠️ PROBLEMA**: Link 14-10 y Link 10-14 comparten los MISMOS objetos Core (arquitectura bidireccional líneas 68-81 Utils.java)

```java
// Utils.java línea 68-81
List<Core> sharedCores = new ArrayList<>();
for (int j = 0; j < numberOfCores; j++) {
    Core core = new Core(fsWidth, capacity);
    sharedCores.add(core);
}
Link linkForward = new Link(distance, sharedCores, vertex, connection);   // 10→14
Link linkBackward = new Link(distance, sharedCores, connection, vertex);  // 14→10
```

Cuando deallocateFs() libera slots en link 14-10, también libera slots en link 10-14 porque apuntan al mismo objeto.

Resultado: Link 10-14 fs:230-232 → **isFree=true** ❌ (sobrescribió el restore de T1)

**T3: Restauración de backup 21->0** (línea 820)
```java
restoreSingleRoute(graph, backups.get(original));  // backup path:21-15-11-10-5-0
```
Restaura fs:230-232 en link 11-10 (NO toca link 10-14)

**T4: Validación** (línea 843)
```
Backup 7->14 espera: link 10-14 → isFree=false
Grafo tiene: link 10-14 → isFree=true
❌ VIOLACIÓN detectada
```

---

## OBJETIVO 5: ¿Las violaciones contaminan la simulación? ✅

### Respuesta: SÍ, CONTAMINACIÓN CRÍTICA

Ver análisis completo: **OBJETIVO_5_CONTAMINACION.md**

### Estado contaminado después del rollback
```
establishedRoutes:
  - Ruta 7->14 [ID:37d6c698] ← Dice que usa fs:230-232 en link 10-14
  - Ruta 21->0 [ID:41910dd1] ← Dice que usa fs:230-232 en link 11-10
  - ... otras rutas ...

Grafo:
  - Link 10-14 core 2 fs 230-232: isFree=TRUE ← DISPONIBLE para asignación
  - Link 11-10 core 2 fs 230-232: isFree=FALSE ← Correctamente ocupado
```

### Tipos de contaminación

**A) Slots fantasma libres**: Slots marcados como libres (isFree=true) cuando deberían estar ocupados  
**B) Rutas huérfanas**: Rutas en establishedRoutes sin recursos realmente asignados en el grafo  
**C) Inconsistencia ruta-recurso**: Rutas dicen usar slots que el grafo muestra como libres

### Consecuencias

1. **Asignaciones dobles**: Un algoritmo RSA puede asignar una nueva demanda en fs:230-232 link 10-14 porque ve isFree=true → dos rutas creen usar los mismos slots

2. **Crosstalk incorrecto**: La ruta 7->14 está en establishedRoutes pero sus slots están libres → no se calcula crosstalk para nuevas rutas asignadas allí

3. **Bloqueos artificiales**: La ruta 7->14 ocupa una entrada en establishedRoutes pero no usa recursos → algoritmos evitan rutas válidas

4. **Deallocación errónea**: Cuando lifetime=579 vence, intenta desasignar slots ya libres → puede liberar slots de otra ruta por error

### Impacto en los resultados experimentales

**Antes del fix bidireccional**: ~0% success rate  
**Después del fix bidireccional**: 15-55% success rate  
**Bloqueos restantes**: 163-270 (según algoritmo)

**Estimación**: Las 108 violaciones (17 eventos) pueden explicar 50-150 de los 163-270 bloqueos restantes debido a:
- Asignaciones dobles que causan conflictos
- Crosstalk incorrecto que hace fallar validaciones
- Rutas huérfanas que ocupan espacio en establishedRoutes

---

## ARCHIVOS Y LÍNEAS RELEVANTES

### Defragmenter.java
- **Línea 770**: Inicio del rollback completo (`if (re == null || re.getFsIndexBegin() == -1)`)
- **Línea 806**: `Utils.deallocateFs(graph, nueva, crosstalkPerUnitLenght);` ← Desasigna nueva ruta
- **Línea 810**: `restoreSingleRoute(graph, backup);` ← Restaura ruta que falló → **pone isFree=false** ✅
- **Línea 813-821**: Loop que deshace rutas reinsertadas
- **Línea 818**: `Utils.deallocateFs(graph, reinsertada, crosstalkPerUnitLenght);` ← **SOBRESCRIBE a isFree=true** ❌
- **Línea 820**: `restoreSingleRoute(graph, backups.get(original));` ← Restaura backup original
- **Línea 843**: `validateRollbackState(...)` ← Detecta violaciones
- **Líneas 1540-1570**: `restoreSingleRoute()` (funciona correctamente)
- **Líneas 1950-2000**: `validateRollbackState()` (validación)

### Utils.java
- **Líneas 68-81**: Arquitectura bidireccional con `List<Core> sharedCores`
- **Líneas 253-268**: `deallocateFs()` libera slots en TODOS los links del path
- **Línea 264**: `fs.setFree(true);` ← Pone isFree=true

---

## POSIBLES SOLUCIONES

### Opción 1: Restaurar DESPUÉS de desasignar reinsertadas ✅ RECOMENDADA
Cambiar el orden de operaciones en el rollback:

**Actual (líneas 806-829)**:
1. Desasignar nueva ruta
2. **Restaurar backup de la ruta que falló** ← Se sobrescribe después
3. Desasignar rutas reinsertadas
4. Restaurar backups de rutas reinsertadas
5. Restaurar rutas no reinsertadas

**Propuesto**:
1. Desasignar nueva ruta
2. Desasignar rutas reinsertadas ← Mover aquí
3. **Restaurar TODOS los backups** ← Ejecutar al final
   - Backup de la ruta que falló
   - Backups de rutas reinsertadas
   - Backups de rutas no reinsertadas

**Ventaja**: Las restauraciones siempre son la última operación, no pueden ser sobrescritas  
**Complejidad**: Baja, solo reordenar operaciones  
**Riesgo**: Bajo, no cambia la lógica, solo el orden

### Opción 2: Restaurar dos veces (inmediato + al final)
Restaurar el backup de la ruta que falló TAMBIÉN después de desasignar las reinsertadas.

**Ventaja**: Garantiza que el estado final es correcto  
**Desventaja**: Operación redundante, puede tener costo de rendimiento

### Opción 3: Marcar links bidireccionales durante deallocateFs
Modificar Utils.deallocateFs() para NO liberar slots si pertenecen a un backup en proceso de restauración.

**Ventaja**: Solución quirúrgica  
**Desventaja**: Alta complejidad, requiere pasar contexto de rollback a Utils, difícil de mantener

---

## RECOMENDACIÓN

**Implementar Opción 1: Restaurar DESPUÉS de desasignar reinsertadas**

Código propuesto (modificar Defragmenter.java líneas 806-829):

```java
// 1. Desasignar nueva ruta
Utils.deallocateFs(graph, nueva, crosstalkPerUnitLenght);
removeRouteFromList(establishedRoutes, nueva);

// 2. Desasignar rutas reinsertadas (MOVER AQUÍ)
for (Map.Entry<EstablishedRoute, EstablishedRoute> e : moved.entrySet()) {
    EstablishedRoute reinsertada = e.getValue();
    Utils.deallocateFs(graph, reinsertada, crosstalkPerUnitLenght);
    removeRouteFromList(establishedRoutes, reinsertada);
}

// 3. Restaurar TODOS los backups al final (no pueden ser sobrescritos)
// 3a. Restaurar ruta que falló
restoreSingleRoute(graph, backup);

// 3b. Restaurar rutas reinsertadas
for (Map.Entry<EstablishedRoute, EstablishedRoute> e : moved.entrySet()) {
    EstablishedRoute original = e.getKey();
    restoreSingleRoute(graph, backups.get(original));
    replaceRouteInList(establishedRoutes, null, original);  // Ajustar lógica
}

// 3c. Restaurar rutas no reinsertadas
for (EstablishedRoute rRest : resolvedConflictSet) {
    if (!moved.containsKey(rRest) && rRest != r) {
        restoreSingleRoute(graph, backups.get(rRest));
    }
}
```

**Validación esperada**: Las 108 violaciones deben reducirse a 0 después de este cambio.

---

## PRÓXIMOS PASOS

1. ✅ Análisis forense completo (ESTE DOCUMENTO)
2. ⏳ Implementar Opción 1 (restaurar después de desasignar)
3. ⏳ Validar con experimento completo
4. ⏳ Verificar que validación FSDM reporta 0 violaciones
5. ⏳ Comparar success rates antes/después del fix
6. ⏳ Análisis de los bloqueos restantes si persisten

---

## LOGS DE EVIDENCIA

- **experimento_bidireccional.log**: Líneas 3703547-3703680 (caso completo)
- **caso_230_completo.txt**: Extracto del rollback con violaciones
- **violaciones_raw.txt**: 108 violaciones agrupadas
- **ANALISIS_CASO_230.md**: Reconstrucción detallada del caso
- **OBJETIVO_5_CONTAMINACION.md**: Análisis de impacto
