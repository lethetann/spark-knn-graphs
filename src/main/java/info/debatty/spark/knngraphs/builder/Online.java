/*
 * The MIT License
 *
 * Copyright 2016 Thibault Debatty.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package info.debatty.spark.knngraphs.builder;

import info.debatty.java.graphs.Graph;
import info.debatty.java.graphs.Neighbor;
import info.debatty.java.graphs.NeighborList;
import info.debatty.java.graphs.Node;
import info.debatty.java.graphs.SimilarityInterface;
import info.debatty.spark.knngraphs.ApproximateSearch;
import info.debatty.spark.knngraphs.BalancedKMedoidsPartitioner;
import java.security.InvalidParameterException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.function.FlatMapFunction;
import org.apache.spark.api.java.function.Function;
import org.apache.spark.api.java.function.PairFlatMapFunction;
import scala.Tuple2;

/**
 *
 * @author Thibault Debatty
 * @param <T>
 */
public class Online<T> {

    private static final int PARTITIONING_ITERATIONS = 5;
    private static final int DEFAULT_SEARCH_SPEEDUP = 4;
    private static final double DEFAULT_MEDOID_UPDATE_RATIO = 0.1;

    // Number of nodes to add before performing a checkpoint
    // (to strip RDD DAG)
    private static final int ITERATIONS_BETWEEN_CHECKPOINTS = 100;

    // the search algorithm also contains a reference to the current graph
    private final ApproximateSearch<T> searcher;
    private final int k;
    private final SimilarityInterface<T> similarity;
    // Number of nodes to add before recomputing centroids
    private double medoid_update_ratio = DEFAULT_MEDOID_UPDATE_RATIO;

    private final long[] partitions_size;
    private final LinkedList<JavaRDD<Graph<T>>> previous_rdds;

    private int search_speedup = DEFAULT_SEARCH_SPEEDUP;
    private long nodes_added;
    private long nodes_before_update_medoids;

    /**
     *
     * @param k number of edges per node
     * @param similarity similarity to use for computing edges
     * @param sc spark context
     * @param initial initial graph
     * @param partitioning_medoids number of partitions
     */
    public Online(
            final int k,
            final SimilarityInterface<T> similarity,
            final JavaSparkContext sc,
            final JavaPairRDD<Node<T>, NeighborList> initial,
            final int partitioning_medoids) {

        this.nodes_added = 0;
        this.similarity = similarity;
        this.k = k;
        this.searcher = new ApproximateSearch<T>(
                initial,
                PARTITIONING_ITERATIONS,
                partitioning_medoids,
                similarity);

        sc.setCheckpointDir("/tmp/checkpoints");

        this.partitions_size = getPartitionsSize();
        this.previous_rdds = new LinkedList<JavaRDD<Graph<T>>>();
        this.nodes_before_update_medoids = computeNodesBeforeUpdate();
    }

    /**
     * Get the total number of nodes in the online graph.
     * @return total number of nodes in the graph
     */
    public final long getSize() {
        long agg = 0;
        for (long value : partitions_size) {
            agg += value;
        }
        return agg;
    }

    /**
     * Set the speedup of the search step to add a node (default: 4).
     * @param search_speedup speedup
     */
    public final void setSearchSpeedup(final int search_speedup) {
        this.search_speedup = search_speedup;
    }

    /**
     * Set the ratio of nodes to add to the graph before recomputing the
     * medoids (default: 0.1).
     * @param update_ratio [0.0 ...] (0 = disable medoid update)
     */
    public final void setMedoidUpdateRatio(final double update_ratio) {
        if (update_ratio < 0) {
            throw new InvalidParameterException("Update ratio must be >= 0!");
        }
        this.medoid_update_ratio = update_ratio;
        this.nodes_before_update_medoids = computeNodesBeforeUpdate();
    }

    /**
     *
     * @param node to add to the graph
     */
    public final void addNode(final Node<T> node) {

        // Find the neighbors of this node
        NeighborList neighborlist = searcher.search(node, k, search_speedup);

        // Assign the node to a partition (most similar medoid, with partition
        // size constraint)
        searcher.assign(node, partitions_size);

        // bookkeeping: update the counts
        partitions_size[(Integer) node
                .getAttribute(BalancedKMedoidsPartitioner.PARTITION_KEY)]++;

        // update the existing graph edges
        JavaRDD<Graph<T>> updated_graph = searcher.getGraph().map(
                    new UpdateFunction<T>(node, neighborlist, similarity));

        // Add the new <Node, NeighborList> to the distributed graph
        updated_graph = updated_graph.map(new AddNode(node, neighborlist));

        // From now on, use the new graph...
        searcher.setGraph(updated_graph);

        //  truncate RDD DAG (would cause a stack overflow, even with caching)
        if ((nodes_added % ITERATIONS_BETWEEN_CHECKPOINTS) == 0) {
            updated_graph.checkpoint();
        }

        // Keep a track of updated RDD to unpersist after two iterations
        previous_rdds.add(updated_graph);
        if (nodes_added > 2) {
            previous_rdds.pop().unpersist();
        }

        nodes_before_update_medoids--;
        if (nodes_before_update_medoids == 0) {
            searcher.getPartitioner().computeNewMedoids(updated_graph);
            this.nodes_before_update_medoids = computeNodesBeforeUpdate();
        }

        nodes_added++;
    }

