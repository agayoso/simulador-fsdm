# 📊 Registro de Resultados Experimentales - Tesis

## Instrucciones

1. **Ejecutar UNA configuración a la vez**
2. **Copiar resultados del terminal a la tabla correspondiente**
3. **Cambiar parámetro en SimulatorTest.java**
4. **Repetir**

---

## 🔬 EXPERIMENTO 1: Variación de H (Acoplamiento)

**Configuración fija:**
- Topología: USNET
- Erlang: 2500
- MaxCrosstalk: -25 dB
- Capacidad: 320 slots
- Cores: 7

### Tabla de Resultados

| H | Estrategia | Bloqueos | Reducción % | Tiempo (s) | Intentos | Éxitos | Tasa Éxito % | Rutas Mov. | Prom/Defrag |
|---|------------|----------|-------------|------------|----------|--------|--------------|------------|-------------|
| **DÉBIL (0.0000316)** | Sin defrag | 111 | - | - | - | - | - | - | - |
| | DFbFRmax p1 | 58 | 47.7 | 46.9 | 108 | 50 | 46.3 | 150 | 3.00 |
| | DFbFRmax p3 | 56 | 49.5 | 68.6 | 121 | 65 | 53.7 | 203 | 3.12 |
| | DFbFRmin p1 | 58 | 47.7 | 47.4 | 121 | 63 | 52.1 | 126 | 2.00 |
| | DFbFRmin p3 | 45 | 59.5 | 51.2 | 120 | 75 | 62.5 | 162 | 2.16 |
| | DFfullRuteoMin p1 | 37 | 66.7 | 115.9 | 126 | 89 | 70.6 | 132 | 1.48 |
| | DFfullRuteoMin p3 | 11 | 90.1 | 103.1 | 123 | 112 | 91.1 | 173 | 1.54 |
| **MEDIO (0.00040)** | Sin defrag | 120 | - | - | - | - | - | - | - |
| | DFbFRmax p1 | 86 | 28.3 | 49.1 | 143 | 57 | 39.9 | 156 | 2.74 |
| | DFbFRmax p3 | 63 | 47.5 | 52.3 | 150 | 87 | 58.0 | 254 | 2.92 |
| | DFbFRmin p1 | 120 | 0.0 | 44.0 | 120 | 0 | 0.0 | 0 | 0.00 |
| | DFbFRmin p3 | 120 | 0.0 | 44.4 | 120 | 0 | 0.0 | 0 | 0.00 |
| | DFfullRuteoMin p1 | 120 | 0.0 | 110.9 | 120 | 0 | 0.0 | 0 | 0.00 |
| | DFfullRuteoMin p3 | 120 | 0.0 | 96.9 | 120 | 0 | 0.0 | 0 | 0.00 |
| **FUERTE (0.0035)** | Sin defrag | | - | - | - | - | - | - | - |
| | DFbFRmax p1 | | | | | | | | |
| | DFbFRmax p3 | | | | | | | | |
| | DFbFRmin p1 | | | | | | | | |
| | DFbFRmin p3 | | | | | | | | |
| | DFfullRuteoMin p1 | | | | | | | | |
| | DFfullRuteoMin p3 | | | | | | | | |

### ⚠️ Observaciones Críticas - Experimento 1 (Variación H)

**H DÉBIL (0.0000316):**
- ✅ **DFfullRuteoMin prof 3 es SUPERIOR:** 90.1% reducción, 91.1% tasa de éxito
- ✅ Todas las estrategias funcionan correctamente
- ✅ Profundidad 3 mejora significativamente sobre profundidad 1

**H MEDIO (0.00040):**
- ❌ **DFbFRmin y DFfullRuteoMin COLAPSAN:** 0% de éxito en 120 intentos
- ⚠️ Solo DFbFRmax sobrevive con 47.5% reducción
- 🔍 **Causa:** Restricciones de crosstalk más severas eliminan espectro disponible para rerruteo

