package app.capgo.webviewversionchecker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

public class WebviewVersionCheckerTest {

    private WebviewVersionChecker checker;

    @Before
    public void setUp() {
        checker = new WebviewVersionChecker();
    }

    @Test
    public void minimumMajorVersion_150_vs_151_fails() {
        WebviewVersionChecker.CompatibilityEvaluation evaluation = checker.evaluateCompatibility(
            "150.0.6998.135",
            150,
            "151",
            151,
            0.0,
            0.00965
        );

        assertNotNull(evaluation);
        assertEquals("outdated", evaluation.state);
        assertFalse(evaluation.isLatest);
    }

    @Test
    public void minimumMajorVersion_151_vs_151_passes() {
        WebviewVersionChecker.CompatibilityEvaluation evaluation = checker.evaluateCompatibility(
            "151.0.6998.135",
            151,
            "151",
            151,
            0.0,
            0.5
        );

        assertNotNull(evaluation);
        assertEquals("latest", evaluation.state);
        assertTrue(evaluation.isLatest);
    }

    @Test
    public void minimumMajorVersion_152_vs_151_passes() {
        WebviewVersionChecker.CompatibilityEvaluation evaluation = checker.evaluateCompatibility(
            "152.0.6998.135",
            152,
            "151",
            151,
            0.0,
            0.5
        );

        assertNotNull(evaluation);
        assertEquals("latest", evaluation.state);
        assertTrue(evaluation.isLatest);
    }

    @Test
    public void minimumMajorVersion_enforced_before_device_share_threshold() {
        WebviewVersionChecker.CompatibilityEvaluation evaluation = checker.evaluateCompatibility(
            "150.0.6998.135",
            150,
            null,
            151,
            0.0,
            99.0
        );

        assertNotNull(evaluation);
        assertEquals("outdated", evaluation.state);
        assertFalse(evaluation.isLatest);
    }

    @Test
    public void compareVersions_treats_150_as_below_151() {
        assertTrue(checker.compareVersions("150.0.6998.135", "151") < 0);
    }

    @Test
    public void shouldResolveLatestVersion_false_when_minimum_major_floor_fails() {
        assertFalse(checker.shouldResolveLatestVersion(150, 151, 0.0, 0.00965));
    }

    @Test
    public void shouldResolveLatestVersion_false_when_device_share_can_decide() {
        assertFalse(checker.shouldResolveLatestVersion(151, 151, 0.0, 0.5));
        assertFalse(checker.shouldResolveLatestVersion(152, 151, 3.0, 54.2));
    }

    @Test
    public void shouldResolveLatestVersion_true_when_device_share_data_missing() {
        assertTrue(checker.shouldResolveLatestVersion(150, null, 3.0, null));
        assertTrue(checker.shouldResolveLatestVersion(151, 151, 3.0, null));
    }
}
