package dev.nullzwo.junit.summarizer;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import org.junit.jupiter.api.DisplayName;
import net.jqwik.api.Label;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * Markiert eine Testklasse als Container für fachliche Akzeptanzkriterien. Für jeden so markierten Testcontainer wird
 * ein Feature-Report generiert, der alle getesteten Kriterien (Testmethoden) auflistet.
 * <p>
 * Damit der Report ausführliche Beschreibungen der Fachlichkeit enthält, sollten alle Testmethoden und Testcontainer
 * mit enstprechenden Text Annotationen versehen werden.
 * <p>
 * Für JUnit Tests benötigt man {@link DisplayName} und für Jqwik Tests nutzt man {@link Label}.
 */
@net.jqwik.api.Tag(Feature.TAG_VALUE)
@org.junit.jupiter.api.Tag(Feature.TAG_VALUE)
@Retention(RUNTIME)
@Target(ElementType.TYPE)
public @interface Feature {
    String TAG_VALUE = "feature";
}