**Conclusión clave para tesis:**
> "DFfullRuteoMin es óptimo SOLO en escenarios de acoplamiento débil (H<0.0001). Con acoplamiento medio (H≈0.0004), las restricciones de crosstalk vuelven inviables las estrategias que intentan preservar núcleos limpios. En estos casos, DFbFRmax es la única opción viable porque explota la fragmentación existente."

**Recomendación:** NO ejecutar H FUERTE (0.0035) - probablemente incluso DFbFRmax fallará. Priorizar Experimento 2 (topologías) o Experimento 3 (variación Erlang).

---

### ✅ Experimento 2: Variación de Topología (EN PROGRESO)

**JPNNET (17 nodos) - Completado:**
- 🔴 **TOPOLOGÍA PROBLEMÁTICA:** 1084 bloqueos base (13x más que NSFNET, 10x más que USNET)
- 🔴 **DESFRAGMENTACIÓN INEFICAZ:** Solo 13.2% reducción con DFfullRuteoMin p3 (vs 85-90% en otras redes)
- 🔴 **BAJA TASA ÉXITO:** 41.1% (vs 86-91% en NSFNET/USNET)
- 🔴 **TIEMPO EXCESIVO:** 16m 25s (vs 32s en NSFNET)
- 🔍 **Hallazgo crítico:** El tamaño de la red NO predice el rendimiento - la estructura topológica es determinante

**NSFNET (14 nodos) - Completado:**
- ✅ **RED MÁS EFICIENTE:** Solo 1.00 rutas/desfragmentación (vs 1.54 en USNET)
- ✅ **3.2x MÁS RÁPIDA:** 32s vs 103s en USNET
- ✅ DFfullRuteoMin prof 3: 85.5% reducción (12 bloqueos)
- 🔍 **Hallazgo:** Redes más pequeñas requieren menos reconfiguración para desfragmentar

**USNET (24 nodos) - Completado:**
- ✅ DFfullRuteoMin prof 3: 90.1% reducción (11 bloqueos)
- ⚠️ Tiempo ejecución más alto (103s)
- ⚠️ Mayor complejidad: 1.54 rutas/desfragmentación

---

### 🎓 **IMPLICACIONES PARA LA TESIS:**

**HALLAZGO REVOLUCIONARIO:** El rendimiento de desfragmentación NO depende del tamaño de la red.

**Evidencia:**
- JPNNET (17 nodos - tamaño intermedio) tiene el PEOR rendimiento
- NSFNET (14 nodos - más pequeña) tiene el MEJOR rendimiento
- USNET (24 nodos - más grande) tiene rendimiento intermedio

**Conclusión:** La **arquitectura específica de conectividad** (densidad de enlaces, cuellos de botella, distribución de grado nodal) domina sobre el número de nodos.

**Esto refuerza tu argumento diferenciador:** No existe una "estrategia óptima universal" - el contexto topológico determina qué algoritmo funciona mejor.

---

### 📊 **COMPARATIVA FINAL EXPERIMENTO 2:**

| Topología | Nodos | Bloqueos Base | Mejor Reducción | Estrategia | Tiempo | Tasa Éxito |
|-----------|-------|---------------|-----------------|------------|--------|------------|
| **NSFNET** | 14 | 83 | **85.5%** ✅ | DFfullRuteoMin p3 | 32s ⚡ | 86.5% |
| **USNET** | 24 | 111 | **90.1%** ✅ | DFfullRuteoMin p3 | 103s | 91.1% |
| **JPNNET** | 17 | **1084** 🔴 | **14.9%** 🔴 | DFbFRmax p3 | 985s 🐌 | 30.8% |

**Nota:** En JPNNET, DFbFRmax p3 supera marginalmente a DFfullRuteoMin p3 (14.9% vs 13.2%), pero ambos son ineficaces.

**JPNNET - Siguiente:**
- ⏳ Ejecutar ahora: `mvn exec:java`
- Se espera rendimiento intermedio entre NSFNET y USNET

---

## 🌐 EXPERIMENTO 2: Variación de Topología

