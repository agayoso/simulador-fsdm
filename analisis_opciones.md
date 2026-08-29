# Análisis de Opciones para Fix de Paths Corruptos

## Problema Identificado

**Causa raíz:** `SimpleWeightedGraph` es NO DIRIGIDO, pero `Link` tiene campos `from/to` con orientación fija.

Cuando `KShortestSimplePaths` encuentra un camino que usa un enlace en dirección inversa a su orientación canónica:
- El grafo devuelve el **mismo objeto Link** (porque es no dirigido)
- Ese Link tiene `from/to` en la dirección canónica original
- `pathToString()` serializa usando `link.getFrom()` y `link.getTo()`
- **Resultado:** paths como `15-11-8-5-0-5` cuando el camino real es `20→15→11→8→5→0`

## Diseño Actual Confirmado

```java
// Utils.java líneas 59-79
Graph<Integer, Link> g = new SimpleWeightedGraph<>(Link.class);
...
Link link = new Link(distance, cores, vertex, connection);
g.addEdge(vertex, connection, link);  // Un solo Link físico
```

```java
// Utils.java líneas 223-224, 273-274
graph.getEdge(link.getTo(), link.getFrom())  // Mismo Link en dirección inversa
```

**Arquitectura:**
- Un solo objeto `Link` = un cable físico bidireccional
- `cores` y `FrequencySlots` = recursos físicos del cable
- `graph.getEdge(A,B)` == `graph.getEdge(B,A)` (mismo objeto)
- assignFs/deallocateFs actualizan crosstalk en ambas direcciones usando el mismo Link

---

## OPCIÓN A: Mantener SimpleWeightedGraph + Normalizar Links en EstablishedRoute

### Concepto
Mantener grafo no dirigido con Links físicos, pero crear **"Link wrappers"** normalizados en `EstablishedRoute.path` que tengan `from/to` correctos.

### Implementación
```java
// En Algorithms.java después de ksp.getEdgeList()
List<Link> normalizedPath = new ArrayList<>();
List<Integer> vertexList = ksp.getVertexList();

for (int i = 0; i < ksp.getEdgeList().size(); i++) {
    Link physicalLink = ksp.getEdgeList().get(i);
    int pathFrom = vertexList.get(i);
    int pathTo = vertexList.get(i + 1);
    
    if (physicalLink.getFrom() == pathFrom && physicalLink.getTo() == pathTo) {
        // Dirección correcta
        normalizedPath.add(physicalLink);
    } else {
        // Dirección inversa: crear wrapper
        Link normalizedLink = new Link(
            physicalLink.getDistance(),
            physicalLink.getCores(),  // ¡REFERENCIA al mismo objeto!
            pathTo,   // Swap
            pathFrom
        );
        normalizedPath.add(normalizedLink);
    }
}
```

### Análisis de Impacto

**1. Cores y FrequencySlot:**
- ✅ **COMPARTE** la misma lista de cores (referencia)
- ✅ No duplica recursos físicos
- ✅ assignFs/deallocateFs modifican el objeto correcto

**2. assignFs() y deallocateFs():**
```java
// PROBLEMA: Esta línea ya no funciona
graph.getEdge(link.getTo(), link.getFrom())
```
- ❌ `link` es el wrapper normalizado, no está en el grafo
- ❌ Necesitaría modificar assignFs/deallocateFs para mapear back al Link físico
- ❌ O almacenar referencia al Link físico en el wrapper

**3. restoreSingleRoute() y copyRoute():**
```java
// copyRoute() copia cada Link
for (Link link : route.getPath()) {
    Link copiedLink = new Link(..., link.getFrom(), link.getTo());
}
```
- ✅ Funciona, copia el Link normalizado
- ✅ from/to quedan correctos

**4. Crosstalk:**
- ❌ Código actual: `graph.getEdge(link.getTo(), link.getFrom())`
- ❌ Con wrapper, esa llamada falla (el wrapper no está en el grafo)
- ❌ Necesita reescribir toda la lógica de crosstalk

