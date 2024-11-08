package dev.nullzwo.junit.summarizer;

import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.AbstractMap;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toList;
import static org.junit.platform.engine.TestExecutionResult.Status.ABORTED;

public class FeaturesGeneratorListener implements TestExecutionListener {
    public static final String REPORT_FOLDER = "src/test/features/";
    public static final Pattern UNLINKED_JIRA_KEY = Pattern.compile("([A-Z]{3,}-\\d{3,})(?!\\])");
    public static final String VAM_JIRA_URL = "http://jira/browse/";

    Set<TestIdentifier> aborted = new HashSet<>();

    @Override
    public void executionFinished(TestIdentifier testIdentifier, TestExecutionResult testExecutionResult) {
        if (testExecutionResult.getStatus() == ABORTED) {
            aborted.add(testIdentifier);
        }
    }

    @Override
    public void testPlanExecutionFinished(TestPlan testPlan) {
        var reports = createReports(testPlan);

        // different display text may result in the same file name
        var filesAndContent = reports.stream()
                                     .collect(groupingBy(
                                             t -> t._1.toLowerCase().replaceAll("[^a-z0-9]+", "-"),
                                             Collectors.mapping(l -> l._2, toList())
                                     ));

        for (Entry<String, List<String>> fc : filesAndContent.entrySet()) {
            try {
                var files = zipWithIndex(fc.getValue().stream()).map(entry ->
                                                                             new Tuple2(
                                                                                     Paths.get(REPORT_FOLDER +
                                                                                               fc.getKey() +
                                                                                               (entry.getKey() > 0 ?
                                                                                                       "_" +
                                                                                                       (entry.getKey() +
                                                                                                        1) : "") +
                                                                                               ".md"),
                                                                                     entry.getValue()
                                                                             )
                ).collect(toList());

                for (Tuple2<Path, String> report : files) {
                    Files.createDirectories(report._1.getParent());
                    Files.writeString(report._1, report._2, UTF_8);
                }
            } catch (IOException ex) {
                throw new RuntimeException(ex);
            }
        }
    }

    /**
     * Converts an {@link java.util.Iterator} to {@link java.util.stream.Stream}.
     */
    public static <T> Stream<T> iterate(Iterator<? extends T> iterator) {
        int characteristics = Spliterator.ORDERED | Spliterator.IMMUTABLE;
        return StreamSupport.stream(Spliterators.spliteratorUnknownSize(iterator, characteristics), false);
    }

    /**
     * Zips the specified stream with its indices.
     */
    public static <T> Stream<Map.Entry<Integer, T>> zipWithIndex(Stream<? extends T> stream) {
        return iterate(new Iterator<>() {
            private final Iterator<? extends T> streamIterator = stream.iterator();
            private int index = 0;

            @Override
            public boolean hasNext() {
                return streamIterator.hasNext();
            }

            @Override
            public Map.Entry<Integer, T> next() {
                return new AbstractMap.SimpleImmutableEntry<>(index++, streamIterator.next());
            }
        });
    }

    static class Tuple2<A, B> {
        public final A _1;
        public final B _2;

        Tuple2(A a, B b) {
            _1 = a;
            _2 = b;
        }
    }

    Collection<Tuple2<String, String>> createReports(TestPlan testPlan) {
        var algs = new Algebras(testPlan, aborted);

        var features = testPlan.getRoots().stream().flatMap(id -> algs.findFeatures.apply(id).stream())
                               .collect(toList());
        var descriptions = features.stream().map(FixDescription.unfold(algs.createDescription)).collect(toList());

        var fakeRootDescription = new FixDescription(new FeatureDescriptor<>(null, List.of(), descriptions));
        var filterThenMerge = FixDescription.unfold(algs.filterAbortedCoalg)
                                            .andThen(FixDescription.unfold(algs.mergeCoalg));
        var processed = filterThenMerge.apply(fakeRootDescription).unfix.subFeatures;

        return processed.stream().map(
                                fix -> new Tuple2<>(fix.unfix.getDisplayName(), FixDescription.fold(algs.showDescription).apply(fix)))
                        .collect(toList());
    }

    static String toDisplayName(TestIdentifier tid) {
        var disp = tid.getDisplayName();
        if(!disp.endsWith("()")) {
            return disp;
        }

        var b = new StringBuilder();
        for (char c : disp.toCharArray()) {
            if('A' <= c && c <= 'Z') {
                b.append(' ').append(Character.toLowerCase(c));
            } else if(Character.isLetterOrDigit(c)) {
                b.append(c);
            }
        }
        return b.toString();
    }

    /**
     * Data structure describing a business feature.
     *
     * @param <A> type of recursion
     */
    static class FeatureDescriptor<A> {
        public final Collection<TestIdentifier> tids;
        public final Collection<TestIdentifier> szenarios;
        public final Collection<A> subFeatures;

        FeatureDescriptor(Collection<TestIdentifier> tids, Collection<TestIdentifier> szenarios,
                          Collection<A> subFeatures) {
            this.tids = tids;
            this.szenarios = szenarios;
            this.subFeatures = subFeatures;
        }

