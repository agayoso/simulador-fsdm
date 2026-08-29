# ANÁLISIS DETALLADO DE SOBRESCRITURAS EN assignFs()

## RESUMEN EJECUTIVO

**ROOT CAUSE IDENTIFICADO**: `assignFs()` sobrescribe slots que están ocupados por rutas establecidas que NO están en el conflictSet.

**EVIDENCIA**: Instrumentación detectó múltiples sobrescrituras durante la asignación de la nueva demanda (PASO 4.2).

---

## CASO 1: DIAGNÓSTICO #1821

### Contexto
- **Demanda bloqueada**: ID desconocido, origen=7, destino=19
- **Intento**: 2 de N
- **Start**: 312
- **ConflictSet**: 3 rutas
  1. Ruta 16->6: fs[311-314] cores 2-3
  2. Ruta 9->14: fs[313-316] cores 0-1
  3. Ruta 9->2: fs[312-315] cores 0-1

### Secuencia de Operaciones

#### ANTES del intento (Estado inicial)
**Link 20-21, core 0, fs313**:
- `free`: ocupado
- `lifetime`: 1598
- **Ocupado por**: Ruta X (NO en conflictSet)

**Link 19-20, core 0, fs312**:
- `free`: ocupado
- `lifetime`: 818
- **Ocupado por**: Ruta Y (NO en conflictSet)

#### PASO 4.1: Desasignar rutas del conflictSet
- `deallocateFs(16->6)` → libera fs[311-314] cores 2-3
- `deallocateFs(9->14)` → libera fs[313-316] cores 0-1
- `deallocateFs(9->2)` → libera fs[312-315] cores 0-1

**Resultado**: Slots 312-315 quedan libres en cores 0-1, pero **siguen ocupados en otros cores por rutas NO en conflictSet**.

#### PASO 4.2: assignFs(nueva demanda 7->19)
Nueva ruta: 7->19 | fs[312-314] | cores [0,1,0,1,0,1,0,1,0,1,0,1]

**⚠️ SOBRESCRITURAS DETECTADAS**:

1. **Link 20-21, core 0, fs313**:
   ```
   Estado ANTERIOR: ocupado, lifetime=1598
   Estado SOBRESCRITO: ocupado, lifetime=589  ← DESTRUYE INFO DE RUTA X
   ```

2. **Link 20-21, core 0, fs314**:
   ```
   Estado ANTERIOR: ocupado, lifetime=1598
   Estado SOBRESCRITO: ocupado, lifetime=589
   ```

3. **Link 19-20, core 0, fs312**:
   ```
   Estado ANTERIOR: ocupado, lifetime=818
   Estado SOBRESCRITO: ocupado, lifetime=589  ← DESTRUYE INFO DE RUTA Y
   ```

**Total**: 11 sobrescrituras detectadas en esta demanda.

#### PASO 4.3: Reinsertar conflictSet
- Ruta 16->6 reinsertada exitosamente en fs[311-314] cores 2-3
- Falla reinserción de siguiente ruta → ROLLBACK

#### PASO ROLLBACK.1: deallocateFs(nueva)
- Libera fs[312-314] en todos los links y cores de la ruta 7->19
- **Link 19-20, core 0, fs312**: ocupado → **libre**, lifetime 589 → **0**
- **Link 19-20, core 0, fs313**: ocupado → **libre**, lifetime 589 → **0**
- **Link 20-21, core 0, fs313**: ocupado → **libre**, lifetime 589 → **0**

**PROBLEMA**: Estos slots deberían volver a estar ocupados con lifetimes 818 y 1598, pero esa información se perdió en el paso 4.2.

#### PASO ROLLBACK.2: deallocateFs(reinsertadas)
- `deallocateFs(16->6)` → libera fs[311-314] cores 2-3

#### PASO ROLLBACK.3: restoreSingleRoute()
- Restaura Ruta 16->6: fs[311-314] cores 2-3
- Restaura Ruta 9->14: fs[313-316] cores 0-1
- Restaura Ruta 9->2: fs[312-315] cores 0-1

**PERO**: Las rutas del conflictSet NO incluyen:
- Link 19-20
- Link 20-21

Por lo tanto, **NO se restauran los slots que fueron sobrescritos en esos links**.

### Estado FINAL (Corrupto)

**Recursos corruptos detectados**: 48 recursos

Ejemplos:
- `19-20/core0/fs312`: ocupado → **libre** (lifetime 818 → 0) ❌
- `19-20/core0/fs313`: ocupado → **libre** (lifetime 1598 → 0) ❌
- `19-20/core0/fs314`: ocupado → **libre** (lifetime 1598 → 0) ❌
- `20-21/core0/fs313`: ocupado → **libre** (lifetime 1598 → 0) ❌
- `12-16/core0/fs312-314`: todos liberados incorrectamente ❌

### Conclusión CASO 1

**La nueva demanda sobrescribió slots ocupados por rutas establecidas que NO estaban en el conflictSet**. Cuando se hace rollback, esos slots quedan libres porque `restoreSingleRoute()` solo restaura las rutas del conflictSet.

---

## CASO 2: DIAGNÓSTICO #1822

### Contexto
- **Demanda**: 7->19
- **Intento**: 3
- **Start**: 313
- **ConflictSet**: Mismo que caso 1 (3 rutas)

### Sobrescrituras Detectadas

**8 sobrescrituras** en links:
- Link 12-16, core 0-1, fs315 (lifetime anterior: 220)
- Link 16-21, core 0-1, fs315 (lifetime anterior: 1598)
- Link 19-20, core 0-1, fs315 (lifetime anterior: 1598)
- Link 20-21, core 0-1, fs315 (lifetime anterior: 1598)

