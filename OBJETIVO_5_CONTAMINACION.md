# RESPUESTA: ¿Las 108 violaciones contaminan la simulación?

## OBJETIVO 5: Determinar si las violaciones contaminan resultados

### Respuesta corta
**SÍ, las 108 violaciones CONTAMINAN la simulación.**

### Evidencia

#### Estado después del rollback del caso 230:
```
establishedRoutes contiene:
  - Ruta 21->0 [ID:41910dd1]
  - Ruta 7->14 [ID:37d6c698]
  - ... otras rutas ...

Grafo contiene:
  - Link 10-14 core 2 fs 230: isFree=TRUE, lifetime=0
  - Link 10-14 core 2 fs 231: isFree=TRUE, lifetime=0
  - Link 10-14 core 2 fs 232: isFree=TRUE, lifetime=0
  - (y todas las demás violaciones similares)

Backup esperaba:
  - Link 10-14 core 2 fs 230: isFree=FALSE, lifetime=579
  - Link 10-14 core 2 fs 231: isFree=FALSE, lifetime=579
  - Link 10-14 core 2 fs 232: isFree=FALSE, lifetime=579
```

### Tipo de contaminación

**A) Slots fantasma libres**: Slots que DEBERÍAN estar ocupados pero están marcados como libres (isFree=true)

**B) Rutas huérfanas**: Rutas en establishedRoutes sin recursos realmente asignados en el grafo

**C) Inconsistencia ruta-recurso**: Las rutas dicen que usan fs:230-232 pero los slots están libres

### Consecuencias para la simulación

#### 1. Asignaciones dobles
Un algoritmo RSA puede asignar una nueva demanda usando fs:230-232 en link 10-14 porque ve isFree=true.

Esto crea:
- Dos rutas que creen usar los mismos slots
- Una en establishedRoutes desde antes del rollback
- Otra nueva asignada después del rollback

#### 2. Crosstalk incorrecto
La ruta 7->14 está en establishedRoutes y cree usar fs:230-232, pero:
- Los slots están libres (isFree=true)
- No se calcula crosstalk para una nueva ruta asignada en esos slots
- El crosstalk real es mayor al calculado

#### 3. Bloqueos artificiales
La ruta 7->14 ocupa una entrada en establishedRoutes pero no usa recursos:
- Los algoritmos ven el link "ocupado" en establishedRoutes
- Pero los slots están libres en el grafo
- Puede causar que algoritmos heurísticos eviten rutas válidas

#### 4. Deallocación con lifetime vencido
Cuando el lifetime=579 de la ruta 7->14 vence (según su entrada en establishedRoutes):
```java
// SimulatorTest.java línea 207
Utils.deallocateFs(g, demandToDelete, Input.getCrosstalkPerUnitLength());
establishedRoutes.remove(demandToDelete);
```

Intenta desasignar slots que YA están libres (isFree=true):
- Utils.deallocateFs() pone isFree=true → no cambia nada (ya es true)
- Pero reduce el crosstalk de slots adyacentes (incorrecto)
- Si otra ruta fue asignada en esos slots, se liberan slots de esa ruta por error

### Evidencia en los resultados experimentales

**Antes del fix bidireccional**: ~0% success rate
- Las violaciones eran diferentes (relacionadas con direcciones inversas)
- Contaminación masiva del estado

**Después del fix bidireccional**: 15-55% success rate
- Fix redujo violaciones drásticamente
- Pero quedan 108 violaciones (17 eventos FSDM)
- Success rate aún está lejos del óptimo (~70-80% esperado)

**Los 163-270 bloqueos restantes** (dependiendo del algoritmo) pueden explicarse parcialmente por:
1. Slots fantasma que causan asignaciones dobles
2. Crosstalk incorrecto que hace fallar validaciones
3. Rutas huérfanas que ocupan espacio en establishedRoutes
4. Deallocation incorrecta cuando lifetimes vencen

### Magnitud del problema

**108 violaciones distribuidas en:**
- 17 eventos FSDM distintos
- Multiple links afectados: 5-10, 6-8, 9-13, 10-14, etc.
- FS ranges desde width=2 hasta width=90
- Cores siempre del grupo [2,3]

**Impacto estimado:**
- Cada evento puede causar 1-10 bloqueos adicionales en demandas futuras
- Crosstalk incorrecto puede propagar el error a enlaces adyacentes
- Las 108 violaciones podrían explicar 50-150 de los 163-270 bloqueos restantes

### Conclusión

**Las 108 violaciones NO son solo un problema de limpieza del rollback.**

Son una **CONTAMINACIÓN CRÍTICA** del estado de la simulación que:
1. Deja slots libres cuando deberían estar ocupados
2. Mantiene rutas en establishedRoutes sin recursos asignados
3. Causa asignaciones dobles, crosstalk incorrecto, y bloqueos artificiales
4. Puede explicar una parte significativa de los 163-270 bloqueos restantes

**El rollback NO está funcionando correctamente** debido al orden de operaciones cuando hay rutas reinsertadas que usan links bidireccionales compartidos.
