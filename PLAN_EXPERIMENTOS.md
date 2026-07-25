# Plan de Experimentación - Tesis Desfragmentación EON

## 📋 Resultados Base (Erlang 2500, H débil)

**Ejecutado:** 2026-07-15

```
BLOQUEOS:
- Sin desfragmentación:       48 (baseline)
- DFbFRmax prof 1:            24 (50.0% reducción)
- DFbFRmax prof 3:            14 (70.8% reducción)
- DFbFRmin prof 1:            19 (60.4% reducción)
- DFbFRmin prof 3:            16 (66.7% reducción)
- DFfullRuteoMin prof 1:      11 (77.1% reducción)
- DFfullRuteoMin prof 3:       3 (93.8% reducción) ⭐ MEJOR

TIEMPOS:
- DFbFRmax prof 1:        39.4s
- DFbFRmax prof 3:        38.1s
- DFbFRmin prof 1:        57.5s
- DFbFRmin prof 3:        45.5s
- DFfullRuteoMin prof 1:  63.8s
- DFfullRuteoMin prof 3:  87.3s ← MÁS COSTOSO
```

**Conclusión preliminar:** DFfullRuteoMin prof 3 es superior pero ~2x más lento que DFbFRmax.

---

## 🎯 Experimentos Sugeridos

### ⚠️ **METODOLOGÍA IMPORTANTE**
**Ejecutar UNA configuración a la vez:**
1. Configurar parámetros en SimulatorTest.java
2. Ejecutar `mvn exec:java`
3. Anotar resultados en RESULTADOS_EXPERIMENTALES.md
4. Cambiar UN parámetro
5. Repetir

**NO ejecutar múltiples topologías/H/Erlang simultáneamente** - Los resultados se mezclan.

---

### **EXPERIMENTO 1: Variación de H (Acoplamiento)**
**Objetivo:** Determinar cómo el acoplamiento de núcleos afecta cada estrategia.

**Configuración fija:**
- Topología: USNET (línea 45)
- Erlang: 2500 (línea 75)

**Pasos:**
1. H DÉBIL (ya ejecutado ayer): 48→3 bloqueos
2. H MEDIO: Descomentar línea 67, comentar línea 70
3. H FUERTE: Descomentar línea 64, comentar línea 70

**Duración estimada:** ~21 minutos (3 ejecuciones × 7 min)

**Hipótesis:** H fuerte debería favorecer DFbFRmin (preserva núcleos poco fragmentados)

---

### **EXPERIMENTO 2: Variación de Carga (Erlang)**
**Objetivo:** Determinar en qué rangos de carga cada estrategia es óptima.

**Configuración fija:**
- Topología: USNET
- H: DÉBIL (línea 70)

**Configuración en SimulatorTest.java (línea 75):**
```java
// Ejecutar 5 VECES SEPARADAS, cambiando el valor cada vez:
for (int erlang = 1500; erlang <= 1500; erlang = erlang + 1000) {  // Ejecución 1
for (int erlang = 2000; erlang <= 2000; erlang = erlang + 1000) {  // Ejecución 2
for (int erlang = 2500; erlang <= 2500; erlang = erlang + 1000) {  // Ejecución 3 (ya hecho)
for (int erlang = 3000; erlang <= 3000; erlang = erlang + 1000) {  // Ejecución 4
for (int erlang = 3500; erlang <= 3500; erlang = erlang + 1000) {  // Ejecución 5
```

**Duración estimada:** ~35 minutos (5 ejecuciones × 7 min)

**Métricas a graficar:**
- Bloqueos vs. Erlang (7 curvas)
- Tiempo ejecución vs. Erlang
- Rutas movidas vs. Erlang

---

### **EXPERIMENTO 3: Variación de Topología**
**Objetivo:** Generalizar resultados a diferentes arquitecturas de red.

**Configuración fija:**
- H: DÉBIL (línea 70)
- Erlang: 2500

**Pasos (ejecutar 3 veces separadas):**
1. USNET (ya ejecutado): 24 nodos, 48→3 bloqueos
2. NSFNET: Línea 44 descomentar, línea 45 comentar
3. JPNNET: Línea 46 descomentar, línea 45 comentar

**Duración estimada:** ~21 minutos (3 ejecuciones × 7 min)

**Métricas a comparar:**
- ¿Qué estrategia es mejor en redes densas vs. dispersas?
- ¿El número de saltos afecta la eficiencia de DFfullRuteoMin?

---

### **EXPERIMENTO 4: Profundidades Extendidas** (Opcional)
**Objetivo:** Ley de rendimientos decrecientes en profundidad.

**Profundidades a probar:** 1, 3, 5, 7

**Requiere:** Agregar código para prof 5 y 7 (ver PLAN original)

**Duración estimada:** ~15 minutos (1 ejecución)

**Pregunta de investigación:** ¿Profundidad >3 justifica el costo computacional adicional?

---

