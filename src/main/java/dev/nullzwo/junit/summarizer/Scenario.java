package dev.nullzwo.junit.summarizer;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.params.ParameterizedTest;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * Markiert einzelne Tests(Methoden) oder Container(u.a. Testklassen, ParameterizedTests) als direkte Szenarien.
 * <br><br><b>Anwendungsfälle:</b>
 * <ul>
 *     <li>Um aus einzelne Methoden einer Testklasse einen Report zu erstellen. Testklasse braucht keine
 *     {@link Feature} Annotation.</li>
 *     <li>Um eine {@link Nested} Testklasse oder {@link ParameterizedTest} nicht als Subfeature sondern als Szenario
 *     im Report aufzulisten</li>
 * </ul>
 *
 * @see Feature
 */
@net.jqwik.api.Tag(Scenario.TAG_VALUE)
@org.junit.jupiter.api.Tag(Scenario.TAG_VALUE)
@Retention(RUNTIME)
@Target({METHOD, TYPE})
public @interface Scenario {
    String TAG_VALUE = "scenario";
}
