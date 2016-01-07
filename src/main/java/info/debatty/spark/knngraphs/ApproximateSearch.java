/*
 * The MIT License
 *
 * Copyright 2015 Thibault Debatty.
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
package info.debatty.spark.knngraphs;

import info.debatty.java.graphs.Dijkstra;
import info.debatty.java.graphs.Graph;
import info.debatty.java.graphs.NeighborList;
import info.debatty.java.graphs.Node;
import info.debatty.java.graphs.SimilarityInterface;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import org.apache.spark.Partitioner;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.function.FlatMapFunction;
import org.apache.spark.api.java.function.PairFlatMapFunction;
import scala.Tuple2;

/**
 *
 * @author Thibault Debatty
 * @param <T>
 */
public class ApproximateSearch<T> implements Serializable {

    private final JavaPairRDD graph;
    private final SimilarityInterface<T> similarity;
    private final int partitioning_medoids;

    /**
     *
     * @param graph
     * @param partitioning_iterations
     * @param partitioning_medoids
     * @param similarity
     */
    public ApproximateSearch(
            JavaPairRDD<Node<T>, NeighborList> graph, 
            int partitioning_iterations, 
            int partitioning_medoids, 
            SimilarityInterface<T> similarity) {

        this.similarity = similarity;
        this.partitioning_medoids = partitioning_medoids;
        
        // Partition the graph        
        BalancedKMedoidsPartitioner partitioner = new BalancedKMedoidsPartitioner();
        partitioner.iterations = partitioning_iterations;
        partitioner.partitions = partitioning_medoids;
        partitioner.similarity = similarity;
        partitioner.imbalance = 1.05;
        
        this.graph = partitioner.partition(graph);
        this.graph.cache();
    }
    
    /**
     *
     * @param query
     * @param k
     * @param max_similarities
     * @return
     */
    public NeighborList search(
            final Node<T> query, 
            final int k, 
            final int max_similarities) {
        
        
        return search(
                query, 
                k,
                max_similarities,
                100, 
                1.01);
    }

    /**
     *
     * @param query
     * @param k
     * @param max_similarities
     * @param gnss_depth
     * @param gnss_expansion
     * @return
     */
    public NeighborList search(
            final Node<T> query, 
            final int k, 
            final int max_similarities, 
            final int gnss_depth,
            final double gnss_expansion) {
        
        final int max_similarities_per_partition = 
                max_similarities / partitioning_medoids;
        
        JavaRDD<NeighborList> candidates_neighborlists_graph = 
                graph.mapPartitions(
                        new FlatMapFunction<
                                Iterator<Tuple2<Node<T>, NeighborList>>, 
                                NeighborList>() {

            public Iterable<NeighborList> call(
                    Iterator<Tuple2<Node<T>, NeighborList>> tuples) 
                    throws Exception {

                // Read all tuples to rebuild the subgraph
                Graph<T> local_graph = new Graph<T>();
                while (tuples.hasNext()) {
                    Tuple2<Node<T>, NeighborList> next = tuples.next();
                    local_graph.put(next._1, next._2);
                }

                ArrayList<NeighborList> result = new ArrayList<NeighborList>(1);

                NeighborList nl = local_graph.search(
                        query, 
                        k, 
                        similarity, 
                        max_similarities_per_partition,
                        gnss_depth,
                        gnss_expansion);
                result.add(nl);
                return result;
            }
        });

        NeighborList final_neighborlist = new NeighborList(k);
        for (NeighborList nl : candidates_neighborlists_graph.collect()) {
            final_neighborlist.addAll(nl);
        }
        return final_neighborlist;
    }

}


class BalancedKMedoidsPartitioner<T> implements Serializable {

    public SimilarityInterface<T> similarity;
    public int iterations = 5;
    public int partitions = 4;
    public double imbalance = 1.1;

