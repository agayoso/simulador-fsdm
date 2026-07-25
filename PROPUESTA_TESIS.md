# Análisis de Dependencia Contextual en Estrategias de Desfragmentación Reactiva para Redes Ópticas Elásticas
o tambien puede ser:
# Evaluación Experimental de la Robustez de Estrategias de Desfragmentación Reactiva en Redes Ópticas Elásticas bajo Variaciones de Crosstalk y Topología

**Autor:** Andrés Gayoso
**Tutor:** Enrique Davalos
**Fecha:** Julio 2026
**Trabajo basado en:** Tesis previa de Rafael Ricardo sobre desfragmentación reactiva en EON

---

## 1. Antecedentes

El trabajo previo de Rafael Ricardo comparó tres estrategias de desfragmentación reactiva para Redes Ópticas Elásticas (EON):

- **DFbFRmax:** Selección de núcleos con máxima fragmentación (BFR)
- **DFbFRmin:** Selección de núcleos con mínima fragmentación (BFR)
- **DFfullRuteoMin:** Minimización de conflictos por enlace mediante re-ruteo completo

Su investigación concluyó que DFfullRuteoMin presenta el mejor rendimiento en términos de reducción de bloqueos.

Sin embargo, dicho estudio evaluó las estrategias en un conjunto limitado de condiciones operacionales, sin considerar variaciones en:
- Niveles de crosstalk (acoplamiento entre núcleos)
- Diversidad de arquitecturas topológicas
- Métricas de costo operacional (rutas reconfiguradas, tasa de éxito)

---

## 2. Objetivo

**El objetivo de esta investigación no es determinar cuál heurística es la mejor, sino establecer bajo qué condiciones cada una resulta más conveniente.**

Extender la evaluación experimental incorporando variaciones en las condiciones físicas (crosstalk) y topológicas de la red, con el fin de caracterizar la dependencia contextual de las estrategias de desfragmentación y establecer criterios de selección para escenarios operacionales diversos.

---

## 3. Hipótesis

**H1:** El desempeño de las heurísticas de desfragmentación depende significativamente del nivel de crosstalk presente en la red.

**H2:** Diferentes arquitecturas topológicas producen variaciones sustanciales en la efectividad de las estrategias de desfragmentación.

**H3:** No existe una única estrategia dominante para todos los escenarios; la selección óptima depende del contexto operacional.

---

### Diagrama Conceptual de la Investigación

```
            Condiciones de la Red

                 Crosstalk (H)
                      │
                      │
                 Topología
                      │
                      ▼

        Comportamiento de la Heurística

                      │
          ┌───────────┼────────────┐
          ▼           ▼            ▼

     Bloqueos     Tiempo      Rutas Movidas

                      │
                      ▼

       Selección de Estrategia
```

**Este diagrama resume la premisa central de la investigación:** Las condiciones físicas (crosstalk) y estructurales (topología) de la red determinan el comportamiento observable de cada heurística (bloqueos, tiempo, rutas reconfiguradas), lo que a su vez permite establecer criterios de selección contextualizada en lugar de asumir una estrategia óptima universal.

---

## 4. Descripción de las Estrategias Evaluadas

Esta sección describe el funcionamiento de cada heurística y su significado operacional en redes reales.

### 4.1 DFbFRmax - Selección de Máxima Fragmentación

**Principio de funcionamiento:**

DFbFRmax selecciona, para cada enlace de la ruta, el núcleo con mayor nivel de fragmentación (BFR - Bandwidth Fragmentation Ratio). El BFR mide el grado de dispersión del espectro disponible:

$$\text{BFR} = 1 - \frac{\text{Bloque contiguo más grande}}{\text{Total de slots libres}}$$

La hipótesis detrás de esta estrategia es que concentrar las operaciones de desfragmentación sobre los núcleos más fragmentados permitirá recuperar bloques continuos de espectro sin afectar los núcleos con mejor organización espectral.

**¿Qué representa en una red real?**

Equivale a intervenir únicamente las zonas más degradadas de la red. En lugar de reorganizar completamente el espectro, intenta aprovechar aquellos núcleos donde la fragmentación ya es elevada para realizar las reconfiguraciones.

En términos operativos, representa una **estrategia conservadora**: evita modificar regiones de la red que todavía presentan buena disponibilidad espectral, minimizando el riesgo de generar nuevas interferencias.

**Costo operacional:** Moderado a alto (mueve múltiples rutas por los núcleos ya congestionados).

### 4.2 DFbFRmin - Selección de Mínima Fragmentación

**Principio de funcionamiento:**