    /**
     * Remove a node using fast approximate algorithm.
     * @param node_to_remove
     */
    public final void fastRemove(final Node<T> node_to_remove) {
        // find the list of nodes to update
        List<Node<T>> nodes_to_update = searcher.getGraph()
                .flatMap(new FindNodesToUpdate(node_to_remove))
                .collect();

        // build the list of candidates
        LinkedList<Node<T>> initial_candidates = new LinkedList<Node<T>>();
        initial_candidates.add(node_to_remove);
        initial_candidates.addAll(nodes_to_update);

        // In spark 1.6.0 the list returned by collect causes an
        // UnsupportedOperationException when you try to remove :(
        LinkedList<Node<T>> candidates  =
                new LinkedList<Node<T>>(
                        searcher.getGraph()
                        .flatMap(new SearchNeighbors(initial_candidates))
                        .collect());
        while (candidates.contains(node_to_remove)) {
            candidates.remove(node_to_remove);
        }

        // update the graph and remove the node
        searcher.setGraph(
                searcher.getGraph()
                        .map(new RemoveUpdate(
                                node_to_remove,
                                nodes_to_update,
                                candidates)));
    }

    /**
     * Get the current graph, represented as a RDD of Graph.
     * @return the current graph
     */
    public final JavaRDD<Graph<T>> getDistributedGraph() {
        return searcher.getGraph();
    }

    /**
     * Get the current graph, converted to a RDD of Tuples (Node, NeighborList).
     * @return
     */
    public final JavaPairRDD<Node<T>, NeighborList> getGraph() {
        return searcher.getGraph().flatMapToPair(new MergeGraphs());
    }

    private long[] getPartitionsSize() {
        List<Long> counts_list = searcher.getGraph().mapPartitions(
                new PartitionCountFunction(), true).collect();

        long[] result = new long[counts_list.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = counts_list.get(i);
        }
        return result;
    }

    /**
     * Compute the number of nodes that can be added before we recompute the
     * medoids (depends on current size and medoid_update_ratio).
     */
    private long computeNodesBeforeUpdate() {
        if (medoid_update_ratio == 0.0) {
            return Long.MAX_VALUE;
        }

        return (long) (getSize() * medoid_update_ratio);

    }
}

/**
 * Used to count the number of items in each partition, when we initialize the
 * distributed online graph.
 * @author Thibault Debatty
 * @param <T>
 */
class PartitionCountFunction<T>
        implements FlatMapFunction
        <Iterator<Tuple2<Node<T>, NeighborList>>, Long> {

    /**
     *
     * @param iterator
     * @return
     * @throws Exception
     */
    public Iterable<Long> call(
            final Iterator<Tuple2<Node<T>, NeighborList>> iterator)
            throws Exception {
        long count = 0;
        while (iterator.hasNext()) {
            iterator.next();
            count++;
        }

        ArrayList<Long> result = new ArrayList<Long>(1);
        result.add(count);
        return result;
    }
}

/**
 *
 * @author Thibault Debatty
 * @param <T>
 */
class AddNode<T> implements Function<Graph<T>, Graph<T>> {
    private final Node<T> node;
    private final NeighborList neighborlist;

    AddNode(final Node<T> node, final NeighborList neighborlist) {
        this.node = node;
        this.neighborlist = neighborlist;
    }

    public Graph<T> call(final Graph<T> graph) {
        Node<T> one_node = graph.getNodes().iterator().next();

        if (node.getAttribute(BalancedKMedoidsPartitioner.PARTITION_KEY).equals(
                one_node.getAttribute(
                        BalancedKMedoidsPartitioner.PARTITION_KEY))) {
            graph.put(node, neighborlist);
        }

        return graph;
    }
}

/**
 * Update the graph when adding a node.
 * @author Thibault Debatty
 * @param <T>
 */
