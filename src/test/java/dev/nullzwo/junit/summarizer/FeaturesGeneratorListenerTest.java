package dev.nullzwo.junit.summarizer;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.platform.engine.ConfigurationParameters;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.TestTag;
import org.junit.platform.engine.UniqueId;
import org.junit.platform.engine.support.descriptor.AbstractTestDescriptor;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;

import static java.util.stream.Collectors.toSet;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.platform.engine.TestDescriptor.Type.CONTAINER;
import static org.junit.platform.engine.TestDescriptor.Type.TEST;

@Feature
@DisplayName("APP-123 basic functionality of summarizer")
class FeaturesGeneratorListenerTest {
    Algebras algebras;
    TestPlan testPlan;

    @Nested
    @DisplayName("does find all defined features")
    class FindFeatures {

        @Test
        void shouldSummarizeTestContainersTaggedAsFeatures() {
            initPlan(feature("foo", "disp"));
            var tree = algebras.findFeatures.apply(headTid());

            assertThat(tree).first()
                            .extracting(TestIdentifier::getDisplayName)
                            .isEqualTo("disp");
        }

        @Test
        @DisplayName("should not summarize tests tagged as features")
        void doesNotFindTaggedFeatureTest() {
            initPlan(test("foo", "disp", Feature.TAG_VALUE));
            var tree = algebras.findFeatures.apply(headTid());

            assertThat(tree).isEmpty();
        }

        @Test
        @DisplayName("should not summarize container tagged as features but also as scenario")
        void doesNotFindTaggedFeatureWhenAlsoSzenario() {
            initPlan(container("foo", "disp", Feature.TAG_VALUE, Scenario.TAG_VALUE));
            var tree = algebras.findFeatures.apply(headTid());

            assertThat(tree).isEmpty();
        }

        @Test
        void doesNotFindTaggedFeatureWhenAlsoSzenarioAndSzenarioChildren() {
            var parent = container("foo", "disp", Feature.TAG_VALUE, Scenario.TAG_VALUE);
            parent.addChild(test("bar", "Bar", Scenario.TAG_VALUE));
            initPlan(parent);
            var tree = algebras.findFeatures.apply(headTid());

            assertThat(tree).isEmpty();
        }

        @Test
        @DisplayName("should summarize untagged container with child tagged as scenario")
        void findsContainerWithTaggedScenarioTest() {
            var parent = container("p", "parent");
            parent.addChild(szenario("foo", "Foo"));
            initPlan(parent);

            var tree = algebras.findFeatures.apply(headTid());

            assertThat(tree).first()
                            .extracting(TestIdentifier::getDisplayName)
                            .isEqualTo("parent");
        }

        @Test
        void shouldSearchInChildren() {
            var parent = container("p", "parent");
            parent.addChild(container("foo", "Foo"));
            parent.addChild(feature("bar", "Bar"));
            initPlan(parent);

            var tree = algebras.searchToplevelFeatures.apply(headTid());

            assertThat(tree.getLeafOrNodes()).extracting(TestIdentifier::getDisplayName)
                                             .contains("Foo", "Bar");
        }

        @Test
        void shouldNotSearchInChildrenOfScenarios() {
            var parent = container("p", "parent", Scenario.TAG_VALUE);
            parent.addChild(container("foo", "Foo"));
            parent.addChild(feature("bar", "Bar"));
            initPlan(parent);

            var tree = algebras.searchToplevelFeatures.apply(headTid());

            assertThat(tree.getLeafOrNodes()).isEmpty();
        }
    }

    @Nested
    @DisplayName("aggregates feature description")
    class CreateDescription {

        @Test
        @DisplayName("should aggregate all child tests of feature as scenarios")
        void ifContainerIsFeatureThenAllTestsAreScenarios() {
            var parent = feature("p", "parent");
            parent.addChild(test("foo", "Foo"));
            parent.addChild(test("bar", "Bar"));
            initPlan(parent);
            var szenarios = algebras.createDescription.apply(headTid()).getSzenarios();
            assertThat(szenarios).extracting(TestIdentifier::getDisplayName).contains("Foo", "Bar");
        }