DFbFRmin selecciona el núcleo **menos fragmentado** de cada enlace. La idea consiste en preservar los espacios espectrales más organizados para realizar las reconfiguraciones.

**¿Qué significa físicamente?**

Supone que mover conexiones hacia núcleos poco fragmentados permitirá generar caminos ópticos con menor probabilidad de bloqueo futuro. Es una estrategia que prioriza **mantener el orden** del espectro, utilizando las regiones mejor organizadas como "zona de trabajo" para las reconfiguraciones.

**Hipótesis implícita:** Los núcleos con baja fragmentación tienen mayor capacidad residual para acomodar rutas adicionales sin exceder umbrales de crosstalk.

**Costo operacional:** Moderado (requiere menos rutas reconfiguradas que DFbFRmax).

### 4.3 DFfullRuteoMin - Minimización Global de Conflictos

**Principio de funcionamiento:**

DFfullRuteoMin evalúa **todas las combinaciones posibles de núcleos** para cada enlace de la nueva conexión. Para cada ventana candidata, calcula el número total de rutas establecidas que entrarían en conflicto (por violación de restricciones de espectro o crosstalk).

Posteriormente selecciona aquella configuración que **minimiza el número total de rutas conflictivas** que deberán ser reconfiguradas.

**¿Qué significa en una red real?**

Representa una **estrategia de optimización global**. Antes de decidir dónde ubicar una conexión, analiza múltiples alternativas buscando aquella que produzca la **menor cantidad de interrupciones** sobre conexiones ya establecidas.

Su costo computacional es mayor (debe evaluar 7^L combinaciones, donde L es el número de enlaces), pero busca minimizar el impacto total sobre la red operativa.

**Característica distintiva:** Tiende a mover exactamente las rutas necesarias (eficiencia en rutas/desfragmentación cercana a 1.0).

**Costo operacional:** Bajo en rutas reconfiguradas, alto en tiempo de cómputo.

---

## 5. Metodología

### 5.1 Plataforma de Simulación

Se utilizó el simulador desarrollado por Rafael Ricardo, implementado en Java con la biblioteca JGraphT, incorporando las siguientes mejoras metodológicas:

**Nuevas métricas implementadas:**
- Tasa de éxito de desfragmentación (%)
- Número de intentos totales de desfragmentación
- Rutas reconfiguradas por desfragmentación exitosa
- Tiempo de ejecución por estrategia

**Parámetros de red:**
- Núcleos por fibra: 7
- Slots de frecuencia por núcleo: 320
- Ancho de slot: 12.5 GHz
- Umbral máximo de crosstalk: -25 dB

### 5.2 Diseño Experimental

Se diseñó una **matriz de experimentos bidimensional** que combina:

**Dimensión 1 - Niveles de crosstalk (H):**
- **H DÉBIL** (0.0000316): Condiciones ideales de laboratorio
- **H MEDIO** (0.00040): Condiciones realistas de despliegue
- **H FUERTE** (0.0035): Condiciones extremas (no evaluado por tiempo limitado)

**Justificación de los valores seleccionados:** Los valores de crosstalk fueron seleccionados para representar distintos niveles de acoplamiento reportados en la literatura para fibras multinúcleo, permitiendo evaluar la sensibilidad de las estrategias frente al incremento progresivo del crosstalk. H DÉBIL representa fibras con bajo acoplamiento (separación adecuada entre núcleos, instalaciones subterráneas), mientras H MEDIO representa condiciones más realistas de despliegue (fibras aéreas, variaciones térmicas, envejecimiento de la infraestructura).

**Dimensión 2 - Topologías:**
- **USNET:** 24 nodos, red de área amplia de EE.UU.
- **NSFNET:** 14 nodos, red nacional de ciencia
- **JPNNET:** 17 nodos, red nacional de Japón

**Carga de red:**
- Erlang: 2500 (demandas generadas: ~10,000)

**Estrategias evaluadas:**
- Baseline (sin desfragmentación)
- DFbFRmax con profundidad 1 y 3
- DFbFRmin con profundidad 1 y 3
- DFfullRuteoMin con profundidad 1 y 3

**Justificación de las profundidades seleccionadas:**
- **Profundidad 1:** Representa el caso base (greedy) sin backtracking. Cada estrategia intenta la primera ventana candidata generada sin explorar alternativas.
- **Profundidad 3:** Representa el caso con backtracking limitado. El algoritmo puede retroceder hasta 2 pasos si una ventana candidata falla, buscando la siguiente mejor opción.
- **No se evaluó profundidad 2** porque no aporta un caso científicamente diferenciado. El salto de P=1 (sin backtracking) a P=3 (con backtracking) captura el comportamiento clave. Valores intermedios no cambian el fenómeno estudiado.
- Esta elección es **consistente con la tesis de Rafael Ricardo**, permitiendo comparabilidad directa de resultados.

