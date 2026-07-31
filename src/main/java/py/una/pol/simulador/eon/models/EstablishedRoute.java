package py.una.pol.simulador.eon.models;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * Ruta establecida por un algoritmo RSA
 *
 * 
 */
@Data
@AllArgsConstructor
public class EstablishedRoute {

    /**
     * Índice inicial del bloque de ranuras de frecuencias que ocupa la conexión
     */
    private Integer fsIndexBegin;
    /**
     * Cantidad de ranuras que ocupa la conexión
     */
    private Integer fsWidth;
    /**
     * Tiempo de vida restante de la conexión
     */
    private Integer lifetime;
    /**
     * Nodo origen
     */
    private Integer from;
    /**
     * Nodo destino
     */
    private Integer to;
    /**
     * Enlaces de la ruta
     */
    private List<Link> path;
    /**
     * Núcleos de los enlaces de la ruta
     */
    private List<Integer> pathCores;
    /**
     * Cantidad de ranuras originales solicitadas por la demanda (antes de división FSDM).
     * En modo SDM: originalDemandFs == fsWidth
     * En modo FSDM: originalDemandFs es el valor original, fsWidth es fsNecesariosPorFibra
     */
    private Integer originalDemandFs;

    /**
     * Constructor vacío
     */
    public EstablishedRoute() {
    }

    /**
     * Constructor con parámetros (7 parámetros - backward compatibility)
     * Asume que fsWidth == originalDemandFs (modo SDM o sin división FSDM)
     *
     * @param path Enlaces de la ruta establecida
     * @param fsIndexBegin Indice inicial del bloque de frecuencias utilizado
     * @param fsWidth Cantidad de ranuras de frecuencia a utilizar
     * @param lifetime Tiempo de vida de la demanda en la ruta
     * @param from Nodo origen
     * @param to Nodo destino
     * @param pathCores Núcleos a los que pertenecen los enlaces de la lista path
     */
    public EstablishedRoute(List<Link> path, Integer fsIndexBegin, Integer fsWidth, Integer lifetime, Integer from, Integer to, List<Integer> pathCores) {
        this.path = path;
        this.fsIndexBegin = fsIndexBegin;
        this.fsWidth = fsWidth;
        this.lifetime = lifetime;
        this.from = from;
        this.to = to;
        this.pathCores = pathCores;
        this.originalDemandFs = fsWidth; // Backward compatibility: asumir no hay división
    }

    /**
     * Constructor con parámetros (8 parámetros - modo FSDM completo)
     *
     * @param path Enlaces de la ruta establecida
     * @param fsIndexBegin Indice inicial del bloque de frecuencias utilizado
     * @param fsWidth Cantidad de ranuras de frecuencia por fibra (fsNecesariosPorFibra en FSDM)
     * @param lifetime Tiempo de vida de la demanda en la ruta
     * @param from Nodo origen
     * @param to Nodo destino
     * @param pathCores Núcleos a los que pertenecen los enlaces de la lista path
     * @param originalDemandFs Cantidad original de ranuras solicitadas (antes de división FSDM)
     */
    public EstablishedRoute(List<Link> path, Integer fsIndexBegin, Integer fsWidth, Integer lifetime, Integer from, Integer to, List<Integer> pathCores, Integer originalDemandFs) {
        this.path = path;
        this.fsIndexBegin = fsIndexBegin;
        this.fsWidth = fsWidth;
        this.lifetime = lifetime;
        this.from = from;
        this.to = to;
        this.pathCores = pathCores;
        this.originalDemandFs = originalDemandFs;
    }

    /**
     * Resta una unidad de tiempo a la conexión
     */
    public void subLifeTime() {
        this.lifetime--;
    }

    @Override
    public String toString() {
        String asd = "EstablisedRoute{"
                + "path=" + path
                + ", fsIndexBegin=" + fsIndexBegin
                + ", fsWidth=" + fsWidth
                + ", tl=" + lifetime
                + ", from=" + from
                + ", to=" + to
                + "}";
        for (Link link : path) {
            asd = asd + link.toString();
        }
        return asd;
    }

}
