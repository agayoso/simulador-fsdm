# ROOT CAUSE DEFINITIVO: Bug en intentarAsignarConCoresFijos()

## RESUMEN

**BUG IDENTIFICADO**: El método `intentarAsignarConCoresFijos()` solo verifica disponibilidad de slots en el core ELEGIDO por la heurística, pero luego asigna en TODAS las fibras del grupo FSDM, sobrescribiendo slots ocupados en las otras fibras.

**UBICACIÓN**: `Defragmenter.java`, línea ~820, método `intentarAsignarConCoresFijos()`

---

## ANÁLISIS DEL CÓDIGO

### Paso 1: Verificación de Disponibilidad

```java
for (int li = 0; li < pathLinks.size(); li++) {
    Link link = pathLinks.get(li);
    int core = pathCores.get(li);  // ← pathCores contiene UN core por enlace

    // Solo verifica el core seleccionado
    if (!isBlockAvailable(link, core, start, width, maxCrosstalk, crosstalkFSList, crosstalkPerUnitLength)) {
        return null;
    }
    updateCrosstalkFSList(crosstalkFSList, core, link, crosstalkPerUnitLength, width);
}
```

**Ejemplo**: Si la heurística selecciona core 0 para todos los enlaces, el loop solo verifica disponibilidad en core 0.

### Paso 2: Expansión FSDM

```java
List<Integer> pathCoresExpandido = new ArrayList<>();

if (fibrasPorGrupo > 1 && input.getGrupos() != null && !input.getGrupos().isEmpty()) {
    int primerCore = pathCores.get(0);
    List<Integer> grupoSeleccionado = null;
    
    for (List<Integer> grupo : input.getGrupos()) {
        if (grupo.contains(primerCore)) {
            grupoSeleccionado = grupo;
            break;
        }
    }
    
    if (grupoSeleccionado != null) {
        // Expandir: para cada enlace, añadir TODAS las fibras del grupo
        for (int li = 0; li < pathLinks.size(); li++) {
            pathCoresExpandido.addAll(grupoSeleccionado);  // ← Añade TODOS los cores del grupo
        }
    }
}
```

**Ejemplo**: Con configuración 4F-2G (grupos [[0,1],[2,3]]):
- Si `pathCores = [0, 0, 0, 0, 0, 0]` (core 0 para 6 enlaces)
- Entonces `pathCoresExpandido = [0,1, 0,1, 0,1, 0,1, 0,1, 0,1]`

### Paso 3: Asignación en TODOS los Cores

```java
EstablishedRoute nueva = new EstablishedRoute(
        pathLinks, start, width, demanda.getLifetime(),
        demanda.getSource(), demanda.getDestination(), pathCoresExpandido, originalFs, fibrasPorGrupo);
Utils.assignFs(graph, nueva, crosstalkPerUnitLength);  // ← Asigna en pathCoresExpandido
```

`assignFs()` itera sobre **todos los cores en `pathCoresExpandido`**, incluyendo core 1, que **NUNCA FUE VERIFICADO** en el paso 1.

---

## DEMOSTRACIÓN CON CASO REAL

### CASO 1827 (del archivo diagnostico_sobrescrituras2.txt)

**Configuración**:
- Demanda: 19->7
- Start: 315, width: 3 (fs315-317)
- Grupos: [[0,1],[2,3]]

**Path de la demanda**: Incluye enlaces 7-9 y 9-12

**Secuencia de Operaciones**:

#### 1. Heurística selecciona cores
`pathCores = [2, 2, 2, 2, 2, 2]` (core 2 para todos los enlaces)

#### 2. Verificación de disponibilidad
```
Link 7-9, core 2, fs315-317: ✅ LIBRE
Link 9-12, core 2, fs315-317: ✅ LIBRE
... (otros enlaces)
```
**PASA la verificación** porque core 2 está libre.

#### 3. Expansión FSDM
```
primerCore = 2
grupoSeleccionado = [2, 3]
pathCoresExpandido = [2,3, 2,3, 2,3, 2,3, 2,3, 2,3]
```

#### 4. assignFs() asigna en TODOS los cores
```
Link 7-9, core 2, fs315-317: ASIGNA ✅
Link 7-9, core 3, fs315-317: ASIGNA ❌ ← SOBRESCRIBE (lifetime 7 → 64)
Link 9-12, core 2, fs315-317: ASIGNA ✅
Link 9-12, core 3, fs315-317: ASIGNA ❌ ← SOBRESCRIBE (lifetime 7 → 64)
```

**RESULTADO**: assignFs() sobrescribe slots ocupados en core 3 que NO fueron verificados.

---

## POR QUÉ ESTO GENERA CORRUPCIÓN

