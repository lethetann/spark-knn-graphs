/*
 * The MIT License
 *
 * Copyright 2017 tibo.
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
package partitioning.fish;

import info.debatty.jinu.Case;
import info.debatty.jinu.TestFactory;
import info.debatty.jinu.TestInterface;
import java.util.Arrays;
import java.util.List;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;

/**
 *
 * @author tibo
 */
public class TestCase {

    /**
     * @param args the command line arguments
     * @throws java.lang.Exception if anything goes wrong
     */
    public static void main(final String[] args) throws Exception {

        OptionParser parser = new OptionParser("t:r:d:");
        OptionSet options = parser.parse(args);
        List<String> time_list = (List<String>) options.valuesOf("t");
        double[] similarities = new double[time_list.size()];
        for (int i = 0; i < similarities.length; i++) {
            similarities[i] = Double.valueOf(time_list.get(i));
        }

        JaBeJaTest.dataset_path = (String) options.valueOf("d");
        KMedoidsTest.dataset_path = (String) options.valueOf("d");
        Edg1DTest.dataset_path = (String) options.valueOf("d");

        // Reduce Spark output logs
        Logger.getLogger("org").setLevel(Level.WARN);
        Logger.getLogger("akka").setLevel(Level.WARN);

        Case test = new Case();
        test.setDescription(TestCase.class.getName() + " : "
                + String.join(" ", Arrays.asList(args)));
        test.setIterations(20);
        test.setParallelism(1);
        test.commitToGit(false);
        test.setBaseDir((String) options.valueOf("r"));
        test.setParamValues(similarities);

        test.addTest(new TestFactory() {
            @Override
            public TestInterface newInstance() {
                return new JaBeJaTest();
            }
        });

        test.addTest(new TestFactory() {
            @Override
            public TestInterface newInstance() {
                return new KMedoidsTest();
            }
        });

        test.addTest(new TestFactory() {
            @Override
            public TestInterface newInstance() {
                return new Edg1DTest();
            }
        });

        test.run();

    }
}