---

## 6. Resultados Experimentales

### 6.1 Experimento 1: Variación de Crosstalk (USNET, Erlang 2500)

#### Configuración H DÉBIL (condiciones ideales)

| Estrategia | Bloqueos | Reducción | Tasa Éxito | Rutas/Defrag | Tiempo (s) |
|------------|----------|-----------|------------|--------------|------------|
| Sin defrag | 111 | - | - | - | - |
| DFbFRmax p1 | 50 | 55.0% | 59.0% | 3.55 | 40 |
| DFbFRmax p3 | 25 | 77.5% | 82.6% | 3.12 | 53 |
| DFbFRmin p1 | 52 | 53.2% | 55.6% | 2.47 | 39 |
| DFbFRmin p3 | 22 | 80.2% | 86.8% | 2.13 | 54 |
| **DFfullRuteoMin p1** | 23 | 79.3% | 83.1% | 1.68 | 86 |
| **DFfullRuteoMin p3** | **11** | **90.1%** | **91.1%** | **1.54** | 103 |

**Observación:** DFfullRuteoMin p3 logra la mayor reducción (90.1%) con el menor número de rutas reconfiguradas (1.54 promedio), confirmando los resultados del trabajo previo. **Esto significa que de 111 solicitudes inicialmente bloqueadas, únicamente 11 permanecieron bloqueadas luego de aplicar la estrategia**, moviendo en promedio solo 1.5 rutas por cada desfragmentación exitosa.

#### Configuración H MEDIO (condiciones realistas)

| Estrategia | Bloqueos | Reducción | Tasa Éxito | Rutas/Defrag | Tiempo (s) |
|------------|----------|-----------|------------|--------------|------------|
| Sin defrag | 120 | - | - | - | - |
| DFbFRmax p1 | 87 | 27.5% | 32.7% | 3.88 | 50 |
| DFbFRmax p3 | 63 | 47.5% | 53.7% | 3.45 | 64 |
| **DFbFRmin p1** | **120** | **0.0%** | **0.0%** | - | 48 |
| **DFbFRmin p3** | **120** | **0.0%** | **0.0%** | - | 64 |
| **DFfullRuteoMin p1** | **120** | **0.0%** | **0.0%** | - | 111 |
| **DFfullRuteoMin p3** | **120** | **0.0%** | **0.0%** | - | 144 |

**Hallazgo crítico:** Con niveles de crosstalk realistas (H = 0.00040), las estrategias DFbFRmin y DFfullRuteoMin presentan una **pérdida completa de robustez** (0% de éxito). Solo DFbFRmax mantiene funcionalidad, aunque con rendimiento reducido (47.5%). **En condiciones de crosstalk medio, DFbFRmax logró recuperar aproximadamente una de cada dos conexiones que originalmente hubieran sido rechazadas** (63 de 120 bloqueos recuperados).

#### Interpretación de Ingeniería - Experimento 1

**¿Qué significa que DFfullRuteoMin pase de 90% a 0%?**

Este resultado NO indica que el algoritmo esté mal diseñado. Significa que **el entorno físico cambió**, no el algoritmo.

Cuando aumenta el valor de H, aumenta el coeficiente de acoplamiento entre núcleos. Como consecuencia, también aumenta el crosstalk inter-núcleo. El algoritmo DFfullRuteoMin continúa buscando la mejor solución posible (minimizar conflictos), pero las **restricciones físicas impiden concretar las reconfiguraciones**.

En términos concretos:
- El algoritmo **encuentra** soluciones válidas desde el punto de vista algorítmico
- Pero la fibra óptica **ya no puede soportarlas** sin exceder -25 dB de crosstalk acumulado
- Resultado: todas las ventanas candidatas son rechazadas

**¿Por qué DFbFRmax sobrevive?**

DFbFRmax, al seleccionar núcleos ya fragmentados, tiende a generar configuraciones con **menor interferencia cruzada**. Los núcleos fragmentados típicamente tienen menos rutas activas densamente empaquetadas, lo que reduce el crosstalk acumulado por reconfiguración.

Además, al ser una estrategia conservadora, DFbFRmax no intenta optimización global sino **recuperación local**, lo que genera ventanas candidatas menos agresivas desde el punto de vista del crosstalk.

**¿Qué aprendería un operador de red?**

