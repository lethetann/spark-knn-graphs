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

package info.debatty.spark.knngraphs.builder;

import info.debatty.java.datasets.gaussian.Dataset;
import info.debatty.java.graphs.Neighbor;
import info.debatty.java.graphs.NeighborList;
import info.debatty.spark.knngraphs.JWSimilarity;
import info.debatty.spark.knngraphs.L2Similarity;
import info.debatty.spark.knngraphs.KNNGraphCase;
import info.debatty.spark.knngraphs.Node;
import java.io.File;
import java.io.IOException;
import java.util.List;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import scala.Tuple2;

/**
 *
 * @author Thibault Debatty
 */
public class BruteTest extends KNNGraphCase {

    private static final int K = 10;

    /**
     * Build the exact SPAM graph.
     * @throws IOException if we cannot read spam file
     * @throws Exception if we cannot build the graph
     */
    public final void testBuildSpamGraph() throws IOException, Exception {
        System.out.println("Build SPAM graph");
        System.out.println("================");

        JavaRDD<String> nodes = readSpam();

        Brute brute = new Brute();
        brute.setK(K);
        brute.setSimilarity(new JWSimilarity());

        // Compute the graph and force execution
        JavaPairRDD<Node<String>, NeighborList> graph =
                brute.computeGraph(nodes);
        graph.count();

        // Save to disk
        File temp = File.createTempFile("graph-spam-", "");
        temp.delete();
        graph = graph.coalesce(1);
        graph.saveAsObjectFile(temp.getAbsolutePath());
        System.out.println("Graph saved to " + temp.getAbsolutePath());

        List<Tuple2<Node<String>, NeighborList>> local_graph = graph.collect();

        assertEquals(726, graph.count());
        for (Tuple2<Node<String>, NeighborList> tuple : local_graph) {
            // Check each node has 10 neighbors
            assertEquals(K, tuple._2.size());

            // Check wether a node receives himself as neighbor...
            Node<String> node = tuple._1;
            for (Neighbor neighbor : tuple._2) {
                assertTrue(!node.equals(neighbor.getNode()));
            }
        }
    }

    /**
     * Build a synthetic graph.
     * @throws Exception if we cannot build the graph
     */
    public final void testBuildSyntheticGraph() throws Exception {
        System.out.println("Build synthetic graph");
        System.out.println("=====================");

        JavaSparkContext sc = getSpark();

        Dataset dataset = new Dataset.Builder(10, 13)
                .setOverlap(Dataset.Builder.Overlap.HIGH)
                .setSize(10000)
                .build();

        // Parallelize the dataset in Spark
        JavaRDD<double[]> nodes = sc.parallelize(dataset.getAll());

        // Build the graph
        Brute<double[]> builder = new Brute<>();
        builder.setK(K);
        builder.setSimilarity(new L2Similarity());
        JavaPairRDD<Node<double[]>, NeighborList> graph =
                builder.computeGraph(nodes);

        graph.count();

        // Save to disk
        File temp = File.createTempFile("graph-synthetic-", "");
        temp.delete();
        graph = graph.coalesce(1);
        graph.saveAsObjectFile(temp.getAbsolutePath());
        System.out.println("Graph saved to " + temp.getAbsolutePath());
    }
}