class UpdateFunction<T>
        implements Function<Graph<T>, Graph<T>> {

    private static final int UPDATE_DEPTH = 2;

    private final NeighborList neighborlist;
    private final SimilarityInterface<T> similarity;
    private final Node<T> node;

    UpdateFunction(
            final Node<T> node,
            final NeighborList neighborlist,
            final SimilarityInterface<T> similarity) {

        this.node = node;
        this.neighborlist = neighborlist;
        this.similarity = similarity;
    }

    public Graph<T> call(final Graph<T> local_graph) {

        // Nodes to analyze at this iteration
        LinkedList<Node<T>> analyze = new LinkedList<Node<T>>();

        // Nodes to analyze at next iteration
        LinkedList<Node<T>> next_analyze = new LinkedList<Node<T>>();

        // List of already analyzed nodes
        HashMap<Node<T>, Boolean> visited = new HashMap<Node<T>, Boolean>();

        // Fill the list of nodes to analyze
        for (Neighbor neighbor : neighborlist) {
            analyze.add(neighbor.node);
        }

        for (int depth = 0; depth < UPDATE_DEPTH; depth++) {
            while (!analyze.isEmpty()) {
                Node other = analyze.pop();
                NeighborList other_neighborlist = local_graph.get(other);

                // This part of the graph is in another partition :-(
                if (other_neighborlist == null) {
                    continue;
                }

                // Add neighbors to the list of nodes to analyze
                // at next iteration
                for (Neighbor other_neighbor : other_neighborlist) {
                    if (!visited.containsKey(other_neighbor.node)) {
                        next_analyze.add(other_neighbor.node);
                    }
                }

                // Try to add the new node (if sufficiently similar)
                other_neighborlist.add(new Neighbor(
                        node,
                        similarity.similarity(
                                node.value,
                                (T) other.value)));

                visited.put(other, Boolean.TRUE);
            }

            analyze = next_analyze;
            next_analyze = new LinkedList<Node<T>>();
        }

        return local_graph;
    }
}

/**
 * In this Spark implementation, the distributed graph is stored as a RDD of
 * subgraphs, this function collects the subgraphs and returns a single graph,
 * represented as an RDD of tuples (Node, NeighborList).
 * This function is used by the method Online.getGraph().
 * @author Thibault Debatty
 * @param <T>
 */
class MergeGraphs<T>
    implements PairFlatMapFunction<Graph<T>, Node<T>, NeighborList> {

    public Iterable<Tuple2<Node<T>, NeighborList>> call(final Graph<T> graph) {

        ArrayList<Tuple2<Node<T>, NeighborList>> list =
                new ArrayList<Tuple2<Node<T>, NeighborList>>(graph.size());

        for (Map.Entry<Node<T>, NeighborList> entry : graph.entrySet()) {
            list.add(new Tuple2<Node<T>, NeighborList>(
                    entry.getKey(),
                    entry.getValue()));
        }

        return list;
    }
}

/**
 * Used by fastRemove to find the nodes that should be updated.
 * @author Thibault Debatty
 * @param <T>
 */
class FindNodesToUpdate<T> implements FlatMapFunction<Graph<T>, Node<T>> {
    private final Node<T> node_to_remove;

    FindNodesToUpdate(final Node<T> node_to_remove) {
        this.node_to_remove = node_to_remove;
    }

    public Iterable<Node<T>> call(final Graph<T> subgraph) {
        LinkedList<Node<T>> nodes_to_update = new LinkedList<Node<T>>();
        for (Node<T> node : subgraph.getNodes()) {
            if (subgraph.get(node).containsNode(node_to_remove)) {
                nodes_to_update.add(node);
            }
        }

        return nodes_to_update;
    }
}

/**
 * Search neighbors from a list of starting points, up to a fixed depth.
 * Used in Online.fastRemove(node) to search the candidates.
 * @author Thibault Debatty
 * @param <T>
 */
class SearchNeighbors<T> implements FlatMapFunction<Graph<T>, Node<T>> {
    private static final int SEARCH_DEPTH = 3;

    private final LinkedList<Node<T>> starting_points;

    SearchNeighbors(final LinkedList<Node<T>> initial_candidates) {
        this.starting_points = initial_candidates;
    }

    public Iterable<Node<T>> call(final Graph<T> subgraph) {
        return subgraph.findNeighbors(starting_points, SEARCH_DEPTH);
    }
}

/**
 * When removing a node, update the subgraphs: remove the node, and assign
 * a new neighbor to nodes that had this node as neighbor.
 * @author Thibault Debatty
 * @param <T>
 */
class RemoveUpdate<T> implements Function<Graph<T>, Graph<T>> {
    private final Node<T> node_to_remove;
    private final List<Node<T>> nodes_to_update;
    private final List<Node<T>> candidates;

    RemoveUpdate(
            final Node<T> node_to_remove,
            final List<Node<T>> nodes_to_update,
            final List<Node<T>> candidates) {

        this.node_to_remove = node_to_remove;
        this.nodes_to_update = nodes_to_update;
        this.candidates = candidates;

    }

    public Graph<T> call(final Graph<T> subgraph) {

        // Remove the node (if present in this subgraph)
        subgraph.getHashMap().remove(node_to_remove);

        for (Node<T> node_to_update : nodes_to_update) {
            if (!subgraph.containsKey(node_to_update)) {
                // This node belongs to another subgraph => skip
                continue;
            }

            NeighborList nl_to_update = subgraph.get(node_to_update);

            // Remove the old node
            nl_to_update.removeNode(node_to_remove);

            // Replace the old node by the best candidate
            for (Node<T> candidate : candidates) {
                double similarity = subgraph.getSimilarity().similarity(
                        node_to_update.value,
                        candidate.value);

                nl_to_update.add(new Neighbor(candidate, similarity));
            }
        }

        return subgraph;
    }
}