### Recursos Corruptos Finales

**16 recursos** corruptos, todos en **fs315**:
- 19-20/core0-1/fs315
- 12-16/core0-1/fs315
- 16-21/core0-1/fs315
- 20-21/core0-1/fs315

**Patrón idéntico**: assignFs() sobrescribe → rollback libera → restoreSingleRoute() no restaura porque esos links NO están en las rutas del conflictSet.

---

## CASO 3: DIAGNÓSTICO #1827

### Contexto
- **Demanda**: 19->7
- **Intento**: 1
- **Start**: 315
- **ConflictSet**: 2 rutas
  1. Ruta 9->14: fs[313-316] cores 0-1
  2. Ruta 9->2: fs[312-315] cores 0-1

### Sobrescrituras Detectadas

**12 sobrescrituras** en:
- Link 9-12, cores 2-3, fs315-317 (lifetime anterior: 7)
- Link 7-9, cores 2-3, fs315-317 (lifetime anterior: 7)

**OBSERVACIÓN CRÍTICA**: La nueva demanda usa **cores 2-3**, pero las rutas del conflictSet usan **cores 0-1**. Los slots sobrescritos pertenecen a una ruta establecida en cores 2-3 que NO fue detectada como conflictiva.

### Recursos Corruptos Finales

**24 recursos** corruptos:
- 7-9/core2-3/fs315-317: todos liberados (lifetime 7 → 0)
- 9-12/core2-3/fs315-317: todos liberados (lifetime 7 → 0)

---

## ANÁLISIS TRANSVERSAL

### Patrón Común en Todos los Casos

1. **evaluarVentanaMinSuma()** calcula un conflictSet INCOMPLETO
   - Detecta rutas que usan los mismos slots en algunos links
   - **NO detecta** todas las rutas que usan esos slots en TODOS los links de la nueva demanda

2. **deallocateFs(conflictSet)** libera slots en algunos cores
   - Otros cores en los mismos slots pueden seguir ocupados por rutas NO en conflictSet

3. **assignFs(nueva)** sobrescribe slots en TODOS los cores/links de la nueva demanda
   - Sobrescribe información (lifetime) de rutas NO en conflictSet
   - `free` sigue siendo `false` (ocupado), pero `lifetime` cambia

4. **Falla reinserción** → ROLLBACK

5. **deallocateFs(nueva)** libera TODOS los slots de la nueva demanda
   - Incluye slots que pertenecían a rutas NO en conflictSet
   - Esos slots quedan `free=true, lifetime=0`

6. **restoreSingleRoute(conflictSet)** restaura SOLO las rutas del conflictSet
   - NO restaura rutas NO en conflictSet
   - **Corrupción permanente**

### Root Cause

**El bug NO está en el rollback. El bug está en la fase de ASIGNACIÓN (PASO 4.2)**.

`assignFs()` sobrescribe **ciegamente** todos los slots del rango [fsIndexBegin, fsIndexBegin+fsWidth) en todos los cores/links de la ruta, **sin verificar si hay otras rutas ocupando esos slots en cores no cubiertos por el conflictSet**.

### Por Qué el ConflictSet es Incompleto

El método `evaluarVentanaMinSuma()` calcula conflictos basándose en:
```java
Set<EstablishedRoute> conflictSet = detectarConflictos(pathLinks, start, fs, establishedRoutes);
```

Aparentemente, `detectarConflictos()` solo marca como conflictivas las rutas que **comparten TODOS los links del path de la nueva demanda**, pero NO detecta rutas que solo comparten ALGUNOS links.

**Ejemplo del CASO 1**:
- Nueva demanda 7->19 pasa por links: ... → 19-20 → 20-21 → ...
- ConflictSet detectado: rutas 16->6, 9->14, 9->2 (ninguna pasa por 19-20 ni 20-21)
- Rutas NO detectadas: Ruta X (pasa por 19-20 y 20-21, usa fs313-314, lifetime=1598)

---

## VERIFICACIÓN NECESARIA

Para confirmar completamente el root cause, necesito analizar:

1. ✅ **assignFs()**: Confirmado que sobrescribe sin verificar
2. ⚠️ **evaluarVentanaMinSuma()** / **detectarConflictos()**: Verificar lógica de detección
3. ⚠️ **ruteoCoreMultiple()**: Verificar si permite asignar sobre slots ocupados

---

## CONCLUSIONES

1. **assignFs() sobrescribe slots ocupados** ✅ PROBADO
2. **El conflictSet es incompleto** ✅ PROBADO (casos 1, 2, 3)
3. **La corrupción ocurre en PASO 4.2 (assignFs), NO en el rollback** ✅ PROBADO
4. **El rollback funciona correctamente** para las rutas del conflictSet
5. **El fix debe estar en la detección de conflictos O en la validación pre-asignación**

---

## PRÓXIMOS PASOS

### Opción 1: Expandir el ConflictSet
Modificar `detectarConflictos()` para detectar TODAS las rutas que usan cualquier slot en el rango [start, start+fs) en CUALQUIER link del path de la nueva demanda, NO solo las que comparten todo el path.

### Opción 2: Validar en assignFs()
Antes de sobrescribir, verificar que el slot esté libre O que pertenezca a una ruta del conflictSet.

### Opción 3: Backup completo de links
En lugar de hacer backup por ruta, hacer backup de los links completos afectados por la nueva demanda.

**RECOMENDACIÓN**: Opción 1 (expandir conflictSet) es la más correcta conceptualmente.