**5. Recursos compartidos/duplicados:**
- ✅ Cores compartidos (referencia)
- ⚠️ Pero crear nuevo objeto Link para cada dirección es confuso
- ⚠️ Dos objetos representan el mismo cable físico

**6. Compatibilidad con graph.getEdge():**
- ❌ **ROMPE COMPLETAMENTE**
- ❌ Todas las llamadas a `graph.getEdge()` con Links normalizados fallan
- ❌ Necesita reescribir 100+ líneas de código

**7. Riesgo y complejidad:**
- 🔴 **ALTO RIESGO**
- Necesita modificar assignFs, deallocateFs, crosstalk, validaciones
- Introduce ambigüedad: dos objetos Link representan un cable
- Fácil introducir bugs de referencia

---

## OPCIÓN B: Cambiar a DirectedWeightedMultigraph + Enlaces Bidireccionales

### Concepto
Usar grafo dirigido con dos aristas por conexión física, pero **compartiendo los mismos cores**.

### Implementación
```java
// Utils.java líneas 59-85
Graph<Integer, Link> g = new DirectedWeightedMultigraph<>(Link.class);

// Crear cores UNA VEZ (recurso físico)
List<Core> sharedCores = new ArrayList<>();
for (int j = 0; j < numberOfCores; j++) {
    Core core = new Core(fsWidth, capacity);
    sharedCores.add(core);
}

// Crear dos Links que COMPARTEN los cores
Link linkForward = new Link(distance, sharedCores, vertex, connection);
Link linkBackward = new Link(distance, sharedCores, connection, vertex);

g.addEdge(vertex, connection, linkForward);
g.addEdge(connection, vertex, linkBackward);
```

### Análisis de Impacto

**1. Cores y FrequencySlot:**
- ✅ **COMPARTE** la misma lista de cores (referencia)
- ✅ No duplica recursos físicos
- ✅ linkForward.getCores() == linkBackward.getCores() (mismo objeto)

**2. assignFs() y deallocateFs():**
```java
// Esta línea sigue funcionando
graph.getEdge(link.getTo(), link.getFrom())
```
- ✅ Devuelve el Link en dirección inversa
- ✅ Que comparte los mismos cores
- ✅ **SIN CAMBIOS EN EL CÓDIGO**

**3. restoreSingleRoute() y copyRoute():**
```java
// copyRoute() copia normalmente
Link copiedLink = new Link(link.getDistance(), copiedCores, link.getFrom(), link.getTo());
```
- ✅ Funciona sin cambios
- ✅ from/to ya correctos (KSP devuelve Link con orientación correcta)

**4. Crosstalk:**
```java
graph.getEdge(link.getTo(), link.getFrom())
```
- ✅ Funciona sin cambios
- ✅ Devuelve Link inverso que comparte cores
- ✅ Actualiza el mismo objeto físico

**5. Recursos compartidos/duplicados:**
- ✅ Cores compartidos explícitamente
- ✅ Dos Links direccionales = modelo correcto de cable bidireccional
- ✅ Semánticamente claro

**6. Compatibilidad con graph.getEdge():**
- ✅ **100% COMPATIBLE**
- ✅ No requiere cambios en código existente
- ✅ KShortestSimplePaths funciona con grafos dirigidos

**7. Riesgo y complejidad:**
- 🟢 **BAJO RIESGO**
- Cambio quirúrgico en createTopology()
- Todo el resto del código funciona sin cambios
- Modelo semánticamente correcto

---

## Validación con Caso Concreto: 20→0