        String getDisplayName() {
            return toDisplayName(tids.stream().findFirst().get());
        }

        <B> FeatureDescriptor<B> map(Function<A, B> f) {
            return new FeatureDescriptor<>(tids, szenarios,
                                           subFeatures.stream().map(f).collect(toList()));
        }

        public Collection<TestIdentifier> getSzenarios() {
            return szenarios;
        }

        public Collection<A> getSubFeatures() {
            return subFeatures;
        }
    }

    /**
     * Fixpoint datastructure (f-structure) for the recursive data type {@link FeatureDescriptor}
     */
    static class FixDescription {
        public final FeatureDescriptor<FixDescription> unfix;

        FixDescription(FeatureDescriptor<FixDescription> unfix) {
            this.unfix = unfix;
        }

        /**
         * Creates a catamorphism, which deconstructs a f-structure level-by-level and applies the algebra.<br>
         * <p>
         * Example: sum a tree algebra (a, b) -> a + b<br>
         * (((1,3),5),(4,(3,2))) -> ((4,5),(4,(3,2))) -> (9,(4,(3,2))) -> (9,(4,5)) -> (9,9) -> 18
         *
         * @param f
         * @param <A>
         * @return
         */
        static <A> Function<FixDescription, A> fold(DescAlgebra<A> f) {
            return ft -> f.apply(ft.unfix.map(fold(f)));
        }

        /**
         * Creates a anamorphism, which constructs a f-structure level-by-level, starting with a seed and
         * repeatedly applying the coalgebra.<br>
         * <p>
         * Example: split words<br>
         * 'The quick brown fox jumps' -> ('The quick brown', 'fox jumps') -> (('The quick', 'brown'), 'fox jumps') ->
         * ((('The', 'quick'), 'brown'), 'fox jumps') -> ((('The', 'quick'), 'brown'), ('fox', 'jumps'))
         *
         * @param f
         * @param <A>
         * @return
         */
        static <A> Function<A, FixDescription> unfold(DescCoAlgebra<A> f) {
            return a -> new FixDescription(f.apply(a).map(unfold(f)));
        }
    }

    interface DescAlgebra<A> {
        A apply(FeatureDescriptor<A> descr);
    }

    interface DescCoAlgebra<A> {
        FeatureDescriptor<A> apply(A seed);
    }

    interface FeatureNode<A> extends Iterable<A> {
        <B> FeatureNode<B> map(Function<A, B> f);
    }

    static class FeatureLeafNode<A> implements FeatureNode<A> {
        private final TestIdentifier id;

        FeatureLeafNode(TestIdentifier id) {
            this.id = id;
        }

        @Override
        public <B> FeatureNode<B> map(Function<A, B> f) {
            return new FeatureLeafNode<>(id);
        }

        @Override
        public Iterator<A> iterator() {
            return List.<A>of().iterator();
        }

        public TestIdentifier getId() {
            return id;
        }
    }

    static class FeatureBranchNode<A> implements FeatureNode<A> {
        public final Collection<A> children;

        FeatureBranchNode(Collection<A> children) {
            this.children = children;
        }

        @Override
        public <B> FeatureNode<B> map(Function<A, B> f) {
            return new FeatureBranchNode<>(children.stream().map(f).collect(toList()));
        }

        @Override
        public Iterator<A> iterator() {
            return children.iterator();
        }
    }

    /**
     * Data structure which represents the tree of the test plan up until the first feature container
     *
     * @param <A> type of recursion
     */
    static class FeatureTree<A> {
        /**
         * Left are further tree nodes. Right are tree leafs.
         */
        public final FeatureNode<A> leafOrNodes;

        FeatureTree(FeatureNode<A> leafOrNodes) {
            this.leafOrNodes = leafOrNodes;
        }

        <B> FeatureTree<B> map(Function<A, B> f) {
            return new FeatureTree<>(leafOrNodes.map(f));
        }

        public FeatureNode<A> getLeafOrNodes() {
            return leafOrNodes;
        }
    }

    /**
     * Creates a hylomorphism, which omits creating the intermediate structure and
     * immediately applies the algebra to the results of the coalgebra
     * <p>
     * Example: sum the length of words in a sentence
     * <p>
     * 'The quick brown' -> ('The quick', 'brown') -> (('The', 'quick'), 'brown') ->
     * ((3, 5), 'brown') -> (8, 'brown') -> (8, 5) -> 13
     *
     * @param coAlg
     * @param alg
     * @param <A>
     * @param <B>
     * @return
     */
    public static <A, B> Function<A, B> refold(TreeAlgebra<B> alg, TreeCoAlgebra<A> coAlg) {
        return a -> alg.apply(coAlg.apply(a).map(refold(alg, coAlg)));
    }

    public interface TreeAlgebra<A> {
        A apply(FeatureTree<A> tree);
    }

    public interface TreeCoAlgebra<A> {
        FeatureTree<A> apply(A value);
    }
}