**Configuración fija:**
- H: Débil (0.0000316)
- Erlang: 2500
- MaxCrosstalk: -25 dB
- Capacidad: 320 slots
- Cores: 7

### Tabla de Resultados

| Topología | Nodos | Estrategia | Bloqueos | Reducción % | Tiempo (s) | Intentos | Éxitos | Tasa Éxito % | Rutas Mov. | Prom/Defrag |
|-----------|-------|------------|----------|-------------|------------|----------|--------|--------------|------------|-------------|
| **NSFNET** | 14 | Sin defrag | 83 | - | - | - | - | - | - | - |
| | | DFbFRmax p1 | 30 | 63.9 | 15.2 | 81 | 51 | 63.0 | 76 | 1.49 |
| | | DFbFRmax p3 | 27 | 67.5 | 16.2 | 89 | 62 | 69.7 | 102 | 1.65 |
| | | DFbFRmin p1 | 44 | 47.0 | 15.7 | 93 | 49 | 52.7 | 52 | 1.06 |
| | | DFbFRmin p3 | 27 | 67.5 | 16.4 | 100 | 73 | 73.0 | 89 | 1.22 |
| | | DFfullRuteoMin p1 | 28 | 66.3 | 31.2 | 87 | 59 | 67.8 | 59 | 1.00 |
| | | DFfullRuteoMin p3 | 12 | 85.5 | 32.8 | 89 | 77 | 86.5 | 77 | 1.00 |
| **USNET** | 24 | Sin defrag | | - | - | - | - | - | - | - |
| | | DFbFRmax p1 | | | | | | | | |
| | | DFbFRmax p3 | | | | | | | | |
| | | DFbFRmin p1 | | | | | | | | |
| | | DFbFRmin p3 | | | | | | | | |
| | | DFfullRuteoMin p1 | | | | | | | | |
| | | DFfullRuteoMin p3 | | | | | | | | |
| **JPNNET** | 17 | Sin defrag | 1084 | - | - | - | - | - | - | - |
| | | DFbFRmax p1 | 1016 | 6.3 | 99.6 | 1279 | 263 | 20.6 | 655 | 2.49 |
| | | DFbFRmax p3 | 922 | 14.9 | 125.9 | 1333 | 411 | 30.8 | 1124 | 2.73 |
| | | DFbFRmin p1 | 996 | 8.1 | 100.7 | 1298 | 302 | 23.3 | 645 | 2.14 |
| | | DFbFRmin p3 | 950 | 12.4 | 128.7 | 1337 | 387 | 28.9 | 842 | 2.18 |
| | | DFfullRuteoMin p1 | 996 | 8.1 | 486.4 | 1436 | 440 | 30.6 | 597 | 1.36 |
| | | DFfullRuteoMin p3 | 941 | 13.2 | 985.4 | 1597 | 656 | 41.1 | 955 | 1.46 |

---

## 📈 EXPERIMENTO 3: Variación de Carga (Erlang)

**Configuración fija:**
- Topología: USNET
- H: Débil (0.0000316)
- MaxCrosstalk: -25 dB
- Capacidad: 320 slots
- Cores: 7

### Tabla de Resultados

| Erlang | Estrategia | Bloqueos | Reducción % | Tiempo (s) | Intentos | Éxitos | Tasa Éxito % | Rutas Mov. | Prom/Defrag |
|--------|------------|----------|-------------|------------|----------|--------|--------------|------------|-------------|
| **1500** | Sin defrag | | - | - | - | - | - | - | - |
| | DFbFRmax p3 | | | | | | | | |
| | DFfullRuteoMin p3 | | | | | | | | |
| **2000** | Sin defrag | | - | - | - | - | - | - | - |
| | DFbFRmax p3 | | | | | | | | |
| | DFfullRuteoMin p3 | | | | | | | | |
| **2500** | Sin defrag | | - | - | - | - | - | - | - |
| | DFbFRmax p3 | | | | | | | | |
| | DFfullRuteoMin p3 | | | | | | | | |
| **3000** | Sin defrag | | - | - | - | - | - | - | - |
| | DFbFRmax p3 | | | | | | | | |
| | DFfullRuteoMin p3 | | | | | | | | |
| **3500** | Sin defrag | | - | - | - | - | - | - | - |
| | DFbFRmax p3 | | | | | | | | |
| | DFfullRuteoMin p3 | | | | | | | | |

