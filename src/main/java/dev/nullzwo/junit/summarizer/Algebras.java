package dev.nullzwo.junit.summarizer;

import dev.nullzwo.junit.summarizer.FeaturesGeneratorListener.DescAlgebra;
import dev.nullzwo.junit.summarizer.FeaturesGeneratorListener.DescCoAlgebra;
import dev.nullzwo.junit.summarizer.FeaturesGeneratorListener.FeatureBranchNode;
import dev.nullzwo.junit.summarizer.FeaturesGeneratorListener.FeatureDescriptor;
import dev.nullzwo.junit.summarizer.FeaturesGeneratorListener.FeatureLeafNode;
import dev.nullzwo.junit.summarizer.FeaturesGeneratorListener.FeatureTree;
import dev.nullzwo.junit.summarizer.FeaturesGeneratorListener.FixDescription;
import dev.nullzwo.junit.summarizer.FeaturesGeneratorListener.TreeAlgebra;
import dev.nullzwo.junit.summarizer.FeaturesGeneratorListener.TreeCoAlgebra;
import org.junit.platform.engine.TestTag;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static dev.nullzwo.junit.summarizer.FeaturesGeneratorListener.UNLINKED_JIRA_KEY;
import static dev.nullzwo.junit.summarizer.FeaturesGeneratorListener.VAM_JIRA_URL;
import static dev.nullzwo.junit.summarizer.FeaturesGeneratorListener.toDisplayName;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toSet;

public class Algebras {
    private final TestPlan testPlan;
    private final Set<TestIdentifier> aborted;

    public Algebras(TestPlan testPlan, Set<TestIdentifier> aborted) {
        this.testPlan = testPlan;
        this.aborted = aborted;
    }

    public static boolean isFeature(TestIdentifier id) {
        return id.getType().isContainer() &&
               id.getTags().contains(TestTag.create(Feature.TAG_VALUE)) &&
               !isSzenario.test(id);
    }

    public static Predicate<TestIdentifier> isTest = id -> id.getType().isTest();
    public static Predicate<TestIdentifier> isSzenario = id -> id.getTags()
                                                                 .contains(TestTag.create(Scenario.TAG_VALUE));

    public TestPlan getTestPlan() {
        return testPlan;
    }

    public Set<TestIdentifier> getAborted() {
        return aborted;
    }

    /**
     * Coalgebra to build a Tree, where the leafs are the first test container encountered in the test plan,
     * which are tagged as features or contains tests tagged as scenarios.
     */
    public TreeCoAlgebra<TestIdentifier> searchToplevelFeatures = tid -> {
        var childs = getTestPlan().getChildren(tid);
        var hasSzenarios = childs.stream().anyMatch(isSzenario);
        var isFeature = isFeature(tid) || (!isSzenario.test(tid) && hasSzenarios);

        return new FeatureTree<>(isFeature ?
                                         new FeatureLeafNode<>(tid) :
                                         new FeatureBranchNode<>(isSzenario.test(tid) ? List.of() : childs));
    };

    /**
     * Algebra which collects all found toplevel features(leafs) from the tree into a list.
     */
    public TreeAlgebra<Collection<TestIdentifier>> collectFeatures = tree -> {
        if (tree.leafOrNodes instanceof FeatureLeafNode) {
            var leaf = ((FeatureLeafNode<Collection<TestIdentifier>>) tree.leafOrNodes);
            return List.of(leaf.getId());
        }
        var branches = ((FeatureBranchNode<Collection<TestIdentifier>>) tree.leafOrNodes);
        return branches.children.stream().flatMap(Collection::stream).collect(toList());
    };

    /**
     * find all features contained in a root test container
     */
    public Function<TestIdentifier, Collection<TestIdentifier>> findFeatures = FeaturesGeneratorListener.refold(
            collectFeatures,
            searchToplevelFeatures);