Si una red presenta elevados niveles de crosstalk (H > 0.0003):
1. Implementar DFfullRuteoMin probablemente implique un **elevado costo computacional sin obtener beneficios** en reducción de bloqueos
2. Resulta más conveniente utilizar **DFbFRmax**, ya que mantiene capacidad de recuperación aun cuando las restricciones físicas son severas
3. La caracterización del nivel de crosstalk de la infraestructura es **crítica antes de seleccionar la estrategia** de desfragmentación

**Implicación práctica:** En redes de área metropolitana con fibras antiguas o rutas aéreas (mayor acoplamiento térmico), DFbFRmax es la opción segura.

### 6.2 Experimento 2: Variación de Topología (H DÉBIL, Erlang 2500)

#### USNET (24 nodos)

| Estrategia | Bloqueos | Reducción | Tasa Éxito | Rutas/Defrag | Tiempo (s) |
|------------|----------|-----------|------------|--------------|------------|
#### Interpretación de Ingeniería - Experimento 2

**¿Por qué importa que JPNNET tenga peor rendimiento?**

No porque tenga 17 nodos (tamaño intermedio), sino porque demuestra que **la estructura de la red modifica completamente el comportamiento del algoritmo**.

Dos operadores pueden tener redes del mismo tamaño. Sin embargo:
- Uno tendrá **múltiples caminos alternativos** (alta conectividad como USNET)
- Otro tendrá **pocos caminos redundantes** (topología tipo árbol)
- Otro tendrá **cuellos de botella** críticos (enlaces con alta carga como JPNNET)

Por esta razón, seleccionar una estrategia únicamente considerando el tamaño de la red puede conducir a **decisiones equivocadas**.

**¿Qué características topológicas hacen que JPNNET sea problemática?**

Aunque no se analizaron métricas de grafos en esta investigación, el alto número de bloqueos base (1084 vs. 83-111) sugiere:
1. **Baja k-conectividad:** Pocas rutas alternativas entre pares de nodos
2. **Enlaces críticos:** Algunos enlaces concentran gran parte del tráfico (cuellos de botella)
3. **Mayor distancia promedio:** Rutas más largas consumen más espectro por demanda

Estos factores limitan las opciones de desfragmentación: si no hay rutas alternativas viables, ningún algoritmo puede recuperar espectro efectivamente.

**¿Qué aprendería un operador de red?**

Antes del despliegue de estrategias de desfragmentación resulta conveniente:
1. **Caracterizar la topología** mediante métricas de grafos (grado promedio, diámetro, clustering)
2. *7Identificar cuellos de botella** que limiten las opciones de re-ruteo
3. **Evaluar el número de caminos K-disjuntos** promedio entre pares de nodos

Si la topología presenta baja conectividad (como JPNNET), incluso las mejores heurísticas tendrán efectividad limitada. En esos casos, puede ser más efectivo **invertir en actualización física** (agregar enlaces) que en algoritmos sofisticados de desfragmentación.

**Implicación práctica:** Redes con topología tipo backbone nacional (pocas rutas alternativas) deben priorizar diseño físico sobre optimización algorítmica.

---

## 7
#### NSFNET (14 nodos)

| Estrategia | Bloqueos | Reducción | Tasa Éxito | Rutas/Defrag | Tiempo (s) |
|------------|----------|-----------|------------|--------------|------------|
| Sin defrag | 83 | - | - | - | - |
| DFfullRuteoMin p3 | 12 | 85.5% | 86.5% | **1.00** | 32 |
7
**Observación:** NSFNET muestra mayor eficiencia operacional: solo 1 ruta reconfigurada por desfragmentación exitosa, y tiempo de ejecución 3.2× menor que USNET.

#### JPNNET (17 nodos)

| Estrategia | Bloqueos | Reducción | Tasa Éxito | Rutas/Defrag | Tiempo (s) |
|------------|----------|-----------|------------|--------------|------------|
| Sin defrag | **1084** | - | - | - | - |
| DFbFRmax p3 | 922 | 14.9% | 30.8% | 2.73 | 125 |
| DFbFRmin p3 | 950 | 12.4% | 28.9% | 2.18 | 128 |
| DFfullRuteoMin p3 | 941 | **13.2%** | 41.1% | 1.46 | 985 |

**Hallazgo sorprendente:** JPNNET (17 nodos, tamaño intermedio) presenta:
- 13× más bloqueos que NSFNET (14 nodos)
- 10× más bloqueos que USNET (24 nodos)
- Rendimiento de desfragmentación 6-7× inferior

**Incluso después de desfragmentar, casi nueve de cada diez conexiones originalmente bloqueadas continuaron sin poder establecerse** (941 de 1084 bloqueos persistieron con la mejor estrategia).

Esto indica que el tamaño de la red (número de nodos) **no predice** el rendimiento de desfragmentación. La arquitectura específica de conectividad es el factor dominante.