        @Test
        @DisplayName("should aggregate only child tests that are tagged as scenario if container untagged")
        void ifContainerIsNoFeatureThenCollectTaggedScenarios() {
            var parent = container("p", "parent");
            parent.addChild(test("foo", "Foo"));
            parent.addChild(szenario("bar", "Bar"));
            initPlan(parent);
            var szenarios = algebras.createDescription.apply(headTid()).getSzenarios();
            assertThat(szenarios).extracting(TestIdentifier::getDisplayName).contains("Bar");
        }

        @Test
        void scenarioContainerAreScenarios() {
            var parent = container("p", "parent");
            parent.addChild(container("foo", "Foo", Scenario.TAG_VALUE, Feature.TAG_VALUE));
            initPlan(parent);
            var szenarios = algebras.createDescription.apply(headTid()).getSzenarios();
            assertThat(szenarios).extracting(TestIdentifier::getDisplayName).contains("Foo");
        }

        @Test
        @DisplayName("should not identify scenarios as sub features")
        void scenarioContainerAreNoSubFeatures() {
            var parent = feature("p", "parent");
            parent.addChild(container("foo", "Foo", Scenario.TAG_VALUE, Feature.TAG_VALUE));
            initPlan(parent);
            var subfeatures = algebras.createDescription.apply(headTid()).getSubFeatures();
            assertThat(subfeatures).isEmpty();
        }

        @Test
        void shouldAggregateChildContainersAsSubFeatures() {
            var parent = feature("p", "parent");
            parent.addChild(feature("foo", "Foo"));
            initPlan(parent);
            var subfeatures = algebras.createDescription.apply(headTid()).getSubFeatures();
            assertThat(subfeatures).extracting(TestIdentifier::getDisplayName).contains("Foo");
        }
    }

    TestIdentifier headTid() {
        return testPlan.getRoots().iterator().next();
    }

    TestDescriptor container(String uniqueId, String displayName, String... tags) {
        return new TestTestDescriptor(CONTAINER, uniqueId, displayName, tags);
    }

    TestDescriptor test(String uniqueId, String displayName, String... tags) {
        return new TestTestDescriptor(TEST, uniqueId, displayName, tags);
    }

    TestDescriptor feature(String uniqueId, String displayName) {
        return container(uniqueId, displayName, Feature.TAG_VALUE);
    }

    TestDescriptor szenario(String uniqueId, String displayName) {
        return test(uniqueId, displayName, Scenario.TAG_VALUE);
    }

    void initPlan(TestDescriptor... descriptors) {
        testPlan = TestPlan.from(List.of(descriptors), new ConfigurationParameters() {
            @Override
            public Optional<String> get(String key) {
                return Optional.empty();
            }

            @Override
            public Optional<Boolean> getBoolean(String key) {
                return Optional.empty();
            }

            @Override
            public int size() {
                return 0;
            }

            @Override
            public Set<String> keySet() {
                return Set.of();
            }
        });
        algebras = new Algebras(testPlan, Set.of());
    }

    static class TestTestDescriptor extends AbstractTestDescriptor {
        private final Type type;
        private final Set<TestTag> tags;

        public TestTestDescriptor(Type type, String uniqueId, String displayName, String... tags) {
            super(UniqueId.forEngine("test").append("method", uniqueId), displayName);
            this.type = type;
            this.tags = Stream.of(tags).map(TestTag::create).collect(toSet());
        }

        @Override
        public Set<TestTag> getTags() {
            return tags;
        }

        @Override
        public Type getType() {
            return type;
        }

        @Override
        public String toString() {
            return "TestTestDescriptor{" +
                   "tags=" + tags +
                   ", type=" + type +
                   '}';
        }
    }
}