    /**
     * Coalgebra which builds the description of a feature from it's test container
     */
    public DescCoAlgebra<TestIdentifier> createDescription = tid -> {
        var childs = getTestPlan().getChildren(tid);
        var direct = childs.stream().filter(isSzenario).collect(toSet());
        var withTests = childs.stream().filter(isTest).collect(toSet());
        withTests.addAll(direct);
        var szenarios = isFeature(tid) ? withTests : direct;
        var subfeatures = childs.stream().flatMap(c -> findFeatures.apply(c).stream()).collect(toSet());

        return new FeatureDescriptor<>(List.of(tid),
                                       szenarios,
                                       subfeatures);
    };

    private <A> Optional<Collection<A>> noneIfEmpty(Collection<A> collection) {
        return collection.isEmpty() ? Optional.empty() : Optional.of(collection);
    }

    /**
     * Algebra which creates a string representation of a feature description
     */
    public DescAlgebra<String> showDescription = descr -> {
        var subFeatureReports = descr.subFeatures.stream().map(r -> r.replaceAll("#(?!#)", "##")).sorted()
                                                 .collect(toList());

        Function<String, String> escape = t -> t.replaceAll("\n", "\\\\n")
                                                .replaceAll("\t", "\\\\t");

        var sources = descr.tids.stream().map(tid -> {
            var uid = tid.getUniqueId();
            var source = uid.substring(uid.lastIndexOf("/") + 1);
            source = source.substring(1, source.length() - 1);
            if (source.startsWith("class:")) {
                var clazz = source.substring(source.indexOf(":") + 1);
                var clazzName = clazz.substring(clazz.lastIndexOf('.') + 1);
                var clazzSrc = clazz.substring(0, clazz.length() - 1).replaceAll("\\.", "/");
                source = "[" + clazzName + "](../java/" + clazzSrc + ".java)";
            }
            return source;
        }).collect(joining(", "));

        var header = "# " + descr.getDisplayName() + "\n<small><small>" + sources + "</small></small>";

        var szenarioTexts = descr.szenarios.stream().map(FeaturesGeneratorListener::toDisplayName).map(escape).sorted()
                                           .collect(toList());
        var report = Stream.of(Optional.of(header),
                               noneIfEmpty(szenarioTexts).map(s -> s.stream().collect(joining("\n- ", "- ", ""))),
                               noneIfEmpty(subFeatureReports).map(s -> s.stream().collect(joining("\n\n")))
        ).flatMap(Optional::stream).collect(joining("\n\n"));

        return UNLINKED_JIRA_KEY.matcher(report)
                                .replaceAll(res -> "[" + res.group(1) + "](" +
                                                   VAM_JIRA_URL +
                                                   res.group(1) + ")");
    };

    /**
     * An algebra which represents a transformation of the f-structure.
     * The transformation merges sub features of the same name into one.
     */
    public DescCoAlgebra<FixDescription> mergeCoalg = fixDescr -> {
        var descr = fixDescr.unfix;
        var mergedSubs = descr.subFeatures.stream().map(fix -> fix.unfix)
                                          .collect(groupingBy(fix -> fix.getDisplayName()))
                                          .values()
                                          .stream()
                                          .map(ds -> {
                                              return new FeatureDescriptor(
                                                      ds.stream().flatMap(d -> d.tids.stream()).collect(toList()),
                                                      ds.stream().flatMap(d -> d.szenarios.stream())
                                                        .collect(toList()),
                                                      ds.stream().flatMap(d -> d.subFeatures.stream())
                                                        .collect(toList())
                                              );
                                          }).map(FixDescription::new)
                                          .collect(toList());
        return new FeatureDescriptor<>(descr.tids, descr.szenarios, mergedSubs);
    };

    public DescCoAlgebra<FixDescription> filterAbortedCoalg = fixDescr -> {
        var descr = fixDescr.unfix;
        var filteredSzenarios = descr.szenarios.stream().filter(sz -> !getAborted().contains(sz)).collect(toList());
        var filteredSubs = descr.subFeatures.stream()
                                            .filter(sf -> !sf.unfix.tids.stream().anyMatch(getAborted()::contains))
                                            .collect(toList());
        return new FeatureDescriptor<>(descr.tids, filteredSzenarios, filteredSubs);
    };
}