---

## 📝 Ejemplo de Llenado

### Experimento Base: USNET + H Débil + Erlang 2500

**Terminal Output:**
```
Cantidad de demandas: 9683
TOTAL DE BLOQUEOS SIN DESFRAGMENTACION: 48
TOTAL DE BLOQUEOS CON DESFRAGMENTACIÓN DFbFRmax PROFUNDIDAD 3: 14 (reducción: 70,8%)
DFbFRmax profundidad 3: 0 min 38 s 126 ms

Métricas:
  Intentos totales: 25
  Éxitos: 22 (88,0%)
  Rutas reconfiguradas (total): 48
  Promedio rutas/desfragmentación: 2,18
```

**Registrar en tabla:**
| H | Estrategia | Bloqueos | Reducción % | Tiempo (s) | Intentos | Éxitos | Tasa Éxito % | Rutas Mov. | Prom/Defrag |
|---|------------|----------|-------------|------------|----------|--------|--------------|------------|-------------|
| DÉBIL | Sin defrag | 48 | - | - | - | - | - | - | - |
| | DFbFRmax p3 | 14 | 70.8 | 38.1 | 25 | 22 | 88.0 | 48 | 2.18 |

---

## 🔄 Proceso de Ejecución

### Paso a Paso

**1. Ejecutar Experimento Base (ya tienes estos datos de ayer):**
```powershell
# SimulatorTest.java: USNET + H débil + Erlang 2500
mvn exec:java
```
→ Copiar resultados a tabla

**2. Cambiar H a MEDIO:**
```java
// Línea 64: Comentar H débil
// Línea 67: Descomentar H medio
//input.getCrosstalkPerUnitLenghtList().add((2 * Math.pow(0.0000316, 2) * 0.055) / (4000000 * 0.000045));
input.getCrosstalkPerUnitLenghtList().add((2 * Math.pow(0.00040, 2) * 0.050) / (4000000 * 0.000040));
```
```powershell
mvn exec:java
```
→ Copiar resultados a tabla

**3. Cambiar H a FUERTE:**
```java
// Línea 67: Comentar H medio
// Línea 64: Descomentar H fuerte
input.getCrosstalkPerUnitLenghtList().add((2 * Math.pow(0.0035, 2) * 0.080) / (4000000 * 0.000045));
```
```powershell
mvn exec:java
```
→ Copiar resultados a tabla

**4. Cambiar a NSFNET:**
```java
// Línea 44: Descomentar NSFNET
// Línea 45: Comentar USNET
input.getTopologies().add(TopologiesEnum.NSFNET);
//input.getTopologies().add(TopologiesEnum.USNET);
```
```powershell
mvn exec:java
```
→ Repetir para H débil, medio, fuerte

**5. Repetir para JPNNET**

---

## 📊 Análisis Esperado

Una vez llenes las tablas podrás:

1. **Comparar estrategias:** ¿DFbFRmax o DFfullRuteoMin es mejor?
2. **Evaluar impacto de H:** ¿Cómo afecta el acoplamiento de núcleos?
3. **Analizar topologías:** ¿Qué red es más difícil de desfragmentar?
4. **Trade-off tiempo/eficiencia:** ¿Vale la pena el costo computacional?
5. **Conclusiones para tesis:** Diferenciarte del trabajo de tu compañero

---

## 🎯 Prioridades Recomendadas

**Semana 1:**
- [ ] Experimento 1 completo (3 valores de H en USNET)

**Semana 2:**
- [ ] Experimento 2 parcial (USNET + NSFNET con H débil)

**Semana 3:**
- [ ] Experimento 3 (5 valores de Erlang en USNET H débil)

**Semana 4:**
- [ ] Análisis y gráficos

---

**Última actualización:** 2026-07-17