---

## 6. Discusión

### 6.1 Cambio de Régimen por Crosstalk

El resultado más significativo es el **cambio de régimen** observado al pasar de H DÉBIL a H MEDIO.

No se trata de una degradación gradual, sino de una transición abrupta:
- DFfullRuteoMin: 90.1% → **0%**
- DFbFRmin: 80.2% → **0%**

**Interpretación física:**

Con crosstalk bajo (H < 0.0001), el proceso de desfragmentación puede mover conexiones entre núcleos sin exceder el umbral de crosstalk acumulado (-25 dB).

Con crosstalk realista (H ≈ 0.0004), el acoplamiento entre núcleos es suficientemente fuerte para que cualquier reconfiguración multi-ruta genere interferencia acumulada que supera el umbral, causando el rechazo de todas las ventanas candidatas.

**Implicación práctica:**

En escenarios de despliegue real con crosstalk >0.0004:
- DFfullRuteoMin **no es viable operacionalmente**
- DFbFRmax es la única estrategia funcional, aunque con rendimiento moderado (47.5%)

Los resultados muestran que las conclusiones obtenidas bajo condiciones ideales no necesariamente se mantienen cuando cambian las condiciones físicas, ampliando el dominio experimental evaluado en el trabajo previo.
7
### 6.2 Robustez vs. Optimalidad

Los resultados revelan un trade-off fundamental:

| Estrategia | Rendimiento Pico | Robustez ante Crosstalk |
|------------|------------------|-------------------------|
| DFfullRuteoMin | ★★★★★ (90.1%) | ☆☆☆☆☆ (pierde robustez con H medio) |
| DFbFRmin | ★★★★☆ (80.2%) | ☆☆☆☆☆ (pierde robustez con H medio) |
| DFbFRmax | ★★★☆☆ (77.5%) | ★★★☆☆ (47.5% con H medio) |

**DFfullRuteoMin** maximiza el rendimiento bajo condiciones ideales pero es **frágil** ante perturbaciones físicas.
### 7.5 Síntesis: Tabla de Decisión para Operadores

La siguiente tabla consolida los resultados experimentales en una guía de decisión práctica:

| Escenario | Mejor Estrategia | ¿Por qué? | ¿Qué significa en una red real? |
|-----------|------------------|-----------|--------------------------------|
| **H DÉBIL** (< 0.0001) | DFfullRuteoMin p3 | Reduce 90% de bloqueos moviendo pocas rutas (1.54 promedio) | Conviene cuando la infraestructura física presenta bajo crosstalk (fibras nuevas, instalación subterránea, clima controlado) |
| **H MEDIO** (≈ 0.0004) | DFbFRmax p3 | Es el único algoritmo que mantiene capacidad de recuperación (47.5%) | Conviene cuando existen fuertes restricciones físicas (fibras antiguas, rutas aéreas, temperatura variable) |
| **Topología tipo NSFNET** | DFfullRuteoMin p3 | Alta eficiencia (1.0 rutas/defrag) y bajo tiempo (32s) | Redes con buena conectividad y múltiples caminos alternativos permiten optimización global |
| **Topología tipo USNET** | DFfullRuteoMin p3 | Muy buen rendimiento (90%) aunque mayor tiempo (103s) | Redes extensas con alta conectividad siguen beneficiándose de estrategias globales |
| **Topología tipo JPNNET** | Ninguna destaca | Todas reducen poco los bloqueos (13-15%) | La topología se convierte en el principal factor limitante; considerar actualización física |
| **Crosstalk desconocido** | DFbFRmax | Robustez ante incertidumbre | Cuando no hay caracterización previa de la planta física, estrategia conservadora minimiza riesgo |

**Regla general:** Si H y topología son favorables → DFfullRuteoMin. Si hay incertidumbre o restricciones → DFbFRmax.

---

## 8
Este enfoque de evaluación (rendimiento bajo perturbaciones vs. rendimiento máximo) constituye un aporte metodológico no presente en el trabajo previo.

### 6.3 Dependencia de Arquitectura Topológica

La comparación entre topologías invalida la hipótesis de que el tamaño de la red determina el rendimiento:

| Topología | Nodos | Bloqueos Base | DFfullRuteoMin p3 |
|-----------|-------|---------------|-------------------|
| NSFNET | 14 | 83 | 85.5% reducción |
| JPNNET | 17 | **1084** | 13.2% reducción |
| USNET | 24 | 111 | 90.1% reducción |

JPNNET, con tamaño intermedio, muestra el peor comportamiento. Esto sugiere que características estructurales de la topología (conectividad, cuellos de botella, distribución de grado nodal) influyen significativamente en:
- La tasa de bloqueo base
- La efectividad de la desfragmentación