    public JavaPairRDD<Node<T>, NeighborList> partition(JavaPairRDD<Node<T>, NeighborList> graph) {

        // Pick some random initial medoids
        double fraction = 10.0 * partitions / graph.count();
        Iterator<Tuple2<Node<T>, NeighborList>> sample_iterator = 
                graph.sample(false, fraction).collect().iterator();
        List<Node<T>> medoids = new ArrayList<Node<T>>();
        for (int i = 0; i < partitions; i++) {
            medoids.add(sample_iterator.next()._1);
        }

        // Perform iterations
        for (int iteration = 0; iteration < iterations; iteration++) {
            //System.out.println("Iteration: " + iteration);
            
            // Assign each node to a partition id
            JavaPairRDD<NodePartition<T>, NeighborList> graph2 = 
                    graph.mapPartitionsToPair(
                            new AssignFunction(medoids),
                            true);
                    

            // Partition
            graph2 = graph2.partitionBy(new NodePartitioner(partitions));
            graph2.cache();

            
            // Compute new centers
            JavaRDD<Node<T>> new_medoids = graph2.mapPartitions(
                    new FlatMapFunction<Iterator<Tuple2<NodePartition<T>, NeighborList>>, Node<T>>() {

                public Iterable<Node<T>> 
                    call(Iterator<Tuple2<NodePartition<T>, NeighborList>> t) 
                            throws Exception {
                        
                    // Build the partition
                    Graph partition = new Graph();
                    while (t.hasNext()) {
                        Tuple2<NodePartition<T>, NeighborList> tuple = t.next();
                        partition.put(tuple._1().node, tuple._2());
                    }

                    if (partition.size() == 0) {
                        return new ArrayList<Node<T>>();
                    }

                    // This partition might contain multiple subgraphs => find largest subgraph
                    ArrayList<Graph<T>> stronglyConnectedComponents = partition.stronglyConnectedComponents();
                    int largest_subgraph_size = 0;
                    Graph<T> largest_subgraph = stronglyConnectedComponents.get(0);
                    for (Graph<T> subgraph : stronglyConnectedComponents) {
                        if (subgraph.size() > largest_subgraph_size) {
                            largest_subgraph = subgraph;
                            largest_subgraph_size = subgraph.size();
                        }
                    }

                    int largest_distance = Integer.MAX_VALUE;
                    Node medoid = (Node) largest_subgraph.keySet().iterator().next();
                    for (Node n : largest_subgraph.keySet()) {
                        //Node n = (Node) o;
                        Dijkstra dijkstra = new Dijkstra(largest_subgraph, n);

                        int node_largest_distance = dijkstra.getLargestDistance();

                        if (node_largest_distance == 0) {
                            continue;
                        }

                        if (node_largest_distance < largest_distance) {
                            largest_distance = node_largest_distance;
                            medoid = n;
                        }
                    }
                    ArrayList<Node<T>> list = new ArrayList<Node<T>>(1);
                    list.add(medoid);
                    
                    return list;
                }
            });
            medoids = new_medoids.collect();
        }

        return graph;
    }

    private static class NodePartitioner extends Partitioner {
        private final int partitions;

        public NodePartitioner(int partitions) {
            this.partitions = partitions;
        }

        @Override
        public int numPartitions() {
            return partitions;
        }

        @Override
        public int getPartition(Object node_partition) {
            return ((NodePartition) node_partition).partition % partitions;
        }
    }

    private  class AssignFunction
        implements PairFlatMapFunction<Iterator<Tuple2<Node<T>, NeighborList>>, NodePartition<T>, NeighborList> {
        private final List<Node<T>> medoids;
        

        public AssignFunction(List<Node<T>> medoids) {
            this.medoids = medoids;
        }
        
        public Iterable<Tuple2<NodePartition<T>, NeighborList>> 
            call(Iterator<Tuple2<Node<T>, NeighborList>> iterator) 
                    throws Exception {


            // fetch all tuples in this partition 
            // to compute the partition_constraint
            ArrayList<Tuple2<Node<T>, NeighborList>> partition_tuples = 
                    new ArrayList<Tuple2<Node<T>, NeighborList>>();

            while (iterator.hasNext()) {
                partition_tuples.add(iterator.next());
            }

            // this could be estimated with total_n / partitions
            int partition_n = partition_tuples.size();
            int partition_constraint = (int) (imbalance * partition_n / partitions);
            int[] partitions_size = new int[partitions];
            ArrayList<Tuple2<NodePartition<T>, NeighborList>> result = 
                    new ArrayList<Tuple2<NodePartition<T>, NeighborList>>(partition_n);

            for (Tuple2<Node<T>, NeighborList> tuple : partition_tuples) {
                double[] similarities = new double[partitions];
                double[] values = new double[partitions];

                // 1. similarities
                for (int center_id = 0; center_id < partitions; center_id++) {
                    similarities[center_id] = similarity.similarity(
                            medoids.get(center_id).value,
                            tuple._1.value);
                }
                
                // 2. value to maximize = similarity * (1 - cluster_size / capacity_constraint)
                for (int center_id = 0; center_id < partitions; center_id++) {
                    values[center_id] = similarities[center_id] *
                            (1 - partitions_size[center_id] / partition_constraint);
                }
                
                // 3. choose partition that minimizes compute value
                int partition = argmax(values);
                partitions_size[partition]++;
                result.add(new Tuple2<NodePartition<T>, NeighborList>(
                        new NodePartition<T>(tuple._1, partition), 
                        tuple._2));
            }
            
            return result;

        }
            
        
    }

    
    private static int argmax(double[] values) {
        double max_value = -1.0 * Double.MAX_VALUE;
        ArrayList<Integer> ties = new ArrayList<Integer>();
        
        for (int i = 0; i < values.length; i++) {
            if (values[i] > max_value) {
                max_value = values[i];
                ties = new ArrayList<Integer>();
                ties.add(i);
                
            } else if(values[i] == max_value) {
                // add a tie
                ties.add(i);
            }
        }
        
        if (ties.size() == 1) {
            return ties.get(0);
        }
        
        Random rand = new Random();
        return ties.get(rand.nextInt(ties.size()));
    }
}

/**
 * Wraps a node and a corresponding partition id
 * @author Thibault Debatty
 */
class NodePartition<T> implements Serializable {
    public Node<T> node;
    public int partition;
    
    public NodePartition(Node<T> node, int partition) {
        this.node = node;
        this.partition = partition;
    }
}