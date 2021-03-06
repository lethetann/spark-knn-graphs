package info.debatty.spark.knngraphs.builder;

import info.debatty.java.graphs.NeighborList;

import info.debatty.java.graphs.SimilarityInterface;
import info.debatty.spark.knngraphs.DistributedGraph;
import info.debatty.spark.knngraphs.Node;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.security.InvalidParameterException;
import java.util.ArrayList;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;

/**
 *
 * @author Thibault Debatty
 * @param <T> the type of element the actual graph builder works with...
 */
public abstract class DistributedGraphBuilder<T> {

    protected int k = 10;
    protected SimilarityInterface<T> similarity;

    /**
     * Set k (the number of edges per node).
     * Default value is 10
     * @param k
     */
    public final void setK(final int k) {
        if (k <= 0) {
            throw new InvalidParameterException("k must be positive!");
        }

        this.k = k;
    }

    /**
     * Define how similarity will be computed between node values.
     * NNDescent can use any similarity (even non metric).
     * @param similarity
     */
    public final void setSimilarity(final SimilarityInterface<T> similarity) {
        this.similarity = similarity;
    }

    /**
     * Compute and return the graph.
     * Children classes must implement doComputeGraph(nodes) method
     *
     * @param nodes
     * @return the graph
     * @throws java.lang.Exception if cannot build graph
     */
    public final JavaPairRDD<Node<T>, NeighborList> computeGraph(
            final JavaRDD<T> nodes) throws Exception {


        return computeGraphFromNodes(DistributedGraph.wrapNodes(nodes));
    }

    /**
     * Compute and return the graph.
     * @param nodes
     * @return
     * @throws Exception on error
     */
    public final JavaPairRDD<Node<T>, NeighborList> computeGraphFromNodes(
            final JavaRDD<Node<T>> nodes) throws Exception {

        if (similarity == null) {
            throw new InvalidParameterException("Similarity is not defined!");
        }

        return doComputeGraph(nodes);
    }

    /**
     *
     * @param nodes
     * @return
     * @throws java.lang.Exception
     */
    protected abstract JavaPairRDD<Node<T>, NeighborList>
            doComputeGraph(JavaRDD<Node<T>> nodes) throws Exception;

    /**
     * Read a file to an ArrayList of String.
     * @param path
     * @return
     * @throws IOException if cannot read file
     */
    public static ArrayList<String> readFile(final String path)
            throws IOException {

        File file = new File(path);
        ArrayList<String> lines;
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            lines = new ArrayList<>();
            String line;
            while ((line = br.readLine()) != null) {
                lines.add(line);
            }
        }
        return lines;
    }
}