**Línea de trabajo futura:** Caracterización topológica mediante métricas de grafos (grado promedio, diámetro, coeficiente de clustering, k-conectividad) para correlacionar estructura y rendimiento de desfragmentación.

### 6.4 Costo Operacional
8.1 ¿Cuál es el Aporte Científico de esta Investigación?

El presente trabajo **no propone una nueva heurística de desfragmentación**.

Su aporte consiste en **extender la validación experimental** de estrategias previamente desarrolladas, evaluando su comportamiento bajo condiciones físicas y topológicas que no habían sido consideradas en el trabajo original.

Los resultados muestran que las conclusiones obtenidas en escenarios ideales **no pueden generalizarse automáticamente** a otros contextos operacionales. En particular, se identificó:

1. Un **cambio de régimen** (transición abrupta, no degradación gradual) al variar crosstalk
2. Una **dependencia topológica no lineal** (el tamaño de red no predice rendimiento)
3. Un **trade-off robustez-optimalidad** cuantificado experimentalmente

Como consecuencia, la investigación propone incorporar **el contexto físico y topológico como criterio** para la selección de estrategias de desfragmentación en redes ópticas elásticas, en lugar de asumir una "mejor estrategia universal".

**En términos prácticos:** Esta tesis no responde *"¿cuál algoritmo es mejor?"* sino *"¿bajo qué condiciones funciona cada algoritmo?"*

Ese cambio de pregunta es la contribución metodológica central.

### 8.3 Guía Práctica de Selección (Resumida)
La métrica **rutas reconfiguradas por desfragmentación** revela un resultado interesante:

Con H DÉBIL en USNET:
- DFfullRuteoMin p3: 1.54 rutas/defrag
- DFbFRmax p3: 3.12 rutas/defrag

El algoritmo con **mejor rendimiento** también tiene **menor costo operacional**. Esto complementa la intuición de que estrategias más sofisticadas podrían requerir mayor reconfiguración, mostrando que bajo condiciones ideales, la optimización global (DFfullRuteoMin) es también la más eficiente.

En NSFNET, DFfullRuteoMin p3 alcanza el mínimo teórico: **1.00 rutas/defrag** (cada desfragmentación exitosa mueve exactamente una ruta).

---
8.4
## 7. Conclusiones

### 7.1 Principales Contribuciones

**C1. Sensibilidad al Contexto Operacional**

El rendimiento de las heurísticas de desfragmentación depende críticamente del contexto físico (crosstalk) y topológico. No existe una estrategia óptima universal; la selección debe considerar las condiciones específicas de despliegue.

**C2. Caracterización de Robustez**

Se identificó un trade-off entre rendimiento pico y robustez ante variaciones físicas. DFfullRuteoMin maximiza reducción de bloqueos bajo condiciones ideales pero pierde robustez ante crosstalk realista, mientras DFbFRmax mantiene funcionalidad aunque con rendimiento reducido.

**C3. Dominancia de Arquitectura sobre Tamaño**

El número de nodos no predice el rendimiento de desfragmentación. La arquitectura topológica específica es el factor dominante, como evidencia el comportamiento anómalo de JPNNET.

**C4. Aporte Metodológico**

Se introdujo un diseño experimental matricial (Topología × Condición Física × Profundidad) que permite caracterizar límites de validez de las heurísticas, superando la evaluación unidimensional del trabajo previo.

### 7.2 Guía Práctica de Selección

Con base en los resultados experimentales, se propone la siguiente guía para operadores de red:

| Condición Observada | Estrategia Recomendada | Fundamento |
|--9. Propuesta de Título de Tesis

**Título propuesto (RECOMENDADO):**

> **"Evaluación de la Robustez de Estrategias de Desfragmentación Reactiva en Redes Ópticas Elásticas bajo Variaciones de Crosstalk y Topología"**

**Otras opciones:**

1. "Caracterización de Robustez en Heurísticas de Desfragmentación Reactiva para EON bajo Condiciones Operacionales Diversas"

2. "Evaluación Experimental de Sensibilidad Contextual en Estrategias de Desfragmentación para Redes Ópticas Elásticas Multi-Núcleo"

3. "Límites de Validez de Estrategias de Desfragmentación Reactiva en Redes Ópticas Elásticas: Un Estudio Experimental"

---

## 10 caracterización de crosstalk es crítica antes de seleccionar estrategia de desfragmentación
- Redes con H > 0.0003 deben evitar DFfullRuteoMin/DFbFRmin
- El análisis topológico previo puede predecir efectividad de desfragmentación