### Situación Actual (INCORRECTA)
```
Path encontrado por KSP: 20 → 15 → 11 → 8 → 5 → 0

Enlaces en el grafo (SimpleWeightedGraph no dirigido):
Link₀₅: from=0, to=5, cores=[...]
Link₅₈: from=5, to=8, cores=[...]
...

KSP devuelve: [Link₁₅₂₀, Link₁₁₁₅, Link₈₁₁, Link₅₈, Link₀₅]
Último link: from=0, to=5 (dirección canónica)

pathToString() genera:
15-11-8-5-0-5
         ↑  ↑  ↑
         │  │  └─ link.getTo() = 5
         │  └─ link.getFrom() = 0
         └─ nodo anterior = 5
```

### Con Opción A (Normalizar)
```
Crear wrapper: Link normalizado con from=5, to=0
Pero cores = referencia al Link físico original

Problema: 
- assignFs recibe link normalizado
- Intenta graph.getEdge(0, 5) → encuentra Link físico
- Pero link.getCores() == linkFísico.getCores() (misma referencia)
- ¿Funciona? Técnicamente sí, pero requiere mapeo complejo
```

### Con Opción B (Dirigido)
```
Grafo dirigido con:
Link₀→₅: from=0, to=5, cores=[Core₀, Core₁, ...]
Link₅→₀: from=5, to=0, cores=[Core₀, Core₁, ...]  # Mismos objetos Core
              ↑           ↑
              └───────────┴─ MISMA REFERENCIA

KSP devuelve: [Link₂₀→₁₅, Link₁₅→₁₁, Link₁₁→₈, Link₈→₅, Link₅→₀]
Último link: from=5, to=0 ✅

pathToString() genera:
20-15-11-8-5-0  ✅ CORRECTO

assignFs con Link₅→₀:
- Modifica cores=[Core₀, Core₁, ...]
- graph.getEdge(0, 5) → Link₀→₅
- Que comparte cores=[Core₀, Core₁, ...]  # Mismo objeto
- ✅ Actualiza el mismo recurso físico
```

---

## RECOMENDACIÓN: OPCIÓN B

**Motivos:**

1. **Cambio mínimo:** Solo modificar `createTopology()` en Utils.java (20 líneas)
2. **Sin romper código existente:** assignFs, deallocateFs, crosstalk, validaciones funcionan sin cambios
3. **Modelo correcto:** Dos Links direccionales representan cable bidireccional
4. **Recursos compartidos correctamente:** cores son referencia compartida
5. **Riesgo mínimo:** Cambio quirúrgico en un solo método
6. **Compatible con JGraphT:** DirectedWeightedMultigraph soporta múltiples aristas dirigidas

**Cambio concreto:**

Archivo: `src/main/java/py/una/pol/simulador/eon/utils/Utils.java`
Método: `createTopology()`
Líneas: 59 + 68-79

```java
// Línea 59: Cambiar tipo de grafo
Graph<Integer, Link> g = new DirectedWeightedMultigraph<>(Link.class);

// Líneas 68-79: Crear enlaces bidireccionales con cores compartidos
for (int i = 0; i < node.get("connections").size(); i++) {
    int connection = node.get("connections").get(i).intValue();
    int distance = node.get("distance").get(i).intValue();
    
    // Crear cores UNA VEZ (recurso físico compartido)
    List<Core> sharedCores = new ArrayList<>();
    for (int j = 0; j < numberOfCores; j++) {
        Core core = new Core(fsWidth, capacity);
        sharedCores.add(core);
    }

    // Crear dos Links que COMPARTEN los mismos cores
    Link linkForward = new Link(distance, sharedCores, vertex, connection);
    Link linkBackward = new Link(distance, sharedCores, connection, vertex);
    
    g.addEdge(vertex, connection, linkForward);
    g.addEdge(connection, vertex, linkBackward);
    g.setEdgeWeight(linkForward, distance);
    g.setEdgeWeight(linkBackward, distance);
}
```

**Importación adicional:**
```java
import org.jgrapht.graph.DirectedWeightedMultigraph;
```

✅ Todo el resto del código funciona sin modificación.