### ~~**EXPERIMENTO 5: Umbral de Crosstalk (MaxCrosstalk)**~~ (Baja prioridad)
**Objetivo:** Evaluar impacto de restricciones más/menos permisivas.

**Configuración en SimulatorTest.java (línea 58):**
```java
// CAMBIAR:
input.setMaxCrosstalk(new BigDecimal("0.003162277660168379331998893544")); // -25 dB

// A (ejecutar 2 veces separadas):
// PRUEBA 1: Más permisivo
input.setMaxCrosstalk(new BigDecimal("0.031622776601683793319988935444")); // -15 dB

// PRUEBA 2: Más estricto
input.setMaxCrosstalk(new BigDecimal("0.001")); // -30 dB
```

**Hipótesis:** Con XT más permisivo (-15 dB), la desfragmentación debería ser menos necesaria.

---

## 📊 Formato de Reporte de Resultados

Para cada experimento, anotar:

```
CONFIGURACIÓN:
- Erlang: 2500
- H: débil
- MaxXT: -25 dB
- Topología: USNET
- Fecha: 2026-07-15

RESULTADOS:
┌─────────────────────────┬──────────┬───────────┬──────────┬─────────────┬──────────┐
│ Estrategia              │ Bloqueos │ Reducción │ Tiempo   │ Rutas Mov.  │ Prom/Def │
├─────────────────────────┼──────────┼───────────┼──────────┼─────────────┼──────────┤
│ Sin defrag              │ 48       │ -         │ -        │ -           │ -        │
│ DFbFRmax prof 1         │ 24       │ 50.0%     │ 39.4s    │ [AGREGAR]   │ [AGREG]  │
│ DFbFRmax prof 3         │ 14       │ 70.8%     │ 38.1s    │ [AGREGAR]   │ [AGREG]  │
│ DFfullRuteoMin prof 3   │ 3        │ 93.8%     │ 87.3s    │ [AGREGAR]   │ [AGREG]  │
└─────────────────────────┴──────────┴───────────┴──────────┴─────────────┴──────────┘
```

---

## 🚀 Plan de Ejecución Recomendado

### **Semana 1: Baseline + Validación**
- [x] Ejecutar configuración base (USNET + H débil + Erlang 2500) ✅ 2026-07-15
- [x] Validar nuevas métricas (rutas movidas, tasa éxito) ✅
- [ ] Anotar resultados en RESULTADOS_EXPERIMENTALES.md

### **Semana 2: Experimento 1 (Variación H)**
- [ ] **Ejecución 1:** USNET + H DÉBIL + Erlang 2500 (ya tienes datos)
- [ ] **Ejecución 2:** USNET + H MEDIO + Erlang 2500
- [ ] **Ejecución 3:** USNET + H FUERTE + Erlang 2500
- [ ] Comparar resultados: ¿H afecta qué estrategia es mejor?

### **Semana 3: Experimento 2 (Variación Erlang)**
- [ ] **Ejecución 1:** Erlang 1500
- [ ] **Ejecución 2:** Erlang 2000
- [ ] **Ejecución 3:** Erlang 2500 (ya tienes datos)
- [ ] **Ejecución 4:** Erlang 3000
- [ ] **Ejecución 5:** Erlang 3500
- [ ] Generar gráfico: Bloqueos vs. Carga

### **Semana 4 (Opcional): Experimento 3 (Topologías)**
- [ ] **Ejecución 1:** USNET (ya tienes datos)
- [ ] **Ejecución 2:** NSFNET
- [ ] **Ejecución 3:** JPNNET
- [ ] Comparar: ¿Arquitectura de red afecta rendimiento?

### **Semana 5: Análisis y Escritura**
- [ ] Consolidar resultados en tablas
- [ ] Generar gráficos (Excel/Python)
- [ ] Redactar sección de resultados
- [ ] Redactar conclusiones

---

## 💡 Conclusiones Potenciales (Preliminares)

Basado en resultados iniciales:

1. **"DFfullRuteoMin prof 3 es la estrategia óptima para reducir bloqueos, con 93.8% de reducción frente al 70.8% de DFbFRmax prof 3"**

2. **"El costo computacional de DFfullRuteoMin (2.3x más lento) se justifica en escenarios de alta carga donde cada bloqueo tiene alto impacto"**

3. **"La profundidad 3 mejora significativamente sobre profundidad 1 (42% en DFbFRmax, 73% en DFfullRuteoMin), sugiriendo que explorar 3 candidatos es suficiente"**

4. **"Se requiere validación con diferentes valores de H y Erlang para generalizar resultados"**

---

## 📌 Notas Importantes

- **SIEMPRE** ejecutar con las mismas demandas (seed fija) para comparación justa
- **ANOTAR** valores exactos de métricas para reproducibilidad
- **GRAFICAR** resultados inmediatamente después de cada experimento
- **DOCUMENTAR** configuraciones que fallen o produzcan resultados inesperados

---

**Última actualización:** 2026-07-16