**Para investigación:**
- Las evaluaciones futuras deben incluir variaciones de contexto, no solo comparación algoritmica
- La robustez debe considerarse como métrica de evaluación junto al rendimiento pico

### 7.4 Limitaciones y Trabajo Futuro

**Limitaciones del estudio actual:**
- No se evaluó H FUERTE (esperado: pérdida completa de funcionalidad)
- No se analizaron métricas topológicas para explicar comportamiento de JPNNET
- Carga fija (Erlang 2500); no se caracterizó dependencia de carga

**Lí1eas de trabajo futuras:**
1. Correlación entre métricas de grafos y rendimiento de desfragmentación
2. Desarrollo de predictor de estrategia óptima basado en caracterización topológica
3. Evaluación de variación de carga (curvas bloqueos vs. Erlang)
4. Diseño de estrategia híbrida adaptativa según nivel de crosstalk detectado

---

## 8. Modelo Conceptual Propuesto

La investigación realizada permite proponer un **modelo conceptual para la selección de estrategias de desfragmentación**.

El modelo considera dos factores principales:

1. **Restricciones físicas de la infraestructura** (nivel de crosstalk H)
2. **Características estructurales de la topología** (conectividad, cuellos de botella)

En función de dichos parámetros es posible seleccionar la estrategia con mayor probabilidad de éxito **antes de ejecutar el algoritmo**, mediante caracterización previa de la red.

**Cambio de paradigma:**

De esta manera, la desfragmentación deja de entenderse como un **problema puramente algorítmico** y pasa a depender también de las **características de la red** sobre la cual será aplicada.

**Modelo de decisión propuesto:**

```
1. Medir/estimar H de la fibra instalada
   ├─ Si H < 0.0001 → DFfullRuteoMin (optimización global)
   └─ Si H > 0.0003 → DFbFRmax (robustez)

2. Analizar topología
   ├─ Calcular k-conectividad promedio
   ├─ Identificar cuellos de botella
   └─ Si conectividad baja → Considerar actualización física

3. Seleccionar estrategia basada en matriz (Sección 7.5)
```

Este modelo permite que operadores de red tomen decisiones informadas sobre qué algoritmo implementar según las características específicas de su infraestructura, evitando la asunción de una "mejor estrategia universal".

---

## 9. Propuesta de Título de Tesis

**Título propuesto:**

> **"Evaluación de la Robustez de Estrategias de Desfragmentación Reactiva en Redes Ópticas Elásticas bajo Variaciones de Crosstalk y Topología"**

**Justificación:** El término **"robustez"** captura exactamente la pregunta científica central: *"¿Qué algoritmo sigue funcionando cuando el entorno deja de ser ideal?"* Esto diferencia claramente este trabajo (evaluación de límites de validez) del trabajo previo (búsqueda de máximo rendimiento).

**Título alternativo:**

> **"Análisis de Dependencia Contextual en Estrategias de Desfragmentación Reactiva para Redes Ópticas Elásticas: Evaluación bajo Variaciones de Crosstalk y Topología"**

---

## 9. Diferenciación con Respecto al Trabajo Previo

| Aspecto | Tesis Rafael Ricardo | Esta Propuesta |
|---------|---------------------|----------------|
| **Objetivo** | Comparación de estrategias | Caracterización de dependencia contextual |
| **Pregunta** | ¿Cuál es la mejor estrategia? | ¿Bajo qué condiciones funciona cada estrategia? |
| **Diseño** | Unidimensional (comparación directa) | Matricial (Topología × Crosstalk × Profundidad) |
| **Métricas** | Bloqueos, tiempo | + Tasa éxito, rutas reconfiguradas, costo operacional |
| **Topologías** | 1 topología | 3 topologías |
| **Crosstalk** | 1 nivel (presumiblemente débil) | 2 niveles (débil, medio) |
| **Conclusión** | DFfullRuteoMin es óptima | Optimalidad depende del contexto |
| **Aporte** | Propuesta y evaluación de DFfullRuteoMin | Límites de validez + guía de selección |

---

## 10. Cronograma de Completitud

Si se aprueba esta dirección, el trabajo restante estimado:

| Actividad | Tiempo Estimado | Descripción |
|-----------|-----------------|-------------|
| **Explicar experimentalmente el comportamiento observado en JPNNET** | 1 semana | Extraer métricas de grafos (k-conectividad, diámetro), correlacionar con rendimiento, explicar por qué 17 nodos presentan peor rendimiento que 24 |
| Redacción Introducción + Estado del Arte | 1 semana | Contextualización EON, SDM, desfragmentación |
| Redacción Metodología detallada | 3 días | Describir simulador, parámetros, diseño experimental |
| Generación de gráficos/visualizaciones | 3 días | Gráficos de barras, curvas, diagramas comparativos |
| Redacción Discusión extendida | 1 semana | Profundizar interpretación física, implicaciones |
| Redacción Conclusiones + Trabajo Futuro | 2 días | Consolidar hallazgos, líneas abiertas |
| Revisión y formato final | 3 días | Uniformidad, referencias, formato institucional |
| **TOTAL** | **≈4 semanas** | Tiempo para documento completo |