1. **PASO 4.1**: `deallocateFs(conflictSet)` libera solo las rutas detectadas como conflictivas
   - Las rutas en cores 2-3 con fs315-317 NO fueron detectadas porque solo se verificó core 2

2. **PASO 4.2**: `assignFs(nueva)` sobrescribe slots en cores 2 Y 3
   - Core 2: Asignación correcta (slots estaban libres)
   - Core 3: **SOBRESCRITURA INCORRECTA** (slots estaban ocupados por Ruta X con lifetime=7)

3. **PASO 4.3**: Falla reinserción → ROLLBACK

4. **ROLLBACK**: 
   - `deallocateFs(nueva)` libera slots en cores 2 Y 3 → fs315-317 quedan libres
   - `restoreSingleRoute(conflictSet)` restaura solo rutas del conflictSet
   - Ruta X NO está en conflictSet → **NO se restaura**
   - **Corrupción permanente**: fs315-317 en core 3 quedan libres cuando deberían estar ocupados

---

## EVIDENCIA EMPÍRICA

### Alertas de assignFs() (CASO 1827)
```
⚠️ ALERTA ASSIGNFS: Sobrescribiendo slot ocupado
  Ruta que intenta asignar: 19->7 | lifetime: 64
  Link: 7-9 | Core: 3 | FS: 315
  Estado ANTERIOR: free=ocupado, lifetime=7
  Estado que se va a sobrescribir: free=ocupado -> libre (INCORRECTO), lifetime=7 -> 64
```

### Recursos Corruptos Finales
```
7-9/core3/fs315 | free: ocupado → libre
7-9/core3/fs315 | lifetime: 7 → 0
7-9/core3/fs316 | free: ocupado → libre
...
9-12/core3/fs315-317 | todos liberados
```

---

## CONCLUSIÓN

**El bug NO está en el rollback. El bug está en `intentarAsignarConCoresFijos()`**, que:
1. Solo verifica disponibilidad en el core seleccionado por la heurística
2. Expande a TODOS los cores del grupo FSDM sin verificar disponibilidad en ellos
3. Llama a `assignFs()` que sobrescribe ciegamente todos los cores expandidos

**IMPACTO**: En configuraciones FSDM (4F-2G), cada sobrescritura afecta 2x cores (todo el grupo), magnificando el problema.

---

## SOLUCIÓN PROPUESTA

### Opción 1: Verificar TODOS los cores del grupo (RECOMENDADA)

Modificar `intentarAsignarConCoresFijos()` para:
1. Determinar el grupo ANTES del loop de verificación
2. Para cada enlace, verificar disponibilidad en TODOS los cores del grupo

```java
// Determinar grupo basado en el primer core
int primerCore = pathCores.get(0);
List<Integer> grupoSeleccionado = determinarGrupo(primerCore, input);

if (grupoSeleccionado == null && fibrasPorGrupo > 1) {
    return null; // No se puede asignar sin grupo válido
}

// Verificar disponibilidad en TODOS los cores del grupo para cada enlace
for (int li = 0; li < pathLinks.size(); li++) {
    Link link = pathLinks.get(li);
    
    if (fibrasPorGrupo > 1 && grupoSeleccionado != null) {
        // FSDM: verificar TODOS los cores del grupo
        for (Integer coreDelGrupo : grupoSeleccionado) {
            if (!isBlockAvailable(link, coreDelGrupo, start, width, maxCrosstalk, crosstalkFSList, crosstalkPerUnitLength)) {
                return null;
            }
            updateCrosstalkFSList(crosstalkFSList, coreDelGrupo, link, crosstalkPerUnitLength, width);
        }
    } else {
        // SDM: verificar solo el core seleccionado
        int core = pathCores.get(li);
        if (!isBlockAvailable(link, core, start, width, maxCrosstalk, crosstalkFSList, crosstalkPerUnitLength)) {
            return null;
        }
        updateCrosstalkFSList(crosstalkFSList, core, link, crosstalkPerUnitLength, width);
    }
}
```

### Opción 2: Modificar evaluarVentanaMinSuma()

Cambiar la lógica para seleccionar el grupo completo con menos conflictos, no el core individual:
```java
// Para FSDM: evaluar grupos completos en lugar de cores individuales
Set<EstablishedRoute> conflictosGrupo = conflictosEnLinkGrupoVentana(link, grupo, start, width, establishedRoutes);
```

### Opción 3: Modificar assignFs() para validar antes de sobrescribir

Agregar verificación en `assignFs()`:
```java
if (wasOccupied) {
    throw new IllegalStateException("Intentando asignar sobre slot ocupado que no fue liberado");
}
```

**RECOMENDACIÓN**: Implementar Opción 1, es la más directa y corrige el problema en su origen.
