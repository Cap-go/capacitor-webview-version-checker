package app.capgo.webviewversionchecker;

import androidx.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

final class WebviewVersionChecker {

    static final class CompatibilityEvaluation {

        final String state;
        final boolean isLatest;
        final String reason;

        CompatibilityEvaluation(String state, boolean isLatest, String reason) {
            this.state = state;
            this.isLatest = isLatest;
            this.reason = reason;
        }
    }

    @Nullable
    CompatibilityEvaluation evaluateCompatibility(
        @Nullable String currentVersionName,
        @Nullable Integer currentMajorVersion,
        @Nullable String latestVersion,
        @Nullable Integer minimumMajorVersion,
        @Nullable Double minimumDeviceSharePercent,
        @Nullable Double currentVersionSharePercent
    ) {
        if (minimumMajorVersion != null && currentMajorVersion != null && currentMajorVersion < minimumMajorVersion) {
            return new CompatibilityEvaluation(
                "outdated",
                false,
                "Installed WebView major version is below the configured minimum major version."
            );
        }

        if (minimumDeviceSharePercent != null && currentMajorVersion != null && currentVersionSharePercent != null) {
            boolean isLatest = currentVersionSharePercent >= minimumDeviceSharePercent;
            return new CompatibilityEvaluation(
                isLatest ? "latest" : "outdated",
                isLatest,
                isLatest
                    ? "Installed WebView major version satisfies the configured minimum device-share threshold."
                    : "Installed WebView major version is below the configured minimum device-share threshold."
            );
        }

        if (currentVersionName != null && !currentVersionName.isEmpty() && latestVersion != null && !latestVersion.isEmpty()) {
            int cmp = compareVersions(currentVersionName, latestVersion);
            boolean isLatest = cmp >= 0;
            return new CompatibilityEvaluation(
                isLatest ? "latest" : "outdated",
                isLatest,
                isLatest
                    ? "Installed WebView version is at or above the latest resolved version."
                    : "Installed WebView version is behind the latest resolved version."
            );
        }

        if (minimumMajorVersion != null && currentMajorVersion != null) {
            boolean isLatest = currentMajorVersion >= minimumMajorVersion;
            return new CompatibilityEvaluation(
                isLatest ? "latest" : "outdated",
                isLatest,
                isLatest
                    ? "Installed WebView major version satisfies the minimum configured major version."
                    : "Installed WebView major version is below the configured minimum major version."
            );
        }

        return null;
    }

    @Nullable
    Integer parseMajorVersion(@Nullable String version) {
        if (version == null || version.isEmpty()) {
            return null;
        }

        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < version.length(); i++) {
            char c = version.charAt(i);
            if (Character.isDigit(c)) {
                digits.append(c);
            } else if (digits.length() > 0) {
                break;
            }
        }

        if (digits.length() == 0) {
            return null;
        }

        try {
            return Integer.parseInt(digits.toString());
        } catch (NumberFormatException error) {
            return null;
        }
    }

    int compareVersions(@Nullable String left, @Nullable String right) {
        List<Integer> leftParts = tokenize(left);
        List<Integer> rightParts = tokenize(right);

        int max = Math.max(leftParts.size(), rightParts.size());
        for (int index = 0; index < max; index++) {
            int a = index < leftParts.size() ? leftParts.get(index) : 0;
            int b = index < rightParts.size() ? rightParts.get(index) : 0;
            if (a > b) {
                return 1;
            }
            if (a < b) {
                return -1;
            }
        }

        return 0;
    }

    private List<Integer> tokenize(@Nullable String value) {
        List<Integer> out = new ArrayList<>();
        if (value == null || value.isEmpty()) {
            return out;
        }

        String[] rawParts = value.split("[^0-9]+");
        for (String part : rawParts) {
            if (part == null || part.isEmpty()) {
                continue;
            }
            try {
                out.add(Integer.parseInt(part));
            } catch (NumberFormatException ignored) {
                out.add(0);
            }
        }

        return out;
    }
}