---

## Anexo: Datos Experimentales Completos

### Tabla Consolidada Experimento 1 - USNET (24 nodos, Erlang 2500)

| Crosstalk | Estrategia | Bloqueos | Reducción % | Tiempo (s) | Intentos | Éxitos | Tasa Éxito % | Rutas Totales | Rutas/Defrag |
|-----------|------------|----------|-------------|------------|----------|--------|--------------|---------------|--------------|
| H DÉBIL | Sin defrag | 111 | - | - | - | - | - | - | - |
| | DFbFRmax p1 | 50 | 55.0 | 40 | 122 | 72 | 59.0 | 256 | 3.55 |
| | DFbFRmax p3 | 25 | 77.5 | 53 | 132 | 109 | 82.6 | 340 | 3.12 |
| | DFbFRmin p1 | 52 | 53.2 | 39 | 126 | 70 | 55.6 | 173 | 2.47 |
| | DFbFRmin p3 | 22 | 80.2 | 54 | 129 | 112 | 86.8 | 239 | 2.13 |
| | DFfullRuteoMin p1 | 23 | 79.3 | 86 | 136 | 113 | 83.1 | 190 | 1.68 |
| | DFfullRuteoMin p3 | 11 | 90.1 | 103 | 145 | 132 | 91.1 | 204 | 1.54 |
| H MEDIO | Sin defrag | 120 | - | - | - | - | - | - | - |
| | DFbFRmax p1 | 87 | 27.5 | 50 | 104 | 34 | 32.7 | 132 | 3.88 |
| | DFbFRmax p3 | 63 | 47.5 | 64 | 121 | 65 | 53.7 | 224 | 3.45 |
| | DFbFRmin p1 | 120 | 0.0 | 48 | 134 | 0 | 0.0 | 0 | - |
| | DFbFRmin p3 | 120 | 0.0 | 64 | 143 | 0 | 0.0 | 0 | - |
| | DFfullRuteoMin p1 | 120 | 0.0 | 111 | 145 | 0 | 0.0 | 0 | - |
| | DFfullRuteoMin p3 | 120 | 0.0 | 144 | 159 | 0 | 0.0 | 0 | - |

### Tabla Consolidada Experimento 2 - Variación de Topología (H DÉBIL, Erlang 2500)

| Topología | Nodos | Estrategia | Bloqueos | Reducción % | Tiempo (s) | Tasa Éxito % | Rutas/Defrag |
|-----------|-------|------------|----------|-------------|------------|--------------|--------------|
| USNET | 24 | Sin defrag | 111 | - | - | - | - |
| | | DFfullRuteoMin p3 | 11 | 90.1 | 103 | 91.1 | 1.54 |
| NSFNET | 14 | Sin defrag | 83 | - | - | - | - |
| | | DFbFRmax p1 | 30 | 63.9 | 15 | 63.0 | 1.49 |
| | | DFbFRmax p3 | 27 | 67.5 | 16 | 69.7 | 1.65 |
| | | DFbFRmin p1 | 44 | 47.0 | 16 | 52.7 | 1.06 |
| | | DFbFRmin p3 | 27 | 67.5 | 16 | 73.0 | 1.22 |
| | | DFfullRuteoMin p1 | 28 | 66.3 | 31 | 67.8 | 1.00 |
| | | DFfullRuteoMin p3 | 12 | 85.5 | 33 | 86.5 | 1.00 |
| JPNNET | 17 | Sin defrag | 1084 | - | - | - | - |
| | | DFbFRmax p1 | 1016 | 6.3 | 100 | 20.6 | 2.49 |
| | | DFbFRmax p3 | 922 | 14.9 | 126 | 30.8 | 2.73 |
| | | DFbFRmin p1 | 996 | 8.1 | 101 | 23.3 | 2.14 |
| | | DFbFRmin p3 | 950 | 12.4 | 129 | 28.9 | 2.18 |
| | | DFfullRuteoMin p1 | 996 | 8.1 | 486 | 30.6 | 1.36 |
| | | DFfullRuteoMin p3 | 941 | 13.2 | 985 | 41.1 | 1.46 |

---

**Documento preparado para revisión - Julio 2026**
